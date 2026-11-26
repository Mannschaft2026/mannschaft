import { beforeEach, describe, expect, it } from 'vitest'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 子ページの主 API が 403/404 を返したとき、シェルへ「見つかりません」を伝える共有状態。
 *
 *   - markIfDenied(err): 403/404 なら notFound を立てて true を返す。それ以外は false を返し状態を変えない
 *   - reset(): notFound を下ろす（シェルがルート／slug 変更時に呼ぶ）
 *   - 状態はサーバー・クライアントとも useState で共有され、子ページとシェルが別々に呼んでも同じ値を見る
 *     （SSR で立った値はペイロード経由でクライアントへ引き継がれる）
 *   - 世代: reset のたびに進む。useScopeNotFound() 呼び出し時の世代と現在の世代が違えば、
 *     markIfDenied は 403/404 でも notFound を立てない（古い画面の遅延応答を黙って捨てる。戻り値は true）
 *   - clear(): 世代を進めずに notFound だけ下ろす（同一ページのクエリ変更用）
 *   - markDenied(): API エラーを介さずに notFound を立てる。世代照合は markIfDenied と同じ
 *   - resetUnlessHydrating: hydration 中は何もしない（SSR の状態を引き継ぐ）。それ以外は reset と同じ
 */
beforeEach(() => {
  useScopeNotFound().reset()
  useState<string | null>('scope-not-found-denied-path').value = null
})

