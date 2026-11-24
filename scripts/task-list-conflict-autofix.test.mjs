import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  selectCandidates,
  processPullRequest,
  countRecentFixes,
  pendingMergeability,
  createGitOps,
  ensureUnionAttribute,
  FIX_MARKER,
  FIX_COMMIT_TRAILER,
  LEDGER_PATH,
} from './task-list-conflict-autofix.mjs';
import { checkTaskListStructure } from './task-list-structure-check.mjs';

// CMP-261124-1222: 台帳だけで衝突している PR を union で直して push し、auto-merge を予約し直す自動修復の試験。
// gh / git は ops を差し替えてモックし、union の効き目だけは実際の git（一時リポジトリ）で確かめる。

const NOW = new Date('2026-11-24T12:00:00Z');
const header = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const row = (id, state = 'P3 未着手') => '| ' + id + ' | 戦役 | ' + state + ' | — | 条件 | 証拠 | ledger |';
const ledger = (...rows) => '# 台帳\n\n' + header + '\n' + rows.join('\n') + '\n';

function pr(overrides = {}) {
  return {
    id: 'PR_kw1',
    number: 101,
    baseRefName: 'main',
    headRefName: 'feature/101-x',
    headRefOid: 'head0001',
    isCrossRepository: false,
    mergeable: 'CONFLICTING',
    labels: { nodes: [] },
    autoMergeRequest: null,
    timelineItems: { nodes: [{ __typename: 'RemovedFromMergeQueueEvent', reason: 'merge_conflict' }] },
    comments: { nodes: [] },
    commits: { nodes: [] },
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// 選定
// ---------------------------------------------------------------------------

test('選定: 予約済み（autoMergeRequest 非 null）または queue 脱落が最新の CONFLICTING PR だけを選ぶ', () => {
  const removed = pr({ number: 1 });
  const reserved = pr({ number: 2, autoMergeRequest: { enabledAt: '2026-11-24T00:00:00Z' }, timelineItems: { nodes: [] } });
  const { candidates } = selectCandidates([removed, reserved]);
  assert.deepEqual(candidates.map((p) => p.number), [1, 2]);
});

test('選定: fork の PR は除外する', () => {
  const { candidates, skipped } = selectCandidates([pr({ isCrossRepository: true })]);
  assert.equal(candidates.length, 0);
  assert.match(skipped[0].reason, /fork/);
});

test('選定: no-ledger-autofix ラベル付きは除外する', () => {
  const { candidates, skipped } = selectCandidates([pr({ labels: { nodes: [{ name: 'no-ledger-autofix' }] } })]);
  assert.equal(candidates.length, 0);
  assert.match(skipped[0].reason, /no-ledger-autofix/);
});

test('選定: 予約なし・脱落イベントなしは除外する（マージ待ちの意思が無い）', () => {
  const none = pr({ timelineItems: { nodes: [] } });
  const reAdded = pr({ number: 3, timelineItems: { nodes: [{ __typename: 'AddedToMergeQueueEvent' }] } });
  const { candidates } = selectCandidates([none, reAdded]);
  assert.equal(candidates.length, 0);
});

test('選定: CONFLICTING 以外（MERGEABLE / UNKNOWN）は除外する', () => {
  const { candidates } = selectCandidates([pr({ mergeable: 'MERGEABLE' }), pr({ number: 5, mergeable: 'UNKNOWN' })]);
  assert.equal(candidates.length, 0);
});

test('選定: mergeable=UNKNOWN でマージ待ちの意思がある PR だけを計算待ちとして挙げる', () => {
  const prs = [
    pr({ number: 1, mergeable: 'UNKNOWN' }),
    pr({ number: 2, mergeable: 'UNKNOWN', timelineItems: { nodes: [] } }),
    pr({ number: 3, mergeable: 'UNKNOWN', isCrossRepository: true }),
    pr({ number: 4, mergeable: 'CONFLICTING' }),
    pr({ number: 5, mergeable: 'MERGEABLE' }),
  ];
  assert.deepEqual(pendingMergeability(prs), [1]);
});

test('選定: 予約なしで queue 脱落理由が merge_conflict 以外（manual / failed_checks）は除外する', () => {
  const manual = pr({ number: 1, timelineItems: { nodes: [{ __typename: 'RemovedFromMergeQueueEvent', reason: 'manual' }] } });
  const failed = pr({ number: 2, timelineItems: { nodes: [{ __typename: 'RemovedFromMergeQueueEvent', reason: 'failed_checks' }] } });
  const conflict = pr({ number: 3 });
  const { candidates, skipped } = selectCandidates([manual, failed, conflict]);
  assert.deepEqual(candidates.map((p) => p.number), [3]);
  assert.match(skipped.find((s) => s.number === 1).reason, /manual/);
});

test('選定: auto-merge 予約済みなら脱落理由が manual でも対象（予約が意思を示す）', () => {
  const reserved = pr({ autoMergeRequest: { enabledAt: '2026-11-24T00:00:00Z' }, timelineItems: { nodes: [{ __typename: 'RemovedFromMergeQueueEvent', reason: 'manual' }] } });
  assert.equal(selectCandidates([reserved]).candidates.length, 1);
});

test('選定: base が main 以外は除外する', () => {
  const { candidates } = selectCandidates([pr({ baseRefName: 'develop' })]);
  assert.equal(candidates.length, 0);
});

// ---------------------------------------------------------------------------
// 1件の処理（ops をモック）
// ---------------------------------------------------------------------------

function mockEnv({ merge, ledgerText = ledger(row('CMP-261124-0001'), row('CMP-261124-0002')), push = { ok: true }, changed = [LEDGER_PATH], autoMerge = { ok: true }, commentResult = { ok: true } } = {}) {
  const calls = [];
  const gitOps = {
    fetchMain: () => { calls.push('fetchMain'); return 'main0001'; },
    fetchHead: (p) => { calls.push('fetchHead'); return p.headRefOid; },
    mergeTree: () => { calls.push('mergeTree'); return merge ?? { ok: true, tree: 'tree0001', conflicts: [] }; },
    abortMerge: () => { calls.push('abortMerge'); },
    readFile: () => { calls.push('readFile'); return ledgerText; },
    changedPaths: () => { calls.push('changedPaths'); return changed; },
    commitMerge: () => { calls.push('commitMerge'); return 'merge0001'; },
    push: () => { calls.push('push'); return push; },
  };
  const comments = [];
  const autoMergeCalls = [];
  const ghOps = {
    listComments: (p) => { calls.push('listComments'); return p.comments?.nodes ?? []; },
    comment: (number, body) => { comments.push({ number, body }); return commentResult; },
    enableAutoMerge: (p, expectedHeadOid) => { calls.push('enableAutoMerge'); autoMergeCalls.push(expectedHeadOid); return autoMerge; },
  };
  return { calls, comments, autoMergeCalls, deps: { gitOps, ghOps, now: NOW, log: () => {}, sleep: async () => {}, checkStructure: checkTaskListStructure, botEmail: 'bot@example.com' } };
}

test('処理: 台帳以外にも衝突が残る → push せず merge を取り消してスキップする', async () => {
  const env = mockEnv({ merge: { ok: false, tree: 'tree0001', conflicts: [LEDGER_PATH, 'backend/src/A.java'] } });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'skip-conflict-outside-ledger');
  assert.match(result.reason, /backend\/src\/A\.java/);
  assert.ok(env.calls.includes('abortMerge'));
  assert.ok(!env.calls.includes('push'));
  assert.ok(!env.calls.includes('commitMerge'));
});

test('処理: merge 自体が失敗 → 取り消してスキップする', async () => {
  const env = mockEnv({ merge: { ok: false, conflicts: [], error: 'fatal: refusing to merge unrelated histories' } });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'skip-merge-failed');
  assert.ok(env.calls.includes('abortMerge'));
  assert.ok(!env.calls.includes('push'));
});

