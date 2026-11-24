-- F09.3: MANAGE_PARKING パーミッションをカタログへ登録し ADMIN へ自動付与する（CMP-260930-0233 マスター裁定 2026-11-24）
--
-- 背景:
--   駐車場申請の「全件閲覧・承認・却下・抽選」を ADMIN 以外（DEPUTY_ADMIN）へ委譲できるようにする。
--   V183（MANAGE_RECRUITMENTS）と同型で、ADMIN は自動付与・DEPUTY_ADMIN は権限グループ経由の手動付与とする。
--   permissions.name は UNIQUE（uq_permissions_name）。scope 列は権限一覧 UI 向けの分類で、
--   認可判定（AccessControlService.checkAdminOrHasPermissionInScope）では参照されない。
--   駐車場はチーム・組織の双方で使うが、1 値しか持てないため 'TEAM' を採る（V183 と同じ扱い）。

-- 1. permissions カタログへ登録（再実行安全）
INSERT INTO permissions (name, display_name, scope, created_at, updated_at)
SELECT 'MANAGE_PARKING', '駐車場管理', 'TEAM', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_PARKING');

-- 2. ADMIN へ is_default=1 で自動付与
INSERT INTO role_permissions (role_id, permission_id, is_default, created_at)
SELECT r.id, p.id, 1, NOW()
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'ADMIN'
  AND p.name = 'MANAGE_PARKING'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- 3. DEPUTY_ADMIN には role_permissions 行を作らない（V183 §3 と同じ理由。
--    チームスコープの判定は is_default で絞らず role_permissions を集約するため、天井行を置くと
--    個別付与なしの DEPUTY_ADMIN 全員が黙って管理権限を得てしまう）。付与は権限グループ経由に一本化する。
