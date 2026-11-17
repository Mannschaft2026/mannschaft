# R1 — リポジトリ衛生（残骸の撤去と `.gitignore`）

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。§3〜§6・§9・§10 の規律がそのまま適用される。先に親文書を読むこと。
> 推奨モデル: Sonnet 以下（機械的な作業）。

## 目的

本物のスクリプトと設定を探しやすくし、`git status` を読める状態に戻す。

## 対象と状態（2026-11-17 実測）

| 群 | ファイル | 追跡ファイルからの参照 | 実装可否 |
|---|---|---|---|
| A | `.gitignore` に無いパターン: `scratch_*`、`.tmp-*`、ルートの0バイト残骸の元になる出力先 | — | **可** |
| B | `backend/scripts/replace_getCurrentUserId.mjs` / `.py` / `_cleanup.mjs` / `_final.mjs` / `_pass2.mjs` / `_pass3.mjs` / `_pass4.mjs`（7本） | 0 | **可**。中身を読んで不要と確かめてから削除 |
| C | `backend/scripts/test_ym.sql` / `test_ym2.sql` / `test_ym3.sql` | 0 | **可**。同上 |
| D | `scripts/merge_locales.py`、`scripts/resolve_conflicts.py` | 0（要再確認） | **可**。同上 |
| E | `start-backend.vbs`、`start-backend.ps1`、`backend-loop.sh` | BE 起動の正系。`.claude/commands/` の `陣立て`・`伝令`・`撤収` が使う | **削除しない。触らない**（個人パスの直書きは R7 の P14 で提案） |
| F | 記事原稿: `qiita_claudecode_assets_to_codex_bridge.md`、`docs/qiita-polyrepo-monorepo.md`／古い計画書: `e2e-test-instructions.md`、`docs/monorepo-migration.md`、`docs/TEST_COVERAGE_PLAN.md`、`docs/E2E_TEST_PLAN.md` | ほぼ0 | **可**。記事原稿2本は `docs/articles/` へ `git mv`、計画書4本は削除 |

## 手順

### ステップ1（回答不要・すぐ着手）: `.gitignore`

1. 本陣で `git status --short | grep '^??' | grep -v '/'` を**読むだけ**実行し、ルート直下の未追跡物の型を把握する。ファイルは消さない（他者の資産）
2. 型として繰り返し現れるものだけを `.gitignore` に足す。起草時点の候補は `/scratch_*` と `/.tmp-*`
3. 既に追跡されているファイルが新しいパターンに当たらないことを確かめる: `git ls-files -ci --exclude-standard` の出力が増えていないこと
4. 1 PR にまとめる

シェルの引用ミスで生まれた名前（`{const`、`({title` など）は型にならないので、パターンを足さない。

### ステップ2: 中身を確かめて削除・移動する（裁可済み）

1. **対象のファイルを開いて中身を読む。** 一回限りの作業の跡であること、今後も使う手順や唯一の記録を含んでいないことを確かめ、判断の根拠を1行ずつ PR 本文に書く。少しでも迷うものは削除せず、報告の質問に回す
1. 対象ごとに参照ゼロを再確認する。確認範囲は追跡ファイル全体に加えて、`.github/workflows/`、`package.json`（ルート・`frontend/`・`backend/scripts/`）、`.claude/commands/`、`docs/`
   ```bash
   git grep -nF "<ファイル名>" -- . ':!docs/evidence' | grep -v "^<自分自身のパス>:"
   ```
2. **実行・利用の参照**（スクリプトや workflow からの呼び出し、手順書の実行コマンド、import）が1件でもあれば削除しない。参照元も不要かを別に判断し、判断できなければ質問する
   - 本指示書（`docs/refactoring/refactor-instructions.md` と `docs/refactoring/lanes/`）と台帳の中の言及は、**削除計画上の言及**であり、利用の参照に数えない。削除した PR で、その言及に「YYYY-MM-DD 削除済み（PR 番号）」と追記する
3. 群ごとに PR を分ける（B+C、D、F）。PR 本文に、中身の確認結果と、参照確認のコマンドおよび出力を貼る
4. F の移動は `git mv` で行い、履歴を残す。移動・削除したパスへのリンクが残っていないことを `git grep` で確かめ、残っていれば直す。古い計画書のうち、現行の文書から「経緯」として参照されているものは、参照元の文を「YYYY-MM-DD に削除（git の履歴を参照）」と書き換える

## やってはならないこと

- 本陣や他の worktree の未追跡ファイルを消すこと。`git clean` を使うこと
- 「たぶん使っていない」で消すこと
- `.claude/` 配下の未追跡物に触れること
- `docs/evidence/` の整理（完了証拠の正本）

## 完了条件

- ステップ1の PR がマージされている
- ステップ2の B・C・D・F が、削除・移動済みか、「中身を確かめた結果、残す」と理由付きで報告に載っている

## 検証

- `node --test scripts/*.test.mjs`（ハーネスが壊れていないこと）
- `node scripts/harness-doctor.mjs`
- CI の全チェックが緑
