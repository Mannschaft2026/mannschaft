/**
 * マイカルテの写真（第2回検分・既存の不具合）: BE の ChartPhotoResponse は `signedUrl` を返すのに、ページが
 * `photoUrl` を読んでいたため写真が出なかった。通常は signedUrl の写真を出し、代理（サポート閲覧）中で
 * signedUrl が null のときは画像を出さず「写真は非表示」の案内を出すことを固定する。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { Chart, ChartPhoto } from '~/types/chart'

const mocks = vi.hoisted(() => ({
  listMyCharts: vi.fn(),
  get: vi.fn(),
}))
mockNuxtImport('useChartApi', () => () => ({ listMyCharts: mocks.listMyCharts, get: mocks.get }))

const stubs = {
  PageHeader: true,
  PageLoading: true,
  Badge: true,
  Dialog: { template: '<div class="dialog-stub"><slot /></div>' },
}

function chart(photos: ChartPhoto[]): Chart {
  return {
    id: 7, teamId: 3, clientUserId: 42, clientName: '本人', staffUserId: 9, staffName: '担当',
    visitDate: '2026-11-20', status: 'FINALIZED', chiefComplaint: null, notes: null,
    nextVisitRecommendation: null, isPinned: false,
    sections: { bodyChart: false, colorRecipe: false, beforeAfter: true, questionnaire: false, consent: false },
    photos, version: 1, createdAt: '2026-11-20T00:00:00Z', updatedAt: '2026-11-20T00:00:00Z',
  }
}

async function openDetail(photos: ChartPhoto[]) {
  mocks.listMyCharts.mockResolvedValue({ data: [chart([])], meta: { total: 1 } })
  mocks.get.mockResolvedValue({ data: chart(photos) })
  const page = await import('~/pages/my/charts.vue')
  const wrapper = await mountSuspended(page.default, { global: { stubs } })
  await flushPromises()
  await wrapper.find('.cursor-pointer').trigger('click')
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('マイカルテの写真', () => {
  it('通常（代理中でない）は BE の signedUrl の写真を出す', async () => {
    const wrapper = await openDetail([
      { id: 1, photoType: 'BEFORE', signedUrl: 'https://signed.example/b.jpg', note: 'ビフォー' },
    ])
    const img = wrapper.find('.dialog-stub img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://signed.example/b.jpg')
    expect(wrapper.text()).not.toContain('Photos are not shown during support viewing')
  })

  it('代理中で signedUrl が null なら画像を出さず非表示の案内を出す', async () => {
    const wrapper = await openDetail([{ id: 1, photoType: 'BEFORE', signedUrl: null }])
    expect(wrapper.find('.dialog-stub img').exists()).toBe(false)
    expect(wrapper.text()).toContain('Photos are not shown during support viewing')
  })
})
