package com.mannschaft.app.signage.websocket;

import java.time.LocalDateTime;

/** tokenと画面を1文で照合する内部projection。raw tokenは含めない。 */
public record SignageSocketAccess(Long tokenId, Long screenId, LocalDateTime expiredAt, String allowedIps) {
}
