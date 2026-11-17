# R6 — 文書と実装の不整合の是正

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書を読むこと。
> 推奨モデル: Sonnet。
> このレーンが触るのは文書だけである。コードと設定は変えない。

## 目的

実装担当が最初に読む文書に、実在しないコマンドや直し済みの欠陥が「現状」として残っている。読んだ人を誤らせる箇所を、実装に合わせて直す。

## 原則

- **実装が正で、文書を合わせる。** 文書のほうが正しく見えたら（実装が仕様から外れている疑い）、直さずに質問する
- 直す前に、実装の現状を**自分で確かめる**（ファイルと行を PR 本文に書く）。親文書の記述を鵜呑みにしない
- 経緯は消さずに「〜は YYYY-MM-DD に修正済み」と1行で残すか、経緯の文書へ移す
- 1 PR は1文書。台帳 `docs/task-list.md` は対象外

## 対象

| # | 文書 | 不整合 | 確かめる実装 | 可否 |
|---|---|---|---|---|
| D1 | `TEST_CONVENTION.md` §7 | `integrationTest` / `allTests` タスク、`@Tag("integration")` を例示するが実在しない | `backend/build-logic/` のタスク定義とタグ（実在は `perf` / `archunit` / `er-diagram`） | 可 |
| D1b | `TEST_CONVENTION.md` §10 | `Thread.sleep` 禁止、`@Disabled` は1スプリント以内。守られていない | R5 の一覧 | R5 の完了後に、現状と返済先（R5）を1行で足す |
| D2 | `docs/security/02_cookie_and_session.md` §2.1 | `secure(false)` のハードコードを現状として記述 | `backend/.../auth/controller/AuthTokenCookies.java`、`mannschaft.cookie.secure` | 可 |
| D2b | `docs/security/README.md` | 当時の状況のままの記述 | 配下の各文書と実装 | 可（明らかに古い箇所だけ） |
| D3 | `docs/development/claude-md-details.md` §12 | 日常の検証に `./gradlew build` / `./gradlew test` を挙げる。`build` は `archTest` まで含み重い | 親文書 §6-3 | 可 |
| D4 | `docs/refactoring/phase4_overview.md` ほか | 第3弾の文書が無い。再エクスポート（`useParkingApi.ts` 等）の撤去計画が無い | `git log` で第3弾の PR を探す | 可（経緯を1段落で補い、本書へのリンクを足す） |
| D5 | `infra/README.md`（必要なら `docs/operations/` の該当文書） | FE のデプロイ構成の記述が、方針・Terraform・workflow で食い違う | 方針は「BE は AWS、FE は Cloudflare 上で SSR」（マスター回答 2026-11-17）。現状は `frontend/Dockerfile` が Node サーバ、`frontend-deploy.yml` が ECR / ECS、wrangler の workflow は無い | 可。**方針と現状の差を事実として1節に書く**。差を埋める実装はしない（R7 の P13）。Terraform ファイル内のコメントは変えない |
| D9 | `frontend/FRONTEND_CODING_CONVENTION.md` §4a「現状と未整備の点」 | 描画方針は規約に定めた（公開ページは SSR、ログイン後は SPA）。**公開ページの一覧が無い** | `frontend/app/pages` のうち未ログインで開けるページ（`middleware` と `definePageMeta` で判定）、`nuxt.config.ts` の `routeRules` | 可。公開ページの一覧表（パス・SSR が要る理由）を作り、§4a から参照する。設定は変えない |
| D10 | `FRONTEND_CODING_CONVENTION.md` §7、`docs/security/02_cookie_and_session.md` | 認証トークンの保存先と送信方法の記述が食い違う（`localStorage` + Bearer と、`SameSite=Strict` の Cookie） | `frontend/app/composables/useApi.ts`、`frontend/app/stores/useAuthStore.ts`、`backend/.../auth/controller/AuthTokenCookies.java`、`config/JwtAuthenticationFilter.java` | **直さない。** 実装の現状を表にして質問として報告する |
| D6 | `boundary-coordination.md` と `modular_monolith_boundary_debt_repayment.md` §7.3 | 引取ルールが矛盾 | — | **直さない。** #4022 にコメントで報告する |
| D8 | `frontend/FRONTEND_CODING_CONVENTION.md` のテストファイルの配置ルール、`TEST_CONVENTION.md` の参照箇所 | **規約はある**（spec は対象と同じディレクトリに置く）。実態が合っていない | 現状の件数（`tests/unit/` に 531、同居が約 290）を再計測する | 規約の文は変えない。「規約を実態に合わせる」「実態を規約へ寄せる」「新規分だけ規約どおりにする」の選択肢と件数を質問として報告する。文書の変更は裁可後 |

## 手順

1. 対象ごとに、実装の現状を確かめる
2. 文書を直す。数値を書くときは計測日を添える
3. 文書内のリンクとアンカーが切れていないことを確かめる。`CLAUDE.md` と `AGENTS.md` から参照されている見出しは名前を変えない
4. 番人が文書を読んでいないか確かめる（例: `TaskListCmpIdDuplicateGuardTest`、ハーネスの `.test.mjs`）。`node --test scripts/*.test.mjs` を通す

## やってはならないこと

- `CLAUDE.md`、`AGENTS.md`、`.claude/commands/`、`backend/.claudecode.md` の規律を変えること（運用の規則であり、マスターの所管）
- `README.md` の構成変更（R7 で提案する）
- 文書を直すついでのコード修正
- 完了証拠（`docs/evidence/`）と台帳の書き換え

## 完了条件

- D1・D2・D2b・D3・D4 が直っている
- D9 は、公開ページの一覧表がある
- D10 は、実装の現状の表と質問が報告にある
- D5 は、方針と現状の差が `infra/README.md` に書かれている
- D6 は #4022 への報告コメントの URL が報告にある
- D8 は、規約と実態の差の件数と選択肢が質問に載っている

## 検証

- `node --test scripts/*.test.mjs`
- リンク切れの確認（直した文書の中の相対リンクを `ls` で確かめる）
- CI の全チェックが緑（docs だけの PR でも必須チェックは走る）
