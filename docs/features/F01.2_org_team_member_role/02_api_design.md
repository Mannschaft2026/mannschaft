## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/search` | 任意 | チーム公開検索（visibility=PUBLIC のみ対象・名前/地域/テンプレートで検索）|
| GET | `/api/v1/teams/slug-check?slug={slug}` | 必要 | スラッグ使用可否チェック（チーム）。レスポンス: `{ available: boolean, suggestions: string[] }`。レートリミット: 60 req/min/user |
| GET | `/api/v1/organizations/search` | 任意 | 組織公開検索（visibility=PUBLIC のみ対象・名前/地域/種別で検索）|
| GET | `/api/v1/organizations/slug-check?slug={slug}` | 必要 | スラッグ使用可否チェック（組織）。レスポンス: `{ available: boolean, suggestions: string[] }`。レートリミット: 60 req/min/user |
| POST | `/api/v1/organizations` | 必要 | 組織作成 |
| GET | `/api/v1/organizations/{slug}` | 任意 | 組織詳細取得（可視性による）。`{slug}`: 組織のURLスラッグ（例: fc-tokyo-association）|
| PATCH | `/api/v1/organizations/{slug}` | 必要（ADMIN+）| 組織情報更新 |
| DELETE | `/api/v1/organizations/{slug}` | 必要（ADMIN+）| 組織論理削除 |
| GET | `/api/v1/organizations/{slug}/members` | 必要（visibility = PUBLIC は外部閲覧可）| 組織メンバー一覧（直接所属・visibility 依存の認可・返却粒度あり）|
| PATCH | `/api/v1/organizations/{slug}/members/{userId}/role` | 必要（ADMIN）| 組織メンバーロール変更 |
| DELETE | `/api/v1/organizations/{slug}/members/{userId}` | 必要（ADMIN）| 組織メンバー除名 |
| POST | `/api/v1/organizations/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン発行（※INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/organizations/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン一覧（※MANAGE_INVITE_TOKENS 権限必要）|
| DELETE | `/api/v1/organizations/{slug}/invite-tokens/{tokenId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織招待トークン失効（※MANAGE_INVITE_TOKENS 権限必要）|
| POST | `/api/v1/teams` | 必要 | チーム作成 |
| GET | `/api/v1/teams/{slug}` | 任意 | チーム詳細取得（可視性による）。`{slug}`: チームのURLスラッグ（例: fc-tokyo）|
| PATCH | `/api/v1/teams/{slug}` | 必要（ADMIN+）| チーム情報更新 |
| DELETE | `/api/v1/teams/{slug}` | 必要（ADMIN+）| チーム論理削除 |
| GET | `/api/v1/teams/{slug}/members` | 必要（visibility = PUBLIC / ORGANIZATION_ONLY は外部閲覧可）| チームメンバー一覧（visibility 依存の認可・返却粒度あり）|
| GET | `/api/v1/teams/{slug}/members/all` | 一覧と同じ（visibility 依存）| チームメンバー**全件**一括取得（ページングなし。CMP-260912-1525）|
| PATCH | `/api/v1/teams/{slug}/members/{userId}/role` | 必要（ADMIN）| メンバーロール変更 |
| DELETE | `/api/v1/teams/{slug}/members/{userId}` | 必要（ADMIN）| メンバー除名 |
| POST | `/api/v1/teams/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム招待トークン発行（※INVITE_MEMBERS + MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/teams/{slug}/invite-tokens` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム招待トークン一覧（※MANAGE_INVITE_TOKENS 権限必要）|
| DELETE | `/api/v1/teams/{slug}/invite-tokens/{tokenId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 招待トークン失効（※MANAGE_INVITE_TOKENS 権限必要）|
| GET | `/api/v1/invite/{token}` | 必要 | 招待プレビュー（参加前確認）|
| POST | `/api/v1/invite/{token}/join` | 必要 | 招待URLで参加 |
| GET | `/api/v1/teams/{slug}/permission-groups` | 必要（ADMIN）| 権限グループ一覧（`?target_role=DEPUTY_ADMIN\|MEMBER` でフィルタ可）|
| POST | `/api/v1/teams/{slug}/permission-groups` | 必要（ADMIN）| 権限グループ作成（`target_role` で DEPUTY_ADMIN / MEMBER を指定）|
| PATCH | `/api/v1/teams/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 権限グループ更新 |
| DELETE | `/api/v1/teams/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 権限グループ論理削除 |
| PUT | `/api/v1/teams/{slug}/members/{userId}/permission-groups` | 必要（ADMIN）| DEPUTY_ADMIN / MEMBER への権限グループ一括設定（ユーザーのロールに対応する `target_role` のグループのみ割り当て可）|
| GET | `/api/v1/organizations/{slug}/permission-groups` | 必要（ADMIN）| 組織権限グループ一覧（`?target_role=DEPUTY_ADMIN\|MEMBER` でフィルタ可）|
| POST | `/api/v1/organizations/{slug}/permission-groups` | 必要（ADMIN）| 組織権限グループ作成（`target_role` で DEPUTY_ADMIN / MEMBER を指定）|
| PATCH | `/api/v1/organizations/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 組織権限グループ更新 |
| DELETE | `/api/v1/organizations/{slug}/permission-groups/{groupId}` | 必要（ADMIN）| 組織権限グループ論理削除 |
| PUT | `/api/v1/organizations/{slug}/members/{userId}/permission-groups` | 必要（ADMIN）| 組織 DEPUTY_ADMIN / MEMBER への権限グループ一括設定 |
| GET | `/api/v1/teams/{slug}/me/permissions` | 必要 | 自分の実効パーミッション一覧（対象チームでの権限確認用）|
| GET | `/api/v1/organizations/{slug}/me/permissions` | 必要 | 自分の実効パーミッション一覧（対象組織での権限確認用）|
| POST | `/api/v1/teams/{slug}/transfer-ownership-offers` | 必要（ADMIN）| チーム オーナー委譲を打診（承諾型オファー作成・PENDING）|
| POST | `/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept` | 必要（指名相手）| 委譲を承諾（対象→ADMIN、発行者→MEMBER 降格を実行）|
| POST | `/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/decline` | 必要（指名相手）| 委譲を辞退（ロール不変）|
| DELETE | `/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}` | 必要（発行者/ADMIN）| 委譲オファーを取消（ロール不変）|
| POST | `/api/v1/organizations/{slug}/transfer-ownership-offers` | 必要（ADMIN）| 組織 オーナー委譲を打診（team と同一仕様）|
| POST | `/api/v1/organizations/{slug}/transfer-ownership-offers/{offerId}/accept` | 必要（指名相手）| 組織委譲を承諾 |
| POST | `/api/v1/organizations/{slug}/transfer-ownership-offers/{offerId}/decline` | 必要（指名相手）| 組織委譲を辞退 |
| DELETE | `/api/v1/organizations/{slug}/transfer-ownership-offers/{offerId}` | 必要（発行者/ADMIN）| 組織委譲オファーを取消 |
| GET | `/api/v1/permissions?scopeType={TEAM\|ORGANIZATION}&scopeId={id}` | 必要（指定スコープの ADMIN+）| パーミッションカタログ一覧（権限グループの作成・更新で選ぶ権限。DTO は「パーミッションカタログ」節）|
| GET | `/api/v1/me/teams` | 必要 | 自分が所属するチーム一覧（ロール・参加日時付き）|
| GET | `/api/v1/me/organizations` | 必要 | 自分が所属する組織一覧（ロール・参加日時付き）|
| POST | `/api/v1/teams/{slug}/follow` | 必要 | チームをフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/teams/{slug}/follow` | 必要 | フォロー解除（自分の SUPPORTER ロールを削除）|
| GET | `/api/v1/teams/{slug}/blocks` | 必要（ADMIN）| ブロック一覧 |
| POST | `/api/v1/teams/{slug}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| ユーザーをブロック（自己登録禁止・現在のロールも同時除名）|
| DELETE | `/api/v1/teams/{slug}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| ブロック解除 |
| POST | `/api/v1/organizations/{slug}/follow` | 必要 | 組織をフォロー（SUPPORTER 自己登録・招待コード不要）|
| DELETE | `/api/v1/organizations/{slug}/follow` | 必要 | 組織フォロー解除 |
| GET | `/api/v1/organizations/{slug}/blocks` | 必要（ADMIN）| 組織ブロック一覧 |
| POST | `/api/v1/organizations/{slug}/blocks` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ユーザーをブロック |
| DELETE | `/api/v1/organizations/{slug}/blocks/{userId}` | 必要（ADMIN/DEPUTY_ADMIN）| 組織ブロック解除 |
| GET | `/api/v1/organizations/{slug}/members/all` | 必要（ADMIN+）| 組織サブツリーの全メンバー一覧（WITH RECURSIVE で全子組織・子チームを網羅・カスケード通知対象確認用）|
| DELETE | `/api/v1/teams/{slug}/me` | 必要 | 自主退会（ADMIN / DEPUTY_ADMIN / MEMBER が自ら離脱。SUPPORTER は `DELETE /teams/{slug}/follow` を使用）|
| DELETE | `/api/v1/organizations/{slug}/me` | 必要 | 自主退会（ADMIN / DEPUTY_ADMIN / MEMBER が自ら離脱。SUPPORTER は `DELETE /organizations/{slug}/follow` を使用）|
| PATCH | `/api/v1/teams/{slug}/archive` | 必要（ADMIN）| チームを手動アーカイブ（`archived_at = NOW()`。招待トークン失効・以降の書き込み操作ブロック）|
| PATCH | `/api/v1/teams/{slug}/unarchive` | 必要（ADMIN）| チームアーカイブ解除（`archived_at = NULL`。書き込み操作を再開）|
| PATCH | `/api/v1/organizations/{slug}/archive` | 必要（ADMIN）| 組織を手動アーカイブ（`archived_at = NOW()`。招待トークン失効・書き込み操作ブロック）|
| PATCH | `/api/v1/organizations/{slug}/unarchive` | 必要（ADMIN）| 組織アーカイブ解除（`archived_at = NULL`）|
| PATCH | `/api/v1/teams/{slug}/restore` | 必要（SYSTEM_ADMIN）| 論理削除済みチームの復元（`deleted_at = NULL`）|
| PATCH | `/api/v1/organizations/{slug}/restore` | 必要（SYSTEM_ADMIN）| 論理削除済み組織の復元（`deleted_at = NULL`）|
| POST | `/api/v1/organizations/{slug}/team-invites` | 必要（ADMIN）| 組織からチームへ所属招待を送信（body `{teamSlug, groupId?, message?}`。実装済み（2-C）・契約は F01.2.1 §6.5/§10）|
| GET | `/api/v1/organizations/{slug}/team-invites` | 必要（ADMIN）| 送信済み招待一覧（PENDING/ORG_INVITE のみ。実装済み）|
| DELETE | `/api/v1/organizations/{slug}/team-invites/{teamSlug}` | 必要（ADMIN）| 招待取消（PENDING を削除し、ORG_INVITE 方向に24時間の冷却。実装済み）|
| DELETE | `/api/v1/organizations/{slug}/teams/{teamSlug}` | 必要（ADMIN）| 所属チームを除名（ACTIVE を削除し、チームの加盟操作者へ通知。実装済み・2-D）|
| GET | `/api/v1/organizations/{slug}/teams` | 必要 | 組織に所属するチーム一覧（ACTIVE のみ）。各要素に `teamGroup`、クエリ `teamGroupId` / `unassigned` 絞り込みを追加済み（F01.2.1 §10.1。実装済み） |
| GET | `/api/v1/teams/{slug}/organizations` | 必要 | チームが所属する組織一覧（ACTIVE のみ。1チームは複数の組織に同時加盟でき、全件を返す）。各要素に `teamGroup`（自チームの所属グループ名）を追加済み（F01.2.1 §10.1。実装済み） |
| GET | `/api/v1/organizations/{slug}/ancestors` | 任意 | 上位組織チェーン取得（root → 親の順。`hierarchy_visibility` を尊重）|
| GET | `/api/v1/organizations/{slug}/children` | 任意 | 下位組織一覧（直近の子のみ・`visibility` で可視範囲フィルタ）|
| GET | `/api/v1/teams/{slug}/org-invites` | 必要（MANAGE_ORG_AFFILIATION。ADMIN は常に保持）| 受信した組織招待一覧（PENDING/ORG_INVITE のみ。実装済み）|
| POST | `/api/v1/teams/{slug}/org-invites/{membershipId}/accept` | 必要（MANAGE_ORG_AFFILIATION）| 組織招待を承諾（PENDING → ACTIVE。実装済み）|
| POST | `/api/v1/teams/{slug}/org-invites/{membershipId}/reject` | 必要（MANAGE_ORG_AFFILIATION）| 組織招待を拒否（PENDING を削除し、再招待に30日の冷却。body `{block?}`。実装済み）|
| DELETE | `/api/v1/teams/{slug}/organizations/{orgSlug}` | 必要（MANAGE_ORG_AFFILIATION）| チームが組織から自主離脱（ACTIVE を削除し、組織 ADMIN へ通知。実装済み・2-D）|
| GET | `/api/v1/invite/{token}/qr` | 不要 | 招待QRコード画像取得（PNG）|
| PATCH | `/api/v1/organizations/{slug}/profile` | 必要（ADMIN / DEPUTY_ADMIN※）| 組織プロフィール拡張項目の一括更新（homepage_url / established_date / philosophy / profile_visibility）。※MANAGE_ORGANIZATION 権限必要 |
| PATCH | `/api/v1/teams/{slug}/profile` | 必要（ADMIN / DEPUTY_ADMIN※）| チームプロフィール拡張項目の一括更新。※MANAGE_TEAM 権限必要 |
| GET | `/api/v1/organizations/{slug}/officers` | 任意 | 組織役員一覧（可視性と `is_visible` に基づくフィルタ）|
| POST | `/api/v1/organizations/{slug}/officers` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員追加（最大50件）|
| PATCH | `/api/v1/organizations/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員編集（氏名・役職・is_visible）|
| DELETE | `/api/v1/organizations/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員削除（物理削除）|
| PUT | `/api/v1/organizations/{slug}/officers/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| 役員並び替え（display_order の一括更新）|
| GET | `/api/v1/teams/{slug}/officers` | 任意 | チーム役員一覧 |
| POST | `/api/v1/teams/{slug}/officers` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員追加 |
| PATCH | `/api/v1/teams/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員編集 |
| DELETE | `/api/v1/teams/{slug}/officers/{officerId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員削除 |
| PUT | `/api/v1/teams/{slug}/officers/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| チーム役員並び替え |
| GET | `/api/v1/organizations/{slug}/custom-fields` | 任意 | 組織カスタムフィールド一覧（可視性と `is_visible` に基づくフィルタ）|
| POST | `/api/v1/organizations/{slug}/custom-fields` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド追加（最大20件）|
| PATCH | `/api/v1/organizations/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド編集 |
| DELETE | `/api/v1/organizations/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド削除 |
| PUT | `/api/v1/organizations/{slug}/custom-fields/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| カスタムフィールド並び替え |
| GET | `/api/v1/teams/{slug}/custom-fields` | 任意 | チームカスタムフィールド一覧 |
| POST | `/api/v1/teams/{slug}/custom-fields` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド追加 |
| PATCH | `/api/v1/teams/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド編集 |
| DELETE | `/api/v1/teams/{slug}/custom-fields/{fieldId}` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド削除 |
| PUT | `/api/v1/teams/{slug}/custom-fields/reorder` | 必要（ADMIN / DEPUTY_ADMIN※）| チームカスタムフィールド並び替え |

> **チーム加盟の書き込み API（F01.2.1 で正式化・2026-09-25）**: 上表の招待系に加えて、チーム→組織の加盟申請（`POST/GET /teams/{slug}/org-applications`、`DELETE /teams/{slug}/org-applications/{membershipId}`）、組織側の申請一覧・承認・拒否（`/organizations/{slug}/team-applications/**`）、申請受付設定（`/organizations/{slug}/team-affiliation-settings`）、申請フォーム（`GET /organizations/{slug}/team-application-form`）、再申請の制限一覧・解除、チームグループ（`/organizations/{slug}/team-groups/**`・割当）を定義した。契約（フィールド・型・null 可否・認可・ページング・エラーコード）の正本は [F01.2.1 §10](../F01.2.1_org_team_groups.md) とし、ここには重複して書かない。加盟の書き込み API・申請受付設定・チームグループ・割当・宛先プレビューは F01.2.1 の実装戦役（CMP-261011-0035）で**すべて実装済み**（2026-10-14 までにマージ）。一覧のページングは `PagedResponse`（`meta: {total, page, size, totalPages}`、`page` は 0 始まり、`size` 既定 20・上限 100）。

### リクエスト／レスポンス仕様

#### パーミッションカタログ（`GET /api/v1/permissions`）

権限グループの作成・更新（`permissionIds`）で選べる権限の一覧。**権限グループの作成・更新 API が受け付ける `permissionIds` の集合と同じ**を、名前順で返す。

- 対象は `permissions.scope` が `TEAM` または `ORGANIZATION` の権限。`PLATFORM` 分類（プラットフォーム運営の権限）は含めず、作成・更新側も同じ判定で拒否する（`ROLE_007`）。さらに、権限と `targetRole` の組合せが `assignableTargetRoles` に反する作成・更新（例: 課金権限を含む MEMBER 向けグループ）は **400（`COMMON_001`）** で拒否する。既存データは変更しないが、そのようなグループは更新時に同じ検証で拒否される。判定は `PermissionCatalogService.isGroupAssignable` の 1 箇所で、カタログと作成・更新の検証が共用する。
- `scope` 列は認可判定では参照されない分類列で、TEAM と ORGANIZATION の両方で使う権限は主たるスコープ 1 値で登録されている（`docs/security/README.md` §4.3 ルール 3。例: 組織 DEPUTY_ADMIN への委任に使う `MANAGE_CONTENT` は TEAM 分類）。そのため、`scopeType` がチームでも組織でも返す集合は同じ。`scopeType` は認可（どのスコープの ADMIN+ か）の確認にだけ使う。

| クエリ | 型 | 必須 | 説明 |
|---|---|---|---|
| `scopeType` | String | ✅ | `TEAM` または `ORGANIZATION`。`PLATFORM` や未定義値は 400（`COMMON_001`）。認可チェックの前に検証する |
| `scopeId` | Long | ✅ | 対象チーム/組織の内部 ID。認可は**このスコープの ADMIN+**（`AccessControlService.checkAdminOrAbove`）。満たさなければ 403 |

レスポンス（200 OK）: `{"data": [PermissionCatalogItemResponse, ...]}`

| フィールド | 型 | 説明 |
|---|---|---|
| `id` | Long | 権限 ID。権限グループ作成・更新リクエストの `permissionIds` に渡す |
| `name` | String | 権限名（コード。例: `MANAGE_ORG_AFFILIATION`）|
| `displayName` | String | 表示名 |
| `scope` | String | 権限の対象スコープ（`TEAM` / `ORGANIZATION`）|
| `adminOnlyGrantable` | Boolean | スコープの ADMIN だけが権限グループへ含められる権限か（`PermissionGroupService.ADMIN_ONLY_GRANTABLE_PERMISSIONS`。DEPUTY_ADMIN が含めると作成・更新・割当が 403）|
| `assignableTargetRoles` | String[] | この権限を含められる権限グループの対象ロール（`DEPUTY_ADMIN` / `MEMBER`）。通常の権限は両方、**課金権限（`MANAGE_TEAM_BILLING` / `MANAGE_ORGANIZATION_BILLING`）は `DEPUTY_ADMIN` のみ**（`BillingPermissionGroupGuard` は課金権限の割当先を DEPUTY_ADMIN に限定するため、MEMBER 向けに含めると誰にも割り当てられないグループになる）。定義は `BillingPermissionGroupGuard.assignableTargetRoles` の 1 箇所で、カタログと作成・更新の検証が共用する |

#### メンバー一覧の `permissionGroups`（`GET /api/v1/teams/{slug}/members` と `/members/all`）

対象チームの ADMIN/DEPUTY_ADMIN 割当を持つ閲覧者（`AccessControlService.isAdminOrAbove`。権限グループ一覧 API と同じ判定。所属の無い SYSTEM_ADMIN 単独は含まない）が閲覧したときだけ、各メンバーに `permissionGroups: [{ "id": 1, "name": "受付・安否確認" }]` を載せる（下記「返却フィールドのロール別制限」）。
DEPUTY_ADMIN・MEMBER 以外のロール、および未割当のメンバーは空配列。一般メンバー・非メンバー向けの応答にはフィールド自体を出さない。割当はページ内（または全件）のユーザーぶんを 1 回の一括取得で解決し、当該チームの権限グループ以外（他チームのグループ）は含めない。
`PUT /api/v1/teams/{slug}/members/{userId}/permission-groups` は割当の**全置換**なので、クライアントはこの値で現在の割当を初期化してから差分を送る。

#### `POST /api/v1/teams`

**リクエストボディ**
```json
{
  "slug": "fc-mannschaft",
  "name": "FCマンシャフト",
  "nickname1": "マンシャフト",
  "nickname2": null,
  "template": "SPORTS",
  "prefecture": "東京都",
  "city": "渋谷区",
  "description": "東京を拠点とするサッカーチーム",
  "visibility": "PUBLIC"
}
```

> **`slug` フィールド（正準仕様・村方式）**: チーム作成時に**ユーザーが任意の slug を必須入力する**（村 `F17.1` と同じ UX）。
> - 形式: `^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$`（3〜30文字・英小文字／数字／ハイフン）・グローバル一意・予約語禁止（§6「slug 正準仕様」参照）。
> - FE は `name` から ASCII 変換した候補を**プレフィル（編集可能）**として初期表示してよいが、最終的な slug はユーザーが確定する。サーバ側の**強制連番付与（`team-000017`）は廃止方針**。
> - 作成前に `GET /api/v1/teams/slug-check?slug={slug}` でリアルタイムに重複確認できる。
> - エラー: 形式不正 → 400 `TEAM_SLUG_INVALID` / 重複 → 400 `TEAM_SLUG_TAKEN` / 予約語 → 400 `TEAM_SLUG_RESERVED`。

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "slug": "fc-mannschaft",
    "name": "FCマンシャフト",
    "visibility": "PUBLIC",
    "member_count": 1,
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

> 組織作成（`POST /api/v1/organizations`）も同一仕様（slug をユーザー任意入力・必須・エラーコードは `ORG_SLUG_INVALID` / `ORG_SLUG_TAKEN` / `ORG_SLUG_RESERVED`）。

---

#### 組織作成ダイアログの公開範囲（2026-10-07）

組織作成の選択値は BE 契約どおり `PUBLIC` / `PRIVATE`。非公開ラベルは既存6言語の `label.visibilityPrivate` を使用する。
旧組織下書きの `MEMBERS_AND_ABOVE` は復元／送信境界だけで `PRIVATE` へ移行する。
チームの4段階は変更せず、作成対象の種類を切り替えたときは公開範囲を初期値 `PUBLIC` に戻し、別種の enum を持ち越さない。

#### `GET /api/v1/teams/search`

`visibility = PUBLIC` のチームを検索する。未認証でも利用可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `q` | String | — | チーム名の部分一致検索（`name` / `nickname1` / `nickname2` を OR 検索）|
| `prefecture` | String | — | 都道府県でフィルタ |
| `city` | String | — | 市区町村でフィルタ |
| `template` | String | — | テンプレート種別でフィルタ（例: `SPORTS`）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `20` | 1ページ件数（最大50）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "FCマンシャフト",
      "nickname1": "マンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp",
      "prefecture": "東京都",
      "city": "渋谷区",
      "template": "SPORTS",
      "member_count": 24,
      "supporter_enabled": true
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "size": 20,
    "has_next": true
  }
}
```

> - `visibility = PUBLIC` のチームのみ返す（GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE は対象外）
> - 論理削除済み・アーカイブ済みチームは常に除外
> - ソート: 名前昇順（デフォルト）
> - `member_count` はフォロワー（SUPPORTER）を含む全メンバー数

---

#### `GET /api/v1/organizations/search`

`visibility = PUBLIC` の組織を検索する。未認証でも利用可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `q` | String | — | 組織名の部分一致検索（`name` / `nickname1` / `nickname2` を OR 検索）|
| `prefecture` | String | — | 都道府県でフィルタ |
| `city` | String | — | 市区町村でフィルタ |
| `org_type` | String | — | 組織種別でフィルタ（`NONPROFIT` / `FORPROFIT`）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `20` | 1ページ件数（最大50）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "nickname1": null,
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "prefecture": "東京都",
      "city": null,
      "org_type": "NONPROFIT",
      "member_count": 150,
      "supporter_enabled": true
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 20,
    "has_next": false
  }
}
```

> - `visibility = PUBLIC` の組織のみ返す（PRIVATE は対象外）
> - 論理削除済み・アーカイブ済み組織は常に除外
> - ソート: 名前昇順（デフォルト）

---

#### `GET /api/v1/organizations/{slug}` / `GET /api/v1/teams/{slug}` のサポーター数（CMP-261004-1942）

組織・チーム詳細のレスポンスは、ヘッダの「サポーター ◯人」表示用に `social.supporterCount` を返す（組織とチームで同形）。

```json
{
  "data": {
    "slug": "fc-tokyo-association",
    "social": { "supporterCount": 12 }
  }
}
```

> - `supporterCount` は `memberships` のうち `role_kind = SUPPORTER` かつ `left_at IS NULL`（退会していない）の行数。MEMBER・退会済み・申請中（`supporter_applications` の PENDING）は数えない。0 人でも `0` を返す（null・欠落にしない）
> - チームは従来から `social.teamFriendCount` / `social.supporterCount` を返している。組織は CMP-261004-1942 で `social.supporterCount` を追加した（組織に `teamFriendCount` は無い）
> - 組織側の集計は membership ドメインの `MembershipService#countActiveSupporters` で行い、`OrganizationService` のトランザクションの**外**にある `OrganizationDetailFacade`（`@Transactional` なし）が組織の応答へ合成する（organization のトランザクションから membership の Repository へ届かせない。D-3T・原則 5／`MembershipRepository` を直接注入しない。D-5）。`GET` に加え、同じ `OrganizationResponse` を返す `PATCH /api/v1/organizations/{slug}`・`PUT /api/v1/organizations/{slug}/slug` の応答も同じ Facade を通るので `social.supporterCount` が入る。組織作成（`POST`）の応答は作成直後のため集計せず `0`
> - 詳細 GET は slug をキーに Valkey へ 10 分キャッシュされる（`org-detail` / `team-detail`）。**組織のサポーター数はキャッシュの外**で毎回数えるため、応援・解除は即時に反映される（`org-detail` にはメンバー数など所属に依存する他の値が残る）。チームのサポーター数は `team-detail` に含まれる。応援・解除・申請の承認／一括承認・入退会など、`MembershipChangedEvent` を発火する所属変更が**コミットされた後**に、組織は `OrganizationDetailCacheMembershipListener`、チームは `TeamDetailCacheMembershipListener` が当該スコープの slug **1 件だけ**をキャッシュから消す（`allEntries` は使わない）。slug の解決はレプリカ遅延を避けるため primary を読む（`readOnly` を付けない）。これにより次の詳細 GET は実数を返す
> - ロールバックされた変更（例: 一括承認の途中で不正な申請 ID があり全件取り消し）では AFTER_COMMIT のため evict は発火せず、温めたキャッシュ（＝DB と同じ旧値）が残る
> - Valkey 障害で evict が失敗しても、本番の FailOpen キャッシュ層が例外をログに残して握るため、応援・解除の応答とコミット済みデータには影響しない（キャッシュは TTL 10 分で自然収束）
> - 申請中・申請取消・却下は所属を変えないため人数も変わらない（キャッシュも消さない）
> - 範囲外: ヘッダの「メンバー ◯人」（`metadata.memberCount`）の数え方は別戦役で扱う（`docs/task-list.md` 参照）

---

#### `GET /api/v1/teams/{slug}/members`

**認可ルール（visibility 依存）**

| チーム visibility | アクセス可能なユーザー |
|------------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `GUESTS_AND_ABOVE` | チームメンバー（任意ロール）が閲覧可（GUEST 以上）|
| `SUPPORTERS_AND_ABOVE` | チームのサポーター以上のロールを持つメンバーが閲覧可 |
| `MEMBERS_AND_ABOVE` | チームの正規メンバー以上のロールを持つメンバーのみ。それ以外は 403 |

**返却フィールドのロール別制限**

| 呼び出し者の区分 | 返却フィールド |
|-----------------|--------------|
| ADMIN / DEPUTY_ADMIN（チームメンバー）| 全フィールド（`user_id`, `display_name`, `icon_url`, `role`, `permission_groups`, `joined_at`）|
| MEMBER / SUPPORTER / GUEST（チームメンバー）| 基本プロフィール（`user_id`, `display_name`, `icon_url`, `role`）— `permission_groups` / `joined_at` は返さない |
| 非メンバー（PUBLIC への外部アクセス）| 基本プロフィールのみ（MEMBER と同内容）|

> - `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済み）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列
> - 支払い状況・連絡先等の個人情報は本エンドポイントには含めない（F04 参照）。SUPPORTER が自身の関連メンバー（子など）の詳細を閲覧する機能は F04 で設計する

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `role` | String | — | ロールでフィルタ（例: `MEMBER`, `DEPUTY_ADMIN`。カンマ区切りで複数指定可: `MEMBER,GUEST`）|
| `q` | String | — | 名前の部分一致検索（`last_name` / `first_name`（実名）および `display_name` を OR 検索）|
| `sort` | String | `joined_at_asc` | ソート順。`joined_at_asc` / `joined_at_desc` / `name_asc` / `name_desc` / `role_asc` / `role_desc` |
| `cursor` | String | — | 次ページ取得用カーソル（前回レスポンスの `meta.next_cursor` を指定）|
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ デフォルトソート: `joined_at`（参加日時）昇順。

ADMIN / DEPUTY_ADMIN が取得した場合（全フィールド）:
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "role": "DEPUTY_ADMIN",
      "permission_groups": [
        {"id": 1, "name": "受付・安否確認"}
      ],
      "joined_at": "2026-03-01T10:00:00Z"
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "size": 50,
    "has_next": true
  }
}
```

MEMBER / SUPPORTER / GUEST またはチーム非メンバー（PUBLIC / ORGANIZATION_ONLY）が取得した場合（基本プロフィールのみ）:
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "role": "DEPUTY_ADMIN"
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `visibility = PRIVATE` かつ呼び出し者がチームメンバーでない / `visibility = ORGANIZATION_ONLY` かつ呼び出し者が当該チームのメンバーでも所属組織のメンバーでもない |
| 404 | チームが存在しない / 論理削除済み |

---

#### `GET /api/v1/teams/{slug}/members/all`（CMP-260912-1525）

チームの**全メンバーを 1 レスポンスで**返す。ページングパラメータは取らない。

**なぜページング経路と別に要るのか**

`TeamService#getMembers` は 1 ページ要求ごとに `user_roles` と `memberships` を
**スコープ全件走査**して重複排除と OQ-2 優先度解決を行う。どの行が何位になるかは
全件見ないと決まらないため、この走査はページぶんに絞り込めない。したがって
「全員を必要とする画面」（時給設定など）が全ページをめくると、総走査量はメンバー数 N に対して
`N × ceil(N / ページサイズ)` になる。ページサイズを大きくしても並列を直列にしても総量は変わらない。

本 EP は走査をちょうど 1 回に固定する。表示名・アバターの実体化とカレンダー色の解決も
全員ぶんをまとめて 1 クエリずつで行うため、総処理量は N に比例する。

**認可**: ページング経路 `GET /api/v1/teams/{slug}/members` と**同一**の visibility ラダー
（`ContentVisibilityChecker#assertCanView`）。返却項目も同一であり、一括化によって
新たに露出する情報は無い（ページングでも全ページをめくれば同じ集合が得られる）。

**集約規則・並び順**: ページング経路と同一（OQ-2 優先度。同じ
`queryMemberIdentities` → `hydrate` の順路を、切り出しを挟まずに通しているだけ）。

**レスポンス（200 OK）** — `meta` は付かない（`ApiResponse` ラッパー）。
```json
{
  "data": [
    { "userId": 42, "displayName": "田中太郎", "avatarUrl": null,
      "roleName": "DEPUTY_ADMIN", "joinedAt": "2026-03-01T10:00:00", "calendarColor": null }
  ]
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 可視性レベル未満（非メンバー等） |
| 404 | チームが存在しない / 論理削除済み |

---

#### `POST /api/v1/teams/{slug}/invite-tokens`

**リクエストボディ**
```json
{
  "role_id": 4,
  "expires_in": "7d",
  "max_uses": 50
}
```

> `expires_in`: `1d` / `7d` / `30d` / `90d` / `unlimited`（`unlimited` は `expires_at = NULL`）

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 10,
    "token": "550e8400-e29b-41d4-a716-446655440000",
    "invite_url": "https://mannschaft.app/invite/550e8400-e29b-41d4-a716-446655440000",
    "role": "MEMBER",
    "expires_at": "2026-03-08T10:00:00Z",
    "max_uses": 50,
    "used_count": 0
  }
}
```

---

#### `GET /api/v1/invite/{token}`（認証必須）

現在の `SecurityConfig` と `InviteController` に従い、プレビューは認証後に取得する。未ログインの招待画面はAPIを呼ばず、ログイン・登録を案内する。ログインには招待URL全体を `redirect` で渡す。登録時もこの復帰先を確認メール送信案内のログイン導線まで引き継ぎ、チーム・組織のトークンをβ登録用 `inviteToken` として送らない。確認メールを別タブで直接開いた場合の復帰先永続化は行わない。

**レスポンス（200 OK）** — `InvitePreviewResponse` の生成型を正本とする。

```json
{
  "data": {
    "targetType": "TEAM",
    "targetName": "FCマンシャフト",
    "roleName": "MEMBER",
    "valid": true
  }
}
```

- `targetType`: `TEAM` または `ORGANIZATION`。組織招待も同じ形状。
- `targetName`: 対象が削除された場合は `null` になり得る。画面は参加先の代替文言を表示する。
- 参加先ID・アイコン・説明・有効期限は現在のプレビュー応答に含まない。

#### 招待付き新規登録の保留承認（CMP-261107-1833）

招待リンクから新規登録した利用者は、登録時点では参加しない。`POST /api/v1/auth/register` の `inviteToken` が
共有招待（宛先なし）を指していれば、users と同じトランザクションで保留（`registration_pending_invites`。招待トークンの
ハッシュと招待先だけを持ち、平文は保存しない）を1件作る。宛先付き（承諾型）招待での登録は `AUTH_083`（403）で拒否し、
users も作らない。存在しない招待はベータ制限 OFF なら保留を作らず登録だけ通す。

メール確認（18歳未満は保護者同意）・ログインの後、本人が次の API で承認・辞退する。利用者は SecurityContext のみから取り、
パスに ID を持たない（他人の保留に触れられない）。応答は招待のハッシュを含まない。保護者同意待ちのトークンは
`AUTH_070`（403）で遮断される。

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/v1/me/pending-invite` | 自分の保留。無ければ `data: null`。`PENDING` のまま本人が既に在籍していれば（`/invite/<token>` など別経路で参加済み）、`JOINED` へ確定して返す（条件付き UPDATE。`invite_token_claims`・`used_count` には触れない）。在籍していなければ招待の有効性を DB 時刻で判定し、失効・期限切れなら `EXPIRED`（`failure_code=ROLE_002`）、上限到達なら `EXPIRED`（`ROLE_003`）へ確定して返す（下記「参照時の期限判定」） |
| POST | `/api/v1/me/pending-invite/accept` | 承認して参加。応答 `{status}`（`JOINED`/`EXPIRED`/`UNAVAILABLE`） |
| POST | `/api/v1/me/pending-invite/decline` | 辞退（`PENDING`→`DECLINED`。共有招待そのものは失効させない） |
| POST | `/api/v1/me/pending-invite/reissue-request` | 失効した招待の再発行を招待権限者へ要請。応答 `{alreadyRequested}` |
| POST | `/api/v1/me/pending-invite/dismiss` | 失効・見送りの案内を閉じる（`EXPIRED`/`REISSUE_DECLINED`→`DISMISSED`）。依頼済み（`REISSUE_REQUESTED`）は 409 `AUTH_085`（閉じても依頼の取り下げにならない） |

GET の応答: `id`・`status`・`scopeType`・`scopeName`・`roleName`・`joinable`（公開・非アーカイブ・非 PROVISIONED）・
`publicPath`（`joinable` のときだけ `/teams/{slug}` または `/organizations/{slug}`。参加申し込みへの導線）・
`reissueRequestedAt`・`retryAvailableAt`（`ACCEPTING` のときだけ。処理開始から5分後）。

状態: `PENDING`→`ACCEPTING`→`JOINED`/`EXPIRED`/`UNAVAILABLE`（原因不明の失敗は `PENDING` に戻す）、
`PENDING`→`DECLINED`、`PENDING`→`EXPIRED`（参照時の期限判定）、`EXPIRED`→`REISSUE_REQUESTED`、
`REISSUE_REQUESTED`→`PENDING`（招待権限者の再招待）、`REISSUE_REQUESTED`→`REISSUE_DECLINED`（招待権限者の見送り）、
`REISSUE_REQUESTED`→`JOINED`（再招待の時点で在籍済み）、`EXPIRED`/`REISSUE_DECLINED`→`DISMISSED`、
未終了（`PENDING`/`ACCEPTING`/`EXPIRED`/`REISSUE_REQUESTED`）→`DISCARDED`（7日間メール未確認の掃除・保護者全員の拒否）。
`REISSUE_DECLINED` は未終了に含めない（破棄の対象外）。

状態の集合（CMP-261107-1833 PR3）: 再依頼できる `REISSUABLE`={`EXPIRED`,`REISSUE_REQUESTED`}（`reissue-request`）と、
閉じられる `DISMISSIBLE`={`EXPIRED`,`REISSUE_DECLINED`}（`dismiss`）を分ける。依頼済み（`REISSUE_REQUESTED`）は閉じられない
（実機 D-1・殿の裁定。依頼の取り下げは機能として用意しない。dismiss は他の状態不一致と同じ 409 `AUTH_085` で、状態は変わらず管理者の一覧に残る）。見送られた依頼は
閉じる前でも後でも再依頼できない（409 `AUTH_085`）。

参照時の期限判定（PR3 設計 g）: GET は A-1 の在籍判定の後、`PENDING` なら保留が指す招待の有効性を DB の現在時刻で判定する
（`InviteAcceptanceFacade#findInviteValidity`）。`expires_at` ちょうどは有効で、直後は失効。失効・期限切れは `ROLE_002`、
上限到達は `ROLE_003` を `failure_code` に記録して条件付き UPDATE で `EXPIRED` にし、`joinable`・`publicPath` の案内を付ける。
再招待の仮発行中（`revoked_at = created_at` かつ作成から60秒以内）は処理中として `PENDING` のまま返し、承認は `AUTH_086`（409）。
60秒を過ぎた仮発行は失効扱い（`EXPIRED`・`ROLE_002`）。

- 承認は `PendingInviteService`（TX なし）→ `PendingInviteStore`（auth の短TX。条件付き UPDATE で `ACCEPTING` を取る）→
  `InviteAcceptanceFacade#joinByInviteTokenHash`（通常の招待承諾と同じ短TXの連鎖）。使用回数・claim・監査は通常の承諾と同等。
- 結果の写像: 成功・在籍済み（`TEAM_003`）・参加後のフォルダ配置失敗は `JOINED`、失効・期限切れ（`ROLE_002`）・上限到達
  （`ROLE_003`）は `EXPIRED`、ブロック（`TEAM_004`）・特権ロール（`ROLE_009`）は `UNAVAILABLE`。承認前に別経路で在籍済みなら
  招待を使わず `JOINED`。
- 二重押し・並行: `ACCEPTING` は1つの attempt だけが取れる。負け側は `JOINED` 済みなら 200 `JOINED`、処理中なら `AUTH_086`（409）。
  `ACCEPTING` のまま5分を超えたら新しい attempt で取り直せ、古い attempt の完了・差し戻しは何も書かない。
- FE（`components/auth/PendingInviteDialog.vue`、`layouts/default.vue` の `<ClientOnly>` 内）: ログイン後（`authStore.user` あり）に GET を1回
  取得する。`AUTH_070` は黙って待ち、保護者承認後のトークン更新（`parental-consent/pending.vue` が `performTokenRefresh` 完了後に遷移）で取り直す。
  `PENDING` は「参加する／断る／あとで」（あとでは `sessionStorage` の `mannschaft.pendingInvite.later` に保留 ID を記録）、
  `EXPIRED`/`REISSUE_REQUESTED` は失効案内（`joinable` なら `publicPath` へのリンク、非公開なら再招待依頼）、`ACCEPTING` は処理中表示で
  `retryAvailableAt` 経過後に再試行を有効化（`setTimeout` で開いたままでも有効化）、`UNAVAILABLE` は「参加できません」の案内で、閉じたら
  `localStorage` の `mannschaft.pendingInvite.unavailableSeen` に保留 ID を記録して次回以降出さない（BE はこの状態の dismiss を受け付けない）。
  `invite/[token]` の未ログイン「新規登録」は `/register?invite=<token>` へ遷移し、`AUTH_083` は専用トースト（`auth.register.invite_targeted_*`）。
- 途中失敗の復旧: 前回の承認がロール付与の前後で失敗して同じ利用者の claim（`CLAIMED`）が残っていれば、次の承認は
  使用回数を進めずにその claim を再開する（`InviteAcceptanceWriter#claimOrResume`）。
- claim の付与印（`invite_token_claims.grant_committed_at`）: `ACCEPTING` が5分を超えて取り直されると、1つの claim を新旧の
  attempt が共有しうる。claim を戻す（`RELEASED`・使用回数-1）か確定する（`GRANTED`）かは、在籍の有無からの推測ではなく
  付与印で決める。付与印はロール付与（T7。`MembershipGrantService#grantRole`）が、user 行の次に claim 行をロックして判定し、
  付与と同じトランザクションで立てる（付与がロールバックされれば印も残らない）。
  - 付与印のある claim はどの経路でも戻さない（戻しの条件付き UPDATE が「印なし」を条件にする）。再開した claim に印があれば、
    招待がその後に失効・期限切れになっていても付与は行わず確定（T9）と配置だけ行う。
  - T7 が在籍済み（`ALREADY_JOINED`）を返したとき、claim に印が無ければ在籍は別経路（参加申請の承認・別の招待など）なので
    claim を戻して 409 `TEAM_003`（保留は `JOINED`）。印があれば別 attempt がこの claim で付与済みなので確定する。
  - 承認前に在籍済みのとき（`settleOrphanClaim`）も同じで、残った claim に印があれば確定、無ければ戻す。
  - T7 の時点で claim が既に戻されていれば（別 attempt が失効を検出して戻した）付与せず `ROLE_002`。
  - 付与印の導入（V266）前から `CLAIMED` だった旧データは「付与されたか不明」として `legacy_grant_unknown_at` で区別し、
    自動では戻さない（使用回数を戻さない安全側。戻すと上限を超えて招待を使わせうる）。在籍済みなら確定し、
    在籍していなければ再承認の付与で付与印が立つ。判定に掛かって参加できない場合も `CLAIMED` のまま残す（監視対象）。
  - 印の受け渡しは `InviteClaimGrantScope`（同一スレッドの呼び出し範囲）で行い、`grantRole` の署名と D-3T の入口は変えない。
    参加申請承認（`grantMemberRole`）は claim に触れない。
- 再招待要請: `EXPIRED`/`REISSUE_REQUESTED` かつ `joinable=false` のときだけ。公開の招待先は 409 `AUTH_085`（参加申し込みへ誘導）。
  7日に1回だけ招待権限者（ADMIN、または `INVITE_MEMBERS` 権限を持つ DEPUTY_ADMIN）へ通知 `INVITE_REISSUE_REQUESTED` を送り、
  期間内の再押下は通知せず `alreadyRequested: true`。通知は受信者の言語で、本文に要請者の表示名（メールアドレスは載せない）と招待先の名前を入れ、遷移先は対象の招待管理画面の再招待依頼の欄（`/teams/{slug}/invites#reissue-requests` 等）。
  7日は前回の依頼時刻からの経過で数え、ちょうど7日以内は通知しない。再招待で `reissue_requested_at` は NULL に戻るので、
  再発行した招待が失効した後は7日を待たずに再依頼できる。
- エラー: `AUTH_084`（404・保留なし）、`AUTH_085`（409・状態不正）、`AUTH_086`（409・処理中）。

#### 再招待依頼の受け手側（CMP-261107-1833 PR3）

招待権限者（ADMIN、または `INVITE_MEMBERS` 権限を持つ DEPUTY_ADMIN。`InviteIssuerPolicy#canIssue`）が、
失効した招待から登録した利用者の再招待依頼（`REISSUE_REQUESTED`）を一覧し、再招待または見送りを行う。
`InviteReissueRequestController` が TEAM 用3本と ORGANIZATION 用3本を別メソッドで持ち（金型: `JoinRequestController`）、
`InviteReissueRequestService`（TX なし）へ委譲する。いずれも `@AuthorizedInService`＋`@AlwaysReachable(CORE)`。

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/v1/{teams\|organizations}/{slug}/invite-reissue-requests?page&size` | 再招待依頼の一覧（古い順。`size` 既定20・最大50）。`data.content[]` と `data.totalElements` |
| POST | `/api/v1/{teams\|organizations}/{slug}/invite-reissue-requests/{id}/reissue` | 再招待。応答 `{status: REISSUED, expiresAt}` または `{status: ALREADY_MEMBER}` |
| POST | `/api/v1/{teams\|organizations}/{slug}/invite-reissue-requests/{id}/decline` | 見送り。応答 `{status: REISSUE_DECLINED}` |

- 一覧の要素: `id`（保留 ID）・`requesterDisplayName`（FE は補間のみで表示）・`requesterAvatarUrl`（任意）・`requestedAt`・
  `roleName`（実際に付与するロール）。メールアドレス・招待のハッシュは含めない。表示名・アバター・付与ロールはページごとに
  まとめて引く（行数に依らない SQL 本数）。
- 一覧と件数の契約: 述語は1つ（`status=REISSUE_REQUESTED`・スコープ一致・依頼者が `ACTIVE`・論理削除されていない。
  `PendingInviteStore#countReissueRequests`/`pageReissueRequests`）。一覧の `totalElements` と管理者向け集約
  （`INVITE_REISSUE`）の `pending_count` は一致する。ブロック中の依頼者は述語に入れず一覧に残る（操作時に判定）。
  閉鎖中（アーカイブ）のスコープの一覧は0件で、再開すると戻る。
- 付与ロール（`InviteReissueRequestService#resolveGrantRole`）: 元の招待のロールが招待で付与できるロール（MEMBER/SUPPORTER。
  `InviteRolePolicy`・CMP-261124-0157）なら引き継ぎ、ADMIN/DEPUTY_ADMIN/GUEST/SYSTEM_ADMIN（承諾で 422 `ROLE_009` になる）や元の招待・ロールが引けない場合は MEMBER。一覧の `roleName` と
  発行で同じ判定を使う。
- 再招待の手順: slug→ID → 招待権限の確認（403）→ 依頼の確認（不在・他スコープ `ROLE_016`、`REISSUE_REQUESTED` 以外
  `ROLE_017`、依頼者が非 ACTIVE・ブロック中 `ROLE_018`）→ 在籍済みなら `JOINED` にして `ALREADY_MEMBER`（招待は作らない）→
  閉鎖中は `ROLE_002` → 仮発行 → 閉鎖世代の再確認 → auth の確定 → 有効化 → 監査。詳細は 03_business_logic.md。
- 見送り: 条件付き UPDATE で `REISSUE_REQUESTED`→`REISSUE_DECLINED`。ブロック中の依頼者でも見送れる（非 ACTIVE は `ROLE_018`）。
- 通知: 再招待（有効化の成功後）・見送りのコミット後に、依頼者だけへ `INVITE_REISSUED` / `INVITE_REISSUE_DECLINED`
  （NORMAL・受信者の言語・本文に招待先の名前）。遷移先は `/dashboard?pendingInvite=open`（FE が保留を取り直し、「あとで」の
  記録を無視してダイアログを出す）。配送に失敗しても確定は巻き戻さない。
- 再発行した招待が有効な間は、同じ相手への DM 招待（承諾型招待）は重複として断られる（宛先付きの未失効招待があるため。仕様）。

**エラーレスポンス**
| ステータス | コード | 条件 |
|---|---|---|
| 401 | — | 未認証 |
| 403 | `COMMON_002` | 招待権限が無い（INVITE_MEMBERS の無い DEPUTY・MEMBER・SUPPORTER・非メンバー・他スコープのパス） |
| 404 | `TEAM_001`/`ORG_001` | slug の招待先が無い（論理削除・PROVISIONED を含む。slug 解決は ACTIVE の招待先だけ） |
| 404 | `ROLE_016` | 依頼が無い・他スコープの依頼 ID（存在秘匿のため同じコード） |
| 404 | `ROLE_002` | 招待先が閉鎖中、または仮発行の間に閉鎖された |
| 409 | `ROLE_017` | 依頼は既に対応済み（`REISSUE_REQUESTED` 以外・競合の負け側） |
| 409 | `ROLE_018` | 依頼者が参加できない（非 ACTIVE。再招待はブロック中も） |
| 500 | `ROLE_019` | 仮発行した招待の有効化に失敗（保留は `PENDING`。60秒の窓を過ぎた次の参照で `EXPIRED` になり、7日を待たず再依頼できる） |
- 画面（招待画面の新規登録導線・ログイン後の承認ダイアログ）は後続 PR で対応する。それまで招待画面は従来どおり
  チーム・組織のトークンを `inviteToken` として送らない（上記の段落）。
- `valid=false` または `ROLE_002` は無効招待として表示する。それ以外の取得失敗は取得エラーとして表示し、同じ取得処理による再試行を提供する。
- 有効なプレビューでは対象種別に応じた任意のフォルダ選択と参加操作を提供する（F15.3 §7.4）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 400 (`ROLE_002`) | 招待トークンが無効 |

---

#### `GET /api/v1/invite/{token}/qr`（未認証可）

招待URLをエンコードした QR コード画像を返す。`GET /invite/{token}` と同一のアクセス制御を適用する。

**レスポンス（200 OK）**

```
Content-Type: image/png
Body: QRコード PNG バイナリ（invite_url をエンコード・デフォルト 300×300px）
```

> - QR コードにエンコードする値は `https://mannschaft.app/invite/{token}` 形式の invite_url
> - `size` クエリパラメータ（任意・整数・px）でサイズ変更可能（最小64 / 最大1024 / デフォルト300）
> - バックエンドで ZXing ライブラリを使用して動的生成（S3 への保存は行わない）
> - **キャッシュ推奨**: ZXing による PNG 生成は CPU コストが高い。同一トークン・同一サイズへの連続リクエストに備え、生成済み画像を Valkey またはオンヒープキャッシュ（`{token}:{size}` をキーに TTL 5分）に保存し、キャッシュヒット時は再生成をスキップする実装を推奨する

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `size` パラメータが範囲外 |
| 404 | トークンが存在しない |
| 429 | レートリミット超過（10 req/min per IP）|

---

#### `POST /api/v1/invite/{token}/join`

**リクエストボディ**: なし（認証ヘッダーからユーザーを取得）

**レスポンス（200 OK）**

チーム招待の場合:
```json
{
  "data": {
    "invite_type": "TEAM",
    "target": {
      "id": 1,
      "name": "FCマンシャフト"
    },
    "role": "MEMBER"
  }
}
```

組織招待の場合:
```json
{
  "data": {
    "invite_type": "ORGANIZATION",
    "target": {
      "id": 5,
      "name": "〇〇サッカー協会"
    },
    "role": "MEMBER"
  }
}
```

> `invite_type` によりフロントエンドが遷移先（チームダッシュボード or 組織ダッシュボード）を決定する。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン期限切れ・使用回数上限・手動失効 |
| 401 | 未認証（ログインしていない状態でのアクセス）|
| 403 | ブロック済みユーザー（`team_blocks` / `organization_blocks` にエントリが存在）|
| 409 | すでにメンバーとして参加済み |
| 422 | 招待先チーム / 組織がアーカイブ済み（`archived_at IS NOT NULL`）|

---

#### `PUT /api/v1/teams/{slug}/members/{userId}/permission-groups`

**リクエストボディ**
```json
{
  "group_ids": [1, 3]
}
```

> 既存の割り当てを一括置換する（差分でなく全上書き）。空配列で全グループ解除。

**レスポンス（200 OK）**
```json
{
  "data": {
    "user_id": 42,
    "permission_groups": [
      {"id": 1, "name": "受付・安否確認"},
      {"id": 3, "name": "スケジュール管理"}
    ]
  }
}
```

**エラーレスポンス（共通）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 401 | 未認証 |
| 403 | 権限不足 |
| 404 | リソース不存在 |
| 409 | 競合（メンバー重複参加など）|
| 422 | ビジネスロジックエラー（独立チームに ORGANIZATION_ONLY 設定など）|

---

#### `GET /api/v1/organizations/{slug}/members`

**認可ルール（visibility 依存）**

| 組織 visibility | アクセス可能なユーザー |
|----------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 組織メンバー（任意ロール）のみ。それ以外は 403 |

> 組織の `visibility` は `PUBLIC` / `PRIVATE` の2値のみ（チームの `ORGANIZATION_ONLY` は存在しない）

**返却フィールドのロール別制限**

| 呼び出し者の区分 | 返却フィールド |
|-----------------|--------------|
| ADMIN / DEPUTY_ADMIN（組織メンバー）| 全フィールド（`user_id`, `display_name`, `icon_url`, `role`, `permission_groups`, `joined_at`）|
| MEMBER / SUPPORTER / GUEST（組織メンバー）| 基本プロフィール（`user_id`, `display_name`, `icon_url`, `role`）|
| 非メンバー（PUBLIC 組織への外部アクセス）| 基本プロフィールのみ（MEMBER と同内容）|

> - `permission_groups` は `role = DEPUTY_ADMIN` または `role = MEMBER`（権限グループが1件以上割り当て済み）のユーザーに返す。その他のロールまたは未割り当て MEMBER は空配列
> - 支払い状況・連絡先等の個人情報は本エンドポイントには含めない（F04 参照）

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `role` | String | — | ロールでフィルタ（カンマ区切りで複数指定可）|
| `q` | String | — | 名前の部分一致検索 |
| `sort` | String | `joined_at_asc` | ソート順（チームメンバー一覧と同一の選択肢）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**

※ デフォルトソート: `joined_at`（参加日時）昇順。レスポンス構造・カーソルベースページネーション仕様は `GET /api/v1/teams/{slug}/members` と同一（`permission_groups` / `joined_at` の返却条件も同様）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | `visibility = PRIVATE` かつ呼び出し者が組織メンバーでない |
| 404 | 組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{slug}/members/all`

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `scope` | String | `INDIVIDUAL` | 収集対象の範囲。`ORGANIZATION`（組織直属のみ）/ `TEAM`（チームメンバーのみ）/ `INDIVIDUAL`（全員）|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大200）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "icon_url": "https://cdn.mannschaft.app/users/42/icon.webp",
      "member_of": {
        "type": "ORGANIZATION",
        "id": 10,
        "name": "1年生"
      },
      "role": "ADMIN"
    },
    {
      "user_id": 55,
      "display_name": "鈴木花子",
      "icon_url": "https://cdn.mannschaft.app/users/55/icon.webp",
      "member_of": {
        "type": "TEAM",
        "id": 3,
        "name": "1年A組"
      },
      "role": "MEMBER"
    }
  ],
  "meta": {
    "next_cursor": "eyJ...",
    "size": 50,
    "has_next": true,
    "scope": "INDIVIDUAL"
  }
}
```

> - `member_of.type`: `"ORGANIZATION"` または `"TEAM"`（どのエンティティ経由で所属しているか）
> - カスケード通知の送信前に対象人数・構成を確認する用途を想定

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `scope` の値が不正 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | 組織が存在しない |

---

#### `GET /api/v1/me/teams`

**取得処理の実装注記（CMP-261026-1912）**: チーム一覧は `TeamQueryService`、代表親順の加盟情報は `TeamOrgMembershipQueryService`、親組織情報は `OrganizationQueryService` で一括取得する。ドメイン間には一覧用recordとIDだけを渡し、既存の代表親順・参加日時・役割・人数・アーカイブ判定・テンプレートを維持する。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `include_archived` | Boolean | `false` | `true` にするとアーカイブ済みチームも含める |
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "FCマンシャフト",
      "icon_url": "https://cdn.mannschaft.app/teams/1/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 24,
      "role": "ADMIN",
      "joined_at": "2026-03-01T10:00:00Z",
      "is_archived": false
    },
    {
      "id": 7,
      "name": "渋谷バスケ部",
      "icon_url": null,
      "visibility": "PRIVATE",
      "member_count": 8,
      "role": "MEMBER",
      "joined_at": "2026-02-15T09:00:00Z",
      "is_archived": false
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済みチーム（`teams.deleted_at IS NOT NULL`）は常に除外する
> - `include_archived = false`（デフォルト）の場合、アーカイブ済みチーム（`archived_at IS NOT NULL`）も除外する
> - `role` はそのチームにおける自分のロール名（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / GUEST）
> - `joined_at` は `memberships.joined_at`（参加日時。F00.5 Phase 2 以降は memberships テーブルの真値を使用）
> - `member_count` はそのチームの現在のメンバー数（`memberships WHERE scope_type='TEAM' AND scope_id = X AND left_at IS NULL` の件数）
> - 返却順: `joined_at` 昇順

---

#### `GET /api/v1/me/organizations`

**取得処理の実装注記（CMP-261026-1912）**: 組織実体・実効ロール・在籍人数を一括照会し、所属組織数に比例するSQL発行を避ける。組織の一覧情報は `OrganizationQueryService` が組織ドメイン内で取得・変換し、呼び出し元にはEntityを公開しない。所属は権限ロールと在籍情報の和集合とし、参加日時は在籍情報を優先する。非アクティブ利用者、SYSTEM_ADMIN、アーカイブ除外、人数のdistinct集計と並び順は従来どおり。現実装は全所属を返して`size`をmetaに設定し、下記のカーソルページングは未実装である。この性能修正では実ページングの導入による既存呼び出し元の取得件数変更は行わない。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `include_archived` | Boolean | `false` | `true` にするとアーカイブ済み組織も含める |
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 150,
      "role": "ADMIN",
      "joined_at": "2026-01-10T08:00:00Z",
      "is_archived": false
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済み組織（`organizations.deleted_at IS NOT NULL`）は常に除外する
> - `include_archived = false`（デフォルト）の場合、アーカイブ済み組織（`archived_at IS NOT NULL`）も除外する
> - `role` はその組織における自分のロール名
> - `joined_at` は `memberships.joined_at`（F00.5 Phase 2 以降は memberships テーブルの真値を使用）
> - `member_count` はその組織の直接所属メンバー数（`memberships WHERE scope_type='ORGANIZATION' AND scope_id = X AND left_at IS NULL` の件数）
> - 返却順: `joined_at` 昇順

**エラーレスポンス（`/me/teams` および `/me/organizations` 共通）**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |

---

#### `GET /api/v1/organizations/{slug}/teams`

組織に所属する（`team_org_memberships.status = 'ACTIVE'`）チーム一覧を返す。

**認可ルール**

| 組織 visibility | アクセス可能なユーザー |
|----------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 組織メンバー（任意ロール）のみ。それ以外は 403 |

**返却チームの visibility フィルタ**

呼び出し可能な場合でも、個々のチームの `visibility` に応じてレスポンスの内容を制限する:

| チーム visibility | 返却条件 |
|------------------|---------|
| `PUBLIC` | 常に返す |
| `ORGANIZATION_ONLY` | 常に返す（組織所属チームが `ORGANIZATION_ONLY` を選択した意図＝組織コンテキストでの公開を尊重するため）|
| `PRIVATE` | 呼び出し者がそのチームのメンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 3,
      "name": "FCマンシャフト U-12",
      "icon_url": "https://cdn.mannschaft.app/teams/3/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 18
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済みチーム（`deleted_at IS NOT NULL`）は常に除外する
> - `member_count` はチームの現在のメンバー数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 組織の `visibility = PRIVATE` かつ呼び出し者が組織メンバーでない |
| 404 | 組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/teams/{slug}/organizations`

チームが所属する（`team_org_memberships.status = 'ACTIVE'`）組織一覧を返す。

**認可ルール**

| チーム visibility | アクセス可能なユーザー |
|------------------|----------------------|
| `PUBLIC` / `ORGANIZATION_ONLY` | 任意の認証済みユーザー |
| `PRIVATE` | チームメンバー（任意ロール）のみ。それ以外は 403 |

**返却組織の visibility フィルタ**

| 組織 visibility | 返却条件 |
|----------------|---------|
| `PUBLIC` | 常に返す |
| `PRIVATE` | 呼び出し者がその組織のメンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 5,
      "name": "〇〇サッカー協会",
      "icon_url": "https://cdn.mannschaft.app/organizations/5/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 150
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済み組織（`deleted_at IS NOT NULL`）は常に除外する
> - `member_count` は組織の直接所属メンバー数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | チームの `visibility = PRIVATE` かつ呼び出し者がチームメンバーでない |
| 404 | チームが存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{slug}/ancestors`

対象組織の上位組織チェーン（祖先）を `parent_organization_id` を辿って返す。配列の先頭が root（最上位）、末尾が直近の親。`hierarchy_visibility` および `visibility` を尊重する。

**認可ルール**

| 対象組織 visibility | アクセス可能なユーザー |
|--------------------|----------------------|
| `PUBLIC` | 任意の認証済み・未認証ユーザー（公開検索由来のアクセスを許可）|
| `PRIVATE` | 対象組織の直接所属メンバー、または対象組織の子孫（子組織・所属チーム）のメンバーのみ |

**祖先個別の返却フィルタ**

各祖先について以下を順に判定し、不可なら**プレースホルダ**（`{ id, hidden: true }` のみ）として配列に残す。プレースホルダはチェーンの抜けを示すために残す（UX 的に「親があるが見れない」ことを伝える）。

| 状況 | 返却内容 |
|------|---------|
| 呼び出し者が当該祖先の直接所属メンバー | フル情報 |
| 呼び出し者が当該祖先の子孫メンバーで `hierarchy_visibility = FULL` | フル情報 |
| 同上で `hierarchy_visibility = BASIC` | `id` / `name` / `nickname1` / `description` / `icon_url` のみ |
| 同上で `hierarchy_visibility = NONE` | `{ id, hidden: true }`（プレースホルダ）|
| 呼び出し者が外部ユーザーで祖先 `visibility = PUBLIC` | `id` / `name` / `nickname1` / `icon_url` のみ |
| 呼び出し者が外部ユーザーで祖先 `visibility = PRIVATE` | `{ id, hidden: true }`（プレースホルダ）|

**深度・サイクル**

- 祖先探索の最大深さは `app.org.max-depth`（デフォルト: 5）。これを超える場合は途中で打ち切る（部分結果を返す。エラーは出さない）
- サイクルが検出された場合（あってはならないが防御的に）はそこで探索を打ち切る

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "全国〇〇連盟",
      "nickname1": null,
      "icon_url": "https://cdn.mannschaft.app/organizations/1/icon.webp",
      "visibility": "PUBLIC",
      "hidden": false
    },
    {
      "id": 3,
      "name": "関東支部",
      "nickname1": "関東",
      "icon_url": null,
      "visibility": "PRIVATE",
      "hidden": false
    },
    {
      "id": 7,
      "hidden": true
    }
  ],
  "meta": {
    "depth": 3,
    "truncated": false
  }
}
```

> - 配列順は **root → 直近の親**（パンくず表示でそのまま左から右に並べられる順序）
> - `hidden: true` のエントリには `id` 以外のフィールドを含めない（情報漏洩防止）
> - `meta.truncated = true` は `max-depth` 到達による打ち切り発生を示す
> - 親が存在しない（トップレベル組織）の場合は `data: []`・`meta.depth: 0` を返す

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証（対象組織が PRIVATE の場合のみ）|
| 403 | 対象組織が PRIVATE で呼び出し者が直接所属でも子孫メンバーでもない |
| 404 | 対象組織が存在しない / 論理削除済み |

---

#### `GET /api/v1/organizations/{slug}/children`

対象組織の **直近の子組織**（`parent_organization_id = {slug}` かつ `deleted_at IS NULL`）の一覧を返す。深い孫は含まない（必要なら呼び出し側で再帰取得）。

**認可ルール**

| 対象組織 visibility | アクセス可能なユーザー |
|--------------------|----------------------|
| `PUBLIC` | 任意の認証済みユーザー |
| `PRIVATE` | 対象組織の直接所属メンバーのみ。それ以外は 403 |

**返却子組織の visibility フィルタ**

| 子組織 visibility | 返却条件 |
|------------------|---------|
| `PUBLIC` | 常に返す |
| `PRIVATE` | 呼び出し者が当該子組織の直接所属メンバーの場合のみ返す。非メンバーにはレスポンスから除外（404 にはしない）|

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `cursor` | String | — | 次ページ取得用カーソル |
| `size` | Int | `50` | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 12,
      "name": "FCマンシャフト ジュニアユース",
      "nickname1": "ジュニアユース",
      "icon_url": "https://cdn.mannschaft.app/organizations/12/icon.webp",
      "visibility": "PUBLIC",
      "member_count": 32
    }
  ],
  "meta": {
    "next_cursor": null,
    "size": 50,
    "has_next": false
  }
}
```

> - 論理削除済み・アーカイブ済みは `archived` フィールドで示し、レスポンスからは除外しない（クライアント側で識別可能）。ただし `archived_at IS NOT NULL` の場合は `archived: true` を含める
> - `member_count` は子組織の直接所属メンバー数

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 対象組織の `visibility = PRIVATE` かつ呼び出し者が直接所属メンバーでない |
| 404 | 対象組織が存在しない / 論理削除済み |

---

#### `PATCH /api/v1/teams/{slug}/archive` / `PATCH /api/v1/teams/{slug}/unarchive`

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | チームが存在しない / 論理削除済み |
| 422 | archive: すでにアーカイブ済み / unarchive: アーカイブ状態でない |

---

#### `PATCH /api/v1/organizations/{slug}/archive` / `PATCH /api/v1/organizations/{slug}/unarchive`

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（ADMIN 未満）|
| 404 | 組織が存在しない / 論理削除済み |
| 422 | archive: すでにアーカイブ済み / unarchive: アーカイブ状態でない |

---

#### `GET /api/v1/teams/{slug}/me/permissions`

対象チームにおける自分の実効パーミッション一覧を返す。フロントエンドの UI 制御（ボタン表示/非表示）に使用する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "role": "DEPUTY_ADMIN",
    "permissions": [
      "MANAGE_SCHEDULES",
      "MANAGE_FILES",
      "MANAGE_POSTS",
      "MANAGE_ANNOUNCEMENTS"
    ],
    "permission_groups": [
      {"id": 1, "name": "受付・安否確認"}
    ]
  }
}
```

> - 権限解決ロジック（Section 5）に従い、ロール・権限グループから実効パーミッションを算出して返す
> - SYSTEM_ADMIN の場合は全パーミッションを返す
> - SUPPORTER / GUEST の場合は `permissions` は空配列
> - `GET /organizations/{slug}/me/permissions` も同一仕様（スコープが組織に変わるのみ）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 404 | チーム/組織が存在しない / 論理削除済み / 自分がメンバーでない |

---

#### オーナー委譲（承諾型・2ステップ / 2026-07-18 承諾型化）

> **⚠️ 旧即時 API の廃止**: 旧 `POST /api/v1/teams/{slug}/transfer-ownership`（押した瞬間に即時昇格・降格）は **廃止し、承諾型の 2 ステップ API に置き換える**。旧エンドポイントは後方互換の観点から一時的に「内部で PENDING オファーを作成して 201 を返す」ラッパーとして残すことも可能だが、既定は **廃止（410 Gone または新エンドポイントへ 308 誘導）** とし、FE を新 API へ全面移行する。ビジネスロジックは [03_business_logic.md「オーナー委譲 承諾フロー（2ステップ・承諾型）」](03_business_logic.md) を参照。

##### `POST /api/v1/teams/{slug}/transfer-ownership-offers`（打診）

ADMIN が別メンバーへオーナー委譲を打診する。この時点ではロールは変わらず PENDING オファーを作成するのみ。

**リクエストボディ**
```json
{ "targetUserId": 42 }
```
> **パラメータ名**: BE は `targetUserId`（旧 `target_user_id` から camelCase 統一）。FE composable が旧来 `newAdminUserId` を送っていた不一致は本承諾型化に伴い `targetUserId` へ統一する（03_business_logic.md 既知課題参照）。

**レスポンス（201 Created）**
```json
{
  "data": {
    "offerId": "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
    "status": "PENDING",
    "target": { "user_id": 42, "display_name": "田中太郎" },
    "issuedBy": { "user_id": 1, "display_name": "佐藤一郎" },
    "expiresAt": "2026-07-25T09:00:00Z"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 操作者が ADMIN でない |
| 404 | チーム/組織が存在しない / 論理削除済み / 対象ユーザーがメンバーでない |
| 409 | 同一スコープに未処理（PENDING）のオファーが既存 |
| 422 | 対象ユーザーが 2FA 未設定 / 操作者 == 対象 / アーカイブ済み |

##### `POST /api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept`（承諾＝実行）

**指名相手本人のみ** が承諾できる。承諾で初めて「対象→ADMIN 昇格」「発行者→MEMBER 降格」を実行する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "new_admin":      { "user_id": 42, "display_name": "田中太郎", "role": "ADMIN" },
    "previous_admin": { "user_id": 1,  "display_name": "佐藤一郎", "role": "MEMBER" }
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | **実行ユーザー ≠ オファーの指名相手（宛先照合 = IDOR 防止）** |
| 409 | オファーが PENDING でない（既に承諾/辞退/取消済み）/ 発行者が既に ADMIN でない |
| 410 | オファーが期限切れ（EXPIRED）|
| 422 | 実行ユーザーが 2FA 未設定 / アーカイブ済み |

##### `POST .../transfer-ownership-offers/{offerId}/decline`（辞退）／`DELETE .../transfer-ownership-offers/{offerId}`（取消）

- **decline**: 指名相手本人のみ。`status=DECLINED`。ロール不変。403 は宛先不一致。
- **DELETE（取消）**: 発行者または対象スコープ ADMIN のみ。`status=CANCELLED`。ロール不変。

> - `POST /organizations/{slug}/transfer-ownership-offers`（および accept/decline/DELETE）も同一仕様（スコープが組織に変わるのみ）

---

#### `PATCH /api/v1/teams/{slug}/restore` / `PATCH /api/v1/organizations/{slug}/restore`

論理削除済みのチーム/組織を復元する。SYSTEM_ADMIN のみ実行可能。

リクエストボディなし。204 No Content を返す。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証 |
| 403 | 権限不足（SYSTEM_ADMIN のみ）|
| 404 | チーム/組織が存在しない |
| 409 | 論理削除されていない（`deleted_at IS NULL`。復元済みの再実行を含む）|

チーム復元は認可後に削除済み・PROVISIONEDを含む現行slugを解決する。非SYSTEM_ADMINは対象の実在・削除状態にかかわらず403。
未削除のチームは `TEAM_006`「このチームは削除されていないため、復元できません」を返す。
同時復元はチーム行のロックで直列化し、成功1件を204、後続を409とする。

復元で戻すのは `deleted_at` のみ。公開範囲・アーカイブ・lifecycle・閉鎖世代・役割・予定は保持し、
削除前の組織加盟と制限は既存規則どおり同一トランザクションで片付け、加盟自体は復元しない。
PROVISIONEDは復元後も通常GET・予定APIから404のまま隠す。
成功コミット後に `TEAM_RESTORED` を既存best-effort監査基盤へ記録し、拒否・失敗・rollbackでは成功監査を残さない。
チームの復元UIはシステム管理の `/system-admin/teams/deleted` に置き、専用の削除済み一覧から選択する（[F10.1](../F10.1_admin_dashboard.md)）。
孤児予定の掃除はSYSTEM_ADMINが復元後に既存のチーム予定UIで行い、
元ADMINがチームを再削除する（元ADMINが不在の場合は別途運用判断が必要）。

---

#### `PATCH /api/v1/organizations/{slug}/profile`（`/api/v1/teams/{slug}/profile` も同一仕様）

組織（またはチーム）のプロフィール拡張項目を一括更新する。

**既存 `PATCH /api/v1/organizations/{slug}` との棲み分け**:
- 既存 PATCH は「基本情報」（`name`, `name_kana`, `nickname1/2`, `description`, `icon_url`, `banner_url`, `visibility`, `hierarchy_visibility`, `supporter_enabled`, `org_type`）のみ
- 本 PATCH は「拡張プロフィール」（`homepage_url`, `established_date`, `established_date_precision`, `philosophy`, `profile_visibility`）のみ
- 既存 PATCH に `homepage_url` 等を渡すと 400（`ORG_049`: 拡張プロフィール項目は `/profile` エンドポイントで更新）
- 本 PATCH に `name` 等の基本情報を渡すと 400（同様に棲み分けを強制）

**楽観ロック**: 既存 `organizations` エンティティに `version` 列があれば利用するが、プロフィール項目の一括更新は頻度が低いため、まずは「最後に書いた人の勝ち」運用とする。同時編集衝突が運用上問題になったら If-Match / ETag ベースの制御を追加する（未解決事項に記載）。

**空文字の正規化**: `homepageUrl`, `philosophy`, `officer.name/title`, `customField.label/value` は、リクエスト値を Service 層で `trim()` し、空文字列になったフィールドは以下のルールで処理
- nullable 項目（homepage_url, philosophy）→ NULL に正規化して保存（削除扱い）
- NOT NULL 項目（officer.name/title, customField.label/value）→ 400 Bad Request（空文字不可）

**権限**: ADMIN または `MANAGE_ORGANIZATION`（チームは `MANAGE_TEAM`）権限を持つ DEPUTY_ADMIN

**リクエスト**
```json
{
  "homepageUrl": "https://example.org",
  "establishedDate": "2015-04-01",
  "establishedDatePrecision": "YEAR_MONTH",
  "philosophy": "全ての人に学びの機会を。",
  "profileVisibility": {
    "homepage_url": true,
    "established_date": true,
    "philosophy": true,
    "officers": true,
    "custom_fields": false
  }
}
```

- 全フィールド任意。リクエストに含まれないフィールドは更新しない（PATCH セマンティクス）
- `homepageUrl`: `^https?://` 必須、最大512文字。`null` 指定で削除
- `establishedDate`: `YYYY-MM-DD` 形式。`establishedDatePrecision` と必ずペア。片方のみの更新は 422
- `establishedDatePrecision`: `YEAR` / `YEAR_MONTH` / `FULL`
- `philosophy`: 最大2000文字。プレーンテキスト
- `profileVisibility`: JSON オブジェクト。既知キー（`homepage_url` / `established_date` / `philosophy` / `officers` / `custom_fields`）以外を含む場合 400

**レスポンス** 200 OK — 更新後のプロフィール全体を返す

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（URL形式不正・`profile_visibility` に不明キー・JSON構文エラー）|
| 401 | 未認証 |
| 403 | ADMIN/DEPUTY_ADMIN でない、または必要権限なし |
| 404 | 組織/チームが存在しない |
| 422 | `establishedDate` と `establishedDatePrecision` の片方だけ指定・文字数超過 |
| 429 | レートリミット超過（10 req/min/user）|

**監査ログ**: 変更前後の差分を `audit_logs` に記録（action = `ORGANIZATION_PROFILE_UPDATE` / `TEAM_PROFILE_UPDATE`）

---

#### `POST /api/v1/organizations/{slug}/officers`（`/api/v1/teams/{slug}/officers` も同一仕様）

組織の役員を追加する。

**権限**: ADMIN または `MANAGE_ORGANIZATION`（チームは `MANAGE_TEAM`）権限を持つ DEPUTY_ADMIN

**リクエスト**
```json
{
  "name": "山田太郎",
  "title": "代表理事",
  "displayOrder": 10,
  "isVisible": true
}
```

- `name` / `title`: 必須、最大100文字、プレーンテキスト
- `displayOrder`: 任意。未指定時はサーバー側で既存最大値 + 10 を採番
- `isVisible`: 任意、デフォルト true

**レスポンス** 201 Created
```json
{
  "id": 42,
  "name": "山田太郎",
  "title": "代表理事",
  "displayOrder": 10,
  "isVisible": true,
  "createdAt": "2026-04-15T11:00:00Z"
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（文字数超過・空文字・HTML/制御文字混入）|
| 403 | 権限不足 |
| 404 | 組織/チームが存在しない |
| 422 | 既に50件登録済み |

---

#### `PATCH /api/v1/organizations/{slug}/officers/{officerId}`

役員情報を更新する。リクエスト・認可・エラーは `POST` と同一（全フィールド任意）。

---

#### `DELETE /api/v1/organizations/{slug}/officers/{officerId}`

役員を物理削除する。204 No Content を返す。同一組織配下の役員でない場合 404。

---

#### `PUT /api/v1/organizations/{slug}/officers/reorder`

役員の並び順を一括更新する（楽観ロックのため単発 PATCH ではなく専用エンドポイント）。

**リクエスト**
```json
{
  "orders": [
    { "officerId": 42, "displayOrder": 10 },
    { "officerId": 43, "displayOrder": 20 },
    { "officerId": 41, "displayOrder": 30 }
  ]
}
```

- `orders` の配列は当該組織の全役員を網羅する必要がある（部分指定は 422）
- 重複 ID・不明 ID は 400

**レスポンス** 204 No Content

---

#### `GET /api/v1/organizations/{slug}/officers`

役員一覧を取得する。

**可視性ルール（重要）:**
1. 組織が PRIVATE のとき: 非メンバーは 403（メンバーは全件取得可）
2. 組織が PUBLIC でも `profile_visibility.officers = false` の場合: 非メンバーは空配列
3. `is_visible = false` の役員は非メンバーのレスポンスから除外（ADMIN / DEPUTY_ADMIN は全件取得）

**レスポンス** 200 OK
```json
{
  "data": [
    { "id": 42, "name": "山田太郎", "title": "代表理事", "displayOrder": 10, "isVisible": true }
  ]
}
```

---

#### `POST /api/v1/organizations/{slug}/custom-fields`（`/api/v1/teams/{slug}/custom-fields` も同一仕様）

フリー記述プロフィール項目を追加する。

**権限**: ADMIN または対応権限を持つ DEPUTY_ADMIN

**リクエスト**
```json
{
  "label": "活動拠点",
  "value": "東京都渋谷区〜",
  "displayOrder": 10,
  "isVisible": true
}
```

- `label`: 必須、最大100文字
- `value`: 必須、最大1000文字
- いずれもプレーンテキスト

**レスポンス** 201 Created

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | 組織/チームが存在しない |
| 422 | 既に20件登録済み |

---

#### `PATCH /api/v1/organizations/{slug}/custom-fields/{fieldId}` / `DELETE` / `PUT .../reorder`

officers と同じ仕様・エラーハンドリング（数値上限のみ 20 件に変更）。

---

#### `GET /api/v1/organizations/{slug}/custom-fields`

可視性ルールは officers と同じ。`profile_visibility.custom_fields = false` のときは非メンバーには空配列。

---


#### 自分の有効権限（`GET /api/v1/{teams|organizations}/{slug}/me/permissions`）の追加項目

| フィールド | 型 | 説明 |
|---|---|---|
| `scopeAdmin` | Boolean | 対象スコープの ADMIN 割当があるか（SYSTEM_ADMIN の有無とは独立）|
| `canGrantAdminOnlyPermissions` | Boolean | ADMIN 専用権限（`PermissionGroupService.ADMIN_ONLY_GRANTABLE_PERMISSIONS`）を含む権限グループの作成・更新・割当（付与・剥奪とも）ができるか。`AccessControlService.canActAsScopeAdminOnly`（スコープ ADMIN 本人で、かつ SYSTEM_ADMIN でない）。SYSTEM_ADMIN とスコープ ADMIN の兼任者は `scopeAdmin=true` でも `false`（`checkScopeAdminOnly` が拒否するため）|

権限グループ一覧の各要素には `containsAdminOnlyPermission`（そのグループが ADMIN 専用権限を含むか）を載せる。含むグループの更新・削除・割当（外す操作も）は `canGrantAdminOnlyPermissions=true` の操作者だけができる。

---

## CMP-261124-0159: チームの非公開化（→ MEMBERS_AND_ABOVE）とサポーターの入口（2026-11-25 マスター裁定 MC-261125-0010）

> **ステータス**: 🟢 設計完了。業務フローは [03_business_logic.md](03_business_logic.md)「非公開化フロー（CMP-261124-0159）」、是正 migration は [04_security_operations.md](04_security_operations.md) §7「CMP-261124-0159 是正 migration」、閲覧上限の正本は [F00 §20](../F00_content_visibility_resolver.md)。用語「メンバー限定」の正本は [F00.5 §2.6](../F00.5_membership_basis.md)。

**依存**: CMP-261124-0157（SUPPORTER 招待で参加した人を正しく SUPPORTER 在籍にする是正。PR #4828 マージ済み）。本節の「SUPPORTER 在籍＝`memberships.role_kind='SUPPORTER'`」はその是正後の状態を前提にする。

JSON は既存規約どおり camelCase、ページングなし。数値はすべて非 null。

### 新規 `GET /api/v1/teams/{slug}/visibility-change-preview`

公開範囲を変える前に、変えた場合に外れる人数などを返す確認用の事前問い合わせ。副作用なし。

- 認可: `PATCH /api/v1/teams/{slug}` と同一（ADMIN+）。権限なし（MEMBER・サポーター・部外者・他チームの ADMIN）は `PATCH` の拒否と同一応答。不在 slug は 404 `TEAM_001`。
- クエリ: `visibility`（必須。`PUBLIC` / `GUESTS_AND_ABOVE` / `SUPPORTERS_AND_ABOVE` / `MEMBERS_AND_ABOVE`。不正値は 400 `COMMON_001`）

| フィールド | 型 | null | 説明 |
|---|---|---|---|
| `currentVisibility` | String | 不可 | 現在の公開範囲 |
| `targetVisibility` | String | 不可 | 指定した公開範囲 |
| `requiresConfirmation` | Boolean | 不可 | 現在が `MEMBERS_AND_ABOVE` 以外で指定が `MEMBERS_AND_ABOVE` のとき true（人数 0 でも true） |
| `supportersToRemove` | Integer | 不可 | 外れる在籍サポーター数（`memberships.role_kind='SUPPORTER' AND left_at IS NULL`）。`requiresConfirmation=false` なら 0 |
| `guestsToRemove` | Integer | 不可 | 外れるゲスト数（`user_roles` の GUEST 行） |
| `pendingApplicationsToReject` | Integer | 不可 | 却下される PENDING のサポーター申請数 |
| `supporterInviteTokensToRevoke` | Integer | 不可 | 無効化される有効な SUPPORTER 招待トークン数（`revoked_at IS NULL`） |
| `version` | Long | 不可 | 判定時点のチームの楽観ロック版。`PATCH` の `version` にそのまま使う |

人数は 4 本の COUNT（集合演算）で返す。対象人数に比例した問い合わせはしない。

### 変更 `PATCH /api/v1/teams/{slug}`（リクエストに 1 項目追加）

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `confirmSupporterRemoval` | Boolean | 条件付き | `visibility` を `MEMBERS_AND_ABOVE` 以外から `MEMBERS_AND_ABOVE` に変えるときは `true` 必須。未指定・false なら 409 `TEAM_074`（何も変更しない）。それ以外の変更では無視する |

- 応答（200）は既存どおり `ApiResponse<TeamResponse>`。`TeamResponse` の**直下**に `visibilityTransition`（オブジェクト、null 可）を追加する。遷移した `PATCH` の応答でだけ値が入り、遷移しなかった `PATCH`・`GET /teams/{slug}` ほか全ての取得系では `null`。FE の型 `types/team.ts` の `TeamResponse` と生成型（`npm run generate:types`）を更新する。

| フィールド | 型 | null | 説明 |
|---|---|---|---|
| `visibilityTransition.removedSupporters` | Integer | 不可 | 実際に外したサポーター数（コミット時点の在籍全員） |
| `visibilityTransition.removedGuests` | Integer | 不可 | 実際に外したゲスト数 |
| `visibilityTransition.rejectedApplications` | Integer | 不可 | 却下した PENDING 申請数 |
| `visibilityTransition.revokedSupporterInviteTokens` | Integer | 不可 | 無効化した SUPPORTER 招待トークン数 |

- 事前問い合わせの人数とコミット時点の人数が違っても（その間に増減しても）、`confirmSupporterRemoval=true` は「コミット時点で在籍する全員を外す」ことへの同意として扱う。実数は応答の `visibilityTransition` で返す。
- 並行更新は既存の `version`（楽観ロック）で弾く（既存の楽観ロック競合応答 409）。

### 拒否の追加（非公開チームへのサポーター追加の禁止）

| エンドポイント | 非公開チーム（`MEMBERS_AND_ABOVE`）での挙動 |
|---|---|
| `POST /api/v1/teams/{slug}/invite-tokens`（`roleId` が SUPPORTER ロール） | 403 `TEAM_073`。MEMBER の招待トークンは従来どおり発行できる |
| `POST /api/v1/invite/{token}/join`（SUPPORTER ロールのトークン） | 403 `TEAM_073`。在籍・`invite_token_claims`・`used_count` を変えない |
| `POST /api/v1/me/pending-invite/accept`（SUPPORTER ロールのトークン） | 応答 `{status: "UNAVAILABLE"}`（既存の値。在籍を作らない） |
| `POST /api/v1/teams/{slug}/supporter-applications/{applicationId}/approve` | 403 `TEAM_073`。申請の状態は変えない |
| `POST /api/v1/teams/{slug}/supporter-applications/bulk-approve` | 403 `TEAM_073`（1 件も承認しない。全体を拒否） |
| `POST /api/v1/teams/{slug}/follow` | 既存どおり `assertCanView(TEAM)` で 403 `VISIBILITY_001`（変更なし・回帰固定） |
| `PATCH /api/v1/teams/{slug}/members/{userId}/role`（GUEST・SUPPORTER への付与・降格） | 403 `TEAM_073`。ADMIN / DEPUTY_ADMIN / MEMBER 間の変更は従来どおり |
| ロール割当の内部経路（`RoleService#assignRole` / `#changeRole` を呼ぶ全経路。管理画面・一括操作を含む） | GUEST・SUPPORTER を非公開チームに付与しようとすると 403 `TEAM_073`（関所で一律に判定） |
| アーカイブからの復元（`archived_membership_grants` の復元。ライフイベント退出の取り消し等） | 非公開チームへの SUPPORTER / GUEST の復元は行わず、その行を復元対象から外す（他の行は復元する）。応答・監査ログに除外件数を残す |
| 参加申請（`JoinRequestService`）の承認で SUPPORTER を作る経路・組織経由の付与（組織の操作で配下チームに在籍・ロールを作る経路） | 403 `TEAM_073`（関所で一律に判定） |

- 上の拒否はすべて「入口の関所」（[03_business_logic.md](03_business_logic.md)「入口の関所」）で一律に判定する。新しい入口が増えても関所を通らずに SUPPORTER 在籍・GUEST を作れないことを ArchUnit `MembersOnlyTeamEntryChokePointTest`（F00 §20.6）で固定する（入口の網羅の番人）。
- 作成・更新の応答本文でチームの中身（一覧・他人の投稿・添付 URL 等）を返す書き込み API は、読み取りと同じ門（F00 §20.3）を通す。

- 判定順: 既存の認可（招待権限・申請承認権限）→ チームの公開範囲。権限のない利用者には従来の拒否を返し、`TEAM_073` で公開範囲を教えない。
- `TeamService#followTeam`（呼び出し元のない死蔵コード）は撤去する。

### エラーコード（新規。番号は現在の最大＋1 で予約、確定はマージ時に origin/main で再確認）

| Code | HTTP | ja メッセージ | 用途 |
|---|---|---|---|
| `TEAM_073` | 403 | メンバー限定のチームにはサポーターを追加できません | 上表の拒否 |
| `TEAM_074` | 409 | 公開範囲をメンバー限定にするには、サポーターとゲストが外れることの確認が必要です | `confirmSupporterRemoval` 未指定 |

6 言語（`messages_{ja,en,zh,ko,es,de}.properties`）に登録し、`GlobalExceptionHandler` の status 表の `TEAM_072` の行（F01.2.1 §11 の並び）の直後に `Map.entry("TEAM_073", HttpStatus.FORBIDDEN)` と `Map.entry("TEAM_074", HttpStatus.CONFLICT)` を、理由コメント（CMP-261124-0159）付きで追加する。

### FE（公開範囲を変える画面。精査で新設を決定）

現状、チーム作成後に公開範囲を変える画面は無い（作成時の `EntityCreateDialog.vue` のみ。`TeamDetailInfo.vue` は表示だけ、`admin/settings.vue` にも無い）。そこで **`/teams/{slug}/info`（`TeamDetailInfo.vue`）の管理者欄に、公開範囲の Select と保存ボタンを新設**する。

- **表示条件**: BE の `PATCH /teams/{slug}` の認可（`TeamController#updateTeam` の `checkAdminOrAbove`＝`DefaultAccessControlService#isAdminOrAbove`、そのチームの `user_roles` が ADMIN / DEPUTY_ADMIN）と**同じ判定結果を BE から受け取って**使う。`GET /api/v1/teams/{slug}/me/permissions`（`EffectivePermissionsResponse`）に `scopeAdminOrDeputy: Boolean`（非 null。`isAdminOrAbove(userId, teamId, "TEAM")` の結果）を追加し、`useRoleAccess()` に `canManageScope`（＝`scopeAdminOrDeputy`）を足して表示条件にする。`roleName` は SYSTEM_ADMIN を優先して返し、`isScopeAdmin` は ADMIN 割当だけを表すため、FE で組み立てると SYSTEM_ADMIN と DEPUTY_ADMIN の兼任者を取りこぼす。新しい prop `canEditVisibility` で `TeamDetailInfo.vue` に渡す。組織の `me/permissions` も同じ項目を返す（同一 DTO）。
- 判定の区分: SYSTEM_ADMIN 単独（チームのロール割当なし）＝false、SYSTEM_ADMIN と ADMIN の兼任＝true、SYSTEM_ADMIN と DEPUTY_ADMIN の兼任＝true、ADMIN・DEPUTY_ADMIN＝true、MEMBER 以下＝false。
- **選択肢**: `PUBLIC` / `GUESTS_AND_ABOVE` / `SUPPORTERS_AND_ABOVE` / `MEMBERS_AND_ABOVE`。表示は `team_visibility.*`（下の文言変更後）。初期値は現在値。
- **保存の流れ**: 現在値と同じなら保存ボタンは無効。変更して保存を押すと、まず事前問い合わせを呼ぶ。`requiresConfirmation=true` なら確認ダイアログ（人数つき。0 人なら `bodyNone`）を出し、キャンセルなら `PATCH` を送らない。了承で `{visibility, confirmSupporterRemoval: true, version: 事前問い合わせの version}` を送る。`requiresConfirmation=false` なら確認なしで `{visibility, version}` を送る。
- **応答の扱い**: 200 なら応答の `TeamResponse` で親シェルの `team` を**差し替える**（`useTeamShellContext` のミューテータ経由。再取得しない）。`visibilityTransition` が非 null なら実数をトースト（`transitionDone`）で出す。
- **409 の分岐**: `error.code` が `TEAM_074` ならトースト `team_visibility.confirmRequired`（通常は起きない。確認を出し直す）。それ以外の 409（楽観ロック競合）はトースト `team_visibility.conflictReload`「他の人がチームを更新しました。最新の状態で確認し直してください」を出し、チームを再取得して**事前問い合わせからやり直す**（古い version で再送しない）。
- **サポーター機能のスイッチ**: 従来どおり表示のみ（編集 UI は作らない）。非公開の間は「有効／無効」の横に `supporterSettingInactive` を添える。
- **data-testid**: `team-visibility-select`・`team-visibility-save`・`team-visibility-confirm-dialog`・`team-visibility-confirm-ok`・`team-visibility-confirm-cancel`・`team-visibility-confirm-counts`・`team-supporter-setting-inactive`。
- **公開範囲の選択肢の文言**: `MEMBERS_AND_ABOVE` の表示を 6 言語とも「メンバー限定」に変える（`common.json` の `team_visibility.MEMBERS_AND_ABOVE` と `scopeLabels.visibility.MEMBERS_AND_ABOVE`。ja「メンバー限定」・en「Members only」・zh「仅限成员」・ko「멤버 전용」・es「Solo miembros」・de「Nur Mitglieder」）。用語の正本 F00.5 §2.6 に合わせる。`file_sharing.json`・`tournament.json` の同名キーは別機能の段（ファイル・大会）なので変えない。

### FE（招待・メンバー管理での SUPPORTER / GUEST）

- `InviteTokenList.vue`（`/teams/{slug}/invites`）のロール選択: 非公開チームでは SUPPORTER の選択肢を**出さない**（MEMBER のみ）。既定値は MEMBER のまま。説明文に `supporterInviteDisabled` を出す。BE は 403 `TEAM_073` で拒否し、FE はそのメッセージをトーストに出す。
- 発行済みの SUPPORTER 招待は非公開化で無効化されるため、一覧では既存の「無効」表示になる。
- `MemberTable.vue`（`/teams/{slug}/members`）のロール変更: 非公開チームでは変更先に SUPPORTER・GUEST を出さない。BE が `TEAM_073` を返した場合はトーストに出し、表は変えない。

| i18n キー（`common.json`、6 言語） | ja |
|---|---|
| `team_visibility.confirmMembersOnly.title` | 公開範囲を「メンバー限定」にします |
| `team_visibility.confirmMembersOnly.body` | サポーター {supporters} 人とゲスト {guests} 人がチームから外れ、通知が届きます。承認待ちのサポーター申請 {applications} 件は却下され、サポーター招待 {tokens} 件は使えなくなります。元に戻しても自動では戻りません。 |
| `team_visibility.confirmMembersOnly.bodyNone` | 外れる人はいません。承認待ちのサポーター申請とサポーター招待もありません。公開範囲を「メンバー限定」にしますか。 |
| `team_visibility.confirmMembersOnly.confirm` | 外して変更する |
| `team_visibility.supporterSettingInactive` | メンバー限定のチームではサポーターを受け付けません（設定は公開範囲を戻したときに使われます） |
| `team_visibility.save` | 公開範囲を保存 |
| `team_visibility.confirmRequired` | 公開範囲を「メンバー限定」にするには確認が必要です。もう一度保存してください |
| `team_visibility.conflictReload` | 他の人がチームを更新しました。最新の状態で確認し直してください |
| `team_visibility.MEMBERS_AND_ABOVE`（変更） | メンバー限定 |
| `team_visibility.confirmMembersOnly.cancel` | やめる |
| `team_visibility.transitionDone` | 公開範囲を変更しました（サポーター {supporters} 人・ゲスト {guests} 人を外しました） |
| `team_visibility.supporterInviteDisabled` | メンバー限定のチームではサポーター招待を発行できません |

- 人数がすべて 0 のときは `bodyNone` を出す（確認と `confirmSupporterRemoval=true` は 0 人でも必須のまま）。
- **`supporterEnabled` の扱い**: 非公開化しても `teams.supporter_enabled` の値は変えない（公開範囲を戻したときにそのまま使う）。非公開の間は入口が `TEAM_073` / `VISIBILITY_001` で閉じるため値に関わらずサポーターは増えない。設定画面ではスイッチを操作不可にし `supporterSettingInactive` を添える。

**通知文（BE の 6 言語メッセージ `messages_{ja,en,zh,ko,es,de}.properties`。通知は BE が作るため FE の i18n には置かない）**

| メッセージキー | ja |
|---|---|
| `notification.team.membersOnlyRemoved.title` | {0} から外れました |
| `notification.team.membersOnlyRemoved.body` | チームの公開範囲が「メンバー限定」に変わったため、サポーター（ゲスト）としての登録が解除されました。 |

通知は受信者本人のスコープ（チームではない）で保存する（F00 §20.3.1 の読み出し時の除外の対象にしないため）。
