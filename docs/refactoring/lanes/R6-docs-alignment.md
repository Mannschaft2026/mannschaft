# R6 — 文書を実装に合わせる

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書、[`../decided-facts.md`](../decided-facts.md)、[`../known-doc-drift.md`](../known-doc-drift.md) を読むこと。進め方は親文書 §4-1 の正規フローに従う。
> 足軽のモデル: Sonnet。
> このレーンが触るのは文書だけである。コードと設定は変えない。規約4本（`TEST_CONVENTION.md`、`frontend/FRONTEND_CODING_CONVENTION.md`、`backend/BACKEND_CODING_CONVENTION.md`、`backend/.claudecode.md`）を編集するのは、このレーンだけである。

## 目的

実装担当が最初に読む文書に、実在しないコマンドや、直し済みの欠陥が「現状」として残っている。読んだ人を誤らせる箇所を、実装に合わせて直す。

## 原則

1. **対象は [`../known-doc-drift.md`](../known-doc-drift.md) で「是正」とした項目だけである。** 「上奏」とした項目は、どちらが正しいかをマスターが決めるまで直さない。推測で解かない
2. 直す前に、実装の現状を**自分で確かめる**（ファイルと行を PR 本文に書く）。一覧の記述を鵜呑みにしない。一覧が誤っていたら、一覧を直して報告する
3. 文書のほうが正しく見えたら（実装が仕様から外れている疑い）、直さずに報告する
4. 経緯は消さない。「〜は YYYY-MM-DD に修正済み」と1行で残すか、経緯の文書へ移す
5. **規約の規則そのものを変えない。** このレーンが直すのは、事実の記述（ファイル名、タスク名、状態の数、現状の説明）である。規則を変える必要があるなら上奏事項である（決定 P7）
6. 1 PR は1文書を基本とし、**scope ごとに分ける**（決定 P5）
   - `tooling` になる文書: `docs/` 配下、`README.md`、`TEST_CONVENTION.md`、`backend/.claudecode.md`、`CLAUDE.md`、`AGENTS.md`
   - `app` になる文書: `frontend/FRONTEND_CODING_CONVENTION.md`、`backend/BACKEND_CODING_CONVENTION.md`、`infra/README.md`。文書だけの変更でも、完了ゲートは `app` の扱いになる
7. 台帳（`docs/task-list.md`）と完了証拠（`docs/evidence/`）は対象外

## 対象

### `tooling` の文書

| # | 文書 | 直すこと | 一覧の番号 |
|---|---|---|---|
| 1 | `docs/security/02_cookie_and_session.md` | `Secure` をハードコードしているという記述を、現状（設定値を参照）に合わせる | A3 |
| 2 | `docs/operations/PRODUCTION_DEPLOY_CHECKLIST.md`、`README.md` | Webhook の系統数（SES は SQS へ移行済み） | A7 |
| 3 | `docs/operations/PRODUCTION_SETUP.md` | Stripe の環境変数の一覧に Webhook のシークレット2種を足す。公開用の API の基点の例を、同一オリジン（空文字）に合わせる | A8、I4 |
| 4 | `docs/architecture/websocket_external_broker_valkey.md` | 「Tunnel の記述は皆無」という記述 | I6 |
| 5 | `README.md` | Spring Boot の版。インフラ構成の節は、R7 の P11 の裁可を待つ（構成の書き直しになるため）。版だけを直す | I7 |
| 6 | `backend/.claudecode.md` §30 | `--tests` の可否を、2系統（タグで走る系統と、凍結ストアを書き戻す系統）に書き分ける。規則は変えない | B5 |
| 7 | `docs/conventions/backend/03-error-handling.md` | ページ番号の例を0始まりに | B4 |
| 8 | `TEST_CONVENTION.md` | §1.2・§9.2・§9.3 の E2E の現状（Playwright は導入済み。置き場所は `frontend/tests/e2e/`）。§8 の FE の CI（同じリポジトリ、Node 22.23.2）。§7・§8 の `integrationTest` などの例には「例であり指示ではない」という注記が既にあるので、実在するタグ（`perf`・`archunit`・`er-diagram`）を併記するに留める | F3、F4、B7 |
| 9 | `docs/development/claude-md-details.md` §12 | 日常の検証コマンドを、親文書 §6 に合わせる | O2 |
| 10 | `docs/refactoring/phase4_overview.md` | 第3弾の経緯を1段落で補う（`git log` で当時の PR を探す）。本指示書へのリンクを足す | O3 |
| 11 | `docs/testing/public-pages.md`（新設） | **公開ページの一覧**を作る。下の「公開ページの一覧の作り方」 | — |
| 12 | `docs/development/boundary-coordination.md` と境界返済の計画書の引取ルールの矛盾 | **直さない。** #4022 にコメントで報告する | O1 |

