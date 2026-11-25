<!-- `backend/.claudecode.md` §18 の本文（逐語移設）。節番号・見出しは元のまま。 -->
[← backend/.claudecode.md（索引）に戻る](../../../backend/.claudecode.md)

## 18. Flyway マイグレーション SQL スタイル規約

> **migration を追加・変更したら E-R 図を再生成してコミットする**（`cd backend && ./gradlew generateErDiagrams`。CI の ER Diagram Drift Check が検出）。仕組みは §10.1、既存テーブルの把握は [`docs/architecture/er/README.md`](../../architecture/er/README.md) を見る。

### ファイル命名規則（全体最大 major + 1 × タイムスタンプ方式）

ファイル命名は `V{major}.{yyyyMMddHHmmss}__{説明}.sql`。

**採番ルール（正典）**:

1. **major は「origin/main 全体の最大 major + 1」を使う**（V82, V83, V84 … と実践済み）。
   - 新規ファイルがリポジトリ内のすべての既存ファイルの後にソートされることが保証され、
     `out-of-order: true` への依存をゼロにできる。
   - PR 作成前に `origin/main` の最大 major を確認し、他 PR と被る場合はリネームする。
2. **同一 major 内の並行 PR 衝突は minor（タイムスタンプ）で回避する**。
   - 同じ major を持つ 2 ファイルが同時に作られても、起動時刻が異なれば `yyyyMMddHHmmss` は衝突しない。

**形式と例**:
```
V84.20260526143022__add_user_profile.sql
```
（V84 がそのとき origin/main の最大 major であった場合）

**タイムスタンプの取得**:
```bash
# bash / WSL2
date -u '+%Y%m%d%H%M%S'

# PowerShell (Windows)
Get-Date -Format 'yyyyMMddHHmmss'
```

**⚠ よくある誤り（Flyway ソート順の罠）**:
Flyway は `V{major}.{minor}` の major と minor をそれぞれ**数値として比較**する。
`V9.20260603120000` の major は `9` であり、`V10`〜`V84` のすべてより**前**にソートされる。
「タイムスタンプ minor は連番 minor より大きいから後に実行される」という理解は
**同一 major 内でのみ正しい**。異なる major をまたぐ場合は major の大小が優先される。
→ 上記採番ルール（全体最大 major + 1）を守れば、この問題は発生しない。

**既存ファイルについて**: 既存の `V{phase}.{連番}__*.sql` ファイルは変更しない。
`out-of-order: true` は既存環境互換のため現状維持だが、上記採番を守れば新規ファイルは
out-of-order に依存しない（常に末尾に追加されるため）。

**番人テスト**: `FlywayFromScratchMigrationTest` がゼロ状態からすべての migration を順に適用し、
ソート順逆転やファイル破損を検知する。新規 migration 追加後は必ず CI で通過を確認すること。

**命名の機械的強制（2026-07-07〜）**: V137〜V143 系列で連番 minor（`.001` 等）が規約を逸脱して
積み上がり、並行 PR が同時に `V141.001` を名乗って main が起動不能になる事故（2026-07-07）が発生した。
これを受け、番人テスト `FlywayTimestampNamingGuardTest`（`common/architecture`）が、
凍結リスト（`FlywayLegacyMigrationBaseline` にハードコードされた既存 migration 全件）**外**の
新規ファイルについて連番 minor をビルドで機械的に拒否し（`V{major}.{yyyyMMddHHmmss}` 形式必須）、
あわせて全ファイルのバージョン重複（V141.001 二重と同型）も検知して fail させる。
既存ファイルはリネームせず凍結免除とする（凍結リストへの追記は禁止）。

SQL の書き方を以下に統一する。

### 基本スタイル
- **キーワードは大文字**: `CREATE TABLE`, `ALTER TABLE`, `NOT NULL`, `DEFAULT`, `FOREIGN KEY` 等
- **テーブル名・カラム名は小文字 snake_case**（§10 DB命名規約と統一）
- **1ファイル = 1つの論理的変更**: テーブル1つの作成、または関連する ALTER のまとめ

### コメント
- テーブル作成時は冒頭に作成意図のコメントを記載する:
```sql
-- 掲示板のスレッドを管理するテーブル
CREATE TABLE bulletin_threads (
    ...
);
```
- DELETE / DROP / ALTER TABLE ... DROP COLUMN を含む破壊的マイグレーションはファイル冒頭に警告を記載する:
```sql
-- ⚠ 破壊的変更: teams.organization_id を削除し team_org_memberships へ移行
ALTER TABLE teams DROP COLUMN organization_id;
```

### 制約・インデックスの命名
- **外部キー制約**: `fk_{テーブル名}_{参照先テーブル名}` 形式で明示的に名前を付ける:
```sql
CONSTRAINT fk_bulletin_threads_team
    FOREIGN KEY (team_id) REFERENCES teams(id)
```
- **インデックス**: `idx_{テーブル名}_{カラム名}` 形式:
```sql
CREATE INDEX idx_bulletin_threads_team_id_created_at
    ON bulletin_threads(team_id, created_at);
```
- **ユニーク制約**: `uq_{テーブル名}_{カラム名}` 形式

