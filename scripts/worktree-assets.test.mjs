import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import * as assets from './worktree-assets.mjs';
const { defaultListProcesses, defaultRunGit, run } = assets;

// ユーザーの global/system git config を読まず、書き換えもしない
const isolatedRoot = realpathSync(mkdtempSync(join(tmpdir(), 'wt-assets-cfg-')));
writeFileSync(join(isolatedRoot, 'gitconfig'), '');
process.env.GIT_CONFIG_GLOBAL = join(isolatedRoot, 'gitconfig');
process.env.GIT_CONFIG_NOSYSTEM = '1';

const roots = [isolatedRoot];
after(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true, maxRetries: 5 });
});

function git(cwd, ...args) {
  return execFileSync('git', ['-c', 'core.quotepath=off', ...args], { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
}

function write(path, text) {
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, text);
}

function sandbox() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'wt-assets-')));
  roots.push(root);
  return root;
}

const SETTINGS = (paths) => JSON.stringify({ worktree: { sparsePaths: paths } }, null, 2);

/** main リポ + 追加 worktree 1つを作る。既定の設定は ["app","docs/development"]。 */
function makeRepo({ settings = ['app', 'docs/development'], worktreeConfig = true, trackedSymlink = false } = {}) {
  const root = sandbox();
  const main = join(root, 'main');
  mkdirSync(main);
  git(main, 'init', '-q', '-b', 'main');
  git(main, 'config', 'user.email', 't@example.com');
  git(main, 'config', 'user.name', 't');
  if (worktreeConfig) git(main, 'config', 'extensions.worktreeConfig', 'true');
  write(join(main, 'README.md'), 'readme\n');
  write(join(main, '.gitignore'), '*.log\n');
  write(join(main, 'app/a.txt'), 'a\n');
  write(join(main, 'app/sub/s.txt'), 's\n');
  write(join(main, 'assets/big1.bin'), 'x'.repeat(1000));
  write(join(main, 'assets/sub/big2.bin'), 'y'.repeat(2000));
  write(join(main, 'docs/development/x.md'), 'x\n');
  write(join(main, 'docs/other/y.md'), 'y\n');
  write(join(main, 'extra/e.txt'), 'e\n');
  if (settings) write(join(main, '.claude/settings.json'), SETTINGS(settings));
  git(main, 'add', '-A');
  if (trackedSymlink) {
    const blob = execFileSync('git', ['hash-object', '-w', '--stdin'], { cwd: main, input: 'e.txt', encoding: 'utf8' }).trim();
    git(main, 'update-index', '--add', '--cacheinfo', `120000,${blob},extra/link`);
  }
  git(main, 'commit', '-q', '-m', 'init');
  const wt = join(root, 'wt');
  git(main, 'worktree', 'add', '-q', '-b', 'feature/t', wt);
  return { root, main, wt };
}

const NO_PROC = () => [];
const BIG = () => 10 ** 15;

async function exec(cwd, argv, deps = {}) {
  const out = [];
  const err = [];
  const code = await run(argv, {
    cwd,
    log: (m) => out.push(m),
    error: (m) => err.push(m),
    listProcesses: NO_PROC,
    freeBytes: BIG,
    probe: false,
    ...deps,
  });
  return { code, out: out.join('\n'), err: err.join('\n'), all: `${out.join('\n')}\n${err.join('\n')}` };
}

const sparseList = (cwd) => {
  try {
    return git(cwd, 'sparse-checkout', 'list').split('\n').map((s) => s.trim()).filter(Boolean).sort();
  } catch {
    return [];
  }
};
const sharedConfig = (main) => readFileSync(join(main, '.git', 'config'), 'utf8');
const snapshot = (cwd) => ({ status: git(cwd, 'status', '--porcelain'), list: sparseList(cwd) });
const gitPath = (cwd, name) => git(cwd, 'rev-parse', '--path-format=absolute', '--git-path', name).trim();

test('引数なし・未知サブコマンドは使い方を表示して非0', async () => {
  const { wt } = makeRepo();
  for (const argv of [[], ['bogus']]) {
    const r = await exec(wt, argv);
    assert.notEqual(r.code, 0);
    assert.match(r.all, /使い方/);
  }
});

test('git リポジトリ外では非0', async () => {
  const dir = sandbox();
  const r = await exec(dir, ['status']);
  assert.notEqual(r.code, 0);
});

test('status: 全展開 worktree は非 sparse と設定を表示し、読み取りのみ', async () => {
  const { main, wt } = makeRepo();
  const before = snapshot(wt);
  const cfg = sharedConfig(main);
  const r = await exec(wt, ['status']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /sparse: いいえ/);
  assert.match(r.all, /app/);
  assert.deepEqual(snapshot(wt), before);
  assert.equal(sharedConfig(main), cfg);
});

test('shrink 成功: 素材が外れ、共有 config と他 worktree・本陣は不変 (AC-8)', async () => {
  const { main, wt } = makeRepo();
  const cfg = sharedConfig(main);
  const mainBefore = snapshot(main);
  const r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
  assert.equal(existsSync(join(wt, 'extra/e.txt')), false);
  assert.equal(existsSync(join(wt, 'app/a.txt')), true);
  assert.equal(existsSync(join(wt, 'README.md')), true);
  assert.equal(existsSync(join(wt, 'docs/development/x.md')), true);
  assert.equal(existsSync(join(wt, 'docs/other/y.md')), false);
  assert.deepEqual(sparseList(wt), ['app', 'docs/development']);
  assert.equal(git(wt, 'status', '--porcelain'), '');
  assert.equal(sharedConfig(main), cfg);
  assert.deepEqual(snapshot(main), mainBefore);
  assert.equal(existsSync(join(main, 'assets/big1.bin')), true);
});

