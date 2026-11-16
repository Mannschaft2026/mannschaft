// 牧場素材の検証スクリプトが sparse checkout（素材未展開）で正しく振る舞うことの試験（CMP-261116-1101 部隊3）。
// 本物のスクリプトと本物のメタデータ manifest を一時 git リポジトリへ複製して子プロセスで実行する。
// 素材PNGは合成の小さなダミーに置き換える。実リポジトリ・global config は書き換えない。
import test, { before, after } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { EXPAND_COMMAND } from './lib/sparse-assets.mjs'
import { readApprovedManifestBytes } from './verify-ranch-public-identity-gitleaks.mjs'
import { requireProductionPackPresent } from './verify-ranch-production-artifact.mjs'
import { installVerifiedPack } from './verify-ranch-production-assets.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendSource = path.resolve(here, '..')
const repoSource = path.resolve(frontendSource, '..')
const CLEAN_ENV = { ...process.env, CI: '' }
for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_PREFIX']) delete CLEAN_ENV[key]
const MANIFEST = 'frontend/public/ranch/approved/ranch-approved-20261108-v1/manifest.json'
const ASSET_FILES = [
  'frontend/dev-fixtures/ranch/base16-v3/a.png',
  'frontend/dev-fixtures/ranch/appearance64-v4/b.png',
  MANIFEST,
  'frontend/public/ranch/approved/ranch-approved-20261108-v1/c.png',
]
const MANIFEST_BODY = '{"entries":[{"assetKey":"synthetic-public-id"}]}'

function git(cwd, ...args) {
  const run = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', '-c', 'commit.gpgsign=false',
    '-c', 'core.autocrlf=false', '-c', 'core.hooksPath=', ...args], { cwd, encoding: 'utf8', env: CLEAN_ENV })
  assert.equal(run.status, 0, `git ${args.join(' ')}: ${run.stderr}`)
  return run.stdout
}
const realTemp = fs.realpathSync(os.tmpdir())
let template
before(() => {
  template = fs.mkdtempSync(path.join(realTemp, 'ranch-sparse-template-'))
  fs.mkdirSync(path.join(template, 'scripts'), { recursive: true })
  fs.copyFileSync(path.join(repoSource, 'scripts/worktree-sparse-exclusions.json'), path.join(template, 'scripts/worktree-sparse-exclusions.json'))
  fs.cpSync(path.join(frontendSource, 'scripts'), path.join(template, 'frontend/scripts'), { recursive: true,
    filter: source => !source.endsWith('.test.mjs') })
  fs.mkdirSync(path.join(template, 'frontend/app/utils'), { recursive: true })
  for (const name of ['ranch-development-clips-manifest.json', 'ranch-development-appearances-manifest.json', 'ranch-production-pack-registration.json']) {
    fs.copyFileSync(path.join(frontendSource, 'app/utils', name), path.join(template, 'frontend/app/utils', name))
  }
  for (const file of ASSET_FILES) {
    fs.mkdirSync(path.dirname(path.join(template, file)), { recursive: true })
    fs.writeFileSync(path.join(template, file), file === MANIFEST ? MANIFEST_BODY : `synthetic ${file}`)
  }
  git(template, 'init', '--quiet')
  git(template, 'add', '.')
  git(template, 'commit', '--quiet', '-m', 'template')
})
after(() => fs.rmSync(template, { recursive: true, force: true }))

const made = []
function copyRepo() {
  const dir = fs.mkdtempSync(path.join(realTemp, 'ranch-sparse-case-'))
  fs.cpSync(template, dir, { recursive: true })
  made.push(dir)
  return dir
}
after(() => { for (const dir of made) fs.rmSync(dir, { recursive: true, force: true }) })
/** sparse 除外を再現（skip-worktree を立ててファイルを消す）。 */
function omit(dir, files) {
  for (const file of files) {
    git(dir, 'update-index', '--skip-worktree', file)
    fs.rmSync(path.join(dir, file))
  }
}
const devFiles = ASSET_FILES.filter(file => file.includes('dev-fixtures'))
const approvedFiles = ASSET_FILES.filter(file => file.includes('public/ranch/approved'))
function run(dir, script, args = [], env = {}) {
  return spawnSync(process.execPath, [path.join(dir, 'frontend/scripts', script), ...args],
    { cwd: path.join(dir, 'frontend'), encoding: 'utf8', env: { ...CLEAN_ENV, ...env } })
}
const out = result => `${result.stdout}\n${result.stderr}`

