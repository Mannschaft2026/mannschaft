<!-- `backend/BACKEND_CODING_CONVENTION.md` §5 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/BACKEND_CODING_CONVENTION.md（索引）に戻る](../../../backend/BACKEND_CODING_CONVENTION.md)

## 5. データアクセスと開発環境

### STRING永続化enumの定数追加

`@Enumerated(EnumType.STRING)` の定数を足すときは、`.claudecode.md` §23 と
[`二段階展開の作法`](../../development/persisted_enum_deployment.md) に従う。
定数だけを全タスクへ配り、旧タスクの退場を確認してから別リリースで書込みを始める。
番人 `PersistedEnumCompatibilityArchTest` が検知した追加を互換性台帳へ反映する前に、
PRで書込み経路・二段階計画・rollback下限をレビューする。

### データアクセス (Spring Data JPA + QueryDSL)
* **基本方式**: データアクセスには **Spring Data JPA** を使用する。各機能パッケージ内に `[Feature]Repository` インターフェースを作成し、`JpaRepository<Entity, Long>` を継承すること。
* **クエリ戦略**:
    * **単純なCRUDおよび名前ベースのクエリ**: Spring Data JPA の派生クエリメソッド（`findByEmail`, `findByStatusAndCreatedAtAfter` 等）を優先的に使用する。
    * **動的フィルタリング**: `.claudecode.md` §13 で定めたフィルタリング規約（`?status=active&price_min=1000` 等）の実装には **QueryDSL** を使用する。`BooleanBuilder` または `BooleanExpression` を用いてタイプセーフに条件を組み立てること。文字列ベースの `@Query` で動的条件を結合する方式は禁止する。
    * **固定的な複雑クエリ**: JOIN が多い集計クエリやレポート用クエリなど、動的条件を伴わない固定的な複雑クエリは `@Query`（JPQL）で記述してよい。ネイティブSQL（`nativeQuery = true`）は最終手段とし、使用時はコメントで理由を記載すること。
* **QueryDSL 設定**: Spring Boot 3.x (Jakarta EE) 環境では、`querydsl-jpa` に **`:jakarta` クラシファイア**を指定すること（`javax` → `jakarta` 名前空間対応）。Gradle の APT（Annotation Processing Tool）を導入し、`Q` クラスを自動生成する。生成されたクラスは `build/generated` 配下に出力し、バージョン管理対象外とする。
```kotlin
// build.gradle.kts
dependencies {
    implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
}
```
* **N+1 問題の防止**: リレーションの取得には `@EntityGraph` またはJPQLの `JOIN FETCH` を明示的に使用し、Lazy Loading による N+1 問題を防止すること。
* **ループ内で DB を呼ばない**: `for` / `forEach` / `stream` の `map`・`filter` 等のループ内で、Repository や他 Service の単件取得（`findById` 等）・`count`・`exists`・`save` を呼ばない。ID を集めて `findAllById` / `…In` / `GROUP BY` 集計 / `saveAll`・一括 UPDATE にまとめる（一覧・統計・CSV 出力・バッチが特に起きやすい）。
  ```java
  // NG: 行数ぶんクエリが走る
  for (var t : threads) { if (!readRepo.existsByThreadIdAndUserId(t.getId(), userId)) { readRepo.save(new Read(t, userId)); } }
  // OK: 1回で既読 ID を取り、差分を saveAll
  Set<Long> read = readRepo.findReadThreadIds(threadIds, userId);
  readRepo.saveAll(threads.stream().filter(t -> !read.contains(t.getId())).map(t -> new Read(t, userId)).toList());
  ```
* **`@Transactional` 内で取得した managed エンティティへの `save()` は不要**（ダーティチェックで反映される）。ループ内で呼ぶと無駄なクエリの温床になる。新規作成時のみ `save` / `saveAll` を使う。
* **件数上限なしの内部取得に注意**: 内部で `PageRequest.of(0, 10000)` や `Pageable.unpaged()` を使う箇所には `max-page-size`（100）が効かず、上限なしとして扱う。件数が増える対象では、ループ化せず集計クエリ（`GROUP BY`）で取得する。
* 棚卸しと解消計画: [n-plus-one-inventory-2026-11.md](../../development/n-plus-one-inventory-2026-11.md)

### 派生クエリの引数型は Entity 属性の型と一致させる — `@Enumerated` 属性に `String` を渡さない（2026-07-30〜）

#### 問題：`@Enumerated(EnumType.STRING)` の属性を `String` で受けると実行時に必ず 500 になる

