# refactor-instructions.md — Mannschaft リファクタリング指示書（第2版）

> 第2版: 2026-11-17。第1版は、規約と既決事項を通読せずに起草したため、既に決まっていることを質問にし、規約や実装と食い違う記述を含んでいた。第2版は、規約・設計書・台帳・過去の裁可を全文照合し、数値を測り直したうえで書き直したものである。
> 基準 commit: `1432237a7d`（数値の測り直しは `720bc65f09`）。正本 Issue: **#4599**。台帳: **CMP-261117-1026**。
>
> **読む順番**
> 1. 本書（全レーン共通の規律）
> 2. [`decided-facts.md`](decided-facts.md) — 既に決まっていること。**質問する前に必ず引く**
> 3. [`known-doc-drift.md`](known-doc-drift.md) — 文書と実装の食い違い。**推測で解かない**
> 4. 自分のレーンの文書（`lanes/R*.md`）
>
> **使い方**: レーンごとに別のセッションへ渡す。`/goal docs/refactoring/lanes/R<番号>-<名前>.md に書かれたことを、docs/refactoring/refactor-instructions.md の規律に従って完遂しろ`。

---

## 1. Objective

既存仕様を壊さずに技術的負債を減らし、今後の変更を安全で速くする。見た目の綺麗さは目的ではない。

射程は、**進行中の他戦役が拾っていない負債**に限る。

| レーン | 内容 | 実装 |
|---|---|---|
| R1 | 追跡されている残骸の撤去と `.gitignore` | あり |
| R2 | Playwright の設定27本の棚卸しと整理 | あり |
| R3 | E2E テストの中の握りつぶし catch 抑止（90 ファイル・478 件）の返済 | あり（一部は方針の提案まで） |
| R4 | FE の巨大ファイルの構造分割 | あり |
| R5 | BE テストの `Thread.sleep` の置換と `@Disabled` の棚卸し | テストのみ |
| R6 | 文書を実装に合わせる | 文書のみ |
| R7 | 大型の負債の提案書 | なし |

BE のドメイン境界負債は戦役 **CMP-261021-0017（#4022）** が返済中である。BE の main コードの構造変更（クラスの移動・改名・分割）は、凍結ストアを壊すため、本書では行わない（マスター裁可 2026-11-17）。

**完了の定義**: レーン文書の完了条件をすべて満たし、§9 の検証と完了ゲートを通り、§10 の形で報告が出ていること。途中で止まったレーンは「一部完了」または「質問待ち」であり、完了とは呼ばない。台帳の行も完了にしない。

---

## 2. Project Understanding

### 2-1. 何を作っているか

Mannschaft は汎用の組織管理プラットフォームである。スポーツチーム・学校・会社・店舗・町内会などを、モジュール式テンプレートで1つの製品として扱う（`README.md`）。

- テナントは「個人・チーム・組織」の3層。組織は入れ子にでき、利用者は複数に所属できる
- ロールは `SYSTEM_ADMIN` / `ADMIN` / `DEPUTY_ADMIN` / `MEMBER` / `SUPPORTER` / `GUEST`
- 課金はモジュール単位の月額、組織数、ストレージ。決済は Stripe（Connect を含む）
- **本番環境はまだ構築されていない**（台帳 CMP-260826-2129）。本番に実データは無い。ローカルと CI のシード、E2E フィクスチャは壊してはならない

### 2-2. 構成と規模（基準 commit での実測）

| 領域 | 場所 | 規模 |
|---|---|---|
| BE | `backend/`（Java 21、Spring Boot 3.5.13、Gradle 8.14.4、Spring Modulith 1.4.13） | main の `.java` 8,642、ドメインのディレクトリ 121、テスト配下の `.java` 3,619（うち `*Test.java` 2,576） |
| DB | `backend/src/main/resources/db/migration/` | バージョン付き migration 1,237 本と繰り返し適用1本。まとめ直しは無く、毎回すべて適用する |
| FE | `frontend/`（Nuxt 3.21、Vue 3.5、Pinia 3、PrimeVue 4.5、PWA、6言語） | pages の `.vue` 552、components の `.vue` 972、composables 542（spec を除く）、stores 19（同） |
| インフラ | `infra/terraform/`、`.github/workflows/`（22本） | これから構築する構成の定義。AWS（ECS・RDS・Valkey・SES・SQS）と Cloudflare（Pages・Tunnel・R2） |
| ハーネス | `scripts/`、`backend/scripts/`、`frontend/scripts/`、`.githooks/` | worktree の管理、検証コピー、完了証拠、番人 |

### 2-3. 主要な入口

- BE: `MannschaftApplication`、`config/SecurityConfig.java`（633行、`requestMatchers` 92箇所）、`config/JwtAuthenticationFilter`、`config/WebSocketConfig`、`common/GlobalExceptionHandler.java`（約3,370行、`@ExceptionHandler` 23個）
- FE: `frontend/nuxt.config.ts`（1,144行）、`frontend/app/composables/useApi.ts`（全 API の共通ラッパ）、`frontend/app/middleware/`（10本）
- 型の契約: `docs/openapi.json`（BE から生成）→ `frontend/app/types/generated/index.ts`（生成。手で編集しない）

### 2-4. データの流れ

