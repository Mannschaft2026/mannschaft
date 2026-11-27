package com.mannschaft.app.supportaccess.event;

import java.util.UUID;

/** F10.9 代理ヘッダー付きリクエストの拒否の監査を outbox に書いた（コミット後にこの1行だけを audit_logs へ反映する）。 */
public record SupportAccessDeniedAuditAppendedEvent(UUID auditOutboxId) {
}
