import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bothLayoutsFromLedger } from './test-support/task-list-split.mjs';
import { createHash } from 'node:crypto';
import { fsLedgerReader, ledgerText, readLedger } from './task-list-ledger.mjs';

// CMP-261124-1222 段A AC-8: 台帳を読む docs/prototypes の消費側（generate-beta-inventory-board-data.py・
// sync-beta-inventory-github.py が使う task_list_ledger.py）が、旧配置の実台帳と、そこから一時生成した新配置で
// 同じ CMP 集合・状態・GitHub 参照集合を返すこと。ハッシュは旧配置では docs/task-list.md の sha256 のまま。

const repoRoot = fileURLToPath(new URL('../', import.meta.url));
const prototypes = join(repoRoot, 'docs', 'prototypes');
const python = ['python3', 'python'].find((cmd) => spawnSync(cmd, ['--version'], { encoding: 'utf8' }).status === 0);

function writeTree(files) {
  const dir = mkdtempSync(join(tmpdir(), 'task-list-consumers-'));
  for (const [p, c] of Object.entries(files)) {
    mkdirSync(dirname(join(dir, p)), { recursive: true });
    writeFileSync(join(dir, p), c);
  }
  return dir;
}

const probe = String.raw`
import hashlib, importlib.util, json, sys
from pathlib import Path
prototypes, roots = sys.argv[1], sys.argv[2:]
sys.path.insert(0, prototypes)
import task_list_ledger as L
def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(prototypes) / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
gen = load("gen", "generate-beta-inventory-board-data.py")
sync = load("sync", "sync-beta-inventory-github.py")
out = []
for root in map(Path, roots):
    text = L.read_ledger_text(root)
    campaigns = gen.parse_ledger_campaigns(root)
    out.append({
        "layout": L.ledger_layout(root),
        "ids": [c["id"] for c in campaigns],
        "status": {c["id"]: [c["status"], c["taskPriority"]] for c in campaigns},
        "githubRefs": {c["id"]: c["githubRefs"] for c in campaigns},
        "sources": sorted({c["source"] for c in campaigns}),
        "syncRefs": sync.cmp_refs(text),
        "sha256": L.ledger_sha256(root),
        "indexSha256": hashlib.sha256((root / "docs" / "task-list.md").read_bytes()).hexdigest(),
    })
print(json.dumps(out, ensure_ascii=False))
`;

test('AC-8: β棚卸しの消費側（task_list_ledger.py 経由）が旧配置と新配置で同じ CMP 集合・状態・GitHub 参照集合を返す', { skip: python ? false : 'python が無い' }, async () => {
  // 実リポジトリの台帳は配置を問わない（段B の移行後も通る）。旧配置・新配置の両方の検体へ写して比べる。
  const both = bothLayoutsFromLedger(await readLedger(fsLedgerReader(repoRoot)));
  const singleRoot = writeTree({ 'docs/task-list.md': both.single });
  const splitRoot = writeTree(both.split);
  try {
    const res = spawnSync(python, ['-I', '-c', probe, prototypes, singleRoot, splitRoot], { encoding: 'utf8', env: { ...process.env, PYTHONIOENCODING: 'utf-8' } });
    assert.equal(res.status, 0, res.stderr);
    const [a, b] = JSON.parse(res.stdout);
    assert.equal(a.layout, 'single');
    assert.equal(b.layout, 'split');
    assert.ok(a.ids.length > 0);
    assert.deepEqual([...b.ids].sort(), [...a.ids].sort(), 'CMP 集合');
    assert.deepEqual(b.status, a.status, '状態・優先度');
    assert.deepEqual(b.githubRefs, a.githubRefs, 'GitHub 参照集合（生成側）');
    assert.deepEqual(b.syncRefs, a.syncRefs, 'GitHub 参照集合（同期側）');
    assert.deepEqual(a.sources, ['docs/task-list.md']);
    assert.ok(b.sources.every((s) => /^docs\/task-list\/(?:\d{4}-\d{2}|legacy)\.md$/.test(s)), b.sources.join(','));
    assert.equal(a.sha256, a.indexSha256, '旧配置のハッシュは docs/task-list.md の sha256 のまま');
  } finally {
    rmSync(singleRoot, { recursive: true, force: true });
    rmSync(splitRoot, { recursive: true, force: true });
  }
});

