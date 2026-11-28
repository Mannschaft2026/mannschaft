-- CMP-261125-2114: シフト管理は ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけに許可する。
-- MANAGE_SHIFTS を持たない DEPUTY_ADMIN にシフトの「閲覧」だけを許す切替を 2 つ追加する（既定 OFF = 一般メンバーと同じ）。
--   deputy_shift_view_enabled        : 未公開シフト表・割当の非マスク・他人の交代/変更依頼・希望一覧/サマリー・PDF 管理者出力・
--                                      ダッシュボード要対応のシフト希望の閲覧
--   deputy_hourly_rate_view_enabled  : 他メンバーの時給の閲覧
-- 操作（作成・更新・削除・承認など）は切替に関わらず常に MANAGE_SHIFTS 必須。
ALTER TABLE team_shift_settings
    ADD COLUMN deputy_shift_view_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER reminder_12h_enabled,
    ADD COLUMN deputy_hourly_rate_view_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER deputy_shift_view_enabled;
