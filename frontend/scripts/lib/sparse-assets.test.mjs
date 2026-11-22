// sparse checkout で素材が展開されていない worktree の判定モジュールの試験（CMP-261116-1101 部隊3）。
// 全て一時ディレクトリの小さな git リポジトリで行い、実リポジトリや global config は書き換えない。
// 「意図した sparse 除外」は本物の cone sparse checkout（git sparse-checkout set --cone）で作る。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import {
  classifyAssetDirs, runWithSparseOmission, requireExpandedForWrite, isCiEnvironment, inCone, EXPAND_COMMAND,
} from './sparse-assets.mjs'

const TARGET = 'frontend/dev-fixtures/ranch'
const FILES = [`${TARGET}/x/one.png`, `${TARGET}/y/two.png`]
const OTHER = 'frontend/dev-fixtures/other/zzz.png'
const EXCLUSIONS = JSON.stringify({ exclusions: [
  { path: 'frontend/dev-fixtures/ranch', reason: 't' },
  { path: 'frontend/dev-fixtures/other', reason: 't' },
] })
const BASE_ENV = { ...process.env }
for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_PREFIX']) delete BASE_ENV[key]
for (const key of ['CI', 'GITHUB_ACTIONS', 'GITLAB_CI', 'CIRCLECI', 'TRAVIS', 'BUILDKITE', 'TF_BUILD', 'APPVEYOR', 'JENKINS_URL', 'TEAMCITY_VERSION']) delete BASE_ENV[key]
const quiet = { ...BASE_ENV }

function git(cwd, ...args) {
  const run = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', '-c', 'commit.gpgsign=false',
    '-c', 'core.autocrlf=false', ...args], { cwd, encoding: 'utf8', env: BASE_ENV })
  assert.equal(run.status, 0, `git ${args.join(' ')} 失敗: ${run.stderr}`)
  return run.stdout
}
// Git管理外を想定するfixtureだけ、祖先リポジトリの探索を止める。
const outsideEnv = dir => ({ ...quiet, GIT_CEILING_DIRECTORIES: path.dirname(fs.realpathSync(dir)) })

// .git を作って消す途中の残留に依存せず、検証コピーを初めからGit管理外として作る。
function makePlainCopy({ withExclusions = true, parent = fs.realpathSync(os.tmpdir()) } = {}) {
  const dir = fs.mkdtempSync(path.join(parent, 'sparse-assets-'))
  fs.mkdirSync(path.join(dir, 'scripts'), { recursive: true })
  if (withExclusions) fs.writeFileSync(path.join(dir, 'scripts/worktree-sparse-exclusions.json'), EXCLUSIONS)
  for (const name of [...FILES, OTHER]) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true })
    fs.writeFileSync(path.join(dir, name), `bytes ${name}`)
  }
  return dir
}
function makeRepo({ withExclusions = true } = {}) {
  const dir = makePlainCopy({ withExclusions })
  git(dir, 'init', '--quiet')
  git(dir, 'add', '.')
  git(dir, 'commit', '--quiet', '-m', 'init')
  return dir
}
const cleanup = dir => fs.rmSync(dir, { recursive: true, force: true })
/** 本物の cone sparse checkout にする（out-of-cone は skip-worktree になり作業木から消える）。 */
const sparsify = (dir, ...keep) => git(dir, 'sparse-checkout', 'set', '--cone', ...keep)
/** sparse を使わず skip-worktree を手で立てて消す（偽の「除外」）。 */
function fakeSkip(dir, files) {
  for (const name of files) {
    git(dir, 'update-index', '--skip-worktree', name)
    fs.rmSync(path.join(dir, name))
  }
}
const classify = (dir, extra = {}) => classifyAssetDirs({ repoRoot: dir, targets: [TARGET], env: quiet, ...extra })

test('全展開は present になり実バイト検査が走る', () => {
  const dir = makeRepo()
  try {
    assert.equal(classify(dir, { expected: FILES }).status, 'present')
  } finally { cleanup(dir) }
})

test('AC-10: 本物の cone sparse で全て範囲外なら omitted', () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts')
    assert.ok(!fs.existsSync(path.join(dir, FILES[0])), '本物の sparse で消えている')
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'omitted')
    assert.equal(result.omitted, 2)
  } finally { cleanup(dir) }
})

