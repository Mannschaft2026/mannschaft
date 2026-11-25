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
  ledgerText,
  readLedger,
} from './task-list-ledger.mjs';
import { checkTaskListStructure } from './task-list-structure-check.mjs';
import { memoryLedgerReader, splitSingleLedgerForTest } from './test-support/task-list-split.mjs';

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

const realSingle = readFileSync(join(repoRoot, 'docs/task-list.md'), 'utf8');

test('AC-1/AC-10: 実台帳（旧配置）と、そこから一時生成した新配置が同じ行集合（ID・行本文）を返し、どちらも構造検査に合格する', async () => {
  const single = await readLedger(fsLedgerReader(repoRoot));
  assert.equal(single.layout, 'single');
  assert.ok(single.rows.length > 0);
  const splitFiles = splitSingleLedgerForTest(realSingle);
  const split = await readLedger(memoryLedgerReader(splitFiles));
  assert.equal(split.layout, 'split');
  const norm = (line) => line.replace(/\]\(\.\.\/evidence\//g, '](evidence/');
  assert.deepEqual(split.rows.map((r) => r.id).sort(), single.rows.map((r) => r.id).sort());
  const singleLines = new Map(single.rows.map((r) => [r.id, r.line]));
  for (const r of split.rows) {
    assert.equal(norm(r.line), singleLines.get(r.id).replace(/\]\(\.\/evidence\//g, '](evidence/'), r.id);
    assert.equal(r.file, expectedLedgerFile(r.id));
  }
  assert.deepEqual(checkTaskListStructure(single).errors, []);
  assert.deepEqual(checkTaskListStructure(split).errors, []);
  assert.ok(!split.rows.some((r) => r.file === 'docs/task-list.md'));
  // 連結本文の CMP 行も一致（行単位で読む消費側の互換）
  const cmpLines = (text) => text.split(/\r?\n/).filter((l) => /^\|\s*CMP-/.test(l)).map(norm).sort();
  assert.deepEqual(cmpLines(ledgerText(split)), cmpLines(ledgerText(single)));
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
  const files = splitSingleLedgerForTest(realSingle);
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
  for (const name of ['scripts/task-list-ledger.test.mjs', 'scripts/task-list-ledger-consumers.test.mjs', 'scripts/completion-evidence-ledger.test.mjs']) {
    assert.ok(light.includes(name), name + ' が登録されていない');
  }
});
