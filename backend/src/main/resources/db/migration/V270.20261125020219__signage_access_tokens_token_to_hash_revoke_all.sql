-- CMP-260826-1457 W2 signage: サイネージアクセストークンの平文保存をハッシュ保存へ統一する。
-- 平文トークンは保存せず SHA-256 の hex(64字)だけを token_hash に持つ（invite_tokens=V258 と同じ流儀）。
-- 既存の平文は捨てる（御裁可: 全系統で既存トークンは即失効・猶予なし。本番は未構築で実利用者のリンクは無い）。
-- ハッシュ化しても平文は復元できないため、既存行は乱数から作ったハッシュで埋めて「どの平文でも照合に当たらない」
-- 状態にし、行は削除せず全件を失効（is_active=0）させる。行・ID・画面との結び付き・端末名・期限・許可IPは残す。
-- MySQL は隣接文字列リテラルを連結しないため COMMENT は1リテラルで書く。

ALTER TABLE signage_access_tokens ADD COLUMN token_hash VARCHAR(64) NULL COMMENT 'アクセストークンのSHA-256ハッシュ(hex)。平文は保存しない' AFTER screen_id;

UPDATE signage_access_tokens
   SET token_hash = SHA2(CONCAT(UUID(), UUID(), RAND(), id), 256),
       is_active = 0,
       updated_at = UTC_TIMESTAMP();

-- V11.156 のインライン UNIQUE（索引名は列名と同じ token）と idx_sat_token を、列ごと廃止する。
ALTER TABLE signage_access_tokens DROP INDEX idx_sat_token;
ALTER TABLE signage_access_tokens DROP INDEX token;
ALTER TABLE signage_access_tokens DROP COLUMN token;
ALTER TABLE signage_access_tokens MODIFY COLUMN token_hash VARCHAR(64) NOT NULL COMMENT 'アクセストークンのSHA-256ハッシュ(hex)。平文は保存しない';
ALTER TABLE signage_access_tokens ADD CONSTRAINT uq_sat_token_hash UNIQUE (token_hash);
