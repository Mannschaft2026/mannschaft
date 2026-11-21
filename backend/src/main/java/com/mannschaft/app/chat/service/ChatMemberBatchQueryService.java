package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatGroupDmDerivedName;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.NameResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * チャンネルメンバーの一括照会サービス（CMP-261103-1225 / #4270 の N+1 解消用・read-only）。
 *
 * <p>ダッシュボード・業務アラートなど chat 以外のドメインが、チャンネルごとに
 * {@link ChatChannelMemberRepository} を呼んでいた箇所（チャンネル数比例の SQL）を、
 * チャンネル群まとめて 1 クエリで引けるようにする。他ドメインから chat の Repository を
 * 直接参照しない（番人 D-5）ため、本 Service を経由させる。戻り値は ID と数値だけで、
 * Entity をシグネチャに出さない（番人 D-1 API 境界）。</p>
 *
 * <p>入力が空なら SQL を発行せず空 Map を返す（{@code IN ()} を発行しない）。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatMemberBatchQueryService {

    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final NameResolverService nameResolverService;

    /**
     * 指定ユーザーが参加しているチャンネルについて、チャンネル ID → 未読数を返す。
     *
     * <p>参加していないチャンネルはキーを持たない（呼び出し側はキーの有無で参加判定できる）。
     * 単票版 {@code findByChannelIdAndUserId(channelId, userId)} をチャンネルごとに呼ぶのと同じ結果になる。</p>
     *
     * @param userId     ユーザー ID
     * @param channelIds 対象チャンネル ID 群
     * @return チャンネル ID → 未読数（参加チャンネルのみ）
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> findUnreadCountByChannelForMember(Long userId, Collection<Long> channelIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (userId == null || channelIds == null || channelIds.isEmpty()) {
            return result;
        }
        for (ChatChannelMemberEntity m : chatChannelMemberRepository.findByUserIdAndChannelIdIn(userId, channelIds)) {
            result.putIfAbsent(m.getChannelId(), m.getUnreadCount());
        }
        return result;
    }

    /**
     * チャンネルごとに「指定ユーザー以外で参加日時が最も古いメンバー」のユーザー ID を返す（DM 相手の解決用）。
     *
     * <p>単票版 {@code findByChannelIdOrderByJoinedAtAsc(channelId)} から自分以外の先頭を採るのと同じ規則で、
     * 同時刻参加は ID の小さい方を採る。他メンバーがいないチャンネルはキーを持たない。</p>
     *
     * @param channelIds 対象チャンネル ID 群
     * @param userId     除外するユーザー ID（呼出ユーザー）
     * @return チャンネル ID → 相手ユーザー ID
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> findFirstOtherMemberUserIdByChannel(Collection<Long> channelIds, Long userId) {
        Map<Long, Long> result = new HashMap<>();
        if (channelIds == null || channelIds.isEmpty()) {
            return result;
        }
        for (ChatChannelMemberEntity m : chatChannelMemberRepository
                .findByChannelIdInAndUserIdNotOrderByJoinedAtAscIdAsc(channelIds, userId)) {
            result.putIfAbsent(m.getChannelId(), m.getUserId());
        }
        return result;
    }

    /**
     * チャンネルごとに「指定ユーザー以外のメンバーのユーザー ID」を、導出名の並び
     * （{@link ChatGroupDmDerivedName#MEMBER_ORDER}: 参加日時の昇順・同値はメンバー行 ID の昇順）で返す
     * （CMP-261113-0110）。全チャンネル分を 1 クエリで取得する。他メンバーがいないチャンネルはキーを持たない。
     *
     * @param channelIds 対象チャンネル ID 群
     * @param userId     除外するユーザー ID（呼出ユーザー）
     * @return チャンネル ID → 参加者ユーザー ID（並び順確定済み）
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> findOrderedOtherMemberUserIdsByChannel(Collection<Long> channelIds, Long userId) {
        if (channelIds == null || channelIds.isEmpty()) {
            return new HashMap<>();
        }
        return ChatGroupDmDerivedName.orderedOtherUserIdsByChannel(
                chatChannelMemberRepository.findByChannelIdInAndUserIdNotOrderByJoinedAtAscIdAsc(channelIds, userId),
                userId);
    }

    /**
     * 名前なし GROUP_DM の導出表示名を、チャンネルごとに返す（CMP-261113-0110）。
     *
     * <p>チャンネル一覧・詳細・作成系の応答（{@code ChannelResponse.derivedName}・{@link ChatGroupDmDerivedNameResolver} 経由）と
     * ダッシュボードのチャットハブ（{@code DirectMessageItemDto.derivedName}）が共通に使う唯一の導出経路である。
     * 参加者の並び（chat）は {@link #findOrderedOtherMemberUserIdsByChannel} で、参加者の表示名（auth）は
     * {@link NameResolverService#resolveUserDisplayNames} でそれぞれ 1 クエリにまとめて取得する（チャンネル数に比例しない）。
     * 表示名が返らない（ソフトデリート済み等）参加者は {@value ChatGroupDmDerivedName#UNKNOWN_USER_NAME}。</p>
     *
     * <p><b>本メソッドは自前のトランザクションを持たない</b>（{@code @Transactional} を付けない）。他ドメイン（auth）の
     * 読み取りを chat のトランザクションへ持ち込まないため（CLAUDE.md DB 設計の原則 #5・番人 D-3T）。チャンネル系の
     * 呼出元はトランザクションの外から呼ぶ。</p>
     *
     * @param targetChannelIds 導出対象（名前なし GROUP_DM）のチャンネル ID 群（対象判定は呼出側で済ませる）
     * @param userId           導出名から除外するユーザー ID（呼出ユーザー）
     * @param knownNames       既に引いた表示名（作成系がトランザクションの前に引いた分。無ければ空）。ここに無い参加者だけを引く
     * @return チャンネル ID → 導出名（参加者 0 名のチャンネルはキーを持たない）
     */
    public Map<Long, String> findGroupDmDerivedNameByChannel(Collection<Long> targetChannelIds, Long userId,
                                                             Map<Long, String> knownNames) {
        Map<Long, String> result = new HashMap<>();
        if (targetChannelIds == null || targetChannelIds.isEmpty()) {
            return result;
        }
        Map<Long, List<Long>> participantsByChannel = findOrderedOtherMemberUserIdsByChannel(targetChannelIds, userId);
        if (participantsByChannel.isEmpty()) {
            return result;
        }
        Set<Long> missing = new HashSet<>();
        participantsByChannel.values().forEach(missing::addAll);
        missing.removeAll(knownNames.keySet());
        Map<Long, String> names = new HashMap<>(knownNames);
        names.putAll(findUserDisplayNames(missing));
        participantsByChannel.forEach((channelId, participants) -> {
            String derived = ChatGroupDmDerivedName.format(participants, names::get);
            if (derived != null) {
                result.put(channelId, derived);
            }
        });
        return result;
    }

    /**
     * ユーザー ID 群の表示名を引く（導出名の唯一の表示名取得経路。ソフトデリート済みは含まれない）。
     * 自前のトランザクションを持たない（{@link #findGroupDmDerivedNameByChannel} と同じ理由）。
     *
     * @param userIds ユーザー ID 群
     * @return ユーザー ID → 表示名
     */
    public Map<Long, String> findUserDisplayNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return nameResolverService.resolveUserDisplayNames(new HashSet<>(userIds));
    }
}
