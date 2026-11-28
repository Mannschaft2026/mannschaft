import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CHANGE_FILE_NAME, CHANGES_DIR, MAX_CHANGE_BYTES, fsLedgerReader, ledgerRowMap, readLedger, splitTableRow, javaTrim } from './task-list-ledger.mjs';
import { checkTaskListStructure } from './task-list-structure-check.mjs';
import { changeFileName, main, planAddChange, planUpdateChange } from './task-list-ledger-cli.mjs';

// CMP-261124-1222 ④ 段1d: 変更申告を作る CLI（add / update --set / rebase）。
// 生成した申告を読む側（readLedger）で適用し、構造検査が OK になる往復を確かめる。境界: 月またぎ・セル内 \|・16KiB。

const ledgerCli = fileURLToPath(new URL('./task-list-ledger.mjs', import.meta.url));
const H = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const row = (id, state = 'P2 実装中', cond = '条件') => '| ' + id + ' | 戦役 | ' + state + ' | — | ' + cond + ' | 証拠 | ledger |';
const sha = (s) => 'sha256:' + createHash('sha256').update(s, 'utf8').digest('hex');
const T = 'CMP-261124-1222';
const A = row(T);
const M11 = '# 2026-11\n\n' + H + '\n' + row('CMP-261101-0000', '完了') + '\n' + A + '\n';
const BASE = { 'docs/task-list.md': '# 台帳（索引）\n\n前置き。\n', 'docs/task-list/2026-11.md': M11, 'docs/task-list/legacy.md': '# legacy\n\n' + H + '\n' + row('CMP-003', 'P5 未着手') + '\n' };
const NOW = new Date('2026-11-27T01:02:03.456Z');
let seq = 0;

function makeRoot(files = BASE) {
  const root = mkdtempSync(join(tmpdir(), 'ledger-change-cli-'));
  for (const [p, c] of Object.entries(files)) {
    mkdirSync(dirname(join(root, p)), { recursive: true });
    writeFileSync(join(root, p), c);
  }
  return root;
}
const changeFiles = (root) => { try { return readdirSync(join(root, CHANGES_DIR)).sort(); } catch { return []; } };
async function run(root, argv, extra = {}) {
  const out = [];
  const err = [];
  const code = await main(argv, { root, out: (m) => out.push(m), err: (m) => err.push(m), now: () => NOW, rand: () => String(123456 + seq++), ...extra });
  return { code, out: out.join('\n'), err: err.join('\n') };
}
async function roundTrip(root) {
  const ledger = await readLedger(fsLedgerReader(root));
  assert.deepEqual(ledger.changes.problems, []);
  assert.deepEqual(checkTaskListStructure(ledger).errors, []);
  assert.equal(ledger.changes.applied.length, ledger.changes.entries.length, '全申告が適用される');
  return ledger;
}

test('申告ファイル名は <CMP-ID>--<UTC yyyymmddThhmmssZ>--<6桁乱数>.md', () => {
  const name = changeFileName(T, NOW, '012345');
  assert.equal(name, T + '--20261127T010203Z--012345.md');
  assert.ok(CHANGE_FILE_NAME.test(name));
  assert.throws(() => changeFileName(T, NOW, '12345'), /6桁/);
  assert.match(changeFileName(T, NOW), /--\d{6}\.md$/);
});

