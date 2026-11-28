// 試験専用: 旧配置の台帳本文（docs/task-list.md 1ファイル）から新配置の fixture を一時生成する（CMP-261124-1222 段A）。
// 本番の移行（render・migrate）は段Bで作る。ここは「両配置で同じ判定になるか」を試すためだけの写像で、
// 索引には CMP 行以外の本文を残し、行を ID の月（旧形式は legacy.md）へ振り分け、相対リンク evidence/… を ../evidence/… に書き換える。
import { extractRows, expectedLedgerFile, LEDGER_INDEX_PATH } from '../task-list-ledger.mjs';

const HEADER = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';

/** 行本文の相対リンク evidence/… ./evidence/… を月別ファイルから見た ../evidence/… に書き換える。 */
export function rewriteEvidenceLinks(line) {
  return line.replace(/\]\((?:\.\/)?evidence\//g, '](../evidence/');
}

/** @returns {Record<string, string>} リポジトリ相対パス → 本文 */
export function splitSingleLedgerForTest(content) {
  const rows = extractRows(content, LEDGER_INDEX_PATH);
  const rowLines = new Set(rows.map((r) => r.lineNumber));
  const lines = content.split('\n');
  const index = lines.filter((_, i) => !rowLines.has(i + 1)).join('\n');
  const groups = new Map();
  for (const r of rows) {
    const file = expectedLedgerFile(r.id);
    if (!groups.has(file)) groups.set(file, []);
    groups.get(file).push(rewriteEvidenceLinks(r.line));
  }
  const files = { [LEDGER_INDEX_PATH]: index };
  for (const [file, group] of [...groups].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const title = file.slice(file.lastIndexOf('/') + 1, -3);
    files[file] = '# 台帳 ' + title + '\n\n' + HEADER + '\n' + group.join('\n') + '\n';
  }
  return files;
}

/** 新配置の files（パス→本文）から旧配置の docs/task-list.md 1ファイルを一時生成する（逆写像。../evidence/ は evidence/ に戻す）。 */
export function joinSplitLedgerForTest(files) {
  const index = files[LEDGER_INDEX_PATH] ?? '';
  const rows = Object.keys(files).filter((p) => p !== LEDGER_INDEX_PATH).sort()
    .flatMap((p) => extractRows(files[p], p).map((r) => r.line.replace(/\]\(\.\.\/evidence\//g, '](evidence/')));
  return (index.endsWith('\n') ? index : index + '\n') + '\n' + HEADER + '\n' + rows.join('\n') + '\n';
}

/** readLedger の結果を files（パス→本文）に戻す。 */
export function ledgerToFiles(ledger) {
  return Object.fromEntries(ledger.files.filter((f) => f.content != null).map((f) => [f.path, f.content]));
}

/** 実リポジトリの台帳（配置は問わない）から、旧配置・新配置の両方の検体を作る。 */
export function bothLayoutsFromLedger(ledger) {
  const files = ledgerToFiles(ledger);
  if (ledger.layout === 'split') return { single: joinSplitLedgerForTest(files), split: files };
  return { single: files[LEDGER_INDEX_PATH], split: splitSingleLedgerForTest(files[LEDGER_INDEX_PATH]) };
}

/** files（パス→本文）を readLedger の reader にする。 */
export function memoryLedgerReader(files) {
  return {
    listDir(dir) {
      const prefix = dir + '/';
      // 直下の名前（ファイルとサブディレクトリ）。サブディレクトリは名前だけを返し、readFile では null（作業木の reader と同じ）。
      const names = [...new Set(Object.keys(files).filter((p) => p.startsWith(prefix)).map((p) => p.slice(prefix.length).split('/')[0]))];
      return names.length ? names : null;
    },
    readFile(path) {
      return Object.prototype.hasOwnProperty.call(files, path) ? files[path] : null;
    },
  };
}
