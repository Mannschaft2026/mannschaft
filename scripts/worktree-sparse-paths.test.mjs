import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  computeSparsePaths,
  findUncoveredPaths,
  compareWithSettings,
  validateExclusions,
} from './worktree-sparse-paths.mjs';

const EX = ['frontend/dev-fixtures/ranch', 'frontend/public/ranch/approved', 'backend/src/main/resources/ranch/approved'];

// ls-tree -r -d 相当（祖先を全て含む）
function withAncestors(dirs) {
  const all = new Set();
  for (const d of dirs) {
    const parts = d.split('/');
    for (let i = 1; i <= parts.length; i++) all.add(parts.slice(0, i).join('/'));
  }
  return [...all];
}
const BASE = withAncestors([
  '.claude', '.github', 'docs/development', 'scripts', 'config',
  'frontend/app', 'frontend/dev-fixtures/ranch', 'frontend/dev-fixtures/other', 'frontend/public/icons',
  'frontend/public/ranch/approved', 'frontend/public/ranch/approved/sub',
  'backend/src/main/java', 'backend/src/main/resources/ranch/approved', 'backend/src/main/resources/ranch/development',
  'backend/src/main/resources/db', 'backend/src/test',
]);

test('除外パス自体は出ず、祖先は子に分解され、兄弟は全て出る', () => {
  const r = computeSparsePaths(BASE, EX);
  assert.deepEqual(r, [
    '.claude', '.github', 'backend/src/main/java', 'backend/src/main/resources/db',
    'backend/src/main/resources/ranch/development', 'backend/src/test', 'config', 'docs',
    'frontend/app', 'frontend/dev-fixtures/other', 'frontend/public/icons', 'scripts',
  ].sort());
  for (const e of EX) assert.ok(!r.includes(e));
  assert.ok(!r.includes('frontend'));
  assert.ok(!r.includes('backend'));
});

test('出力はソート済みで重複がない', () => {
  const r = computeSparsePaths(BASE, EX);
  assert.deepEqual(r, [...new Set(r)].sort());
});

test('新しいトップレベル／frontend 直下／除外の兄弟が出力に現れる', () => {
  for (const d of ['newtop', 'frontend/newdir', 'frontend/public/ranch/other']) {
    const r = computeSparsePaths([...BASE, ...withAncestors([d])], EX);
    assert.ok(r.includes(d), d);
  }
});

test('除外リストが空ならトップレベルの全ディレクトリ', () => {
  assert.deepEqual(computeSparsePaths(BASE, []), ['.claude', '.github', 'backend', 'config', 'docs', 'frontend', 'scripts']);
});

test('除外ディレクトリが入力に存在しないとエラー', () => {
  assert.throws(() => computeSparsePaths(BASE, ['frontend/gone']), /存在しない|見つから/);
});

test('不正な除外定義はエラー', () => {
  const bad = [
    ['祖先子孫', ['frontend/public', 'frontend/public/ranch/approved']],
    ['末尾スラッシュ', ['frontend/dev-fixtures/ranch/']],
    ['バックスラッシュ', [['frontend', 'dev-fixtures', 'ranch'].join(String.fromCharCode(92))]],
    ['重複', [EX[0], EX[0]]],
    ['空文字', ['']],
    ['dotdot', ['frontend/../scripts']],
    ['先頭スラッシュ', ['/frontend/app']],
    ['配列でない', 'frontend/app'],
  ];
  for (const [name, ex] of bad) {
    assert.throws(() => validateExclusions(ex), undefined, name);
    assert.throws(() => computeSparsePaths(BASE, ex), undefined, name);
  }
});

test('findUncoveredPaths: cone の取りこぼしを検出する', () => {
  assert.deepEqual(findUncoveredPaths(['a/x/file'], ['a/b/c'], []), ['a/x/file']);
  assert.deepEqual(findUncoveredPaths(['a/file', 'a/b/file', 'a/b/c/file', 'a/b/c/d/file', 'rootfile'], ['a/b/c'], []), []);
});

test('findUncoveredPaths: 除外配下と除外の祖先直下は被覆扱い', () => {
  assert.deepEqual(findUncoveredPaths(['p/q/r/img.png'], ['z'], ['p/q/r']), []);
  assert.deepEqual(findUncoveredPaths(['p/other/f'], ['z'], ['p/q/r']), ['p/other/f']);
});

test('生成結果は追跡ファイルを全て被覆する', () => {
  const files = [...BASE.filter((d) => d !== 'frontend/public/ranch').map((d) => `${d}/f.txt`), 'README.md', 'frontend/package.json', 'frontend/public/x.txt'];
  const r = computeSparsePaths(BASE, EX);
  assert.deepEqual(findUncoveredPaths(files, r, EX), []);
});

