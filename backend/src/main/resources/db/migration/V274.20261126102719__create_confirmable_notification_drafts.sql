-- 確認通知の作成フォームの下書き（CMP-261126-1558）。
-- スコープ（TEAM / ORGANIZATION）×作成者ごとに複数件（上限10件はアプリ層で保証）を保存する。
-- user_id・scope_id はクロスドメイン参照のため FK を張らない（インデックスのみ）。一意制約も付けない。
-- 退会30日後の強匿名化（ConfirmableNotificationDraftPurgeEventListener）と、updated_at から90日経過後の
-- 日次掃除バッチが物理削除する。時刻列は UTC（JPA 経路は UTC 壁時計へ変換して格納する）。
CREATE TABLE confirmable_notification_drafts (
    id BINARY(16) NOT NULL,
    scope_type ENUM('TEAM', 'ORGANIZATION') NOT NULL COMMENT '下書きの送信スコープの種類',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT '送信スコープのチームまたは組織の ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '作成者（本人だけが読み書きできる）',
    title VARCHAR(200) NOT NULL DEFAULT '' COMMENT '件名（入力途中は空でもよい）',
    body TEXT NULL COMMENT '本文（API の上限は10,000文字）',
    priority ENUM('NORMAL', 'HIGH', 'URGENT') NOT NULL DEFAULT 'NORMAL' COMMENT '優先度',
    deadline_at DATETIME(6) NULL COMMENT '確認期限（UTC）',
    audience_mode ENUM('DEFAULT', 'TARGETS', 'GROUP') NOT NULL DEFAULT 'DEFAULT' COMMENT '宛先の選び方',
    selected_targets JSON NULL COMMENT '直接指定した宛先 [{"type":"TEAM","id":1}]（最大200件）',
    selected_group_id BINARY(16) NULL COMMENT '選んだ宛先グループの ID',
    selected_template_id BIGINT UNSIGNED NULL COMMENT '選んだテンプレートの ID',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '楽観ロック。PUT の baseVersion と照合する',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cnd_scope_user_updated (scope_type, scope_id, user_id, updated_at),
    KEY idx_cnd_user (user_id),
    KEY idx_cnd_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
