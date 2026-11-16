<script setup lang="ts">
/**
 * F06.4.1 頑張り記録の集計表示（棒 / 折線 / 円 + 同内容の数値表）。
 *
 * 数値は BE の集計値（10 進文字列）をそのまま使い、FE で合計・再集計しない（§3.1）。
 * - 未記録（value=null）と明示的な 0（"0.00"）を区別して表示する
 * - 円は分母 0（ZERO_TOTAL）/ データなし（NO_DATA）では描かない（BaseChart 側でも正値が無ければ描かない）
 * - 色だけに依存しないよう、凡例（ラベル）と数値表を必ず併設する
 */
import type { ChartData } from 'chart.js'
import type { EffortStatsResult } from '~/composables/useEffortRecordApi'
import { formatEffortValueWithUnit } from '~/utils/effortFields'

const props = defineProps<{
  stats: EffortStatsResult
}>()

const { t, te } = useI18n()

const PALETTE = ['#2563eb', '#16a34a', '#d97706', '#9333ea', '#dc2626', '#0891b2', '#65a30d', '#db2777', '#4b5563', '#ca8a04', '#0d9488', '#7c3aed']

const chartType = computed<'bar' | 'line' | 'pie'>(() => {
  if (props.stats.chart === 'PIE') return 'pie'
  return props.stats.chart === 'LINE' ? 'line' : 'bar'
})

const isPie = computed(() => props.stats.chart === 'PIE')

/** 描画に使う値。value=null は null のまま渡す（0 に置換しない・線は補間しない）。 */
function toPlot(value: string | undefined | null): number | null {
  return value === null || value === undefined ? null : Number(value)
}

const chartData = computed<ChartData<'line' | 'pie', Array<number | null>>>(() => {
  const s = props.stats
  if (isPie.value) {
    const cats = s.categories ?? []
    return {
      labels: cats.map((c) => c.label ?? c.key ?? ''),
      datasets: [{
        label: s.unit ?? '',
        data: cats.map((c) => toPlot(c.value)),
        backgroundColor: cats.map((_, i) => PALETTE[i % PALETTE.length]),
      }],
    }
  }
  const buckets = s.buckets ?? []
  return {
    labels: buckets.map((b) => b.startOn ?? ''),
    datasets: [{
      label: s.unit ?? '',
      data: buckets.map((b) => toPlot(b.value)),
      backgroundColor: PALETTE[0],
      borderColor: PALETTE[0],
      spanGaps: false,
    }],
  }
})

const noData = computed(() => props.stats.emptyReason === 'NO_DATA')
const zeroTotal = computed(() => props.stats.emptyReason === 'ZERO_TOTAL')
/** 円は分母が正のときだけ描く。バーは空でなければ描く。 */
const showChart = computed(() => !noData.value && !(isPie.value && zeroTotal.value))

/** 割合の表示用（HALF_UP・小数 1 桁）。分母は BE の total を使い、分母 0 / null なら表示しない。 */
function percentOf(value: string | undefined): string | null {
  const total = props.stats.total === undefined || props.stats.total === null ? NaN : Number(props.stats.total)
  const v = value === undefined ? NaN : Number(value)
  if (!Number.isFinite(total) || total <= 0 || !Number.isFinite(v)) return null
  return `${(Math.round((v / total) * 1000 + Number.EPSILON) / 10).toFixed(1)}%`
}

/** 単位コード（MINUTE 等）を表示名へ翻訳した「値 単位」。未知コードはコードのまま。 */
function withUnit(value: string | undefined | null): string {
  return formatEffortValueWithUnit(String(value ?? ''), props.stats.unit, (code) => (te(`effort.unit.${code}`) ? t(`effort.unit.${code}`) : null))
}

function bucketCell(v: string | undefined | null, sampleCount: number | undefined): string {
  return v === null || v === undefined || (sampleCount ?? 0) === 0 ? bucketValue(v, sampleCount) : withUnit(v)
}

function bucketValue(v: string | undefined | null, sampleCount: number | undefined): string {
  if (v === null || v === undefined || (sampleCount ?? 0) === 0) return t('effort.chart.notRecorded')
  return v
}
</script>

<template>
  <div data-testid="effort-stats-view">
    <p v-if="noData" class="py-6 text-center text-sm text-surface-500" data-testid="effort-stats-nodata">
      {{ t('effort.chart.noData') }}
    </p>
    <template v-else>
      <p v-if="zeroTotal" class="mb-3 rounded-lg bg-surface-100 p-3 text-sm dark:bg-surface-700" data-testid="effort-stats-zerototal">
        {{ t('effort.chart.zeroTotal') }}
      </p>
      <BaseChart
        v-if="showChart"
        :type="chartType"
        :data="chartData"
        :empty-message="t('effort.chart.noData')"
      />

      <!-- 数値表（円: カテゴリ別 / 棒・折線: 区間別）。BE の値をそのまま表示する -->
      <table v-if="isPie" class="mt-4 w-full text-sm" data-testid="effort-stats-table">
        <thead>
          <tr class="text-left text-surface-500">
            <th class="py-1">{{ t('effort.chart.category') }}</th>
            <th class="py-1 text-right">{{ t('effort.chart.value') }}</th>
            <th class="py-1 text-right">{{ t('effort.chart.share') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(c, i) in stats.categories" :key="c.key" data-testid="effort-stats-row">
            <td class="py-1">
              <span class="mr-2 inline-block h-3 w-3 rounded-sm align-middle" :style="{ backgroundColor: PALETTE[i % PALETTE.length] }" aria-hidden="true" />
              {{ c.key === '__unspecified__' ? t('effort.chart.unspecifiedSubject') : c.label }}
            </td>
            <td class="py-1 text-right">{{ withUnit(c.value) }}</td>
            <td class="py-1 text-right">{{ percentOf(c.value) ?? '-' }}</td>
          </tr>
        </tbody>
        <tfoot v-if="stats.total !== undefined && stats.total !== null">
          <tr class="font-semibold">
            <td class="py-1">{{ t('effort.chart.total') }}</td>
            <td class="py-1 text-right" data-testid="effort-stats-total">{{ withUnit(stats.total) }}</td>
            <td />
          </tr>
        </tfoot>
      </table>
      <table v-else class="mt-4 w-full text-sm" data-testid="effort-stats-table">
        <thead>
          <tr class="text-left text-surface-500">
            <th class="py-1">{{ t('effort.chart.period') }}</th>
            <th class="py-1 text-right">{{ t('effort.chart.value') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in stats.buckets" :key="b.startOn" data-testid="effort-stats-row">
            <td class="py-1">{{ b.startOn }}<template v-if="b.endOn && b.endOn !== b.startOn"> - {{ b.endOn }}</template></td>
            <td class="py-1 text-right">{{ bucketCell(b.value, b.sampleCount) }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>
