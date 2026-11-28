#!/usr/bin/env node
// Task-List PR Gate（.github/workflows/task-list-pr-gate.yml）の台帳まわりの部品（CMP-261124-1222 ④ 段1c）。
//
// - 台帳の取得: Contents API の1件ずつ取得をやめ、GraphQL の Tree entries で1参照1回にまとめて取る
//   （索引 docs/task-list.md・docs/task-list/ 直下・docs/task-list/changes/ 直下を1クエリ）。取得 API の呼び出し数は申告の件数に依らない。
//   blob 本文は oid でキャッシュし、参照（base・head・分岐点・base.ref）をまたいで同じ blob を取り直さない。
//   本文が null（バイナリ）は赤。isTruncated（GraphQL が本文を切った大きい月別ファイル）は git blob API で oid から全文を取り直す
//   （呼び出しは切られた blob の数だけで申告の件数には依らない。取り直せなければ赤）。申告（16KiB 以下）が切られていれば赤。
//   GraphQL の失敗（5xx・errors・ref を解決できない）は不在と扱わず例外にする。
// - 仮想 head の等式・連鎖（task-list-structure-check.mjs の checkVirtualHead）を PR 側の判定に写す:
//   申告の改変・削除（畳み込み以外）と PR が足した申告の連鎖違反（fork・孤児・重複・形式）は赤、
//   月別ファイルの直接編集による等式違反は互換期間（P2-4）のため notice（警告）のみ。段3で赤にする。
// - 月別ファイルを変える PR の作者が台帳 bot（mannschaft-ledger-bot[bot]）かの判定（段3で赤の条件に使う。今は警告文に含めるだけ）。
// PR head のコードは実行しない（本モジュールは信頼 base の checkout から読む）。依存パッケージなし。

import { LEDGER_DIR, LEDGER_INDEX_PATH, CHANGES_DIR, CHANGES_DIR_NAME, applyChanges, extractRows, readLedger } from './task-list-ledger.mjs';
import { checkVirtualHead } from './task-list-structure-check.mjs';

/** 台帳の畳み込み bot（GitHub App）の PR 作者 login。 */
export const LEDGER_BOT_LOGIN = 'mannschaft-ledger-bot[bot]';

/** PR（pulls.get の data か、その user）の作者が台帳 bot か。login の一致に加え、user.type があれば Bot であること。 */
export function isLedgerBotAuthor(pullOrUser) {
  const user = pullOrUser && typeof pullOrUser === 'object' && 'user' in pullOrUser ? pullOrUser.user : pullOrUser;
  if (!user || typeof user !== 'object') return false;
  if (user.login !== LEDGER_BOT_LOGIN) return false;
  return user.type === undefined || user.type === 'Bot';
}

const BLOB_FIELDS = '__typename ... on Blob { oid text isTruncated isBinary byteSize }';
/** 1参照1回で台帳を取る GraphQL クエリ（索引・docs/task-list/ 直下・その1段下＝changes/ 直下）。 */
export const LEDGER_TREE_QUERY = `query($owner: String!, $repo: String!, $commit: String!, $index: String!, $dir: String!) {
  repository(owner: $owner, name: $repo) {
    commit: object(expression: $commit) { __typename oid }
    index: object(expression: $index) { ${BLOB_FIELDS} }
    dir: object(expression: $dir) {
      __typename
      ... on Tree {
        entries {
          name type oid
          object {
            ${BLOB_FIELDS}
            ... on Tree { entries { name type oid object { ${BLOB_FIELDS} } } }
          }
        }
      }
    }
  }
}`;

/**
 * GraphQL で台帳を読む取得元。read(ref) は readLedger の結果（既定＝実効台帳）を返す。
 * @param {{ github: { graphql: Function, rest?: { git?: { getBlob?: Function } } }, owner: string, repo: string }} options
 */
