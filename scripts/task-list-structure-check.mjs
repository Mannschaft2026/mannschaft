#!/usr/bin/env node
// 台帳 docs/task-list.md（と新配置の docs/task-list/*.md）の構造検査器（CMP-261121-1114 / 両配置対応 CMP-261124-1222）。
//
// Task-List PR Gate の merge queue（merge_group）経路で、合成コミットの台帳を検査するために使う。
// 判定規則は Java 番人 backend/src/test/java/com/mannschaft/app/common/architecture/TaskListCmpIdDuplicateGuardTest.java
// の3テスト（CMP-ID 重複＋走査経路の自己検証 / 列数7 / 未完行の優先度 P1〜P5）を移植したもの。
// 規則を変えるときは Java 番人（と共有ヘルパ TaskListLedger.java）と本スクリプト、共有 fixture
// scripts/fixtures/task-list-ledger-cases.json を同時に直すこと。
//
// 両配置（旧: docs/task-list.md 1ファイル / 新: 索引＋docs/task-list/YYYY-MM.md・legacy.md）は scripts/task-list-ledger.mjs の
// readLedger で読み、全ファイルを横断して検査する。新配置のときだけ配置違反（月違い・legacy 違い・不正なファイル名・
// 索引の CMP 行）も赤にする。規則と試験データは Java 番人と共有する（scripts/fixtures/task-list-ledger-cases.json）。
//
// 使い方: node scripts/task-list-structure-check.mjs <task-list.md のパス> [--base <ref|dir>] [--merge-base <ref|dir>]
//   月別ファイル・申告（task-list/changes/）は索引と同じディレクトリの task-list/ から読む。
//   --base を付けると仮想 head の等式・連鎖検査（checkVirtualHead）も行う。申告の改変・削除・連鎖違反と月別ファイルの直接編集（段3 で施行）は exit 1。
//   --allow-direct-edit は互換期間（段1c〜段3）の挙動で、直接編集を警告（exit 0）に留める。
// 違反・ファイル不在・空・走査不能はすべて理由を出力して exit 1（例外の stack で落とさない）。
// 依存パッケージなし（GitHub ランナー既定の Node で動く）。

import { pathToFileURL } from 'node:url';
import { statSync } from 'node:fs';
import {
  CMP_ID_FORM,
  LEDGER_DIR,
  LEDGER_INDEX_PATH,
  LEGACY_FILE_NAME,
  applyChanges,
  asLedger,
  closesTableRow,
  expectedLedgerFile,
  extractRows,
  fsLedgerReader,
  fsLedgerReaderForIndex,
  gitLedgerReader,
  isLedgerFileName,
  javaTrim,
  newFileSkeleton,
  readLedger,
  splitTableRow,
} from './task-list-ledger.mjs';

export { CMP_ID_FORM, splitTableRow };

/** Java の \s（ASCII 空白のみ）。JS の \s は全角空白等も含み判定が Java 番人とずれるため明示する。 */
const JAVA_SPACE = '[ \\t\\n\\x0B\\f\\r]';
/**
 * Java の (?m)^ と同じ「行頭」。Java は LF・CRLF・CR・U+0085(NEL)・U+2028・U+2029 を行終端とし、
 * CR と LF の間は行頭にしない。JS の m フラグは NEL を行終端にしないため、後読みで明示する。
 */
const JAVA_LINE_START = '(?:^|(?<=[\\n\\x85\\u{2028}\\u{2029}])|(?<=\\r)(?!\\n))';
/** Java の .（行終端以外の1文字）。 */
const JAVA_DOT = '[^\\n\\r\\x85\\u{2028}\\u{2029}]';
/** 表の行頭に現れる CMP-ID（Java: CMP_ID_ROW）。 */
const CMP_ID_ROW = new RegExp(JAVA_LINE_START + '\\|' + JAVA_SPACE + '*(' + CMP_ID_FORM + ')' + JAVA_SPACE + '*\\|', 'gu');
/** 走査経路の自己検証用の緩い計数（Java: CMP_ID_ROW_LOOSE）。 */
const CMP_ID_ROW_LOOSE = new RegExp(JAVA_LINE_START + '\\|' + JAVA_DOT + '*?(' + CMP_ID_FORM + ')', 'gu');
const CMP_ID_PREFIX = new RegExp('^(' + CMP_ID_FORM + ')');
const CMP_ID_EXACT = new RegExp('^(' + CMP_ID_FORM + ')$');
/** 1行の列数（ID / 戦役 / 状態 / 依存 / 完了条件 / 証拠(PR/テスト) / 台帳）。 */
export const EXPECTED_COLUMN_COUNT = 7;
/** 優先度または完了系の語（前方一致。Java: STATUS_PRIORITY_OR_DONE）。 */
const STATUS_PRIORITY_OR_DONE = /^(P[1-5] |完了|取り下げ|取下げ|取下|重複|不要|対応不要|検証済み|対応済み|合格)/;

