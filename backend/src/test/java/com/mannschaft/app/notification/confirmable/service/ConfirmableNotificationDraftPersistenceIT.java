package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSaveRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftUpdateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableDraftAudienceMode;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * CMP-261126-1558 試練: 下書きの永続化を実 MySQL で検証する（モックなし）。
 *
 * <h2>AC ↔ テスト</h2>
 * <ul>
 *   <li>AC2: {@link #ac2_実DBで10件まで作成でき11件目は上限超過_削除後は再作成できる()}</li>
 *   <li>AC3: {@link #ac3_内容を変えるとversionが1つ進み_同じ内容ならversionは進まない()}・
 *       {@link #ac3_古いbaseVersionの更新はVERSION_CONFLICTで保存済みの内容を壊さない()}</li>
 *   <li>AC6: {@link #ac6_selectedTargetsのJSONと全項目が往復する()}</li>
 *   <li>AC9: {@link #ac9_90日を超えたものだけが掃除バッチで消える()}・{@link #ac9_境界_90日ちょうどの前後()}</li>
 * </ul>
 *
 * <p>下書きの user_id はクロスドメイン参照で FK が無いため、利用者行を作らず一意な番号を使う。
 * Service はトランザクションをまたいで呼び、version の進みは実際のコミット結果で測る。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261126-1558 下書き永続化 試練（AC2・AC3・AC6・AC9）")
class ConfirmableNotificationDraftPersistenceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConfirmableNotificationDraftService draftService;
    @Autowired
    private ConfirmableNotificationDraftCleanupBatchService cleanupBatchService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private Long teamId;

    @BeforeEach
    void setUp() {
        userId = 8_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        teamId = 7_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM confirmable_notification_drafts WHERE user_id = ?", userId);
    }

    private ConfirmableNotificationDraftSaveRequest saveRequest(String title) {
        ConfirmableNotificationDraftSaveRequest request = new ConfirmableNotificationDraftSaveRequest();
        request.setTitle(title);
        request.setBody("本文");
        return request;
    }

    private ConfirmableNotificationDraftUpdateRequest updateRequest(String title, String body, Long baseVersion) {
        ConfirmableNotificationDraftUpdateRequest request = new ConfirmableNotificationDraftUpdateRequest();
        request.setTitle(title);
        request.setBody(body);
        request.setBaseVersion(baseVersion);
        return request;
    }

    private long countOwn() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_drafts WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    private UUID insertDraftUpdatedAt(Instant updatedAt) {
        UUID id = UuidV7.generate();
        jdbc.update("INSERT INTO confirmable_notification_drafts "
                        + "(id, scope_type, scope_id, user_id, title, priority, audience_mode, version, created_at, updated_at) "
                        + "VALUES (UNHEX(?), 'TEAM', ?, ?, '掃除対象', 'NORMAL', 'DEFAULT', 0, ?, ?)",
                id.toString().replace("-", ""), teamId, userId, utcLiteral(updatedAt), utcLiteral(updatedAt));
        return id;
    }

    /** Hibernate の格納基準（hibernate.jdbc.time_zone=UTC）に合わせ、ドライバの TZ 変換を経ない UTC 文字列で渡す。 */
    private static String utcLiteral(Instant instant) {
        return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC).toString().replace('T', ' ');
    }

    private boolean exists(UUID id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM confirmable_notification_drafts WHERE id = UNHEX(?)",
                Long.class, id.toString().replace("-", ""));
        return n != null && n > 0;
    }

    @Test
    @DisplayName("AC6: selected_targets の JSON と全項目が、作成→取得で往復する")
    void ac6_selectedTargetsのJSONと全項目が往復する() {
        ConfirmableNotificationDraftSaveRequest request = saveRequest("往復");
        request.setPriority(ConfirmableNotificationPriority.URGENT);
        request.setDeadlineAt(Instant.parse("2027-03-01T01:02:03.456789Z"));
        request.setAudienceMode(ConfirmableDraftAudienceMode.TARGETS);
        request.setSelectedTargets(List.of(
                new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 12L),
                new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 34L)));
        UUID groupId = UuidV7.generate();
        request.setSelectedGroupId(groupId);
        request.setSelectedTemplateId(56L);

        ConfirmableNotificationDraftDetailResponse created =
                draftService.create(ScopeType.TEAM, teamId, userId, request);
        ConfirmableNotificationDraftDetailResponse loaded =
                draftService.get(ScopeType.TEAM, teamId, userId, created.getId());

        assertThat(loaded.getTitle()).isEqualTo("往復");
        assertThat(loaded.getBody()).isEqualTo("本文");
        assertThat(loaded.getPriority()).isEqualTo(ConfirmableNotificationPriority.URGENT);
        assertThat(loaded.getDeadlineAt()).isEqualTo(Instant.parse("2027-03-01T01:02:03.456789Z"));
        assertThat(loaded.getAudienceMode()).isEqualTo(ConfirmableDraftAudienceMode.TARGETS);
        assertThat(loaded.getSelectedTargets())
                .extracting(ConfirmableTargetSpec::getType, ConfirmableTargetSpec::getId)
                .containsExactly(tuple(ConfirmableTargetType.TEAM, 12L), tuple(ConfirmableTargetType.ORGANIZATION, 34L));
        assertThat(loaded.getSelectedGroupId()).isEqualTo(groupId);
        assertThat(loaded.getSelectedTemplateId()).isEqualTo(56L);
        assertThat(loaded.getVersion()).isNotNull();

        String raw = jdbc.queryForObject("SELECT CAST(selected_targets AS CHAR) FROM confirmable_notification_drafts "
                + "WHERE id = UNHEX(?)", String.class, created.getId().toString().replace("-", ""));
        assertThat(raw).as("DB には [{type,id}] の JSON で保存する").contains("\"type\"").contains("\"TEAM\"")
                .contains("\"id\"").contains("12");
    }

    @Test
    @DisplayName("AC3: 内容を変えると version が1つ進み、同じ内容なら version は進まない")
    void ac3_内容を変えるとversionが1つ進み_同じ内容ならversionは進まない() {
        ConfirmableNotificationDraftDetailResponse created =
                draftService.create(ScopeType.TEAM, teamId, userId, saveRequest("初版"));
        long v0 = created.getVersion();

        ConfirmableNotificationDraftDetailResponse changed = draftService.update(
                ScopeType.TEAM, teamId, userId, created.getId(), updateRequest("第2版", "本文", v0));
        assertThat(changed.getVersion()).as("応答の version が1つ進む").isEqualTo(v0 + 1);
        assertThat(draftService.get(ScopeType.TEAM, teamId, userId, created.getId()).getVersion())
                .as("保存済みの version も1つ進む").isEqualTo(v0 + 1);

        ConfirmableNotificationDraftDetailResponse same = draftService.update(
                ScopeType.TEAM, teamId, userId, created.getId(), updateRequest("第2版", "本文", v0 + 1));
        assertThat(same.getVersion()).as("同じ内容の保存では version は進まない").isEqualTo(v0 + 1);
        assertThat(draftService.get(ScopeType.TEAM, teamId, userId, created.getId()).getVersion())
                .isEqualTo(v0 + 1);
    }

    @Test
    @DisplayName("AC3: 古い baseVersion の更新は VERSION_CONFLICT で、保存済みの内容を壊さない")
    void ac3_古いbaseVersionの更新はVERSION_CONFLICTで保存済みの内容を壊さない() {
        ConfirmableNotificationDraftDetailResponse created =
                draftService.create(ScopeType.TEAM, teamId, userId, saveRequest("初版"));
        long v0 = created.getVersion();
        draftService.update(ScopeType.TEAM, teamId, userId, created.getId(), updateRequest("タブA", "本文", v0));

        Throwable thrown = catchThrowable(() -> draftService.update(
                ScopeType.TEAM, teamId, userId, created.getId(), updateRequest("タブB", "本文", v0)));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.DRAFT_VERSION_CONFLICT);
        assertThat(draftService.get(ScopeType.TEAM, teamId, userId, created.getId()).getTitle())
                .as("後から来た古い版で上書きしない").isEqualTo("タブA");
    }

    @Test
    @DisplayName("AC2: 実DBで10件まで作成でき、11件目は上限超過。削除後は再び作成できる")
    void ac2_実DBで10件まで作成でき11件目は上限超過_削除後は再作成できる() {
        UUID first = null;
        for (int i = 1; i <= 10; i++) {
            ConfirmableNotificationDraftDetailResponse created =
                    draftService.create(ScopeType.TEAM, teamId, userId, saveRequest("下書き" + i));
            if (first == null) {
                first = created.getId();
            }
        }
        assertThat(countOwn()).isEqualTo(10);

        Throwable thrown = catchThrowable(() ->
                draftService.create(ScopeType.TEAM, teamId, userId, saveRequest("11件目")));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.DRAFT_LIMIT_EXCEEDED);
        assertThat(countOwn()).as("11件目は保存されない").isEqualTo(10);

        // 別スコープ（同じ番号の組織）は別勘定
        draftService.create(ScopeType.ORGANIZATION, teamId, userId, saveRequest("組織側"));

        draftService.delete(ScopeType.TEAM, teamId, userId, first);
        draftService.create(ScopeType.TEAM, teamId, userId, saveRequest("削除後"));
        assertThat(countOwn()).isEqualTo(11);
    }

    @Test
    @DisplayName("AC9: updated_at から90日を超えたものだけが、日次の掃除バッチで物理削除される")
    void ac9_90日を超えたものだけが掃除バッチで消える() {
        Instant now = Instant.now();
        UUID stale = insertDraftUpdatedAt(now.minus(Duration.ofDays(91)));
        UUID recent = insertDraftUpdatedAt(now.minus(Duration.ofDays(89)));
        UUID fresh = insertDraftUpdatedAt(now);

        cleanupBatchService.runBatch();

        assertThat(exists(stale)).as("91日前に更新した下書きは消える").isFalse();
        assertThat(exists(recent)).as("89日前に更新した下書きは残る").isTrue();
        assertThat(exists(fresh)).as("今日更新した下書きは残る").isTrue();
    }

    @Test
    @DisplayName("AC9: 境界 — 基準時刻の90日と1秒前は消え、90日の1秒後（未満）は残る")
    void ac9_境界_90日ちょうどの前後() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID over = insertDraftUpdatedAt(now.minus(Duration.ofDays(90)).minusSeconds(1));
        UUID under = insertDraftUpdatedAt(now.minus(Duration.ofDays(90)).plusSeconds(1));

        long deleted = cleanupBatchService.purgeExpired(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(exists(over)).isFalse();
        assertThat(exists(under)).isTrue();
    }
}
