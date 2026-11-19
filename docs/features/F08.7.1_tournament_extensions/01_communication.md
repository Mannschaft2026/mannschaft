# F08.7.1 / 01: 連絡機能（掲示板・チャット連絡スペース）

> **ステータス**: 🟡 設計中（CMP-261118-0900 参加者導線を追記中）
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 大会・ディビジョン・参加者（連絡単位の源泉）
> - [F05.1_bulletin_board.md](../F05.1_bulletin_board.md) — 掲示板（`scope_type` 拡張先・カテゴリ/スレッド実体）
> - [F04.2_chat.md](../F04.2_chat.md) — チャット（`channel_type` / `source_type` 拡張先）
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — 認可方針・PUBLIC 露出方針

本書は確定要件 ①（連絡単位＝大会全体＋各ディビジョンの二段・掲示板＆チャット両方）・②（閲覧＝参加チーム全メンバー＋公開スペースは PUBLIC 可）・③（投稿＝主催組織 ADMIN＋各チーム代表/副代表）・④（スペースは作成時に自動付帯）を実装可能レベルに具体化する。

---

## 1. 概要

大会の参加チーム間で連絡を取り合うための**連絡スペース**を、(a) 大会全体（`TOURNAMENT`）と (b) 各ディビジョン（`TOURNAMENT_DIVISION`）の **二段**で、**掲示板**と**チャット**の両方として自動付帯する。

- **掲示板**（F05.1）= 非同期・お知らせ／連絡向け（日程変更・審判派遣・スポンサー募集など）。
- **チャット**（F04.2）= 同期・速報／雑談向け（既定で非公開）。
- スペースは大会／ディビジョン作成時に**自動生成**され、運営の手間ゼロで連絡を開始できる（ADHD 配慮：入力摩擦ゼロ）。
- 各スペースに**公開トグル**（`is_public`）を設け、主催者が「未ログイン者にも見せる広報スペース」にできる。

連絡単位は二段なので、1 大会あたり最大で `(1 + ディビジョン数) × 2(掲示板/チャット)` のスペースが生成される（例: 4 部リーグ ＝ (1+4)×2 = 10 スペース）。

---

## 2. データモデル

### 2.1 新規テーブル `tournament_contact_space`（tournament ドメイン）

「このスコープ（大会 or ディビジョン）の、この種別（掲示板 or チャット）のスペースが、どの bulletin/chat リソースに払い出されているか」と、その公開フラグを 1 テーブルで管理する。冪等化の逆引きキーも兼ねる。

- **主キーは UUIDv7**（原則 6・`UuidV7Entity` 継承）。
- **クロスドメイン FK は張らない**（原則 1）。`scope_id` / `ref_id` は ID 値のみ保持し、整合性はアプリ層で保証する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BINARY(16) | NO | （UUIDv7 自動生成） | PK |
| `scope_type` | VARCHAR(30) | NO | — | `TOURNAMENT` / `TOURNAMENT_DIVISION` |
| `scope_id` | BIGINT UNSIGNED | NO | — | `tournaments.id`（TOURNAMENT 時）または `tournament_divisions.id`（TOURNAMENT_DIVISION 時）。FK なし |
| `space_kind` | VARCHAR(20) | NO | — | `BULLETIN` / `CHAT` |
| `ref_id` | BIGINT UNSIGNED | NO | — | 払い出した実体の ID。BULLETIN なら `bulletin_categories.id`（または board id）、CHAT なら `chat_channels.id`。FK なし（クロスドメイン） |
| `is_public` | BOOLEAN | NO | FALSE | 公開トグル（TRUE で PUBLIC が閲覧可。要件②）。CHAT は既定 FALSE |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除（大会/ディビジョン削除時に archive。§6） |

**インデックス**

```sql
-- 冪等化（同一スコープ×種別で 1 つ）＋「このスコープのリソース id」逆引き
UNIQUE KEY uq_tcs_scope_kind (scope_type, scope_id, space_kind)
-- ref_id 逆引き（chat_channel id → どの大会スペースか）
INDEX idx_tcs_ref (space_kind, ref_id)
```

**備考**
- `UNIQUE(scope_type, scope_id, space_kind)` が「冪等化」「逆引き」「公開フラグ保持」の 3 役を 1 本で担う。払い出し競合時はこの UNIQUE で弾き、`DataIntegrityViolationException` を catch して再取得する（§3.4）。
- このテーブルは organization_id を持たない（大会は組織配下だが、スペース自体はテナント単位で爆発的に増えるものではなく、大会 ID から組織を辿れる）。`AbstractTenantAwareRepository` は適用しない。

### 2.2 bulletin `ScopeType` の拡張（F05.1）

bulletin の `scope_type` は **実 DDL では `VARCHAR(20)`**（`V5.001__create_bulletin_categories_table.sql:4` / `V5.002__create_bulletin_threads_table.sql:5`）、JPA は `com.mannschaft.app.bulletin.ScopeType`（`@Enumerated(EnumType.STRING)` ＋ `@Column(nullable=false, length=20)`、現値 `ORGANIZATION` / `TEAM` / `PERSONAL` / `VILLAGE`）。ここに enum 定数 **`TOURNAMENT` / `TOURNAMENT_DIVISION`** を追加する。

- `scope_id` には大会 ID / ディビジョン ID を直接格納する。
- **桁確認**: `TOURNAMENT`（10字）・`TOURNAMENT_DIVISION`（**19字**）はいずれも `VARCHAR(20)` に収まる。**MODIFY 不要**（F05.1 §3 の `scope_type` 記述と統一。実 DDL は VARCHAR・JPA は `EnumType.STRING`。F05.1 の ENUM 表記は誤りなので併せて是正対象）。
- DDL: VARCHAR ゆえ enum 定数追加（Java `ScopeType` への `TOURNAMENT` / `TOURNAMENT_DIVISION` 追加）のみで足り、列定義の `ALTER` は不要。**実装時に実スキーマを grep して 20 桁制約を超えないことを再確認**する。
- スコープ別スレッド一覧の主クエリ用 `(scope_type, scope_id)` 複合 index は **実 DDL に未存在**（`V5.002` には PRIMARY KEY / category FK / author FK / FULLTEXT のみ。後続移行にも追加なし）。**実装時に grep で確認し、無ければ Flyway 移行で追加**する（§3.4・README Y-2 と整合）。

