import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Toast from 'primevue/toast'
import { useToast } from 'primevue/usetoast'
import { defineComponent, h, nextTick, onMounted } from 'vue'
import { appToastPt } from '~/utils/toastPassThrough'

/**
 * CMP-261125-0126: グローバル Toast の閉じるボタンは 44x44px 以上のタップ領域を確保する。
 * jsdom では寸法が測れないため、PrimeVue の Toast を実際にマウントして
 * 閉じるボタン要素に 44px 下限クラス（min-h-11 min-w-11）が付くことを検証する。
 */
describe('appToastPt（Toast の閉じるボタン タップ領域）', () => {
  it('定数が closeButton を定義している', () => {
    expect(appToastPt.closeButton).toBeDefined()
  })

  it('実際に描画された閉じるボタンに 44px 下限クラスが付く', async () => {
    const Host = defineComponent({
      setup() {
        const toast = useToast()
        onMounted(() => {
          toast.add({ severity: 'error', summary: 'エラー', detail: '失敗', life: 0 })
        })
        return () => h(Toast, { pt: appToastPt })
      },
    })
    const wrapper = mount(Host, {
      attachTo: document.body,
      global: { plugins: [PrimeVue, ToastService] },
    })
    await nextTick()
    await nextTick()
    const btn = document.body.querySelector('.p-toast-close-button')
    expect(btn).not.toBeNull()
    expect(btn!.classList.contains('min-h-11')).toBe(true)
    expect(btn!.classList.contains('min-w-11')).toBe(true)
    wrapper.unmount()
  })
})
