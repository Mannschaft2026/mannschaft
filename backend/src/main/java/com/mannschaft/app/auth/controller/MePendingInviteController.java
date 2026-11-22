package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * /api/v1/me/pending-invite（CMP-261107-1833 設計 §2）。ユーザーは SecurityContext のみから取り、パスに ID を持たない。
 *
 * <p><b>試練スタブ</b>: 経路と署名だけを置いた（Bean 登録・認可・本体は出陣で実装する）。</p>
 */
@RequestMapping("/api/v1/me/pending-invite")
@RequiredArgsConstructor
public class MePendingInviteController {

    private final PendingInviteService pendingInviteService;

    @GetMapping
    public ApiResponse<PendingInviteResponse> get() {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    @PostMapping("/accept")
    public ApiResponse<PendingInviteAcceptResponse> accept() {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    @PostMapping("/decline")
    public ApiResponse<Void> decline() {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    @PostMapping("/reissue-request")
    public ApiResponse<PendingInviteReissueResponse> requestReissue() {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }

    @PostMapping("/dismiss")
    public ApiResponse<Void> dismiss() {
        throw new UnsupportedOperationException("CMP-261107-1833 未実装");
    }
}