/**
 * 1ファイルの本文を解析する（判定は Java 番人と同じ）。
 * 重複・件数照合は Java の (?m)^ と同じ行終端（LF・CRLF・CR・NEL・U+2028・U+2029）で、
 * 列数・優先度は Java と同じく LF だけで行を分ける（CRLF の CR は末尾の空セルに入るだけで判定に影響しない）。
 */
function analyzeContent(text) {
  const occurrences = new Map();
  for (const match of text.matchAll(CMP_ID_ROW)) occurrences.set(match[1], (occurrences.get(match[1]) || 0) + 1);
  const strictCount = [...occurrences.values()].reduce((sum, n) => sum + n, 0);
  const looseCount = [...text.matchAll(CMP_ID_ROW_LOOSE)].length;
  const columnErrors = [];
  const priorityErrors = [];
  const ids = [];
  let rows = 0;
  for (const line of text.split('\n')) {
    if (!line.startsWith('|')) continue;
    const cells = splitTableRow(line);
    if (cells.length < 2) continue;
    const firstCell = javaTrim(cells[1]);
    if (!CMP_ID_PREFIX.test(firstCell)) continue;
    rows++;
    const columns = cells.length - 2;
    if (!closesTableRow(line)) {
      columnErrors.push('列数: ' + firstCell + ' の行が | で閉じていない（終端の | が無い行は列数を数えられない。7列の各セルを | で囲むこと）。');
    } else if (columns !== EXPECTED_COLUMN_COUNT) {
      columnErrors.push('列数: ' + firstCell + ' の行は ' + columns + ' 列（期待 ' + EXPECTED_COLUMN_COUNT + ' 列）。セル内の | は \\| にエスケープすること（コードスパン内でも）。');
    }
    if (cells.length < 4 || !CMP_ID_EXACT.test(firstCell)) continue;
    ids.push(firstCell);
    const status = javaTrim(cells[3].replace(/\*/g, ''));
    if (!STATUS_PRIORITY_OR_DONE.test(status)) {
      const head = status.length > 30 ? status.slice(0, 30) + '…' : status;
      priorityErrors.push('優先度: ' + firstCell + ' の状態セル "' + head + '" が P1〜P5（後ろに空白）または完了系の語で始まらない。未完の行は先頭に優先度を書くこと。');
    }
  }
  return { occurrences, strictCount, looseCount, columnErrors, priorityErrors, ids, rows };
}

/**
 * 台帳を検査する（両配置・全ファイル横断）。ledger は readLedger の結果、または旧配置の docs/task-list.md の本文（文字列）。
 * 文字列が null/undefined ならファイル不在として扱う。違反はファイル名を前置きして返す。
 * @returns {{ ok: boolean, errors: string[], stats: { rows: number, looseRows: number, layout: string, files: number } }}
 */
