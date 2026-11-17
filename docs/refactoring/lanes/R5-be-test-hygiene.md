# R5 — BE テストの `Thread.sleep` の置換と `@Disabled` の棚卸し

> 親文書: [`../refactor-instructions.md`](../refactor-instructions.md)。先に親文書と [`../decided-facts.md`](../decided-facts.md) を読むこと。進め方は親文書 §4-1 の正規フローに従う。
> 足軽のモデル: Sonnet。
> **横展開型のレーンである。着手前に #4599 のコメントで対象ファイルを宣言すること。**
> このレーンが触るのは、`backend/src/test/` と、棚卸しの文書1本（`docs/testing/be-test-hygiene-inventory.md`）だけである。`backend/src/main/` は1行も変えない。PR の scope は `test-only` になる。

## 目的

規約（`TEST_CONVENTION.md` §10）が禁じているのに残っている `Thread.sleep` を減らし、滞留している `@Disabled` を見える形にする。

## 先に読むもの

- `TEST_CONVENTION.md` の §2.4（時刻に依存するテスト）、§7.1（番人の実行）、§9.7（`@Transactional` なテストと通知）、§10
- `docs/conventions/backend/30-arch-guard-tests.md`（凍結ストアの節は全文）
- `docs/conventions/backend/06-event-driven.md` §6.1
- Awaitility を使っている既存のテスト（117 ファイルある。同じドメインのものを手本にする）

## 現状（2026-11-17 実測）

- `Thread.sleep` の実呼び出しは 32 ファイル・43 行である（コメントの中の言及を除く）
- `@Disabled` のアノテーションは 11 ファイル・16 行である（Javadoc の中の言及を除く）

### 対象外（着手時に取り直す）

| ファイル | 理由 |
|---|---|
| `common/architecture/FlywayMigrationTimeFunctionGuardScanningLogicTest` | 番人。#4022 の領地 |
| `common/migration/FlywayFromScratchMigrationTest` | migration の番人。未完の台帳行 CMP-260930-2313、CMP-260924-0012、CMP-260909-2154 が対象にしている |
| `team/affiliation/TeamOrgInviteCommittedIT` | 未完の台帳行 CMP-261004-1822（稀に 500 になる競合の調査中）。待ちを直すと、原因の調査を隠すおそれがある |
| `common/visibility/guard/NotificationDispatchVisibilityGuardTest` の `@Disabled` | 番人。一覧に載せるだけ |

名前に `Arch` か `Guard` を含むテストと、`common/architecture/` 配下のテストには触らない。

### `Thread.sleep` がある場所（実呼び出しがあるもの。パスは `backend/src/test/java/com/mannschaft/app/` から）

- `auth/mfa/`: `ConcurrentOnEvent`、`MfaEmailOtpSendIT`、`MfaEmailOtpValidateIT`、`MfaEmailSetupIT`、`MfaReauthOtpIT`
- `auth/service/BirthProfileLockWaitObserver`
- `billing/api/PriceRevisionReachesPlanChangeIT`
- `chat/ws/ChatChannelSubscriptionAuthzIntegrationTest`
- `common/schema/EntityDigitBoundaryColumnFlywaySchemaIT`
- `config/RequestLoggingFilterTest`
- `organization/teamgroup/`: `OrgTeamGroupAssignmentConcurrencyIT`、`OrgTeamGroupAsyncAndConcurrencyIT`
- `ranch/RanchDevelopmentPolicyIT`
- `role/`: `InviteAcceptanceItSupport`、`InviteGenerationConsistencyIT`
- `shift/ShiftSlotMoveConcurrentOverlapIT`
- `shiftbudget/MonthlyShiftBudgetCloseTransactionIT`
- `social/announcement/audience/BroadcastGroupPushIT`
- `team/affiliation/`: `TeamOrgAffiliationBatchesCommittedIT`、`TeamOrgLifecycleCleanupCommittedIT`、`TeamOrgLifecycleCommittedItSupport`、`TeamOrgLifecycleConcurrencyIT`
- `team/migration/TeamOrgAffiliationEntityRoundtripFlywayIT`
- `tournament/entry/TournamentEntryPkTypeFlywayIT`
- `village/`: `event/VillageCharterWithdrawalIT`、`migration/VillageMembershipSlotMigrationTest`
- `websocket/`: `WebSocketPrincipalWiringIntegrationTest`、`WebSocketStompRejectionIntegrationTest`、`relay/WebSocketRelayMultiNodeIntegrationTest`

並行性と WebSocket の結合テストが多い。**すべてを置き換えられるとは限らない。** 置き換えられなかったものを理由付きで一覧にすることも、このレーンの成果である。

## 最重要の注意

- **凍結ストアを書き戻す系統の番人を絞って実行しない**（親文書 §4-4 の24番）。ローカルの確認は `./gradlew compileJava compileTestJava --no-daemon` に留め、テストの実行結果は CI で見る
- 自分の変更で `backend/src/test/resources/archunit_store/` や `*_freeze.txt` に差分が出たら、**commit せずに止める**
- **テストクラスを改名・移動・追加しない。** shard の割当が変わる。どうしても要るなら `./gradlew verifyShardCoverage -Pshard.total=6` を通す
- 重い Gradle 実行は、`backend/` の中で `bash scripts/gradle-turnstile.sh bash ./gradlew …` として直列化する