test('処理: 構造検査 NG（重複）→ push せず PR にコメントする', async () => {
  const env = mockEnv({ ledgerText: ledger(row('CMP-261124-0001', 'P3 未着手'), row('CMP-261124-0001', 'P2 実装中')) });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'structure-ng');
  assert.ok(!env.calls.includes('push'));
  assert.ok(!env.calls.includes('commitMerge'));
  assert.equal(env.comments.length, 1);
  assert.match(env.comments[0].body, /CMP-261124-0001/);
  assert.match(env.comments[0].body, /人手/);
});

test('処理: 構造検査 NG の同じ head への再コメントはしない（30分毎の再実行でコメントを積まない）', async () => {
  const env = mockEnv({ ledgerText: ledger(row('CMP-261124-0001'), row('CMP-261124-0001')) });
  const first = await processPullRequest(pr(), env.deps);
  assert.equal(first.outcome, 'structure-ng');
  const again = pr({ comments: { nodes: [{ body: env.comments[0].body, createdAt: NOW.toISOString() }] } });
  const env2 = mockEnv({ ledgerText: ledger(row('CMP-261124-0001'), row('CMP-261124-0001')) });
  await processPullRequest(again, env2.deps);
  assert.equal(env2.comments.length, 0);
});

test('処理: 正常 → push・auto-merge 予約・コメント（merge commit SHA 入り）', async () => {
  const env = mockEnv();
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'fixed');
  assert.ok(env.calls.includes('push'));
  assert.ok(env.calls.includes('enableAutoMerge'));
  assert.ok(env.calls.indexOf('push') < env.calls.indexOf('enableAutoMerge'));
  assert.equal(env.comments.length, 1);
  assert.match(env.comments[0].body, /merge0001/);
  assert.ok(env.comments[0].body.includes(FIX_MARKER));
});

