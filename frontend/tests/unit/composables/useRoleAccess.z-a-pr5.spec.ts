import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

/**
 * 戦役 Z-A PR5: スコープの実ロールだけで管理判定する（"SYSTEM_ADMIN" を管理者扱いしない）。
 *
 * PR3/PR5 以後、/me/permissions の roleName と /me/teams・/me/organizations の role は実ロールのみで、
 * "SYSTEM_ADMIN" を返さない。FE の「role が SYSTEM_ADMIN なら管理者」という判定は死にコードとして削除する
 * （設計書 z-a-design.md §4 分類 (i)）。旧応答の "SYSTEM_ADMIN" は未知のロール＝権限なし扱い（fail closed）。
 *
 *   ZA5-RA-01: roleName="SYSTEM_ADMIN" は isAdmin / isAdminOrDeputy とも false
 *   ZA5-RA-02: 所属の無い運営アカウント（roleName=null, scopeAdmin=false）は isAdmin / isAdminOrDeputy / isMember / isScopeAdmin とも false
 *   ZA5-RA-03（回帰）: ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER の判定は従来どおり
 *   ZA5-ST-01: useTeamStore.adminTeams / useOrganizationStore.adminOrganizations は role="SYSTEM_ADMIN" を含めない
 *   ZA5-ST-02（回帰）: 実ロール ADMIN は従来どおり含める
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// eslint-disable-next-line import/first
import { useRoleAccess } from '~/composables/useRoleAccess'
// eslint-disable-next-line import/first
import { useTeamStore } from '~/stores/useTeamStore'
// eslint-disable-next-line import/first
import { useOrganizationStore } from '~/stores/useOrganizationStore'

async function load(roleName: string | null, scopeAdmin?: boolean) {
  mockFetch.mockResolvedValueOnce({ data: { roleName, permissions: [], scopeAdmin } })
  const access = useRoleAccess('team', 'team-1')
  await access.loadPermissions()
  return access
}

describe('useRoleAccess — 戦役Z-A PR5 実ロールのみで判定', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  it('ZA5-RA-01: roleName="SYSTEM_ADMIN"（旧応答）は isAdmin / isAdminOrDeputy とも false', async () => {
    const access = await load('SYSTEM_ADMIN', false)
    expect(access.isAdmin.value).toBe(false)
    expect(access.isAdminOrDeputy.value).toBe(false)
  })

  it('ZA5-RA-02: 所属の無い運営アカウント（roleName=null）は管理・メンバー判定がすべて false', async () => {
    const access = await load(null, false)
    expect(access.isAdmin.value).toBe(false)
    expect(access.isAdminOrDeputy.value).toBe(false)
    expect(access.isMember.value).toBe(false)
    expect(access.isScopeAdmin.value).toBe(false)
  })

  it.each([
    ['ADMIN', true, true, true],
    ['DEPUTY_ADMIN', false, true, true],
    ['MEMBER', false, false, true],
    ['SUPPORTER', false, false, false],
  ])('ZA5-RA-03（回帰）: %s は isAdmin=%s isAdminOrDeputy=%s isMember=%s', async (role, admin, deputy, member) => {
    const access = await load(role, role === 'ADMIN')
    expect(access.isAdmin.value).toBe(admin)
    expect(access.isAdminOrDeputy.value).toBe(deputy)
    expect(access.isMember.value).toBe(member)
  })
})

describe('所属一覧ストア — 戦役Z-A PR5 実ロールのみで管理スコープを数える', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('ZA5-ST-01: role="SYSTEM_ADMIN" のチーム・組織を管理対象に含めない', () => {
    const teams = useTeamStore()
    const orgs = useOrganizationStore()
    teams.myTeams = [{ id: 1, role: 'SYSTEM_ADMIN' }] as unknown as typeof teams.myTeams
    orgs.myOrganizations = [{ id: 2, role: 'SYSTEM_ADMIN' }] as unknown as typeof orgs.myOrganizations
    expect(teams.adminTeams).toHaveLength(0)
    expect(orgs.adminOrganizations).toHaveLength(0)
  })

  it('ZA5-ST-02（回帰）: 実ロール ADMIN は含め、DEPUTY_ADMIN・MEMBER・SUPPORTER は含めない', () => {
    const teams = useTeamStore()
    const orgs = useOrganizationStore()
    const rows = ['ADMIN', 'DEPUTY_ADMIN', 'MEMBER', 'SUPPORTER'].map((role, i) => ({ id: i + 1, role }))
    teams.myTeams = rows as unknown as typeof teams.myTeams
    orgs.myOrganizations = rows as unknown as typeof orgs.myOrganizations
    expect(teams.adminTeams.map(t => t.id)).toEqual([1])
    expect(orgs.adminOrganizations.map(o => o.id)).toEqual([1])
  })
})
