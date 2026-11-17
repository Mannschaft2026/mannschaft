# R1 — リポジトリ衛生（残骸の撤去と `.gitignore`）

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書と [`../decided-facts.md`](../decided-facts.md) を読むこと。進め方は親文書 §4-1 の正規フローに従う。
> 足軽のモデル: Sonnet 以下（機械的な作業）。

## 目的

本物のスクリプトと文書を探しやすくし、`git status` を読める状態に戻す。

## 対象（2026-11-17 に中身と参照を確認済み。着手時に取り直す）

| 群 | ファイル | 中身 | 処置 | PR の scope |
|---|---|---|---|---|
| A | `.gitignore` | ルートに溜まる未追跡物の型（`scratch_*`、`.tmp-*`）が載っていない | パターンを足す | `app` |
| B | `backend/scripts/replace_getCurrentUserId.mjs`・`.py`・`_cleanup.mjs`・`_final.mjs`・`_pass2.mjs`・`_pass3.mjs`・`_pass4.mjs` | コントローラーの `getCurrentUserId()` を `SecurityUtils.getCurrentUserId()` へ一括で置き換えた移行スクリプトと、その取りこぼしを直した第2〜4パス。置換は完了している | 削除 | `app` |
| C | `backend/scripts/test_ym.sql`・`test_ym2.sql`・`test_ym3.sql` | `year_month` という列名が MySQL で通るかを試した実験。得た知見は `docs/features/F20.1_entitlement_billing/01_data_model.md` に残っている | 削除 | `app` |
| D | `scripts/merge_locales.py`、`scripts/resolve_conflicts.py` | 前者は `team_visibility` のキーを6言語に足した一回限りの作業で、今の置き場ではパスが合わず動かない。後者は特定の4ファイルのマージ競合を解いたもの | 削除 | `tooling` |
| E | `qiita_claudecode_assets_to_codex_bridge.md`（ルート）、`docs/qiita-polyrepo-monorepo.md` | 記事の原稿 | `docs/articles/` へ `git mv`（ディレクトリは実在する） | ルートの1本は `app`、`docs/` の1本は `tooling` |
| F | `e2e-test-instructions.md`（ルート）、`docs/monorepo-migration.md`、`docs/TEST_COVERAGE_PLAN.md`、`docs/E2E_TEST_PLAN.md` | 順に、過去の E2E 自動化の実装指示書／完了済みと自ら書く移行手順書／2026-03-26 時点のカバレッジ計画／2026-04-06 の E2E の陣立て書（2026-09-24 に更新あり） | 削除 | ルートの1本は `app`、`docs/` の3本は `tooling` |

**触らないもの**: `start-backend.vbs`、`start-backend.ps1`、`backend-loop.sh`。BE 常駐の正系であり、`.claude/commands/` の `陣立て`・`伝令`・`撤収` が使う（決定 I10）。

## 手順

### ステップ1: `.gitignore`

1. 着手前に、`git ls-files -ci --exclude-standard | wc -l` の値を記録する（起草時は 52）
2. 本陣で `git status --short | grep '^??' | grep -v '/'` を**読むだけ**実行し、ルートの未追跡物の型を見る。ファイルは消さない
3. 型として繰り返し現れるものだけを足す。候補は `/scratch_*` と `/.tmp-*`。シェルの引用ミスで生まれた名前（`{const` など）は型にならないので足さない
4. 手順1の値が増えていないことを確かめる（追跡済みのファイルが新しいパターンに当たっていない）
5. 既存の行を動かさない。特に `.claude/campaigns/*` の行は台帳 CMP-261108-1105 が触れている

### ステップ2: 削除と移動

1. **対象のファイルを開いて中身を読む。** 上の表の「中身」と合っているか、今後も使う手順や唯一の記録を含んでいないかを確かめ、1ファイル1行の根拠を PR 本文に書く。少しでも迷うものは残し、報告の質問に回す
2. `docs/E2E_TEST_PLAN.md` は 2026-09-24 に更新されている。`git log -3 -p -- docs/E2E_TEST_PLAN.md` で最新の差分を読み、現行の文書に移すべき内容が無いかを確かめる
3. 参照を確かめる。範囲は追跡ファイル全体である
   ```bash
   git grep -nF "<ファイル名>" -- . ':!docs/evidence' ':!docs/refactoring' ':!docs/task-list.md'
   ```
   - **利用の参照**（スクリプトや workflow からの呼び出し、手順書の実行コマンド、import、リンク）が1件でもあれば、削除しない
   - `docs/evidence/` の JSON が、ファイルの hash を過去の記録として持っていることがある（記事原稿と `e2e-test-instructions.md`）。**その記録は書き換えない**
   - 本指示書（`docs/refactoring/`）と台帳の中の言及は、削除の計画であり、利用の参照に数えない。削除した PR で「YYYY-MM-DD 削除済み（PR 番号）」と追記する
4. `node --test scripts/*.test.mjs` を、削除の前と後で実行する。完了証拠の検査が、過去の証拠が指すファイルの存在を確かめ直して落ちるようなら、**削除をやめて報告する**
5. `e2e-test-instructions.md` は、本指示書と同じ「`/goal` に渡す指示書」の唯一の先例である（PR #1558）。削除の PR 本文に、その旨と、内容が git の履歴から読めることを書く
6. 移動は `git mv` で行い、履歴を残す。移動・削除したパスへのリンクが残っていないことを確かめる
7. PR は scope ごとに分ける。`tooling` だけの PR（D と、E・F のうち `docs/` のもの）と、`app` の PR（A、B+C、E・F のうちルートのもの）

## やってはならないこと

- 本陣や他の worktree の未追跡ファイルを消すこと。`git clean` を使うこと
- 中身を読まずに消すこと
- `.claude/` 配下の未追跡物、`docs/evidence/` に触れること
- 起動スクリプト3本に触れること

## 完了条件

1. `.gitignore` にパターンが足され、追跡済みのファイルに影響していない
2. B・C・D が削除されている。または、中身を確かめた結果「残す」と理由付きで報告にある
3. E が `docs/articles/` に移っている
4. F が削除されている。または、同上
5. 削除・移動したパスへのリンクが残っていない
6. ハーネスのテストが、前後で同じ結果である

## 検証

- `node --test scripts/*.test.mjs`、`node scripts/harness-doctor.mjs`
- `app` の PR は、親文書 §9-3 の完了ゲートに従う
- CI の全チェックが緑で、検分に合格している
