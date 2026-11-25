// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import TeamAccessDeniedState from './TeamAccessDeniedState.vue'

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

function mountState(kind: 'forbidden' | 'notFound') {
  return mount(TeamAccessDeniedState, {
    props: { kind },
    global: { stubs: { NuxtLink: { props: ['to'], template: '<a :href="to" data-testid="home-link"><slot /></a>' } } },
  })
}

describe('TeamAccessDeniedState（CMP-261124-0159 PR-F1 / AC-0159-86・87）', () => {
  it('forbidden は「メンバー限定など公開範囲の外」の理由文言を出す', () => {
    const wrapper = mountState('forbidden')
    expect(wrapper.text()).toContain('team.access.forbiddenTitle')
    expect(wrapper.text()).toContain('team.access.forbiddenBody')
    expect(wrapper.text()).not.toContain('team.access.notFoundTitle')
    expect(wrapper.find('[data-testid="team-access-denied"]').attributes('data-kind')).toBe('forbidden')
  })

  it('notFound は「見つかりません」の文言を出す', () => {
    const wrapper = mountState('notFound')
    expect(wrapper.text()).toContain('team.access.notFoundTitle')
    expect(wrapper.text()).toContain('team.access.notFoundBody')
    expect(wrapper.text()).not.toContain('team.access.forbiddenTitle')
    expect(wrapper.find('[data-testid="team-access-denied"]').attributes('data-kind')).toBe('notFound')
  })

  it('どちらもホームへ戻る導線を持つ', () => {
    for (const kind of ['forbidden', 'notFound'] as const) {
      const wrapper = mountState(kind)
      const link = wrapper.find('[data-testid="home-link"]')
      expect(link.exists()).toBe(true)
      expect(link.attributes('href')).toBe('/dashboard')
      expect(wrapper.text()).toContain('team.access.backToHome')
    }
  })
})
