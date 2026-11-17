# refactor-instructions.md — Mannschaft リファクタリング指示書

> 起草: 2026-11-17（基準 commit `1432237a7d`）。起草者: 殿（軍議）＋家老6名の偵察。
> 使い方: 実装担当（足軽）に `/goal docs/refactoring/refactor-instructions.md に書かれたことを完遂しろ` と渡す。
> 並行で進める場合は、セッションごとに `docs/refactoring/lanes/R*.md` を1本ずつ渡す（本書の規律は全レーン共通で適用される）。
> 本書の数値は起草時点の実測または文書引用である。着手時に必ず再計測すること（§6）。
> 正本 Issue: **#4599**（担当宣言はここのコメントで行う）。台帳: **CMP-261117-1026**。
> マスターの裁可（2026-11-17）: BE 本体は提案のみとする方針を採用。付録 A の質問 Q1〜Q6 は回答済み。

---

## 1. Objective

既存仕様を壊さずに技術的負債を減らし、今後の変更を安全で速くする。見た目の綺麗さは目的ではない。

今回の射程は、**他の進行中戦役が拾っていない負債**に限る。

- リポジトリの残骸と使い捨て設定の撤去（R1・R2）
- FE の握りつぶし catch 抑止 479 件の返済（R3）
- FE の巨大ファイルの構造分割（R4）
- BE テスト基盤の規約違反の是正（R5）
- 文書と実装の不整合の是正（R6）
- BE 本体の大型負債に対する**提案書の作成**（R7。実装しない）

BE のドメイン境界負債（越境 Entity / Repository / `@Transactional`、`common` の業務型）は、戦役 **CMP-261021-0017（親 Issue #4022）** が141レーン体制で返済中である。本書はそこへ一切手を出さない（§4-3、§11）。

完了の定義: 各レーンの「完了条件」をすべて満たし、§9 の検証が通り、§10 の形式で報告が出ていること。レーンの一部が Stop And Ask で止まった場合は、止まった理由を報告して作業を中断してよい。ただし、その状態は「一部完了」または「質問待ち」であり、**完了とは呼ばない**。台帳の行も完了にしない。

---

## 2. Project Understanding

### 2-1. 何を作っているか

Mannschaft は汎用の組織管理プラットフォーム。スポーツチーム・学校・会社・店舗・町内会などを、モジュール式テンプレートで1つの製品として扱う（`README.md`）。

- テナントは「個人・チーム・組織」の3層。組織は入れ子にでき、利用者は複数に所属できる
- ロールは `SYSTEM_ADMIN` / `ADMIN` / `DEPUTY_ADMIN` / `MEMBER` / `SUPPORTER` / `GUEST`。`SYSTEM_ADMIN` は JWT に載り、チーム・組織の `ADMIN` はリクエストごとに DB で判定する
- 課金はモジュール単位の月額サブスク、組織数課金、ストレージ課金。決済は Stripe（Connect を含む）
- 本番に実データは無い（マスター確認 2026-08-09）。ただしローカルと CI のシード、E2E フィクスチャは壊してはならない

### 2-2. 構成と規模（2026-11-17 実測）

| 領域 | 場所 | 規模 |
|---|---|---|
| BE | `backend/`（Java 21、Spring Boot 3.5.13、Gradle 8.14、Spring Modulith 1.4） | main 8,642 クラス、ドメインパッケージ 122、テスト 3,619 ファイル |
| DB | `backend/src/main/resources/db/migration/` | Flyway migration 1,238 本。baseline なしで毎回全適用 |
| FE | `frontend/`（Nuxt 3.21、Vue 3.5、Pinia 3、PrimeVue 4.5、6言語） | pages 597、components 1,101、composables 624、stores 21 |
| インフラ | `infra/terraform/`、`.github/workflows/`（22本） | Cloudflare（Pages・Tunnel・R2）＋ AWS（ECS Fargate 1台・RDS MySQL・Valkey・SES・SQS） |
| 開発ハーネス | `scripts/`、`backend/scripts/`、`frontend/scripts/`、`.githooks/` | worktree 管理、検証コピー、完了証拠、番人 |

API の利用者は現在 Web の FE だけである。モバイルアプリは存在しない（マスター回答 2026-11-17）。スマートフォンには当面 PWA（`@vite-pwa/nuxt`、`nuxt.config.ts` の `pwa:`）で対応し、将来 Android / iOS のネイティブアプリに対応する計画がある。したがって、ネイティブアプリ向けに用意されている仕様（`refresh_token` を Cookie と body の両方で返す等）は**消さない**。PWA の manifest と service worker の設定も変えない。

### 2-3. 主要エントリーポイント

- BE: `MannschaftApplication`、`config/SecurityConfig.java`（633行、`requestMatchers` 92箇所）、`config/JwtAuthenticationFilter`、`config/WebSocketConfig`、`common/GlobalExceptionHandler.java`（3,361行）
- FE: `frontend/nuxt.config.ts`（約1,100行）、`frontend/app/composables/useApi.ts`（全 API の共通ラッパ。401 リフレッシュを内包）、`frontend/app/middleware/`（10本）
- 型の契約: `docs/openapi.json`（BE から生成）→ `frontend/app/types/generated/index.ts`（生成。手編集禁止）

### 2-4. データの流れ

