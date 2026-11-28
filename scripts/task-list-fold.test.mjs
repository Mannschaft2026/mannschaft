import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { CHANGES_DIR, fsLedgerReader, ledgerRowMap, newFileSkeleton, readLedger } from './task-list-ledger.mjs';
import { checkVirtualHead } from './task-list-structure-check.mjs';
import { memoryLedgerReader } from './test-support/task-list-split.mjs';
import {
  FOLD_AGE_THRESHOLD_MS,
  FOLD_BRANCH,
  FOLD_CMP_ID,
  FOLD_COUNT_THRESHOLD,
  buildFoldFiles,
  foldPullRequestBody,
  planFold,
  runFold,
  shouldFold,
  verifyFold,
} from './task-list-fold.mjs';

// CMP-261124-1222 ④ 段2: 畳み込み bot（scripts/task-list-fold.mjs）。
// AC-5（畳み込み前後で実効台帳一致・queue 待ち中の別申告 AC-5a〜c）・AC-6 の「bot の正しい畳み込みは緑」セル・
// 起動閾値の境界・途中失敗（push 拒否・API 失敗）・dry-run で書き込みが無いこと。GitHub API と git はモック。

const repoRoot = fileURLToPath(new URL('../', import.meta.url));
const H = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const IDX = '# 台帳（索引）\n\n前置き。\n';
const row = (id, status = 'P2 実装中', extra = '条件') => '| ' + id + ' | 戦役 | ' + status + ' | — | ' + extra + ' | 証拠 | ledger |';
const sha = (s) => 'sha256:' + createHash('sha256').update(s.replace(/\r/g, ''), 'utf8').digest('hex');
const meta = (op, id, b) => '<!-- ledger-change v1 op=' + op + ' id=' + id + ' base=' + b + ' -->';
const add = (line, id) => meta('add', id, 'none') + '\n' + line + '\n';
const upd = (from, to, id) => meta('update', id, sha(from)) + '\n' + to + '\n';
const cname = (id, t, r = '000001') => CHANGES_DIR + '/' + id + '--' + t + '--' + r + '.md';
const A0 = row('CMP-261124-1222');
const A1 = row('CMP-261124-1222', 'P2 実装中（④着手）');
const A2 = row('CMP-261124-1222', 'P2 実装中（④畳み込み）');
const M11 = '# 2026-11\n\n' + H + '\n' + row('CMP-261101-0000', '完了') + '\n' + A0 + '\n';
const LEG = '# legacy\n\n' + H + '\n' + row('CMP-003', 'P5 未着手') + '\n';
const base = { 'docs/task-list.md': IDX, 'docs/task-list/2026-11.md': M11, 'docs/task-list/legacy.md': LEG };
const read = (files) => readLedger(memoryLedgerReader(files), { requireRows: false });
const T0 = Date.parse('2026-11-28T00:00:00Z');

/** 畳み込み結果の木（パス→本文）。 */
async function foldedTree(files) {
  const ledger = await read(files);
  const plan = planFold(ledger);
  return { ledger, plan, tree: buildFoldFiles(files, plan) };
}

/** 3者マージ（台帳だけ）: base→ours と base→theirs の差分を重ねる。両側が同じパスを違う内容に変えたら例外。 */
function merge3(baseFiles, ours, theirs) {
  const out = {};
  for (const p of new Set([...Object.keys(baseFiles), ...Object.keys(ours), ...Object.keys(theirs)])) {
    const b = baseFiles[p];
    const o = ours[p];
    const t = theirs[p];
    let v;
    if (o === t) v = o;
    else if (o === b) v = t;
    else if (t === b) v = o;
    else throw new Error('衝突: ' + p);
    if (v !== undefined) out[p] = v;
  }
  return out;
}

test('申告0件なら planFold は何も畳み込まず、起動もしない', async () => {
  const { plan } = await foldedTree(base);
  assert.equal(plan.folded.length, 0);
  assert.equal(plan.removed.length, 0);
  assert.deepEqual(plan.files, {});
  assert.equal(shouldFold({ plan, now: T0, mode: 'force' }).run, false);
  assert.equal(shouldFold({ plan, now: T0, mode: 'threshold' }).run, false);
});

