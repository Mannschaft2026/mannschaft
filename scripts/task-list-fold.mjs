#!/usr/bin/env node
// 台帳の変更申告の畳み込み bot（CMP-261124-1222 ④ 段2）。
//
// main の実効台帳（月別ファイル＋未畳み込みの申告 docs/task-list/changes/*.md）から、申告を月別ファイルへ適用して
// 申告ファイルを消した木を作り、GitHub App mannschaft-ledger-bot で bot 専用ブランチ ledger-bot/fold に commit して
// 畳み込み PR を作る/更新する（auto-merge SQUASH を予約）。
//
// 規則（正本: 戦役台帳 2026-11-27-ledger-change-files.md の「畳み込み bot」と Codex 軍議検分の反映 P1-1・P1-2）:
// - 並び: 月別の既存順 → add を（ファイル名時刻, 乱数）順に末尾（applyChanges と同じ。読む側の仮想本文と一致する）。
//   新しい月は newFileSkeleton（索引には月の一覧を置かないので索引は変えない）。
// - fork・孤児・重複・形式不正など違反を含む行（ID）の申告は、その ID の申告を1件も畳み込まずに残し、ログに出す
//   （違反は構造検査・番人が引き続き赤にする）。
// - 自己検査: 出す前に E(month(main), changes(main)) == E(month(fold), changes(fold)) を PR-1 の checkVirtualHead で確かめる
//   （PR 側 mb=main と同じ評価。違反・警告が1件でもあれば書き込まない）。
// - 開いている畳み込み PR は常に1本（ブランチ固定）。merge queue 投入中（isInMergeQueue か mergeQueueEntry）なら触らない。
//   未投入なら最新 main から作り直す: auto-merge 予約を解除 → queue 外を再確認 → bot 専用ブランチに限り lease 付き force push
//   → 再予約（予約が残ったままだと push 前に自動で queue 投入されうる。投入は SHA を変えないので lease では防げない）。
//   同じ main・同じ木なら push しない。
// - 起動: 畳み込める申告が N≥20 件、または最古の申告（ファイル名時刻）が 6 時間を超えたとき（threshold）。
//   1日1回の定期実行・workflow_dispatch（force）は1件でもあれば畳み込む。0件なら何もしない（未投入の古い畳み込み PR は閉じる）。
// - 既定は dry-run（計画をログに出すだけで git・GitHub に一切書かない）。--apply のときだけ書く
//   （workflow は repo 変数 LEDGER_FOLD_ENABLED が 'true' のときだけ --apply を付ける）。
// - 途中失敗: push 拒否は次回に回す（exit 0）。GitHub API・git の失敗は不在と扱わず exit 1（次回の起動でやり直す。
//   push 済みで PR 作成に失敗しても、次回はブランチの内容が同じなら push せずに PR を作る）。
//
// 使い方: node scripts/task-list-fold.mjs [--apply] [--mode threshold|force] [--repo owner/name]
// 試験: scripts/task-list-fold.test.mjs。依存パッケージなし（gh / git はサブプロセス）。

import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { LEDGER_DIR, applyChanges, gitLedgerReader, ledgerRowMap, readLedger } from './task-list-ledger.mjs';
import { checkVirtualHead, diffRowMaps } from './task-list-structure-check.mjs';
import { createGit } from './task-list-conflict-autofix.mjs';

export const FOLD_BRANCH = 'ledger-bot/fold';
export const FOLD_COUNT_THRESHOLD = 20;
export const FOLD_AGE_THRESHOLD_MS = 6 * 3600 * 1000;
/** 畳み込み PR・commit に書く唯一の CMP-ID（PR 本文の CMP-ID は1つだけ）。 */
export const FOLD_CMP_ID = 'CMP-261124-1222';
export const FOLD_COMMIT_TRAILER = 'Ledger-Fold: task-list-fold';
export const FOLD_PR_MARKER = '<!-- ledger-fold -->';
export const HEAD_REFLECT_WAIT_MS = 10_000;
export const HEAD_REFLECT_MAX_WAITS = 6;

// ---------------------------------------------------------------------------
// 計画（純粋関数）
// ---------------------------------------------------------------------------

