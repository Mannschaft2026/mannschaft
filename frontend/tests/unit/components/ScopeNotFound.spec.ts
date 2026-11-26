// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import ScopeNotFound from '~/components/ScopeNotFound.vue'
import enMessages from '~/locales/en/common.json'
import jaMessages from '~/locales/ja/common.json'

/**
 * CMP-261125-1108 非所属・権限なしのチーム／組織の子ページで出す共通の「見つかりません」表示。
 *
 *   - 見出し common.scopeNotFound.title、本文 common.scopeNotFound.body
 *   - ダッシュボードへ戻る導線（既存キー common.scopeShell.back_to_dashboard → /dashboard）
 *   - 再試行ボタンを持たない（5xx・通信断の汎用エラーとは別物）
 *   - 権限・所属を示唆する文言を含まない
 */

function createTestI18n(locale: 'ja' | 'en') {
  return createI18n({
    legacy: false,
    locale,
    missingWarn: false,
    fallbackWarn: false,
    messages: { ja: jaMessages, en: enMessages, zh: {}, ko: {}, es: {}, de: {} },
  })
}

const NuxtLinkStub = {
  props: ['to'],
  template: '<a :href="typeof to === \'string\' ? to : to?.path" v-bind="$attrs"><slot /></a>',
}

function mountNotFound(locale: 'ja' | 'en' = 'en') {
  const i18n = createTestI18n(locale)
  const wrapper = mount(ScopeNotFound, {
    global: {
      plugins: [i18n],
      stubs: { NuxtLink: NuxtLinkStub },
    },
  })
  return { wrapper, t: i18n.global.t }
}

describe('ScopeNotFound.vue（CMP-261125-1108）', () => {
  it('data-testid="scope-not-found" の領域に、見出しと本文を i18n キーから表示する', () => {
    const { wrapper, t } = mountNotFound('en')
    const title = t('common.scopeNotFound.title')
    const body = t('common.scopeNotFound.body')
    // キーが訳文へ解決されている（キー名そのままの表示ではない）
    expect(title).not.toBe('common.scopeNotFound.title')
    expect(body).not.toBe('common.scopeNotFound.body')

    const root = wrapper.find('[data-testid="scope-not-found"]')
    expect(root.exists()).toBe(true)
    expect(root.text()).toContain(title)
    expect(root.text()).toContain(body)
  })

  it('ja では見出しが「見つかりません」', () => {
    const { wrapper } = mountNotFound('ja')
    expect(wrapper.text()).toContain('見つかりません')
  })

  it('ダッシュボードへ戻るリンクを持つ（既存キー common.scopeShell.back_to_dashboard）', () => {
    const { wrapper } = mountNotFound('en')
    const link = wrapper.find('a[href="/dashboard"]')
    expect(link.exists()).toBe(true)
    expect(link.text()).toContain('Back to dashboard')
  })

  it('再試行ボタンを持たない', () => {
    const { wrapper } = mountNotFound('en')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('Retry')
    expect(wrapper.text()).not.toContain('Try again')
  })

  it('権限・所属を示唆する文言を含まない（汎用の取得失敗文言も流用しない）', () => {
    const { wrapper } = mountNotFound('en')
    expect(wrapper.text()).not.toMatch(/permission|member|access|belong/i)
    const ja = mountNotFound('ja').wrapper
    expect(ja.text()).not.toMatch(/権限|所属|メンバー/)
    expect(ja.text()).not.toContain('情報を取得できませんでした')
  })
})
