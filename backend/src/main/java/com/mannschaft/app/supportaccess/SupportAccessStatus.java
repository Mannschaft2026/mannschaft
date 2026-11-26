package com.mannschaft.app.supportaccess;

/** F10.9 依頼・許可の状態。PENDING/APPROVED が「開いている」状態。 */
public enum SupportAccessStatus {
    PENDING,
    APPROVED,
    DENIED,
    REVOKED,
    EXPIRED,
    CANCELLED
}
