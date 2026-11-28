package com.mannschaft.app.signage.websocket;

import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-P: サイネージ配信トピックの購読認可（{@code SignageSubscriptionInterceptor}・F00 §20.5.5）。
 *
 * <p>実 STOMP スタック（RANDOM_PORT・SockJS の生 WebSocket 経路 {@code /ws/signage/websocket}）に、CONNECT ヘッダ
 * {@code signage-token} でトークンを載せて接続し、{@code /topic/signage/{screenId}/emergency}・{@code /update} の
 * SUBSCRIBE が次の条件をすべて満たすときだけ成立することを確かめる（AC-0159-99・AC-0159-26b）。</p>
 * <ul>
 *   <li>接続に使ったトークンが有効（存在・未失効・期限内）</li>
 *   <li>購読先の画面がそのトークンの画面と一致（別画面の購読＝画面間 IDOR を拒否）</li>
 *   <li>画面の所属チームが非公開チーム（{@code MEMBERS_AND_ABOVE}）でない</li>
 * </ul>
 * <p>拒否された購読には、配信元が送っても 1 件も届かない。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 サイネージ配信トピックの購読認可（STOMP 実接続）")
class SignageSubscriptionAuthzIntegrationTest extends AbstractMySqlIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SignageScreenRepository screenRepository;

    @Autowired
    private SignageAccessTokenRepository tokenRepository;

    @Autowired
    private SecretTokenVault vault;

    @Autowired
    private SignageWebSocketPublisher publisher;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> screenIds = new ArrayList<>();

    private Long teamId;
    private Long screenA;
    private Long screenB;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("PUBLIC");
        screenA = insertScreen(teamId, "画面A");
        screenB = insertScreen(teamId, "画面B");
    }

    @AfterEach
    void tearDown() {
        clients.forEach(WebSocketStompClient::stop);
        clients.clear();
        for (Long id : screenIds) {
            jdbc.update("DELETE FROM signage_access_tokens WHERE screen_id = ?", id);
            jdbc.update("DELETE FROM signage_emergency_messages WHERE screen_id = ?", id);
            jdbc.update("DELETE FROM signage_screens WHERE id = ?", id);
        }
        for (Long id : teamIds) {
            jdbc.update("DELETE FROM teams WHERE id = ?", id);
        }
        screenIds.clear();
        teamIds.clear();
    }

    // ─── AC-0159-99: 画面間 IDOR ───────────────────────────

    @Test
    @DisplayName("AC-0159-99: 画面 A のトークンで A の /emergency・/update は購読でき、配信が届く")
    void 自画面の購読は許可() throws Exception {
        String token = issueToken(screenA, null, true);
        StompSession session = open(token).session().get(60, TimeUnit.SECONDS);

        BlockingQueue<Object> emergency = new LinkedBlockingQueue<>();
        BlockingQueue<Object> update = new LinkedBlockingQueue<>();
        session.subscribe("/topic/signage/" + screenA + "/emergency", collecting(emergency));
        session.subscribe("/topic/signage/" + screenA + "/update", collecting(update));

        assertThat(publishUntilReceived(() -> publisher.publishEmergency(screenA, "A の緊急連絡"), emergency))
                .as("A の緊急メッセージが届く").isNotNull();
        assertThat(publishUntilReceived(() -> publisher.publishSlideUpdate(screenA), update))
                .as("A の更新通知が届く").isNotNull();
    }

    @Test
    @DisplayName("AC-0159-99: 画面 A のトークンで別画面 B の /emergency を購読すると拒否され、B の緊急メッセージは 1 件も届かない")
    void 別画面のemergency購読は拒否() throws Exception {
        assertRejectedAndSilent(issueToken(screenA, null, true), screenB, "emergency");
    }

    @Test
    @DisplayName("AC-0159-99: 画面 A のトークンで別画面 B の /update を購読すると拒否され、B の更新通知は 1 件も届かない")
    void 別画面のupdate購読は拒否() throws Exception {
        assertRejectedAndSilent(issueToken(screenA, null, true), screenB, "update");
    }

    @Test
    @DisplayName("AC-0159-99: トークンなしの購読は拒否")
    void トークンなしは拒否() throws Exception {
        assertRejectedAndSilent(null, screenA, "emergency");
    }

    @Test
    @DisplayName("AC-0159-99: 存在しないトークンの購読は拒否")
    void 無効トークンは拒否() throws Exception {
        assertRejectedAndSilent(UUID.randomUUID().toString(), screenA, "emergency");
    }

    @Test
    @DisplayName("AC-0159-99: 失効（is_active=false）トークンの購読は拒否")
    void 失効トークンは拒否() throws Exception {
        assertRejectedAndSilent(issueToken(screenA, null, false), screenA, "emergency");
    }

    @Test
    @DisplayName("AC-0159-99: 期限切れトークンの購読は拒否")
    void 期限切れトークンは拒否() throws Exception {
        assertRejectedAndSilent(issueToken(screenA, LocalDateTime.now().minusDays(1), true), screenA, "update");
    }

    // ─── AC-0159-26b: 非公開チーム ────────────────────────

    @Test
    @DisplayName("AC-0159-26b: 有効のまま残ったトークンでも、画面の所属チームが非公開なら購読は拒否され緊急メッセージ本文を 1 件も受信しない")
    void 非公開チームの画面の購読は拒否() throws Exception {
        String token = issueToken(screenA, null, true);
        jdbc.update("UPDATE teams SET visibility = 'MEMBERS_AND_ABOVE' WHERE id = ?", teamId);
        assertRejectedAndSilent(token, screenA, "emergency");
    }

    @Test
    @DisplayName("AC-0159-26b: 公開チームでは従来どおり購読できる（対照）")
    void 公開チームは購読できる() throws Exception {
        jdbc.update("UPDATE teams SET visibility = 'SUPPORTERS_AND_ABOVE' WHERE id = ?", teamId);
        String token = issueToken(screenA, null, true);
        StompSession session = open(token).session().get(60, TimeUnit.SECONDS);
        BlockingQueue<Object> emergency = new LinkedBlockingQueue<>();
        session.subscribe("/topic/signage/" + screenA + "/emergency", collecting(emergency));
        assertThat(publishUntilReceived(() -> publisher.publishEmergency(screenA, "公開チームの緊急連絡"), emergency))
                .isNotNull();
    }

    // ─── ヘルパー ─────────────────────────────────────────

    private void assertRejectedAndSilent(String token, Long screenId, String kind) throws Exception {
        Connection connection = open(token);
        StompSession session = connection.session().get(60, TimeUnit.SECONDS);
        BlockingQueue<Object> sink = new LinkedBlockingQueue<>();
        session.subscribe("/topic/signage/" + screenId + "/" + kind, collecting(sink));

        StompHeaders error = connection.handler().error.get(60, TimeUnit.SECONDS);
        assertThat(error).as("購読は ERROR フレームで拒否される").isNotNull();

        for (int i = 0; i < 6; i++) {
            if ("emergency".equals(kind)) {
                publisher.publishEmergency(screenId, "届いてはならない緊急連絡");
            } else {
                publisher.publishSlideUpdate(screenId);
            }
            Thread.sleep(300);
        }
        assertThat(sink.poll(1, TimeUnit.SECONDS)).as("拒否された購読には 1 件も届かない").isNull();
    }

    private static Object publishUntilReceived(Runnable publish, BlockingQueue<Object> sink) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            publish.run();
            Object received = sink.poll(500, TimeUnit.MILLISECONDS);
            if (received != null) {
                return received;
            }
        }
        return null;
    }

    private static final class ErrorCapturingHandler extends StompSessionHandlerAdapter {
        final CompletableFuture<StompHeaders> error = new CompletableFuture<>();

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            error.complete(headers);
        }
    }

    private record Connection(CompletableFuture<StompSession> session, ErrorCapturingHandler handler) { }

    private Connection open(String signageToken) {
        StandardWebSocketClient ws = new StandardWebSocketClient();
        ws.setUserProperties(Map.<String, Object>of("org.apache.tomcat.websocket.IO_TIMEOUT_MS", "60000"));
        WebSocketStompClient client = new WebSocketStompClient(ws);
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);
        StompHeaders connect = new StompHeaders();
        if (signageToken != null) {
            connect.add("signage-token", signageToken);
        }
        ErrorCapturingHandler handler = new ErrorCapturingHandler();
        CompletableFuture<StompSession> future = client.connectAsync(
                "ws://localhost:" + port + "/ws/signage/websocket", new WebSocketHttpHeaders(), connect, handler);
        return new Connection(future, handler);
    }

    private static StompFrameHandler collecting(BlockingQueue<Object> sink) {
        return new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                sink.offer(payload != null ? payload : "NULL");
            }
        };
    }

    private Long insertTeam(String visibility) {
        String name = "上限試練WSチーム-" + UUID.randomUUID();
        jdbc.update("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                + "created_at, updated_at) VALUES (?, ?, 1, 0, 0, ?, NOW(), NOW())",
                name, visibility, "c59ws-" + UUID.randomUUID().toString().substring(0, 12));
        Long id = jdbc.queryForObject("SELECT id FROM teams WHERE name = ?", Long.class, name);
        teamIds.add(id);
        return id;
    }

    private Long insertScreen(Long scopeTeamId, String name) {
        SignageScreenEntity saved = screenRepository.save(SignageScreenEntity.builder()
                .scopeType("TEAM").scopeId(scopeTeamId).name(name).createdBy(1L).build());
        screenIds.add(saved.getId());
        return saved.getId();
    }

    private String issueToken(Long screenId, LocalDateTime expiredAt, boolean active) {
        String plaintext = vault.issueBase64Url().plaintext();
        SignageAccessTokenEntity entity = tokenRepository.save(SignageAccessTokenEntity.builder()
                .screenId(screenId).tokenHash(vault.hash(plaintext)).name("購読試練").createdBy(1L)
                .expiredAt(expiredAt).build());
        if (!active) {
            entity.deactivate();
            tokenRepository.save(entity);
        }
        return plaintext;
    }
}
