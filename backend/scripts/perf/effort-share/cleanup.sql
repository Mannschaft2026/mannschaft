-- F06.4.1 PR-3b AC-60 負荷実測の後始末（seed.sql で入れた行だけ）。CMP-261115-2145
-- mysql ... -e "SET @probe_owner=..., @probe_team=..., @owner_base=900000000, @team_base=900000000; SOURCE cleanup.sql;"
DELETE FROM effort_team_shares WHERE team_id >= @team_base OR (team_id = @probe_team AND projection_hash = REPEAT('0', 64));
DELETE FROM effort_records WHERE owner_user_id >= @owner_base
   OR (owner_user_id = @probe_owner AND private_note IS NULL AND JSON_EXTRACT(values_json, '$.subject') = 'math');