test('処理: 既に auto-merge 予約済みなら予約し直さない', async () => {
  const env = mockEnv();
  const result = await processPullRequest(pr({ autoMergeRequest: { enabledAt: '2026-11-24T00:00:00Z' } }), env.deps);
  assert.equal(result.outcome, 'fixed');
  assert.ok(!env.calls.includes('enableAutoMerge'));
});

test('処理: auto-merge 予約に expectedHeadOid=作成した merge commit を渡す', async () => {
  const env = mockEnv();
  await processPullRequest(pr(), env.deps);
  assert.deepEqual(env.autoMergeCalls, ['merge0001']);
});

test('処理: push 後に head が動いて expectedHeadOid が不一致 → 再試行せず予約し直さない（ログとコメント）', async () => {
  const env = mockEnv({ autoMerge: { ok: false, headMismatch: true, error: 'head=other（期待 merge0001）' } });
  const logs = [];
  env.deps.log = (m) => logs.push(m);
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'fixed-head-moved');
  assert.equal(env.autoMergeCalls.length, 1);
  assert.ok(logs.some((l) => /予約し直さない/.test(l)));
  assert.equal(env.comments.length, 1);
  assert.match(env.comments[0].body, /予約し直していません/);
});

test('処理: push 後のコメント投稿失敗は結果に載せて返す（握りつぶさない）', async () => {
  const env = mockEnv({ commentResult: { ok: false, error: 'HTTP 403' } });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'fixed');
  assert.equal(result.commentError, 'HTTP 403');
});

test('処理: 構造検査 NG のコメント重複確認は PR の全コメント（ページング済み）で行う', async () => {
  const env0 = mockEnv({ ledgerText: ledger(row('CMP-261124-0001'), row('CMP-261124-0001')) });
  await processPullRequest(pr(), env0.deps);
  const filler = Array.from({ length: 120 }, (_, i) => ({ body: 'その他 ' + i, createdAt: NOW.toISOString() }));
  const old = pr({ comments: { nodes: [{ body: env0.comments[0].body, createdAt: NOW.toISOString() }, ...filler] } });
  const env = mockEnv({ ledgerText: ledger(row('CMP-261124-0001'), row('CMP-261124-0001')) });
  await processPullRequest(old, env.deps);
  assert.ok(env.calls.includes('listComments'));
  assert.equal(env.comments.length, 0);
});

