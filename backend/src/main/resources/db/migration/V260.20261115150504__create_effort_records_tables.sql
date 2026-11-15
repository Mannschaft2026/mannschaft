-- F06.4.1 個人の頑張り記録 PR-1: 定義・定義revision・原本・冪等receipt と標準プリセット seed
-- 設計正本: docs/features/F06.4.1_personal_effort_records_data.md §2
-- チーム共有 projection 表（effort_team_shares）と memberships の拡張は PR-3 で追加する。
-- クロスドメイン FK は張らない。FK は effort 定義の内部（revision -> definition）のみ。

CREATE TABLE effort_definitions (
  id BINARY(16) NOT NULL,
  source_type VARCHAR(10) NOT NULL,
  source_key VARCHAR(80) NOT NULL,
  organization_id BIGINT UNSIGNED NULL,
  team_id BIGINT UNSIGNED NULL,
  team_template_id BIGINT UNSIGNED NULL,
  current_revision INT UNSIGNED NOT NULL,
  retired_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_effort_definition_source (source_type, source_key),
  KEY idx_effort_definition_team (organization_id, team_id, deleted_at),
  CONSTRAINT chk_effort_definition_source_type CHECK (source_type IN ('PRESET','TEAM')),
  CONSTRAINT chk_effort_definition_revision CHECK (current_revision >= 1 AND version >= 0),
  CONSTRAINT chk_effort_definition_scope CHECK (
    (source_type = 'PRESET' AND organization_id IS NULL AND team_id IS NULL AND team_template_id IS NULL)
    OR (source_type = 'TEAM' AND organization_id IS NOT NULL AND team_id IS NOT NULL AND team_template_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE effort_definition_revisions (
  id BINARY(16) NOT NULL,
  definition_id BINARY(16) NOT NULL,
  revision INT UNSIGNED NOT NULL,
  definition_json JSON NOT NULL,
  definition_hash CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_effort_revision (definition_id, revision),
  CONSTRAINT fk_effort_revision_definition FOREIGN KEY (definition_id) REFERENCES effort_definitions (id),
  CONSTRAINT chk_effort_revision_number CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE effort_records (
  id BINARY(16) NOT NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  definition_id BINARY(16) NOT NULL,
  definition_revision INT UNSIGNED NOT NULL,
  definition_snapshot JSON NOT NULL,
  recorded_on DATE NOT NULL,
  record_timezone VARCHAR(64) NOT NULL,
  values_json JSON NOT NULL,
  private_note TEXT NULL,
  purged_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  content_revision BIGINT NOT NULL DEFAULT 1,
  share_definition_snapshot JSON NULL,
  share_mapping_json JSON NULL,
  share_id BINARY(16) NULL,
  share_organization_id BIGINT UNSIGNED NULL,
  share_team_id BIGINT UNSIGNED NULL,
  share_state VARCHAR(12) NULL,
  share_version BIGINT NOT NULL DEFAULT 0,
  grant_membership_id BIGINT UNSIGNED NULL,
  selected_field_keys JSON NULL,
  desired_projection JSON NULL,
  desired_hash CHAR(64) NULL,
  synced_source_version BIGINT NULL,
  last_error_code VARCHAR(32) NULL,
  share_updated_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_effort_record_share (share_id),
  KEY idx_effort_owner_date (owner_user_id, deleted_at, recorded_on DESC, created_at DESC, id DESC),
  KEY idx_effort_owner_definition (owner_user_id, definition_id, deleted_at, recorded_on),
  KEY idx_effort_sync (share_state, share_updated_at, id),
  CONSTRAINT chk_effort_record_versions CHECK (version >= 0 AND content_revision >= 1 AND share_version >= 0 AND definition_revision >= 1),
  CONSTRAINT chk_effort_record_share_state CHECK (share_state IS NULL OR share_state IN ('PENDING','VISIBLE','FAILED','REVOKED','MODERATED')),
  CONSTRAINT chk_effort_record_share_group CHECK (
    (share_id IS NULL AND share_organization_id IS NULL AND share_team_id IS NULL AND share_state IS NULL)
    OR (share_id IS NOT NULL AND share_organization_id IS NOT NULL AND share_team_id IS NOT NULL
        AND share_state IS NOT NULL AND grant_membership_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE effort_operation_receipts (
  id BINARY(16) NOT NULL,
  route_kind VARCHAR(10) NOT NULL,
  route_id BIGINT UNSIGNED NOT NULL,
  actor_user_id BIGINT UNSIGNED NOT NULL,
  operation_key CHAR(36) NOT NULL,
  operation_path VARCHAR(255) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_status SMALLINT UNSIGNED NULL,
  response_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_effort_receipt (route_kind, route_id, actor_user_id, operation_path, operation_key),
  KEY idx_effort_receipt_expiry (expires_at),
  CONSTRAINT chk_effort_receipt_route_kind CHECK (route_kind IN ('OWNER','TEAM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 標準プリセット定義 seed（固定 UUIDv7 形式。全シャードへ同じ UUID/内容で配布する）
--   STUDY_V1    : 01938a00-0000-7000-8000-000000000001
--   EXERCISE_V1 : 01938a00-0000-7000-8000-000000000002
--   WEIGHT_V1   : 01938a00-0000-7000-8000-000000000003
INSERT INTO effort_definitions
  (id, source_type, source_key, organization_id, team_id, team_template_id, current_revision, retired_at, version, created_at, updated_at, deleted_at)
VALUES
  (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001', '-', '')), 'PRESET', 'STUDY_V1', NULL, NULL, NULL, 1, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL),
  (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000002', '-', '')), 'PRESET', 'EXERCISE_V1', NULL, NULL, NULL, 1, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL),
  (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000003', '-', '')), 'PRESET', 'WEIGHT_V1', NULL, NULL, NULL, 1, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL);

SET @effort_study_json = '{"definitionUuid":"01938a00-0000-7000-8000-000000000001","revision":1,"source":"PRESET","presetCode":"STUDY_V1","teamTemplateId":null,"templateName":"勉強","durationFieldKey":"duration","subjectFieldKey":"subject","fields":[{"fieldKey":"subject","label":"科目","labelKey":"effort.field.subject","type":"TEXT","required":true,"semantic":"SUBJECT","unit":"","aggregation":"NONE","scale":0,"min":null,"max":null,"options":[]},{"fieldKey":"duration","label":"時間","labelKey":"effort.field.duration","type":"NUMBER","required":true,"semantic":"DURATION","unit":"min","aggregation":"SUM","scale":0,"min":"0","max":"1440","options":[]}]}';
SET @effort_exercise_json = '{"definitionUuid":"01938a00-0000-7000-8000-000000000002","revision":1,"source":"PRESET","presetCode":"EXERCISE_V1","teamTemplateId":null,"templateName":"運動","durationFieldKey":"duration","subjectFieldKey":null,"fields":[{"fieldKey":"duration","label":"時間","labelKey":"effort.field.duration","type":"NUMBER","required":true,"semantic":"DURATION","unit":"min","aggregation":"SUM","scale":0,"min":"0","max":"1440","options":[]},{"fieldKey":"intensity","label":"強度","labelKey":"effort.field.intensity","type":"NUMBER","required":false,"semantic":"INTENSITY","unit":"level","aggregation":"NONE","scale":0,"min":"1","max":"10","options":[]},{"fieldKey":"menu","label":"メニュー","labelKey":"effort.field.menu","type":"TEXT","required":false,"semantic":"MENU","unit":"","aggregation":"NONE","scale":0,"min":null,"max":null,"options":[]}]}';
SET @effort_weight_json = '{"definitionUuid":"01938a00-0000-7000-8000-000000000003","revision":1,"source":"PRESET","presetCode":"WEIGHT_V1","teamTemplateId":null,"templateName":"体重","durationFieldKey":null,"subjectFieldKey":null,"fields":[{"fieldKey":"weight","label":"体重","labelKey":"effort.field.weight","type":"NUMBER","required":true,"semantic":"WEIGHT","unit":"kg","aggregation":"LATEST","scale":2,"min":"0.01","max":"500.00","options":[]}]}';

INSERT INTO effort_definition_revisions
  (id, definition_id, revision, definition_json, definition_hash, created_at, updated_at)
VALUES
  (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000011', '-', '')), UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001', '-', '')), 1, CAST(@effort_study_json AS JSON), SHA2(@effort_study_json, 256), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000012', '-', '')), UNHEX(REPLACE('01938a00-0000-7000-8000-000000000002', '-', '')), 1, CAST(@effort_exercise_json AS JSON), SHA2(@effort_exercise_json, 256), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
  (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000013', '-', '')), UNHEX(REPLACE('01938a00-0000-7000-8000-000000000003', '-', '')), 1, CAST(@effort_weight_json AS JSON), SHA2(@effort_weight_json, 256), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
