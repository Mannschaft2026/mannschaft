-- CMP-260826-1457 W2 filesharing: ファイル共有リンクの平文保存をハッシュ保存へ統一する（V258 invite_tokens と同じ型）。
-- 平文トークンは保存せず SHA-256 の hex(64字)だけを token_hash に持つ。
-- 既存の平文は捨てる（御裁可: 既存のリンクはすべて即失効・旧値の互換照合は作らない）。ハッシュ化しても平文は復元できないため、
-- 既存行は乱数から作ったハッシュで埋めて「どの平文でも照合に当たらない」状態にし、行は削除せず全件を失効させる。
--   - 行・ID・file_id・パスワード・利用回数・DL可否・発行者は残す（監査・参照元を壊さない）
-- MySQL は隣接文字列リテラルを連結しないため COMMENT は1リテラルで書く。

ALTER TABLE shared_file_links ADD COLUMN token_hash VARCHAR(64) NULL COMMENT '共有リンクトークンのSHA-256ハッシュ(hex)。平文は保存しない' AFTER file_id;

UPDATE shared_file_links
   SET token_hash = SHA2(CONCAT(UUID(), UUID(), RAND(), id), 256),
       is_active = FALSE;

ALTER TABLE shared_file_links DROP INDEX uk_file_links_token;
ALTER TABLE shared_file_links DROP COLUMN token;
ALTER TABLE shared_file_links MODIFY COLUMN token_hash VARCHAR(64) NOT NULL COMMENT '共有リンクトークンのSHA-256ハッシュ(hex)。平文は保存しない';
ALTER TABLE shared_file_links ADD CONSTRAINT uk_file_links_token_hash UNIQUE (token_hash);