test('shrink/reapply は本陣(メイン作業木)では変更前に拒否する (AC-8)', async () => {
  const { main } = makeRepo();
  const before = snapshot(main);
  const cfg = sharedConfig(main);
  for (const sub of ['shrink', 'reapply']) {
    const r = await exec(main, [sub]);
    assert.notEqual(r.code, 0);
    assert.match(r.all, /本陣|メイン/);
  }
  assert.deepEqual(snapshot(main), before);
  assert.equal(sharedConfig(main), cfg);
  assert.equal(existsSync(join(main, 'assets/big1.bin')), true);
});

test('status と expand は本陣でも可。全展開なら expand は何もしない (AC-8)', async () => {
  const { main } = makeRepo();
  const before = snapshot(main);
  assert.equal((await exec(main, ['status'])).code, 0);
  const r = await exec(main, ['expand']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /既に全展開/);
  assert.deepEqual(snapshot(main), before);
});

test('設定が未投入なら shrink/reapply は「設定が未投入」で非0', async () => {
  const { wt } = makeRepo({ settings: null });
  for (const sub of ['shrink', 'reapply']) {
    const r = await exec(wt, [sub]);
    assert.notEqual(r.code, 0);
    assert.match(r.all, /設定が未投入/);
  }
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
});

test('shrink 拒否: 除外対象配下の未追跡・未コミット・ステージ済み・ignore済み (AC-6)', async () => {
  const cases = {
    未追跡: (wt) => write(join(wt, 'assets/new.txt'), 'n'),
    未コミット: (wt) => write(join(wt, 'assets/big1.bin'), 'changed'),
    ステージ済み: (wt) => {
      write(join(wt, 'extra/e.txt'), 'changed');
      git(wt, 'add', 'extra/e.txt');
    },
    ignore済み: (wt) => write(join(wt, 'assets/sub/gen.log'), 'log'),
    新規ディレクトリの未追跡: (wt) => write(join(wt, 'brandnew/f.txt'), 'f'),
  };
  for (const [name, setup] of Object.entries(cases)) {
    const { wt } = makeRepo();
    setup(wt);
    const before = snapshot(wt);
    const r = await exec(wt, ['shrink']);
    assert.notEqual(r.code, 0, name);
    assert.match(r.all, /assets|extra|brandnew/, name);
    assert.deepEqual(snapshot(wt), before, name);
    assert.deepEqual(sparseList(wt), [], name);
    assert.equal(existsSync(join(wt, 'assets/big1.bin')), true, name);
  }
});

test('shrink 許可: 除外対象の外の変更・未追跡は保たれる (AC-6)', async () => {
  const { wt } = makeRepo();
  write(join(wt, 'app/a.txt'), 'edited');
  write(join(wt, 'app/untracked.txt'), 'u');
  const r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.equal(readFileSync(join(wt, 'app/a.txt'), 'utf8'), 'edited');
  assert.equal(existsSync(join(wt, 'app/untracked.txt')), true);
});

test('shrink 拒否: 未マージ・進行中の操作・index.lock (AC-6)', async () => {
  {
    const { wt } = makeRepo();
    const blob = (t) => execFileSync('git', ['hash-object', '-w', '--stdin'], { cwd: wt, input: t, encoding: 'utf8' }).trim();
    const lines = ['1', '2', '3'].map((s) => `100644 ${blob(`v${s}\n`)} ${s}\tapp/a.txt\n`).join('');
    execFileSync('git', ['update-index', '--index-info'], { cwd: wt, input: `0 ${'0'.repeat(40)}\tapp/a.txt\n${lines}`, stdio: ['pipe', 'pipe', 'pipe'] });
    const r = await exec(wt, ['shrink']);
    assert.notEqual(r.code, 0);
    assert.match(r.all, /未マージ/);
    assert.deepEqual(sparseList(wt), []);
  }
  for (const marker of ['MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD']) {
    const { wt } = makeRepo();
    writeFileSync(gitPath(wt, marker), `${git(wt, 'rev-parse', 'HEAD').trim()}\n`);
    const r = await exec(wt, ['shrink']);
    assert.notEqual(r.code, 0, marker);
    assert.match(r.all, new RegExp(marker), marker);
    assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
  }
  {
    const { wt } = makeRepo();
    mkdirSync(gitPath(wt, 'rebase-merge'), { recursive: true });
    const r = await exec(wt, ['shrink']);
    assert.notEqual(r.code, 0);
    assert.match(r.all, /rebase/);
  }
  {
    const { wt } = makeRepo();
    writeFileSync(gitPath(wt, 'index.lock'), '');
    const r = await exec(wt, ['shrink']);
    assert.notEqual(r.code, 0);
    assert.match(r.all, /index\.lock/);
    assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
  }
});

test('shrink 拒否: 対象 worktree のプロセス動作中 / 取得失敗 / --skip-process-check (AC-6)', async () => {
  const { wt } = makeRepo();
  const running = () => [{ pid: 99999, ppid: 1, cmd: `java -jar ${wt}/backend/build/app.jar` }];
  let r = await exec(wt, ['shrink'], { listProcesses: running });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /java/);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);

  const failing = () => {
    throw new Error('取得できない');
  };
  r = await exec(wt, ['shrink'], { listProcesses: failing });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /確認できない/);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);

  r = await exec(wt, ['shrink', '--skip-process-check'], { listProcesses: failing });
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);

  const other = makeRepo();
  const unrelated = () => [{ pid: 2, ppid: 1, cmd: 'node C:/other/place/server.js' }];
  r = await exec(other.wt, ['shrink'], { listProcesses: unrelated });
  assert.equal(r.code, 0, r.all);
});