1. **配置**（決定 M1・I3）: BE は AWS、FE は Cloudflare。FE と BE は同一オリジンで、`/api/**` と `/ws` だけが Cloudflare Tunnel 経由で BE へ流れる。ただし BE は、その外に決済からの戻り先4本と外部 Webhook の受け口を持つ（[`known-doc-drift.md`](known-doc-drift.md) の I5。不具合の疑いとして台帳 CMP-261117-1220 に起票済み）
2. **描画**（決定 M2）: 公開ページは SSR、ログイン後の画面は SPA。`nuxt.config.ts` は SSR を全体で有効にしたままで、ログイン後の画面はサーバが枠だけを返し、中身をブラウザ側で描く。親ルートが `<NuxtPage/>` を持ち続ける「永続シェル」方式を使う（`frontend/FRONTEND_CODING_CONVENTION.md` §4a）
3. **認証**（決定 S1・S2）: Web は HttpOnly Cookie、ネイティブアプリは Bearer ヘッダーの二本立て。FE はトークンをメモリにだけ持つ。規約の一部はこの実装より古い（[`known-doc-drift.md`](known-doc-drift.md) の A1）
4. **BE の中**: Controller → Service → Repository（JPA）→ MySQL。機能をまたぐ連携はイベント。付随する通知は、業務トランザクションでイベントを発行し、コミット後の非同期リスナーが1件ずつ別トランザクションで送る（決定 B2・B3）
5. **定期バッチ**: `@Scheduled` と ShedLock で排他する（決定 B5）

### 2-5. 外部依存

Stripe（決済・Connect・Webhook）、AWS SES / SNS / SQS（メール）、Cloudflare R2（S3 互換。ローカルは MinIO）、Web Push、LINE（ログインと Bot）、Google（OAuth・カレンダー・Places）、Apple（OAuth）、WebAuthn、AI ダイジェスト、翻訳 API、Slack（エラー監視）。

### 2-6. 利用者

API を使うのは現在 Web の FE だけである。スマートフォンには当面 PWA で対応し、将来 Android / iOS のネイティブアプリに対応する（決定 M3）。API を Web 専用の前提で作らない。

---

## 3. Behaviors To Preserve

以下を変えてはならない。変える必要があるように見えたら、止めて報告する（§5）。詳しい根拠は [`decided-facts.md`](decided-facts.md) にある。

### 3-1. 認証・境界

- 認証 Cookie の属性（`HttpOnly`・`SameSite=Strict`・`Path=/`・本番は `Secure`）。発行と削除で揃える
- CSRF トークンを使わず、`SameSite=Strict` と同一オリジンで守る設計。FE と BE を別サイトに分けない
- Bearer を優先し、無ければ Cookie を読む認証の順序。`refresh_token` を Cookie と body の両方で返す仕様
- リフレッシュの single-flight と4状態。一時障害でログアウトしない
- 許可オリジンは1つの設定で管理し、`*` を使わない
- 既定は全リクエスト認証必須。公開パスの一覧を増減しない
- 暗号文の形式と鍵の扱い

### 3-2. 課金・外部連携

- Stripe の Webhook の署名検証と、platform 用・Connect 用のシークレットの分離
- Stripe SDK の 28.x 固定
- 決済からの戻り URL の署名済み state
- 過去に送ったメールの中のリンク、OAuth のコールバック URL、WebAuthn の rp-id

### 3-3. データと契約

- 既存の Flyway migration を編集も改名もしない
- 公開 API のパスと、リクエスト・レスポンスの形。`docs/openapi.json` に差分が出たら挙動の変更である
- DB の日時は UTC の壁時計、アプリ層は JST
- 退会は二段。受付直後に個人情報を消去し、30日後にバッチがユーザー本体と残りの個人データを物理削除する。投稿・履歴・統計は紐付けを断って残す。決済履歴は7年保持する（決定 M4）
- users / teams / organizations は論理削除。users の物理削除は退会のバッチだけが行う
- 文字列は6言語すべてにその言語の訳を入れる。BE の ErrorCode の訳も同じ PR で足す
- PWA の manifest と service worker の設定

### 3-4. 非同期・バッチ

- `@Async` のプールの明示。`@Scheduled` と `@SchedulerLock`・`@BatchEndpoint` の組
- 業務トランザクションと付随通知の境界（コミット後・非同期・1件ごとの別トランザクション）

### 3-5. 機械的なゲート

- 必須チェックのジョブ名「**Compile & Test**」と「**OpenAPI Drift Check**」。改名しない。これらの workflow に `on.pull_request.paths` やジョブ単位の `if:` を足さない
- ArchUnit の凍結ストア（`backend/src/test/resources/archunit_store/` の7本と `stored.rules`、約13,800行）
- そのほかの凍結物: 各 `*_freeze.txt`、`# CENSUS_FLOOR`・`# CLASSIFIED_FLOOR`・`# REMOVED` の下限、`DECLARATION_EXCEPTIONS`、`KNOWN_UNPAID_DRIFT`、`FlywayLegacyMigrationBaseline`、上限の定数 `EXPECTED_LINES_*`
- テストの shard の割当

---

## 4. Non-Negotiables

### 4-1. 進め方は、このプロジェクトの正規フローに従う

