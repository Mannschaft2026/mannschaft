/**
 * F10.9 サポート閲覧中のカルテ写真: BE が署名 URL を返さない（signedUrl が null）ときは
 * 画像を出さず「代理閲覧中は写真を表示しません」を出す。署名 URL があるときは従来どおり画像を出す。
 *
 * 第2回検分: BE の ChartPhotoResponse は `signedUrl` を返すのに FE が `photoUrl` を読んでいたため、
 * 代理中でなくても写真が出なかった。BE の項目名（生成型）に合わせたことを固定する。
 */
import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import BeforeAfterPhoto from '~/components/chart/BeforeAfterPhoto.vue'
import type { ChartPhoto } from '~/types/chart'

function photo(overrides: Partial<ChartPhoto>): ChartPhoto {
  return {
    id: 1,
    photoType: 'BEFORE',
    signedUrl: 'https://signed.example/p.jpg',
    signedUrlExpiresAt: '2026-11-27T10:00:00Z',
    ...overrides,
  }
}

describe('BeforeAfterPhoto', () => {
  it('BE の応答どおり signedUrl があれば（代理中でない通常時）画像を出す', async () => {
    const wrapper = await mountSuspended(BeforeAfterPhoto, {
      props: { chartId: 1, photos: [photo({})] },
    })
    expect(wrapper.find('img').exists()).toBe(true)
    expect(wrapper.find('img').attributes('src')).toBe('https://signed.example/p.jpg')
    expect(wrapper.text()).not.toContain('Photos are not shown during support viewing')
  })

  it('アフターの写真も signedUrl で出す', async () => {
    const wrapper = await mountSuspended(BeforeAfterPhoto, {
      props: { chartId: 1, photos: [photo({ id: 2, photoType: 'AFTER', signedUrl: 'https://signed.example/a.jpg' })] },
    })
    expect(wrapper.find('img').attributes('src')).toBe('https://signed.example/a.jpg')
  })

  it('signedUrl が null（代理閲覧中）なら画像を出さず非表示の案内を出す', async () => {
    const wrapper = await mountSuspended(BeforeAfterPhoto, {
      props: { chartId: 1, photos: [photo({ signedUrl: null, signedUrlExpiresAt: undefined })] },
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('Photos are not shown during support viewing')
  })
})
