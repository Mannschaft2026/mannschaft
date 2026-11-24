// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { extractApiFieldErrors } from '~/utils/apiFieldErrors'

describe('extractApiFieldErrors', () => {
  it('400 応答の fieldErrors を返す。無ければ空', () => {
    const e = { data: { error: { fieldErrors: [{ field: 'values.duration', message: '入力できる範囲を超えています' }] } } }
    expect(extractApiFieldErrors(e)).toEqual([{ field: 'values.duration', message: '入力できる範囲を超えています' }])
    expect(extractApiFieldErrors(new Error('x'))).toEqual([])
    expect(extractApiFieldErrors(null)).toEqual([])
  })
})
