import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import { defaultRunGit, run } from './worktree-assets.mjs';

const roots = [];
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
function makeRepo({ settings = ['app', 'docs/development'], worktreeConfig = true } = {}) {
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
  write(join(main, 'assets/big1.bin'), 'x'.repeat(1000));
  write(join(main, 'assets/sub/big2.bin'), 'y'.repeat(2000));
  write(join(main, 'docs/development/x.md'), 'x\n');
  write(join(main, 'docs/other/y.md'), 'y\n');
  write(join(main, 'extra/e.txt'), 'e\n');
  if (settings) write(join(main, '.claude/settings.json'), SETTINGS(settings));
  git(main, 'add', '-A');
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