**API 入口（グローバル方式）**: 大会連絡スペースの掲示板は、グローバル方式掲示板 API（`/api/v1/bulletin/threads` の一覧・作成、`/api/v1/bulletin/threads/read-all`、`/api/v1/bulletin/threads/{threadId}` 系）に `scope_type=TOURNAMENT` / `TOURNAMENT_DIVISION`、`scope_id=<大会ID／ディビジョンID の数値>` を指定して利用する（`scope_id` に slug は使えず 400）。認可は §4（`TournamentContactAccessService`：閲覧=canView・投稿=canPost）に委譲し、権限なしは閲覧 403 `TOUR_030`／投稿 403 `TOUR_031`。パス方式（`/api/v1/{scopeType}/{scopeId}/bulletin/...`）は大会スコープを受理しない（403）。権限なしを不在 ID と同じ 404 に畳む存在秘匿は別戦役で扱う。

### 2.3 chat `ChannelType` / `source_type` の拡張（F04.2）

chat の `chat_channels.channel_type` に **`TOURNAMENT_CHAT`（15字）/ `TOURNAMENT_DIVISION_CHAT`（24字）** を追加する。紐付けは既存の **`source_type` / `source_id` 方式**（EVENT_CHAT と同じ・カラム追加ゼロ）を踏襲する。

> **🔴 桁あふれ警告（検分1周目で発覚・実コード確認済み）**: `chat_channels.channel_type` は実 DDL で **`VARCHAR(20)`**（`V4.013__create_chat_channels_table.sql:3`）、Entity も `ChatChannelEntity.java:36-38` で `@Enumerated(EnumType.STRING) @Column(nullable=false, length=20)`。`TOURNAMENT_DIVISION_CHAT` は **24 字**で **20 桁に収まらない**（保存時に切り詰め／エラーになる）。**「VARCHAR ゆえ DDL 変更不要・カラム追加ゼロ」は誤り。**
>
> したがって実装時に **以下の 2 点が必須**:
> 1. Flyway 移行で桁拡張: `ALTER TABLE chat_channels MODIFY channel_type VARCHAR(30) NOT NULL;`
> 2. Entity 桁拡張: `ChatChannelEntity.channelType` の `@Column(length = 20)` を **`length = 30`** に変更。
>
> （`source_type` 側は本機能で `TOURNAMENT` / `TOURNAMENT_DIVISION`＝最長 19 字を入れる。`chat_channels.source_type` の桁も実装時に grep で確認し、20 桁未満なら問題ないが、20 桁ちょうど制約の場合は 19 字で収まるため MODIFY 不要。）

| 項目 | 値 |
|------|-----|
| `channel_type` | `TOURNAMENT_CHAT`（大会全体）/ `TOURNAMENT_DIVISION_CHAT`（ディビジョン） |
| `source_type` | `TOURNAMENT`（source_id = `tournaments.id`）/ `TOURNAMENT_DIVISION`（source_id = `tournament_divisions.id`） |
| `team_id` / `organization_id` | ともに NULL（大会スコープは特定チーム/組織に属さない横断スペース。CROSS_TEAM と同様の扱い） |
| `is_private` | 既定 TRUE（公開トグル ON 時のみ後述の read-only 公開を許可。§5） |
| `name` | `{大会名} 連絡` / `{大会名} {ディビジョン名} 連絡` |

- 既存 `UNIQUE KEY uq_chat_channels_source (source_type, source_id)` により、同一大会/ディビジョンからの重複チャンネル作成を防止できる（payload 追加ゼロ）。
- `source_data` 拡張（F04.2 既存方式）：`source_type='TOURNAMENT'` / `'TOURNAMENT_DIVISION'` の場合、`GET /chat/channels/{id}` レスポンスに大会サマリー（`tournament_id` / `tournament_name` / `division_name?` / `status`）を含める。チャット画面上部に大会カードを固定表示する。

---

## 3. 自動生成フック（スペースの自動付帯・要件④）

スペースは大会／ディビジョン作成時に **直接サービス呼び出し**で払い出す。tournament ドメインから chat / bulletin ドメインの Service を呼ぶため**ドメイン越境**となる。原則 5 に従い、各フックに越境 TODO コメントを明記する。

> ```java
> // TODO: tournament ドメインから chat/bulletin ドメインの Service を直接呼んでいる。
> //       将来は TournamentCreatedEvent / DivisionCreatedEvent によるイベント駆動化候補。
> ```

### 3.1 大会作成時（`TournamentService.createTournament`）

`tournament` を save した直後（同一トランザクション内）に以下を実行する:

1. 大会全体の **掲示板スペース**を払い出す（`scope_type=TOURNAMENT, space_kind=BULLETIN`）。`BulletinCategoryService.provisionForScope(TOURNAMENT, tournamentId)` を呼び、デフォルトカテゴリを生成（§3.2）。`tournament_contact_space` に `ref_id = 代表カテゴリ id`（または board id）を記録。
2. 大会全体の **チャットスペース**を払い出す。`TournamentChatChannelService.createForTournament(tournamentId)`（新設・F04.2 `EventChatChannelService` を範とする）で `channel_type=TOURNAMENT_CHAT, source_type=TOURNAMENT, source_id=tournamentId, is_private=TRUE` のチャンネルを作成。`tournament_contact_space` に `ref_id = chat_channel id` を記録。

### 3.2 ディビジョン作成時（`DivisionService.createDivision`）

`tournament_division` を save した直後に、そのディビジョンの掲示板＋チャットを払い出す（`scope_type=TOURNAMENT_DIVISION, scope_id=divisionId`）。手順は §3.1 と同型。

**bulletin デフォルトカテゴリの自動生成**（村実装 `VillageLobbyService` に倣う）:
- provisioning 時にデフォルトカテゴリを 2 件生成する: 「お知らせ」（`post_min_role=ADMIN` 相当の運営連絡）・「連絡」（参加チーム代表が投稿）。
- カテゴリ名の重複防止は F05.1 の方式（Service 層で `deleted_at IS NULL` 照合）に従う。

### 3.3 シーズン継続時（`TournamentService.continueTournament`）

シーズン継続で**ディビジョンが複製される**場合、複製先ディビジョンにも §3.2 と同じ払い出しを行う（**払い出し漏れ防止**）。継続元のスペースは旧シーズンの履歴として残す（新シーズンは新ディビジョン ID＝新スペース）。

> **テストで検証必須**: `continueTournament` 後に複製ディビジョン数 × 2（掲示板/チャット）のスペースが `tournament_contact_space` に存在することをアサートする（この漏れは過去類似機能で頻発した）。

### 3.4 冪等化・競合制御

