import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readdirSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CHANGES_DIR,
  MAX_CHANGE_BYTES,
  changeRowHash,
  fsLedgerReader,
  gitLedgerReader,
  ledgerRowMap,
  ledgerText,
  newFileSkeleton,
  readLedger,
} from './task-list-ledger.mjs';
import { checkTaskListStructure, checkVirtualHead } from './task-list-structure-check.mjs';
import { portDiff } from './task-list-ledger-cli.mjs';
import { memoryLedgerReader } from './test-support/task-list-split.mjs';

// CMP-261124-1222 ④ 段1a: 台帳の変更申告ファイル（docs/task-list/changes/*.md）を読む側。
// 申告0件で従来と完全一致（AC-1）・add/update の適用（AC-2）・fork/孤児/重複（AC-3）・連鎖の順序非依存（AC-4）・
// 畳み込み前後の実効台帳一致（AC-5 の reader 部分）・性能（申告500件の fs readLedger）。

const repoRoot = fileURLToPath(new URL('../', import.meta.url));
const ledgerCli = fileURLToPath(new URL('./task-list-ledger.mjs', import.meta.url));
const checkCli = fileURLToPath(new URL('./task-list-structure-check.mjs', import.meta.url));

const H = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const IDX = '# 台帳（索引）\n\n前置き。\n';
const row = (id, status = 'P2 実装中', extra = '条件') => '| ' + id + ' | 戦役 | ' + status + ' | — | ' + extra + ' | 証拠 | ledger |';
const sha = (s) => 'sha256:' + createHash('sha256').update(s.replace(/\r/g, ''), 'utf8').digest('hex');
const meta = (op, id, b) => '<!-- ledger-change v1 op=' + op + ' id=' + id + ' base=' + b + ' -->';
const add = (line, id) => meta('add', id, 'none') + '\n' + line + '\n';
const upd = (from, to, id) => meta('update', id, sha(from)) + '\n' + to + '\n';
const cname = (id, t, r = '000001') => CHANGES_DIR + '/' + id + '--' + t + '--' + r + '.md';
const A = row('CMP-261124-1222');
const B = row('CMP-261124-1222', 'P2 実装中（④着手）');
const C = row('CMP-261124-1222', '完了');
const X = row('CMP-261124-1222', 'P1 実装中（別系統）');
const M11 = '# 2026-11\n\n' + H + '\n' + row('CMP-261101-0000', '完了') + '\n' + A + '\n';
const LEG = '# legacy\n\n' + H + '\n' + row('CMP-003', 'P5 未着手') + '\n';
const base = { 'docs/task-list.md': IDX, 'docs/task-list/2026-11.md': M11, 'docs/task-list/legacy.md': LEG };
const read = (files, opts) => readLedger(memoryLedgerReader(files), { requireRows: false, ...opts });
const mapOf = async (files) => Object.fromEntries(ledgerRowMap(await read(files)));

function writeTree(files) {
  const dir = mkdtempSync(join(tmpdir(), 'task-list-changes-'));
  for (const [p, c] of Object.entries(files)) {
    mkdirSync(dirname(join(dir, p)), { recursive: true });
    writeFileSync(join(dir, p), c);
  }
  return dir;
}

test('AC-1: 申告0件の台帳（実リポジトリの生の月別・索引だけを写した固定の写し）は、実効と raw が完全一致する', async () => {
  // 実リポジトリが申告0件であることは前提にしない（PR-2 以降は申告が入る）。生のファイルだけを写して申告0件の検体にする。
  const real = await readLedger(fsLedgerReader(repoRoot), { raw: true });
  const copy = Object.fromEntries(real.rawFiles.filter((f) => f.content != null).map((f) => [f.path, f.content]));
  assert.ok(!Object.keys(copy).some((p) => p.startsWith(CHANGES_DIR + '/')));
  const eff = await read(copy);
  const raw = await read(copy, { raw: true });
  assert.equal(eff.changes.entries.length, 0);
  assert.deepEqual(eff.changes.problems, []);
  assert.deepEqual(eff.files, raw.files);
  assert.deepEqual(eff.files, real.rawFiles);
  assert.deepEqual(eff.rows, raw.rows);
  assert.equal(ledgerText(eff), ledgerText(raw));
});

