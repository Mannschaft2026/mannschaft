package com.mannschaft.app.notification.confirmable.entity;

/**
 * CMP-261126-1558: 確認通知の下書きが保持する宛先の選び方。
 *
 * <ul>
 *   <li>{@link #DEFAULT}: 既定（送信スコープ配下すべて）</li>
 *   <li>{@link #TARGETS}: ターゲットを直接指定（{@code selected_targets}）</li>
 *   <li>{@link #GROUP}: 宛先グループを指定（{@code selected_group_id}）</li>
 * </ul>
 */
public enum ConfirmableDraftAudienceMode {
    DEFAULT,
    TARGETS,
    GROUP
}
