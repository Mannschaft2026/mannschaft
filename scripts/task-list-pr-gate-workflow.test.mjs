import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
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

// 台帳の取得は GraphQL（Tree entries を1参照1回）。refs の各キー（base・head・ancestor・main）ごとに台帳の files を差し替えられる。
const SHA = { base: 'b'.repeat(40), head: 'h'.repeat(40), ancestor: 'c'.repeat(40), main: 'main' };
const LEDGER_PATH = /^docs\/task-list(?:\.md$|\/)/;

function treeEntriesOf(files, dir) {
  const prefix = dir + '/';
  const names = [...new Set(Object.keys(files).filter((p) => p.startsWith(prefix)).map((p) => p.slice(prefix.length).split('/')[0]))].sort();
  return names.map((name) => {
    const path = prefix + name;
    if (Object.prototype.hasOwnProperty.call(files, path)) return { name, type: 'blob', oid: blobOf(files[path], path).oid, object: blobOf(files[path], path) };
    return { name, type: 'tree', oid: 'tree:' + path, object: { __typename: 'Tree', entries: treeEntriesOf(files, path) } };
  });
}
function blobOf(text, path) {
  return { __typename: 'Blob', oid: createHash('sha1').update('blob ' + text).digest('hex'), text, isTruncated: false, isBinary: false, byteSize: Buffer.byteLength(text) };
}
function graphqlTreeResponse(files) {
  const hasDir = Object.keys(files).some((p) => p.startsWith('docs/task-list/'));
  return {
    repository: {
      commit: { __typename: 'Commit', oid: 'x'.repeat(40) },
      index: Object.prototype.hasOwnProperty.call(files, 'docs/task-list.md') ? blobOf(files['docs/task-list.md'], 'docs/task-list.md') : null,
      dir: hasDir ? { __typename: 'Tree', entries: treeEntriesOf(files, 'docs/task-list') } : null,
    },
  };
}

