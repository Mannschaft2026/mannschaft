#!/usr/bin/env node
// 台帳（戦役横断の課題正本）の共通読み取り（CMP-261124-1222 段A）。
//
// 台帳は2つの配置を取りうる。読む側はどちらでも同じ行集合（ID・行本文・所在ファイル）を得る。
// - 旧配置（single）: docs/task-list.md 1ファイルに前置きと7列表の全行がある。
// - 新配置（split）: docs/task-list.md は索引（前置きのみ・表の行なし）。行は月別ファイルに置く。
//     docs/task-list/YYYY-MM.md … ID CMP-YYMMDD-HHMM の YYMM の月の行
//     docs/task-list/legacy.md  … 旧形式 ID（CMP-003 等、月不明）の行
//   判定: docs/task-list/ に YYYY-MM.md か legacy.md が1つでもあれば新配置。
//
// ファイルの読み取りは注入する（reader = { listDir(dir), readFile(path) }。どちらも同期・非同期のどちらで返してもよい）。
// - listDir(dir): ディレクトリ内の名前の配列。ディレクトリが無ければ null。
// - readFile(path): 本文の文字列。ファイルが無ければ null。
// - 取得そのものの失敗（API エラー・git の失敗等）は例外を投げること。null（不在）と混同しない。
// 作業木（fsLedgerReader）・git の tree（gitLedgerReader）・GitHub Contents API（workflow 側で組み立てる）のどれからでも使う。
// Java 側の同じ実装: backend/src/test/java/com/mannschaft/app/common/architecture/TaskListLedger.java
// 共有 fixture: scripts/fixtures/task-list-ledger-cases.json
// 依存パッケージなし（GitHub ランナー既定の Node で動く）。

import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';

export const LEDGER_INDEX_PATH = 'docs/task-list.md';
export const LEDGER_DIR = 'docs/task-list';
export const LEGACY_FILE_NAME = 'legacy.md';
/** 月別ファイル名（YYYY-MM.md、月は 01〜12）。 */
export const MONTH_FILE_NAME = /^\d{4}-(?:0[1-9]|1[0-2])\.md$/;
/** CMP-ID の形。日時形式を先に置く（CMP-\d+ を先にすると CMP-260819-2131 から CMP-260819 だけを拾う）。 */
export const CMP_ID_FORM = 'CMP-\\d{6}-\\d{4}|CMP-\\d+';
const CMP_ID_EXACT = new RegExp('^(?:' + CMP_ID_FORM + ')$');
const NEW_FORM_ID = /^CMP-(\d{2})(\d{2})\d{2}-\d{4}$/;
const OLD_FORM_ID = /^CMP-\d+$/;

/** 取得失敗・0件など「台帳を読めた」と言えない状態。 */
export class LedgerReadError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'LedgerReadError';
  }
}

/** Java の String.trim()（U+0020 以下の文字だけを両端から除く）。JS の trim() は全角空白も除くため使わない。 */
export function javaTrim(s) {
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) start++;
  while (end > start && s.charCodeAt(end - 1) <= 0x20) end--;
  return s.slice(start, end);
}

/**
 * GFM の表の1行をセル分割する（Java: splitTableRow）。
 * \| だけを区切りにしない。コードスパン内の生の | も区切りとして数える（GFM の描画と同じ）。
 */
export function splitTableRow(line) {
  const result = [];
  let buf = '';
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '\\' && line[i + 1] === '|') {
      buf += '|';
      i++;
      continue;
    }
    if (c === '|') {
      result.push(buf);
      buf = '';
      continue;
    }
    buf += c;
  }
  result.push(buf);
  return result;
}

/** docs/task-list/ 直下のファイル名として正しいか（YYYY-MM.md か legacy.md）。 */
export function isLedgerFileName(name) {
  return name === LEGACY_FILE_NAME || MONTH_FILE_NAME.test(name);
}

/** CMP-ID が新配置で置かれるべきファイル（リポジトリ相対）。形が CMP-ID でなければ null。 */
export function expectedLedgerFile(id) {
  const m = NEW_FORM_ID.exec(id);
  if (m) return LEDGER_DIR + '/20' + m[1] + '-' + m[2] + '.md';
  if (OLD_FORM_ID.test(id)) return LEDGER_DIR + '/' + LEGACY_FILE_NAME;
  return null;
}

/** 1ファイルの本文から、先頭セルが CMP-ID そのものである表の行を取り出す（行本文は行末の CR を除く）。 */
export function extractRows(content, file) {
  const rows = [];
  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].endsWith('\r') ? lines[i].slice(0, -1) : lines[i];
    if (!line.startsWith('|')) continue;
    const cells = splitTableRow(line);
    if (cells.length < 2) continue;
    const id = javaTrim(cells[1]);
    if (!CMP_ID_EXACT.test(id)) continue;
    rows.push({ id, line, file, lineNumber: i + 1 });
  }
  return rows;
}

