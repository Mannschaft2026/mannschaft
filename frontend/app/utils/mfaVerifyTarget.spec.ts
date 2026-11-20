import { describe, expect, it } from 'vitest'
import { buildMfaVerifyTarget } from './mfaVerifyTarget'

describe('buildMfaVerifyTarget（CMP-261012-0911）', () => {
  it('session・primary・methods・redirect を /2fa-verify のクエリへ載せる', () => {
    const url = new URL(buildMfaVerifyTarget({
      mfaSessionToken: 'tok',
      primaryMethod: 'EMAIL',
      availableMethods: ['EMAIL', 'BACKUP_CODE'],
    }, '/teams/x'), 'http://localhost')
    expect(url.pathname).toBe('/2fa-verify')
    expect(url.searchParams.get('session')).toBe('tok')
    expect(url.searchParams.get('primary')).toBe('EMAIL')
    expect(url.searchParams.get('methods')).toBe('EMAIL,BACKUP_CODE')
    expect(url.searchParams.get('redirect')).toBe('/teams/x')
  })

  it('方式情報・redirect が欠落していても session だけで組み立てる', () => {
    const url = new URL(buildMfaVerifyTarget({ mfaSessionToken: 'only' }), 'http://localhost')
    expect(url.searchParams.get('session')).toBe('only')
    expect(url.searchParams.has('primary')).toBe(false)
    expect(url.searchParams.has('methods')).toBe(false)
    expect(url.searchParams.has('redirect')).toBe(false)
  })
})
