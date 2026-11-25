// CMP-261125-1951: 5xx のエラートーストは useApi に一元化し、1操作で1枚にする。
// 業務コード付きの 5xx は BE の message（無ければ i18n）を detail に出し、コードの無い 5xx は従来どおり
// error.server_retry を出す。500ms 集約も維持する。呼び出し側が handleApiError に渡しても枚数は増えない。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
const external = vi.hoisted(() => ({
  fetch: vi.fn<typeof fetch>(),
  navigate: vi.fn(),
  toastAdd: vi.fn<(opts: Record<string, unknown>) => void>(),
  capture: vi.fn(),
  captureQuiet: vi.fn(),
}))
vi.mock('ofetch', async importOriginal => {
  const original = await importOriginal<typeof import('ofetch')>()
  return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('navigateTo', () => external.navigate)
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.capture, captureQuiet: external.captureQuiet }))
// useNotification は PrimeVue の同じトーストへ出すので、総枚数を数えるため同じ spy へ流す。
mockNuxtImport('useNotification', () => () => ({
  error: (summary: string, detail?: string) =>
    external.toastAdd({ severity: 'error', summary, detail, life: 5000 }),
}))
mockNuxtImport('useI18n', () => () => {
  const i18n = useNuxtApp().$i18n
  return { t: (key: string) => i18n.t(key), te: (key: string) => i18n.te(key) }
})
const { useApi } = await import('~/composables/useApi')
const { useErrorHandler } = await import('~/composables/useErrorHandler')
const json = (data: unknown, status: number) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
const tr = (key: string, named?: Record<string, unknown>) => useNuxtApp().$i18n.t(key, named ?? {})

beforeEach(() => {
  setActivePinia(createPinia())
  external.fetch.mockReset()
  external.navigate.mockReset()
  external.toastAdd.mockReset()
  external.capture.mockReset()
  external.captureQuiet.mockReset()
  // $toast は Nuxt の provide で再定義できないため、実体の add を spy で差し替える。
  const toast = useNuxtApp().$toast as { add: (opts: Record<string, unknown>) => void }
  vi.spyOn(toast, 'add').mockImplementation(opts => external.toastAdd(opts))
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
})
afterEach(async () => {
  // 集約タイマー（モジュール単位の状態）を次のテストへ持ち越さない。
  if (vi.isFakeTimers()) {
    await vi.runOnlyPendingTimersAsync()
    vi.useRealTimers()
  }
  vi.restoreAllMocks()
})

describe('CMP-261125-1951 useApi の 5xx トースト', () => {
  it('AC5 業務コードと message 付きの 5xx は 500ms 後に BE の message を detail に1回だけ出す', async () => {
    external.fetch.mockResolvedValue(
      json({ error: { code: 'RANCH_SERVICE_UNAVAILABLE', message: '牧場は一時的に利用できません' } }, 503),
    )
    await expect(useApi()('/api/v1/me/ranch')).rejects.toMatchObject({ statusCode: 503 })

    await vi.advanceTimersByTimeAsync(499)
    expect(external.toastAdd).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(external.toastAdd).toHaveBeenCalledTimes(1)
    expect(external.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'error',
        summary: tr('error.server'),
        detail: '牧場は一時的に利用できません',
      }),
    )
  })

  it('AC5 業務コードだけで message の無い 5xx は i18n で解決した文言を detail に出す', async () => {
    external.fetch.mockResolvedValue(json({ error: { code: 'COMMON_999' } }, 500))
    await expect(useApi()('/api/v1/me/ranch')).rejects.toThrow()

    await vi.advanceTimersByTimeAsync(500)
    expect(external.toastAdd).toHaveBeenCalledTimes(1)
    expect(external.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ detail: tr('error.COMMON_999') }),
    )
  })

  it('AC6 業務コードの無い 5xx は従来どおり error.server_retry を出す', async () => {
    external.fetch.mockResolvedValue(json({}, 500))
    await expect(useApi()('/api/v1/me/ranch')).rejects.toThrow()

    await vi.advanceTimersByTimeAsync(500)
    expect(external.toastAdd).toHaveBeenCalledTimes(1)
    expect(external.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ summary: tr('error.server'), detail: tr('error.server_retry') }),
    )
  })

  it('AC6 500ms 以内の複数の 5xx は集約して1回にする', async () => {
    external.fetch.mockImplementation(async () => json({}, 502))
    const api = useApi()
    await Promise.allSettled([api('/api/v1/a'), api('/api/v1/b'), api('/api/v1/c')])

    await vi.advanceTimersByTimeAsync(500)
    expect(external.toastAdd).toHaveBeenCalledTimes(1)
    expect(external.toastAdd).toHaveBeenCalledWith(
      expect.objectContaining({ detail: tr('error.server_multiple', { count: 3 }) }),
    )
  })

  it.each([
    ['業務コード付き', { error: { code: 'RANCH_SERVICE_UNAVAILABLE', message: '牧場は一時的に利用できません' } }],
    ['業務コード無し', {}],
  ])('AC7 %s 5xx を呼び出し側が handleApiError に渡してもトーストは合計1回', async (_name, body) => {
    external.fetch.mockResolvedValue(json(body, 503))
    const { handleApiError } = useErrorHandler()
    try {
      await useApi()('/api/v1/me/ranch')
      expect.unreachable('5xx は呼び出し元へ例外を返す')
    } catch (error) {
      handleApiError(error)
    }

    await vi.advanceTimersByTimeAsync(500)
    expect(external.toastAdd).toHaveBeenCalledTimes(1)
    expect(external.captureQuiet).toHaveBeenCalledTimes(1)
  })
})