function byName(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

async function fetchOrThrow(what, fn) {
  try {
    return await fn();
  } catch (error) {
    throw new LedgerReadError(what + ' の取得に失敗した（不在とは扱わない）: ' + (error?.message ?? error), { cause: error });
  }
}

/**
 * 台帳を読む。
 * @param {{ listDir(dir: string): (string[]|null|Promise<string[]|null>), readFile(path: string): (string|null|Promise<string|null>) }} reader
 * @param {{ requireRows?: boolean }} [options] requireRows（既定 true）: 索引の不在・行0件を LedgerReadError にする。
 *   構造検査のように不在・0件を自分で報告する呼び出し側だけが false にする。
 * @returns {Promise<{ layout: 'single'|'split', files: {path: string, content: string|null}[], rows: {id: string, line: string, file: string, lineNumber: number}[], missing: string[] }>}
 *   files は索引が先頭、続いて docs/task-list/ のファイルを名前順（新配置のときだけ）。
 */
export async function readLedger(reader, { requireRows = true } = {}) {
  if (!reader || typeof reader.listDir !== 'function' || typeof reader.readFile !== 'function') {
    throw new TypeError('readLedger: reader には listDir と readFile が要る');
  }
  const entries = await fetchOrThrow(LEDGER_DIR + '/ の一覧', () => reader.listDir(LEDGER_DIR));
  if (entries != null && !Array.isArray(entries)) throw new LedgerReadError(LEDGER_DIR + '/ の一覧が配列でない');
  const names = (entries ?? []).map(String).sort(byName);
  const layout = names.some(isLedgerFileName) ? 'split' : 'single';
  const indexContent = await fetchOrThrow(LEDGER_INDEX_PATH, () => reader.readFile(LEDGER_INDEX_PATH));
  if (indexContent != null && typeof indexContent !== 'string') throw new LedgerReadError(LEDGER_INDEX_PATH + ' の本文が文字列でない');
  const files = [{ path: LEDGER_INDEX_PATH, content: indexContent ?? null }];
  if (layout === 'split') {
    for (const name of names) {
      const path = LEDGER_DIR + '/' + name;
      const content = await fetchOrThrow(path, () => reader.readFile(path));
      if (typeof content !== 'string') throw new LedgerReadError(path + ' は一覧にあるのに本文を読めない');
      files.push({ path, content });
    }
  }
  const rows = files.flatMap((f) => (f.content == null ? [] : extractRows(f.content, f.path)));
  const missing = files.filter((f) => f.content == null).map((f) => f.path);
  if (requireRows) {
    if (missing.length) throw new LedgerReadError(missing.join(', ') + ' が存在しない（台帳が無い状態を「行0件」とは扱わない）');
    if (rows.length === 0) throw new LedgerReadError('台帳（' + layout + ' 配置）から CMP 行を1件も読めなかった。0件は正常とみなさない');
  }
  return { layout, files, rows, missing };
}

/** 文字列1つ（旧配置の docs/task-list.md の本文）を台帳として扱う。content が null なら索引不在。 */
export function singleFileLedger(content) {
  const text = typeof content === 'string' ? content : null;
  return {
    layout: 'single',
    files: [{ path: LEDGER_INDEX_PATH, content: text }],
    rows: text == null ? [] : extractRows(text, LEDGER_INDEX_PATH),
    missing: text == null ? [LEDGER_INDEX_PATH] : [],
  };
}

/** 文字列または readLedger の結果を台帳として受け取る。 */
export function asLedger(input) {
  if (input && typeof input === 'object' && Array.isArray(input.files)) return input;
  return singleFileLedger(input);
}

/** 台帳の全ファイルの本文を所定の順（索引→月別・legacy の名前順）に改行で連結する。行単位で読む消費側の互換用。 */
export function ledgerText(input) {
  const ledger = asLedger(input);
  return ledger.files.filter((f) => f.content != null).map((f) => (f.content.endsWith('\n') ? f.content : f.content + '\n')).join('');
}

/** 作業木の reader。root はリポジトリルート。 */
export function fsLedgerReader(root) {
  return fsReaderFor((p) => join(root, ...p.split('/')));
}

/**
 * 索引ファイルのパスを直接与える reader（CLI 用）。月別ファイルは索引と同じディレクトリの task-list/ から読む
 * （例: .merge-group-head/docs/task-list.md → .merge-group-head/docs/task-list/2026-11.md）。
 */
export function fsLedgerReaderForIndex(indexPath) {
  const base = dirname(indexPath);
  return fsReaderFor((p) => {
    if (p === LEDGER_INDEX_PATH) return indexPath;
    if (p === LEDGER_DIR) return join(base, 'task-list');
    if (p.startsWith(LEDGER_DIR + '/')) return join(base, 'task-list', ...p.slice(LEDGER_DIR.length + 1).split('/'));
    throw new Error('台帳の外のパスは読まない: ' + p);
  });
}

function fsReaderFor(resolve) {
  return {
    listDir(dir) {
      const full = resolve(dir);
      const stat = statSync(full, { throwIfNoEntry: false });
      if (!stat || !stat.isDirectory()) return null;
      return readdirSync(full);
    },
    readFile(path) {
      const full = resolve(path);
      const stat = statSync(full, { throwIfNoEntry: false });
      if (!stat || !stat.isFile()) return null;
      return readFileSync(full, 'utf8');
    },
  };
}

/** git の commit / tree（ref）の reader。ref が解決できなければ作成時に例外。maxBuffer を超える本文は取得失敗（ENOBUFS）とする。 */
export function gitLedgerReader(ref, { cwd, maxBuffer = 64 * 1024 * 1024 } = {}) {
  const git = (args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer });
  const tree = git(['rev-parse', '--verify', '--quiet', ref + '^{tree}']).trim();
  if (!tree) throw new LedgerReadError('git の ref を解決できない: ' + ref);
  const lsTree = (path) => git(['ls-tree', '-z', tree, '--', path]).split('\0').filter(Boolean)
    .map((entry) => { const tab = entry.indexOf('\t'); const [, type] = entry.slice(0, tab).split(' '); return { type, path: entry.slice(tab + 1) }; });
  return {
    listDir(dir) {
      const self = lsTree(dir);
      if (!self.length || self[0].type !== 'tree') return null;
      return lsTree(dir + '/').map((e) => e.path.slice(dir.length + 1));
    },
    readFile(path) {
      const entry = lsTree(path);
      if (!entry.length || entry[0].type !== 'blob') return null;
      return git(['cat-file', 'blob', tree + ':' + path]);
    },
  };
}
