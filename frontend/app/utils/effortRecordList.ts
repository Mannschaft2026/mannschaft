/**
 * F06.4.1 個人記録画面の一覧・集計の状態管理ヘルパ（純関数）。
 */

/** 続き読込の結果を既存一覧へ統合する。同じ id（UUID）は重複させず、version が新しい方（同値は後着）を採る。位置は初出のまま。 */
export function mergeEffortRecords<T extends { id?: string; version?: number }>(existing: readonly T[], incoming: readonly T[]): T[] {
  const result: T[] = [...existing]
  const indexById = new Map<string, number>()
  result.forEach((r, i) => {
    if (r.id) indexById.set(r.id, i)
  })
  for (const r of incoming) {
    const at = r.id ? indexById.get(r.id) : undefined
    if (at === undefined) {
      if (r.id) indexById.set(r.id, result.length)
      result.push(r)
    } else if ((r.version ?? 0) >= (result[at]?.version ?? 0)) {
      result[at] = r
    }
  }
  return result
}
