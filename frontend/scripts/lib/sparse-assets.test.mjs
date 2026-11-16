// sparse checkout で素材が展開されていない worktree の判定モジュールの試験（CMP-261116-1101 部隊3）。
// 全て一時ディレクトリの小さな git リポジトリで行い、実リポジトリや global config は書き換えない。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import {
  classifyAssetDirs, runWithSparseOmission, requireExpandedForWrite, EXPAND_COMMAND,
} from './sparse-assets.mjs'

const TARGET = 'frontend/dev-fixtures/ranch'
const EXCLUSIONS = JSON.stringify({ exclusions: [
  { path: 'frontend/dev-fixtures/ranch', reason: 't' },
  { path: 'frontend/public/ranch/approved', reason: 't' },
] })
const BASE_ENV = { ...process.env }
for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_PREFIX']) delete BASE_ENV[key]

function git(cwd, ...args) {
  const run = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', '-c', 'commit.gpgsign=false',
    '-c', 'core.autocrlf=false', ...args], { cwd, encoding: 'utf8', env: BASE_ENV })
  assert.equal(run.status, 0, `git ${args.join(' ')} 失敗: ${run.stderr}`)
  return run.stdout
}
function makeRepo(files = ['a/one.png', 'a/two.png']) {
  const dir = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'sparse-assets-'))
  git(dir, 'init', '--quiet')
  fs.mkdirSync(path.join(dir, 'scripts'), { recursive: true })
  fs.writeFileSync(path.join(dir, 'scripts/worktree-sparse-exclusions.json'), EXCLUSIONS)
  for (const name of files) {
    const file = path.join(dir, TARGET, name)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, `bytes ${name}`)
  }
  git(dir, 'add', '.')
  git(dir, 'commit', '--quiet', '-m', 'init')
  return dir
}
const cleanup = dir => fs.rmSync(dir, { recursive: true, force: true })
function skipWorktree(dir, ...names) {
  for (const name of names) {
    git(dir, 'update-index', '--skip-worktree', `${TARGET}/${name}`)
    fs.rmSync(path.join(dir, TARGET, name))
  }
}
const quiet = { ...BASE_ENV, CI: '' }

test('全展開は present になり実バイト検査が走る', () => {
  const dir = makeRepo()
  try {
    assert.equal(classifyAssetDirs({ repoRoot: dir, targets: [TARGET] }).status, 'present')
  } finally { cleanup(dir) }
})

test('AC-10: 追跡パス全てが skip-worktree で欠落していれば omitted', () => {
  const dir = makeRepo()
  try {
    skipWorktree(dir, 'a/one.png', 'a/two.png')
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET] })
    assert.equal(result.status, 'omitted')
    assert.equal(result.omitted, 2)
  } finally { cleanup(dir) }
})

test('AC-10: 追跡パス0件は失敗', () => {
  const dir = makeRepo()
  try {
    const result = classifyAssetDirs({ repoRoot: dir, targets: ['frontend/nothing-here'] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'no-tracked-paths')
  } finally { cleanup(dir) }
})

test('AC-10: git の失敗（git リポジトリでない）は失敗', () => {
  const dir = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'sparse-assets-nogit-'))
  try {
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'git-failed')
  } finally { cleanup(dir) }
})

test('AC-10: 未マージ状態は失敗', () => {
  const dir = makeRepo()
  try {
    git(dir, 'checkout', '--quiet', '-b', 'other')
    fs.writeFileSync(path.join(dir, TARGET, 'a/one.png'), 'other side')
    git(dir, 'commit', '--quiet', '-am', 'other')
    git(dir, 'checkout', '--quiet', '-')
    fs.writeFileSync(path.join(dir, TARGET, 'a/one.png'), 'this side')
    git(dir, 'commit', '--quiet', '-am', 'this')
    const merge = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', 'merge', 'other'], { cwd: dir, encoding: 'utf8', env: BASE_ENV })
    assert.notEqual(merge.status, 0, 'コンフリクトを作れていない')
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'unmerged')
  } finally { cleanup(dir) }
})

test('AC-10: 部分展開（一部だけ在る）は失敗', () => {
  const dir = makeRepo()
  try {
    skipWorktree(dir, 'a/one.png')
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'partial')
  } finally { cleanup(dir) }
})

test('AC-10: 手で削除（skip-worktree でない欠落）は失敗', () => {
  const dir = makeRepo()
  try {
    fs.rmSync(path.join(dir, TARGET, 'a/one.png'))
    fs.rmSync(path.join(dir, TARGET, 'a/two.png'))
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'manual-deletion')
  } finally { cleanup(dir) }
})

