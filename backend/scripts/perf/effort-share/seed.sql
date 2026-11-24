-- F06.4.1 PR-3b AC-60 負荷実測の投入（CMP-261115-2145）。CI では流さない。手順は run.sh の冒頭。
--
-- 前提: ローカル Docker の MySQL 8.0（mannschaft-mysql）に専用スキーマを作り、Flyway で本番と同じ migration を適用済み。
-- 投入は本番 DDL のまま（UUID・照合順序・index を変えない）。owner 10万記録（1万人×10件）と team 100万 projection（1000チーム×1000件）。
-- 実測に使う本人・チーム（JWT を発行できる実在の利用者と、その人が所属するチーム）を @probe_owner・@probe_team で渡す。
--   mysql -h 127.0.0.1 -u root -p <schema> -e "SET @probe_owner=..., @probe_team=..., @owner_base=900000000, @team_base=900000000; SOURCE seed.sql;"
-- 合成の owner / team ID は @owner_base / @team_base 以上に置き、既存の利用者データと重ねない。
--
-- 後始末の範囲: 本スクリプトが投入した行の主キーを、実測専用の記録表 perf_effort_seed_ids に先に採番して残し、
-- その ID で投入する。cleanup.sql はこの記録表の ID と一致する行だけを消す（業務値・ID 範囲では消さない。
-- 実測本人・実測チームの既存の記録や共有には触れない）。記録表は CREATE TABLE（IF NOT EXISTS なし）で作るため、
-- 前回の投入を cleanup.sql で片付けないまま再投入するとここで失敗して止まる（二重投入・記録漏れを起こさない）。

SET SESSION cte_max_recursion_depth = 1000000;

CREATE TABLE perf_effort_seed_ids (
    table_name VARCHAR(32) NOT NULL,
    n          INT         NOT NULL,
    id         BINARY(16)  NOT NULL,
    PRIMARY KEY (table_name, n),
    UNIQUE KEY uk_perf_effort_seed_ids_id (table_name, id)
);

-- 標準プリセット STUDY の revision 1（migration seed）を原本の snapshot に使う
SET @study = UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001', '-', ''));
SET @study_json = (SELECT definition_json FROM effort_definition_revisions WHERE definition_id = @study AND revision = 1);

-- 投入する行の ID を先に採番して記録表へ残す（cleanup.sql はこの ID だけを消す）
INSERT INTO perf_effort_seed_ids (table_name, n, id)
WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < 99999)
SELECT 'effort_records', n, UUID_TO_BIN(UUID()) FROM seq;

INSERT INTO perf_effort_seed_ids (table_name, n, id)
WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < 999999)
SELECT 'effort_team_shares', n, UUID_TO_BIN(UUID()) FROM seq;

-- owner 10万記録: 合成 owner 9,999人 × 10件 + 実測本人 10件（366日に散らす）
INSERT INTO effort_records (id, owner_user_id, definition_id, definition_revision, definition_snapshot, recorded_on,
                            record_timezone, values_json, private_note, version, content_revision, share_version,
                            created_at, updated_at)
SELECT p.id,
       IF(p.n < 10, @probe_owner, @owner_base + FLOOR(p.n / 10)),
       @study, 1, @study_json,
       DATE_SUB('2026-11-30', INTERVAL (p.n * 37) MOD 366 DAY),
       'Asia/Tokyo',
       JSON_OBJECT('subject', 'math', 'duration', CAST((p.n MOD 120) + 1 AS CHAR)),
       NULL, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM perf_effort_seed_ids p
WHERE p.table_name = 'effort_records';

-- team 100万 projection: 999チーム × 1000件 + 実測チーム 1000件（全件 VISIBLE。F00 の owner 照合は大半が不一致＝最悪寄り）
INSERT INTO effort_team_shares (id, team_id, owner_user_id, source_record_id, source_version, grant_membership_id,
                                share_version, definition_id, recorded_on, projection_json, projection_hash, state,
                                version, created_at, updated_at)
SELECT p.id,
       IF(p.n < 1000, @probe_team, @team_base + FLOOR(p.n / 1000)),
       @owner_base + (p.n MOD 9999),
       UUID_TO_BIN(UUID()), 1, 1, 1, @study,
       DATE_SUB('2026-11-30', INTERVAL (p.n * 13) MOD 366 DAY),
       JSON_OBJECT('fields', JSON_ARRAY()), REPEAT('0', 64), 'VISIBLE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM perf_effort_seed_ids p
WHERE p.table_name = 'effort_team_shares';

ANALYZE TABLE effort_records, effort_team_shares;

SELECT 'effort_records' AS t, COUNT(*) FROM effort_records r
    JOIN perf_effort_seed_ids p ON p.table_name = 'effort_records' AND p.id = r.id
UNION ALL
SELECT 'effort_team_shares', COUNT(*) FROM effort_team_shares s
    JOIN perf_effort_seed_ids p ON p.table_name = 'effort_team_shares' AND p.id = s.id;