1. ブラウザは Cloudflare の apex ドメインへ接続する。`/api/**` と `/ws` だけが Origin Rule で Cloudflare Tunnel 経由の Spring Boot（ECS）へ流れ、それ以外は FE へ流れる（`infra/terraform/modules/edge/main.tf`）。**FE と BE は同一オリジン**である
   - 決定済みの構成（マスター回答 2026-11-17）: **BE は AWS、FE は Cloudflare**。描画の方針は「**ログイン画面は SSR、ログイン後はページ遷移をなるべく発生させない SPA**」
   - 描画の実態: `nuxt.config.ts` は SSR を全体で有効にしたまま（`ssr: false` は `/activities/**` と未公開機能のパスだけ）。ただしログイン後の画面は、サーバでは枠だけを出し、中身はブラウザ側で取得して描く。親ルートが `<NuxtPage/>` を持つ「永続シェル」方式（`pages/teams/[slug].vue`、`pages/organizations/[slug].vue`、`pages/villages/[id].vue` など）で、遷移のたびの再取得を避けている。`docs/features/F02.2_dashboard.md` も「SSR 不要。SPA + スケルトン UI」と決めている。原則は `backend/.claudecode.md` §2、実装規則は `frontend/FRONTEND_CODING_CONVENTION.md` §4a に定めた（2026-11-17 追記）
   - 現状との差: FE のビルドは Node サーバ向け（`frontend/Dockerfile` が `node server/index.mjs` を起動）で、Cloudflare 上で SSR するための Nitro の preset も、wrangler でデプロイする workflow も無い。既存の `frontend-deploy.yml` は ECR / ECS 向けである。この差は負債 D5 として扱い、実装せず提案（R7 の P13）にする
2. FE は `useApi` 経由で `/api/v1/**` を呼ぶ。認証は、`localStorage` の JWT を `Authorization: Bearer` ヘッダーで送る方式（`frontend/FRONTEND_CODING_CONVENTION.md` §7）。これとは別に、リフレッシュ用のトークンが `SameSite=Strict` の Cookie としても発行される
3. BE は Controller → Service → Repository（JPA）→ MySQL。ドメイン間は ID 参照と Service 経由が原則
4. 付随処理は、業務トランザクション内でイベントを発行し、`AFTER_COMMIT` のリスナーが非同期に実行する。通知とメールは outbox 方式
5. 定期バッチは `@Scheduled` と ShedLock で排他する

### 2-5. 外部依存

Stripe（決済・Connect・Webhook）、AWS SES / SNS / SQS（メール）、Cloudflare R2（S3 互換ストレージ。ローカルは MinIO）、Web Push、LINE（ログインと Bot）、Google（OAuth・カレンダー・Places）、Apple（OAuth）、WebAuthn、AI ダイジェスト、翻訳 API、Slack（エラー監視）。

---

## 3. Behaviors To Preserve

以下は絶対に変えてはならない。変更が必要に見えたら、実装を止めて質問する（§5）。

### 3-1. 認証・セッション・境界

| 不変条件 | 根拠 |
|---|---|
| 認証 Cookie は `HttpOnly`・`SameSite=Strict`・`Path=/`。発行と削除で属性を揃える | `backend/.../auth/controller/AuthTokenCookies.java`、`docs/security/02_cookie_and_session.md` |
| CSRF トークンは使わない。防御は SameSite=Strict と同一オリジンに依存する。FE と BE を別サイトに分けない | `SecurityConfig.java`、同上文書 §1 |
| CORS と WebSocket の許可オリジンは `mannschaft.allowed-origins` の一本。`*` を使わない | `application.yml`、`CorsConfig.java`、`WebSocketConfig.java` |
| BE の公開パスは `/api/` と `/ws` の下だけ。それ以外に置くと本番で届かない | `infra/terraform/modules/edge/main.tf` |
| 本番の `NUXT_PUBLIC_API_BASE` は空文字（同一オリジン）。SSR 時だけ `NUXT_INTERNAL_API_BASE` を使う | `frontend/app/composables/useApiBaseUrl.ts`、`nuxt.config.ts` |
| FE の 401 リフレッシュは single-flight。結果は4状態で、一時障害（transient）ではログアウトしない | `frontend/app/composables/useApi.ts` |
| リフレッシュトークンのローテーションは行ロック＋猶予60秒。猶予超過の再提示は全端末を無効化する | `docs/security/02_cookie_and_session.md` §3-4 |
| 既定は `anyRequest().authenticated()`。公開パスの一覧を増減しない | `SecurityConfig.java` |
| `JWT_SECRET`・`ENCRYPTION_KEY`・`HMAC_KEY` の扱いと暗号文の形式を変えない | `application-prod.yml`、`common/EncryptionService.java` |

### 3-2. 課金・外部連携

- Stripe の platform 用と Connect 用の Webhook シークレットは別系統。署名検証の後に冪等処理を行う順序を変えない
- Stripe SDK は 28.2.0 固定。上げると手数料の仕組みが黙って壊れる（`backend/build.gradle.kts` のコメント）
- Stripe 復帰 URL の署名済み state（HMAC・purpose・expiry）を変えない
- 過去に送ったメール内のリンク（配信停止、開封ピクセル）と OAuth コールバック URL、WebAuthn の rp-id を変えない

### 3-3. データと契約

- 既存の Flyway migration は**編集も改名もしない**。checksum 不一致で既存環境が起動しなくなる
- 公開 API のパス・リクエスト・レスポンスの形を変えない。`docs/openapi.json` に差分が出たら、それは挙動変更である
- 監査ログなどの時刻は UTC 格納。日時の方針は `docs/architecture/datetime_policy_utc_instant_vs_wallclock.md`
- 退会は匿名化（物理削除しない）。即時と30日後の二段モデル
- コアエンティティ（users / teams / organizations）は `deleted_at` による論理削除
- i18n は6言語すべてにその言語の訳を入れる。UI への文字列直書きは禁止

### 3-4. 機械的ゲート（壊すと全 PR が止まる）

