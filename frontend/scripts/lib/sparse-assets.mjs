// sparse checkout された足軽 worktree で、牧場 PNG 素材（展開しない既定）の欠落が
// 「意図した sparse 除外」だけかを判定する共通モジュール（CMP-261116-1101 部隊3）。
//
// 設計の要点
//  - 実バイト（PNG の中身・hash・寸法）検査だけを省略してよい。メタデータ検査は常に走らせる（AC-9）。
//  - 省略してよいのは「追跡パスが1件以上あり、全てが skip-worktree で、1件も展開されておらず、
//    除外正本（scripts/worktree-sparse-exclusions.json）の配下」のときだけ（AC-10）。
//    追跡0件・git 失敗・未マージ・部分展開・手で削除・除外正本外は全て失敗。
//  - CI（CI=true / 1）では省略しない（AC-11）。
//  - import しただけではプロセスを終了しない・出力しない（AC-12）。
//
// このファイルは frontend/ 配下に置く。FE の Docker ビルドコンテキストが frontend/ であり、
// 成果物検証（verify-ranch-production-artifact.mjs）が import するため。
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

/** 展開手順（コマンド名は別 PR #4567 の worktree-assets.mjs。ここでは案内するだけ）。 */
export const EXPAND_COMMAND = 'node scripts/worktree-assets.mjs expand'
const EXCLUSIONS_FILE = 'scripts/worktree-sparse-exclusions.json'

/** リポジトリを cwd で決めるため、フック等から引き継いだ git の場所指定を外す。 */
function cleanEnv(env) {
  const next = { ...env }
  for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_PREFIX', 'GIT_COMMON_DIR']) delete next[key]
  return next
}

function runGit(repoRoot, args, env = process.env) {
  const run = spawnSync('git', ['--literal-pathspecs', '-c', 'core.quotepath=off', '-C', repoRoot, ...args],
    { encoding: 'buffer', env: cleanEnv(env), maxBuffer: 256 * 1024 * 1024, windowsHide: true })
  if (run.error) return { ok: false, error: `git を実行できない: ${run.error.code ?? run.error.message}` }
  if (run.status !== 0) return { ok: false, error: `git ${args.join(' ')} が終了コード ${run.status}: ${run.stderr.toString('utf8').trim()}` }
  return { ok: true, stdout: run.stdout }
}

const underAny = (file, dirs) => dirs.some(dir => file === dir || file.startsWith(`${dir}/`))

/** 除外正本の path 一覧。読めない・不正なら例外（黙って空にしない）。 */
export function loadExclusions(repoRoot) {
  const file = path.join(repoRoot, EXCLUSIONS_FILE)
  if (!fs.existsSync(file)) throw new Error(`sparse 除外の正本が無い: ${EXCLUSIONS_FILE}`)
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
  const rows = Array.isArray(parsed?.exclusions) ? parsed.exclusions : null
  if (!rows || rows.some(row => typeof row?.path !== 'string' || !row.path)) throw new Error(`sparse 除外の正本が不正: ${EXCLUSIONS_FILE}`)
  return rows.map(row => row.path)
}

const fail = (code, message, extra = {}) => ({ status: 'fail', code, message, ...extra })

/**
 * targets（リポジトリルートからの相対ディレクトリ）配下の追跡パスの状態を判定する。
 * status: 'present'（欠落なし）/ 'omitted'（全て意図した sparse 除外で未展開）/ 'fail'
 */
