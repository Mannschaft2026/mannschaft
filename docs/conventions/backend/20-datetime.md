<!-- `backend/.claudecode.md` §20 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 20. 日時の扱い統一

### DB（MySQL 8.0）
- 日時カラムは **`DATETIME`** を使用する。`TIMESTAMP` は使わない（2038年問題 + MySQL の暗黙タイムゾーン変換を避けるため）
- **DB に格納する値は UTC 壁時計で統一する（2026-07-29 / Issue #2486 で JST から変更）**
- 日付のみのカラムは `DATE` 型を使用する

#### 格納基準 UTC の二層モデル（重要・混同しやすい）

「アプリ層の値」と「DB の格納値」は<b>別のタイムゾーンの壁時計</b>である。両者を混同しないこと。

| 層 | 壁時計 | 決めているもの |
|---|---|---|
| **アプリ層**（`LocalDateTime`）| **JST**（Asia/Tokyo）| JVM 既定 TZ。`ServerTimeZoneEnvironmentPostProcessor` が Bean 生成前に `SERVER_ZONE`（`Asia/Tokyo`）へ確定 |
| **DB 格納値**（`DATETIME` カラム）| **UTC** | `spring.jpa.properties.hibernate.jdbc.time_zone: UTC`（`application.yml`）|

- Hibernate はこの設定があるとき `setTimestamp(i, ts, Calendar.getInstance(UTC))` で書く。
  `LocalDateTime`（JST 壁時計）は JVM 既定 TZ で瞬間に解決され、その瞬間が UTC 壁時計として格納される。
  **Hibernate 経由の読み書きは往復無損失**なので、アプリ側のコードは今までどおり `LocalDateTime` を扱えばよい。
- **API / フロントエンドの契約は変わらない**。レスポンスの日時は従来どおり JST 前提の ISO 8601 文字列である
  （`LocalDateTimeTimezoneSerializer` が JVM 既定 TZ ＝ JST のオフセットを付けて直列化する）。
- **注意が要るのは「Hibernate を通さず DB を直接触る経路」だけ**。以下は必ず UTC 基準で書くこと:
    - ネイティブクエリの `NOW()` / `CURRENT_TIMESTAMP` / `CURDATE()`（MySQL のセッション TZ に従う）
    - `CONVERT_TZ()` の変換元（`mannschaft.audit.stored-zone-offset` の既定 `+00:00` がこれに当たる）
    - テストや seed スクリプトが文字列リテラルで `INSERT` する日時

#### API 境界の TZ 変換（出力と入力は必ず対で考える）

上の二層モデルは「アプリ層 ⇄ DB」の話である。これとは別に **「アプリ層 ⇄ API のクライアント」** の層でも
TZ 変換が起きる。ボディ（JSON）は `@Primary ObjectMapper`（`JacksonConfig`）が、
クエリパラメータ・パス変数は Spring の `ConversionService`（`WebMvcConfig#addFormatters`）が担う。

| 方向 | 担当 | 変換 |
|---|---|---|
| **出力（ボディ）** | `LocalDateTimeTimezoneSerializer` | 保持値を JVM 既定 TZ（＝ JST 壁時計）と解釈し、`TimezoneContextHolder` のユーザー TZ へ変換してオフセット付きで出力 |
| **入力（ボディ）** | `LocalDateTimeTimezoneDeserializer` | 下表の規則で解釈し、**アプリ層の保持形式（JST 壁時計）** へ変換 |
| **入力（クエリ／パス変数）** | `UserZoneLocalDateTimeFormatter` | 同じ下表の規則で解釈（Issue #2508 Phase 1）|

**解釈規則の実体は `UserZoneLocalDateTimeParser` 1 箇所**にあり、上記の入力 2 経路はいずれもこれに委譲する。
ボディとクエリで解釈がずれる（＝片方だけ直る／片方だけ壊れる）事故を構造的に防ぐため、
**新たに日時文字列を解釈するコードを書くときは必ずこのパーサを使うこと**（規則のコピペ実装は禁止）。

`@RequestParam LocalDateTime` は Jackson を通らないため、標準変換のままだと **オフセットを黙って捨てる**。
`WebMvcConfig#addFormatters` で `addFormatterForFieldType(LocalDateTime.class, …)` を 1 本登録することで
`@DateTimeFormat(iso = …)` / `@DateTimeFormat(pattern = …)` / アノテーション無し の**全種を掌握**している
（`addFormatterForFieldAnnotation` は fieldType 側の登録に負けて呼ばれないので使わない）。
クエリ側の解釈不能入力は `ParseException` → `MethodArgumentTypeMismatchException` → **400**（§3.5 の 4 番）。