- 必須チェックのジョブ名「**Compile & Test**」と「**OpenAPI Drift Check**」を改名しない。これらの workflow に `on.pull_request.paths` やジョブ単位の `if:` を足さない（`docs/development/ci_required_check_paths_filter_trap.md`）
- ArchUnit 凍結ストア（`backend/src/test/resources/archunit_store/`、7本・約13,800行）と各 `*_freeze.txt` を手で触らない
- テストの shard 割当（`verifyShardCoverage`）。テストクラスを追加・改名したら必ず通す

---

## 4. Non-Negotiables

### 4-1. 作業場所と git

1. **最初に `pwd` と `git status` を確認する。** `C:\Claude\mannschaft` 直下（本陣）では実装も commit もしない。`git worktree add` で隔離した作業木で行う（`CLAUDE.md`「作業開始前の必須確認」、`docs/development/worktree_operations.md`）
2. 本陣に残っている未コミット変更と未追跡ファイルは**他者の資産**である。触らない、消さない、自分の変更と混ぜない
3. `main` へ直接 commit しない。ブランチは `feature/<issue番号>-<説明>`。PR を作り、CI が通ってからマージする
4. `--no-verify`、force push、`reset --hard`、フックの無効化をしない
5. 1 PR は1つの目的に限る。小さく、単独で revert できる単位にする
6. 無関係な整形、import の並べ替え、ついでのリファクタリングをしない
7. コミットメッセージ・PR 本文・コメントは日本語。識別子は英語

### 4-2. 挙動

8. 既存の挙動を勝手に変えない。挙動が変わる修正（例: 握りつぶしていたエラーを表示する）は、レーン文書がそれを目的として明示している場合に限る
9. 正しい仕様がコードから判断できないときは、実装を止めて質問する（§5）
10. 証拠なく削除しない。削除の前に、参照が無いことを `git grep`（追跡ファイル全体）と、Nuxt の自動 import・Spring の DI・リフレクション・設定ファイル・workflow・`.claude/commands/` からの参照で確認し、確認コマンドと結果を PR 本文に貼る
11. 症状を隠す修正（空の catch、`!` や型キャストでの黙らせ、テストの無効化、抑止の追加）をしない。直せないなら直せないと報告する
12. 編集前に baseline の検証結果を記録する（§6）。baseline で既に落ちているものは、自分の変更のせいにも、自分の手柄にもしない

### 4-3. 触ってはならない領域

13. **CMP-261021-0017（#4022）の領地**。具体的には次のとおり。必要が生じたら所管へ依頼する
    - `backend/src/main/java/com/mannschaft/app/common/` と `config/` のクラスの移動・改名・分割
    - `backend/src/test/java/.../common/architecture/`（番人）と凍結ストア、`EXPECTED_LINES_*` 定数
    - `backend/build.gradle.kts`、`settings.gradle.kts`、`backend/build-logic/`、`.github/workflows/backend-ci.yml`、`shard-weights.properties`
    - Modulith 基盤（`moduleevent` パッケージ、`event_publication`）
    - 各ドメインの越境参照（D-1 / D-3 / D-5 / ServiceAPI）の解消
14. **BE の main コードでクラスを移動・改名・分割しない。** 凍結ストアの違反行はクラス名とメソッド署名で記録されているため、移動や分割だけで既存行が無効になり、新規違反として CI が落ちる
15. ArchUnit の番人を含むテストを `--tests` で絞って実行しない。凍結ストアから違反行が静かに消える（`docs/conventions/backend/30-arch-guard-tests.md`）。ローカルの確認は `compileJava compileTestJava` に留め、全量は CI に任せる
16. 既存 migration、`docs/openapi.json` の手編集、`frontend/app/types/generated/` の手編集
17. 決済（`payment/`、`billing/`、`advertising/` の Stripe 呼び出し）、認可（`role/`、`common/visibility/`、各 `*ScopeAccessGuard`）、`SecurityConfig`、各種 Filter、トランザクション境界（`REQUIRES_NEW`、`@TransactionalEventListener`）、outbox
18. FE の `useApi.ts`、`nuxt.config.ts`、`useAuthStore.ts`、牧場の生成物（`app/utils/ranch-production-assets.ts`、`frontend/scripts/verify-ranch-*`）
19. 進行中の他戦役の対象。着手前に `docs/task-list.md` を対象のクラス名・ファイル名で検索し、未完の行があれば触らない

### 4-4. 並行作業

20. R3・R4 のような横展開型のレーンは、**着手前に**正本 Issue（#4599）のコメントで対象ファイル名を宣言する。PR を立ててからでは遅い。断念するときは解放を宣言する
21. 重い Gradle 実行は、`backend/` の中で `bash scripts/gradle-turnstile.sh bash ./gradlew …` として直列化する（スクリプトは作業ディレクトリを変えないので、必ず `backend/` で打つ）
22. 台帳 `docs/task-list.md` は worktree + PR で扱う。新しい行は末尾に追加し、ID は `date '+%y%m%d-%H%M'` で作る `CMP-YYMMDD-HHMM`、列は7つ、未完の行は状態の先頭に `P1`〜`P5` を付ける

---

## 5. Stop And Ask Conditions

次のどれかに当たったら、その作業を止めて質問をまとめる。止めた作業以外で、答えに依存しない部分は続けてよい。

