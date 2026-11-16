package com.mannschaft.app.workflow;

import com.mannschaft.app.shiftbudget.ShiftBudgetCmp2152RealDbTestBase;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 試練 33 — {@link WorkflowRequestService#startOrResumeForSource} の冪等と並行を実 DB で検査する。
 *
 * <p>確定設計 r3(3)(4): 1TX・workflow Repository のみ。</p>
 * <ol>
 *   <li>source ロック行（UNIQUE(source_type, source_id)）を作成＋FOR UPDATE（UNIQUE 違反なら再ロック）</li>
 *   <li>同 source の生存申請を一覧取得し、最古を正とする（2 件以上は診断ログ）</li>
 *   <li>無ければ作成と提出を同 TX で行う</li>
 *   <li>DRAFT なら提出を再開する</li>
 *   <li>提出済み以降は何もしない</li>
 *   <li>申請 ID と状態を返す</li>
 * </ol>
 * <p>既存申請データに一意制約は付けない（既存重複の恐れ）ため、重複排除はロック行で行う。
 * 本試練は 2 スレッドを同時に走らせて、その直列化が実際に効くことを見る。</p>
 */
@DisplayName("CMP-260909-2152 試練33 startOrResumeForSource の冪等と並行（実DB）")
class WorkflowRequestStartOrResumeForSourceIT extends ShiftBudgetCmp2152RealDbTestBase {

    private static final String SOURCE_TYPE = "BUDGET_THRESHOLD_ALERT";

    @Autowired private WorkflowRequestService workflowRequestService;
    @Autowired private WorkflowRequestRepository workflowRequestRepository;

    @Test
    @DisplayName("試練33 同じ source を 2 スレッドが同時に起動しても、申請は 1 件・ステップも 1 組で、両者に同じ申請 ID が返る")
    void 同時起動でも申請は1件() throws Exception {
        Long templateId = seedWorkflowTemplate(false);
        Long sourceId = nextId();

        CyclicBarrier start = new CyclicBarrier(2);
        List<WorkflowRequestResponse> results = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable task = () -> {
            try {
                start.await(30, TimeUnit.SECONDS);
                results.add(startOrResume(templateId, sourceId));
            } catch (Throwable t) {
                errors.add(t);
            }
        };
        Thread a = new Thread(task, "cmp2152-src-A");
        Thread b = new Thread(task, "cmp2152-src-B");
        a.start();
        b.start();
        a.join(60_000);
        b.join(60_000);

        assertThat(errors).as("UNIQUE 違反などを呼び出し側へ漏らさず、ロック行で直列化して解決する").isEmpty();
        List<Long> live = liveWorkflowRequestIds(SOURCE_TYPE, sourceId);
        assertThat(live).as("source ロック無しだと両スレッドが『未起票』と判定して 2 件立つ").hasSize(1);
        assertThat(results).extracting(WorkflowRequestResponse::getId).containsOnly(live.get(0));
        assertThat(workflowRequestStatus(live.get(0))).isEqualTo("IN_PROGRESS");
        assertThat(workflowRequestStepCount(live.get(0))).as("提出が 2 回走るとステップが二重に作られる").isEqualTo(1);
    }

    @Test
    @DisplayName("試練33 同じ source の DRAFT があれば、その申請 ID で提出を再開し新しい申請を作らない")
    void 既存DRAFTは提出を再開する() {
        Long templateId = seedWorkflowTemplate(false);
        Long sourceId = nextId();
        Long draftId = seedRequest(templateId, sourceId);

        WorkflowRequestResponse result = startOrResume(templateId, sourceId);

        assertThat(result.getId()).isEqualTo(draftId);
        assertThat(liveWorkflowRequestIds(SOURCE_TYPE, sourceId)).containsExactly(draftId);
        assertThat(workflowRequestStatus(draftId)).isEqualTo("IN_PROGRESS");
        assertThat(workflowRequestStepCount(draftId)).isEqualTo(1);
    }

    @Test
    @DisplayName("試練33 提出済み以降の申請があれば何もせず同じ ID を返す（ステップを作り直さない）")
    void 提出済みなら何もしない() {
        Long templateId = seedWorkflowTemplate(false);
        Long sourceId = nextId();
        WorkflowRequestResponse first = startOrResume(templateId, sourceId);

        WorkflowRequestResponse again = startOrResume(templateId, sourceId);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(liveWorkflowRequestIds(SOURCE_TYPE, sourceId)).containsExactly(first.getId());
        assertThat(workflowRequestStepCount(first.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("試練33 既存データに同 source の生存申請が 2 件あれば最古を正として返し、新規作成しない")
    void 既存重複は最古を正とする() {
        Long templateId = seedWorkflowTemplate(false);
        Long sourceId = nextId();
        Long oldest = seedRequest(templateId, sourceId);
        Long newer = seedRequest(templateId, sourceId);

        WorkflowRequestResponse result = startOrResume(templateId, sourceId);

        assertThat(result.getId()).isEqualTo(oldest);
        assertThat(liveWorkflowRequestIds(SOURCE_TYPE, sourceId)).containsExactly(oldest, newer);
        assertThat(workflowRequestStatus(oldest)).isEqualTo("IN_PROGRESS");
        assertThat(workflowRequestStatus(newer)).as("正でない重複には触らない").isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("試練33 作成は通るが提出で落ちたら、申請もステップも残さない（作成と提出は同 TX）")
    void 提出失敗は作成ごと巻き戻る() {
        Long templateId = seedWorkflowTemplate(true);
        Long sourceId = nextId();

        Throwable thrown = null;
        try {
            startOrResume(templateId, sourceId);
        } catch (Throwable t) {
            thrown = t;
        }

        assertThat(thrown).as("提出失敗は呼び出し側（段取り役）へ失敗として伝わる").isNotNull();
        assertThat(thrown).as("骨格のまま（未実装）は失敗の意味が違う").isNotInstanceOf(UnsupportedOperationException.class);
        assertThat(liveWorkflowRequestIds(SOURCE_TYPE, sourceId)).isEmpty();
        assertThat(workflowRequestCountForTemplate(templateId)).isZero();
    }

    private WorkflowRequestResponse startOrResume(Long templateId, Long sourceId) {
        return workflowRequestService.startOrResumeForSource(
                "ORGANIZATION", orgId, templateId, SOURCE_TYPE, sourceId,
                "CMP2152 超過承認 source#" + sourceId, null);
    }

    private Long seedRequest(Long templateId, Long sourceId) {
        return workflowRequestRepository.save(WorkflowRequestEntity.builder()
                .templateId(templateId)
                .scopeType("ORGANIZATION")
                .scopeId(orgId)
                .title("CMP2152 既存下書き")
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .build()).getId();
    }
}