test('AC-10: 除外正本に無いディレクトリの skip-worktree 欠落は「意図した除外」ではなく失敗', () => {
  const dir = makeRepo()
  try {
    skipWorktree(dir, 'a/one.png', 'a/two.png')
    fs.writeFileSync(path.join(dir, 'scripts/worktree-sparse-exclusions.json'), JSON.stringify({ exclusions: [{ path: 'elsewhere', reason: 't' }] }))
    const result = classifyAssetDirs({ repoRoot: dir, targets: [TARGET] })
    assert.equal(result.status, 'fail')
    assert.equal(result.code, 'not-excluded')
  } finally { cleanup(dir) }
})

test('AC-9: omitted でもメタデータ検査は先に走り、不正なら失敗する。実バイト検査は省略され理由が出る', async () => {
  const dir = makeRepo()
  try {
    skipWorktree(dir, 'a/one.png', 'a/two.png')
    const calls = []
    const logs = []
    const outcome = await runWithSparseOmission({ repoRoot: dir, targets: [TARGET], env: quiet, label: '試験',
      log: line => logs.push(line), metadata: () => calls.push('metadata'), bytes: () => calls.push('bytes') })
    assert.deepEqual(calls, ['metadata'])
    assert.equal(outcome.bytesVerified, false)
    assert.match(logs.join('\n'), /省略/)
    assert.ok(logs.join('\n').includes(TARGET))
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], env: quiet, label: '試験', log: () => {},
      metadata: () => { throw new Error('不正メタデータ') }, bytes: () => calls.push('bytes2') }), /不正メタデータ/)
    assert.ok(!calls.includes('bytes2'))
  } finally { cleanup(dir) }
})

test('AC-11: CI=true で素材が無ければ失敗（CI では省略しない）', async () => {
  const dir = makeRepo()
  try {
    skipWorktree(dir, 'a/one.png', 'a/two.png')
    for (const value of ['true', '1']) {
      await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], env: { ...BASE_ENV, CI: value }, label: '試験', log: () => {},
        metadata: () => {}, bytes: () => {} }), /CI/)
    }
  } finally { cleanup(dir) }
})

test('AC-11: 展開済みなら実バイト検査が走り、改竄の失敗がそのまま伝わる', async () => {
  const dir = makeRepo()
  try {
    fs.writeFileSync(path.join(dir, TARGET, 'a/one.png'), 'tampered')
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], env: quiet, label: '試験', log: () => {},
      metadata: () => {},
      bytes: () => { assert.equal(fs.readFileSync(path.join(dir, TARGET, 'a/one.png'), 'utf8'), 'bytes a/one.png', '改竄を検出') } }), /改竄/)
  } finally { cleanup(dir) }
})

test('失敗判定（partial など）では bytes を呼ばず失敗する', async () => {
  const dir = makeRepo()
  try {
    skipWorktree(dir, 'a/one.png')
    let called = false
    await assert.rejects(runWithSparseOmission({ repoRoot: dir, targets: [TARGET], env: quiet, label: '試験', log: () => {},
      metadata: () => {}, bytes: () => { called = true } }), /partial|部分/)
    assert.equal(called, false)
  } finally { cleanup(dir) }
})

test('AC-14: 書き込み前の展開要求。sparse なら expand の手順を出して失敗、全展開と git 管理外は通る', () => {
  const dir = makeRepo()
  try {
    assert.doesNotThrow(() => requireExpandedForWrite({ repoRoot: dir, targets: [TARGET] }))
    skipWorktree(dir, 'a/one.png', 'a/two.png')
    assert.throws(() => requireExpandedForWrite({ repoRoot: dir, targets: [TARGET] }), error => {
      assert.ok(error.message.includes(EXPAND_COMMAND))
      return true
    })
  } finally { cleanup(dir) }
  const plain = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'sparse-assets-plain-'))
  try {
    assert.doesNotThrow(() => requireExpandedForWrite({ repoRoot: plain, targets: [TARGET] }))
  } finally { cleanup(plain) }
})

test('AC-12: import しただけでは出力もプロセス終了もしない', () => {
  const run = spawnSync(process.execPath, ['--input-type=module', '-e',
    `await import(${JSON.stringify(new URL('./sparse-assets.mjs', import.meta.url).href)}); console.log('imported')`],
  { encoding: 'utf8' })
  assert.equal(run.status, 0)
  assert.equal(run.stdout.trim(), 'imported')
})
