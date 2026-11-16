#!/usr/bin/env node
/**
 * 足軽 worktree の素材（牧場 PNG など）を展開・縮小する。
 * git sparse-checkout（cone モード）を、現在の worktree だけに対して操作する。
 * 縮小時の一覧は HEAD の `.claude/settings.json` の `worktree.sparsePaths` を正本とする。
 *
 *   node scripts/worktree-assets.mjs status
 *   node scripts/worktree-assets.mjs expand   [--skip-process-check] [--skip-space-check]
 *   node scripts/worktree-assets.mjs shrink   [--skip-process-check] [--force-replace]
 *   node scripts/worktree-assets.mjs reapply  [--skip-process-check] [--force-replace]
 */
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readdirSync, readlinkSync, renameSync, statfsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = 'node scripts/worktree-assets.mjs';
const SETTINGS_FILE = '.claude/settings.json';
const DIGEST_KEY = 'mannschaft.sparsePathsDigest';
/** 空き容量の見積りに足す固定の余裕。 */
export const SPACE_MARGIN_BYTES = 200 * 1024 * 1024;

const USAGE = `使い方: ${SCRIPT} <status|expand|shrink|reapply> [オプション]
  status    sparse の状態・展開/除外ディレクトリ・設定との一致を表示（読み取りのみ）
  expand    全展開する（必要容量の見積りを表示し、空きが足りなければ何もしない）
  shrink    HEAD の worktree.sparsePaths で縮小する（本陣では拒否）
  reapply   sparse の worktree で、HEAD の最新の worktree.sparsePaths へ一覧を更新する（本陣では拒否）
オプション:
  --skip-process-check  プロセス一覧の検査だけを省く（使用中プローブと事後検証は省かない）
  --skip-space-check    空き容量を取得できない環境でだけ、容量検査を省く（expand）
  --force-replace       設定・履歴・本コマンドの印のいずれにも当たらない既存の sparse 一覧を置き換える
限界: Windows では cwd だけが worktree 内のプロセスは検出できない。使用中の検出は
  「外れるディレクトリの rename プローブ」と「適用後の実状態検証」による。
  検査から実行までの間に起きた変更は原理的に防げない（事後検証で非0になる）。
  実行中は対象 worktree で他の作業をしない（エディタ・ビルド・テスト・git 操作を止める）。
  junction・symlink が外れる領域や展開先（祖先を含む）にある場合は、参照先を壊さないよう拒否する。`;

