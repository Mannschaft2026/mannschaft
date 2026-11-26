-- 運営アカウント（SYSTEM_ADMIN）のテナント所属の点検 SQL（戦役 Z-A PR2 / 受け入れ条件 P7）。
-- 正本: .claude/campaigns/z-a-design.md §2「点検クエリ」に、受諾待ちの招待・参加申請・移管申し出・サポーター申請を加えたもの。
-- 1 行でも返れば運営アカウントがテナントに所属している（または所属待ちが残っている）。結果は 0 行でなければならない。
-- 本番では家老も足軽も実行しない（殿が本番前に実行する）。列: kind, user_id, scope_type, scope_id, detail
WITH sa AS (
  SELECT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id
  WHERE r.name = 'SYSTEM_ADMIN' AND ur.team_id IS NULL AND ur.organization_id IS NULL
)
SELECT 'memberships' AS kind, m.user_id AS user_id, m.scope_type AS scope_type,
       CAST(m.scope_id AS CHAR) AS scope_id, m.role_kind AS detail
  FROM memberships m JOIN sa ON sa.user_id = m.user_id WHERE m.left_at IS NULL
UNION ALL
SELECT 'user_roles', ur.user_id, IF(ur.team_id IS NULL, 'ORGANIZATION', 'TEAM'),
       CAST(COALESCE(ur.team_id, ur.organization_id) AS CHAR), r.name
  FROM user_roles ur JOIN roles r ON r.id = ur.role_id JOIN sa ON sa.user_id = ur.user_id
  WHERE ur.team_id IS NOT NULL OR ur.organization_id IS NOT NULL
UNION ALL
SELECT 'committee_members', cm.user_id, 'COMMITTEE', CAST(cm.committee_id AS CHAR), cm.role
  FROM committee_members cm JOIN sa ON sa.user_id = cm.user_id WHERE cm.left_at IS NULL
UNION ALL
SELECT 'village_memberships', vm.subject_id, 'VILLAGE', HEX(vm.village_id), vm.role
  FROM village_memberships vm JOIN sa ON sa.user_id = vm.subject_id
  WHERE vm.subject_type = 'USER' AND vm.left_at IS NULL
    -- 唯一の例外（マスター決定 2026-11-26）: 公式村（OFFICIAL）の作成者としての村長所属は 0 件判定から除く。
    AND NOT EXISTS (
      SELECT 1 FROM villages v
      WHERE v.id = vm.village_id AND v.type = 'OFFICIAL'
        AND v.created_by_user_id = vm.subject_id AND vm.role = 'HEADMAN'
    )
UNION ALL
SELECT 'supporter_applications', s.user_id, s.scope_type, CAST(s.scope_id AS CHAR), s.status
  FROM supporter_applications s JOIN sa ON sa.user_id = s.user_id WHERE s.status = 'PENDING'
UNION ALL
SELECT 'join_requests', j.requester_user_id, IF(j.team_id IS NULL, 'ORGANIZATION', 'TEAM'),
       CAST(COALESCE(j.team_id, j.organization_id) AS CHAR), j.status
  FROM join_requests j JOIN sa ON sa.user_id = j.requester_user_id WHERE j.status = 'PENDING'
UNION ALL
SELECT 'invite_tokens', t.target_user_id, IF(t.team_id IS NULL, 'ORGANIZATION', 'TEAM'),
       CAST(COALESCE(t.team_id, t.organization_id) AS CHAR), 'TARGETED_INVITE'
  FROM invite_tokens t JOIN sa ON sa.user_id = t.target_user_id
  WHERE t.revoked_at IS NULL AND t.expires_at > NOW() AND t.used_count < t.max_uses
UNION ALL
SELECT 'ownership_transfer_offers', o.target_user_id, IF(o.team_id IS NULL, 'ORGANIZATION', 'TEAM'),
       CAST(COALESCE(o.team_id, o.organization_id) AS CHAR), o.status
  FROM ownership_transfer_offers o JOIN sa ON sa.user_id = o.target_user_id WHERE o.status = 'PENDING'
UNION ALL
SELECT 'committee_invitations', ci.invitee_user_id, 'COMMITTEE', CAST(ci.committee_id AS CHAR), 'PENDING'
  FROM committee_invitations ci JOIN sa ON sa.user_id = ci.invitee_user_id WHERE ci.resolved_at IS NULL
UNION ALL
SELECT 'village_invitations', vi.target_user_id, 'VILLAGE', HEX(vi.village_id), 'TARGETED_INVITE'
  FROM village_invitations vi JOIN sa ON sa.user_id = vi.target_user_id
  WHERE vi.revoked_at IS NULL AND vi.used_count < vi.max_uses
UNION ALL
SELECT 'village_join_requests', vj.subject_id, 'VILLAGE', HEX(vj.village_id), vj.status
  FROM village_join_requests vj JOIN sa ON sa.user_id = vj.subject_id
  WHERE vj.subject_type = 'USER' AND vj.status = 'PENDING'
UNION ALL
SELECT 'follows', f.follower_id, 'FOLLOW', CAST(f.followed_id AS CHAR), 'PERSONAL_FOLLOW'
  FROM follows f JOIN sa ON sa.user_id = f.follower_id WHERE f.follower_type = 'USER'
