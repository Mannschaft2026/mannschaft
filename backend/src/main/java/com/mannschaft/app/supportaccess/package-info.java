/**
 * F10.9 本人承認制のサポート閲覧（代理ログイン）ドメイン（戦役 Z-B）。
 *
 * <p>試練段階のスタブ。運営専用アカウントが本人の再認証つき許可（24時間・閲覧のみ）を得て、
 * {@code X-Support-Access-Grant-Id} ヘッダーで本人の視点を閲覧する。旧 {@code X-Admin-Impersonate-User-Id}
 * （権限を引き継ぐ変身）は本ドメインで置き換える。</p>
 *
 * <p>スタブのクラスには Spring のステレオタイプ注釈を付けていない（未実装の port に依存する Bean が
 * 既存の全 ApplicationContext を壊さないため）。実装役が注釈と port の実装を加える。</p>
 */
package com.mannschaft.app.supportaccess;
