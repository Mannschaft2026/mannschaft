-- F02.5 行動メモ: チームタイムライン投稿IDを timeline_post_id から分離する
-- timeline_post_id は PERSONAL 投稿専用（F02.5 設計書どおり）に戻し、
-- publish-to-team / publish-daily-to-team が作るチーム投稿の ID は team_timeline_post_id に保持する。
-- これにより publish-daily がチーム投稿を旧個人投稿と誤認して論理削除する不具合を根治する。

ALTER TABLE action_memos
  ADD COLUMN team_timeline_post_id BIGINT UNSIGNED NULL DEFAULT NULL
    COMMENT 'チームタイムラインに投稿したチーム投稿のID（timeline_posts.id への ID 参照・FKなし）。NULL = 未投稿' AFTER posted_team_id;

-- 既存行の移行: timeline_post_id が TEAM 投稿を指している行は team_timeline_post_id へ移し、timeline_post_id を NULL にする
UPDATE action_memos am
  JOIN timeline_posts tp ON tp.id = am.timeline_post_id
   SET am.team_timeline_post_id = am.timeline_post_id,
       am.timeline_post_id = NULL
 WHERE tp.scope_type = 'TEAM';