本書は正規フローを上書きしない。レーンを受け取ったセッションは、そのレーンの**殿**として動く（Claude は `CLAUDE.md` と `.claude/commands/`、Codex は `AGENTS.md` に従う）。

1. **着手前に台帳を「実装中」にする。** レーンごとに子の CMP を採番し（`date '+%y%m%d-%H%M'`、親は CMP-261117-1026）、状態を `P4 実装中` にした台帳だけの PR を先にマージする。実装の PR で初めて行を足してはならない（`.claude/commands/出陣.md`）
2. **実装は足軽に任せる。** 足軽は `Agent(isolation:"worktree")` で起動し、モデルを毎回明示する。既定は Sonnet。Sonnet が実際に詰まったときだけ Opus に上げる。足軽は1体1タスクで、設計の判断をしない
3. **テストを先に書く**（`/試練`）。R4 の特性テスト、R5 の置換前の確認がこれに当たる
4. **検分せずにマージしない**（`/検分`。既定は Codex）。CI が緑なだけでは足りない
5. **`app` の PR は、実機 E2E とアリシゼーションの両方を通す**（§9）
6. **マージと後片付けは `/凱旋` の手順で行う。** worktree の掃除の確認と、WSL の検証コピーの確認を含む
7. 報告は、各コマンド文書が定める発言者ラベルを付ける。§10 の形式は PR 本文とレーンの完了報告に使う

### 4-2. 作業場所と git

8. **最初に `pwd` と `git status` を確認する。** `C:\Claude\mannschaft` 直下（本陣）では実装も commit もしない
9. 本陣の未コミット変更と未追跡ファイルは他者の資産である。触らない、消さない
10. `main` へ直接 commit しない。ブランチは `feature/<issue番号>-<説明>`
11. `--no-verify`、force push、`reset --hard`、フックの無効化をしない
12. 1 PR は1つの目的に限る。小さく、単独で revert できる単位にする
13. 無関係な整形、import の並べ替え、ついでのリファクタリングをしない
14. コミットメッセージ・PR 本文・コメントは日本語。識別子は英語

### 4-3. 挙動

15. 既存の挙動を勝手に変えない
16. **質問する前に [`decided-facts.md`](decided-facts.md) と出典を引く。** 規約・設計書・台帳を検索しても決まっていないことだけを質問にする。質問には「どこを検索して、決まっていないと確かめたか」を添える
17. 文書と実装、または文書どうしの食い違いを、推測で解かない。[`known-doc-drift.md`](known-doc-drift.md) に無いものを見つけたら報告する
18. 証拠なく削除しない。**中身を読み**、利用の参照が無いことを確かめ、確認の方法と結果を PR 本文に貼る
19. 症状を隠す修正（空の catch、`!` や型キャストでの黙らせ、テストの無効化、抑止の追加）をしない
20. 編集前に baseline を記録する（§6）。baseline で既に落ちているものを、自分の変更のせいにも手柄にもしない
21. 実証していない観測を台帳へ起票しない

### 4-4. 触ってはならない領域

22. **#4022 の領地**: `backend/src/main/java/.../common/` と `config/` のクラスの移動・改名・分割、番人（`common/architecture/`）、凍結物（§3-5）、`backend/build.gradle.kts`・`settings.gradle.kts`・`backend/build-logic/`・`.github/workflows/backend-ci.yml`・`shard-weights.properties`、Modulith 基盤、各ドメインの越境参照の解消
23. **BE の main コードのクラスの移動・改名・分割。** 凍結ストアの違反行はクラス名とメソッド署名で記録されており、動かすだけで新しい違反として CI が落ちる
24. **凍結ストアを書き戻す系統の番人を絞って実行しない。** `archUnitTest`（`@AnalyzeClasses` 系）と、凍結ストアを使うテストを `--tests` で絞ると、ストアから違反行が静かに消える。タグで走る系統（`archTest`）は、完全修飾名での絞り込みが許されている（`TEST_CONVENTION.md` §7.1）。見分けが付かないときは絞らない
25. 既存の migration、`docs/openapi.json` と `frontend/app/types/generated/` の手編集
26. 決済（`payment/`・`billing/`・`advertising/` の Stripe 呼び出し）、認可（`role/`・`common/visibility/`・各 `*ScopeAccessGuard`）、`SecurityConfig`、各種 Filter、トランザクション境界、outbox
27. FE の `useApi.ts`、`nuxt.config.ts`、`useAuthStore.ts`、牧場の生成物（`app/utils/ranch-production-assets.ts`、`frontend/scripts/verify-ranch-*`）
28. **未完の台帳行がある対象。** 着手前に、対象のファイル名とクラス名で `docs/task-list.md` を検索する。未完の行があれば触らず、所管として報告に載せる。レーン文書の「対象外」は起草時点の照合結果であり、着手時に必ず取り直す

### 4-5. 並行作業

