#!/usr/bin/env node
// 台帳（戦役横断の課題正本）の共通読み取り（CMP-261124-1222 段A）。
//
// 台帳は2つの配置を取りうる。読む側はどちらでも同じ行集合（ID・行本文・所在ファイル）を得る。
// - 旧配置（single）: docs/task-list.md 1ファイルに前置きと7列表の全行がある。
// - 新配置（split）: docs/task-list.md は索引（前置きのみ・表の行なし）。行は月別ファイルに置く。
//     docs/task-list/YYYY-MM.md … ID CMP-YYMMDD-HHMM の YYMM の月の行
//     docs/task-list/legacy.md  … 旧形式 ID（CMP-003 等、月不明）の行
//   判定: docs/task-list/ に YYYY-MM.md か legacy.md が1つでもあれば新配置。
// - 変更申告（CMP-261124-1222 ④）: docs/task-list/changes/*.md。readLedger は既定で申告を適用した実効台帳を files に返す（下の「変更申告」節）。
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
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';

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

/**
 * 表の行が終端の | で閉じているか（行末の U+0020 以下を除いた最後の文字が | で、\| のエスケープでない）。
 * 終端の | が無い行は splitTableRow の要素数から列数を数えられない（"| … | 台帳 | EXTRA" が7列に見える）ため、
 * 列数の検査（月別ファイル・申告の両方）はこれを満たすことを先に要求する。
 */
