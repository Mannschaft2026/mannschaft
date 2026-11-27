import test from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fsLedgerReader, readLedger } from './task-list-ledger.mjs';
import { main as structureMain } from './task-list-structure-check.mjs';
import { main as cliMain, migrateLedger, portDiff, renderTable, rewriteLinks } from './task-list-ledger-cli.mjs';

// CMP-261124-1222 段B: 台帳の月別移行（migrate）・表示（render）・差分移植（port-diff）。

const HEADER = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|----|------|------|------|---------|----------------|------|\n';
const row = (id, status, evidence = '—') => '| ' + id + ' | 戦役' + id + ' | ' + status + ' | — | 条件 | ' + evidence + ' | — |';
const ROWS = [
  row('CMP-261101-0900', 'P1 未着手', '[証拠](evidence/CMP-261101-0900.json)'),
  row('CMP-261024-0214', '完了（PR #1）', '[a](./evidence/x.json) [b](https://example.com/a) [c](#anchor) [d](docs/evidence/y.json "title")'),
  row('CMP-003', 'P3 保留'),
  '|  CMP-261105-1200 | 先頭に空白2つ | P2 実装中 | — | 条件 | — | — |',
  row('CMP-261102-0100', 'P4 未着手'),
  row('CMP-261020-1000', '完了'),
  row('CMP-261103-0100', 'P1 実装中'),
];
const OLD = [
  '# 横断課題正本', '', '## 位置づけ', '', '前置き', '', '---', '', '## 戦役一覧', '',
  HEADER.trimEnd(), ...ROWS.slice(0, 4), '', '台帳列は注記。', '', '---', '',
  '## 完了アーカイブ', '', '| ID | 戦役 | 完了日 | 証拠(PR/テスト) |', '|----|------|--------|----------------|', ...ROWS.slice(4), '',
].join('\n');

function makeRoot(text = OLD) {
  const root = mkdtempSync(join(tmpdir(), 'ledger-cli-'));
  mkdirSync(join(root, 'docs'), { recursive: true });
  writeFileSync(join(root, 'docs', 'task-list.md'), text);
  return root;
}
const read = (root, p) => readFileSync(join(root, p), 'utf8');
const tree = (root) => readdirSync(join(root, 'docs'), { recursive: true }).map((p) => String(p).replaceAll('\\', '/')).sort();

test('rewriteLinks: 相対リンクだけに ../ を付ける', () => {
  assert.equal(rewriteLinks('[a](evidence/x.json) [b](./evidence/y.json) [c](https://e.com) [d](#h) [e](/abs) [f](docs/z.md "t")'),
    '[a](../evidence/x.json) [b](../evidence/y.json) [c](https://e.com) [d](#h) [e](/abs) [f](../docs/z.md "t")');
  assert.equal(rewriteLinks('| CMP-003 | 本文に ] ( が無い行 |'), '| CMP-003 | 本文に ] ( が無い行 |');
});

