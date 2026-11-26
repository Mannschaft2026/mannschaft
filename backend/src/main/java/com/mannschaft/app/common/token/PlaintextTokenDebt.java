package com.mannschaft.app.common.token;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 平文トークン保存の<b>移行期間だけ</b>の債務宣言（汎用。最初の利用は CMP-260826-1457）。
 *
 * <p>W0 で既存の違反（平文トークン列と、金庫を通さない発行メソッド）にすべて付け、
 * W1〜W2 の各波が自分の系統の注釈を外していく。W3 の完了条件は
 * 「{@code cmp = "CMP-260826-1457"} の債務がゼロであること」で、この型は削除せず汎用の仕組みとして残す
 * （認証系トークンの金庫統合 CMP-261103-2059 など、別戦役の債務にも使う）。</p>
 *
 * <p>招待・共有以外の系統（webhook・iCal・見守り・Google カレンダー連携など）の債務は、
 * 切り出し先の {@code cmp = "CMP-261125-0945"} が負う。両方の CMP ID が並んで使われ、
 * それぞれが台帳で未完了である間だけ許される。</p>
 *
 * <p>番人 {@code PlaintextSecretTokenGuardArchTest} は {@link #cmp()} が
 * {@code docs/task-list.md} に登録された CMP ID で、状態が完了でないもの以外のとき違反として扱う
 * （任意の文字列・未登録の ID・完了済みの戦役で番人を黙らせることを防ぐ）。</p>
 *
 * <p>判定は番人 {@code PlaintextSecretTokenGuardArchTest} が行う。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface PlaintextTokenDebt {

    /**
     * 債務を負っている戦役の ID。{@code docs/task-list.md} に登録済みで、状態が完了でないものだけを許す。
     *
     * @return 戦役 ID
     */
    String cmp();
}
