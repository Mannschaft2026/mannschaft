#!/usr/bin/env node
/**
 * 足軽 worktree の worktree.sparsePaths（git sparse-checkout cone モード）を除外正本から生成・検査する。
 * 除外の正本: scripts/worktree-sparse-exclusions.json
 *   --print  生成結果を1行1パスで出力
 *   --check  .claude/settings.json の worktree.sparsePaths と一致・被覆を検査（未設定は成功）
 *   --write  .claude/settings.json に書き込む
 *   --ref <ref>  追跡一覧を読む ref（既定 HEAD。作業木の実在に依存しない）
 */
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const EXCLUSIONS_FILE = resolve(HERE, 'worktree-sparse-exclusions.json');
const SETTINGS_FILE = resolve(HERE, '..', '.claude', 'settings.json');

const parentOf = (p) => (p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '');
const isUnder = (p, dir) => p === dir || p.startsWith(`${dir}/`);

/** 除外定義の検査。不正なら Error。 */
export function validateExclusions(exclusions) {
  if (!Array.isArray(exclusions)) throw new Error('除外定義は配列でなければならない');
  const seen = new Set();
  for (const e of exclusions) {
    if (typeof e !== 'string' || e === '') throw new Error(`除外パスが空または文字列でない: ${JSON.stringify(e)}`);
    if (e.includes(String.fromCharCode(92))) throw new Error(`除外パスにバックスラッシュは使えない: ${e}`);
    if (e.startsWith('/') || e.endsWith('/')) throw new Error(`除外パスの先頭・末尾にスラッシュは使えない: ${e}`);
    const segs = e.split('/');
    if (segs.some((s) => s === '' || s === '.' || s === '..')) throw new Error(`除外パスに空・.・.. の要素は使えない: ${e}`);
    if (seen.has(e)) throw new Error(`除外パスが重複している: ${e}`);
    seen.add(e);
  }
  for (const a of seen) {
    for (const b of seen) {
      if (a !== b && b.startsWith(`${a}/`)) throw new Error(`除外パスが別の除外パスの祖先・子孫になっている: ${a} / ${b}`);
    }
  }
}

/** trackedDirs（追跡ディレクトリ一覧）と除外から cone 用パスのソート済み配列を返す。 */
export function computeSparsePaths(trackedDirs, exclusions) {
  validateExclusions(exclusions);
  const dirs = new Set(trackedDirs);
  for (const e of exclusions) {
    if (!dirs.has(e)) throw new Error(`除外ディレクトリが追跡一覧に存在しない（改名・削除？）: ${e}`);
  }
  const excluded = new Set(exclusions);
  const ancestors = new Set();
  for (const e of exclusions) for (let p = parentOf(e); p; p = parentOf(p)) ancestors.add(p);
  const out = [];
  for (const d of dirs) {
    if (excluded.has(d) || ancestors.has(d)) continue;
    const parent = parentOf(d);
    if (parent === '' || ancestors.has(parent)) out.push(d);
  }
  return out.sort();
}

/**
 * 展開されない追跡ファイルを返す。cone が自動展開するのはルート直下と、
 * sparsePaths に指定したディレクトリの祖先ディレクトリ直下のみ（除外パスは git に渡さないので祖先扱いしない）。
 */
export function findUncoveredPaths(trackedFiles, sparsePaths, exclusions) {
  const autoDirs = new Set(['']);
  for (const r of sparsePaths) for (let p = parentOf(r); p; p = parentOf(p)) autoDirs.add(p);
  return trackedFiles.filter((f) => {
    if (sparsePaths.some((r) => isUnder(f, r))) return false;
    if (exclusions.some((r) => isUnder(f, r))) return false;
    return !autoDirs.has(parentOf(f));
  });
}

const LOOKALIKE = /^sparse[-_ ]?paths?$/i;