- 払い出し前に `tournamentContactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(...)` で既存を確認し、あれば再利用する（リトライ・再実行で二重生成しない）。
- chat 側も既存 `findBySourceTypeAndSourceId` で既存チャンネルを確認する。
- 競合（同時実行）時は `UNIQUE(scope_type, scope_id, space_kind)` 違反 → `DataIntegrityViolationException` を catch → 再取得（`VillageLobbyService` 方式）。
- **source 複合 index の有無**: `chat_channels(source_type, source_id)` は F04.2 で `uq_chat_channels_source` として既存。`bulletin_threads(scope_type, scope_id)` 複合 index は **実 DDL に未存在**（`V5.002` 確認済・後続移行にも無し）。**実装時に grep で再確認し、無ければ Flyway 移行で追加**する（症状を隠さず根治）。

---

## 4. 認可（read / write 分離）

新規 `TournamentContactAccessService`（tournament ドメイン）を設け、村の二段認可（`VillageBulletinAccessService` の `checkView` / `checkModerator`）を範として read / write を分離する。掲示板・チャット双方のコントローラ／サービス入口で**必ず**通す（多層防御）。

クロスドメインの所属判定は `TeamMembershipRepository` / `AccessControlService` を **ID 参照のみ**で呼ぶ（原則 1）。存在しない／削除済みスペースは一律 **404**（IDOR 対策・存在を漏らさない）。

### 4.1 `canView(scopeType, scopeId, userId)`（要件②）

```
canView(scopeType, scopeId, userId):
    space = findActiveSpace(scopeType, scopeId, space_kind)
    space が存在しない / deleted_at IS NOT NULL → 404（IDOR 対策）

    space.is_public == true                                      → 許可（PUBLIC・未ログイン含む。read-only）
    userId が対象チーム(status IN (REGISTERED, ACTIVE)) のメンバー  → 許可
    主催組織 ADMIN（isAdmin(userId, orgId, "ORGANIZATION")）       → 許可
    SYSTEM_ADMIN                                                  → 許可
    else                                                         → 403
```

- **チーム解決の源泉＝`tournament_participants`**。`status IN (REGISTERED, ACTIVE)` を含め、`WITHDRAWN` / `DISQUALIFIED` を**除外**する（**大会開始前から連絡可能**にする決定。要件のユーザビリティ確保）。
- `TOURNAMENT`（大会全体）スコープは**全ディビジョンの participants を集約**して判定する。
- 主催組織は大会 ID から `tournaments.organization_id` を辿って解決する。

### 4.2 `canPost(scopeType, scopeId, userId)`（要件③）

```
canPost(scopeType, scopeId, userId):
    対象チームの ADMIN/DEPUTY_ADMIN
        （accessControlService.isAdminOrAbove(userId, teamId, "TEAM")）  → 許可
    主催組織 ADMIN（isAdmin(userId, orgId, "ORGANIZATION")）             → 許可
    SYSTEM_ADMIN                                                        → 許可
    else                                                               → 403
```

- 投稿できるのは **各チームの代表（ADMIN）・副代表（DEPUTY_ADMIN）** と **主催組織 ADMIN** のみ。一般 MEMBER・SUPPORTER・PUBLIC は閲覧のみ（権限昇格防止：MEMBER が代表になりすませない）。
- `is_public=true` のスペースでも、**PUBLIC は常に read-only**。公開で投稿権が広がることはない。

### 4.3 N+1 回避（大会全体スコープ）

`TOURNAMENT` スコープの `canView` / `canPost` は「大会の全参加チームのいずれかで当該ユーザーがメンバー/代表か」を判定するため、ディビジョン × チーム × メンバーで N+1 になりやすい。これを避けるため、`TournamentParticipantRepository` に**単発 exists クエリ**を新設する:

```java
// 大会のいずれかの参加チーム（REGISTERED/ACTIVE）に当該ユーザーが所属するか
boolean existsActiveMemberOfAnyParticipantTeam(Long tournamentId, Long userId);
// 大会のいずれかの参加チームで当該ユーザーが ADMIN/DEPUTY_ADMIN か
boolean existsTeamAdminOfAnyParticipantTeam(Long tournamentId, Long userId);
// ディビジョン単位の同等クエリ
boolean existsActiveMemberOfDivisionParticipantTeam(Long divisionId, Long userId);
boolean existsTeamAdminOfDivisionParticipantTeam(Long divisionId, Long userId);
```

JOIN は `tournament_participants × team_memberships`（status 絞り込み付き）の 1 クエリで完結させる。

### 4.4 `@Transactional` の境界

`TournamentContactAccessService` は読み取り専用（`@Transactional(readOnly=true)`）で、tournament ドメイン内に閉じる。所属判定で `TeamMembershipRepository` を呼ぶのはクロスドメインだが**ID 参照の読み取りのみ**であり、書き込みトランザクションを跨がない。

---

## 5. 公開トグル（`is_public`）

主催者（主催組織 ADMIN / SYSTEM_ADMIN）が各スペースの公開可否を切り替える。

### 5.1 API

```
PATCH /api/v1/tournaments/{tournamentId}/contact-spaces/{spaceId}/visibility
PATCH /api/v1/tournaments/{tournamentId}/divisions/{divisionId}/contact-spaces/{spaceId}/visibility
  body: { "is_public": true }
  認可: 主催組織 ADMIN / SYSTEM_ADMIN のみ（canPost より厳しい。チーム代表は公開設定できない）
  応答: 200 更新後のスペース / 403 / 404
```

### 5.2 公開時の挙動・セキュリティ

- **chat の公開は既定 OFF**（`is_private=TRUE`）。公開トグルを ON にしても **PUBLIC は read-only**（投稿は常に §4.2 の代表＋主催者のみ）。スペクテーター（観戦者・未ログイン）への露出範囲は閲覧のみに限定する。
- 掲示板の公開も同様に PUBLIC は閲覧のみ。
- 公開トグルの監査：`audit_logs` に `TOURNAMENT_CONTACT_SPACE_VISIBILITY_UPDATED` を記録（誰が・いつ・どのスペースを公開/非公開にしたか）。
- セキュリティ精査項目（docs/security 連携）：公開スペースに**非公開大会の機微情報（未確定の対戦相手・内部連絡）が露出しないか**を運営に明示。公開はあくまで「広報目的の連絡スペース」用途とし、内部連絡カテゴリ／チャンネルは非公開のまま保つ運用を推奨する。

---

## 6. 削除・退会の取り扱い

### 6.1 大会・ディビジョン削除（原則 2：クロスドメイン CASCADE なし）

