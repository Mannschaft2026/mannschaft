// CMP-261107-1833 PR3 P2-5: 招待管理画面の欄の出し分けで、権限の取得失敗を「権限なし」に倒さず、エラーを表示して再試行できる。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const mocks = vi.hoisted(() => ({
  loadPermissions: vi.fn(),
  handleApiError: vi.fn(),
  roleName: { value: null as string | null },
  permissions: { value: [] as string[] },
}))

vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({ roleName: mocks.roleName, permissions: mocks.permissions, loadPermissions: mocks.loadPermissions }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: mocks.handleApiError }),
}))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }
})

const loaders = import.meta.glob('./useInviteReissueAccess.ts')
const loader = loaders['./useInviteReissueAccess.ts']
type Mod = {
  useInviteReissueAccess: (scope: 'team' | 'organization', slug: ReturnType<typeof ref<string>>) => {
    canManage: { value: boolean }
    loadFailed: { value: boolean }
    load: () => Promise<void>
  }
}
const mod = (loader ? await loader() : null) as Mod | null
function use() {
  expect(mod, 'app/composables/useInviteReissueAccess.ts が未実装').not.toBeNull()
  return mod!.useInviteReissueAccess('team', ref('my-team'))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.roleName.value = null
  mocks.permissions.value = []
})

describe('useInviteReissueAccess', () => {
  it('IRA-01: 取得成功で ADMIN なら出す。失敗フラグは立たない', async () => {
    mocks.roleName.value = 'ADMIN'
    mocks.loadPermissions.mockResolvedValueOnce({ ok: true })
    const a = use()
    await a.load()
    expect(a.canManage.value).toBe(true)
    expect(a.loadFailed.value).toBe(false)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
  })

  it('IRA-02: 取得失敗はエラーを表示し、失敗フラグを立てる（権限なしとは区別する）', async () => {
    const error = new Error('network')
    mocks.loadPermissions.mockResolvedValueOnce({ ok: false, error })
    const a = use()
    await a.load()
    expect(a.loadFailed.value).toBe(true)
    expect(a.canManage.value).toBe(false)
    expect(mocks.handleApiError).toHaveBeenCalledWith(error, expect.any(String))
  })

  it('IRA-03: 再試行で成功すれば失敗フラグが下りて欄を出せる', async () => {
    mocks.loadPermissions.mockResolvedValueOnce({ ok: false, error: new Error('network') })
    const a = use()
    await a.load()
    mocks.roleName.value = 'DEPUTY_ADMIN'
    mocks.permissions.value = ['INVITE_MEMBERS']
    mocks.loadPermissions.mockResolvedValueOnce({ ok: true })
    await a.load()
    expect(a.loadFailed.value).toBe(false)
    expect(a.canManage.value).toBe(true)
  })

  it('IRA-04: 権限を持たない DEPUTY は取得成功でも出さず、エラーも出さない', async () => {
    mocks.roleName.value = 'DEPUTY_ADMIN'
    mocks.loadPermissions.mockResolvedValueOnce({ ok: true })
    const a = use()
    await a.load()
    expect(a.canManage.value).toBe(false)
    expect(a.loadFailed.value).toBe(false)
  })
})
