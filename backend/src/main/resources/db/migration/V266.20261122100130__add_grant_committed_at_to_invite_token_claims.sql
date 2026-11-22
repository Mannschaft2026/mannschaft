-- 招待 claim に「この claim による付与がコミット済み」の印を足す（CMP-261107-1833・Codex 検分 P1/P2 の根治）。
-- 新規登録の保留承認では、5 分を超えて取り直した attempt が同じ claim を再開するため、1つの claim を複数の attempt が
-- 共有しうる。共有中の claim を戻す（RELEASED・使用回数-1）か確定する（GRANTED）かを、在籍の有無からの推測ではなく
-- この印で決める。印はロール付与（T7）と同じトランザクションで、claim 行をロックしてから立てる
-- （付与がロールバックされれば印も残らない）。印のある claim は戻さない。印の無い claim は付与に使われていない。
-- 既存の GRANTED 行は付与が確定しているので印を補う（CLAIMED 行は付与の成否が分からないので補わない）。
ALTER TABLE invite_token_claims
    ADD COLUMN grant_committed_at DATETIME(6) NULL COMMENT 'この claim によるロール付与がコミットされた時刻（UTC）。NULL は未付与' AFTER status;

UPDATE invite_token_claims SET grant_committed_at = updated_at WHERE status = 'GRANTED';

ALTER TABLE invite_token_claims
    ADD CONSTRAINT chk_itc_grant_committed CHECK (grant_committed_at IS NULL OR status <> 'RELEASED');
