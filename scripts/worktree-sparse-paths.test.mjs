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
  assert.deepEqual(findUncoveredPaths(['p/q/r/img.png', 'p/file'], ['z'], ['p/q/r']), []);
  assert.deepEqual(findUncoveredPaths(['p/other/f'], ['z'], ['p/q/r']), ['p/other/f']);
});

test('生成結果は追跡ファイルを全て被覆する', () => {
  const files = [...BASE.map((d) => `${d}/f.txt`), 'README.md', 'frontend/package.json', 'frontend/public/x.txt'];
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
  assert.equal(compareWithSettings({ worktree: { sparsePaths: 'a' } }, exp).status, 'mismatch');
});

test('実際の除外正本が妥当である', () => {
  const j = JSON.parse(readFileSync(new URL('./worktree-sparse-exclusions.json', import.meta.url), 'utf8'));
  const paths = j.exclusions.map((e) => e.path);
  assert.equal(paths.length, 3);
  assert.doesNotThrow(() => validateExclusions(paths));
  for (const e of j.exclusions) assert.ok(e.reason);
});
