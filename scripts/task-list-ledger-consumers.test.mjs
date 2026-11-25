import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { splitSingleLedgerForTest } from './test-support/task-list-split.mjs';

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

test('AC-8: β棚卸しの消費側（task_list_ledger.py 経由）が旧配置と新配置で同じ CMP 集合・状態・GitHub 参照集合を返す', { skip: python ? false : 'python が無い' }, () => {
  const single = readFileSync(join(repoRoot, 'docs', 'task-list.md'), 'utf8');
  const singleRoot = writeTree({ 'docs/task-list.md': single });
  const splitRoot = writeTree(splitSingleLedgerForTest(single));
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