export function checkTaskListStructure(input) {
  const ledger = asLedger(input);
  const errors = [];
  const stats = { rows: 0, looseRows: 0, layout: ledger.layout, files: 0 };
  const index = ledger.files.find((f) => f.path === LEDGER_INDEX_PATH);
  if (!index || typeof index.content !== 'string') {
    errors.push(LEDGER_INDEX_PATH + ' が存在しない（読めない）。台帳が無い状態を「問題なし」とは判定しない。');
    return { ok: false, errors, stats };
  }
  if (index.content.trim() === '') {
    errors.push(LEDGER_INDEX_PATH + ' が空である。台帳が空の状態を「問題なし」とは判定しない。');
    return { ok: false, errors, stats };
  }
  const split = ledger.layout === 'split';
  const files = split ? ledger.files : [index];
  stats.files = files.length;
  const occurrences = new Map();
  let looseTotal = 0;
  for (const file of files) {
    if (typeof file.content !== 'string') {
      errors.push(file.path + ': 本文を読めない。');
      continue;
    }
    const a = analyzeContent(file.content);
    looseTotal += a.looseCount;
    stats.rows += a.rows;
    if (a.strictCount !== a.looseCount) {
      errors.push(file.path + ': CMP-ID を含む表の行は ' + a.looseCount + ' 件あるが、先頭セルの CMP-ID として厳密に抽出できたのは ' + a.strictCount
        + ' 件で件数が一致しない。先頭セルが壊れた行、または先頭セル以外にだけ CMP-ID を持つ表の行がある（取りこぼした行の重複を見逃すため fail とする）。');
    }
    for (const [id, count] of a.occurrences) {
      const entry = occurrences.get(id) ?? { count: 0, files: [] };
      entry.count += count;
      if (!entry.files.includes(file.path)) entry.files.push(file.path);
      occurrences.set(id, entry);
    }
    for (const message of [...a.columnErrors, ...a.priorityErrors]) errors.push(file.path + ': ' + message);
    if (!split) continue;
    // 配置違反（新配置のときだけ）
    if (file.path === LEDGER_INDEX_PATH) {
      for (const id of a.ids) errors.push(file.path + ': 配置: 新配置では索引に CMP 行を置かない（' + id + ' は ' + expectedLedgerFile(id) + ' へ）。');
      continue;
    }
    const name = file.path.slice(file.path.lastIndexOf('/') + 1);
    if (!isLedgerFileName(name)) {
      errors.push(file.path + ': 配置: docs/task-list/ のファイル名は YYYY-MM.md（月は 01〜12）か ' + LEGACY_FILE_NAME + ' に限る。');
    }
    for (const id of a.ids) {
      const expected = expectedLedgerFile(id);
      if (expected === file.path) continue;
      if (name === LEGACY_FILE_NAME) errors.push(file.path + ': 配置: ' + LEGACY_FILE_NAME + ' に新形式 ID ' + id + ' がある（' + expected + ' へ置くこと）。');
      else errors.push(file.path + ': 配置: ' + id + ' は ' + expected + ' に置くこと（自分の月・旧形式は ' + LEGACY_FILE_NAME + '）。');
    }
  }
  stats.looseRows = looseTotal;
  if (looseTotal === 0) {
    errors.push('CMP-ID を含む表の行を1件も見つけられなかった（' + ledger.layout + ' 配置・' + files.length + ' ファイル）。走査経路か台帳の表形式が壊れており、「重複なし」の判定自体が信用できない。');
  }
  for (const [id, { count, files: where }] of occurrences) {
    if (count > 1) errors.push('CMP-ID の重複: ' + id + ' が ' + count + ' 回出現する（' + where.join(', ') + '）。後から main へ入った側が採番し直すこと。');
  }
  if (stats.rows === 0 && looseTotal > 0) {
    errors.push('先頭セルに CMP-ID を持つ表の行を1件も見つけられなかった。走査経路（splitTableRow / CMP_ID_FORM）が壊れている可能性がある。');
  }
  // 変更申告（docs/task-list/changes/）の形式・名前・サイズ・ID 一致・連鎖（fork・孤児・重複）・台帳外のもの（CMP-261124-1222 ④）。
  // 上の検査は申告を適用した実効台帳（仮想の月別ファイル）に対して行っている。
  stats.changes = ledger.changes?.entries?.length ?? 0;
  for (const p of ledger.changes?.problems ?? []) errors.push(p.message);
  return { ok: errors.length === 0, errors, stats };
}

/**
 * 月別ファイルを直接編集した PR への案内（段3 の施行後、直接編集は赤。CMP-261124-1222 ④ P2-4）。
 * PR ゲート・merge_group・自動修復のコメントで同じ文言を使う。
 */