test('実リポジトリの台帳は申告の有無に依らず構造・連鎖検査に合格する', async () => {
  const real = await readLedger(fsLedgerReader(repoRoot));
  assert.deepEqual(real.changes.problems, []);
  assert.deepEqual(checkTaskListStructure(real).errors, []);
});

test('AC-1: changes/ が空ディレクトリでも不在と同じ（files・rows・構造検査・CLI 出力が一致）', async () => {
  const none = writeTree(base);
  const empty = writeTree(base);
  mkdirSync(join(empty, 'docs', 'task-list', 'changes'));
  try {
    const a = await readLedger(fsLedgerReader(none));
    const b = await readLedger(fsLedgerReader(empty));
    assert.deepEqual(b.files, a.files);
    assert.deepEqual(b.rows, a.rows);
    assert.deepEqual(checkTaskListStructure(b), checkTaskListStructure(a));
    for (const args of [[checkCli, 'docs/task-list.md'], [ledgerCli, 'render']]) {
      const ra = spawnSync(process.execPath, args, { cwd: none, encoding: 'utf8' });
      const rb = spawnSync(process.execPath, args, { cwd: empty, encoding: 'utf8' });
      assert.equal(ra.status, 0, ra.stderr);
      assert.equal(rb.stdout.replaceAll(empty, none), ra.stdout);
      assert.equal(rb.status, ra.status);
    }
  } finally {
    rmSync(none, { recursive: true, force: true });
    rmSync(empty, { recursive: true, force: true });
  }
});

