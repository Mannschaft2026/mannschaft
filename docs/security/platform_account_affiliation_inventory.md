# 運営アカウントのテナント所属禁止 — 経路の網羅表（戦役 Z-A PR2・確定版）

状態: **確定版（出陣で全経路に Policy を通した）**。新しい所属経路を足すときは、本表に行を足し、番人 G4 の基準線（`g4_sink_baseline.txt`）を Policy を通したことを確かめてから更新する。

- 方針の正本: `docs/security/03_role_authority_model.md` §3.6（マスター決定 2026-11-26「運営は運営専用アカウント。個人アカウントとの併用禁止。村・サポーター（フォロー）も禁止」）
- 判定の窓口: `com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy`
  - `assertAffiliatable(userId)`: 運営アカウントなら `MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN`（403）
  - `isPlatformAccount(userId)`: 例外を投げない判定（後継候補の選出から外す用途）
  - 依存は `UserRoleRepository` だけ。判定は `existsPlatformAdminRoleByUserId`（SYSTEM_ADMIN ロールの有無を**アカウントの状態を問わず**数える。停止中・非 ACTIVE の運営アカウントも対象。殿の決定 2026-11-26）。ACTIVE・未削除に限る既存の `existsSystemAdminByUserId` は退会ブロック等の別用途で、本禁止の判定には使わない。新しいフラグは作らない
- 番人 G4: `PlatformAccountAffiliationPolicyGuardTest`（生成シンクを持つファイルは Policy を呼ぶ。基準線 `backend/src/test/resources/platform_account_affiliation/g4_sink_baseline.txt`、除外表 `g4_exemptions.txt`）
- 点検 SQL: `backend/src/test/resources/platform_account_affiliation/inspection.sql`（Flyway 実スキーマで `PlatformAccountAffiliationInspectionFlywayIT` が 0 件を固定）

行番号は 2026-11-26 の origin/main（598dea59）時点。

## 1. 所属行を作る経路（P1）