describe('useScopeNotFound（CMP-261125-1108）', () => {
  it('初期状態では notFound は false', () => {
    expect(useScopeNotFound().notFound.value).toBe(false)
  })

  it('403 を渡すと true を返し notFound が立つ', () => {
    const scope = useScopeNotFound()
    expect(scope.markIfDenied({ statusCode: 403 })).toBe(true)
    expect(scope.notFound.value).toBe(true)
  })

  it('404（response.status 形式）を渡すと true を返し notFound が立つ', () => {
    const scope = useScopeNotFound()
    expect(scope.markIfDenied({ response: { status: 404 } })).toBe(true)
    expect(scope.notFound.value).toBe(true)
  })

  it.each([
    ['500', { statusCode: 500 }],
    ['503', { response: { status: 503 } }],
    ['通信断', { name: 'FetchError', message: 'fetch failed' }],
    ['ステータス無し', new Error('boom')],
  ])('%s では false を返し notFound を立てない', (_label, error) => {
    const scope = useScopeNotFound()
    expect(scope.markIfDenied(error)).toBe(false)
    expect(scope.notFound.value).toBe(false)
  })

  it('一度立った notFound は、後続の 5xx で markIfDenied が false を返しても下ろさない（状態を変えない）', () => {
    const scope = useScopeNotFound()
    scope.markIfDenied({ statusCode: 403 })
    expect(scope.markIfDenied({ statusCode: 500 })).toBe(false)
    expect(scope.notFound.value).toBe(true)
  })

  it('reset で notFound が下りる', () => {
    const scope = useScopeNotFound()
    scope.markIfDenied({ statusCode: 404 })
    scope.reset()
    expect(scope.notFound.value).toBe(false)
  })

  it('子ページ側で立てた状態をシェル側の別呼び出しから読める（useState 共有）', () => {
    const child = useScopeNotFound()
    const shell = useScopeNotFound()
    child.markIfDenied({ statusCode: 403 })
    expect(shell.notFound.value).toBe(true)
    shell.reset()
    expect(child.notFound.value).toBe(false)
  })

  it('状態は useState（キー scope-not-found）そのもの。SSR で立った値をクライアントの呼び出しが引き継ぐ', () => {
    useState<boolean>('scope-not-found').value = true
    expect(useScopeNotFound().notFound.value).toBe(true)
  })

  it('reset 前に呼び出した画面の markIfDenied は、403 でも notFound を立てず true を返す（遅延応答を捨てる）', () => {
    const stale = useScopeNotFound()
    useScopeNotFound().reset()
    const current = useScopeNotFound()

    expect(stale.markIfDenied({ statusCode: 403 })).toBe(true)
    expect(current.notFound.value).toBe(false)
    // 5xx は従来どおり false（呼び出し側の汎用エラー処理に任せる）
    expect(stale.markIfDenied({ statusCode: 500 })).toBe(false)

    expect(current.markIfDenied({ statusCode: 404 })).toBe(true)
    expect(current.notFound.value).toBe(true)
  })

  it('resetUnlessHydrating は hydration 中は状態を下ろさず世代も進めない。それ以外は reset と同じ', () => {
    const nuxtApp = useNuxtApp()
    const before = nuxtApp.isHydrating
    const page = useScopeNotFound()
    page.markIfDenied({ statusCode: 403 })
    try {
      nuxtApp.isHydrating = true
      useScopeNotFound().resetUnlessHydrating()
      expect(page.notFound.value).toBe(true)
      // 世代が据え置きなので、hydration 中の子ページの判定も有効なまま
      page.notFound.value = false
      expect(page.markIfDenied({ statusCode: 403 })).toBe(true)
      expect(page.notFound.value).toBe(true)

      nuxtApp.isHydrating = false
      useScopeNotFound().resetUnlessHydrating()
      expect(page.notFound.value).toBe(false)
    }
    finally {
      nuxtApp.isHydrating = before
    }
  })

  it('markDenied は世代が一致するときだけ notFound を立てる', () => {
    const stale = useScopeNotFound()
    useScopeNotFound().reset()
    stale.markDenied()
    expect(useScopeNotFound().notFound.value).toBe(false)

    const current = useScopeNotFound()
    current.markDenied()
    expect(current.notFound.value).toBe(true)
  })

  it('clear は notFound を下ろすが世代は進めない（それ以前に setup した画面も引き続き立てられる）', () => {
    const page = useScopeNotFound()
    page.markIfDenied({ statusCode: 403 })
    page.clear()
    expect(page.notFound.value).toBe(false)
    expect(page.markIfDenied({ statusCode: 403 })).toBe(true)
    expect(page.notFound.value).toBe(true)
  })

  it('AC-8 スコープ切替: 新しいシェルが reset した後に古いシェルが破棄されても、新しい画面の判定を失効させない', () => {
    // Nuxt の NuxtPage（Suspense）は「新しいシェル＋子ページの setup → 古いシェルの破棄」の順に動く
    const oldShell = useScopeNotFound()
    oldShell.reset()
    const newShell = useScopeNotFound()
    newShell.resetUnlessHydrating()
    const newChild = useScopeNotFound()

    oldShell.releaseOnUnmount()

    expect(newChild.markIfDenied({ statusCode: 403 })).toBe(true)
    expect(newChild.notFound.value).toBe(true)
  })

  it('後続のシェルが無いまま破棄されたシェルは、世代を進めて notFound を下ろす（シェル外への遷移）', () => {
    const shell = useScopeNotFound()
    shell.reset()
    const child = useScopeNotFound()
    child.markIfDenied({ statusCode: 403 })

    shell.releaseOnUnmount()

    expect(child.notFound.value).toBe(false)
    // 破棄後に届いた古い 403 は立てない
    expect(child.markIfDenied({ statusCode: 403 })).toBe(true)
    expect(child.notFound.value).toBe(false)
  })

  it('ルート単位の拒否（ミドルウェア用）: 拒否したパスだけ isRouteDenied が true。reset では消えない', () => {
    const scope = useScopeNotFound()
    scope.setRouteDenied('/organizations/x/analytics', true)
    expect(scope.isRouteDenied('/organizations/x/analytics')).toBe(true)
    expect(scope.isRouteDenied('/organizations/y/analytics')).toBe(false)
    scope.reset()
    expect(useScopeNotFound().isRouteDenied('/organizations/x/analytics')).toBe(true)
    scope.setRouteDenied('/organizations/x/analytics', false)
    expect(scope.isRouteDenied('/organizations/x/analytics')).toBe(false)
  })
})
