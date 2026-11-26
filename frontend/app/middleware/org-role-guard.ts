import { isScopeNotFound } from '~/utils/loadError'

/**
 * 組織詳細配下の管理者専用ページ（永続シェルのタブ対象外・budget/analytics/audit-logs 等）向け
 * 直リンク防御ミドルウェア（CMP-260917-1351 課題B）。
 *
 * 実証済みの欠陥: MEMBER が URL 直打ちでこれらのページを開くと、BE は 403 を返すのに
 * 画面はそのまま表示され空・エラー状態になる（二重防御が invites/permission-groups/
 * admin-console の3件にしか無かった）。
 *
 * 方針（CMP-261125-1108 で改訂。FRONTEND_CODING_CONVENTION §12「スコープ子ページの 403/404 は
 * 『見つかりません』に寄せる」）:
 *  - 非所属（roleName=null）・権限なし（ADMIN 以外）・権限情報の 403/404（非所属の PRIVATE・不存在 slug）は、
 *    トースト・リダイレクト・全画面エラーを出さず、ルートを拒否する（`setRouteDenied`）。組織シェルが
 *    子ページを描画せず本文を共通の ScopeNotFound に置き換える（タブ・戻る導線は残る）。
 *    トーストや組織トップへの誘導は「所属している／存在する」ことを示唆するため出さない
 *  - 取得失敗（5xx・通信断）は 503 でフルページエラーに落とす（権限なしに誤倒さず、症状を隠さない）
 *  - ADMIN は通過し、同じパスの以前の拒否を解除する
 *
 * 対象ページは全て ADMIN 専用（OrganizationSidebar.vue の該当項目 requiredRole:'ADMIN' と揃える。
 * 翻訳ページ（translations.vue）は CMP-260917-1351 課題C で MEMBER 可に変更されたため対象外）。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  // ロール取得は client の認証トークンに依存するため SSR ではスキップ（admin-console.ts と同じ作法）。
  if (import.meta.server) return

  const slug = String(to.params.slug ?? '')
  if (!slug) return

  const nuxtApp = useNuxtApp()
  const t = (key: string): string => nuxtApp.$i18n.t(key)
  const { setRouteDenied } = useScopeNotFound()

  const access = useRoleAccess('organization', slug)
  const result = await access.loadPermissions()

  if (!result.ok) {
    // 403/404（非所属の PRIVATE・不存在 slug）は「見つかりません」。全画面エラーにしない
    if (isScopeNotFound(result.error)) {
      setRouteDenied(to.path, true)
      return
    }
    throw createError({
      statusCode: 503,
      statusMessage: t('adminConsole.error.fetchFailedTitle'),
      data: { body: t('adminConsole.error.fetchFailedBody') },
      fatal: true,
    })
  }

  // 非所属・ADMIN 以外は「見つかりません」（トースト・リダイレクトなし）
  setRouteDenied(to.path, !access.isAdmin.value)
})
