// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

/**
 * CommerceDisclosureModal の UT。
 *   CDM-001: Dialog の見出しが landing.legal.commerce.title、data-testid が付く
 *   CDM-002: 本文は CommerceDisclosureContent を見出し非表示（h1 重複なし）で描画する
 *   CDM-003: 閉じるボタンで visible が false になる
 *   CDM-004: anchor="contact" 指定で開いたとき contact 項目へ scrollIntoView する（未指定ならしない）
 *   CDM-005: モーダル内の contact 項目は id="contact" を持たない（ページと同時に DOM にあっても id 重複しない）
 *   CDM-006: ページ単独表示の Content は従来どおり h1 と id="contact" を持つ
 */

const items = {
  seller: { label: '販売業者', value: 'A' },
  contact: { label: 'お問い合わせ', value: 'B' },
}
mockNuxtImport('useI18n', () => () => ({
  t: (key: string) => key,
  tm: () => items,
  rt: (v: unknown) => v,
}))

const Modal = (await import('~/components/legal/CommerceDisclosureModal.vue')).default
const Content = (await import('~/components/legal/CommerceDisclosureContent.vue')).default

function mountModal(props: Record<string, unknown> = {}) {
  return mount(Modal, {
    attachTo: document.body,
    props: { visible: true, ...props },
    global: { plugins: [PrimeVue] },
  })
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('CommerceDisclosureModal', () => {
  it('CDM-001: 見出しと data-testid', async () => {
    const w = mountModal()
    await flushPromises()
    const dialog = document.body.querySelector('[data-testid="commerce-disclosure-modal"]')
    expect(dialog).not.toBeNull()
    expect(dialog!.textContent).toContain('landing.legal.commerce.title')
    w.unmount()
  })

  it('CDM-002: 本文を描画し h1 は出さない', async () => {
    const w = mountModal()
    await flushPromises()
    const dialog = document.body.querySelector('[data-testid="commerce-disclosure-modal"]')!
    expect(dialog.textContent).toContain('販売業者')
    expect(dialog.querySelector('h1')).toBeNull()
    w.unmount()
  })

  it('CDM-003: 閉じるボタンで visible=false', async () => {
    const w = mountModal()
    await flushPromises()
    const btn = Array.from(document.body.querySelectorAll('button')).find(b => b.textContent?.includes('button.close'))
    expect(btn).toBeDefined()
    btn!.click()
    await flushPromises()
    expect(w.emitted('update:visible')?.at(-1)).toEqual([false])
    w.unmount()
  })

  it('CDM-004: anchor=contact で contact 項目へスクロールする', async () => {
    const spy = vi.fn()
    Element.prototype.scrollIntoView = spy
    const w = mountModal({ anchor: 'contact' })
    await flushPromises()
    await vi.waitFor(() => expect(spy).toHaveBeenCalled())
    const target = spy.mock.contexts[0] as Element
    expect(target.getAttribute('data-section')).toBe('contact')
    w.unmount()
  })

  it('CDM-004b: anchor 未指定ではスクロールしない', async () => {
    const spy = vi.fn()
    Element.prototype.scrollIntoView = spy
    const w = mountModal()
    await flushPromises()
    expect(spy).not.toHaveBeenCalled()
    w.unmount()
  })

  it('CDM-005: モーダル内に id="contact" が無い', async () => {
    const w = mountModal()
    await flushPromises()
    expect(document.body.querySelector('#contact')).toBeNull()
    w.unmount()
  })
})

describe('CommerceDisclosureContent', () => {
  it('CDM-006: 単独表示では h1 と id="contact" を持つ', () => {
    const w = mount(Content)
    expect(w.find('h1').exists()).toBe(true)
    expect(w.find('#contact').exists()).toBe(true)
  })
})
