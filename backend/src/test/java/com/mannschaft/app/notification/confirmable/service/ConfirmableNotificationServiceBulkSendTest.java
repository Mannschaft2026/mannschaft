package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261002-1559 試練（単体）: 同期 {@link ConfirmableNotificationService#send} の受信者上限撤廃と
 * 「500人ずつ多値 INSERT」（{@link ConfirmableRecipientBulkWriter}）への切り替え。
 *
 * <p>マスター裁可（2026-11-25）: 受信者上限を撤廃し、同期 send も
 * {@link ConfirmableFanoutChunkSink} と同じ多値 INSERT の共通部品を使う。通知（親）は1件のまま。</p>
 *
 * <p>実 DB での境界値・SQL 文数・一意制約・ロールバックは
 * {@link ConfirmableNotificationSyncSendBulkIT} が担う。本クラスは呼び出しの形（チャンク分割・
 * 1人1保存の経路を使わないこと・課金しないこと）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261002-1559 同期 send の受信者上限撤廃と一括INSERT（単体）")
class ConfirmableNotificationServiceBulkSendTest {

    private static final Long SCOPE_ID = 10L;
    private static final Long CREATOR_ID = 1L;

    @Mock
    private ConfirmableNotificationRepository notificationRepository;
    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Mock
    private ConfirmableNotificationSettingsService settingsService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private NotificationCreditService notificationCreditService;
    @Mock
    private ConfirmableNotificationConfirmService confirmService;
    @Mock
    private ConfirmableNotificationQueryService queryService;
    /** CMP-261002-1559: 出陣で ConfirmableNotificationService に注入される共通部品。 */
    @Mock
    private ConfirmableRecipientBulkWriter bulkWriter;

    @InjectMocks
    private ConfirmableNotificationService service;

    @BeforeEach
    void setUp() {
        given(settingsService.getOrCreate(any(), any())).willReturn(ConfirmableNotificationSettingsEntity.builder()
                .scopeType(ScopeType.ORGANIZATION).scopeId(SCOPE_ID).build());
        given(userRepository.findById(any())).willReturn(Optional.empty());
        given(userRepository.getReferenceById(any())).willAnswer(inv -> mock(UserEntity.class));
        given(notificationRepository.save(any(ConfirmableNotificationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(recipientRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private static List<Long> users(int count) {
        return LongStream.rangeClosed(1_001L, 1_000L + count).boxed().toList();
    }

    private Throwable send(List<Long> recipients) {
        return catchThrowable(() -> service.send(
                ScopeType.ORGANIZATION, SCOPE_ID, "CMP-261002-1559", "本文",
                ConfirmableNotificationPriority.NORMAL, null,
                null, null, null, null, null, CREATOR_ID, recipients));
    }

    /** insertRecipients に渡された各チャンク（呼び出し順）。 */
    @SuppressWarnings("unchecked")
    private List<List<Long>> capturedRecipientChunks() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkWriter, atLeastOnce()).insertRecipients(any(), captor.capture(), anyInt(), anyInt());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<List<Long>> capturedAppNotificationChunks() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(bulkWriter, atLeastOnce()).insertAppNotifications(any(), captor.capture(), anyString());
        return captor.getAllValues();
    }

    private static List<Long> flatten(List<List<Long>> chunks) {
        List<Long> all = new ArrayList<>();
        chunks.forEach(all::addAll);
        return all;
    }

    private void assertChunkedExactly(List<Long> expectedUnique, int expectedChunks) {
        List<List<Long>> recipientChunks = capturedRecipientChunks();
        assertThat(recipientChunks)
                .as("受信者行は %d チャンク（500人ずつ）で多値 INSERT される", expectedChunks)
                .hasSize(expectedChunks)
                .allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(ConfirmableRecipientBulkWriter.CHUNK_SIZE));
        assertThat(flatten(recipientChunks))
                .as("全受信者が1回ずつ、入力順で受信者行の INSERT に渡る")
                .containsExactlyElementsOf(expectedUnique);

        List<List<Long>> notificationChunks = capturedAppNotificationChunks();
        assertThat(notificationChunks)
                .as("アプリ内通知も %d チャンクで多値 INSERT される", expectedChunks)
                .hasSize(expectedChunks)
                .allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(ConfirmableRecipientBulkWriter.CHUNK_SIZE));
        assertThat(flatten(notificationChunks))
                .as("全受信者にアプリ内通知が1件ずつ作られる")
                .containsExactlyElementsOf(expectedUnique);
    }

    @Test
    @DisplayName("F1: 受信者500人（境界・1チャンク）は SEND_FAILED にならず、一括INSERT 1チャンクで全員に届く")
    void F1_受信者500人_1チャンクで全員分の受信者行と通知を一括INSERTする() {
        List<Long> recipients = users(500);

        Throwable thrown = send(recipients);

        assertThat(thrown).as("F1: 500人は送信できる").isNull();
        assertChunkedExactly(recipients, 1);
    }

    @Test
    @DisplayName("F1: 受信者501人（境界+1）でも SEND_FAILED にならず、500人+1人の2チャンクで全員に届く")
    void F1_受信者501人_SEND_FAILEDにならず2チャンクで全員に届く() {
        List<Long> recipients = users(501);

        Throwable thrown = send(recipients);

        assertThat(thrown)
                .as("F1: 受信者上限は撤廃される（現状は MAX_RECIPIENT_COUNT=500 超過で SEND_FAILED）")
                .isNull();
        assertChunkedExactly(recipients, 2);
    }

    @Test
    @DisplayName("F1: 受信者1000人でも SEND_FAILED にならず、500人×2チャンクで全員に届く")
    void F1_受信者1000人_500人ずつ2チャンクで全員に届く() {
        List<Long> recipients = users(1000);

        Throwable thrown = send(recipients);

        assertThat(thrown).as("F1: 1000人でも送信できる").isNull();
        assertChunkedExactly(recipients, 2);
    }

    @Test
    @DisplayName("F2: 1000人送信で、1人1保存の経路（recipientRepository.saveAll・NotificationHelper#notifyAll の1人ずつ保存）を使わない")
    void F2_1000人送信_1人1INSERTの経路を使わない() {
        Throwable thrown = send(users(1000));

        assertThat(thrown).as("F2: 送信自体が成功する").isNull();
        verify(recipientRepository, never()).saveAll(any());
        verify(notificationHelper, never()).notifyAll(
                anyList(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("F3/F5: チャンク境界をまたいで同じ利用者が重複しても受信者行・通知は1人1件、総数・未確認数は一意化後の人数")
    void F3_F5_チャンク境界をまたぐ重複は1人1件で総数と未確認数は一意化後の人数() {
        List<Long> unique = users(1000);
        List<Long> withDuplicates = new ArrayList<>(unique);
        withDuplicates.addAll(unique.subList(0, 200)); // 後方チャンクに前方チャンクの利用者を再掲
        assertThat(new LinkedHashSet<>(withDuplicates)).hasSize(1000);

        Throwable thrown = send(withDuplicates);

        assertThat(thrown).as("F3: 重複を含んでも送信できる").isNull();
        assertChunkedExactly(unique, 2);

        ArgumentCaptor<ConfirmableNotificationEntity> saved = ArgumentCaptor.forClass(ConfirmableNotificationEntity.class);
        verify(notificationRepository, atLeastOnce()).save(saved.capture());
        ConfirmableNotificationEntity last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getTotalRecipientCount()).as("F5: 総受信者数は一意化後の1000").isEqualTo(1000);
        assertThat(last.getUnconfirmedCount()).as("F5: 未確認数は一意化後の1000").isEqualTo(1000);
    }

    @Test
    @DisplayName("F6: 同期 send は F09.13 の課金対象外。組織スコープ1000人でも通知クレジットを消費しない")
    void F6_組織スコープ1000人_通知クレジットを消費しない() {
        Throwable thrown = send(users(1000));

        assertThat(thrown).as("F6: 送信自体が成功する（対象外の固定は送信成功が前提）").isNull();
        verify(notificationCreditService, never()).consume(any(), anyInt(), any());
        verify(notificationCreditService, never()).consume(eq(SCOPE_ID), anyInt(), any());
    }

    @Test
    @DisplayName("F7: 確認通知の sourceType は可視性フィルタの対象外（filterAccessibleRecipients は素通し）という前提を固定する")
    void F7_確認通知のsourceTypeは可視性フィルタの対象外という前提を固定する() {
        // 同期 send の受信者集合は呼出元（委員会・請求・募集）が業務上の権限で確定させたもの。
        // NotificationHelper#filterAccessibleRecipients は sourceType を ReferenceType に解決できないとき
        // 入力を素通しする（同期 send のアプリ内通知の sourceType は通知種別によらず CONFIRMABLE_NOTIFICATION）。
        // これがマップされた場合は、
        // 一括INSERT 経路でも受信者行・通知の両方に同じフィルタを掛け直す必要がある（この前提が崩れたら red）。
        assertThat(NotificationSourceTypeMapper.resolve("CONFIRMABLE_NOTIFICATION"))
                .as("F7: CONFIRMABLE_NOTIFICATION は可視性フィルタの解決対象外")
                .isEmpty();
    }
}
