import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '../../../app/stores/useAuthStore'
import { stashParentalConsentToken, takeParentalConsentReturnPath } from '../../../app/utils/parentalConsentReturn'

/**
 * CMP-261123-1124 試練（red 先行）: 保護者同意待ちの印をストアが保持する契約。
 *
 * 背景: 同意待ちの未成年はトークンを持つ（JWT ppc=true）が、旧実装は setUser を呼ばず
 * isAuthenticated=false のままにしていたため、auth middleware が /login へ戻していた。
 * 本契約では「同意待ちも認証済み」とし、本人の status を user に載せて
 * getter `isPendingParentalConsent` で判定する。
 *
 * - AC2: リロード後も同意待ちのまま（保存済み本人から復元。正は /users/me の status）
 * - AC2: ログアウト・別ユーザー切替・承認後の取り直しで印が残らない
 * - AC7: status を持たない（従来形の）本人・ACTIVE は同意待ち扱いしない
 */

const { teamClear, organizationClear, chatClear, actionMemoClear, navigateToMock, disarmMock, scopeFoldersClear } = vi.hoisted(() => ({
  teamClear: vi.fn(),
  organizationClear: vi.fn(),
  chatClear: vi.fn(),
  actionMemoClear: vi.fn(),
  navigateToMock: vi.fn(),
  disarmMock: vi.fn(),
  scopeFoldersClear: vi.fn(),
}))

vi.mock('../../../app/stores/useTeamStore', () => ({ useTeamStore: () => ({ clear: teamClear }) }))
vi.mock('../../../app/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ clear: organizationClear }) }))
vi.mock('../../../app/composables/useScopeFoldersStore', () => ({ useScopeFoldersStore: () => ({ clear: scopeFoldersClear }) }))
mockNuxtImport('navigateTo', () => (...args: unknown[]) => navigateToMock(...args))
mockNuxtImport('useChatTabsStore', () => () => ({ clearAll: chatClear }))
mockNuxtImport('disarmProactiveRefresh', () => () => disarmMock())
mockNuxtImport('useActionMemoStore', () => () => ({ clear: actionMemoClear }))

const pendingChild = {
  id: 7,
  email: 'child@example.com',
  fullName: '山田 太郎',
  profileImageUrl: null,
  status: 'PENDING_PARENTAL_CONSENT',
}

/** 実装前は getter が無いため undefined になる。型を固定せずに読む。 */
function pendingFlag(): unknown {
  return (useAuthStore() as unknown as { isPendingParentalConsent?: unknown }).isPendingParentalConsent
}

type SetUserArg = Parameters<ReturnType<typeof useAuthStore>['setUser']>[0]

describe('useAuthStore 保護者同意待ちの印（CMP-261123-1124）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('AC2-S1: 同意待ちの本人を setUser すると認証済みかつ同意待ちになる', async () => {
    await useAuthStore().setUser(pendingChild as unknown as SetUserArg)

    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(pendingFlag()).toBe(true)
  })

  it('AC2-S2: リロード（保存済み本人の復元）後も同意待ちのまま', async () => {
    await useAuthStore().setUser(pendingChild as unknown as SetUserArg)
    // リロード相当: ストアを作り直して localStorage から復元する
    setActivePinia(createPinia())
    useAuthStore().loadFromStorage()

    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(pendingFlag()).toBe(true)
  })

  it('AC2-S3: ログアウトで印が消え、保存済み本人も残らない', async () => {
    await useAuthStore().setUser(pendingChild as unknown as SetUserArg)
    await useAuthStore().logout()

    expect(pendingFlag()).toBe(false)
    expect(localStorage.getItem('currentUser')).toBeNull()
    setActivePinia(createPinia())
    useAuthStore().loadFromStorage()
    expect(pendingFlag()).toBe(false)
  })

  it('AC2-S4: 別ユーザー（ACTIVE）へ切り替えると印が残らない', async () => {
    await useAuthStore().setUser(pendingChild as unknown as SetUserArg)
    await useAuthStore().setUser({
      id: 8, email: 'other@example.com', fullName: '別 人', profileImageUrl: null, status: 'ACTIVE',
    } as unknown as SetUserArg)

    expect(pendingFlag()).toBe(false)
    setActivePinia(createPinia())
    useAuthStore().loadFromStorage()
    expect(pendingFlag()).toBe(false)
  })

  it('AC6-S5: 承認後に同じ本人を ACTIVE で取り直すと印が外れ、リロード後も戻らない', async () => {
    await useAuthStore().setUser(pendingChild as unknown as SetUserArg)
    await useAuthStore().setUser({ ...pendingChild, status: 'ACTIVE' } as unknown as SetUserArg)

    expect(pendingFlag()).toBe(false)
    setActivePinia(createPinia())
    useAuthStore().loadFromStorage()
    expect(pendingFlag()).toBe(false)
  })

  it('AC7-S6: status を持たない従来形の本人は同意待ち扱いしない（一般ユーザーの回帰）', async () => {
    await useAuthStore().setUser({ id: 9, email: 'a@example.com', fullName: 'A B', profileImageUrl: null })

    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(pendingFlag()).toBe(false)
  })
})

/**
 * CMP-261123-1124 Codex 第6回 P1: 未認証の保護者 A が保存した承認 token を、後からログインした別ユーザー B が
 * 引き継がない。ログアウトの全経路（logout / serverLogout）で保存を必ず破棄する。
 */
describe('useAuthStore ログアウトと保存済みの承認 token（CMP-261123-1124）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('logout で保存済みの承認 token が破棄され、次にログインした別ユーザーは復帰しない', async () => {
    stashParentalConsentToken('tok-A')
    await useAuthStore().setUser({ id: 9, email: 'a@example.com', fullName: 'A B', profileImageUrl: null })

    await useAuthStore().logout()

    expect(localStorage.getItem('parentalConsentReturn')).toBeNull()
    expect(takeParentalConsentReturnPath()).toBeUndefined()
  })

  it('serverLogout（API 失敗時も）で破棄される', async () => {
    stashParentalConsentToken('tok-A')

    await useAuthStore().serverLogout()

    expect(takeParentalConsentReturnPath()).toBeUndefined()
  })
})