29. 横展開型のレーン（R3・R4・R5）は、**着手前に** #4599 のコメントで対象ファイルを宣言する。PR を立ててからでは遅い。断念するときは解放を宣言する
30. 重い Gradle 実行は、`backend/` の中で `bash scripts/gradle-turnstile.sh bash ./gradlew …` として直列化する
31. 台帳は worktree と PR で扱う。新しい行は末尾に足し、列は7つ、未完の行は状態の先頭に `P1`〜`P5` を付ける
32. 複数のレーンが触りうる文書の担当を固定する
    - `TEST_CONVENTION.md`、`frontend/FRONTEND_CODING_CONVENTION.md`、`backend/BACKEND_CODING_CONVENTION.md`、`backend/.claudecode.md` を編集するのは **R6 だけ**
    - `docs/testing/real_e2e_setup.md` を編集するのは **R2 だけ**
    - `docs/refactoring/proposals/` を書くのは **R7 だけ**
    - 他のレーンが追記したい内容は、担当レーンへの依頼として報告に書く

---

## 5. Stop And Ask Conditions

次のどれかに当たったら、その作業を止める。答えに依存しない部分は続けてよい。

- [`decided-facts.md`](decided-facts.md) と規約・設計書・台帳を検索しても、正しい仕様が分からない
- テストと実装が矛盾している、または `@Disabled` の理由が「未実装」「実装ギャップ」である
- 削除の候補に、利用の参照が無いと言い切れないものがある
- 変更が、公開 API・DB スキーマ・保存済みデータ・Cookie・環境変数名に及ぶ
- 変更が、認証・認可・課金・通知・外部連携に及ぶ
- `docs/openapi.json`、ER 図、凍結物のいずれかに差分が出た
- baseline では通っていた検証が落ち、原因が30分で特定できない
- 対象に、他セッションの担当宣言か、未完の台帳行がある
- 設計案が複数あり、選ぶことがプロダクトの判断になる
- 規約を変える必要が出た（規約の変更はマスターへの上奏事項である）

質問は §10 の報告の「質問」欄に、**対象・観測した事実・検索して決まっていないと確かめた場所・選択肢・推奨**の5点で書く。

---

## 6. Baseline Commands

編集前に実行し、結果（終了コード・件数・所要時間）を記録する。worktree の中で実行する。**どのディレクトリで打つかを必ず守る**。

### 6-1. 共通（リポジトリのルートで）

```bash
pwd && git status --short | head -20 && git log --oneline -1
```

### 6-2. FE（`frontend/` で。Node 22.23.2・npm 10.9.8 固定）

| コマンド | 保証するもの | 注意 |
|---|---|---|
| `npm ci` | lockfile どおりの導入 | lockfile を更新しない |
| `npm run lint` | ESLint（握りつぶし catch の番人を含む） | `eslint-suppressions.json` が効く。件数がずれると終了コード2で落ちる |
| `npm run lint:prune` | 不要になった抑止の削除 | 全ファイルが対象。実行後に `git diff frontend/eslint-suppressions.json` で、消えたのが自分の分だけかを確かめる |
| `NODE_OPTIONS=--max-old-space-size=8192 npm run typecheck` | `nuxt typecheck` | 既定のヒープでは落ちる。`vue-tsc` 単体では代わりにならない |
| `npx vitest run <対象 spec>` | 対象の単体テスト | 結果が0件で終了コード0なら、実行されていない。件数を見る |
| `npm run test:unit` | 牧場の検証を含む単体テスト全量 | 重い。PR 前に1回 |
| `npx playwright test --config <config> --list` | E2E の収集対象 | サーバ無しで動くかは config による（`globalSetup` を持つものは未確認）。R2 で確かめる |

`frontend/.husky/pre-push` は `lint`・`typecheck`・`test:unit` を走らせる定義になっている（フックが有効かどうかは環境による）。

### 6-3. BE（`backend/` で。WSL / Linux。Docker が要るものがある）

| コマンド | 保証するもの | 注意 |
|---|---|---|
| `./gradlew compileJava compileTestJava --no-daemon` | 本番とテストの型 | 日常の確認はこれ |
| `./gradlew verifyShardCoverage -Pshard.total=6` | テストクラスが必ず1つの shard に入る | テストクラスを足したり改名したりしたとき |
| `./gradlew archTest` | タグ `archunit` が付いた番人 | `check` に含まれる。完全修飾名での絞り込みは可 |
| `./gradlew archUnitTest archUnitFreezeStoreIntegrityTest` | `@AnalyzeClasses` 系の番人と凍結ストアの整合 | `test` に含まれる。**絞らない** |
| `./gradlew test -PexcludeMigrationTests=true` | 単体と結合（Flyway の再生を除く） | 極めて重い（CI は6 shard）。原則 CI に任せる |
| `./gradlew checkErDiagrams -Der.requireDocker=true` | ER 図と実スキーマの一致 | 本書の範囲では差分が出ないはず |
| `./gradlew generateOpenApiDocs -PopenApiPort=8082` | `docs/openapi.json` の再生成 | 本書の範囲では差分が出ないはず |

### 6-4. ハーネス（リポジトリのルートで）

```bash
node scripts/harness-doctor.mjs
node --test scripts/*.test.mjs
```

### 6-5. 負債量の測り直し（リポジトリのルートで。着手時と完了時に記録して比べる）