for (const script of ['verify-ranch-development-clips.mjs', 'verify-ranch-development-appearances.mjs']) {
  test(`AC-9/10 ${script}: sparse 除外ならメタデータ検査は通って実バイト省略（理由・対象つき・exit 0）`, () => {
    const dir = copyRepo()
    omit(dir, devFiles)
    const result = run(dir, script)
    assert.equal(result.status, 0, out(result))
    assert.match(out(result), /省略/)
    assert.ok(out(result).includes('frontend/dev-fixtures/ranch'), '対象を表示')
  })

  test(`AC-9 ${script}: sparse でも不正メタデータは非0（省略に逃げない）`, () => {
    const dir = copyRepo()
    omit(dir, devFiles)
    const file = path.join(dir, 'frontend/app/utils', script.includes('clips') ? 'ranch-development-clips-manifest.json' : 'ranch-development-appearances-manifest.json')
    const manifest = JSON.parse(fs.readFileSync(file, 'utf8'))
    manifest.entries[0].habitat = manifest.entries[0].habitat === 'SEA' ? 'LAND' : 'SEA'
    fs.writeFileSync(file, JSON.stringify(manifest))
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.doesNotMatch(result.stdout, /省略/)
  })

  test(`AC-11 ${script}: CI=true で素材が無ければ失敗（CI では省略しない）`, () => {
    const dir = copyRepo()
    omit(dir, devFiles)
    const result = run(dir, script, [], { CI: 'true' })
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /CI/)
  })

  test(`AC-10 ${script}: 手で削除（skip-worktree でない欠落）は失敗`, () => {
    const dir = copyRepo()
    for (const file of devFiles) fs.rmSync(path.join(dir, file))
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /manual-deletion/)
  })

  test(`AC-10 ${script}: 部分展開は失敗`, () => {
    const dir = copyRepo()
    omit(dir, devFiles.slice(0, 1))
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /partial/)
  })

  test(`AC-10 ${script}: 追跡パス0件は失敗`, () => {
    const dir = copyRepo()
    git(dir, 'rm', '-r', '--quiet', '--cached', 'frontend/dev-fixtures')
    fs.rmSync(path.join(dir, 'frontend/dev-fixtures'), { recursive: true, force: true })
    git(dir, 'commit', '--quiet', '-m', 'remove')
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /no-tracked-paths/)
  })

  test(`AC-10 ${script}: git が使えない（リポジトリでない）は失敗`, () => {
    const dir = copyRepo()
    fs.rmSync(path.join(dir, '.git'), { recursive: true, force: true })
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /git-failed/)
  })

  test(`AC-11 ${script}: 展開済み素材が改ざん・不整合なら失敗（実バイト検査が走る）`, () => {
    const dir = copyRepo()
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.doesNotMatch(result.stdout, /PASS/)
  })
}

test('AC-12: 検証モジュールは import しただけでは出力もプロセス終了もしない', () => {
  for (const script of ['verify-ranch-development-clips.mjs', 'verify-ranch-development-appearances.mjs',
    'verify-ranch-production-assets.mjs', 'verify-ranch-production-artifact.mjs', 'verify-ranch-public-identity-gitleaks.mjs']) {
    const result = spawnSync(process.execPath, ['--input-type=module', '-e',
      `await import(${JSON.stringify(pathToFileURL(path.join(here, script)).href)}); console.log('imported')`],
    { encoding: 'utf8', env: CLEAN_ENV })
    assert.equal(result.status, 0, `${script}: ${out(result)}`)
    assert.equal(result.stdout.trim(), 'imported', script)
  }
})

