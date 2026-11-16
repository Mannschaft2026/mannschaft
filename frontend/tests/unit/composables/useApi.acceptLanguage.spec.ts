import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * CMP-261113-1507 AC-15: useApi 経由のリクエストに現在の UI 言語（$i18n.locale）が
 * Accept-Language として付く。
 *
 * BE は未ログイン時に Accept-Language でエラー文言の言語を決める（ログイン済みは users.locale 優先）。
 * FE が送らないとブラウザ既定の Accept-Language（SSR ではサーバー側の値）に倒れ、
 * 画面の言語とエラー文言の言語が食い違う。未ログイン（トークン無し）でも付くことを固定する。
 */

type HookOptions = { headers?: HeadersInit } & Record<string, unknown>
type Hooks = { onRequest: (ctx: { options: HookOptions }) => void }

const { captured } = vi.hoisted(() => ({ captured: { hooks: null as unknown } }))

vi.mock('ofetch', () => {
  const fn = Object.assign(vi.fn(), {
    create: (opts: unknown) => {
      captured.hooks = opts
      return vi.fn()
    },
  })
  return { ofetch: fn }
})

const { useApi } = await import('~/composables/useApi')

type Code = 'ja' | 'en' | 'zh' | 'ko' | 'es' | 'de'
type I18nLike = { locale: { value: string }, setLocale: (c: Code) => Promise<void> }

function i18n(): I18nLike {
  return (useNuxtApp() as unknown as { $i18n: I18nLike }).$i18n
}

function requestHeaders(): Headers {
  useApi()
  const hooks = captured.hooks as Hooks
  const options: HookOptions = {}
  hooks.onRequest({ options })
  return new Headers(options.headers)
}

describe('useApi Accept-Language（AC-15）', () => {
  let original = 'ja'

  beforeEach(() => {
    captured.hooks = null
    original = i18n().locale.value
    useAuthStore().accessToken = null
  })

  afterEach(async () => {
    await i18n().setLocale(original as Code)
  })

  it.each(['en', 'de', 'ja'] as const)('AC-15: 未ログインでも UI 言語 %s が Accept-Language として付く', async (code) => {
    await i18n().setLocale(code)
    expect(requestHeaders().get('Accept-Language')).toBe(code)
  })

  it('AC-15: ログイン済み（Authorization 付与時）でも UI 言語が Accept-Language として付く', async () => {
    await i18n().setLocale('ko')
    useAuthStore().accessToken = 'dummy-token'
    const headers = requestHeaders()
    expect(headers.get('Authorization')).toBe('Bearer dummy-token')
    expect(headers.get('Accept-Language')).toBe('ko')
  })

  it('AC-15: 呼び出し側が明示した Accept-Language は上書きしない', async () => {
    await i18n().setLocale('en')
    useApi()
    const hooks = captured.hooks as Hooks
    const options: HookOptions = { headers: { 'Accept-Language': 'es' } }
    hooks.onRequest({ options })
    expect(new Headers(options.headers).get('Accept-Language')).toBe('es')
  })
})