- 正しい仕様がコードとテストと仕様書（`docs/features/`）から判断できない
- テストと実装が矛盾している、または `@Disabled` の理由が「未実装」「実装ギャップ」である
- 削除候補に、参照ゼロと言い切れない要素がある（動的参照、運用手順書、個人環境のスクリプトからの利用）
- 変更が公開 API・DB スキーマ・保存済みデータ・Cookie・環境変数名に及ぶ
- 変更が認証・認可・課金・通知・外部連携に及ぶ
- `docs/openapi.json`、ER 図、凍結ストアのいずれかに差分が出た
- baseline では通っていた検証が落ち、原因が30分で特定できない
- 対象ファイルに他セッションの担当宣言、または未完の台帳行がある
- 設計案が複数あり、選択がプロダクト判断になる
- 握りつぶし catch を外した結果、利用者に見えるエラー表示が新しく増える箇所で、既存の同種画面に手本が無い

質問は §10 の報告の「質問」欄に、**対象ファイル・観測した事実・選択肢・推奨**の4点で書く。

---

## 6. Baseline Commands

編集前に実行し、結果（終了コード、件数、所要時間）を記録する。すべて worktree 内で実行する。

### 6-1. 共通

```bash
pwd && git status --short | head -20 && git log --oneline -1
git worktree list | wc -l
```

### 6-2. FE（`frontend/` で実行。Node 22.23.2・npm 10.9.8 固定）

| コマンド | 保証するもの | 注意 |
|---|---|---|
| `npm ci` | lockfile どおりの導入 | lockfile を更新しない。Windows で更新すると Linux 用の依存が消える |
| `npm run lint` | 握りつぶし catch の番人を含む ESLint | `eslint-suppressions.json` が効く。`lint:prune` は意図して使うときだけ |
| `npm run typecheck` | `nuxt typecheck` | 既定ヒープでは落ちる。`.github/workflows/frontend-ci.yml` と同じ `NODE_OPTIONS` を使う。`vue-tsc` 単体では代用にならない |
| `npx vitest run <対象 spec>` | 対象の単体テスト | 結果が「PASS(0) FAIL(0)」で終了コード0なら**未実行**である。件数を必ず見る。`mountSuspended` を使う spec は `--hookTimeout` を伸ばす |
| `npm run test:unit` | 牧場の検証を含む単体テスト全量 | 重い。PR 前に1回 |
| `npx playwright test --config <config> --list` | E2E の収集対象（サーバ不要） | R2 の前後比較に使う |

### 6-3. BE（`backend/` で実行。WSL / Linux。Docker 必須のものあり）

| コマンド | 保証するもの | 注意 |
|---|---|---|
| `./gradlew compileJava compileTestJava --no-daemon` | 本番とテストの型 | 日常の確認はこれ |
| `./gradlew verifyShardCoverage -Pshard.total=6` | テストクラスが必ず1つの shard に入る | テストクラスを追加・改名したとき |
| `./gradlew archTest` と `./gradlew archUnitTest archUnitFreezeStoreIntegrityTest` | ArchUnit の番人。**2系統ある**。`archTest` は JUnit のタグ `archunit` が付いたテスト、`archUnitTest` は `@AnalyzeClasses` 系（ArchUnit のエンジン）を走らせる。片方だけでは全番人にならない（`backend/build-logic/src/main/kotlin/mannschaft.backend-conventions.gradle.kts`、`TEST_CONVENTION.md` の該当節） | **絞らない**。重い。ローカルで省くなら、CI で両系統が緑であることを確かめる |
| `./gradlew test -PexcludeMigrationTests=true` | 単体と結合（Flyway 再生を除く） | 極めて重い（CI は6 shard）。原則 CI に任せる |
| `./gradlew checkErDiagrams -Der.requireDocker=true` | ER 図と実スキーマの一致 | migration か Entity を触ったとき。本書の範囲では触らないはず |
| `./gradlew generateOpenApiDocs -PopenApiPort=8082` | `docs/openapi.json` の再生成 | 差分が出たら挙動変更。本書の範囲では差分ゼロが正 |

### 6-4. ハーネス

```bash
node scripts/harness-doctor.mjs
node --test scripts/*.test.mjs
```

### 6-5. 負債量の再計測（着手時と完了時に記録して比較する）

```bash
# R3: 握りつぶし catch の抑止（起草時: 91 ファイル・479 件）
node -e "const s=require('./frontend/eslint-suppressions.json');let t=0;for(const f in s)for(const k in s[f])t+=s[f][k].count;console.log(Object.keys(s).length,t)"
# R2: Playwright config の本数（起草時: frontend 直下に27本）
git ls-files frontend | grep -cE '^frontend/(playwright|pw-)[^/]*config\.ts$'
# R5: Thread.sleep（起草時: 35 ファイル・49 件）と @Disabled（13 ファイル・33 件）
git grep -c 'Thread\.sleep' -- backend/src/test | wc -l
git grep -c '@Disabled' -- backend/src/test | wc -l
```

---

## 7. Debt Map

「実装可否」の凡例: **可** = 本書の規律の範囲で実装してよい。**条件付き** = 質問への回答か前提作業の後。**提案のみ** = 提案書を書くだけで実装しない。**範囲外** = 触らない（§11）。

### 7-1. リポジトリ衛生

