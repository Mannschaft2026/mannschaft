/**
 * F10.9 サポート閲覧中のカルテ写真: BE が署名 URL を返さない（photoUrl が null）ときは
 * 画像を出さず「代理閲覧中は写真を表示しません」を出す。署名 URL があるときは従来どおり画像を出す。
 */
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import BeforeAfterPhoto from '~/components/chart/BeforeAfterPhoto.vue'
import type { ChartPhoto } from '~/types/chart'

function photo(overrides: Partial<ChartPhoto>): ChartPhoto {
  return { id: 1, photoType: 'BEFORE', photoUrl: 'https://signed.example/p.jpg', ...overrides } as ChartPhoto
}

describe('BeforeAfterPhoto', () => {
  it('署名 URL があれば画像を出す', async () => {
    const wrapper = await mountSuspended(BeforeAfterPhoto, {
      props: { chartId: 1, photos: [photo({})] },
    })
    expect(wrapper.find('img').exists()).toBe(true)
    expect(wrapper.find('img').attributes('src')).toBe('https://signed.example/p.jpg')
  })

  it('署名 URL が null（代理閲覧中）なら画像を出さず非表示の案内を出す', async () => {
    const wrapper = await mountSuspended(BeforeAfterPhoto, {
      props: { chartId: 1, photos: [photo({ photoUrl: null })] },
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('Photos are not shown during support viewing')
  })
})
