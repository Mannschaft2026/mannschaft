import { toYmd } from '~/utils/activityFields'

/**
 * F06.4.1 §3.1 / §5.1: 期間ボタンから API の date_from / date_to / grain を決める。
 * 週は月曜〜日曜、月は暦月、年は 1/1〜12/31（両端包含）。日付は暦日のみで扱い、秒換算をしない。
 * 週/月は DAY、年は MONTH、任意は 1〜62 日 = DAY・63〜366 日 = MONTH。
 */
export type EffortPeriodKind = 'week' | 'month' | 'year' | 'custom'

export interface EffortPeriodRange {
  dateFrom: string
  dateTo: string
  grain: 'DAY' | 'MONTH'
}

/** 任意期間の最大日数（BE と同じ 366 日）。 */
export const EFFORT_MAX_RANGE_DAYS = 366
const DAY_GRAIN_MAX_DAYS = 62
const DAY_MS = 86_400_000

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

/** 両端包含の日数。DST の日長差を避けるため UTC 暦日へ寄せて数える。 */
export function inclusiveDays(from: Date, to: Date): number {
  const a = Date.UTC(from.getFullYear(), from.getMonth(), from.getDate())
  const b = Date.UTC(to.getFullYear(), to.getMonth(), to.getDate())
  return Math.floor((b - a) / DAY_MS) + 1
}

/**
 * @param offset anchor を基準に前へ（負）/ 後ろへ（正）動かす期間数（custom では無視）
 * @param custom kind='custom' の開始・終了日
 * @returns 範囲。任意期間が不正（未入力・逆転・366 日超）なら null（呼び出し側で入力エラー表示）
 */
export function resolveEffortPeriod(
  kind: EffortPeriodKind,
  anchor: Date,
  offset = 0,
  custom?: { from: Date | null; to: Date | null },
): EffortPeriodRange | null {
  const a = startOfDay(anchor)
  if (kind === 'week') {
    const dow = (a.getDay() + 6) % 7 // 月曜 = 0
    const from = new Date(a.getFullYear(), a.getMonth(), a.getDate() - dow + offset * 7)
    const to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + 6)
    return { dateFrom: toYmd(from), dateTo: toYmd(to), grain: 'DAY' }
  }
  if (kind === 'month') {
    const from = new Date(a.getFullYear(), a.getMonth() + offset, 1)
    const to = new Date(from.getFullYear(), from.getMonth() + 1, 0)
    return { dateFrom: toYmd(from), dateTo: toYmd(to), grain: 'DAY' }
  }
  if (kind === 'year') {
    const y = a.getFullYear() + offset
    return { dateFrom: toYmd(new Date(y, 0, 1)), dateTo: toYmd(new Date(y, 11, 31)), grain: 'MONTH' }
  }
  const from = custom?.from ? startOfDay(custom.from) : null
  const to = custom?.to ? startOfDay(custom.to) : null
  if (!from || !to || to < from) return null
  const days = inclusiveDays(from, to)
  if (days > EFFORT_MAX_RANGE_DAYS) return null
  return { dateFrom: toYmd(from), dateTo: toYmd(to), grain: days <= DAY_GRAIN_MAX_DAYS ? 'DAY' : 'MONTH' }
}

export type EffortChartKind = 'BAR' | 'LINE' | 'PIE'

export interface EffortChartTarget {
  chart: EffortChartKind
  fieldKey: string
}

interface TargetField {
  fieldKey?: string
  semantic?: string
  aggregation?: string
}

/**
 * snapshot から、§5.1 のグラフ許可表に従って描けるグラフ（chart × fieldKey）を列挙する。
 * DURATION: BAR（SUM）/ PIE（SUBJECT 対応ありのみ）、WEIGHT: LINE、NUMBER: aggregation=SUM のときのみ BAR。
 */
export function listEffortChartTargets(snapshot: {
  fields?: readonly TargetField[]
  durationFieldKey?: string | null
  subjectFieldKey?: string | null
}): EffortChartTarget[] {
  const out: EffortChartTarget[] = []
  for (const f of snapshot.fields ?? []) {
    if (!f.fieldKey) continue
    if (f.semantic === 'DURATION') {
      out.push({ chart: 'BAR', fieldKey: f.fieldKey })
      if (snapshot.subjectFieldKey && snapshot.durationFieldKey === f.fieldKey) {
        out.push({ chart: 'PIE', fieldKey: f.fieldKey })
      }
    } else if (f.semantic === 'WEIGHT') {
      out.push({ chart: 'LINE', fieldKey: f.fieldKey })
    } else if (f.semantic === 'NUMBER' && f.aggregation === 'SUM') {
      out.push({ chart: 'BAR', fieldKey: f.fieldKey })
    }
  }
  return out
}