test('AC-10: 追跡パス0件は失敗', () => {
  const dir = makeRepo()
  try {
    const result = classifyAssetDirs({ repoRoot: dir, targets: ['frontend/nothing-here'], env: quiet })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'no-tracked-paths')
  } finally { cleanup(dir) }
})

test('AC-10: .git があるのに git が失敗する（壊れたリポジトリ）は失敗', () => {
  const dir = makeRepo()
  try {
    fs.rmSync(path.join(dir, '.git/HEAD'))
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'git-failed')
  } finally { cleanup(dir) }
})

test('AC-10: 未マージ状態は失敗', () => {
  const dir = makeRepo()
  try {
    git(dir, 'checkout', '--quiet', '-b', 'other')
    fs.writeFileSync(path.join(dir, FILES[0]), 'other side')
    git(dir, 'commit', '--quiet', '-am', 'other')
    git(dir, 'checkout', '--quiet', '-')
    fs.writeFileSync(path.join(dir, FILES[0]), 'this side')
    git(dir, 'commit', '--quiet', '-am', 'this')
    const merge = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', 'merge', 'other'], { cwd: dir, encoding: 'utf8', env: BASE_ENV })
    assert.notEqual(merge.status, 0, 'コンフリクトを作れていない')
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'unmerged')
  } finally { cleanup(dir) }
})

test('AC-10: 部分展開（一部だけ在る）は失敗', () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts', `${TARGET}/x`)
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'partial')
  } finally { cleanup(dir) }
})

test('AC-10: 手で削除（skip-worktree でない欠落）は失敗', () => {
  const dir = makeRepo()
  try {
    for (const name of FILES) fs.rmSync(path.join(dir, name))
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'manual-deletion')
  } finally { cleanup(dir) }
})

test('AC-10: 除外正本に無いディレクトリの sparse 欠落は「意図した除外」ではなく失敗', () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts')
    fs.writeFileSync(path.join(dir, 'scripts/worktree-sparse-exclusions.json'), JSON.stringify({ exclusions: [{ path: 'elsewhere', reason: 't' }] }))
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'not-excluded')
  } finally { cleanup(dir) }
})

test('指摘2: sparse checkout が無効のまま skip-worktree を手で立てて消しても omitted にならない', () => {
  const dir = makeRepo()
  try {
    fakeSkip(dir, FILES)
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'sparse-not-enabled')
  } finally { cleanup(dir) }
})

test('指摘2: sparse が有効でも、範囲内（cone に含まれる）パスを手で skip-worktree にして消したら失敗', () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts', TARGET)
    fakeSkip(dir, FILES)
    const result = classify(dir, { expected: FILES })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'in-cone')
  } finally { cleanup(dir) }
})

test('指摘1: 期待パスが追跡されていなければ、別の素材が全て skip-worktree でも失敗（削除・改名を隠せない）', () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts')
    const result = classify(dir, { expected: [...FILES, `${TARGET}/x/renamed-away.png`] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'expected-not-tracked')
  } finally { cleanup(dir) }
})

test('指摘1: 検証対象ディレクトリが追跡0件なら、隣の素材が追跡・未展開でも失敗', () => {
  const dir = makeRepo()
  try {
    git(dir, 'rm', '-r', '--quiet', TARGET)
    git(dir, 'commit', '--quiet', '-m', 'rm')
    sparsify(dir, 'scripts')
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'no-tracked-paths')
  } finally { cleanup(dir) }
})

test('指摘5: 期待一覧に無い余分な追跡ファイルは、省略前に失敗する（配布物一覧の過剰を隠さない）', () => {
  const dir = makeRepo()
  try {
    fs.writeFileSync(path.join(dir, TARGET, 'x/unexpected.png'), 'extra')
    git(dir, 'add', '.')
    git(dir, 'commit', '--quiet', '-m', 'extra')
    sparsify(dir, 'scripts')
    const result = classify(dir, { expected: FILES, exact: true })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'unexpected-tracked')
    assert.equal(classify(dir, { expected: FILES }).status, 'omitted', 'exact 指定なしは従来どおり（gitleaks の manifest 取得用）')
    assert.match(result.message, /unexpected.png/)
  } finally { cleanup(dir) }
})