```bash
# R3: 握りつぶし catch の抑止を、場所別に（起草時: tests/e2e 90 ファイル・478 件、tests/unit 1 ファイル・1 件）
node -e "const s=require('./frontend/eslint-suppressions.json');const g={};for(const f in s){const k=f.split('/').slice(0,2).join('/');const c=Object.values(s[f]).reduce((a,x)=>a+x.count,0);g[k]=g[k]||[0,0];g[k][0]++;g[k][1]+=c}console.log(g)"
# R2: Playwright の設定の本数（起草時: 27）
git ls-files frontend | grep -cE '^frontend/(playwright|pw-)[^/]*config\.ts$'
# R5: Thread.sleep の実呼び出し（起草時: 43 行・32 ファイル）と @Disabled のアノテーション（16 行・11 ファイル）
git grep -nE 'Thread\.sleep\(' -- backend/src/test | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' | wc -l
git grep -nE 'Thread\.sleep\(' -- backend/src/test | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' | cut -d: -f1 | sort -u | wc -l
git grep -nE '^\s*@Disabled' -- backend/src/test | wc -l
git grep -lE '^\s*@Disabled' -- backend/src/test | wc -l
```

`git grep -c` はファイルごとの件数を出すので、`| wc -l` を付けるとファイル数になる。件数が欲しいときは `git grep -n … | wc -l` を使う。コメントの中の言及を数えないように、`Thread.sleep` は行頭がコメント記号の行を除き、`@Disabled` は行頭のアノテーションだけを数えている。

---

## 7. Debt Map

凡例: **可** = 本書の規律の範囲で実装してよい。**提案のみ** = R7 が提案書を書く。**是正計画** = 規約で既に禁じられており、どう直すかの計画を R7 が書く。**所管あり** = 別の戦役が持っている。触らない。

### 7-1. リポジトリ衛生（R1）

| # | 負債 | 根拠 | なぜ負債か | リスク | 可否 |
|---|---|---|---|---|---|
| H1 | 一回限りの移行スクリプト | `backend/scripts/replace_getCurrentUserId*`（7本）、`backend/scripts/test_ym*.sql`（3本）。中身を確認済み。置換は完了しており、参照は無い | 本物のスクリプトを探す妨げになる | 低 | 可 |
| H2 | 一回限りの補助スクリプト | `scripts/merge_locales.py`（今の置き場では動かない）、`scripts/resolve_conflicts.py`（特定の4ファイルの競合を解く） | 同上 | 低 | 可 |
| H3 | `.gitignore` の穴 | ルートに `scratch_*`・`.tmp-*` などの未追跡物が溜まる | `git status` が読めず、誤コミットの元になる | 低 | 可 |
| H4 | 古い文書 | 記事原稿2本、古い計画書4本（R1 に一覧） | 古い数値や完了済みの手順が現行と誤読される | 低 | 可（裁可済み: 原稿は移動、計画書は削除） |
| H5 | 起動スクリプトが個人環境に依存 | `start-backend.vbs`・`start-backend.ps1`・`backend-loop.sh` がパスを直書き。BE 常駐の正系である | 別の PC では動かない | 中 | 提案のみ（P14）。台帳 CMP-260925-0906 が隣接 |

### 7-2. FE（R2・R3・R4）

| # | 負債 | 根拠 | なぜ負債か | リスク | 可否 |
|---|---|---|---|---|---|
| F1 | Playwright の設定の乱立 | `frontend/` 直下に27本。25本が個別用。11本は他のファイルから参照されない。命名が3流儀。対象の spec はすべて現存する | 本線の設定が埋もれ、差が意図か偶然か分からない | 中。本線が一部の spec を除外しており、専用の設定を消すとどこからも実行されなくなる。既定のポートも設定ごとに違う | 可（R2）。個別用のうち6本は、所管があるか固有の設定があるため対象外（一覧の正本は R2 の文書） |
| F2 | **E2E テストの中の握りつぶし catch** | `frontend/eslint-suppressions.json` の 479 件のうち 478 件が `tests/e2e/` の 90 ファイル。画面のコード（`app/`）には無い。約230件は、スピナーの消滅を待つ同じ1行のコピーで、同じ処理をする共通ヘルパ `waitForSpinnerGone` が既にある。約190件は「要素が見えなければ先へ進む」形 | 前者は重複。後者は、落ちるべきテストが通ってしまう | 前者は低。後者は中（テストの合否が変わる） | 可（R3）。3ファイルは所管あり |
| F3 | 巨大ファイル | 1,000行超が4本、700〜999行が23本（生成物と JSON を除く）。規約の目安は300行（FE 規約 §6） | 変更が衝突し、レビューが重い | 型は低、スケジュール系の SFC は高 | 可（R4）。スケジュール系5本は提案が先（P10） |
| F4 | 手動型と生成型の二重管理 | 手動型 178 ファイル。生成型は、注釈が整った DTO では nullable などを出すが、未整備の DTO では出ない | 手動型と API のずれが実行時まで見つからない | 高 | 所管あり（CMP-261012-2342、CMP-260912-1823、CMP-261017-1026 ほか）。R7 は計測だけを足す（P1） |
| F5 | spec の置き場所が規約と合っていない | 規約は「対象と同じディレクトリ」。実態は `tests/unit/` に 531、同居が 293 | 新しい spec の置き場所が割れる | 低 | **新規は規約どおり同居**。既存を動かすかは上奏（[`known-doc-drift.md`](known-doc-drift.md) の F5） |
| F6 | 共通部品への未移行、Dialog と Modal の併存 | `PageHeader` を使わない pages が 228。`*Dialog.vue` 97、`*Modal.vue` 83 | 見た目と操作が揺れる | 中（見た目が変わる） | 範囲外（`/統一` の戦役） |
| F7 | ロケールの列挙の直書き | `nuxt.config.ts` に各言語 76 の JSON を列挙 | 登録漏れで訳が出ない。lint と単体テストでは検出されない | 高 | 提案のみ（P13） |