/** 申告のファイル名時刻（yyyymmddThhmmssZ）を UTC のミリ秒にする。 */
export function changeTimeMs(time) {
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(time ?? '');
  if (!m) return NaN;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
}

const byString = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

/**
 * main の台帳（readLedger の既定の結果。rawFiles と changes を使う）から畳み込み計画を作る。
 * @returns {{ layout: string, folded: object[], kept: {path: string, id: string|null, reason: string}[], files: Record<string, string>, removed: string[], skipped?: string }}
 *   files は変わる（新しい）月別ファイルの本文、removed は消す申告のパス。
 */
export function planFold(ledger) {
  const entries = ledger.changes?.entries ?? [];
  const problems = ledger.changes?.problems ?? [];
  const empty = { layout: ledger.layout, folded: [], kept: [], files: {}, removed: [] };
  if (!entries.length) return empty;
  if (ledger.layout !== 'split' || !Array.isArray(ledger.rawFiles)) {
    return { ...empty, kept: entries.map((e) => ({ path: e.path, id: e.id, reason: '台帳が新配置（月別）でないため畳み込まない' })), skipped: 'layout' };
  }
  const byPath = new Map(entries.map((e) => [e.path, e]));
  const reasons = new Map();
  const heldIds = new Set();
  const note = (path, msg) => {
    if (!reasons.has(path)) reasons.set(path, []);
    reasons.get(path).push(msg);
  };
  for (const p of problems) {
    for (const path of p.paths ?? [p.path]) {
      const e = byPath.get(path);
      if (!e) continue;
      note(path, p.message);
      if (e.id) heldIds.add(e.id);
    }
  }
  for (const e of entries) if (!e.valid && e.id) heldIds.add(e.id);
  const applied = new Set(ledger.changes?.applied ?? []);
  const folded = [];
  const kept = [];
  for (const e of [...entries].sort((a, b) => byString(a.path, b.path))) {
    if (heldIds.has(e.id) || !e.valid || !applied.has(e.path)) {
      const own = reasons.get(e.path);
      kept.push({ path: e.path, id: e.id, reason: own ? own.join(' / ') : '同じ行（' + e.id + '）に違反のある申告があるため、この行の申告は畳み込まない' });
    } else {
      folded.push(e);
    }
  }
  if (!folded.length) return { ...empty, kept };
  const result = applyChanges(ledger.rawFiles, ledger.layout, folded);
  const before = new Map(ledger.rawFiles.map((f) => [f.path, f.content]));
  const files = {};
  for (const f of result.files) {
    if (f.content == null || !f.path.startsWith(LEDGER_DIR + '/')) continue;
    if (before.get(f.path) !== f.content) files[f.path] = f.content;
  }
  return { layout: ledger.layout, folded, kept, files, removed: folded.map((e) => e.path) };
}

/** 元の木（パス→本文）に計画を当てた木を返す（純粋関数）。 */
export function buildFoldFiles(files, plan) {
  const out = { ...files };
  for (const [p, c] of Object.entries(plan.files)) out[p] = c;
  for (const p of plan.removed) delete out[p];
  return out;
}

/** パス→本文 を readLedger の reader にする（サブディレクトリは名前だけ返し readFile は null。作業木の reader と同じ）。 */
export function filesLedgerReader(files) {
  return {
    listDir(dir) {
      const prefix = dir + '/';
      const names = [...new Set(Object.keys(files).filter((p) => p.startsWith(prefix)).map((p) => p.slice(prefix.length).split('/')[0]))];
      return names.length ? names : null;
    },
    readFile(path) {
      return Object.prototype.hasOwnProperty.call(files, path) ? files[path] : null;
    },
  };
}

/**
 * 自己検査: 畳み込み後の木の台帳が main と等式（E(month(main), changes(main)) == E(month(fold), changes(fold))）を満たし、
 * 月別ファイルの行以外を変えておらず、申告の追加・改変も無いこと（checkVirtualHead の PR 側評価で違反・警告0件）。
 * @param mainLedger readLedger の結果（main）
 * @param foldFiles 畳み込み後の木の台帳部分（パス→本文）
 */