/** settings.json の worktree.sparsePaths を検査し、期待値と集合比較（順序は無視）。status: unset / ok / mismatch / invalid */
export function compareWithSettings(settings, expected) {
  const invalid = (...problems) => ({ status: 'invalid', missing: [], extra: [], problems });
  if (settings === null || typeof settings !== 'object' || Array.isArray(settings)) return invalid('settings.json の最上位がオブジェクトでない');
  if (!('worktree' in settings)) return { status: 'unset', missing: [], extra: [] };
  const wt = settings.worktree;
  if (wt === null || typeof wt !== 'object' || Array.isArray(wt)) return invalid('worktree がオブジェクトでない');
  if (!('sparsePaths' in wt)) {
    const like = Object.keys(wt).filter((k) => LOOKALIKE.test(k));
    if (like.length) return invalid(`sparsePaths の誤記と思われるキーがある: ${like.join(', ')}`);
    return { status: 'unset', missing: [], extra: [] };
  }
  const cur = wt.sparsePaths;
  if (!Array.isArray(cur)) return invalid('sparsePaths が配列でない');
  if (cur.length === 0) return invalid('sparsePaths が空配列');
  if (cur.some((p) => typeof p !== 'string' || p === '')) return invalid('sparsePaths に空でない文字列以外の要素がある');
  if (new Set(cur).size !== cur.length) return invalid('sparsePaths に重複がある');
  const curSet = new Set(cur);
  const expSet = new Set(expected);
  const missing = expected.filter((p) => !curSet.has(p));
  const extra = cur.filter((p) => !expSet.has(p));
  return { status: missing.length || extra.length ? 'mismatch' : 'ok', missing, extra };
}

function git(args) {
  const r = spawnSync('git', args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, cwd: HERE });
  if (r.error) throw new Error(`git を実行できない: ${r.error.message}`);
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} が失敗 (${r.status}): ${r.stderr.trim()}`);
  return r.stdout.split('\0').filter(Boolean);
}

export function loadExclusions() {
  const j = JSON.parse(readFileSync(EXCLUSIONS_FILE, 'utf8'));
  return j.exclusions.map((e) => e.path);
}

const CONE_HINT = 'cone モードではこの場所の直下ファイルを展開できない。ファイルを別のディレクトリへ移すか、除外の単位を見直すこと';

function reportUncovered(uncovered, label) {
  console.error(`${label}: sparse で展開されない追跡ファイルがある（${uncovered.length} 件）。${CONE_HINT}`);
  for (const p of uncovered.slice(0, 20)) console.error(`  未被覆: ${p}`);
}

function main(argv) {
  const mode = ['--print', '--check', '--write'].find((m) => argv.includes(m));
  if (!mode) throw new Error('--print / --check / --write のいずれかを指定すること');
  const ri = argv.indexOf('--ref');
  const ref = ri >= 0 ? argv[ri + 1] : 'HEAD';
  if (!ref) throw new Error('--ref に値が必要');
  const exclusions = loadExclusions();
  const dirs = git(['ls-tree', '--full-tree', '-r', '-d', '--name-only', '-z', ref]);
  const files = git(['ls-tree', '--full-tree', '-r', '--name-only', '-z', ref]);
  const expected = computeSparsePaths(dirs, exclusions);
  // 設定の有無に関わらず、生成結果そのものが取りこぼすファイルを検査する
  const genUncovered = findUncoveredPaths(files, expected, exclusions);
  if (mode === '--print') {
    console.log(expected.join('\n'));
    if (genUncovered.length) reportUncovered(genUncovered, '生成結果');
    return genUncovered.length ? 1 : 0;
  }
  if (genUncovered.length) {
    reportUncovered(genUncovered, '生成結果');
    return 1;
  }
  const settings = JSON.parse(readFileSync(SETTINGS_FILE, 'utf8'));
  if (mode === '--write') {
    settings.worktree = { ...(settings.worktree ?? {}), sparsePaths: expected };
    writeFileSync(SETTINGS_FILE, `${JSON.stringify(settings, null, 2)}\n`);
    console.log(`.claude/settings.json に ${expected.length} 件を書き込んだ`);
    return 0;
  }
  const cmp = compareWithSettings(settings, expected);
  if (cmp.status === 'unset') {
    console.log(`worktree.sparsePaths は未設定（有効化前）。生成結果の未被覆は 0 件（${expected.length} 件）`);
    return 0;
  }
  if (cmp.status === 'invalid') {
    for (const p of cmp.problems) console.error(`settings.json が不正: ${p}`);
    return 1;
  }
  let bad = false;
  if (cmp.status === 'mismatch') {
    bad = true;
    console.error('worktree.sparsePaths が生成結果と一致しない。`node scripts/worktree-sparse-paths.mjs --write` で更新すること');
    for (const p of cmp.missing) console.error(`  不足: ${p}`);
    for (const p of cmp.extra) console.error(`  余剰: ${p}`);
  }
  if (!bad) console.log(`worktree.sparsePaths は生成結果と一致（${expected.length} 件、未被覆 0）`);
  return bad ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (e) {
    console.error(`エラー: ${e.message}`);
    process.exitCode = 2;
  }
}