test('指摘6: regularFilesOnly なら symlink(120000)・gitlink(160000) として追跡された期待パスは省略前に失敗する', () => {
  const dir = makeRepo()
  try {
    const blob = spawnSync('git', ['hash-object', '-w', '--stdin'], { cwd: dir, input: 'target', encoding: 'utf8', env: BASE_ENV }).stdout.trim()
    git(dir, 'update-index', '--cacheinfo', `120000,${blob},${FILES[0]}`)
    git(dir, 'commit', '--quiet', '-m', 'symlink')
    fs.rmSync(path.join(dir, FILES[0]))
    sparsify(dir, 'scripts')
    const result = classify(dir, { expected: FILES, exact: true, regularFilesOnly: true })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'non-regular-tracked')
    assert.equal(classify(dir, { expected: FILES, exact: true }).status, 'omitted', '指定なし（clips）は従来どおり')
  } finally { cleanup(dir) }
})

test('inCone: ルート直下・祖先直下・一覧配下は範囲内、それ以外は範囲外', () => {
  const list = ['frontend/scripts', 'scripts']
  assert.equal(inCone('README.md', list), true)
  assert.equal(inCone('frontend/package.json', list), true)
  assert.equal(inCone('frontend/scripts/a/b.mjs', list), true)
  assert.equal(inCone('frontend/dev-fixtures/ranch/x.png', list), false)
  assert.equal(inCone('frontend/dev-fixtures/x.png', list), false)
})

test('指摘3: CI 判定は CI 変数だけでなく提供元の変数でも立つ（GITHUB_ACTIONS=true と CI=false/空の組合せを含む）', () => {
  assert.equal(isCiEnvironment({}), false)
  assert.equal(isCiEnvironment({ CI: 'true' }), true)
  assert.equal(isCiEnvironment({ CI: '1' }), true)
  assert.equal(isCiEnvironment({ GITHUB_ACTIONS: 'true', CI: 'false' }), true)
  assert.equal(isCiEnvironment({ GITHUB_ACTIONS: 'true', CI: '' }), true)
  assert.equal(isCiEnvironment({ TF_BUILD: 'True' }), true)
  assert.equal(isCiEnvironment({ GITHUB_ACTIONS: 'false', CI: 'false' }), false)
})

test('AC-9: omitted でもメタデータ検査は先に走り、不正なら失敗する。実バイト検査は省略され理由が出る', async () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts')
    const calls = []
    const logs = []
    const outcome = await runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet, label: '試験',
      log: line => logs.push(line), metadata: () => calls.push('metadata'), bytes: () => calls.push('bytes') })
    assert.deepEqual(calls, ['metadata'])
    assert.equal(outcome.bytesVerified, false)
    assert.match(logs.join('\n'), /省略/)
    assert.ok(logs.join('\n').includes(TARGET))
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet, label: '試験', log: () => {},
      metadata: () => { throw new Error('不正メタデータ') }, bytes: () => calls.push('bytes2') }), /不正メタデータ/)
    assert.ok(!calls.includes('bytes2'))
  } finally { cleanup(dir) }
})

test('AC-11/指摘3: CI では省略しない（CI=true・1、GITHUB_ACTIONS=true かつ CI=false/空 のいずれも失敗）', async () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts')
    for (const extra of [{ CI: 'true' }, { CI: '1' }, { GITHUB_ACTIONS: 'true', CI: 'false' }, { GITHUB_ACTIONS: 'true', CI: '' }]) {
      await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: { ...quiet, ...extra }, label: '試験', log: () => {},
        metadata: () => {}, bytes: () => {} }), /CI/, JSON.stringify(extra))
    }
  } finally { cleanup(dir) }
})

test('AC-11: 展開済みなら実バイト検査が走り、改竄の失敗がそのまま伝わる', async () => {
  const dir = makeRepo()
  try {
    fs.writeFileSync(path.join(dir, FILES[0]), 'tampered')
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet, label: '試験', log: () => {},
      metadata: () => {},
      bytes: () => { assert.equal(fs.readFileSync(path.join(dir, FILES[0]), 'utf8'), `bytes ${FILES[0]}`, '改竄を検出') } }), /改竄/)
  } finally { cleanup(dir) }
})

