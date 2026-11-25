import { isScopeNotFound } from '~/utils/loadError'

/**
 * チーム／組織の子ページの主 API が 403/404 だったことを、シェルへ伝える共有状態（CMP-261125-1108）。
 *
 * - 子ページ: 主 API の catch の先頭で `if (markIfDenied(e)) return`（トーストを出さない）
 * - シェル: `notFound` が立ったら本文（NuxtPage）を ScopeNotFound に置き換え、マウント時・パス変更時・
 *   アンマウント時に `reset()`、クエリだけの変更時に `clear()`
 *
 * # 状態の持ち方
 *  サーバー・クライアントとも `useState`（Nuxt アプリ単位。サーバーではリクエスト単位）で持つ。
 *  SSR で立った notFound はペイロード経由でクライアントへ引き継がれ、hydration で不整合を起こさない。
 *
 * # 世代（遅延応答の取り違え防止）
 *  `reset()` のたびに世代を進める。`useScopeNotFound()` を呼んだ時点（各ページの setup）の世代を捕まえ、
 *  `markIfDenied` は捕まえた世代が現在の世代と一致するときだけ notFound を立てる。
 *  非所属スコープ X の子ページで応答待ちのまま Y やシェル外へ遷移した後に X の 403 が届いても、
 *  世代が一致しないので遷移先を置き換えない（古い画面の応答として黙って捨て、トーストも出さない）。
 */
export function useScopeNotFound() {
  const notFound = useState<boolean>('scope-not-found', () => false)
  const generation = useState<number>('scope-not-found-generation', () => 0)
  const capturedGeneration = generation.value

  /**
   * 403/404 なら true を返す（呼び出し側はトーストを出さずに終える）。それ以外は状態を変えず false。
   * 403/404 でも、呼び出し元の画面が reset 後の古い画面（世代不一致）なら notFound は立てない。
   */
  function markIfDenied(error: unknown): boolean {
    if (!isScopeNotFound(error)) return false
    if (capturedGeneration === generation.value) notFound.value = true
    return true
  }

  /**
   * API エラーを介さずに「見つかりません」にする（例: 権限情報から非メンバーと判明したとき）。
   * notFound へ直接代入せずこれを使う（世代照合を通すため。古い画面からは立てない）。
   */
  function markDenied() {
    if (capturedGeneration === generation.value) notFound.value = true
  }

  /** notFound を下ろし、世代を進める（それ以前に setup した画面の markIfDenied を失効させる）。 */
  function reset() {
    generation.value += 1
    notFound.value = false
  }

  /**
   * 世代は進めずに notFound だけ下ろす。同一ページでクエリだけ変わったとき（再マウントされない子ページが
   * そのまま取り直す）に使う。世代を進めると、その取り直しの 403 まで古い画面扱いで捨ててしまうため。
   */
  function clear() {
    notFound.value = false
  }

  /**
   * シェルの setup 用。hydration 中は SSR で立った状態を引き継ぐため何もしない
   * （ここで下ろすとサーバー描画と食い違う）。それ以外は reset と同じ。
   */
  function resetUnlessHydrating() {
    if (tryUseNuxtApp()?.isHydrating) return
    reset()
  }

  return { notFound, markIfDenied, markDenied, reset, clear, resetUnlessHydrating }
}
