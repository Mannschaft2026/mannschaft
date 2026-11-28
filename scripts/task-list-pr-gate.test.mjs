import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { LEDGER_BOT_LOGIN, changedMonthFiles, createGraphqlLedgerSource, evaluateLedgerChanges, isLedgerBotAuthor } from './task-list-pr-gate.mjs';
import { readLedger, ledgerRowMap } from './task-list-ledger.mjs';
import { selectEvidencePaths, validateCompletionChanges } from './completion-evidence.mjs';
import { memoryLedgerReader } from './test-support/task-list-split.mjs';

// CMP-261124-1222 ④ 段1c: PR ゲートの部品（GraphQL 一括取得・bot 判定・等式判定）と、実効台帳を渡した完了証拠の検査（AC-9）。

const H = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const IDX = '# 台帳（索引）\n\n前置き。\n';
const row = (id, state, evidence = '証拠') => '| ' + id + ' | 戦役 | ' + state + ' | — | 条件 | ' + evidence + ' | ledger |';
const month = (...rows) => '# 2026-11\n\n' + H + '\n' + rows.join('\n') + '\n';
const sha = (s) => 'sha256:' + createHash('sha256').update(s, 'utf8').digest('hex');
const meta = (op, id, b) => '<!-- ledger-change v1 op=' + op + ' id=' + id + ' base=' + b + ' -->';
const upd = (from, to, id) => meta('update', id, sha(from)) + '\n' + to + '\n';
const add = (line, id) => meta('add', id, 'none') + '\n' + line + '\n';
const cname = (id, t, r = '000001') => 'docs/task-list/changes/' + id + '--' + t + '--' + r + '.md';
const T = 'CMP-261124-1222';
const A = row(T, 'P2 実装中');
const oidOf = (text) => createHash('sha1').update('blob ' + text).digest('hex');

/** files（パス→本文）を GraphQL の Tree entries 応答にする mock。mutate で blob ごとに isTruncated 等を差し込む。 */
function fakeGithub(filesByRef, { mutate = () => {}, getBlob } = {}) {
  const calls = { graphql: 0, getBlob: 0 };
  const blob = (text, path) => { const b = { __typename: 'Blob', oid: oidOf(text), text, isTruncated: false, isBinary: false, byteSize: Buffer.byteLength(text) }; mutate(b, path); return b; };
  const entries = (files, dir) => {
    const prefix = dir + '/';
    const names = [...new Set(Object.keys(files).filter((p) => p.startsWith(prefix)).map((p) => p.slice(prefix.length).split('/')[0]))].sort();
    return names.map((name) => (Object.prototype.hasOwnProperty.call(files, prefix + name)
      ? { name, type: 'blob', oid: oidOf(files[prefix + name]), object: blob(files[prefix + name], prefix + name) }
      : { name, type: 'tree', oid: 't', object: { __typename: 'Tree', entries: entries(files, prefix + name) } }));
  };
  return {
    calls,
    graphql: async (query, v) => {
      calls.graphql++;
      const files = filesByRef[v.commit];
      if (!files) return { repository: { commit: null, index: null, dir: null } };
      return { repository: {
        commit: { __typename: 'Commit', oid: 'x' },
        index: files['docs/task-list.md'] == null ? null : blob(files['docs/task-list.md'], 'docs/task-list.md'),
        dir: Object.keys(files).some((p) => p.startsWith('docs/task-list/')) ? { __typename: 'Tree', entries: entries(files, 'docs/task-list') } : null,
      } };
    },
    rest: { git: { getBlob: async (args) => { calls.getBlob++; return getBlob(args); } } },
  };
}

const base = { 'docs/task-list.md': IDX, 'docs/task-list/2026-11.md': month(A) };

test('bot 判定: mannschaft-ledger-bot[bot]（type Bot）だけを台帳 bot とする', () => {
  assert.equal(LEDGER_BOT_LOGIN, 'mannschaft-ledger-bot[bot]');
  assert.equal(isLedgerBotAuthor({ user: { login: 'mannschaft-ledger-bot[bot]', type: 'Bot' } }), true);
  assert.equal(isLedgerBotAuthor({ login: 'mannschaft-ledger-bot[bot]', type: 'Bot' }), true);
  assert.equal(isLedgerBotAuthor({ user: { login: 'mannschaft-ledger-bot[bot]', type: 'User' } }), false, 'type が User の同名は bot でない');
  assert.equal(isLedgerBotAuthor({ user: { login: 'mannschaft-ledger-bot', type: 'Bot' } }), false);
  assert.equal(isLedgerBotAuthor({ user: { login: 'kenta', type: 'User' } }), false);
  assert.equal(isLedgerBotAuthor(null), false);
  assert.equal(isLedgerBotAuthor({ user: null }), false);
});

