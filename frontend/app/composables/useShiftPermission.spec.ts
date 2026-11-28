// @vitest-environment happy-dom
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { computed, effectScope, nextTick, ref, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resolveShiftPermission, type ShiftPermissionInput } from './useShiftPermission'

/**
 * シフト管理の出し分け判定（CMP-261125-2114）。
 * 管理操作は ADMIN と MANAGE_SHIFTS 付き DEPUTY_ADMIN だけ。MANAGE_SHIFTS 無しの DEPUTY_ADMIN は
 * チームの閲覧切替が ON のときだけ閲覧できる。SYSTEM_ADMIN は特別扱いしない。
 */

const base: ShiftPermissionInput = {
  roleName: 'MEMBER',
  isScopeAdmin: false,
  permissions: [],
  deputyShiftViewEnabled: false,
  deputyHourlyRateViewEnabled: false,
}

describe('resolveShiftPermission（真理値表）', () => {
  it('ADMIN は管理・閲覧・時給閲覧のすべてができる（切替に依らない）', () => {
    expect(resolveShiftPermission({ ...base, roleName: 'ADMIN', isScopeAdmin: true })).toEqual({
      canManageShift: true,
      canViewShift: true,
      canViewHourlyRate: true,
      isDeputyWithoutManage: false,
    })
  })

  it('MANAGE_SHIFTS を持つ DEPUTY_ADMIN は管理・閲覧・時給閲覧ができる（切替に依らない）', () => {
    expect(
      resolveShiftPermission({ ...base, roleName: 'DEPUTY_ADMIN', permissions: ['MANAGE_SHIFTS'] }),
    ).toEqual({
      canManageShift: true,
      canViewShift: true,
      canViewHourlyRate: true,
      isDeputyWithoutManage: false,
    })
  })

  it('MANAGE_SHIFTS 無しの DEPUTY_ADMIN は、切替が両方 ON でも管理操作はできず、閲覧だけできる', () => {
    expect(
      resolveShiftPermission({
        ...base,
        roleName: 'DEPUTY_ADMIN',
        deputyShiftViewEnabled: true,
        deputyHourlyRateViewEnabled: true,
      }),
    ).toEqual({
      canManageShift: false,
      canViewShift: true,
      canViewHourlyRate: true,
      isDeputyWithoutManage: true,
    })
  })

  it('MANAGE_SHIFTS 無しの DEPUTY_ADMIN は、シフト閲覧と時給閲覧の切替を独立に判定する', () => {
    const shiftOnly = resolveShiftPermission({ ...base, roleName: 'DEPUTY_ADMIN', deputyShiftViewEnabled: true })
    expect(shiftOnly.canViewShift).toBe(true)
    expect(shiftOnly.canViewHourlyRate).toBe(false)
    const rateOnly = resolveShiftPermission({ ...base, roleName: 'DEPUTY_ADMIN', deputyHourlyRateViewEnabled: true })
    expect(rateOnly.canViewShift).toBe(false)
    expect(rateOnly.canViewHourlyRate).toBe(true)
  })

  it('MANAGE_SHIFTS 無しの DEPUTY_ADMIN は、切替が OFF なら何もできない（一般メンバーと同じ）', () => {
    expect(resolveShiftPermission({ ...base, roleName: 'DEPUTY_ADMIN' })).toEqual({
      canManageShift: false,
      canViewShift: false,
      canViewHourlyRate: false,
      isDeputyWithoutManage: true,
    })
  })

  it('MEMBER は切替が ON でも何もできない（切替は副管理者にだけ効く）', () => {
    expect(
      resolveShiftPermission({
        ...base,
        roleName: 'MEMBER',
        deputyShiftViewEnabled: true,
        deputyHourlyRateViewEnabled: true,
      }),
    ).toEqual({
      canManageShift: false,
      canViewShift: false,
      canViewHourlyRate: false,
      isDeputyWithoutManage: false,
    })
  })

  it('権限グループで MANAGE_SHIFTS が付いた MEMBER は管理できない（BE は 403。切替が ON でも閲覧もできない）', () => {
    expect(
      resolveShiftPermission({
        ...base,
        roleName: 'MEMBER',
        permissions: ['MANAGE_SHIFTS'],
        deputyShiftViewEnabled: true,
        deputyHourlyRateViewEnabled: true,
      }),
    ).toEqual({
      canManageShift: false,
      canViewShift: false,
      canViewHourlyRate: false,
      isDeputyWithoutManage: false,
    })
  })

  it('SYSTEM_ADMIN はチームロールが無ければ特別扱いしない（roleName だけでは許さない）', () => {
    expect(
      resolveShiftPermission({
        ...base,
        roleName: 'SYSTEM_ADMIN',
        deputyShiftViewEnabled: true,
        deputyHourlyRateViewEnabled: true,
      }),
    ).toEqual({
      canManageShift: false,
      canViewShift: false,
      canViewHourlyRate: false,
      isDeputyWithoutManage: false,
    })
  })

  it('SYSTEM_ADMIN でもチームの ADMIN 割当（scopeAdmin）があれば管理できる', () => {
    expect(resolveShiftPermission({ ...base, roleName: 'SYSTEM_ADMIN', isScopeAdmin: true }).canManageShift).toBe(true)
  })

  it('権限の取得失敗（roleName = null）は何もできない側に倒れる', () => {
    expect(resolveShiftPermission({ ...base, roleName: null })).toEqual({
      canManageShift: false,
      canViewShift: false,
      canViewHourlyRate: false,
      isDeputyWithoutManage: false,
    })
  })
})

