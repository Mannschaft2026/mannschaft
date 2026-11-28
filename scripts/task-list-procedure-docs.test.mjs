import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// CMP-261124-1222 ④ 段3: 台帳の規約文書から旧手順（月別ファイル・索引へ行を直接書く「末尾に追加」・port-diff で移す）が消え、
// 新手順（node scripts/task-list-ledger.mjs add / update で変更申告を作る）が書かれていることを固定する。

const repoRoot = fileURLToPath(new URL('../', import.meta.url));
const read = (path) => readFileSync(fileURLToPath(new URL('../' + path, import.meta.url)), 'utf8');

/** 台帳の追記手順を書いている規約文書（段3 で改訂した文書）。 */
const PROCEDURE_DOCS = [
  'CLAUDE.md',
  'README.md',
  'docs/task-list.md',
  'docs/development/merge_queue_operations.md',
  'docs/development/completion-evidence.md',
  'docs/development/claude-md-details.md',
  'docs/development/stalled-worktrees.md',
  'docs/development/boundary-coordination.md',
  '.claude/campaigns/_TEMPLATE.md',
  '.claude/commands/アリシゼーション.md',
  '.claude/commands/ボード更新.md',
  '.claude/commands/凱旋.md',
  '.claude/commands/出陣.md',
  '.claude/commands/試練.md',
  '.agents/skills/inventory-update/SKILL.md',
];

/** 旧手順の言い回し（台帳の行を月別ファイル・索引へ直接足す・port-diff で月別ファイルへ移す）。 */
const OLD_PROCEDURE = [
  /末尾に追加し/,
  /表の末尾に(?:追加|追記|起票)/,
  /月の?ファイル(?:の)?末尾/,
  /月別ファイルの末尾/,
  /末尾に追記し/,
  /\*\*表の末尾\*\*に起票/,
  /port-diff\s+<base>/,
];

test('規約文書（段3 の改訂対象）に台帳の旧手順（末尾に追加・port-diff）が残っていない', () => {
  const hits = [];
  for (const path of PROCEDURE_DOCS) {
    const lines = read(path).split('\n');
    lines.forEach((line, i) => {
      for (const pattern of OLD_PROCEDURE) if (pattern.test(line)) hits.push(path + ':' + (i + 1) + ': ' + line.slice(0, 120));
    });
  }
  assert.deepEqual(hits, []);
});

test('リポジトリの Markdown 全体（台帳の本体を除く）に台帳の旧手順の言い回しが残っていない（git grep）', () => {
  const args = ['grep', '-n', '-E', '末尾に追加し|表の末尾に(追加|追記|起票)|月の?ファイル(の)?末尾|月別ファイルの末尾|port-diff +<base>', '--', '*.md', ':!docs/task-list/*.md', ':!docs/task-list/changes/*'];
  const r = spawnSync('git', args, { cwd: repoRoot, encoding: 'utf8' });
  assert.ok(r.status === 0 || r.status === 1, 'git grep が失敗した: ' + r.stderr);
  // 台帳と無関係な「末尾に追加し」（機能仕様の文言）は対象外にする
  const ledgerHits = r.stdout.split('\n').filter(Boolean).filter((line) => /台帳|task-list|CMP/.test(line));
  assert.deepEqual(ledgerHits, []);
});

test('主要な規約文書は新手順（task-list-ledger.mjs add / update で申告を作る）を案内する', () => {
  for (const path of ['CLAUDE.md', 'README.md', 'docs/task-list.md', 'docs/development/merge_queue_operations.md', 'docs/development/claude-md-details.md', 'docs/development/stalled-worktrees.md', '.claude/commands/出陣.md', '.claude/commands/試練.md']) {
    const text = read(path);
    assert.match(text, /task-list-ledger\.mjs add/, path + ' に add の案内が無い');
    assert.match(text, /update <CMP-ID>|\bupdate\b/, path + ' に update の案内が無い');
    assert.match(text, /docs\/task-list\/changes\/|申告/, path + ' に申告の説明が無い');
  }
  // 重複・食い違いの扱いは fork・孤児と CLI の rebase（git の rebase ではない）
  for (const path of ['CLAUDE.md', 'docs/task-list.md', 'docs/development/claude-md-details.md']) {
    const text = read(path);
    assert.match(text, /fork/, path);
    assert.match(text, /孤児/, path);
    assert.match(text, /task-list-ledger\.mjs rebase/, path);
  }
});

test('台帳の月別・legacy ファイルの前置きと雛形（newFileSkeleton）も旧手順（末尾に追加）を案内しない', async () => {
  const { newFileSkeleton } = await import('./task-list-ledger.mjs');
  const { readdirSync } = await import('node:fs');
  const dir = fileURLToPath(new URL('../docs/task-list/', import.meta.url));
  for (const name of readdirSync(dir).filter((n) => n.endsWith('.md'))) {
    const head = readFileSync(dir + name, 'utf8').split('\n').slice(0, 5).join('\n');
    assert.doesNotMatch(head, /末尾に追加/, name);
    assert.match(head, /task-list-ledger\.mjs add \/ update/, name);
  }
  for (const path of ['docs/task-list/2026-12.md', 'docs/task-list/legacy.md']) {
    assert.doesNotMatch(newFileSkeleton(path), /末尾に追加/, path);
    assert.match(newFileSkeleton(path), /task-list-ledger\.mjs add \/ update/, path);
  }
});

test('docs/task-list.md の前文は更新タイミング・ID 採番・置き場所・畳み込みを申告前提で書く', () => {
  const text = read('docs/task-list.md');
  for (const heading of ['### 更新タイミング', '### ID の採番（日時形式）', '### 台帳の置き場（月別ファイル）']) assert.ok(text.includes(heading), heading);
  assert.match(text, /畳み込み/);
  assert.match(text, /直接編集は禁止/);
  assert.match(text, /mannschaft-ledger-bot\[bot\]/);
});