test('GraphQL 取得: 1参照1回で索引・月別・申告を読み、readLedger の実効台帳と一致する', async () => {
  const files = { ...base, [cname(T, '20261127T010000Z')]: upd(A, row(T, 'P2 実装中（④）'), T) };
  const github = fakeGithub({ r1: files });
  const source = createGraphqlLedgerSource({ github, owner: 'o', repo: 'r' });
  const viaGraphql = await source.read('r1');
  const viaMemory = await readLedger(memoryLedgerReader(files));
  assert.deepEqual(viaGraphql.files, viaMemory.files);
  assert.deepEqual(viaGraphql.changes.problems, []);
  assert.equal(ledgerRowMap(viaGraphql).get(T), row(T, 'P2 実装中（④）'));
  assert.equal(github.calls.graphql, 1);
});

test('GraphQL 取得: 申告 1001 件でも欠けず、呼び出しは1回', async () => {
  const files = { ...base };
  for (let i = 0; i < 1001; i++) {
    const id = 'CMP-2611' + String(10 + (i % 18)).padStart(2, '0') + '-' + String(i).padStart(4, '0');
    files[cname(id, '20261127T010000Z', String(100000 + i))] = add(row(id, 'P3 未着手'), id);
  }
  const github = fakeGithub({ r1: files });
  const ledger = await createGraphqlLedgerSource({ github, owner: 'o', repo: 'r' }).read('r1');
  assert.equal(ledger.changes.entries.length, 1001);
  assert.equal(ledger.changes.applied.length, 1001);
  assert.equal(ledger.rows.length, 1002);
  assert.equal(github.calls.graphql, 1);
});

test('GraphQL 取得: isTruncated の月別ファイルは oid で blob を取り直し、参照をまたいで同じ oid は1回だけ取る', async () => {
  const github = fakeGithub({ r1: base, r2: base }, {
    mutate: (b, path) => { if (path === 'docs/task-list/2026-11.md') { b.text = b.text.slice(0, 10); b.isTruncated = true; } },
    getBlob: ({ file_sha }) => { assert.equal(file_sha, oidOf(base['docs/task-list/2026-11.md'])); return { data: { encoding: 'base64', content: Buffer.from(base['docs/task-list/2026-11.md']).toString('base64') } }; },
  });
  const source = createGraphqlLedgerSource({ github, owner: 'o', repo: 'r' });
  const a = await source.read('r1');
  const b = await source.read('r2');
  assert.equal(ledgerRowMap(a).get(T), A);
  assert.deepEqual(a.files, b.files);
  assert.equal(github.calls.getBlob, 1, 'oid キャッシュ');
  assert.equal(github.calls.graphql, 2);
});

test('GraphQL 取得: 申告が isTruncated・本文 null（バイナリ）・ref 解決不能・5xx は不在とせず赤', async () => {
  const files = { ...base, [cname(T, '20261127T010000Z')]: upd(A, row(T, 'P2 実装中（④）'), T) };
  const truncated = fakeGithub({ r1: files }, { mutate: (b, path) => { if (path.includes('/changes/')) { b.isTruncated = true; } } });
  await assert.rejects(createGraphqlLedgerSource({ github: truncated, owner: 'o', repo: 'r' }).read('r1'), /切られている/);
  const binary = fakeGithub({ r1: files }, { mutate: (b, path) => { if (path.endsWith('2026-11.md')) { b.text = null; b.isBinary = true; } } });
  await assert.rejects(createGraphqlLedgerSource({ github: binary, owner: 'o', repo: 'r' }).read('r1'), /不在とは扱わない/);
  const unresolved = fakeGithub({});
  await assert.rejects(createGraphqlLedgerSource({ github: unresolved, owner: 'o', repo: 'r' }).read('nope'), /ref を解決できない/);
  const down = { graphql: async () => { throw Object.assign(new Error('Bad Gateway'), { status: 502 }); } };
  await assert.rejects(createGraphqlLedgerSource({ github: down, owner: 'o', repo: 'r' }).read('r1'), /不在とは扱わない.*Bad Gateway/);
});