test('途中失敗は非0で到達状態と復旧手順を表示し、再実行で完了できる (AC-7)', async () => {
  const { wt } = makeRepo();
  write(join(wt, 'app/work.txt'), 'wip');
  write(join(wt, 'app/a.txt'), 'edited');
  const failing = (cwd, args) => {
    if (args.includes('sparse-checkout') && args.includes('set')) return { status: 1, stdout: '', stderr: 'error: file in use' };
    return defaultRunGit(cwd, args);
  };
  let r = await exec(wt, ['shrink'], { runGit: failing });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /file in use/);
  assert.match(r.all, /到達状態/);
  assert.match(r.all, /復旧/);
  assert.match(r.all, /worktree-assets\.mjs shrink/);
  assert.equal(readFileSync(join(wt, 'app/a.txt'), 'utf8'), 'edited');
  assert.equal(existsSync(join(wt, 'app/work.txt')), true);

  r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
  assert.equal(readFileSync(join(wt, 'app/a.txt'), 'utf8'), 'edited');
  assert.equal(existsSync(join(wt, 'app/work.txt')), true);
});

test('設定と同じ一覧で既に sparse なら shrink は何もしない', async () => {
  const { wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  const before = snapshot(wt);
  const r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /既に/);
  assert.deepEqual(snapshot(wt), before);
});

test('無関係な sparse 一覧は --force-replace なしでは上書きせず拒否 (AC-8)', async () => {
  const { main, wt } = makeRepo();
  git(wt, 'sparse-checkout', 'set', '--cone', 'extra');
  const before = snapshot(wt);
  for (const sub of ['shrink', 'reapply']) {
    const r = await exec(wt, [sub]);
    assert.notEqual(r.code, 0, sub);
    assert.match(r.all, /force-replace/, sub);
    assert.deepEqual(snapshot(wt), before, sub);
  }
  const cfg = sharedConfig(main);
  const r = await exec(wt, ['shrink', '--force-replace']);
  assert.equal(r.code, 0, r.all);
  assert.deepEqual(sparseList(wt), ['app', 'docs/development']);
  assert.equal(sharedConfig(main), cfg);
});

test('extensions.worktreeConfig が無いと共有 config を変えるため拒否する (AC-8)', async () => {
  const { main, wt } = makeRepo({ worktreeConfig: false });
  const cfg = sharedConfig(main);
  const r = await exec(wt, ['shrink']);
  assert.notEqual(r.code, 0);
  assert.match(r.all, /worktreeConfig/);
  assert.equal(sharedConfig(main), cfg);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
});

test('expand: 全展開すると status は空のまま除外ファイルが全て実在する (AC-5)', async () => {
  const { main, wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  const cfg = sharedConfig(main);
  const r = await exec(wt, ['expand']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /必要な容量/);
  assert.equal(git(wt, 'status', '--porcelain'), '');
  for (const f of git(wt, 'ls-files').split('\n').filter(Boolean)) assert.equal(existsSync(join(wt, f)), true, f);
  assert.deepEqual(sparseList(wt), []);
  assert.equal(sharedConfig(main), cfg);
});

test('expand: 空き容量不足なら何も変えず非0 (AC-5)', async () => {
  const { wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  const before = snapshot(wt);
  const r = await exec(wt, ['expand'], { freeBytes: () => 10 });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /空き容量/);
  assert.deepEqual(snapshot(wt), before);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
});

test('status: sparse の一覧が設定と一致・不一致を示す', async () => {
  const { wt } = makeRepo();
  await exec(wt, ['shrink']);
  let r = await exec(wt, ['status']);
  assert.match(r.all, /sparse: はい/);
  assert.match(r.all, /一覧は設定と一致/);
  assert.match(r.all, /assets/);
  git(wt, 'sparse-checkout', 'set', '--cone', 'app');
  r = await exec(wt, ['status']);
  assert.match(r.all, /一覧が設定と異なる/);
});

test('reapply: 新ディレクトリが設定に加わった commit を取り込むと実在し、作業中の変更が保たれる (AC-20)', async () => {
  const { main, wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  write(join(wt, 'app/a.txt'), 'edited');
  write(join(wt, 'app/untracked.txt'), 'u');

  // main の履歴に、設定へ extra を加える commit を作り、worktree へ取り込む
  git(main, 'checkout', '-q', '-b', 'upd');
  write(join(main, '.claude/settings.json'), SETTINGS(['app', 'docs/development', 'extra']));
  git(main, 'commit', '-q', '-am', 'settings');
  git(main, 'checkout', '-q', 'main');
  git(wt, 'merge', '-q', '--ff-only', 'upd');
  assert.equal(existsSync(join(wt, 'extra/e.txt')), false);

  const cfg = sharedConfig(main);
  const r = await exec(wt, ['reapply']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'extra/e.txt')), true);
  assert.equal(readFileSync(join(wt, 'app/a.txt'), 'utf8'), 'edited');
  assert.equal(existsSync(join(wt, 'app/untracked.txt')), true);
  assert.deepEqual(sparseList(wt), ['app', 'docs/development', 'extra']);
  assert.equal(sharedConfig(main), cfg);
});

test('reapply: sparse でない worktree では拒否する', async () => {
  const { wt } = makeRepo();
  const r = await exec(wt, ['reapply']);
  assert.notEqual(r.code, 0);
  assert.match(r.all, /sparse ではない/);
});

// ---------------------------------------------------------------------------
// Codex 検分 8件の再現テスト
// ---------------------------------------------------------------------------

let branchSeq = 0;
/** main 側で設定を変える commit を作り、wt へ ff 取り込みする。 */
function pullSettings(main, wt, paths) {
  const b = `upd${(branchSeq += 1)}`;
  git(main, 'checkout', '-q', '-b', b);
  write(join(main, '.claude/settings.json'), SETTINGS(paths));
  git(main, 'commit', '-q', '-am', `settings ${b}`);
  git(main, 'checkout', '-q', 'main');
  git(wt, 'merge', '-q', '--ff-only', b);
}

