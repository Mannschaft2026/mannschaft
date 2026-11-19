# N+1 型 DB アクセス棚卸し（2026-11）

作成日: 2026-11-17

偵察方法: `backend/src/main/java` を対象に、ループ（for / forEach / stream）内の Repository・他 Service 呼び出し（単件取得・count・exists・save）を静的に走査し、件数の出所（ページ上限・`PageRequest.of(0,10000)`・`Pageable.unpaged()`・リクエスト入力）で優先度を付けた。LAZY 関連起因の N+1 は検出なし。解消は台帳の B1〜B4 戦役で扱う。規約は [05 データアクセス](../conventions/backend/05-data-access-dev-env.md) を参照。

---

前提: application.yml の max-page-size: 100 で Controller の Pageable は上限100。内部の PageRequest.of(0,10000) / Pageable.unpaged() には効かない。LAZY 関連起因の N+1 は検出なし。

| 優先度 | 戦役 | file:line | パターン | 件数の出所/上限 | 置換案 |
|---|---|---|---|---|---|
| 高 | B1 | schedule/service/ScheduleAttendanceService.java:800-802 | buildAttendanceStats 二重ループで findByScheduleIdAndUserId（メンバー×予定） | メンバー PageRequest(0,10000)×期間内予定全件 | findByScheduleIdIn 1回→Map または GROUP BY user_id,status |
| 高 | B1 | schedule/service/ScheduleAttendanceService.java:758-759 | 自分の出欠統計で予定ごとに findByScheduleIdAndUserId | 期間内全予定・上限なし | findByUserIdAndScheduleIdIn |
| 高 | B1 | event/service/EventService.java:349-356 (getStats) | イベントごとに count 4本 | PageRequest(0,10000)→最大4万クエリ | event_id,status の GROUP BY |
| 高 | B1 | activity/service/ActivityStatsService.java:97-99 | 結果ごとに findByActivityResultIdOrderByCreatedAtAsc | 最大1万件 | findByActivityResultIdIn / GROUP BY |
| 高 | B1 | activity/service/ActivityStatsService.java:181-182 (CSV) | countByActivityResultId | 最大5000件 | GROUP BY result_id IN |
| 高 | B2 | membership/query/MemberQueryDispatcher.java:271,275,286,322,348 | roleNameFor→roleRepository.findById ＋ findMemberSummaryById を1行ごと | Pageable.unpaged() 全メンバー | 既存 findMemberSummariesByIds(:379) と roleNamesFor に置換 |
| 高 | B2 | bulletin/service/BulletinReadStatusService.java:195-205（全既読） | スレッドごとに save＋findById＋save | スコープ内全スレッド | saveAll＋UPDATE … WHERE id IN |
| 高 | B2 | survey/service/SurveyResultService.java:264 (CSV) | 回答者ごとに userRepository.findById | 全回答者 | findAllById |
| 高 | B2 | survey/service/SurveyResultService.java:300 | 選択回答ごとに optionRepository.findById | 回答者×選択数 | options を Map 化 |
| 高 | B2 | forms/service/FormCsvExportService.java:105-106 | 提出ごとに findBySubmissionId | 全提出 | findBySubmissionIdIn |
| 高 | B2 | chat/service/ChatChannelService.java:381-382 (deleteChannel) | メッセージごとに attachmentRepository.findByMessageId | 全メッセージ | findByMessageIdIn |
| 高 | B2 | chat/service/ChatChannelService.java:584-591（履歴コピー） | メッセージごとに save | 全トップレベルメッセージ | saveAll |
| 高 | B2 | filesharing/service/SharedFolderQueryService.java:270-280 (deleteFolder) | フォルダごとに findByFolderId、ファイルごとに save | 最大10,000フォルダ | findByFolderIdIn＋saveAll/bulk UPDATE |
| 高 | B2 | dashboard/service/DashboardService.java:474-475 | スレッドごとに existsByThreadIdAndUserId | 最大100だがホットパス | readStatusRepository.findReadThreadIds 一括 |
| 高 | B3 | schedule/batch/ProxyDelegationCleanupBatchService.java:73-74,99-100 | 委任ごとに exists ×2 | 有効委任全件 | IN で一括→Set |
| 高 | B3 | quickmemo/service/TagUsageCountReconcileBatchService.java:56-60 | タグごとに count×2 | 最大1万タグ | GROUP BY＋一括 UPDATE |
| 高 | B3 | todo/batch/MilestoneReconciliationBatch.java:94,102-103 | findById＋count×2 | 全マイルストーン | GROUP BY＋findAllById |
| 高 | B3 | recruitment/service/RecruitmentNoShowDetectBatch.java:81-96 | 参加者ごとに find＋save×2 | 上限なし | findByParticipantIdIn＋saveAll |
| 高 | B3 | gamification/service/GamificationPointService.java:251-266 (adminResetPoints) | ユーザーごとに sum＋save | スコープ全ユーザー | GROUP BY SUM＋saveAll |
| 中 | B4 | survey/service/SurveyService.java:522-528,546-552 | exists＋save | ユーザー入力・AddTargets/AddResultViewers に @Size なし | @Size 付与＋IN 差分 saveAll |
| 中 | B4 | chat/service/ChatMemberService.java:75-84 | exists＋save | AddMemberRequest に @Size なし | 同上 |
| 中 | B4 | moderation/service/ReportActionService.java:169-179 | findById＋save | BulkResolveRequest に @Size なし | @Size＋findAllById |
| 中 | B4 | activity/service/ActivityResultService.java:603-619 | find＋save | AddParticipantsRequest に @Size なし | @Size＋IN＋saveAll |
| 中 | B4 | contact/service/ContactableMemberService.java:126-138 | メンバーごとに4本 | ページ | IN 一括→Set |
| 中 | B4 | service/service/ServiceRecordService.java:480-491 | 1行3本 | Page≤100 | IN / teamId Map キャッシュ |
| 中 | B4 | chart/service/ChartRecordService.java:107,325 | countByChartRecordId | Page≤100 | GROUP BY |
| 中 | B4 | advertising/service/AdSegmentService.java:50-70 | チームごとに4本 | Page≤100 | GROUP BY |
| 中 | B4 | forms/service/FormSubmissionService.java:111,132,532 / FormTemplateService.java:67 | findBySubmissionId / fields | Page≤100 | …In |
| 中 | B4 | workflow/service/WorkflowTemplateService.java:63-64,80-81 | steps＋fields | Page≤100 | …In |
| 中 | B4 | ticket/service/TicketBookService.java:215,314,601,626 | productRepository.findById | 自分のチケット全件 | findAllById |
| 中 | B4 | tournament/service/StandingsQueryService.java:54,109-123,177-186 | findById 多数 | 参加履歴上限なし | findAllById／IN |
| 中 | B4 | todo/service/ProjectService.java:260-261,644-645 | count×2 | マイルストーン数 | GROUP BY |
| 中 | B4 | knowledgebase/service/KbPageService.java:255-256 | 同一クエリを子孫数だけ再実行 | 上限なし | ループ外へ |
| 中 | B4 | circulation/service/CirculationService.java:990-991 / CirculationExportAsyncExecutor.java:135-145 | findMemberSummaryById 等 | 受信者数 | ByIds / In |
| 中 | B4 | timetable/personal/listener/TeamSlotNoteNotifyListener.java:144-146 | settings findById | チーム全員 | findAllById |
| 中 | B4 | tournament/fee/TournamentFeePaymentService.java:123-144 | exists×チーム数ほか | 全参加費 | IN / findAllById |
| 中 | B4 | schedule/service/PersonalScheduleReminderService.java:61-62 | findById | 期限到来全件 | findAllById |
| 中 | B4 | translation/service/StaleTranslationBatchService.java:58-67 | findById＋save | 全件 | findAllById＋saveAll |
| 中 | B4 | recruitment/service/RecruitmentPenaltyRecomputeBatch.java:95,107 | findById＋count | チャンク | Map |
| 中 | B4 | weather/job/WeatherLocationBootstrapJob.java:115 | findByUserIdAndLabel | 全ユーザー | IN |
| 中 | B4 | webhook/service/WebhookDeliveryService.java:101-102 | endpoint findById | 全スコープ購読・イベント毎 | JOIN で scope 絞り |
| 中 | B4 | supporter/service/SupporterService.java:213,248 | userRepository.findById | Page≤100 | findAllById |
| 中 | B4 | advertising/service/AdvertiserAccountService.java:138 / AdCreditLimitRequestService.java:98 | findById | Page≤100 | findAllById |
| 中 | B4 | matching/service/MatchProposalService.java:361 | findById | Page≤100 | findAllById |
| 中 | B4 | organization/service/OrganizationService.java:826 / OrganizationHierarchyService.java:231 | countByOrganizationId | Page | GROUP BY |
| 中 | B4 | committee/controller/CommitteeController.java:70-73 | Service 経由 2本 | Page≤100 | IN |
| 中 | B4 | promotion/service/CouponService.java:143 | findById | 保有全件 | findAllById |
| 中 | B4 | cms/service/BlogSeriesService.java:56 | countBySeriesId | 全件 | GROUP BY |
| 中 | B4 | role/service/PermissionGroupService.java:324-326 (hasPermission) | permission findById | 認可経路 | findByIdIn |
| 中 | B4 | reservation/service/EmergencyClosureService.java:143-146 | save＋getSlotEntity | 対象日全予約 | findAllById＋saveAll |
| 中 | B4 | survey/service/SurveyResponseService.java:241 | option findById | 選択数 | Map |
| 低 | 議題A | actionmemo/service/ActionMemoPublishingService.java:114-119 | findById＋save | 実質数件 | 別戦役で対応中 |
| 低 | - | payment/service/PaymentGateService.java:169,181 ほか約40箇所 | 実質数件 | - | 横展開時に任意 |

ループ内 save/delete 約343箇所の大半はリクエスト内子要素の逐次保存（件数少）で低扱い。
