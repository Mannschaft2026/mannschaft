-- CMP-260924-0012: direct_mail_logs_archive に sender_type を追加する（本体 direct_mail_logs.sender_type の欠落返済）。
--
-- 本体は V67.021 で sender_type VARCHAR(20) NOT NULL DEFAULT 'USER' を追加済みだが、archive（V9.014）には列が無く、
-- 設計書 F09.6 の「本体と同一スキーマ」に反していた。
-- 仕様（マスター確定）: archive.sender_type は NOT NULL。既存の過去履歴は送信者種類を判別できないため 'UNKNOWN' とし、
-- 'USER' への推測埋めはしない。新規の保存では送信者種類を必須とするため、DEFAULT は列追加の直後に外す。
--
-- 手順:
--  1. 列が既に在る場合は、想定外の型・NULL 可否・DEFAULT・生成列でないかを確かめ、外れていれば CHECK 違反で失敗させる
--     （connection-local の TEMPORARY TABLE を使い、使い終えたら直ちに DROP する。永続 routine は作らない）。
--  2. 列が無いときだけ ADD COLUMN（MySQL 8.0 は ADD COLUMN IF NOT EXISTS 非対応のため PREPARE/EXECUTE。手本は V230 の 3 本）。
--  3. DEFAULT を外す（既に外れていても同じ結果になる冪等な操作）。
-- UPDATE は使わない。既存行は列追加時の DEFAULT 'UNKNOWN' で埋まる。
-- 比較は BINARY で大文字小文字を厳密に区別する。文字列リテラル内に セミコロン を書かないこと。

CREATE TEMPORARY TABLE cmp0012_sender_type_guard (
    ok TINYINT NOT NULL,
    CONSTRAINT chk_cmp0012_sender_type_guard CHECK (ok = 1)
);

INSERT INTO cmp0012_sender_type_guard (ok)
SELECT CASE
           WHEN COUNT(*) = 0 THEN 1
           WHEN SUM(BINARY DATA_TYPE = BINARY 'varchar'
                    AND CHARACTER_MAXIMUM_LENGTH = 20
                    AND BINARY IS_NULLABLE = BINARY 'NO'
                    AND (COLUMN_DEFAULT IS NULL OR BINARY COLUMN_DEFAULT = BINARY 'UNKNOWN')
                    AND EXTRA = ''
                    AND GENERATION_EXPRESSION = '') = COUNT(*) THEN 1
           ELSE 0
       END
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'direct_mail_logs_archive' AND COLUMN_NAME = 'sender_type';

DROP TEMPORARY TABLE cmp0012_sender_type_guard;

SET @c1 = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'direct_mail_logs_archive' AND COLUMN_NAME = 'sender_type');
SET @s1 = IF(@c1 = 0, 'ALTER TABLE direct_mail_logs_archive ADD COLUMN sender_type VARCHAR(20) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''送信者種類（USER / SYSTEM_AD 等。判別できない過去の履歴は UNKNOWN）'' AFTER sender_id', 'SELECT 1');
PREPARE stmt FROM @s1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE direct_mail_logs_archive ALTER COLUMN sender_type DROP DEFAULT;