test('古い一覧は新しいディレクトリのファイルを未被覆にする', () => {
  const old = computeSparsePaths(BASE, EX);
  assert.deepEqual(findUncoveredPaths(['frontend/newdir/f.ts'], old, EX), ['frontend/newdir/f.ts']);
});

test('compareWithSettings: 未設定・一致・不足・余剰（順序は無視）', () => {
  const exp = ['a', 'b'];
  assert.equal(compareWithSettings({}, exp).status, 'unset');
  assert.equal(compareWithSettings({ worktree: {} }, exp).status, 'unset');
  assert.equal(compareWithSettings({ worktree: { sparsePaths: ['b', 'a'] } }, exp).status, 'ok');
  const m = compareWithSettings({ worktree: { sparsePaths: ['a', 'c'] } }, exp);
  assert.equal(m.status, 'mismatch');
  assert.deepEqual(m.missing, ['b']);
  assert.deepEqual(m.extra, ['c']);
  assert.equal(compareWithSettings({ worktree: { sparsePaths: 'a' } }, exp).status, 'invalid');
});

test('実際の除外正本が妥当である', () => {
  const j = JSON.parse(readFileSync(new URL('./worktree-sparse-exclusions.json', import.meta.url), 'utf8'));
  const paths = j.exclusions.map((e) => e.path);
  assert.equal(paths.length, 3);
  assert.doesNotThrow(() => validateExclusions(paths));
  for (const e of j.exclusions) assert.ok(e.reason);
});

// --- 差し戻し対応 ---
test('findUncoveredPaths: 除外の祖先直下は cone で展開されないので未被覆', () => {
  assert.deepEqual(findUncoveredPaths(['p/file', 'p/q/file'], ['z'], ['p/q/r']), ['p/file', 'p/q/file']);
  assert.deepEqual(findUncoveredPaths(['p/q/r/x.png', 'z/f', 'top'], ['z'], ['p/q/r']), []);
});

test('生成結果で取りこぼす構成（除外の祖先に他の子が無い）を未被覆として検出する', () => {
  const dirs = ['z', 'p', 'p/q', 'p/q/r'];
  const r = computeSparsePaths(dirs, ['p/q/r']);
  assert.deepEqual(r, ['z']);
  assert.deepEqual(findUncoveredPaths(['p/file', 'p/q/file', 'p/q/r/a'], r, ['p/q/r']), ['p/file', 'p/q/file']);
});

test('配下判定はパス要素で行い、接頭辞が同じ別名を誤認しない', () => {
  assert.deepEqual(findUncoveredPaths(['x/ranch-old/f'], ['z'], ['x/ranch']), ['x/ranch-old/f']);
  assert.deepEqual(findUncoveredPaths(['a/bc/f'], ['a/b'], []), ['a/bc/f']);
  assert.deepEqual(findUncoveredPaths(['a/b/f', 'a/f'], ['a/b'], []), []);
});

test('除外直下にファイルとサブディレクトリが混在しても配下は全て除外扱い', () => {
  assert.deepEqual(findUncoveredPaths(['e/f', 'e/s/g', 'e/s/t/h'], ['z'], ['e']), []);
});

test('大文字小文字違いは別物', () => {
  assert.deepEqual(computeSparsePaths(['A', 'a', 'a/Sub'], ['a/Sub']).sort(), ['A']);
  assert.deepEqual(findUncoveredPaths(['x/Ranch/f'], ['z'], ['x/ranch']), ['x/Ranch/f']);
});

test('空白・非 ASCII を含むパス', () => {
  const dirs = withAncestors(['日本 語/子', 'my dir/ranch', 'my dir/other']);
  const r = computeSparsePaths(dirs, ['my dir/ranch']);
  assert.deepEqual(r, ['my dir/other', '日本 語'].sort());
  assert.deepEqual(findUncoveredPaths(['日本 語/子/f', 'my dir/ranch/x'], r, ['my dir/ranch']), []);
});

test('compareWithSettings: 不正な設定は unset にしない', () => {
  const bad = [
    { worktree: null }, { worktree: [] }, { worktree: 'x' },
    { worktree: { sparsePath: ['z'] } }, { worktree: { sparse_paths: ['z'] } }, { worktree: { sparsepaths: ['z'] } },
    { worktree: { sparsePaths: [] } }, { worktree: { sparsePaths: 'a' } }, { worktree: { sparsePaths: ['a', 1] } },
    { worktree: { sparsePaths: ['a', 'a'] } }, { worktree: { sparsePaths: null } },
  ];
  for (const s of bad) assert.equal(compareWithSettings(s, ['a']).status, 'invalid', JSON.stringify(s));
  assert.equal(compareWithSettings({}, ['a']).status, 'unset');
  assert.equal(compareWithSettings({ worktree: { other: 1 } }, ['a']).status, 'unset');
  assert.ok(compareWithSettings({ worktree: null }, ['a']).problems.length > 0);
});