- 大会／ディビジョン削除（論理削除）時、連絡スペースは**即時物理削除せず soft delete / archive で残す**（履歴保持）。`tournament_contact_space.deleted_at` をセットし、紐づく bulletin スレッド / chat チャンネルは `is_archived=TRUE` 相当でアーカイブする。
- `DivisionService.deleteDivision` が**物理 delete** を行う実装の場合は、削除前に**スペース archive フック**を追加する（孤児化を防ぐ。chat_channel / bulletin が宙に浮かないよう、archive 後に空 ref として残すか、tournament ドメイン側の `deleted_at` だけ立てる）。
- クロスドメイン CASCADE（`ON DELETE CASCADE` で chat_channels を巻き込む等）は**作らない**（原則 2）。連絡履歴は統計・証跡として価値があるため保持する。

### 6.2 ユーザー退会

- 投稿（スレッド・メッセージ）は `user_id` を保持したまま残す。表示名は既存の匿名化フロー（`user.anonymize()`）に**自動追従**する（CLAUDE.md 原則 4）。
- **本機能専用の退会リスナーは不要**。bulletin / chat 既存の匿名化追従に乗る。
- **新規テーブルの `created_by` 等の user_id 列**（`tournament_contact_space` の払い出し者を将来記録する場合を含む）は、**履歴・証跡として保持**する＝CLAUDE.md の退会二段モデルにおける**強匿名化対象外**（user_id は NULL 化せず残す）。表示名のみ既存の匿名化に追従させ、退会後は匿名表示名で描画する。GDPR 上の個人特定リスクは表示名匿名化で除去され、証跡としての user_id 参照は保持してよい（即時消去対象の「再設定で復旧可能なデータ」には当たらない）。

---

## 7. F08.7 §5.9 との関係

F08.7 §5.9「ディビジョン別ターゲティング」は「掲示板/通知/DM の配信対象をディビジョンで絞る」フィルタ機能の計画であった。本書はそれを**連絡スペースという常設の場**として具体化したもの。両者は補完関係:

- **本書（連絡スペース）**: 大会/ディビジョンに紐づく**常設の掲示板・チャット**。参加チームが日常的に連絡する場。
- **§5.9（ターゲティング）**: 既存の通知/DM/タイムラインの**配信先フィルタ**として `target_type='TOURNAMENT_DIVISION'` を使う一過性の配信。

F08.7 §5.9 は本書 01 へのリンクに置き換える（参加チーム解決の源泉＝`tournament_participants`・status=ACTIVE 除外ルールは共通）。

---

## 8. 精査ログ

### 8.1 1 回目（不備・セキュリティ・ユーザビリティ・見落とし・保守性）

- **不備**: 二段（大会/ディビジョン）× 2 種別（掲示板/チャット）の払い出しを §3.1〜§3.3 で網羅。continueTournament の複製漏れをテスト必須として明記。
- **セキュリティ**: read/write 分離（§4.1/§4.2）、IDOR 404 統一、PUBLIC read-only、公開トグルは主催組織 ADMIN 限定で代表には開放しない（権限昇格防止）。
- **ユーザビリティ**: 自動付帯で入力摩擦ゼロ。REGISTERED で開始前から連絡可。
- **見落とし**: N+1 回避の exists クエリ（§4.3）、source 複合 index の実装時 grep、bulletin カテゴリ自動生成。
- **保守性**: 越境 TODO 明記（原則 5）、UUIDv7（原則 6）、クロスドメイン FK なし（原則 1）、CASCADE なし（原則 2）。

### 8.2 未解決事項

**現時点でなし。**

---

## 9. 実装メモ（隊1・2026-05-31）

設計書に対する実装上の確定事項・軽微な差分を記録する。

### 9.1 実装ファイル

- DDL: `V9.20260531120000__create_tournament_contact_space.sql`（新規テーブル）/ `V9.20260531120100__add_bulletin_threads_scope_index.sql`（§2.2 の `(scope_type, scope_id)` 複合 index 根治追加）
- enum: `bulletin.ScopeType`（+TOURNAMENT/TOURNAMENT_DIVISION）/ `chat.ChannelType`（+TOURNAMENT_CHAT/TOURNAMENT_DIVISION_CHAT）/ `tournament.ContactSpaceScopeType` / `tournament.ContactSpaceKind`
- Entity/Repo: `TournamentContactSpaceEntity`（UuidV7Entity 継承）/ `TournamentContactSpaceRepository`
- 認可: `TournamentContactAccessService`（checkView/checkPost/checkVisibilityManage）+ `TournamentParticipantRepository` の N+1 回避 exists クエリ 4 本
- 払い出し: `chat.TournamentChatChannelService` / `tournament.TournamentContactSpaceProvisioningService`（フック: createTournament / continueTournament / createDivision、archive: delete*）
- 公開トグル: `TournamentContactSpaceService` + `TournamentContactSpaceController`（PATCH visibility / GET 一覧）
- 本体配線: `BulletinThreadService`（*Global メソッド群）/ `ChatMessageService`（sendMessage / checkChannelViewAccess）+ `ChatMessageController.listMessages`

### 9.2 設計差分

- **チーム所属判定の実装**: 設計書は `TeamMembershipRepository` を範としているが、本コードベースに同名クラスは無く、TEAM メンバー判定は `memberships`（`scope_type='TEAM'`）、TEAM 代表（ADMIN/DEPUTY_ADMIN）判定は `user_roles × roles` を使う。N+1 回避 exists クエリは `tournament_participants × tournament_divisions × (memberships | user_roles)` の native JOIN 1 本で完結（§4.3 の意図どおり）。
- **添付クォータ**: 大会連絡スペースの bulletin/chat 添付は専用 StorageScopeType を持たないため、`VILLAGE` と同方針で操作者の PERSONAL クォータに計上する（`BulletinAttachmentService` / `ChatAttachmentService`）。
- **公開 PUBLIC 露出経路**: `TournamentContactAccessService.checkView` は `is_public=true` で未ログイン（userId=null）を許可する（§4.1 準拠）。ただし現行の chat/bulletin コントローラは authenticated 経路のため、未ログイン HTTP 公開専用エンドポイントは本波では新設していない（将来の public controller で露出する余地を残す）。

---

## 10. 参加者向け導線（チーム側連絡スペース画面と一覧 API）（CMP-261118-0900）

> **ステータス**: 🟡 設計中（精査前）。本節は追記のみで、§1〜§9 は書き換えない。
> **認可の契約**: 本節は「§4（PR #4643 改訂後）」の契約（`TournamentContactSpaceAccessGate`・存在秘匿・403 は `TOUR_031` のみ）を正とし、引用せず参照する。#4643 が main に入るまで、本節が参照する Gate 系クラス名は同 PR のブランチ上にのみ存在する。

### 10.1 背景と目的

