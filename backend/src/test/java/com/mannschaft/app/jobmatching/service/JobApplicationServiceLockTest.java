package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.state.JobApplicationStateMachine;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link JobApplicationService} の取り下げ・不採用のロック順と最新値判定のユニットテスト（CMP-261021-0355）。
 *
 * <p>不採用は受諾と同じ「求人行 FOR UPDATE → 応募行 PESSIMISTIC_WRITE」の順でロックし、取り下げは応募行だけを
 * ロックする。いずれもロック後に読み直した状態で遷移を判定する。実 DB での並行性は
 * {@code JobAcceptanceCapacityConcurrencyIT} が検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobApplicationService 取り下げ・不採用のロック")
class JobApplicationServiceLockTest {

    private static final Long POSTING_ID = 1000L;
    private static final Long APPLICATION_ID = 2000L;
    private static final Long REQUESTER_ID = 100L;
    private static final Long APPLICANT_ID = 200L;

    @Mock private JobApplicationRepository applicationRepository;
    @Mock private JobPostingRepository postingRepository;
    @Mock private JobPolicy jobPolicy;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private EntityManager entityManager;

    private JobApplicationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new JobApplicationService(applicationRepository, postingRepository,
                new JobApplicationStateMachine(), jobPolicy, eventPublisher);
        // @PersistenceContext はコンストラクタ注入されないため、リフレクションで注入する。
        Field emField = JobApplicationService.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(service, entityManager);
    }

    @Test
    @DisplayName("不採用: 求人行 FOR UPDATE → 応募行 PESSIMISTIC_WRITE の順でロックしてから遷移する")
    void 不採用は求人行から応募行の順でロックする() {
        JobApplicationEntity app = application(JobApplicationStatus.APPLIED);
        JobPostingEntity posting = posting();
        given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
        given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
        given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity saved = service.reject(APPLICATION_ID, "理由", REQUESTER_ID);

        assertThat(saved.getStatus()).isEqualTo(JobApplicationStatus.REJECTED);
        InOrder order = inOrder(applicationRepository, postingRepository, entityManager);
        order.verify(applicationRepository).findById(APPLICATION_ID);
        order.verify(postingRepository).findByIdForUpdate(POSTING_ID);
        order.verify(entityManager).refresh(app, LockModeType.PESSIMISTIC_WRITE);
        order.verify(applicationRepository).save(app);
        verify(postingRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("不採用: ロック後に読み直した値が ACCEPTED なら JOB_INVALID_STATE_TRANSITION で上書きしない")
    void 不採用はロック後の最新値で判定する() {
        JobApplicationEntity app = application(JobApplicationStatus.APPLIED);
        JobPostingEntity posting = posting();
        given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
        given(postingRepository.findByIdForUpdate(POSTING_ID)).willReturn(Optional.of(posting));
        given(jobPolicy.canDecideApplication(REQUESTER_ID, posting)).willReturn(true);
        doAnswer(inv -> {
            ((JobApplicationEntity) inv.getArgument(0)).accept(REQUESTER_ID);
            return null;
        }).when(entityManager).refresh(app, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> service.reject(APPLICATION_ID, "理由", REQUESTER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        assertThat(app.getStatus()).isEqualTo(JobApplicationStatus.ACCEPTED);
        verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("取り下げ: 応募行だけを PESSIMISTIC_WRITE でロックし、求人行はロックしない")
    void 取り下げは応募行だけをロックする() {
        JobApplicationEntity app = application(JobApplicationStatus.APPLIED);
        given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        JobApplicationEntity saved = service.withdraw(APPLICATION_ID, APPLICANT_ID);

        assertThat(saved.getStatus()).isEqualTo(JobApplicationStatus.WITHDRAWN);
        InOrder order = inOrder(applicationRepository, entityManager);
        order.verify(applicationRepository).findById(APPLICATION_ID);
        order.verify(entityManager).refresh(app, LockModeType.PESSIMISTIC_WRITE);
        order.verify(applicationRepository).save(app);
        verify(postingRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("取り下げ: ロック後に読み直した値が ACCEPTED なら JOB_INVALID_STATE_TRANSITION で上書きしない")
    void 取り下げはロック後の最新値で判定する() {
        JobApplicationEntity app = application(JobApplicationStatus.APPLIED);
        given(applicationRepository.findById(APPLICATION_ID)).willReturn(Optional.of(app));
        doAnswer(inv -> {
            ((JobApplicationEntity) inv.getArgument(0)).accept(REQUESTER_ID);
            return null;
        }).when(entityManager).refresh(app, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> service.withdraw(APPLICATION_ID, APPLICANT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION));
        assertThat(app.getStatus()).isEqualTo(JobApplicationStatus.ACCEPTED);
        verify(applicationRepository, never()).save(any());
    }

    private JobApplicationEntity application(JobApplicationStatus status) {
        JobApplicationEntity entity = JobApplicationEntity.builder()
                .jobPostingId(POSTING_ID)
                .applicantUserId(APPLICANT_ID)
                .selfPr("test pr")
                .status(status)
                .appliedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
        setId(entity, APPLICATION_ID);
        return entity;
    }

    private JobPostingEntity posting() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 10, 9, 0);
        JobPostingEntity entity = JobPostingEntity.builder()
                .teamId(10L)
                .createdByUserId(REQUESTER_ID)
                .title("テスト求人")
                .description("テスト内容")
                .workLocationType(WorkLocationType.ONSITE)
                .workAddress("東京都")
                .workStartAt(base)
                .workEndAt(base.plusHours(3))
                .rewardType(RewardType.LUMP_SUM)
                .baseRewardJpy(3000)
                .capacity(2)
                .applicationDeadlineAt(base.minusDays(1))
                .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                .status(JobPostingStatus.OPEN)
                .build();
        setId(entity, POSTING_ID);
        return entity;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("id フィールド設定失敗", e);
        }
    }
}
