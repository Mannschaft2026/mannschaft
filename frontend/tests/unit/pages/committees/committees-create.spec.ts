import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import CommitteesPage from '~/pages/organizations/[slug]/committees.vue'

/**
 * CMP-261123-2128: 委員会の設立フォーム。
 *
 * BE（CommitteeCreateRequest）は initialChairUserId を必須とし、purposeTag は
 * TOURNAMENT/GOVERNANCE/ELECTION/PROJECT/OTHER の enum。FE は委員長欄が無く、目的タグが自由入力、
 * 400 の欄別エラーを出さず、送信中の二重押しも防げていなかった。
 *
 * 検証観点:
 *   CC-001 委員長を選んで送信すると initialChairUserId が POST 本文に入る
 *   CC-002 目的タグは enum の5値から選ぶ（自由入力欄ではない）
 *   CC-003 400 の fieldErrors が該当欄に表示される
 *   CC-004 送信中に作成ボタンを2回押しても createCommittee は1回
 *   CC-005 委員長未選択では送信できない
 */

const listCommittees = vi.fn()
const createCommittee = vi.fn()
const getMembers = vi.fn()
const handleApiError = vi.fn()
const getFieldErrors = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useCommitteeApi', () => () => ({ listCommittees, createCommittee }))
mockNuxtImport('useOrganizationApi', () => () => ({ getMembers }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, getFieldErrors }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(true), loadPermissions: vi.fn(async () => {}) }))

const members = [
  { userId: 1, displayName: '自分', avatarUrl: null, roleName: 'ADMIN', joinedAt: '2026-01-01T00:00:00Z' },
  { userId: 2, displayName: '花子', avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01T00:00:00Z' },
]
const emptyList = { data: [], meta: { page: 0, size: 20, total: 0, totalPages: 0 } }
const membersPage = { data: members, meta: { page: 0, size: 100, total: 2, totalPages: 1 } }

function setCurrentUser(id: number) {
  useAuthStore().$patch({ user: { id } as never })
}

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await new Promise((r) => setTimeout(r, 0))
}

async function openDialog() {
  const wrapper = await mountSuspended(CommitteesPage)
  await flush()
  const open = wrapper.find('[data-testid="committee-create-open"]')
  expect(open.exists(), '設立ボタン').toBe(true)
  await open.trigger('click')
  await flush()
  return wrapper
}

function selectByTestId(wrapper: Awaited<ReturnType<typeof openDialog>>, testid: string) {
  return wrapper.findAllComponents({ name: 'Select' }).find((c) => c.attributes('data-testid') === testid)
}

function createButton(): HTMLButtonElement {
  return document.body.querySelector('[data-testid="committee-create-submit"]') as HTMLButtonElement
}

async function fillName(value: string) {
  const input = document.body.querySelector('[data-testid="committee-create-name"]') as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flush()
}

beforeAll(async () => {
  listCommittees.mockResolvedValue(emptyList)
  getMembers.mockResolvedValue(membersPage)
  const warmup = await mountSuspended(CommitteesPage)
  warmup.unmount()
})

beforeEach(() => {
  listCommittees.mockReset().mockResolvedValue(emptyList)
  createCommittee.mockReset().mockResolvedValue({ data: {} })
  getMembers.mockReset().mockResolvedValue(membersPage)
  handleApiError.mockReset()
  getFieldErrors.mockReset().mockReturnValue({})
  setCurrentUser(1)
  document.body.innerHTML = ''
})

