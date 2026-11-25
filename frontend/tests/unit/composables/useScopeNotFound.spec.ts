import { beforeEach, describe, expect, it } from 'vitest'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 子ページの主 API が 403/404 を返したとき、シェルへ「見つかりません」を伝える共有状態。
 *
 *   - markIfDenied(err): 403/404 なら notFound を立てて true を返す。それ以外は false を返し状態を変えない
 *   - reset(): notFound を下ろす（シェルがルート／slug 変更時に呼ぶ）
 *   - 状態は useState で共有され、子ページとシェルが別々に呼んでも同じ値を見る
 */
beforeEach(() => {
  useScopeNotFound().reset()
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
})