| ドメイン | 経路 | 作る行 | 判定する利用者 | 固定するテスト |
|---|---|---|---|---|
| membership | `MembershipService.join`:94（合流点。Team/Org の参加・フォロー、Supporter、RoleService.joinMembership:983、Provisioning 受諾:155 を束ねる） | memberships | `req.userId` | TenantPaths `JoinMergePoint` / IT P1・P4 |
| role | `MembershipGrantService.grantMemberRole`:104（参加申請の承認） | memberships / user_roles | 申請者 | RolePaths `GrantMergePoint` |
| role | `MembershipGrantService.grantRole`:139（招待の受諾・ロール招待） | memberships / user_roles | 受諾者 | RolePaths `GrantMergePoint` |
| role | `InviteAcceptanceFacade.joinByInvite`:87/98・`acceptInvitationById`:108（招待の受諾入口） | （grantRole 経由） | 受諾者 | RolePaths `InviteAcceptance` |
| role | `RoleService.assignRole`:158（save:207） | user_roles | 付与先 | RolePaths `RoleServicePaths` |
| role | `RoleService.changeRole`:269（save:330） | user_roles | 対象者 | 同上 |
| role | `RoleService.transferOwnership`:834（save:885） | user_roles | 譲渡先 | 同上 |
| role | `OwnershipTransferOfferService.acceptOffer`:201 | （transferOwnership 経由） | 受諾者 | RolePaths `OwnershipOffer` |
| role | `AdminRoleMutationLockService.grantAdminRole`:58（ProvisioningAcceptanceService:145 の受諾者） | user_roles | 付与先 | RolePaths `AdminGrant` |
| role | `RoleSuccessionService.forceAssignInitialAdminOnUnarchive`:523（SystemAdminScopeForceUnarchiveController の指定） | user_roles | 指定先 | RolePaths `ForceUnarchive` |
| role | `RoleSuccessionService.selectSuccessionCandidate`:148／`selectTopCandidates`（後継候補の選出） | （昇格） | 候補 | IT `P1_後継候補の選出から運営アカウントを除外する` |
| team | `TeamService.createTeam`:148（user_roles:190・join:202） | user_roles / memberships | 作成者 | TenantPaths `TeamPaths` |
| team | `TeamService.followTeam`:1076（join:1097） | memberships(SUPPORTER) | 本人 | 合流点で固定 |
| organization | `OrganizationService.createOrganization`:117（user_roles:161・join:173） | user_roles / memberships | 作成者 | TenantPaths `OrganizationPaths` |
| organization | `OrganizationMembershipService.followOrganization`（join:145） | memberships(SUPPORTER) | 本人 | 合流点で固定 |
| supporter | `SupporterService.follow`:72（即時承認 join:98／手動承認の申請行:110） | memberships(SUPPORTER) / supporter_applications | 本人 | TenantPaths `SupporterFollow` |
| supporter | `SupporterService.approve`:281・`bulkApprove`:318 | memberships(SUPPORTER) | 申請者 | 合流点で固定 |
| joinrequest | `JoinRequestService.approve`:217（grantMemberRole:230） | （合流点） | 申請者 | 合流点で固定 |
| committee | `CommitteeService.createCommittee`:57（委員長 save:111） | committees / committee_members | 作成者・初期委員長 | CommitteePaths `CreateCommittee` |
| committee | `CommitteeInvitationService.acceptInvitation`:189（save:257） | committee_members | 受諾者 | CommitteePaths `CommitteeInvitations` |
| village | `VillageMembershipService.join`:81 | village_memberships | 操作者（主体 USER） | VillagePaths `Join` |
| village | `VillageInvitationService.accept`:163 | village_memberships | 受諾者 | VillagePaths `Invitations`・`NoSideEffects` |
| village | `VillageJoinRequestService.approve`:244（admit:267） | village_memberships | 申請主体 | Slot.admit で固定 |
| village | `VillageCreationRequestService.createRequest`（村長 admit。**公式村（OFFICIAL）の作成者としての村長所属だけ例外**。Policy `assertAffiliatableAsOfficialVillageCreator`・Slot `allocateForOfficialVillageCreator` / `admitOfficialVillageCreator`）・`approve`（例外なし。承認で作る村は常に通常村） | villages / village_memberships | 申請者 | VillagePaths `CreationRequests` |
| village | `VillageMembershipSlotService.allocate`:82・`admit`:119（五入口の共通保存点） | village_memberships | 主体 USER | VillagePaths `Slot` |
| village | `VillageMembershipProfileService.updateMyProfileVisibility`:61（save:73。既存の誤所属を延命させない） | village_memberships（更新） | 本人 | VillagePaths `Profile` |
| village | `VillageHeadmanSuccessionBatchService.processVillage`:114（promote save:191） | village_memberships（村長昇格） | 候補（`isPlatformAccount` で除外。例外で止めず次の古参へ。尽きれば従来どおり凍結） | HeadmanSuccessionTest |
| social | `FollowService.follow`:45（利用者のタイムライン上の個人フォロー。マスター決定で禁止に含める） | follows（USER→チーム/組織/利用者） | 本人 | `PlatformAccountAffiliationBanFollowTest` |

## 2. 運営アカウント宛ての招待・参加申請を作る経路（P2: 作成時点で拒否）

| ドメイン | 経路 | 作る行 | 判定する利用者 | 固定するテスト |
|---|---|---|---|---|
| role | `MembershipInviteService.issueMembershipInvite`:90（DM 相手が宛先）・`MembershipInviteTokenIssuer.issue` / `stageForReissue`（保存点でも判定。G4 対象） | invite_tokens（宛先付き） | 宛先 | RolePaths `MembershipInviteIssue` |
| role | `OwnershipTransferOfferService.createOffer`（ADMIN の認可の後に判定） | ownership_transfer_offers | 譲渡先 | RolePaths `OwnershipOffer` |
| joinrequest | `JoinRequestService.createRequest`:84 | join_requests | 申請者 | TenantPaths `JoinRequestCreate` |
| supporter | `SupporterService.follow`（手動承認）:110 | supporter_applications | 本人 | TenantPaths `SupporterFollow` |
| committee | `CommitteeInvitationService.sendInvitations`（委員長・副委員長の認可の後に判定） | committee_invitations | 宛先（1 人でも運営なら全体を 403） | CommitteePaths `CommitteeInvitations` |
| village | `VillageInvitationService.issue`:82（`targetUserId` 指定時。村長・長老の認可の後に判定） | village_invitations | 宛先 | VillagePaths `Invitations` |
| village | `VillageJoinRequestService.createRequest`:89 | village_join_requests | 申請者 | VillagePaths `JoinRequests` |
| village | `VillageCreationRequestService.createRequest`:75 | village_creation_requests | 申請者 | VillagePaths `CreationRequests` |

