// @vitest-environment nuxt
// F10.9 サポート閲覧（本人側）: 依頼理由はプレーンテキスト、許可は再認証（パスワード）が必須でインライン、保護者待ちの表示。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import { useNuxtApp } from '#app'
import SupportAccessSettingsPage from './support-access.vue'

const external = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => external.api)

const FUTURE = new Date(Date.now() + 60 * 60 * 1000).toISOString()
const REASON = '不具合の調査です https://example.invalid/phish <b>強調</b>'

function grant(overrides: Record<string, unknown> = {}) {
  return {
    id: '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b',
    role: 'TARGET',
    operatorDisplayName: '運営太郎',
    targetDisplayName: '本人',
    status: 'PENDING',
    requestReason: REASON,
    referenceType: 'FEEDBACK',
    requestedAt: new Date().toISOString(),
    requestExpiresAt: FUTURE,
    decidedAt: null,
    expiresAt: null,
    revokedAt: null,
    endedReason: null,
    guardianApprovalRequired: false,
    targetApproved: false,
    guardianApproved: false,
    canApprove: true,
    ...overrides,
  }
}

function page<T>(data: T[]) {
  return { data, meta: { nextCursor: null, hasNext: false, limit: 20 } }
}

let pending: ReturnType<typeof grant>[] = []
const wrappers: VueWrapper[] = []
let originalLocale = 'ja'

beforeEach(async () => {
  const i18n = useNuxtApp().$i18n
  originalLocale = i18n.locale.value
  await i18n.setLocale('ja')
  external.api.mockReset()
  external.api.mockImplementation(async (url: string, options?: { query?: { status?: string } }) => {
    if (url.endsWith('/me/support-access/grants')) {
      return page(options?.query?.status === 'PENDING' ? pending : [])
    }
    if (url.endsWith('/me/support-access/logs')) return page([])
    return { data: grant({ status: 'APPROVED' }) }
  })
})
afterEach(async () => {
  wrappers.splice(0).forEach(w => w.unmount())
  await useNuxtApp().$i18n.setLocale(originalLocale as 'ja' | 'en' | 'zh' | 'ko' | 'es' | 'de')
})

async function mountPage() {
  const wrapper = await mountSuspended(SupportAccessSettingsPage)
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('設定 > サポート閲覧の許可', () => {
  it('依頼理由はプレーンテキストで表示し、リンク化も HTML 解釈もしない', async () => {
    pending = [grant()]
    const wrapper = await mountPage()
    const reason = wrapper.get('[data-testid="support-access-reason-text"]')
    expect(reason.text()).toBe(REASON)
    expect(reason.find('a').exists()).toBe(false)
    expect(reason.find('b').exists()).toBe(false)
  })

  it('許可は再認証（パスワード）が必須で、モーダルを開かずインラインで行う', async () => {
    pending = [grant()]
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="support-access-approve"]').trigger('click')
    expect(wrapper.find('[data-testid="support-access-reauth"]').exists()).toBe(true)
    // 再認証材料が無い間は送れない
    const submit = wrapper.get('[data-testid="support-access-approve-password"]')
    expect(submit.attributes('disabled')).toBeDefined()
    expect(external.api).not.toHaveBeenCalledWith(expect.stringContaining('/approve'), expect.anything())

    await wrapper.get('[data-testid="support-access-password"] input').setValue('correct-horse')
    await wrapper.get('[data-testid="support-access-reauth"]').trigger('submit')
    await flushPromises()
    expect(external.api).toHaveBeenCalledWith(
      '/api/v1/me/support-access/grants/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b/approve',
      expect.objectContaining({ method: 'POST', body: { currentPassword: 'correct-horse' } }),
    )
  })

  it('許可の再認証に失敗したら BE のメッセージをその場に表示する', async () => {
    pending = [grant()]
    const wrapper = await mountPage()
    external.api.mockImplementation(async (url: string, options?: { query?: { status?: string } }) => {
      if (url.endsWith('/approve')) {
        throw Object.assign(new Error('bad'), { data: { error: { code: 'SUPPORT_ACCESS_013', message: '本人確認に失敗しました' } }, status: 400 })
      }
      if (url.endsWith('/me/support-access/grants')) return page(options?.query?.status === 'PENDING' ? pending : [])
      return page([])
    })
    await wrapper.get('[data-testid="support-access-approve"]').trigger('click')
    await wrapper.get('[data-testid="support-access-password"] input').setValue('wrong')
    await wrapper.get('[data-testid="support-access-reauth"]').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[data-testid="support-access-panel-error"]').text()).toContain('本人確認に失敗しました')
  })

  it('保護者の承認待ち（未成年・後見下で、本人は許可済み）は許可・拒否ボタンを出さずその旨を表示する', async () => {
    pending = [grant({ guardianApprovalRequired: true, targetApproved: true, canApprove: false })]
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="support-access-guardian-waiting"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="support-access-approve"]').exists()).toBe(false)
  })
})
