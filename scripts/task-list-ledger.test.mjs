import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  LedgerReadError,
  expectedLedgerFile,
  fsLedgerReader,
  gitLedgerReader,
  isLedgerFileName,
  ledgerRowMap,
  ledgerText,
  readLedger,
} from './task-list-ledger.mjs';
import { checkTaskListStructure } from './task-list-structure-check.mjs';
import { bothLayoutsFromLedger, joinSplitLedgerForTest, memoryLedgerReader, splitSingleLedgerForTest } from './test-support/task-list-split.mjs';

// CMP-261124-1222 段A: 台帳の両配置（旧: docs/task-list.md 1ファイル / 新: 索引＋docs/task-list/YYYY-MM.md・legacy.md）の
// 共通読み取り readLedger と、全ファイル横断の構造検査。共有 fixture は Java 番人（TaskListCmpIdDuplicateGuardTest）も読む。

const repoRoot = fileURLToPath(new URL('../', import.meta.url));
const scriptPath = fileURLToPath(new URL('./task-list-structure-check.mjs', import.meta.url));
const { cases } = JSON.parse(readFileSync(fileURLToPath(new URL('./fixtures/task-list-ledger-cases.json', import.meta.url)), 'utf8'));

test('共有 fixture が読めて、旧配置・新配置の両方を含む', () => {
  assert.ok(cases.length >= 40);
  assert.ok(cases.some((c) => c.name.startsWith('新配置')));
  assert.ok(cases.some((c) => c.name.startsWith('旧配置')));
});

for (const c of cases) {
  test('共有fixture: ' + c.name + ' → ' + (c.expectOk ? 'success' : 'fail'), async () => {
    const ledger = await readLedger(memoryLedgerReader(c.files), { requireRows: false });
    const result = checkTaskListStructure(ledger);
    assert.equal(result.ok, c.expectOk, JSON.stringify(result.errors));
    if (c.expectOk) assert.deepEqual(result.errors, []);
    else if (c.reason) assert.ok(result.errors.join('\n').includes(c.reason), c.reason + ' が理由に無い: ' + JSON.stringify(result.errors));
    if (c.expectRows) assert.deepEqual(ledger.rows.map((r) => [r.id, r.file]), c.expectRows);
    // 申告（docs/task-list/changes/）を含む case: 理由はすべて含まれる・実効行・実効の月別ファイル本文（CMP-261124-1222 ④）
    for (const r of c.reasons ?? []) assert.ok(result.errors.join('\n').includes(r), r + ' が理由に無い: ' + JSON.stringify(result.errors));
    if (c.expectLines) {
      const map = ledgerRowMap(ledger);
      for (const [id, line] of Object.entries(c.expectLines)) assert.equal(map.get(id), line, id);
    }
    for (const [p, content] of Object.entries(c.expectFiles ?? {})) assert.equal(ledger.files.find((f) => f.path === p)?.content, content, p);
  });
}

test('AC-1: 新配置の判定は docs/task-list/ に YYYY-MM.md か legacy.md があること', async () => {
  const idx = '# 索引\n';
  assert.equal((await readLedger(memoryLedgerReader({ 'docs/task-list.md': idx, 'docs/task-list/legacy.md': '' }), { requireRows: false })).layout, 'split');
  assert.equal((await readLedger(memoryLedgerReader({ 'docs/task-list.md': idx, 'docs/task-list/2026-11.md': '' }), { requireRows: false })).layout, 'split');
  assert.equal((await readLedger(memoryLedgerReader({ 'docs/task-list.md': idx, 'docs/task-list/README.md': '' }), { requireRows: false })).layout, 'single');
  assert.equal((await readLedger(memoryLedgerReader({ 'docs/task-list.md': idx }), { requireRows: false })).layout, 'single');
  assert.equal(isLedgerFileName('2026-00.md'), false);
  assert.equal(isLedgerFileName('2026-1.md'), false);
});

test('AC-1: 月境界（CMP-261130-2359→2026-11、CMP-261201-0000→2026-12）・旧形式→legacy', () => {
  assert.equal(expectedLedgerFile('CMP-261130-2359'), 'docs/task-list/2026-11.md');
  assert.equal(expectedLedgerFile('CMP-261201-0000'), 'docs/task-list/2026-12.md');
  assert.equal(expectedLedgerFile('CMP-003'), 'docs/task-list/legacy.md');
  assert.equal(expectedLedgerFile('CMP-1932'), 'docs/task-list/legacy.md');
  assert.equal(expectedLedgerFile('XYZ'), null);
});

