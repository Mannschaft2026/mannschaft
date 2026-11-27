import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { splitSingleLedgerForTest } from './test-support/task-list-split.mjs';

// CMP-261121-1114: Task-List PR Gate の merge queue（merge_group）対応の静的検査。
// merge_group 経路は別 workflow（task-list-pr-gate-merge-group.yml）に分離し、必須チェック名
// "Task-List PR Gate" を報告すること、信頼 base（merge_group.base_sha）の検査器だけを使うこと、
// 権限が contents: read のみであること、PR 側の task-list-pr-gate.yml が merge_group を持たないことを固定する。

const read = (name) => readFileSync(fileURLToPath(new URL('../.github/workflows/' + name, import.meta.url)), 'utf8').replace(/\r\n/g, '\n');
const mergeGroupWorkflow = read('task-list-pr-gate-merge-group.yml');
const prWorkflow = read('task-list-pr-gate.yml');

function topLevelBlock(workflow, key) {
  const lines = workflow.split('\n');
  const start = lines.findIndex((line) => line === key + ':');
  assert.notEqual(start, -1, key + ': ブロックが無い');
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (/^\S/.test(line)) break;
    body.push(line);
  }
  return body.join('\n');
}

function steps(workflow) {
  const jobs = topLevelBlock(workflow, 'jobs');
  const stepsAt = jobs.indexOf('\n    steps:\n');
  assert.notEqual(stepsAt, -1, 'steps が無い');
  return jobs.slice(stepsAt).split(/\n(?=      - name: )/).slice(1).map((block) => ({
    block,
    name: block.match(/^      - name: (.+)$/m)[1].trim(),
    if: block.match(/^        if: (.+)$/m)?.[1].trim() ?? null,
    uses: block.match(/^        uses: (.+)$/m)?.[1].trim() ?? null,
    ref: block.match(/^          ref: (.+)$/m)?.[1].trim() ?? null,
    path: block.match(/^          path: (.+)$/m)?.[1].trim() ?? null,
  }));
}

test('AC-1/2: merge_group 用 workflow は on: merge_group（checks_requested）だけで起動する', () => {
  assert.equal(topLevelBlock(mergeGroupWorkflow, 'on'), '  merge_group:\n    types: [checks_requested]\n');
});

test('AC-1/2: ジョブは1つで、名前は必須チェック名 "Task-List PR Gate"', () => {
  const jobs = topLevelBlock(mergeGroupWorkflow, 'jobs');
  const jobKeys = jobs.split('\n').filter((line) => /^  [A-Za-z0-9_-]+:$/.test(line));
  assert.equal(jobKeys.length, 1, jobKeys.join(','));
  assert.match(jobs, /^    name: Task-List PR Gate$/m);
  assert.doesNotMatch(jobs, /^    if:/m, 'ジョブ単位の if で skip させない');
  assert.doesNotMatch(jobs, /^    permissions:/m, '権限は workflow 単位の contents: read だけにする');
});

test('AC-12: 権限は contents: read のみ、concurrency は github.ref ベース', () => {
  assert.equal(topLevelBlock(mergeGroupWorkflow, 'permissions'), '  contents: read\n');
  assert.match(topLevelBlock(mergeGroupWorkflow, 'concurrency'), /^  group: \S*\$\{\{ github\.ref \}\}$/m);
});

test('AC-12: 検査器は base_sha から直下へ、台帳だけを head_sha から別ディレクトリへ取得する', () => {
  const all = steps(mergeGroupWorkflow);
  for (const step of all) assert.equal(step.if, null, 'step 単位の if は不要: ' + step.name);
  const checkouts = all.filter((step) => /^actions\/checkout@/.test(step.uses ?? ''));
  assert.equal(checkouts.length, 2);
  const rootCheckouts = checkouts.filter((step) => step.path === null);
  assert.equal(rootCheckouts.length, 1, 'ワークスペース直下へ checkout するのは信頼 base の1回だけ');
  assert.match(rootCheckouts[0].ref, /^\$\{\{ github\.event\.merge_group\.base_sha \}\}$/);
  const head = checkouts.find((step) => /merge_group\.head_sha/.test(step.ref ?? ''));
  assert.ok(head, 'head_sha の取得が無い');
  assert.notEqual(head.path, null, 'head_sha をワークスペース直下へ checkout してはならない');
  // CMP-261124-1222 AC-6: 新配置の月別ファイル（docs/task-list/）と索引の両方を取得する
  assert.match(head.block, /sparse-checkout: \|\n\s+docs\/task-list\.md\n\s+docs\/task-list\/\n/);
  assert.match(head.block, /sparse-checkout-cone-mode: false/);
  for (const step of checkouts) assert.match(step.block, /persist-credentials: false/);
  assert.ok(checkouts.indexOf(rootCheckouts[0]) < checkouts.indexOf(head), 'base を先に checkout してから head を別ディレクトリへ取得する');
  const run = all.find((step) => /node scripts\/task-list-structure-check\.mjs/.test(step.block));
  assert.ok(run, '検査器を実行していない');
  assert.ok(run.block.includes('node scripts/task-list-structure-check.mjs ' + head.path + '/docs/task-list.md'), '検査対象は head から取得した台帳、検査器は base 側');
  assert.doesNotMatch(topLevelBlock(mergeGroupWorkflow, 'jobs'), /github-script|pull_request/, 'merge_group 経路で PR 本文検査を行わない');
});

