#!/usr/bin/env node
// 台帳の月別移行・表示・差分移植の CLI 本体（CMP-261124-1222 段B）。
//   migrate            旧配置（docs/task-list.md 1枚）を 索引＋docs/task-list/YYYY-MM.md・legacy.md へ分ける。冪等・失敗時は元に戻る。
//   render [--open] [--priority P1] [--month 2026-11]   全ファイル合わせた表を標準出力へ出す。0件は空表。
//   port-diff <base> <head>   旧配置の docs/task-list.md への差分（行追加・行更新）を新配置へ適用する。
// 入口: node scripts/task-list-ledger.mjs <サブコマンド>。依存パッケージなし。

import { execFileSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readdirSync, renameSync, rmSync, rmdirSync, statSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  LEDGER_DIR,
  LEDGER_INDEX_PATH,
  LEGACY_FILE_NAME,
  LedgerReadError,
  expectedLedgerFile,
  extractRows,
  fsLedgerReader,
  fsLedgerReaderForIndex,
  readLedger,
  splitTableRow,
  javaTrim,
} from './task-list-ledger.mjs';
import { checkTaskListStructure } from './task-list-structure-check.mjs';

export const TABLE_HEADER = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |';
export const TABLE_SEPARATOR = '|----|------|------|------|---------|----------------|------|';
/** 索引と月別ファイルの境目。この見出しより前を索引の前置きとして残す。 */
export const ROWS_HEADING = '## 戦役一覧';

/** 索引（docs/task-list.md）から見た相対リンクを、docs/task-list/ 配下のファイルから見た相対リンクへ直す。 */
export function rewriteLinks(line) {
  return line.replace(/\]\(([^)\s]*)/g, (whole, target) => {
    if (target === '' || /^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|#|\/)/.test(target)) return whole;
    return '](../' + target.replace(/^\.\//, '');
  });
}

function fileHeading(path) {
  const name = path.slice(path.lastIndexOf('/') + 1);
  if (name === LEGACY_FILE_NAME) {
    return '# 台帳 旧形式 ID（CMP-003 等）の行\n\n旧形式 ID の行はここに置く（月が決まらないため）。新しい行は ID の月のファイル docs/task-list/YYYY-MM.md の末尾に追加する。規約は [索引](../task-list.md) を参照。\n';
  }
  const month = name.slice(0, -3);
  return '# 台帳 ' + month + '（ID が CMP-' + month.slice(2, 4) + month.slice(5, 7) + 'DD-HHMM の行）\n\n新しい行は本ファイルの末尾に追加する。規約は [索引](../task-list.md) を参照。\n';
}

/** 月別ファイルの新規作成時の本文（見出し＋7列表ヘッダ）。 */
export function newFileSkeleton(path) {
  return fileHeading(path) + '\n' + TABLE_HEADER + '\n' + TABLE_SEPARATOR + '\n';
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
 * @returns {{ added: string[], updated: string[], deleted: string[], preambleChanged: boolean, problems: string[], files: Map<string, string> }}
 *   files: 変更後の本文（パス → 本文）。変更のあったファイルだけ。problems が空でなければ適用してはならない（問題の無い分だけは files に入る）。
 */
export function planPortDiff({ baseText, headText, ledger }) {
  const base = new Map(extractRows(baseText ?? '', LEDGER_INDEX_PATH).map((r) => [r.id, r.line]));
  const head = extractRows(headText ?? '', LEDGER_INDEX_PATH);
  const headIds = new Set(head.map((r) => r.id));
  const result = { added: [], updated: [], deleted: [...base.keys()].filter((id) => !headIds.has(id)), preambleChanged: false, problems: [], files: new Map() };
  if (ledger.layout !== 'split') {
    result.problems.push('作業木が新配置でない。port-diff は新配置への適用用');
    return result;
  }
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
  const ledger = await readLedger(fsLedgerReader(root), { requireRows: false });
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

/** CLI。終了コードを返す。 */
export async function main(argv, { out = console.log, err = console.error, root = process.cwd() } = {}) {
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
    err('使い方: node scripts/task-list-ledger.mjs <migrate | render [--open] [--priority P1] [--month YYYY-MM] | port-diff <base> <head> [--dry-run]>');
    return 1;
  } catch (error) {
    err('台帳 ' + (cmd ?? '') + ' 失敗: ' + (error?.message ?? error));
    return 1;
  }
}
