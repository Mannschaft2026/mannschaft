package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSaveRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSummaryResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftUpdateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableDraftAudienceMode;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDraftEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261126-1558 試練: {@link ConfirmableNotificationDraftService} の単体テスト。
 *
 * <h2>AC ↔ テスト</h2>
 * <ul>
 *   <li>AC1: {@link CreateGetListUpdateDelete} — 作成・取得・一覧（updated_at 降順・本文冒頭100文字）・更新・削除</li>
 *   <li>AC2: {@link Limit} — 10件までは作成でき、11件目は DRAFT_LIMIT_EXCEEDED。削除後は再び作成できる</li>
 *   <li>AC3: {@link OptimisticLock} — baseVersion 一致で更新、不一致で DRAFT_VERSION_CONFLICT
 *       （version が進む／同内容なら進まない、は実DBの {@code ConfirmableNotificationDraftPersistenceIT}）</li>
 *   <li>AC4: {@link RequestValidation} — title 200文字超・body 10,000文字超・targets 200件超は検証エラー</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfirmableNotificationDraftService 単体テスト（CMP-261126-1558）")
class ConfirmableNotificationDraftServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final UUID DRAFT_ID = UUID.fromString("0190c0de-0000-7000-8000-000000000001");

    @Mock
    private ConfirmableNotificationDraftRepository draftRepository;

    @InjectMocks
    private ConfirmableNotificationDraftService draftService;

    private static ConfirmableNotificationDraftEntity draft(UUID id, String title, String body, long version,
                                                            Instant updatedAt) {
        ConfirmableNotificationDraftEntity entity = ConfirmableNotificationDraftEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .userId(USER_ID)
                .title(title)
                .body(body)
                .priority(ConfirmableNotificationPriority.NORMAL)
                .audienceMode(ConfirmableDraftAudienceMode.DEFAULT)
                .version(version)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
        entity.setId(id);
        return entity;
    }

    private static ConfirmableNotificationDraftSaveRequest saveRequest(String title, String body) {
        ConfirmableNotificationDraftSaveRequest request = new ConfirmableNotificationDraftSaveRequest();
        request.setTitle(title);
        request.setBody(body);
        request.setPriority(ConfirmableNotificationPriority.HIGH);
        request.setDeadlineAt(Instant.parse("2027-01-10T06:00:00Z"));
        request.setAudienceMode(ConfirmableDraftAudienceMode.TARGETS);
        request.setSelectedTargets(List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, TEAM_ID)));
        request.setSelectedTemplateId(77L);
        return request;
    }

    private static ConfirmableNotificationDraftUpdateRequest updateRequest(String title, String body, Long baseVersion) {
        ConfirmableNotificationDraftUpdateRequest request = new ConfirmableNotificationDraftUpdateRequest();
        request.setTitle(title);
        request.setBody(body);
        request.setPriority(ConfirmableNotificationPriority.URGENT);
        request.setAudienceMode(ConfirmableDraftAudienceMode.DEFAULT);
        request.setBaseVersion(baseVersion);
        return request;
    }

    private void stubSaveEcho() {
        given(draftRepository.saveAndFlush(any(ConfirmableNotificationDraftEntity.class))).willAnswer(invocation -> {
            ConfirmableNotificationDraftEntity e = invocation.getArgument(0);
            if (e.getId() == null) {
                e.setId(DRAFT_ID);
            }
            return e;
        });
    }

    private static ErrorAssert assertBusinessError(Throwable thrown) {
        return new ErrorAssert(thrown);
    }

    private record ErrorAssert(Throwable thrown) {
        void is(ConfirmableNotificationErrorCode expected) {
            assertThat(thrown).as("BusinessException(%s) が投げられること", expected.getCode())
                    .isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(expected);
        }
    }

    // =====================================================================
    // AC1
    // =====================================================================

    @Nested
    @DisplayName("AC1: 作成・取得・一覧・更新・削除")
    class CreateGetListUpdateDelete {

        @Test
        @DisplayName("作成: パスのスコープと JWT の userId で保存し、全項目を返す")
        void create_savesWithScopeAndUserFromArguments() {
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(0L);
            stubSaveEcho();

            ConfirmableNotificationDraftDetailResponse response = draftService.create(
                    ScopeType.TEAM, TEAM_ID, USER_ID, saveRequest("件名", "本文"));

            ArgumentCaptor<ConfirmableNotificationDraftEntity> captor =
                    ArgumentCaptor.forClass(ConfirmableNotificationDraftEntity.class);
            verify(draftRepository).saveAndFlush(captor.capture());
            ConfirmableNotificationDraftEntity saved = captor.getValue();
            assertThat(saved.getScopeType()).isEqualTo(ScopeType.TEAM);
            assertThat(saved.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getTitle()).isEqualTo("件名");
            assertThat(saved.getBody()).isEqualTo("本文");
            assertThat(saved.getPriority()).isEqualTo(ConfirmableNotificationPriority.HIGH);
            assertThat(saved.getDeadlineAt()).isEqualTo(Instant.parse("2027-01-10T06:00:00Z"));
            assertThat(saved.getAudienceMode()).isEqualTo(ConfirmableDraftAudienceMode.TARGETS);
            assertThat(saved.getSelectedTemplateId()).isEqualTo(77L);

            assertThat(response.getId()).isEqualTo(DRAFT_ID);
            assertThat(response.getTitle()).isEqualTo("件名");
            assertThat(response.getSelectedTargets())
                    .extracting(ConfirmableTargetSpec::getType, ConfirmableTargetSpec::getId)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(ConfirmableTargetType.TEAM, TEAM_ID));
        }

        @Test
        @DisplayName("作成: title を省略すると空文字で保存する（title は NOT NULL DEFAULT ''）")
        void create_nullTitleBecomesEmpty() {
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(0L);
            stubSaveEcho();

            draftService.create(ScopeType.TEAM, TEAM_ID, USER_ID, new ConfirmableNotificationDraftSaveRequest());

            ArgumentCaptor<ConfirmableNotificationDraftEntity> captor =
                    ArgumentCaptor.forClass(ConfirmableNotificationDraftEntity.class);
            verify(draftRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("");
            assertThat(captor.getValue().getPriority()).isEqualTo(ConfirmableNotificationPriority.NORMAL);
        }

        @Test
        @DisplayName("取得: 本人・同スコープの下書きの全項目と version を返す")
        void get_returnsAllFieldsAndVersion() {
            ConfirmableNotificationDraftEntity entity =
                    draft(DRAFT_ID, "件名", "本文", 3L, Instant.parse("2026-11-01T00:00:00Z"));
            given(draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ConfirmableNotificationDraftDetailResponse response =
                    draftService.get(ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID);

            assertThat(response.getId()).isEqualTo(DRAFT_ID);
            assertThat(response.getTitle()).isEqualTo("件名");
            assertThat(response.getBody()).isEqualTo("本文");
            assertThat(response.getVersion()).isEqualTo(3L);
            assertThat(response.getUpdatedAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        }

        @Test
        @DisplayName("取得: 本人・同スコープで見つからなければ DRAFT_NOT_FOUND（他人・他スコープも同じ）")
        void get_notFound() {
            given(draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(any(), any(), anyLong(), anyLong()))
                    .willReturn(Optional.empty());

            assertBusinessError(catchThrowable(() -> draftService.get(ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID)))
                    .is(ConfirmableNotificationErrorCode.DRAFT_NOT_FOUND);
        }

        @Test
        @DisplayName("一覧: updated_at 降順で、要素は id・title・本文の冒頭100文字・updatedAt")
        void list_descendingWithExcerpt() {
            String longBody = "あ".repeat(100) + "い".repeat(50);
            UUID newerId = UUID.fromString("0190c0de-0000-7000-8000-000000000002");
            ConfirmableNotificationDraftEntity newer =
                    draft(newerId, "新しい", longBody, 1L, Instant.parse("2026-11-20T00:00:00Z"));
            ConfirmableNotificationDraftEntity older =
                    draft(DRAFT_ID, "古い", "短い本文", 1L, Instant.parse("2026-11-01T00:00:00Z"));
            given(draftRepository.findByScopeTypeAndScopeIdAndUserIdOrderByUpdatedAtDesc(
                    ScopeType.TEAM, TEAM_ID, USER_ID)).willReturn(List.of(newer, older));

            List<ConfirmableNotificationDraftSummaryResponse> list =
                    draftService.list(ScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(list).extracting(ConfirmableNotificationDraftSummaryResponse::getId)
                    .containsExactly(newerId, DRAFT_ID);
            assertThat(list.get(0).getTitle()).isEqualTo("新しい");
            assertThat(list.get(0).getBodyExcerpt()).as("本文の冒頭100文字").isEqualTo("あ".repeat(100));
            assertThat(list.get(1).getBodyExcerpt()).as("100文字未満はそのまま").isEqualTo("短い本文");
            assertThat(list.get(0).getUpdatedAt()).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"));
        }

        @Test
        @DisplayName("一覧: 本文の冒頭はサロゲートペアを途中で切らずに100文字（コードポイント）で切る")
        void list_excerptCountsCodePoints() {
            String body = "😀".repeat(120);
            given(draftRepository.findByScopeTypeAndScopeIdAndUserIdOrderByUpdatedAtDesc(
                    ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(List.of(draft(DRAFT_ID, "絵文字", body, 1L, Instant.parse("2026-11-01T00:00:00Z"))));

            List<ConfirmableNotificationDraftSummaryResponse> list =
                    draftService.list(ScopeType.TEAM, TEAM_ID, USER_ID);

            assertThat(list.get(0).getBodyExcerpt()).isEqualTo("😀".repeat(100));
        }

        @Test
        @DisplayName("更新: baseVersion が一致すれば内容を書き換えて返す")
        void update_appliesChanges() {
            ConfirmableNotificationDraftEntity entity =
                    draft(DRAFT_ID, "旧件名", "旧本文", 2L, Instant.parse("2026-11-01T00:00:00Z"));
            given(draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            stubSaveEcho();

            ConfirmableNotificationDraftDetailResponse response = draftService.update(
                    ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID, updateRequest("新件名", "新本文", 2L));

            assertThat(response.getTitle()).isEqualTo("新件名");
            assertThat(response.getBody()).isEqualTo("新本文");
            assertThat(response.getPriority()).isEqualTo(ConfirmableNotificationPriority.URGENT);
        }

        @Test
        @DisplayName("削除: 本人・同スコープの条件で削除する")
        void delete_scopedToOwner() {
            given(draftRepository.deleteByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(1L);

            draftService.delete(ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID);

            verify(draftRepository).deleteByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID);
            verify(draftRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("削除: 本人・同スコープで1件も消えなければ DRAFT_NOT_FOUND（他人・他スコープも同じ）")
        void delete_notFound() {
            given(draftRepository.deleteByIdAndScopeTypeAndScopeIdAndUserId(any(), any(), anyLong(), anyLong()))
                    .willReturn(0L);

            assertBusinessError(catchThrowable(() -> draftService.delete(ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID)))
                    .is(ConfirmableNotificationErrorCode.DRAFT_NOT_FOUND);
        }
    }

    // =====================================================================
    // AC2
    // =====================================================================

    @Nested
    @DisplayName("AC2: 上限10件")
    class Limit {

        @Test
        @DisplayName("9件保存済みなら10件目は作成できる")
        void tenthIsAllowed() {
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(9L);
            stubSaveEcho();

            draftService.create(ScopeType.TEAM, TEAM_ID, USER_ID, saveRequest("10件目", null));

            verify(draftRepository).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
        }

        @Test
        @DisplayName("10件保存済みなら11件目は DRAFT_LIMIT_EXCEEDED で、何も保存しない")
        void eleventhIsRejected() {
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(10L);

            assertBusinessError(catchThrowable(() -> draftService.create(
                    ScopeType.TEAM, TEAM_ID, USER_ID, saveRequest("11件目", null))))
                    .is(ConfirmableNotificationErrorCode.DRAFT_LIMIT_EXCEEDED);
            verify(draftRepository, never()).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
            verify(draftRepository, never()).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
        }

        @Test
        @DisplayName("上限で弾かれた後に1件削除すれば、また作成できる")
        void createAgainAfterDelete() {
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(10L, 9L);
            given(draftRepository.deleteByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(1L);
            stubSaveEcho();

            assertThatThrownBy(() -> draftService.create(ScopeType.TEAM, TEAM_ID, USER_ID, saveRequest("11件目", null)))
                    .isInstanceOf(BusinessException.class);
            draftService.delete(ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID);
            draftService.create(ScopeType.TEAM, TEAM_ID, USER_ID, saveRequest("削除後", null));

            verify(draftRepository).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
        }

        @Test
        @DisplayName("上限は スコープ×作成者 ごとに数える（別スコープの件数で弾かない）")
        void limitIsCountedPerScopeAndUser() {
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(10L);
            given(draftRepository.countByScopeTypeAndScopeIdAndUserId(ScopeType.ORGANIZATION, TEAM_ID, USER_ID))
                    .willReturn(0L);
            stubSaveEcho();

            draftService.create(ScopeType.ORGANIZATION, TEAM_ID, USER_ID, saveRequest("組織側", null));

            verify(draftRepository).countByScopeTypeAndScopeIdAndUserId(eq(ScopeType.ORGANIZATION), eq(TEAM_ID), eq(USER_ID));
            verify(draftRepository).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
        }
    }

    // =====================================================================
    // AC3
    // =====================================================================

    @Nested
    @DisplayName("AC3: baseVersion による楽観ロック")
    class OptimisticLock {

        @Test
        @DisplayName("baseVersion が保存済みの version と異なれば DRAFT_VERSION_CONFLICT で、何も書き換えない")
        void mismatchIsConflict() {
            ConfirmableNotificationDraftEntity entity =
                    draft(DRAFT_ID, "保存済み", "本文", 5L, Instant.parse("2026-11-01T00:00:00Z"));
            given(draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            assertBusinessError(catchThrowable(() -> draftService.update(
                    ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID, updateRequest("上書き", "本文", 4L))))
                    .is(ConfirmableNotificationErrorCode.DRAFT_VERSION_CONFLICT);
            assertThat(entity.getTitle()).as("競合時は Entity を書き換えない").isEqualTo("保存済み");
            verify(draftRepository, never()).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
            verify(draftRepository, never()).saveAndFlush(any(ConfirmableNotificationDraftEntity.class));
        }

        @Test
        @DisplayName("baseVersion を省略した PUT も DRAFT_VERSION_CONFLICT（無条件上書きを許さない）")
        void missingBaseVersionIsConflict() {
            ConfirmableNotificationDraftEntity entity =
                    draft(DRAFT_ID, "保存済み", "本文", 5L, Instant.parse("2026-11-01T00:00:00Z"));
            given(draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(DRAFT_ID, ScopeType.TEAM, TEAM_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            assertBusinessError(catchThrowable(() -> draftService.update(
                    ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID, updateRequest("上書き", "本文", null))))
                    .is(ConfirmableNotificationErrorCode.DRAFT_VERSION_CONFLICT);
        }

        @Test
        @DisplayName("本人・同スコープの行が無ければ、他人・他スコープと同じ DRAFT_NOT_FOUND（存在を推測させない）")
        void missingRowIsNotFound() {
            given(draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(any(), any(), anyLong(), anyLong()))
                    .willReturn(Optional.empty());

            assertBusinessError(catchThrowable(() -> draftService.update(
                    ScopeType.TEAM, TEAM_ID, USER_ID, DRAFT_ID, updateRequest("x", "y", 1L))))
                    .is(ConfirmableNotificationErrorCode.DRAFT_NOT_FOUND);
        }
    }

    // =====================================================================
    // AC4
    // =====================================================================

    @Nested
    @DisplayName("AC4: 入力の上限（Bean Validation）")
    class RequestValidation {

        private static ValidatorFactory factory;
        private static Validator validator;

        @BeforeAll
        static void setUpValidator() {
            factory = Validation.buildDefaultValidatorFactory();
            validator = factory.getValidator();
        }

        @AfterAll
        static void closeValidator() {
            factory.close();
        }

        private Set<String> violatedProperties(Object request) {
            Set<ConstraintViolation<Object>> violations = validator.validate(request);
            Set<String> props = new java.util.TreeSet<>();
            for (ConstraintViolation<Object> v : violations) {
                props.add(v.getPropertyPath().toString());
            }
            return props;
        }

        private List<ConfirmableTargetSpec> targets(int n) {
            List<ConfirmableTargetSpec> list = new ArrayList<>();
            IntStream.range(0, n).forEach(i -> list.add(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, (long) i + 1)));
            return list;
        }

        @Test
        @DisplayName("上限ちょうど（title 200・body 10,000・targets 200）は通る")
        void boundaryIsValid() {
            ConfirmableNotificationDraftSaveRequest request = saveRequest("a".repeat(200), "b".repeat(10_000));
            request.setSelectedTargets(targets(200));

            assertThat(violatedProperties(request)).isEmpty();
        }

        @Test
        @DisplayName("title が200文字を超えると検証エラー")
        void titleTooLong() {
            assertThat(violatedProperties(saveRequest("a".repeat(201), null))).contains("title");
        }

        @Test
        @DisplayName("body が10,000文字を超えると検証エラー")
        void bodyTooLong() {
            assertThat(violatedProperties(saveRequest("件名", "b".repeat(10_001)))).contains("body");
        }

        @Test
        @DisplayName("selectedTargets が200件を超えると検証エラー")
        void targetsTooMany() {
            ConfirmableNotificationDraftSaveRequest request = saveRequest("件名", null);
            request.setSelectedTargets(targets(201));

            assertThat(violatedProperties(request)).contains("selectedTargets");
        }

        @Test
        @DisplayName("更新リクエストにも同じ上限が掛かり、baseVersion は必須")
        void updateRequestHasSameLimitsAndRequiresBaseVersion() {
            ConfirmableNotificationDraftUpdateRequest request = updateRequest("a".repeat(201), "b".repeat(10_001), null);
            request.setSelectedTargets(targets(201));

            assertThat(violatedProperties(request)).contains("title", "body", "selectedTargets", "baseVersion");
        }
    }
}