test('AC-8: inventory-update スキルの手順が両配置を案内する', () => {
  const skill = readFileSync(join(repoRoot, '.agents', 'skills', 'inventory-update', 'SKILL.md'), 'utf8');
  assert.match(skill, /docs\/task-list\/YYYY-MM\.md/);
  assert.match(skill, /legacy\.md/);
});

// ---------------------------------------------------------------------------
// CMP-261124-1222 ④（P2-3）: 変更申告（docs/task-list/changes/）を適用した実効台帳を Python の消費側も読む。
// 委譲方式（node scripts/task-list-ledger.mjs effective --json）。申告0件なら node を呼ばず従来どおり。
// ---------------------------------------------------------------------------

const probe2 = String.raw`
import hashlib, importlib.util, json, sys
from pathlib import Path
prototypes, roots = sys.argv[1], sys.argv[2:]
sys.path.insert(0, prototypes)
import task_list_ledger as L
def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(prototypes) / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
gen = load("gen", "generate-beta-inventory-board-data.py")
sync = load("sync", "sync-beta-inventory-github.py")
out = []
for root in map(Path, roots):
    try:
        text = L.read_ledger_text(root)
        campaigns = gen.parse_ledger_campaigns(root)
        raw = hashlib.sha256()
        for p in L.ledger_files(root):
            raw.update(p.read_bytes())
        out.append({
            "text": text,
            "campaigns": {c["id"]: {"status": c["status"], "githubRefs": c["githubRefs"], "source": c["source"]} for c in campaigns},
            "syncRefs": sync.cmp_refs(text),
            "sha256": L.ledger_sha256(root),
            "rawSha256": raw.hexdigest(),
            "sourceFiles": [L.relative(root, p) for p in L.ledger_source_files(root)],
        })
    except Exception as error:
        out.append({"error": type(error).__name__ + ": " + str(error)})
print(json.dumps(out, ensure_ascii=True))
`;

function runProbe(roots, env = {}) {
  const res = spawnSync(python, ['-I', '-c', probe2, prototypes, ...roots], { encoding: 'utf8', env: { ...process.env, PYTHONIOENCODING: 'utf-8', ...env } });
  assert.equal(res.status, 0, res.stderr);
  return JSON.parse(res.stdout);
}

const H = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const row = (id, status, evidence) => '| ' + id + ' | 戦役 | ' + status + ' | — | 条件 | ' + evidence + ' | ledger |';
const sha = (s) => 'sha256:' + createHash('sha256').update(s.replace(/\r/g, ''), 'utf8').digest('hex');
const meta = (op, id, b) => '<!-- ledger-change v1 op=' + op + ' id=' + id + ' base=' + b + ' -->';
const OLD = row('CMP-261124-1222', 'P2 実装中', 'PR #1111');
const NEW = row('CMP-261124-1222', '完了', 'PR #2222');
const ADDED = row('CMP-261201-0000', 'P3 未着手', 'Issue #3333');
const splitBase = {
  'docs/task-list.md': '# 台帳（索引）\n',
  'docs/task-list/2026-11.md': '# 2026-11\n\n' + H + '\n' + row('CMP-261101-0000', '完了', 'PR #1000') + '\n' + OLD + '\n',
  'docs/task-list/legacy.md': '# legacy\n\n' + H + '\n' + row('CMP-003', 'P5 未着手', '—') + '\n',
};
const withChanges = {
  ...splitBase,
  'docs/task-list/changes/CMP-261124-1222--20261127T010000Z--000001.md': meta('update', 'CMP-261124-1222', sha(OLD)) + '\n' + NEW + '\n',
  'docs/task-list/changes/CMP-261201-0000--20261130T150000Z--000001.md': meta('add', 'CMP-261201-0000', 'none') + '\n' + ADDED + '\n',
};

