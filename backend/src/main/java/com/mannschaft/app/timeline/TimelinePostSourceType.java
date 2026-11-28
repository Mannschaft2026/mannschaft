package com.mannschaft.app.timeline;

/**
 * タイムライン投稿の由来（応答専用。DB へは保存しない）。
 *
 * <p>F06.4.1: 対応表 {@code timeline_effort_sources} に行がある投稿（個人の頑張り記録のチーム共有）は
 * {@link #PERSONAL_EFFORT_SHARE}。通常の投稿は応答で {@code null}（本文の文字列では判定しない）。</p>
 */
public enum TimelinePostSourceType {
    /** 個人の頑張り記録のチーム共有から作られた投稿。 */
    PERSONAL_EFFORT_SHARE
}