### `app` の文書（PR を分け、完了ゲートは `app`）

| # | 文書 | 直すこと | 一覧の番号 |
|---|---|---|---|
| 13 | `frontend/FRONTEND_CODING_CONVENTION.md` | §5 のリフレッシュの結果を4状態に。§2・§13 のプラグインのファイル名。§11 の「Playwright は将来導入」。§4a の「現状と未整備の点」から、公開ページの一覧（11番）へのリンク | A2、F2、F3 |
| 14 | `backend/BACKEND_CODING_CONVENTION.md` | §6 のプロファイルの一覧を実物に。末尾の `integrationTest` の記述に、`TEST_CONVENTION.md` と同じ注記 | B3、B7 |
| 15 | `infra/README.md` | 「本番は未構築」（台帳 CMP-260826-2129）と、FE の配置の方針（Cloudflare）と現状（Node サーバ向けのビルド、ECS 向けの workflow）の差を、事実として1節に書く。差を埋める実装はしない | I1 |

13〜15 は、判断 N2（挙動を変えない `app` の PR の扱い）の答えを待ってから着手してよい。待つ間に 1〜12 を進める。

### 直さないもの（上奏の材料として、一覧のまま残す）

認証トークンの記述（A1）、Cookie の有効期間（A4）、暗号鍵と許可オリジンの必須性（A5・A6）、Valkey の冗長化（A9）、SSR のときに BE を呼ぶ先（I3）、依存の自動更新（I8）、イベントの時刻の型（B1）、コミットとマージの権限（B2）、静的解析（B6）、トースト（F1）、spec の置き場所（F5）、日時の直列化（F6）、`useApi.ts` のコメントと直書き（F7・F8）。

## 公開ページの一覧の作り方（11番）

描画方針（決定 M2）は「公開ページは SSR、ログイン後は SPA」である。どのページが公開ページかの一覧が無い。

1. `frontend/app/pages` の全ページについて、**`middleware: 'auth'` が付いていないもの**を拾う。認証の middleware は、付けたページだけに効く（決定 F6）。`definePageMeta` の `auth: false` は印にすぎないので、判定に使わない
2. `layout` が `landing`・`public`・`auth` のもの、`guest` middleware が付いたものを併せて見る
3. `middleware` の記述が無いページには、親ルートで保護されているもの（例: `teams/[slug]/` の配下）が含まれる。親をたどって確かめる
4. `nuxt.config.ts` の `routeRules` で `ssr: false` になっているパスを併記する
5. 表にする: パス／レイアウト／middleware／SSR が要る理由（検索エンジン、共有したときの見出し、初回の表示）／根拠（ファイルと行）
6. 既存の定義（`docs/features/F19.1_public_pages_identity_disclosure.md` の公開ページの節）と食い違いがあれば、報告する

起草時に確かめた例: `/`（LP）、`/login`・`/register`・`/forgot-password`・`/reset-password`、`/product`・`/features/[slug]`・`/use-cases/[slug]`・`/terms`・`/privacy`・`/commerce-disclosure`、`/public/teams/[slug]`・`/public/organizations/[slug]`・`/public/users/[id]`、`/discover/teams`・`/discover/organizations`、`/blog/posts/[slug]`、`/activity/[id]`、`/shared/[token]`、`/invite/[token]`。これがすべてではない。

`nuxt.config.ts` は変えない。設定でページの種別ごとに描画方式を明示する案は、R7 の P6 が扱う。

## 手順

1. 対象ごとに、実装の現状を確かめる
2. 文書を直す。数値を書くときは、測った日を添える
3. 文書の中のリンクとアンカーが切れていないことを確かめる。`CLAUDE.md` と `AGENTS.md` から参照されている見出しは、名前を変えない
4. 番人が文書を読んでいないかを確かめる（`node --test scripts/*.test.mjs` を前後で実行する）

## やってはならないこと

- 「上奏」とした項目を直すこと
- 規約の規則を変えること
- `CLAUDE.md`、`AGENTS.md`、`.claude/commands/` の規律を変えること
- 文書を直すついでのコードの修正
- 台帳と完了証拠の書き換え
- `nuxt.config.ts`、Terraform のファイル（コメントを含む）の変更

## 完了条件

1. 1〜10 が直っている
2. 11 の公開ページの一覧がある
3. 12 は、#4022 への報告コメントの URL が報告にある
4. 13〜15 は、直っているか、判断 N2 を待っていると報告にある
5. 一覧（`known-doc-drift.md`）の「是正」の項目に、直した PR の番号が追記されている
6. 新しく見つけた食い違いが、一覧に足されている

## 検証

- `node --test scripts/*.test.mjs`
- 直した文書の中の相対リンクが切れていないこと
- 親文書 §9-3 の完了ゲート（PR の scope に従う）
- CI の全チェックが緑で、検分に合格している
