// @vitest-environment happy-dom
/**
 * 戦役 Z-A PR5 AC9: 運営アカウントに AdminSettingsHub（テナントの管理設定の集約ハブ）の導線を出さない。
 *
 * 運営はテナントの FAQ・公開設定・名前開示を触らない（マスター決定 2026-11-26/27）。PR5 以後 BE は
 * /me/permissions で "SYSTEM_ADMIN" を返さないので、roleName==='SYSTEM_ADMIN' のときだけ正準 API
 * （getAdminActionRequired）で兼任 DEPUTY を確かめる分岐は死にコードとして削除する（設計書 §4 分類 (i)）。
 *
 *   ZA5-HUB-01: 所属の無い運営アカウント（roleName=null, scopeAdmin=false）にはリンクを 1 本も出さず、正準 API も呼ばない
 *   ZA5-HUB-02: 旧応答 roleName="SYSTEM_ADMIN" でも正準 API を呼ばず、リンクを出さない（SYSTEM_ADMIN 専用の救済分岐を持たない）
 *   ZA5-HUB-03（回帰）: ADMIN・DEPUTY_ADMIN は従来どおり、MEMBER・SUPPORTER は出さない
 *   ZA5-HUB-04: AdminSettingsHub.vue のコードに SYSTEM_ADMIN の文字列判定が残っていない
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import PrimeVue from 'primevue/config'
import en from '~/locales/en/common.json'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AdminSettingsHub from './AdminSettingsHub.vue'

const state = vi.hoisted(() => ({
  role: 'ADMIN' as string | null,
  scopeAdmin: false,
  permissions: vi.fn(),
  loadFlags: vi.fn(),
  probe: vi.fn(),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref(state.role),
  isScopeAdmin: ref(state.scopeAdmin),
  loadPermissions: state.permissions,
}))
mockNuxtImport('useFeatureFlagStore', () => () => ({
  publicLoaded: true,
  isEnabled: () => true,
  loadPublicFlags: state.loadFlags,
}))
mockNuxtImport('useScopeTabApi', () => () => ({ getAdminActionRequired: state.probe }))

async function mountHub(scopeType: 'team' | 'organization') {
  const router = createRouter({ history: createMemoryHistory(), routes: [] })
  const wrapper = mount(AdminSettingsHub, {
    props: { scopeType, slug: 'sample' },
    global: {
      plugins: [
        router,
        PrimeVue,
        createI18n({ legacy: false, locale: 'en', messages: { ja: {}, en, zh: {}, ko: {}, es: {}, de: {} } }),
      ],
      stubs: { NuxtLink: RouterLink, PageHeader: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('AdminSettingsHub — 戦役Z-A PR5 AC9', () => {
  beforeEach(() => {
    state.role = 'ADMIN'
    state.scopeAdmin = false
    state.permissions.mockReset().mockResolvedValue({ ok: true })
    state.loadFlags.mockReset().mockResolvedValue(undefined)
    // 兼任 DEPUTY を「確認できた」応答。呼ばれたら救済分岐が残っている証拠になる。
    state.probe.mockReset().mockResolvedValue({})
  })

  for (const scopeType of ['team', 'organization'] as const) {
    it(`ZA5-HUB-01: 所属の無い運営アカウントには ${scopeType} の設定リンクを出さず正準APIも呼ばない`, async () => {
      state.role = null
      const wrapper = await mountHub(scopeType)
      expect(wrapper.findAll('a')).toHaveLength(0)
      expect(state.probe).not.toHaveBeenCalled()
    })

    it(`ZA5-HUB-02: 旧応答 roleName="SYSTEM_ADMIN" でも ${scopeType} の正準APIを呼ばずリンクを出さない`, async () => {
      state.role = 'SYSTEM_ADMIN'
      const wrapper = await mountHub(scopeType)
      expect(state.probe).not.toHaveBeenCalled()
      expect(wrapper.findAll('a')).toHaveLength(0)
    })

    it.each([
      ['ADMIN', true, true],
      ['DEPUTY_ADMIN', false, true],
      ['MEMBER', false, false],
      ['SUPPORTER', false, false],
    ] as const)(`ZA5-HUB-03（回帰）: ${scopeType} の %s（scopeAdmin=%s）の公開設定リンク=%s`, async (role, scopeAdmin, shown) => {
      state.role = role
      state.scopeAdmin = scopeAdmin
      const wrapper = await mountHub(scopeType)
      expect(wrapper.find('a[href$="/public-settings"]').exists()).toBe(shown)
      expect(wrapper.find('a[href$="/faq-settings"]').exists()).toBe(role === 'ADMIN')
      expect(state.probe).not.toHaveBeenCalled()
    })
  }

  it('ZA5-HUB-04: AdminSettingsHub.vue のコードに SYSTEM_ADMIN の文字列判定が残っていない（コメントを除く）', () => {
    const src = readFileSync(resolve(process.cwd(), 'app/components/admin/AdminSettingsHub.vue'), 'utf-8')
    const code = src
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, '$1')
    expect(code).not.toMatch(/(['"`])SYSTEM_ADMIN\1/)
  })
})
