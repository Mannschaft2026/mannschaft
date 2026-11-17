# 既決事項の一覧 — 聞き返す前にここを引く

> 親文書: [`refactor-instructions.md`](refactor-instructions.md)。
> 作成: 2026-11-17。規約・設計書・台帳を通読して照合した結果である。**出典が正本**で、本書は索引にすぎない。食い違ったら出典を信じ、本書の誤りを報告すること。
>
> 使い方: マスターへ質問を書く前、または「〜が無い」「〜は未確認」「〜を提案する」と書く前に、必ずこの一覧と出典を引く。ここにあることを質問にしてはならない。ここに無いことは、規約（`backend/.claudecode.md`、`backend/BACKEND_CODING_CONVENTION.md`、`frontend/FRONTEND_CODING_CONVENTION.md`、`TEST_CONVENTION.md`、`docs/conventions/`）、`docs/security/`、`docs/architecture/`、`docs/features/`、`docs/task-list.md`、`docs/master-confirmations.md` を検索してから質問にする。

## 1. 2026-11-17 にマスターが決めたこと

| # | 決定 | 記録先 |
|---|---|---|
| M1 | BE は AWS、FE は Cloudflare に置く | `backend/.claudecode.md` §2、`CLAUDE.md` プロジェクト概要 |
| M2 | 公開ページ（LP、ログイン画面、ブログ、チーム紹介など）は SSR。ログイン後の画面は例外なく、ページ遷移をなるべく発生させない SPA に統一する | `frontend/FRONTEND_CODING_CONVENTION.md` §4a |
| M3 | API を使うのは現在 Web の FE だけ。スマートフォンは当面 PWA で対応し、将来 Android / iOS のネイティブアプリに対応する | `backend/.claudecode.md` §2 |
| M4 | 退会は実装が正。受付直後に個人情報を消去し、30日後にバッチがユーザー本体と残りの個人データを物理削除する | `CLAUDE.md` アーキテクチャ思想の3・4、`docs/architecture/domain_db_design_principles.md` |
| M5 | BE 本体の構造変更は実装せず、提案書に留める | `refactor-instructions.md` |
| M6 | 追跡されている残骸は、中身を確かめて不要なら削除する。記事原稿2本は `docs/articles/` へ移し、古い計画書4本は削除する | `lanes/R1-repo-hygiene.md` |
| M7 | 本陣の作業木にあった未コミット変更3件はマスターの作業ではない。main の状態へ戻した | 付録 A の Q5 |

## 2. 配置・運用

| # | 決定 | 出典 |
|---|---|---|
| I1 | **本番環境はまだ構築されていない**（マスター回答 2026-09-01）。Terraform と deploy workflow は、これから作る構成の定義である | `docs/task-list.md` の CMP-260826-2129、CMP-260821-1235 |
| I2 | 本番に実データは無い。既存データを救うための移行 SQL や緩和策を既定で設計に入れない。ただしローカルと CI のシード、E2E フィクスチャは壊さない | マスター確認 2026-08-09 |
| I3 | FE は Cloudflare Pages（Nuxt）に置く想定。`/api/**` と `/ws` だけを Origin Rule で Cloudflare Tunnel 経由の BE へ流す。FE と BE は同一オリジン | `infra/terraform/modules/edge/main.tf`、`infra/terraform/envs/prod/main.tf` |
| I4 | 本番の入口は ALB から Cloudflare Tunnel へ移行済み（2026-07-10）。ECS は arm64、1台構成 | `infra/README.md` |
| I5 | AWS へのデプロイ認証は GitHub OIDC が方針。デプロイ用のロールは Terraform に定義されている（AWS 上に実際に作られているかは未確認。本番は未構築）。deploy workflow は長期キーのままである | `infra/README.md`、`infra/terraform/bootstrap/main.tf` |
| I6 | 秘密は Secrets Manager に置く。Terraform は箱だけを作り、値は手動で入れる | `infra/README.md`、`infra/terraform/modules/app/main.tf` |
| I7 | 環境変数は `MANNSCHAFT_` で始める。`local` 以外のプロファイルでは、必須の値に既定値を置かない（未設定なら起動に失敗させる） | `backend/.claudecode.md` §21、`BACKEND_CODING_CONVENTION.md` §6 |
| I8 | 新しい秘密鍵を足すときは、鍵管理の表・本番セットアップ手順・CI の secrets・`application-prod.yml` を同じ PR で更新する | `docs/security/09_key_management_and_rotation.md` |
| I9 | `application-test.yml` が `src/main/resources` と `src/test/resources` の両方にあるのは**意図した設計**である。前者は起動用、後者はテスト用で、番人が両者の同期を見ている | `docs/development/test_profile_key_coverage.md` |
| I10 | ローカルの BE 常駐は `start-backend.vbs` → `start-backend.ps1` → WSL の `backend-loop.sh`。検証には使わず、検証は `scripts/verification-run.mjs` を通す | `.claude/commands/陣立て.md`、`docs/development/local-verification.md` |
| I11 | ポートは、本陣が BE 8080 / FE 3000、検証用が BE 8081 / FE 3001。検証用 BE は CORS の許可オリジンを上書きする。ブラウザは `localhost` で開く（`127.0.0.1` では15分後に強制ログアウトされる） | `CLAUDE.md`、`frontend/FRONTEND_CODING_CONVENTION.md` §7 |

