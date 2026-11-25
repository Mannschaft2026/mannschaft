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

/** files（パス→本文）を readLedger の reader にする。 */
export function memoryLedgerReader(files) {
  return {
    listDir(dir) {
      const prefix = dir + '/';
      const names = Object.keys(files).filter((p) => p.startsWith(prefix)).map((p) => p.slice(prefix.length)).filter((n) => !n.includes('/'));
      return names.length ? names : null;
    },
    readFile(path) {
      return Object.prototype.hasOwnProperty.call(files, path) ? files[path] : null;
    },
  };
}
