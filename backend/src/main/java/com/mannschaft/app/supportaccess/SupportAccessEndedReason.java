package com.mannschaft.app.supportaccess;

/** F10.9 許可が終わった理由（ended_reason）。 */
public enum SupportAccessEndedReason {
    USER_REVOKED,
    OPERATOR_CANCELLED,
    EXPIRED,
    WITHDRAWAL,
    OPERATOR_INELIGIBLE
}