/** 何も変えず非0であること（sparse 一覧・status・共有 config 不変）を確かめる。 */
const refused = async (wt, main, argv, deps) => {
  const before = snapshot(wt);
  const cfg = sharedConfig(main);
  const r = await exec(wt, argv, deps);
  assert.notEqual(r.code, 0, `${argv.join(' ')}: ${r.all}`);
  assert.deepEqual(snapshot(wt), before);
  assert.equal(sharedConfig(main), cfg);
  return r;
};

test('[1] expand は worktreeConfig 無効＋共有 core.sparsecheckout 漏れでも共有 config を変えず拒否。全サブコマンドで config 不変', async () => {
  const { main, wt } = makeRepo();
  git(wt, 'sparse-checkout', 'set', '--cone'); // 空一覧（ルート直下のみ）の cone sparse
  git(main, 'config', '--unset', 'extensions.worktreeConfig');
  git(main, 'config', 'core.sparseCheckout', 'true'); // 漏れ
  const cfg = sharedConfig(main);
  const listBefore = git(wt, 'ls-files', '-t');
  for (const sub of ['expand', 'shrink', 'reapply']) {
    const r = await exec(wt, [sub, '--force-replace']);
    assert.notEqual(r.code, 0, sub);
    assert.match(r.all, /worktreeConfig/, sub);
    assert.equal(sharedConfig(main), cfg, sub);
  }
  assert.equal((await exec(wt, ['status'])).code, 0);
  assert.equal(sharedConfig(main), cfg);
  assert.equal(git(wt, 'ls-files', '-t'), listBefore);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
});

test('[1] 正常系の全サブコマンドで共有 config のバイト列が同一', async () => {
  const { main, wt } = makeRepo();
  const cfg = sharedConfig(main);
  for (const argv of [['status'], ['shrink'], ['reapply'], ['expand'], ['shrink'], ['expand']]) {
    const r = await exec(wt, argv);
    assert.equal(r.code, 0, `${argv}: ${r.all}`);
    assert.equal(sharedConfig(main), cfg, argv.join(' '));
  }
});

test('[2] shrink 後に除外ファイルが残っていれば非0で残ったパスと復旧手順。一覧一致でも再適用して検証し直す', async () => {
  const { wt } = makeRepo();
  const keep = (cwd, args, input) => {
    const r = defaultRunGit(cwd, args, input);
    if (args.includes('set') && r.status === 0) write(join(wt, 'assets/big1.bin'), 'x'.repeat(1000)); // 他プロセスが保持したファイルを模す
    return r;
  };
  let r = await exec(wt, ['shrink'], { runGit: keep });
  assert.notEqual(r.code, 0, r.all);
  assert.match(r.all, /assets\/big1\.bin/);
  assert.match(r.all, /復旧/);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
  // 一覧は既に一致しているが、再実行で再適用・検証し直す
  let reapplied = false;
  const spy = (cwd, args, input) => {
    if (args[0] === 'sparse-checkout' && args[1] === 'reapply') reapplied = true;
    return defaultRunGit(cwd, args, input);
  };
  r = await exec(wt, ['shrink'], { runGit: spy });
  assert.equal(reapplied, true, r.all);
  if (r.code === 0) assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
  else assert.match(r.all, /assets\/big1\.bin/);
});

test('[3] 展開されるパスの位置に未追跡ファイルがあれば reapply/expand は何も変えず非0', async () => {
  const { main, wt } = makeRepo({ settings: ['app'] });
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  pullSettings(main, wt, ['app', 'assets']);
  write(join(wt, 'assets'), 'ただのファイル'); // assets/big1.bin の祖先位置
  for (const argv of [['reapply'], ['expand']]) {
    const r = await refused(wt, main, argv);
    assert.match(r.all, /assets/);
    assert.equal(readFileSync(join(wt, 'assets'), 'utf8'), 'ただのファイル');
  }
});

test('[3] 展開先に別内容のローカルファイルがあっても上書きせず保つ（git が変更として扱う）', async () => {
  const { wt } = makeRepo({ settings: ['app'] });
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  write(join(wt, 'assets/sub/big2.bin'), 'ローカルの別内容');
  await exec(wt, ['expand']);
  assert.equal(readFileSync(join(wt, 'assets/sub/big2.bin'), 'utf8'), 'ローカルの別内容');
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
});

test('[4] ルート直下だけの cone sparse（list が空）も sparse と判定し、既存一覧の保護を迂回しない', async () => {
  const { main, wt } = makeRepo();
  git(wt, 'sparse-checkout', 'set', '--cone');
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
  let r = await exec(wt, ['status']);
  assert.match(r.all, /sparse: はい/);
  for (const sub of ['shrink', 'reapply']) {
    r = await refused(wt, main, [sub]);
    assert.match(r.all, /force-replace/, sub);
    assert.doesNotMatch(r.all, /sparse ではない/, sub);
  }
  r = await exec(wt, ['expand']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
});

test('[4] 共有 core.sparsecheckout が漏れていて worktree 側に設定もファイルも無ければ「sparse ではない」', async () => {
  const { main, wt } = makeRepo();
  git(main, 'config', '--worktree', 'core.sparseCheckout', 'false');
  git(main, 'config', 'core.sparseCheckout', 'true'); // 漏れ
  const r = await exec(wt, ['status']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /sparse: いいえ/);
  const e = await exec(wt, ['expand']);
  assert.equal(e.code, 0, e.all);
  assert.match(e.all, /既に全展開/);
  const re = await exec(wt, ['reapply']);
  assert.notEqual(re.code, 0);
  assert.match(re.all, /sparse ではない/);
});

test('[5] worktree.sparsePaths が不正なら変更前に非0（正規化で黙って直さない）', async () => {
  const { main, wt } = makeRepo();
  const bad = [['--no-cone'], ['/app'], ['app/'], [' app'], ['app', 'app'], ['../x'], ['app/./x'], ['a\\b'], ['C:/x'], ['-app'], [''], [1], ['nonexistent'], ['README.md']];
  for (const paths of bad) {
    write(join(wt, '.claude/settings.json'), SETTINGS(paths));
    git(wt, 'commit', '-q', '-am', 'bad');
    const r = await refused(wt, main, ['shrink']);
    assert.match(r.all, /sparsePaths/, JSON.stringify(paths));
    assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
    assert.equal(readFileSync(join(wt, 'app/a.txt'), 'utf8'), 'a\n');
  }
  assert.deepEqual(sparseList(wt), []);
});

test('[6] cwd が worktree 内のプロセス（相対パス起動）も拒否する', async () => {
  const { wt } = makeRepo();
  const list = () => [{ pid: 99999, ppid: 1, cmd: 'node server.js', cwd: join(wt, 'frontend') }];
  const r = await exec(wt, ['shrink'], { listProcesses: list });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /server\.js/);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
});