### 7-3. BE のテスト（R5）

| # | 負債 | 根拠 | なぜ負債か | リスク | 可否 |
|---|---|---|---|---|---|
| T1 | `Thread.sleep` | 実呼び出しが 32 ファイル・43 行。規約が禁止（`TEST_CONVENTION.md` §10） | 遅く、環境で不安定になる | 中。多くが並行性・WebSocket の結合テストで、置換が難しい | 可（R5）。2ファイルは所管あり、1ファイルは番人で対象外 |
| T2 | `@Disabled` | アノテーションが 11 ファイル・16 行。規約は1スプリント以内 | 守られていない仕様が見えなくなる | 低 | 棚卸しのみ可。有効化は仕様の判断 |
| T3 | テスト全量が重い | CI は6 shard、1 shard が最大120分 | 開発の往復が遅い | 高 | 所管あり（CMP-261004-2047、CMP-261108-1103、CMP-261111-0508、CMP-261014-1428、CMP-261004-1824） |

`application-test.yml` が2か所にあるのは意図した設計であり、負債ではない（決定 I9）。

### 7-4. 文書（R6）

[`known-doc-drift.md`](known-doc-drift.md) に一覧がある。「是正」と書いたものを R6 が直す。「上奏」はマスターの判断を待つ。

### 7-5. BE 本体・インフラ（R7。実装しない）

| # | 負債 | 根拠 | 位置づけ |
|---|---|---|---|
| B1 | Service が `ApiResponse<Entity>` を返す | 12 ファイル・36 箇所（`gamification` 5・12、`knowledgebase` 5・16、`skill` 2・8） | **是正計画**（P2）。規約（`backend/.claudecode.md` §19）が禁じている。`knowledgebase` は CMP-261023-2301 が所管 |
| B2 | 必須の環境変数に既定値がある | `mannschaft.allowed-origins` の既定が localhost。本番用の設定に記載が無い | **是正計画**（P3）。規約（同 §21）違反 |
| B3 | 鍵の再暗号化が未実装 | 方式は決定済みで列も追加済み。バッチと次の鍵の受け口が無い | **実装計画**（P4。決定 S12） |
| B4 | デプロイの認証が方針と合っていない | 方針は OIDC で、ロールは Terraform に定義済み（AWS 上の作成状況は未確認）。workflow が長期キーのまま | **切り替えの計画**（P5） |
| B5 | BE の公開パスの一部が Cloudflare の振り分けの外 | 決済からの戻り先4本と外部 Webhook の受け口 | **起票済み**（台帳 CMP-261117-1220。[`known-doc-drift.md`](known-doc-drift.md) の I5）。案の比較は P7 |
| B6 | FE を Cloudflare で動かす手段が未決 | ビルドは Node サーバ向け。Cloudflare 向けの設定も、デプロイする workflow も無い | 提案のみ（P6）。台帳 CMP-260826-2129・CMP-260821-1235・CMP-261112-0158 |
| B7 | `ScopeType` の重複 | 同じ名前の enum が8つ、末尾が `ScopeType` の別名の enum が24 | 提案のみ（P8） |
| B8 | Stripe の呼び出しと Webhook の入口の分散 | `payment/`・`billing/`・`advertising/` が SDK を直接呼ぶ | 提案のみ（P9）。台帳 CMP-260918-1359 が隣接 |
| B9 | 広域 catch | `catch (Exception\|Throwable` が 388 ファイル・742 行 | 提案のみ（P12）。握りつぶしは規約違反、非同期リスナーの境界の catch は正規形 |
| B10 | 巨大クラス | 1,000行超が20本（`GlobalExceptionHandler`、`UserRoleRepository` ほか） | **所管あり**（#4022）。R7 は申し送りだけを書く |
| B11 | `README.md` が 126KB で、インフラ構成の記述が古い | 冒頭に個別 PR の進捗が積もる | 提案のみ（P11） |

---

## 8. Implementation Phases

### Phase 0 — 現在の状態の確認（全レーン共通・必須）

1. worktree を作り、`pwd` と `git status` を確かめる
2. [`decided-facts.md`](decided-facts.md) と [`known-doc-drift.md`](known-doc-drift.md) を読む
3. §6 の baseline を実行して記録する。§6-5 で負債量を測り直す
4. 自分のレーンの対象を、ファイル名とクラス名で `docs/task-list.md` と #4599 のコメントから検索し、重なりを取り直す
5. 子の CMP を採番し、台帳を「実装中」にする PR をマージする
6. #4599 のコメントで担当を宣言する

### Phase 1 以降 — レーン

