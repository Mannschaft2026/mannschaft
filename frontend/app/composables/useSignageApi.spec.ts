import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useSignageApi } from './useSignageApi'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)
beforeEach(() => mocks.api.mockReset())

describe('アクセストークンのAPI契約（CMP-260826-1457: 平文は発行応答だけ）', () => {
  it('発行は端末名を body で送る（BE は name を必須とする）', async () => {
    await useSignageApi().issueToken(7, { name: 'エントランス', expiredAt: null })
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/signage/screens/7/tokens', {
      method: 'POST',
      body: { name: 'エントランス', expiredAt: null },
    })
  })

  it('一覧取得は GET で呼ぶ', async () => {
    mocks.api.mockResolvedValue({ data: [] })
    await useSignageApi().getTokens(7)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/signage/screens/7/tokens')
  })
})

describe('サイネージ数値scopeIdのAPI契約（初回greenのcharacterization）', () => {
  it('一覧queryは解決済みの数値IDを使う', async () => {
    await useSignageApi().getScreens('ORGANIZATION', 42)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/signage/screens?scopeType=ORGANIZATION&scopeId=42')
  })

  it('作成bodyもLongに対応する数値IDを保つ', async () => {
    await useSignageApi().createScreen({
      scopeType: 'ORGANIZATION', scopeId: 42, name: 'screen',
      layout: 'FULLSCREEN', defaultSlideDuration: 10, transitionEffect: 'FADE',
    })
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/signage/screens', {
      method: 'POST',
      body: {
        scopeType: 'ORGANIZATION', scopeId: 42, name: 'screen',
        layout: 'FULLSCREEN', defaultSlideDuration: 10, transitionEffect: 'FADE',
      },
    })
  })
})
