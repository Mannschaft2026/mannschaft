-- CMP-260826-1457 W2: 連絡先招待トークンの平文保存をハッシュ保存へ統一する（V258 invite_tokens と同じ型）。
-- 平文トークンは保存せず SHA-256 の hex(64字)だけを token_hash に持つ。
-- 既存の平文は捨てる（御裁可: 既存の招待はすべて失効・猶予なし）。ハッシュ化しても平文は復元できないため、
-- 既存行は乱数から作ったハッシュで埋めて「どの平文でも照合に当たらない」状態にし、行は削除せず全件を失効させる。
--   - 行・ID・発行者・利用回数・上限・ラベルは残す（監査・通知の参照元を壊さない）
--   - revoked_at が既にある行は日時を保つ（COALESCE）。未失効の行だけ UTC 現在時刻で失効させる
-- MySQL は隣接文字列リテラルを連結しないため COMMENT は1リテラルで書く。

ALTER TABLE contact_invite_tokens ADD COLUMN token_hash VARCHAR(64) NULL COMMENT '招待トークンのSHA-256ハッシュ(hex)。平文は保存しない' AFTER user_id;

UPDATE contact_invite_tokens
   SET token_hash = SHA2(CONCAT(UUID(), UUID(), RAND(), id), 256),
       revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP());

ALTER TABLE contact_invite_tokens DROP INDEX uq_cit_token;
ALTER TABLE contact_invite_tokens DROP COLUMN token;
ALTER TABLE contact_invite_tokens MODIFY COLUMN token_hash VARCHAR(64) NOT NULL COMMENT '招待トークンのSHA-256ハッシュ(hex)。平文は保存しない';
ALTER TABLE contact_invite_tokens ADD CONSTRAINT uq_cit_token_hash UNIQUE (token_hash);
