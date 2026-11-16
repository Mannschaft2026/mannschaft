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
for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_PREFIX', 'GITHUB_ACTIONS', 'GITLAB_CI', 'TF_BUILD', 'JENKINS_URL']) delete CLEAN_ENV[key]
const MANIFEST = 'frontend/public/ranch/approved/ranch-approved-20261108-v1/manifest.json'
const APPROVED_FILES = [MANIFEST, 'frontend/public/ranch/approved/ranch-approved-20261108-v1/c.png']
// 各検証が manifest で参照するファイル（本物の manifest から算出）。テンプレートには中身のダミーを追跡させる。
function expectedFor(manifestName, root) {
  const manifest = JSON.parse(fs.readFileSync(path.join(frontendSource, 'app/utils', manifestName), 'utf8'))
  return manifest.entries.flatMap(entry => [entry.fallback.sourcePath, ...Object.values(entry.clips).map(clip => clip.sourcePath)])
    .map(file => `${root}/${file}`)
}
const CLIPS_ROOT = 'frontend/dev-fixtures/ranch/base16-v3'
const APPEARANCES_ROOT = 'frontend/dev-fixtures/ranch/appearance64-v4'
const SUBJECTS = [
  { script: 'verify-ranch-development-clips.mjs', manifest: 'ranch-development-clips-manifest.json', root: CLIPS_ROOT,
    files: expectedFor('ranch-development-clips-manifest.json', CLIPS_ROOT) },
  { script: 'verify-ranch-development-appearances.mjs', manifest: 'ranch-development-appearances-manifest.json', root: APPEARANCES_ROOT,
    files: expectedFor('ranch-development-appearances-manifest.json', APPEARANCES_ROOT) },
]
const ASSET_FILES = [...SUBJECTS.flatMap(subject => subject.files), ...APPROVED_FILES]
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
  git(template, 'init', '--quiet')
  git(template, 'add', '.')
  // 素材（2400件超）は作業木へ実体を作らず、index にだけ登録する（実体を作るとこの環境では1ケース数分かかる）。
  // 作業木に無い追跡パスは「手で削除」と同じ状態になり、sparse にすると本物の skip-worktree になる。
  const blob = body => spawnSync('git', ['hash-object', '-w', '--stdin'], { cwd: template, input: body, encoding: 'utf8', env: CLEAN_ENV }).stdout.trim()
  const common = blob('synthetic asset bytes'), manifestBlob = blob(MANIFEST_BODY)
  const info = ASSET_FILES.map(file => `100644 ${file === MANIFEST ? manifestBlob : common} 0	${file}
`).join('')
  const added = spawnSync('git', ['update-index', '--add', '--index-info'], { cwd: template, input: info, encoding: 'utf8', env: CLEAN_ENV })
  assert.equal(added.status, 0, added.stderr)
  git(template, 'commit', '--quiet', '-m', 'template')
  git(template, 'gc', '--quiet')
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
/** 本物の cone sparse checkout にする（素材ディレクトリは範囲外になり skip-worktree で作業木から消える）。 */
const sparsify = dir => git(dir, 'sparse-checkout', 'set', '--cone', 'scripts', 'frontend/scripts', 'frontend/app/utils')
function run(dir, script, args = [], env = {}) {
  return spawnSync(process.execPath, [path.join(dir, 'frontend/scripts', script), ...args],
    { cwd: path.join(dir, 'frontend'), encoding: 'utf8', env: { ...CLEAN_ENV, ...env } })
}
const out = result => `${result.stdout}\n${result.stderr}`