test('[6] Linux: 実プロセスの /proc/<pid>/cwd が worktree 内なら拒否', { skip: process.platform !== 'linux' }, async () => {
  const { spawn } = await import('node:child_process');
  const { wt } = makeRepo();
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { cwd: wt, stdio: 'ignore' });
  try {
    await new Promise((r) => setTimeout(r, 500));
    const r = await exec(wt, ['shrink'], { listProcesses: defaultListProcesses });
    assert.notEqual(r.code, 0, r.all);
    assert.match(r.all, new RegExp(`pid ${child.pid}`));
  } finally {
    child.kill();
  }
});

test('[6] 使用中プローブ: 外れるディレクトリの rename が失敗すれば何も変えず非0。--skip-process-check でも省かない', async () => {
  const { main, wt } = makeRepo();
  const { renameSync } = await import('node:fs');
  const busy = (from, to) => {
    if (from.replaceAll('\\', '/').endsWith('/assets')) throw Object.assign(new Error('EBUSY'), { code: 'EBUSY' });
    renameSync(from, to);
  };
  for (const extra of [[], ['--skip-process-check']]) {
    const r = await refused(wt, main, ['shrink', ...extra], { probe: true, rename: busy });
    assert.match(r.all, /使用中/);
    assert.match(r.all, /assets/);
    assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
    assert.equal(existsSync(join(wt, 'extra/e.txt')), true);
  }
});

test('[6] 使用中プローブが全て通れば一時名は残らず shrink が成功する', async () => {
  const { wt } = makeRepo();
  const r = await exec(wt, ['shrink'], { probe: true });
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
  assert.equal(git(wt, 'status', '--porcelain'), '');
});

test('[6] プローブ後に元へ戻せない場合は再試行し、失敗なら手動復旧手順を表示して非0', async () => {
  const { wt } = makeRepo();
  const { renameSync } = await import('node:fs');
  let backTries = 0;
  const stuck = (from, to) => {
    if (from.includes('.wa-probe-')) {
      backTries += 1;
      throw Object.assign(new Error('EPERM'), { code: 'EPERM' });
    }
    renameSync(from, to);
  };
  const r = await exec(wt, ['shrink'], { probe: true, rename: stuck });
  assert.notEqual(r.code, 0);
  assert.ok(backTries >= 3, `再試行回数 ${backTries}`);
  assert.match(r.all, /手動復旧/);
  assert.match(r.all, /\.wa-probe-/);
  assert.deepEqual(sparseList(wt), []);
});

test('[6] プローブが一度だけ戻せなくても再試行で復旧して続行する', async () => {
  const { wt } = makeRepo();
  const { renameSync } = await import('node:fs');
  let failedOnce = false;
  const flaky = (from, to) => {
    if (from.includes('.wa-probe-') && !failedOnce) {
      failedOnce = true;
      throw Object.assign(new Error('EPERM'), { code: 'EPERM' });
    }
    renameSync(from, to);
  };
  const r = await exec(wt, ['shrink'], { probe: true, rename: flaky });
  assert.equal(r.code, 0, r.all);
  assert.equal(failedOnce, true);
});

test('[7] 空き容量は blob 合計×1.5＋ファイル数×4096＋固定余裕で見積もり、取得不能は拒否（--skip-space-check で進む）', async () => {
  const { wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  assert.ok(assets.SPACE_MARGIN_BYTES >= 100 * 1024 * 1024);
  const before = snapshot(wt);
  let r = await exec(wt, ['expand'], { freeBytes: () => assets.SPACE_MARGIN_BYTES + 3000 });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /空き容量/);
  assert.deepEqual(snapshot(wt), before);
  const unknown = () => {
    throw new Error('取得不能');
  };
  r = await exec(wt, ['expand'], { freeBytes: unknown });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /取得/);
  assert.deepEqual(snapshot(wt), before);
  r = await exec(wt, ['expand', '--skip-space-check'], { freeBytes: unknown });
  assert.equal(r.code, 0, r.all);
});

test('[8] 管理用 worktree の一覧（設定の部分集合）は --force-replace なしで reapply/shrink が置き換えない', async () => {
  const { main, wt } = makeRepo();
  git(wt, 'sparse-checkout', 'set', '--cone', 'docs/development');
  for (const sub of ['reapply', 'shrink']) {
    const r = await refused(wt, main, [sub]);
    assert.match(r.all, /force-replace/, sub);
  }
});

