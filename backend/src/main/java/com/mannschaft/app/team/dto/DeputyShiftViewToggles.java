package com.mannschaft.app.team.dto;

/**
 * MANAGE_SHIFTS を持たない DEPUTY_ADMIN にシフトの閲覧を許す切替（CMP-261125-2114）。
 *
 * <p>他ドメイン（shift）の認可が値として受け取るための最小の読み取り専用 record。設定行が無いチームは
 * 既定どおりどちらも OFF（{@link #OFF}）。</p>
 *
 * @param shiftView      未公開シフト表・割当の非マスク・他人の交代/変更依頼・希望一覧/サマリー・PDF 管理者出力・
 *                       ダッシュボードのシフト希望の閲覧を許すか
 * @param hourlyRateView 他メンバーの時給の閲覧を許すか
 */
public record DeputyShiftViewToggles(boolean shiftView, boolean hourlyRateView) {

    /** 設定行が無い場合の既定（どちらも OFF）。 */
    public static final DeputyShiftViewToggles OFF = new DeputyShiftViewToggles(false, false);
}
