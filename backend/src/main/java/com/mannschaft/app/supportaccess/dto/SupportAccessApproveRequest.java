package com.mannschaft.app.supportaccess.dto;

/** F10.9 本人の許可（再認証の材料。どちらか一方が必須）。 */
public record SupportAccessApproveRequest(String currentPassword, String passkeyAssertionJson) {
}
