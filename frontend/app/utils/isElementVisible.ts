/**
 * 要素が実際に表示されているか（自身または祖先が display:none / visibility:hidden / hidden 属性でないか）。
 * offsetParent は jsdom で常に null になるため、computed style を祖先まで辿って判定する。
 */
export function isElementVisible(el: HTMLElement): boolean {
  if (!el.isConnected) return false
  for (let node: HTMLElement | null = el; node; node = node.parentElement) {
    if (node.hidden) return false
    const style = window.getComputedStyle(node)
    if (style.display === 'none') return false
    if (node === el && (style.visibility === 'hidden' || style.visibility === 'collapse')) return false
  }
  return true
}
