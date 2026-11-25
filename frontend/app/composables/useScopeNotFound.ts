import { isScopeNotFound } from '~/utils/loadError'

/**
 * クライアントでは 1 ページ読込 = 1 アプリなので、モジュール単位の ref で共有する。
 * 単体テストのように Nuxt アプリ文脈の外で mount されるコンポーネントの setup からも、
 * 子ページとシェルが同じ値を見られる（useState は setup 内で Nuxt アプリが引けず例外になる）。
 * サーバーではリクエスト間で状態が漏れないよう useState（リクエスト単位）を使う。
 */
const clientNotFound = ref(false)

/**
 * チーム／組織の子ページの主 API が 403/404 だったことを、シェルへ伝える共有状態（CMP-261125-1108）。
 *
 * - 子ページ: 主 API の catch の先頭で `if (markIfDenied(e)) return`（トーストを出さない）
 * - シェル: `notFound` が立ったら本文（NuxtPage）を ScopeNotFound に置き換え、slug／ルート変更で `reset()`
 */
export function useScopeNotFound() {
  const notFound = import.meta.client
    ? clientNotFound
    : useState<boolean>('scope-not-found', () => false)

  /** 403/404 なら notFound を立てて true を返す。それ以外は状態を変えず false。 */
  function markIfDenied(error: unknown): boolean {
    if (!isScopeNotFound(error)) return false
    notFound.value = true
    return true
  }

  function reset() {
    notFound.value = false
  }

  return { notFound, markIfDenied, reset }
}