export async function verifyFold(mainLedger, foldFiles) {
  const reasons = [];
  let head;
  try {
    head = await readLedger(filesLedgerReader(foldFiles), { requireRows: false });
  } catch (error) {
    return { ok: false, reasons: ['畳み込み後の台帳を読めない: ' + error.message] };
  }
  const r = checkVirtualHead({ mergeBase: mainLedger, base: mainLedger, head });
  for (const v of r.violations) reasons.push('違反（' + v.kind + '）: ' + v.message);
  for (const w of r.warnings) reasons.push('警告（' + w.kind + '）: ' + w.message);
  const diff = diffRowMaps(ledgerRowMap(mainLedger), ledgerRowMap(head));
  if (diff.length) reasons.push('実効台帳（ID→行）が変わる: ' + diff.slice(0, 10).join(', '));
  if (r.addedChanges.length) reasons.push('畳み込みが申告を足している: ' + r.addedChanges.join(', '));
  return { ok: reasons.length === 0, reasons, check: r };
}

/**
 * 起動するか。mode: 'threshold'（main への push・毎時）は N≥20 か最古が6時間超、'force'（1日1回・手動）は1件でも。
 * 違反で残す申告は数えない（畳み込めないため）。
 */
export function shouldFold({ plan, now, mode }) {
  const n = plan.folded.length;
  if (n === 0) return { run: false, reason: '畳み込める申告が0件' };
  const times = plan.folded.map((e) => changeTimeMs(e.time)).filter((t) => Number.isFinite(t));
  const oldest = times.length ? Math.min(...times) : null;
  const ageMs = oldest == null ? 0 : now - oldest;
  const ageText = oldest == null ? '不明' : (ageMs / 3600_000).toFixed(2) + ' 時間';
  if (mode === 'force') return { run: true, reason: '定期実行・手動（' + n + ' 件、最古 ' + ageText + '）' };
  if (n >= FOLD_COUNT_THRESHOLD) return { run: true, reason: '件数 ' + n + ' ≥ ' + FOLD_COUNT_THRESHOLD };
  if (ageMs > FOLD_AGE_THRESHOLD_MS) return { run: true, reason: '最古の申告が ' + ageText + '（6 時間超）' };
  return { run: false, reason: '閾値未満（' + n + ' 件 < ' + FOLD_COUNT_THRESHOLD + '、最古 ' + ageText + ' ≤ 6 時間）' };
}

/** PR 題名（CMP-ID は FOLD_CMP_ID だけ）。 */
export function foldPullRequestTitle(plan) {
  return '台帳: 変更申告 ' + plan.folded.length + ' 件を月別ファイルへ畳み込む（' + FOLD_CMP_ID + '）';
}

const code = (s) => '`' + s + '`';

/** PR 本文。畳み込む行の ID・申告のファイル名（CMP-ID を含む）は書かない（本文の CMP-ID は FOLD_CMP_ID だけにする）。 */
export function foldPullRequestBody(plan, mainOid) {
  const months = Object.keys(plan.files).sort();
  return FOLD_PR_MARKER + '\n'
    + '## 概要\n\n'
    + '台帳の変更申告（' + code(LEDGER_DIR + '/changes/') + '）を月別ファイルへ畳み込む自動 PR（' + FOLD_CMP_ID + ' ④ 段2、' + code('scripts/task-list-fold.mjs') + '）。\n\n'
    + '- 取り込んだ main: ' + code(mainOid) + '\n'
    + '- 畳み込んだ申告: ' + plan.folded.length + ' 件（申告ファイルを削除）\n'
    + '- 変更した月別ファイル: ' + (months.map(code).join(', ') || 'なし') + '\n'
    + '- 違反（fork・孤児・重複・形式不正）を含む行のため残した申告: ' + plan.kept.length + ' 件（詳細は workflow のログ）\n\n'
    + '畳み込みの前後で実効台帳（ID→行）が一致すること（等式 E(month(main), changes(main)) == E(month(fold), changes(fold))）を作成時に自己検査済み。\n'
    + 'この PR は bot が管理する（開いている畳み込み PR は常に1本。merge queue 未投入なら最新 main から作り直す）。手で編集しないこと。\n\n'
    + '実機E2E・アリシゼーション: UI の無い台帳データの自動整理のため対象外。\n';
}