export const DIRECT_EDIT_GUIDANCE = '行の追加は `node scripts/task-list-ledger.mjs add "<7列行>"`、更新は `node scripts/task-list-ledger.mjs update <CMP-ID> --set <列名>=<値>` で申告（docs/task-list/changes/）を作ること（月別ファイルへの反映は台帳 bot の畳み込みが行う）。'
  + '月別ファイル・索引を直接編集した旧手順の PR は、台帳の自動修復（task-list-conflict-autofix）が申告へ変換して main の月別ファイルに戻す（変換できない差分は PR にコメントする）。';

/** 名前の別名（全ファイル横断であることを呼び出し側で明示したいとき）。 */
export const checkLedger = checkTaskListStructure;


// ---------------------------------------------------------------------------
// 仮想 head の等式検査（CMP-261124-1222 ④ P1-1）
// ---------------------------------------------------------------------------
//
// mb = 分岐点（merge-base(base, head)）、base = PR の base、head = PR head。merge_group は実マージなので mb = base（base_sha と group head）。
// PR 自身の差分: 申告の追加 D+ = changes(head) \ changes(mb)、申告の削除 D- = changes(mb) \ changes(head)、月別の差分 month(mb)→month(head)。
// - 等式 E(month(mb), changes(mb) ∪ D+) == E(month(head), changes(head))（ID→行 の対応表で比べ、行順は問わない。P1-2）。
//   D- の申告は「月別ファイルへ畳み込まれた」ときだけ消してよい。畳み込みが正しければ左辺（消した申告も適用）と右辺（月別に反映済み）が一致する。
//   （正本の記述は「changes(mb)∪D+ ∖D-」だが、∖D- を左辺から除くと正しい畳み込みが常に不一致になるため、D- は左辺に残して評価する。）
// - 結果種別: 違反（violations）= 申告の改変（change-modified）・畳み込みでない申告の削除（change-deleted）・PR が足した申告の連鎖違反
//   （fork / orphan / duplicate / 形式）・月別ファイルの直接編集（direct-edit。段3 で施行。enforceDirectEdit: false＝互換期間 P2-4 の
//   挙動では警告 warnings に回す）。等式が成り立ち申告を消している PR は fold（畳み込み）と分類する（作者が bot かは PR ゲート側で見る）。
// - 連鎖は仮想マージ month(base)＋changes(base) ∪ D+ で判定し、D+ に関わる問題だけを違反にする（main の前進だけでは古い PR を赤にしない）。
// - 月別ファイルの行以外（見出し・前置き・表ヘッダ）は変えてはならない。新しい月のファイルは雛形（newFileSkeleton）と同じでなければ direct-edit。

function changeMapOf(ledger) {
  return new Map((ledger.changes?.entries ?? []).map((e) => [e.path, e]));
}

function rowMapOfFiles(files) {
  const map = new Map();
  for (const f of files) {
    if (f.content == null) continue;
    for (const r of extractRows(f.content, f.path)) if (!map.has(r.id)) map.set(r.id, r.line);
  }
  return map;
}

/** ID→行 の対応表の差（a と b で行が違う・片方にしかない ID）。 */
export function diffRowMaps(a, b) {
  const ids = new Set([...a.keys(), ...b.keys()]);
  return [...ids].filter((id) => a.get(id) !== b.get(id)).sort();
}

function monthFiles(ledger) {
  return new Map((ledger.rawFiles ?? ledger.files).filter((f) => f.path.startsWith(LEDGER_DIR + '/') && f.content != null).map((f) => [f.path, f.content]));
}

function nonRowLines(content, path) {
  const rowLines = new Set(extractRows(content, path).map((r) => r.lineNumber));
  const lines = content.split('\n').map((l) => (l.endsWith('\r') ? l.slice(0, -1) : l)).filter((_, i) => !rowLines.has(i + 1));
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  return lines;
}

