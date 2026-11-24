import type { ToastPassThroughOptions } from 'primevue/toast'

/**
 * グローバル Toast（app.vue）の pass-through。
 *
 * 閉じるボタンのタップ領域を 44x44px 以上にする（FRONTEND_CODING_CONVENTION §3b）。
 * `min-h-11 min-w-11` + flex 中央寄せでヒット領域だけ拡大し、×アイコンの視覚サイズは維持する。
 * （CMP-261125-0126: 実測 28x28px → 44x44px）
 */
export const appToastPt: ToastPassThroughOptions = {
  closeButton: {
    // テーマ(@primeuix/styles)は margin:-25% 0 0 0 / right:-25% で右上へ食い出させているため、
    // 44px に拡大するとトースト外へはみ出す。!m-0 !right-0 で打ち消して内側に収める。
    class: 'min-h-11 min-w-11 flex items-center justify-center !m-0 !right-0',
  },
}
