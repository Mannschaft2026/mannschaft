# F08.7.1 / 01: 連絡機能（掲示板・チャット連絡スペース）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-11-20（CMP-261118-0900: §10 参加者導線を追記）
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

**API 入口（グローバル方式）**: 大会連絡スペースの掲示板は、グローバル方式掲示板 API（`/api/v1/bulletin/threads` の一覧・作成、`/api/v1/bulletin/threads/read-all`、`/api/v1/bulletin/threads/{threadId}` 系）に `scope_type=TOURNAMENT` / `TOURNAMENT_DIVISION`、`scope_id=<大会ID／ディビジョンID の数値>` を指定して利用する（`scope_id` に slug は使えず 400）。認可は §4（`TournamentContactAccessService`：閲覧=canView・投稿=canPost）の規則に従う。閲覧権のない者は不在 ID と同一の 404 に畳み（スコープ系は `TOUR_029`、子リソース ID 指定は子の不在コード `BULLETIN_002`/`BULLETIN_003`/`BULLETIN_007`/`BULLETIN_025`）、閲覧できるが投稿権のない者だけを 403 `TOUR_031` とする（CMP-261116-0726）。旧 403 `TOUR_030`（閲覧権なし）は欠番とし、送出しない（番号は再利用しない）。パス方式（`/api/v1/{scopeType}/{scopeId}/bulletin/...`）は大会スコープを受理しない（403）。

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

クロスドメインの所属判定は `TeamMembershipRepository` / `AccessControlService` を **ID 参照のみ**で呼ぶ（原則 1）。存在しない／削除済みスペース、および**閲覧権のない者**への応答は一律 **404**（IDOR 対策・存在を漏らさない。§4.3）。

### 4.1 `canView(scopeType, scopeId, userId)`（要件②）

```
canView(scopeType, scopeId, userId):
    space = findActiveSpace(scopeType, scopeId, space_kind)
    space が存在しない / deleted_at IS NOT NULL → 404（IDOR 対策）

    space.is_public == true                                      → 許可（PUBLIC・未ログイン含む。read-only）
    userId が対象チーム(status IN (REGISTERED, ACTIVE)) のメンバー  → 許可
    主催組織 ADMIN（isAdmin(userId, orgId, "ORGANIZATION")）       → 許可
    SYSTEM_ADMIN                                                  → 許可
    else                                                         → 404（不在と同一応答。403 にしない）
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
    else（閲覧できる者）                                                → 403 TOUR_031
    else（閲覧もできない者）                                            → 404（§4.3）
```

- 投稿できるのは **各チームの代表（ADMIN）・副代表（DEPUTY_ADMIN）** と **主催組織 ADMIN** のみ。一般 MEMBER・SUPPORTER・PUBLIC は閲覧のみ（権限昇格防止：MEMBER が代表になりすませない）。
- `is_public=true` のスペースでも、**PUBLIC は常に read-only**。公開で投稿権が広がることはない。

### 4.3 存在秘匿の判定表（CMP-261116-0726）

閲覧権のない者に対し、スペース・スレッド・返信・添付・フォルダ等の**存在を応答の差で漏らさない**。判定は「閲覧できるか」を先に見て、拒否の種類を分ける。

| 状況 | 応答 |
|------|------|
| 閲覧権なし（非公開スペースの部外者・未ログイン・主催組織外・脱退/失格チーム等） | **404**。スコープ系 EP は `TOUR_029`、子リソース ID 指定 EP は子の不在コード（スレッド `BULLETIN_002`・返信 `BULLETIN_003`・添付 `BULLETIN_007`・添付対象 `BULLETIN_025`）。実在しない ID と status・コード・本文が一致する |
| 閲覧可・投稿（管理）権なし | **403 `TOUR_031`** |
| スペース・大会・ディビジョンが不在、または大会とディビジョンの帰属が不一致 | 404（閲覧権なしと同一） |
| 閲覧権あり・投稿権あり | 許可 |

- 「閲覧権」は §4.1 の許可主体（公開スペースの閲覧者、参加チームの有効メンバー、主催組織 ADMIN、SYSTEM_ADMIN）。**スペース種別（掲示板／チャット）ごとの `is_public` で判定**する（掲示板・フォルダは掲示板スペース、チャットはチャットスペースを基準にする）。
- 投稿権の拒否時に 403 か 404 かを分けるのは閲覧権の有無のみ。**投稿権を持つ者は閲覧判定を経ずに通る**。
- **投稿後に資格を失った本人**（所属チームの脱退・失格等）は、閲覧権がなければ**自分が書いた投稿も不在扱い**（404）になる。公開スペースでは閲覧権があるため、本人の編集・削除は従来どおり行える。
- 未認証の呼び出しは、公開 EP（大会フォルダ一覧等）を除き、存在の有無によらず同一の 401。
- 旧 403 `TOUR_030` は欠番。CMP-261116-0726 以降は送出せず、番号も再利用しない（FE ロケールの文言は残置）。

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
  応答: 200 更新後のスペース / 403 TOUR_032 / 404 TOUR_029
