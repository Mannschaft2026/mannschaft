-- F02.5 行動メモ: 日ごとの公開記録（action_memo_daily_posts）
-- メモの日付を変えると action_memos.timeline_post_id を外す（移動先の日に旧日の投稿へのリンクが出ないようにするため）。
-- そのため「日Aの全メモを別日へ移し、Aへ戻して再公開」すると publish-daily が旧個人投稿を見つけられず、
-- 新旧2件の投稿が残っていた。日ごとの公開記録を (user_id, memo_date) で持ち、メモの日付変更・削除に影響されず旧投稿を追えるようにする。
-- クロスドメインFKは持たない（user_id / timeline_post_id は ID 参照・整合性はアプリ層で保証）。

CREATE TABLE action_memo_daily_posts (
    id               BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    user_id          BIGINT UNSIGNED NOT NULL COMMENT '所有ユーザー（users.id への ID 参照・FKなし）',
    memo_date        DATE            NOT NULL COMMENT '公開した行動日',
    timeline_post_id BIGINT UNSIGNED NOT NULL COMMENT 'その日の個人（PERSONAL）投稿の timeline_posts.id（ID 参照・FKなし）',
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_amdp_user_date (user_id, memo_date),
    KEY idx_amdp_timeline_post (timeline_post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F02.5 行動メモの日ごとの公開記録（1ユーザー1日につき生存する個人投稿を1件指す）';

-- 既存データの backfill: 次の条件をすべて満たす (user_id, memo_date) だけを入れる。判定できないものは入れない。
--   1. メモ（論理削除済みを含む）の timeline_post_id が指す投稿が生存している（deleted_at IS NULL）
--   2. 本人の PERSONAL 投稿である（scope_type = 'PERSONAL' かつ scope_id = user_id、投稿者 user_id も一致）
--   3. 投稿本文の日付見出し「## YYYY-MM-DD の行動ログ」（publish-daily の本文生成と同じ形式）がメモの memo_date と一致する
--      （旧実装は日付変更後も投稿 ID を保持していたため、別日へ移されたメモの現在の memo_date を公開日とみなさない）
-- 同じ (user_id, memo_date) に複数の候補があれば最大の投稿 ID を採る。
-- 注: 日付変更で投稿 ID が既に失われた過去分は復元できない（既知の制限）。
INSERT INTO action_memo_daily_posts (id, user_id, memo_date, timeline_post_id)
SELECT UNHEX(REPLACE(UUID(), '-', '')), c.user_id, c.memo_date, c.timeline_post_id
  FROM (
        SELECT am.user_id, am.memo_date, MAX(tp.id) AS timeline_post_id
          FROM action_memos am
          JOIN timeline_posts tp ON tp.id = am.timeline_post_id
         WHERE tp.deleted_at IS NULL
           AND tp.scope_type = 'PERSONAL'
           AND tp.scope_id = am.user_id
           AND tp.user_id = am.user_id
           AND tp.content LIKE CONCAT('## ', DATE_FORMAT(am.memo_date, '%Y-%m-%d'), ' の行動ログ%')
         GROUP BY am.user_id, am.memo_date
       ) c;
