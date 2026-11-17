# 文書と実装の食い違いの一覧

> 親文書: [`refactor-instructions.md`](refactor-instructions.md)。
> 作成: 2026-11-17。規約・設計書と実装を照合して見つけた食い違いである。
>
> **扱い方**: 実装担当は、ここにある食い違いを**推測で解いてはならない**（`AGENTS.md`「正本」、`backend/.claudecode.md` §28）。どちらが正しいかを決めるのはマスターである。「R6 の扱い」が「是正」のものだけ、R6 が文書を実装に合わせて直す。それ以外は、一覧のまま上奏の材料にする。
>
> 凡例: **是正** = R6 が文書を直す。**上奏** = どちらが正しいかマスターの判断が要る。**起票済み** = 台帳に行がある。**起票** = 不具合の疑いとして台帳へ起票する。

## 1. 認証・セキュリティ

| # | 食い違い | 一方 | もう一方 | 扱い |
|---|---|---|---|---|
| A1 | 認証トークンの保存先と送り方 | `frontend/FRONTEND_CODING_CONVENTION.md` §5（SSR と認証の制約）・§7、`backend/BACKEND_CODING_CONVENTION.md` の認証の節、`docs/conventions/backend/08-security.md`、`backend/.claudecode.md` §2:「JWT は `localStorage`」「Cookie に格納しない」「Bearer で送る」 | 実装と設計書: FE はトークンをメモリにだけ持ち、Cookie が常に送られる。Web は Cookie、ネイティブアプリは Bearer の二本立て（`docs/features/F01.1_auth.md`、`docs/security/02_cookie_and_session.md`、`useAuthStore.ts`、`useApi.ts`、`JwtAuthenticationFilter.java`） | 上奏。規約5か所が古い。認証に関わるので、直す前に裁可を得る。直すときは、索引（規約の本体）と詳細文書を同じ PR で直す |
| A2 | リフレッシュの結果の状態数 | FE 規約 §5: 3状態 | 実装: 4状態（`useApi.ts` の `TokenRefreshResult`） | 是正 |
| A3 | 認証 Cookie の `Secure` | `docs/security/02_cookie_and_session.md`:「`AuthLoginController` は `secure(false)` をハードコード」 | 実装: `AuthTokenCookies` が `mannschaft.cookie.secure` を参照 | 是正 |
| A4 | access_token の Cookie の有効期間 | `docs/security/02_cookie_and_session.md`:「890 秒」「890 秒に修正すること」 | 実装: アクセストークンの有効期限そのまま | 上奏。文書の指示が未反映なのか、指示が撤回されたのかが分からない |
| A5 | 暗号鍵が本番で必須か | `docs/security/README.md`、`docs/security/09_key_management_and_rotation.md`、デプロイのチェックリスト: 必須 | `docs/operations/PRODUCTION_SETUP.md`:「未設定で機能 OFF」。`application.yml` の既定は空。`application-prod.yml` に記載なし | 上奏 |
| A6 | 許可オリジンが本番で必須か | 規約（`backend/.claudecode.md` §21）と運用文書: 本番で設定し、既定値を置かない | `application.yml` の既定は localhost。`application-prod.yml` に記載なし | 上奏。規約どおりに直すなら設定の変更になる（R7 の P3） |
| A7 | Webhook の系統数 | デプロイのチェックリスト、`README.md`: 4系統（SES を含む） | `docs/security/README.md`、`docs/security/01_authorization_baseline.md`: 3系統（SES は SQS へ移行済み） | 是正 |
| A8 | Stripe の環境変数 | `docs/operations/PRODUCTION_SETUP.md`: 秘密鍵と URL 2本だけ | `application.yml`、Terraform: Webhook のシークレット2種も要る | 是正 |
| A9 | Valkey の冗長化 | `docs/security/09_key_management_and_rotation.md`:「本番環境ではシングルポイント構成は禁止」 | Terraform: 1ノード、自動フェイルオーバー無効 | **所管あり。** 要件は決定済みで、実装は台帳 CMP-012 が持つ（再開の条件は「AWS 稼働準備の開始と、2台化の費用の承認」） |