function foldCommitMessage(plan) {
  return foldPullRequestTitle(plan) + '\n\n'
    + '変更申告 ' + plan.folded.length + ' 件を月別ファイルへ適用し、申告ファイルを削除した（月別の既存順のあとに add を（ファイル名時刻, 乱数）順で末尾）。\n'
    + '畳み込み前後で実効台帳が一致することを自己検査済み。\n\n' + FOLD_COMMIT_TRAILER;
}

// ---------------------------------------------------------------------------
// 実行（git・GitHub は注入）
// ---------------------------------------------------------------------------

/**
 * 1回分の畳み込み。deps: { gitOps, ghOps, now, mode, apply, log, sleep }。apply の既定は false（dry-run）。
 * @returns {Promise<{ outcome: string, exitCode: number, reason?: string, pr?: number }>}
 */
export async function runFold({ gitOps, ghOps, now = Date.now(), mode = 'threshold', apply = false, log = console.log, sleep = (ms) => new Promise((r) => setTimeout(r, ms)) }) {
  const done = (outcome, exitCode, extra = {}) => {
    log('結果: ' + outcome + (extra.reason ? '（' + extra.reason + '）' : ''));
    return { outcome, exitCode, ...extra };
  };
  try {
    const mainOid = gitOps.fetchMain();
    const ledger = await readLedger(gitOps.ledgerReader(mainOid), { requireRows: true });
    const plan = planFold(ledger);
    log('main ' + mainOid + ': 未畳み込みの申告 ' + (ledger.changes?.entries?.length ?? 0) + ' 件、畳み込める ' + plan.folded.length + ' 件、残す ' + plan.kept.length + ' 件' + (apply ? '' : '（dry-run: 書き込まない）'));
    for (const k of plan.kept) log('  残す ' + k.path + ': ' + k.reason);
    const pr = ghOps.findFoldPullRequest();
    if (pr) log('開いている畳み込み PR: #' + pr.number + '（head ' + pr.headRefOid + (pr.isInMergeQueue ? '、merge queue 投入中' : '') + '）');
    if (inMergeQueue(pr)) return done('skip-in-queue', 0, { reason: '#' + pr.number + ' が merge queue 投入中のため触らない' });

    if (plan.folded.length === 0) {
      if (pr) {
        if (!apply) return done('dry-run', 0, { reason: '畳み込める申告が無いので #' + pr.number + ' を閉じる予定（dry-run）' });
        ghOps.closePullRequest(pr.number, '畳み込める申告が main に無くなったため、この畳み込み PR を閉じる（task-list-fold）。');
        return done('closed-stale', 0, { pr: pr.number });
      }
      return done('nothing-to-fold', 0);
    }
    const trigger = shouldFold({ plan, now, mode });
    log('起動判定（' + mode + '）: ' + (trigger.run ? '畳み込む' : '畳み込まない') + ' — ' + trigger.reason);
    if (!trigger.run) return done('below-threshold', 0, { reason: trigger.reason });

    const baseFiles = Object.fromEntries([
      ...ledger.rawFiles.filter((f) => f.content != null).map((f) => [f.path, f.content]),
      ...ledger.changes.entries.map((e) => [e.path, e.content]),
    ]);
    const verify = await verifyFold(ledger, buildFoldFiles(baseFiles, plan));
    if (!verify.ok) {
      for (const r of verify.reasons) log('  自己検査 NG: ' + r);
      return done('verify-failed', 1, { reason: '畳み込み結果が等式を満たさないため書き込まない' });
    }
    log('自己検査: OK（実効台帳一致。変更する月別ファイル: ' + Object.keys(plan.files).sort().join(', ') + '、削除する申告 ' + plan.removed.length + ' 件）');
    if (!apply) return done('dry-run', 0, { reason: pr ? '#' + pr.number + ' を最新 main から作り直す予定' : '畳み込み PR を作る予定' });

    const tree = gitOps.writeTree(gitOps.treeOf(mainOid), plan.files, plan.removed);
    const remoteOid = gitOps.fetchBranch(FOLD_BRANCH);
    const info = remoteOid ? gitOps.commitInfo(remoteOid) : null;
    const same = Boolean(info && info.tree === tree && info.parents.length === 1 && info.parents[0] === mainOid);
    let headOid = remoteOid;
    let pushed = false;
    if (!same) {
      const commit = gitOps.commitTree(tree, mainOid, foldCommitMessage(plan));
      if (pr) {
        // auto-merge 予約が残ったままだと、push までのあいだに必須チェックが揃って GitHub が自動で queue へ投入しうる。
        // queue 投入では head の SHA が変わらないため force-with-lease では防げない。先に予約を解除して自動投入の道を断ち、
        // 解除の後に queue 外であることを確かめてから push する（解除前に投入済みなら触らずに終える）。
        // 解除〜再予約のあいだで失敗しても、次回は「予約なしの PR」として同じ手順で作り直して再予約する。
        if (pr.autoMergeRequest) {
          ghOps.disableAutoMerge(pr);
          log('#' + pr.number + ' の auto-merge 予約を解除した（作り直しのあいだの自動 queue 投入を防ぐ）');
        }
        const latest = ghOps.findFoldPullRequest();
        if (inMergeQueue(latest)) return done('skip-in-queue', 0, { reason: '#' + latest.number + ' が push 前に merge queue へ入っていたため触らない' });
        if (latest && latest.number !== pr.number) throw new Error('畳み込み PR が判定中に入れ替わった（#' + pr.number + ' → #' + latest.number + '）');
      }
      const r = gitOps.forcePush(commit, FOLD_BRANCH, remoteOid ?? null);
      if (!r.ok) return done('skip-push-rejected', 0, { reason: 'push 拒否（次回に回す）: ' + r.reason });
      headOid = commit;
      pushed = true;
      log(FOLD_BRANCH + ' へ push 済み ' + commit + '（親 main ' + mainOid + '）');
    } else {
      log(FOLD_BRANCH + ' は既に最新 main の上の同じ内容（' + remoteOid + '）。push しない');
    }

    const title = foldPullRequestTitle(plan);
    const body = foldPullRequestBody(plan, mainOid);
    let target = pr;
    let outcome;
    if (pr) {
      ghOps.updatePullRequest(pr.number, { title, body });
      outcome = pushed ? 'updated' : 'up-to-date';
    } else {
      target = ghOps.createPullRequest({ title, body, head: FOLD_BRANCH, base: 'main' });
      outcome = 'created';
    }
    log('畳み込み PR #' + target.number + '（' + outcome + '）');
    if (pushed || !target.autoMergeRequest) {
      const am = await enableAutoMergeWithWait(ghOps, target, headOid, pr?.headRefOid ?? null, { log, sleep });
      if (!am.ok) return done('automerge-failed', 1, { reason: am.error, pr: target.number });
    }
    return done(outcome, 0, { pr: target.number });
  } catch (error) {
    return done('api-failed', 1, { reason: '取得・書き込みに失敗（次回の起動でやり直す）: ' + (error?.message ?? error) });
  }
}

