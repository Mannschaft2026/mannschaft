-- 招待 claim に「この claim による付与がコミット済み」の印を足す（CMP-261107-1833・Codex 検分 P1/P2 の根治）。
-- 新規登録の保留承認では、5 分を超えて取り直した attempt が同じ claim を再開するため、1つの claim を複数の attempt が
-- 共有しうる。共有中の claim を戻す（RELEASED・使用回数-1）か確定する（GRANTED）かを、在籍の有無からの推測ではなく
-- この印で決める。印はロール付与（T7）と同じトランザクションで、claim 行をロックしてから立てる
-- （付与がロールバックされれば印も残らない）。印のある claim は戻さない。印の無い claim は付与に使われていない。
--
-- ただしこの migration より前に作られた CLAIMED 行は、旧版の T7 が印を立てずに付与をコミットした後で止まった
-- 可能性があり、「未付与」とは言えない（付与されたか不明）。在籍の有無で印を補うと別経路の参加と区別できないため、
-- 旧データの印 legacy_grant_unknown_at を付けて区別し、自動では戻さない（使用回数を戻さない安全側。招待の再利用を
-- 許さない）。在籍済みでの片付けでは確定し、在籍していなければ再承認の付与で付与印が立つ。
-- 既存の GRANTED 行は付与が確定しているので付与印を補う。
ALTER TABLE invite_token_claims
    ADD COLUMN grant_committed_at DATETIME(6) NULL COMMENT 'この claim によるロール付与がコミットされた時刻（UTC）。NULL は未付与' AFTER status,
    ADD COLUMN legacy_grant_unknown_at DATETIME(6) NULL COMMENT '付与印の導入前から CLAIMED だった旧データの印（UTC）。付与されたか不明なので自動では戻さない' AFTER grant_committed_at;

UPDATE invite_token_claims SET grant_committed_at = updated_at WHERE status = 'GRANTED';

UPDATE invite_token_claims SET legacy_grant_unknown_at = UTC_TIMESTAMP(6) WHERE status = 'CLAIMED';

ALTER TABLE invite_token_claims
    ADD CONSTRAINT chk_itc_grant_committed CHECK (grant_committed_at IS NULL OR status <> 'RELEASED');
