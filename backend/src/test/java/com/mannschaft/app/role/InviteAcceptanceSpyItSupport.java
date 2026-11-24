package com.mannschaft.app.role;

import com.mannschaft.app.chat.service.ChatMembershipInviteCardService;
import com.mannschaft.app.role.service.InviteAcceptanceWriter;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 招待承諾の障害・部分失敗の試験が共有する spy 一式（構成キーを 1 つに揃えるための中間基底）。
 *
 * <p>BeanOverride（{@code @MockitoSpyBean}）の集合は Spring の構成キーに含まれ、集合が 1 つでも違えば別 context になる。
 * 障害注入の IT ごとに spy の集合を変えると構成キー（起動する context 数）が増えるため、同じ集合を宣言する。
 * 使わない spy も本物へ委譲する（呼び出しを記録するだけで挙動は変えない）ので、使わない試験への影響は無い。
 * 規約 31「構成キーの棚卸しと認可 override の番人」（distinct key のラチェット）。</p>
 */
abstract class InviteAcceptanceSpyItSupport extends InviteAcceptanceItSupport {

    @MockitoSpyBean protected MembershipGrantService grantSpy;
    @MockitoSpyBean protected InviteAcceptanceWriter writerSpy;
    @MockitoSpyBean protected MyScopeFolderService folderSpy;
    @MockitoSpyBean protected ChatMembershipInviteCardService cardSpy;
}