| レーン | 文書 | PR の scope の見込み | 他レーンとの関係 |
|---|---|---|---|
| R1 | [`lanes/R1-repo-hygiene.md`](lanes/R1-repo-hygiene.md) | 削除する場所による（`docs/`・`scripts/` は `tooling`、それ以外は `app`） | 独立 |
| R2 | [`lanes/R2-playwright-config.md`](lanes/R2-playwright-config.md) | `app` | 独立。R3 と同じ spec を同時に触らない |
| R3 | [`lanes/R3-e2e-swallow-catch.md`](lanes/R3-e2e-swallow-catch.md) | `app`（`eslint-suppressions.json` を変えるため） | レーンの中を複数セッションで分担できる |
| R4 | [`lanes/R4-fe-large-files.md`](lanes/R4-fe-large-files.md) | `app` | 独立 |
| R5 | [`lanes/R5-be-test-hygiene.md`](lanes/R5-be-test-hygiene.md) | `test-only` | 独立 |
| R6 | [`lanes/R6-docs-alignment.md`](lanes/R6-docs-alignment.md) | 文書の場所による（PR を分ける） | 規約の編集を一手に持つ |
| R7 | [`lanes/R7-proposals.md`](lanes/R7-proposals.md) | `tooling` | 独立 |

推奨の順序は、安全なものから: **R1 → R6 → R5 → R2 → R3 → R4 → R7**。R7 はいつ始めてもよい。

### 大きな設計変更

§7-5 の全項目は、承認なしに実装しない。R7 が提案書や計画にまとめ、マスターの裁可を待つ。

---

## 9. Verification Requirements

### 9-1. 各 PR で

- 変更に対応する §6 のコマンドを実行し、**実際の出力**を PR 本文に貼る
- FE を触ったら `lint`・`typecheck`・対象の spec。PR 前に `test:unit` を1回
- BE のテストを触ったら `compileJava compileTestJava`。テストクラスを足したり改名したりしたら `verifyShardCoverage`。全量は CI に任せる
- `git diff --stat` に、`docs/openapi.json`・`frontend/app/types/generated/`・`docs/architecture/er/`・凍結物が現れたら、想定外である。止める
- 構造だけの分割は、前後で公開する名前（export・props・emits・ルート・自動 import の名前）が同じことを示す
- 削除は、中身の確認の結果と、参照の確認の方法と結果を貼る
- 負債量を §6-5 で測り直す。**抑止や凍結の行数が増えていたら失敗**である

### 9-2. 受け入れ条件とテストの対応

レーン文書の完了条件を受け入れ条件として番号を振り、条件ごとに「確かめたテストまたは証拠」を対応させた表を PR 本文に載せる。攻め口の5類型（空／境界値／途中失敗／認可／性能）について、対象の条件を挙げるか、「該当なし」と理由を書く。

### 9-3. 完了ゲート（正本は `docs/development/completion-evidence.md` と `.claude/commands/凱旋.md`）

- PR の scope は、変更したファイルから決まる（決定 P5）。`node scripts/completion-evidence.mjs` の推定に従い、軽く申告しない
- `app` の PR は、**実機 E2E（権限あり／権限なし／他テナントのロール横断、URL の直打ちを含む）と、その後のアリシゼーションの両方**が通らなければ、台帳の行を完了にできない。挙動を変えない変更でも、規則は同じである
- 実施できない事情があるときは、PR 本文と台帳に未実施と理由を書く。そのレーンは完了にしない
- 台帳の行を完了へ動かすには `docs/evidence/<CMP-ID>.json` が要る。証拠の欄は URL かパスだけを書く。締めは実装の PR をマージした後に行う
- 実機の検証は `scripts/verification-run.mjs` を通す。検証用のポートと CORS の上書きは `CLAUDE.md` のポート規約に従い、ブラウザは `localhost` で開く
- 凱旋では、`node scripts/worktree-cleanup.mjs --days 1 --check --limit 60` と、WSL の検証コピーの `node scripts/verification-run.mjs --check` を済ませる

---

## 10. Reporting Format

レーンが完了したとき、または止まったときに、次の形で報告する（PR 本文にも同じ項目を使う）。

```markdown
## リファクタリング報告: <レーン名>（<日付>、子 CMP: <ID>）

### 結果
- 状態: 完了 / 一部完了 / 質問待ち
- PR: <URL と状態>
- 負債量: <指標> 着手時 N → 完了時 M

### 変更の要約
- <PR ごとに、何を・なぜ・どのファイルを>

### 受け入れ条件とテストの対応
| 条件 | 確かめたテストまたは証拠 |
|---|---|

### 実行したコマンドと結果
| コマンド | 実行した場所 | 結果（終了コード・件数・所要時間） |
|---|---|---|

### baseline との差
- baseline で既に落ちていたもの:
- 自分の変更で新しく落ちたもの:

### 実施しなかったこと
- <飛ばした手順と理由。実機とアリシゼーションの未実施を含む>

### 所管があるため触らなかった対象
- <ファイルと CMP-ID>

### 質問
- 対象 / 観測した事実 / 検索して決まっていないと確かめた場所 / 選択肢 / 推奨

### 見つけたが直していない問題
- <実証済みのものは、台帳へ起票した CMP-ID。未実証のものは観測として書く>
```

---

## 11. Out-of-scope Items