describe('pages/organizations/[slug]/committees.vue — 委員会の設立フォーム', () => {
  it('CC-001: 委員長を選んで送信すると initialChairUserId が POST 本文に入る', async () => {
    const wrapper = await openDialog()
    await fillName('総務委員会')
    const chair = selectByTestId(wrapper, 'committee-create-chair')
    expect(chair, '委員長の選択欄').toBeTruthy()
    chair!.vm.$emit('update:modelValue', 2)
    await flush()
    createButton().click()
    await flush()

    expect(createCommittee).toHaveBeenCalledTimes(1)
    expect(createCommittee.mock.calls[0]![1]).toMatchObject({ name: '総務委員会', initialChairUserId: 2 })
  })

  it('CC-002: 目的タグは enum の5値から選ぶ（自由入力欄ではない）', async () => {
    const wrapper = await openDialog()
    const purpose = selectByTestId(wrapper, 'committee-create-purpose')
    expect(purpose, '目的タグの選択欄').toBeTruthy()
    const options = purpose!.props('options') as Array<{ value: string }>
    expect(options.map((o) => o.value)).toEqual(['TOURNAMENT', 'GOVERNANCE', 'ELECTION', 'PROJECT', 'OTHER'])
    expect(document.body.querySelector('input[data-testid="committee-create-purpose"]')).toBeNull()

    await fillName('大会実行委員会')
    purpose!.vm.$emit('update:modelValue', 'TOURNAMENT')
    await flush()
    createButton().click()
    await flush()
    expect(createCommittee.mock.calls[0]![1]).toMatchObject({ purposeTag: 'TOURNAMENT' })
  })

  it('CC-003: 400 の fieldErrors が該当欄に表示される', async () => {
    createCommittee.mockRejectedValue({ statusCode: 400 })
    getFieldErrors.mockReturnValue({ name: '名前が不正です', initialChairUserId: '委員長が不正です' })
    await openDialog()
    await fillName('x')
    createButton().click()
    await flush()

    expect(document.body.querySelector('[data-testid="committee-create-name-error"]')?.textContent).toContain('名前が不正です')
    expect(document.body.querySelector('[data-testid="committee-create-chair-error"]')?.textContent).toContain('委員長が不正です')
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('CC-003b: fieldErrors が無い失敗は handleApiError に回す', async () => {
    const err = { statusCode: 500 }
    createCommittee.mockRejectedValue(err)
    await openDialog()
    await fillName('x')
    createButton().click()
    await flush()
    expect(handleApiError).toHaveBeenCalledWith(err, 'createCommittee')
  })

  it('CC-004: 送信中に作成ボタンを2回押しても createCommittee は1回', async () => {
    let resolve!: (v: unknown) => void
    createCommittee.mockReturnValue(new Promise((r) => { resolve = r }))
    await openDialog()
    await fillName('総務委員会')
    const btn = createButton()
    btn.click()
    btn.click()
    await flush()
    expect(createCommittee).toHaveBeenCalledTimes(1)
    resolve({ data: {} })
    await flush()
  })

  it('CC-005: 委員長未選択では送信できない', async () => {
    setCurrentUser(999) // 組織メンバーではないので既定の委員長は入らない
    await openDialog()
    await fillName('総務委員会')
    createButton().click()
    await flush()
    expect(createCommittee).not.toHaveBeenCalled()
    expect(createButton().disabled).toBe(true)
  })

  it('CC-006: GUEST と displayName=null は委員長候補から外れる', async () => {
    getMembers.mockResolvedValue({
      data: [
        ...members,
        { userId: 3, displayName: 'ゲスト', avatarUrl: null, roleName: 'GUEST', joinedAt: '2026-01-01T00:00:00Z' },
        { userId: 4, displayName: null, avatarUrl: null, roleName: 'MEMBER', joinedAt: '2026-01-01T00:00:00Z' },
        { userId: 5, displayName: '支援者', avatarUrl: null, roleName: 'SUPPORTER', joinedAt: '2026-01-01T00:00:00Z' },
      ],
      meta: { page: 0, size: 100, total: 5, totalPages: 1 },
    })
    const wrapper = await openDialog()
    const chair = selectByTestId(wrapper, 'committee-create-chair')
    const ids = (chair!.props('options') as Array<{ userId: number }>).map((o) => o.userId)
    expect(ids).toEqual([1, 2, 5])
  })

  it('CC-007: 表示欄に対応しない fieldErrors だけの 400 は handleApiError に回す', async () => {
    const err = { statusCode: 400 }
    createCommittee.mockRejectedValue(err)
    getFieldErrors.mockReturnValue({ startDate: '不正' })
    await openDialog()
    await fillName('x')
    createButton().click()
    await flush()
    expect(handleApiError).toHaveBeenCalledWith(err, 'createCommittee')
  })
})