export function classifyAssetDirs({ repoRoot, targets, env = process.env }) {
  const listed = runGit(repoRoot, ['ls-files', '-t', '-z', '--', ...targets], env)
  if (!listed.ok) return fail('git-failed', listed.error)
  const unmerged = runGit(repoRoot, ['ls-files', '-u', '-z', '--', ...targets], env)
  if (!unmerged.ok) return fail('git-failed', unmerged.error)
  if (unmerged.stdout.length > 0) return fail('unmerged', '未マージ（コンフリクト）状態の追跡パスがある。先に解消すること')
  const tracked = listed.stdout.toString('utf8').split('\0').filter(Boolean).map(record => ({ tag: record[0], file: record.slice(2) }))
  if (tracked.length === 0) return fail('no-tracked-paths', `対象の追跡パスが0件（パス誤り・改名の疑い）: ${targets.join(', ')}`)
  const missing = tracked.filter(row => !fs.lstatSync(path.join(repoRoot, row.file), { throwIfNoEntry: false }))
  const base = { tracked: tracked.length, omitted: missing.length, targets }
  if (missing.length === 0) return { status: 'present', ...base }
  const manual = missing.filter(row => row.tag !== 'S')
  if (manual.length > 0) {
    return fail('manual-deletion', `skip-worktree でない欠落（手で削除された疑い）が${manual.length}件: 例 ${manual[0].file}`, base)
  }
  if (missing.length !== tracked.length) {
    return fail('partial', `部分展開（${tracked.length}件中${tracked.length - missing.length}件だけ存在）。展開し直すか全て除外すること`, base)
  }
  const exclusions = loadExclusions(repoRoot)
  const outside = missing.filter(row => !underAny(row.file, exclusions))
  if (outside.length > 0) {
    return fail('not-excluded', `skip-worktree だが除外正本（${EXCLUSIONS_FILE}）に無いパス: 例 ${outside[0].file}`, base)
  }
  return { status: 'omitted', ...base }
}

const isCi = env => env.CI === 'true' || env.CI === '1'

/**
 * メタデータ検査は常に実行し、実バイト検査は sparse 除外のときだけ省略する。
 * 省略時は理由と対象を log に出す。CI・失敗判定では省略せず例外にする。
 */
export async function runWithSparseOmission({ repoRoot, targets, env = process.env, label, metadata, bytes, log = console.log }) {
  await metadata()
  const state = classifyAssetDirs({ repoRoot, targets, env })
  if (state.status === 'present') {
    await bytes()
    return { bytesVerified: true, state }
  }
  if (state.status === 'fail') {
    throw new Error(`${label}: 素材の状態を安全に判定できない（${state.code}）: ${state.message}`)
  }
  if (isCi(env)) {
    throw new Error(`${label}: CI では実バイト検査を省略しない。素材が展開されていない（対象 ${targets.join(', ')}、${state.omitted}件）`)
  }
  log(`${label}: メタデータ検査は完了。実バイト検査（PNG の中身・hash・寸法）は省略 — sparse checkout により未展開`
    + `（対象 ${targets.join(', ')}、追跡 ${state.tracked} 件全て skip-worktree）。実バイト検査が必要なら先に ${EXPAND_COMMAND}`)
  return { bytesVerified: false, state }
}

/**
 * 素材ディレクトリへ書き込む処理の前に呼ぶ。sparse で未展開なら何も書かずに例外（展開手順つき）。
 * git 管理外のディレクトリは対象外（従来どおり通す）。
 */
export function requireExpandedForWrite({ repoRoot, targets, env = process.env }) {
  const top = runGit(repoRoot, ['rev-parse', '--show-toplevel'], env)
  if (!top.ok) return
  if (path.relative(fs.realpathSync(top.stdout.toString('utf8').trim()), fs.realpathSync(repoRoot)) !== '') return
  const listed = runGit(repoRoot, ['ls-files', '-t', '-z', '--', ...targets], env)
  if (!listed.ok) throw new Error(`素材の展開状態を確認できないため書き込まない: ${listed.error}`)
  const skipped = listed.stdout.toString('utf8').split('\0').filter(Boolean).filter(record => record[0] === 'S')
  if (skipped.length > 0) {
    throw new Error(`${targets.join(', ')} は sparse checkout で未展開（skip-worktree ${skipped.length} 件）。`
      + `何も書き込まずに中止した。先に素材を展開すること: ${EXPAND_COMMAND}`)
  }
}

/**
 * 未展開の成果物素材を「配布可能」と検証しないための前提確認。CI の有無を問わず、sparse 未展開なら例外。
 * git が使えない環境（Docker ビルドコンテキスト等）は判定できないので素通しし、後続の実ファイル読み込みの失敗に任せる。
 */
export function requireExpandedForArtifact({ repoRoot, targets, env = process.env }) {
  requireExpandedForWrite({ repoRoot, targets, env })
}
