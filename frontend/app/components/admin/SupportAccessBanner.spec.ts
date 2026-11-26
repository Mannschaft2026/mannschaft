// @vitest-environment nuxt
// 戦役Z-B 試練 S16: サポート閲覧中バナー（対象者・残り時間・「閲覧のみ」・終了ボタン）。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import SupportAccessBanner from '~/components/admin/SupportAccessBanner.vue'
import jaCommon from '~/locales/ja/common.json'

const state = vi.hoisted(() => ({
  store: null as null | {
    isActive: boolean
    targetDisplayName: string
    remainingSeconds: number
    endSession: () => Promise<void>
  },
}))
mockNuxtImport('useSupportAccessStore', () => () => state.store)

type Dict = Record<string, unknown>
function ja(path: string): string {
  const value = path.split('.').reduce<unknown>((acc, key) => (acc as Dict | undefined)?.[key], jaCommon as Dict)
  expect(typeof value, `ja/common.json に ${path} が無い`).toBe('string')
  return value as string
}

const wrappers: VueWrapper[] = []
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
  document.body.innerHTML = ''
})

async function mountBanner(active: boolean) {
  state.store = reactive({
    isActive: active,
    targetDisplayName: '山田 花子',
    remainingSeconds: 5 * 60 * 60 + 30 * 60,
    endSession: vi.fn().mockResolvedValue(undefined),
  })
  const wrapper = await mountSuspended(SupportAccessBanner, { attachTo: document.body })
  wrappers.push(wrapper)
  return wrapper
}

describe('戦役Z-B S16 SupportAccessBanner', () => {
  it('S16 閲覧中は対象者・「閲覧のみ」・残り時間を表示する', async () => {
    await mountBanner(true)
    const text = document.body.textContent ?? ''
    expect(text).toContain('山田 花子')
    expect(text).toContain(ja('supportAccess.banner.readOnly'))
    expect(text).toMatch(/5/)
    expect(text).toMatch(/30/)
    expect(text).not.toContain('supportAccess.')
  })

  it('S16 終了ボタンで閲覧を終える', async () => {
    await mountBanner(true)
    const button = Array.from(document.body.querySelectorAll('button'))
      .find(b => (b.textContent ?? '').includes(ja('supportAccess.banner.end')))
    expect(button, '終了ボタンが無い').toBeTruthy()
    button!.click()
    expect(state.store!.endSession).toHaveBeenCalledTimes(1)
  })

  it('S16 閲覧中でなければ何も表示しない', async () => {
    await mountBanner(false)
    expect(document.body.textContent ?? '').not.toContain('山田 花子')
  })
})