test('AC-1: 取得失敗と0件・不在を区別する（失敗は空にしない）', async () => {
  const failing = { listDir: () => { throw Object.assign(new Error('HTTP 500'), { status: 500 }); }, readFile: () => '' };
  await assert.rejects(readLedger(failing), (e) => e instanceof LedgerReadError && /取得に失敗/.test(e.message) && /HTTP 500/.test(e.message));
  const failingFile = { listDir: () => ['2026-11.md'], readFile: async (p) => { if (p !== 'docs/task-list.md') throw new Error('rate limited'); return '# 索引\n'; } };
  await assert.rejects(readLedger(failingFile), /docs\/task-list\/2026-11\.md の取得に失敗.*rate limited/);
  const listedButAbsent = { listDir: () => ['2026-11.md'], readFile: (p) => (p === 'docs/task-list.md' ? '# 索引\n' : null) };
  await assert.rejects(readLedger(listedButAbsent), /一覧にあるのに本文を読めない/);
  await assert.rejects(readLedger(memoryLedgerReader({})), /存在しない/);
  await assert.rejects(readLedger(memoryLedgerReader({ 'docs/task-list.md': '# 台帳\n' })), /1件も読めなかった/);
  await assert.rejects(readLedger(memoryLedgerReader({ 'docs/task-list.md': '# 索引\n', 'docs/task-list/2026-11.md': '# 2026-11\n' })), /split 配置.*1件も/);
});

// 旧配置の検体（実台帳から独立。月境界・旧形式・証拠リンク・エスケープを含む）
const sampleSingle = '# 台帳\n\n前置き\n\n| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|\n'
  + '| CMP-003 | 旧 | 完了 | — | 条件 | 証拠 | l |\n'
  + '| CMP-261130-2359 | 月末 | 完了 | — | `a \\| b` | [証拠](evidence/CMP-261130-2359.json) | l |\n'
  + '| CMP-261201-0000 | 月初 | P2 実装中 | — | 条件 | [証拠](./evidence/CMP-261201-0000.json) | l |\n'
  + '| CMP-260101-0101 | 年初 | P5 未着手 | — | 条件 | 証拠 | l |\n';