test('GraphQL 取得: changes/ のネスト・.md 以外は readLedger の問題として報告する（読み飛ばさない）', async () => {
  const files = { ...base, [cname(T, '20261127T010000Z') + '/x.md']: 'x', 'docs/task-list/changes/readme.txt': 'x' };
  const ledger = await createGraphqlLedgerSource({ github: fakeGithub({ r1: files }), owner: 'o', repo: 'r' }).read('r1');
  assert.deepEqual(ledger.changes.problems.map((p) => p.kind).sort(), ['entry', 'entry']);
});

test('P2-4 互換期間（enforceDirectEdit: false）: 月別ファイルを変えた PR の作者が bot でなければ notice に作者と bot 名を含める（違反にしない）。bot なら含めない', async () => {
  const mb = await readLedger(memoryLedgerReader(base));
  const head = await readLedger(memoryLedgerReader({ ...base, 'docs/task-list/2026-11.md': month(row(T, '完了')) }));
  assert.deepEqual(changedMonthFiles(mb, head), ['docs/task-list/2026-11.md']);
  const human = evaluateLedgerChanges({ mergeBase: mb, base: mb, head, pull: { user: { login: 'kenta', type: 'User' } }, enforceDirectEdit: false });
  assert.deepEqual(human.violations, []);
  assert.ok(human.notices.some((m) => m.includes('kenta') && m.includes('mannschaft-ledger-bot[bot] でない')), human.notices.join('\n'));
  const bot = evaluateLedgerChanges({ mergeBase: mb, base: mb, head, pull: { user: { login: LEDGER_BOT_LOGIN, type: 'Bot' } }, enforceDirectEdit: false });
  assert.ok(!bot.notices.some((m) => m.includes('でない')));
  assert.equal(bot.botAuthor, true);
});

test('P2-4 施行後（既定）: 同じ旧手順 PR（月別ファイルの直接編集）は作者が bot でなければ赤で、申告 CLI と自動修復を案内する', async () => {
  const mb = await readLedger(memoryLedgerReader(base));
  const head = await readLedger(memoryLedgerReader({ ...base, 'docs/task-list/2026-11.md': month(row(T, '完了')) }));
  const human = evaluateLedgerChanges({ mergeBase: mb, base: mb, head, pull: { user: { login: 'kenta', type: 'User' } } });
  assert.ok(human.violations.length > 0);
  const text = human.violations.join('\n');
  assert.match(text, /direct-edit/);
  assert.match(text, /kenta/);
  assert.match(text, /node scripts\/task-list-ledger\.mjs add/);
  assert.match(text, /node scripts\/task-list-ledger\.mjs update/);
  assert.match(text, /自動修復/);
  assert.deepEqual(human.notices.filter((m) => /direct-edit|でない/.test(m)), [], '施行後は警告に回さない');
  // bot でも等式が崩れる月別編集（不正な畳み込み）は赤
  const bot = evaluateLedgerChanges({ mergeBase: mb, base: mb, head, pull: { user: { login: LEDGER_BOT_LOGIN, type: 'Bot' } } });
  assert.ok(bot.violations.some((m) => /direct-edit/.test(m)), bot.violations.join('\n'));
});

test('P2-4 施行後: 実効台帳が変わらない月別ファイルの編集（行の並べ替え）でも、作者が bot でなければ赤', async () => {
  const P = row('CMP-261101-0000', '完了');
  const mb = await readLedger(memoryLedgerReader({ ...base, 'docs/task-list/2026-11.md': month(P, A) }));
  const head = await readLedger(memoryLedgerReader({ ...base, 'docs/task-list/2026-11.md': month(A, P) }));
  const human = evaluateLedgerChanges({ mergeBase: mb, base: mb, head, pull: { user: { login: 'kenta', type: 'User' } } });
  assert.ok(human.violations.some((m) => /direct-edit/.test(m) && m.includes('docs/task-list/2026-11.md')), human.violations.join('\n'));
  const bot = evaluateLedgerChanges({ mergeBase: mb, base: mb, head, pull: { user: { login: LEDGER_BOT_LOGIN, type: 'Bot' } } });
  assert.deepEqual(bot.violations, []);
});

