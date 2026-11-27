import type { MaybeRefOrGetter } from 'vue'
import type { TeamResponse } from '~/types/team'
import { resolveSlugRedirectPath } from '~/utils/slugRedirect'
import { getLoadErrorHttpStatus, isScopeNotFound } from '~/utils/loadError'

/** ゲートの状態。ok 以外のとき、呼び出し側は本文の API を呼んではならない。 */
export type TeamAccessGateState = 'loading' | 'ok' | 'notFound' | 'error'

/**
 * チーム配下の個別ページ（親シェル対象外の系統 2）が最初に通す、チーム詳細の可否判定
 * （CMP-261124-0159 PR-F1 / F00 §20.5.6）。
 * 403 の理由は表示しない（2026-11-27 マスター裁定で CMP-261125-1108 に統一。AC-0159-86・87 は取り下げ）。
 *
 * - 403 → 'notFound'（404 と同じ「見つかりません」。トーストなし。slug 移転の解決もしない）
 * - 404 → slug 移転を解決して遷移できればそれに従い、できなければ 'notFound'（トーストなし）
 * - その他 → 'error'（既存の handleApiError でトースト。再試行は load()）
 * - 200 → 'ok'（team を返す）。403/404 の間は team を保持しない＝本文 API を呼ぶ根拠が無い
 *
 * 呼び出し時に 1 回自動で取得する。後発の要求・slug 変更で古い応答は破棄する。
 *
 * 本文の読み込み（onMounted で行っていた API 呼び出し・権限取得）は `onOk(fn)` に渡す。
 * 'ok' になった時点（再判定で ok に戻ったときも）でだけ fn を呼び、403/404/判定中/通信失敗の間は呼ばない。
 */
export function useTeamAccessGate(slug: MaybeRefOrGetter<string>) {
  const teamApi = useTeamApi()
  const { handleApiError } = useErrorHandler()

  const team = ref<TeamResponse | null>(null)
  const state = ref<TeamAccessGateState>('loading')
  const error = shallowRef<unknown>(null)
  let seq = 0

  async function load(): Promise<void> {
    const mySeq = ++seq
    const requestedSlug = toValue(slug)
    const isCurrent = () => mySeq === seq && toValue(slug) === requestedSlug
    state.value = 'loading'
    error.value = null
    try {
      const result = await teamApi.getTeam(requestedSlug)
      if (!isCurrent()) return
      team.value = result.data
      state.value = 'ok'
    }
    catch (e) {
      if (!isCurrent()) return
      team.value = null
      if (isScopeNotFound(e)) {
        if (getLoadErrorHttpStatus(e) === 403) {
          state.value = 'notFound'
          return
        }
        const route = useRoute()
        const { resolveSlug } = useSlugRedirect()
        const target = await resolveSlugRedirectPath(route.path, resolveSlug)
        if (!isCurrent()) return
        if (target) {
          await navigateTo({ path: target, query: route.query, hash: route.hash }, { redirectCode: 301, replace: true })
          return
        }
        state.value = 'notFound'
        return
      }
      error.value = e
      state.value = 'error'
      handleApiError(e, 'チーム詳細取得')
    }
  }

  /** 'ok' になるたびに fn を呼ぶ。戻り値は無視する（Promise を返してよい）。本文 API は必ずここ（または state==='ok' の描画）から呼ぶこと。 */
  function onOk(fn: () => unknown): void {
    watch(state, (s) => { if (s === 'ok') void fn() }, { immediate: true })
  }

  watch(() => toValue(slug), () => { void load() })
  onBeforeUnmount(() => { ++seq })
  void load()

  return { team, state, error, load, onOk }
}