test('処理: auto-merge 予約の失敗はコメントに残す', async () => {
  const env = mockEnv({ autoMerge: { ok: false, error: 'Pull request is in clean status' } });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'fixed-automerge-failed');
  assert.equal(env.comments.length, 1);
  assert.match(env.comments[0].body, /clean status/);
});

test('処理: push 競合（fast-forward 不可）は次回に回す', async () => {
  const env = mockEnv({ push: { ok: false, reason: 'non-fast-forward' } });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'skip-push-rejected');
  assert.ok(!env.calls.includes('enableAutoMerge'));
  assert.equal(env.comments.length, 0);
});

test('処理: head が取得中に動いた → スキップ', async () => {
  const env = mockEnv();
  env.deps.gitOps.fetchHead = () => 'head9999';
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'skip-head-moved');
  assert.ok(!env.calls.includes('mergeTree'));
});

test('処理: workflow ファイルが変わる merge は push しない（App に workflows 権限が無い）', async () => {
  const env = mockEnv({ changed: [LEDGER_PATH, '.github/workflows/backend-ci.yml'] });
  const result = await processPullRequest(pr(), env.deps);
  assert.equal(result.outcome, 'skip-workflow-change');
  assert.ok(!env.calls.includes('push'));
});

const fixCommit = (hoursAgo, { message = 'Merge origin/main\n\n' + FIX_COMMIT_TRAILER, email = 'someone@example.com' } = {}) => ({
  commit: { oid: 'c' + hoursAgo, message, committedDate: new Date(NOW - hoursAgo * 3600e3).toISOString(), author: { email }, committer: { email } },
});

test('上限: 直近24時間に bot が3回直した PR（commit 履歴で数える）はスキップしてコメントで知らせる', async () => {
  const target = pr({ commits: { nodes: [fixCommit(1), fixCommit(5), fixCommit(23)] } });
  assert.equal(countRecentFixes(target, NOW), 3);
  const env = mockEnv();
  const result = await processPullRequest(target, env.deps);
  assert.equal(result.outcome, 'skip-limit');
  assert.ok(!env.calls.includes('mergeTree'));
  assert.equal(env.comments.length, 1);
  assert.match(env.comments[0].body, /3回/);
});

test('上限: 24時間より前の修復は数えない', () => {
  assert.equal(countRecentFixes(pr({ commits: { nodes: [fixCommit(1), fixCommit(25), fixCommit(48)] } }), NOW), 1);
});

test('上限: 固定印が無くても作者/コミッタが bot の commit は数え、人の commit は数えない', () => {
  const nodes = [
    fixCommit(1, { message: 'Merge branch main', email: 'bot@example.com' }),
    fixCommit(2, { message: '人の修正', email: 'human@example.com' }),
    fixCommit(3),
  ];
  assert.equal(countRecentFixes(pr({ commits: { nodes } }), NOW, { botEmail: 'bot@example.com' }), 2);
  assert.equal(countRecentFixes(pr({ commits: { nodes } }), NOW), 1);
});

test('上限: 修復コメントを消しても commit 履歴で数える（コメントは数えない）', () => {
  const target = pr({ comments: { nodes: [1, 2, 3].map(() => ({ body: FIX_MARKER, createdAt: NOW.toISOString() })) }, commits: { nodes: [] } });
  assert.equal(countRecentFixes(target, NOW), 0);
});