/**
 * 仮想 head の等式・連鎖を検査する。引数はいずれも readLedger の結果（既定・raw どちらでもよい。rawFiles と changes を使う）。
 * enforceDirectEdit（既定 true＝段3 の施行後）: 月別ファイルの直接編集を違反にする。false は互換期間（段1c〜段3）の挙動で警告のみ。
 *   分岐点・base・head のどれも旧単一ファイル配置のとき（新配置の main と無関係な台帳）は施行しない。
 * @returns {{ ok: boolean, violations: {kind: string, path?: string, message: string}[], warnings: {kind: string, path?: string, message: string}[], fold: boolean, addedChanges: string[], removedChanges: string[], modifiedChanges: string[] }}
 */
export function checkVirtualHead({ mergeBase, base = mergeBase, head, enforceDirectEdit = true }) {
  const mbC = changeMapOf(mergeBase);
  const baseC = changeMapOf(base);
  const headC = changeMapOf(head);
  const violations = [];
  const warnings = [];
  // 旧単一ファイル配置どうし（月別ファイルも申告の置き場も無い台帳）では直接編集が唯一の手段なので施行しない（main は新配置）。
  const enforce = enforceDirectEdit && [mergeBase, base, head].some((l) => l.layout === 'split');
  const directEdit = (message) => (enforce
    ? violations.push({ kind: 'direct-edit', message: message + DIRECT_EDIT_GUIDANCE })
    : warnings.push({ kind: 'direct-edit', message: message + '（互換期間のため警告のみ）' }));
  const added = [...headC.keys()].filter((p) => !mbC.has(p)).sort();
  const removed = [...mbC.keys()].filter((p) => !headC.has(p)).sort();
  const modified = [...headC.keys()].filter((p) => mbC.has(p) && mbC.get(p).content !== headC.get(p).content).sort();
  for (const p of modified) {
    violations.push({ kind: 'change-modified', path: p, message: p + ': 申告の改変（change-modified）: 申告は追加のみ。行を変えるなら新しい update 申告を足すこと。' });
  }
  const addedEntries = added.map((p) => headC.get(p));
  for (const e of addedEntries) for (const er of e.errors) violations.push({ kind: er.kind, path: er.path, message: er.message });
  const lhs = applyChanges(mergeBase.rawFiles ?? mergeBase.files, mergeBase.layout, [...mbC.values(), ...addedEntries]);
  const rhs = applyChanges(head.rawFiles ?? head.files, head.layout, [...headC.values()]);
  const diff = diffRowMaps(rowMapOfFiles(lhs.files), rowMapOfFiles(rhs.files));
  const shown = diff.slice(0, 10).join(', ') + (diff.length > 10 ? ' ほか ' + (diff.length - 10) + ' 件' : '');
  if (diff.length && removed.length) {
    violations.push({ kind: 'change-deleted', path: removed[0], message: removed.join(', ') + ': 申告の削除（change-deleted）: 消した申告が月別ファイルへ正しく畳み込まれていない（実効台帳が変わる ID: ' + shown + '）。申告は畳み込み以外で消さないこと。' });
  } else if (diff.length) {
    directEdit('月別ファイルの直接編集（direct-edit）: 実効台帳が変わる ID: ' + shown + '。行の追加・更新は申告（docs/task-list/changes/）で行うこと。');
  }
  const mbMonths = monthFiles(mergeBase);
  const headMonths = monthFiles(head);
  const nonRowChanged = [];
  for (const path of [...new Set([...mbMonths.keys(), ...headMonths.keys()])].sort()) {
    const before = mbMonths.has(path) ? nonRowLines(mbMonths.get(path), path) : null;
    const after = headMonths.has(path) ? nonRowLines(headMonths.get(path), path) : null;
    if (after == null) nonRowChanged.push(path + '（削除）');
    else if (before == null) { if (after.join('\n') !== nonRowLines(newFileSkeleton(path), path).join('\n')) nonRowChanged.push(path + '（新しい月のファイルが雛形と違う）'); }
    else if (after.join('\n') !== before.join('\n')) nonRowChanged.push(path);
  }
  if (nonRowChanged.length) {
    directEdit('月別ファイルの行以外の変更（direct-edit）: ' + nonRowChanged.join(', ') + '。月別ファイルの見出し・表ヘッダは変えないこと。');
  }
  const addedSet = new Set(added);
  const virtualMerge = applyChanges(base.rawFiles ?? base.files, base.layout, [...baseC.values(), ...addedEntries.filter((e) => !baseC.has(e.path))]);
  for (const p of virtualMerge.problems) {
    if ((p.paths ?? [p.path]).some((x) => addedSet.has(x))) violations.push({ kind: p.kind, path: p.path, message: '仮想マージ（base の月別＋base の申告＋PR が足した申告）: ' + p.message });
  }
  return { ok: violations.length === 0, violations, warnings, fold: removed.length > 0 && diff.length === 0, addedChanges: added, removedChanges: removed, modifiedChanges: modified };
}

