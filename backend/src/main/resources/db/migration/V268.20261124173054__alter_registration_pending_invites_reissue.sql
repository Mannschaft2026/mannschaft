-- 再招待依頼の受け手側（CMP-261107-1833 PR3）。
-- 招待権限者が再招待を見送った状態 REISSUE_DECLINED を status の CHECK に加え、
-- 再招待依頼の一覧・件数（status=REISSUE_REQUESTED・スコープ一致・古い順）用の索引を張る。テーブルは増やさない。
ALTER TABLE registration_pending_invites DROP CHECK chk_registration_pending_invites_status;

ALTER TABLE registration_pending_invites
    ADD CONSTRAINT chk_registration_pending_invites_status CHECK (status IN ('PENDING', 'ACCEPTING', 'JOINED',
        'EXPIRED', 'UNAVAILABLE', 'DECLINED', 'REISSUE_REQUESTED', 'REISSUE_DECLINED', 'DISMISSED', 'DISCARDED'));

CREATE INDEX idx_rpi_scope_status
    ON registration_pending_invites (scope_type, scope_id, status, reissue_requested_at);
