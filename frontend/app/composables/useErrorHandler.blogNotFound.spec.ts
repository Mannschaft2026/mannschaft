// @vitest-environment happy-dom
// CMP-261008-1701: ブログ記事の不在（BE の CMS_001）と不正 query（FE 合成）のトースト文言が全ロケールで同一であること、
// および FE 合成エラーだけが障害報告の対象から外れることを、実際の useErrorHandler で検証する。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createBlogPostNotFoundError } from '~/utils/blogPostReadQuery'
import { useErrorHandler } from './useErrorHandler'

// nuxt-i18n の変換で JSON import がメッセージ AST 化されるため、生の JSON を fs で読む。
function raw(locale: string): { error: Record<string, string> } {
  return JSON.parse(readFileSync(resolve(process.cwd(), `app/locales/${locale}/common.json`), 'utf-8'))
}
const messages = { ja: raw('ja'), en: raw('en'), zh: raw('zh'), ko: raw('ko'), es: raw('es'), de: raw('de') }
type Locale = keyof typeof messages
const i18n = createI18n({ legacy: false, locale: 'ja', fallbackLocale: false, messages })
const notifyError = vi.fn()
const captureQuiet = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => i18n.global.t(key),
  te: (key: string) => i18n.global.te(key),
}))
mockNuxtImport('useNotification', () => () => ({ error: notifyError }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet }))

/** 実 BE の不在応答（Accept-Language で訳された message 付き）を模す。 */
function beNotFound(message: string) {
  return Object.assign(new Error('[GET] 404'), {
    statusCode: 404,
    data: { error: { code: 'CMS_001', message, fieldErrors: [] } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('useI18n', () => ({ t: (key: string) => i18n.global.t(key), te: (key: string) => i18n.global.te(key) }))
  vi.stubGlobal('useNotification', () => ({ error: notifyError }))
  vi.stubGlobal('useErrorReport', () => ({ captureQuiet }))
})

describe('ブログ不在トーストの全ロケール一致', () => {
  const beMessages: Record<Locale, string> = {
    ja: '記事が見つかりません',
    en: 'Post not found',
    zh: '未找到文章',
    ko: '게시글을 찾을 수 없습니다',
    es: 'Entrada no encontrada',
    de: 'Beitrag nicht gefunden',
  }

  for (const locale of Object.keys(messages) as Locale[]) {
    it(`${locale}: 実BE不在と不正queryでトースト文言が同一で、日本語直書きが混ざらない`, () => {
      i18n.global.locale.value = locale
      const { handleError } = useErrorHandler()

      handleError(beNotFound(beMessages[locale]))
      handleError(createBlogPostNotFoundError())

      expect(notifyError).toHaveBeenCalledTimes(2)
      const [, bodyFromBe] = notifyError.mock.calls[0] as [string, string]
      const [, bodyFromQuery] = notifyError.mock.calls[1] as [string, string]
      expect(bodyFromQuery).toBe(bodyFromBe)
      expect(bodyFromQuery).toBe(messages[locale].error.CMS_001)
      if (locale !== 'ja') expect(bodyFromQuery).not.toContain('記事')
    })
  }

  it('en: 合成エラーは日本語を含まない', () => {
    i18n.global.locale.value = 'en'
    useErrorHandler().handleError(createBlogPostNotFoundError())
    const [, body] = notifyError.mock.calls[0] as [string, string]
    expect(body).toBe('Blog post not found.')
  })
})

describe('障害報告（captureQuiet）の対象', () => {
  it('FE 合成の不正 query エラーは報告せず、不在トーストは出す', () => {
    useErrorHandler().handleError(createBlogPostNotFoundError())
    expect(captureQuiet).not.toHaveBeenCalled()
    expect(notifyError).toHaveBeenCalledTimes(1)
  })

  it('本物の API エラー（CMS_001 を含む）は従来どおり報告される', () => {
    const real = beNotFound('記事が見つかりません')
    useErrorHandler().handleError(real)
    expect(captureQuiet).toHaveBeenCalledTimes(1)
    expect(captureQuiet.mock.calls[0]?.[0]).toBe(real)
  })
})
