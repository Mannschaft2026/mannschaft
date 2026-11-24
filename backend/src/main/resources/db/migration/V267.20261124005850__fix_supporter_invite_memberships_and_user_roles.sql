-- CMP-261124-0157: SUPPORTER 招待で参加した人が正式メンバー扱いになっていた既存データを是正する。
--
-- 原因: 招待参加（InviteAcceptanceFacade -> MembershipGrantService#grantRole）が招待ロールに関係なく
-- memberships.role_kind = 'MEMBER' を作り、user_roles にも招待ロール（SUPPORTER）の行を作っていた。
-- 正しくは memberships.role_kind = 'SUPPORTER' のみで、user_roles の行は作らない
-- （F01.2 03_business_logic.md「招待参加フロー」手順8。user_roles の SUPPORTER 行は V60.010 で廃止済み）。
--
-- 是正対象の判定（他経路で正当に MEMBER になった人を巻き込まないため、次のすべてを満たす在籍だけ）:
--   1. memberships が在籍中（left_at IS NULL）で role_kind = 'MEMBER'
--   2. 同じ利用者・同じスコープ（TEAM は team_id、ORGANIZATION は organization_id）に user_roles の SUPPORTER 行がある
--      （V60.010 の後に SUPPORTER 行を書いたのは招待参加と RoleService#assignRole/changeRole だけ）
--   3. 同じスコープの SUPPORTER ロールの招待（invite_tokens）について、その利用者の確保記録
--      （invite_token_claims）が付与済み（status = 'GRANTED' または grant_committed_at が非 NULL）
--      ＝ その在籍は SUPPORTER 招待の承諾で作られたことが記録で裏付けられる
--   条件 3 があるので、管理者のロール割当だけで SUPPORTER 行を持つ人・MEMBER 招待で参加した人・
--   ADMIN/DEPUTY_ADMIN 等の権限ロール行を持つ人（user_roles は利用者×スコープで1行）は対象外になる。
--   確保記録の導入（V257）より前の共有招待による参加は、どの招待で参加したかの記録が無いため対象外とする
--   （本番に実データは無い）。
--
-- 冪等: 1回目で対象の在籍は SUPPORTER になり当該 user_roles 行は消えるので、再実行しても何も変わらない。
-- 監査用に変更前を残す必要がある場合は、適用前に次の SELECT で対象を控えること（本番実データなし）。
--   SELECT m.id, m.user_id, m.scope_type, m.scope_id, ur.id FROM memberships m JOIN user_roles ur ... （下の条件と同じ）

-- 1) 在籍を SUPPORTER に直す
UPDATE memberships m
    JOIN user_roles ur
        ON ur.user_id = m.user_id
        AND ((m.scope_type = 'TEAM' AND ur.team_id = m.scope_id AND ur.organization_id IS NULL)
          OR (m.scope_type = 'ORGANIZATION' AND ur.organization_id = m.scope_id AND ur.team_id IS NULL))
    JOIN roles r ON r.id = ur.role_id AND r.name = 'SUPPORTER'
SET m.role_kind = 'SUPPORTER',
    m.updated_at = UTC_TIMESTAMP()
WHERE m.left_at IS NULL
  AND m.role_kind = 'MEMBER'
  AND EXISTS (
      SELECT 1
      FROM invite_tokens t
          JOIN roles tr ON tr.id = t.role_id AND tr.name = 'SUPPORTER'
          JOIN invite_token_claims c ON c.token_id = t.id AND c.user_id = m.user_id
      WHERE ((m.scope_type = 'TEAM' AND t.team_id = m.scope_id)
          OR (m.scope_type = 'ORGANIZATION' AND t.organization_id = m.scope_id))
        AND (c.status = 'GRANTED' OR c.grant_committed_at IS NOT NULL)
  );

-- 2) 是正した在籍（SUPPORTER 招待の承諾が裏付けられた SUPPORTER 在籍）に残る user_roles の SUPPORTER 行を消す
DELETE ur
FROM user_roles ur
    JOIN roles r ON r.id = ur.role_id AND r.name = 'SUPPORTER'
    JOIN memberships m
        ON m.user_id = ur.user_id
        AND m.left_at IS NULL
        AND m.role_kind = 'SUPPORTER'
        AND ((m.scope_type = 'TEAM' AND ur.team_id = m.scope_id AND ur.organization_id IS NULL)
          OR (m.scope_type = 'ORGANIZATION' AND ur.organization_id = m.scope_id AND ur.team_id IS NULL))
WHERE EXISTS (
      SELECT 1
      FROM invite_tokens t
          JOIN roles tr ON tr.id = t.role_id AND tr.name = 'SUPPORTER'
          JOIN invite_token_claims c ON c.token_id = t.id AND c.user_id = ur.user_id
      WHERE ((m.scope_type = 'TEAM' AND t.team_id = m.scope_id)
          OR (m.scope_type = 'ORGANIZATION' AND t.organization_id = m.scope_id))
        AND (c.status = 'GRANTED' OR c.grant_committed_at IS NOT NULL)
  );
