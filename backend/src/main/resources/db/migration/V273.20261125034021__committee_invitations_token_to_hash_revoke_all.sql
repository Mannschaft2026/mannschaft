-- CMP-260826-1457 W2 committee: 委員会招集トークンの平文保存をハッシュ保存へ統一する（V258 invite_tokens と同じ型）。
-- 平文トークンは保存せず SHA-256 の hex(64字)だけを invite_token_hash に持つ。
-- 既存の平文は捨てる（御裁可: 既存の未解決の招集は即失効・旧値の互換照合は作らない）。ハッシュ化しても平文は復元できないため、
-- 既存行は乱数から作ったハッシュで埋めて「どの平文でも照合に当たらない」状態にし、行は削除せず未解決の招集を失効させる。
--   - 行・ID・委員会・被招集者・提案ロール・招集者・メッセージは残す（監査を壊さない）
--   - すでに解決済み（ACCEPTED / DECLINED / EXPIRED / CANCELLED）の行は resolved_at・resolution を変えない
--   - 未解決の行だけ UTC 現在時刻で resolved_at を入れ resolution=EXPIRED にする。解決済みになるため、
--     「未解決の招集が既にある」ことを理由にした再招集のスキップ（existsBy…ResolvedAtIsNull）に当たらず、再招集できる
-- MySQL は隣接文字列リテラルを連結しないため COMMENT は1リテラルで書く。

ALTER TABLE committee_invitations ADD COLUMN invite_token_hash VARCHAR(64) NULL COMMENT '招集トークンのSHA-256ハッシュ(hex)。平文は保存しない' AFTER proposed_role;

UPDATE committee_invitations
   SET invite_token_hash = SHA2(CONCAT(UUID(), UUID(), RAND(), id), 256),
       resolution = IF(resolved_at IS NULL, 'EXPIRED', resolution),
       resolved_at = COALESCE(resolved_at, UTC_TIMESTAMP());

ALTER TABLE committee_invitations DROP INDEX uq_committee_invitations_token;
ALTER TABLE committee_invitations DROP COLUMN invite_token;
ALTER TABLE committee_invitations MODIFY COLUMN invite_token_hash VARCHAR(64) NOT NULL COMMENT '招集トークンのSHA-256ハッシュ(hex)。平文は保存しない';
ALTER TABLE committee_invitations ADD CONSTRAINT uq_committee_invitations_token_hash UNIQUE (invite_token_hash);
