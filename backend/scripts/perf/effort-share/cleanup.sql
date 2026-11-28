-- F06.4.1 PR-3b AC-60 負荷実測の後始末（seed.sql で入れた行だけ）。CMP-261115-2145
--   mysql -h 127.0.0.1 -u root -p <schema> -e "SOURCE cleanup.sql;"
-- seed.sql が投入前に採番して記録表 perf_effort_seed_ids に残した ID と一致する行だけを物理削除する。
-- 業務値（科目・メモの有無・projection hash）や ID の範囲では消さない（実測本人・実測チームの既存の記録や
-- 共有、範囲内に偶然ある既存行を巻き込まない）。記録表が無い（seed.sql 未実行・片付け済み）なら最初の DELETE で失敗して止まる。

DELETE s FROM effort_team_shares s
    JOIN perf_effort_seed_ids p ON p.table_name = 'effort_team_shares' AND p.id = s.id;
DELETE r FROM effort_records r
    JOIN perf_effort_seed_ids p ON p.table_name = 'effort_records' AND p.id = r.id;

-- 投入行が残っていないことを確かめてから記録表を消す（0 でなければ DROP の前に止めて調べる）
SELECT 'effort_records' AS t, COUNT(*) AS remaining FROM effort_records r
    JOIN perf_effort_seed_ids p ON p.table_name = 'effort_records' AND p.id = r.id
UNION ALL
SELECT 'effort_team_shares', COUNT(*) FROM effort_team_shares s
    JOIN perf_effort_seed_ids p ON p.table_name = 'effort_team_shares' AND p.id = s.id;

DROP TABLE perf_effort_seed_ids;