test('CMP-261124-1222 ④: merge_group 経路は構造検査に加え、base_sha（ワークスペース直下）と group head で申告の連鎖・等式を検査する', () => {
  const run = steps(mergeGroupWorkflow).find((step) => /node scripts\/task-list-structure-check\.mjs/.test(step.block));
  assert.ok(run, '検査器を実行していない');
  assert.ok(run.block.includes('node scripts/task-list-structure-check.mjs .merge-group-head/docs/task-list.md --base .'), run.block);
});

test('AC-10: PR 側の task-list-pr-gate.yml は merge_group を持たず、pull_request_target 経路は従来どおり', () => {
  const on = topLevelBlock(prWorkflow, 'on');
  assert.doesNotMatch(prWorkflow, /merge_group/);
  assert.match(on, /^  pull_request_target:\n    branches: \[main\]\n    types: \[opened, synchronize, reopened, ready_for_review, edited\]$/m);
  assert.match(on, /^  workflow_dispatch:$/m);
  assert.equal(topLevelBlock(prWorkflow, 'permissions'), '  contents: read\n  actions: read\n  checks: write\n  pull-requests: read\n');
  const checkout = steps(prWorkflow).find((step) => step.name === 'Checkout trusted base');
  assert.match(checkout.ref, /^\$\{\{ steps\.latest\.outputs\.base_sha \|\| github\.sha \}\}$/);
  assert.match(topLevelBlock(prWorkflow, 'jobs'), /^    name: Task-List PR Gate$/m);
});

// ---------------------------------------------------------------------------
// CMP-261124-1222 AC-5: PR 側 gate の script を Contents API のモックで実際に走らせ、台帳の両配置で
// 「実装 PR は base で登録済み・実装中」を判定すること、取得失敗を空にしないことを確かめる。
// ---------------------------------------------------------------------------

function stepScript(name) {
  const block = steps(prWorkflow).find((step) => step.name === name)?.block;
  assert.ok(block, 'ステップが無い: ' + name);
  const lines = block.split('\n');
  const start = lines.findIndex((line) => line === '          script: |');
  assert.notEqual(start, -1, 'github-script の script ブロックが無い: ' + name);
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (line.trim() !== '' && !line.startsWith('            ')) break;
    body.push(line.slice(12));
  }
  return body.join('\n');
}
const gateScript = () => stepScript('Validate registration and completion evidence');
const fetchScript = () => stepScript('Fetch latest pull request');

const headerRow = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const ledgerOf = (...rows) => '# 台帳\n\n' + headerRow + '\n' + rows.map(([id, state]) => '| ' + id + ' | 戦役 | ' + state + ' | — | 条件 | 証拠 | ledger |').join('\n') + '\n';

