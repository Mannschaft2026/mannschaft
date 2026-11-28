#!/usr/bin/env node
// 台帳の月別移行・表示・差分移植の CLI 本体（CMP-261124-1222 段B）。
//   migrate            旧配置（docs/task-list.md 1枚）を 索引＋docs/task-list/YYYY-MM.md・legacy.md へ分ける。冪等・失敗時は元に戻る。
//   render [--open] [--priority P1] [--month 2026-11]   全ファイル合わせた表を標準出力へ出す。0件は空表。
//   port-diff <base> <head>   旧配置の docs/task-list.md への差分（行追加・行更新）を新配置へ適用する（生の月別ファイルへ）。
//   effective [--json] [--raw] [--root <dir>]   申告（docs/task-list/changes/）適用後の実効台帳を出す（--json は files・rows・changes。CMP-261124-1222 ④）。
//   add <7列行>                          行の追加を申告する（docs/task-list/changes/<CMP-ID>--<UTC>--<乱数>.md を作る。既存 ID は拒否）。
//   update <CMP-ID> --set <列名>=<値> …   現在の実効行の列を置き換える申告を作る（base = 現在の実効行の sha256）。
//   rebase <申告ファイル>                 base 不一致（孤児）の update 申告の base を現在の実効行で取り直す。内容は作者が確認すること。
// 入口: node scripts/task-list-ledger.mjs <サブコマンド>。依存パッケージなし。

import { execFileSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readdirSync, renameSync, rmSync, rmdirSync, statSync, writeFileSync, readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { randomInt } from 'node:crypto';
import {
  LEDGER_DIR,
  LEDGER_INDEX_PATH,
  LEGACY_FILE_NAME,
  LedgerReadError,
  CHANGES_DIR,
  MAX_CHANGE_BYTES,
  TABLE_HEADER,
  applyChanges,
  changeRowHash,
  ledgerRowMap,
  parseChangeFile,
  TABLE_SEPARATOR,
  expectedLedgerFile,
  ledgerText,
  newFileSkeleton,
  extractRows,
  fsLedgerReader,
  fsLedgerReaderForIndex,
  readLedger,
  splitTableRow,
  javaTrim,
} from './task-list-ledger.mjs';
import { checkTaskListStructure } from './task-list-structure-check.mjs';

// 月別ファイルの雛形は読む側（申告の add で新月ファイルを作る）と共有するため task-list-ledger.mjs に置く。従来の import 先も保つ。
export { TABLE_HEADER, TABLE_SEPARATOR, newFileSkeleton };

/** 索引と月別ファイルの境目。この見出しより前を索引の前置きとして残す。 */
export const ROWS_HEADING = '## 戦役一覧';

/** 索引（docs/task-list.md）から見た相対リンクを、docs/task-list/ 配下のファイルから見た相対リンクへ直す。 */
export function rewriteLinks(line) {
  return line.replace(/\]\(([^)\s]*)/g, (whole, target) => {
    if (target === '' || /^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|#|\/)/.test(target)) return whole;
    return '](../' + target.replace(/^\.\//, '');
  });
}


/** 旧配置の本文を {索引本文, ファイルごとの行, 捨てた行} に分ける（純粋関数）。 */
export function splitSingleLedger(text) {
  const lines = text.split('\n');
  const headingAt = lines.findIndex((l) => l.trimEnd() === ROWS_HEADING);
  if (headingAt < 0) throw new LedgerReadError('旧台帳に「' + ROWS_HEADING + '」の見出しが無い。移行できない');
  const rowLineNumbers = new Set(extractRows(text, LEDGER_INDEX_PATH).map((r) => r.lineNumber));
  const byFile = new Map();
  const dropped = [];
  for (let i = headingAt; i < lines.length; i++) {
    const line = lines[i];
    if (rowLineNumbers.has(i + 1)) {
      const row = lines[i].endsWith('\r') ? lines[i].slice(0, -1) : lines[i];
      const id = javaTrim(splitTableRow(row)[1]);
      const file = expectedLedgerFile(id);
      if (!file) throw new LedgerReadError('配置先を決められない ID: ' + id);
      if (!byFile.has(file)) byFile.set(file, []);
      byFile.get(file).push(lines[i]);
    } else if (line.trim() !== '') {
      dropped.push(line);
    }
  }
  const index = lines.slice(0, headingAt).join('\n').replace(/\s+$/, '') + '\n';
  return { index, byFile, dropped };
}

function verifySplit(oldText, built, tmpIndexPath) {
  const oldRows = extractRows(oldText, LEDGER_INDEX_PATH);
  const problems = [];
  const expectedByFile = new Map();
  for (const row of oldRows) {
    const file = expectedLedgerFile(row.id);
    if (!expectedByFile.has(file)) expectedByFile.set(file, []);
    expectedByFile.get(file).push([row.id, rewriteLinks(row.line)]);
  }
  return readLedger(fsLedgerReaderForIndex(tmpIndexPath)).then((ledger) => {
    if (ledger.layout !== 'split') problems.push('移行後が新配置と判定されない');
    if (ledger.rows.length !== oldRows.length) problems.push('行数が違う: ' + oldRows.length + ' → ' + ledger.rows.length);
    const oldIds = oldRows.map((r) => r.id).sort().join('\n');
    const newIds = ledger.rows.map((r) => r.id).sort().join('\n');
    if (oldIds !== newIds) problems.push('ID 集合が一致しない');
    const gotByFile = new Map();
    for (const row of ledger.rows) {
      if (!gotByFile.has(row.file)) gotByFile.set(row.file, []);
      gotByFile.get(row.file).push([row.id, row.line]);
    }
    for (const [file, expected] of expectedByFile) {
      if (JSON.stringify(gotByFile.get(file) ?? []) !== JSON.stringify(expected)) problems.push(file + ' の行がリンク書き換え以外で一致しない、または相対順序が違う');
    }
    if (ledger.rows.some((r) => r.file === LEDGER_INDEX_PATH)) problems.push('索引に CMP 行が残った');
    const newCheck = checkTaskListStructure(ledger);
    if (!newCheck.ok) problems.push('移行後の構造検査が赤: ' + newCheck.errors.slice(0, 3).join(' / '));
    if (problems.length) throw new LedgerReadError('移行後の検証に失敗（入れ替えない）: ' + problems.join('; '));
    return { rows: ledger.rows.length, files: ledger.files.length - 1, structure: newCheck.stats };
  });
}

/**
 * 旧配置を新配置へ移す。一時ディレクトリで組み立てて検証し、検証後に rename で入れ替える。
 * 途中で失敗したら入れ替え済みの分を戻して例外を投げる。
 * @param {string} root リポジトリルート
 * @param {{ inject?: (step: string) => void }} [options] inject: 試験用の失敗注入（各段階の直前に呼ばれる）
 */
export async function migrateLedger(root, { inject = () => {} } = {}) {
  const indexPath = join(root, ...LEDGER_INDEX_PATH.split('/'));
  const dirPath = join(root, ...LEDGER_DIR.split('/'));
  const ledger = await readLedger(fsLedgerReader(root), { requireRows: false });
  if (ledger.layout === 'split') return { migrated: false, reason: '既に新配置（何もしない）' };
  const oldText = ledger.files[0].content;
  if (oldText == null) throw new LedgerReadError(LEDGER_INDEX_PATH + ' が無い');
  if (existsSync(dirPath) && readdirSync(dirPath).length > 0) {
    throw new LedgerReadError(LEDGER_DIR + '/ に台帳以外のファイルがある。移行しない: ' + readdirSync(dirPath).join(', '));
  }
  const oldCheck = checkTaskListStructure(ledger);
  if (!oldCheck.ok) throw new LedgerReadError('移行前の検証に失敗（旧台帳が構造検査で赤。先に直す）: ' + oldCheck.errors.slice(0, 3).join(' / '));
  const { index, byFile, dropped } = splitSingleLedger(oldText);
  const tmp = join(root, 'docs', '.task-list-migrate-' + process.pid + '-' + Date.now());
  const tmpIndex = join(tmp, 'task-list.md');
  const tmpDir = join(tmp, 'task-list');
  let swapped = [];
  try {
    inject('build');
    mkdirSync(tmpDir, { recursive: true });
    writeFileSync(tmpIndex, index);
    for (const [file, rowLines] of [...byFile].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
      const body = newFileSkeleton(file) + rowLines.map((l) => rewriteLinks(l) + '\n').join('');
      writeFileSync(join(root, ...file.split('/')).replace(dirPath, tmpDir), body);
    }
    inject('verify');
    const verified = await verifySplit(oldText, byFile, tmpIndex);
    inject('swap-backup');
    renameSync(indexPath, join(tmp, 'old-task-list.md'));
    swapped.push('index-backup');
    if (existsSync(dirPath)) rmdirSync(dirPath);
    inject('swap-dir');
    renameSync(tmpDir, dirPath);
    swapped.push('dir');
    inject('swap-index');
    renameSync(tmpIndex, indexPath);
    swapped.push('index');
    inject('cleanup');
    rmSync(tmp, { recursive: true, force: true });
    return { migrated: true, ...verified, dropped };
  } catch (error) {
    // 巻き戻し: 入れ替え済みの分を戻す
    try {
      if (swapped.includes('index')) renameSync(indexPath, tmpIndex);
      if (swapped.includes('dir')) renameSync(dirPath, tmpDir);
      if (swapped.includes('index-backup')) renameSync(join(tmp, 'old-task-list.md'), indexPath);
    } finally {
      rmSync(tmp, { recursive: true, force: true });
    }
    throw error;
  }
}

/** 状態セルの先頭が P1〜P5（未完）か。 */
function statusOf(line) {
  return javaTrim(splitTableRow(line)[3] ?? '').replace(/\*/g, '');
}

/**
 * 台帳の行を絞って表にする。0件でもヘッダ付きの空表を返す。
 * @param {{ rows: {id: string, line: string, file: string}[] }} ledger
 */
export function renderTable(ledger, { open = false, priority = null, month = null } = {}) {
  if (priority != null && !/^P[1-5]$/.test(priority)) throw new Error('--priority は P1〜P5: ' + priority);
  if (month != null && !/^\d{4}-(?:0[1-9]|1[0-2])$/.test(month)) throw new Error('--month は YYYY-MM: ' + month);
  const rows = ledger.rows.filter((row) => {
    const status = statusOf(row.line);
    if (open && !/^P[1-5] /.test(status)) return false;
    if (priority != null && !status.startsWith(priority + ' ')) return false;
    if (month != null && expectedLedgerFile(row.id) !== LEDGER_DIR + '/' + month + '.md') return false;
    return true;
  });
  return [TABLE_HEADER, TABLE_SEPARATOR, ...rows.map((r) => r.line)].join('\n') + '\n';
}

/** 行（先頭セルが CMP-ID の表の行）以外の行。前置きなど「移植できない」変更の検出に使う。 */
function nonRowLines(text) {
  const rowNumbers = new Set(extractRows(text ?? '', LEDGER_INDEX_PATH).map((r) => r.lineNumber));
  return (text ?? '').split('\n').filter((_, i) => !rowNumbers.has(i + 1));
}

/**
 * 旧配置の docs/task-list.md の差分（base→head の行追加・行更新）を、新配置の台帳へ当てる計画を立てる（純粋関数。ファイルは触らない）。
 * port-diff（CLI）と台帳衝突の自動修復（task-list-conflict-autofix）が共有する。
 * @param {{ baseText: string|null, headText: string|null, ledger: { layout: string, files: {path: string, content: string|null}[], rows: {id: string, file: string}[] } }} input ledger は新配置の readLedger の結果
 * @returns {{ added: string[], updated: string[], unchanged: string[], deleted: string[], preambleChanged: boolean, problems: string[], files: Map<string, string> }}
 *   files: 変更後の本文（パス → 本文）。変更のあったファイルだけ。problems が空でなければ適用してはならない（問題の無い分だけは files に入る）。
 */
export function planPortDiff({ baseText, headText, ledger }) {
  const baseRows = extractRows(baseText ?? '', LEDGER_INDEX_PATH);
  const head = extractRows(headText ?? '', LEDGER_INDEX_PATH);
  const base = new Map(baseRows.map((r) => [r.id, r.line]));
  const headIds = new Set(head.map((r) => r.id));
  const result = { added: [], updated: [], unchanged: [], deleted: [...base.keys()].filter((id) => !headIds.has(id)), preambleChanged: false, problems: [], files: new Map() };
  if (ledger.layout !== 'split') {
    result.problems.push('作業木が新配置でない。port-diff は新配置への適用用');
    return result;
  }
  // Map 化の前に重複 ID を見つける（後勝ちで静かに潰さない）
  for (const [what, rows] of [['分岐点', baseRows], ['PR の head', head]]) {
    const seen = new Set();
    for (const r of rows) {
      if (seen.has(r.id)) result.problems.push(what + ' の旧配置に重複 ID がある: ' + r.id);
      seen.add(r.id);
    }
  }
  if (result.problems.length) return result;
  result.preambleChanged = JSON.stringify(nonRowLines(baseText)) !== JSON.stringify(nonRowLines(headText));
  const where = new Map(ledger.rows.map((r) => [r.id, r]));
  const edits = new Map(); // file -> { replace: Map(id,line), append: string[] }
  const edit = (file) => { if (!edits.has(file)) edits.set(file, { replace: new Map(), append: [] }); return edits.get(file); };
  for (const row of head) {
    if (base.get(row.id) === row.line) continue;
    const line = rewriteLinks(row.line);
    const present = where.get(row.id);
    if (base.has(row.id)) {
      if (!present) { result.problems.push('更新対象の行が新配置に見つからない: ' + row.id); continue; }
      const current = present.line;
      if (current === line) { result.unchanged.push(row.id); continue; } // main に同じ更新が既にある
      if (current !== rewriteLinks(base.get(row.id))) {
        result.problems.push('競合: ' + row.id + ' は main 側でも更新されている（分岐点・main・PR の3者が異なる）。手で統合すること');
        continue;
      }
      edit(present.file).replace.set(row.id, line);
      result.updated.push(row.id);
    } else {
      if (present) { result.problems.push('追加する行が新配置に既にある: ' + row.id); continue; }
      const file = expectedLedgerFile(row.id);
      if (!file) { result.problems.push('配置先を決められない ID: ' + row.id); continue; }
      edit(file).append.push(line);
      result.added.push(row.id);
    }
  }
  const contentOf = new Map(ledger.files.map((f) => [f.path, f.content]));
  for (const [file, { replace, append }] of edits) {
    let text = contentOf.get(file) ?? newFileSkeleton(file);
    if (replace.size) {
      text = text.split('\n').map((l) => {
        if (!l.startsWith('|')) return l;
        const id = javaTrim(splitTableRow(l)[1] ?? '');
        return replace.has(id) ? replace.get(id) : l;
      }).join('\n');
    }
    if (append.length) text = (text.endsWith('\n') ? text : text + '\n') + append.map((l) => l + '\n').join('');
    result.files.set(file, text);
  }
  return result;
}

/**
 * 旧配置の docs/task-list.md の差分（base→head の行追加・行更新）を、新配置の作業木へ適用する。
 * @returns {{ added: string[], updated: string[], skippedDeleted: string[], files: string[] }}
 */
export async function portDiff({ baseText, headText, root, dryRun = false }) {
  // 生の月別ファイルへ適用する（実効台帳で書くと未畳み込みの申告を月別ファイルへ畳み込んでしまう）
  const ledger = await readLedger(fsLedgerReader(root), { requireRows: false, raw: true });
  const plan = planPortDiff({ baseText, headText, ledger });
  if (plan.problems.length) throw new LedgerReadError(plan.problems[0]);
  const result = { added: plan.added, updated: plan.updated, skippedDeleted: plan.deleted, files: [...plan.files.keys()].sort() };
  if (dryRun) return result;
  mkdirSync(join(root, LEDGER_DIR), { recursive: true });
  for (const [file, text] of plan.files) writeFileSync(join(root, ...file.split('/')), text);
  return result;
}

function gitShow(ref, path, cwd) {
  try {
    return execFileSync('git', ['show', ref + ':' + path], { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 });
  } catch {
    return null; // ref にそのファイルが無い
  }
}

/**
 * effective --json の出力（申告適用後の実効台帳）。Python の docs/prototypes/task_list_ledger.py がこれを読む。
 * 申告の本文（content）は出さない（行は files・rows にある）。
 */
export function effectiveJson(ledger) {
  return {
    layout: ledger.layout,
    files: ledger.files,
    rows: ledger.rows.map((r) => ({ id: r.id, file: r.file, line: r.line, lineNumber: r.lineNumber })),
    missing: ledger.missing,
    changes: {
      entries: ledger.changes.entries.map((e) => ({ path: e.path, id: e.id, op: e.op, base: e.base, valid: e.valid })),
      applied: ledger.changes.applied,
      problems: ledger.changes.problems.map((p) => ({ kind: p.kind, path: p.path, message: p.message })),
    },
  };
}

// ---------------------------------------------------------------------------
// 変更申告を作る（add / update / rebase。CMP-261124-1222 ④ 段1d）
// ---------------------------------------------------------------------------

/** 表の7列の名前（update --set の列名）。 */
export const LEDGER_COLUMNS = ['ID', '戦役', '状態', '依存', '完了条件', '証拠(PR/テスト)', '台帳'];
const COLUMN_ALIASES = new Map([['証拠', '証拠(PR/テスト)']]);
const CMP_ID_ONLY = /^(?:CMP-\d{6}-\d{4}|CMP-\d+)$/;

function pad(n, width) {
  return String(n).padStart(width, '0');
}

/** 申告のファイル名 <CMP-ID>--<UTC yyyymmddThhmmssZ>--<6桁乱数>.md。rand を省くと暗号乱数で作る。 */
export function changeFileName(id, now = new Date(), rand = pad(randomInt(0, 1000000), 6)) {
  if (!/^\d{6}$/.test(String(rand))) throw new Error('乱数は6桁の数字: ' + rand);
  const t = now.getUTCFullYear() + pad(now.getUTCMonth() + 1, 2) + pad(now.getUTCDate(), 2) + 'T' + pad(now.getUTCHours(), 2) + pad(now.getUTCMinutes(), 2) + pad(now.getUTCSeconds(), 2) + 'Z';
  return id + '--' + t + '--' + rand + '.md';
}

/** 表の行を生のセル（\| のエスケープを保ったまま）に分ける。先頭・末尾の空セルを含む。 */
function rawCells(line) {
  const cells = [];
  let buf = '';
  for (let i = 0; i < line.length; i++) {
    if (line[i] === '\\' && line[i + 1] === '|') { buf += '\\|'; i++; continue; }
    if (line[i] === '|') { cells.push(buf); buf = ''; continue; }
    buf += line[i];
  }
  cells.push(buf);
  return cells;
}

/** セルに入れる値の | を \| にエスケープする（既に \| のものはそのまま）。改行は拒否。 */
export function escapeCell(value) {
  const text = String(value);
  if (/[\r\n]/.test(text)) throw new Error('セルの値に改行は入れられない');
  return text.replace(/(?<!\\)\|/g, '\\|');
}

/** 申告の本文（1行目メタ・2行目の7列行）。上限 16KiB を超えれば例外。台帳の自動修復（planChangePort）も使う。 */
export function changeContent(op, id, base, line) {
  const content = '<!-- ledger-change v1 op=' + op + ' id=' + id + ' base=' + base + ' -->\n' + line + '\n';
  const bytes = Buffer.byteLength(content, 'utf8');
  if (bytes > MAX_CHANGE_BYTES) throw new Error('申告が ' + bytes + ' バイトで上限 ' + MAX_CHANGE_BYTES + ' バイト（16KiB）を超える。行を短くすること');
  return content;
}

function checkedChange(path, content) {
  const parsed = parseChangeFile(path, content);
  if (!parsed.valid) throw new Error(parsed.errors.map((e) => e.message).join(' / '));
  return parsed;
}

/**
 * add 申告の計画（純粋関数）。line は完全な7列行（月別ファイルに置いたときの形）。
 * \returns {{ path: string, content: string, id: string, warnings: string[] }}
 */
export function planAddChange(ledger, line, { now = new Date(), rand } = {}) {
  const text = String(line).replace(/\r?\n$/, '');
  if (/[\r\n]/.test(text)) throw new Error('行は1行で渡すこと');
  if (!text.startsWith('|')) throw new Error('表の行（| で始まる7列行）を渡すこと');
  const cells = splitTableRow(text);
  if (cells.length - 2 !== 7) throw new Error('列数が ' + (cells.length - 2) + '（期待 7 列）。セル内の | は \\| にエスケープすること');
  const id = javaTrim(cells[1]);
  if (!CMP_ID_ONLY.test(id) || !expectedLedgerFile(id)) throw new Error('先頭セルが CMP-ID でない: ' + id);
  if (ledgerRowMap(ledger).has(id)) throw new Error(id + ' は既に実効台帳にある。新しい ID を採るか update にすること');
  const path = CHANGES_DIR + '/' + changeFileName(id, now, rand);
  const content = changeContent('add', id, 'none', text);
  checkedChange(path, content);
  return { path, content, id, warnings: [] };
}

/**
 * update 申告の計画（純粋関数）。sets は [列名, 値] の配列。base は現在の実効行の sha256。
 * \returns {{ path: string, content: string, id: string, before: string, after: string, warnings: string[] }}
 */
export function planUpdateChange(ledger, id, sets, { now = new Date(), rand } = {}) {
  if (!CMP_ID_ONLY.test(String(id))) throw new Error('CMP-ID を指定すること: ' + id);
  if (!Array.isArray(sets) || sets.length === 0) throw new Error('--set <列名>=<値> を1つ以上指定すること');
  const before = ledgerRowMap(ledger).get(id);
  if (before == null) throw new Error(id + ' は実効台帳に無い（行の追加は add）');
  const cells = rawCells(before);
  if (cells.length - 2 !== 7) throw new Error(id + ' の現在の行が7列でない（' + (cells.length - 2) + ' 列）。先に台帳を直すこと');
  for (const [rawName, value] of sets) {
    const name = COLUMN_ALIASES.get(rawName) ?? rawName;
    const index = LEDGER_COLUMNS.indexOf(name);
    if (index < 0) throw new Error('列名が不正: ' + rawName + '（' + LEDGER_COLUMNS.join(' / ') + '）');
    if (index === 0) throw new Error('ID 列は変えられない（別の ID にするなら add）');
    cells[index + 1] = ' ' + escapeCell(value) + ' ';
  }
  const after = cells.join('|');
  if (after === before) throw new Error(id + ' の行に変化が無い');
  const base = changeRowHash(before);
  const path = CHANGES_DIR + '/' + changeFileName(id, now, rand);
  const content = changeContent('update', id, base, after);
  checkedChange(path, content);
  const warnings = [];
  const applied = new Set(ledger.changes?.applied ?? []);
  const entries = ledger.changes?.entries ?? [];
  const sameBase = entries.filter((e) => e.id === id && e.op === 'update' && e.base === base && !applied.has(e.path));
  if (sameBase.length) warnings.push('同じ base の未畳み込み申告がある（' + sameBase.map((e) => e.path).join(', ') + '）。この申告と fork（分岐）になる。先に連鎖を直すこと');
  const afterHash = changeRowHash(after);
  const rollback = entries.filter((e) => e.id === id && e.base === afterHash);
  if (rollback.length) warnings.push('巻き戻し（A→B→A）: 新しい行は既存の申告 ' + rollback.map((e) => e.path).join(', ') + ' の base と同じ版に戻る。読む側で偽の fork・孤児に見えることがある。巻き戻しでなく別の値にできないか確認すること');
  return { path, content, id, before, after, warnings };
}

/**
 * rebase 申告の計画（純粋関数）。申告自身を除いた実効台帳の現在行から base を取り直す。
 * \returns {{ changed: boolean, content: string, message: string[] }}
 */
export function planRebaseChange(ledger, path, content) {
  const entry = parseChangeFile(path, content);
  if (!entry.valid) throw new Error(entry.errors.map((e) => e.message).join(' / '));
  if ((ledger.changes?.applied ?? []).includes(path)) return { changed: false, content, message: [path + ' は実効台帳に連鎖している（取り直し不要）'] };
  const others = (ledger.changes?.entries ?? []).filter((e) => e.path !== path);
  const without = applyChanges(ledger.rawFiles ?? ledger.files, ledger.layout, others);
  const rows = without.files.flatMap((f) => (f.content == null ? [] : extractRows(f.content, f.path)));
  const current = ledgerRowMap({ files: without.files, rows }).get(entry.id);
  if (entry.op === 'add') {
    if (current != null) throw new Error(entry.id + ' は既に実効台帳にある。add は rebase できないので、新しい ID を採って add し直すこと');
    return { changed: false, content, message: [path + ' は add（base=none）で、ID は実効台帳に無い（取り直し不要）'] };
  }
  if (current == null) throw new Error(entry.id + ' は実効台帳に無い。update の対象が無い');
  const base = changeRowHash(current);
  const next = changeContent('update', entry.id, base, entry.line);
  const candidate = checkedChange(path, next);
  // 書く前に、取り直した申告が他の申告と合わせて実効台帳に適用されることを確かめる（fork 等で適用されないなら書かない）
  const trial = applyChanges(ledger.rawFiles ?? ledger.files, ledger.layout, [...others, candidate]);
  if (!trial.applied.includes(path)) {
    const problems = trial.problems.filter((p) => (p.paths ?? [p.path]).includes(path)).map((p) => p.message);
    throw new Error('base を取り直しても実効台帳に適用されない（書き換えない）: ' + (problems.join(' / ') || '原因不明'));
  }
  return {
    changed: true,
    content: next,
    message: [
      path + ': base を現在の実効行で取り直した（' + entry.base + ' → ' + base + '）。',
      '内容（2行目）は取り直し前に作った行のまま。現在の実効行との違いを作者が確認し、main 側の更新を消していないか確かめること:',
      '  現在の実効行: ' + current,
      '  申告の行    : ' + entry.line,
    ],
  };
}

async function readRootLedger(root) {
  return readLedger(fsLedgerReader(root), { requireRows: false });
}

/** 申告を書き、読む側で適用できることを確かめる（適用されなければ消して失敗）。 */
async function writeChange(root, plan, ledger) {
  // 書く前に、作る申告が他の申告と合わせて実効台帳に適用されることを確かめる（fork 等で適用されないなら作らない）
  const trial = applyChanges(ledger.rawFiles ?? ledger.files, ledger.layout, [...(ledger.changes?.entries ?? []), parseChangeFile(plan.path, plan.content)]);
  if (!trial.applied.includes(plan.path)) {
    const problems = trial.problems.filter((p) => (p.paths ?? [p.path]).includes(plan.path)).map((p) => p.message);
    throw new Error('作る申告が実効台帳に適用されない（作らない）: ' + (problems.join(' / ') || '原因不明'));
  }
  const full = join(root, ...plan.path.split('/'));
  mkdirSync(join(root, ...CHANGES_DIR.split('/')), { recursive: true });
  writeFileSync(full, plan.content, { flag: 'wx' });
  const written = await readRootLedger(root);
  if (!written.changes.applied.includes(plan.path)) {
    const problems = written.changes.problems.filter((p) => (p.paths ?? [p.path]).includes(plan.path)).map((p) => p.message);
    rmSync(full, { force: true });
    throw new Error('作った申告が実効台帳に適用されない（消した）: ' + (problems.join(' / ') || '原因不明'));
  }
  return written;
}

/** CLI。終了コードを返す。 */
export async function main(argv, { out = console.log, err = console.error, root = process.cwd(), now = () => new Date(), rand = undefined } = {}) {
  const [cmd, ...rest] = argv;
  try {
    if (cmd === 'migrate') {
      const r = await migrateLedger(root);
      if (!r.migrated) { out('台帳の移行: ' + r.reason); return 0; }
      out('台帳の移行: 完了（CMP 行 ' + r.rows + ' 件 → 月別・legacy ' + r.files + ' ファイル。索引から捨てた非表行 ' + r.dropped.length + ' 行）');
      for (const d of r.dropped) out('  捨てた行: ' + d.slice(0, 80));
      return 0;
    }
    if (cmd === 'render') {
      const opts = { open: false, priority: null, month: null };
      for (let i = 0; i < rest.length; i++) {
        if (rest[i] === '--open') opts.open = true;
        else if (rest[i] === '--priority') opts.priority = rest[++i];
        else if (rest[i] === '--month') opts.month = rest[++i];
        else throw new Error('不明な引数: ' + rest[i]);
      }
      const ledger = await readLedger(fsLedgerReader(root));
      process.stdout.write(renderTable(ledger, opts));
      return 0;
    }
    if (cmd === 'port-diff') {
      const dry = rest.includes('--dry-run');
      const [base, head] = rest.filter((a) => a !== '--dry-run');
      if (!base || !head) throw new Error('使い方: port-diff <base> <head> [--dry-run]');
      const r = await portDiff({ baseText: gitShow(base, LEDGER_INDEX_PATH, root), headText: gitShow(head, LEDGER_INDEX_PATH, root), root, dryRun: dry });
      out('port-diff' + (dry ? '（dry-run）' : '') + ': 追加 ' + r.added.length + ' 件 [' + r.added.join(', ') + '] / 更新 ' + r.updated.length + ' 件 [' + r.updated.join(', ') + '] / 対象ファイル ' + r.files.join(', '));
      if (r.skippedDeleted.length) err('注意: base にあって head に無い行は適用しない（行の削除は手で）: ' + r.skippedDeleted.join(', '));
      return 0;
    }
    if (cmd === 'effective') {
      const opts = { json: false, raw: false, root };
      for (let i = 0; i < rest.length; i++) {
        if (rest[i] === '--json') opts.json = true;
        else if (rest[i] === '--raw') opts.raw = true;
        else if (rest[i] === '--root') opts.root = rest[++i];
        else throw new Error('不明な引数: ' + rest[i]);
      }
      const ledger = await readLedger(fsLedgerReader(opts.root), { requireRows: false, raw: opts.raw });
      process.stdout.write(opts.json ? JSON.stringify(effectiveJson(ledger)) + '\n' : ledgerText(ledger));
      return 0;
    }
    if (cmd === 'add') {
      if (rest.length !== 1) throw new Error('使い方: add "<7列行>"');
      const ledger = await readRootLedger(root);
      const plan = planAddChange(ledger, rest[0], { now: now(), rand: rand?.() });
      await writeChange(root, plan, ledger);
      out('申告を作った: ' + plan.path + '（add ' + plan.id + '）');
      return 0;
    }
    if (cmd === 'update') {
      const [id, ...opts] = rest;
      const sets = [];
      for (let i = 0; i < opts.length; i++) {
        if (opts[i] !== '--set' || opts[i + 1] == null) throw new Error('使い方: update <CMP-ID> --set <列名>=<値> [--set …]');
        const kv = opts[++i];
        const eq = kv.indexOf('=');
        if (eq <= 0) throw new Error('--set は <列名>=<値>: ' + kv);
        sets.push([kv.slice(0, eq), kv.slice(eq + 1)]);
      }
      const ledger = await readRootLedger(root);
      const plan = planUpdateChange(ledger, id, sets, { now: now(), rand: rand?.() });
      for (const w of plan.warnings) err('警告: ' + w);
      await writeChange(root, plan, ledger);
      out('申告を作った: ' + plan.path + '（update ' + plan.id + '）');
      out('  変更前: ' + plan.before);
      out('  変更後: ' + plan.after);
      return 0;
    }
    if (cmd === 'rebase') {
      if (rest.length !== 1) throw new Error('使い方: rebase <申告ファイル>');
      const path = CHANGES_DIR + '/' + basename(rest[0]);
      const full = join(root, ...path.split('/'));
      if (!existsSync(full)) throw new Error('申告は ' + CHANGES_DIR + '/ に置くこと: ' + rest[0]);
      const original = readFileSync(full, 'utf8');
      const plan = planRebaseChange(await readRootLedger(root), path, original);
      if (plan.changed) {
        writeFileSync(full, plan.content);
        let ledger;
        try {
          ledger = await readRootLedger(root);
        } catch (error) {
          writeFileSync(full, original);
          throw error;
        }
        if (!ledger.changes.applied.includes(path)) {
          writeFileSync(full, original); // 書き込み後の検証に失敗したら元の内容に戻す
          throw new Error('取り直した申告が実効台帳に適用されない（元に戻した）: ' + ledger.changes.problems.filter((p) => (p.paths ?? [p.path]).includes(path)).map((p) => p.message).join(' / '));
        }
      }
      for (const m of plan.message) out(m);
      return 0;
    }
    err('使い方: node scripts/task-list-ledger.mjs <add "<7列行>" | update <CMP-ID> --set <列名>=<値> … | rebase <申告ファイル> | migrate | render [--open] [--priority P1] [--month YYYY-MM] | port-diff <base> <head> [--dry-run] | effective [--json] [--raw] [--root <dir>]>');
    return 1;
  } catch (error) {
    err('台帳 ' + (cmd ?? '') + ' 失敗: ' + (error?.message ?? error));
    return 1;
  }
}
