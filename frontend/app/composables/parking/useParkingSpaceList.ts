import type { ParkingSpaceResponse } from '~/types/parking'

/** 区画一覧の 1 ページ分の取得（useParkingSpacesApi().getSpaces と同じ形）。 */
export type FetchSpacesPage = (
  page: number,
  size: number,
) => Promise<{ data: ParkingSpaceResponse[]; meta?: { totalPages?: number } }>

/** BE の size 上限は 100（一覧系の共通上限）。 */
const SPACE_PAGE_SIZE = 100
/** 想定外の無限ループを避けるための安全弁（100 件 × 50 ページ = 5000 区画）。 */
const MAX_PAGES = 50

/**
 * 区画を全ページ取得して 1 本の配列にする。
 *
 * /spaces は page 0 始まり・size 既定 20 でページングされるため、1 回だけ呼ぶと
 * 21 区画目以降の「申請受付中」区画が申請フォームに出ない（漏れる）。
 * meta.totalPages を見て最終ページまで取り切る。途中で失敗したら例外をそのまま投げ、
 * 呼び出し側は「取得失敗」として扱う（一部だけ取れた状態を成功として返さない）。
 */
export async function fetchAllSpaces(fetchPage: FetchSpacesPage): Promise<ParkingSpaceResponse[]> {
  const all: ParkingSpaceResponse[] = []
  for (let page = 0; page < MAX_PAGES; page++) {
    const res = await fetchPage(page, SPACE_PAGE_SIZE)
    all.push(...(res.data ?? []))
    const totalPages = res.meta?.totalPages ?? 1
    if (page + 1 >= totalPages) return all
  }
  return all
}

/** 申請フォームに出せる区画か（受付中・来場者用でない・空き）。 */
export function isApplicableSpace(space: ParkingSpaceResponse): boolean {
  return (
    space.applicationStatus === 'ACCEPTING' &&
    space.spaceType !== 'VISITOR' &&
    space.status === 'VACANT'
  )
}
