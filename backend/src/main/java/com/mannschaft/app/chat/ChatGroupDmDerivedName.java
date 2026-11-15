package com.mannschaft.app.chat;

import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 名前なし GROUP_DM（Zimmer）の表示名を参加者名から導出する規則（CMP-261113-0110）。
 *
 * <p>チャンネル一覧・詳細・作成系の応答（{@code ChannelResponse.derivedName}）と、ダッシュボードのチャットハブ
 * （{@code DirectMessageItemDto.derivedName}）が同じ規則で導出するよう、判定・並び順・連結をここ 1 か所に置く。
 * データの取得（メンバー行・ユーザー）は呼出側が一括クエリで行い、本クラスは純粋関数だけを持つ。</p>
 *
 * <ul>
 *   <li>対象は channelType=GROUP_DM かつ明示名が null/空白のチャンネルだけ。</li>
 *   <li>呼出ユーザー以外のメンバーを {@code joinedAt} 昇順・同値ならメンバー行 id 昇順で並べる
 *       （{@code ChatChannelMemberRepository#findByChannelIdInAndUserIdNotOrderByJoinedAtAscIdAsc} と同じ並び）。</li>
 *   <li>表示名を {@code ", "} で連結し、先頭 {@value #MAX_NAMES} 名まで。超過分は {@code " +N"}（N=残り人数）。</li>
 *   <li>ユーザーが取得できない（ソフトデリート等）・表示名が null の参加者は {@value #UNKNOWN_USER_NAME}。</li>
 *   <li>自分以外が 0 名なら null。</li>
 * </ul>
 *
 * <p>導出名は応答のたびに計算し、DB の name 列には書かない。</p>
 */
public final class ChatGroupDmDerivedName {

    /** 取得できない参加者の表示名（DM 相手の解決と同じフォールバック）。 */
    public static final String UNKNOWN_USER_NAME = "ユーザー";

    /** 連結する表示名の最大人数。 */
    public static final int MAX_NAMES = 3;

    /** 参加者の並び順: 参加日時の昇順、同値ならメンバー行 id の昇順。 */
    public static final Comparator<ChatChannelMemberEntity> MEMBER_ORDER =
            Comparator.comparing(ChatChannelMemberEntity::getJoinedAt,
                            Comparator.nullsLast(Comparator.<java.time.LocalDateTime>naturalOrder()))
                    .thenComparing(ChatChannelMemberEntity::getId,
                            Comparator.nullsLast(Comparator.<Long>naturalOrder()));

    private ChatGroupDmDerivedName() {
    }

    /**
     * 導出名の対象か（GROUP_DM かつ明示名が null/空白）を判定する。
     *
     * @param channelType チャンネル種別
     * @param name        明示名
     * @return 対象なら true
     */
    public static boolean isTarget(ChannelType channelType, String name) {
        return channelType == ChannelType.GROUP_DM && (name == null || name.isBlank());
    }

    /**
     * 導出名の対象チャンネル（{@link #isTarget(ChannelType, String)}）か判定する。
     *
     * @param channel チャンネル
     * @return 対象なら true
     */
    public static boolean isTarget(ChatChannelEntity channel) {
        return channel != null && isTarget(channel.getChannelType(), channel.getName());
    }

    /**
     * チャンネル群のうち導出名の対象になるチャンネルの ID を返す。
     *
     * @param channels チャンネル群
     * @return 対象チャンネル ID の集合
     */
    public static Set<Long> targetChannelIds(Collection<ChatChannelEntity> channels) {
        return channels.stream()
                .filter(ChatGroupDmDerivedName::isTarget)
                .map(ChatChannelEntity::getId)
                .collect(Collectors.toSet());
    }

    /**
     * チャンネル群のうち GROUP_DM（名前の有無を問わない）のチャンネル ID を返す。
     *
     * @param channels チャンネル群
     * @return GROUP_DM のチャンネル ID の集合
     */
    public static Set<Long> groupDmChannelIds(Collection<ChatChannelEntity> channels) {
        return channels.stream()
                .filter(c -> c.getChannelType() == ChannelType.GROUP_DM)
                .map(ChatChannelEntity::getId)
                .collect(Collectors.toSet());
    }

    /**
     * GROUP_DM の表示名を「明示名 → 導出名 → null」の順で解決する（ダッシュボードのチャットハブ用）。
     * GROUP_DM 以外は null（1対1 DM は相手の表示名を別項目で持つ）。
     *
     * @param channel             チャンネル
     * @param derivedNameByChannel チャンネル ID → 導出名（導出名がないチャンネルはキーを持たない）
     * @return 表示名。GROUP_DM 以外、または名前なしで導出名もないときは null
     */
    public static String groupDmDisplayName(ChatChannelEntity channel, Map<Long, String> derivedNameByChannel) {
        if (channel == null || channel.getChannelType() != ChannelType.GROUP_DM) {
            return null;
        }
        if (!isTarget(channel)) {
            return channel.getName();
        }
        return derivedNameByChannel.get(channel.getId());
    }

    /**
     * メンバー行群を、チャンネルごとの「呼出ユーザー以外の参加者ユーザー ID（{@link #MEMBER_ORDER} 順）」へまとめる。
     * 他メンバーがいないチャンネルはキーを持たない。
     *
     * @param members  対象チャンネル群のメンバー行（並び順は問わない）
     * @param viewerId 除外する呼出ユーザー ID
     * @return チャンネル ID → 参加者ユーザー ID（並び順確定済み）
     */
    public static Map<Long, List<Long>> orderedOtherUserIdsByChannel(
            Collection<ChatChannelMemberEntity> members, Long viewerId) {
        Map<Long, List<ChatChannelMemberEntity>> byChannel = new LinkedHashMap<>();
        for (ChatChannelMemberEntity m : members) {
            if (m.getChannelId() == null || m.getUserId() == null || m.getUserId().equals(viewerId)) {
                continue;
            }
            byChannel.computeIfAbsent(m.getChannelId(), k -> new ArrayList<>()).add(m);
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        byChannel.forEach((channelId, rows) -> result.put(channelId, rows.stream()
                .sorted(MEMBER_ORDER)
                .map(ChatChannelMemberEntity::getUserId)
                .toList()));
        return result;
    }

    /**
     * 並び順確定済みの参加者から導出名を組み立てる。
     *
     * @param orderedUserIds 呼出ユーザー以外の参加者ユーザー ID（並び順確定済み。null/空なら導出名なし）
     * @param displayNameOf  ユーザー ID → 表示名（取得できない場合は null を返す）
     * @return 導出名。参加者 0 名なら null
     */
    public static String format(List<Long> orderedUserIds, Function<Long, String> displayNameOf) {
        if (orderedUserIds == null || orderedUserIds.isEmpty()) {
            return null;
        }
        List<String> names = orderedUserIds.stream()
                .limit(MAX_NAMES)
                .map(id -> {
                    String name = displayNameOf.apply(id);
                    return name != null ? name : UNKNOWN_USER_NAME;
                })
                .toList();
        String joined = String.join(", ", names);
        int rest = orderedUserIds.size() - names.size();
        return rest > 0 ? joined + " +" + rest : joined;
    }
}