async function runGate({ files, refs = {}, title = '実装 CMP-261201-0000', failGraphql = false, latest = {}, latestSequence = null, refSha = 'h'.repeat(40), base = {}, author = { login: 'someone', type: 'User' }, prFiles = [{ filename: 'scripts/x.mjs', status: 'modified' }], headContents = {} }) {
  const repoRoot = fileURLToPath(new URL('../', import.meta.url));
  const previous = process.cwd();
  process.chdir(repoRoot);
  const failed = [];
  const infos = [];
  const outputs = {};
  const calls = { graphql: 0, ledgerContents: 0, getBlob: 0 };
  let listFilesCalled = 0;
  let getCalls = 0;
  const filesAt = (ref) => {
    const key = Object.keys(SHA).find((k) => SHA[k] === ref);
    if (!key) throw new Error('mock: 未知の ref ' + ref);
    return refs[key] ?? files;
  };
  const notFound = () => Object.assign(new Error('Not Found'), { status: 404 });
  const latestOf = (overrides) => ({ number: 1, title, body: '', created_at: '2026-11-25T00:00:00Z', changed_files: prFiles.length, user: author, base: { sha: SHA.base, ref: 'main', repo: { full_name: 'o/r' }, ...base }, head: { sha: SHA.head }, ...overrides });
  const github = {
    paginate: async (fn, params) => (await fn(params)).data,
    graphql: async (query, variables) => {
      calls.graphql++;
      if (failGraphql) throw Object.assign(new Error('Server Error'), { status: 502 });
      assert.match(query, /entries/);
      assert.equal(variables.index, variables.commit + ':docs/task-list.md');
      assert.equal(variables.dir, variables.commit + ':docs/task-list');
      return graphqlTreeResponse(filesAt(variables.commit));
    },
    rest: {
      git: {
        getRef: async () => ({ data: { object: { sha: refSha } } }),
        getBlob: async () => { calls.getBlob++; throw new Error('mock: getBlob は使わない'); },
      },
      pulls: {
        get: async () => {
          const overrides = latestSequence ? latestSequence[Math.min(getCalls, latestSequence.length - 1)] : latest;
          getCalls++;
          return { data: latestOf(overrides) };
        },
        listFiles: async () => { listFilesCalled++; return { data: prFiles }; } },
      repos: {
        compareCommitsWithBasehead: async () => ({ data: { merge_base_commit: { sha: SHA.ancestor } } }),
        getContent: async ({ path, ref }) => {
          if (LEDGER_PATH.test(path)) calls.ledgerContents++;
          const source = ref === SHA.head ? { ...filesAt(ref), ...headContents } : filesAt(ref);
          if (Object.prototype.hasOwnProperty.call(source, path)) {
            return { data: { type: 'file', encoding: 'base64', content: Buffer.from(source[path], 'utf8').toString('base64'), sha: 's' } };
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
    payload: { pull_request: { number: 1, title: 'ペイロードの古い題名', body: '古い本文', created_at: '2026-11-25T00:00:00Z', changed_files: 1, base: { sha: SHA.base, ref: 'main' }, head: { sha: SHA.head, ref: 'feature/x', repo: { name: 'r', owner: { login: 'o' } } } } },
  };
  const core = { info: (m) => infos.push(m), notice: (m) => infos.push(m), warning: (m) => infos.push(m), error: (m) => failed.push(m), setFailed: (m) => failed.push(m), setOutput: (k, v) => { outputs[k] = v; } };
  const AsyncFunction = Object.getPrototypeOf(async () => {}).constructor;
  const realSetTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn) => realSetTimeout(fn, 0); // 反映待ちの 10 秒は実時間で待たない
  try {
    await new AsyncFunction('github', 'context', 'core', 'require', fetchScript())(github, context, core, createRequire(import.meta.url));
    if (outputs.stale === 'true') return { failed, infos, listFilesCalled, outputs, getCalls, calls };
    process.env.LATEST_PULL = outputs.pull;
    await new AsyncFunction('github', 'context', 'core', 'require', gateScript())(github, context, core, createRequire(import.meta.url));
    return { failed, infos, listFilesCalled, outputs, getCalls, calls };
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

test('AC-5: 台帳取得（GraphQL）の 5xx は不在とみなさず例外にする', async () => {
  const files = newLayout(['CMP-261201-0000', 'P4 実装中']);
  await assert.rejects(runGate({ files, failGraphql: true }), /could not be read from base.*不在とは扱わない.*Server Error/);
});

test('AC-5: 索引が無い・行が0件の台帳は例外にする（0件は赤）', async () => {
  await assert.rejects(runGate({ files: { 'docs/task-list.md': '# 台帳\n' } }), /1件も読めなかった/);
  await assert.rejects(runGate({ files: {} }), /存在しない/);
});

test('AC-5: PR 側 gate は信頼 base の scripts/task-list-ledger.mjs だけを読み、head を checkout しない', () => {
  const script = gateScript();
  assert.match(script, /path\.join\(process\.cwd\(\), 'scripts', 'task-list-ledger\.mjs'\)/);
  assert.match(script, /createGraphqlLedgerSource\(/);
  assert.match(script, /path\.join\(process\.cwd\(\), 'scripts', 'task-list-pr-gate\.mjs'\)/);
  assert.doesNotMatch(script, /readContent\('docs\/task-list\.md'/, '台帳の単一ファイル読みを残さない');
  assert.doesNotMatch(script, /listContentDir/, '台帳を Contents API でディレクトリ単位に読まない（GraphQL の Tree entries で一括取得）');
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

// ---------------------------------------------------------------------------
// CMP-261124-1222 ④ 段1c: PR ゲートの台帳取得（GraphQL 一括）・仮想 head の等式と連鎖・実効台帳での登録／完了証拠の判定。
// ---------------------------------------------------------------------------

const CH = 'docs/task-list/changes/';
const chMeta = (op, id, b) => '<!-- ledger-change v1 op=' + op + ' id=' + id + ' base=' + b + ' -->';
const rowHash = (line) => 'sha256:' + createHash('sha256').update(line.replace(/\r/g, ''), 'utf8').digest('hex');
const chAdd = (line, id) => chMeta('add', id, 'none') + '\n' + line + '\n';
const chUpd = (from, to, id) => chMeta('update', id, rowHash(from)) + '\n' + to + '\n';
const chName = (id, t, r = '000001') => CH + id + '--' + t + '--' + r + '.md';
const R = (id, state, evidence = '証拠') => '| ' + id + ' | 戦役 | ' + state + ' | — | 条件 | ' + evidence + ' | ledger |';
const IDXT = '# 台帳（索引）\n\n前置き。\n';
const HT = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const month = (...rows) => '# 2026-11\n\n' + HT + '\n' + rows.join('\n') + '\n';
const T = 'CMP-261124-1222';
const P = 'CMP-261101-0000';
const A0 = R(T, 'P2 実装中');
const B0 = R(T, 'P2 実装中（④着手）');
const C0 = R(T, 'P2 実装中（別系統）');
const ledgerFiles = (rows, changes = {}) => ({ 'docs/task-list.md': IDXT, 'docs/task-list/2026-11.md': month(...rows), ...changes });
const docsOnly = (...names) => names.map((filename) => ({ filename, status: 'added' }));
const BOT = { login: 'mannschaft-ledger-bot[bot]', type: 'Bot' };

test('性能: 台帳取得の API 呼び出し数は申告の件数に依らない（GraphQL 1参照1回・Contents API で台帳を読まない）', async () => {
  const counts = [];
  for (const n of [0, 3, 60]) {
    const changes = {};
    for (let i = 0; i < n; i++) {
      const id = 'CMP-2611' + String(10 + (i % 18)).padStart(2, '0') + '-' + String(1000 + i).padStart(4, '0');
      changes[chName(id, '20261127T0100' + String(i % 60).padStart(2, '0') + 'Z', String(100000 + i))] = chAdd(R(id, 'P3 未着手'), id);
    }
    const base = ledgerFiles([A0]);
    const head = ledgerFiles([A0], changes);
    const { failed, calls } = await runGate({ files: base, refs: { head }, title: '実装 ' + T, prFiles: docsOnly(...Object.keys(changes), 'scripts/x.mjs') });
    assert.deepEqual(failed, [], 'n=' + n);
    assert.equal(calls.ledgerContents, 0, 'Contents API で台帳を読まない');
    counts.push(calls.graphql);
  }
  assert.deepEqual(counts, [counts[0], counts[0], counts[0]], '件数で呼び出し数が変わらない: ' + counts.join(','));
  assert.ok(counts[0] <= 4, '参照は base・head・分岐点・base.ref の4つまで: ' + counts[0]);
});

test('AC-8: 申告だけで実装中になった CMP（base の月別は未着手）でコード PR の登録規則が通る', async () => {
  const before = R(T, 'P4 未着手');
  const after = R(T, 'P4 実装中');
  const files = ledgerFiles([before], { [chName(T, '20261127T010000Z')]: chUpd(before, after, T) });
  const { failed, infos } = await runGate({ files, title: '実装 ' + T });
  assert.deepEqual(failed, []);
  assert.ok(infos.some((m) => m.includes(T + ' is registered as 実装中')), infos.join('\n'));
  // 対照: 申告が無ければ未着手のままで赤
  await assert.rejects(runGate({ files: ledgerFiles([before]), title: '実装 ' + T }), /must be registered as 実装中/);
});

test('AC-6（PR 側・人）: 申告の改変は赤', async () => {
  const n = chName(T, '20261127T010000Z');
  const base = ledgerFiles([A0], { [n]: chUpd(A0, B0, T) });
  const head = ledgerFiles([A0], { [n]: chUpd(A0, C0, T) });
  const { failed } = await runGate({ files: base, refs: { head }, title: '実装 ' + T, prFiles: docsOnly(n) });
  assert.ok(failed.some((m) => /change-modified/.test(m)), failed.join('\n'));
});

test('AC-6（PR 側・人）: 畳み込みでない申告の削除は赤', async () => {
  const n = chName(T, '20261127T010000Z');
  const base = ledgerFiles([A0], { [n]: chUpd(A0, B0, T) });
  const head = ledgerFiles([A0]);
  const { failed } = await runGate({ files: base, refs: { head }, prFiles: docsOnly(n) });
  assert.ok(failed.some((m) => /change-deleted/.test(m)), failed.join('\n'));
});

test('AC-6（PR 側・bot）: bot でも不正な畳み込み（申告を消したのに月別へ反映しない）は赤', async () => {
  const n = chName(T, '20261127T010000Z');
  const base = ledgerFiles([A0], { [n]: chUpd(A0, B0, T) });
  const head = ledgerFiles([A0]);
  const { failed } = await runGate({ files: base, refs: { head }, author: BOT, prFiles: docsOnly(n) });
  assert.ok(failed.some((m) => /change-deleted/.test(m)), failed.join('\n'));
});

test('AC-6（PR 側・bot）: bot の正しい畳み込みは緑で、警告も出さない', async () => {
  const n = chName(T, '20261127T010000Z');
  const base = ledgerFiles([P, A0].map((x) => (x === P ? R(P, '完了') : x)), { [n]: chUpd(A0, B0, T) });
  const head = ledgerFiles([R(P, '完了'), B0]);
  const { failed, infos } = await runGate({ files: base, refs: { head }, author: BOT, prFiles: docsOnly(n, 'docs/task-list/2026-11.md') });
  assert.deepEqual(failed, []);
  assert.ok(!infos.some((m) => /direct-edit|でない/.test(m)), infos.join('\n'));
  assert.ok(infos.some((m) => /畳み込み|fold/.test(m)), infos.join('\n'));
});

test('AC-6 / P2-4（PR 側・人・互換期間）: 月別ファイルの直接編集は緑のまま notice（警告）で、作者が bot でないことを示す', async () => {
  const base = ledgerFiles([A0]);
  const head = ledgerFiles([B0]);
  const { failed, infos } = await runGate({ files: base, refs: { head }, prFiles: docsOnly('docs/task-list/2026-11.md') });
  assert.deepEqual(failed, []);
  assert.ok(infos.some((m) => /direct-edit/.test(m)), infos.join('\n'));
  assert.ok(infos.some((m) => m.includes('someone') && m.includes('mannschaft-ledger-bot[bot] でない')), infos.join('\n'));
});

test('AC-6（PR 側・人）: 人の正しい畳み込みは互換期間は緑、作者が bot でない旨の notice を出す', async () => {
  const n = chName(T, '20261127T010000Z');
  const base = ledgerFiles([A0], { [n]: chUpd(A0, B0, T) });
  const head = ledgerFiles([B0]);
  const { failed, infos } = await runGate({ files: base, refs: { head }, prFiles: docsOnly(n, 'docs/task-list/2026-11.md') });
  assert.deepEqual(failed, []);
  assert.ok(infos.some((m) => m.includes('mannschaft-ledger-bot[bot] でない')), infos.join('\n'));
});

test('AC-6a: main だけで add・update・畳み込みが進んだ、台帳を触らない古い PR は緑', async () => {
  const ancestor = ledgerFiles([A0]);
  const id2 = 'CMP-261127-0900';
  const base = ledgerFiles([B0], { [chName(id2, '20261127T090000Z')]: chAdd(R(id2, 'P3 実装中'), id2), [chName(T, '20261127T100000Z')]: chUpd(B0, C0, T) });
  const { failed } = await runGate({ files: base, refs: { head: ancestor, ancestor }, title: '実装 ' + id2 });
  assert.deepEqual(failed, []);
});

test('AC-6b: 同じ行を main と競合して更新した古い PR は赤（仮想マージで fork）', async () => {
  const ancestor = ledgerFiles([A0]);
  const base = ledgerFiles([A0], { [chName(T, '20261127T090000Z', '111111')]: chUpd(A0, B0, T) });
  const head = ledgerFiles([A0], { [chName(T, '20261127T100000Z', '222222')]: chUpd(A0, C0, T) });
  const { failed } = await runGate({ files: base, refs: { head, ancestor }, prFiles: docsOnly(chName(T, '20261127T100000Z', '222222')) });
  assert.ok(failed.some((m) => /fork/.test(m)), failed.join('\n'));
});

test('AC-7: 古い枝の PR の base 不一致申告（main で既に畳み込まれた行を古い版から更新）は仮想マージで赤（孤児）', async () => {
  const ancestor = ledgerFiles([A0]);
  const base = ledgerFiles([B0]);
  const n = chName(T, '20261127T100000Z');
  const head = ledgerFiles([A0], { [n]: chUpd(A0, C0, T) });
  const { failed } = await runGate({ files: base, refs: { head, ancestor }, prFiles: docsOnly(n) });
  assert.ok(failed.some((m) => /孤児|orphan/.test(m)), failed.join('\n'));
});

test('AC-7: 対照: 同じ申告でも base の最新行から作れば緑', async () => {
  const ancestor = ledgerFiles([A0]);
  const base = ledgerFiles([B0]);
  const n = chName(T, '20261127T100000Z');
  const head = ledgerFiles([A0], { [n]: chUpd(B0, C0, T) });
  // 分岐点では B0 が無いので PR 系列内では孤児だが、連鎖は仮想マージで判定する（台帳は PR の差分＝申告の追加だけ）
  const { failed } = await runGate({ files: base, refs: { head, ancestor }, prFiles: docsOnly(n) });
  assert.deepEqual(failed.filter((m) => /fork|孤児|orphan|重複/.test(m)), []);
});

test('AC-9: 完了 update 申告は証拠 JSON 無しで赤', async () => {
  const done = R(T, '完了', '[証拠](../evidence/' + T + '.json)');
  const n = chName(T, '20261127T100000Z');
  const base = ledgerFiles([A0]);
  const head = ledgerFiles([A0], { [n]: chUpd(A0, done, T) });
  await assert.rejects(runGate({ files: base, refs: { head }, prFiles: docsOnly(n) }), /証拠JSONが空または不正/);
});

test('AC-9: 畳み込み PR では証拠の選定が0件（完了済みの申告を月別へ移すだけ）', async () => {
  const done = R(T, '完了', '[証拠](../evidence/' + T + '.json)');
  const n = chName(T, '20261127T100000Z');
  const base = ledgerFiles([A0], { [n]: chUpd(A0, done, T) });
  const head = ledgerFiles([done]);
  const { failed, infos } = await runGate({ files: base, refs: { head }, author: BOT, prFiles: docsOnly(n, 'docs/task-list/2026-11.md') });
  assert.deepEqual(failed, []);
  assert.ok(infos.some((m) => /validated for 0 changed\/new completion proof/.test(m)), infos.join('\n'));
});

test('AC-10: 最新 PR の作者（user）を取得ステップの出力に含める（bot 判定に使う）', async () => {
  const { outputs } = await runGate({ files: ledgerFiles([R('CMP-261201-0000', 'P4 実装中')]) });
  assert.deepEqual(JSON.parse(outputs.pull).user, { login: 'someone', type: 'User' });
});