export function closesTableRow(line) {
  let end = line.length;
  while (end > 0 && line.charCodeAt(end - 1) <= 0x20) end--;
  return end > 0 && line[end - 1] === '|' && !(end > 1 && line[end - 2] === '\\');
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
 * @param {{ requireRows?: boolean, raw?: boolean }} [options] requireRows（既定 true）: 索引の不在・行0件を LedgerReadError にする。
 *   構造検査のように不在・0件を自分で報告する呼び出し側だけが false にする。
 *   raw（既定 false）: files を申告適用前の生の月別ファイルで返す（申告の解析・problems は返す。applied は空）。
 * @returns {Promise<{ layout: 'single'|'split', files: {path: string, content: string|null}[], rawFiles: {path: string, content: string|null}[], rows: {id: string, line: string, file: string, lineNumber: number}[], missing: string[], changes: { entries: object[], applied: string[], problems: {kind: string, path: string, message: string}[] } }>}
 *   files は索引が先頭、続いて docs/task-list/ のファイルを名前順（新配置のときだけ）。既定では申告（docs/task-list/changes/）を
 *   base 連鎖で適用した仮想本文（実効台帳）で、申告が add で作る新しい月のファイルも含む。申告そのものは files に入れず changes に置く。
 *   申告が0件（changes/ が無い・空）なら files は生の本文と同じ。
 */
export async function readLedger(reader, { requireRows = true, raw = false } = {}) {
  if (!reader || typeof reader.listDir !== 'function' || typeof reader.readFile !== 'function') {
    throw new TypeError('readLedger: reader には listDir と readFile が要る');
  }
  const entries = await fetchOrThrow(LEDGER_DIR + '/ の一覧', () => reader.listDir(LEDGER_DIR));
  if (entries != null && !Array.isArray(entries)) throw new LedgerReadError(LEDGER_DIR + '/ の一覧が配列でない');
  const listed = (entries ?? []).map(String).sort(byName);
  const hasChanges = listed.includes(CHANGES_DIR_NAME);
  const names = listed.filter((n) => n !== CHANGES_DIR_NAME);
  const layout = names.some(isLedgerFileName) ? 'split' : 'single';
  const indexContent = await fetchOrThrow(LEDGER_INDEX_PATH, () => reader.readFile(LEDGER_INDEX_PATH));
  if (indexContent != null && typeof indexContent !== 'string') throw new LedgerReadError(LEDGER_INDEX_PATH + ' の本文が文字列でない');
  const rawFiles = [{ path: LEDGER_INDEX_PATH, content: indexContent ?? null }];
  if (layout === 'split') {
    for (const name of names) {
      const path = LEDGER_DIR + '/' + name;
      const content = await fetchOrThrow(path, () => reader.readFile(path));
      if (typeof content !== 'string') throw new LedgerReadError(path + ' は一覧にあるのに本文を読めない');
      rawFiles.push({ path, content });
    }
  }
  const read = hasChanges ? await readChanges(reader) : { entries: [], problems: [] };
  const effective = applyChanges(rawFiles, layout, read.entries);
  const files = raw ? rawFiles : effective.files;
  const rows = files.flatMap((f) => (f.content == null ? [] : extractRows(f.content, f.path)));
  const missing = rawFiles.filter((f) => f.content == null).map((f) => f.path);
  if (requireRows) {
    if (missing.length) throw new LedgerReadError(missing.join(', ') + ' が存在しない（台帳が無い状態を「行0件」とは扱わない）');
    if (rows.length === 0) throw new LedgerReadError('台帳（' + layout + ' 配置）から CMP 行を1件も読めなかった。0件は正常とみなさない');
  }
  const changes = { entries: read.entries, applied: raw ? [] : effective.applied, problems: [...read.problems, ...effective.problems] };
  return { layout, files, rawFiles, rows, missing, changes };
}

/** 文字列1つ（旧配置の docs/task-list.md の本文）を台帳として扱う。content が null なら索引不在。 */
export function singleFileLedger(content) {
  const text = typeof content === 'string' ? content : null;
  return {
    layout: 'single',
    files: [{ path: LEDGER_INDEX_PATH, content: text }],
    rows: text == null ? [] : extractRows(text, LEDGER_INDEX_PATH),
    missing: text == null ? [LEDGER_INDEX_PATH] : [],
    changes: { entries: [], applied: [], problems: [] },
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
      // stat と read の2回にせず1回で読む（申告が数百件あっても I/O を件数×1 に抑える）。不在・ディレクトリは null。
      try {
        return readFileSync(resolve(path), 'utf8');
      } catch (error) {
        if (error?.code === 'ENOENT' || error?.code === 'EISDIR' || error?.code === 'ENOTDIR') return null;
        throw error;
      }
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


// ---------------------------------------------------------------------------
// 変更申告（docs/task-list/changes/*.md。CMP-261124-1222 ④）
// ---------------------------------------------------------------------------
//
// 申告: docs/task-list/changes/<CMP-ID>--<UTC yyyymmddThhmmssZ>--<6桁乱数>.md。LF・2行固定・16KiB 以下。
//   1行目 <!-- ledger-change v1 op=add|update id=<CMP-ID> base=none|sha256:<64hex> -->
//   2行目 完全な7列行（月別ファイルに置いたときの形。証拠リンクは ../evidence/X）。delete は無い（行削除禁止を維持）。
// base = 実効台帳上の対象行文字列（CR 除去・UTF-8）の sha256。畳み込みで不変。
// 実効台帳 = 月別ファイル＋未畳み込み申告を「base 連鎖」で適用したもの（行ごとに 現在行のハッシュ = base の update を辿る）。
//   同じ base の update が2件以上 = fork、どの版とも繋がらない update = 孤児、既に実効台帳にある ID への add = 重複。
//   いずれも problems に入れ、その申告は適用しない（構造検査・Java 番人が赤にする）。
//   ファイル名の時刻・乱数は add の並び（月別ファイル末尾へ（時刻, 乱数）順）と同点決着にだけ使う。update の順序は連鎖が決める。
// CRLF の申告は CRLF を LF とみなして読む（base は CR 除去で計算するので LF の申告と同じ結果）。
// 月別ファイル内の行順は意味を持たない（読むのは AI と CI のみ）。比較は ID→行 の対応表（ledgerRowMap）で行う。
// Java 側の同じ実装: TaskListLedger.java（共有 fixture scripts/fixtures/task-list-ledger-cases.json の「申告:」case）。

export const CHANGES_DIR_NAME = 'changes';
export const CHANGES_DIR = LEDGER_DIR + '/' + CHANGES_DIR_NAME;
/** 申告1件の上限（バイト・UTF-8）。ちょうどは可。 */
export const MAX_CHANGE_BYTES = 16 * 1024;
/** 申告のファイル名。 */
export const CHANGE_FILE_NAME = new RegExp('^(' + CMP_ID_FORM + ')--(\\d{8}T\\d{6}Z)--(\\d{6})\\.md$');
const CHANGE_META = new RegExp('^<!-- ledger-change v1 op=(add|update) id=(' + CMP_ID_FORM + ') base=(none|sha256:[0-9a-f]{64}) -->$');
const CHANGE_NAME_RULE = '<CMP-ID>--<UTC yyyymmddThhmmssZ>--<6桁乱数>.md';

export const TABLE_HEADER = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |';
export const TABLE_SEPARATOR = '|----|------|------|------|---------|----------------|------|';

function fileHeading(path) {
  const name = path.slice(path.lastIndexOf('/') + 1);
  if (name === LEGACY_FILE_NAME) {
    return '# 台帳 旧形式 ID（CMP-003 等）の行\n\n旧形式 ID の行はここに置く（月が決まらないため）。行の追加・更新は node scripts/task-list-ledger.mjs add / update で申告（docs/task-list/changes/）を作り、本ファイルは直接編集しない（反映は台帳 bot の畳み込み）。規約は [索引](../task-list.md) を参照。\n';
  }
  const month = name.slice(0, -3);
  return '# 台帳 ' + month + '（ID が CMP-' + month.slice(2, 4) + month.slice(5, 7) + 'DD-HHMM の行）\n\n行の追加・更新は node scripts/task-list-ledger.mjs add / update で申告（docs/task-list/changes/）を作り、本ファイルは直接編集しない（反映は台帳 bot の畳み込み）。規約は [索引](../task-list.md) を参照。\n';
}

/** 月別ファイルの新規作成時の本文（見出し＋7列表ヘッダ）。申告の add が新しい月に入るときもこの雛形から作る。 */
export function newFileSkeleton(path) {
  return fileHeading(path) + '\n' + TABLE_HEADER + '\n' + TABLE_SEPARATOR + '\n';
}

/** 申告の base（対象行の CR を除いた UTF-8 の sha256）。 */
export function changeRowHash(line) {
  return 'sha256:' + createHash('sha256').update(String(line).replace(/\r/g, ''), 'utf8').digest('hex');
}

function problem(kind, path, message, paths) {
  return paths ? { kind, path, paths, message: path + ': 申告: ' + message } : { kind, path, message: path + ': 申告: ' + message };
}

/**
 * 申告1件を解析する（純粋関数）。valid が false なら errors に理由（kind: name / size / format / id）。
 * @returns {{ path: string, name: string, id: string|null, time: string|null, rand: string|null, op: string|null, base: string|null, line: string|null, content: string, valid: boolean, errors: {kind: string, path: string, message: string}[] }}
 */
export function parseChangeFile(path, content) {
  const name = path.slice(path.lastIndexOf('/') + 1);
  const m = CHANGE_FILE_NAME.exec(name);
  const errors = [];
  const entry = { path, name, id: m ? m[1] : null, time: m ? m[2] : null, rand: m ? m[3] : null, op: null, base: null, line: null, content, valid: false, errors };
  if (!m) {
    errors.push(problem('name', path, 'ファイル名が不正（名前は ' + CHANGE_NAME_RULE + ' に限る）。'));
    return entry;
  }
  const bytes = Buffer.byteLength(content, 'utf8');
  if (bytes > MAX_CHANGE_BYTES) {
    errors.push(problem('size', path, 'サイズが ' + bytes + ' バイトで上限 ' + MAX_CHANGE_BYTES + ' バイト（16KiB）を超える。'));
    return entry;
  }
  const text = content.replace(/\r\n/g, '\n');
  const lines = (text.endsWith('\n') ? text.slice(0, -1) : text).split('\n');
  if (text.includes('\r') || lines.length !== 2) {
    errors.push(problem('format', path, '形式: 2行（メタ行と表の行）でない（' + lines.length + ' 行）。'));
    return entry;
  }
  const meta = CHANGE_META.exec(lines[0]);
  if (!meta) {
    errors.push(problem('format', path, '形式: 1行目が <!-- ledger-change v1 op=add|update id=<CMP-ID> base=none|sha256:<64hex> --> でない。'));
    return entry;
  }
  const [, op, id, base] = meta;
  entry.op = op;
  entry.base = base;
  if (op === 'add' && base !== 'none') errors.push(problem('format', path, '形式: add の base は none に限る。'));
  if (op === 'update' && base === 'none') errors.push(problem('format', path, '形式: update の base は sha256:<64hex>（対象行のハッシュ）が要る。'));
  if (id !== m[1]) errors.push(problem('id', path, 'ID 不一致: ファイル名の ID ' + m[1] + ' とメタ行の ID ' + id + ' が違う。'));
  const line = lines[1];
  const cells = line.startsWith('|') ? splitTableRow(line) : [];
  if (cells.length < 3) {
    errors.push(problem('format', path, '形式: 2行目が表の行（| で始まる7列行）でない。'));
  } else if (!closesTableRow(line)) {
    errors.push(problem('format', path, '形式: 2行目の列数を数えられない（行末が | で閉じていない）。7列の各セルを | で囲むこと。'));
  } else if (cells.length - 2 !== 7) {
    // 最終の実効本文だけでなく申告ごとに7列を検査する（壊れた中間行を経由する連鎖を通さない）
    errors.push(problem('format', path, '形式: 2行目の列数が ' + (cells.length - 2) + '（期待 7 列）。セル内の | は \\| にエスケープすること。'));
  } else if (javaTrim(cells[1]) !== id) {
    errors.push(problem('id', path, 'ID 不一致: メタ行の ID ' + id + ' と行の先頭セル ' + javaTrim(cells[1]) + ' が違う。'));
  }
  entry.line = line;
  entry.valid = errors.length === 0;
  return entry;
}

/** docs/task-list/changes/ を読む。エントリの不正（.md 以外・サブディレクトリ・不正名・読めない）は problems。取得失敗は例外。 */
async function readChanges(reader) {
  const entries = [];
  const problems = [];
  const listed = await fetchOrThrow(CHANGES_DIR + '/ の一覧', () => reader.listDir(CHANGES_DIR));
  if (listed == null) {
    problems.push(problem('entry', CHANGES_DIR, CHANGES_DIR + ' がディレクトリでない。'));
    return { entries, problems };
  }
  if (!Array.isArray(listed)) throw new LedgerReadError(CHANGES_DIR + '/ の一覧が配列でない');
  for (const name of listed.map(String).sort(byName)) {
    const path = CHANGES_DIR + '/' + name;
    if (!name.endsWith('.md')) {
      problems.push(problem('entry', path, CHANGES_DIR + '/ に .md 以外のもの（サブディレクトリを含む）を置かない。'));
      continue;
    }
    if (!CHANGE_FILE_NAME.test(name)) {
      problems.push(problem('name', path, 'ファイル名が不正（名前は ' + CHANGE_NAME_RULE + ' に限る）。'));
      continue;
    }
    const content = await fetchOrThrow(path, () => reader.readFile(path));
    if (content == null) {
      problems.push(problem('entry', path, '本文を読めない（サブディレクトリ＝ネストは置かない）。'));
      continue;
    }
    if (typeof content !== 'string') throw new LedgerReadError(path + ' の本文が文字列でない');
    const entry = parseChangeFile(path, content);
    entries.push(entry);
    problems.push(...entry.errors);
  }
  return { entries, problems };
}

function changeOrder(a, b) {
  return byName(a.time, b.time) || byName(a.rand, b.rand) || byName(a.name, b.name);
}

function stripCr(line) {
  return line.endsWith('\r') ? line.slice(0, -1) : line;
}

/**
 * 申告を生の台帳ファイルへ base 連鎖で適用する（純粋関数）。
 * @param {{path: string, content: string|null}[]} rawFiles readLedger の生の files（索引が先頭）
 * @param {'single'|'split'} layout split なら add は ID の月ファイル（旧形式は legacy.md）、single なら索引の末尾
 * @param {ReturnType<typeof parseChangeFile>[]} entries 申告（valid でないものは無視する）
 * @returns {{ files: {path: string, content: string|null}[], applied: string[], problems: {kind: string, path: string, paths?: string[], message: string}[] }}
 */
export function applyChanges(rawFiles, layout, entries) {
  const valid = entries.filter((e) => e.valid);
  const docs = new Map();
  const loc = new Map();
  for (const f of rawFiles) {
    if (f.content == null) continue;
    const doc = { path: f.path, lines: f.content.split('\n'), appended: [], isNew: false };
    docs.set(f.path, doc);
    for (const r of extractRows(f.content, f.path)) {
      if (!loc.has(r.id)) loc.set(r.id, { doc, appended: false, idx: r.lineNumber - 1, cr: doc.lines[r.lineNumber - 1].endsWith('\r'), source: f.path });
    }
  }
  const get = (l) => (l.appended ? l.doc.appended[l.idx] : stripCr(l.doc.lines[l.idx]));
  const set = (l, v) => {
    if (l.appended) l.doc.appended[l.idx] = v;
    else l.doc.lines[l.idx] = v + (l.cr ? '\r' : '');
  };
  const problems = [];
  const applied = new Set();
  for (const e of valid.filter((x) => x.op === 'add').sort(changeOrder)) {
    const existing = loc.get(e.id);
    if (existing) {
      // paths に既存側（先の add 申告または月別ファイル）も入れる。仮想マージでどちらが PR 側でも競合を拾うため
      problems.push(problem('duplicate', e.path, '重複: 既に実効台帳にある ID ' + e.id + ' への add（' + existing.source + '）。新しい ID を採るか update にすること。', [e.path, existing.source]));
      continue;
    }
    const target = layout === 'split' ? expectedLedgerFile(e.id) : LEDGER_INDEX_PATH;
    let doc = docs.get(target);
    if (!doc) {
      doc = { path: target, lines: (layout === 'split' ? newFileSkeleton(target) : '').split('\n'), appended: [], isNew: true };
      docs.set(target, doc);
    }
    doc.appended.push(e.line);
    loc.set(e.id, { doc, appended: true, idx: doc.appended.length - 1, cr: false, source: e.path });
    applied.add(e.path);
  }
  const updatesById = new Map();
  for (const e of valid.filter((x) => x.op === 'update')) {
    if (!updatesById.has(e.id)) updatesById.set(e.id, []);
    updatesById.get(e.id).push(e);
  }
  for (const id of [...updatesById.keys()].sort(byName)) {
    const list = updatesById.get(id).sort(changeOrder);
    const byBase = new Map();
    for (const u of list) {
      if (!byBase.has(u.base)) byBase.set(u.base, []);
      byBase.get(u.base).push(u);
    }
    const forked = new Set();
    for (const [base, group] of byBase) {
      if (group.length < 2) continue;
      forked.add(base);
      const paths = group.map((g) => g.path);
      problems.push(problem('fork', paths[0], 'fork（分岐）: ' + id + ' に同じ base の update が ' + group.length + ' 件ある（' + paths.join(', ') + '）。一本の連鎖（前の申告の行を base に）に直すこと。', paths));
    }
    const l = loc.get(id);
    const used = new Set();
    if (l) {
      for (;;) {
        const h = changeRowHash(get(l));
        const group = byBase.get(h);
        if (!group || forked.has(h) || used.has(group[0])) break;
        set(l, group[0].line);
        used.add(group[0]);
        applied.add(group[0].path);
      }
    }
    for (const u of list) {
      if (used.has(u) || forked.has(u.base)) continue;
      problems.push(problem('orphan', u.path, '孤児: ' + id + ' の update の base が実効台帳のどの版とも繋がらない' + (l ? '' : '（対象の行が無い）') + '。最新の実効行から base を計算し直すこと。'));
    }
  }
  const render = (doc) => {
    const text = doc.lines.join('\n');
    if (!doc.appended.length) return text;
    return (text === '' || text.endsWith('\n') ? text : text + '\n') + doc.appended.map((x) => x + '\n').join('');
  };
  const files = rawFiles.map((f) => (f.content == null ? f : { path: f.path, content: render(docs.get(f.path)) }));
  for (const doc of docs.values()) if (doc.isNew) files.push({ path: doc.path, content: render(doc) });
  files.sort((a, b) => (a.path === LEDGER_INDEX_PATH ? -1 : b.path === LEDGER_INDEX_PATH ? 1 : byName(a.path, b.path)));
  return { files, applied: [...applied].sort(byName), problems };
}

/** 実効台帳の ID→行 の対応表（順序を問わない比較用。同じ ID が複数あれば最初の行）。 */
export function ledgerRowMap(input) {
  const ledger = asLedger(input);
  const map = new Map();
  for (const r of ledger.rows) if (!map.has(r.id)) map.set(r.id, r.line);
  return map;
}

// CLI（migrate / render / port-diff / effective）。本体は task-list-ledger-cli.mjs（循環 import を避けて起動時にだけ読む）。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  // 評価完了後に読む（top-level await にすると本体との循環 import で止まる）
  import('./task-list-ledger-cli.mjs').then(({ main }) => main(process.argv.slice(2))).then((code) => { process.exitCode = code; });
}