test('P2-4 施行後: 申告の追加だけの PR（人）と bot の正しい畳み込みは緑のまま', async () => {
  const n = cname(T, '20261127T010000Z');
  const mb = await readLedger(memoryLedgerReader(base));
  const withChange = await readLedger(memoryLedgerReader({ ...base, [n]: upd(A, row(T, 'P2 実装中（④）'), T) }));
  assert.deepEqual(evaluateLedgerChanges({ mergeBase: mb, base: mb, head: withChange, pull: { user: { login: 'kenta', type: 'User' } } }).violations, []);
  const folded = await readLedger(memoryLedgerReader({ ...base, 'docs/task-list/2026-11.md': month(row(T, 'P2 実装中（④）')) }));
  const fold = evaluateLedgerChanges({ mergeBase: withChange, base: withChange, head: folded, pull: { user: { login: LEDGER_BOT_LOGIN, type: 'Bot' } } });
  assert.deepEqual(fold.violations, []);
  assert.equal(fold.fold, true);
});

// AC-9: 完了 update 申告は証拠 JSON 無しで赤・有りで緑（completion-evidence に実効台帳を渡す）。畳み込みでは選定0件。
const evidenceFor = (cmpId) => ({
  schemaVersion: 1,
  cmpId,
  scope: 'tooling',
  implementation: { pr: 42, sha: 'a'.repeat(40) },
  acceptanceCriteria: [{ id: 'AC-1', description: '検査する', tests: [{ name: 'unit', evidence: 'scripts/check.mjs' }] }],
  ci: [{ runId: 100 }],
  stages: { review: { status: 'passed', evidence: 'scripts/check.mjs' }, real: { status: 'not-applicable', reason: '対象外', scope: 'tooling' }, exploratory: { status: 'not-applicable', reason: '対象外', scope: 'tooling' } },
  regressions: [],
});
const validationOptions = {
  repository: 'owner/repo',
  fileExists: (path) => path === 'scripts/check.mjs',
  implementation: { repository: 'owner/repo', merged: true, sha: 'a'.repeat(40), checks: ['success'], files: ['scripts/check.mjs'] },
  runs: new Map([[100, { repository: 'owner/repo', sha: 'a'.repeat(40), conclusion: 'success' }]]),
};

test('AC-9: 完了 update 申告（実効台帳）は証拠 JSON 無しで赤・有りで緑', async () => {
  const done = row(T, '完了', '[証拠](../evidence/' + T + '.json)');
  const baseLedger = await readLedger(memoryLedgerReader(base));
  const headLedger = await readLedger(memoryLedgerReader({ ...base, [cname(T, '20261127T100000Z')]: upd(A, done, T) }));
  const selected = selectEvidencePaths(baseLedger, headLedger, [], { ancestorTaskList: baseLedger });
  assert.deepEqual(selected, ['docs/evidence/' + T + '.json']);
  assert.throws(() => validateCompletionChanges(baseLedger, headLedger, {}, { ...validationOptions, ancestorTaskList: baseLedger }), /証拠JSONが空または不正/);
  assert.equal(validateCompletionChanges(baseLedger, headLedger, { ['docs/evidence/' + T + '.json']: evidenceFor(T) }, { ...validationOptions, ancestorTaskList: baseLedger }), true);
});

test('AC-9: 畳み込み（完了申告を月別へ移して申告を消す）では証拠の選定が0件', async () => {
  const done = row(T, '完了', '[証拠](../evidence/' + T + '.json)');
  const before = await readLedger(memoryLedgerReader({ ...base, [cname(T, '20261127T100000Z')]: upd(A, done, T) }));
  const folded = await readLedger(memoryLedgerReader({ ...base, 'docs/task-list/2026-11.md': month(done) }));
  assert.deepEqual(selectEvidencePaths(before, folded, [], { ancestorTaskList: before }), []);
  assert.equal(validateCompletionChanges(before, folded, {}, { ...validationOptions, ancestorTaskList: before }), true);
});
