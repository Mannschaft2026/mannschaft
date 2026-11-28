// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// app/plugins/dayjs.ts が本番で行う拡張（テストでは plugin が走らないため）
dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * layouts/landing.vue フッターの特商法リンクはページ遷移ではなくモーダルを開く。
 *   LND-001: 「特定商取引法に基づく表記」は button[type=button]（NuxtLink ではない）でモーダルを開く
 *   LND-002: 「お問い合わせ」も button で同じモーダルを anchor=contact 付きで開く
 *   LND-003: /commerce-disclosure への NuxtLink がフッターに残らない
 *   LND-004: モーダルは初期状態で閉じている（SSR/ハイドレーション時に開いた状態を出さない）
 */

mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  locale: ref('ja'),
  setLocale: () => {},
  availableLocales: ['ja'],
}))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

const Layout = (await import('~/layouts/landing.vue')).default

const Modal = {
  name: 'CommerceDisclosureModal',
  props: { visible: Boolean, anchor: { type: String, default: undefined } },
  template: '<div data-testid="stub-modal" :data-visible="String(visible)" :data-anchor="anchor" />',
}

function mountLayout() {
  return mount(Layout, {
    global: {
      stubs: {
        NuxtLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' },
        Select: true,
        Button: true,
        CommerceDisclosureModal: Modal,
      },
    },
  })
}

describe('layouts/landing.vue 特商法モーダル', () => {
  it('LND-004: 初期は閉じている', () => {
    const w = mountLayout()
    expect(w.get('[data-testid="stub-modal"]').attributes('data-visible')).toBe('false')
  })

  it('LND-001: 特商法ボタンでモーダルが開く', async () => {
    const w = mountLayout()
    const btn = w.findAll('button[type="button"]').find(b => b.text() === 'landing.layout.footer_commerce')
    expect(btn).toBeDefined()
    await btn!.trigger('click')
    const m = w.get('[data-testid="stub-modal"]')
    expect(m.attributes('data-visible')).toBe('true')
    expect(m.attributes('data-anchor')).toBeUndefined()
  })

  it('LND-002: お問い合わせボタンは anchor=contact で開く', async () => {
    const w = mountLayout()
    const btn = w.findAll('button[type="button"]').find(b => b.text() === 'landing.layout.footer_contact')
    expect(btn).toBeDefined()
    await btn!.trigger('click')
    const m = w.get('[data-testid="stub-modal"]')
    expect(m.attributes('data-visible')).toBe('true')
    expect(m.attributes('data-anchor')).toBe('contact')
  })

  it('LND-003: /commerce-disclosure への遷移リンクが無い', () => {
    const w = mountLayout()
    expect(w.findAll('a').some(a => (a.attributes('data-to') ?? '').startsWith('/commerce-disclosure'))).toBe(false)
  })
})