## 3. 認証・セキュリティ

| # | 決定 | 出典 |
|---|---|---|
| S1 | 認証は二本立て。**Web は HttpOnly Cookie、ネイティブアプリは Bearer ヘッダー**。BE は Bearer を優先し、無ければ Cookie を読む | `docs/features/F01.1_auth.md`、`docs/security/02_cookie_and_session.md`、`config/JwtAuthenticationFilter.java` |
| S2 | FE はトークンをメモリ上にだけ持つ。`localStorage` に置くのは利用者情報と有効期限だけ。API へは Cookie が常に送られ、メモリにトークンがあるときだけ Bearer も付く | `frontend/app/stores/useAuthStore.ts`、`frontend/app/composables/useApi.ts` |
| S3 | `refresh_token` は Cookie と body の両方で返す（ネイティブアプリ向け）。消さない | `docs/security/02_cookie_and_session.md` |
| S4 | 認証 Cookie は `HttpOnly`・`SameSite=Strict`・`Path=/`。`Secure` は `MANNSCHAFT_COOKIE_SECURE`（本番は true）。発行と削除で属性を揃える | 同上、`auth/controller/AuthTokenCookies.java` |
| S5 | CSRF トークンは使わない。`SameSite=Strict` と同一オリジンで守る。意図した設計であり、維持する | `docs/security/02_cookie_and_session.md` §1 |
| S6 | リフレッシュは single-flight。結果は4状態（成功／認証失敗／一時障害／セッション変更）。一時障害ではログアウトしない | `frontend/app/composables/useApi.ts` |
| S7 | リフレッシュトークンのローテーションは行ロックと猶予60秒。猶予を過ぎた再提示は全端末を無効化する | `docs/security/02_cookie_and_session.md` §4 |
| S8 | `SYSTEM_ADMIN` は JWT に載る。チーム・組織の `ADMIN` は載せず、リクエストごとに DB で判定する | 同上 §3.1 |
| S9 | 既定は全リクエスト認証必須。新しい公開パスは `@IntentionallyPublic` に matcher 式を付け、レート制限を実装してからマージする | `docs/security/README.md`、`docs/security/public_api_addition_checklist.md` |
| S10 | 認可で `@PreAuthorize` に書けないものは、Controller → Facade（`@Transactional` なし）→ `XxxAccessPolicy` → `AccessControlService` の層で行う。業務 Service は Policy を呼ばない | `backend/.claudecode.md` §1 |
| S11 | 認可のマーカーは4種（`@SelfScopedEndpoint` / `@AuthorizedInService` / `@AuthorizedByPathConfig` / `@IntentionallyPublic`）。転用しない。免除リストは無い | `backend/.claudecode.md` §30、`docs/conventions/backend/30-arch-guard-tests.md` |
| S12 | 暗号鍵の版管理は、**暗号文に埋め込まず、`encryption_key_version` 列で持つ**方式に決定済み。列は追加済みで、再暗号化のバッチと次の鍵の受け口が未実装 | `docs/security/09_key_management_and_rotation.md` §2.3 |
| S13 | Stripe SDK は 28.x に固定する。29.x 以降は手数料の仕組みが黙って壊れる | `backend/build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts` のコメント、`docs/features/F08.9_membership_billing_paywall/` |
| S14 | Stripe の Webhook は署名を検証する。platform 用と Connect 用のシークレットは別系統 | `docs/security/01_authorization_baseline.md`、`application.yml` |
| S15 | CORS は `*` を使わない。許可オリジンは環境変数で注入する | `BACKEND_CODING_CONVENTION.md` §8、`docs/security/03_security_headers_and_csp.md` |
| S16 | CSP は nonce 方式で強制する。`unsafe-eval` を足さない | `docs/security/03_security_headers_and_csp.md` |

## 4. バックエンド