連絡スペース（§1〜§5）は API 上は参加チームの有効メンバーも閲覧でき、代表・副代表は投稿できる（§4.1/§4.2）。しかし画面が主催組織側の `organizations/[slug]/tournaments/[tId]/communication.vue` にしかなく、参加者はそこへ到達できない（組織メンバーでないため組織配下のページに入れない・導線も無い）。参加者が自分のチームの文脈で連絡スペースを開く入口と、それを支える参加者向け一覧 API を定義する。

範囲外（本節では変更しない）: 主催者向け一覧 `GET /api/v1/tournaments/{tournamentId}/contact-spaces`（`requireVisibilityManage` 相当の認可・応答）、公開トグル PATCH、掲示板・チャットの投稿/閲覧 API の契約、主催組織側画面。

### 10.2 御裁可済みの方針（変更不可）

1. 入口はチーム側の新ページ `/teams/{slug}/tournaments/{tId}/communication`（既存の `fees.vue` / `roster.vue` / `submissions.vue` と並ぶ）。参加大会カードからリンクする。主催組織側画面は主催者用に残す。掲示板一覧・スレッド作成等のコンポーネントは共用する。
2. 参加者向けの一覧 API を新設する（主催者向け一覧の認可は緩めない）。
3. 参加者に見せるのは「大会全体」と「自チームが出ている部門」の連絡スペースだけ。部門名は新 API の応答に含め、組織配下の部門一覧 API（`DivisionController` の `GET /api/v1/organizations/{orgId}/tournaments/{tId}/divisions`）は参加者画面から呼ばない。
4. 関連不具合は別課題（§10.10）。

### 10.3 API 契約

#### 10.3.1 パスの決定

案 A `GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces` と案 B `GET /api/v1/tournaments/{tournamentId}/contact-spaces/me` を比較し、**案 A を採る**。

| 観点 | 案 A（チーム文脈つき） | 案 B（`/me`） |
|------|------|------|
| 部門の絞り込み | 「このチームが出ている部門」を決定的に決められる | 同一ユーザーが複数の参加チームに属すると、どのチームの部門かが決まらず和集合になる。画面（チーム配下）と応答の文脈がずれる |
| slug→内部 ID 解決の既存流儀 | `StandingsController` の `GET /api/v1/teams/{teamId}/tournament-history`・`tournament-stats`（`@PathVariable String teamId` → `teamService.resolveTeamId`）と同形 | 画面は slug 起点なので、結局 FE が内部 ID を別途取る必要がある（数値 ID の露出が増える） |
| 存在オラクル | チーム slug・大会 ID・参加状態をすべて「不在と同一の 404 `TOUR_029`」に畳める（§10.3.4） | 同様に畳めるが、チームを特定しないため「自チーム所属だが当該大会の参加者でない」と「大会が無い」の区別が不要になる一方、画面との対応が取れない |
| 既存との整合 | `teams/{teamId}/...` は FE のチーム配下ページ群と同じ入口 | 主催者向け `/tournaments/{id}/contact-spaces` と同パス系で紛らわしい（`me` を ID と取り違える余地） |

根拠の要点: チーム文脈が要る（同じユーザーが複数の参加チームに属しうる。部門は「チーム×大会」でしか決まらない）ため案 A。

#### 10.3.2 リクエスト

`GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces`

| 項目 | 型 | 必須 | 説明 |
|------|----|----|------|
| `teamSlug`（パス） | string | 必須 | チーム slug。内部の数値 ID は受け付けない扱いを既存流儀に合わせる（`teamService.resolveTeamId` の挙動に従う。**推測**: 数値文字列も解決される実装の可能性があり、実装時に確認する） |
| `tournamentId`（パス） | long | 必須 | 大会 ID。数値以外は 400（Spring の型変換。ただし §10.3.4 のとおり、認証後の「不在」と同形にするため、型不正のみ例外的に 400） |
| クエリ | — | — | なし（絞り込み・ページングは持たない） |

認証必須（未ログインは 401。§4（PR #4643 改訂後）の「未認証の呼び出しは同一の 401」に合わせる）。

#### 10.3.3 応答（200）

`ApiResponse` 包み（`{ "data": ... }`）。

```
data: {
  tournamentId:   number            // 非 null
  tournamentName: string            // 非 null（ヘッダ表示用。大会が不在なら 404 になるため null は無い）
  divisions: [                      // 自チームが REGISTERED/ACTIVE で出ている部門のみ。空配列は 404 にするため通常 1 件以上
    { divisionId: number, name: string }   // name は非 null
  ]
  spaces: [                         // 見られるスペースのみ。0 件もあり得る（§10.3.6）
    {
      id:         string (UUID)     // tournament_contact_space.id。非 null
      scopeType:  "TOURNAMENT" | "TOURNAMENT_DIVISION"
      scopeId:    number            // 大会 ID または部門 ID
      divisionName: string | null   // scopeType=TOURNAMENT のとき null。それ以外は非 null
      spaceKind:  "BULLETIN" | "CHAT"
      refId:      number            // 掲示板=カテゴリ id、チャット=チャンネル id（既存 ContactSpaceResponse と同義）
      isPublic:   boolean
      accessLevel: "VIEW" | "POST"  // 非 null。権限なしは応答に含めず、この列挙に「NONE」は無い
    }
  ]
}
```

- `spaces` は並び順を固定する: 大会全体（BULLETIN, CHAT）→ 部門（部門 ID 昇順、各 BULLETIN, CHAT）。
- `accessLevel` は **`TournamentContactSpaceAccessGate.requireViewResolvingLevel` の結果と同値**（`ContactAccessLevel.VIEW` / `POST`）。画面の出し分けと、投稿 API（`requirePost`）の実判定をずらさないため、一覧用に別ロジックで再計算しない。ユーザー単位の判定のため、チームを跨ぐ副作用がある（例: 同一大会の別の参加チームで代表を務める者は、大会全体スペースが `POST` になる。投稿 API でも通るので表示と一致する）。
- 閲覧できないスペース（閲覧判定が不在応答になるもの）は応答から**除外**する（例外にしない）。

#### 10.3.4 認可・エラー（F00 を経由するか）