test('[8] 本コマンドが適用した一覧は印(worktree ローカル)で認められ、履歴に無い設定へも reapply できる', async () => {
  const { main, wt } = makeRepo();
  write(join(wt, '.claude/settings.json'), SETTINGS(['app', 'extra']));
  git(wt, 'commit', '-q', '-am', 'tmp settings');
  const cfg = sharedConfig(main);
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  assert.match(git(wt, 'config', '--worktree', '--get', 'mannschaft.sparsePathsDigest').trim(), /^[0-9a-f]{40}$/);
  assert.equal(sharedConfig(main), cfg);
  git(wt, 'reset', '-q', '--hard', 'HEAD~1'); // その設定は到達可能な履歴から消える
  const r = await exec(wt, ['reapply']);
  assert.equal(r.code, 0, r.all);
  assert.deepEqual(sparseList(wt), ['app', 'docs/development']);
});

test('[8] settings.json の履歴上の版と完全一致する一覧は設定由来として認める', async () => {
  const { wt } = makeRepo();
  write(join(wt, '.claude/settings.json'), SETTINGS(['app']));
  git(wt, 'commit', '-q', '-am', 'narrow');
  git(wt, 'sparse-checkout', 'set', '--cone', 'app', 'docs/development');
  const r = await exec(wt, ['reapply']);
  assert.equal(r.code, 0, r.all);
  assert.deepEqual(sparseList(wt), ['app']);
});

// ---------------------------------------------------------------------------
// Codex 再検分（2回目）4件 + 再点検の再現テスト
// ---------------------------------------------------------------------------

test('[R1] 除外ディレクトリが別の作業木を指す junction/symlink なら何も変えず非0。参照先は無傷', async () => {
  const { main, wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  symlinkSync(join(main, 'assets'), join(wt, 'assets'), 'junction');
  const mainBefore = snapshot(main);
  for (const argv of [['shrink'], ['reapply']]) {
    const r = await refused(wt, main, argv);
    assert.match(r.all, /junction|symlink|シンボリック/, argv[0]);
    assert.match(r.all, /assets/, argv[0]);
  }
  // expand は junction 越しのファイルを書き換えない（参照先は無傷のまま）
  await exec(wt, ['expand']);
  assert.equal(readFileSync(join(main, 'assets/big1.bin'), 'utf8'), 'x'.repeat(1000));
  assert.deepEqual(snapshot(main), mainBefore);
});

test('[R1] 外れる領域の深い階層にある junction/symlink も拒否する', async () => {
  const { main, wt } = makeRepo();
  mkdirSync(join(wt, 'extra/deep'), { recursive: true });
  symlinkSync(join(main, 'assets'), join(wt, 'extra/deep/ln.log'), 'junction'); // ignore 済みの名前
  const r = await refused(wt, main, ['shrink']);
  assert.match(r.all, /junction|symlink|シンボリック/);
  assert.match(r.all, /extra\/deep\/ln\.log/);
  assert.equal(existsSync(join(main, 'assets/big1.bin')), true);
});

test('[R1] 作業木のルート自身が symlink/junction なら拒否する', async () => {
  const { main, wt } = makeRepo();
  const link = join(main, '..', 'wt-link');
  symlinkSync(wt, link, 'junction');
  // git が実パスへ解決するため、ルート検査は ctx.cwd 側の別名も見る
  const before = snapshot(wt);
  const r = await exec(link, ['shrink']);
  assert.notEqual(r.code, 0, r.all);
  assert.match(r.all, /junction|symlink|シンボリック/);
  assert.deepEqual(snapshot(wt), before);
});

test('[R1] git が追跡している symlink エントリ(120000)は拒否対象から除く', async () => {
  const { wt } = makeRepo({ trackedSymlink: true });
  assert.match(git(wt, 'ls-files', '-s', 'extra/link'), /^120000 /);
  const r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'extra/e.txt')), false);
});

test('[R2] 一覧一致の再適用経路も安全検査を通る: 外れる領域の ignore 済みファイルを消さない', async () => {
  const { main, wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  write(join(wt, 'assets/big1.bin'), 'x'.repeat(1000)); // HEAD と同じ内容で再作成
  write(join(wt, 'assets/gen.log'), '生成物'); // ignore 済み
  const r = await refused(wt, main, ['shrink']);
  assert.match(r.all, /assets\/gen\.log/);
  assert.equal(readFileSync(join(wt, 'assets/gen.log'), 'utf8'), '生成物');
  const r2 = await refused(wt, main, ['reapply']);
  assert.match(r2.all, /assets\/gen\.log/);
  assert.equal(readFileSync(join(wt, 'assets/gen.log'), 'utf8'), '生成物');
});

test('[R2] 一覧一致の再適用経路でも使用中プロセスと rename プローブを通る', async () => {
  const { wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  write(join(wt, 'assets/big1.bin'), 'x'.repeat(1000)); // 未完了状態を作る
  const running = () => [{ pid: 99999, ppid: 1, cmd: 'node server.js', cwd: wt }];
  const r = await exec(wt, ['shrink'], { listProcesses: running });
  assert.notEqual(r.code, 0);
  assert.match(r.all, /server\.js/);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
  const { renameSync } = await import('node:fs');
  const busy = (from, to) => {
    if (from.replaceAll('\\', '/').endsWith('/assets')) throw Object.assign(new Error('EBUSY'), { code: 'EBUSY' });
    renameSync(from, to);
  };
  const r2 = await exec(wt, ['shrink'], { probe: true, rename: busy });
  assert.notEqual(r2.code, 0);
  assert.match(r2.all, /使用中/);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), true);
});

