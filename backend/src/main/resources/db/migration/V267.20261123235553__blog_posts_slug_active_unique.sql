-- ⚠ 一意制約の変更（台帳 CMP-261123-2341）: ブログ記事 slug の重複を既存データごと是正し、
-- deleted_at が NULL の行が MySQL の一意制約をすり抜ける穴を塞ぐ。
--
-- 旧制約 UNIQUE (team_id|organization_id|user_id, slug, deleted_at) は、生存行（deleted_at IS NULL）どうしでは
-- NULL != NULL のため重複を拒否せず、同一スコープに同じ slug が複数共存できた（slug 詳細取得が 500 になる原因）。
-- 他テーブル（budget_fiscal_years 等 V249）の前例に倣い、生存行だけ 1 になる生成列を一意キーへ加える。
-- スコープ列（team_id / organization_id / user_id）は CHECK で排他なので、列ごとに 1 本ずつ張る
-- （該当しない側は NULL となり制約に掛からない）。

-- 1. 既存の重複是正: 生存行（deleted_at IS NULL）の同一スコープ・同一 slug のうち、
--    PUBLISHED を優先し、同順位なら id が小さい（先に作られた）行をそのまま残す。
--    残りは slug の末尾に連番（-2, -3 ...）を付ける。公開済みの記事は URL・お知らせの元ページリンクが
--    その slug を指している可能性が高いため、変更しない。
UPDATE blog_posts p
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY team_id, organization_id, user_id, slug
               ORDER BY (status = 'PUBLISHED') DESC, id ASC
           ) AS rn
    FROM blog_posts
    WHERE deleted_at IS NULL
) r ON r.id = p.id
LEFT JOIN (
    SELECT team_id, organization_id, user_id, slug
    FROM blog_posts
    WHERE deleted_at IS NULL
) e ON e.slug = CONCAT(LEFT(p.slug, 190), '-', r.rn)
   AND e.team_id <=> p.team_id AND e.organization_id <=> p.organization_id AND e.user_id <=> p.user_id
SET p.slug = IF(e.slug IS NULL,
                CONCAT(LEFT(p.slug, 190), '-', r.rn),
                CONCAT(LEFT(p.slug, 170), '-dup-', p.id))
WHERE r.rn > 1;

-- 1 の連番候補が既存の別 slug（例: 元から "114-2" がある）と衝突する行は、連番でなく -dup-{id} にして既存行を動かさない。
-- 2. それでも重複が残る場合（是正後どうしの衝突）の保険。
--    まだ重複している行のうち残す側（同じ順位規則）以外へ、id を足して必ず一意にする。
UPDATE blog_posts p
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY team_id, organization_id, user_id, slug
               ORDER BY (status = 'PUBLISHED') DESC, id ASC
           ) AS rn
    FROM blog_posts
    WHERE deleted_at IS NULL
) r ON r.id = p.id
SET p.slug = CONCAT(LEFT(p.slug, 170), '-dup-', p.id)
WHERE r.rn > 1;

-- 3. 生存行だけ 1 になる生成列を一意キーへ加え、旧制約と置き換える。
ALTER TABLE blog_posts
    ADD COLUMN active_unique_key TINYINT
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL,
    DROP INDEX uq_bp_slug_team,
    DROP INDEX uq_bp_slug_org,
    DROP INDEX uq_bp_slug_user,
    ADD CONSTRAINT uq_bp_slug_team_active UNIQUE (team_id, slug, active_unique_key),
    ADD CONSTRAINT uq_bp_slug_org_active UNIQUE (organization_id, slug, active_unique_key),
    ADD CONSTRAINT uq_bp_slug_user_active UNIQUE (user_id, slug, active_unique_key);
