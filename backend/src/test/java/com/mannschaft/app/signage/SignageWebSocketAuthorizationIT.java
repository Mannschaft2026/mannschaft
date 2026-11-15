package com.mannschaft.app.signage;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageAccessTokenRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.signage.websocket.SignageWebSocketPublisher;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;

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
    @Autowired SimpMessagingTemplate messaging;
    @Autowired TestRestTemplate rest;
    @Autowired AuthTokenService jwtTokens;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    @PersistenceContext EntityManager em;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private final List<Long> tokenIds = new ArrayList<>();
    private final List<Long> screenIds = new ArrayList<>();
    private final List<Long> ownedUsers = new ArrayList<>();
    private Long teamId;
    private Long organizationId;
    private SignageScreenEntity screen;
    private SignageAccessTokenEntity token;
    private SignageAccessTokenEntity otherToken;

    @BeforeEach
    void fixture() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (Long actor : List.of(910001L, 910002L, 920001L)) {
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM users WHERE id=:id")
                        .setParameter("id", actor).getSingleResult()).longValue()).isZero();
                MembershipTestHelper.insertActiveUser(em, actor);
                ownedUsers.add(actor);
            }
            teamId = TeamOrgFixtureHelper.insertTeam(em, "掲示WS専用チーム", "sg-ws-" + UUID.randomUUID());
            organizationId = TeamOrgFixtureHelper.insertOrganization(em, "掲示WS別組織", "sg-ws-" + UUID.randomUUID());
            MembershipTestHelper.insertMembership(em, 910001L, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, 910002L, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, 910002L, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, 920001L, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, 920001L, "ADMIN", null, organizationId);
            screen = screens.saveAndFlush(SignageScreenEntity.builder()
                    .scopeType("TEAM").scopeId(teamId).name("専用掲示WS試練")
                    .createdBy(910002L).build());
            screenIds.add(screen.getId());
            token = issueToken(screen.getId());
            otherToken = issueToken(screen.getId());
        });
    }

    private SignageAccessTokenEntity issueToken(Long screenId) {
        var saved = tokens.saveAndFlush(SignageAccessTokenEntity.builder().screenId(screenId)
                .name("専用capability").token(UUID.randomUUID().toString()).createdBy(910001L).build());
        tokenIds.add(saved.getId());
        return saved;
    }

    @AfterEach
    void cleanup() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        clients.forEach(WebSocketStompClient::stop);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (!screenIds.isEmpty()) em.createNativeQuery("DELETE FROM signage_emergency_messages WHERE screen_id IN (:ids)")
                    .setParameter("ids", screenIds).executeUpdate();
            if (!tokenIds.isEmpty()) em.createNativeQuery("DELETE FROM signage_access_tokens WHERE id IN (:ids)")
                    .setParameter("ids", tokenIds).executeUpdate();
            if (!screenIds.isEmpty()) em.createNativeQuery("DELETE FROM signage_screens WHERE id IN (:ids)")
                    .setParameter("ids", screenIds).executeUpdate();
            for (String table : List.of("user_roles", "memberships")) {
                if (!ownedUsers.isEmpty()) em.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (:ids)")
                        .setParameter("ids", ownedUsers).executeUpdate();
            }
            if (!ownedUsers.isEmpty()) em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", ownedUsers).executeUpdate();
            if (teamId != null) em.createNativeQuery("DELETE FROM teams WHERE id=:id")
                    .setParameter("id", teamId).executeUpdate();
            if (organizationId != null) em.createNativeQuery("DELETE FROM organizations WHERE id=:id")
                    .setParameter("id", organizationId).executeUpdate();
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
    void AC1_SUBSCRIBEheaderへtokenを載せても資格を取得できない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        StompSession deniedSession = connect("/ws/signage", null, null, null);
        StompHeaders headers = new StompHeaders();
        headers.set("X-Signage-Token", token.getToken());
        headers.setId(UUID.randomUUID().toString());
        headers.setDestination(emergency());
        Inbox denied = new Inbox();
        deniedSession.subscribe(headers, denied);
        await().atMost(Duration.ofSeconds(10)).until(() -> !deniedSession.isConnected()
                || registered(emergency(), headers.getId()));
        assertDelivery(control, denied);
    }

    @Test
    void AC4_有効tokenでもprefix外wildcardから本文を受信しない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox denied = subscribe(connect("/ws", token.getToken(), null, null), "/topic/**");
        assertDelivery(control, denied);
    }

    @Test
    void AC4_unsubscribe後に同じidをwildcardで再利用しても資格を引き継がない() throws Exception {
        Inbox control = subscribe(connect("/ws", otherToken.getToken(), null, null), emergency());
        StompSession session = connect("/ws/signage", token.getToken(), null, null);
        Inbox exact = subscribe(session, emergency());
        exact.subscription.unsubscribe();
        await().atMost(Duration.ofSeconds(10)).until(() -> !registered(emergency(), exact.id));
        Inbox wildcard = subscribe(session, "/topic/**", exact.id);
        assertDelivery(control, wildcard);
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

    @Test
    void AC2_tokenなしは匿名と同scope相当JWTと他scope相当JWTでも拒否する() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        assertDelivery(control, subscribe(connect("/ws/signage", null, null, null), emergency()));
        for (Long actor : List.of(910001L, 910002L, 920001L)) {
            var jwt = jwtTokens.issueAccessToken(actor, List.of("MEMBER"));
            assertDelivery(control, subscribe(connect("/ws", null, jwt, null), emergency()));
        }
    }

    @Test
    void AC2_端末token保有者はJWT所属に依存せず両endpointで更新も受信する() throws Exception {
        for (String endpoint : List.of("/ws", "/ws/signage")) {
            for (String jwt : List.of("", jwtTokens.issueAccessToken(910001L, List.of("MEMBER")),
                    jwtTokens.issueAccessToken(920001L, List.of("ADMIN")))) {
                Inbox inbox = subscribe(connect(endpoint, token.getToken(), jwt.isEmpty() ? null : jwt, null),
                        "/topic/signage/" + screen.getId() + "/update");
                publisher.publishSlideUpdate(screen.getId());
                assertThat(inbox.messages.poll(10, TimeUnit.SECONDS)).containsEntry("type", "SLIDE_UPDATE");
            }
        }
    }

    @Test
    void AC2_TEAMtokenで別画面とORGANIZATION画面を購読できない() throws Exception {
        var other = new TransactionTemplate(transactions).execute(status -> {
            var entity = screens.saveAndFlush(SignageScreenEntity.builder()
                .scopeType("ORGANIZATION").scopeId(organizationId).name("別scope試練")
                    .createdBy(910001L).build());
            screenIds.add(entity.getId());
            return issueToken(entity.getId());
        });
        String destination = "/topic/signage/" + other.getScreenId() + "/emergency";
        Inbox control = subscribe(connect("/ws", other.getToken(), null, null), destination);
        Inbox denied = subscribe(connect("/ws/signage", token.getToken(), null, null), destination);
        String marker = UUID.randomUUID().toString();
        publisher.publishEmergency(other.getScreenId(), marker);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        assertThat(denied.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void AC3_不在と空と失効と期限切れのtokenは拒否する() throws Exception {
        Inbox control = subscribe(connect("/ws", otherToken.getToken(), null, null), emergency());
        for (String value : List.of("", UUID.randomUUID().toString())) {
            assertDelivery(control, deniedConnectionOrSubscription(value, null));
        }
        mutateToken("isActive", false);
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), null));
        mutateToken("isActive", true);
        mutateToken("expiredAt", LocalDateTime.now().minusDays(1));
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), null));
    }

    @Test
    void AC3_直接peerのIPを許可し偽装forwardedheaderを信用しない() throws Exception {
        Inbox control = subscribe(connect("/ws", otherToken.getToken(), null, null), emergency());
        mutateToken("allowedIps", "[\"127.0.0.0/8\"]");
        Inbox loopback = subscribe(connect("/ws/signage", token.getToken(), null, "203.0.113.1"), emergency());
        String marker = UUID.randomUUID().toString();
        publisher.publishEmergency(screen.getId(), marker);
        assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        assertThat(loopback.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        mutateToken("allowedIps", "[\"203.0.113.1/32\"]");
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), "203.0.113.1"));
        assertDelivery(control, loopback);
    }

    @Test
    void AC6_接続後の期限到達と画面停止と削除は次配送と再接続を拒否する() throws Exception {
        Inbox established = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox control = subscribe(connect("/ws/signage", otherToken.getToken(), null, null), emergency());
        mutateToken("expiredAt", LocalDateTime.now().minusSeconds(1));
        assertDelivery(control, established);
        assertDelivery(control, deniedConnectionOrSubscription(token.getToken(), null));
        mutateToken("expiredAt", null);
        for (String column : List.of("isActive", "deletedAt")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var entity = screens.findById(screen.getId()).orElseThrow();
                if (column.equals("isActive")) entity.deactivate(); else entity.softDelete();
                screens.saveAndFlush(entity);
            });
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(established.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            assertThat(control.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            // 無関係topicへの対照配送で、サーバ/接続そのものの停止と区別する。
            assertNonSignageDelivery();
            new TransactionTemplate(transactions).executeWithoutResult(status -> em.createNativeQuery(
                    "UPDATE signage_screens SET is_active=1, deleted_at=NULL WHERE id=:id")
                    .setParameter("id", screen.getId()).executeUpdate());
        }
    }

    @Test
    void AC5_clientSENDは匿名JWT有効device全てで偽装できない() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        List<StompSession> senders = List.of(connect("/ws", null, null, null),
                connect("/ws/signage", null, jwtTokens.issueAccessToken(910001L, List.of("MEMBER")), null),
                connect("/ws", token.getToken(), null, null));
        for (StompSession sender : senders) {
            sender.send(emergency(), Map.of("message", "client-forged"));
            assertThat(control.messages.poll(400, TimeUnit.MILLISECONDS)).isNull();
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        }
    }

    @Test
    void AC5_正規管理RESTは画面ADMINだけが実配信できる() throws Exception {
        Inbox device = subscribe(connect("/ws/signage", token.getToken(), null, null), emergency());
        String marker = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokens.issueAccessToken(910002L, List.of("MEMBER")));
        var response = rest.postForEntity("/api/v1/signage/screens/" + screen.getId() + "/emergency",
                new HttpEntity<>(Map.of("message", marker), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(device.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
    }

    @Test
    void AC7_不正宛先とoverflowとsuffixは購読拒否し他topicを維持する() throws Exception {
        Inbox control = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        for (String destination : List.of("/topic/signage/no-id/emergency",
                "/topic/signage/999999999999999999999999999/emergency", emergency() + "/extra",
                "/topic/signage/*/emergency", "/topic/signage/{id}/update")) {
            var session = connect("/ws/signage", token.getToken(), null, null);
            subscribe(session, destination);
            await().atMost(Duration.ofSeconds(10)).until(() -> !session.isConnected());
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(control.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
        }
        assertNonSignageDelivery();
    }

    private void assertNonSignageDelivery() throws Exception {
        String destination = "/topic/contract-signage-control/" + UUID.randomUUID();
        Inbox inbox = subscribe(connect("/ws", null, null, null), destination);
        String marker = UUID.randomUUID().toString();
        messaging.convertAndSend(destination, Map.of("message", marker));
        assertThat(inbox.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
    }

    private Inbox deniedConnectionOrSubscription(String capability, String fakeIp) throws Exception {
        try {
            return subscribe(connect("/ws", capability, null, fakeIp), emergency());
        } catch (ExecutionException deniedConnect) {
            // 同一endpointの正当端末へmarkerを配送する呼出側の対照と組で評価する。
            return new Inbox();
        }
    }

    private void mutateToken(String property, Object value) {
        if (!List.of("isActive", "expiredAt", "allowedIps").contains(property)) throw new IllegalArgumentException();
        new TransactionTemplate(transactions).executeWithoutResult(status -> em.createQuery(
                "update SignageAccessTokenEntity set " + property + "=:value where id=:id")
                .setParameter("value", value).setParameter("id", token.getId()).executeUpdate());
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
        return subscribe(session, destination, UUID.randomUUID().toString());
    }

    private Inbox subscribe(StompSession session, String destination, String id) {
        Inbox inbox = new Inbox();
        StompHeaders headers = new StompHeaders();
        headers.setId(id);
        headers.setDestination(destination);
        inbox.id = id;
        inbox.subscription = session.subscribe(headers, inbox);
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
        String id;
        StompSession.Subscription subscription;

        @Override
        public Type getPayloadType(StompHeaders headers) { return Map.class; }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.offer((Map<String, Object>) payload);
        }
    }
}