**受信側の解釈規則（Issue #2508）:**

| 入力 | 解釈 |
|---|---|
| オフセット付き（`+09:00` / `Z` / `-04:00`）| そのまま瞬間として解釈し、Asia/Tokyo の壁時計へ変換して保持（クライアントが瞬間を明示しているので最も信頼できる）|
| オフセット無し ＋ ユーザー TZ が**明示的に解決済み**（`TimezoneContextHolder.isResolved()` が true）| その TZ の壁時計として解釈 → Asia/Tokyo へ変換 |
| オフセット無し ＋ **未解決**（未認証・バッチスレッド・キャッシュ Bean 不在）| **Asia/Tokyo の壁時計**として解釈（＝恒等変換）|
| 解釈不能（配列形式・数値・非 ISO 文字列）| `InvalidFormatException` → **400**。握り潰しも無音フォールバックもしない |

**未解決を Asia/Tokyo として扱う理由（最重要）:**

`TimezoneContextHolder.get()` は**未セット時に UTC を返す**し、`UserTimezoneFilter` も未認証時に UTC を
明示セットする。つまり `get()` の戻り値だけでは **「`users.timezone` から解決された値」と
「未認証・バッチの既定 UTC」を区別できない**。

この区別を入れずに `get()` を無条件に信じると、**フィルターを通らないバッチスレッド
（`get()` は UTC）のオフセット無し入力が UTC 壁時計として解釈され、−9 時間ずれた値が保持される**。
`@Primary ObjectMapper` は HTTP の `@RequestBody` だけでなく **`payload_json` の読み戻し**にも
使われるため（`ScheduleScheduledTaskBatchService` 等）、既定値がそのままデータ破壊に化ける。

そのため `TimezoneContextHolder` は「セット済み」と「**解決済み**」を区別する:

- `setResolved(zone)` — `users.timezone` を実際に引き当てられたときだけ使う。`isResolved()` が true になる
- `set(zone)` — ユーザー由来でない既定値を積む。`isResolved()` は false のまま
- `get()` の意味は従来どおり（未セット＝UTC）。**シリアライザは無改修**で影響半径を絞っている

`users.timezone` は `NOT NULL DEFAULT 'Asia/Tokyo'` なので、**国内ユーザーは解決済み・未解決の
いずれの経路でも恒等変換**となり既存の挙動は変わらない。

> **シリアライザだけを足すな**（番人テスト `JacksonTimeTypeSymmetryGuardTest` が機械的に拒否）。
> java.time 型の出力をカスタムしたら、対になるデシリアライザも同じ `SimpleModule` に
> `addDeserializer` で登録すること。片方だけだと「自分の出力を読み戻せない」「ユーザー TZ を
> 無視して値がずれる」という静かなデータ破壊になる。ズレ幅は（ユーザー TZ のオフセット）−（+09:00）で、
> **日本のユーザーだけ偶然 0 になるため発覚が遅れる**。
> なお `LocalDateTimeTimezoneSerializer` は `ZoneId.systemDefault()`、デシリアライザは定数
> `SERVER_ZONE` を見る**非対称な参照**なので、`SERVER_ZONE` を変えるなら両方同時に変えること
> （この一致も同番人が固定している）。

#### 環境側で揃えるべき設定（ズレると 9 時間の静かなバグになる）

MySQL のセッション TZ と JDBC の `serverTimezone` も UTC に揃える。片方でも JST に残ると、
生 SQL の結果だけが 9 時間ずれて「特定環境でしか再現しない」不具合になる。

| 対象 | 設定 |
|---|---|
| ローカル（`docker-compose.yml`）| `--default-time-zone=+00:00` |
| ローカル（`application-local.yml`）| `serverTimezone=UTC` |
| CI（`application-ci.yml`）| `serverTimezone=UTC` |
| 本番（`infra/terraform`）| RDS パラメータ `time_zone=UTC` ＋ JDBC `serverTimezone=UTC` |

これらは番人テスト `TimeZoneStorageBasisGuardTest`（`com.mannschaft.app.config`）が機械的に固定している。

#### なぜ JST から UTC へ変えたか（2026-07-29）

