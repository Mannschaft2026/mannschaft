-- 招待付き新規登録の保留承認（CMP-261107-1833）。
-- 招待リンクから新規登録した利用者は、登録時点では参加させず、この表に保留を1行だけ残す。
-- メール確認（未成年は保護者同意）・ログインの後、本人が承認したときに初めて招待を使って参加する。
-- 招待トークンの平文は持たず、SecretTokenVault のハッシュ（token_hash）だけを保存する。
-- user_id は同ドメイン（auth）の users への FK（ON DELETE CASCADE）。招待表・チーム・組織へは FK を張らない
-- （クロスドメインFK禁止）。1利用者につき1行（UNIQUE(user_id)）。
-- 時刻列の「今」はアプリの native 文が UTC_TIMESTAMP(6) で書く（JPA 経路は UTC 壁時計へ変換して格納する）。
CREATE TABLE registration_pending_invites (
    id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL COMMENT '登録した利用者',
    token_hash CHAR(64) NOT NULL COMMENT '招待トークンのハッシュ（平文は保存しない）',
    scope_type VARCHAR(20) NOT NULL COMMENT '招待先の種類（TEAM または ORGANIZATION）',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT '招待先のチームまたは組織の ID',
    status VARCHAR(20) NOT NULL COMMENT '保留の状態',
    failure_code VARCHAR(20) NULL COMMENT '参加できなかった理由のエラーコード',
    accept_attempt_id BINARY(16) NULL COMMENT '処理中（ACCEPTING）の承認試行の世代',
    accepting_started_at DATETIME(6) NULL COMMENT '承認処理を始めた時刻（UTC）',
    reissue_requested_at DATETIME(6) NULL COMMENT '再招待を最後に要請した時刻（UTC）',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_registration_pending_invites_user_id UNIQUE (user_id),
    CONSTRAINT fk_registration_pending_invites_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_registration_pending_invites_status CHECK (status IN ('PENDING', 'ACCEPTING', 'JOINED',
        'EXPIRED', 'UNAVAILABLE', 'DECLINED', 'REISSUE_REQUESTED', 'DISMISSED', 'DISCARDED')),
    CONSTRAINT chk_registration_pending_invites_scope_type CHECK (scope_type IN ('TEAM', 'ORGANIZATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