test('[R3] 障害物を退避して再実行しても、欠落した展開対象を既存状態として扱わず非0。復元後は0', async () => {
  const { main, wt } = makeRepo({ settings: ['app'] });
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  pullSettings(main, wt, ['app', 'extra']);
  // 事前検査の後に別プロセスが extra を通常ファイルとして作る
  const race = (cwd, args, input) => {
    if (args[0] === 'sparse-checkout' && args.includes('set')) writeFileSync(join(wt, 'extra'), '障害物');
    return defaultRunGit(cwd, args, input);
  };
  let r = await exec(wt, ['reapply'], { runGit: race });
  assert.notEqual(r.code, 0, r.all);
  rmSync(join(wt, 'extra'), { force: true });
  r = await exec(wt, ['reapply']);
  assert.notEqual(r.code, 0, `再実行で未完了を見逃した: ${r.all}`);
  assert.match(r.all, /extra\/e\.txt/);
  assert.match(r.all, /git restore|git checkout/);
  assert.equal(existsSync(join(wt, 'extra/e.txt')), false);
  git(wt, 'checkout', '--', 'extra/e.txt'); // 案内どおりに復元
  r = await exec(wt, ['reapply']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'extra/e.txt')), true);
});

test('[R4] sparsePaths の親子重複は拒否（該当の組を表示）。一覧比較は順序・重複を無視した集合比較', async () => {
  const { main, wt } = makeRepo();
  write(join(wt, '.claude/settings.json'), SETTINGS(['app', 'app/sub']));
  git(wt, 'commit', '-q', '-am', 'nested');
  const r = await refused(wt, main, ['shrink']);
  assert.match(r.all, /app/);
  assert.match(r.all, /app\/sub/);
  assert.match(r.all, /祖先|子孫/);

  // 順序違いは同一一覧: 設定 ['docs/development','app'] と現在の ['app','docs/development'] は一致
  write(join(wt, '.claude/settings.json'), SETTINGS(['docs/development', 'app']));
  git(wt, 'commit', '-q', '-am', 'order');
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  const again = await exec(wt, ['shrink']);
  assert.equal(again.code, 0, again.all);
  assert.match(again.all, /既に/);
});

test('[R4] 履歴上の版が順序違い・重複を含んでも、同じ集合なら設定由来として認める', async () => {
  const { wt } = makeRepo();
  write(join(wt, '.claude/settings.json'), SETTINGS(['docs/development', 'app', 'app']));
  git(wt, 'commit', '-q', '-am', 'unsorted dup');
  write(join(wt, '.claude/settings.json'), SETTINGS(['app']));
  git(wt, 'commit', '-q', '-am', 'narrow');
  git(wt, 'sparse-checkout', 'set', '--cone', 'app', 'docs/development');
  const r = await exec(wt, ['reapply']);
  assert.equal(r.code, 0, r.all);
  assert.deepEqual(sparseList(wt), ['app']);
});

test('[再点検] 拒否しても rename プローブの一時名は残らない', async () => {
  const { wt } = makeRepo();
  const { renameSync } = await import('node:fs');
  const busy = (from, to) => {
    if (from.replaceAll('\\', '/').endsWith('/extra')) throw Object.assign(new Error('EBUSY'), { code: 'EBUSY' });
    renameSync(from, to);
  };
  const r = await exec(wt, ['shrink'], { probe: true, rename: busy });
  assert.notEqual(r.code, 0);
  const leftovers = [wt, join(wt, 'docs')].flatMap((d) => readdirSync(d)).filter((n) => n.includes('.wa-probe-'));
  assert.deepEqual(leftovers, []);
});

test('[再点検] 強制終了で残った一時名は次回実行で検出して報告し、勝手に消さない', async () => {
  const { main, wt } = makeRepo();
  write(join(wt, 'assets.wa-probe-4242/big1.bin'), 'x'.repeat(1000));
  const r = await refused(wt, main, ['shrink']);
  assert.match(r.all, /assets\.wa-probe-4242/);
  assert.match(r.all, /元の名前/);
  assert.equal(existsSync(join(wt, 'assets.wa-probe-4242/big1.bin')), true);
  const s = await exec(wt, ['status']);
  assert.match(s.all, /assets\.wa-probe-4242/);
});

test('[再点検] --help に「実行中は対象 worktree で他の作業をしない」旨がある', async () => {
  const { wt } = makeRepo();
  const r = await exec(wt, ['--help']);
  assert.equal(r.code, 0);
  assert.match(r.all, /他の作業をしない/);
});

// ---------------------------------------------------------------------------
// Codex 3回目の再検分 2件の再現テスト
// ---------------------------------------------------------------------------

test('[E1] expand: 障害物で欠落 → 障害物を除いて再実行しても欠落を見逃さず非0。復元後は0', async () => {
  const { wt } = makeRepo();
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  // 事前検査の後、disable の直前に別プロセスが extra を通常ファイルとして作る
  const race = (cwd, args, input) => {
    if (args[0] === 'sparse-checkout' && args[1] === 'disable') writeFileSync(join(wt, 'extra'), '障害物');
    return defaultRunGit(cwd, args, input);
  };
  let r = await exec(wt, ['expand'], { runGit: race });
  assert.notEqual(r.code, 0, r.all);
  rmSync(join(wt, 'extra'), { force: true });
  r = await exec(wt, ['expand']);
  assert.notEqual(r.code, 0, `再実行で欠落を見逃した: ${r.all}`);
  assert.doesNotMatch(r.all, /既に全展開のため何もしない/);
  assert.match(r.all, /extra\/e\.txt/);
  assert.match(r.all, /git restore/);
  git(wt, 'checkout', '--', 'extra/e.txt'); // 案内どおりに復元
  r = await exec(wt, ['expand']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /既に全展開/);
  assert.equal(git(wt, 'status', '--porcelain'), '');
});

test('[E2] 操作前からある included 側の削除は障害としない。shrink は完了し再実行も0', async () => {
  const { wt } = makeRepo({ settings: ['app'] });
  rmSync(join(wt, 'app/a.txt')); // 利用者の未ステージ削除
  let r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.equal(existsSync(join(wt, 'assets/big1.bin')), false);
  assert.equal(existsSync(join(wt, 'app/a.txt')), false); // 削除は保たれる
  r = await exec(wt, ['shrink']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /既に/);
  r = await exec(wt, ['reapply']);
  assert.equal(r.code, 0, r.all);
});