- **F00（`ContentVisibilityChecker` 等の可視性ラダー）は経由しない。** 連絡スペースの閲覧可否は §4（PR #4643 改訂後）の `TournamentContactSpaceAccessGate`（`requireView` / `requireViewResolvingLevel`）が正本であり、本 API はその上に乗る（既存の連絡スペース系 EP と同じ。チームのコンテンツ可視性ではなく、大会参加という所属で決まるため）。
- 判定順:
  1. 認証（未ログイン → 401）。
  2. `teamSlug` を内部 ID に解決。解決できない → **404 `TOUR_029`**（チーム不在のエラーコードをそのまま返さず、本 API 内で `TOUR_029` に統一する。**要確認**: `resolveTeamId` は不在時に team ドメインのエラーを投げると推測されるため、実装は非例外の存在確認で行うか、捕捉して `TOUR_029` に変換する）。
  3. 呼び出し者が当該チームの有効メンバー（`memberships` の `scope_type='TEAM'`・退会済みでない）か。違う → 404 `TOUR_029`。
  4. 当該チームが当該大会のいずれかの部門に `REGISTERED` / `ACTIVE` で参加しているか（`tournament_participants`、`WITHDRAWN` / `DISQUALIFIED` は含めない。§4.1 と同じ）。違う、または大会が不在 → 404 `TOUR_029`。
  5. 大会全体と、自チームが出ている各部門について、種別ごとに Gate で閲覧可否・水準を解決し、見られるものだけ返す。
- 上記 2〜4 の拒否は status・コード・本文が互いに一致する同一応答にする（存在オラクル対策。チーム slug の実在は非機密だが、「そのチームが当該大会に出ているか」「自分が所属か」は漏らさない）。403 は本 API では返さない（閲覧可能だが権限不足、に当たる状況がないため。投稿の 403 `TOUR_031` は投稿 API 側）。
- 主催組織 ADMIN・SYSTEM_ADMIN はチームの所属者ではないので本 API では 404 になる。これらは主催者用画面と主催者向け一覧を使う（§10.2 の方針どおり、主催者向け一覧を参加者に開放しない裏返し）。**要裁可（低）**: チームの所属者でもある主催組織 ADMIN の扱いは、メンバーとして通る（上記 3 を満たす）ため `POST` になるのは自然だが、確認されたい。
- 新規エラーコードは不要（§10.7）。

#### 10.3.5 性能・N+1・件数上限

- 参加チーム数・部門数・スペース数に比例するクエリを出さない。`TournamentContactSpaceRepository` 等への追加クエリは次の定数本に収める: (a) チーム解決、(b) 自チームの当該大会の参加部門（`tournament_participants × tournament_divisions` を 1 本、部門名つき、大会名も同時に取る）、(c) 該当スコープ（大会 + 部門 ID 群）のスペースを `IN` で 1 本、(d) 水準判定。
- (d) は Gate を (スコープ × 種別) ごとに呼ぶと 1 呼び出しあたり数本のクエリになり、スペース数 `2 + 2×D`（D = 自チームの参加部門数）に比例する。D は通常 1、同一チームが同一大会の複数部門に出る場合も数件で、参加チーム全体には比例しない。**これを許容するか、Gate にスコープ単位の一括解決メソッド（スコープごとに 1 回、種別ごとの `isPublic` 差分だけで水準を決める）を足すかを実装時に選ぶ。いずれの場合も、結果は `requireViewResolvingLevel` の (スコープ, 種別) ごとの結果と一致すること**（契約テストで固定、AC-P20）。
- 件数の上限: 応答はページングせず全件を返す。上限は `2 + 2×D`。D の上限は DB 制約では保証されない（`uq_tp_div_team` は部門×チームの一意のみ）。黙って切り捨てると症状を隠すため、切り捨てはしない。現実の運用では D は小さい前提とし、上限（超過時は 4xx/5xx で明示）を設けるか否かは**要裁可**（§10.12）。

#### 10.3.6 0 件・境界

- 自チームが参加していれば、大会作成時の自動付帯（§3）により通常は 1 件以上のスペースがある。`spaces` が空配列になるのは、自動付帯前のデータ・払い出し失敗・論理削除済みなどの異常寄りの状態。200 + 空配列で返し、FE が「連絡スペースが準備されていません」と表示する（握りつぶしではなく明示の空状態）。
- チームが当該大会の複数部門に出ている場合、`divisions` に複数入り、画面は部門ごとのタブを出す。

### 10.4 受け入れ条件

凡例: 【E2E】= 実機 E2E（ロール横断・URL 直打ち）で確かめる。【契約】= BE の契約テスト／統合テストで確かめる。【FE】= FE 単体・コンポーネントテスト。

前提の設定: 大会 T（主催組織 O）、部門 D1・D2。チーム A は D1 に `ACTIVE`、チーム B は D2 に `ACTIVE`、チーム C は T に不参加。A の代表 a1（ADMIN）・副代表 a2（DEPUTY_ADMIN）・一般 a3（MEMBER）・a4（SUPPORTER）。b1 は B の代表。x は A・B の両方の一般会員。外部者 z。スペースは自動付帯のまま全て非公開。

**一覧 API（正常系・権限別）**

- AC-P01【契約】【E2E】a1 が `GET .../teams/A/tournaments/T/contact-spaces` を呼ぶと 200。`divisions` は D1 のみ、`spaces` は 大会全体の掲示板・チャット と D1 の掲示板・チャット の計 4 件で、すべて `accessLevel=POST`。D2 のスペースは含まれない。
- AC-P02【契約】a2（副代表）も AC-P01 と同じ結果。
- AC-P03【契約】【E2E】a3（一般会員）は 200 で同じ 4 件が返り、すべて `accessLevel=VIEW`。
- AC-P04【契約】【E2E】a4（SUPPORTER）は AC-P03 と同じ（4 件・`VIEW`）。
- AC-P05【契約】`spaces[]` の `divisionName` は大会全体のとき null、部門のとき部門名。`tournamentName` が非 null。
- AC-P06【契約】`accessLevel` は、同一ユーザー・同一スペースについて `requireViewResolvingLevel` の戻り値および投稿 API（掲示板スレッド作成・チャット送信）の成否と一致する（`POST` なら投稿 API が 2xx、`VIEW` なら 403 `TOUR_031`）。

**一覧 API（異常系・存在オラクル）**

- AC-P07【契約】【E2E】T に不参加のチーム C の会員 c1 が `.../teams/C/tournaments/T/contact-spaces` を呼ぶと 404 `TOUR_029`。
- AC-P08【契約】a1 が自分の所属でないチーム B の slug で呼ぶと 404 `TOUR_029`（他チームの slug で参加状況を探れない）。
- AC-P09【契約】z（A の非会員）が `.../teams/A/...` を呼ぶと 404 `TOUR_029`。
- AC-P10【契約】【E2E】未ログインで呼ぶと 401（URL 直打ちを含む）。
- AC-P11【契約】存在しない大会 ID・存在しないチーム slug・退会済みメンバーが呼んだ場合は、AC-P07/08/09 と status・`error.code`・本文が一致する。
- AC-P12【契約】論理削除済みの大会（または全スペースが論理削除）は 404 または空配列のいずれか一方に決め、同じ入力に対して他の拒否応答と区別がつかない形にする（**要確認**: 現行の大会論理削除時の `tournamentRepository` の挙動を実装時に確認して決める）。
- AC-P13【契約】型不正の `tournamentId`（`abc`）は 400（Spring 型変換）。