## 2. 配置・インフラ

| # | 食い違い | 一方 | もう一方 | 扱い |
|---|---|---|---|---|
| I1 | FE の実行場所 | Terraform、`backend/.claudecode.md` §2、`CLAUDE.md`: Cloudflare Pages | `.github/workflows/frontend-deploy.yml`: ECR / ECS。`frontend/Dockerfile`: Node サーバ。`README.md`:「本番 FE コンテナ」 | 上奏。方針は Cloudflare で確定。実現手段が未決（R7 の P6）。台帳に CMP-260821-1235 がある |
| I2 | `README.md` のインフラ構成 | `README.md`: EC2、S3 + CloudFront、Lambda | Terraform: ECS、RDS、R2、Cloudflare Tunnel | 是正（R7 の P11 と併せて） |
| I3 | SSR のときに BE を呼ぶ先 | `docs/security/03_security_headers_and_csp.md`: コンテナの内部名 | Terraform: 公開ドメイン経由 | 上奏。R7 の P6 の論点 |
| I4 | 公開用の API の基点 | `docs/operations/PRODUCTION_SETUP.md`: 別ドメインの例 | Terraform、`docs/security/03_security_headers_and_csp.md`: 空文字（同一オリジン） | 是正 |
| I5 | **BE の公開パスと Cloudflare の振り分け** | Cloudflare が BE へ流すのは `/api/` と `/ws` で始まるパスだけ（`infra/terraform/modules/edge/main.tf`） | BE は `/billing/checkout/success`・`/billing/checkout/cancel`・`/billing/portal/return`・`/billing/payment-action/return`（決済からの戻り先）と `/incoming/*`（外部 Webhook の受け口）を、その外に持つ（`config/SecurityConfig.java`） | **起票済み**（台帳 CMP-261117-1220）。このまま本番を構築すると、決済後の戻りと外部 Webhook が BE に届かない疑いがある。案の比較は R7 の P7 |
| I6 | Tunnel の記述 | `docs/architecture/websocket_external_broker_valkey.md`:「IaC・docs に Tunnel の記述は皆無」 | `infra/terraform/modules/edge/main.tf`、`infra/README.md` に記述がある | 是正 |
| I7 | Spring Boot の版 | `README.md`: 3.3.x | ビルド設定: 3.5.13 | 是正 |
| I8 | 依存の自動更新 | `docs/security/04_dependency_and_supply_chain.md`: Dependabot | リポジトリには `.github/dependabot.yml` と `renovate.json` の両方がある | 上奏 |
| I9 | デプロイの認証 | 方針: OIDC。ロールは Terraform に定義済み（AWS 上の作成状況は未確認） | `backend-deploy.yml`、`frontend-deploy.yml`: 長期キー | 上奏。切り替えの計画は R7 の P5 |

## 3. バックエンドの規約

| # | 食い違い | 一方 | もう一方 | 扱い |
|---|---|---|---|---|
| B1 | イベントの発生時刻の型 | `backend/.claudecode.md` §6:「`LocalDateTime`。`Instant` は使わない」 | 同 §20:「新しい瞬間は `Instant` か `OffsetDateTime`。新規コードで `LocalDateTime` を足さない」 | 上奏 |
| B2 | コミットとマージの権限 | `backend/.claudecode.md` §28: マスターの承認なしにコミット・プッシュしない。マージは裁可の後に殿が行う | `AGENTS.md`「自動進行」: commit・push・PR・マージまで自動で進める | 上奏。Claude と Codex で規律が違うのは意図かもしれない |
| B3 | プロファイルの数 | `BACKEND_CODING_CONVENTION.md` §6: 基本4種 | 実物: 基本＋`ci`・`openapi-gen`・`prod`・`staging`・`test` | 是正 |
| B5 | `--tests` の可否 | `backend/.claudecode.md` §30 は `archTest --tests` を許し、同じ節で番人の絞り込みを禁じる | 対象が違う（タグで走る系統と、凍結ストアを書き戻す系統）が、読み手には矛盾に見える | 是正（書き分ける） |
| B6 | 静的解析 | `BACKEND_CODING_CONVENTION.md` §6・§8: Checkstyle と SpotBugs を CI と pre-commit に組み込む | ビルド設定と workflow に、どちらも無い | 上奏。規約を実態に合わせるか、導入するか |
| B7 | 結合テストのタスク | `BACKEND_CODING_CONVENTION.md` 末尾、`TEST_CONVENTION.md` §7: `integrationTest` などのタスク | 実在しない。`TEST_CONVENTION.md` には「例であり指示ではない」との注記が既にある | 是正（`BACKEND_CODING_CONVENTION.md` 側にも同じ注記） |
| B8 | 退会 | `CLAUDE.md` の旧記述:「匿名化（物理削除しない）」 | 実装と `backend/.claudecode.md` §8・§14: 30日後に物理削除 | **是正済み**（2026-11-17、マスター決定 M4） |

