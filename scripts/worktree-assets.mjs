#!/usr/bin/env node
/**
 * 足軽 worktree の素材（牧場 PNG など）を展開・縮小する。
 * git sparse-checkout（cone モード）を、現在の worktree だけに対して操作する。
 * 縮小時の一覧は HEAD の `.claude/settings.json` の `worktree.sparsePaths` を正本とする。
 *
 *   node scripts/worktree-assets.mjs status
 *   node scripts/worktree-assets.mjs expand   [--skip-process-check]
 *   node scripts/worktree-assets.mjs shrink   [--skip-process-check] [--force-replace]
 *   node scripts/worktree-assets.mjs reapply  [--skip-process-check] [--force-replace]
 */
import { spawnSync } from 'node:child_process';
import { existsSync, statfsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = 'node scripts/worktree-assets.mjs';
const SETTINGS_FILE = '.claude/settings.json';

const USAGE = `使い方: ${SCRIPT} <status|expand|shrink|reapply> [オプション]
  status    sparse の状態・展開/除外ディレクトリ・設定との一致を表示（読み取りのみ）
  expand    全展開する（必要な容量を表示し、空きが足りなければ何もしない）
  shrink    HEAD の worktree.sparsePaths で縮小する（本陣では拒否）
  reapply   sparse の worktree で、HEAD の最新の worktree.sparsePaths へ一覧を更新する（本陣では拒否）
オプション:
  --skip-process-check  対象 worktree を使うプロセスの検査を省く（取得できない環境用。明示時のみ）
  --force-replace       設定と無関係な既存の sparse 一覧を置き換える`;

/** git を実行する。差し替え可能（テストで失敗を注入する）。 */
export function defaultRunGit(cwd, args) {
  const r = spawnSync('git', ['-c', 'core.quotepath=off', ...args], {
    cwd,
    encoding: 'utf8',
    maxBuffer: 512 * 1024 * 1024,
    windowsHide: true,
  });
  if (r.error) return { status: 127, stdout: '', stderr: String(r.error.message) };
  return { status: r.status ?? 1, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
}

/** 稼働中プロセスの一覧 [{pid, ppid, cmd}]。取得できなければ例外。差し替え可能。 */
export function defaultListProcesses() {
  if (process.platform === 'win32') {
    const r = spawnSync(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json -Compress',
      ],
      { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, windowsHide: true },
    );
    if (r.error || r.status !== 0) throw new Error(`Get-CimInstance 失敗: ${r.error?.message ?? r.stderr}`);
    const parsed = JSON.parse(r.stdout);
    return (Array.isArray(parsed) ? parsed : [parsed]).map((p) => ({
      pid: p.ProcessId,
      ppid: p.ParentProcessId,
      cmd: p.CommandLine ?? '',
    }));
  }
  const r = spawnSync('ps', ['-eo', 'pid=,ppid=,args='], { encoding: 'utf8', maxBuffer: 128 * 1024 * 1024 });
  if (r.error || r.status !== 0) throw new Error(`ps 失敗: ${r.error?.message ?? r.stderr}`);
  return r.stdout
    .split('\n')
    .map((line) => line.match(/^\s*(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean)
    .map((m) => ({ pid: Number(m[1]), ppid: Number(m[2]), cmd: m[3] }));
}

export function defaultFreeBytes(path) {
  const s = statfsSync(path);
  return Number(s.bavail) * Number(s.bsize);
}

const normPath = (p) => p.replaceAll('\\', '/').replace(/^\/+|\/+$/g, '');
const normSet = (list) => [...new Set(list.map(normPath).filter(Boolean))].sort();
const sameSet = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
const dirOf = (path) => (path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '');
const human = (n) => (n >= 1024 ** 3 ? `${(n / 1024 ** 3).toFixed(2)} GB` : `${(n / 1024 ** 2).toFixed(1)} MB`);

/** cone モードで、ディレクトリ dir の直下ファイルが展開されるか（ルート・一覧配下・一覧の祖先）。 */
function isIncluded(dir, sparse) {
  if (dir === '') return true;
  return sparse.some((p) => dir === p || dir.startsWith(`${p}/`) || p.startsWith(`${dir}/`));
}

function makeContext(deps) {
  const log = deps.log ?? ((m) => console.log(m));
  const error = deps.error ?? ((m) => console.error(m));
  const runGit = deps.runGit ?? defaultRunGit;
  return {
    log,
    error,
    runGit,
    listProcesses: deps.listProcesses ?? defaultListProcesses,
    freeBytes: deps.freeBytes ?? defaultFreeBytes,
    cwd: deps.cwd ?? process.cwd(),
    top: '',
    git(args) {
      return runGit(this.top || this.cwd, args);
    },
  };
}

function gitPathAbs(ctx, name) {
  const r = ctx.git(['rev-parse', '--path-format=absolute', '--git-path', name]);
  return r.status === 0 ? r.stdout.trim() : null;
}

function isMainWorktree(ctx) {
  const dir = ctx.git(['rev-parse', '--path-format=absolute', '--git-dir']);
  const common = ctx.git(['rev-parse', '--path-format=absolute', '--git-common-dir']);
  if (dir.status !== 0 || common.status !== 0) throw new Error('git-dir を判定できない');
  return resolve(dir.stdout.trim()).toLowerCase() === resolve(common.stdout.trim()).toLowerCase();
}

function currentSparse(ctx) {
  const r = ctx.git(['sparse-checkout', 'list']);
  if (r.status !== 0) return { sparse: false, list: [] };
  const list = normSet(r.stdout.split('\n'));
  return { sparse: list.length > 0, list };
}

/** HEAD の設定。{ ok, paths } または { ok:false, reason } */
function readSettings(ctx) {
  const r = ctx.git(['show', `HEAD:${SETTINGS_FILE}`]);
  if (r.status !== 0) return { ok: false, reason: `HEAD に ${SETTINGS_FILE} が無い` };
  let json;
  try {
    json = JSON.parse(r.stdout);
  } catch (e) {
    return { ok: false, reason: `${SETTINGS_FILE} が JSON として読めない: ${e.message}` };
  }
  const paths = json?.worktree?.sparsePaths;
  if (!Array.isArray(paths) || paths.length === 0) return { ok: false, reason: `${SETTINGS_FILE} に worktree.sparsePaths が無い` };
  return { ok: true, paths: normSet(paths) };
}

function trackedFiles(ctx) {
  const r = ctx.git(['ls-files', '-t', '-z']);
  if (r.status !== 0) throw new Error(`git ls-files 失敗: ${r.stderr.trim()}`);
  return r.stdout
    .split('\0')
    .filter(Boolean)
    .map((e) => ({ tag: e[0], path: e.slice(2) }));
}

function headSizes(ctx) {
  const r = ctx.git(['ls-tree', '-r', '-l', '-z', 'HEAD']);
  if (r.status !== 0) throw new Error(`git ls-tree 失敗: ${r.stderr.trim()}`);
  const sizes = new Map();
  for (const e of r.stdout.split('\0').filter(Boolean)) {
    const tab = e.indexOf('\t');
    const size = Number(e.slice(0, tab).trim().split(/\s+/)[3]);
    sizes.set(e.slice(tab + 1), Number.isFinite(size) ? size : 0);
  }
  return sizes;
}

function statusEntries(ctx) {
  const r = ctx.git(['status', '--porcelain=v1', '-z', '-unormal', '--ignored=traditional']);
  if (r.status !== 0) throw new Error(`git status 失敗: ${r.stderr.trim()}`);
  const tokens = r.stdout.split('\0').filter((t) => t !== '');
  const entries = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const code = tokens[i].slice(0, 2);
    const paths = [tokens[i].slice(3)];
    if (/[RC]/.test(code)) paths.push(tokens[(i += 1)]);
    for (const p of paths) entries.push({ code, path: p });
  }
  return entries;
}

function stateSummary(ctx) {
  const cur = currentSparse(ctx);
  const st = ctx.git(['status', '--short']);
  const lines = st.stdout.split('\n').filter(Boolean);
  return [
    '到達状態:',
    `  sparse: ${cur.sparse ? 'はい' : 'いいえ'}`,
    `  sparse 一覧: ${cur.list.length ? cur.list.join(', ') : '(なし)'}`,
    `  git status: ${lines.length}件${lines.length ? `\n    ${lines.slice(0, 10).join('\n    ')}${lines.length > 10 ? '\n    ...' : ''}` : ''}`,
  ].join('\n');
}

function fail(ctx, message, recovery) {
  ctx.error(message);
  if (recovery) ctx.error(recovery);
  return 1;
}

/** 失敗時: 非0・到達状態・復旧手順を出す (AC-7)。 */
function failMidway(ctx, what, r, rerun) {
  ctx.error(`${what}に失敗した: ${(r.stderr || r.stdout || '').trim()}`);
  ctx.error(stateSummary(ctx));
  ctx.error(`復旧: 既存の変更・未追跡・ignore 済みファイルは失っていない。原因（使用中のファイル・ロック等）を解消して再実行する: ${rerun}`);
  return 1;
}

/** 縮小の安全検査。問題の説明の配列（空なら安全）。 */
function safetyProblems(ctx, newList, { checkRegion, checkProcess, skipProcessCheck }) {
  const problems = [];
  const unmerged = ctx.git(['ls-files', '-u', '-z']);
  if (unmerged.status === 0 && unmerged.stdout) {
    const paths = [...new Set(unmerged.stdout.split('\0').filter(Boolean).map((e) => e.slice(e.indexOf('\t') + 1)))];
    problems.push(`未マージのパスがある:\n    ${paths.slice(0, 20).join('\n    ')}`);
  }
  for (const marker of ['MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply', 'BISECT_LOG']) {
    const p = gitPathAbs(ctx, marker);
    if (p && existsSync(p)) problems.push(`進行中の操作がある: ${marker}`);
  }
  const lock = gitPathAbs(ctx, 'index.lock');
  if (lock && existsSync(lock)) problems.push(`index.lock が残っている: ${lock}`);

  if (checkRegion) {
    const hits = [];
    for (const { code, path } of statusEntries(ctx)) {
      const isDir = path.endsWith('/');
      const dir = isDir ? normPath(path) : dirOf(path);
      if (isIncluded(dir, newList)) continue;
      const kind = code === '??' ? '未追跡' : code === '!!' ? 'ignore済み' : /\S./.test(code) && code[0] !== ' ' ? 'ステージ済み/変更' : '未コミット変更';
      hits.push(`[${kind}] ${path}`);
    }
    if (hits.length) problems.push(`縮小で外れる領域に変更・未追跡・ignore 済みファイルがある（失うため中断）:\n    ${hits.slice(0, 30).join('\n    ')}${hits.length > 30 ? `\n    ... 他 ${hits.length - 30} 件` : ''}`);
  }

  if (checkProcess && !skipProcessCheck) {
    const want = resolve(ctx.top).replaceAll('\\', '/').toLowerCase();
    try {
      const procs = ctx.listProcesses().filter((p) => p.pid !== process.pid && p.pid !== process.ppid);
      const hit = procs.filter((p) => {
        const cmd = (p.cmd ?? '').replaceAll('\\', '/').toLowerCase();
        return cmd.includes(want) && /java|node|gradle/.test(cmd);
      });
      if (hit.length) problems.push(`この worktree を使うプロセスが動いている（停止してから再実行）:\n    ${hit.slice(0, 10).map((p) => `pid ${p.pid}: ${p.cmd.slice(0, 160)}`).join('\n    ')}`);
    } catch (e) {
      problems.push(`プロセスを確認できない（${e.message}）。確認できないため拒否する。自分で確認済みなら --skip-process-check を付ける`);
    }
  }
  return problems;
}

function worktreeConfigEnabled(ctx) {
  const r = ctx.git(['config', '--get', 'extensions.worktreeConfig']);
  return r.status === 0 && r.stdout.trim().toLowerCase() === 'true';
}

function applyList(ctx, list, rerun) {
  const r = ctx.git(['sparse-checkout', 'set', '--cone', ...list]);
  if (r.status !== 0) return { code: failMidway(ctx, 'git sparse-checkout set', r, rerun) };
  const after = currentSparse(ctx);
  if (!sameSet(after.list, list)) {
    return { code: failMidway(ctx, '適用後の検証（sparse 一覧が期待と異なる）', { stderr: `期待: ${list.join(', ')} / 実際: ${after.list.join(', ')}` }, rerun) };
  }
  return { code: 0 };
}

function cmdStatus(ctx) {
  const main = isMainWorktree(ctx);
  const cur = currentSparse(ctx);
  const settings = readSettings(ctx);
  ctx.log(`worktree: ${ctx.top}${main ? '（本陣・メイン作業木）' : ''}`);
  ctx.log(`sparse: ${cur.sparse ? 'はい' : 'いいえ'}`);
  if (cur.sparse) ctx.log(`現在の sparse 一覧: ${cur.list.join(', ')}`);
  if (settings.ok) {
    ctx.log(`HEAD の worktree.sparsePaths: ${settings.paths.join(', ')}`);
    if (cur.sparse) ctx.log(sameSet(cur.list, settings.paths) ? '一覧は設定と一致（古くない）' : '一覧が設定と異なる（古い可能性。reapply で更新、無関係なら --force-replace が必要）');
  } else {
    ctx.log(`設定が未投入: ${settings.reason}`);
  }
  const files = trackedFiles(ctx);
  const sizes = headSizes(ctx);
  const groups = new Map();
  let skipped = 0;
  for (const f of files) {
    if (f.tag !== 'S') continue;
    skipped += 1;
    const g = f.path.split('/').slice(0, 2).join('/');
    const e = groups.get(g) ?? { n: 0, bytes: 0 };
    e.n += 1;
    e.bytes += sizes.get(f.path) ?? 0;
    groups.set(g, e);
  }
  ctx.log(`展開: ${files.length - skipped} ファイル / 除外(skip-worktree): ${skipped} ファイル`);
  for (const [g, e] of [...groups].sort()) ctx.log(`  除外 ${g}: ${e.n} ファイル, ${human(e.bytes)}`);
  return 0;
}

function cmdExpand(ctx, opts) {
  const files = trackedFiles(ctx);
  const skipped = files.filter((f) => f.tag === 'S');
  const cur = currentSparse(ctx);
  if (skipped.length === 0 && !cur.sparse) {
    ctx.log('既に全展開のため何もしない');
    return 0;
  }
  const sizes = headSizes(ctx);
  const need = skipped.reduce((sum, f) => sum + (sizes.get(f.path) ?? 0), 0);
  let free;
  try {
    free = ctx.freeBytes(ctx.top);
  } catch (e) {
    return fail(ctx, `空き容量を取得できないため中断: ${e.message}`);
  }
  ctx.log(`必要な容量: ${human(need)}（除外中 ${skipped.length} ファイル） / 空き容量: ${human(free)}`);
  if (free < need) return fail(ctx, `空き容量が足りないため何も変えずに中断する（不足 ${human(need - free)}）`);
  if (cur.sparse && !worktreeConfigEnabled(ctx)) {
    return fail(ctx, 'extensions.worktreeConfig が無効のため、全展開すると共有 .git/config を変えてしまう。中断する。');
  }
  const r = ctx.git(['sparse-checkout', 'disable']);
  if (r.status !== 0) return failMidway(ctx, 'git sparse-checkout disable', r, `${SCRIPT} expand`);
  const remain = trackedFiles(ctx).filter((f) => f.tag === 'S').length;
  if (remain > 0) return failMidway(ctx, '全展開の検証', { stderr: `除外が ${remain} ファイル残っている` }, `${SCRIPT} expand`);
  ctx.log('全展開した');
  return 0;
}

function cmdShrinkOrReapply(ctx, sub, opts) {
  if (isMainWorktree(ctx)) {
    return fail(ctx, `${sub} は本陣（メイン作業木）では実行しない。足軽の worktree で実行すること。何も変更していない。`);
  }
  const settings = readSettings(ctx);
  if (!settings.ok) return fail(ctx, `設定が未投入のため ${sub} できない: ${settings.reason}`);
  const want = settings.paths;
  const cur = currentSparse(ctx);
  const rerun = `${SCRIPT} ${sub}${opts.skipProcessCheck ? ' --skip-process-check' : ''}${opts.forceReplace ? ' --force-replace' : ''}`;

  if (sub === 'reapply' && !cur.sparse) return fail(ctx, 'この worktree は sparse ではないため reapply できない。縮小するなら shrink を使う。');
  if (cur.sparse && sameSet(cur.list, want)) {
    ctx.log('sparse 一覧は既に設定と一致しているため何もしない');
    return 0;
  }
  if (cur.sparse && !opts.forceReplace) {
    const related = sub === 'reapply' && (cur.list.every((p) => want.includes(p)) || want.every((p) => cur.list.includes(p)));
    if (!related) {
      return fail(
        ctx,
        `現在の sparse 一覧（${cur.list.join(', ')}）は設定（${want.join(', ')}）と無関係のため、上書きせず中断する。置き換えてよい場合のみ --force-replace を付ける。`,
      );
    }
  }
  if (!worktreeConfigEnabled(ctx)) {
    return fail(ctx, 'extensions.worktreeConfig が無効のため、sparse 設定が共有 .git/config を書き換えてしまう。他の worktree に影響するので中断する。');
  }
  const removing = !cur.sparse || cur.list.some((p) => !want.includes(p));
  const problems = safetyProblems(ctx, want, { checkRegion: removing, checkProcess: removing, skipProcessCheck: opts.skipProcessCheck });
  if (problems.length) {
    ctx.error(`${sub} を中断する（何も変更していない）:`);
    for (const p of problems) ctx.error(`  - ${p}`);
    return 1;
  }
  const { code } = applyList(ctx, want, rerun);
  if (code !== 0) return code;
  ctx.log(`${sub} 完了: sparse 一覧 = ${want.join(', ')}`);
  return 0;
}

export async function run(argv, deps = {}) {
  const ctx = makeContext(deps);
  const [sub, ...rest] = argv;
  if (!['status', 'expand', 'shrink', 'reapply'].includes(sub)) {
    ctx.error(sub ? `未知のサブコマンド: ${sub}` : 'サブコマンドが無い');
    ctx.error(USAGE);
    return 1;
  }
  const opts = { skipProcessCheck: false, forceReplace: false };
  for (const a of rest) {
    if (a === '--skip-process-check') opts.skipProcessCheck = true;
    else if (a === '--force-replace') opts.forceReplace = true;
    else {
      ctx.error(`未対応の引数: ${a}`);
      ctx.error(USAGE);
      return 1;
    }
  }
  const top = ctx.runGit(ctx.cwd, ['rev-parse', '--show-toplevel']);
  if (top.status !== 0) return fail(ctx, 'git リポジトリの中で実行すること');
  ctx.top = top.stdout.trim();
  try {
    if (sub === 'status') return cmdStatus(ctx);
    if (sub === 'expand') return cmdExpand(ctx, opts);
    return cmdShrinkOrReapply(ctx, sub, opts);
  } catch (e) {
    ctx.error(`${sub} が失敗した: ${e.message}`);
    ctx.error(stateSummary(ctx));
    ctx.error(`復旧: 変更したファイルは失っていない。原因を解消して再実行する: ${SCRIPT} ${sub}`);
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  process.exitCode = await run(process.argv.slice(2));
}
