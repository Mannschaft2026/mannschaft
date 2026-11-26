import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamTimelineDigestPage from '~/pages/teams/[slug]/timeline-digest.vue'
import OrgTimelineDigestPage from '~/pages/organizations/[slug]/timeline-digest.vue'

/**
 * CMP-261125-1108 代表子ページ: タイムラインダイジェスト（チーム／組織）。
 *
 *   SNF-C1 主 API（ダイジェスト一覧）が 403 → markIfDenied 経由で notFound を立て、エラートーストを出さない
 *   SNF-C2 主 API が 404 → 同上
 *   SNF-C3 主 API が 500 → 従来どおり timeline_digest.error_load_list のトーストを出し、notFound は立てない
 */

const mocks = vi.hoisted(() => ({
  route: { current: { params: { slug: 'x' }, query: {}, path: '/teams/x/timeline-digest' } as Record<string, unknown> },
  listDigests: vi.fn(),
  notifyError: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => mocks.route.current)
mockNuxtImport('useTimelineDigestApi', () => () => ({
  listDigests: mocks.listDigests,
  deleteDigest: vi.fn(),
  publishDigest: vi.fn(),
  regenerateDigest: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: mocks.notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))

type ScopeNotFoundApi = {
  notFound: { value: boolean }
  markIfDenied: (err: unknown) => boolean
  reset: () => void
}
/** 新規 composable は試練時点で未実装のため、ページ側の red 理由が見えるよう動的 import で取り込む。 */
const SCOPE_NOT_FOUND_MODULE = '~/composables/useScopeNotFound'
async function scopeNotFound(): Promise<ScopeNotFoundApi> {
  const mod = (await import(/* @vite-ignore */ SCOPE_NOT_FOUND_MODULE)) as {
    useScopeNotFound: () => ScopeNotFoundApi
  }
  return mod.useScopeNotFound()
}

// 子ページが立てた状態を次のケースへ持ち越さない
afterEach(async () => {
  ;(await scopeNotFound()).reset()
})

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

const stubs = { DigestGenerateDialog: true, DigestConfigForm: true, Dialog: true }

const PAGES = [
  { label: 'teams', scope: 'teams', page: TeamTimelineDigestPage },
  { label: 'organizations', scope: 'organizations', page: OrgTimelineDigestPage },
] as const

beforeEach(() => {
  mocks.listDigests.mockReset()
  mocks.notifyError.mockReset()
})

for (const { label, scope, page } of PAGES) {
  describe(`pages/${label}/[slug]/timeline-digest.vue — 非所属・権限なし（CMP-261125-1108）`, () => {
    beforeEach(() => {
      mocks.route.current = { params: { slug: 'scope-a' }, query: {}, path: `/${scope}/scope-a/timeline-digest` }
    })

    for (const status of [403, 404] as const) {
      it(`SNF-C${status === 403 ? 1 : 2}: ダイジェスト一覧が ${status} なら notFound を立て、エラートーストを出さない`, async () => {
        mocks.listDigests.mockRejectedValueOnce({ statusCode: status, response: { status } })

        const wrapper = await mountSuspended(page, { global: { stubs } })
        await flush()

        expect(mocks.notifyError).not.toHaveBeenCalled()
        expect((await scopeNotFound()).notFound.value).toBe(true)
        wrapper.unmount()
      })
    }

    it('SNF-C3: ダイジェスト一覧が 500 なら従来どおりエラートーストを出し、notFound は立てない', async () => {
      mocks.listDigests.mockRejectedValueOnce({ statusCode: 500, response: { status: 500 } })

      const wrapper = await mountSuspended(page, { global: { stubs } })
      await flush()

      expect(mocks.notifyError).toHaveBeenCalledTimes(1)
      expect((await scopeNotFound()).notFound.value).toBe(false)
      wrapper.unmount()
    })
  })
}
