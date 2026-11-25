import { nextTick } from 'vue'

const PIN_BUTTON = '[data-testid="announcement-pin"]'

/**
 * ピン操作で一覧が並べ替わっても、操作したピンボタンにフォーカスを残す。
 *
 * 並べ替えで行の DOM ノードが移動するとブラウザは blur し、フォーカスが BODY に落ちる。
 * 操作前にフォーカスが当該行のピンボタンにあった場合だけ、並べ替え後に同じ行のボタンへ戻す。
 * 利用者が別の場所へ移動していた場合（activeElement が body 以外）は奪わない。
 *
 * @param id  announcement_feed ID
 * @param run 実際のピン切り替え処理
 */
export async function runKeepingPinFocus(id: number, run: () => Promise<void>): Promise<void> {
  const hadFocus = typeof document !== 'undefined'
    && (document.activeElement as HTMLElement | null)
      ?.closest<HTMLElement>(`[data-announcement-id="${id}"]`) != null
    && document.activeElement?.matches(PIN_BUTTON) === true
  try {
    await run()
  } finally {
    if (hadFocus) {
      await nextTick()
      if (document.activeElement === document.body || document.activeElement === null) {
        document.querySelector<HTMLElement>(`[data-announcement-id="${id}"] ${PIN_BUTTON}`)
          ?.focus({ preventScroll: true })
      }
    }
  }
}