// ───────────────────────────────────────────────
// composable: 閲覧切替の取得は MANAGE_SHIFTS 無しの副管理者のときだけ
// ───────────────────────────────────────────────
const roleName = ref<string | null>(null)
const permissions = ref<string[]>([])
const isScopeAdmin = ref(false)
const roleResolved = ref(false)
const loadPermissionsMock = vi.fn()
const getShiftSettingsMock = vi.fn()
const handleApiErrorMock = vi.fn()

mockNuxtImport('useRoleAccess', () => () => ({
  roleName,
  permissions,
  isScopeAdmin: computed(() => isScopeAdmin.value),
  isMember: computed(() => true),
  roleResolved,
  roleLoadFailed: ref(false),
  loading: ref(false),
  loadPermissions: loadPermissionsMock,
}))
mockNuxtImport('useTeamShiftSettingsApi', () => () => ({ getShiftSettings: getShiftSettingsMock }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: handleApiErrorMock }))

const { useShiftPermission } = await import('./useShiftPermission')

const scopes: EffectScope[] = []
function create(slug: string | null) {
  const scope = effectScope()
  scopes.push(scope)
  return scope.run(() => useShiftPermission(ref(slug)))!
}

function asRole(role: string, opts: { scopeAdmin?: boolean, perms?: string[] } = {}) {
  loadPermissionsMock.mockImplementation(async () => {
    roleName.value = role
    permissions.value = opts.perms ?? []
    isScopeAdmin.value = opts.scopeAdmin ?? false
    roleResolved.value = true
    return { ok: true }
  })
}

describe('useShiftPermission（閲覧切替の取得）', () => {
  beforeEach(() => {
    roleName.value = null
    permissions.value = []
    isScopeAdmin.value = false
    roleResolved.value = false
    loadPermissionsMock.mockReset()
    getShiftSettingsMock.mockReset()
    handleApiErrorMock.mockReset()
  })
  afterEach(() => { for (const s of scopes.splice(0)) s.stop() })

  it('MANAGE_SHIFTS 無しの DEPUTY_ADMIN は切替を取得し、ON の分だけ閲覧できる', async () => {
    asRole('DEPUTY_ADMIN')
    getShiftSettingsMock.mockResolvedValue({ deputyShiftViewEnabled: true, deputyHourlyRateViewEnabled: false })
    const p = create('team-a')

    await p.loadPermissions()

    expect(getShiftSettingsMock).toHaveBeenCalledWith('team-a')
    expect(p.canManageShift.value).toBe(false)
    expect(p.canViewShift.value).toBe(true)
    expect(p.canViewHourlyRate.value).toBe(false)
    expect(p.isDeputyWithoutManage.value).toBe(true)
  })

  it('ADMIN は切替を取得しない（無駄な要求を撃たない）', async () => {
    asRole('ADMIN', { scopeAdmin: true, perms: ['MANAGE_SHIFTS'] })
    const p = create('team-a')

    await p.loadPermissions()
    await nextTick()

    expect(getShiftSettingsMock).not.toHaveBeenCalled()
    expect(p.canManageShift.value).toBe(true)
  })

  it('MANAGE_SHIFTS を持つ DEPUTY_ADMIN は切替を取得せず管理できる', async () => {
    asRole('DEPUTY_ADMIN', { perms: ['MANAGE_SHIFTS'] })
    const p = create('team-a')

    await p.loadPermissions()
    await nextTick()

    expect(getShiftSettingsMock).not.toHaveBeenCalled()
    expect(p.canManageShift.value).toBe(true)
  })

  it('MANAGE_SHIFTS が付いた MEMBER は管理できず、切替も取得しない', async () => {
    asRole('MEMBER', { perms: ['MANAGE_SHIFTS'] })
    const p = create('team-a')

    await p.loadPermissions()
    await nextTick()

    expect(getShiftSettingsMock).not.toHaveBeenCalled()
    expect(p.canManageShift.value).toBe(false)
  })

  it('MEMBER は切替を取得しない', async () => {
    asRole('MEMBER')
    const p = create('team-a')

    await p.loadPermissions()
    await nextTick()

    expect(getShiftSettingsMock).not.toHaveBeenCalled()
    expect(p.canViewShift.value).toBe(false)
  })

  it('切替の取得に失敗したら握りつぶさず通知し、閲覧は不可（安全側）に倒す', async () => {
    asRole('DEPUTY_ADMIN')
    const error = new Error('boom')
    getShiftSettingsMock.mockRejectedValue(error)
    const p = create('team-a')

    await p.loadPermissions()

    expect(handleApiErrorMock).toHaveBeenCalledWith(error)
    expect(p.settingsLoadFailed.value).toBe(true)
    expect(p.canViewShift.value).toBe(false)
    expect(p.canViewHourlyRate.value).toBe(false)
  })

  it('権限の取得失敗は切替を取得せず、失敗をそのまま返す', async () => {
    const failure = { ok: false, error: new Error('x') }
    loadPermissionsMock.mockResolvedValue(failure)
    const p = create('team-a')

    await expect(p.loadPermissions()).resolves.toBe(failure)
    expect(getShiftSettingsMock).not.toHaveBeenCalled()
  })
})
