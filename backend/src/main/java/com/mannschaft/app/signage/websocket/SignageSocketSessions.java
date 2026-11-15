package com.mannschaft.app.signage.websocket;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** server側sessionとexact購読の束縛。保持する資格はIDと直接peerだけでraw tokenを持たない。 */
@Component
public class SignageSocketSessions {
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    /** 外部へ渡せる不変の資格。JWT Principalとは独立する。 */
    record Access(Long tokenId, Long screenId, String peerIp) { }

    private record State(Access access, ConcurrentMap<String, String> destinations) { }

    void establish(String sessionId, SignageSocketAccess projection, String peerIp) {
        if (sessionId != null) states.put(sessionId,
                new State(new Access(projection.tokenId(), projection.screenId(), peerIp), new ConcurrentHashMap<>()));
    }

    Optional<Access> session(String sessionId) {
        State state = sessionId == null ? null : states.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(state.access());
    }

    void bind(String sessionId, String subscriptionId, String destination) {
        if (sessionId == null || subscriptionId == null || destination == null) return;
        // disconnectと同時でも切り離されたStateをglobalな別mapへ残さない。
        states.computeIfPresent(sessionId, (id, state) -> {
            state.destinations().put(subscriptionId, destination);
            return state;
        });
    }

    Optional<Access> binding(String sessionId, String subscriptionId, String destination) {
        if (sessionId == null || subscriptionId == null || destination == null) return Optional.empty();
        State state = states.get(sessionId);
        return state != null && destination.equals(state.destinations().get(subscriptionId))
                ? Optional.of(state.access()) : Optional.empty();
    }

    void unsubscribe(String sessionId, String subscriptionId) {
        State state = sessionId == null ? null : states.get(sessionId);
        if (state != null && subscriptionId != null) state.destinations().remove(subscriptionId);
    }

    void disconnect(String sessionId) {
        if (sessionId != null) states.remove(sessionId);
    }

    /** abrupt closeも回収する。複数disconnect通知は同じsessionのremoveだけなので冪等。 */
    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        disconnect(event.getSessionId());
    }
}