async function runGate({ files, title = '実装 CMP-261201-0000', failDir = false, latest = {}, latestSequence = null, refSha = 'h'.repeat(40), base = {} }) {
  const repoRoot = fileURLToPath(new URL('../', import.meta.url));
  const previous = process.cwd();
  process.chdir(repoRoot);
  const failed = [];
  const infos = [];
  const outputs = {};
  let listFilesCalled = 0;
  let getCalls = 0;
  const notFound = () => Object.assign(new Error('Not Found'), { status: 404 });
  const latestOf = (overrides) => ({ number: 1, title, body: '', created_at: '2026-11-25T00:00:00Z', changed_files: 1, base: { sha: 'b'.repeat(40), ref: 'main', repo: { full_name: 'o/r' }, ...base }, head: { sha: 'h'.repeat(40) }, ...overrides });
  const github = {
    paginate: async (fn, params) => (await fn(params)).data,
    rest: {
      git: { getRef: async () => ({ data: { object: { sha: refSha } } }) },
      pulls: {
        get: async () => {
          const overrides = latestSequence ? latestSequence[Math.min(getCalls, latestSequence.length - 1)] : latest;
          getCalls++;
          return { data: latestOf(overrides) };
        },
        listFiles: async () => { listFilesCalled++; return { data: [{ filename: 'scripts/x.mjs', status: 'modified' }] }; } },
      repos: {
        compareCommitsWithBasehead: async () => ({ data: { merge_base_commit: { sha: 'c'.repeat(40) } } }),
        getContent: async ({ path }) => {
          if (Object.prototype.hasOwnProperty.call(files, path)) {
            return { data: { type: 'file', encoding: 'base64', content: Buffer.from(files[path], 'utf8').toString('base64'), sha: 's' } };
          }
          const children = Object.keys(files).filter((p) => p.startsWith(path + '/'));
          if (children.length) {
            if (failDir) throw Object.assign(new Error('Server Error'), { status: 500 });
            return { data: children.map((p) => ({ name: p.slice(path.length + 1), type: 'file' })) };
          }
          throw notFound();
        },
      },
    },
  };
  const context = {
    eventName: 'pull_request_target',
    ref: 'refs/pull/1/merge',
    repo: { owner: 'o', repo: 'r' },
    payload: { pull_request: { number: 1, title: 'ペイロードの古い題名', body: '古い本文', created_at: '2026-11-25T00:00:00Z', changed_files: 1, base: { sha: 'b'.repeat(40), ref: 'main' }, head: { sha: 'h'.repeat(40), ref: 'feature/x', repo: { name: 'r', owner: { login: 'o' } } } } },
  };
  const core = { info: (m) => infos.push(m), notice: (m) => infos.push(m), error: (m) => failed.push(m), setFailed: (m) => failed.push(m), setOutput: (k, v) => { outputs[k] = v; } };
  const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
  const realSetTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn) => realSetTimeout(fn, 0); // 反映待ちの 10 秒は実時間で待たない
  try {
    await new AsyncFunction('github', 'context', 'core', 'require', fetchScript())(github, context, core, createRequire(import.meta.url));
    if (outputs.stale === 'true') return { failed, infos, listFilesCalled, outputs, getCalls };
    process.env.LATEST_PULL = outputs.pull;
    await new AsyncFunction('github', 'context', 'core', 'require', gateScript())(github, context, core, createRequire(import.meta.url));
    return { failed, infos, listFilesCalled, outputs, getCalls };
  } finally {
    delete process.env.LATEST_PULL;
    globalThis.setTimeout = realSetTimeout;
    process.chdir(previous);
  }
}

const oldLayout = (...rows) => ({ 'docs/task-list.md': ledgerOf(...rows) });
const newLayout = (...rows) => splitSingleLedgerForTest(ledgerOf(...rows));

for (const [name, layout] of [['旧配置', oldLayout], ['新配置', newLayout]]) {
  test('AC-5: ' + name + ' の base で実装中の CMP-ID を持つ実装 PR は合格', async () => {
    const files = layout(['CMP-003', '完了'], ['CMP-261130-2359', '完了'], ['CMP-261201-0000', 'P4 実装中']);
    if (name === '新配置') assert.ok(files['docs/task-list/2026-12.md'].includes('CMP-261201-0000'));
    const { failed, infos } = await runGate({ files });
    assert.deepEqual(failed, []);
    assert.ok(infos.some((m) => /CMP-261201-0000 is registered as 実装中/.test(m)), infos.join('\n'));
  });

  test('AC-5: ' + name + ' の base に未登録・実装中でない CMP-ID は不合格', async () => {
    await assert.rejects(runGate({ files: layout(['CMP-261130-2359', 'P4 実装中']) }), /CMP-261201-0000 must appear exactly once/);
    await assert.rejects(runGate({ files: layout(['CMP-261201-0000', 'P4 未着手']) }), /must be registered as 実装中/);
  });
}

test('AC-5: Contents API のディレクトリ取得失敗（500）は空とみなさず例外にする', async () => {
  const files = newLayout(['CMP-261201-0000', 'P4 実装中']);
  await assert.rejects(runGate({ files, failDir: true }), /could not be read from base.*取得に失敗.*Server Error/);
});

test('AC-5: 索引が無い・行が0件の台帳は例外にする（0件は赤）', async () => {
  await assert.rejects(runGate({ files: { 'docs/task-list.md': '# 台帳\n' } }), /1件も読めなかった/);
  await assert.rejects(runGate({ files: {} }), /存在しない/);
});