test('AC-2: readLedger は files を申告適用後の仮想本文で返し、changes を別フィールド、{ raw: true } で生の月別を返す', async () => {
  const files = { ...base, [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222') };
  const eff = await read(files);
  const raw = await read(files, { raw: true });
  assert.equal(eff.files.find((f) => f.path === 'docs/task-list/2026-11.md').content, M11.replace(A, B));
  assert.equal(raw.files.find((f) => f.path === 'docs/task-list/2026-11.md').content, M11);
  assert.ok(!eff.files.some((f) => f.path.startsWith(CHANGES_DIR)), '申告は files に入れない');
  assert.deepEqual(eff.changes.entries.map((e) => [e.path, e.op, e.id]), [[cname('CMP-261124-1222', '20261127T010000Z'), 'update', 'CMP-261124-1222']]);
  assert.deepEqual(eff.changes.applied, [cname('CMP-261124-1222', '20261127T010000Z')]);
  assert.deepEqual(raw.changes.applied, []);
  assert.equal(ledgerRowMap(eff).get('CMP-261124-1222'), B);
  assert.equal(ledgerRowMap(raw).get('CMP-261124-1222'), A);
});

test('AC-2: base は対象行（CR 除去・UTF-8）の sha256', () => {
  assert.equal(changeRowHash(A), sha(A));
  assert.equal(changeRowHash(A + '\r'), sha(A));
  assert.equal(MAX_CHANGE_BYTES, 16384);
});

test('AC-4: A→B→C の連鎖はファイル名時刻・乱数の並びに依らず同じ実効台帳', async () => {
  const perms = [['20261127T010000Z', '20261127T020000Z'], ['20261127T020000Z', '20261127T010000Z'], ['20261127T010000Z', '20261127T010000Z']];
  const results = [];
  for (const [t1, t2] of perms) {
    results.push(await mapOf({ ...base, [cname('CMP-261124-1222', t1, '000009')]: upd(A, B, 'CMP-261124-1222'), [cname('CMP-261124-1222', t2, '000001')]: upd(B, C, 'CMP-261124-1222') }));
  }
  for (const r of results) assert.equal(r['CMP-261124-1222'], C);
  assert.deepEqual(results[1], results[0]);
  assert.deepEqual(results[2], results[0]);
});

test('AC-3: fork・孤児・重複はファイル名つきで報告し、その申告は適用しない', async () => {
  const f1 = cname('CMP-261124-1222', '20261127T010000Z', '000001');
  const f2 = cname('CMP-261124-1222', '20261127T010000Z', '000002');
  const fork = await read({ ...base, [f1]: upd(A, B, 'CMP-261124-1222'), [f2]: upd(A, C, 'CMP-261124-1222') });
  assert.deepEqual(fork.changes.problems.map((p) => p.kind), ['fork']);
  assert.ok(fork.changes.problems[0].message.includes(f1) && fork.changes.problems[0].message.includes(f2));
  const orphan = await read({ ...base, [f1]: upd(B, C, 'CMP-261124-1222') });
  assert.deepEqual(orphan.changes.problems.map((p) => [p.kind, p.path]), [['orphan', f1]]);
  const dup = await read({ ...base, [f1]: add(B, 'CMP-261124-1222') });
  assert.deepEqual(dup.changes.problems.map((p) => [p.kind, p.path]), [['duplicate', f1]]);
  for (const l of [fork, orphan, dup]) assert.equal(ledgerRowMap(l).get('CMP-261124-1222'), A);
});

test('AC-5（reader）: 畳み込み前（月別＋申告）と畳み込み後（月別に反映・申告削除）で実効台帳が一致する', async () => {
  const n1 = row('CMP-261127-0900', 'P3 未着手');
  const n2 = row('CMP-261201-0000', 'P3 未着手');
  const changes = {
    [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222'),
    [cname('CMP-261127-0900', '20261127T000000Z')]: add(n1, 'CMP-261127-0900'),
    [cname('CMP-261201-0000', '20261130T150000Z')]: add(n2, 'CMP-261201-0000'),
  };
  const before = await read({ ...base, ...changes });
  const folded = Object.fromEntries(before.files.map((f) => [f.path, f.content]));
  const after = await read(folded);
  assert.deepEqual(Object.fromEntries(ledgerRowMap(after)), Object.fromEntries(ledgerRowMap(before)));
  assert.equal(folded['docs/task-list/2026-12.md'], newFileSkeleton('docs/task-list/2026-12.md') + n2 + '\n');
  // 一部だけ畳み込んで残りを申告のまま置いても同じ
  const partial = { ...base, 'docs/task-list/2026-11.md': M11.replace(A, B), [cname('CMP-261127-0900', '20261127T000000Z')]: changes[cname('CMP-261127-0900', '20261127T000000Z')], [cname('CMP-261201-0000', '20261130T150000Z')]: changes[cname('CMP-261201-0000', '20261130T150000Z')] };
  assert.deepEqual(await mapOf(partial), Object.fromEntries(ledgerRowMap(before)));
});

test('AC-5a/5b: 同月で先に作った add が後から到着しても・同時刻で乱数だけ違っても、実効台帳（ID→行）は到着順に依らない', async () => {
  const a1 = [cname('CMP-261127-0900', '20261127T000000Z', '111111'), add(row('CMP-261127-0900', 'P3 未着手'), 'CMP-261127-0900')];
  const a2 = [cname('CMP-261127-0901', '20261127T000000Z', '222222'), add(row('CMP-261127-0901', 'P3 未着手'), 'CMP-261127-0901')];
  const a3 = [cname('CMP-261127-0902', '20261126T000000Z', '000001'), add(row('CMP-261127-0902', 'P3 未着手'), 'CMP-261127-0902')];
  const all = await read({ ...base, ...Object.fromEntries([a1, a2, a3]) });
  // 並びは（時刻, 乱数）順に末尾
  assert.deepEqual(all.rows.filter((r) => r.id.startsWith('CMP-261127')).map((r) => r.id), ['CMP-261127-0902', 'CMP-261127-0900', 'CMP-261127-0901']);
  // a2 を先に畳み込み、a1・a3 が後から到着した場合
  const step1 = await read({ ...base, ...Object.fromEntries([a2]) });
  const foldedA2 = Object.fromEntries(step1.files.map((f) => [f.path, f.content]));
  const late = await read({ ...foldedA2, ...Object.fromEntries([a1, a3]) });
  assert.deepEqual(Object.fromEntries(ledgerRowMap(late)), Object.fromEntries(ledgerRowMap(all)));
});

test('AC-2: git tree の reader でも作業木と同じ実効台帳（申告を含む）', async () => {
  const files = { ...base, [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222'), [cname('CMP-261201-0000', '20261130T150000Z')]: add(row('CMP-261201-0000', 'P3 未着手'), 'CMP-261201-0000') };
  const dir = writeTree(files);
  try {
    const git = (...args) => spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.com', '-c', 'core.autocrlf=false', ...args], { cwd: dir, encoding: 'utf8' });
    git('init', '-q', '-b', 'main');
    git('add', '-A');
    assert.equal(git('commit', '-q', '-m', 'x').status, 0);
    const fromFs = await readLedger(fsLedgerReader(dir));
    const fromGit = await readLedger(gitLedgerReader('HEAD', { cwd: dir }));
    assert.deepEqual(fromGit.files, fromFs.files);
    assert.deepEqual(fromGit.changes.applied, fromFs.changes.applied);
    assert.equal(fromFs.changes.applied.length, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('effective --json は実効台帳（files・rows・changes）を JSON で出す', async () => {
  const files = { ...base, [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222') };
  const dir = writeTree(files);
  try {
    const res = spawnSync(process.execPath, [ledgerCli, 'effective', '--json'], { cwd: dir, encoding: 'utf8' });
    assert.equal(res.status, 0, res.stderr);
    const json = JSON.parse(res.stdout);
    const expected = await readLedger(fsLedgerReader(dir));
    assert.equal(json.layout, 'split');
    assert.deepEqual(json.files, expected.files);
    assert.deepEqual(json.rows.map((r) => [r.id, r.file, r.line]), expected.rows.map((r) => [r.id, r.file, r.line]));
    assert.deepEqual(json.changes.applied, [cname('CMP-261124-1222', '20261127T010000Z')]);
    assert.deepEqual(json.changes.problems, []);
    const viaRoot = spawnSync(process.execPath, [ledgerCli, 'effective', '--json', '--root', dir], { cwd: repoRoot, encoding: 'utf8' });
    assert.deepEqual(JSON.parse(viaRoot.stdout), json);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('port-diff は生の月別ファイルへ適用し、未畳み込みの申告を月別ファイルへ書き込まない', async () => {
  const change = cname('CMP-261124-1222', '20261127T010000Z');
  const dir = writeTree({ ...base, [change]: upd(A, B, 'CMP-261124-1222') });
  try {
    const old = '# 台帳\n\n' + H + '\n' + A + '\n';
    await portDiff({ baseText: old, headText: old + row('CMP-261127-0900', 'P3 未着手') + '\n', root: dir });
    const m11 = readFileSync(join(dir, 'docs/task-list/2026-11.md'), 'utf8');
    assert.ok(m11.includes(A) && !m11.includes(B), '申告の内容が月別ファイルへ畳み込まれた');
    assert.ok(m11.includes('CMP-261127-0900'));
    assert.deepEqual(readdirSync(join(dir, 'docs/task-list/changes')), [change.slice(CHANGES_DIR.length + 1)]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('性能: 申告500件（add 250・update 250 の連鎖）で fs readLedger が1秒未満（基準環境 CI で厳守）', async () => {
  const files = { ...base };
  let prev = A;
  for (let i = 0; i < 250; i++) {
    const id = 'CMP-261127-' + String(1000 + i).padStart(4, '0');
    files[cname(id, '20261127T' + String(100000 + i).padStart(6, '0') + 'Z')] = add(row(id, 'P3 未着手'), id);
    const next = row('CMP-261124-1222', 'P2 実装中（' + i + '）');
    files[cname('CMP-261124-1222', '20261127T000000Z', String(i).padStart(6, '0'))] = upd(prev, next, 'CMP-261124-1222');
    prev = next;
  }
  const dir = writeTree(files);
  try {
    // 並列実行・一時的な負荷の揺れを避けるため、温めの1回の後に3回測って最小値で判定する
    await readLedger(fsLedgerReader(dir));
    let ms = Infinity;
    let ledger;
    for (let i = 0; i < 3; i++) {
      const t0 = performance.now();
      ledger = await readLedger(fsLedgerReader(dir));
      ms = Math.min(ms, performance.now() - t0);
    }
    assert.equal(ledger.changes.entries.length, 500);
    assert.equal(ledger.changes.applied.length, 500);
    assert.deepEqual(ledger.changes.problems, []);
    assert.equal(ledgerRowMap(ledger).get('CMP-261124-1222'), prev);
    // 計算部分（同じ500件をメモリ上で適用）はどの環境でも1秒未満
    let cpuMs = Infinity;
    for (let i = 0; i < 3; i++) {
      const t1 = performance.now();
      await readLedger(memoryLedgerReader(files));
      cpuMs = Math.min(cpuMs, performance.now() - t1);
    }
    assert.ok(cpuMs < 1000, 'メモリ上の適用 ' + cpuMs + ' ms');
    // 診断（判定には使わない）: 同じ500件を readFileSync するだけの I/O 下限との比
    const t2 = performance.now();
    const changesDir = join(dir, 'docs', 'task-list', 'changes');
    for (const name of readdirSync(changesDir)) readFileSync(join(changesDir, name), 'utf8');
    const ioFloorMs = performance.now() - t2;
    console.log('申告500件の fs readLedger: ' + ms.toFixed(1) + ' ms（3回の最小）/ メモリ上の適用: ' + cpuMs.toFixed(1) + ' ms / I/O 下限: ' + ioFloorMs.toFixed(1) + ' ms');
    // 基準環境（GitHub Actions の ubuntu-latest。CI=true）では fs readLedger 1秒未満を厳守する。
    // ローカル（リアルタイム走査の重い Windows 作業木など）は I/O 自体が1秒を超えうるため診断出力のみ。
    if (process.env.CI === 'true') assert.ok(ms < 1000, 'fs readLedger ' + ms + ' ms');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// 仮想 head の等式検査（P1-1）: mb=分岐点、base=PR の base、head=PR head（merge_group は mb=base）
// ---------------------------------------------------------------------------

async function vh({ mb, b = mb, h }) {
  return checkVirtualHead({ mergeBase: await read(mb), base: await read(b), head: await read(h) });
}
const kinds = (r) => ({ violations: r.violations.map((v) => v.kind), warnings: r.warnings.map((w) => w.kind) });
const fold = async (files) => Object.fromEntries((await read(files)).files.map((f) => [f.path, f.content]));

test('AC-6a: main だけ add/update/畳み込みが進んだ、台帳無変更の古い PR は緑', async () => {
  const mainAdvanced = await fold({ ...base, [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222') });
  const withPending = { ...mainAdvanced, [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900', 'P3 未着手'), 'CMP-261127-0900') };
  const r = await vh({ mb: base, b: withPending, h: base });
  assert.deepEqual(kinds(r), { violations: [], warnings: [] });
  assert.equal(r.ok, true);
});

test('AC-6b: 同じ行を main と競合更新した古い PR は赤（main が申告のまま=fork / 畳み込み済み=孤児）', async () => {
  const prChange = { [cname('CMP-261124-1222', '20261127T020000Z')]: upd(A, X, 'CMP-261124-1222') };
  const mainPending = { ...base, [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222') };
  const r1 = await vh({ mb: base, b: mainPending, h: { ...base, ...prChange } });
  assert.equal(r1.ok, false);
  assert.deepEqual(kinds(r1).violations, ['fork']);
  const mainFolded = await fold(mainPending);
  const r2 = await vh({ mb: base, b: mainFolded, h: { ...base, ...prChange } });
  assert.equal(r2.ok, false);
  assert.deepEqual(kinds(r2).violations, ['orphan']);
});

test('等式: 申告の追加だけの PR は緑', async () => {
  const r = await vh({ mb: base, h: { ...base, [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222') } });
  assert.deepEqual(kinds(r), { violations: [], warnings: [] });
});

test('等式（互換期間 P2-4）: enforceDirectEdit: false では月別ファイルの直接編集は警告（direct-edit）で、違反にはしない', async () => {
  const compat = async (h) => checkVirtualHead({ mergeBase: await read(base), base: await read(base), head: await read(h), enforceDirectEdit: false });
  const r = await compat({ ...base, 'docs/task-list/2026-11.md': M11.replace(A, B) });
  assert.equal(r.ok, true);
  assert.deepEqual(kinds(r), { violations: [], warnings: ['direct-edit'] });
  const r2 = await compat({ ...base, 'docs/task-list/2026-11.md': M11.replace('# 2026-11', '# 2026-11 改') });
  assert.deepEqual(kinds(r2), { violations: [], warnings: ['direct-edit'] });
});

test('等式（施行後 P2-4・段3）: 既定では月別ファイルの直接編集（行・行以外）は違反（direct-edit）で、申告 CLI と自動修復を案内する', async () => {
  const r = await vh({ mb: base, h: { ...base, 'docs/task-list/2026-11.md': M11.replace(A, B) } });
  assert.equal(r.ok, false);
  assert.deepEqual(kinds(r), { violations: ['direct-edit'], warnings: [] });
  assert.match(r.violations[0].message, /node scripts\/task-list-ledger\.mjs add/);
  assert.match(r.violations[0].message, /update/);
  assert.match(r.violations[0].message, /自動修復/);
  const r2 = await vh({ mb: base, h: { ...base, 'docs/task-list/2026-11.md': M11.replace('# 2026-11', '# 2026-11 改') } });
  assert.deepEqual(kinds(r2), { violations: ['direct-edit'], warnings: [] });
  // 申告の追加だけ・正しい畳み込みは施行後も緑（同じ入力で互換期間と結果が変わるのは直接編集だけ）
  const c1 = cname('CMP-261124-1222', '20261127T010000Z');
  assert.deepEqual(kinds(await vh({ mb: base, h: { ...base, [c1]: upd(A, B, 'CMP-261124-1222') } })), { violations: [], warnings: [] });
  const mb = { ...base, [c1]: upd(A, B, 'CMP-261124-1222') };
  assert.deepEqual(kinds(await vh({ mb, h: await fold(mb) })), { violations: [], warnings: [] });
});

test('等式: 申告の改変・削除は違反', async () => {
  const c1 = cname('CMP-261124-1222', '20261127T010000Z');
  const mb = { ...base, [c1]: upd(A, B, 'CMP-261124-1222') };
  const modified = await vh({ mb, h: { ...base, [c1]: upd(A, C, 'CMP-261124-1222') } });
  assert.equal(modified.ok, false);
  assert.ok(kinds(modified).violations.includes('change-modified'));
  const deleted = await vh({ mb, h: base });
  assert.equal(deleted.ok, false);
  assert.deepEqual(kinds(deleted).violations, ['change-deleted']);
});

test('等式: 正しい畳み込み（月別へ反映＋申告削除）は緑で fold と分類、不正な畳み込みは違反', async () => {
  const c1 = cname('CMP-261124-1222', '20261127T010000Z');
  const c2 = cname('CMP-261201-0000', '20261130T150000Z');
  const mb = { ...base, [c1]: upd(A, B, 'CMP-261124-1222'), [c2]: add(row('CMP-261201-0000', 'P3 未着手'), 'CMP-261201-0000') };
  const good = await vh({ mb, h: await fold(mb) });
  assert.deepEqual(kinds(good), { violations: [], warnings: [] });
  assert.equal(good.fold, true);
  assert.deepEqual(good.removedChanges, [c1, c2]);
  const folded = await fold(mb);
  const bad = await vh({ mb, h: { ...folded, 'docs/task-list/2026-11.md': folded['docs/task-list/2026-11.md'].replace(B, C) } });
  assert.equal(bad.ok, false);
  assert.deepEqual(kinds(bad).violations, ['change-deleted']);
});

test('AC-5c: 先行エントリが脱落して group が組み直されても等式は成立する（merge_group は base と head を直接比べる）', async () => {
  const pr1 = { [cname('CMP-261127-0900', '20261127T000000Z')]: add(row('CMP-261127-0900', 'P3 未着手'), 'CMP-261127-0900') };
  const pr2 = { [cname('CMP-261124-1222', '20261127T010000Z')]: upd(A, B, 'CMP-261124-1222') };
  const groupBoth = await vh({ mb: base, h: { ...base, ...pr1, ...pr2 } });
  assert.equal(groupBoth.ok, true);
  const rebuilt = await vh({ mb: base, h: { ...base, ...pr2 } }); // pr1 が脱落
  assert.deepEqual(kinds(rebuilt), { violations: [], warnings: [] });
  // 畳み込み PR が queue 中に別の申告がマージされても両方緑（AC-5）
  const foldPr = await fold({ ...base, ...pr1 });
  const afterFold = await vh({ mb: { ...base, ...pr1 }, h: { ...foldPr, ...pr2 } });
  assert.deepEqual(kinds(afterFold), { violations: [], warnings: [] });
});

test('仮想マージ: 分岐後に main が同じ ID を add し、古い PR も同じ ID を add すると、ファイル名時刻の前後に依らず赤', async () => {
  const id = 'CMP-261127-0900';
  for (const [mainTime, prTime] of [['20261127T020000Z', '20261127T010000Z'], ['20261127T010000Z', '20261127T020000Z']]) {
    const mainAdd = { [cname(id, mainTime, '111111')]: add(row(id, 'P3 未着手（main）'), id) };
    const prAdd = { [cname(id, prTime, '222222')]: add(row(id, 'P3 未着手（PR）'), id) };
    const r = await vh({ mb: base, b: { ...base, ...mainAdd }, h: { ...base, ...prAdd } });
    assert.equal(r.ok, false, mainTime + ' / ' + prTime);
    assert.deepEqual(kinds(r).violations, ['duplicate'], mainTime + ' / ' + prTime);
    assert.ok(r.violations[0].message.includes(Object.keys(prAdd)[0]) && r.violations[0].message.includes(Object.keys(mainAdd)[0]), r.violations[0].message);
    // main が畳み込み済み（月別ファイルに同じ ID）でも赤
    const folded = await fold({ ...base, ...mainAdd });
    const r2 = await vh({ mb: base, b: folded, h: { ...base, ...prAdd } });
    assert.deepEqual(kinds(r2).violations, ['duplicate']);
  }
});

test('等式（施行後）: 分岐点・base・head がすべて旧単一ファイル配置なら直接編集は警告のまま（施行は新配置の台帳だけ）', async () => {
  const single = (...rows) => ({ 'docs/task-list.md': '# 台帳\n\n' + H + '\n' + rows.join('\n') + '\n' });
  const r = await vh({ mb: single(A), h: single(B) });
  assert.deepEqual(kinds(r), { violations: [], warnings: ['direct-edit'] });
  // head だけ旧配置（main は新配置）なら施行する
  const r2 = await vh({ mb: single(A), b: base, h: single(B) });
  assert.deepEqual(kinds(r2).violations, ['direct-edit']);
});

test('CLI: --base <dir> で等式検査。施行後は直接編集も exit 1、--allow-direct-edit（互換期間）なら警告で exit 0、申告削除は常に exit 1', async () => {
  const c1 = cname('CMP-261124-1222', '20261127T010000Z');
  const baseDir = writeTree({ ...base, [c1]: upd(A, B, 'CMP-261124-1222') });
  const directDir = writeTree({ ...base, [c1]: upd(A, B, 'CMP-261124-1222'), 'docs/task-list/legacy.md': LEG.replace('P5 未着手', '完了') });
  const deletedDir = writeTree(base);
  try {
    const run = (dir, ...extra) => spawnSync(process.execPath, [checkCli, join(dir, 'docs/task-list.md'), '--base', baseDir, ...extra], { encoding: 'utf8' });
    const enforced = run(directDir);
    assert.equal(enforced.status, 1, enforced.stdout + enforced.stderr);
    assert.match(enforced.stderr, /direct-edit/);
    assert.match(enforced.stderr, /task-list-ledger\.mjs add/);
    const direct = run(directDir, '--allow-direct-edit');
    assert.equal(direct.status, 0, direct.stdout + direct.stderr);
    assert.match(direct.stdout + direct.stderr, /警告.*direct-edit|direct-edit.*警告/);
    const deleted = run(deletedDir);
    assert.equal(deleted.status, 1);
    assert.equal(run(deletedDir, '--allow-direct-edit').status, 1, '互換期間でも申告の削除は赤');
    assert.match(deleted.stderr, /change-deleted/);
    assert.match(deleted.stderr, new RegExp(c1.replace(/[.]/g, '\\.')));
  } finally {
    for (const d of [baseDir, directDir, deletedDir]) rmSync(d, { recursive: true, force: true });
  }
});

test('CLI: --base <git ref> でも等式検査ができる', async () => {
  const dir = writeTree(base);
  try {
    const git = (...args) => spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.com', '-c', 'core.autocrlf=false', ...args], { cwd: dir, encoding: 'utf8' });
    git('init', '-q', '-b', 'main');
    git('add', '-A');
    assert.equal(git('commit', '-q', '-m', 'x').status, 0);
    writeFileSync(join(dir, 'docs/task-list/2026-11.md'), M11.replace(A, B));
    const res = spawnSync(process.execPath, [checkCli, 'docs/task-list.md', '--base', 'HEAD'], { cwd: dir, encoding: 'utf8' });
    assert.equal(res.status, 1, '施行後は直接編集が exit 1: ' + res.stdout + res.stderr);
    assert.match(res.stdout + res.stderr, /direct-edit/);
    const compat = spawnSync(process.execPath, [checkCli, 'docs/task-list.md', '--base', 'HEAD', '--allow-direct-edit'], { cwd: dir, encoding: 'utf8' });
    assert.equal(compat.status, 0, '互換期間は警告のみ: ' + compat.stdout + compat.stderr);
    assert.match(compat.stdout + compat.stderr, /direct-edit/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('.gitattributes: 申告 docs/task-list/changes/*.md は text eol=lf で、merge=union を付けない', () => {
  const p = 'docs/task-list/changes/CMP-261124-1222--20261127T010000Z--000001.md';
  const res = spawnSync('git', ['check-attr', 'text', 'eol', 'merge', '--', p, 'docs/task-list/2026-11.md'], { cwd: repoRoot, encoding: 'utf8' });
  assert.equal(res.status, 0, res.stderr);
  const attrs = res.stdout.replace(/\r/g, '');
  assert.ok(attrs.includes(p + ': text: set'), attrs);
  assert.ok(attrs.includes(p + ': eol: lf'), attrs);
  assert.ok(attrs.includes(p + ': merge: unspecified'), attrs);
  assert.ok(attrs.includes('docs/task-list/2026-11.md: merge: union'), attrs);
});