test('上限: 上限到達の通知は24時間に1回だけ（コメントは全ページを見る）', async () => {
  const env = mockEnv();
  const first = pr({ commits: { nodes: [fixCommit(1), fixCommit(2), fixCommit(3)] } });
  await processPullRequest(first, env.deps);
  const env2 = mockEnv();
  const filler = Array.from({ length: 120 }, (_, i) => ({ body: 'その他 ' + i, createdAt: NOW.toISOString() }));
  const second = pr({ commits: first.commits, comments: { nodes: [{ body: env.comments[0].body, createdAt: NOW.toISOString() }, ...filler] } });
  const result = await processPullRequest(second, env2.deps);
  assert.equal(result.outcome, 'skip-limit');
  assert.equal(env2.comments.length, 0);
});

// ---------------------------------------------------------------------------
// 実際の git による統合試験（一時リポジトリ）
// ---------------------------------------------------------------------------

function sh(cwd, ...args) {
  const r = spawnSync('git', args, { cwd, encoding: 'utf8' });
  if (r.status !== 0) throw new Error('git ' + args.join(' ') + ' 失敗: ' + r.stderr);
  return r.stdout.trim();
}

function makeRealGit(cwd) {
  return (args) => {
    const r = spawnSync('git', args, {
      cwd,
      encoding: 'utf8',
      env: { ...process.env, GIT_AUTHOR_NAME: 't', GIT_AUTHOR_EMAIL: 't@example.com', GIT_COMMITTER_NAME: 't', GIT_COMMITTER_EMAIL: 't@example.com' },
    });
    return { code: r.status, stdout: r.stdout ?? '', stderr: r.stderr ?? '' };
  };
}

