package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatGroupDmDerivedName;
import com.mannschaft.app.chat.dto.ChannelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 名前なし GROUP_DM（Zimmer）の導出表示名を解決する（CMP-261113-0110）。
 *
 * <p><b>トランザクションの外で使う</b>（本クラスに {@code @Transactional} を付けてはならない）。導出に要る参加者の表示名は
 * auth ドメインのユーザーであり、chat の書き込み・読み取りトランザクションに他ドメインの読み取りを巻き込まない
 * （CLAUDE.md DB 設計の原則 #5・番人 D-3T）。参加者の並び（chat）は {@link ChatMemberBatchQueryService}、
 * 表示名（auth）は {@code NameResolverService#resolveUserDisplayNames} でそれぞれ 1 クエリにまとめて取得し、
 * 連結は {@link ChatGroupDmDerivedName} の規則に従う。</p>
 *
 * <p>チャンネル一覧・詳細・作成系の応答（{@code ChannelResponse.derivedName}）と、ダッシュボードのチャットハブ
 * （{@code DirectMessageItemDto.derivedName}）は、すべて {@link ChatMemberBatchQueryService#findGroupDmDerivedNameByChannel}
 * を通る（本クラスはチャンネル系の呼出元向けの薄い入口）。</p>
 *
 * <p>表示名の取得は {@code NameResolverService} の 1 経路に一本化している。同サービスはユーザーを
 * {@code findAllById}（1 クエリ）で引き、ソフトデリート済みのユーザーは返さない（{@code @SQLRestriction}）。
 * 返らなかった参加者は {@value ChatGroupDmDerivedName#UNKNOWN_USER_NAME} と表示する。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatGroupDmDerivedNameResolver {

    private final ChatMemberBatchQueryService chatMemberBatchQueryService;

    /**
     * ユーザー ID 群の表示名を引く（ソフトデリート済みは含まれない）。
     *
     * <p>作成系（Zimmer 招待・グループ変換・会話開始）は、書き込みトランザクションの<b>前</b>にこれで参加予定者の
     * 表示名を引き、{@link #resolve(Collection, Long, Map)} に渡す。表示名の取得が失敗すれば、チャンネル・メンバー・
     * コピー履歴を 1 行も書かずに作成ごと失敗する（AC-16）。</p>
     *
     * @param userIds ユーザー ID 群
     * @return ユーザー ID → 表示名
     */
    public Map<Long, String> lookupDisplayNames(Collection<Long> userIds) {
        return chatMemberBatchQueryService.findUserDisplayNames(userIds);
    }

    /**
     * 作成系の事前取得: 元チャンネル（Kabine / 変換前の DM）の呼出ユーザー以外のメンバーと、追加で参加する
     * ユーザーの表示名を、書き込みトランザクションの前に引く（AC-16。取得が失敗すれば何も書かずに失敗する）。
     *
     * @param sourceChannelId 元チャンネル ID（無ければ null）
     * @param callerId        呼出ユーザー ID
     * @param extraUserIds    追加で参加するユーザー ID 群（無ければ空）
     * @return ユーザー ID → 表示名
     */
    public Map<Long, String> prefetchParticipantNames(Long sourceChannelId, Long callerId,
                                                      Collection<Long> extraUserIds) {
        Set<Long> userIds = new HashSet<>();
        if (sourceChannelId != null) {
            chatMemberBatchQueryService.findOrderedOtherMemberUserIdsByChannel(List.of(sourceChannelId), callerId)
                    .values().forEach(userIds::addAll);
        }
        if (extraUserIds != null) {
            userIds.addAll(extraUserIds);
        }
        userIds.remove(callerId);
        return lookupDisplayNames(userIds);
    }

    /**
     * 導出名の対象チャンネル群について、導出名を返す。
     *
     * @param targetChannelIds 名前なし GROUP_DM のチャンネル ID 群（対象判定は呼出側で済ませる）
     * @param viewerId         呼出ユーザー ID（導出名から除外する）
     * @param knownNames       既に引いた表示名（作成系の事前取得分。無ければ空）。ここに無い参加者だけを追加で引く
     * @return チャンネル ID → 導出名（参加者 0 名のチャンネルはキーを持たない）
     */
    public Map<Long, String> resolve(Collection<Long> targetChannelIds, Long viewerId, Map<Long, String> knownNames) {
        return chatMemberBatchQueryService.findGroupDmDerivedNameByChannel(targetChannelIds, viewerId, knownNames);
    }

    /** {@link #resolve(Collection, Long, Map)} の事前取得なし版。 */
    public Map<Long, String> resolve(Collection<Long> targetChannelIds, Long viewerId) {
        return resolve(targetChannelIds, viewerId, Map.of());
    }

    /**
     * チャンネル応答群に導出名を付ける（名前なし GROUP_DM のみ。それ以外は null のまま）。
     *
     * @param channels チャンネル応答群
     * @param viewerId 呼出ユーザー ID
     * @return 導出名を付けた応答群（順序は入力どおり）
     */
    public List<ChannelResponse> withDerivedNames(List<ChannelResponse> channels, Long viewerId) {
        return withDerivedNames(channels, viewerId, Map.of());
    }

    /** {@link #withDerivedNames(List, Long)} の、作成系の事前取得した表示名を使う版。 */
    public List<ChannelResponse> withDerivedNames(List<ChannelResponse> channels, Long viewerId,
                                                  Map<Long, String> knownNames) {
        List<Long> targets = channels.stream()
                .filter(Objects::nonNull)
                .filter(ChatGroupDmDerivedNameResolver::isTarget)
                .map(ChannelResponse::getId)
                .toList();
        if (targets.isEmpty()) {
            return channels;
        }
        Map<Long, String> derived = resolve(targets, viewerId, knownNames);
        return channels.stream()
                .map(ch -> ch != null && derived.containsKey(ch.getId())
                        ? ch.toBuilder().derivedName(derived.get(ch.getId())).build()
                        : ch)
                .toList();
    }

    /** 1 件版。 */
    public ChannelResponse withDerivedName(ChannelResponse channel, Long viewerId, Map<Long, String> knownNames) {
        if (channel == null) {
            return null;
        }
        return withDerivedNames(List.of(channel), viewerId, knownNames).get(0);
    }

    /** 1 件・事前取得なし版。 */
    public ChannelResponse withDerivedName(ChannelResponse channel, Long viewerId) {
        return withDerivedName(channel, viewerId, Map.of());
    }

    private static boolean isTarget(ChannelResponse channel) {
        boolean groupDm = channel.getIdentity() != null
                && ChannelType.GROUP_DM.name().equals(channel.getIdentity().channelType());
        String name = channel.getMeta() != null ? channel.getMeta().name() : null;
        return groupDm && ChatGroupDmDerivedName.isTarget(ChannelType.GROUP_DM, name);
    }
}
