/**
 * 戦役 Z-A PR5 AC9: 運営アカウント（authStore.isSystemAdmin=true、テナント所属なし）のグローバルナビは
 * /system-admin への導線だけを持ち、テナント管理の導線（代理入力デスク・組合業務）を持たない。
 * 一般利用者（組合・管理組合の ADMIN/DEPUTY_ADMIN）の導線は変わらない。
 *
 * 拒否型（!authStore.isSystemAdmin による代理入力導線の除外）の既存テストは DenyTypeTestsFrozenGuardTest が
 * SHA-256 で固定している（tests/unit/composables/useAppNavGroups.spec.ts）。本ファイルはそれに触れず、
 * 「所属の無い運営アカウント」という PR5 以後の現実の形を別に固定する。
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppNavGroups } from '~/composables/useAppNavGroups'
import { useTeamStore } from '~/stores/useTeamStore'
import { useAuthStore } from '~/stores/useAuthStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'

const keys = () => useAppNavGroups().groups.value.flatMap((g) => g.items.map((i) => ({ key: i.key, path: i.path })))
const TENANT_ADMIN_KEYS = ['proxy-desk', 'proxy-consents', 'proxy-records']

describe('useAppNavGroups — 戦役Z-A PR5 AC9', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('ZA5-NAV-01: 所属の無い運営アカウントには /system-admin の導線が出て、テナント管理の導線は出ない', () => {
    const authStore = useAuthStore()
    authStore.user = { id: 1, systemRole: 'SYSTEM_ADMIN' } as typeof authStore.user
    useTeamStore().myTeams = []
    useOrganizationStore().myOrganizations = []

    const items = keys()
    expect(items).toContainEqual({ key: 'system-admin', path: '/system-admin' })
    for (const k of TENANT_ADMIN_KEYS) expect(items.map((i) => i.key)).not.toContain(k)
    expect(items.filter((i) => i.path.startsWith('/admin/'))).toEqual([])
  })

  it('ZA5-NAV-02（回帰）: 一般利用者の組合 ADMIN・管理組合 DEPUTY_ADMIN は代理入力の導線を持ち、/system-admin は持たない', () => {
    const authStore = useAuthStore()
    authStore.user = { id: 2 } as typeof authStore.user
    useTeamStore().myTeams = [{ id: 1, template: 'NEIGHBORHOOD', role: 'ADMIN' }] as unknown as ReturnType<typeof useTeamStore>['myTeams']
    useOrganizationStore().myOrganizations = [{ id: 2, role: 'DEPUTY_ADMIN' }] as unknown as ReturnType<typeof useOrganizationStore>['myOrganizations']

    const items = keys().map((i) => i.key)
    for (const k of TENANT_ADMIN_KEYS) expect(items).toContain(k)
    expect(items).not.toContain('system-admin')
  })

  it.each(['MEMBER', 'SUPPORTER'])('ZA5-NAV-03（回帰）: 一般利用者 %s は代理入力の導線も /system-admin も持たない', (role) => {
    const authStore = useAuthStore()
    authStore.user = { id: 3 } as typeof authStore.user
    useTeamStore().myTeams = [{ id: 1, template: 'CONDO', role }] as unknown as ReturnType<typeof useTeamStore>['myTeams']
    useOrganizationStore().myOrganizations = [{ id: 2, role }] as unknown as ReturnType<typeof useOrganizationStore>['myOrganizations']

    const items = keys().map((i) => i.key)
    for (const k of TENANT_ADMIN_KEYS) expect(items).not.toContain(k)
    expect(items).not.toContain('system-admin')
  })
})