Spring Data JPA の派生クエリ（`findByXxx`）の**バインド型はエンティティ属性側で決まる**。
DB のカラムが `VARCHAR` であっても、Entity 側が enum なら `String` を渡した時点で Hibernate のパラメータ束縛が失敗する。

```java
// Entity: enum 属性（列は VARCHAR だが JPA 上の型は enum）
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private NotificationScopeType scopeType;

// NG: String で受ける派生クエリ
Page<NotificationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
        String scopeType, Long scopeId, Pageable pageable);
// 呼び出し側は自然と .name() を書いてしまう
repo.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(NotificationScopeType.FRIEND_TEAM.name(), teamId, pageable);
```

実行時にこうなる（**コンパイルは通り、アプリ起動も成功する。実際にそのクエリが呼ばれた瞬間に初めて落ちる**）:

```
org.springframework.dao.InvalidDataAccessApiUsageException:
  Argument [FRIEND_TEAM] of type [java.lang.String] did not match parameter type
  [com.mannschaft.app.notification.NotificationScopeType (n/a)]
```

`GlobalExceptionHandler` に `DataAccessException` 系の個別ハンドラは無いため、汎用 `@ExceptionHandler(Exception.class)` に落ちて **HTTP 500** になる。

```java
// OK: Entity 属性と同じ enum 型で受ける
Page<NotificationEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
        NotificationScopeType scopeType, Long scopeId, Pageable pageable);
```

#### なぜ従来のテストで検出できなかったか

**リポジトリをモックした Service 単体テストでは原理的に検出できない。** モックは「`.name()` の文字列で呼ばれたこと」しか検証せず、Hibernate のパラメータ束縛が発生しないため、壊れたクエリが恒久的に偽グリーンになる。

- 実際に 2026-07-30 に `NotificationRepository` で 4 本（うち本番稼働経路 3 本）が同時に発覚した
- 該当経路は `@SpringBootTest` + Testcontainers で一度も実行されておらず、実機で初めて 500 として表面化した

#### ルール

1. 派生クエリの引数型は **Entity の属性型をそのまま書く**。`@Enumerated` 属性なら enum、`String` 属性なら `String`。
2. 呼び出し側で `.name()` / `valueOf()` を書きたくなったら、**それは引数型が間違っているサイン**。変換ではなく宣言を直すこと。
3. **ドメインをまたいで enum を渡す場合**（例: `scopefolder.ScopeType` → `notification.NotificationScopeType`）は、写像専用メソッドを 1 つ設けて明示的に写像する。**このとき `switch` を使ってはならない**（下記「⚠️ 罠」参照）。`==` による enum 参照比較で分岐し、未知値は `IllegalStateException` を投げること。黙って `null` や空リストを返して握りつぶすのは禁止。
    * ⚠️ **罠: 別ドメインの enum に対する `switch` は ArchUnit D-1 を落とす。** javac は enum switch のために `$SwitchMap$...` を保持する**合成クラス（`OuterClass$1`）を自動生成**する。この合成クラスは外側クラスの依存をそのまま写して持つため、`CrossDomainEntityImportArchTest`（D-1）が**新しいクラス名の新規違反**として検出し CI が落ちる（外側クラス本体の依存が凍結ストア済みでも、合成クラスは別名なので凍結に当たらない）。D-1 は合成クラスを除外していない。
    * `switch` を捨てるとコンパイル時の網羅性保証が失われるため、**必ずテストで肩代わりすること**。写像元 enum の `values()` を**全件ループ**して 1 つずつ写像が成功することを検査する番人テストを置く（定数を個別に書き並べる形にすると、新定数が増えても何も落ちない）。実例: `NotificationScopeTypeMappingTest`。
4. **enum 属性を条件に含む派生クエリは、実 DB（Testcontainers）を通すテストを必ず 1 本置く**。モック単体テストだけで済ませてはならない。
5. ネイティブクエリ（`nativeQuery = true`）は SQL 文字列として評価されるため `String` で正しい。JPQL / 派生クエリのみが本ルールの対象。

### Entity 更新パターン規約 — managed entity の直接ミューテートで行う（`toBuilder().build() → save` 禁止）（2026-06-19〜）

#### 問題：`toBuilder().build()` で再構築して `save()` すると INSERT 化する

`BaseEntity` / `UuidV7Entity` を継承したエンティティを以下のように更新しようとすると、**静かに INSERT が発行される**という致命的なバグが発生する。

```java
// NG: toBuilder().build() で再構築して save
MyEntity updated = entity.toBuilder()
    .name(request.name())
    .build();
myRepository.save(updated);  // → INSERT が発行される（id=null のため）
```