## 3. 対象外（P3 と G4 の除外表）

| 経路 | 理由 |
|---|---|
| `TeamService.createProvisionedTeam`:491・`OrganizationService.createProvisionedOrganization`:460 | 運営によるプロビジョニング作成。所属行を作らない（P3: TenantPaths と IT で固定） |
| `TeamOrgAffiliationCommandService` | チームの組織加盟（主体はチーム） |
| `OrganizationMembershipCommitteeCleanupListener` | 組織離脱に伴う委員の退任（更新のみ） |
| `VillageUserCleanerEventListener` | 退会時の村所属の整理（更新のみ） |
| `SharedInviteTokenIssuer` | 宛先の無い共有招待リンク。発行時は宛先が無いので判定しない。**受諾時**に `InviteAcceptanceFacade.accept`（使用回数の確保より前）と合流点（`MembershipGrantService.grantRole`・`MembershipService.join`）で拒否する（RolePaths `InviteAcceptance`） |
| `TeamFriendsService.follow`（team→team） | チームのフォロー（follows の主体がチーム）。G4 除外表に載せる |
| 公式村の作成者としての村長所属（`VillageCreationRequestService.createRequest` の `type=OFFICIAL`） | **唯一の例外**（マスター決定 2026-11-26）。運営が認定・運営する公式村は運営アカウントが村長として運用するため。範囲は「公式村・作成者本人・村長」の3条件を満たす所属行だけ（述語は Policy `isOfficialVillageCreatorHeadman` の1箇所に集約し、作成時の所属・所属プロフィールの公開設定更新・点検 SQL が共有する。点検 SQL の NOT EXISTS 条件と Java の述語はコメントで対応付けている）。公式村でも村長以外のロールへの変更（自己降格を含む）は 403 MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN。運営アカウントが他の村・他の経路（招待受諾・参加申請・承認）で所属することと、通常村を作って村長になることは 403 のまま（VillagePaths で固定）。G4 は例外メソッドを使えるクラスを Slot / ProfileService の2クラスに限定している（入村枠の例外用メソッドは package-private で、admitOfficialVillageCreator は村の種別・作成者を自前で確認する）。点検 SQL は公式村の作成者の村長所属を 0 件判定から除く |

## 4. 決定事項（殿・マスター 2026-11-26）

- `social.FollowService.follow`（利用者のタイムライン上の個人フォロー）も**禁止対象に含める**。G4 の生成シンクに `FollowEntity` を足し、`FollowService` に Policy を通した。
- `TeamFriendsService`（チーム→チームのフォロー）は主体がチームなので対象外（除外表に理由付きで記載）。
- **停止中・非 ACTIVE の運営アカウントも対象**。Policy は状態を問わず SYSTEM_ADMIN ロールの有無で判定する。
- **公式村（OFFICIAL）に限り、運営アカウントが作成者として村長に所属するのは許す**（2026-11-26 マスター決定）。例外の範囲は作成時の作成者所属だけ。
- 招待・打診の宛先が運営アカウントかどうかの判定は、**認可の後**に置く（委員会招集・DM の承諾型招待・オーナー移管の打診は村招待と同じ扱い。認可の無い者へ宛先の属性を漏らさない）。

## 5. 既存の所属行の点検（本番は未構築・殿が本番前に実行）

- 点検 SQL: `backend/src/test/resources/platform_account_affiliation/inspection.sql`。1 行でも返れば、運営アカウントがテナントに所属している（または所属待ちが残っている）。
- 開発・CI: Flyway 実スキーマ（シード・バックフィル後）で 0 件であることを `PlatformAccountAffiliationInspectionFlywayIT` が常時固定する。
- 本番（未構築。台帳 CMP-260826-2129）: 構築後・公開前に殿が点検 SQL を 1 回実行して 0 件を確認する。足軽・家老は本番で実行しない。0 件でなければ、運営アカウントを運営専用に整理（所属行の削除・退会）してから公開する。
- 順序の注意: 本禁止（Z-A PR2）は、共通点の SYSTEM_ADMIN 短絡の撤去（Z-A PR3）より**前に**マージすること（短絡の撤去で `ALREADY_MEMBER` 判定等が運営アカウントを通すようになるため）。
