package com.mannschaft.app.tournament.service;

/**
 * 大会・ディビジョン連絡スペースに対する呼び出し者のアクセス水準（CMP-261116-0726）。
 *
 * <p>{@link TournamentContactSpaceAccessGate#requireViewResolvingLevel} の戻り値。閲覧すらできない者は
 * 例外（不在応答）となるため、ここに「権限なし」は存在しない。</p>
 */
public enum ContactAccessLevel {

    /** 閲覧のみ可能（公開スペースの閲覧者・参加チームの一般 active member）。 */
    VIEW,

    /** 投稿・管理が可能（参加チームの ADMIN/DEPUTY_ADMIN・主催組織 ADMIN・SYSTEM_ADMIN）。 */
    POST
}
