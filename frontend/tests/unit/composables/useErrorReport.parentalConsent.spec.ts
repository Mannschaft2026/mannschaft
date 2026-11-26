import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useErrorReport } from '~/composables/useErrorReport'

/**
 * CMP-261123-1124 AC4: 同意待ちでは 5xx の自動エラー報告（POST /api/v1/error-reports）を送らない。
 * 同 API は BE 同意ゲートの許可リスト外で 403 AUTH_070 になり、届かないのに「送信済み」と表示してしまうため。
 * 本人復元前（ppc=true のトークンのみ）も同様。useApi の 5xx 経路は errorReport.capture を呼ぶ。
 */
function jwtWith(payload: Record<string, unknown>): string {
  const b64url = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`
}

type AuthStub = {
  isPendingParentalConsent: boolean
  accessToken: string | null
  currentUser: { id: number } | null
  isAuthenticated: boolean
  loadFromStorage: () => void
}
// Nuxt 初期化（認証プラグイン）が最初に useAuthStore を引くため、宣言時点から有効なモックにしておく
let authStub: AuthStub = {
  isPendingParentalConsent: false,
  accessToken: null,
  currentUser: null,
  isAuthenticated: false,
  loadFromStorage: () => {},
}
mockNuxtImport('useAuthStore', () => () => authStub)

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockResolvedValue({})
  vi.stubGlobal('$fetch', fetchMock)
  authStub = { ...authStub, isPendingParentalConsent: false, accessToken: null, currentUser: null }
  useErrorReport().close()
})

describe('useErrorReport と同意待ち（CMP-261123-1124 AC4）', () => {
  it('同意待ち: 5xx の capture は送信せず、送信済み表示にもしない', async () => {
    authStub = { ...authStub, isPendingParentalConsent: true, accessToken: null, currentUser: { id: 7 } }
    const report = useErrorReport()

    report.capture(new Error('HTTP 500'), { apiUrl: '/api/v1/parental-consent/invitations', statusCode: 500 })
    await Promise.resolve()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(report.state.value.visible).toBe(false)
    expect(report.state.value.submitted).toBe(false)
  })

  it('本人復元前（ppc=true のトークンのみ）でも送信しない', async () => {
    authStub = {
      ...authStub,
      isPendingParentalConsent: false,
      accessToken: jwtWith({ sub: '7', ppc: true, exp: Math.floor(Date.now() / 1000) + 900 }),
      currentUser: null,
    }
    const report = useErrorReport()

    report.capture(new Error('HTTP 502'), { statusCode: 502 })
    report.captureQuiet(new Error('widget failed'), { context: 'x' })
    await report.submitComment('追加コメント')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(report.state.value.commentSent).toBe(false)
  })

  it('同意待ち: captureQuiet と追加コメントも送信しない', async () => {
    authStub = { ...authStub, isPendingParentalConsent: true, accessToken: 'x', currentUser: { id: 7 } }
    const report = useErrorReport()

    report.captureQuiet(new Error('widget failed'))
    await report.submitComment('追加コメント')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(report.state.value.commentSent).toBe(false)
  })

  it('AC7: 一般ユーザーは従来どおり自動送信する（回帰）', async () => {
    authStub = { ...authStub, isPendingParentalConsent: false, accessToken: jwtWith({ sub: '7', ppc: false }), currentUser: { id: 7 } }
    const report = useErrorReport()

    report.capture(new Error('HTTP 500'), { statusCode: 500 })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    expect(String(fetchMock.mock.calls[0]![0])).toContain('/api/v1/error-reports')
  })
})
