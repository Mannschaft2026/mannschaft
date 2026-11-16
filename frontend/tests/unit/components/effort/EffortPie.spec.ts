/**
 * F06.4.1 AC-18: BaseChart type='pie' と EffortStatsView。
 * 未記録と 0 の区別・分母 0 で円を描かない・凡例+数値表・FE で再集計しない。
 */
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { ChartData } from 'chart.js'
import BaseChart from '~/components/charts/BaseChart.vue'
import EffortStatsView from '~/components/effort/EffortStatsView.vue'
import type { EffortStatsResult } from '~/composables/useEffortRecordApi'

// happy-dom には canvas 2D コンテキストが無いため最小スタブを注入する（BaseChart.spec と同じ作法）。
beforeAll(() => {
  const proto = globalThis.HTMLCanvasElement?.prototype
  if (proto && typeof proto.getContext !== 'function') {
    Object.defineProperty(proto, 'getContext', {
      configurable: true,
      value: () =>
        new Proxy({}, {
          get: (_t, prop) => {
            if (prop === 'canvas') return { width: 0, height: 0 }
            if (prop === 'measureText') return () => ({ width: 0 })
            if (prop === 'getImageData') return () => ({ data: [] })
            if (prop === 'createLinearGradient' || prop === 'createRadialGradient') return () => ({ addColorStop: () => {} })
            return () => {}
          },
        }),
    })
  }
})

// mountSuspended の初回は Nuxt 環境の冷間コンパイルで既定 5 秒を超えうる（BaseChart.spec も同様）。
vi.setConfig({ testTimeout: 120000 })

const global = { stubs: { ClientOnly: { template: '<div><slot /></div>' } } }

const pie = (data: Array<number | null>): ChartData<'pie', Array<number | null>> => ({
  labels: data.map((_, i) => `c${i}`),
  datasets: [{ data }],
})

describe('BaseChart type=pie', () => {
  it('正の値があれば円を描く（0 のカテゴリが混じっても可）', async () => {
    const w = await mountSuspended(BaseChart, { props: { type: 'pie', data: pie([30, 0, 60]) }, global })
    expect(w.find('canvas').exists()).toBe(true)
  })

  it('分母 0（全て 0）は円を描かない', async () => {
    const w = await mountSuspended(BaseChart, { props: { type: 'pie', data: pie([0, 0]), emptyMessage: 'empty-pie' }, global })
    expect(w.find('canvas').exists()).toBe(false)
    expect(w.text()).toContain('empty-pie')
  })

  it('全 null（未記録）も円を描かない', async () => {
    const w = await mountSuspended(BaseChart, { props: { type: 'pie', data: pie([null, null]) }, global })
    expect(w.find('canvas').exists()).toBe(false)
  })

  it('bar の全 0 は従来どおり描く（既存挙動を壊さない）', async () => {
    const w = await mountSuspended(BaseChart, {
      props: { type: 'bar', data: { labels: ['a'], datasets: [{ data: [0] }] } satisfies ChartData<'bar'> },
      global,
    })
    expect(w.find('canvas').exists()).toBe(true)
  })
})

const pieStats = (over: Partial<EffortStatsResult> = {}): EffortStatsResult => ({
  chart: 'PIE', grain: 'DAY', unit: 'MINUTE', dateFrom: '2026-11-01', dateTo: '2026-11-03', buckets: [],
  categories: [
    { key: 'math', label: '科目A', value: '30.00', sampleCount: 2 },
    { key: 'sci', label: '科目B', value: '0.00', sampleCount: 1 },
    { key: '__unspecified__', label: 'x', value: '60.00', sampleCount: 1 },
  ],
  total: '90.00', totalSampleCount: 4, emptyReason: undefined, ...over,
})