const norm = (line) => line.replace(/\]\((?:\.\.?\/)?evidence\//g, '](evidence/');

async function assertSameRows(singleText, splitFiles) {
  const single = await readLedger(memoryLedgerReader({ 'docs/task-list.md': singleText }));
  const split = await readLedger(memoryLedgerReader(splitFiles));
  assert.equal(single.layout, 'single');
  assert.equal(split.layout, 'split');
  assert.deepEqual(split.rows.map((r) => r.id).sort(), single.rows.map((r) => r.id).sort());
  const singleLines = new Map(single.rows.map((r) => [r.id, norm(r.line)]));
  for (const r of split.rows) {
    assert.equal(norm(r.line), singleLines.get(r.id), r.id);
    assert.equal(r.file, expectedLedgerFile(r.id));
  }
  assert.deepEqual(checkTaskListStructure(single).errors, []);
  assert.deepEqual(checkTaskListStructure(split).errors, []);
  const cmpLines = (text) => text.split(/\r?\n/).filter((l) => /^\|\s*CMP-/.test(l)).map(norm).sort();
  assert.deepEqual(cmpLines(ledgerText(split)), cmpLines(ledgerText(single)));
  return { single, split };
}

test('AC-1: 独立した旧配置の検体と、そこから生成した新配置が同じ行集合（ID・行本文）を返し、どちらも構造検査に合格する', async () => {
  const { split } = await assertSameRows(sampleSingle, splitSingleLedgerForTest(sampleSingle));
  assert.deepEqual(split.rows.map((r) => [r.id, r.file]), [
    ['CMP-260101-0101', 'docs/task-list/2026-01.md'],
    ['CMP-261130-2359', 'docs/task-list/2026-11.md'],
    ['CMP-261201-0000', 'docs/task-list/2026-12.md'],
    ['CMP-003', 'docs/task-list/legacy.md'],
  ]);
  // 新配置から旧配置へ戻しても同じ
  await assertSameRows(joinSplitLedgerForTest(splitSingleLedgerForTest(sampleSingle)), splitSingleLedgerForTest(sampleSingle));
});

test('AC-1/AC-10: 実リポジトリの台帳（配置は問わない）が構造検査に合格し、もう一方の配置に写しても同じ行集合を返す', async () => {
  const real = await readLedger(fsLedgerReader(repoRoot));
  assert.ok(['single', 'split'].includes(real.layout));
  assert.ok(real.rows.length > 0);
  assert.deepEqual(checkTaskListStructure(real).errors, []);
  const both = bothLayoutsFromLedger(real);
  const { single, split } = await assertSameRows(both.single, both.split);
  assert.deepEqual([...(real.layout === 'single' ? single : split).rows.map((r) => r.id)], real.rows.map((r) => r.id));
});

test('AC-1/AC-2: CRLF 改行の新配置（月別ファイル・索引）でも LF と同じ行集合・同じ判定になる', async () => {
  const lf = splitSingleLedgerForTest(sampleSingle);
  const crlf = Object.fromEntries(Object.entries(lf).map(([p, c]) => [p, c.replace(/\n/g, '\r\n')]));
  const a = await readLedger(memoryLedgerReader(lf));
  const b = await readLedger(memoryLedgerReader(crlf));
  assert.deepEqual(b.rows.map((r) => [r.id, r.file, r.line]), a.rows.map((r) => [r.id, r.file, r.line]));
  assert.deepEqual(checkTaskListStructure(b).errors, []);
  const bad = { ...crlf, 'docs/task-list/2026-12.md': crlf['docs/task-list/2026-12.md'].replace('CMP-261201-0000', 'CMP-261130-2358') };
  const badLf = { ...lf, 'docs/task-list/2026-12.md': lf['docs/task-list/2026-12.md'].replace('CMP-261201-0000', 'CMP-261130-2358') };
  assert.deepEqual(checkTaskListStructure(await readLedger(memoryLedgerReader(bad))).errors, checkTaskListStructure(await readLedger(memoryLedgerReader(badLf))).errors);
});

test('AC-7: .gitattributes で月別ファイルは text eol=lf merge=union、索引は merge=union（git check-attr）', () => {
  const res = spawnSync('git', ['check-attr', 'text', 'eol', 'merge', '--', 'docs/task-list/2026-11.md', 'docs/task-list/legacy.md', 'docs/task-list.md'], { cwd: repoRoot, encoding: 'utf8' });
  assert.equal(res.status, 0, res.stderr);
  const attrs = res.stdout.replace(/\r/g, '');
  for (const p of ['docs/task-list/2026-11.md', 'docs/task-list/legacy.md']) {
    assert.match(attrs, new RegExp('^' + p.replace(/[./]/g, '\\$&') + ': text: set$', 'm'));
    assert.match(attrs, new RegExp('^' + p.replace(/[./]/g, '\\$&') + ': eol: lf$', 'm'));
    assert.match(attrs, new RegExp('^' + p.replace(/[./]/g, '\\$&') + ': merge: union$', 'm'));
  }
  assert.match(attrs, /^docs\/task-list\.md: merge: union$/m);
});

async function withTempRepo(files, fn) {
  const dir = mkdtempSync(join(tmpdir(), 'task-list-ledger-'));
  try {
    for (const [p, c] of Object.entries(files)) {
      mkdirSync(dirname(join(dir, p)), { recursive: true });
      writeFileSync(join(dir, p), c);
    }
    return await fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('AC-1: 作業木・git tree の reader が同じ行集合を返す（新配置）', async () => {
  const files = bothLayoutsFromLedger(await readLedger(fsLedgerReader(repoRoot))).split;
  await withTempRepo(files, async (dir) => {
    const git = (...args) => spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.com', '-c', 'core.autocrlf=false', ...args], { cwd: dir, encoding: 'utf8' });
    git('init', '-q', '-b', 'main');
    git('add', '-A');
    assert.equal(git('commit', '-q', '-m', 'split').status, 0);
    const fromFs = await readLedger(fsLedgerReader(dir));
    const fromGit = await readLedger(gitLedgerReader('HEAD', { cwd: dir }));
    assert.equal(fromGit.layout, 'split');
    assert.deepEqual(fromGit.rows.map((r) => [r.id, r.file, r.line]), fromFs.rows.map((r) => [r.id, r.file, r.line]));
    assert.throws(() => gitLedgerReader('no-such-ref', { cwd: dir }));
  });
});

test('AC-2: CLI は索引と同じディレクトリの task-list/ を読み、違反をファイル名付きで出して exit 1', async () => {
  const ok = splitSingleLedgerForTest('# 台帳\n\n| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|\n| CMP-261130-2359 | 戦 | 完了 | — | 条件 | 証拠 | l |\n| CMP-261201-0000 | 戦 | P3 未着手 | — | 条件 | 証拠 | l |\n');
  await withTempRepo(ok, (dir) => {
    const res = spawnSync(process.execPath, [scriptPath, join(dir, 'docs/task-list.md')], { encoding: 'utf8' });
    assert.equal(res.status, 0, res.stdout + res.stderr);
    assert.match(res.stdout, /split 配置・3 ファイル/);
  });
  const bad = { ...ok, 'docs/task-list/2026-12.md': ok['docs/task-list/2026-12.md'].replace('CMP-261201-0000', 'CMP-261130-2358') };
  await withTempRepo(bad, (dir) => {
    const res = spawnSync(process.execPath, [scriptPath, join(dir, 'docs/task-list.md')], { encoding: 'utf8' });
    assert.equal(res.status, 1);
    assert.match(res.stderr, /docs\/task-list\/2026-12\.md: 配置: CMP-261130-2358 は docs\/task-list\/2026-11\.md/);
    assert.doesNotMatch(res.stderr, /\n\s+at /);
  });
});

test('新試験が PR 軽量検査（pr-light-checks.yml）で走る', () => {
  const light = readFileSync(join(repoRoot, '.github/workflows/pr-light-checks.yml'), 'utf8');
  for (const name of ['scripts/task-list-ledger.test.mjs', 'scripts/task-list-ledger-consumers.test.mjs', 'scripts/task-list-ledger-changes.test.mjs', 'scripts/completion-evidence-ledger.test.mjs']) {
    assert.ok(light.includes(name), name + ' が登録されていない');
  }
});