test('AC-14: 配置CLIは sparse で省略せず、何も書き込む前に非0で展開手順を表示する', () => {
  const dir = copyRepo()
  omit(dir, approvedFiles)
  const before = git(dir, 'status', '--porcelain')
  const fakePack = path.join(dir, 'no-such-pack')
  const result = run(dir, 'verify-ranch-production-assets.mjs', ['--pack', fakePack, '--manifest-sha256', 'a'.repeat(64),
    '--master', path.join(dir, 'no-master.json'), '--master-sha256', 'b'.repeat(64), '--repository', dir])
  assert.notEqual(result.status, 0, out(result))
  assert.ok(out(result).includes(EXPAND_COMMAND), out(result))
  assert.equal(fs.existsSync(path.join(dir, 'work')), false, '作業ディレクトリも作らない')
  assert.equal(git(dir, 'status', '--porcelain'), before, '作業木を変更しない')
})

test('AC-14: installVerifiedPack も sparse なら書き込み前に失敗する', () => {
  const dir = copyRepo()
  omit(dir, approvedFiles)
  assert.throws(() => installVerifiedPack({}, dir), error => error.message.includes(EXPAND_COMMAND))
  assert.equal(fs.existsSync(path.join(dir, 'work')), false)
})

test('AC-14: 全展開の配置CLIは従来どおり進む（展開要求で止まらない）', () => {
  const dir = copyRepo()
  const result = run(dir, 'verify-ranch-production-assets.mjs', ['--pack', path.join(dir, 'no-such-pack'), '--manifest-sha256', 'a'.repeat(64),
    '--master', path.join(dir, 'no-master.json'), '--master-sha256', 'b'.repeat(64), '--repository', dir])
  assert.notEqual(result.status, 0)
  assert.ok(!out(result).includes(EXPAND_COMMAND), '全展開で展開手順を出さない')
})

test('AC-15: gitleaks再検査は sparse でも成功スキップせず、HEAD から manifest を取得して実際に読む', () => {
  const dir = copyRepo()
  const expanded = readApprovedManifestBytes({ repository: dir, env: CLEAN_ENV })
  assert.equal(expanded.toString('utf8'), MANIFEST_BODY)
  omit(dir, approvedFiles)
  const fromHead = readApprovedManifestBytes({ repository: dir, env: CLEAN_ENV })
  assert.equal(fromHead.toString('utf8'), MANIFEST_BODY)
  assert.throws(() => readApprovedManifestBytes({ repository: dir, env: { ...CLEAN_ENV, CI: 'true' } }), /CI/)
})

test('AC-15: manifest が sparse 除外でなく欠落（手で削除）なら失敗する', () => {
  const dir = copyRepo()
  fs.rmSync(path.join(dir, MANIFEST))
  assert.throws(() => readApprovedManifestBytes({ repository: dir, env: CLEAN_ENV }), new RegExp(EXPAND_COMMAND))
})

test('AC-16: 素材なしの成果物は CI の有無を問わず検証失敗（sparse でも pack 欠落でも）', () => {
  const dir = copyRepo()
  omit(dir, approvedFiles)
  const packDirectory = path.join(dir, 'frontend/public/ranch/approved/ranch-approved-20261108-v1')
  for (const env of [{ ...CLEAN_ENV, CI: '' }, { ...CLEAN_ENV, CI: 'true' }]) {
    assert.throws(() => requireProductionPackPresent({ repoRoot: dir, packDirectory, env }), error => error.message.includes(EXPAND_COMMAND))
  }
  const cli = run(dir, 'verify-ranch-production-artifact.mjs')
  assert.notEqual(cli.status, 0, out(cli))
  assert.ok(out(cli).includes(EXPAND_COMMAND), out(cli))
  // git の無い環境（Docker ビルドコンテキスト）でも pack が無ければ失敗する。
  const plain = copyRepo()
  fs.rmSync(path.join(plain, '.git'), { recursive: true, force: true })
  fs.rmSync(path.join(plain, 'frontend/public/ranch/approved'), { recursive: true, force: true })
  const noGit = run(plain, 'verify-ranch-production-artifact.mjs')
  assert.notEqual(noGit.status, 0, out(noGit))
  assert.ok(out(noGit).includes(EXPAND_COMMAND), out(noGit))
})