| 項目 | 理由 | 所管 |
|---|---|---|
| BE のドメイン境界負債、`common` と `config` の整理、Gradle の分割、Modulith、巨大クラスの分割 | 進行中の戦役がある | CMP-261021-0017（#4022） |
| `UuidV7Entity` を継承していない Entity（凍結 569 行） | 同上 | CMP-261021-0115 |
| テスト全量の高速化、shard の割当 | 同上 | CMP-261004-2047 ほか（§7-3 の T3） |
| `payment` の巨大クラスの分割と金額の値オブジェクト | 同上 | CMP-260918-1359 |
| 時刻設計の是正、TODO コメントの棚卸し | 同上 | CMP-023、CMP-019 |
| 手動型から生成型への移行 | 同上 | CMP-261012-2342 ほか |
| 画面側の握りつぶし catch（取得の失敗を空に見せる画面） | 同上。過去に CMP-260922-2045 で一括して返済済み | CMP-260925-0904、CMP-260930-1533、CMP-260909-0543、CMP-260910-1602、CMP-260912-0910 |
| 本番環境の構築、Terraform、deploy workflow、secrets | 本番は未構築 | CMP-260826-2129、CMP-260821-1235 |
| 停滞している worktree の掃除 | 他者の資産 | CMP-261108-1829、`/陣払い` |
| 依存ライブラリの更新 | 固定に理由があるものがある | CMP-261006-1342、CMP-261021-0813、CMP-261029-1315、CMP-261103-0818 |
| 既存の migration の整理 | checksum と番人を壊す | — |
| 認証・認可・課金・Webhook・トランザクション境界・outbox の変更 | 事故の影響が大きい | — |
| 画面デザインの統一 | 見た目が変わる | `/統一` の戦役 |
| 本陣の未追跡ファイル、`.claude/` 配下の未追跡物 | 他者の資産 | — |
| 新機能、性能改善、見た目の変更 | リファクタリングではない | — |

---

## 付録 A. 質問と回答の記録

### A-1. 第1版でマスターに尋ね、回答を得たもの（2026-11-17）

| ID | 質問 | 回答 | 備考 |
|---|---|---|---|
| Q1 | FE のデプロイ先と描画方式 | BE は AWS、FE は Cloudflare。公開ページ（LP を含む）は SSR、ログイン後は SPA に統一 | **既に決まっていた**（Terraform のコメント、`backend/.claudecode.md` §2）。尋ねる必要は無かった。未決なのは実現の手段だけ |
| Q2 | Web の FE 以外のクライアント | 今は無い。PWA でスマートフォンに対応し、将来 Android / iOS | **既に決まっていた**（`docs/features/F01.1_auth.md` がネイティブアプリの認証を設計済み）。尋ねる必要は無かった |
| Q3 | 追跡されている残骸の削除 | 内容を確認して、不要なら削除 | 過去の決定は無かった |
| Q4 | 古い文書の扱い | 記事原稿は移動、計画書は削除 | 同上 |
| Q5 | 本陣の未コミット変更 | マスターの作業ではない。あるべき姿に | 2026-11-17 に main の状態へ復元済み。控えは本陣の `.claude/honjin-uncommitted-backup-20261117.patch` |
| Q6 | 親 Issue と台帳の行 | 起こす | #4599、CMP-261117-1026 |

追加の決定: 退会は実装が正（物理削除あり）。BE 本体は提案のみ。

### A-2. 第2版の時点で、マスターの判断を待っているもの

規約・設計書・台帳・`docs/master-confirmations.md` を検索して、決まっていないことを確かめたものだけを挙げる。どれも、答えが無くても各レーンは既定の動作で進められる。

| ID | 判断してほしいこと | 検索して確かめたこと | 既定の動作 |
|---|---|---|---|
| N1 | 認証トークンの記述が古い規約5か所（FE 規約 §5・§7、BE 規約の認証の節、`docs/conventions/backend/08-security.md`、`backend/.claudecode.md` §2）を、実装と認証の設計書（Web は Cookie、ネイティブは Bearer。FE はメモリ保持）に合わせて直してよいか | 設計書と実装は一致しており、規約だけが古い。規約の変更は上奏事項（`backend/.claudecode.md` §28） | R6 は直さず、食い違いの一覧に載せたままにする |
| N2 | 挙動を変えない `app` の PR（`.gitignore`、ルートの文書の削除、コーディング規約の文書の修正、E2E の設定ファイルの削除）にも、実機 E2E とアリシゼーションを課すか | `completion-evidence.md` の規則は例外を設けていない。コーディング規約2本（FE・BE）が `tooling` の一覧に無く、文書だけの変更でも `app` になる。なお spec だけの変更は `test-only` であり、この問いの対象ではない（R3 は `eslint-suppressions.json` も変えるので `app` になる） | 規則どおり課す |
| N3 | FE の spec の置き場所。既存の `tests/unit/` の 531 本を規約どおり同居へ動かすか、規約を実態に合わせるか | 規約は「同居」で確定している。既存を動かすかどうかの決定は無い | 新規は同居。既存は動かさない |
| N4 | そのほか、[`known-doc-drift.md`](known-doc-drift.md) で「上奏」とした食い違い（Cookie の有効期間、暗号鍵と許可オリジンの必須性、イベントの時刻の型、Checkstyle・SpotBugs、トーストの表示時間など） | それぞれ、文書どうしか文書と実装が食い違っており、どちらが正しいかの決定が見つからない | 一覧のままにし、推測で直さない |
