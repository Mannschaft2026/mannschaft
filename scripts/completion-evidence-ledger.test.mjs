import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inferImplementationScope, parseTaskList, selectEvidencePaths, validateCompletionChanges } from './completion-evidence.mjs';
import { readLedger } from './task-list-ledger.mjs';
import { memoryLedgerReader, splitSingleLedgerForTest } from './test-support/task-list-split.mjs';

// CMP-261124-1222 段A（AC-4 / AC-11）: 完了証拠の検査器が台帳の両配置（旧: 1ファイル / 新: 索引＋月別・legacy）を判別し、
// 分岐点（ancestor）・base・head の配置の組み合わせが変わっても完了遷移・削除防止の判定が変わらないこと。
// 証拠リンク evidence/X と ../evidence/X は同じ証拠として扱う（移行を差し替えと誤判定しない）。

const scriptPath = fileURLToPath(new URL('./completion-evidence.mjs', import.meta.url));
const headers = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const row = (id, state, evidence = '説明') => '| ' + id + ' | 戦役 | ' + state + ' | — | 条件 | ' + evidence + ' | ledger |';
const doc = (...rows) => '# 台帳\n\n前置き\n\n' + headers + '\n' + rows.join('\n') + '\n';
const link = (id) => '[証拠](evidence/' + id + '.json)';
const A = 'CMP-261130-2359';
const B = 'CMP-261201-0000';
const L = 'CMP-003';
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

async function asLayout(text, layout) {
  if (layout === 'single') return text;
  return readLedger(memoryLedgerReader(splitSingleLedgerForTest(text)));
}

const LAYOUTS = ['single', 'split'];
const COMBOS = LAYOUTS.flatMap((a) => LAYOUTS.flatMap((b) => LAYOUTS.map((h) => ({ ancestor: a, base: b, head: h }))));

/** 全配置の組み合わせで fn を走らせ、結果（返り値か例外の文言）がすべて旧配置だけの場合と一致することを確かめる。 */
async function assertSameAcrossLayouts({ ancestor, base, head }, fn) {
  const outcome = async (layouts) => {
    const a = ancestor === undefined ? undefined : await asLayout(ancestor, layouts.ancestor);
    const b = await asLayout(base, layouts.base);
    const h = await asLayout(head, layouts.head);
    try {
      return { value: fn(b, h, a) };
    } catch (error) {
      return { error: error.message };
    }
  };
  const expected = await outcome({ ancestor: 'single', base: 'single', head: 'single' });
  for (const combo of COMBOS) assert.deepEqual(await outcome(combo), expected, JSON.stringify(combo));
  return expected;
}

test('AC-4: parseTaskList は新配置（ファイル横断）を旧配置と同じ byId で返し、ファイル跨ぎの重複を拒否する', async () => {
  const text = doc(row(A, '完了', link(A)), row(B, 'P3 未着手'), row(L, '完了'));
  const single = parseTaskList(text);
  const split = parseTaskList(await asLayout(text, 'split'));
  assert.deepEqual([...split.byId.keys()].sort(), [...single.byId.keys()].sort());
  assert.equal(split.byId.get(A).file, 'docs/task-list/2026-11.md');
  assert.equal(split.byId.get(B).file, 'docs/task-list/2026-12.md');
  assert.equal(split.byId.get(L).file, 'docs/task-list/legacy.md');
  assert.equal(split.byId.get(A).state, '完了');
  assert.equal(split.byId.get(B).priority, 'P3');
  const dup = { layout: 'split', files: [
    { path: 'docs/task-list.md', content: '# 索引\n' },
    { path: 'docs/task-list/2026-11.md', content: headers + '\n' + row(A, '完了') + '\n' },
    { path: 'docs/task-list/2026-12.md', content: headers + '\n' + row(A, 'P3 未着手') + '\n' },
  ] };
  assert.throws(() => parseTaskList(dup), /CMP-261130-2359 の重複/);
});

test('AC-4: 新しい完了（証拠 JSON 付き）は全配置の組み合わせで同じ証拠を選び、同じく検証する', async () => {
  const base = doc(row(A, '実装中'), row(L, '完了'));
  const head = doc(row(A, '完了', link(A)), row(L, '完了'));
  const selected = await assertSameAcrossLayouts({ ancestor: base, base, head }, (b, h, a) => selectEvidencePaths(b, h, [], { ancestorTaskList: a }));
  assert.deepEqual(selected.value, ['docs/evidence/' + A + '.json']);
  const evidenceByPath = { ['docs/evidence/' + A + '.json']: evidenceFor(A) };
  const validated = await assertSameAcrossLayouts({ ancestor: base, base, head }, (b, h, a) => validateCompletionChanges(b, h, evidenceByPath, { ...validationOptions, ancestorTaskList: a }));
  assert.equal(validated.value, true);
  const missing = await assertSameAcrossLayouts({ ancestor: base, base, head }, (b, h, a) => validateCompletionChanges(b, h, {}, { ...validationOptions, ancestorTaskList: a }));
  assert.match(missing.error, /証拠JSONが空または不正/);
});

