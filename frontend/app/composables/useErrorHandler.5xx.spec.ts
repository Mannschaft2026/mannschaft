// @vitest-environment happy-dom
// CMP-261125-1951: 5xx のエラートーストは useApi の onResponseError に一元化する。
// handleApiError は 5xx（statusCode / status / response.status のどれでも）を受けたらトーストを出さず、
// エラーレポート（captureQuiet）だけを残す。4xx・ネットワーク断・ENTITLEMENT_003・team-loyalty は従来どおり。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useErrorHandler } from './useErrorHandler'

const notifyError = vi.fn()
const captureQuiet = vi.fn()
const t = (key: string) => key
const te = () => false

mockNuxtImport('useI18n', () => () => ({ t, te }))
mockNuxtImport('useNotification', () => () => ({ error: notifyError }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet }))

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('useI18n', () => ({ t, te }))
  vi.stubGlobal('useNotification', () => ({ error: notifyError }))
  vi.stubGlobal('useErrorReport', () => ({ captureQuiet }))
})
afterEach(() => vi.unstubAllGlobals())

describe('CMP-261125-1951 handleApiError は 5xx でトーストを出さない', () => {
  describe('AC1 業務コードの無い 5xx', () => {
    it.each([
      ['statusCode', { statusCode: 500 }],
      ['status', { status: 503 }],
      ['response.status', { response: { status: 502 } }],
    ])('%s 形の 5xx では notification.error を呼ばない', (_shape, error) => {
      useErrorHandler().handleApiError(error)
      expect(notifyError).not.toHaveBeenCalled()
    })
  })

  describe('AC2 業務コード付きの 5xx', () => {
    it.each([
      ['status', { status: 503, data: { error: { code: 'X', message: '固有文言' } } }],
      ['statusCode', { statusCode: 503, data: { error: { code: 'X', message: '固有文言' } } }],
      [
        'response.status',
        { response: { status: 500 }, data: { error: { code: 'COMMON_999', message: '固有文言' } } },
      ],
    ])('%s 形の業務コード付き 5xx でも notification.error を呼ばない', (_shape, error) => {
      useErrorHandler().handleApiError(error)
      expect(notifyError).not.toHaveBeenCalled()
    })

    it('context が team-loyalty でも 5xx は通知しない', () => {
      useErrorHandler().handleApiError(
        { statusCode: 503, data: { error: { code: 'LOYALTY_012' } } },
        'team-loyalty',
      )
      expect(notifyError).not.toHaveBeenCalled()
    })
  })

  describe('AC3 5xx でもエラーレポートは送る', () => {
    it.each([
      ['statusCode', { statusCode: 500 }],
      ['status', { status: 503, data: { error: { code: 'X', message: '固有文言' } } }],
      ['response.status', { response: { status: 502 } }],
    ])('%s 形の 5xx で captureQuiet を1回呼ぶ', (_shape, error) => {
      useErrorHandler().handleApiError(error, 'ranch')
      expect(captureQuiet).toHaveBeenCalledExactlyOnceWith(error, { context: 'ranch' })
    })
  })
})

describe('CMP-261125-1951 AC4 回帰: 5xx 以外は従来どおり', () => {
  it('4xx の業務コードは BE message で通知する', () => {
    useErrorHandler().handleApiError({
      statusCode: 409,
      data: { error: { code: 'COMMON_001', message: '具体的なBE理由' } },
    })
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', '具体的なBE理由')
  })

  it('コードの無い 4xx は不明エラーで通知する', () => {
    useErrorHandler().handleApiError({ statusCode: 404 })
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', 'error.unknown')
  })

  it.each([
    ['status なしのオブジェクト', {}],
    ['TypeError（fetch failed）', new TypeError('fetch failed')],
  ])('ネットワーク断（%s）は不明エラーで通知する', (_name, error) => {
    useErrorHandler().handleApiError(error)
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', 'error.unknown')
    expect(captureQuiet).toHaveBeenCalledTimes(1)
  })

  it('ENTITLEMENT_003 は通知しない', () => {
    useErrorHandler().handleApiError({ statusCode: 402, data: { error: { code: 'ENTITLEMENT_003' } } })
    expect(notifyError).not.toHaveBeenCalled()
    expect(captureQuiet).toHaveBeenCalledTimes(1)
  })

  it('context が team-loyalty の 4xx は領域の文言で通知する', () => {
    useErrorHandler().handleApiError(
      { statusCode: 409, data: { error: { code: 'COMMON_003', message: 'BE理由' } } },
      'team-loyalty',
    )
    expect(notifyError).toHaveBeenCalledExactlyOnceWith('dialog.error', 'loyalty.error.changed')
  })
})