**原因（歴史的）**: かつて一部の Entity が（継承しているにもかかわらず）`@Builder` を使用していた。Lombok の素の `@Builder(toBuilder = true)` による `toBuilder()` は**同一クラスのフィールドのみ**をコピーし、`BaseEntity` / `UuidV7Entity` 等の**親クラス由来の継承フィールド（`id`）はコピーしない**。その結果、再構築されたインスタンスの `id` が `null` になり、JPA は新規INSERT と判断する。

**現在の実態**: 継承する Entity（`UuidV7Entity` / `BaseEntity` を継承するクラス）は **`@SuperBuilder(toBuilder = true)` が標準**（コードベースに 600 以上）。`@SuperBuilder` は親クラスのフィールド（`id` を含む）も `toBuilder()` でコピーするため、上記の toBuilder corruption は**構造的に解消済み**である。継承を持たない単純 DTO（例: `bulletin.dto.ThreadResponse`）はそのまま `@Builder` でよい（コピー対象がそのクラスのフィールドのみで完結するため安全）。

- 一意制約があれば `500 Internal Server Error` が発生する
- 一意制約がなければ**行が静かに二重化**する（検知が困難で非常に危険）

この問題は Service のユニットテストでは検出できず、**実際の DB を使う統合テスト・実機テストでしか発覚しない**（2026-06-19 横断根治キャンペーンで約45箇所を修正）。

#### ルール：managed entity に直接ミューテートして save する

既存エンティティの更新は、`findById` で取得した **managed entity に対してドメインメソッドでその場でフィールドをミューテートし、そのまま `save(entity)`** で行うこと。

```java
// OK: managed entity を直接ミューテートして save
MyEntity entity = myRepository.findById(id)
    .orElseThrow(() -> new NotFoundException("リソースが見つかりません"));

entity.applyUpdate(request);  // ← ドメインメソッドでミューテート
myRepository.save(entity);    // → UPDATE が発行される（id が保持されているため）
```

Entity 側にドメインメソッドを定義する:

```java
// Entity 側のドメインメソッド
public void applyUpdate(UpdateMyRequest request) {
    if (request.name() != null) {
        this.name = request.name();
    }
    if (request.description() != null) {
        this.description = request.description();
    }
    // ... 他のフィールドも同様に
}
```

#### `toBuilder()` / `builder()` が許可される用途

**限定例外（CMP-260820-1017）**: `ResidentRegistryEntity` は、新規登録の安全な16入力だけを受け取る private constructor に `@Builder` を付ける。公開 builder に死亡・転出・論理削除・監査・派生状態や継承 ID／日時を含めず、`toBuilder()` は提供しない。初期状態は Entity 内で定め、更新は既存の業務メソッドで managed entity に直接行う。JPA 用の protected 無引数 constructor は維持する。

以下の用途では `id` 消失の問題が発生しないため、引き続き使用してよい。

| 用途 | 説明 |
|---|---|
| **新規エンティティの作成** | `Entity.builder()...build()` による新規行作成（`id` は自動生成されるため問題なし） |
| **レスポンス DTO の組み立て** | DTO は `BaseEntity` を継承しないため問題なし |
| **`@Id` を自前宣言しているエンティティ** | 親クラスから `id` を継承していないため `toBuilder()` でもコピーされる |
| **TestFixture での複製** | 新規行を作ることが目的の文脈（テストデータ生成）は許可 |

#### 移行時の注意事項

`toBuilder().build() → save` を managed entity の直接ミューテートへ書き換える際、以下の点に注意すること。

**① null ガードのセマンティクスを正確に再現する**

旧コードで「リクエストが `null` のフィールドは既存値を維持する」という部分更新セマンティクスがある場合、`applyUpdate` メソッド内または呼び出し側で同じ null ガードを実装すること。

```java
// 旧コード（NG）の null ガードを applyUpdate に移植する例
public void applyUpdate(UpdateMyRequest request) {
    if (request.name() != null) {          // null ガードを applyUpdate 内で再現
        this.name = request.name();
    }
}
```

**② 旧値の先行キャプチャ（副作用がある場合）**

監査ログ・通知・状態遷移など、**旧値と新値を比較して副作用を起こす処理**がある場合は、ミューテートで旧値が上書きされる前に、必ずローカル変数に旧値を退避してから比較・記録すること。

```java
// NG: ミューテート後に entity.getOldField() を参照すると常に新値になる
entity.applyUpdate(request);
if (entity.getName().equals(request.name())) { ... }  // 常に true になる

// OK: 先に旧値を退避してからミューテート
String oldName = entity.getName();   // ← 先に旧値をキャプチャ
entity.applyUpdate(request);
if (!oldName.equals(entity.getName())) {
    auditLog.record("名前変更: {} → {}", oldName, entity.getName());
}
```

