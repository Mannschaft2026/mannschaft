// @vitest-environment node

import { describe, expect, it } from 'vitest'
import { isScopeNotFound } from '~/utils/loadError'

/**
 * CMP-261125-1108 非所属・権限なしの子ページを共通の「見つかりません」表示にする。
 *
 * isScopeNotFound は「主 API の失敗を見つかりません表示へ置き換えるか」の判定。
 * 403 と 404 だけを true とし、5xx・ステータス無し・通信断は従来の汎用エラー（再試行あり）へ残すため false。
 */
describe('isScopeNotFound（CMP-261125-1108）', () => {
  it.each([
    ['statusCode 403', { statusCode: 403 }],
    ['status 403', { status: 403 }],
    ['response.status 403', { response: { status: 403 } }],
    ['statusCode 404', { statusCode: 404 }],
    ['status 404', { status: 404 }],
    ['response.status 404', { response: { status: 404 } }],
  ])('%s は見つかりません扱い（true）', (_label, error) => {
    expect(isScopeNotFound(error)).toBe(true)
  })

  it.each([
    ['500', { statusCode: 500 }],
    ['502', { response: { status: 502 } }],
    ['503', { status: 503 }],
    ['401', { statusCode: 401 }],
    ['422', { statusCode: 422 }],
    ['通信断（FetchError）', { name: 'FetchError', message: 'fetch failed' }],
    ['通信断（TypeError）', new TypeError('Failed to fetch')],
    ['ステータス無しの Error', new Error('boom')],
    ['null', null],
    ['undefined', undefined],
    ['文字列', '403'],
  ])('%s は見つかりません扱いにしない（false）', (_label, error) => {
    expect(isScopeNotFound(error)).toBe(false)
  })
})