test('[E3] expand（全展開済み）: 除外ディレクトリ配下の欠落は非0、それ以外の欠落は注意表示で0', async () => {
  const { wt } = makeRepo();
  rmSync(join(wt, 'extra/e.txt')); // 設定では除外される領域
  let r = await exec(wt, ['expand']);
  assert.notEqual(r.code, 0, r.all);
  assert.match(r.all, /extra\/e\.txt/);
  assert.match(r.all, /git restore/);
  git(wt, 'checkout', '--', 'extra/e.txt');
  rmSync(join(wt, 'app/a.txt')); // 設定では含める領域＝利用者の削除と区別できない
  r = await exec(wt, ['expand']);
  assert.equal(r.code, 0, r.all);
  assert.match(r.all, /注意/);
  assert.match(r.all, /app\/a\.txt/);
});

// ---------------------------------------------------------------------------
// Codex 4回目の再検分: 記録ファイルの失敗時の扱い
// ---------------------------------------------------------------------------

const pendingPath = (wt) => git(wt, 'rev-parse', '--path-format=absolute', '--git-path', 'worktree-assets-pending.json').trim();

/** 設定を ['app'] → ['app','extra'] へ変えた sparse worktree。reapply の git 直前に extra を通常ファイルで塞ぐ。 */
async function raceSetup() {
  const { main, wt } = makeRepo({ settings: ['app'] });
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  pullSettings(main, wt, ['app', 'extra']);
  const race = (cwd, args, input) => {
    if (args[0] === 'sparse-checkout' && args.includes('set')) writeFileSync(join(wt, 'extra'), '障害物');
    return defaultRunGit(cwd, args, input);
  };
  return { main, wt, race };
}

test('[P1] 記録を保存できなければ展開前に何も変えず非0（記録パスがディレクトリ）', async () => {
  const { main, wt, race } = await raceSetup();
  mkdirSync(pendingPath(wt), { recursive: true }); // 書き込み不能にする
  const r = await refused(wt, main, ['reapply'], { runGit: race });
  assert.match(r.all, /記録/);
  assert.equal(existsSync(join(wt, 'extra')), false, 'git の書き換えまで進んだ');
  rmSync(pendingPath(wt), { recursive: true, force: true });
  const ok = await exec(wt, ['reapply']);
  assert.equal(ok.code, 0, ok.all);
  assert.equal(existsSync(join(wt, 'extra/e.txt')), true);
});

test('[P1] 記録の書き込みが失敗したら（ENOSPC 等）展開前に何も変えず非0', async () => {
  const { main, wt, race } = await raceSetup();
  const full = () => {
    throw Object.assign(new Error('no space'), { code: 'ENOSPC' });
  };
  const r = await refused(wt, main, ['reapply'], { runGit: race, writeFile: full });
  assert.match(r.all, /記録/);
  assert.match(r.all, /ENOSPC|no space/);
  assert.equal(existsSync(join(wt, 'extra')), false, 'git の書き換えまで進んだ');
});

test('[P2] 壊れた記録は「記録なし」として扱わず、何も変えず非0。記録は消さない。status でも報告', async () => {
  const { main, wt } = makeRepo({ settings: ['app'] });
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  const p = pendingPath(wt);
  const variants = {
    途中で切れたJSON: '["extra/e.tx',
    配列でない: '{"a":1}',
    要素が文字列でない: '[1,2]',
  };
  for (const [name, body] of Object.entries(variants)) {
    writeFileSync(p, body);
    for (const sub of ['shrink', 'reapply', 'expand']) {
      const r = await refused(wt, main, [sub]);
      assert.match(r.all, /worktree-assets-pending\.json/, `${name}/${sub}`);
      assert.match(r.all, /git restore/, `${name}/${sub}`);
      assert.equal(readFileSync(p, 'utf8'), body, `${name}/${sub}: 記録が消えた・変わった`);
    }
    const s = await exec(wt, ['status']);
    assert.equal(s.code, 0, s.all);
    assert.match(s.all, /要処置/, name);
    assert.match(s.all, /worktree-assets-pending\.json/, name);
  }
  // 読み取りエラー（ディレクトリ）
  rmSync(p, { force: true });
  mkdirSync(p);
  const r = await refused(wt, main, ['shrink']);
  assert.match(r.all, /worktree-assets-pending\.json/);
  rmSync(p, { recursive: true, force: true });
  assert.equal((await exec(wt, ['shrink'])).code, 0); // 手で除いた後は進める
});

test('[P3] 記録は一時ファイルへ書いてから rename で置換する（途中切れを残さない）', async () => {
  const { main, wt } = makeRepo({ settings: ['app'] });
  assert.equal((await exec(wt, ['shrink'])).code, 0);
  pullSettings(main, wt, ['app', 'extra']);
  const written = [];
  const { writeFileSync: real } = await import('node:fs');
  const spy = (path, data) => {
    written.push(path);
    real(path, data);
  };
  const r = await exec(wt, ['reapply'], { writeFile: spy });
  assert.equal(r.code, 0, r.all);
  assert.ok(written.length > 0, '記録を書いていない');
  for (const w of written) {
    assert.notEqual(w, pendingPath(wt), '記録へ直接書いた');
    assert.match(w.replaceAll('\\', '/'), /worktree-assets-pending\.json\.tmp/);
  }
  assert.equal(existsSync(pendingPath(wt)), false);
  assert.deepEqual(readdirSync(join(pendingPath(wt), '..')).filter((n) => n.includes('.tmp')), []);
});
