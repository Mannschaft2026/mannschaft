package com.mannschaft.app.search;

/**
 * F04.6 グローバル検索の検索対象種別（設計書 §3）。
 *
 * <p>API の {@code type} パラメータと応答の {@code type} / {@code typeCounts} のキーに、
 * この列挙子名（大文字）をそのまま使う。未定義値・小文字は 400。</p>
 */
public enum SearchType {
    /** タイムライン投稿。 */
    POST,
    /** チャットメッセージ。 */
    MESSAGE,
    /** 掲示板スレッド。 */
    THREAD,
    /** ブログ記事。 */
    ARTICLE,
    /** ファイル。 */
    FILE,
    /** ユーザー。 */
    USER,
    /** チーム。 */
    TEAM,
    /** 組織。 */
    ORGANIZATION,
    /** 活動記録。 */
    ACTIVITY
}
