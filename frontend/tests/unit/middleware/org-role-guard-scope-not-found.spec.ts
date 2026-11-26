import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { computed, ref, type Ref } from 'vue'
import type { RouteLocationNormalized } from 'vue-router'
import orgRoleGuard from '~/middleware/org-role-guard'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 実機 NG（AC-4 / AC-5）: 組織の管理者専用ページ（analytics・budget 等）の直リンク防御。
 *
 *   ORG-G1 権限情報が 404（不存在 slug）→ 全画面の汎用エラー（503 fatal）にせず、ルートを拒否して
 *          シェル内の ScopeNotFound に任せる（チーム側の不存在 slug と同じ見え方）
 *   ORG-G2 権限情報が 403 → 同上
 *   ORG-G3 正常応答で未所属（roleName=null）・MEMBER・DEPUTY_ADMIN → トースト・リダイレクトせず、ルートを拒否する
 *          （方針「非所属・権限なし→見つかりません」。トーストが所属・権限の有無を示唆しないように）
 *   ORG-G4 ADMIN → 通過し、同じパスの以前の拒否を解除する
 *   ORG-G5 取得失敗（5xx・通信断）→ 従来どおり 503 fatal（権限なしに誤倒ししない）
 */
const navigate = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigate(...args))

type LoadResult = { ok: true } | { ok: false, error: unknown }
let role: Ref<string | null>
let loadResult: LoadResult
mockNuxtImport('useRoleAccess', () => () => ({
  loadPermissions: async () => loadResult,
  isAdmin: computed(() => role.value === 'ADMIN' || role.value === 'SYSTEM_ADMIN'),
  isAdminOrDeputy: computed(() => ['ADMIN', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN'].includes(role.value ?? '')),
}))

const path = '/organizations/no-such-org-xyz/analytics'
function route(): RouteLocationNormalized {
  return {
    path,
    fullPath: path,
    hash: '',
    params: { slug: 'no-such-org-xyz' },
    query: {},
    name: undefined,
    matched: [],
    meta: {},
    redirectedFrom: undefined,
  }
}

let toastAdd: ReturnType<typeof vi.fn>
beforeEach(() => {
  navigate.mockReset()
  role = ref<string | null>(null)
  loadResult = { ok: true }
  useScopeNotFound().setRouteDenied(path, false)
  const nuxtApp = useNuxtApp()
  toastAdd = vi.fn()
  if (nuxtApp.$toast) (nuxtApp.$toast as { add: unknown }).add = toastAdd
  else nuxtApp.provide('toast', { add: toastAdd })
})

describe('org-role-guard: 非所属・権限なし・不存在は ScopeNotFound に寄せる（CMP-261125-1108）', () => {
  it.each([
    ['ORG-G1 404', { statusCode: 404, response: { status: 404 } }],
    ['ORG-G2 403', { statusCode: 403, response: { status: 403 } }],
  ])('%s: 全画面エラー・トースト・リダイレクトを出さずルートを拒否する', async (_label, error) => {
    loadResult = { ok: false, error }
    await expect(orgRoleGuard(route(), route())).resolves.toBeUndefined()
    expect(useScopeNotFound().isRouteDenied(path)).toBe(true)
    expect(navigate).not.toHaveBeenCalled()
    expect(toastAdd).not.toHaveBeenCalled()
  })

  it.each([null, 'MEMBER', 'DEPUTY_ADMIN', 'SUPPORTER'])('ORG-G3 %s: トースト・リダイレクトせずルートを拒否する', async (value) => {
    role.value = value
    await orgRoleGuard(route(), route())
    expect(useScopeNotFound().isRouteDenied(path)).toBe(true)
    expect(navigate).not.toHaveBeenCalled()
    expect(toastAdd).not.toHaveBeenCalled()
  })

  it('ORG-G4 ADMIN: 通過し、以前の拒否を解除する', async () => {
    useScopeNotFound().setRouteDenied(path, true)
    role.value = 'ADMIN'
    await orgRoleGuard(route(), route())
    expect(useScopeNotFound().isRouteDenied(path)).toBe(false)
    expect(navigate).not.toHaveBeenCalled()
    expect(toastAdd).not.toHaveBeenCalled()
  })

  it.each([
    ['500', { statusCode: 500, response: { status: 500 } }],
    ['通信断', new Error('fetch failed')],
  ])('ORG-G5 %s: 従来どおり 503 fatal で伝える（拒否にしない）', async (_label, error) => {
    loadResult = { ok: false, error }
    await expect(orgRoleGuard(route(), route())).rejects.toMatchObject({ statusCode: 503, fatal: true })
    expect(useScopeNotFound().isRouteDenied(path)).toBe(false)
  })
})