### 禁止事項
- `IF NOT EXISTS` は付けない（Flyway がバージョン管理するため二重実行は起きない。付けるとミスの検知が遅れる）
- `IF EXISTS`（DROP 時）も同様に禁止。存在しないオブジェクトの DROP はマイグレーション設計のミスであり、明示的にエラーにすべき

### Entity に列を足したら migration も必ず足す（番人あり）

テスト環境は `spring.jpa.hibernate.ddl-auto=create` + `flyway.enabled=false` で
**Entity からスキーマを生成する**ため、「Entity にフィールドを足したが migration を書き忘れた」乖離は
通常のテストでは原理的に検出できない。Flyway で構築した環境（本番・staging・新規開発環境）だけが
`Unknown column` で当該ドメインの API 全滅という形で壊れる
（実例: `queue_tickets.guest_phone` が 4 ヶ月間放置された）。

これを機械的に防ぐため、`FlywayFromScratchMigrationTest` に
**「全 Entity のマッピング列が Flyway 実スキーマに存在すること」の番人テスト**を置いている。
Hibernate 自身が構築した `Metadata`（Spring Boot と同じ命名戦略を適用した権威ある物理名）と、
Flyway 適用後の JDBC メタデータを突き合わせる。

- Flyway 実スキーマを要する番人テストは**このクラスに相乗りさせる**こと。
  新クラスを作ると MySQL コンテナが 1 個増え、CI の壁時計を直撃する。
- `KNOWN_UNPAID_DRIFT`（既知の未返済ドリフト台帳）は**空であること**。2026-09-29 に
  CMP-260924-0010 で全額返済した（凍結時点 38 列）。番人が赤くなったら台帳へ追記して黙らせず、
  **増やさず migration（`ALTER TABLE ... ADD COLUMN`）で返済する**こと。
  既存データのある環境向けの返済 migration は、冪等（列の存在を確かめてから足す）にし、
  既存行の埋め戻しを伴う場合は新しく足した列だけを対象にする（手本: V230 の 3 本と
  `UnpaidDriftRepaymentFixture`）。
- 見落としやすい罠として、**`BaseEntity` 由来の `created_at` / `updated_at` を
  `CREATE TABLE` が作っていない**ケースが 16 件あった（V230 で返済済み）。
  新規テーブルでは必ず両方を作ること。
- 命名戦略の罠に注意: Spring の `CamelCaseToUnderscoresNamingStrategy` は
  「小文字の直後の大文字」でのみ `_` を挿入する。`s3Key` → `s3key`、`positionX` → `positionx` となり
  `s3_key` / `position_x` にはならない。この形のフィールドは `@Column(name = "...")` を明示すること。

### migration の DML で「今」を書くときは `UTC_TIMESTAMP()` を使う（番人あり）**【必須】**

DB 格納基準は UTC 壁時計である（`TimeZoneStorageBasisGuardTest`）。migration の DML（INSERT / UPDATE /
DELETE の WHERE 比較）は JPA を経由しないため、そこに書く「今」を返す組み込み関数は
**DB セッションの `time_zone` 設定にそのまま従う**。対象は日時型だけでなく**日付型・時刻型の別名も同じ**:

| 区分 | ❌ 禁止（セッション TZ 依存） | ✅ 正解 |
|---|---|---|
| 日時 | `NOW()` / `SYSDATE()` / `CURRENT_TIMESTAMP` / `LOCALTIMESTAMP` | `UTC_TIMESTAMP()` |
| 日付 | `CURDATE()` / `CURRENT_DATE` | `UTC_DATE()` |
| 時刻 | `CURTIME()` / `CURRENT_TIME` / `LOCALTIME` | `UTC_TIME()` |

したがって新規 migration では必ず右列を使うこと（生 SQL 側の規約 §4.1 と同じ流儀。
詳細: [`docs/architecture/datetime_policy_utc_instant_vs_wallclock.md`](../../architecture/datetime_policy_utc_instant_vs_wallclock.md) 4.2節）。

```sql
-- ❌ 禁止（セッション TZ 依存）
UPDATE teams SET updated_at = NOW() WHERE id = 1;
-- ✅ 正解（設定に依らず UTC 壁時計）
UPDATE teams SET updated_at = UTC_TIMESTAMP() WHERE id = 1;
```

- **番人**: `FlywayMigrationTimeFunctionGuardTest`（`common/architecture`）が migration ファイル単位の
  件数で凍結し、新規追加を機械的に拒否する。凍結台帳
  （`src/test/resources/flyway_migration_time_guard/session_tz_time_function_freeze.txt`）**への追記は禁止**。
- **列 DEFAULT / ON UPDATE の `CURRENT_TIMESTAMP` は対象外**。DDL の保険であり、式 DEFAULT へ倒すには
  適用済み migration の書き換え（Flyway チェックサム不一致）が要るため。代わりに
  `FlywayMigrationSessionTimeZoneUtcIT` が「接続セッションの TZ が UTC である」ことを実測で守る。
- 既存 migration（65 ファイル・746 箇所）は**書き換えない**。適用済みファイルの編集は
  チェックサム不一致で全環境の起動を止める。実測でずれは 0 秒であり是正の必要も無い（CMP-260912-2258）。

---