test('失敗判定（partial など）では bytes を呼ばず失敗する', async () => {
  const dir = makeRepo()
  try {
    sparsify(dir, 'scripts', `${TARGET}/x`)
    let called = false
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet, label: '試験', log: () => {},
      metadata: () => {}, bytes: () => { called = true } }), /partial/)
    assert.equal(called, false)
  } finally { cleanup(dir) }
})

test('指摘4: git リポジトリの外（.git 無しの検証コピー）で素材が全て在れば従来どおり全検査を走らせる', async () => {
  const dir = makePlainCopy()
  try {
    let called = false
    const outcome = await runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: outsideEnv(dir), label: '試験', log: () => {},
      metadata: () => {}, bytes: () => { called = true } })
    assert.equal(called, true)
    assert.equal(outcome.bytesVerified, true)
  } finally { cleanup(dir) }
})

test('指摘4: git リポジトリの外で1つでも欠ければ省略せず失敗（bytes は呼ばない）', async () => {
  const dir = makePlainCopy()
  try {
    fs.rmSync(path.join(dir, FILES[1]))
    let called = false
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: outsideEnv(dir), label: '試験', log: () => {},
      metadata: () => {}, bytes: () => { called = true } }), /missing-outside-git/)
    assert.equal(called, false)
  } finally { cleanup(dir) }
})

test('指摘4: 除外正本が無いコピーでも、素材が揃っていれば検査は走り、未展開なら省略せず失敗', async () => {
  const dir = makeRepo({ withExclusions: false })
  try {
    let called = false
    await runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet, label: '試験', log: () => {},
      metadata: () => {}, bytes: () => { called = true } })
    assert.equal(called, true)
    sparsify(dir, 'scripts')
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: quiet, label: '試験', log: () => {},
      metadata: () => {}, bytes: () => {} }), /exclusions-unreadable/)
  } finally { cleanup(dir) }
})

test('AC-14: 書き込み前の展開要求。sparse なら expand の手順を出して失敗、全展開と git 管理外は通る、壊れた git は失敗', () => {
  const dir = makeRepo()
  try {
    assert.doesNotThrow(() => requireExpandedForWrite({ repoRoot: dir, targets: [TARGET], env: quiet }))
    sparsify(dir, 'scripts')
    assert.throws(() => requireExpandedForWrite({ repoRoot: dir, targets: [TARGET], env: quiet }), error => {
      assert.ok(error.message.includes(EXPAND_COMMAND))
      return true
    })
    fs.rmSync(path.join(dir, '.git/HEAD'))
    assert.throws(() => requireExpandedForWrite({ repoRoot: dir, targets: [TARGET], env: quiet }), /git が失敗/)
  } finally { cleanup(dir) }
  const plain = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'sparse-assets-plain-'))
  try {
    assert.doesNotThrow(() => requireExpandedForWrite({ repoRoot: plain, targets: [TARGET], env: outsideEnv(plain) }))
  } finally { cleanup(plain) }
})

test('C19: 祖先 core.worktree が子を指すGit管理外コピーで欠損を拒否する', async () => {
  const parent = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'c19-parent-'))
  try {
    git(parent, 'init', '--quiet')
    const dir = makePlainCopy({ parent })
    git(parent, 'config', '--local', 'core.worktree', fs.realpathSync(dir))
    fs.rmSync(path.join(dir, FILES[1]))
    let called = false
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], expected: FILES, env: outsideEnv(dir), label: '試験', log: () => {},
      metadata: () => {}, bytes: () => { called = true } }), /missing-outside-git/)
    assert.equal(called, false)
  } finally { cleanup(parent) }
})

test('AC-12: import しただけでは出力もプロセス終了もしない', () => {
  const run = spawnSync(process.execPath, ['--input-type=module', '-e',
    `await import(${JSON.stringify(new URL('./sparse-assets.mjs', import.meta.url).href)}); console.log('imported')`],
  { encoding: 'utf8' })
  assert.equal(run.status, 0)
  assert.equal(run.stdout.trim(), 'imported')
})