test('add: 生成→読む側で適用→構造検査 OK の往復。LF・2行固定・base=none', async () => {
  const root = makeRoot();
  try {
    const line = row('CMP-261127-0900', 'P3 未着手');
    const r = await run(root, ['add', line]);
    assert.equal(r.code, 0, r.err);
    const [name] = changeFiles(root);
    assert.match(name, /^CMP-261127-0900--20261127T010203Z--\d{6}\.md$/);
    const content = readFileSync(join(root, CHANGES_DIR, name), 'utf8');
    assert.equal(content, '<!-- ledger-change v1 op=add id=CMP-261127-0900 base=none -->\n' + line + '\n');
    assert.ok(!content.includes('\r'));
    const ledger = await roundTrip(root);
    assert.equal(ledgerRowMap(ledger).get('CMP-261127-0900'), line);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('add: 既存 ID（月別ファイル・未畳み込みの add 申告）は拒否し、ファイルを作らない', async () => {
  const root = makeRoot();
  try {
    let r = await run(root, ['add', row('CMP-261101-0000', 'P3 未着手')]);
    assert.equal(r.code, 1);
    assert.match(r.err, /既に実効台帳にある/);
    assert.deepEqual(changeFiles(root), []);
    assert.equal((await run(root, ['add', row('CMP-261127-0900')])).code, 0);
    r = await run(root, ['add', row('CMP-261127-0900', 'P1 未着手')]);
    assert.equal(r.code, 1);
    assert.equal(changeFiles(root).length, 1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('add: 7列でない行・先頭セルが CMP-ID でない行は拒否する', async () => {
  const ledger = await readLedger({ listDir: (d) => (d === 'docs/task-list' ? ['2026-11.md'] : null), readFile: (p) => ({ 'docs/task-list.md': BASE['docs/task-list.md'], 'docs/task-list/2026-11.md': M11 })[p] ?? null });
  assert.throws(() => planAddChange(ledger, '| CMP-261127-0900 | 戦役 | P3 未着手 | — | 条件 | 証拠 |', { now: NOW, rand: '000001' }), /7 列/);
  assert.throws(() => planAddChange(ledger, '| X | a | b | c | d | e | f |', { now: NOW, rand: '000001' }), /CMP-ID/);
});

test('境界: 月またぎ（CMP-261130-2359 → CMP-261201-0000）の add は新しい月ファイルを雛形から作り、構造検査 OK', async () => {
  const root = makeRoot();
  try {
    assert.equal((await run(root, ['add', row('CMP-261130-2359', 'P3 未着手')])).code, 0);
    assert.equal((await run(root, ['add', row('CMP-261201-0000', 'P3 未着手')])).code, 0);
    const ledger = await roundTrip(root);
    assert.equal(ledger.rows.find((r) => r.id === 'CMP-261130-2359').file, 'docs/task-list/2026-11.md');
    assert.equal(ledger.rows.find((r) => r.id === 'CMP-261201-0000').file, 'docs/task-list/2026-12.md');
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('update --set: 現在の実効行の sha256 を base にし、指定列だけを置き換える。連続 update は連鎖する', async () => {
  const root = makeRoot();
  try {
    let r = await run(root, ['update', T, '--set', '状態=P2 実装中（④）', '--set', '依存=CMP-003']);
    assert.equal(r.code, 0, r.err);
    let [first] = changeFiles(root);
    const firstContent = readFileSync(join(root, CHANGES_DIR, first), 'utf8');
    assert.equal(firstContent.split('\n')[0], '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(A) + ' -->');
    const expected = '| ' + T + ' | 戦役 | P2 実装中（④） | CMP-003 | 条件 | 証拠 | ledger |';
    assert.equal(firstContent.split('\n')[1], expected);
    r = await run(root, ['update', T, '--set', '状態=完了']);
    assert.equal(r.code, 0, r.err);
    const names = changeFiles(root);
    assert.equal(names.length, 2);
    const second = readFileSync(join(root, CHANGES_DIR, names.find((n) => n !== first)), 'utf8');
    assert.match(second, new RegExp('base=' + sha(expected).replace('sha256:', 'sha256:')));
    const ledger = await roundTrip(root);
    assert.equal(ledgerRowMap(ledger).get(T), expected.replace('P2 実装中（④）', '完了'));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('境界: セル内の | は \\| にエスケープし、既存セルの \\| はそのまま保つ（列数が崩れない）', async () => {
  const pipeRow = '| ' + T + ' | 戦役 a\\|b | P2 実装中 | — | 条件 | 証拠 | ledger |';
  const root = makeRoot({ ...BASE, 'docs/task-list/2026-11.md': M11.replace(A, pipeRow) });
  try {
    const r = await run(root, ['update', T, '--set', '完了条件=x|y と x\\|z']);
    assert.equal(r.code, 0, r.err);
    const ledger = await roundTrip(root);
    const line = ledgerRowMap(ledger).get(T);
    assert.equal(line, '| ' + T + ' | 戦役 a\\|b | P2 実装中 | — | x\\|y と x\\|z | 証拠 | ledger |');
    const cells = splitTableRow(line).slice(1, -1).map(javaTrim);
    assert.deepEqual(cells, [T, '戦役 a|b', 'P2 実装中', '—', 'x|y と x|z', '証拠', 'ledger']);
    // add でも同じ
    assert.equal((await run(root, ['add', '| CMP-261127-0900 | 戦役 p\\|q | P3 未着手 | — | 条件 | 証拠 | ledger |'])).code, 0);
    await roundTrip(root);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('update: 未知の列・ID 列・存在しない ID・変化なしは拒否する', async () => {
  const root = makeRoot();
  try {
    assert.match((await run(root, ['update', T, '--set', '色=赤'])).err, /列名/);
    assert.match((await run(root, ['update', T, '--set', 'ID=CMP-261127-0000'])).err, /ID/);
    assert.match((await run(root, ['update', 'CMP-261127-0000', '--set', '状態=完了'])).err, /実効台帳に無い/);
    assert.match((await run(root, ['update', T, '--set', '状態=P2 実装中'])).err, /変化が無い/);
    assert.match((await run(root, ['update', T])).err, /--set/);
    assert.deepEqual(changeFiles(root), []);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('境界: 申告 16KiB ちょうどは可、1バイト超過は拒否する', async () => {
  const ledger = await readLedger({ listDir: (d) => (d === 'docs/task-list' ? ['2026-11.md'] : null), readFile: (p) => ({ 'docs/task-list.md': BASE['docs/task-list.md'], 'docs/task-list/2026-11.md': M11 })[p] ?? null });
  const probe = planUpdateChange(ledger, T, [['完了条件', 'x']], { now: NOW, rand: '000001' });
  const fill = 'x'.repeat(1 + MAX_CHANGE_BYTES - Buffer.byteLength(probe.content, 'utf8'));
  const exact = planUpdateChange(ledger, T, [['完了条件', fill]], { now: NOW, rand: '000001' });
  assert.equal(Buffer.byteLength(exact.content, 'utf8'), MAX_CHANGE_BYTES);
  assert.throws(() => planUpdateChange(ledger, T, [['完了条件', fill + 'x']], { now: NOW, rand: '000001' }), /16KiB/);
});

test('update: 同じ base の未畳み込み申告がある（fork になる）・巻き戻し（A→B→A）は警告する', async () => {
  const root = makeRoot();
  try {
    assert.equal((await run(root, ['update', T, '--set', '状態=P2 実装中（④）'])).code, 0);
    const r = await run(root, ['update', T, '--set', '状態=P2 実装中']);
    assert.equal(r.code, 0);
    assert.match(r.err, /巻き戻し/);
    // 同じ base の申告を手で置いて fork にした状態で update すると警告
    const root2 = makeRoot({ ...BASE,
      [CHANGES_DIR + '/' + T + '--20261127T000000Z--000001.md']: '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(A) + ' -->\n' + row(T, 'P1 実装中') + '\n',
      [CHANGES_DIR + '/' + T + '--20261127T000000Z--000002.md']: '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(A) + ' -->\n' + row(T, 'P3 実装中') + '\n' });
    try {
      const r2 = await run(root2, ['update', T, '--set', '完了条件=新しい条件']);
      assert.match(r2.err, /同じ base の未畳み込み申告/);
    } finally { rmSync(root2, { recursive: true, force: true }); }
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('rebase: base 不一致（孤児）の申告の base を現在の実効行で取り直し、内容は作者が確認すべき旨を出す', async () => {
  const B = row(T, 'P2 実装中（main 側）');
  const mine = row(T, 'P2 実装中（自分の更新）');
  const name = CHANGES_DIR + '/' + T + '--20261127T000000Z--000001.md';
  const root = makeRoot({ ...BASE, 'docs/task-list/2026-11.md': M11.replace(A, B), [name]: '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(A) + ' -->\n' + mine + '\n' });
  try {
    const before = await readLedger(fsLedgerReader(root));
    assert.ok(before.changes.problems.some((p) => p.kind === 'orphan'));
    const r = await run(root, ['rebase', join(root, name)]);
    assert.equal(r.code, 0, r.err);
    assert.match(r.out, /作者が確認/);
    assert.ok(r.out.includes(B) && r.out.includes(mine), '現在の実効行と申告の行を並べて示す');
    assert.equal(readFileSync(join(root, name), 'utf8'), '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(B) + ' -->\n' + mine + '\n');
    const ledger = await roundTrip(root);
    assert.equal(ledgerRowMap(ledger).get(T), mine);
    // 既に連鎖している申告は取り直し不要
    const again = await run(root, ['rebase', join(root, name)]);
    assert.equal(again.code, 0);
    assert.match(again.out, /取り直し不要/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('rebase: 既存 ID になった add は rebase できない（新しい ID を採る）', async () => {
  const name = CHANGES_DIR + '/CMP-261101-0000--20261127T000000Z--000001.md';
  const root = makeRoot({ ...BASE, [name]: '<!-- ledger-change v1 op=add id=CMP-261101-0000 base=none -->\n' + row('CMP-261101-0000', 'P1 未着手') + '\n' });
  try {
    const r = await run(root, ['rebase', join(root, name)]);
    assert.equal(r.code, 1);
    assert.match(r.err, /新しい ID/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('入口: node scripts/task-list-ledger.mjs add / update / effective --json が動く', () => {
  const root = makeRoot();
  try {
    const add = spawnSync(process.execPath, [ledgerCli, 'add', row('CMP-261127-0900', 'P3 未着手')], { cwd: root, encoding: 'utf8' });
    assert.equal(add.status, 0, add.stderr);
    const upd = spawnSync(process.execPath, [ledgerCli, 'update', 'CMP-261127-0900', '--set', '状態=P3 実装中'], { cwd: root, encoding: 'utf8' });
    assert.equal(upd.status, 0, upd.stderr);
    const eff = spawnSync(process.execPath, [ledgerCli, 'effective', '--json'], { cwd: root, encoding: 'utf8' });
    assert.equal(eff.status, 0, eff.stderr);
    const json = JSON.parse(eff.stdout);
    assert.equal(json.rows.find((r) => r.id === 'CMP-261127-0900').line, row('CMP-261127-0900', 'P3 実装中'));
    assert.deepEqual(json.changes.problems, []);
    assert.equal(json.changes.applied.length, 2);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('検分 P2: rebase の結果が適用されない（3件 fork になる）なら書かず、元の内容を保って失敗する', async () => {
  const B = row(T, 'P2 実装中（main 側）');
  const name = CHANGES_DIR + '/' + T + '--20261127T000000Z--000001.md';
  const orphan = '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(A) + ' -->\n' + row(T, 'P1 自分') + '\n';
  const root = makeRoot({ ...BASE, 'docs/task-list/2026-11.md': M11.replace(A, B), [name]: orphan,
    [CHANGES_DIR + '/' + T + '--20261127T000000Z--000002.md']: '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(B) + ' -->\n' + row(T, 'P3 x') + '\n',
    [CHANGES_DIR + '/' + T + '--20261127T000000Z--000003.md']: '<!-- ledger-change v1 op=update id=' + T + ' base=' + sha(B) + ' -->\n' + row(T, 'P4 y') + '\n' });
  try {
    const r = await run(root, ['rebase', join(root, name)]);
    assert.equal(r.code, 1);
    assert.equal(readFileSync(join(root, name), 'utf8'), orphan, '元の内容を保つ');
  } finally { rmSync(root, { recursive: true, force: true }); }
});