**参加状態・部門・件数の境界**

- AC-P14【契約】チーム A の参加が `WITHDRAWN` または `DISQUALIFIED` のとき、a1・a3 とも 404 `TOUR_029`（辞退・除外後は閲覧できない。§4.1 と一致）。`REGISTERED`（大会開始前）では 200・AC-P01 と同じ。
- AC-P15【契約】チーム A が D1 は `ACTIVE`、D2 は `WITHDRAWN` のとき、`divisions` は D1 のみ、`spaces` に D2 は含まれない。
- AC-P16【契約】チーム A が D1・D2 の両方に `ACTIVE` のとき、`divisions` は 2 件、`spaces` は 2 + 2×2 = 6 件。
- AC-P17【契約】参加は部門単位のため「大会に参加しているが部門に出ていない」チームは存在しない。出ている部門が 0 件になった A（全部門が `WITHDRAWN`/`DISQUALIFIED`）は AC-P14 と同じ 404。部門に出ていない別チームの部門スペースは、A の応答に含まれない（AC-P01）。
- AC-P18【契約】スペースが 0 件（払い出し前）の場合は 200 + `spaces=[]`、`divisions` は存在する。
- AC-P19【契約】x（A と B の両方の一般会員）が `.../teams/A/...` を呼ぶと D1 の 4 件のみ、`.../teams/B/...` を呼ぶと D2 の 4 件のみが返る（チームごとに分かれ、和集合にならない）。x が A の代表でもある場合、大会全体スペースの `accessLevel` はユーザー単位の Gate 判定どおり（AC-P06 と一致）。
- AC-P20【契約】クエリ数の上限: D = 1 と D = 3 で、参加チーム数を 2 と 40 に変えても、発行クエリ数は参加チーム数に依存しない（`QueryCounter` 等で固定。**推測**: 既存の N+1 検査ユーティリティの有無は実装時に確認）。
- AC-P21【契約】公開スペース（`isPublic=true`）でも、A の非会員 z は本 API では 404（本 API は「自分のチームの文脈」の入口であり、公開スペースの閲覧は既存の公開経路に従う）。
- AC-P22【契約】主催者向け一覧 `GET /api/v1/tournaments/{tournamentId}/contact-spaces` の認可・応答は変わらない（a1 が呼ぶと従来どおりの拒否応答。回帰）。

**画面（チーム側）**

- AC-P23【E2E】a1 でログインし、`/teams/A/tournaments` の参加大会カードから「連絡スペース」リンクを押すと `/teams/A/tournaments/T/communication` が開き、大会全体・D1 のタブと、掲示板・チャットのタブが見える。
- AC-P24【E2E】a1 は新規スレッド作成ボタンが見え、実際にスレッドを作成できる。a3（一般会員）・a4（SUPPORTER）はスレッドを閲覧できるが新規スレッド作成ボタンが出ない（`accessLevel=VIEW`）。
- AC-P25【E2E】a3 が D1 の掲示板スレッドを開き、返信（または投稿）を試みても 403 `TOUR_031`（画面で操作を出さないため通常到達しないが、API を直接叩いても拒否される）。
- AC-P26【E2E】チーム C の c1 が `/teams/C/tournaments/T/communication` を URL 直打ちすると「この大会の連絡スペースを利用できません」の状態表示（404 を空白画面や無言のリダイレクトにしない）。z・他テナントのユーザーも同様。
- AC-P27【E2E】未ログインで URL 直打ちするとログイン画面へ（`auth` middleware。ログイン後に元の URL へ戻る）。
- AC-P28【E2E】チャットタブでメッセージ送信ボタンの有無が `accessLevel` と一致する（`VIEW` では送信不可）。**要確認**: `ChatMessagePanel` に送信可否を渡す props の有無。
- AC-P29【E2E】辞退（`WITHDRAWN`）に変更されたチームの a1 が画面を再読み込みすると、AC-P26 と同じ状態表示になり、参加大会カードのリンクは残っていても押下先で同表示になる（カードの出し分けは別課題で扱わない）。
- AC-P30【FE】一覧取得が 5xx・通信断のとき、エラー通知と再試行導線を出す（空状態を偽装しない）。404 は「利用できません」の状態表示で、通知トーストを濫発しない。
- AC-P31【FE】主催組織側の `organizations/[slug]/tournaments/[tId]/communication.vue` は変更前と同じ挙動のまま（回帰）。

実機 E2E で必須の確認: AC-P01・P03・P04・P07・P09〜P10・P23〜P29（ロール横断と URL 直打ち）。契約テストで確認: AC-P01〜P22（MockMvc 等で認可・応答形・クエリ数）。FE 単体: AC-P30・P31 と、`accessLevel` に応じたボタン出し分け。

### 10.5 FE 画面仕様

- **ページ**: `frontend/app/pages/teams/[slug]/tournaments/[tId]/communication.vue`（新設）。`definePageMeta({ layout: 'team', middleware: 'auth' })`。`fees.vue` と違い `orgId` クエリは要らない（API がチーム slug と大会 ID で完結する）。
- **構成**: `PageHeader`（戻り先は `/teams/{slug}/tournaments`）→ 大会名 → スコープ切替（「大会全体」＋ 応答 `divisions` の部門名）→ 掲示板/チャットのタブ → 本体。主催側 `communication.vue` の骨格を共用する。共通部分は新規コンポーネント（案: `TournamentContactPanel`）に切り出し、主催側と参加者側の両方から使う。**共用の範囲**: 掲示板一覧（`BulletinThreadList`）・詳細（`BulletinThreadDetail`）・作成（`BulletinThreadForm`）・チャット（`ChatMessagePanel`）。主催者専用部分（公開トグル、`isAdminOrDeputy` の組織権限）は共用コンポーネントに含めず、主催側ページに残す。
- **読み込む API**: 新 EP 1 本のみ。部門一覧 API は呼ばない（部門名は応答に含まれる）。チャットを開くときの `GET /chat/channels/{id}` と、掲示板の既存グローバル API（`scope_type=TOURNAMENT[_DIVISION]`）は既存のまま。
- **権限による出し分け**: スペースごとの `accessLevel` を使う。新規スレッド作成ボタン（`can-create`）は `POST` のときだけ表示する（主催側のように「常に出してサーバーが 403」とはしない）。チャットの送信は `POST` のときだけ有効（AC-P28）。`VIEW` のときは「閲覧のみ（投稿はチームの代表・副代表と主催者）」と補足する。管理（ピン・削除）系の `can-manage` は参加者側では渡さない（チーム ADMIN の権限は別物であり、本節は範囲外）。
- **読み込み・空・失敗**: 読み込み中は `PageLoading`。404 は「利用できない」状態（理由は特定しない）。`spaces` が 0 件は「連絡スペースが準備されていません」。その他失敗はエラー通知＋再試行。`catch {}` で握りつぶさない。部門を切り替えても、先に開いたスレッド・作成ダイアログを持ち越さない（主催側と同じ）。
- **参加大会カードからの導線**: `frontend/app/pages/teams/[slug]/tournaments/index.vue` のカードは全体が単一の `NuxtLink`（組織画面へ）。リンクの入れ子は不正な HTML になるため、カードを「本文リンク」と「下部のアクション行（連絡スペースへ）」に分け、後者に `/teams/${teamSlug}/tournaments/${tournamentId}/communication` のリンクを置く。`tournamentId` は履歴の `identifiers.tournamentId`。既存リンクの `organizationId` の扱いは別課題（§10.10）で、本節では触れない。
- **URL**: 開いているスコープ・タブ・スレッドを URL に反映するのは別課題（§10.10）。本節の初版は反映しない（ページ URL のみ直打ちで再現できればよい）。