test('AC-4: 過去完了行の削除・証拠リンクの削除・差し替えは全配置の組み合わせで同じく拒否する', async () => {
  const base = doc(row(A, '完了', link(A)), row(B, '完了', link(B)));
  const deleted = await assertSameAcrossLayouts({ ancestor: base, base, head: doc(row(B, '完了', link(B))) }, (b, h, a) => selectEvidencePaths(b, h, [], { ancestorTaskList: a }));
  assert.match(deleted.error, /CMP-261130-2359: 過去完了行・証拠を削除できません/);
  const unlinked = await assertSameAcrossLayouts({ ancestor: base, base, head: doc(row(A, '完了', '説明'), row(B, '完了', link(B))) }, (b, h, a) => validateCompletionChanges(b, h, {}, { ...validationOptions, ancestorTaskList: a }));
  assert.match(unlinked.error, /証拠リンクを削除できません/);
  const replaced = await assertSameAcrossLayouts({ ancestor: base, base, head: doc(row(A, '完了', '[証拠](evidence/' + B + '.json)'), row(B, '完了', link(B))) }, (b, h, a) => selectEvidencePaths(b, h, [], { ancestorTaskList: a }));
  assert.match(replaced.error, /一致しません/);
});

test('AC-4: 分岐点より後に main で完了した行を head が持たない（main の前進）は全配置の組み合わせで拒否しない', async () => {
  const ancestor = doc(row(A, '実装中'));
  const base = doc(row(A, '実装中'), row(B, '完了', link(B)));
  const head = doc(row(A, '実装中'));
  const r = await assertSameAcrossLayouts({ ancestor, base, head }, (b, h, a) => selectEvidencePaths(b, h, [], { ancestorTaskList: a }));
  assert.deepEqual(r.value, []);
  // 分岐点を渡さなければ削除と判定する（対照）
  await assert.rejects(async () => selectEvidencePaths(await asLayout(base, 'split'), head, []), /過去完了行・証拠を削除できません/);
});

test('AC-4: 移行（evidence/X → ../evidence/X・行の移動）だけの差分は証拠の差し替えと判定しない', async () => {
  const text = doc(row(A, '完了', link(A)), row(B, '完了', '[証拠](./evidence/' + B + '.json)'), row(L, '完了'));
  const split = await asLayout(text, 'split');
  assert.ok(split.files.some((f) => f.content.includes('](../evidence/' + A + '.json)')), '移行後のリンクは ../evidence/');
  assert.deepEqual(selectEvidencePaths(text, split, [], { ancestorTaskList: text }), []);
  assert.equal(validateCompletionChanges(text, split, {}, { ...validationOptions, ancestorTaskList: text }), true);
  assert.equal(parseTaskList(split).byId.get(A).evidence.includes('../evidence/'), true);
  // 証拠 JSON 自体が変わったときは、移行後のリンクでも同じパスを選ぶ
  assert.deepEqual(selectEvidencePaths(text, split, ['docs/evidence/' + A + '.json'], { ancestorTaskList: text }), ['docs/evidence/' + A + '.json']);
});

test('AC-11: .agents/ は tooling に分類する', () => {
  assert.equal(inferImplementationScope(['.agents/skills/inventory-update/SKILL.md']), 'tooling');
  assert.equal(inferImplementationScope(['.agents/skills/x/SKILL.md', 'scripts/a.mjs']), 'tooling');
  assert.equal(inferImplementationScope(['.agents/skills/x/SKILL.md', 'backend/src/main/java/A.java']), 'app');
  assert.equal(inferImplementationScope(['.agentsx/a.md']), 'app');
});

function withGitRepo(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'completion-ledger-'));
  const git = (...args) => spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.com', '-c', 'core.autocrlf=false', ...args], { cwd: dir, encoding: 'utf8' });
  const commit = (files, remove = []) => {
    for (const p of remove) rmSync(join(dir, p), { force: true });
    for (const [p, c] of Object.entries(files)) {
      mkdirSync(dirname(join(dir, p)), { recursive: true });
      writeFileSync(join(dir, p), c);
    }
    git('add', '-A');
    assert.equal(git('commit', '-q', '-m', 'c').status, 0);
    return git('rev-parse', 'HEAD').stdout.trim();
  };
  git('init', '-q', '-b', 'main');
  try {
    return fn({ dir, commit });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('AC-4: CLI --base/--head は各 ref の配置を判別する（旧配置 base → 新配置 head の移行は合格、新配置 head で完了行を消すと不合格）', () => {
  withGitRepo(({ dir, commit }) => {
    const text = doc(row(A, '完了', '説明'), row(L, '完了'));
    const baseSha = commit({ 'docs/task-list.md': text });
    const headSha = commit(splitSingleLedgerForTest(text));
    const ok = spawnSync(process.execPath, [scriptPath, '--base', baseSha, '--head', headSha], { cwd: dir, encoding: 'utf8' });
    assert.equal(ok.status, 0, ok.stdout + ok.stderr);
    const split = splitSingleLedgerForTest(text);
    const brokenSha = commit({ ...split, 'docs/task-list/2026-11.md': split['docs/task-list/2026-11.md'].replace(/^\| CMP-261130-2359 .*\n/m, '') });
    const ng = spawnSync(process.execPath, [scriptPath, '--base', baseSha, '--head', brokenSha], { cwd: dir, encoding: 'utf8' });
    assert.equal(ng.status, 1, ng.stdout + ng.stderr);
    assert.match(ng.stderr, /CMP-261130-2359: 過去完了行・証拠を削除できません/);
  });
});