## 手順

### ステップ1: `Thread.sleep` を分類する

1件ずつ、何を待っているかで分ける。

| 種別 | 例 | 直し方 |
|---|---|---|
| ① 非同期の処理の完了待ち | `@Async` のリスナー、イベントの配送、outbox の取り込み、WebSocket のフレームの到着 | Awaitility で、**観測できる条件**（DB の行、モックの呼び出し回数、受信したフレーム、状態の値）を待つ。タイムアウトは同じドメインの手本に合わせる |
| ② 時刻を進めるための待ち | 「少し後の `created_at`」「有効期限切れ」 | 規約 §2.4 の3つの形のどれかにする。入力と期待値の両方を相対にする／固定の入力を引数で渡す／本番コードに `Clock` を注入する。**3つ目は `src/main` の変更になるので、このレーンではできない。** その場合は止めて報告する。時刻を SQL で直接書き込む直し方はしない（規約 §10 が手書きの INSERT を禁じている。DB の時刻は UTC である） |
| ③ 並行性のテストでタイミングを作る待ち | 2つのスレッドを競合させる | `CountDownLatch` などで順序を決める。難しければ直さずに一覧へ残す |
| ④ 対象外 | 上の「対象外」 | 触らない |

### ステップ2: 置き換える

1. 1 PR は、同じドメインの最大5ファイル
2. **通知やイベントの配送を待つテストでは、先にそのテストが実効的に `@Transactional` かを確かめる。** そうであれば、コミット後のリスナーは動かない。そのテストは、待ちを直しても何も検証していない（決定 T3）。この場合は置き換えず、「配送を検証できていないテスト」として一覧に載せて報告する。直し方は `docs/conventions/backend/06-event-driven.md` §6.1 の手順に従う必要があり、テストの作り直しになる
3. 置き換えるたびに、そのテストが検証している内容が変わっていないことを確かめる。待ちの条件を緩くして、落ちるはずのテストが通るようにしてはならない
4. 「待った後に何も起きていないこと」を確かめるテストは、Awaitility の `during` 系で「一定の時間ずっと成り立つ」形にする。単に待ちを消してはならない。この形のテストが、そもそも実効的に `@Transactional` で偽の緑になっていないかも確かめる
5. `compileTestJava` を通す。PR を出して CI を見る
6. CI で不安定になったら、再実行で通して済ませない。待ちの条件が、実装の本当の完了条件と合っているかを調べ直す

### ステップ3: `@Disabled` を棚卸しする（表を作るだけ）

`docs/testing/be-test-hygiene-inventory.md` に次の表を作る。

| テスト | 無効にした理由（原文） | 分類 | 推奨 |
|---|---|---|---|

分類は、**DB の仕様による制約**（MySQL の CHECK、NULL を含む一意制約）／**実装のギャップ**（Bean Validation しか無いなど）／**未実装の機能**／**作り直し待ち**／**不明**。

起草時の見立ては、DB の仕様が8件前後、実装のギャップが3件（`ActionMemoServiceProgressTest`）、未実装が2件（`RepairPlanAuditLogIntegrationTest`）、作り直し待ちが2件である。

有効にも削除にもしない。「実装のギャップ」と「未実装」は、仕様として必要かどうかの判断が要るので、質問として報告に載せる。同じ文書に、置き換えられなかった `Thread.sleep` の一覧も載せる。

## やってはならないこと

- `backend/src/main/` の変更（テストを通すための本体の修正を含む）
- テストの削除、`@Disabled` の追加、アサーションの削除や緩和
- タイムアウトをむやみに伸ばして通すこと
- `build.gradle.kts`、`build-logic/`、テストの JVM の設定、`shard-weights.properties` の変更
- 巨大なテストの `@Nested` への分割（shard に影響する。対象外）
- 対象外のファイルに触れること

## Stop And Ask

- 待ちを外すとテストが落ち、本体の側の競合や順序の不具合に見える
- 何を待っているのか読み取れない
- 凍結物に差分が出た
- 直すには `src/main` に `Clock` を入れる必要がある

## 完了条件

1. ①と②の `Thread.sleep` のうち、このレーンの制約の中で置き換えられるものが、置き換えられている
2. 置き換えなかったもの（③、④、`Clock` が要るもの、配送を検証できていないテスト）が、理由付きで一覧にある
3. `@Disabled` の棚卸し表がある
4. 着手時と完了時の件数が報告にある
5. `git diff --stat` に、`archunit_store/`・`*_freeze.txt`・`src/main/` が無い

## 検証

- `./gradlew compileJava compileTestJava --no-daemon`
- CI のジョブ「Compile & Test」（全 shard）、「Non-JST timezone contract」、「Shard coverage guard」、「ArchUnit tests (separate JVM)」が緑
- 親文書 §9-3 の完了ゲート（`test-only`）
- 検分に合格している