export function createGraphqlLedgerSource({ github, owner, repo }) {
  if (!github || typeof github.graphql !== 'function') throw new TypeError('createGraphqlLedgerSource: github.graphql が要る');
  const blobs = new Map(); // oid -> text（参照をまたぐキャッシュ）
  const stats = { graphql: 0, blob: 0 };

  async function blobText(object, path) {
    if (!object || object.__typename !== 'Blob') return null;
    if (blobs.has(object.oid)) return blobs.get(object.oid);
    let text = object.text;
    if (object.isBinary || (text == null && !object.isTruncated)) throw new Error(path + ' の本文を取れない（バイナリまたは null）。不在とは扱わない');
    if (object.isTruncated) {
      if (path.startsWith(CHANGES_DIR + '/')) throw new Error(path + ' の本文が切られている（isTruncated）。申告は 16KiB 以下のはず');
      const getBlob = github.rest?.git?.getBlob;
      if (typeof getBlob !== 'function') throw new Error(path + ' の本文が切られている（isTruncated）のに blob API で取り直せない');
      stats.blob++;
      const response = await getBlob({ owner, repo, file_sha: object.oid });
      if (response?.data?.encoding !== 'base64' || typeof response.data.content !== 'string') throw new Error(path + ' の blob（' + object.oid + '）を base64 で取れない');
      text = Buffer.from(response.data.content, 'base64').toString('utf8');
    }
    if (typeof text !== 'string') throw new Error(path + ' の本文が文字列でない');
    blobs.set(object.oid, text);
    return text;
  }

  /** 1参照ぶんの台帳を1クエリで取り、メモリ上の reader にする。 */
  async function snapshot(ref) {
    if (typeof ref !== 'string' || ref === '') throw new Error('台帳を読む ref が空');
    stats.graphql++;
    let data;
    try {
      data = await github.graphql(LEDGER_TREE_QUERY, { owner, repo, commit: ref, index: ref + ':' + LEDGER_INDEX_PATH, dir: ref + ':' + LEDGER_DIR });
    } catch (error) {
      throw new Error('GraphQL で台帳を取得できなかった（不在とは扱わない）: ' + (error?.message ?? error), { cause: error });
    }
    const repository = data?.repository;
    if (!repository) throw new Error('GraphQL の応答に repository が無い');
    if (!repository.commit) throw new Error('ref を解決できない: ' + ref);
    const dirs = new Map(); // dir -> names | null
    const files = new Map(); // path -> text | null
    files.set(LEDGER_INDEX_PATH, await blobText(repository.index, LEDGER_INDEX_PATH));
    const top = repository.dir;
    if (top && top.__typename === 'Tree') {
      if (!Array.isArray(top.entries)) throw new Error(LEDGER_DIR + ' の entries を取れない');
      dirs.set(LEDGER_DIR, top.entries.map((e) => e.name));
      for (const entry of top.entries) {
        const path = LEDGER_DIR + '/' + entry.name;
        if (entry.type === 'blob') files.set(path, await blobText(entry.object, path));
        else if (entry.type === 'tree') {
          files.set(path, null);
          const sub = entry.object;
          if (!sub || !Array.isArray(sub.entries)) throw new Error(path + ' の entries を取れない');
          dirs.set(path, sub.entries.map((e) => e.name));
          // changes/ の直下だけを本文まで読む（それより深いものは readLedger が「ネスト」として赤にする）
          for (const child of sub.entries) {
            const childPath = path + '/' + child.name;
            files.set(childPath, child.type === 'blob' && entry.name === CHANGES_DIR_NAME ? await blobText(child.object, childPath) : null);
          }
        } else files.set(path, null);
      }
    } else dirs.set(LEDGER_DIR, null);
    return {
      commitOid: repository.commit.oid,
      reader: {
        listDir: (dir) => (dirs.has(dir) ? dirs.get(dir) : null),
        readFile: (path) => (files.has(path) ? files.get(path) : null),
      },
    };
  }

  return {
    stats,
    async read(ref, options) {
      const { reader } = await snapshot(ref);
      return readLedger(reader, options);
    },
  };
}

function monthFileMap(ledger) {
  return new Map((ledger.rawFiles ?? ledger.files).filter((f) => f.path.startsWith(LEDGER_DIR + '/') && f.content != null).map((f) => [f.path, f.content]));
}