#### 背景・参考

2026-06-19 の横断根治キャンペーンで約45箇所の `toBuilder().build() → save` を修正した。
手本 PR: #1643（`EventService.updateEvent` + `EventEntity.applyUpdate` の直接ミューテートへの書き換え）、他 #1648 / #1651 / #1656 / #1667 等。

継承する Entity は `@SuperBuilder(toBuilder = true)` を標準とすることで、継承フィールド（`id`）も
`toBuilder()` でコピーされ、本問題は**構造的に回避**される。これにより `id=null` 起因の意図しない INSERT は発生しない。
ただし「managed entity を直接ミューテートして save する」上記ルールは、監査ログの旧値キャプチャや
更新意図の明確化の観点から引き続き推奨する（`@SuperBuilder` 化は安全網であって、更新フローの正準ではない）。
非継承の単純 DTO は `@Builder`（`@SuperBuilder` ではない）でよい。

### Entity フィールドの @Column(name) 必須ルール

#### 数字を挟む略語フィールドは @Column(name=...) を必ず明示すること

Hibernate の物理命名戦略（`CamelCaseToUnderscoresNamingStrategy`）は
**小文字→大文字の境界**でのみアンダースコアを挿入する。数字→大文字の境界では挿入しない。

そのため `S3Key` / `S3Url` / `Pdf2` 等のように数字と大文字が隣接するフィールド名では、
Hibernate が生成する列名と Flyway の DDL で定義した列名が食い違う。

| フィールド名 | Hibernate 生成（誤）| DDL 実列名（正）|
|---|---|---|
| `scannedDocumentS3Key` | `scanned_documents3key` | `scanned_document_s3_key` |
| `guardianCertificateS3Key` | `guardian_certificates3key` | `guardian_certificate_s3_key` |
| `photoS3Key` | `photos3key` | `photo_s3_key` |
| `imageS3Key` | `images3key` | `image_s3_key` |
| `coverImageS3Key` | `cover_images3key` | `cover_image_s3_key` |
| `certificateS3Key` | `certificates3key` | `certificate_s3_key` |

**根治事例**: 2026-06-06 実機テストで `ProxyInputConsentEntity` の S3Key フィールド列名不一致が
`ProxyInputContextFilter` 経由の全リクエストで SQLSyntaxErrorException を引き起こす致命的バグとして発覚。
`ddl-auto=create` を使う UT/IT では Hibernate が誤った列名でテーブルを作成してしまうため
自己整合で通過してしまい検出不能だった（CI が通っても実 Flyway スキーマで壊れるパターン）。

**ルール**:

```java
// NG: name 未指定（Hibernate が誤った列名を生成する）
@Column(length = 512)
private String scannedDocumentS3Key;

// OK: DDL の実列名に合わせて明示する
@Column(name = "scanned_document_s3_key", length = 512)
private String scannedDocumentS3Key;
```

**影響を受けるパターン（これらを含むフィールド名は必ず `name=` を明示）**:
- `S3Key` / `S3Url` / `S3Bucket` — S3 関連
- `PdfUrl` / `PdfKey` / `Pdf2` — PDF 関連
- `HtmlUrl` / `HtmlContent` — HTML 関連（H が大文字で前が数字の場合）
- その他「数字→大文字」が連続する略語を含むフィールド全般

**再発防止テスト**: `ddl-auto=create` のみのテスト（通常の `AbstractMySqlIntegrationTest` 派生クラス）では検出不能。
S3Key 系フィールドを持つ Entity には、専用の Flyway 実スキーマテストクラスを作成すること。

**機械的な番人（2026-08-20 / Issue #2856 で追加）**: `backend/src/test/java/com/mannschaft/app/common/architecture/EntityDigitBoundaryColumnNameGuardTest.java` が全 `@Entity` を反射で走査し、フィールド名に「数字→大文字」の並びを含むのに `@Column(name=...)` が未指定なものを機械的に落とす（Docker 不要・第一防衛線）。実 Flyway スキーマに対する経験的な再現は`backend/src/test/java/com/mannschaft/app/common/schema/EntityDigitBoundaryColumnFlywaySchemaIT.java`が担う（Issue #2856 では `data_exports.s3_key` ほか 7 Entity の不一致をこの 2 本で赤→緑にした）。

実装パターン（`@SpringBootTest` プロパティ上書き + ネイティブ SQL 列名直接確認）:

