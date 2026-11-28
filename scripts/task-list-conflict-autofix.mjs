#!/usr/bin/env node
// 台帳 docs/task-list.md（新配置では docs/task-list/*.md も）の末尾追記衝突の自動修復（CMP-261124-1222 第2段・両配置対応）。
//
// main は GitHub merge queue（SQUASH）必須で、台帳に行を末尾追記する PR どうしが衝突すると queue から
// merge_conflict で外れ、auto-merge の予約も消える。GitHub のサーバ側マージは .gitattributes の merge=union を
// 見ないが、Actions 上のローカル git なら効く。そこで main への push ごとに（取りこぼしは30分毎の schedule で）、
// 「台帳だけで衝突している PR」を union で直して push し、auto-merge を予約し直す。
//
// 安全上の方針:
// - PR の head のコードは実行しない。git merge-tree --write-tree（作業木を使わないマージ）でマージ結果の tree を作り、
//   台帳の中身を main 側の（信頼済みの）scripts/task-list-structure-check.mjs で検査するだけ。
//   作業木に PR の内容を展開しないので、merge --abort に当たる後始末も不要（失敗時は何も書かない）。
// - union は .git/info/attributes に書く（PR 側の .gitattributes に依存しない。info/attributes が最優先）。
// - push は fast-forward のみ（force しない）。先に head が動いていれば拒否され、次回に回る。
// - 構造検査 NG（同じ行の両側編集で旧行と新行が両方残る等）は push せず PR にコメントして人手に回す。
// - 暴走防止: 同じ PR を bot が直したのが直近24時間で3回以上ならスキップし、コメントで知らせる。
// - 旧手順の PR（月別ファイル docs/task-list/YYYY-MM.md・legacy.md や旧単一ファイルの docs/task-list.md へ行を直接書く）は、
//   planChangePort で行の追加・更新を変更申告（docs/task-list/changes/）へ変換し、月別ファイル・索引は main の内容に戻した merge commit を
//   push する（段3 から PR ゲートは月別ファイルの直接編集を赤にするため。CMP-261124-1222 ④ AC-12）。衝突していなくても対象にする。
//
// 使い方: node scripts/task-list-conflict-autofix.mjs [--dry-run] [--repo owner/name]
//   GH_TOKEN（GitHub App の installation token）が要る。--dry-run は選定と判定だけを出し、git も書き込みもしない。
// 依存パッケージなし（gh / git はサブプロセス）。

import { spawnSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { checkTaskListStructure } from './task-list-structure-check.mjs';
import { randomInt } from 'node:crypto';
import { CHANGES_DIR, CHANGES_DIR_NAME, LEDGER_DIR, LEDGER_INDEX_PATH, changeRowHash, extractRows, ledgerRowMap, readLedger } from './task-list-ledger.mjs';
import { changeContent, changeFileName, rewriteLinks } from './task-list-ledger-cli.mjs';
import { checkVirtualHead } from './task-list-structure-check.mjs';

export const LEDGER_PATH = LEDGER_INDEX_PATH;
export const OPT_OUT_LABEL = 'no-ledger-autofix';
export const FIX_MARKER = '<!-- ledger-autofix:fixed -->';
export const LIMIT_MARKER = '<!-- ledger-autofix:limit -->';
export const PORT_MANUAL_MARKER_PREFIX = '<!-- ledger-autofix:port-manual head=';
export const STRUCTURE_NG_MARKER_PREFIX = '<!-- ledger-autofix:structure-ng head=';
/** 台帳の畳み込み bot（GitHub App）。GraphQL の author.login は "[bot]" なし、REST の user.login は "[bot]" 付き。 */
export const LEDGER_BOT_LOGINS = new Set(['mannschaft-ledger-bot', 'mannschaft-ledger-bot[bot]']);
/** 自動修復の起動理由。conflict: 台帳だけの衝突で queue から外れた（予約し直す）/ ledger-edit: 旧手順で台帳を直接編集した（予約はしない）。 */
export const TRIGGER_CONFLICT = 'conflict';
export const TRIGGER_LEDGER_EDIT = 'ledger-edit';
/** 申告の作り方の案内（コメント用）。 */
const CHANGE_CLI_GUIDANCE = '行の追加は `node scripts/task-list-ledger.mjs add "<7列行>"`、更新は `node scripts/task-list-ledger.mjs update <CMP-ID> --set <列名>=<値>` で申告（' + CHANGES_DIR + '/）を作り、月別ファイル・索引の直接編集は main の内容に戻すこと';
export const MAX_FIXES_PER_DAY = 3;
export const HEAD_REFLECT_WAIT_MS = 10_000;
export const HEAD_REFLECT_MAX_WAITS = 6;
/** bot の修復 commit のメッセージに必ず入れる固定印（trailer）。24時間の上限はこれと bot の署名で数える。 */
export const FIX_COMMIT_TRAILER = 'Ledger-Autofix: task-list-conflict-autofix';
/** 自動修復の対象にする queue 脱落理由（RemovedFromMergeQueueEvent.reason。GraphQL では String で、実測値は merge_conflict / failed_checks / manual / merged）。 */
export const CONFLICT_REMOVAL_REASON = 'merge_conflict';
const DAY_MS = 24 * 3600 * 1000;
/** .git/info/attributes に書く union 指定（索引＝旧配置の台帳と、新配置の月別・legacy ファイル）。 */
const UNION_ATTRIBUTE_LINES = [LEDGER_PATH + ' merge=union', LEDGER_DIR + '/*.md merge=union'];
const LEDGER_FILE_IN_DIR = new RegExp('^' + LEDGER_DIR.replace(/[.*+?^$(){}|[\]\\/-]/g, '\\$&') + '/[^/]+\\.md$');

/** 衝突を union で直してよい台帳のパスか（docs/task-list.md と docs/task-list/ 直下の *.md）。 */
export function isLedgerPath(path) {
  return path === LEDGER_PATH || LEDGER_FILE_IN_DIR.test(path);
}

// ---------------------------------------------------------------------------
// 選定
// ---------------------------------------------------------------------------

/** timeline の最新の merge queue 関連イベント（無ければ null）。 */
function lastQueueEvent(pr) {
  const nodes = pr.timelineItems?.nodes ?? [];
  return nodes.length ? nodes[nodes.length - 1] : null;
}

/** 索引（docs/task-list.md）か、月別・legacy ファイル（docs/task-list/ 直下の *.md。changes/ は含まない）か。 */
export function isLedgerEditPath(path) {
  return path === LEDGER_PATH || LEDGER_FILE_IN_DIR.test(path);
}

/** PR が台帳（索引・月別ファイル）を直接編集している（かもしれない）か。files の一覧が欠けている（100件超）なら true（git で確かめる）。 */
function touchesLedgerFiles(pr) {
  const files = pr.files;
  if (!files || !Array.isArray(files.nodes)) return false;
  if (Number.isInteger(files.totalCount) && files.totalCount > files.nodes.length) return true;
  return files.nodes.some((f) => isLedgerEditPath(f.path));
}

/**
 * 自動修復の対象 PR を選ぶ。共通の条件は open・base=main・同一リポジトリ（fork 除外）・no-ledger-autofix ラベルなし・作者が台帳 bot でない
 * （畳み込み PR は bot 自身が最新 main から作り直す）。そのうえで次のどちらか:
 * - conflict: mergeable=CONFLICTING で、マージ待ちの意思（auto-merge 予約済み、または最新の queue イベントが衝突による脱落
 *   reason=merge_conflict）があるもの。手動で queue から外した（reason=manual 等）PR は、予約が無ければ対象にしない。
 * - ledger-edit（段3）: head が月別ファイル・索引を変えている（mergeable・予約に関係なく）。旧手順の直接編集を申告へ変換する。
 * 返す PR には autofixTrigger（conflict / ledger-edit）を付ける。
 */
export function selectCandidates(prs) {
  const candidates = [];
  const skipped = [];
  for (const pr of prs) {
    const skip = (reason) => skipped.push({ number: pr.number, reason });
    if (pr.baseRefName !== 'main') { skip('base が main ではない'); continue; }
    if (pr.isCrossRepository) { skip('fork の PR（head が別リポジトリ）'); continue; }
    if ((pr.labels?.nodes ?? []).some((l) => l.name === OPT_OUT_LABEL)) { skip(OPT_OUT_LABEL + ' ラベル付き'); continue; }
    if (LEDGER_BOT_LOGINS.has(pr.author?.login)) { skip('台帳 bot の PR（畳み込み PR は bot 自身が作り直す）'); continue; }
    if (pr.mergeable !== 'CONFLICTING') {
      if (touchesLedgerFiles(pr)) { candidates.push({ ...pr, autofixTrigger: TRIGGER_LEDGER_EDIT }); continue; }
      skip('mergeable=' + pr.mergeable);
      continue;
    }
    const last = lastQueueEvent(pr);
    const removedByConflict = last?.__typename === 'RemovedFromMergeQueueEvent' && last.reason === CONFLICT_REMOVAL_REASON;
    if (pr.autoMergeRequest == null && !removedByConflict) {
      if (touchesLedgerFiles(pr)) { candidates.push({ ...pr, autofixTrigger: TRIGGER_LEDGER_EDIT }); continue; }
      skip(last?.__typename === 'RemovedFromMergeQueueEvent'
        ? 'auto-merge 予約なし・queue 脱落理由が ' + last.reason + '（merge_conflict 以外は人の判断で外したものとして扱う）'
        : 'auto-merge 予約なし・queue 脱落イベントなし（マージ待ちの意思が無い）');
      continue;
    }
    candidates.push({ ...pr, autofixTrigger: TRIGGER_CONFLICT });
  }
  return { candidates, skipped };
}

/**
 * mergeable が UNKNOWN（GitHub が遅延計算中。main への push 直後はほぼ全 PR がこうなる）で、
 * それ以外の条件では対象になりうる PR の番号。main() はこれが空になるまで少し待って取り直す。
 */
export function pendingMergeability(prs) {
  // 台帳の直接編集で対象になる PR は mergeable に依らないので待たない（衝突判定のための待ちだけ）
  const unknown = prs.filter((pr) => pr.mergeable === 'UNKNOWN' && !touchesLedgerFiles(pr)).map((pr) => ({ ...pr, mergeable: 'CONFLICTING' }));
  return selectCandidates(unknown).candidates.map((pr) => pr.number);
}

function recentComments(comments, now, marker) {
  return comments.filter((c) => (c.body ?? '').includes(marker) && now - new Date(c.createdAt) < DAY_MS);
}

/**
 * bot がこの PR を直近24時間に直した回数。コメントではなく PR の commit 履歴で数える
 * （コメントは消せる・ページ外に流れる）。bot の修復 commit = メッセージに固定印があるか、作者/コミッタが bot。
 */
export function countRecentFixes(pr, now, { botEmail } = {}) {
  return (pr.commits?.nodes ?? []).filter(({ commit }) => {
    if (!commit || now - new Date(commit.committedDate) >= DAY_MS) return false;
    const byBot = botEmail != null && (commit.author?.email === botEmail || commit.committer?.email === botEmail);
    return byBot || (commit.message ?? '').includes(FIX_COMMIT_TRAILER);
  }).length;
}

/** push 拒否が「GitHub App に workflows 権限が無い」ものか（workflow を変える merge を push したとき）。 */
export function isWorkflowPermissionRejection(reason) {
  const text = String(reason ?? '');
  return /without\s+`?workflows`?\s+permission/i.test(text)
    || (/refusing to allow/i.test(text) && /workflow/i.test(text) && /permission/i.test(text));
}

// ---------------------------------------------------------------------------
// 旧手順の PR の変更申告への変換（planChangePort。CMP-261124-1222 ④ 段3 / AC-12）
// ---------------------------------------------------------------------------

async function readTreeLedger(gitOps, treeish) {
  // 既定（実効台帳）と rawFiles（生の月別）を両方使う
  return readLedger({ listDir: (dir) => gitOps.listDir(treeish, dir), readFile: (path) => gitOps.readFile(treeish, path) }, { requireRows: false });
}

/** 月別・legacy ファイル（docs/task-list/ 直下の *.md）の生の本文（パス → 本文）。索引は含まない。 */
function monthFilesOf(ledger) {
  return new Map(ledger.rawFiles.filter((f) => f.path !== LEDGER_PATH && f.content != null).map((f) => [f.path, f.content]));
}

/** 表の行以外の行（末尾の空行は除く）。 */
function nonRowLinesOf(content, path) {
  const rowLines = new Set(extractRows(content, path).map((r) => r.lineNumber));
  const lines = content.split('\n').map((l) => (l.endsWith('\r') ? l.slice(0, -1) : l)).filter((_, i) => !rowLines.has(i + 1));
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  return lines;
}

/** ファイル群（パス → 本文）の行を ID → 行 にする。重複 ID は problems に入れる。 */
function rowsOfFiles(files, what, problems, transform = (l) => l) {
  const map = new Map();
  for (const [path, content] of files) {
    for (const r of extractRows(content, path)) {
      if (map.has(r.id)) problems.push(what + ' に重複 ID がある: ' + r.id);
      else map.set(r.id, transform(r.line));
    }
  }
  return map;
}

/**
 * 旧手順の PR（月別ファイル・legacy.md、または旧単一ファイル docs/task-list.md へ行を直接書く）の行追加・行更新を、
 * 変更申告（docs/task-list/changes/）へ変換する計画を立てる。月別ファイル・索引は main の内容に戻す（旧単一ファイル配置の PR は索引も main に戻す。
 * 新配置の PR の索引の前置きの変更は規約文書の変更として残す）。
 * - 分岐点（merge-base(head, main)）の生の行 → head の生の行 の差分を PR の編集とみなし、base は分岐点の実効行から計算する
 *   （分岐点で未畳み込みの申告がある行を直接編集していれば、どの版を直したのか決められないので人手）。
 * - 三者比較（P2-6）: main の実効行が分岐点と同じなら申告に変換 / main が既に PR と同じ結果なら申告不要 / 三者相違なら競合として人手。
 * - 行削除・月別ファイルの行以外（見出し・表ヘッダ）の変更・旧単一ファイルの前置きの変更は変換できない（人手）。
 * @returns {Promise<{ kind: 'none' } | { kind: 'unportable', reasons: string[] } | { kind: 'ported', tree: string, added: string[], updated: string[], unchanged: string[], changeFiles: string[] }>}
 */
export async function planChangePort({ gitOps, headOid, mainOid, mergedTree, now = new Date(), random = () => String(randomInt(0, 1000000)).padStart(6, '0') }) {
  if (!gitOps.listDir || !gitOps.mergeBase || !gitOps.writeTree) return { kind: 'none' };
  const mainLedger = await readTreeLedger(gitOps, mainOid);
  if (mainLedger.layout !== 'split') return { kind: 'none' };
  const headLedger = await readTreeLedger(gitOps, headOid);
  const mbOid = gitOps.mergeBase(headOid, mainOid);
  if (!mbOid) return { kind: 'unportable', reasons: ['分岐点（merge-base）が無い（判別不能）'] };
  const mbLedger = await readTreeLedger(gitOps, mbOid);
  const reasons = [];
  let mbRows;
  let headRows;
  const restore = {}; // パス → main の本文（null は削除）
  if (headLedger.layout === 'single') {
    // 旧単一ファイル配置の PR（月別移行より前に作った PR）
    if (mbLedger.layout !== 'single') return { kind: 'unportable', reasons: ['分岐点が旧配置でない（判別不能）'] };
    const baseText = mbLedger.rawFiles[0].content;
    const headText = headLedger.rawFiles[0].content;
    if (baseText == null || headText == null) return { kind: 'unportable', reasons: ['分岐点または head の ' + LEDGER_PATH + ' を読めない'] };
    if (baseText === headText) return { kind: 'none' };
    if (JSON.stringify(nonRowLinesOf(baseText, LEDGER_PATH)) !== JSON.stringify(nonRowLinesOf(headText, LEDGER_PATH))) reasons.push('前置き（表の行以外）の変更は変換できない');
    mbRows = rowsOfFiles([[LEDGER_PATH, baseText]], '分岐点の旧配置', reasons, rewriteLinks);
    headRows = rowsOfFiles([[LEDGER_PATH, headText]], 'PR の head の旧配置', reasons, rewriteLinks);
    restore[LEDGER_PATH] = mainLedger.rawFiles[0].content;
  } else {
    if (mbLedger.layout !== 'split') return { kind: 'unportable', reasons: ['分岐点が旧配置で head が新配置（判別不能）'] };
    const mbMonths = monthFilesOf(mbLedger);
    const headMonths = monthFilesOf(headLedger);
    const changed = [...new Set([...mbMonths.keys(), ...headMonths.keys()])].filter((p) => mbMonths.get(p) !== headMonths.get(p)).sort();
    if (!changed.length) return { kind: 'none' };
    for (const path of changed) {
      if (!headMonths.has(path)) { reasons.push(path + ' の削除は変換できない'); continue; }
      const after = nonRowLinesOf(headMonths.get(path), path).join('\n');
      const before = mbMonths.has(path) ? nonRowLinesOf(mbMonths.get(path), path).join('\n') : null;
      // 新しい月のファイルは見出し・表ヘッダの形を問わない（申告の add が雛形で作り直す）
      if (before != null && after !== before) reasons.push(path + ' の行以外（見出し・表ヘッダ・前置き）の変更は変換できない');
    }
    mbRows = rowsOfFiles(mbMonths, '分岐点の月別ファイル', reasons);
    headRows = rowsOfFiles(headMonths, 'PR の head の月別ファイル', reasons);
  }
  if (reasons.length) return { kind: 'unportable', reasons };
  // 月別ファイルは main に戻す（main に無い月別ファイルは消す）
  const mainMonths = monthFilesOf(mainLedger);
  for (const name of gitOps.listDir(mergedTree, LEDGER_DIR) ?? []) {
    const path = LEDGER_DIR + '/' + name;
    if (name !== CHANGES_DIR_NAME && LEDGER_FILE_IN_DIR.test(path) && !mainMonths.has(path)) restore[path] = null;
  }
  for (const [path, content] of mainMonths) restore[path] = content;

  const mbEffective = ledgerRowMap(mbLedger);
  const mainEffective = ledgerRowMap(mainLedger);
  const result = { added: [], updated: [], unchanged: [], deleted: [] };
  const plans = [];
  for (const id of mbRows.keys()) if (!headRows.has(id)) result.deleted.push(id);
  if (result.deleted.length) reasons.push('行の削除は変換できない: ' + result.deleted.join(', '));
  for (const [id, line] of headRows) {
    const before = mbRows.get(id);
    if (before === line) continue;
    const current = mainEffective.get(id);
    if (before !== undefined) {
      const from = headLedger.layout === 'single' ? before : mbEffective.get(id);
      if (from !== before) { reasons.push(id + ' は分岐点で未畳み込みの申告がある行を直接編集している（どの版を直したのか決められない）'); continue; }
      if (current === line) { result.unchanged.push(id); continue; } // main が既に同じ結果（申告不要）
      if (current !== from) { reasons.push('競合: ' + id + ' は main 側でも更新されている（分岐点・main・PR の3者が異なる）。手で統合すること'); continue; }
      plans.push({ op: 'update', id, base: changeRowHash(from), line });
      result.updated.push(id);
    } else {
      if (current === line) { result.unchanged.push(id); continue; }
      if (current !== undefined) { reasons.push('競合: ' + id + ' は main に別の内容で既にある（分岐点・main・PR の3者が異なる）。手で統合すること'); continue; }
      plans.push({ op: 'add', id, base: 'none', line });
      result.added.push(id);
    }
  }
  if (reasons.length) return { kind: 'unportable', reasons };

  const files = { ...restore };
  const taken = new Set(gitOps.listDir(mergedTree, CHANGES_DIR) ?? []);
  const changeFiles = [];
  for (const plan of plans) {
    let name;
    for (let i = 0; i < 20 && (name == null || taken.has(name)); i++) name = changeFileName(plan.id, now, random());
    if (taken.has(name)) return { kind: 'unportable', reasons: ['申告のファイル名を決められない（乱数の衝突）: ' + plan.id] };
    taken.add(name);
    let content;
    try {
      content = changeContent(plan.op, plan.id, plan.base, plan.line);
    } catch (error) {
      return { kind: 'unportable', reasons: [plan.id + ': ' + error.message] };
    }
    files[CHANGES_DIR + '/' + name] = content;
    changeFiles.push(CHANGES_DIR + '/' + name);
  }
  const tree = gitOps.writeTree(mergedTree, files);
  // 変換後の台帳を読み直し、PR の意図した行が実効台帳に入ったこと・申告の問題が無いこと・施行後の等式を満たすことを確かめる
  const after = await readTreeLedger(gitOps, tree);
  const afterRows = ledgerRowMap(after);
  const check = [...after.changes.problems.map((p) => p.message)];
  for (const [id, line] of headRows) if (mbRows.get(id) !== line && afterRows.get(id) !== line) check.push(id + ' が変換後の実効台帳で PR の行にならない');
  for (const v of checkVirtualHead({ mergeBase: mainLedger, base: mainLedger, head: after }).violations) check.push(v.message);
  if (check.length) return { kind: 'unportable', reasons: ['変換後の検査に失敗: ' + check.slice(0, 5).join(' / ')] };
  return { kind: 'ported', tree, ...result, changeFiles };
}

// ---------------------------------------------------------------------------
// 1件の処理
// ---------------------------------------------------------------------------

function formatErrors(errors) {
  return errors.map((e) => '- ' + e).join('\n');
}

/**
 * 1件の PR を修復する。deps: { gitOps, ghOps, now, log, sleep, checkStructure }。
 * @returns {{ outcome: string, reason?: string, mergeCommit?: string }}
 */
export async function processPullRequest(pr, deps) {
  const { gitOps, ghOps, now, log, sleep, checkStructure, botEmail } = deps;
  const reReserve = pr.autofixTrigger !== TRIGGER_LEDGER_EDIT; // 旧手順の直接編集を理由に選んだ PR は、マージ待ちの意思が分からないので予約しない
  const tag = '#' + pr.number + '（' + pr.headRefName + '）';
  let comments = null;
  const allComments = () => (comments ??= ghOps.listComments(pr));
  /** コメントを投稿し、失敗は握りつぶさず結果に載せる。 */
  const post = (body) => {
    const r = ghOps.comment(pr.number, body) ?? { ok: true };
    if (!r.ok) log(tag + ': コメント投稿に失敗: ' + r.error);
    return r.ok ? null : r.error;
  };

  // 暴走防止
  const fixes = countRecentFixes(pr, now, { botEmail });
  if (fixes >= MAX_FIXES_PER_DAY) {
    let commentError = null;
    if (recentComments(allComments(), now, LIMIT_MARKER).length === 0) {
      commentError = post(LIMIT_MARKER + '\n'
        + '台帳（' + LEDGER_PATH + '）衝突の自動修復は、この PR に対して直近24時間で既に ' + fixes + ' 回行ったため、上限（' + MAX_FIXES_PER_DAY + '回）に達しました。'
        + 'これ以上は自動で直しません。main を取り込んで手で解消し、`gh pr merge ' + pr.number + ' --squash` で予約し直してください。');
    }
    log(tag + ': スキップ（直近24時間の自動修復が ' + fixes + ' 回で上限）');
    return { outcome: 'skip-limit', reason: '直近24時間で ' + fixes + ' 回修復済み', commentError };
  }

  const headOid = gitOps.fetchHead(pr);
  if (headOid !== pr.headRefOid) {
    log(tag + ': スキップ（取得中に head が動いた: ' + pr.headRefOid + ' → ' + headOid + '。次回に回す）');
    return { outcome: 'skip-head-moved' };
  }
  const mainOid = gitOps.fetchMain();

  let merged = gitOps.mergeTree(headOid, mainOid);
  if (!merged.ok) {
    gitOps.abortMerge();
    if (merged.error) {
      log(tag + ': スキップ（merge 失敗: ' + merged.error + '）');
      return { outcome: 'skip-merge-failed', reason: merged.error };
    }
    const others = merged.conflicts.filter((p) => !isLedgerPath(p));
    if (others.length) {
      const reason = '台帳以外にも衝突: ' + others.join(', ');
      log(tag + ': スキップ（' + reason + '）');
      return { outcome: 'skip-conflict-outside-ledger', reason };
    }
  }

  // 旧手順の PR（月別ファイル・legacy.md や旧単一ファイルへ行を直接書く）は、行の追加・更新を変更申告へ変換し、月別ファイル・索引は
  // main に戻す（段3 から PR ゲートは直接編集を赤にする）。衝突の有無（union で綺麗にマージされてしまう場合を含む）によらず先に判定する。
  {
    let port;
    try {
      port = await planChangePort({ gitOps, headOid, mainOid, mergedTree: merged.tree, now, random: deps.random });
    } catch (error) {
      log(tag + ': スキップ（申告への変換計画で失敗: ' + error.message + '）');
      return { outcome: 'skip-merge-failed', reason: error.message };
    }
    if (port.kind === 'unportable') {
      const marker = PORT_MANUAL_MARKER_PREFIX + headOid + ' -->';
      let commentError = null;
      if (!allComments().some((c) => (c.body ?? '').includes(marker))) {
        commentError = post(marker + '\n'
          + 'この PR は台帳の月別ファイル（' + LEDGER_DIR + '/YYYY-MM.md・legacy.md）または索引（' + LEDGER_PATH + '）を直接編集しています。'
          + '台帳の変更は申告（' + CHANGES_DIR + '/）で行う決まりで、PR ゲートは直接編集を赤にします。自動で申告へ変換できない差分のため、手で直してください: '
          + CHANGE_CLI_GUIDANCE + '。\n\n' + formatErrors(port.reasons));
      }
      log(tag + ': スキップ（申告へ自動変換できない: ' + port.reasons.join(' / ') + '）');
      return { outcome: 'skip-port-unportable', reason: port.reasons.join(' / '), commentError };
    }
    if (port.kind === 'ported') merged = { ok: true, tree: port.tree, conflicts: [], ported: port };
    else if (pr.autofixTrigger === TRIGGER_LEDGER_EDIT) {
      // 台帳の直接編集を理由に選んだが、変換するもの（月別ファイルの行の変更）が無い（索引の前置きだけの変更等）。push しない。
      log(tag + ': スキップ（月別ファイルの直接編集が無く、変換するものが無い）');
      return { outcome: 'skip-nothing-to-port' };
    }
  }
  if (!merged.ok) {
    const reason = '台帳の衝突が union でも解消しなかった';
    log(tag + ': スキップ（' + reason + '）');
    return { outcome: 'skip-conflict-outside-ledger', reason };
  }

  // マージ結果の tree から台帳を両配置対応で読む（新配置なら月別・legacy の全ファイルを横断して検査する）。
  let ledger;
  try {
    ledger = await readLedger({
      listDir: (dir) => (gitOps.listDir ? gitOps.listDir(merged.tree, dir) : null),
      readFile: (path) => gitOps.readFile(merged.tree, path),
    }, { requireRows: false });
  } catch (error) {
    log(tag + ': スキップ（マージ結果の台帳を読めない: ' + error.message + '）');
    return { outcome: 'skip-merge-failed', reason: error.message };
  }
  const check = checkStructure(ledger);
  if (!check.ok) {
    const marker = STRUCTURE_NG_MARKER_PREFIX + headOid + ' -->';
    let commentError = null;
    if (!allComments().some((c) => (c.body ?? '').includes(marker))) {
      commentError = post(marker + '\n'
        + '台帳（' + LEDGER_PATH + '）の衝突を union で自動解消しようとしましたが、解消後の台帳が構造検査に合格しないため push しませんでした。'
        + '同じ行を PR と main の両方で編集した場合、union は旧行と新行を両方残します。人手で main を取り込んで解消してください。\n\n'
        + formatErrors(check.errors));
    }
    log(tag + ': 構造検査 NG のため push しない（' + check.errors.length + ' 件）');
    return { outcome: 'structure-ng', reason: check.errors.join(' / '), commentError };
  }

  const message = 'Merge origin/main into ' + pr.headRefName + '\n\n'
    + (merged.ported
      ? '台帳の直接編集（行の追加 ' + merged.ported.added.length + ' 件・更新 ' + merged.ported.updated.length + ' 件）を変更申告へ自動移植し、月別ファイル・索引を main の内容に戻した（task-list-conflict-autofix の planChangePort）。\n\n'
      : '台帳 ' + LEDGER_PATH + ' の末尾追記衝突を merge=union で自動解消した（task-list-conflict-autofix）。\n\n') + FIX_COMMIT_TRAILER;
  const mergeCommit = gitOps.commitMerge(merged.tree, headOid, mainOid, message);
  const pushed = gitOps.push(mergeCommit, pr.headRefName);
  if (!pushed.ok) {
    if (isWorkflowPermissionRejection(pushed.reason)) {
      log(tag + ': スキップ（App に workflows 権限が無く、workflow を変える merge を push できない: ' + pushed.reason + '）');
      return { outcome: 'skip-workflow-permission', reason: pushed.reason };
    }
    log(tag + ': push 拒否（' + pushed.reason + '）。次回に回す');
    return { outcome: 'skip-push-rejected', reason: pushed.reason };
  }
  log(tag + ': push 済み ' + mergeCommit);

  let autoMergeNote = '';
  let outcome = 'fixed';
  if (!reReserve) {
    autoMergeNote = 'auto-merge は予約していません（マージ待ちの意思が分からない PR のため）。';
  } else if (pr.autoMergeRequest == null) {
    let result = { ok: false, error: '未実行' };
    let failures = 0;
    let reflectWaits = 0;
    for (;;) {
      // expectedHeadOid=作成した merge commit。push 後に誰かが head を進めていたら予約しない（未検証の head を予約しない）。
      result = ghOps.enableAutoMerge(pr, mergeCommit);
      if (result.ok) break;
      if (result.headMismatch) {
        // push 直後は PR head の反映が遅れ、push 前の head（headOid）が返ることがある。反映待ちとして再試行する。
        // push 前 head でも作った commit でもない第三の commit のときだけ headMismatch として扱う。
        if (result.current === headOid) {
          if (reflectWaits >= HEAD_REFLECT_MAX_WAITS) {
            // 第三の commit ではない。反映待ちが上限に達しただけなので、headMismatch とは別の失敗として扱う。
            result = { ok: false, headNotReflected: true, error: 'PR head が ' + HEAD_REFLECT_MAX_WAITS + ' 回待っても push 前の ' + headOid + ' のまま（作成した ' + mergeCommit + ' が反映されない）' };
            break;
          }
          reflectWaits++;
          log(tag + ': PR head が push 前のまま（反映待ち ' + reflectWaits + '/' + HEAD_REFLECT_MAX_WAITS + '）');
          await sleep(HEAD_REFLECT_WAIT_MS);
          continue;
        }
        break;
      }
      failures++;
      log(tag + ': auto-merge 予約失敗（' + failures + '回目）: ' + result.error);
      if (failures >= 3) break;
      await sleep(10_000);
    }
    if (result.ok) {
      autoMergeNote = 'auto-merge（SQUASH）を予約し直しました。必須チェックが緑になると自動で merge queue へ入ります。';
    } else if (result.headNotReflected) {
      outcome = 'fixed-head-not-reflected';
      log(tag + ': auto-merge を予約できなかった（反映待ちの上限: ' + result.error + '）');
      autoMergeNote = '**push 後の head 反映を確認できず、auto-merge を予約できませんでした**（' + result.error + '。別の commit が積まれたわけではありません）。head が `' + mergeCommit + '` になっていることを確かめてから `gh pr merge ' + pr.number + ' --squash` で予約してください。';
    } else if (result.headMismatch) {
      outcome = 'fixed-head-moved';
      log(tag + ': push 後に head が ' + mergeCommit + ' から動いたため auto-merge を予約し直さない（' + result.error + '）');
      autoMergeNote = '**push 後に head が別の commit へ進んだため、auto-merge は予約し直していません**（' + result.error + '）。内容を確かめてから `gh pr merge ' + pr.number + ' --squash` で予約してください。';
    } else {
      outcome = 'fixed-automerge-failed';
      autoMergeNote = '**auto-merge の予約し直しに失敗しました**（' + result.error + '）。`gh pr merge ' + pr.number + ' --squash` で予約してください。';
    }
  } else {
    autoMergeNote = 'auto-merge は予約済みのままです。';
  }
  const commentError = post(FIX_MARKER + '\n'
    + (merged.ported
      ? '台帳の月別ファイル・索引への直接編集（旧手順）を変更申告（' + CHANGES_DIR + '/）へ自動移植し（追加: ' + (merged.ported.added.join(', ') || 'なし') + ' / 更新: ' + (merged.ported.updated.join(', ') || 'なし')
        + (merged.ported.unchanged.length ? ' / main に同じ内容があり申告不要: ' + merged.ported.unchanged.join(', ') : '') + '）、月別ファイル・索引を main の内容に戻し、'
        + '今後は ' + CHANGE_CLI_GUIDANCE.replace(/、月別ファイル.*$/, '') + '。'
      : '台帳（' + LEDGER_PATH + '）だけの衝突を merge=union で自動解消し、')
    + 'main を取り込む merge commit `' + mergeCommit + '` を push しました'
    + '（取り込んだ main: `' + mainOid + '`）。' + autoMergeNote + '\n\n'
    + '自動修復を止めたいときは `' + OPT_OUT_LABEL + '` ラベルを付けてください（同じ PR への自動修復は24時間に' + MAX_FIXES_PER_DAY + '回まで）。');
  return { outcome, mergeCommit, commentError };
}

// ---------------------------------------------------------------------------
// git / gh の実体
// ---------------------------------------------------------------------------

/** cwd で git を走らせる関数を作る。返り値は { code, stdout, stderr }。 */
export function createGit({ cwd, env = {} }) {
  return (args, { input, env: extraEnv } = {}) => {
    const r = spawnSync('git', args, { cwd, encoding: 'utf8', input, env: { ...process.env, ...env, ...extraEnv }, maxBuffer: 256 * 1024 * 1024 });
    return { code: r.status, stdout: r.stdout ?? '', stderr: (r.stderr ?? '') + (r.error ? String(r.error) : '') };
  };
}

function must(result, what) {
  if (result.code !== 0) throw new Error(what + ' に失敗: ' + result.stderr.trim());
  return result.stdout.trim();
}

/** .git/info/attributes（worktree 共通）に台帳（索引と docs/task-list/*.md）の merge=union を書く。既にある行は足さない。 */
export function ensureUnionAttribute(git) {
  const commonDir = must(git(['rev-parse', '--path-format=absolute', '--git-common-dir']), 'git-common-dir の取得');
  const infoDir = join(commonDir, 'info');
  const file = join(infoDir, 'attributes');
  mkdirSync(infoDir, { recursive: true });
  const current = existsSync(file) ? readFileSync(file, 'utf8') : '';
  const present = new Set(current.split(/\r?\n/).map((line) => line.trim()));
  const missing = UNION_ATTRIBUTE_LINES.filter((line) => !present.has(line));
  if (!missing.length) return;
  appendFileSync(file, (current && !current.endsWith('\n') ? '\n' : '') + missing.join('\n') + '\n');
}

function assertBranchName(name) {
  if (typeof name !== 'string' || !name || name.startsWith('-') || /[\s~^:?*\[\\]|\.\.|@\{/.test(name)) {
    throw new Error('不正なブランチ名: ' + name);
  }
}

export function createGitOps({ git }) {
  return {
    fetchMain() {
      must(git(['fetch', '--no-tags', '--quiet', 'origin', '+refs/heads/main:refs/remotes/origin/main']), 'main の fetch');
      return must(git(['rev-parse', 'refs/remotes/origin/main']), 'main の rev-parse');
    },
    fetchHead(pr) {
      assertBranchName(pr.headRefName);
      const ref = 'refs/remotes/origin/' + pr.headRefName;
      must(git(['fetch', '--no-tags', '--quiet', 'origin', '+refs/heads/' + pr.headRefName + ':' + ref]), pr.headRefName + ' の fetch');
      return must(git(['rev-parse', ref]), pr.headRefName + ' の rev-parse');
    },
    /** 作業木を使わずにマージする（exit 0: 衝突なし / 1: 衝突あり / それ以外: 失敗）。 */
    mergeTree(headOid, mainOid) {
      const r = git(['merge-tree', '--write-tree', '--name-only', '--no-messages', headOid, mainOid]);
      const lines = r.stdout.split('\n').map((l) => l.trim()).filter(Boolean);
      if (r.code === 0) return { ok: true, tree: lines[0], conflicts: [] };
      if (r.code === 1 && lines.length) return { ok: false, tree: lines[0], conflicts: [...new Set(lines.slice(1))] };
      return { ok: false, conflicts: [], error: (r.stderr || r.stdout).trim() || 'merge-tree exit ' + r.code };
    },
    /** merge-tree は作業木・index・ref に何も書かないので、取り消すものは無い（merge --abort 相当は不要）。 */
    abortMerge() {},
    readFile(tree, path) {
      const r = git(['cat-file', 'blob', tree + ':' + path]);
      return r.code === 0 ? r.stdout : null;
    },
    /** tree 内のディレクトリ直下の名前（無ければ null）。git の失敗は例外（不在と混同しない）。 */
    listDir(tree, dir) {
      const self = git(['ls-tree', '-z', tree, '--', dir]);
      if (self.code !== 0) throw new Error(dir + ' の ls-tree に失敗: ' + self.stderr.trim());
      if (!/^\d+ tree /.test(self.stdout)) return null;
      const r = git(['ls-tree', '-z', '--name-only', tree, '--', dir + '/']);
      if (r.code !== 0) throw new Error(dir + '/ の ls-tree に失敗: ' + r.stderr.trim());
      return r.stdout.split('\0').filter(Boolean).map((p) => p.slice(dir.length + 1));
    },
    /** 2つの commit の分岐点（無ければ null）。 */
    mergeBase(a, b) {
      const r = git(['merge-base', a, b]);
      return r.code === 0 ? r.stdout.trim() : null;
    },
    /** baseTree をもとに、files（パス → 本文。null はそのパスを消す）を差し替えた tree を作る（作業木・本物の index は使わない）。 */
    writeTree(baseTree, files) {
      const dir = mkdtempSync(join(tmpdir(), 'ledger-index-'));
      const env = { GIT_INDEX_FILE: join(dir, 'index') };
      try {
        must(git(['read-tree', baseTree], { env }), 'read-tree');
        for (const [path, text] of Object.entries(files)) {
          if (text == null) {
            must(git(['update-index', '--force-remove', '--', path], { env }), 'update-index --force-remove ' + path);
            continue;
          }
          const oid = must(git(['hash-object', '-w', '--stdin'], { input: text }), 'hash-object');
          must(git(['update-index', '--add', '--cacheinfo', '100644,' + oid + ',' + path], { env }), 'update-index ' + path);
        }
        return must(git(['write-tree'], { env }), 'write-tree');
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    },
    changedPaths(fromOid, toTree) {
      return must(git(['diff-tree', '-r', '--name-only', fromOid, toTree]), '差分の取得').split('\n').map((l) => l.trim()).filter(Boolean);
    },
    commitMerge(tree, headOid, mainOid, message) {
      return must(git(['commit-tree', tree, '-p', headOid, '-p', mainOid, '-m', message]), 'merge commit の作成');
    },
    /** fast-forward のみ（force しない）。 */
    push(commit, branch) {
      assertBranchName(branch);
      const r = git(['push', '--quiet', 'origin', commit + ':refs/heads/' + branch]);
      return r.code === 0 ? { ok: true } : { ok: false, reason: r.stderr.trim() || 'push exit ' + r.code };
    },
  };
}

const PR_QUERY = 'query($owner:String!,$name:String!,$cursor:String){repository(owner:$owner,name:$name){pullRequests(states:OPEN,baseRefName:"main",first:50,after:$cursor){pageInfo{hasNextPage endCursor} nodes{'
  + 'id number baseRefName headRefName headRefOid isCrossRepository mergeable author{login} files(first:100){totalCount nodes{path}} '
  + 'labels(first:30){nodes{name}} autoMergeRequest{enabledAt} '
  + 'timelineItems(last:1,itemTypes:[ADDED_TO_MERGE_QUEUE_EVENT,REMOVED_FROM_MERGE_QUEUE_EVENT]){nodes{__typename ... on RemovedFromMergeQueueEvent{reason createdAt}}} '
  + 'commits(last:100){nodes{commit{oid message committedDate author{email} committer{email}}}}}}}}';
const ENABLE_AUTO_MERGE = 'mutation($id:ID!,$oid:GitObjectID!){enablePullRequestAutoMerge(input:{pullRequestId:$id,mergeMethod:SQUASH,expectedHeadOid:$oid}){pullRequest{number autoMergeRequest{enabledAt}}}}';
const HEAD_OID = 'query($id:ID!){node(id:$id){... on PullRequest{headRefOid}}}';

export function createGhOps({ repo }) {
  const [owner, name] = repo.split('/');
  const gh = (args, input) => {
    const r = spawnSync('gh', args, { encoding: 'utf8', input, maxBuffer: 64 * 1024 * 1024 });
    return { code: r.status, stdout: r.stdout ?? '', stderr: (r.stderr ?? '') + (r.error ? String(r.error) : '') };
  };
  return {
    listPullRequests() {
      const all = [];
      let cursor = null;
      for (let page = 0; page < 20; page++) {
        const args = ['api', 'graphql', '-f', 'query=' + PR_QUERY, '-f', 'owner=' + owner, '-f', 'name=' + name];
        if (cursor) args.push('-f', 'cursor=' + cursor);
        const data = JSON.parse(must(gh(args), 'PR 一覧の取得')).data.repository.pullRequests;
        all.push(...data.nodes);
        if (!data.pageInfo.hasNextPage) break;
        cursor = data.pageInfo.endCursor;
      }
      return all;
    },
    /** PR の全コメント（REST をページングして全件）。 */
    listComments(pr) {
      const out = must(gh(['api', '--paginate', '--slurp', 'repos/' + repo + '/issues/' + pr.number + '/comments?per_page=100']), '#' + pr.number + ' のコメント取得');
      return JSON.parse(out).flat().map((c) => ({ body: c.body, createdAt: c.created_at }));
    },
    /** 失敗は { ok: false, error } で返す（呼び出し側が結果に載せる）。 */
    comment(number, body) {
      const r = gh(['api', '-X', 'POST', 'repos/' + repo + '/issues/' + number + '/comments', '--input', '-'], JSON.stringify({ body }));
      return r.code === 0 ? { ok: true } : { ok: false, error: (r.stderr || r.stdout).trim() || 'exit ' + r.code };
    },
    enableAutoMerge(pr, expectedHeadOid) {
      const r = gh(['api', 'graphql', '-f', 'query=' + ENABLE_AUTO_MERGE, '-f', 'id=' + pr.id, '-f', 'oid=' + expectedHeadOid]);
      if (r.code === 0) return { ok: true };
      const error = (r.stderr || r.stdout).trim();
      // 失敗時は現在の head を取り直し、expectedHeadOid との不一致かどうかを確かめる（文言に頼らない）。
      const h = gh(['api', 'graphql', '-f', 'query=' + HEAD_OID, '-f', 'id=' + pr.id, '--jq', '.data.node.headRefOid']);
      const current = h.code === 0 ? h.stdout.trim() : null;
      if (current && current !== expectedHeadOid) return { ok: false, headMismatch: true, current, error: 'head=' + current + '（期待 ' + expectedHeadOid + '）: ' + error };
      return { ok: false, error };
    },
  };
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

export async function main(argv) {
  const dryRun = argv.includes('--dry-run');
  const repoIndex = argv.indexOf('--repo');
  const repo = repoIndex >= 0 ? argv[repoIndex + 1] : process.env.GITHUB_REPOSITORY;
  if (!repo || !repo.includes('/')) {
    console.error('リポジトリが分からない（--repo owner/name か GITHUB_REPOSITORY を与えること）');
    return 1;
  }
  const log = (m) => console.log(m);
  const ghOps = createGhOps({ repo });
  // main への push 直後は mergeable が UNKNOWN（遅延計算）なので、対象になりうる PR の判定が出るまで少し待って取り直す。
  // 一覧の取得自体が計算の引き金になる。待ち切れなかった PR は30分毎の schedule で拾う。
  let prs = ghOps.listPullRequests();
  for (let round = 1; round <= 4; round++) {
    const pending = pendingMergeability(prs);
    if (!pending.length) break;
    log('mergeable 計算待ち（' + round + '回目、30秒待つ）: #' + pending.join(', #'));
    await new Promise((resolve) => setTimeout(resolve, 30_000));
    prs = ghOps.listPullRequests();
  }
  const { candidates, skipped } = selectCandidates(prs);
  log('open な main 向け PR ' + prs.length + ' 件、自動修復の対象 ' + candidates.length + ' 件' + (dryRun ? '（dry-run: 判定のみ）' : ''));
  const unknown = skipped.filter((x) => x.reason === 'mergeable=UNKNOWN').length;
  for (const s of skipped.filter((x) => !/^mergeable=(MERGEABLE|UNKNOWN)$/.test(x.reason))) log('  対象外 #' + s.number + ': ' + s.reason);
  if (unknown) log('  mergeable=UNKNOWN（未計算・マージ待ちの意思なし）で対象外: ' + unknown + ' 件');
  if (dryRun) {
    const now = new Date();
    for (const pr of candidates) {
      const fixes = countRecentFixes(pr, now, { botEmail: process.env.LEDGER_BOT_EMAIL || null });
      log('  対象 #' + pr.number + '（' + pr.headRefName + '、' + (pr.autofixTrigger === TRIGGER_LEDGER_EDIT ? '台帳の直接編集を申告へ変換' : '台帳だけの衝突') + '）: '
        + (fixes >= MAX_FIXES_PER_DAY ? '上限到達でスキップ予定' : '修復を試みる予定（直近24時間の修復 ' + fixes + ' 回）'));
    }
    return 0;
  }
  if (!candidates.length) return 0;

  const name = process.env.LEDGER_BOT_NAME || 'ledger-autofix[bot]';
  const email = process.env.LEDGER_BOT_EMAIL || 'ledger-autofix[bot]@users.noreply.github.com';
  const git = createGit({
    cwd: process.cwd(),
    env: { GIT_AUTHOR_NAME: name, GIT_AUTHOR_EMAIL: email, GIT_COMMITTER_NAME: name, GIT_COMMITTER_EMAIL: email },
  });
  ensureUnionAttribute(git);
  const deps = {
    gitOps: createGitOps({ git }),
    ghOps,
    now: new Date(),
    log,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    checkStructure: checkTaskListStructure,
    botEmail: email,
    random: () => String(randomInt(0, 1000000)).padStart(6, '0'),
  };
  let errors = 0;
  for (const pr of candidates) {
    try {
      const result = await processPullRequest(pr, deps);
      log('#' + pr.number + ' → ' + result.outcome + (result.reason ? '（' + result.reason + '）' : ''));
      if (result.commentError) {
        errors++;
        console.error('#' + pr.number + ': PR へのコメント投稿に失敗（処理結果 ' + result.outcome + '）: ' + result.commentError);
      }
    } catch (error) {
      errors++;
      console.error('#' + pr.number + ': 想定外の失敗: ' + (error?.message ?? error));
    }
  }
  return errors ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
