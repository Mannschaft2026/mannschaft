import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ShiftManageNotice from './ShiftManageNotice.vue'

/**
 * CMP-261125-2114 D2: シフト管理の権限告知。
 *   - 管理者向けは「シフトの管理」という画面上の名前と手順を示し、閉じられる。閉じた状態はチーム別に localStorage へ保存し、
 *     読み書きできない環境では毎回表示する（例外で画面を壊さない）
 *   - 権限のない副管理者向けは次の行動（管理者に権限グループで付けてもらう）が分かる文で、閉じられない
 */
const stubs = {
  NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  Message: {
    props: ['closable'],
    emits: ['close'],
    template: '<div><slot /><button v-if="closable" data-testid="close-btn" @click="$emit(\'close\')">x</button></div>',
  },
}

async function mountNotice(props: { variant: 'admin' | 'deputy', teamSlug?: string | null }) {
  const w = await mountSuspended(ShiftManageNotice, { props, global: { stubs } })
  await flushPromises()
  return w
}

describe('ShiftManageNotice', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('管理者向け: 権限グループ画面へのリンクがあり、英語の権限名（MANAGE_SHIFTS）を出さない', async () => {
    const w = await mountNotice({ variant: 'admin', teamSlug: 'team-a' })
    expect(w.find('[data-testid="shift-manage-notice-admin"]').exists()).toBe(true)
    expect(w.get('[data-testid="shift-manage-notice-link"]').attributes('href')).toBe('/teams/team-a/permission-groups')
    expect(w.text()).not.toContain('MANAGE_SHIFTS')
  })

  it('管理者向け: 閉じるとチーム別に保存され、再マウントしても出ない。別チームには出る', async () => {
    const w = await mountNotice({ variant: 'admin', teamSlug: 'team-a' })
    await w.get('[data-testid="close-btn"]').trigger('click')
    expect(w.find('[data-testid="shift-manage-notice-admin"]').exists()).toBe(false)
    expect(window.localStorage.getItem('shift-manage-notice-dismissed:team-a')).toBe('1')

    const again = await mountNotice({ variant: 'admin', teamSlug: 'team-a' })
    expect(again.find('[data-testid="shift-manage-notice-admin"]').exists()).toBe(false)
    const other = await mountNotice({ variant: 'admin', teamSlug: 'team-b' })
    expect(other.find('[data-testid="shift-manage-notice-admin"]').exists()).toBe(true)
  })

  it('管理者向け: localStorage を読めない環境では表示する', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const w = await mountNotice({ variant: 'admin', teamSlug: 'team-a' })
    expect(w.find('[data-testid="shift-manage-notice-admin"]').exists()).toBe(true)
  })

  it('管理者向け: localStorage に書けなくても閉じられる（例外を出さない）', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    const w = await mountNotice({ variant: 'admin', teamSlug: 'team-a' })
    await w.get('[data-testid="close-btn"]').trigger('click')
    expect(w.find('[data-testid="shift-manage-notice-admin"]').exists()).toBe(false)
  })

  it('権限のない副管理者向け: 閉じられず、管理者に権限グループで付けてもらう旨の案内を出す', async () => {
    const w = await mountNotice({ variant: 'deputy' })
    expect(w.find('[data-testid="close-btn"]').exists()).toBe(false)
    expect(w.get('[data-testid="shift-manage-notice-deputy"]').text()).toMatch(/管理者|administrator|Administrator/)
    expect(w.text()).not.toContain('MANAGE_SHIFTS')
  })
})
