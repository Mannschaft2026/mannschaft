import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import ShiftPublishErrorDialog from './ShiftPublishErrorDialog.vue'

const mocks = vi.hoisted(() => ({
  getAllMembers: vi.fn(),
  loadPermissions: vi.fn(),
  handleApiError: vi.fn(),
  isAdmin: { value: true },
}))
mockNuxtImport('useTeamApi', () => () => ({ getAllMembers: mocks.getAllMembers }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: mocks.isAdmin,
  loadPermissions: mocks.loadPermissions,
}))

const body = () => document.body.textContent ?? ''

describe('ShiftPublishErrorDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.isAdmin = ref(true) as unknown as { value: boolean }
    mocks.loadPermissions.mockResolvedValue({ ok: true })
    mocks.getAllMembers.mockResolvedValue({ data: [{ userId: 7, displayName: '山田太郎' }] })
  })

  it('時給未設定の対象者名と勤務日を一覧表示し、ADMIN には設定画面への導線を出す', async () => {
    await mountSuspended(ShiftPublishErrorDialog, {
      props: {
        info: {
          kind: 'hourlyRateMissing',
          missing: [
            { userId: 7, slotDate: '2026-11-20' },
            { userId: 8, slotDate: '2026-11-21' },
          ],
        },
        teamSlug: 'team-12',
      },
    })
    await flushPromises()
    expect(body()).toContain('山田太郎')
    expect(body()).toContain('2026-11-20')
    expect(body()).toContain('8')
    expect(document.querySelector('[data-testid="shift-publish-error-settings-link"]')).not.toBeNull()
  })

  it('ADMIN でなければ設定画面への導線を出さない', async () => {
    mocks.isAdmin = ref(false) as unknown as { value: boolean }
    await mountSuspended(ShiftPublishErrorDialog, {
      props: {
        info: { kind: 'hourlyRateMissing', missing: [{ userId: 7, slotDate: '2026-11-20' }] },
        teamSlug: 'team-12',
      },
    })
    await flushPromises()
    expect(document.querySelector('[data-testid="shift-publish-error-settings-link"]')).toBeNull()
  })

  it('名簿の取得に失敗したらエラーを通知し、ID 表示で一覧は残す', async () => {
    const err = new Error('boom')
    mocks.getAllMembers.mockRejectedValue(err)
    await mountSuspended(ShiftPublishErrorDialog, {
      props: {
        info: { kind: 'hourlyRateMissing', missing: [{ userId: 7, slotDate: '2026-11-20' }] },
        teamSlug: 'team-12',
      },
    })
    await flushPromises()
    expect(mocks.handleApiError).toHaveBeenCalledWith(err)
    expect(document.querySelector('[data-testid="shift-publish-error-missing-list"]')).not.toBeNull()
  })

  it('409 は読み込み直しの案内を出し、名簿を取得しない', async () => {
    await mountSuspended(ShiftPublishErrorDialog, {
      props: { info: { kind: 'conflict' }, teamSlug: 'team-12' },
    })
    await flushPromises()
    expect(document.querySelector('[data-testid="shift-publish-error-conflict"]')).not.toBeNull()
    expect(mocks.getAllMembers).not.toHaveBeenCalled()
  })
})