test('P2-3: 実 parse_ledger_campaigns() は未畳み込み update 後の状態・証拠・GitHub 参照だけを返す（Node の実効台帳と一致）', { skip: python ? false : 'python が無い' }, async () => {
  const root = writeTree(withChanges);
  try {
    const [r] = runProbe([root]);
    assert.equal(r.error, undefined, r.error);
    assert.deepEqual(r.campaigns['CMP-261124-1222'], { status: 'done', githubRefs: [2222], source: 'docs/task-list/2026-11.md' });
    assert.deepEqual(r.campaigns['CMP-261201-0000'], { status: 'not-started', githubRefs: [3333], source: 'docs/task-list/2026-12.md' });
    assert.ok(!JSON.stringify(r.syncRefs).includes('1111'), '畳み込み前の行の参照を拾っている');
    const effective = await readLedger(fsLedgerReader(root));
    const cmp = (text) => text.split('\n').map((l) => l.replace(/\r$/, '')).filter((l) => /^\|\s*CMP-/.test(l)).sort();
    assert.deepEqual(cmp(r.text), cmp(ledgerText(effective)));
    assert.notEqual(r.sha256, r.rawSha256, '申告があるときのハッシュは実効台帳で計算する');
    assert.ok(r.sourceFiles.includes('docs/task-list/changes/CMP-261124-1222--20261127T010000Z--000001.md'), 'taskListCommit の入力に申告を含める');
    // 畳み込み後（月別に反映・申告削除）も同じ CMP 集合・状態・参照・ハッシュ
    const folded = writeTree(Object.fromEntries(effective.files.map((f) => [f.path, f.content])));
    try {
      const [f] = runProbe([folded]);
      assert.deepEqual(f.campaigns, r.campaigns);
      assert.equal(f.sha256, r.sha256);
    } finally {
      rmSync(folded, { recursive: true, force: true });
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('AC-1: 申告0件（changes/ 無し・空）では node を呼ばず、Python の出力・ハッシュは申告導入前と同じ', { skip: python ? false : 'python が無い' }, () => {
  const none = writeTree(splitBase);
  const empty = writeTree(splitBase);
  mkdirSync(join(empty, 'docs', 'task-list', 'changes'));
  try {
    // node が無い環境でも動く（申告0件では委譲しない）
    const [a, b] = runProbe([none, empty], { TASK_LIST_LEDGER_NODE: join(none, 'no-such-node') });
    assert.equal(a.error, undefined, a.error);
    assert.equal(b.error, undefined, b.error);
    assert.equal(a.sha256, a.rawSha256, '申告0件のハッシュは生のファイルのバイト列');
    const { sourceFiles: _a, ...restA } = a;
    const { sourceFiles: _b, ...restB } = b;
    assert.deepEqual(restB, restA);
  } finally {
    rmSync(none, { recursive: true, force: true });
    rmSync(empty, { recursive: true, force: true });
  }
});

test('申告があるのに node が無いときは止まる（生の月別ファイルだけで続けない）', { skip: python ? false : 'python が無い' }, () => {
  const root = writeTree(withChanges);
  try {
    const [r] = runProbe([root], { TASK_LIST_LEDGER_NODE: join(root, 'no-such-node') });
    assert.match(r.error ?? '', /RuntimeError: .*node が見つからない/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('共有 fixture の申告 case（合格するもの）で Python の実効台帳が Node と一致し、問題のある case は止まる', { skip: python ? false : 'python が無い' }, async () => {
  const { cases } = JSON.parse(readFileSync(join(repoRoot, 'scripts', 'fixtures', 'task-list-ledger-cases.json'), 'utf8'));
  const targets = cases.filter((c) => c.name.startsWith('申告:') && Object.keys(c.files).some((p) => p.startsWith('docs/task-list/changes/')) && !Object.keys(c.files).some((p) => /changes\/[^/]+\//.test(p)));
  assert.ok(targets.length >= 20, String(targets.length));
  const roots = targets.map((c) => writeTree(c.files));
  try {
    const results = runProbe(roots);
    for (const [i, c] of targets.entries()) {
      const node = await readLedger(fsLedgerReader(roots[i]), { requireRows: false });
      const cmp = (text) => text.split('\n').map((l) => l.replace(/\r$/, '')).filter((l) => /^\|\s*CMP-/.test(l)).sort();
      if (node.changes.problems.length === 0) {
        assert.equal(results[i].error, undefined, c.name + ': ' + results[i].error);
        assert.deepEqual(cmp(results[i].text), cmp(ledgerText(node)), c.name);
      } else {
        assert.match(results[i].error ?? '', /RuntimeError: 台帳の変更申告に問題がある/, c.name);
      }
    }
  } finally {
    for (const r of roots) rmSync(r, { recursive: true, force: true });
  }
});