## 4. フロントエンドとテストの規約

| # | 食い違い | 一方 | もう一方 | 扱い |
|---|---|---|---|---|
| F1 | トーストの実装 | FE 規約 §12: `composables/useToast.ts`、`vue-sonner`、エラーは手で閉じるまで表示 | 実装: `useNotification.ts`（PrimeVue の Toast）。エラーも5秒で消える。同じ規約の §3a は「`useToast()` を直接呼ばない」と書く | 上奏。エラーの表示時間は仕様の判断 |
| F2 | プラグインのファイル名 | FE 規約 §2・§13: `errorHandler.client.ts`（camelCase） | 実物: `error-handler.client.ts` | 是正 |
| F3 | E2E の現状 | FE 規約 §11、`TEST_CONVENTION.md` §1.2・§9.2:「Playwright は将来導入」「`frontend/e2e/` に置く」 | 実物: 設定27本、spec 約550本が `frontend/tests/e2e/` にある | 是正 |
| F4 | FE の CI | `TEST_CONVENTION.md` §8:「フロントエンドは別リポジトリ」「Node 20」 | 実物: 同じリポジトリ、Node 22.23.2 固定 | 是正 |
| F5 | spec の置き場所 | FE 規約 §11: 対象と同じディレクトリ | 実物: `tests/unit/` に約530、同居が約290 | 上奏。既存を動かすか、規約を緩めるか。**新規は規約どおり同居**で確定 |
| F6 | 日時の直列化 | `docs/conventions/backend/20-datetime.md` の FE の節:「オフセットなし＝JST 前提」 | 同じ文書の API 境界の節:「オフセットを付けて直列化」 | 上奏（時刻設計の戦役 CMP-023 の所管） |
| F7 | `useApi.ts` と `middleware/auth.client.ts` のコメント | 「トークンを `localStorage` から復元」 | 復元するのは利用者情報だけ | 是正（コメントだけ。ただし `useApi.ts` は触らない対象なので、R6 ではなく上奏に回す） |
| F8 | 直書きの日本語 | FE 規約 §15: UI の文字列を直書きしない | `useApi.ts` に日本語のエラー文言が1つ残る | 起票（`useApi.ts` は本戦役で触らない） |

## 5. 運用文書

| # | 食い違い | 一方 | もう一方 | 扱い |
|---|---|---|---|---|
| O1 | 引取のルール | `docs/development/boundary-coordination.md`: 期限による自動の引取は禁止 | `docs/architecture/modular_monolith_boundary_debt_repayment.md` §7.3: 3日＋24時間で引取可 | #4022 へ報告 |
| O2 | 日常の検証コマンド | `docs/development/claude-md-details.md` §12: `./gradlew build` | `build` は番人まで含み重い。日常は `compileJava compileTestJava` | 是正 |
| O3 | リファクタの第3弾 | `docs/refactoring/phase4_overview.md` の実施表に名前だけある | 設計書が無い | 是正（経緯を1段落で補う） |