/** --base / --merge-base の値（ディレクトリ＝リポジトリルート、*.md ファイル＝索引、それ以外＝git の ref）を reader にする。 */
function readerFor(spec) {
  const stat = statSync(spec, { throwIfNoEntry: false });
  if (stat?.isDirectory()) return fsLedgerReader(spec);
  if (stat?.isFile()) return fsLedgerReaderForIndex(spec);
  return gitLedgerReader(spec, { cwd: process.cwd() });
}

/** CLI 本体。終了コードを返す（0: 問題なし・警告のみ / 1: 違反・読めない）。 */
export async function main(argv, out = console.log, err = console.error) {
  const opts = { base: null, mergeBase: null, allowDirectEdit: false };
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--base') opts.base = argv[++i] ?? null;
    else if (argv[i] === '--merge-base') opts.mergeBase = argv[++i] ?? null;
    else if (argv[i] === '--allow-direct-edit') opts.allowDirectEdit = true;
    else positional.push(argv[i]);
  }
  const [filePath] = positional;
  if (!filePath || (argv.includes('--base') && !opts.base) || (argv.includes('--merge-base') && !opts.mergeBase)) {
    err('使い方: node scripts/task-list-structure-check.mjs <docs/task-list.md のパス> [--base <ref|dir>] [--merge-base <ref|dir>] [--allow-direct-edit]');
    return 1;
  }
  let ledger;
  try {
    ledger = await readLedger(fsLedgerReaderForIndex(filePath), { requireRows: false });
  } catch (error) {
    err('台帳を読めなかった（' + filePath + '）: ' + error.message);
    return 1;
  }
  const result = checkTaskListStructure(ledger);
  let virtualHead = null;
  if (opts.base) {
    try {
      const base = await readLedger(readerFor(opts.base), { requireRows: false });
      const mergeBase = opts.mergeBase ? await readLedger(readerFor(opts.mergeBase), { requireRows: false }) : base;
      virtualHead = checkVirtualHead({ mergeBase, base, head: ledger, enforceDirectEdit: !opts.allowDirectEdit });
    } catch (error) {
      err('比較元の台帳を読めなかった（--base ' + opts.base + (opts.mergeBase ? ' --merge-base ' + opts.mergeBase : '') + '）: ' + error.message);
      return 1;
    }
    const annotate = process.env.GITHUB_ACTIONS === 'true';
    for (const w of virtualHead.warnings) {
      out('警告（' + w.kind + '）: ' + w.message);
      if (annotate) out('::warning::' + w.message);
    }
  }
  const failed = !result.ok || (virtualHead && !virtualHead.ok);
  if (failed) {
    err('台帳構造検査: NG（' + filePath + '、' + ledger.layout + ' 配置）');
    for (const message of result.errors) err('  ✗ ' + message);
    for (const v of virtualHead?.violations ?? []) err('  ✗ 等式・連鎖（' + v.kind + '）: ' + v.message);
    return 1;
  }
  out('台帳構造検査: OK（' + filePath + '、' + ledger.layout + ' 配置・' + result.stats.files + ' ファイル、CMP 行 ' + result.stats.rows + ' 件。重複・列数・優先度' + (ledger.layout === 'split' ? '・配置' : '') + 'の違反なし）');
  if (virtualHead) {
    out('等式・連鎖検査: OK（--base ' + opts.base + '。申告 追加 ' + virtualHead.addedChanges.length + ' 件・削除 ' + virtualHead.removedChanges.length + ' 件' + (virtualHead.fold ? '・畳み込み' : '') + '、警告 ' + virtualHead.warnings.length + ' 件）');
  }
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
