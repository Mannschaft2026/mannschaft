-- F10.9 本人承認制のサポート閲覧（代理ログイン）: 依頼・許可・期限・取消、代理中の閲覧記録（戦役 Z-B）
-- users へは FK を張らず ID だけ持つ（クロスドメイン FK 禁止・原則1）。grants と logs は同一ドメインなので FK 可。
CREATE TABLE support_access_grants (
    id                          BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    operator_user_id            BIGINT UNSIGNED NOT NULL COMMENT '依頼した運営専用アカウント(users.id・FKなし)',
    target_user_id              BIGINT UNSIGNED NOT NULL COMMENT '代理される本人(users.id・FKなし)',
    status                      VARCHAR(20)     NOT NULL COMMENT 'PENDING/APPROVED/DENIED/REVOKED/EXPIRED/CANCELLED',
    request_reason              VARCHAR(1000)   NOT NULL COMMENT '運営の依頼理由(必須・プレーンテキスト)',
    reference_type              VARCHAR(30)     NULL COMMENT '依頼の参照元種別(例 FEEDBACK)',
    reference_id                BIGINT UNSIGNED NULL COMMENT '依頼の参照元ID',
    requested_at                DATETIME(6)     NOT NULL COMMENT '依頼時刻',
    request_expires_at          DATETIME(6)     NOT NULL COMMENT 'PENDING の失効時刻(依頼+72h)',
    decided_at                  DATETIME(6)     NULL COMMENT '本人の許可/拒否の時刻',
    deny_comment                VARCHAR(500)    NULL COMMENT '拒否時のコメント(任意)',
    expires_at                  DATETIME(6)     NULL COMMENT '許可の期限(最後の許可+24h・固定・延長不可)',
    revoked_at                  DATETIME(6)     NULL COMMENT '取消時刻',
    ended_reason                VARCHAR(30)     NULL COMMENT 'USER_REVOKED/OPERATOR_CANCELLED/EXPIRED/WITHDRAWAL/OPERATOR_INELIGIBLE',
    last_session_started_at     DATETIME(6)     NULL COMMENT '直近の閲覧開始時刻',
    active_session_started_at   DATETIME(6)     NULL COMMENT '閲覧開始(POST sessions)の時刻。NULLなら未開始でヘッダーは403',
    active_session_ended_at     DATETIME(6)     NULL COMMENT '閲覧終了の時刻。値があれば終了済みでヘッダーは403(閲覧開始で NULL に戻す)',
    guardian_approval_required  TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '未成年・後見下の本人で保護者の承認も要るか',
    guardian_user_id            BIGINT UNSIGNED NULL COMMENT '承認する保護者(users.id・FKなし)',
    guardian_decided_at         DATETIME(6)     NULL COMMENT '保護者の許可時刻',
    open_flag                   TINYINT         AS (CASE WHEN status IN ('PENDING','APPROVED') THEN 1 ELSE NULL END) STORED COMMENT '開いている依頼の一意制約用(生成列)',
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_support_access_grants_open (operator_user_id, target_user_id, open_flag),
    KEY idx_support_access_grants_target_status (target_user_id, status, expires_at),
    KEY idx_support_access_grants_operator_status (operator_user_id, status, requested_at),
    KEY idx_support_access_grants_status_expires (status, expires_at),
    KEY idx_support_access_grants_status_request_expires (status, request_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='F10.9 サポート閲覧の依頼と本人許可';

CREATE TABLE support_access_logs (
    id                BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    grant_id          BINARY(16)      NOT NULL COMMENT 'support_access_grants.id',
    operator_user_id  BIGINT UNSIGNED NOT NULL COMMENT '閲覧した運営者(users.id・FKなし)',
    target_user_id    BIGINT UNSIGNED NOT NULL COMMENT '閲覧された本人(users.id・FKなし)',
    event_kind        VARCHAR(20)     NOT NULL COMMENT 'SESSION_START/VIEW/DENIED/SESSION_END',
    http_method       VARCHAR(10)     NULL,
    request_path      VARCHAR(512)    NULL COMMENT 'クエリ文字列は保存しない(PII回避)',
    route_pattern     VARCHAR(255)    NULL COMMENT 'Springの最適一致パターン',
    scope_type        VARCHAR(20)     NULL COMMENT 'TEAM/ORGANIZATION/PERSONAL 等(パスから推定)',
    scope_id          BIGINT UNSIGNED NULL,
    http_status       SMALLINT        NULL,
    deny_code         VARCHAR(30)     NULL,
    occurred_at       DATETIME(6)     NOT NULL,
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_support_access_logs_target_occurred (target_user_id, occurred_at),
    KEY idx_support_access_logs_grant_occurred (grant_id, occurred_at),
    KEY idx_support_access_logs_occurred (occurred_at),
    CONSTRAINT fk_support_access_logs_support_access_grants FOREIGN KEY (grant_id) REFERENCES support_access_grants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='F10.9 サポート閲覧の閲覧記録';

-- 遷移・拒否の監査の outbox。遷移と同じトランザクションで1行書き、コミット後に audit_logs へ反映する。
-- 反映に失敗した行は published_at が NULL のまま残り、再試行バッチが拾う（遷移の確定と監査の欠落が起きない）。
CREATE TABLE support_access_audit_outbox (
    id                BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    grant_id          BINARY(16)      NULL COMMENT 'support_access_grants.id(拒否の監査では無い場合がある・FKなし)',
    event_type        VARCHAR(50)     NOT NULL COMMENT 'audit_logs.event_type(SUPPORT_ACCESS_*)',
    operator_user_id  BIGINT UNSIGNED NULL COMMENT '運営者(users.id・FKなし)',
    target_user_id    BIGINT UNSIGNED NULL COMMENT '本人(users.id・FKなし)',
    occurred_at       DATETIME(6)     NOT NULL COMMENT '遷移の時刻',
    attempt_count     INT             NOT NULL DEFAULT 0 COMMENT 'audit_logs への反映の試行回数',
    next_attempt_at   DATETIME(6)     NOT NULL COMMENT '次に反映を試みてよい時刻(取得中の行はリースで先送り)',
    last_error        VARCHAR(500)    NULL COMMENT '直近の反映失敗の要約',
    published_at      DATETIME(6)     NULL COMMENT 'audit_logs へ反映した時刻。NULLなら未反映',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_support_access_audit_outbox_pending (published_at, next_attempt_at),
    KEY idx_support_access_audit_outbox_grant (grant_id, published_at),
    KEY idx_support_access_audit_outbox_target (target_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='F10.9 サポート閲覧の監査 outbox';

-- 運営者ごとの依頼の排他（1日20件の上限の count→insert を直列化する）。行の中身は使わず、行ロックだけを使う。
CREATE TABLE support_access_request_locks (
    operator_user_id  BIGINT UNSIGNED NOT NULL COMMENT '依頼する運営者(users.id・FKなし)',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (operator_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='F10.9 サポート閲覧の依頼の運営者単位の排他';