| # | 負債 | 根拠 | なぜ負債か | 影響・リスク | 改善案 | 検証 | 実装可否 |
|---|---|---|---|---|---|---|---|
| H1 | 一回限りの移行スクリプトが追跡されたまま | `backend/scripts/replace_getCurrentUserId*.{mjs,py}`（7本）、`backend/scripts/test_ym*.sql`（3本）。追跡ファイルからの参照ゼロ | 本物のスクリプトを探す妨げになる | 低 | 中身を読んで不要と確かめてから削除 | `git grep` で参照ゼロを再確認 | 可（裁可済み: 内容を確認して不要なら削除） |
| H2 | 参照ゼロの補助スクリプト | `scripts/merge_locales.py`、`scripts/resolve_conflicts.py` | 同上 | 低 | 同上 | 同上 | 可（同上） |
| H3 | `.gitignore` の穴 | ルートに `scratch_*`、`.tmp-*` などの未追跡物が溜まる | `git status` が読めなくなり、誤コミットの元になる | 低 | パターン追加のみ。ファイル自体は消さない | `git status --short` の行数 | 可 |
| H4 | 起動スクリプトが個人環境に依存 | `start-backend.vbs` → `start-backend.ps1` → WSL の `backend-loop.sh` が BE 起動の正系で、`/陣立て`・`/伝令`・`/撤収` が使う。`C:\Claude\mannschaft` と `/home/kenta` を直書きしている | 別の PC や別のユーザー名では動かない | 中（開発環境の起動を壊す恐れ） | パスを環境から解決する案を書く。**3本とも削除しない** | — | 提案のみ（R7 の P14） |
| H5 | ルートと `docs/` 直下の古い文書 | 記事原稿2本（`qiita_claudecode_assets_to_codex_bridge.md`、`docs/qiita-polyrepo-monorepo.md`）と、古い計画書4本（`e2e-test-instructions.md`、`docs/monorepo-migration.md`、`docs/TEST_COVERAGE_PLAN.md`、`docs/E2E_TEST_PLAN.md`） | 古い数値や完了済みの手順が現行と誤読される | 低 | 記事原稿2本は `docs/articles/` へ移動、計画書4本は削除 | リンク切れの確認 | 可（裁可済み） |

### 7-2. FE

| # | 負債 | 根拠 | なぜ負債か | 影響・リスク | 改善案 | 検証 | 実装可否 |
|---|---|---|---|---|---|---|---|
| F1 | Playwright config の乱立 | `frontend/` 直下に27本。大半が単一 spec 用。`package.json` と workflow からの参照ゼロ。11本は追跡ファイル全体でも参照ゼロ。命名が3流儀 | 本線の設定が埋もれ、設定の差が意図か偶然か分からない | 低。E2E の実行手順書が config 名を指している場合がある | 棚卸し表を作り、固有設定が無いものを本線へ寄せる | `--list` の収集結果が前後で一致 | 可（裁可済み: 内容を確認して不要なら削除） |
| F2 | 握りつぶし catch の抑止 | `frontend/eslint-suppressions.json` に 91 ファイル・479 件。ルールは `no-restricted-syntax` の1種類。番人は `frontend/eslint-rules/swallow-catch-rules.mjs` | 障害が利用者にも開発者にも見えない。`CLAUDE.md` が禁止する対処療法の残り | 中。外すとエラー表示が増え、挙動が変わる | ファイル単位で返済し、抑止を減らす | lint、typecheck、エラー経路の spec | 可（R3 の手順に従う） |
| F3 | 巨大ファイル | 1,000行超が4本（生成物を除く）: `types/village.ts` 1,268、`components/reservation/WeeklyScheduleManager.vue` 1,183、`components/schedule/CalendarWeekGrid.vue` 1,116、`pages/calendar.vue` 1,100。700〜990行が約25本 | 変更が衝突し、レビューが重い | 型ファイルは低。スケジュール系 SFC は高 | 型と API composable から始め、SFC は特性テストの後 | typecheck、既存 spec、追加した特性テスト | 可（R4 の順序に従う） |
| F4 | 手動型と生成型の二重管理 | 手動型 178 ファイル。生成型を使うのは 111 ファイル | 手動型と API のずれが実行時まで見つからない | 高。生成型は、注釈が整った DTO では nullable・必須・文字列リテラルの union を出している（`frontend/app/types/generated/index.ts` に `\| null` が約80箇所、必須フィールドが約4万）。一方で**注釈が未整備の DTO** では nullable が出ず、enum が `string` になり、フィールドが optional になる（optional は約6万）。未整備のものを型だけ寄せると `!` とキャストが増える | 整備済みと未整備を分けて数え、未整備の BE 側の OpenAPI 注釈を先に整える | — | 提案のみ（R7） |
| F5 | テスト配置が規約と合っていない | 規約は「spec は対象と同じディレクトリに置く」（`frontend/FRONTEND_CODING_CONVENTION.md` のテストファイルの配置ルール）。実態は `tests/unit/` に 531 spec、`app/**` 同居が約 290 | 規約どおりに置くと少数派になり、新規の置き場所が人によって割れる | 低 | 規約を実態に合わせて改めるか、実態を規約へ寄せるかを決める。既存は動かさない | — | 提案のみ（R6 が選択肢を質問として出す） |
| F6 | Dialog と Modal の命名併存、共通部品への未移行 | `*Dialog.vue` 97、`*Modal.vue` 83。`PageHeader` 未使用の pages が 214 | 見た目と操作が画面ごとに揺れる | 中。見た目が変わる | 既存の `/統一` スキルの戦役で扱う | — | 範囲外 |
| F7 | `nuxt.config.ts` の locale 列挙の直書き | 77 ファイル × 6 言語 | 追加時の登録漏れで訳が出ない | 高（起動と E2E に波及） | 生成化 | — | 提案のみ（R7） |

### 7-3. BE テスト基盤