describe('EffortStatsView（AC-18）', () => {
  it('凡例（色付きカテゴリ）と数値表を併設し、BE の値と合計をそのまま表示する（再集計しない）', async () => {
    const w = await mountSuspended(EffortStatsView, { props: { stats: pieStats() }, global })
    expect(w.find('canvas').exists()).toBe(true)
    const rows = w.findAll('[data-testid="effort-stats-row"]')
    expect(rows).toHaveLength(3)
    expect(rows[0]!.text()).toContain('30.00')
    expect(rows[0]!.text()).toContain('33.3%')
    expect(rows[1]!.text()).toContain('0.00') // 明示 0 は 0.0% として残る
    expect(rows[1]!.text()).toContain('0.0%')
    expect(rows[2]!.text()).toContain('66.7%')
    expect(w.get('[data-testid="effort-stats-total"]').text()).toContain('90.00')
  })

  it('ZERO_TOTAL は円を描かず、0 合計を数値表に残す（架空の 100% を作らない）', async () => {
    const w = await mountSuspended(EffortStatsView, {
      props: { stats: pieStats({ emptyReason: 'ZERO_TOTAL', total: '0.00', categories: [{ key: 'math', label: 'A', value: '0.00', sampleCount: 1 }] }) },
      global,
    })
    expect(w.find('canvas').exists()).toBe(false)
    expect(w.find('[data-testid="effort-stats-zerototal"]').exists()).toBe(true)
    expect(w.get('[data-testid="effort-stats-total"]').text()).toContain('0.00')
    expect(w.get('[data-testid="effort-stats-row"]').text()).toContain('-') // 分母 0 では割合を出さない
  })

  it('NO_DATA は表も円も出さず、未記録の案内だけ', async () => {
    const w = await mountSuspended(EffortStatsView, { props: { stats: pieStats({ emptyReason: 'NO_DATA', total: undefined, categories: [] }) }, global })
    expect(w.find('canvas').exists()).toBe(false)
    expect(w.find('[data-testid="effort-stats-table"]').exists()).toBe(false)
    expect(w.find('[data-testid="effort-stats-nodata"]').exists()).toBe(true)
  })

  it('棒グラフ: 未記録（null）と明示 0 を別表示にする', async () => {
    const stats: EffortStatsResult = {
      chart: 'BAR', grain: 'DAY', unit: 'MINUTE', dateFrom: '2026-11-01', dateTo: '2026-11-03', categories: [], total: '90.00', totalSampleCount: 2,
      buckets: [
        { startOn: '2026-11-01', endOn: '2026-11-01', value: '0.00', sampleCount: 1 },
        { startOn: '2026-11-02', endOn: '2026-11-02', value: undefined, sampleCount: 0 },
        { startOn: '2026-11-03', endOn: '2026-11-03', value: '90.00', sampleCount: 1 },
      ],
    }
    const w = await mountSuspended(EffortStatsView, { props: { stats }, global })
    const rows = w.findAll('[data-testid="effort-stats-row"]')
    expect(rows[0]!.text()).toContain('0.00')
    expect(rows[1]!.text()).not.toContain('0.00')
    expect(rows[1]!.text()).not.toContain('MINUTE')
    expect(rows[2]!.text()).toContain('90.00')
  })
  // ALIC-1: 単位コード（MINUTE/KILOGRAM）を表示名へ翻訳し、値との間は半角空白 1 つに統一する。
  it('数値表の単位は表示名（分・kg）で、値との間に空白が入る（コード直表示・空白欠落なし）', async () => {
    const bar: EffortStatsResult = {
      chart: 'BAR', grain: 'DAY', unit: 'KILOGRAM', dateFrom: '2026-11-01', dateTo: '2026-11-01', categories: [], total: undefined, totalSampleCount: 1,
      buckets: [{ startOn: '2026-11-01', endOn: '2026-11-01', value: '62.50', sampleCount: 1 }],
    }
    const wb = await mountSuspended(EffortStatsView, { props: { stats: bar }, global })
    expect(wb.get('[data-testid="effort-stats-row"]').text()).toContain('62.50 kg')
    expect(wb.text()).not.toContain('KILOGRAM')
    const wp = await mountSuspended(EffortStatsView, { props: { stats: pieStats() }, global })
    expect(wp.findAll('[data-testid="effort-stats-row"]')[0]!.text()).toMatch(/30\.00 (分|min)/)
    expect(wp.get('[data-testid="effort-stats-total"]').text()).toMatch(/90\.00 (分|min)/)
    expect(wp.text()).not.toContain('MINUTE')
  })
})
