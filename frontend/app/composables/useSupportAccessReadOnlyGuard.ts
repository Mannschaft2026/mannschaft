/**
 * F10.9 サポート閲覧中（閲覧のみ）の画面側の共通ガード（戦役Z-B 検分差し戻し対応）。
 *
 * 書込は BE が必ず SUPPORT_ACCESS_003 で拒否する。ここは「押せそうに見えて押すと失敗する」画面を作らないための、
 * 主要画面に効く最小の共通の仕組み:
 * - 保存系の操作（フォームの送信ボタン・フォーム内とダイアログ下部の主ボタン）を無効に見せる（main.css が
 *   html.support-access-readonly と {@link SUPPORT_ACCESS_READ_ONLY_ACTION_SELECTOR} で同じ範囲を指す）。
 * - キーボード操作（Enter・Space でのボタン押下）でも動かないよう、クリックを捕捉段階で止める。
 * - フォームの送信（Enter キーでの暗黙の送信を含む）を捕捉段階で止める。
 * 閲覧のための操作（検索・タブ・ページ送り・閉じる等）は止めない。止めたくない要素には data-support-access-allow を付ける。
 */

/** 閲覧のみの間に止める保存系の操作。main.css の html.support-access-readonly の規則と範囲を揃えること。 */
export const SUPPORT_ACCESS_READ_ONLY_ACTION_SELECTOR = [
  'button[type="submit"]',
  'input[type="submit"]',
  'form .p-button:not(.p-button-secondary):not(.p-button-text):not(.p-button-outlined):not(.p-button-link)',
  '.p-dialog-footer .p-button:not(.p-button-secondary):not(.p-button-text):not(.p-button-outlined):not(.p-button-link)',
].join(', ')

const ALLOW_SELECTOR = '[data-support-access-allow]'
export const SUPPORT_ACCESS_READ_ONLY_CLASS = 'support-access-readonly'

/** 閲覧のみの間に止めるべき操作の要素か（許可の印がある要素の内側は止めない）。 */
export function isBlockedReadOnlyAction(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  if (target.closest(ALLOW_SELECTOR)) return false
  return target.closest(SUPPORT_ACCESS_READ_ONLY_ACTION_SELECTOR) !== null
}

/**
 * 閲覧のみのガードを document に取り付ける。isActive が偽の間は何もしない。戻り値で取り外す。
 *
 * @param isActive  いま閲覧中か（読み出しごとに評価する）
 * @param onBlocked 操作を止めたときの知らせ（トースト等）
 */
export function installSupportAccessReadOnlyGuard(isActive: () => boolean, onBlocked: () => void): () => void {
  if (typeof document === 'undefined') return () => {}

  const onClick = (event: Event) => {
    if (!isActive() || !isBlockedReadOnlyAction(event.target)) return
    event.preventDefault()
    event.stopImmediatePropagation()
    onBlocked()
  }
  const onSubmit = (event: Event) => {
    if (!isActive()) return
    if (event.target instanceof Element && event.target.closest(ALLOW_SELECTOR)) return
    event.preventDefault()
    event.stopImmediatePropagation()
    onBlocked()
  }

  // 捕捉段階で受け、画面側（Vue の @click・@submit）より先に止める
  document.addEventListener('click', onClick, true)
  document.addEventListener('submit', onSubmit, true)
  return () => {
    document.removeEventListener('click', onClick, true)
    document.removeEventListener('submit', onSubmit, true)
  }
}

/** html の閲覧のみクラスを付け外しする（main.css の無効表示）。 */
export function setSupportAccessReadOnlyClass(active: boolean): void {
  if (typeof document === 'undefined') return
  document.documentElement.classList.toggle(SUPPORT_ACCESS_READ_ONLY_CLASS, active)
}