| # | 決定 | 出典 |
|---|---|---|
| B1 | Entity を API のレスポンスにそのまま返さない。DTO は作成用と更新用を分け、一覧は `Summary`、詳細は `Detail` | `backend/.claudecode.md` §19 |
| B2 | 機能をまたぐ連携はイベントで行う。リスナーは `AFTER_COMMIT`。同じ機能の中は直接呼ぶ | 同 §6 |
| B3 | 付随する通知は、業務トランザクションでイベントを発行し、`AFTER_COMMIT` と `@Async("event-pool")` のリスナーを経て、1件ごとに `REQUIRES_NEW` で送る。`@Transactional` を外すだけの修正はしない | 同 §17 原則5 |
| B4 | `@Async` はプールを必ず指定する。無指定は禁止 | 同 §15 |
| B5 | `@Scheduled` には `@SchedulerLock`（`lockAtMostFor` を明示）と `@BatchEndpoint` を付ける | 同 §30.1 |
| B6 | catch を空にしない。必ずログに出すか、投げ直す。ただし非同期の配送リスナーの境界は、`try/catch` と ERROR ログが正規形である | `BACKEND_CODING_CONVENTION.md` §4、`backend/.claudecode.md` §17 |
| B7 | DB の日時は UTC の壁時計。アプリ層は JST。新しく「起きた瞬間」を持つなら `Instant` か `OffsetDateTime` | `backend/.claudecode.md` §20 |
| B8 | Enum は `@Enumerated(STRING)` で保存する。定数の追加は二段階で展開する。別ドメインの enum を `switch` しない（凍結ストアの D-1 が落ちる） | 同 §23、`BACKEND_CODING_CONVENTION.md` §5 |
| B9 | ページネーションは、通常が `?page=0&size=20`（最大100）、リアルタイム系が `?cursor=&limit=` | `backend/.claudecode.md` §12 |
| B10 | 主要な業務データは `@Version` で楽観ロックする。更新リクエストに `version` を必須とする | 同 §22 |
| B11 | Service の分割の目安は、有効行500・メソッド100行・DI が7個を超えたとき | `BACKEND_CODING_CONVENTION.md` §2.1 |
| B12 | 新しい ErrorCode を足すときは、5言語の訳を同じ PR で足す。HTTP ステータスとの対応は `GlobalExceptionHandler` に一元化されている | 同 §4、`backend/.claudecode.md` §3 |
| B13 | Flyway は `V{全体の最大major+1}.{yyyyMMddHHmmss}__説明.sql`。既存のファイルは変えない。migration か Entity を変えたら ER 図を再生成する | `backend/.claudecode.md` §18 |
| B14 | API は `/api/v1/…`。破壊的な変更のときだけ版を上げ、旧版を3か月以上並行で動かす | 同 §4 |
| B15 | ファイルのアップロードは、署名付き URL の3段階で行う | 同 §26 |

## 5. フロントエンド

| # | 決定 | 出典 |
|---|---|---|
| F1 | 新しい UI は共通コンポーネントを使う（`PageHeader`、`SectionCard`、`PageLoading`、`DashboardEmptyState`、`DashboardErrorState` など）。直書きしない | `frontend/FRONTEND_CODING_CONVENTION.md` §3a |
| F2 | **取得の失敗を「空」に見せない。** 読み込み中／失敗／空／表示の4状態を持ち、失敗は `DashboardErrorState`（再試行ボタン付き）で見せる。手本は `pages/my/shift-availability.vue` | 同 §3a |
| F3 | エラーの処理は `useErrorHandler()`、トーストは `useNotification()` を使う。`try/catch` と `console.error` の直書きはしない | 同 §3a |
| F4 | 5xx・429・402 は `useApi` が既に表示している。catch の側で重ねて表示しない | `frontend/app/composables/useApi.ts` |
| F5 | アプリ内の遷移は `<NuxtLink>` か `navigateTo()`。スコープ配下は永続シェルに載せる。ページのルート要素は1つ | 同 §4a |
| F6 | 未ログインで開けるページは、`middleware: 'auth'` を付けていないページである（認証の middleware は付けたページだけに効く）。`definePageMeta` の `auth: false` は印にすぎず、参照するコードは無い | `frontend/app/pages/shared/[token].vue` のコメント、`frontend/app/middleware/` |
| F7 | 画面は 390px 幅で横に伸びない。モバイルとデスクトップの境界は 768px。タップ領域は 44px 以上 | 同 §3b |
| F8 | 状態は Composables を優先し、全体で共有するときだけ Pinia を使う | 同 §4 |
| F9 | 機能ごとの API 呼び出しは `use[Feature].ts` に置く。`useApi.ts` には共通の設定だけを置く | 同 §5 |
| F10 | コンポーネントが300行を超えたら、composable と子コンポーネントに分ける | 同 §6 |
| F11 | ESLint の抑止は、`// eslint-disable-next-line ルール名 -- 理由` を1行単位で書く。ファイル単位の抑止は原則しない | 同 §8 |
| F12 | **spec は対象と同じディレクトリに置く。** `__tests__/` は作らない | 同 §11 |
| F13 | テストで、引数なしの `new Date()` や `dayjs()` から期待値を組まない。時計を固定する | `TEST_CONVENTION.md` §2.4.1 |
| F14 | 文字列は6言語すべてにその言語の訳を入れる。新しいロケールのファイルを足したら `nuxt.config.ts` にも登録する（lint と単体テストでは検出されない） | 同 §15 |
| F15 | 日時の整形は `useDatetime()` と `utils/localDate.ts` を使う。自前で整形しない。日付の入力は `<DatePicker>` | 同 §15・§16 |
| F16 | 型は `types/generated/` を手で編集しない。新しい API の型は生成型を優先し、既存の手動型は段階的に移す | `CLAUDE.md`「型定義の管理」 |