旧規約は「国内向けサービスなので JST 格納」としていたが、`hibernate.jdbc.time_zone: UTC` の指定が
test プロファイルにしか存在せず、実態は **test=UTC / local・ci=JST / prod=UTC**（terraform は当初から
`serverTimezone=UTC` ＋ RDS `time_zone=UTC`）と<b>三者三様に割れていた</b>。同じコードが環境ごとに違う
壁時計を書く以上、生 SQL を使う機能はどこかの環境で必ず壊れる（実際 CI では格納 JST × `NOW()` UTC で
9 時間ずれていた）。多数派かつ将来の正である UTC に倒し、共有 `application.yml` で一元宣言した。

格納基準の変更は本来「既存全行の意味が変わる」極めて侵襲的な操作だが、**本番環境が未構築で再解釈すべき
既存データがゼロ**だったため、変換マイグレーションなしで実施できた。

> **既存のローカル DB について**: `mysql-data` ボリュームには JST 壁時計で書かれた行が残る。
> 以降の行は UTC になるため新旧が混在する。気になる場合は `docker compose down -v` で作り直すこと。

### Java（Spring Boot）
- 裁可済みの[日時方針](../../architecture/datetime_policy_utc_instant_vs_wallclock.md)に従い、新規の「起きた瞬間」は **`Instant` / `OffsetDateTime`** を使い、UTCで保持する。
- 土地の約束（壁時計）は **`LocalDate` / `LocalTime` ＋店・チームのTZ** で保持し、壁時計そのものをUTC化しない。
- 既存の `LocalDateTime` は日時方針の段階移行に従う。新規コードで瞬間と壁時計を混同する型として追加せず、既存フィールドの一括変更は行わない。
- JVM のデフォルトタイムゾーンは **ApplicationContext 生成前**に `UserZoneLocalDateTimeParser.SERVER_ZONE`（`Asia/Tokyo`）へ設定する。`@PostConstruct` など Bean 生成中の切替は禁止（DataSource の最初の接続が先に作られ DATE 列が前日で保存される。CMP-261123-1335）:
```java
// com.mannschaft.app.config.ServerTimeZoneEnvironmentPostProcessor（META-INF/spring.factories に登録）
public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    TimeZone.setDefault(TimeZone.getTimeZone(UserZoneLocalDateTimeParser.SERVER_ZONE));
}
```
  ⚠️ **`-Duser.timezone` は Spring コンテキストを起動するテストでは効かない**: EnvironmentPostProcessor が JVM 既定 TZ を無条件に `SERVER_ZONE` へ上書きするため、`-Ptest.timezone=America/Los_Angeles` や `UTC` でテスト JVM を起動しても、`@SpringBootTest` / `SpringApplication` を使うテストは黙って JST で動く。Non-JST ジョブ（`backend-ci.yml`）へ Spring コンテキストを使うテストを足しても非 JST の検証にならない。ここへ足すのは Spring を使わない単体テストか、`ServerTimeZoneBootstrapTest` のように意図して UTC を再現するテストに限ること。
  `TimeZoneConfig` の番人が、全 DataSource（自動構成・replica・archive）の生成時点で既定 TZ が `SERVER_ZONE` と食い違えば起動を失敗させる。正本は `SERVER_ZONE` の1つで Dockerfile・Terraform・`TZ` 環境変数には併記しない。
- ⚠️ **JVM 既定 TZ が JST であることと、DB 格納基準が UTC であることは矛盾しない**（上の「二層モデル」参照）。
  JVM の JST 固定は `LocalDateTime.now()` が JST 壁時計を返すこと・API 直列化のオフセットが `+09:00` になることを
  保証するためのものであり、DB へ書く際の基準は `hibernate.jdbc.time_zone: UTC` が別途決めている。
  この JST 固定を外すと API の日時表現まで変わるため、格納基準の話とは独立に扱うこと。
- **Jackson の日時直列化**: `LocalDateTime` がデフォルトで配列（`[2026,3,18,21,0,0]`）として出力されるのを防ぐため、以下を `application.yml` に設定する:
```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false  # ISO 8601 文字列で出力
```
- または `JavaTimeModule` を明示的に登録する（Spring Boot は `jackson-datatype-jsr310` が依存にあれば自動登録するが、上記設定は必須）

### フロントエンド（TypeScript / Nuxt 3）
- API レスポンスの日時は **ISO 8601 文字列**で受け取る: `"2026-03-13T21:00:00"`（タイムゾーンオフセットなし = JST 前提）
- 表示時の日時フォーマットは **dayjs** で統一する（プロジェクト内で1つのライブラリに絞る）

---
