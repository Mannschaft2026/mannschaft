-- F06.4.1 個人の頑張り記録 PR-3a: チーム共有の表と加入期間の終了理由（CMP-261115-2145）
-- 設計正本: docs/features/F06.4.1_personal_effort_records_data.md §2（effort_team_shares）・§7（timeline_effort_sources）・§8（memberships）
-- チーム側の表は organization の列を持たず、経路キーは team_id（2026-11-18 マスター裁定）。
-- 表と列だけを先に入れる（共有・TL ガードは PR-3b）。本番データなし。

CREATE TABLE effort_team_shares (
  id BINARY(16) NOT NULL,
  team_id BIGINT UNSIGNED NOT NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  source_record_id BINARY(16) NOT NULL,
  source_version BIGINT NOT NULL,
  grant_membership_id BIGINT UNSIGNED NOT NULL,
  share_version BIGINT NOT NULL,
  definition_id BINARY(16) NOT NULL,
  recorded_on DATE NOT NULL,
  projection_json JSON NOT NULL,
  projection_hash CHAR(64) NOT NULL,
  state VARCHAR(12) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_effort_team_source (team_id, source_record_id),
  KEY idx_effort_team_date (team_id, deleted_at, state, recorded_on DESC, created_at DESC, id DESC),
  KEY idx_effort_team_membership (team_id, grant_membership_id, state),
  KEY idx_effort_team_person (team_id, owner_user_id, deleted_at, state, recorded_on),
  KEY idx_effort_team_definition (team_id, definition_id, deleted_at, state, recorded_on),
  CONSTRAINT chk_effort_team_share_state CHECK (state IN ('PENDING','VISIBLE','REVOKED','MODERATED')),
  CONSTRAINT chk_effort_team_share_versions CHECK (source_version >= 0 AND grant_membership_id >= 1
    AND share_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE timeline_effort_sources (
  id BINARY(16) NOT NULL,
  team_id BIGINT UNSIGNED NOT NULL,
  timeline_post_id BIGINT UNSIGNED NOT NULL,
  share_uuid BINARY(16) NOT NULL,
  grant_membership_id BIGINT UNSIGNED NOT NULL,
  share_version BIGINT NOT NULL,
  content_revision BIGINT NOT NULL,
  is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_timeline_effort_share (share_uuid),
  UNIQUE KEY uq_timeline_effort_post (timeline_post_id),
  KEY idx_timeline_effort_team (team_id, is_revoked),
  CONSTRAINT fk_timeline_effort_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id),
  CONSTRAINT chk_timeline_effort_versions CHECK (grant_membership_id >= 1 AND share_version >= 0
    AND content_revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 加入期間の終了理由（NULL = 在籍中、または移行前に終了した行。どちらも共有許可なし扱いは Service が決める）
ALTER TABLE memberships
  ADD COLUMN effort_share_disposition VARCHAR(32) NULL,
  ADD CONSTRAINT chk_membership_effort_share_disposition
    CHECK (effort_share_disposition IS NULL OR effort_share_disposition IN ('REVOKED','RETAINED_ACCOUNT_WITHDRAWAL'));