for (const { script, manifest: manifestName, root, files } of SUBJECTS) {
  const id = script.replace('verify-ranch-development-', '').replace('.mjs', '')

  test(`AC-9/10 ${id}: 本物の sparse ならメタデータ検査は通って実バイト省略（理由・対象つき・exit 0）`, () => {
    const dir = copyRepo()
    sparsify(dir)
    assert.ok(!fs.existsSync(path.join(dir, files[0])), '本物の sparse で素材が消えている')
    const result = run(dir, script)
    assert.equal(result.status, 0, out(result))
    assert.match(out(result), /省略/)
    assert.ok(out(result).includes(root), '対象を表示')
  })

  test(`AC-9 ${id}: sparse でも不正メタデータは非0（省略に逃げない）`, () => {
    const dir = copyRepo()
    sparsify(dir)
    const file = path.join(dir, 'frontend/app/utils', manifestName)
    const manifest = JSON.parse(fs.readFileSync(file, 'utf8'))
    manifest.entries[0].habitat = manifest.entries[0].habitat === 'SEA' ? 'LAND' : 'SEA'
    fs.writeFileSync(file, JSON.stringify(manifest))
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.doesNotMatch(result.stdout, /省略/)
  })

  test(`AC-11/指摘3 ${id}: CI では省略しない（CI=true、GITHUB_ACTIONS=true かつ CI=false）`, () => {
    const dir = copyRepo()
    sparsify(dir)
    for (const env of [{ CI: 'true' }, { GITHUB_ACTIONS: 'true', CI: 'false' }, { GITHUB_ACTIONS: 'true', CI: '' }]) {
      const result = run(dir, script, [], env)
      assert.notEqual(result.status, 0, out(result))
      assert.match(out(result), /CI/)
    }
  })

  test(`AC-10 ${id}: 手で削除（skip-worktree でない欠落）は失敗`, () => {
    // テンプレートは素材が index にだけ在り作業木に無い（= sparse でなく欠落している状態）。
    const dir = copyRepo()
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /manual-deletion/)
  })

  test(`AC-10 ${id}: 部分展開は失敗`, () => {
    const dir = copyRepo()
    sparsify(dir)
    git(dir, 'update-index', '--no-skip-worktree', files[0])
    fs.mkdirSync(path.dirname(path.join(dir, files[0])), { recursive: true })
    fs.writeFileSync(path.join(dir, files[0]), 'restored')
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /partial/)
  })

  test(`指摘1 ${id}: 自分の素材が追跡0件なら、別の素材が追跡・未展開でも失敗（省略で隠さない）`, () => {
    const dir = copyRepo()
    git(dir, 'rm', '-r', '--quiet', root)
    git(dir, 'commit', '--quiet', '-m', 'remove own assets')
    sparsify(dir)
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /no-tracked-paths/)
  })

  test(`指摘1 ${id}: manifest が参照する1件が追跡から消えた（改名）なら、残りが未展開でも失敗`, () => {
    const dir = copyRepo()
    git(dir, 'rm', '--quiet', files[0])
    git(dir, 'commit', '--quiet', '-m', 'rename away')
    sparsify(dir)
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /expected-not-tracked/)
  })

  test(`指摘5 ${id}: 期待一覧に無い余分な追跡ファイルがあれば、全て未展開でも省略せず失敗`, () => {
    const dir = copyRepo()
    const blob = spawnSync('git', ['hash-object', '-w', '--stdin'], { cwd: dir, input: 'extra', encoding: 'utf8', env: CLEAN_ENV }).stdout.trim()
    git(dir, 'update-index', '--add', '--cacheinfo', `100644,${blob},${root}/unexpected.png`)
    git(dir, 'commit', '--quiet', '-m', 'extra file')
    sparsify(dir)
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /unexpected-tracked/)
    assert.doesNotMatch(out(result), /メタデータ検査は完了/)
  })

  test(`指摘2 ${id}: sparse 無効のまま skip-worktree を手で立てて消しても省略しない`, () => {
    const dir = copyRepo()
    const flagged = spawnSync('git', ['update-index', '--skip-worktree', '-z', '--stdin'], { cwd: dir, input: files.join(' ') + ' ', encoding: 'utf8', env: CLEAN_ENV })
    assert.equal(flagged.status, 0, flagged.stderr)
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /sparse-not-enabled/)
  })

  test(`指摘4 ${id}: .git の無いコピー（git archive 相当）は git-failed で止まらず、素材が欠ければ省略せず失敗（全て在る場合は lib 試験）`, () => {
    const dir = copyRepo()
    fs.rmSync(path.join(dir, '.git'), { recursive: true, force: true })
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /missing-outside-git/)
    assert.doesNotMatch(out(result), /git-failed|メタデータ検査は完了/)
  })

  test(`AC-10 ${id}: .git があるのに git が失敗（壊れたリポジトリ）は失敗`, () => {
    const dir = copyRepo()
    fs.rmSync(path.join(dir, '.git/HEAD'))
    const result = run(dir, script)
    assert.notEqual(result.status, 0, out(result))
    assert.match(out(result), /git-failed/)
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
  sparsify(dir)
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
  sparsify(dir)
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
  fs.mkdirSync(path.dirname(path.join(dir, MANIFEST)), { recursive: true })
  fs.writeFileSync(path.join(dir, MANIFEST), MANIFEST_BODY)
  const expanded = readApprovedManifestBytes({ repository: dir, env: CLEAN_ENV })
  assert.equal(expanded.toString('utf8'), MANIFEST_BODY)
  fs.rmSync(path.join(dir, MANIFEST))
  sparsify(dir)
  const fromHead = readApprovedManifestBytes({ repository: dir, env: CLEAN_ENV })
  assert.equal(fromHead.toString('utf8'), MANIFEST_BODY)
  assert.throws(() => readApprovedManifestBytes({ repository: dir, env: { ...CLEAN_ENV, CI: 'true' } }), /CI/)
  assert.throws(() => readApprovedManifestBytes({ repository: dir, env: { ...CLEAN_ENV, GITHUB_ACTIONS: 'true', CI: 'false' } }), /CI/)
})

test('AC-15: manifest が sparse 除外でなく欠落（手で削除）なら失敗する', () => {
  const dir = copyRepo()
  assert.throws(() => readApprovedManifestBytes({ repository: dir, env: CLEAN_ENV }), new RegExp(EXPAND_COMMAND))
})

test('AC-16: 素材なしの成果物は CI の有無を問わず検証失敗（sparse でも pack 欠落でも）', () => {
  const dir = copyRepo()
  sparsify(dir)
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