```java
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"   // Flyway 適用済みスキーマをそのまま使う
})
@Testcontainers
@Transactional
@EnabledIf("com.example.XxxS3KeyFlywaySchemaTest#isDockerAvailable")
@DisplayName("Xxx S3Key 列名マッピング再発防止テスト（Flyway 実スキーマ）")
class XxxS3KeyFlywaySchemaTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_s3key_flyway")
            .withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))   // WSL2 VHD 遅延対策（定石）
            .withCommand("--log_bin_trust_function_creators=1");  // TRIGGER 作成権限

    static { if (isDockerAvailable()) MYSQL.start(); }

    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;  // 外部依存モック化

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() { ... }

    @PersistenceContext EntityManager em;

    @Test
    void s3KeyColumnsMatchFlywaySchema() {
        // 1. S3 キーを指定して永続化・flush
        em.persist(entity); em.flush(); em.clear();

        // 2. JPA 往復確認
        XxxEntity loaded = em.find(XxxEntity.class, entity.getId());
        assertThat(loaded.getSomeS3Key()).isEqualTo(expectedKey);

        // 3. ネイティブ SQL で Flyway DDL 定義の実列名を直接指定して確認
        //    → JPA 側と DB 側の「両方が誤列名」という見逃しパターンを排除
        List<Object[]> rows = em.createNativeQuery(
                "SELECT some_s3_key FROM xxx_table WHERE id = :id")
                .setParameter("id", entity.getId()).getResultList();
        assertThat(rows.get(0)[0]).isEqualTo(expectedKey);
    }
}
```

**注意**: `@SpringBootTest(properties=...)` でプロパティを上書きすると、`AbstractMySqlIntegrationTest` とは
別の ApplicationContext が生成される（OOM 回避のため、このパターンのクラスを不必要に増やさないこと）。
参照実装: `ProxyInputConsentS3KeyFlywaySchemaTest`。

### コネクションプール (HikariCP)
* **使用ライブラリ**: Spring Boot 標準の **HikariCP** をそのまま使用する。別のプールライブラリへの変更は禁止する。
* **設定指針**:
    * `maximum-pool-size`: デフォルト `10`。本番環境ではDB接続数の上限とアプリケーションインスタンス数を考慮して調整する（目安: `DB max_connections / インスタンス数 - 余裕`）。
    * `minimum-idle`: `maximum-pool-size` と同値に設定し、コネクションの急増減を防ぐ（HikariCP 推奨設定）。
    * `connection-timeout`: `30000`ms（30秒）。これを超えた場合は `SQLException` がスローされる。
    * `leak-detection-threshold`: 開発環境では `2000`ms に設定し、コネクションリークを早期検出する。本番環境では `0`（無効）でよい。
* **環境別設定**: プール設定は `application-{profile}.yml` でプロファイル別に管理する。

### 開発環境ツール
* **Spring Boot DevTools**:
    * 開発時の `spring-boot-devtools` 依存を追加し、コード変更時の自動リスタートとライブリロードを有効化する。
    * `build.gradle.kts` では `developmentOnly` スコープで追加し、本番ビルドに含めないこと。
    * テンプレートファイルやプロパティ変更時のキャッシュ無効化も自動で行われる。
* **Spring Boot Docker Compose Support**（Spring Boot 3.1+）:
    * プロジェクトルートに `compose.yml`（MySQL + Valkey）を配置し、`gradle bootRun` 実行時にコンテナを自動起動・自動停止させる。
    * `spring-boot-docker-compose` 依存を追加し、手動での `docker compose up/down` を不要にする。
    * CI 環境では Docker Compose Support を無効化し、Testcontainers を使用すること（`spring.docker.compose.enabled=false`）。

### APIドキュメント (Springdoc OpenAPI) 設定規約
* **グループ化**:
    * `Public API`（`/api/v1/...`）、`Admin API`（`/api/v1/admin/...`）、`System Admin API`（`/api/v1/system-admin/...`）、`Internal API`（`/internal/...`）の4区分でグループを定義する。
* **認証スキーマ**:
    * JWT Bearer 認証を `SecurityScheme` として定義し、Swagger UI 上で全エンドポイントに対してトークンを送信できる構成にする。
* **レスポンス記述**:
    * 各 API には、正常系（200/201）だけでなく、想定される異常系（400, 401, 403, 404）のレスポンス例を明記する。
* **自動同期**:
    * この OpenAPI 仕様を「唯一の正解（Single Source of Truth）」とし、フロントエンドの Zod スキーマ生成のソースとして活用する（FRONTEND_CODING_CONVENTION.md §7「フロント・バック間のバリデーション同期」参照）。