/** merge queue 投入中か（isInMergeQueue と mergeQueueEntry のどちらかが立っていれば投入中とみなす）。 */
export function inMergeQueue(pr) {
  return Boolean(pr && (pr.isInMergeQueue || pr.mergeQueueEntry));
}

/** auto-merge（SQUASH）を予約する。push 直後の head 反映遅れ（push 前の head が返る）は待って再試行する。 */
async function enableAutoMergeWithWait(ghOps, pr, expectedHeadOid, previousHeadOid, { log, sleep }) {
  for (let waits = 0; ; waits++) {
    const r = ghOps.enableAutoMerge(pr, expectedHeadOid);
    if (r.ok) return r;
    if (r.headMismatch && previousHeadOid && r.current === previousHeadOid && waits < HEAD_REFLECT_MAX_WAITS) {
      log('PR head が push 前のまま（反映待ち ' + (waits + 1) + '/' + HEAD_REFLECT_MAX_WAITS + '）');
      await sleep(HEAD_REFLECT_WAIT_MS);
      continue;
    }
    return { ok: false, error: 'auto-merge の予約に失敗: ' + r.error };
  }
}

// ---------------------------------------------------------------------------
// git / gh の実体
// ---------------------------------------------------------------------------

function must(result, what) {
  if (result.code !== 0) throw new Error(what + ' に失敗: ' + result.stderr.trim());
  return result.stdout.trim();
}

