// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest'
import { runKeepingPinFocus } from './announcementFocus'

function row(id: number): HTMLElement {
  const el = document.createElement('div')
  el.dataset.announcementId = String(id)
  el.innerHTML = '<button data-testid="announcement-pin"></button>'
  return el
}

describe('runKeepingPinFocus', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('並べ替えでフォーカスが BODY に落ちても、操作したピンボタンへ戻す', async () => {
    const list = document.createElement('div')
    list.append(row(1), row(2))
    document.body.append(list)
    const button = list.querySelector<HTMLElement>('[data-announcement-id="2"] [data-testid="announcement-pin"]')!
    button.focus()
    await runKeepingPinFocus(2, async () => {
      // 並べ替えによる DOM 移動と blur をブラウザ同様に再現する
      list.prepend(list.lastElementChild!)
      ;(document.activeElement as HTMLElement).blur()
    })
    expect(document.activeElement).toBe(button)
  })

  it('操作前にピンボタンにフォーカスが無ければ奪わない', async () => {
    const list = document.createElement('div')
    list.append(row(1))
    const other = document.createElement('input')
    document.body.append(list, other)
    other.focus()
    await runKeepingPinFocus(1, async () => {})
    expect(document.activeElement).toBe(other)
  })

  it('利用者が別要素へ移動していたら奪わない', async () => {
    const list = document.createElement('div')
    list.append(row(1))
    const other = document.createElement('input')
    document.body.append(list, other)
    list.querySelector<HTMLElement>('[data-testid="announcement-pin"]')!.focus()
    await runKeepingPinFocus(1, async () => { other.focus() })
    expect(document.activeElement).toBe(other)
  })
})