| # | 負債 | 根拠 | なぜ負債か | 影響・リスク | 改善案 | 検証 | 実装可否 |
|---|---|---|---|---|---|---|---|
| T1 | `Thread.sleep` の残存 | `backend/src/test` に 35 ファイル・49 件。`TEST_CONVENTION.md` §10 が禁止 | 遅く、環境によって不安定になる | 中。置換で不安定さが表に出ることがある | Awaitility に置換（既に 141 ファイルで使用） | `compileTestJava`、CI の該当 shard | 可（R5） |
| T2 | `@Disabled` の滞留 | 13 ファイル・33 件。規約は「1スプリント以内に解決」 | 守られていない仕様が見えなくなる | 低 | 理由別の棚卸し表を作る。有効化は仕様判断が要る | — | 可（棚卸しのみ）。有効化は質問 |
| T3 | テスト用設定の置き場所が2つ | `application-test.yml` が `src/main/resources` と `src/test/resources` の両方にある | 本番の成果物にテスト設定が入る | 中。番人 `TestProfileKeyCoverageGuardTest` が両者を見ている | 統合案を書く | — | 提案のみ（R7） |
| T4 | テスト全量が重い | CI は6 shard、1 shard 最大120分。Spring context の種類が多い | 開発の往復が遅い | 高 | — | — | 範囲外（CMP-261004-2047 が所管） |

### 7-4. 文書と実装の不整合

| # | 負債 | 根拠 | 改善案 | 実装可否 |
|---|---|---|---|---|
| D1 | `TEST_CONVENTION.md` §7 が実在しないタスクとタグを例示 | `integrationTest` / `allTests` タスク、`@Tag("integration")` は無い。実在するタグは `perf` / `archunit` / `er-diagram` | 実在するものに書き直す | 可（R6） |
| D2 | `docs/security/02_cookie_and_session.md` §2.1 が修正済みの欠陥を現状として記述 | 実装は `AuthTokenCookies` で `mannschaft.cookie.secure` を参照 | 現状に合わせる | 可（R6） |
| D3 | `docs/development/claude-md-details.md` §12 の検証コマンドが重い旧式 | `./gradlew build` は `archTest` まで含む | §6 の表に合わせる | 可（R6） |
| D4 | `docs/refactoring/` に第3弾の文書が無く、再エクスポートの撤去計画も無い | `phase4_overview.md` の実施表にだけ第3弾がある | 経緯を1段落で補う。本書へのリンクを足す | 可（R6） |
| D5 | FE のデプロイ構成が、決定済みの方針と合っていない | 方針は「FE は Cloudflare 上で SSR」。Terraform は Cloudflare Pages を作り、コメントで wrangler によるデプロイを前提にする。しかし wrangler を使う workflow は無く、`frontend-deploy.yml` は ECR / ECS 向け、`frontend/Dockerfile` は Node サーバを起動する。`nuxt.config.ts` に Cloudflare 向けの Nitro preset の指定も無い | 文書には方針と現状の差を明記する。構成の実現は提案にする | 文書は可（R6）。実現は提案のみ（R7 の P13） |
| D9 | 描画方針と設定が合っていない | 規約は「公開ページは SSR、ログイン後は SPA」（`backend/.claudecode.md` §2、`frontend/FRONTEND_CODING_CONVENTION.md` §4a）。`nuxt.config.ts` は SSR を全体で有効にしたままで、公開ページの一覧も無い | 公開ページの一覧を作る。設定での明示は提案にする | 一覧の作成は可（R6）。設定の変更は提案のみ（R7 の P13） |
| D10 | 認証トークンの扱いの記述が文書間で食い違う | `FRONTEND_CODING_CONVENTION.md` §7 は「JWT は `localStorage`、認証トークンを Cookie に格納しない」と定める。一方、同じ節の注記と `docs/security/02_cookie_and_session.md` は、`access_token` / `refresh_token` が `SameSite=Strict` の Cookie としても発行されると書く | 実装の現状（どのトークンがどこに保存され、どちらで認証されるか）を確かめて質問にまとめる。**文書もコードも直さない**（認証に関わる） | 報告のみ（R6） |
| D6 | 引取ルールの矛盾 | `boundary-coordination.md`（自動引取禁止）と `modular_monolith_boundary_debt_repayment.md` §7.3（3日＋24時間で引取可） | #4022 の所管へ報告する。自分では直さない | 報告のみ |
| D7 | `README.md` が 126KB。冒頭に個別 PR の進捗が積もる | `CLAUDE.md` の「README を更新せよ」が蓄積を生む | 進捗を別文書へ分ける案を書く | 提案のみ（R7） |

### 7-5. BE 本体（すべて提案のみ）

BE 本体の構造変更は、凍結ストアと #4022 の領地に必ず触れる。本書では**実装せず、提案書を書く**（R7）。

| # | 負債 | 根拠 | なぜ負債か | 変更リスク |
|---|---|---|---|---|
| B1 | 巨大クラス | 1,000行超が20本。`common/GlobalExceptionHandler` 3,361、`role/repository/UserRoleRepository` 3,115（`@Query` 81本）、`payment/stripe/StripePaymentProviderImpl` 2,056、`payment/escrow/ConnectChargeService` 1,731、`timeline/service/TimelinePostService` 1,663 ほか | 変更が衝突し、責務が混ざる | 高。分割で凍結行が無効になる |
| B2 | Service が `ApiResponse<Entity>` を返す | 12 ファイル・36 箇所（`gamification` 5、`knowledgebase` 5、`skill` 2） | スキーマ変更が API に漏れる。層が逆転している | 中。JSON の形が変わりうる。`knowledgebase` は #4022 第1波の担当 |
| B3 | `ScopeType` enum の重複 | 7つのパッケージに同名 enum、類似 enum が4つ | 値の差が仕様か偶然か分からない | 高。認可に波及する |
| B4 | Stripe 呼び出しと Webhook 入口の分散 | `payment/`・`billing/`・`advertising/` が SDK を直接呼ぶ。Webhook の入口が5系統以上 | 冪等処理の適用漏れを確かめにくい | 高（課金） |
| B5 | 広域 catch | `catch (Exception\|Throwable` が 388 ファイル・742 行 | 障害原因が隠れ、ロールバック漏れの恐れがある | 中。1件ずつの精査が要る |
| B6 | `@Deprecated` の残置 | 16 ファイル・63 行（`todo/dto/TodoResponse` 28 ほか） | 呼び出し元が残っているか不明 | 低〜中。DTO のフィールドなら API 契約 |
| B7 | 暗号文に鍵バージョンが無い | `common/EncryptionService`。`docs/security/09` に再暗号化の計画がある | 鍵を替えると既存の暗号化データが復号できない | 高 |
| B8 | `application.yml` が 670 行・プロファイル6種 | 環境変数、既定値、設計メモが混在 | 環境差の事故が起きやすい | 中 |
| B9 | デプロイの認証方式が2系統 | infra 系は OIDC、deploy 系は長期キー | 鍵管理の負担 | 中（secrets の変更を伴う） |

