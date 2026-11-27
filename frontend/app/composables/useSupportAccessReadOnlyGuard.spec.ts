// @vitest-environment happy-dom
// 戦役Z-B 検分差し戻し対応: 閲覧のみの共通の仕組み。type=submit だけでなく PrimeVue の保存系ボタン（フォーム内・
// ダイアログ下部の主ボタン）のクリックと、Enter での送信も止める。閲覧の操作（閉じる・検索等）は止めない。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  installSupportAccessReadOnlyGuard,
  isBlockedReadOnlyAction,
  setSupportAccessReadOnlyClass,
} from '~/composables/useSupportAccessReadOnlyGuard'

let active = true
let uninstall: () => void = () => {}
const blocked = vi.fn()

beforeEach(() => {
  active = true
  blocked.mockReset()
  document.body.innerHTML = `
    <form id="f">
      <input id="name" />
      <button id="save" class="p-button" type="button">保存</button>
      <button id="cancel" class="p-button p-button-secondary" type="button">キャンセル</button>
      <button id="submit" type="submit">送信</button>
    </form>
    <div class="p-dialog-footer">
      <button id="dialog-ok" class="p-button">確定</button>
      <button id="dialog-close" class="p-button p-button-text">閉じる</button>
    </div>
    <button id="tab" class="p-button">タブ</button>
    <div data-support-access-allow><form><button id="allowed" type="submit">終了</button></form></div>
  `
  uninstall = installSupportAccessReadOnlyGuard(() => active, blocked)
})
afterEach(() => {
  uninstall()
  setSupportAccessReadOnlyClass(false)
})

function el(id: string): HTMLElement {
  const found = document.getElementById(id)
  if (!found) throw new Error(`missing #${id}`)
  return found
}

describe('閲覧のみの共通ガード', () => {
  it('保存系（送信ボタン・フォーム内の主ボタン・ダイアログ下部の主ボタン）を止める対象にし、閲覧の操作は対象にしない', () => {
    expect(isBlockedReadOnlyAction(el('save'))).toBe(true)
    expect(isBlockedReadOnlyAction(el('submit'))).toBe(true)
    expect(isBlockedReadOnlyAction(el('dialog-ok'))).toBe(true)
    expect(isBlockedReadOnlyAction(el('cancel'))).toBe(false)
    expect(isBlockedReadOnlyAction(el('dialog-close'))).toBe(false)
    expect(isBlockedReadOnlyAction(el('tab'))).toBe(false)
    expect(isBlockedReadOnlyAction(el('allowed'))).toBe(false)
  })

  it('閲覧中は保存系のクリック（キーボードでの押下も click になる）を画面の処理より先に止める', () => {
    const handler = vi.fn()
    el('save').addEventListener('click', handler)
    el('save').click()
    expect(handler).not.toHaveBeenCalled()
    expect(blocked).toHaveBeenCalledTimes(1)
  })

  it('閲覧中は Enter キーでの暗黙の送信を含むフォームの送信を止める', () => {
    const onSubmit = vi.fn()
    el('f').addEventListener('submit', onSubmit)
    el('f').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(blocked).toHaveBeenCalled()
  })

  it('閲覧中でなければ何も止めない', () => {
    active = false
    const handler = vi.fn()
    el('save').addEventListener('click', handler)
    el('save').click()
    expect(handler).toHaveBeenCalled()
    expect(blocked).not.toHaveBeenCalled()
  })

  it('html の閲覧のみクラスを付け外しする（main.css の無効表示）', () => {
    setSupportAccessReadOnlyClass(true)
    expect(document.documentElement.classList.contains('support-access-readonly')).toBe(true)
    setSupportAccessReadOnlyClass(false)
    expect(document.documentElement.classList.contains('support-access-readonly')).toBe(false)
  })
})