/** bare の origin と作業用 clone を作り、base（表）→ feature と main がそれぞれ編集した状態にする。 */
function setupRepos({ baseLedger, featureLedger, mainLedger, featureExtra, mainExtra }) {
  const root = mkdtempSync(join(tmpdir(), 'ledger-autofix-'));
  const origin = join(root, 'origin.git');
  const seed = join(root, 'seed');
  spawnSync('git', ['init', '--bare', '-b', 'main', origin]);
  spawnSync('git', ['clone', '-q', origin, seed]);
  const cfg = ['-c', 'user.name=t', '-c', 'user.email=t@example.com', '-c', 'core.autocrlf=false'];
  const commit = (files, msg) => {
    for (const [p, c] of Object.entries(files)) {
      const full = join(seed, p);
      spawnSync('node', ['-e', 'require("fs").mkdirSync(require("path").dirname(process.argv[1]),{recursive:true})', full]);
      writeFileSync(full, c);
    }
    sh(seed, ...cfg, 'add', '-A');
    sh(seed, ...cfg, 'commit', '-q', '-m', msg);
  };
  sh(seed, 'checkout', '-q', '-b', 'main');
  commit({ [LEDGER_PATH]: baseLedger, 'README.md': 'base\n' }, 'base');
  sh(seed, 'push', '-q', 'origin', 'main');
  sh(seed, 'checkout', '-q', '-b', 'feature/1-x');
  commit({ [LEDGER_PATH]: featureLedger, ...(featureExtra ?? {}) }, 'feature');
  sh(seed, 'push', '-q', 'origin', 'feature/1-x');
  sh(seed, 'checkout', '-q', 'main');
  commit({ [LEDGER_PATH]: mainLedger, ...(mainExtra ?? {}) }, 'main');
  sh(seed, 'push', '-q', 'origin', 'main');
  const featureOid = sh(seed, 'rev-parse', 'feature/1-x');
  const runner = join(root, 'runner');
  spawnSync('git', ['clone', '-q', origin, runner]);
  return { root, origin, runner, featureOid, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

const base = ledger(row('CMP-261124-0001', '完了'));
const tailA = ledger(row('CMP-261124-0001', '完了'), row('CMP-261124-1001', 'P3 未着手'));
const tailB = ledger(row('CMP-261124-0001', '完了'), row('CMP-261124-2002', 'P2 未着手'));

test('統合: union なしでは末尾追記どうしが衝突する（対照）', () => {
  const repos = setupRepos({ baseLedger: base, featureLedger: tailA, mainLedger: tailB });
  try {
    const ops = createGitOps({ git: makeRealGit(repos.runner) });
    const mainOid = ops.fetchMain();
    const headOid = ops.fetchHead({ headRefName: 'feature/1-x' });
    const result = ops.mergeTree(headOid, mainOid);
    assert.equal(result.ok, false);
    assert.deepEqual(result.conflicts, [LEDGER_PATH]);
  } finally {
    repos.cleanup();
  }
});

test('統合: union で末尾追記衝突を自動解消し、両行が残り、構造検査に合格し、fast-forward で push できる', async () => {
  const repos = setupRepos({ baseLedger: base, featureLedger: tailA, mainLedger: tailB });
  try {
    const git = makeRealGit(repos.runner);
    ensureUnionAttribute(git);
    const attrs = readFileSync(join(repos.runner, '.git', 'info', 'attributes'), 'utf8');
    assert.match(attrs, /docs\/task-list\.md merge=union/);
    const ops = createGitOps({ git });
    const mainOid = ops.fetchMain();
    const headOid = ops.fetchHead({ headRefName: 'feature/1-x' });
    assert.equal(headOid, repos.featureOid);
    const merged = ops.mergeTree(headOid, mainOid);
    assert.equal(merged.ok, true, JSON.stringify(merged));
    const text = ops.readFile(merged.tree, LEDGER_PATH);
    assert.ok(text.includes('CMP-261124-1001'));
    assert.ok(text.includes('CMP-261124-2002'));
    assert.equal(checkTaskListStructure(text).ok, true);

    // processPullRequest を実 git ＋ gh モックで通す
    const comments = [];
    const result = await processPullRequest(pr({ headRefName: 'feature/1-x', headRefOid: repos.featureOid }), {
      gitOps: ops,
      ghOps: { listComments: () => [], comment: (n, body) => { comments.push(body); return { ok: true }; }, enableAutoMerge: () => ({ ok: true }) },
      now: NOW, log: () => {}, sleep: async () => {}, checkStructure: checkTaskListStructure,
    });
    assert.equal(result.outcome, 'fixed', JSON.stringify(result));
    const remoteHead = sh(repos.origin, 'rev-parse', 'refs/heads/feature/1-x');
    assert.equal(remoteHead, result.mergeCommit);
    const parents = sh(repos.origin, 'rev-list', '--parents', '-n', '1', remoteHead).split(' ').slice(1);
    assert.deepEqual(parents, [repos.featureOid, mainOid]);
    assert.match(comments[0], new RegExp(result.mergeCommit));
    assert.ok(sh(repos.origin, 'log', '-1', '--format=%B', remoteHead).includes(FIX_COMMIT_TRAILER));
  } finally {
    repos.cleanup();
  }
});

test('統合: 台帳以外のファイルも衝突 → union でも衝突が残り、push しない', async () => {
  const repos = setupRepos({
    baseLedger: base, featureLedger: tailA, mainLedger: tailB,
    featureExtra: { 'README.md': 'feature\n' }, mainExtra: { 'README.md': 'main\n' },
  });
  try {
    const git = makeRealGit(repos.runner);
    ensureUnionAttribute(git);
    const ops = createGitOps({ git });
    const result = await processPullRequest(pr({ headRefName: 'feature/1-x', headRefOid: repos.featureOid }), {
      gitOps: ops,
      ghOps: { listComments: () => [], comment: () => assert.fail('コメントしない'), enableAutoMerge: () => assert.fail('予約しない') },
      now: NOW, log: () => {}, sleep: async () => {}, checkStructure: checkTaskListStructure,
    });
    assert.equal(result.outcome, 'skip-conflict-outside-ledger');
    assert.match(result.reason, /README\.md/);
    assert.equal(sh(repos.origin, 'rev-parse', 'refs/heads/feature/1-x'), repos.featureOid);
  } finally {
    repos.cleanup();
  }
});

test('統合: 同じ行の両側編集 → union で旧行と新行が両方残り、構造検査が重複を検出して push しない', async () => {
  const repos = setupRepos({
    baseLedger: ledger(row('CMP-261124-0001', 'P3 未着手')),
    featureLedger: ledger(row('CMP-261124-0001', 'P2 実装中')),
    mainLedger: ledger(row('CMP-261124-0001', '完了')),
  });
  try {
    const git = makeRealGit(repos.runner);
    ensureUnionAttribute(git);
    const ops = createGitOps({ git });
    const mainOid = ops.fetchMain();
    const merged = ops.mergeTree(repos.featureOid, mainOid);
    assert.equal(merged.ok, true);
    const text = ops.readFile(merged.tree, LEDGER_PATH);
    assert.ok(text.includes('P2 実装中') && text.includes('| 完了 |'));
    const check = checkTaskListStructure(text);
    assert.equal(check.ok, false);
    assert.match(check.errors.join('\n'), /重複: CMP-261124-0001/);

    const comments = [];
    const result = await processPullRequest(pr({ headRefName: 'feature/1-x', headRefOid: repos.featureOid }), {
      gitOps: ops,
      ghOps: { listComments: () => [], comment: (n, body) => { comments.push(body); return { ok: true }; }, enableAutoMerge: () => assert.fail('予約しない') },
      now: NOW, log: () => {}, sleep: async () => {}, checkStructure: checkTaskListStructure,
    });
    assert.equal(result.outcome, 'structure-ng');
    assert.equal(comments.length, 1);
    assert.equal(sh(repos.origin, 'rev-parse', 'refs/heads/feature/1-x'), repos.featureOid);
  } finally {
    repos.cleanup();
  }
});

test('統合: ensureUnionAttribute は二重に追記しない', () => {
  const repos = setupRepos({ baseLedger: base, featureLedger: tailA, mainLedger: tailB });
  try {
    const git = makeRealGit(repos.runner);
    ensureUnionAttribute(git);
    ensureUnionAttribute(git);
    const attrs = readFileSync(join(repos.runner, '.git', 'info', 'attributes'), 'utf8');
    assert.equal(attrs.match(/merge=union/g).length, 1);
  } finally {
    repos.cleanup();
  }
});

// ---------------------------------------------------------------------------
// workflow の静的検査
// ---------------------------------------------------------------------------

const repository = fileURLToPath(new URL('../', import.meta.url));
const readRepo = (p) => readFileSync(join(repository, p), 'utf8').replace(/\r\n/g, '\n');

test('workflow: push(main)・schedule・workflow_dispatch(dry_run) で起動し、多重実行を打ち切らずに防ぐ', () => {
  const wf = readRepo('.github/workflows/task-list-conflict-autofix.yml');
  assert.match(wf, /\n  push:\n    branches:\s*\[\s*main\s*\]/);
  assert.match(wf, /\n  schedule:\n    - cron: '\*\/30 \* \* \* \*'/);
  assert.match(wf, /\n  workflow_dispatch:\n    inputs:\n      dry_run:/);
  assert.match(wf, /\nconcurrency:\n  group: task-list-conflict-autofix\n  cancel-in-progress: false/);
  assert.match(wf, /\npermissions:\n  contents: read\n\n/);
  assert.match(wf, /actions\/create-github-app-token@v\d/);
  assert.match(wf, /vars\.LEDGER_BOT_APP_ID/);
  assert.match(wf, /secrets\.LEDGER_BOT_PRIVATE_KEY/);
  assert.match(wf, /ref: main/);
  assert.ok(!/pull_request_target/.test(wf), 'pull_request_target を使わない');
});

test('workflow: 新試験が PR 軽量検査で走る', () => {
  const light = readRepo('.github/workflows/pr-light-checks.yml');
  assert.ok(light.includes('scripts/task-list-conflict-autofix.test.mjs'));
});