### 10.6 DDL / 状態遷移

- **DDL 不要**: 既存の `tournament_contact_space`（§2.1）と `tournament_participants` の読み取りのみ。新規カラム・インデックスは持たない。参加チームの絞り込み `(division_id, team_id)` は `uq_tp_div_team`、`idx_tp_team` で足りる（**推測**: クエリは `team_id` 起点で `idx_tp_team`、大会への紐付けは `tournament_divisions` を join。実行計画は実装時に確認）。
- **状態遷移なし**: 新しい状態は持たない。参加状態は既存の `ParticipantStatus`（`REGISTERED` / `ACTIVE` / `WITHDRAWN` / `DISQUALIFIED`）を読むだけで、`REGISTERED` / `ACTIVE` を「参加中」、`WITHDRAWN` / `DISQUALIFIED` を「参加外」と扱う（§4.1 と同一規則）。

### 10.7 エラーコード（流用）

| 状況 | status | コード | 備考 |
|------|--------|--------|------|
| 未ログイン | 401 | 既存の認証エラー | §4（PR #4643 改訂後）の未認証規則 |
| チーム不在・非所属・大会不在・非参加・参加外（辞退/除外） | 404 | `TOUR_029`（`CONTACT_SPACE_NOT_FOUND`） | 本 API では全て同一応答 |
| 閲覧可だが投稿権なし（投稿 API 側） | 403 | `TOUR_031`（`CONTACT_SPACE_POST_FORBIDDEN`） | 本 API は返さない |
| 公開設定の権限なし（PATCH 側） | 403 | `TOUR_032`（`CONTACT_SPACE_VISIBILITY_FORBIDDEN`） | 本 API は触らない |
| `TOUR_030`（閲覧権なし 403） | — | 欠番 | #4643 で送出停止。再利用しない |

新規エラーコードは不要と判断する。足りなくなる場合（例: 参加外を別応答にしたい）は、存在オラクルを開くため原則不可。必要が生じたら理由を書いて要裁可とする。

### 10.8 Flyway

不要（DDL 変更なし）。

### 10.9 i18n

既存のキー体系は `frontend/app/locales/{ja,en,zh,ko,es,de}/tournament.json` の `tournament.communication.*`（`title`・`bulletin`・`chat`・`tournament_space`・`division_space`・`empty`・`no_bulletin`・`no_chat`・`load_failed` 等）。参加者向けに次のキーを `tournament.communication.participant.*` として追加する（6 言語すべてに、その言語の訳を入れる。直書き禁止）。ja の案:

| キー | ja（案） |
|------|---------|
| `tournament.communication.participant.title` | 大会の連絡スペース |
| `tournament.communication.participant.open_link` | 連絡スペースを開く |
| `tournament.communication.participant.view_only_notice` | 閲覧のみです。投稿できるのはチームの代表・副代表と主催者です |
| `tournament.communication.participant.unavailable` | この大会の連絡スペースを利用できません |
| `tournament.communication.participant.unavailable_hint` | 参加中のチームの会員のみ利用できます。チームの参加状況をご確認ください |
| `tournament.communication.participant.load_retry` | 再読み込み |
| `tournament.communication.participant.empty` | 連絡スペースが準備されていません |

既存の `load_failed` / `bulletin` / `chat` / `tournament_space` は再利用する。参加大会カードの既存ラベルは直書きが残っている箇所があるが、本節の追加分は直書きにしない。

### 10.10 既知課題・別課題（本節の範囲外・ここでは直さない）

台帳への起票は殿が別途行う。

1. 参加大会カード（`teams/[slug]/tournaments/index.vue`）のリンクが `organizations/${t.organizationId}/...` と組織の数値 ID になっている疑い。組織画面のパスは slug 前提（`organizations/[slug]/...`）のため、数値 ID では解決できない可能性がある。**推測**（実機で未確認）。
2. 投稿が 403 になったときの通知が原因別に分かれていない（`TOUR_031` など原因ごとの文言がない）。
3. スレッドを開いても URL に反映されず、再読み込み・共有で同じスレッドに戻れない（スコープ・タブも同様）。

### 10.11 非機能

- **性能**: §10.3.5。参加チーム数・スペース数に比例するクエリを出さない。
- **後方互換**: 主催者向け API・主催組織側画面は変えない（AC-P22・AC-P31）。新 EP の追加のみ。
- **段階導入**: 第 1 段は本節（一覧 API・チーム側ページ・カード導線）。公開スペースの非ログイン閲覧導線、通知（未読バッジ）、URL 反映は後続（§10.10 ほか）。
- **ドキュメント同期**: 実装時に `docs/openapi.json` の再生成と `frontend` の `npm run generate:types` を行う。

### 10.12 要裁可・未確定事項（精査前）

1. 主催組織 ADMIN・SYSTEM_ADMIN がチーム所属者でない場合に本 API が 404 になることの承認（§10.3.4）。
2. `D`（自チームの参加部門数）の上限を設けるか（§10.3.5）。
3. 論理削除済み大会の応答（404 か 空配列か）の確定（AC-P12）。
4. 水準判定を Gate 呼び出しの繰り返しで許容するか、Gate にスコープ単位の一括解決を足すか（§10.3.5。後者は #4643 の Gate への追加になり、同 PR マージ後の別 PR が必要）。