```

- **スペースの存在を知り得ない者は 404 `TOUR_029`**（不在と同一応答。副作用なし）。**知り得るが管理権のない者は 403 `TOUR_032`**。「知り得る」とは、そのスコープ（大会またはディビジョン）のいずれかのスペース（掲示板／チャット）を**閲覧できる**こと（公開スペース、または参加チームの有効メンバー）。
- パスの大会とディビジョンの**帰属が一致しない**場合、およびパスのスペース ID が当該スコープのものでない場合は、主催組織 ADMIN でも **404 `TOUR_029`**（スペースは変更されない）。

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

> **ステータス**: 🟢 設計完了。本節は追記のみで、§1〜§9 は書き換えない。
> **認可の契約**: 本節は §4 の契約（`TournamentContactSpaceAccessGate`・存在秘匿・403 は `TOUR_031` のみ。PR #4643 でマージ済み）を正とし、引用せず参照する。本節のクラス名・行番号は 2026-11-20 時点の main で確認した。

### 10.1 背景と目的

連絡スペース（§1〜§5）は API 上は参加チームの有効メンバーも閲覧でき、代表・副代表は投稿できる（§4.1/§4.2）。しかし画面が主催組織側の `organizations/[slug]/tournaments/[tId]/communication.vue` にしかなく、参加者はそこへ到達できない。参加者が自チームの文脈で連絡スペースを開く入口と、それを支える参加者向け一覧 API を定義する。

範囲外（本節では変更しない）: 主催者向け一覧 `GET /api/v1/tournaments/{tournamentId}/contact-spaces`（認可・応答）、公開トグル PATCH、掲示板・チャットの投稿/閲覧 API の契約、主催組織側画面の挙動。

### 10.2 確定事項（マスター御裁可済み・変更不可）

1. 入口はチーム側の新ページ `/teams/{slug}/tournaments/{tId}/communication`（既存の `fees.vue` / `roster.vue` / `submissions.vue` と並ぶ）。参加大会カードからリンクする。主催組織側画面は主催者用に残す。掲示板一覧・スレッド作成等のコンポーネントは共用する。
2. 参加者向けの一覧 API を新設する（主催者向け一覧の認可は緩めない）。
3. 参加者に見せるのは「大会全体」と「自チームが出ている部門」の連絡スペースだけ。部門名は新 API の応答に含め、組織配下の部門一覧 API（`DivisionController` の `GET /api/v1/organizations/{orgId}/tournaments/{tId}/divisions`）は参加者画面から呼ばない。
4. 主催組織 ADMIN・SYSTEM_ADMIN がチームに所属していない場合、本 API は 404 `TOUR_029`。主催者は主催者用の画面・一覧を使う。主催者がそのチームの会員を兼ねる場合は、全スペースが `POST` になる（AC-P33）。
5. 部門数 D の上限は設けない。`uq_tp_div_team`（`division_id, team_id` の一意。`V8.042__create_tournament_participants_table.sql:11`）により、1 チームの参加は 1 部門に 1 行までで、D は大会の部門数以下に収まる。
6. 論理削除済みの大会は 404 `TOUR_029`。`TournamentEntity` に `@SQLRestriction("deleted_at IS NULL")`（`TournamentEntity.java:31`）があるため JPA 経由では自然にそうなる。native クエリでは `t.deleted_at IS NULL` を明示する。
7. Gate（`TournamentContactSpaceAccessGate`）に、例外を投げずにスコープ単位で一括解決するメソッドを追加する（§10.3.5）。既存 Gate（#4643 でマージ済み）への追加として実装する。
8. 関連不具合は別課題（§10.10）。

### 10.3 API 契約

#### 10.3.1 パスの決定

案 A `GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces` と案 B `GET /api/v1/tournaments/{tournamentId}/contact-spaces/me` を比較し、**案 A を採る**。

| 観点 | 案 A（チーム文脈つき） | 案 B（`/me`） |
|------|------|------|
| 部門の絞り込み | 「このチームが出ている部門」を決定的に決められる | 同一ユーザーが複数の参加チームに属すると、部門が和集合になり、チーム配下の画面と応答の文脈がずれる |
| slug→内部 ID 解決の既存流儀 | `StandingsController` の `GET /api/v1/teams/{teamId}/tournament-history`・`tournament-stats`（`@PathVariable String teamId` → `teamService.resolveTeamId`、`StandingsController.java:125-130`）と同形 | 画面は slug 起点なので FE が内部 ID を別途取る必要があり、数値 ID の露出が増える |
| 存在オラクル | チーム slug・大会 ID・参加状態をすべて「不在と同一の 404 `TOUR_029`」に畳める（§10.3.4） | 畳めるが、画面との対応が取れない |
| 既存との整合 | `teams/{teamId}/...` は FE のチーム配下ページ群と同じ入口 | 主催者向け `/tournaments/{id}/contact-spaces` と同系で紛らわしい |

根拠の要点: 部門は「チーム×大会」でしか決まらず、同じユーザーが複数の参加チームに属しうるため、チーム文脈が要る。

#### 10.3.2 リクエスト

`GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces`

| 項目 | 型 | 必須 | 説明 |
|------|----|----|------|
| `teamSlug`（パス） | **`String`** | 必須 | チーム slug。`TeamScopeId` 型は使わない（使うと不在時の 404 の本文が `TOUR_029` とずれ、AC-P11 の同一応答を満たせない）。数値だけの文字列も slug として扱い、内部 ID としては解釈しない |
| `tournamentId`（パス） | long | 必須 | 大会 ID。数値以外は 400（Spring 型変換。リソースの有無に依存しないためオラクルにならない。AC-P13） |
| クエリ | — | — | なし（絞り込み・ページングは持たない） |

認証必須（未ログインは 401。§4 の「未認証の呼び出しは同一の 401」に合わせる）。

#### 10.3.3 応答（200）

`ApiResponse` 包み（`{ "data": ... }`）。

```
data: {
  tournamentId:   number            // 非 null
  tournamentName: string            // 非 null
  divisions: [                      // 自チームが REGISTERED/ACTIVE で出ている部門。1 件以上（0 件なら 404）
    { divisionId: number, name: string }   // name は非 null
  ]
  spaces: [                         // 実在し、かつ呼び出し者が見られるスペース。0 件もあり得る（§10.3.6）
    {
      id:           string (UUID)   // tournament_contact_space.id。非 null
      scopeType:    "TOURNAMENT" | "TOURNAMENT_DIVISION"
      scopeId:      number          // 大会 ID または部門 ID
      divisionName: string | null   // scopeType=TOURNAMENT のとき null。それ以外は非 null
      spaceKind:    "BULLETIN" | "CHAT"
      refId:        number          // 掲示板=カテゴリ id、チャット=チャンネル id（既存 ContactSpaceResponse と同義）
      isPublic:     boolean
      accessLevel:  "VIEW" | "POST" // 非 null。「権限なし」は応答に含めない（NONE は無い）
    }
  ]
}
```

- `spaces` の並び: 大会全体（BULLETIN, CHAT）→ 部門（部門 ID 昇順、各 BULLETIN, CHAT）。
- `accessLevel` は `requireViewResolvingLevel` の (scope, kind) ごとの結果と同値（`ContactAccessLevel.VIEW` / `POST`）。画面の出し分けと投稿 API（`requirePost`）の実判定をずらさないため、別ロジックで再計算しない。ユーザー単位の判定のため、同一大会の別の参加チームで代表を務める者は、大会全体スペースが `POST` になる（投稿 API でも通るので表示と一致する）。
- **`canPost` は `kind` を見ない**（`TournamentContactSpaceAccessGate.java:193-201`）ため、同一スコープの掲示板とチャットの `accessLevel` は同じになる（閲覧できる限り）。
- スペースの一括判定は「**実在した行だけ**」を対象にする。存在しない行は判定せず、応答にも含めない。閲覧できない行は応答から外す（例外にしない）。

#### 10.3.4 認可・エラー（F00 を経由するか）

- **F00（`ContentVisibilityChecker` 等の可視性ラダー）は経由しない。** 連絡スペースの閲覧可否は §4 の `TournamentContactSpaceAccessGate` が正本であり、本 API はその上に乗る。
- **構成**: Controller は `String teamSlug` を受け、トランザクションを持たない Facade（tournament ドメイン。名称案 `ParticipantContactSpaceFacade`）を呼ぶ。判定はトランザクションの外で行い、「例外の catch で分岐しない」（baseline）に従う。各段は戻り値（`Optional` / 空）で分岐し、拒否はすべて同一の `BusinessException(TOUR_029)` を 1 箇所で投げる。
- **判定順**:
  1. 認証（未ログイン → 401）。
  2. slug 解決: 例外を投げない Optional 版 `TeamService.resolveFollowableTeamId(String slug)`（`TeamService.java:1395`）を**流用する**。不在・論理削除済み・アーカイブ済み・ACTIVE 以外（PROVISIONED）は空を返す。名称は「フォロー」だが、除外条件が本 API の要件（アーカイブ済み・PROVISIONED は 404）と一致するため新設しない（名称の改善は別課題）。空なら 404 `TOUR_029`。tournament から team ドメインの Service を ID/slug 参照のみで読み取り呼び出すため、越境である旨のコメントを付ける（原則 5）。
  3. 参加判定を **native クエリ 1 本**に統合する。入力は `teamId`・`tournamentId`・`userId`、出力は「参加中の部門（`divisionId`, 部門名）と大会名」の行。条件は `TournamentParticipantRepository` の `countActiveMemberOf*`（`:111-128`, `:177-185` 付近。`countActiveMemberOfAnyParticipantTeam` は `:129`、`countActiveMemberOfDivisionParticipantTeam` は `:184` で宣言）と完全に同一にする: `tournament_participants p × tournament_divisions d × tournaments t × memberships m`、`p.team_id = :teamId`、`d.tournament_id = :tournamentId`、`t.deleted_at IS NULL`、`p.status IN ('REGISTERED','ACTIVE')`、`m.scope_type = 'TEAM' AND m.scope_id = p.team_id AND m.user_id = :userId AND m.left_at IS NULL`（ロールでは絞らない。SUPPORTER も含む）。行が 0 件なら 404 `TOUR_029`（呼び出し者が非所属・チームが非参加・参加外・大会不在・論理削除済み大会を区別しない）。
  4. 大会全体と、手順 3 の各部門について、実在するスペース行を `IN` で 1 本取得し、Gate の一括解決（§10.3.5）で見られるものとその水準を決める。
- 手順 2〜3 の拒否は status・`error.code`・本文が一致する同一応答にする。403 は本 API では返さない（投稿の 403 `TOUR_031` は投稿 API 側）。
- 主催組織 ADMIN・SYSTEM_ADMIN は、チームの所属者でなければ手順 3 で行が 0 件となり 404（確定事項 4）。所属者を兼ねる場合は手順 3 を満たし、Gate の判定で全スペースが `POST` になる。
- 新規エラーコードは不要（§10.7）。

#### 10.3.5 性能・N+1・Gate の一括解決

- 参加チーム数・部門数・スペース数に比例するクエリを出さない。クエリは定数本に収める: (a) slug 解決 1、(b) 参加判定 1（上記 native）、(c) スペース取得 1（`scope_type='TOURNAMENT' AND scope_id=:t` OR `scope_type='TOURNAMENT_DIVISION' AND scope_id IN (:divisionIds)`、`deleted_at IS NULL`）、(d) 水準判定（下記）。
- **Gate に追加するメソッド**（名称案 `resolveLevelsForSpaces(Long userId, Long tournamentId, Collection<TournamentContactSpaceEntity> spaces)`）: 例外を投げず、`Map<UUID(スペース id), ContactAccessLevel>` を返す。**閲覧できない行はマップに含めない**（`NONE` は返さない）。呼び出し元（Facade の手順 3）の結果には依存せず、**このメソッド単体で既存 Gate と同じ閲覧資格判定を行う**（既存 Gate は対象スコープへの所属を `isActiveParticipantMember`（`TournamentContactSpaceAccessGate.java:71`・`:93`・`:129`）で確認する。手順 3 の所属確認は「自チームの部門だけを対象にする」ためのもので、他の参加チームの所属を保証しない）。構成は次の 5 要素で、クエリ本数は D に依存しない。
  1. 大会全体の代表判定 1 本（`countTeamAdminOfAnyParticipantTeam` 相当）。
  2. 部門ごとの代表判定を、部門 ID 群に対する**集約 1 本**（代表である部門 ID の集合を返す）。
  3. 大会全体の有効メンバー判定 1 本（`countActiveMemberOfAnyParticipantTeam` 相当）。
  4. 部門ごとの有効メンバー判定を、部門 ID 群に対する**集約 1 本**（有効メンバーである部門 ID の集合を返す。条件は `countActiveMemberOfDivisionParticipantTeam` と同一）。
  5. 主催組織 ADMIN・SYSTEM_ADMIN の判定（大会 1 件から組織 ID を引く 1 本 ＋ `isAdmin` / `isSystemAdmin`。スコープに依存しないので 1 回だけ）。
  - 行ごとの水準（`requireViewResolvingLevel` と同順）: 5 成立、または該当スコープで 1/2 成立 → `POST`。そうでなく `isPublic` → `VIEW`。そうでなく該当スコープで 3/4 成立 → `VIEW`。いずれも不成立ならマップに含めない。
- Gate は #4643 でマージ済みのため、上記の追加は本設計の実装 PR に含める。**不変条件**: 全 (scope, kind) について、このメソッドの結果は `requireViewResolvingLevel` の戻り値（例外になるものはマップ不在）と一致する。AC-P20・AC-P34 で固定する。
- 件数: ページングせず全件を返す。上限は `2 + 2×D`（D ≤ 大会の部門数。確定事項 5）。

#### 10.3.6 0 件・境界

- 参加していれば、自動付帯（§3）により通常は 1 件以上ある。`spaces` が空配列になるのは、自動付帯前・払い出し失敗・論理削除済みなどの異常寄りの状態。200 + 空配列で返し、FE は既存キー `tournament.communication.empty` を表示する。
- 複数部門に出ている場合は `divisions` に複数入り、画面は部門ごとのタブを出す。

### 10.4 受け入れ条件

凡例: 【契約】= BE の契約テスト／統合テスト。【E2E】= 実機 E2E（ロール横断・URL 直打ち）。【FE】= FE 単体・コンポーネントテスト。**【E2E】の観測点は、ブラウザのネットワークで見る当該 API 応答の status と `error.code`（200 のときは `spaces[].accessLevel`）と、画面の状態表示**とする。

前提: 大会 T（主催組織 O）、部門 D1・D2。チーム A は D1 に `ACTIVE`、チーム B は D2 に `ACTIVE`、チーム C は T に不参加。A の代表 a1（ADMIN）・副代表 a2（DEPUTY_ADMIN）・一般 a3（MEMBER）・a4（SUPPORTER）。b1 は B の代表。x は A・B 両方の一般会員。外部者 z。スペースは自動付帯のまま全て非公開。**E2E の大会の公開範囲は `PUBLIC` または `PARTICIPANTS_ONLY` に限る**（§10.10 の制限 4 のため）。

**一覧 API（正常系・権限別）**

- AC-P01【契約】【E2E】a1 が `GET .../teams/A/tournaments/T/contact-spaces` を呼ぶと 200。`divisions` は D1 のみ、`spaces` は大会全体の掲示板・チャットと D1 の掲示板・チャットの計 4 件で、すべて `accessLevel=POST`。D2 のスペースは含まれない。観測点: 200 の本文。
- AC-P02【契約】a2（副代表）も AC-P01 と同じ。
- AC-P03【契約】【E2E】a3 は 200 で同じ 4 件、すべて `VIEW`。観測点: 200 の `accessLevel`。
- AC-P04【契約】【E2E】a4（SUPPORTER）は AC-P03 と同じ。観測点: 同上。
- AC-P05【契約】`divisionName` は大会全体のとき null、部門のとき部門名。`tournamentName` は非 null。
- AC-P06【契約】`accessLevel` は、同一ユーザー・同一スペースについて投稿 API（スレッド作成・チャット送信）の成否と一致する（`POST` なら 2xx、`VIEW` なら 403 `TOUR_031`）。

**一覧 API（異常系・存在オラクル）**

- AC-P07【契約】【E2E】T に不参加のチーム C の会員 c1 が `.../teams/C/tournaments/T/contact-spaces` を呼ぶと 404 `TOUR_029`。観測点: status と `error.code`。
- AC-P08【契約】a1 が所属でないチーム B の slug で呼ぶと 404 `TOUR_029`。
- AC-P09【契約】【E2E】z が `.../teams/A/...` を呼ぶと 404 `TOUR_029`。観測点: status と `error.code`。
- AC-P10【契約】【E2E】未ログインで呼ぶと 401（URL 直打ち含む）。観測点: status。
- AC-P11【契約】次の入力は、AC-P07〜P09 と status・`error.code`・`error.message`・本文の構造が一致する。比較から除くフィールドは `timestamp`・`path`・トレース/リクエスト ID のみ。入力: 存在しない大会 ID、存在しないチーム slug、アーカイブ済みチームの slug（AC-P35）、PROVISIONED（未有効化）チームの slug、数値だけの slug（実在チームの内部 ID と同じ数字・およびどのチームにも該当しない数字）、退会済みメンバー、論理削除済み大会（AC-P12）。
- AC-P12【契約】論理削除済みの大会は 404 `TOUR_029`（native クエリの `t.deleted_at IS NULL`）。
- AC-P13【契約】型不正の `tournamentId`（`abc`）は 400。

**参加状態・部門・件数の境界**

- AC-P14【契約】A の参加が `WITHDRAWN` または `DISQUALIFIED` のとき、a1・a3 とも 404 `TOUR_029`。`REGISTERED` では 200（AC-P01 と同じ）。
- AC-P15【契約】A が D1 は `ACTIVE`、D2 は `WITHDRAWN` のとき、`divisions` は D1 のみ、D2 のスペースは含まれない。
- AC-P16【契約】A が D1・D2 の両方に `ACTIVE` のとき、`divisions` は 2 件、`spaces` は 6 件。
- AC-P17【契約】参加は部門単位のため、部門に出ていないチームは存在しない。出ている部門が 0 件の A（全部門が参加外）は AC-P14 と同じ 404。他チームの部門のスペースは A の応答に含まれない。
- AC-P18【契約】スペースが 0 件（払い出し前）のとき 200 + `spaces=[]`、`divisions` は存在する。一括解決は実在した行だけを対象にし、存在しない行を判定・応答しない。
- AC-P19【契約】x が `.../teams/A/...` を呼ぶと D1 の 4 件のみ、`.../teams/B/...` では D2 の 4 件のみ（和集合にならない）。x が A の代表でもある場合、`accessLevel` は AC-P34 の一致規則に従う。
- AC-P20【契約】クエリ本数: `SqlIntentCounter`（`backend/src/test/java/com/mannschaft/app/common/visibility/perf/SqlIntentCounter.java`）で、D=1 と D=3、参加チーム数 2 と 40 の組み合わせのすべてで、本 API の発行クエリ本数が**同一の定数上限以下**であることを固定する（上限値は実装時の実測で確定し、D・参加チーム数に依存する式にしない）。
- AC-P21【契約】公開スペース（`isPublic=true`）でも、A の非会員 z は本 API では 404（公開スペースの閲覧は既存経路に従う）。
- AC-P22【契約】主催者向け一覧 `GET /api/v1/tournaments/{tournamentId}/contact-spaces` の回帰: そのスコープのスペースを閲覧できる者（例: a1）は 403 `TOUR_032`、閲覧できない者（例: z）は 404 `TOUR_029`。主催組織 ADMIN は 200。現行の拒否応答が §4.3 の契約と一致していること。
- AC-P33【契約】主催組織 ADMIN o1 が、A の一般会員（a3 と同じ立場）を兼ねる場合、`.../teams/A/...` で全スペースが `POST`。所属を兼ねない o1 は 404 `TOUR_029`。SYSTEM_ADMIN も所属を兼ねなければ 404。
- AC-P34【契約】新設する Gate の一括解決メソッドは、全 (scope, kind) について `requireViewResolvingLevel` の戻り値と一致する（一致しないスペースは両者とも不在）。同一スコープの掲示板とチャットの `accessLevel` は同じ。a1・a3・o1・公開スペースに加え、**呼び出し者が所属しない部門（例: A の a3 に対する、D2 の非公開スペース）を入力に含め、既存 Gate と同じく閲覧不可＝マップに含まれない**ことを固定する。大会全体スコープでの非所属者（z）も同様に含まれない。
- AC-P35【契約】アーカイブ済みのチームの slug は、その会員が呼んでも 404 `TOUR_029`。

**画面（チーム側）**

- AC-P23【E2E】a1 でログインし、`/teams/A/tournaments` の参加大会カードから「連絡スペース」リンクを押すと `/teams/A/tournaments/T/communication` が開き、大会全体・D1 のタブと、掲示板・チャットのタブが見える。観測点: 遷移先 URL と、API 200。
- AC-P24【E2E】a1 は新規スレッド作成ボタンが見え、スレッドを作成できる（投稿 API が 2xx）。a3・a4 はスレッドを閲覧でき、新規スレッド作成ボタンが出ない。観測点: 画面のボタン有無と、一覧 API の `accessLevel`。
- AC-P25【E2E】a3 が掲示板スレッドを開いても返信欄が出ない（`BulletinThreadDetail` の `canReply=false`）。API を直接叩いた返信は 403 `TOUR_031`（観測点: 画面の返信欄の有無と、直接呼びの status・`error.code`）。a1 は返信欄が出て投稿できる。また、VIEW の利用者（a3）が**未ロックの**スレッドを見たとき、返信欄も「このスレッドはロックされています」の表示も出ない（観測点: 画面の表示）。ロック済みスレッドでは、VIEW・POST とも従来どおりロック表示が出る。
- AC-P26【E2E】C の c1 が `/teams/C/tournaments/T/communication` を URL 直打ちすると、「この大会の連絡スペースを利用できません」の状態表示（404 を空白画面や無言のリダイレクトにしない）。z・他テナントのユーザーも同様。観測点: 404 `TOUR_029` と状態表示。
- AC-P27【E2E】未ログインで URL 直打ちするとログイン画面へ。観測点: 遷移先 URL（`auth` middleware）。
- AC-P28【E2E】チャットタブで、`VIEW`（a3）では送信入力が出ない／無効、`POST`（a1）では送信できる（`ChatMessagePanel` の `canSend`）。さらに VIEW（a3）がメッセージのスレッドを開いても、`ChatThreadPanel` に返信入力が出ず、送信要求が発生しない（Enter キー押下でも `sendReply` が呼ばれない）。観測点: 画面の送信 UI と、ネットワークに返信の送信リクエストが出ないこと、a1 の送信 API の 2xx。
- AC-P29【E2E】辞退（`WITHDRAWN`）にしたチームの a1 が再読み込みすると AC-P26 と同じ表示になる。観測点: 404 `TOUR_029` と状態表示。
- AC-P30【FE】一覧取得が 5xx・通信断のとき、エラー通知と再試行導線を出す（空状態を偽装しない）。404 は状態表示で、通知トーストを濫発しない。
- AC-P31【FE】`BulletinThreadDetail`・`ChatMessagePanel`・`ChatThreadPanel` の既存の呼び出し元すべて（`ChatThreadPanel` の呼び出し元は `ChatMessagePanel.vue:522` のみで、`ChatMessagePanel` 経由で全画面に及ぶ。他は`pages/teams/[slug]/bulletin.vue`・`pages/organizations/[slug]/bulletin.vue`・`pages/villages/[id]/bulletin.vue`・`pages/teams/[slug]/chat.vue`・`pages/organizations/[slug]/chat.vue`・`pages/chat/index.vue`・`pages/organizations/[slug]/tournaments/[tId]/communication.vue`）で、新 prop 未指定のまま返信欄・送信 UI が従来どおり出る（既定 true）。
- AC-P36【FE】参加者画面では `can-manage` を渡さない（ピン・削除・ロックの UI が出ない）。

実機 E2E で必須: AC-P01・P03・P04・P07・P09・P10・P23〜P29（ロール横断と URL 直打ち）。契約テスト: AC-P01〜P22・P33〜P35。FE 単体: AC-P30・P31・P36 と、`accessLevel` に応じた出し分け。

### 10.5 FE 画面仕様

- **ページ**: `frontend/app/pages/teams/[slug]/tournaments/[tId]/communication.vue`（新設）。`definePageMeta({ layout: 'team', middleware: 'auth' })`。`fees.vue` と違い `orgId` クエリは要らない。
- **構成**: `PageHeader`（戻り先は `/teams/{slug}/tournaments`）→ 大会名 → スコープ切替（「大会全体」＋応答 `divisions` の部門名）→ 掲示板/チャットのタブ → 本体。主催側 `communication.vue` の骨格を共通コンポーネント（案: `TournamentContactPanel`）に切り出して共用する。共用範囲は `BulletinThreadList` / `BulletinThreadDetail` / `BulletinThreadForm` / `ChatMessagePanel`。主催者専用部分（公開トグル、組織権限）は主催側ページに残す。
- **既存コンポーネントへの追加（後方互換）**:
  - `BulletinThreadDetail.vue` の返信欄は現状 `v-if="!thread.isLocked"` だけで出る（`:235-240`）。`canReply`（boolean、**既定 true**）を追加し、返信欄を `v-if="canReply && !thread.isLocked"` とする。直後の `v-else`（`:241`、「このスレッドはロックされています」）は、`canReply=false` かつ未ロックでもロック表示が出てしまうため、**`v-else-if="thread.isLocked"` に変える**。
  - `ChatMessagePanel.vue` の props（`:16-22`）に送信可否が無い。`canSend`（boolean、**既定 true**）を追加し、false のとき送信入力を出さない（確定事項）。
  - `ChatMessagePanel.vue:522` が表示する `ChatThreadPanel.vue` にも返信入力（`InputText` `:244`・送信ボタン `:257`、送信関数 `sendReply` `:64`）がある。`ChatThreadPanel` にも `canSend`（**既定 true**）を追加し、`ChatMessagePanel` から受けた値を伝える。false のとき返信入力・送信ボタンを出さず、`sendReply` を呼ばない。
- **読み込む API**: 新 EP 1 本のみ。部門一覧 API は呼ばない。チャットを開くときの `GET /chat/channels/{id}` と、掲示板の既存グローバル API は既存のまま。
- **権限による出し分け**: スペースごとの `accessLevel` を使う。新規スレッド作成ボタン（`can-create`）は `POST` のときだけ表示（主催側のように常に出す方式はとらない）。`BulletinThreadDetail` には `:can-reply="accessLevel === 'POST'"`、`ChatMessagePanel` には `:can-send="accessLevel === 'POST'"`。`VIEW` のときは「閲覧のみ」の補足を出す。**`can-manage` は参加者画面では渡さない**。API 上はチーム代表もロック等の操作ができるが、画面では意図的に出さない（範囲外）。
- **読み込み・空・失敗**: 読み込み中は `PageLoading`。404 は「利用できない」状態（理由は特定しない）。`spaces` が 0 件は既存キー `tournament.communication.empty`。その他の失敗はエラー通知＋再試行。`catch {}` で握りつぶさない。部門を切り替えても、先に開いたスレッド・作成ダイアログを持ち越さない。
- **参加大会カードからの導線**: `teams/[slug]/tournaments/index.vue` のカードは全体が単一の `NuxtLink`（組織画面へ）。リンクの入れ子は不正な HTML になるため、カードを「本文リンク」と「下部のアクション行」に分け、後者に `/teams/${teamSlug}/tournaments/${tournamentId}/communication` を置く。`tournamentId` は `identifiers.tournamentId`。**カードは部門ごとに 1 枚出る**（`:139-141` の `v-for` が履歴＝参加行単位）ため、同じ大会で複数部門に出ている場合は同じ連絡スペースへのリンクが重複する。初版は重複を許容し、リンク先は同一（大会単位）。既存リンクの `organizationId` の扱いは別課題。
- **URL**: スコープ・タブ・スレッドの URL 反映は別課題。初版は反映しない。

### 10.6 DDL / 状態遷移

- **DDL 不要**: 既存の `tournament_contact_space`（§2.1）・`tournament_participants`・`memberships` の読み取りのみ。新規カラム・インデックスは持たない（参加の絞り込みは `uq_tp_div_team`・`idx_tp_team`、スペースは `uq_tcs_scope_kind` が使える）。
- **状態遷移なし**: 参加状態は既存の `ParticipantStatus`（`REGISTERED` / `ACTIVE` / `WITHDRAWN` / `DISQUALIFIED`）を読むだけ。`REGISTERED` / `ACTIVE` を参加中、`WITHDRAWN` / `DISQUALIFIED` を参加外とする（§4.1 と同一）。

### 10.7 エラーコード（流用）

| 状況 | status | コード | 備考 |
|------|--------|--------|------|
| 未ログイン | 401 | 既存の認証エラー | §4 の未認証規則 |
| チーム不在・アーカイブ済み・PROVISIONED・非所属・大会不在/論理削除・非参加・参加外 | 404 | `TOUR_029`（`CONTACT_SPACE_NOT_FOUND`） | 本 API では全て同一応答 |
| 閲覧可だが投稿権なし（投稿 API 側） | 403 | `TOUR_031` | 本 API は返さない |
| 公開設定の権限なし（PATCH／主催者向け一覧側） | 403 | `TOUR_032` | 本 API は触らない |
| `TOUR_030` | — | 欠番 | #4643 で送出停止（マージ済み）。再利用しない |

新規エラーコードは不要。

### 10.8 Flyway

不要（DDL 変更なし）。

### 10.9 i18n

既存のキー体系は `frontend/app/locales/{ja,en,zh,ko,es,de}/tournament.json` の `tournament.communication.*`。再利用: `title`・`bulletin`・`chat`・`tournament_space`・`division_space`・**`empty`（0 件表示）**・`load_failed`。新規は次を `tournament.communication.participant.*` として 6 言語に追加する（直書き禁止）。ja の案:

| キー | ja（案） |
|------|---------|
| `tournament.communication.participant.title` | 大会の連絡スペース |
| `tournament.communication.participant.open_link` | 連絡スペースを開く |
| `tournament.communication.participant.view_only_notice` | 閲覧のみです。投稿できるのはチームの代表・副代表と主催者です |
| `tournament.communication.participant.unavailable` | この大会の連絡スペースを利用できません |
| `tournament.communication.participant.unavailable_hint` | 参加中のチームの会員のみ利用できます。チームの参加状況をご確認ください |
| `tournament.communication.participant.load_retry` | 再読み込み |

### 10.10 既知課題・別課題（本節の範囲外・ここでは直さない）

台帳への起票は殿が別途行う。

1. 参加大会カードのリンクが `organizations/${t.organizationId}/...` と組織の数値 ID になっている疑い。組織画面のパスは slug 前提のため解決できない可能性がある（実機で未確認）。
2. 投稿が 403 になったときの通知が原因別に分かれていない。
3. スレッドを開いても URL に反映されない（スコープ・タブも同様）。
4. **既知の制限**: 大会の公開範囲が `MEMBERS_AND_ABOVE`・`ADMINS_AND_ABOVE`・`SCOPE_AFFILIATED` の場合、組織に属さない参加者には、チームの参加大会履歴（`StandingsQueryService.java:117-120` の `canViewTournament` による除外）に大会が出ず、参加大会カードも本節の入口も表示されない。本節の初版は `PUBLIC` / `PARTICIPANTS_ONLY` の大会を前提とする。

### 10.11 非機能

- **性能**: §10.3.5。クエリ本数は D・参加チーム数・スペース数に依存しない定数。
- **後方互換**: 主催者向け API・主催組織側画面は変えない（AC-P22・AC-P31）。新 EP と、既定値つきの prop 追加（`canReply`・`canSend`）のみ。
- **段階導入**: 第 1 段は本節（一覧 API・チーム側ページ・カード導線・Gate の一括解決）。公開スペースの非ログイン導線、未読バッジ、URL 反映、§10.10 の制限 4 の解消は後続。
- **ドキュメント同期**: 実装時に `docs/openapi.json` の再生成と `frontend` の `npm run generate:types` を行う。

### 10.12 要裁可・未確定事項

なし（§10.2 に確定）。残る実装時の確定値は、AC-P20 のクエリ本数の定数上限のみ（実測で決める）。