## 6. テスト・番人

| # | 決定 | 出典 |
|---|---|---|
| T1 | テストで `Thread.sleep` を使わない。`@Disabled` は1スプリント以内に解消する | `TEST_CONVENTION.md` §10 |
| T2 | 時刻に依存するテストの安全な書き方は3つ。本番コードに `Clock` を注入する／入力と期待値の両方を相対にする／固定の入力を引数で渡す | 同 §2.4 |
| T3 | 実効的に `@Transactional` なテストでは `AFTER_COMMIT` のリスナーが動かない。通知やイベントの配送を、そのようなテストで検証しない | 同 §9.7、`backend/.claudecode.md` §6 |
| T4 | テストデータを手書きの INSERT で作らない | `TEST_CONVENTION.md` §10 |
| T5 | ArchUnit の番人は2系統ある。JUnit のタグで走る系統（`archTest`）は、完全修飾名を指定した絞り込みが許されている。凍結ストアを書き戻す系統（`archUnitTest`）は絞ってはならない | 同 §7.1、`backend/.claudecode.md` §30 |
| T6 | 凍結ストアの行が減ったときは、上限の定数を自分で変えない。締めのレーンが後で引き下げる | `docs/conventions/backend/30-arch-guard-tests.md`、`docs/architecture/archunit_freeze_history.md` |
| T7 | CI は6つの shard に分かれる。テストクラスを足したり改名したりしたら `verifyShardCoverage` を通す | `backend/.claudecode.md` §31 |
| T8 | E2E でスピナーの消滅を待つときは、共通ヘルパ `waitForSpinnerGone` を使う。ハイドレーションの完了は `waitForHydration` で待つ | `frontend/tests/e2e/helpers/wait.ts` |

## 7. 進め方

| # | 決定 | 出典 |
|---|---|---|
| P1 | 正規の流れは、軍議 → 御裁可 → 試練 → 出陣 → 検分 → 実機 → アリシゼーション → 凱旋。軍議は Codex の検分を通してから上奏する | `CLAUDE.md`、`.claude/commands/` |
| P2 | 実装に着手する前に、main 上の台帳の行を「実装中」にする。そのための台帳だけの PR を先にマージする。実装の PR で初めて行を足してはならない | `.claude/commands/出陣.md` |
| P3 | 足軽は `Agent(isolation:"worktree")` で起動し、モデルを毎回明示する。既定は Sonnet で、Sonnet が詰まったときだけ Opus に上げる | `.claude/commands/出陣.md`、`.claude/commands/役割分担.md` |
| P4 | 検分せずにマージしない。CI が緑なだけでは足りない。検分の既定は Codex | `.claude/commands/凱旋.md` |
| P5 | PR の scope は変更ファイルから決まる。`tooling` になるのは `scripts/`・`.github/`・`.claude/`・`docs/` と、限られた規約ファイルだけ。`frontend/` や `infra/` の文書、`.gitignore`、テストの設定ファイルは `app` になる。`app` は実機とアリシゼーションの両方が要る | `docs/development/completion-evidence.md`、`scripts/completion-evidence.mjs` |
| P6 | 凱旋では、worktree の掃除の確認（`worktree-cleanup.mjs --check`）と、WSL の検証コピーの確認（`verification-run.mjs --check`）を済ませる | `.claude/commands/凱旋.md` |
| P7 | 規約や仕様書を変える必要があるときは、マスターへ上奏する。足軽は設計の判断をしない。規約どうしの食い違いは、推測で解かずに報告する | `backend/.claudecode.md` §28、`AGENTS.md` |
| P8 | 実証していない観測を台帳へ起票しない | `.claude/commands/アリシゼーション.md` |
| P9 | 構造だけを変える分割でも、自動の配線が黙って壊れることがある。分割した直後から `@Cacheable` が一度も効かなくなった先例がある（同じ Bean の中の呼び出しには AOP が効かない） | `docs/refactoring/phase4_overview.md` |
| P10 | 握りつぶし catch の番人は、1文だけの catch しか検出しない。`console.error` を1行足せば lint は通るが、握りつぶしは残る | `frontend/eslint-rules/swallow-catch-rules.mjs`、台帳の CMP-260922-2045 |
