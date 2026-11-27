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
// 使い方: node scripts/task-list-structure-check.mjs <task-list.md のパス>
//   月別ファイルは索引と同じディレクトリの task-list/ から読む。
// 違反・ファイル不在・空・走査不能はすべて理由を出力して exit 1（例外の stack で落とさない）。
// 依存パッケージなし（GitHub ランナー既定の Node で動く）。

import { pathToFileURL } from 'node:url';
import {
  CMP_ID_FORM,
  LEDGER_INDEX_PATH,
  LEGACY_FILE_NAME,
  asLedger,
  expectedLedgerFile,
  fsLedgerReaderForIndex,
  isLedgerFileName,
  javaTrim,
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
    if (columns !== EXPECTED_COLUMN_COUNT) {
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
  return { ok: errors.length === 0, errors, stats };
}

/** 名前の別名（全ファイル横断であることを呼び出し側で明示したいとき）。 */
export const checkLedger = checkTaskListStructure;

/** CLI 本体。終了コードを返す（0: 問題なし / 1: 違反・読めない）。 */
export async function main(argv, out = console.log, err = console.error) {
  const [filePath] = argv;
  if (!filePath) {
    err('使い方: node scripts/task-list-structure-check.mjs <docs/task-list.md のパス>');
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
  if (!result.ok) {
    err('台帳構造検査: NG（' + filePath + '、' + ledger.layout + ' 配置）');
    for (const message of result.errors) err('  ✗ ' + message);
    return 1;
  }
  out('台帳構造検査: OK（' + filePath + '、' + ledger.layout + ' 配置・' + result.stats.files + ' ファイル、CMP 行 ' + result.stats.rows + ' 件。重複・列数・優先度' + (ledger.layout === 'split' ? '・配置' : '') + 'の違反なし）');
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
