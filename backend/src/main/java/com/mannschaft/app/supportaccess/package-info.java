/**
 * F10.9 本人承認制のサポート閲覧（代理ログイン）ドメイン（戦役 Z-B）。
 *
 * <p>運営専用アカウントが本人の再認証つき許可（24時間・閲覧のみ）を得て、
 * {@code X-Support-Access-Grant-Id} ヘッダーで本人の視点を閲覧する。旧・管理者変身ヘッダー
 * （権限を引き継ぐ変身）は本ドメインで置き換える。</p>
 *
 * <p>port（利用者・再認証は auth、所属・通知は supportaccess/adapter）の実装を各ドメインが持つ。
 * 代理ヘッダーの解釈（Filter・読取専用・Interceptor）は第2隊の範囲。</p>
 */
package com.mannschaft.app.supportaccess;