偵察で「`@Scheduled` に `@SchedulerLock` が無い」と疑われた5ファイルは、裏取りの結果すべて**誤検知**だった（Javadoc の言及、または手動トリガー専用）。番人 `ScheduledBatchGuardTest` が守っている。対応不要。

---

## 8. Implementation Phases

### Phase 0 — 現在状態の確認（全レーン共通・必須）

1. worktree を作り、`pwd` と `git status` を確認する
2. §6 の baseline を実行して記録する。§6-5 の負債量を再計測する
3. 自分のレーンの対象を `docs/task-list.md` と正本 Issue で検索し、担当の重複が無いことを確かめる
4. 担当を宣言する（横展開型のレーン）

### Phase 1 以降 — レーン

各レーンの手順・完了条件・検証は `docs/refactoring/lanes/` の個別文書にある。レーン同士は対象が重ならないように切ってあり、別セッションで並行に進められる。共有しうる文書と成果物の担当は次のとおり固定する。

- `TEST_CONVENTION.md` と `frontend/FRONTEND_CODING_CONVENTION.md` を編集するのは **R6 だけ**。他のレーンが追記したい内容は、R6 への依頼として報告に書く
- `frontend/README.md` の E2E 実行例を編集するのは **R2 だけ**
- スケジュール・カレンダー系の分割提案（P10）を書くのは **R7 だけ**。R4 はその裁可を待ち、自分では書かない

| レーン | 文書 | 内容 | 並行可否 | 質問への依存 |
|---|---|---|---|---|
| R1 | `lanes/R1-repo-hygiene.md` | 残骸の撤去と `.gitignore` | 可 | なし（裁可済み） |
| R2 | `lanes/R2-playwright-config.md` | Playwright config の棚卸しと統合 | 可 | なし（裁可済み） |
| R3 | `lanes/R3-swallow-catch.md` | 握りつぶし catch 抑止の返済 | 可。レーン内も複数セッションで分担できる | なし |
| R4 | `lanes/R4-fe-large-files.md` | FE の巨大ファイルの構造分割 | 可。R3 と同じファイルを同時に触らない | なし |
| R5 | `lanes/R5-be-test-hygiene.md` | `Thread.sleep` の置換と `@Disabled` の棚卸し | 可 | なし |
| R6 | `lanes/R6-docs-alignment.md` | 文書と実装の不整合の是正 | 可 | なし |
| R7 | `lanes/R7-proposals.md` | BE 本体などの提案書の作成（実装なし） | 可 | なし |

推奨の順序は、安全なものから: **R1（`.gitignore`）→ R2（棚卸し）→ R6 → R5 → R3 → R4 → R7**。並行に走らせるなら R3 と R4 に人手を厚く割く。

### 大きな設計変更

§7-5 の全項目、F4、F7、T3、D7 は、承認なしに実装しない。R7 で提案書にまとめ、マスターの裁可を待つ。

---

## 9. Verification Requirements

- 各 PR で、変更に対応する §6 のコマンドを実行し、結果を PR 本文に貼る。貼るのは実際の出力であり、「通ったはず」は不可
- FE を触ったら `npm run lint`・`npm run typecheck`・対象 spec。PR 前に `npm run test:unit` を1回
- BE のテストを触ったら `compileJava compileTestJava`。テストクラスの追加・改名があれば `verifyShardCoverage`。全量は CI に任せ、CI の全チェックが緑になるまでマージしない
- `git diff --stat` に `docs/openapi.json`、`frontend/app/types/generated/`、`docs/architecture/er/`、`archunit_store/` が現れたら、想定外である。止めて質問する
- 構造だけの分割（R4）は、分割の前後で公開インターフェース（export、props、emits、ルート）が同じであることを示す。既存 spec が無い箇所は、**先に**特性テストを足し、分割前に緑であることを確かめる
- 挙動が変わる返済（R3）は、エラー経路の spec を足す
- **完了ゲートは正本に従う。** `CLAUDE.md`「正しい実行フロー」と `docs/development/completion-evidence.md` が正本である。要点は次のとおり
  - PR の変更ファイルから scope（`app` / `tooling` / `test-only`）が決まる。`node scripts/completion-evidence.mjs` の推定に従い、自分で軽く申告しない
  - 見込み: R3・R4 は `app`。R2 も `app`（`playwright*.config.ts` はテストの実行範囲を変えうるため）。R1 は削除対象の場所による（`backend/scripts/` は `app` 側に倒れる）。R5 は `test-only`。R6・R7 は `tooling`
  - `app` の PR は、**実機 E2E（権限あり／権限なし／他テナントのロール横断、URL 直打ちを含む）と、その後のアリシゼーションの両方**が `passed` でなければ完了にできない。単体・CI が緑でも省略の理由にならない
  - 実施できない事情があるときは、PR 本文と台帳に未実施と理由を明記する。そのレーンは完了にしない
  - 台帳の行を完了へ動かすには `docs/evidence/<CMP-ID>.json` が要る。証拠欄は URL かパスだけを書く。締めは実装 PR のマージ後に行う