/** 分岐点から head で生の月別ファイル（docs/task-list/*.md）が変わったパス。 */
export function changedMonthFiles(mergeBase, head) {
  const before = monthFileMap(mergeBase);
  const after = monthFileMap(head);
  return [...new Set([...before.keys(), ...after.keys()])].filter((p) => before.get(p) !== after.get(p)).sort();
}

/** reader が報告する申告の問題のうち、連鎖（fork・孤児・重複）以外＝申告ディレクトリの中身そのものの不正。 */
const CHAIN_KINDS = new Set(['fork', 'orphan', 'duplicate']);

/**
 * 仮想 head（最新 base の月別＋base の申告＋PR が追加した申告）の実効台帳。完了証拠の選定・検証に使う（古い枝が
 * 最新 base の行から作った申告は PR head 単体では孤児で未適用になるため、仮想 head で完了遷移を拾う）。
 */
export function virtualHeadLedger({ mergeBase, base, head }) {
  const mbPaths = new Set((mergeBase.changes?.entries ?? []).map((e) => e.path));
  const basePaths = new Set((base.changes?.entries ?? []).map((e) => e.path));
  const added = (head.changes?.entries ?? []).filter((e) => !mbPaths.has(e.path) && !basePaths.has(e.path));
  const applied = applyChanges(base.rawFiles ?? base.files, base.layout, [...(base.changes?.entries ?? []), ...added]);
  const rows = applied.files.flatMap((f) => (f.content == null ? [] : extractRows(f.content, f.path)));
  return { layout: base.layout, files: applied.files, rawFiles: base.rawFiles ?? base.files, rows, missing: [], changes: { entries: [...(base.changes?.entries ?? []), ...added], applied: applied.applied, problems: applied.problems } };
}

/**
 * PR の台帳変更（申告・月別ファイル）を判定する。引数は readLedger の結果（分岐点・base・head）と最新の PR（user を使う）。
 * - 申告の削除は「等式が成り立つ（正しい畳み込み）」かつ「作者が台帳 bot」のときだけ許す。それ以外は赤（change-deleted）。
 * - reader が報告した申告ディレクトリの不正（.md 以外・不正名・ネスト・サイズ・形式・ID 不一致等）は赤。
 * @returns {{ violations: string[], notices: string[], fold: boolean, monthFilesChanged: string[], botAuthor: boolean, addedChanges: string[], removedChanges: string[], virtualHead: object }}
 */
export function evaluateLedgerChanges({ mergeBase, base, head, pull }) {
  const result = checkVirtualHead({ mergeBase, base, head });
  const monthFilesChanged = changedMonthFiles(mergeBase, head);
  const botAuthor = isLedgerBotAuthor(pull);
  const author = pull?.user?.login ?? '(不明)';
  const violations = result.violations.map((v) => v.message);
  for (const p of head.changes?.problems ?? []) {
    if (CHAIN_KINDS.has(p.kind)) continue; // 連鎖は仮想マージ（checkVirtualHead）で判定する
    if (!violations.includes(p.message)) violations.push(p.message);
  }
  if (result.fold && !botAuthor) {
    violations.push(result.removedChanges.join(', ') + ': 申告の削除（change-deleted）: 申告を消してよいのは ' + LEDGER_BOT_LOGIN + ' の畳み込みだけ（作者 ' + author + '）。月別ファイルへの反映と申告の削除は台帳 bot に任せること。');
  }
  const notices = result.warnings.map((w) => w.message);
  if (monthFilesChanged.length && !botAuthor) {
    notices.push('月別ファイルを変える PR の作者 ' + author + ' は ' + LEDGER_BOT_LOGIN + ' でない（変更: ' + monthFilesChanged.join(', ')
      + '）。互換期間のため警告のみ。段3から月別ファイルの変更は台帳 bot の畳み込みに限る（行の追加・更新は node scripts/task-list-ledger.mjs add / update で申告を作る）。');
  }
  return {
    violations,
    notices,
    fold: result.fold && botAuthor,
    monthFilesChanged,
    botAuthor,
    addedChanges: result.addedChanges,
    removedChanges: result.removedChanges,
    virtualHead: virtualHeadLedger({ mergeBase, base, head }),
  };
}
