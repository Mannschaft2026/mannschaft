package com.mannschaft.app.role.dto;

import java.util.UUID;

/**
 * 承諾の確保（claim）が成功した証。以降のロール付与・確定・補償はこの claim の主キーで指す。
 *
 * @param resumed        同じ招待・同じ利用者に残っていた確保（CLAIMED）を再開したものか（使用回数は進めていない。
 *                       新規登録の保留承認の復旧専用。CMP-261107-1833 設計 §9-3）
 * @param grantCommitted 再開した確保による付与（T7）が既にコミット済みか。真ならロール付与は行わず、確定（T9）だけ行う
 *                       （前回の attempt が付与をコミットした後に止まった。claim の付与印で判定する）
 */
public record InviteClaimTicket(
        UUID claimId,
        Long tokenId,
        String scopeType,
        Long scopeId,
        Long roleId,
        Long invitedBy,
        boolean resumed,
        boolean grantCommitted) {

    /** 新しく確保した（使用回数を1進めた）証。 */
    public InviteClaimTicket(UUID claimId, Long tokenId, String scopeType, Long scopeId, Long roleId, Long invitedBy) {
        this(claimId, tokenId, scopeType, scopeId, roleId, invitedBy, false, false);
    }

    /** 確保の証（付与はまだコミットされていない）。 */
    public InviteClaimTicket(UUID claimId, Long tokenId, String scopeType, Long scopeId, Long roleId, Long invitedBy,
                             boolean resumed) {
        this(claimId, tokenId, scopeType, scopeId, roleId, invitedBy, resumed, false);
    }
}