test('migrate: ID 集合一致・行はリンク以外バイト一致・月ごとに元の相対順序・索引に行なし・構造検査が緑', async () => {
  const root = makeRoot();
  try {
    const r = await migrateLedger(root);
    assert.equal(r.migrated, true);
    assert.equal(r.rows, ROWS.length);
    const ledger = await readLedger(fsLedgerReader(root));
    assert.equal(ledger.layout, 'split');
    assert.deepEqual(ledger.rows.map((x) => x.id).sort(), ROWS.map((l) => l.split('|')[1].trim()).sort());
    const expectFile = { 'CMP-261101-0900': '2026-11', 'CMP-261103-0100': '2026-11', 'CMP-261105-1200': '2026-11', 'CMP-261102-0100': '2026-11', 'CMP-261024-0214': '2026-10', 'CMP-261020-1000': '2026-10', 'CMP-003': 'legacy' };
    for (const x of ledger.rows) assert.equal(x.file, 'docs/task-list/' + expectFile[x.id] + '.md', x.id);
    // 月内の相対順序は元のまま
    assert.deepEqual(ledger.rows.filter((x) => x.file.endsWith('2026-11.md')).map((x) => x.id),
      ['CMP-261101-0900', 'CMP-261105-1200', 'CMP-261102-0100', 'CMP-261103-0100']);
    for (const x of ledger.rows) assert.equal(x.line, rewriteLinks(ROWS.find((l) => l.includes(x.id))));
    assert.ok(read(root, 'docs/task-list/2026-11.md').includes('](../evidence/CMP-261101-0900.json)'));
    assert.ok(read(root, 'docs/task-list/2026-10.md').includes('[a](../evidence/x.json)'));
    const index = read(root, 'docs/task-list.md');
    assert.ok(!/^\| ?\s*CMP-/m.test(index));
    assert.ok(index.includes('前置き') && !index.includes('戦役一覧'));
    assert.ok(r.dropped.length > 0);
    assert.deepEqual(tree(root), ['task-list', 'task-list.md', 'task-list/2026-10.md', 'task-list/2026-11.md', 'task-list/legacy.md']);
    const logs = [];
    assert.equal(await structureMain([join(root, 'docs', 'task-list.md')], (m) => logs.push(m), (m) => logs.push(m)), 0, logs.join('\n'));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('migrate: 再実行は冪等（何もしない）', async () => {
  const root = makeRoot();
  try {
    await migrateLedger(root);
    const snapshot = tree(root).filter((p) => p.endsWith('.md')).map((p) => [p, read(root, 'docs/' + p)]);
    const again = await migrateLedger(root);
    assert.equal(again.migrated, false);
    assert.deepEqual(tree(root).filter((p) => p.endsWith('.md')).map((p) => [p, read(root, 'docs/' + p)]), snapshot);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

for (const step of ['build', 'verify', 'swap-backup', 'swap-dir', 'swap-index', 'cleanup']) {
  test('migrate: 失敗注入 ' + step + ' で移行前の状態に戻る', async () => {
    const root = makeRoot();
    try {
      const before = tree(root);
      await assert.rejects(migrateLedger(root, { inject: (s) => { if (s === step) throw new Error('注入した失敗: ' + s); } }), /注入した失敗/);
      assert.equal(read(root, 'docs/task-list.md'), OLD);
      assert.deepEqual(tree(root), before);
      // 戻った後に改めて移行できる
      assert.equal((await migrateLedger(root)).migrated, true);
    } finally { rmSync(root, { recursive: true, force: true }); }
  });
}

test('migrate: 検証で不一致なら入れ替えない（旧台帳に重複 ID）', async () => {
  const root = makeRoot(OLD + ROWS[0] + '\n');
  try {
    await assert.rejects(migrateLedger(root), /検証に失敗/);
    assert.equal(read(root, 'docs/task-list.md'), OLD + ROWS[0] + '\n');
    assert.deepEqual(tree(root), ['task-list.md']);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('migrate: docs/task-list/ に台帳以外のファイルがあれば移行しない', async () => {
  const root = makeRoot();
  try {
    mkdirSync(join(root, 'docs', 'task-list'));
    writeFileSync(join(root, 'docs', 'task-list', 'memo.txt'), 'x');
    await assert.rejects(migrateLedger(root), /台帳以外のファイル/);
    assert.equal(read(root, 'docs/task-list.md'), OLD);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('render: --open / --priority / --month / 組合せ / 0件は空表', async () => {
  const root = makeRoot();
  try {
    await migrateLedger(root);
    const ledger = await readLedger(fsLedgerReader(root));
    const ids = (t) => t.split('\n').filter((l) => /^\|\s*CMP-/.test(l)).map((l) => l.split('|')[1].trim()).sort();
    assert.deepEqual(ids(renderTable(ledger, {})), ROWS.map((l) => l.split('|')[1].trim()).sort());
    assert.deepEqual(ids(renderTable(ledger, { open: true })), ['CMP-003', 'CMP-261101-0900', 'CMP-261102-0100', 'CMP-261103-0100', 'CMP-261105-1200']);
    assert.deepEqual(ids(renderTable(ledger, { priority: 'P1' })), ['CMP-261101-0900', 'CMP-261103-0100']);
    assert.deepEqual(ids(renderTable(ledger, { month: '2026-10' })), ['CMP-261020-1000', 'CMP-261024-0214']);
    assert.deepEqual(ids(renderTable(ledger, { open: true, priority: 'P1', month: '2026-11' })), ['CMP-261101-0900', 'CMP-261103-0100']);
    const empty = renderTable(ledger, { priority: 'P5' });
    assert.deepEqual(ids(empty), []);
    assert.ok(empty.startsWith('| ID |'));
    assert.deepEqual(ids(renderTable(ledger, { month: '2030-01' })), []);
    assert.throws(() => renderTable(ledger, { priority: 'P9' }));
    assert.throws(() => renderTable(ledger, { month: '2026-13' }));
    // 旧配置でも同じ結果
    const old = makeRoot();
    try { assert.deepEqual(ids(renderTable(await readLedger(fsLedgerReader(old)), { priority: 'P1' })), ['CMP-261101-0900', 'CMP-261103-0100']); } finally { rmSync(old, { recursive: true, force: true }); }
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('port-diff: 旧配置への行追加・行更新を新配置へ適用する（月ファイルが無ければ作る）', async () => {
  const root = makeRoot();
  try {
    await migrateLedger(root);
    const baseText = OLD;
    const updated = row('CMP-261101-0900', 'P1 実装中', '[証拠](evidence/CMP-261101-0900.json) PR #9');
    const added1 = row('CMP-261201-0100', 'P3 未着手');
    const added2 = row('CMP-261106-0100', 'P2 未着手', '[e](evidence/n.json)');
    const headText = OLD.replace(ROWS[0], updated) + added1 + '\n' + added2 + '\n';
    const dry = await portDiff({ baseText, headText, root, dryRun: true });
    assert.deepEqual([dry.added.sort(), dry.updated], [['CMP-261106-0100', 'CMP-261201-0100'], ['CMP-261101-0900']]);
    assert.ok(!existsSync(join(root, 'docs/task-list/2026-12.md')));
    await portDiff({ baseText, headText, root });
    const nov = read(root, 'docs/task-list/2026-11.md');
    assert.ok(nov.includes(rewriteLinks(updated) + '\n'));
    assert.ok(!nov.includes(ROWS[0].replace('](evidence', '](../evidence')));
    assert.ok(nov.trimEnd().endsWith(rewriteLinks(added2)));
    assert.ok(read(root, 'docs/task-list/2026-12.md').includes(added1));
    const logs = [];
    assert.equal(await structureMain([join(root, 'docs', 'task-list.md')], (m) => logs.push(m), (m) => logs.push(m)), 0, logs.join('\n'));
    // 旧配置には適用しない
    const old = makeRoot();
    try { await assert.rejects(portDiff({ baseText, headText, root: old }), /新配置でない/); } finally { rmSync(old, { recursive: true, force: true }); }
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('移行後に旧配置（索引）へ CMP 行を足すと構造検査が赤になる', async () => {
  const root = makeRoot();
  try {
    await migrateLedger(root);
    const indexPath = join(root, 'docs', 'task-list.md');
    writeFileSync(indexPath, read(root, 'docs/task-list.md') + row('CMP-261201-0100', 'P3 未着手') + '\n');
    const errs = [];
    assert.equal(await structureMain([indexPath], () => {}, (m) => errs.push(m)), 1);
    assert.ok(errs.join('\n').includes('索引に CMP 行を置かない'), errs.join('\n'));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('CLI: 不明なサブコマンドは exit 1、migrate は新配置で何もしない', async () => {
  const root = makeRoot();
  try {
    const logs = [];
    const io = { root, out: (m) => logs.push(m), err: (m) => logs.push(m) };
    assert.equal(await cliMain(['nope'], io), 1);
    assert.equal(await cliMain(['migrate'], io), 0);
    assert.equal(await cliMain(['migrate'], io), 0);
    assert.ok(logs.some((m) => m.includes('既に新配置')));
    assert.equal(await cliMain(['render', '--bogus'], io), 1);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('実台帳（作業木の docs/task-list.md が旧配置なら）を移行しても ID 集合・行数が一致する', async (t) => {
  const repo = fileURLToPath(new URL('../', import.meta.url));
  const live = join(repo, 'docs', 'task-list.md');
  if (existsSync(join(repo, 'docs', 'task-list'))) return t.skip('既に新配置');
  const root = mkdtempSync(join(tmpdir(), 'ledger-real-'));
  try {
    mkdirSync(join(root, 'docs'));
    cpSync(live, join(root, 'docs', 'task-list.md'));
    const before = await readLedger(fsLedgerReader(root));
    const t0 = Date.now();
    const r = await migrateLedger(root);
    const after = await readLedger(fsLedgerReader(root));
    assert.equal(r.migrated, true);
    assert.deepEqual(after.rows.map((x) => x.id).sort(), before.rows.map((x) => x.id).sort());
    assert.ok(Date.now() - t0 < 30000);
  } finally { rmSync(root, { recursive: true, force: true }); }
});


test('port-diff: 行更新は main の現在行が分岐点の行と一致するときだけ置換する（P1）', async () => {
  const root = makeRoot();
  try {
    await migrateLedger(root);
    const baseText = OLD;
    const pr = (l) => OLD.replace(ROWS[2], l);
    const mainChanged = row('CMP-003', '完了（main で更新）');
    const monthPath = join(root, 'docs/task-list/legacy.md');
    const orig = read(root, 'docs/task-list/legacy.md');
    // main 側も更新済み: 3者が異なる → 競合で停止（CLI は例外、書き込まない）
    writeFileSync(monthPath, orig.replace(ROWS[2], mainChanged));
    await assert.rejects(portDiff({ baseText, headText: pr(row('CMP-003', 'P2 実装中')), root }), /競合/);
    assert.ok(read(root, 'docs/task-list/legacy.md').includes(mainChanged));
    assert.ok(!read(root, 'docs/task-list/legacy.md').includes('P2 実装中'));
    // main が既に PR と同じ行: 何もしない
    writeFileSync(monthPath, orig.replace(ROWS[2], row('CMP-003', 'P2 実装中')));
    const same = await portDiff({ baseText, headText: pr(row('CMP-003', 'P2 実装中')), root });
    assert.deepEqual([same.updated, same.files], [[], []]);
    // main が分岐点のまま: 置換する
    writeFileSync(monthPath, orig);
    assert.deepEqual((await portDiff({ baseText, headText: pr(row('CMP-003', 'P2 実装中')), root })).updated, ['CMP-003']);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('port-diff: base・head の旧配置に重複 ID があれば移植しない（P2）', async () => {
  const root = makeRoot();
  try {
    await migrateLedger(root);
    await assert.rejects(portDiff({ baseText: OLD, headText: OLD + ROWS[2] + '\n', root }), /重複 ID/);
    await assert.rejects(portDiff({ baseText: OLD + ROWS[2] + '\n', headText: OLD, root }), /重複 ID/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