/** git を実行する。差し替え可能（テストで失敗を注入する）。 */
export function defaultRunGit(cwd, args, input) {
  const r = spawnSync('git', ['-c', 'core.quotepath=off', ...args], {
    cwd,
    encoding: 'utf8',
    input,
    maxBuffer: 512 * 1024 * 1024,
    windowsHide: true,
  });
  if (r.error) return { status: 127, stdout: '', stderr: String(r.error.message) };
  return { status: r.status ?? 1, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
}

/** 稼働中プロセスの一覧 [{pid, ppid, cmd, cwd?}]。取得できなければ例外。差し替え可能。 */
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
    .map((m) => {
      const entry = { pid: Number(m[1]), ppid: Number(m[2]), cmd: m[3] };
      if (process.platform === 'linux') {
        try {
          entry.cwd = readlinkSync(`/proc/${entry.pid}/cwd`);
        } catch {
          // 権限が無い・終了済みは cwd 不明のまま（cmd 一致で検査する）
        }
      }
      return entry;
    });
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
const digestOf = (list) => createHash('sha1').update(normSet(list).join('\n')).digest('hex');
const lc = (p) => p.replaceAll('\\', '/').toLowerCase();

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
    rename: deps.rename ?? renameSync,
    probe: deps.probe ?? process.platform === 'win32',
    cwd: deps.cwd ?? process.cwd(),
    top: '',
    git(args, input) {
      return runGit(this.top || this.cwd, args, input);
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

function trackedFiles(ctx) {
  const r = ctx.git(['ls-files', '-t', '-z']);
  if (r.status !== 0) throw new Error(`git ls-files 失敗: ${r.stderr.trim()}`);
  return r.stdout
    .split('\0')
    .filter(Boolean)
    .map((e) => ({ tag: e[0], path: e.slice(2) }));
}

/**
 * この worktree が sparse か。list の非空性では判定しない（ルート直下だけの cone は list が空）。
 * worktree 側の設定+info/sparse-checkout、または skip-worktree ビットの立ったファイルで判定する。
 * 共有 config の core.sparsecheckout 漏れだけでは sparse としない。
 */
function sparseState(ctx, files) {
  const skip = files.filter((f) => f.tag === 'S').length;
  const w = ctx.git(['config', '--worktree', '--get', 'core.sparseCheckout']);
  const cfgOn = w.status === 0 && w.stdout.trim().toLowerCase() === 'true';
  const fp = gitPathAbs(ctx, 'info/sparse-checkout');
  const sparse = skip > 0 || (cfgOn && Boolean(fp) && existsSync(fp));
  let list = [];
  if (sparse) {
    const r = ctx.git(['sparse-checkout', 'list']);
    if (r.status === 0) list = normSet(r.stdout.split('\n'));
  }
  return { sparse, list, skip };
}

function parseSettingsText(text) {
  try {
    return { json: JSON.parse(text) };
  } catch (e) {
    return { error: e.message };
  }
}

/** sparsePaths の全要素を検証する。正規化で黙って直さない。エラー文の配列を返す。 */
function validateSparsePaths(ctx, paths) {
  const errors = [];
  const seen = new Set();
  paths.forEach((p, i) => {
    const label = `[${i}] ${JSON.stringify(p)}`;
    if (typeof p !== 'string') return errors.push(`${label}: 文字列ではない`);
    if (p === '') return errors.push(`${label}: 空`);
    if (p !== p.trim()) return errors.push(`${label}: 前後に空白がある`);
    if (/[\x00-\x1f]/.test(p)) return errors.push(`${label}: 制御文字を含む`);
    if (p.startsWith('-')) return errors.push(`${label}: 先頭が - （git のオプションと解釈されうる）`);
    if (p.includes('\\')) return errors.push(`${label}: バックスラッシュを含む（/ 区切りにする）`);
    if (p.startsWith('/') || /^[A-Za-z]:/.test(p)) return errors.push(`${label}: 絶対パス・ドライブ指定`);
    if (p.split('/').some((s) => s === '' || s === '.' || s === '..')) return errors.push(`${label}: 空・. ・.. の要素、または末尾の / を含む`);
    if (seen.has(p)) return errors.push(`${label}: 重複`);
    seen.add(p);
    const t = ctx.git(['cat-file', '-t', `HEAD:${p}`]);
    if (t.status !== 0 || t.stdout.trim() !== 'tree') errors.push(`${label}: HEAD に追跡ディレクトリとして実在しない`);
  });
  // cone では祖先と子孫の併記を git が祖先へ正規化してしまう。黙って正規化せず拒否する
  const strs = [...seen];
  for (const a of strs) {
    for (const b of strs) {
      if (b.startsWith(`${a}/`)) errors.push(`親子重複: "${a}" は "${b}" の祖先（cone では子孫が祖先へ正規化される。どちらかを外す）`);
    }
  }
  return errors;
}

/** HEAD の設定。{ ok, paths } または { ok:false, reason } */
function readSettings(ctx) {
  const r = ctx.git(['show', `HEAD:${SETTINGS_FILE}`]);
  if (r.status !== 0) return { ok: false, reason: `HEAD に ${SETTINGS_FILE} が無い` };
  const parsed = parseSettingsText(r.stdout);
  if (parsed.error) return { ok: false, reason: `${SETTINGS_FILE} が JSON として読めない: ${parsed.error}` };
  const paths = parsed.json?.worktree?.sparsePaths;
  if (!Array.isArray(paths) || paths.length === 0) return { ok: false, reason: `${SETTINGS_FILE} に worktree.sparsePaths が無い` };
  const errors = validateSparsePaths(ctx, paths);
  if (errors.length) return { ok: false, reason: `worktree.sparsePaths が不正（何も変更していない）:\n    ${errors.join('\n    ')}` };
  return { ok: true, paths: [...paths].sort() };
}

/** 現在の一覧を「設定由来」と認めるか: HEAD 設定・settings.json の履歴・本コマンドの印のいずれかと一致。 */
function isOwnList(ctx, list, want) {
  if (sameSet(list, want)) return true;
  const mark = ctx.git(['config', '--worktree', '--get', DIGEST_KEY]);
  if (mark.status === 0 && mark.stdout.trim() === digestOf(list)) return true;
  const log = ctx.git(['log', '--format=%H', '-n', '100', '--', SETTINGS_FILE]);
  if (log.status !== 0) return false;
  for (const sha of log.stdout.split('\n').filter(Boolean)) {
    const show = ctx.git(['show', `${sha}:${SETTINGS_FILE}`]);
    if (show.status !== 0) continue;
    const paths = parseSettingsText(show.stdout).json?.worktree?.sparsePaths;
    if (Array.isArray(paths) && paths.every((p) => typeof p === 'string') && sameSet(normSet(paths), list)) return true;
  }
  return false;
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
  const st = ctx.git(['status', '--short']);
  const lines = st.stdout.split('\n').filter(Boolean);
  let sparseText = '(判定不能)';
  try {
    const s = sparseState(ctx, trackedFiles(ctx));
    sparseText = `${s.sparse ? 'はい' : 'いいえ'} / 一覧: ${s.list.length ? s.list.join(', ') : '(なし)'}`;
  } catch {
    // 要約は失敗しても本来のエラー表示を妨げない
  }
  return [
    '到達状態:',
    `  sparse: ${sparseText}`,
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

function ancestorPids(procs, start) {
  const byPid = new Map(procs.map((p) => [p.pid, p]));
  const set = new Set([start]);
  for (let p = byPid.get(start); p && !set.has(p.ppid); p = byPid.get(p.ppid)) set.add(p.ppid);
  return set;
}

/** 縮小で外れる領域の最も浅いディレクトリ（プローブ対象）。 */
function leavingRoots(leaving, want) {
  const roots = new Set();
  for (const f of leaving) {
    const parts = dirOf(f.path).split('/');
    for (let i = 1; i <= parts.length; i += 1) {
      const q = parts.slice(0, i).join('/');
      if (!isIncluded(q, want)) {
        roots.add(q);
        break;
      }
    }
  }
  return [...roots].sort();
}

/**
 * 使用中プローブ: 外れるディレクトリを同じ親の一時名へ rename して戻す。
 * Windows では配下に開かれたハンドルがあると rename が失敗する。
 * 戻り値 { busy: string[], fatal?: string }
 */
function probeDirectories(ctx, roots) {
  const busy = [];
  for (const root of roots) {
    const abs = join(ctx.top, root);
    if (!existsSync(abs)) continue;
    const tmp = `${abs}.wa-probe-${process.pid}`;
    try {
      ctx.rename(abs, tmp);
    } catch (e) {
      busy.push(`${root}（${e.code ?? e.message}）`);
      continue;
    }
    let last;
    let restored = false;
    for (let attempt = 0; attempt < 5 && !restored; attempt += 1) {
      try {
        ctx.rename(tmp, abs);
        restored = true;
      } catch (e) {
        last = e;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 100);
      }
    }
    if (!restored) {
      return {
        busy,
        fatal: [
          `使用中プローブで ${abs} を一時名 ${tmp} へ移したが、元の名前へ戻せなかった（${last?.code ?? last?.message}）。`,
          `手動復旧: ${tmp} を ${abs} へ名前変更して戻すこと（Windows: Move-Item -LiteralPath "${tmp}" -Destination "${abs}" / Linux: mv "${tmp}" "${abs}"）。`,
          '戻すまで他の操作（git status・shrink 等）をしない。ファイルは失われていない。',
        ].join('\n'),
      };
    }
  }
  return { busy };
}

/** 展開されるパス（とその祖先）の位置に未追跡・ignore 済みの実体が既にあるか。 */
function expansionCollisions(ctx, paths) {
  const hits = new Set();
  const cache = new Map();
  const stat = (rel) => {
    if (!cache.has(rel)) {
      try {
        cache.set(rel, lstatSync(join(ctx.top, rel)));
      } catch {
        cache.set(rel, null);
      }
    }
    return cache.get(rel);
  };
  for (const p of paths) {
    const parts = p.split('/');
    for (let i = 0; i < parts.length; i += 1) {
      const rel = parts.slice(0, i + 1).join('/');
      const st = stat(rel);
      if (!st) break;
      if (i < parts.length - 1) {
        if (!st.isDirectory()) {
          hits.add(`${rel}（ファイル等がディレクトリの位置を塞いでいる。展開先: ${p}）`);
          break;
        }
      } else {
        hits.add(`${rel}（追跡ファイルの位置に未追跡・ignore 済みの実体がある）`);
      }
    }
  }
  return [...hits];
}

/** 縮小・更新の事前検査。問題の説明の配列（空なら安全）。 */
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
      const kind = code === '??' ? '未追跡' : code === '!!' ? 'ignore済み' : code[0] !== ' ' ? 'ステージ済み/変更' : '未コミット変更';
      hits.push(`[${kind}] ${path}`);
    }
    if (hits.length) problems.push(`縮小で外れる領域に変更・未追跡・ignore 済みファイルがある（失うため中断）:\n    ${hits.slice(0, 30).join('\n    ')}${hits.length > 30 ? `\n    ... 他 ${hits.length - 30} 件` : ''}`);
  }

  if (checkProcess && !skipProcessCheck) {
    const want = lc(resolve(ctx.top));
    try {
      const all = ctx.listProcesses();
      const skip = ancestorPids(all, process.pid);
      const hit = all.filter((p) => {
        if (skip.has(p.pid)) return false;
        const cmd = lc(p.cmd ?? '');
        const cwd = p.cwd ? lc(p.cwd) : '';
        const inside = cmd.includes(want) || cwd === want || cwd.startsWith(`${want}/`);
        return inside && /java|node|gradle/.test(cmd);
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

const WORKTREE_CONFIG_MESSAGE =
  'extensions.worktreeConfig が無効のため、sparse の操作が共有 .git/config を書き換えてしまう。他の worktree に影響するので何も変えず中断する。';

/** lstat（リンクを辿らない）。無ければ null。 */
function lstatSafe(ctx, rel) {
  try {
    return lstatSync(rel === '' ? ctx.top : join(ctx.top, rel));
  } catch {
    return null;
  }
}

/** 作業木に実体（リンク自体を含む）があるか。 */
const present = (ctx, rel) => lstatSafe(ctx, rel) !== null;

/** git が追跡している symlink エントリ（mode 120000）のパス集合。これは通常の追跡ファイルとして扱う。 */
function trackedSymlinks(ctx) {
  const r = ctx.git(['ls-files', '-s', '-z']);
  if (r.status !== 0) throw new Error(`git ls-files -s 失敗: ${r.stderr.trim()}`);
  const set = new Set();
  for (const e of r.stdout.split('\0').filter(Boolean)) {
    if (e.startsWith('120000 ')) set.add(e.slice(e.indexOf('\t') + 1));
  }
  return set;
}

/**
 * junction・symlink（reparse point）の検査。git の sparse 操作はリンクの向こう側の追跡ファイルまで
 * 削除・作成しうるため、変更を伴う全経路で変更前に拒否する。
 * 対象: worktree ルート自身、外れる/展開されるパスの全ての祖先、外れる領域の配下の全階層。
 * git が追跡する symlink エントリ（120000）自体は通常の追跡ファイルなので除く。
 */
function reparseProblems(ctx, { leaving, expanding, roots }) {
  const hits = new Map();
  const tracked = trackedSymlinks(ctx);
  const add = (rel, why) => {
    if (!hits.has(rel)) hits.set(rel, why);
  };
  const rootSt = lstatSafe(ctx, '');
  if (rootSt?.isSymbolicLink()) add('.', 'worktree ルート自身が junction/symlink');
  const aliasSt = (() => {
    try {
      return lstatSync(ctx.cwd);
    } catch {
      return null;
    }
  })();
  if (aliasSt?.isSymbolicLink()) add(ctx.cwd.replaceAll('\\', '/'), '実行場所が junction/symlink（worktree ルートの別名）');
  for (const f of [...leaving, ...expanding]) {
    const parts = f.path.split('/');
    for (let i = 0; i < parts.length - 1; i += 1) {
      const rel = parts.slice(0, i + 1).join('/');
      const st = lstatSafe(ctx, rel);
      if (!st) break;
      if (st.isSymbolicLink()) {
        add(rel, `祖先ディレクトリが junction/symlink（${f.path} の親）`);
        break;
      }
    }
    const leaf = lstatSafe(ctx, f.path);
    if (leaf?.isSymbolicLink() && !tracked.has(f.path)) add(f.path, '追跡ファイルの位置に junction/symlink（git が追跡するリンクではない）');
  }
  const walk = (rel) => {
    let entries;
    try {
      entries = readdirSync(join(ctx.top, rel), { withFileTypes: true });
    } catch {
      return;
    }
    for (const d of entries) {
      const child = rel ? `${rel}/${d.name}` : d.name;
      if (d.isSymbolicLink()) {
        if (!tracked.has(child)) add(child, '外れる領域の中に junction/symlink がある');
      } else if (d.isDirectory()) {
        walk(child);
      }
    }
  };
  for (const root of roots) walk(root);
  return [...hits].map(([rel, why]) => `${rel}（${why}）`);
}

/** 前回の rename プローブが強制終了で残した一時名（status の未追跡・ignore から検出）。削除はしない。 */
function staleProbeNames(ctx) {
  const names = new Set();
  for (const { path } of statusEntries(ctx)) {
    const m = normPath(path).match(/^(.*)\.wa-probe-\d+$/);
    if (m) names.add(`${normPath(path)}|${m[1]}`);
  }
  return [...names].map((s) => {
    const [tmp, orig] = s.split('|');
    return { tmp, orig };
  });
}

/**
 * 目標一覧 want に対する実状態の全体検証（前回との差分ではない）。
 * - 展開されるべき追跡ファイルは skip-worktree が外れて実在する（欠落＝status の ` D`）
 * - 外れるべき追跡ファイルは skip-worktree が立っていて作業木に無い
 * want === null は全展開（外れるものは無い）。
 */
function verifyFull(ctx, want, scope = null) {
  const problems = [];
  const left = [];
  const notExpanded = [];
  const missing = [];
  for (const f of trackedFiles(ctx)) {
    const inc = want === null || isIncluded(dirOf(f.path), want);
    if (inc) {
      if (scope && !scope.has(f.path)) continue; // 全展開では、展開対象だった（S だった）ファイルだけを見る
      if (f.tag === 'S') notExpanded.push(f.path);
      else if (!present(ctx, f.path)) missing.push(f.path);
    } else if (f.tag !== 'S' || present(ctx, f.path)) {
      left.push(f.path);
    }
  }
  const fmt = (title, list) => `${title}:\n    ${list.slice(0, 20).join('\n    ')}${list.length > 20 ? `\n    ... 他 ${list.length - 20} 件` : ''}`;
  if (left.length) problems.push(fmt('外れるはずのファイルが残っている（使用中の可能性）', left));
  if (notExpanded.length) problems.push(fmt('展開されるはずのファイルが展開されていない（skip-worktree のまま）', notExpanded));
  if (missing.length) {
    problems.push(
      `${fmt('追跡ファイルが作業木に無い（status の D。展開を妨げる実体があった・意図しない削除）', missing)}\n    復元: git restore -- <上のパス>（意図した削除ならコミットまたは git rm してから再実行）`,
    );
  }
  return problems;
}

function failVerify(ctx, what, problems, rerun) {
  ctx.error(`${what}の結果検証に失敗した（一部だけ適用された可能性）:`);
  for (const p of problems) ctx.error(`  - ${p}`);
  ctx.error(stateSummary(ctx));
  ctx.error(`復旧: 既存の変更・未追跡・ignore 済みファイルは失っていない。使用中のプロセス・エディタ等を閉じ、未追跡の実体があれば退避し、欠落ファイルは復元してから再実行する（再適用して検証し直す）: ${rerun}`);
  return 1;
}

/**
 * 作業木を書き換える git 呼び出し（sparse-checkout set / reapply / disable）は、この関数だけが行う。
 * 入口で必ず同じ安全検査を通す: worktreeConfig・未コミット/ステージ済み/未追跡/ignore 済み・未マージ・
 * 進行中操作・index.lock・junction/symlink・展開先の衝突・前回プローブの残骸・プロセス・使用中プローブ。
 * kind: 'set'（want へ一覧を設定）/ 'reapply'（現在の一覧を再適用）/ 'disable'（全展開, want=null）
 * 戻り値: 0 または非0（メッセージは表示済み）
 */
function guardedApply(ctx, { sub, kind, want, opts, rerun }) {
  if (!worktreeConfigEnabled(ctx)) return fail(ctx, WORKTREE_CONFIG_MESSAGE);
  const files = trackedFiles(ctx);
  const outside = (f) => want !== null && !isIncluded(dirOf(f.path), want);
  const leaving = files.filter((f) => outside(f) && f.tag !== 'S');
  const leftover = files.filter((f) => outside(f) && f.tag === 'S' && present(ctx, f.path));
  const expanding = files.filter((f) => !outside(f) && f.tag === 'S');
  const removing = leaving.length > 0 || leftover.length > 0;

  const problems = safetyProblems(ctx, want ?? [], { checkRegion: removing, checkProcess: removing, skipProcessCheck: opts.skipProcessCheck });
  const roots = leavingRoots([...leaving, ...leftover], want ?? []);
  const reparse = reparseProblems(ctx, { leaving: [...leaving, ...leftover], expanding, roots });
  if (reparse.length) problems.push(`junction/symlink がある（参照先の追跡ファイルまで消えるおそれがあるため拒否）:\n    ${reparse.slice(0, 30).join('\n    ')}`);
  const collisions = expansionCollisions(ctx, expanding.map((f) => f.path));
  if (collisions.length) problems.push(`展開先に未追跡・ignore 済みの実体がある（上書き・削除しない。退避してから再実行）:\n    ${collisions.slice(0, 30).join('\n    ')}`);
  const stale = staleProbeNames(ctx);
  if (stale.length) {
    problems.push(
      `前回の使用中プローブの一時名が残っている（強制終了の残骸。勝手には消さない）:\n    ${stale.map((s) => `${s.tmp} → 元の名前 ${s.orig} へ戻す（Windows: Move-Item -LiteralPath "${s.tmp}" -Destination "${s.orig}" / Linux: mv "${s.tmp}" "${s.orig}"）`).join('\n    ')}`,
    );
  }
  if (problems.length) {
    ctx.error(`${sub} を中断する（何も変更していない）:`);
    for (const p of problems) ctx.error(`  - ${p}`);
    return 1;
  }
  if (removing && ctx.probe) {
    const probe = probeDirectories(ctx, roots);
    if (probe.fatal) {
      ctx.error(probe.fatal);
      return 1;
    }
    if (probe.busy.length) {
      ctx.error(`${sub} を中断する（何も変更していない）:`);
      ctx.error(`  - 使用中（rename できない）のため縮小できない。開いているファイル・プロセス・エディタを閉じて再実行する:\n    ${probe.busy.join('\n    ')}`);
      return 1;
    }
  }

  const args = kind === 'set' ? ['sparse-checkout', 'set', '--cone', '--stdin'] : kind === 'reapply' ? ['sparse-checkout', 'reapply'] : ['sparse-checkout', 'disable'];
  const r = ctx.git(args, kind === 'set' ? `${want.join('\n')}\n` : undefined);
  if (r.status !== 0) return failMidway(ctx, `git ${args.slice(0, 2).join(' ')}`, r, rerun);
  if (kind === 'set') {
    const after = sparseState(ctx, trackedFiles(ctx));
    if (!sameSet(after.list, want)) {
      return failMidway(ctx, '適用後の検証（sparse 一覧が期待と異なる）', { stderr: `期待: ${want.join(', ')} / 実際: ${after.list.join(', ')}` }, rerun);
    }
  }
  const verify = verifyFull(ctx, want, want === null ? new Set(expanding.map((f) => f.path)) : null);
  if (verify.length) return failVerify(ctx, sub, verify, rerun);
  return 0;
}

function cmdStatus(ctx) {
  const main = isMainWorktree(ctx);
  const files = trackedFiles(ctx);
  const cur = sparseState(ctx, files);
  const settings = readSettings(ctx);
  ctx.log(`worktree: ${ctx.top}${main ? '（本陣・メイン作業木）' : ''}`);
  ctx.log(`sparse: ${cur.sparse ? 'はい' : 'いいえ'}`);
  if (cur.sparse) ctx.log(`現在の sparse 一覧: ${cur.list.length ? cur.list.join(', ') : '(ルート直下のみ)'}`);
  if (settings.ok) {
    ctx.log(`HEAD の worktree.sparsePaths: ${settings.paths.join(', ')}`);
    if (cur.sparse) ctx.log(sameSet(cur.list, settings.paths) ? '一覧は設定と一致（古くない）' : '一覧が設定と異なる（古い可能性。reapply で更新、無関係なら --force-replace が必要）');
  } else {
    ctx.log(`設定が未投入または不正: ${settings.reason}`);
  }
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
  for (const s of staleProbeNames(ctx)) ctx.log(`要処置: 前回の使用中プローブの一時名が残っている: ${s.tmp} → 元の名前 ${s.orig} へ戻す（勝手には消さない）`);
  return 0;
}

function clearDigest(ctx) {
  ctx.git(['config', '--worktree', '--unset', DIGEST_KEY]); // 無ければ非0だが問題ない
}

function cmdExpand(ctx, opts) {
  const files = trackedFiles(ctx);
  const skipped = files.filter((f) => f.tag === 'S');
  const cur = sparseState(ctx, files);
  if (skipped.length === 0 && !cur.sparse) {
    ctx.log('既に全展開のため何もしない');
    return 0;
  }
  const sizes = headSizes(ctx);
  const blobs = skipped.reduce((sum, f) => sum + (sizes.get(f.path) ?? 0), 0);
  // autocrlf 等で checkout 後は blob より増える。保守的に blob×1.5 + ファイル数×4096 + 固定余裕
  const need = Math.ceil(blobs * 1.5) + skipped.length * 4096 + SPACE_MARGIN_BYTES;
  let free = null;
  try {
    free = ctx.freeBytes(ctx.top);
  } catch (e) {
    if (!opts.skipSpaceCheck) return fail(ctx, `空き容量を取得できないため何も変えず中断する（${e.message}）。確認済みなら --skip-space-check を付ける`);
  }
  ctx.log(`必要な容量(見積り): ${human(need)}（blob ${human(blobs)}×1.5 + ${skipped.length} ファイル×4KB + 余裕 ${human(SPACE_MARGIN_BYTES)}） / 空き容量: ${free === null ? '取得不能（--skip-space-check で続行）' : human(free)}`);
  if (free !== null && free < need) return fail(ctx, `空き容量が足りないため何も変えずに中断する（不足 ${human(need - free)}）`);
  const code = guardedApply(ctx, { sub: 'expand', kind: 'disable', want: null, opts, rerun: `${SCRIPT} expand` });
  if (code !== 0) return code;
  clearDigest(ctx);
  ctx.log('全展開した');
  return 0;
}

function cmdShrinkOrReapply(ctx, sub, opts) {
  if (isMainWorktree(ctx)) {
    return fail(ctx, `${sub} は本陣（メイン作業木）では実行しない。足軽の worktree で実行すること。何も変更していない。`);
  }
  const settings = readSettings(ctx);
  if (!settings.ok) return fail(ctx, `設定が未投入または不正のため ${sub} できない（何も変更していない）: ${settings.reason}`);
  const want = settings.paths;
  const files = trackedFiles(ctx);
  const cur = sparseState(ctx, files);
  const rerun = `${SCRIPT} ${sub}${opts.skipProcessCheck ? ' --skip-process-check' : ''}${opts.forceReplace ? ' --force-replace' : ''}`;

  if (sub === 'reapply' && !cur.sparse) return fail(ctx, 'この worktree は sparse ではないため reapply できない。縮小するなら shrink を使う。');

  let kind = 'set';
  if (cur.sparse && sameSet(cur.list, want)) {
    // 完了判定は前回との差分ではなく、常に現在の目標一覧に対する全体検証
    if (verifyFull(ctx, want).length === 0) {
      ctx.log('sparse 一覧は既に設定と一致しており、実状態も完了しているため何もしない');
      return 0;
    }
    ctx.log('一覧は一致しているが実状態が未完了のため、安全検査を通したうえで git sparse-checkout reapply で再適用する');
    kind = 'reapply';
  } else if (cur.sparse && !opts.forceReplace && !isOwnList(ctx, cur.list, want)) {
    return fail(
      ctx,
      `現在の sparse 一覧（${cur.list.length ? cur.list.join(', ') : 'ルート直下のみ'}）は、設定・settings.json の履歴・本コマンドの印のいずれにも当たらない無関係な一覧のため、上書きせず中断する（何も変更していない）。置き換えてよい場合のみ --force-replace を付ける。`,
    );
  }

  const code = guardedApply(ctx, { sub, kind, want, opts, rerun });
  if (code !== 0) return code;
  const mark = ctx.git(['config', '--worktree', DIGEST_KEY, digestOf(want)]);
  if (mark.status !== 0) return failMidway(ctx, '印（worktree ローカル設定）の記録', mark, rerun);
  ctx.log(`${sub} 完了: sparse 一覧 = ${want.join(', ')}`);
  return 0;
}

export async function run(argv, deps = {}) {
  const ctx = makeContext(deps);
  const [sub, ...rest] = argv;
  if (sub === '--help' || sub === '-h') {
    ctx.log(USAGE);
    return 0;
  }
  if (!['status', 'expand', 'shrink', 'reapply'].includes(sub)) {
    ctx.error(sub ? `未知のサブコマンド: ${sub}` : 'サブコマンドが無い');
    ctx.error(USAGE);
    return 1;
  }
  const opts = { skipProcessCheck: false, skipSpaceCheck: false, forceReplace: false };
  for (const a of rest) {
    if (a === '--skip-process-check') opts.skipProcessCheck = true;
    else if (a === '--skip-space-check') opts.skipSpaceCheck = true;
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
