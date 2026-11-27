import { isScopeNotFound } from '~/utils/loadError'

/**
 * チーム／組織の子ページの主 API が 403/404 だったことを、シェルへ伝える共有状態（CMP-261125-1108）。
 *
 * - 子ページ: 主 API の catch の先頭で `if (markIfDenied(e)) return`（トーストを出さない）
 * - シェル: `notFound` が立ったら本文（NuxtPage）を ScopeNotFound に置き換え、マウント時・パス変更時に
 *   `reset()`、クエリだけの変更時に `clear()`、アンマウント時に `releaseOnUnmount()`
 * - ルートミドルウェア（org-role-guard 等）: ページの描画前に非所属・権限なしと判明したら
 *   `setRouteDenied(to.path, true)`。シェルは `isRouteDenied(route.path)` のとき子ページを描画せず置き換える
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
 *
 * # シェルの破棄（スコープ切替・ブラウザバック）
 *  シェルは slug ごとに別インスタンス（NuxtPage のキーが slug を含む）で、NuxtPage の Suspense は
 *  「新しいシェルと子ページの setup → 古いシェルの破棄」の順に動く。古いシェルが破棄時に無条件で
 *  reset すると、新しい子ページが捕まえた世代を失効させ、その 403 を捨ててしまう（実機 NG・AC-8）。
 *  そのため破棄時は `releaseOnUnmount()` を使い、自分の後に別のシェルが reset していない
 *  （＝世代の持ち主が自分のまま）ときだけ reset する。
 */
export function useScopeNotFound() {
  const notFound = useState<boolean>('scope-not-found', () => false)
  const generation = useState<number>('scope-not-found-generation', () => 0)
  /** ミドルウェアが拒否したルートのパス（拒否なしは null）。世代の reset では消さない。 */
  const deniedPath = useState<string | null>('scope-not-found-denied-path', () => null)
  const capturedGeneration = generation.value
  /** この呼び出し（シェル）が最後に reset した世代。releaseOnUnmount の照合に使う。 */
  let ownedGeneration = generation.value

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
    ownedGeneration = generation.value
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
    if (tryUseNuxtApp()?.isHydrating) {
      ownedGeneration = generation.value
      return
    }
    reset()
  }

  /**
   * シェルのアンマウント用。自分が最後に reset した後に別のシェルが reset していなければ（シェル外への遷移）、
   * reset して古い画面の遅延 403 を失効させる。別のシェルが既に reset していれば（スコープ切替・ブラウザバックで
   * 新しいシェルが先に setup 済み）、状態はそのシェルのものなので触らない。
   */
  function releaseOnUnmount() {
    if (ownedGeneration === generation.value) reset()
  }

  /**
   * ルートミドルウェア用。ページの描画前に非所属・権限なし・不存在と判明したルートを拒否する（denied=true）、
   * または同じパスの以前の拒否を解除する（denied=false）。拒否は別パスへ移れば効かなくなり、同じパスへ
   * 戻ったときはミドルウェアが改めて判定し直す。
   */
  function setRouteDenied(path: string, denied: boolean) {
    if (denied) deniedPath.value = path
    else if (deniedPath.value === path) deniedPath.value = null
  }

  /** シェル用。現在のパスがミドルウェアに拒否されているか。 */
  function isRouteDenied(path: string): boolean {
    return deniedPath.value === path
  }

  return {
    notFound,
    markIfDenied,
    markDenied,
    reset,
    clear,
    resetUnlessHydrating,
    releaseOnUnmount,
    setRouteDenied,
    isRouteDenied,
  }
}
