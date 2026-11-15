package com.mannschaft.app.signage;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.signage.websocket.SignageWebSocketPublisher;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CMP-261114-0713: 実TCP・実MySQL・実SimpleBrokerで掲示購読と失効後配送を検証する先行試練。
 *
 * <p>WebSocket接続契約のため共通MySQL基底のWebEnvironmentだけをRANDOM_PORTへ上書きする。
 * 認可・token/画面Repository・Publisherは実物。共通基底のRedis代替は外部依存だけである。
 * 正例への同一marker配送を必ず対照にし、接続失敗や全配送停止を拒否成功と扱わない。
 * HTTP表示tokenの既存契約はSignageScopeContractITが別に固定する。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("掲示WS: 端末capabilityと既存接続の配送認可")
class SignageWebSocketAuthorizationIT extends AbstractMySqlIntegrationTest {

    @Autowired SignageScreenRepository screens;
    @Autowired SignageAccessTokenRepository tokens;
    @Autowired SignageWebSocketPublisher publisher;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired AuthTokenService jwtTokens;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private SignageScreenEntity screen;
    private SignageAccessTokenEntity token;
    private SignageAccessTokenEntity otherToken;

    @BeforeEach
    void fixture() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            screen = screens.saveAndFlush(SignageScreenEntity.builder()
                    .scopeType("TEAM").scopeId(910001L).name("専用掲示WS試練")
                    .createdBy(910001L).build());
            token = issueToken(screen.getId());
            otherToken = issueToken(screen.getId());
        });
    }

    private SignageAccessTokenEntity issueToken(Long screenId) {
        return tokens.saveAndFlush(SignageAccessTokenEntity.builder().screenId(screenId)
                .name("専用capability").token(UUID.randomUUID().toString()).createdBy(910001L).build());
    }

    @AfterEach
    void cleanup() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        clients.forEach(WebSocketStompClient::stop);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tokens.deleteById(token.getId());
            tokens.deleteById(otherToken.getId());
            screens.deleteById(screen.getId());
        });
    }

    @Test
    void AC2_他scopeJWTだけでは緊急本文を受信しない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws/signage", null,
                jwtTokens.issueAccessToken(920001L, List.of("MEMBER")), null), emergency());
        assertDelivery(control, denied);
    }

    @Test
    void AC1_query内の端末tokenだけでは購読できない() throws Exception {
        Inbox control = subscribe(connect("/ws/signage", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws?signage_token=" + token.getToken(), null, null, null), emergency());
        assertDelivery(control, denied);
    }

    @Test
    void AC4_有効tokenでもprefix外wildcardから本文を受信しない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws", token.getToken(), null, null), "/topic/**");
        assertDelivery(control, denied);
    }

    @Test
    void AC6_失効commit後は既存接続へ配送せず別tokenへ配送する() throws Exception {
        Inbox revoked = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox control = subscribe(connect("/ws/signage", otherToken.getToken(), null, null), emergency());
        String before = UUID.randomUUID().toString();
        publisher.publishEmergency(screen.getId(), before);
        assertThat(revoked.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", before);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", before);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var entity = tokens.findById(token.getId()).orElseThrow();
            entity.deactivate();
            tokens.saveAndFlush(entity);
        });
        assertDelivery(control, revoked);
    }

    private String emergency() {
        return "/topic/signage/" + screen.getId() + "/emergency";
    }

    private void assertDelivery(Inbox control, Inbox denied) throws Exception {
        String marker = UUID.randomUUID().toString();
        publisher.publishEmergency(screen.getId(), marker);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).as("同一markerが正当端末へ届く")
                .containsEntry("message", marker);
        assertThat(denied.messages.poll(400, TimeUnit.MILLISECONDS)).as("無資格配送は0件").isNull();
    }

    private StompSession connect(String endpoint, String capability, String jwt, String fakeIp) throws Exception {
        var client = new WebSocketStompClient(new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);
        var handshake = new WebSocketHttpHeaders();
        handshake.setOrigin("http://localhost:3000");
        if (fakeIp != null) {
            handshake.set("X-Forwarded-For", fakeIp);
            handshake.set("X-Real-IP", fakeIp);
        }
        var headers = new StompHeaders();
        if (capability != null) headers.set("X-Signage-Token", capability);
        if (jwt != null) headers.set("Authorization", "Bearer " + jwt);
        var session = client.connectAsync("ws://127.0.0.1:" + port + endpoint,
                handshake, headers, new StompSessionHandlerAdapter() { }).get(30, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private Inbox subscribe(StompSession session, String destination) {
        Inbox inbox = new Inbox();
        StompHeaders headers = new StompHeaders();
        String id = UUID.randomUUID().toString();
        headers.setId(id);
        headers.setDestination(destination);
        session.subscribe(headers, inbox);
        await().atMost(Duration.ofSeconds(10)).until(() -> !session.isConnected() || registered(destination, id));
        return inbox;
    }

    private boolean registered(String destination, String subscriptionId) {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE)
                .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination).build();
        return broker.getSubscriptionRegistry().findSubscriptions(message).values().stream()
                .anyMatch(ids -> ids.contains(subscriptionId));
    }

    /** 実STOMPの受信本文だけを保持する。資格情報・接続headerは記録しない。 */
    static class Inbox implements StompFrameHandler {
        final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) { return Map.class; }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.offer((Map<String, Object>) payload);
        }
    }
}
