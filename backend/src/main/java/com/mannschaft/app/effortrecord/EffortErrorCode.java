package com.mannschaft.app.effortrecord;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.4.1 個人の頑張り記録のエラーコード定義。
 *
 * <p>設計書 {@code F06.4.1_personal_effort_records_api.md} §6 の割当に従う。
 * HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} で明示登録する
 * （001=404 / 002=409 / 003=403 / 004,005=503）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum EffortErrorCode implements ErrorCode {

    /** 原本不在 / 他人の原本 / 共有不在 / 別テナント（区別しない。存在秘匿の 404） */
    EFFORT_001("EFFORT_001", "記録が見つかりません", Severity.WARN),

    /** チームテンプレート廃止・revision 変更により新規共有できない */
    EFFORT_002("EFFORT_002", "このテンプレートは変更または廃止されたため共有できません", Severity.WARN),

    /**
     * 努力由来 source への通常操作すべて（like/mitayo/comment/reply/quote/repost/通常本文更新/deletePost/pin/bookmark/
     * フレンドチーム転送/公開化）。既定文言は全操作に通じる形で、取消 API へ誘導する（API 契約 §6）。
     */
    EFFORT_003("EFFORT_003", "この投稿には操作できません。共有をやめるときは、記録の『共有を取り消す』から行ってください",
            Severity.WARN),

    /** F00/source 照会障害。障害を空一覧に置換せず取得失敗とする */
    EFFORT_004("EFFORT_004", "記録の取得に失敗しました。時間をおいて再度お試しください", Severity.ERROR),

    /** 共有処理インフラ障害で要求を保存できない（保存済みの原本は残る） */
    EFFORT_005("EFFORT_005", "共有の処理を受け付けられませんでした。個人の記録は保存されています", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