test('AC-5: add と update の連鎖を月別へ畳み込み、申告を消し、前後で実効台帳（ID→行）が一致する', async () => {
  const files = {
    ...base,
    [cname('CMP-261124-1222', '20261124T010000Z')]: upd(A0, A1, 'CMP-261124-1222'),
    [cname('CMP-261124-1222', '20261124T020000Z')]: upd(A1, A2, 'CMP-261124-1222'),
    [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900'), 'CMP-261127-0900'),
  };
  const { ledger, plan, tree } = await foldedTree(files);
  assert.equal(plan.folded.length, 3);
  assert.deepEqual(plan.kept, []);
  assert.ok(!Object.keys(tree).some((p) => p.startsWith(CHANGES_DIR + '/')));
  assert.equal(tree['docs/task-list/2026-11.md'], '# 2026-11\n\n' + H + '\n' + row('CMP-261101-0000', '完了') + '\n' + A2 + '\n' + row('CMP-261127-0900') + '\n');
  assert.equal(tree['docs/task-list.md'], IDX);
  const after = await read(tree);
  assert.deepEqual(after.changes.entries, []);
  assert.deepEqual(Object.fromEntries(ledgerRowMap(after)), Object.fromEntries(ledgerRowMap(ledger)));
  assert.equal((await verifyFold(ledger, tree)).ok, true);
});

test('AC-6（bot の正しい畳み込み）: PR 側（mb=main）・merge_group 側の等式検査が緑で、畳み込みと分類され警告も無い', async () => {
  const files = {
    ...base,
    [cname('CMP-261124-1222', '20261124T010000Z')]: upd(A0, A1, 'CMP-261124-1222'),
    [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900'), 'CMP-261127-0900'),
  };
  const { ledger, tree } = await foldedTree(files);
  const head = await read(tree);
  const pr = checkVirtualHead({ mergeBase: ledger, base: ledger, head });
  assert.deepEqual(pr.violations, []);
  assert.deepEqual(pr.warnings, []);
  assert.equal(pr.fold, true);
  assert.equal(pr.removedChanges.length, 2);
  const group = checkVirtualHead({ mergeBase: ledger, head });
  assert.equal(group.ok, true);
});

test('並び（P1-2）: 月別の既存順のあとに add を（ファイル名時刻, 乱数）順で末尾に足す', async () => {
  const files = {
    ...base,
    [cname('CMP-261126-0001', '20261126T000000Z', '900000')]: add(row('CMP-261126-0001'), 'CMP-261126-0001'),
    [cname('CMP-261125-0001', '20261126T000000Z', '100000')]: add(row('CMP-261125-0001'), 'CMP-261125-0001'),
    [cname('CMP-261120-0001', '20261120T000000Z', '500000')]: add(row('CMP-261120-0001'), 'CMP-261120-0001'),
  };
  const { tree } = await foldedTree(files);
  const ids = tree['docs/task-list/2026-11.md'].split('\n').filter((l) => l.startsWith('| CMP')).map((l) => l.split('|')[1].trim());
  assert.deepEqual(ids, ['CMP-261101-0000', 'CMP-261124-1222', 'CMP-261120-0001', 'CMP-261125-0001', 'CMP-261126-0001']);
});

test('境界: 月またぎ（261130-2359 → 261201-0000）は既存月の末尾と、新しい月のファイル（雛形＋行）に分かれる', async () => {
  const files = {
    ...base,
    [cname('CMP-261130-2359', '20261130T145900Z')]: add(row('CMP-261130-2359'), 'CMP-261130-2359'),
    [cname('CMP-261201-0000', '20261130T150000Z')]: add(row('CMP-261201-0000'), 'CMP-261201-0000'),
  };
  const { ledger, plan, tree } = await foldedTree(files);
  assert.ok(tree['docs/task-list/2026-11.md'].endsWith(A0 + '\n' + row('CMP-261130-2359') + '\n'));
  assert.equal(tree['docs/task-list/2026-12.md'], newFileSkeleton('docs/task-list/2026-12.md') + row('CMP-261201-0000') + '\n');
  assert.deepEqual(Object.keys(plan.files).sort(), ['docs/task-list/2026-11.md', 'docs/task-list/2026-12.md']);
  const v = await verifyFold(ledger, tree);
  assert.equal(v.ok, true, v.reasons.join('\n'));
});

test('違反（fork・孤児・重複・形式不正）を含む行の申告は畳み込まずに残し、他の行だけ畳み込む', async () => {
  const fork1 = cname('CMP-261124-1222', '20261124T010000Z', '000001');
  const fork2 = cname('CMP-261124-1222', '20261124T010000Z', '000002');
  const orphan = cname('CMP-261101-0000', '20261124T030000Z');
  const dup = cname('CMP-003', '20261124T040000Z');
  const dupAdd1 = cname('CMP-261127-1111', '20261127T000000Z', '000001');
  const dupAdd2 = cname('CMP-261127-1111', '20261127T000000Z', '000002');
  const broken = cname('CMP-261127-2222', '20261127T000000Z');
  const brokenOk = cname('CMP-261127-2222', '20261127T000001Z');
  const good = cname('CMP-261127-0900', '20261127T000000Z');
  const files = {
    ...base,
    [fork1]: upd(A0, A1, 'CMP-261124-1222'),
    [fork2]: upd(A0, A2, 'CMP-261124-1222'),
    [orphan]: upd(row('CMP-261101-0000', 'P1 存在しない版'), row('CMP-261101-0000', 'P1 孤児'), 'CMP-261101-0000'),
    [dup]: add(row('CMP-003', 'P4 重複'), 'CMP-003'),
    [dupAdd1]: add(row('CMP-261127-1111'), 'CMP-261127-1111'),
    [dupAdd2]: add(row('CMP-261127-1111', 'P3 二重'), 'CMP-261127-1111'),
    [broken]: meta('add', 'CMP-261127-2222', 'none') + '\n| CMP-261127-2222 | 列不足 |\n',
    [brokenOk]: add(row('CMP-261127-2222'), 'CMP-261127-2222'),
    [good]: add(row('CMP-261127-0900'), 'CMP-261127-0900'),
  };
  const { ledger, plan, tree } = await foldedTree(files);
  assert.deepEqual(plan.folded.map((e) => e.path), [good]);
  assert.deepEqual(plan.kept.map((k) => k.path).sort(), [broken, brokenOk, dup, dupAdd1, dupAdd2, fork1, fork2, orphan].sort());
  for (const k of plan.kept) assert.ok(k.reason.length > 0);
  for (const p of [fork1, fork2, orphan, dup, dupAdd1, dupAdd2, broken, brokenOk]) assert.equal(tree[p], files[p], p + ' は残す');
  assert.equal(tree[good], undefined);
  assert.equal(tree['docs/task-list/legacy.md'], LEG);
  const v = await verifyFold(ledger, tree);
  assert.equal(v.ok, true, v.reasons.join('\n'));
  // 違反は残る（構造検査・番人が引き続き赤にする）
  const after = await read(tree);
  assert.deepEqual(after.changes.problems.map((p) => p.kind).sort(), ledger.changes.problems.map((p) => p.kind).sort());
});

test('verifyFold は実効台帳が変わる畳み込み（行の取りこぼし・申告の消し過ぎ）を拒む', async () => {
  const c = cname('CMP-261127-0900', '20261127T000000Z');
  const files = { ...base, [c]: add(row('CMP-261127-0900'), 'CMP-261127-0900') };
  const ledger = await read(files);
  const dropped = { ...base };
  const v = await verifyFold(ledger, dropped);
  assert.equal(v.ok, false);
  assert.ok(v.reasons.length > 0);
});

// AC-5a〜c: 畳み込み PR（F, main M0 から作成）が queue 待ちのあいだに、別の申告 X が main に入って M1 になる。
// merge_group は base=M1・group head=M1 に F を重ねたもの。PR 側は mb=M0・base=M1・head=F。どちらも緑で、実効台帳は M1 と一致。
async function assertQueueRace(m0, x) {
  const { tree: f } = await foldedTree(m0);
  const m1 = { ...m0, ...x };
  const g = merge3(m0, f, m1);
  const [lM0, lM1, lF, lG] = await Promise.all([read(m0), read(m1), read(f), read(g)]);
  const pr = checkVirtualHead({ mergeBase: lM0, base: lM1, head: lF });
  assert.deepEqual(pr.violations, [], 'PR 側');
  const group = checkVirtualHead({ mergeBase: lM1, head: lG });
  assert.deepEqual(group.violations, [], 'merge_group 側');
  assert.deepEqual(group.warnings, []);
  assert.deepEqual(Object.fromEntries(ledgerRowMap(lG)), Object.fromEntries(ledgerRowMap(lM1)));
  assert.deepEqual(lG.changes.problems, []);
  return { lG, lM1 };
}

test('AC-5a: 同じ月で先に作成した（ファイル名時刻が古い）add が、畳み込み PR の queue 待ち中に後から main へ到着しても等式成立', async () => {
  const m0 = { ...base, [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900'), 'CMP-261127-0900') };
  const x = { [cname('CMP-261126-0800', '20261126T000000Z')]: add(row('CMP-261126-0800'), 'CMP-261126-0800') };
  await assertQueueRace(m0, x);
});

test('AC-5b: 同時刻で乱数だけ違う add が queue 待ち中に到着しても等式成立', async () => {
  const m0 = { ...base, [cname('CMP-261127-0900', '20261127T000000Z', '500000')]: add(row('CMP-261127-0900'), 'CMP-261127-0900') };
  const x = { [cname('CMP-261127-0901', '20261127T000000Z', '100000')]: add(row('CMP-261127-0901'), 'CMP-261127-0901') };
  await assertQueueRace(m0, x);
});

test('AC-5a: 畳み込む行への後続 update（base=畳み込まれた行）が queue 待ち中に到着しても連鎖が繋がる', async () => {
  const m0 = { ...base, [cname('CMP-261124-1222', '20261124T010000Z')]: upd(A0, A1, 'CMP-261124-1222') };
  const x = { [cname('CMP-261124-1222', '20261128T000000Z')]: upd(A1, A2, 'CMP-261124-1222') };
  const { lG } = await assertQueueRace(m0, x);
  assert.equal(ledgerRowMap(lG).get('CMP-261124-1222'), A2);
});

test('AC-5c: queue の先行エントリ（別申告の PR）が脱落して group が M0 の上に再構築されても等式成立', async () => {
  const m0 = { ...base, [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900'), 'CMP-261127-0900') };
  const { tree: f } = await foldedTree(m0);
  const ahead = { [cname('CMP-261127-0950', '20261127T005000Z')]: add(row('CMP-261127-0950'), 'CMP-261127-0950') };
  // 先行エントリ込みの group（M0+P の上に F）
  const withAhead = merge3(m0, f, { ...m0, ...ahead });
  const lA = await read({ ...m0, ...ahead });
  assert.equal(checkVirtualHead({ mergeBase: lA, head: await read(withAhead) }).ok, true);
  // 先行エントリ脱落 → M0 の上に F だけで再構築
  const lM0 = await read(m0);
  const rebuilt = await read(merge3(m0, f, m0));
  const r = checkVirtualHead({ mergeBase: lM0, head: rebuilt });
  assert.equal(r.ok, true);
  assert.deepEqual(r.warnings, []);
  assert.deepEqual(Object.fromEntries(ledgerRowMap(rebuilt)), Object.fromEntries(ledgerRowMap(lM0)));
});

// ---------------------------------------------------------------------------
// 起動閾値
// ---------------------------------------------------------------------------

function plannedAdds(n, ageMs) {
  const files = { ...base };
  for (let i = 0; i < n; i++) {
    const t = new Date(T0 - ageMs + i * 1000).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
    const id = 'CMP-2611' + String(10 + Math.floor(i / 60)).padStart(2, '0') + '-' + String(1000 + i).slice(-4);
    files[cname(id, t)] = add(row(id), id);
  }
  return files;
}

test('閾値: 件数 N=19 は起動しない・N=20 は起動する（最古が新しいとき）', async () => {
  assert.equal(FOLD_COUNT_THRESHOLD, 20);
  const p19 = planFold(await read(plannedAdds(19, 60_000)));
  const p20 = planFold(await read(plannedAdds(20, 60_000)));
  assert.equal(p19.folded.length, 19);
  assert.equal(shouldFold({ plan: p19, now: T0, mode: 'threshold' }).run, false);
  assert.equal(shouldFold({ plan: p20, now: T0, mode: 'threshold' }).run, true);
});

test('閾値: 最古の申告がちょうど6時間は起動しない・6時間を超えたら起動する', async () => {
  assert.equal(FOLD_AGE_THRESHOLD_MS, 6 * 3600 * 1000);
  const plan = planFold(await read(plannedAdds(1, FOLD_AGE_THRESHOLD_MS)));
  assert.equal(shouldFold({ plan, now: T0, mode: 'threshold' }).run, false);
  assert.equal(shouldFold({ plan, now: T0 + 1000, mode: 'threshold' }).run, true);
});

test('閾値: 定期実行・手動（force）は1件でも起動し、畳み込める申告が0件なら起動しない', async () => {
  const plan = planFold(await read(plannedAdds(1, 1000)));
  assert.equal(shouldFold({ plan, now: T0, mode: 'force' }).run, true);
  assert.equal(shouldFold({ plan, now: T0, mode: 'threshold' }).run, false);
});

test('閾値: 違反で残す申告は件数・最古の判定に数えない', async () => {
  const files = plannedAdds(19, 60_000);
  files[cname('CMP-261101-0000', '20261101T000000Z')] = upd(row('CMP-261101-0000', '存在しない'), row('CMP-261101-0000', 'P1 x'), 'CMP-261101-0000');
  const plan = planFold(await read(files));
  assert.equal(plan.kept.length, 1);
  assert.equal(shouldFold({ plan, now: T0, mode: 'threshold' }).run, false);
});

// ---------------------------------------------------------------------------
// runFold（git・GitHub はモック）
// ---------------------------------------------------------------------------

const WRITE_OPS = new Set(['writeTree', 'commitTree', 'forcePush', 'createPullRequest', 'updatePullRequest', 'enableAutoMerge', 'disableAutoMerge', 'closePullRequest']);

function mocks({ files, pr = null, prSeq = null, remote = null, remoteInfo = null, fail = {}, pushResult = { ok: true }, mainOid = 'main1' } = {}) {
  const calls = [];
  const rec = (name, args, value) => {
    calls.push({ name, args });
    if (fail[name]) throw new Error(name + ' 失敗（API 5xx）');
    return value;
  };
  const gitOps = {
    fetchMain: () => rec('fetchMain', [], mainOid),
    ledgerReader: (oid) => rec('ledgerReader', [oid], memoryLedgerReader(files)),
    treeOf: (oid) => rec('treeOf', [oid], 'tree-of-' + oid),
    fetchBranch: (b) => rec('fetchBranch', [b], remote),
    commitInfo: (oid) => rec('commitInfo', [oid], remoteInfo),
    writeTree: (baseTree, changed, removed) => rec('writeTree', [baseTree, changed, removed], 'newtree'),
    commitTree: (tree, parent, msg) => rec('commitTree', [tree, parent, msg], 'foldcommit'),
    forcePush: (commit, branch, expected) => rec('forcePush', [commit, branch, expected], pushResult),
  };
  const ghOps = {
    // prSeq: 呼ぶたびに次の状態を返す（queue 状態が途中で遷移する注入）。尽きたら最後の状態のまま
    findFoldPullRequest: () => rec('findFoldPullRequest', [], prSeq ? prSeq[Math.min(calls.filter((c) => c.name === 'findFoldPullRequest').length, prSeq.length - 1)] : pr),
    createPullRequest: (a) => rec('createPullRequest', [a], { id: 'PR_new', number: 9001, headRefOid: 'foldcommit' }),
    updatePullRequest: (n, a) => rec('updatePullRequest', [n, a], undefined),
    enableAutoMerge: (p, oid) => rec('enableAutoMerge', [p, oid], { ok: true }),
    disableAutoMerge: (p) => rec('disableAutoMerge', [p], undefined),
    closePullRequest: (n, body) => rec('closePullRequest', [n, body], undefined),
  };
  return { gitOps, ghOps, calls, writes: () => calls.filter((c) => WRITE_OPS.has(c.name)) };
}

const run = (m, opts = {}) => runFold({ gitOps: m.gitOps, ghOps: m.ghOps, now: T0, mode: 'force', apply: true, log: () => {}, sleep: async () => {}, ...opts });
const oneChange = { ...base, [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900'), 'CMP-261127-0900') };

test('runFold: 申告0件なら何も書かない（apply でも）', async () => {
  const m = mocks({ files: base });
  const r = await run(m);
  assert.equal(r.outcome, 'nothing-to-fold');
  assert.equal(r.exitCode, 0);
  assert.deepEqual(m.writes(), []);
});

test('runFold: dry-run（既定）は計画を出すだけで、git・GitHub への書き込みが一切無い', async () => {
  const m = mocks({ files: oneChange });
  const logs = [];
  const r = await run(m, { apply: false, log: (x) => logs.push(x) });
  assert.equal(r.outcome, 'dry-run');
  assert.equal(r.exitCode, 0);
  assert.deepEqual(m.writes(), []);
  assert.ok(logs.some((l) => l.includes('dry-run')));
  // 既定（apply 未指定）も dry-run
  const m2 = mocks({ files: oneChange });
  const r2 = await runFold({ gitOps: m2.gitOps, ghOps: m2.ghOps, now: T0, mode: 'force', log: () => {}, sleep: async () => {} });
  assert.equal(r2.outcome, 'dry-run');
  assert.deepEqual(m2.writes(), []);
});

test('runFold: 畳み込み PR が無ければ main の上に commit して bot 専用ブランチへ push し、PR を作って auto-merge を予約する', async () => {
  const m = mocks({ files: oneChange });
  const r = await run(m);
  assert.equal(r.outcome, 'created');
  assert.equal(r.exitCode, 0);
  const names = m.writes().map((c) => c.name);
  assert.deepEqual(names, ['writeTree', 'commitTree', 'forcePush', 'createPullRequest', 'enableAutoMerge']);
  const [, changed, removed] = m.calls.find((c) => c.name === 'writeTree').args;
  assert.deepEqual(removed, [cname('CMP-261127-0900', '20261127T000000Z')]);
  assert.ok(changed['docs/task-list/2026-11.md'].endsWith(row('CMP-261127-0900') + '\n'));
  assert.equal(m.calls.find((c) => c.name === 'commitTree').args[1], 'main1');
  assert.deepEqual(m.calls.find((c) => c.name === 'forcePush').args, ['foldcommit', FOLD_BRANCH, null]);
  const created = m.calls.find((c) => c.name === 'createPullRequest').args[0];
  assert.equal(created.head, FOLD_BRANCH);
  assert.equal(created.base, 'main');
  assert.deepEqual(m.calls.find((c) => c.name === 'enableAutoMerge').args[1], 'foldcommit');
});

test('runFold: 開いている畳み込み PR が merge queue 投入中なら触らない', async () => {
  const m = mocks({ files: oneChange, pr: { id: 'PR_1', number: 77, headRefOid: 'old', isInMergeQueue: true } });
  const r = await run(m);
  assert.equal(r.outcome, 'skip-in-queue');
  assert.equal(r.exitCode, 0);
  assert.deepEqual(m.writes(), []);
});

test('runFold: 未投入の畳み込み PR は最新 main から作り直し、bot 専用ブランチを lease 付きで force push して PR を更新する（新しい PR は作らない）', async () => {
  const m = mocks({ files: oneChange, pr: { id: 'PR_1', number: 77, headRefOid: 'old', isInMergeQueue: false }, remote: 'old', remoteInfo: { tree: 'oldtree', parents: ['main0'] } });
  const r = await run(m);
  assert.equal(r.outcome, 'updated');
  assert.deepEqual(m.calls.find((c) => c.name === 'forcePush').args, ['foldcommit', FOLD_BRANCH, 'old']);
  assert.ok(m.writes().some((c) => c.name === 'updatePullRequest' && c.args[0] === 77));
  assert.ok(!m.writes().some((c) => c.name === 'createPullRequest'));
});

test('runFold: 既存 PR の head が最新 main の上の同じ木なら push しない（予約だけ確かめる）', async () => {
  const m = mocks({ files: oneChange, pr: { id: 'PR_1', number: 77, headRefOid: 'old', isInMergeQueue: false, autoMergeRequest: null }, remote: 'old', remoteInfo: { tree: 'newtree', parents: ['main1'] } });
  const r = await run(m);
  assert.equal(r.outcome, 'up-to-date');
  assert.ok(!m.writes().some((c) => c.name === 'forcePush' || c.name === 'commitTree'));
  assert.ok(m.writes().some((c) => c.name === 'enableAutoMerge'));
});

test('runFold: 閾値未満（threshold モード・1件・最古1時間）は何も書かない', async () => {
  const m = mocks({ files: { ...base, [cname('CMP-261127-0900', '20261127T230000Z')]: add(row('CMP-261127-0900'), 'CMP-261127-0900') } });
  const r = await run(m, { mode: 'threshold' });
  assert.equal(r.outcome, 'below-threshold');
  assert.deepEqual(m.writes(), []);
});

test('途中失敗: push 拒否は PR を作らず次回に回す（exit 0）', async () => {
  const m = mocks({ files: oneChange, pushResult: { ok: false, reason: 'stale info' } });
  const r = await run(m);
  assert.equal(r.outcome, 'skip-push-rejected');
  assert.equal(r.exitCode, 0);
  assert.ok(!m.writes().some((c) => c.name === 'createPullRequest' || c.name === 'enableAutoMerge'));
});

test('途中失敗: PR 一覧の API 失敗は不在と扱わず、何も書かずに赤（exit 1）で次回に回す', async () => {
  const m = mocks({ files: oneChange, fail: { findFoldPullRequest: true } });
  const r = await run(m);
  assert.equal(r.outcome, 'api-failed');
  assert.equal(r.exitCode, 1);
  assert.deepEqual(m.writes(), []);
});

test('途中失敗: push 後の PR 作成が API 失敗なら赤で終わり、次回はブランチがあっても PR を作り直す', async () => {
  const m = mocks({ files: oneChange, fail: { createPullRequest: true } });
  const r = await run(m);
  assert.equal(r.outcome, 'api-failed');
  assert.equal(r.exitCode, 1);
  const m2 = mocks({ files: oneChange, remote: 'foldcommit', remoteInfo: { tree: 'newtree', parents: ['main1'] } });
  const r2 = await run(m2);
  assert.equal(r2.outcome, 'created');
  assert.ok(!m2.writes().some((c) => c.name === 'forcePush'), '同じ内容は push し直さない');
  assert.ok(m2.writes().some((c) => c.name === 'createPullRequest'));
});

test('runFold: 畳み込める申告が無くなった未投入の畳み込み PR は閉じる', async () => {
  const m = mocks({ files: base, pr: { id: 'PR_1', number: 77, headRefOid: 'old', isInMergeQueue: false } });
  const r = await run(m);
  assert.equal(r.outcome, 'closed-stale');
  assert.deepEqual(m.writes().map((c) => c.name), ['closePullRequest']);
});

test('PR 本文の CMP-ID は CMP-261124-1222 だけ（畳み込む行の ID を書かない）', async () => {
  const files = { ...oneChange, [cname('CMP-261124-1222', '20261124T010000Z')]: upd(A0, A1, 'CMP-261124-1222') };
  const plan = planFold(await read(files));
  const body = foldPullRequestBody(plan, 'main1');
  assert.equal(FOLD_CMP_ID, 'CMP-261124-1222');
  assert.deepEqual([...new Set(body.match(/CMP-\d{6}-\d{4}|CMP-\d+/g))], [FOLD_CMP_ID]);
  const m = mocks({ files });
  await run(m);
  const created = m.calls.find((c) => c.name === 'createPullRequest').args[0];
  assert.deepEqual([...new Set((created.title + '\n' + created.body).match(/CMP-\d{6}-\d{4}|CMP-\d+/g))], [FOLD_CMP_ID]);
  assert.deepEqual([...new Set(m.calls.find((c) => c.name === 'commitTree').args[2].match(/CMP-\d{6}-\d{4}|CMP-\d+/g))], [FOLD_CMP_ID]);
});

test('実リポジトリの台帳: 畳み込み計画を作れて自己検査（等式）に合格する', async () => {
  const ledger = await readLedger(fsLedgerReader(repoRoot), { requireRows: true });
  const plan = planFold(ledger);
  const files = Object.fromEntries([...ledger.rawFiles.filter((f) => f.content != null).map((f) => [f.path, f.content]), ...ledger.changes.entries.map((e) => [e.path, e.content])]);
  const v = await verifyFold(ledger, buildFoldFiles(files, plan));
  assert.equal(v.ok, true, v.reasons.join('\n'));
});

// ---------------------------------------------------------------------------
// workflow（.github/workflows/task-list-fold.yml）
// ---------------------------------------------------------------------------

test('workflow: 既定 dry-run（LEDGER_FOLD_ENABLED が true のときだけ --apply）・App token・直列化・起動条件', () => {
  const y = readFileSync(new URL('../.github/workflows/task-list-fold.yml', import.meta.url), 'utf8');
  assert.match(y, /vars\.LEDGER_FOLD_ENABLED == 'true'/);
  assert.match(y, /--apply/);
  assert.match(y, /vars\.LEDGER_BOT_APP_ID/);
  assert.match(y, /secrets\.LEDGER_BOT_PRIVATE_KEY/);
  assert.match(y, /actions\/create-github-app-token@v2/);
  assert.match(y, /concurrency:\s*\n\s*group: task-list-fold\s*\n\s*cancel-in-progress: false/);
  assert.match(y, /workflow_dispatch:/);
  assert.match(y, /schedule:/);
  assert.match(y, /docs\/task-list\/changes\/\*\*/);
  assert.doesNotMatch(y, /pull_request/);
  assert.match(y, /node scripts\/task-list-fold\.mjs/);
});


// ---------------------------------------------------------------------------
// auto-merge 予約済みの畳み込み PR の作り直し（検分 P2: 予約が残ったまま push すると、push 前に GitHub が自動で
// queue に投入しうる。queue 投入では SHA が変わらず force-with-lease で防げない）
// ---------------------------------------------------------------------------

const reserved = { id: 'PR_1', number: 77, headRefOid: 'old', isInMergeQueue: false, mergeQueueEntry: null, autoMergeRequest: { enabledAt: '2026-11-27T00:00:00Z' } };
const reservedRemote = { remote: 'old', remoteInfo: { tree: 'oldtree', parents: ['main0'] } };

test('P2: 予約済みの畳み込み PR は、予約解除 → queue 外を再確認 → force-with-lease → 再予約の順で作り直す', async () => {
  const unreserved = { ...reserved, autoMergeRequest: null };
  const m = mocks({ files: oneChange, prSeq: [reserved, unreserved], ...reservedRemote });
  const r = await run(m);
  assert.equal(r.outcome, 'updated');
  const seq = m.calls.map((c) => c.name).filter((n) => ['disableAutoMerge', 'findFoldPullRequest', 'forcePush', 'enableAutoMerge'].includes(n));
  assert.deepEqual(seq, ['findFoldPullRequest', 'disableAutoMerge', 'findFoldPullRequest', 'forcePush', 'enableAutoMerge']);
  assert.equal(m.calls.find((c) => c.name === 'enableAutoMerge').args[1], 'foldcommit');
});

test('P2: 予約解除の後の再確認で queue 投入中（解除前に投入済み）なら push せずに終える', async () => {
  const queued = { ...reserved, autoMergeRequest: null, isInMergeQueue: true };
  const m = mocks({ files: oneChange, prSeq: [reserved, queued], ...reservedRemote });
  const r = await run(m);
  assert.equal(r.outcome, 'skip-in-queue');
  assert.equal(r.exitCode, 0);
  assert.ok(m.writes().some((c) => c.name === 'disableAutoMerge'));
  assert.ok(!m.writes().some((c) => ['forcePush', 'updatePullRequest', 'enableAutoMerge'].includes(c.name)));
});

test('P2: isInMergeQueue=false でも mergeQueueEntry があれば queue 投入中とみなして触らない（最初の確認・再確認の両方）', async () => {
  const entry = { ...reserved, mergeQueueEntry: { state: 'AWAITING_CHECKS' } };
  const m1 = mocks({ files: oneChange, pr: entry, ...reservedRemote });
  assert.equal((await run(m1)).outcome, 'skip-in-queue');
  assert.deepEqual(m1.writes(), []);
  const m2 = mocks({ files: oneChange, prSeq: [reserved, { ...entry, autoMergeRequest: null }], ...reservedRemote });
  assert.equal((await run(m2)).outcome, 'skip-in-queue');
  assert.ok(!m2.writes().some((c) => c.name === 'forcePush'));
});

test('P2: 予約が無い PR でも push 直前に queue 外を再確認し、再確認の直後に queued へ変わっていたら push しない', async () => {
  const free = { ...reserved, autoMergeRequest: null };
  const m = mocks({ files: oneChange, prSeq: [free, { ...free, isInMergeQueue: true }], ...reservedRemote });
  const r = await run(m);
  assert.equal(r.outcome, 'skip-in-queue');
  assert.ok(!m.writes().some((c) => c.name === 'disableAutoMerge' || c.name === 'forcePush'));
});

test('P2: 予約解除の API 失敗は push せずに赤で終える', async () => {
  const m = mocks({ files: oneChange, prSeq: [reserved], fail: { disableAutoMerge: true }, ...reservedRemote });
  const r = await run(m);
  assert.equal(r.outcome, 'api-failed');
  assert.equal(r.exitCode, 1);
  assert.ok(!m.writes().some((c) => c.name === 'forcePush'));
});

test('P2: 予約解除〜再予約の間で失敗しても、次回は予約なしの PR を正しく作り直して再予約する', async () => {
  const unreserved = { ...reserved, autoMergeRequest: null };
  // 1回目: 解除後の push が拒否される（予約なし・head は old のまま）
  const m1 = mocks({ files: oneChange, prSeq: [reserved, unreserved], pushResult: { ok: false, reason: 'stale info' }, ...reservedRemote });
  assert.equal((await run(m1)).outcome, 'skip-push-rejected');
  // 2回目: 予約なしの PR（head old）→ 解除はせず、再確認 → push → 再予約
  const m2 = mocks({ files: oneChange, pr: unreserved, ...reservedRemote });
  const r2 = await run(m2);
  assert.equal(r2.outcome, 'updated');
  assert.ok(!m2.writes().some((c) => c.name === 'disableAutoMerge'));
  assert.ok(m2.writes().some((c) => c.name === 'enableAutoMerge' && c.args[1] === 'foldcommit'));
  // 1回目: push 後の再予約が失敗 → 2回目: 同じ内容なので push せず予約だけ
  const m3 = mocks({ files: oneChange, prSeq: [reserved, unreserved], fail: { enableAutoMerge: true }, ...reservedRemote });
  assert.equal((await run(m3)).exitCode, 1);
  const m4 = mocks({ files: oneChange, pr: { ...unreserved, headRefOid: 'foldcommit' }, remote: 'foldcommit', remoteInfo: { tree: 'newtree', parents: ['main1'] } });
  const r4 = await run(m4);
  assert.equal(r4.outcome, 'up-to-date');
  assert.ok(!m4.writes().some((c) => c.name === 'forcePush'));
  assert.ok(m4.writes().some((c) => c.name === 'enableAutoMerge'));
});
