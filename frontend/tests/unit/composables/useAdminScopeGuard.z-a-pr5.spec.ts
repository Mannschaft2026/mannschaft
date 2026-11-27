import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent, h, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

/**
 * 戦役 Z-A PR5: useAdminScopeGuard（/admin/* 横断ルートの直リンク防御）は実ロールだけで判定する。
 *
 * ROLE_RANK から SYSTEM_ADMIN を外す（設計書 z-a-design.md §4）。/me/teams・/me/organizations は
 * PR5 以後 "SYSTEM_ADMIN" を返さないので、旧応答の "SYSTEM_ADMIN" は未知のロール＝権限なし（fail closed）。
 * 運営アカウントはテナントに所属しないため、スコープ管理画面のガードは通らない（URL 直打ちでもダッシュボードへ戻す）。
 *
 *   ZA5-GD-01: role="SYSTEM_ADMIN"（旧応答）は ADMIN / DEPUTY_ADMIN 要求のどちらでも拒否
 *   ZA5-GD-02: 所属の無い運営アカウント（所属一覧が空）は所属一覧を取り直したうえで拒否
 *   ZA5-GD-03（回帰）: ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER の可否は従来どおり
 *   ZA5-GD-04: ROLE_RANK に SYSTEM_ADMIN の段が無い（ソース検査）
 */

const state = vi.hoisted(() => ({
  myTeams: [] as Array<{ id: number; role: string }>,
  myOrganizations: [] as Array<{ id: number; role: string }>,
  fetchMyTeams: vi.fn(),
  fetchMyOrganizations: vi.fn(),
  navigateTo: vi.fn(),
}))

mockNuxtImport('useTeamStore', () => () => ({
  get myTeams() { return state.myTeams },
  fetchMyTeams: state.fetchMyTeams,
}))
mockNuxtImport('useOrganizationStore', () => () => ({
  get myOrganizations() { return state.myOrganizations },
  fetchMyOrganizations: state.fetchMyOrganizations,
}))
mockNuxtImport('navigateTo', () => (...args: unknown[]) => state.navigateTo(...args))

async function runGuard(scopeType: 'team' | 'organization', minRole: 'ADMIN' | 'DEPUTY_ADMIN') {
  const Host = defineComponent({
    setup() {
      useAdminScopeGuard(minRole, { scopeType: ref(scopeType), scopeId: ref('10') })
      return () => h('div')
    },
  })
  await mountSuspended(Host)
  await flushPromises()
}

const denied = () => state.navigateTo.mock.calls.some(([to]) => to === '/dashboard')

describe('useAdminScopeGuard — 戦役Z-A PR5 実ロールのみで判定', () => {
  beforeEach(() => {
    state.myTeams = []
    state.myOrganizations = []
    state.fetchMyTeams.mockReset().mockResolvedValue(undefined)
    state.fetchMyOrganizations.mockReset().mockResolvedValue(undefined)
    state.navigateTo.mockReset()
  })

  for (const scopeType of ['team', 'organization'] as const) {
    const seed = (role: string) => {
      if (scopeType === 'team') state.myTeams = [{ id: 10, role }]
      else state.myOrganizations = [{ id: 10, role }]
    }

    it.each(['ADMIN', 'DEPUTY_ADMIN'] as const)(
      `ZA5-GD-01: ${scopeType} の role="SYSTEM_ADMIN"（旧応答）は %s 要求のガードを通さない`,
      async (minRole) => {
        seed('SYSTEM_ADMIN')
        await runGuard(scopeType, minRole)
        expect(denied()).toBe(true)
      },
    )

    it(`ZA5-GD-02: 所属の無い運営アカウントは ${scopeType} の所属一覧を取り直したうえで拒否する`, async () => {
      await runGuard(scopeType, 'DEPUTY_ADMIN')
      const fetch = scopeType === 'team' ? state.fetchMyTeams : state.fetchMyOrganizations
      expect(fetch).toHaveBeenCalled()
      expect(denied()).toBe(true)
    })

    it.each([
      ['ADMIN', 'ADMIN', true],
      ['ADMIN', 'DEPUTY_ADMIN', true],
      ['DEPUTY_ADMIN', 'DEPUTY_ADMIN', true],
      ['DEPUTY_ADMIN', 'ADMIN', false],
      ['MEMBER', 'DEPUTY_ADMIN', false],
      ['SUPPORTER', 'DEPUTY_ADMIN', false],
    ] as const)(`ZA5-GD-03（回帰）: ${scopeType} の %s は %s 要求で通過=%s`, async (role, minRole, allowed) => {
      seed(role)
      await runGuard(scopeType, minRole)
      expect(denied()).toBe(!allowed)
    })
  }

  it('ZA5-GD-04: useAdminScopeGuard.ts の ROLE_RANK に SYSTEM_ADMIN の段が無い', () => {
    const src = readFileSync(resolve(process.cwd(), 'app/composables/useAdminScopeGuard.ts'), 'utf-8')
    const rank = src.match(/const ROLE_RANK[^=]*=\s*\{([\s\S]*?)\}/)
    expect(rank, 'ROLE_RANK の定義が見つからない').not.toBeNull()
    expect(rank![1]).not.toMatch(/SYSTEM_ADMIN/)
  })
})