export function createFoldGitOps({ git, cwd }) {
  const branchRef = 'refs/heads/' + FOLD_BRANCH;
  return {
    fetchMain() {
      must(git(['fetch', '--no-tags', '--quiet', 'origin', '+refs/heads/main:refs/remotes/origin/main']), 'main の fetch');
      return must(git(['rev-parse', 'refs/remotes/origin/main']), 'main の rev-parse');
    },
    ledgerReader(oid) {
      return gitLedgerReader(oid, { cwd });
    },
    treeOf(oid) {
      return must(git(['rev-parse', oid + '^{tree}']), 'tree の取得');
    },
    /** bot 専用ブランチの現在の commit（無ければ null）。あれば fetch して中身を読めるようにする。 */
    fetchBranch() {
      const r = git(['ls-remote', '--exit-code', 'origin', branchRef]);
      if (r.code === 2) return null;
      const oid = must(r, FOLD_BRANCH + ' の ls-remote').split(/\s+/)[0];
      must(git(['fetch', '--no-tags', '--quiet', 'origin', '+' + branchRef + ':refs/remotes/origin/' + FOLD_BRANCH]), FOLD_BRANCH + ' の fetch');
      return oid;
    },
    commitInfo(oid) {
      const text = must(git(['cat-file', 'commit', oid]), 'commit の読み取り');
      const head = text.split('\n\n')[0].split('\n');
      return { tree: head.find((l) => l.startsWith('tree ')).slice(5), parents: head.filter((l) => l.startsWith('parent ')).map((l) => l.slice(7)) };
    },
    /** baseTree に changed（パス→本文）を書き、removed を消した tree を作る（作業木・本物の index は使わない）。 */
    writeTree(baseTree, changed, removed) {
      const dir = mkdtempSync(join(tmpdir(), 'ledger-fold-'));
      const env = { GIT_INDEX_FILE: join(dir, 'index') };
      try {
        must(git(['read-tree', baseTree], { env }), 'read-tree');
        for (const [path, text] of Object.entries(changed)) {
          const oid = must(git(['hash-object', '-w', '--stdin'], { input: text }), 'hash-object');
          must(git(['update-index', '--add', '--cacheinfo', '100644,' + oid + ',' + path], { env }), 'update-index ' + path);
        }
        for (const path of removed) must(git(['update-index', '--force-remove', '--', path], { env }), 'update-index --force-remove ' + path);
        return must(git(['write-tree'], { env }), 'write-tree');
      } finally {
        rmSync(dir, { recursive: true, force: true });
      }
    },
    commitTree(tree, parent, message) {
      return must(git(['commit-tree', tree, '-p', parent, '-m', message]), 'commit の作成');
    },
    /** bot 専用ブランチだけを lease 付きで force push する（expected が null ならブランチが無いことを要求）。 */
    forcePush(commit, branch, expected) {
      if (branch !== FOLD_BRANCH) throw new Error('force push は ' + FOLD_BRANCH + ' だけに限る: ' + branch);
      const r = git(['push', '--quiet', '--force-with-lease=' + branchRef + ':' + (expected ?? ''), 'origin', commit + ':' + branchRef]);
      return r.code === 0 ? { ok: true } : { ok: false, reason: r.stderr.trim() || 'push exit ' + r.code };
    },
  };
}

const FIND_PR = 'query($owner:String!,$name:String!,$head:String!){repository(owner:$owner,name:$name){pullRequests(states:OPEN,headRefName:$head,first:10){nodes{'
  + 'id number baseRefName headRefOid isCrossRepository isInMergeQueue mergeQueueEntry{state} autoMergeRequest{enabledAt}}}}}';
const ENABLE_AUTO_MERGE = 'mutation($id:ID!,$oid:GitObjectID!){enablePullRequestAutoMerge(input:{pullRequestId:$id,mergeMethod:SQUASH,expectedHeadOid:$oid}){pullRequest{number}}}';
const DISABLE_AUTO_MERGE = 'mutation($id:ID!){disablePullRequestAutoMerge(input:{pullRequestId:$id}){pullRequest{number}}}';
const HEAD_OID = 'query($id:ID!){node(id:$id){... on PullRequest{headRefOid}}}';

