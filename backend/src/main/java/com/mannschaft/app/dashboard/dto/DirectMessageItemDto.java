package com.mannschaft.app.dashboard.dto;

import java.time.LocalDateTime;

/**
 * DM用カードDTO。
 * DIRECT（1対1 DM / GROUP_DM）チャンネルのチャットハブ表示に使用する。
 */
public record DirectMessageItemDto(
        Long channelId,
        Long partnerId,
        String partnerDisplayName,
        String partnerAvatarUrl,
        int unreadCount,
        boolean isPinned,
        boolean isMuted,
        LocalDateTime lastMessageAt,
        /** 最終メッセージのプレビュー（最大50文字）。lastMessageAt が null の場合は null */
        String lastMessagePreview,
        /**
         * GROUP_DM の表示名（CMP-261113-0110）。明示名 → 参加者名からの導出名 → null（FE が未命名文言を出す）の順。
         * 1対1 DM は null。導出規則は {@code com.mannschaft.app.chat.ChatGroupDmDerivedName}（チャンネル一覧・詳細と共通）。
         */
        String derivedName
) {}
