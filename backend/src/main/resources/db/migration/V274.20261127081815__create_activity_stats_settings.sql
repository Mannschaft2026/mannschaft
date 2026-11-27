-- F06.4 §15.3 活動統計のスコープ別設定（CMP-261124-0159 PR-A1b）
-- 上位参加者（名前つき）をメンバーに公開するか。行が無いスコープは既定（OFF＝管理者のみ）。
-- scope_id・updated_by は ID 参照のみ（クロスドメイン FK 禁止）。updated_by は退会者の物理削除時に NULL 化する。
CREATE TABLE activity_stats_settings (
    id                                  BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    scope_type                          VARCHAR(20)     NOT NULL COMMENT 'TEAM / ORGANIZATION / COMMITTEE',
    scope_id                            BIGINT UNSIGNED NOT NULL COMMENT 'スコープID（teams.id 等への ID 参照・FKなし）',
    top_participants_visible_to_members BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '上位参加者をメンバーに公開するか（既定OFF＝管理者のみ）',
    updated_by                          BIGINT UNSIGNED NULL COMMENT '最終更新者（users.id への ID 参照・FKなし。退会者の物理削除時にNULL）',
    version                             BIGINT          NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    created_at                          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '作成した瞬間（UTC）',
    updated_at                          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最後に更新した瞬間（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uq_activity_stats_settings_scope (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F06.4 活動統計のスコープ別設定（上位参加者の公開範囲）';