test('AC-5: PR 側 gate は信頼 base の scripts/task-list-ledger.mjs だけを読み、head を checkout しない', () => {
  const script = gateScript();
  assert.match(script, /path\.join\(process\.cwd\(\), 'scripts', 'task-list-ledger\.mjs'\)/);
  assert.match(script, /ledgerLib\.readLedger\(/);
  assert.doesNotMatch(script, /readContent\('docs\/task-list\.md'/, '台帳の単一ファイル読みを残さない');
  assert.match(script, /ledgerLibPathInBase = 'scripts\/task-list-ledger\.mjs'/);
  assert.match(script, /file\.previous_filename === ledgerLibPathInBase/);
  const checkouts = steps(prWorkflow).filter((step) => /^actions\/checkout@/.test(step.uses ?? ''));
  assert.equal(checkouts.length, 1);
  assert.doesNotMatch(prWorkflow, /pull_request\.head\.(sha|ref) \}\}/);
});

// CMP-261124-1222 AC-10: rerun は古いペイロードではなく最新の PR（title/body/base.sha）で判定し、head のずれた run は判定しない。
test('AC-10: ペイロードの題名が古くても、最新の PR の題名の CMP-ID で判定する', async () => {
  const files = oldLayout(['CMP-261201-0000', 'P4 実装中']);
  const { failed, infos } = await runGate({ files, title: '実装 CMP-261201-0000' });
  assert.deepEqual(failed, []);
  assert.ok(infos.some((m) => /CMP-261201-0000 is registered as 実装中/.test(m)), infos.join('\n'));
});

test('AC-10: ペイロードの head が古い run（ブランチ ref がさらに新しい commit）は判定せず notice で成功終了する', async () => {
  const files = oldLayout(['CMP-261201-0000', 'P4 未着手']); // 判定すれば赤になる台帳
  const { failed, infos, listFilesCalled, outputs } = await runGate({ files, latest: { head: { sha: 'f'.repeat(40) } }, refSha: 'f'.repeat(40) });
  assert.deepEqual(failed, []);
  assert.equal(listFilesCalled, 0, '判定処理に進んでいない');
  assert.equal(outputs.stale, 'true');
  assert.ok(infos.some((m) => /stale/.test(m)), infos.join('\n'));
});

test('AC-10: API の反映遅れ（ref は payload の head のまま）は上限付きで取り直し、反映後に判定する', async () => {
  const files = oldLayout(['CMP-261201-0000', 'P4 実装中']);
  const lag = { head: { sha: 'o'.repeat(40) } };
  const { failed, infos, getCalls, outputs } = await runGate({ files, latestSequence: [lag, lag, {}] });
  assert.equal(getCalls, 3);
  assert.notEqual(outputs.stale, 'true');
  assert.deepEqual(failed, []);
  assert.ok(infos.some((m) => /CMP-261201-0000 is registered as 実装中/.test(m)), infos.join('\n'));
});

test('AC-10: 反映遅れが上限まで続けば成功にせず失敗させる（検査されないまま緑にしない）', async () => {
  const files = oldLayout(['CMP-261201-0000', 'P4 未着手']);
  await assert.rejects(runGate({ files, latest: { head: { sha: 'o'.repeat(40) } } }), /kept returning a different head/);
});

test('AC-10: 最新の PR の base がこの repo の main でなければ、検査コードを取得する前に失敗させる', async () => {
  const files = oldLayout(['CMP-261201-0000', 'P4 実装中']);
  await assert.rejects(runGate({ files, base: { ref: 'unreviewed-branch' } }), /base must be main/);
  await assert.rejects(runGate({ files, base: { repo: { full_name: 'evil/r' } } }), /base must be main/);
  const all = steps(prWorkflow);
  assert.ok(all.findIndex((step) => step.name === 'Fetch latest pull request') < all.findIndex((step) => step.name === 'Checkout trusted base'));
  assert.match(all.find((step) => step.name === 'Validate registration and completion evidence').block, /if: steps\.latest\.outputs\.stale != 'true'/);
});

test('AC-10: 最初のステップで pulls.get し、その最新 base.sha を checkout する。ペイロードの base.sha で checkout しない', () => {
  const all = steps(prWorkflow);
  const names = all.map((step) => step.name);
  assert.ok(names.indexOf('Fetch latest pull request') < names.indexOf('Checkout trusted base'));
  assert.match(all[0].block, /pulls\.get/);
  assert.match(all[0].block, /git\.getRef/);
  assert.doesNotMatch(prWorkflow, /uses: actions\/checkout@[^\n]*\n\s+with:\n\s+ref: \$\{\{ github\.event\.pull_request\.base\.sha/);
  assert.doesNotMatch(prWorkflow, /pull_request\.base\.sha/);
  assert.match(gateScript(), /process\.env\.LATEST_PULL/);
});