export function createFoldGhOps({ repo }) {
  const [owner, name] = repo.split('/');
  const gh = (args, input) => {
    const r = spawnSync('gh', args, { encoding: 'utf8', input, maxBuffer: 64 * 1024 * 1024 });
    return { code: r.status, stdout: r.stdout ?? '', stderr: (r.stderr ?? '') + (r.error ? String(r.error) : '') };
  };
  const rest = (method, path, payload, what) => JSON.parse(must(gh(['api', '-X', method, path, '--input', '-'], JSON.stringify(payload)), what) || 'null');
  return {
    findFoldPullRequest() {
      const out = must(gh(['api', 'graphql', '-f', 'query=' + FIND_PR, '-f', 'owner=' + owner, '-f', 'name=' + name, '-f', 'head=' + FOLD_BRANCH]), '畳み込み PR の取得');
      const nodes = JSON.parse(out).data.repository.pullRequests.nodes.filter((p) => p.baseRefName === 'main' && !p.isCrossRepository);
      if (nodes.length > 1) throw new Error('畳み込み PR が複数開いている（#' + nodes.map((p) => p.number).join(', #') + '）。1本にしてから再実行すること');
      return nodes[0] ?? null;
    },
    createPullRequest({ title, body, head, base }) {
      const pr = rest('POST', 'repos/' + repo + '/pulls', { title, body, head, base }, '畳み込み PR の作成');
      return { id: pr.node_id, number: pr.number, headRefOid: pr.head?.sha ?? null, autoMergeRequest: null };
    },
    updatePullRequest(number, { title, body }) {
      rest('PATCH', 'repos/' + repo + '/pulls/' + number, { title, body }, '#' + number + ' の更新');
    },
    closePullRequest(number, comment) {
      rest('POST', 'repos/' + repo + '/issues/' + number + '/comments', { body: FOLD_PR_MARKER + '\n' + comment }, '#' + number + ' へのコメント');
      rest('PATCH', 'repos/' + repo + '/pulls/' + number, { state: 'closed' }, '#' + number + ' を閉じる');
    },
    disableAutoMerge(pr) {
      must(gh(['api', 'graphql', '-f', 'query=' + DISABLE_AUTO_MERGE, '-f', 'id=' + pr.id]), '#' + pr.number + ' の auto-merge 予約解除');
    },
    enableAutoMerge(pr, expectedHeadOid) {
      const r = gh(['api', 'graphql', '-f', 'query=' + ENABLE_AUTO_MERGE, '-f', 'id=' + pr.id, '-f', 'oid=' + expectedHeadOid]);
      if (r.code === 0) return { ok: true };
      const error = (r.stderr || r.stdout).trim();
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
  const apply = argv.includes('--apply');
  const modeIndex = argv.indexOf('--mode');
  const mode = modeIndex >= 0 ? argv[modeIndex + 1] : 'threshold';
  const repoIndex = argv.indexOf('--repo');
  const repo = repoIndex >= 0 ? argv[repoIndex + 1] : process.env.GITHUB_REPOSITORY;
  if (mode !== 'threshold' && mode !== 'force') {
    console.error('--mode は threshold か force');
    return 1;
  }
  if (!repo || !repo.includes('/')) {
    console.error('リポジトリが分からない（--repo owner/name か GITHUB_REPOSITORY を与えること）');
    return 1;
  }
  const botName = process.env.LEDGER_BOT_NAME || 'mannschaft-ledger-bot[bot]';
  const botEmail = process.env.LEDGER_BOT_EMAIL || 'mannschaft-ledger-bot[bot]@users.noreply.github.com';
  const cwd = process.cwd();
  const git = createGit({ cwd, env: { GIT_AUTHOR_NAME: botName, GIT_AUTHOR_EMAIL: botEmail, GIT_COMMITTER_NAME: botName, GIT_COMMITTER_EMAIL: botEmail } });
  const result = await runFold({ gitOps: createFoldGitOps({ git, cwd }), ghOps: createFoldGhOps({ repo }), now: Date.now(), mode, apply });
  return result.exitCode;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