- **受け入れ条件とテストの対応表を PR 本文に載せる。** レーン文書の完了条件を AC として番号を振り、AC ごとに「確かめたテストまたは証拠」を1行で対応させる。加えて、攻め口5類型（空／境界値／途中失敗／認可／性能）のそれぞれについて、対象の AC を挙げるか、「該当なし＋理由」を書く
- 削除は、参照ゼロの確認コマンドと結果を PR 本文に貼る
- 負債量を §6-5 で再計測し、着手時と比べる。**抑止や凍結の行数が増えていたら失敗**である

---

## 10. Reporting Format

レーンの完了時、または止まった時点で、次の形で報告する。

```markdown
## リファクタリング報告: <レーン名>（<日付>）

### 結果
- 状態: 完了 / 一部完了 / 質問待ち
- PR: <URL と状態の一覧>
- 負債量: <指標> 着手時 N → 完了時 M

### 変更の要約
- <PR ごとに、何を・なぜ・どのファイルを>

### 実行したコマンドと結果
| コマンド | 実行場所 | 結果（終了コード・件数・所要時間） |
|---|---|---|

### baseline との差
- baseline で既に落ちていたもの: <一覧>
- 自分の変更で新しく落ちたもの: <無ければ「なし」>

### 実施しなかったこと
- <手順のうち飛ばしたものと理由。実機確認の未実施を含む>

### 質問
- 対象 / 観測した事実 / 選択肢 / 推奨

### 見つけたが直していない問題
- <範囲外の不具合や負債。台帳へ起票したなら CMP-ID>
```

範囲外の不具合を見つけたら、直さずに `docs/task-list.md` へ新しい行として起票する（worktree + PR）。

---

## 11. Out-of-scope Items

| 項目 | 理由 | 所管 |
|---|---|---|
| BE のドメイン境界負債（D-1 / D-3 / D-3T / D-5 / ServiceAPI）、`common` と `config` の整理、Gradle 分割、Modulith | 進行中の戦役がある | CMP-261021-0017（#4022） |
| `UuidV7Entity` 未継承 569 件 | 同上 | CMP-261021-0115 |
| テスト全量の高速化、Spring context の集約 | 同上 | CMP-261004-2047 |
| `payment` の神クラス分割と Money 値オブジェクト | 同上 | CMP-260918-1359 |
| 時刻設計の是正、TODO コメントの棚卸し | 同上 | CMP-023、CMP-019 |
| 既存 migration の整理・squash・baseline 化 | checksum と番人を壊す | — |
| BE の main コードのクラス移動・改名・分割 | 凍結ストアを壊す。R7 で提案のみ | — |
| 認証・認可・課金・Webhook・トランザクション境界・outbox の変更 | 事故の影響が大きい | — |
| インフラ（Terraform、deploy workflow、secrets） | 本番に触れる | — |
| 手動型から生成型への移行 | BE の OpenAPI 注釈の整備が先。R7 で提案のみ | — |
| 画面デザインの統一（`PageHeader` 等への寄せ、Dialog / Modal の統一） | 見た目が変わる | `/統一` の戦役 |
| 依存ライブラリの更新（特に Stripe SDK、ArchUnit） | 固定に理由がある | Renovate と個別判断 |
| 本陣の未追跡ファイル、`.claude/` 配下の未追跡物、停滞 worktree の掃除 | 他者の資産 | `/陣払い` |
| 新機能、性能改善、見た目の変更 | リファクタリングではない | — |

---

## 付録 A. 実装前の質問と回答（2026-11-17 マスター回答済み）

未回答の質問は無い。実装中に新しい疑問が出たら、§5 と §10 に従って報告する。

| ID | 質問 | 回答 | 本書への反映 |
|---|---|---|---|
| Q1 | FE の本番デプロイ先と、SSR か静的生成か | BE は AWS、FE は Cloudflare。ログイン画面は SSR、ログイン後はページ遷移をなるべく発生させない SPA | §2-4。原則は `backend/.claudecode.md` §2、実装規則は `frontend/FRONTEND_CODING_CONVENTION.md` §4a に追記済み。現状のビルドとデプロイはこの方針に届いていない（D5）。文書の是正は R6、実現の設計は R7 の P13 |
| Q2 | Web の FE 以外に API を使うクライアントはあるか | 今は無い。当面は PWA でスマートフォンに対応し、将来 Android / iOS に対応する | §2-2。ネイティブアプリ向けの仕様と PWA の設定を消さない |
| Q3 | 追跡されている残骸（H1・H2、参照ゼロの Playwright config）を削除してよいか | 内容を確認して、不要なら削除 | R1・R2。**ファイルの中身を読み、参照ゼロを確かめた**うえで削除する |
| Q4 | 古い文書6本の扱い | 記事原稿2本は `docs/articles/` へ移動、計画書4本は削除 | R1 |
| Q5 | 本陣の作業木にあった未コミット変更（`AGENTS.md`、`.claude/commands/アリシゼーション.md`、`start-backend.ps1` の削除）は誰の作業か | マスターの作業ではない。あるべき姿に戻す | 2026-11-17 に3件とも main の状態へ復元済み。`start-backend.ps1` は BE 起動の正系の一部であり、削除は誤りだった。起動スクリプト3本は残す（H4） |
| Q6 | 親 Issue と台帳の行を起こしてよいか | 起こす | Issue #4599、台帳 CMP-261117-1026 |

裁可: BE 本体（§7-5）は提案のみとする。本書一式は PR にする。
