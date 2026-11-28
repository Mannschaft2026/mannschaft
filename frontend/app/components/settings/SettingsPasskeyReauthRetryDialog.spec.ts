import { mountSuspended } from '@nuxt/test-utils/runtime'
import { describe, expect, it } from 'vitest'
import SettingsPasskeyReauthRetryDialog from './SettingsPasskeyReauthRetryDialog.vue'

/** AUTH_087 後の「本人確認をやり直す」ダイアログ。ボタン押下で retry を発火する（自動では再認証しない）。 */
const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Message: { template: '<div><slot /></div>' },
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button :data-testid="$attrs[\'data-testid\']" @click="$emit(\'click\')">{{ label }}</button>',
    inheritAttrs: false,
  },
}

describe('SettingsPasskeyReauthRetryDialog', () => {
  it('やり直すボタンの押下で retry を発火する', async () => {
    const wrapper = await mountSuspended(SettingsPasskeyReauthRetryDialog, { props: { visible: true }, global: { stubs } })

    await wrapper.find('[data-testid="passkey-reauth-retry-submit"]').trigger('click')

    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect(wrapper.find('[data-testid="passkey-reauth-retry-leftover"]').exists()).toBe(false)
  })

  it('登録の再試行では端末側に残ったパスキーの案内を出す', async () => {
    const wrapper = await mountSuspended(SettingsPasskeyReauthRetryDialog, { props: { visible: true, leftoverNote: true }, global: { stubs } })

    expect(wrapper.find('[data-testid="passkey-reauth-retry-leftover"]').exists()).toBe(true)
  })

  it('キャンセルで update:visible=false を発火する', async () => {
    const wrapper = await mountSuspended(SettingsPasskeyReauthRetryDialog, { props: { visible: true }, global: { stubs } })

    await wrapper.find('[data-testid="passkey-reauth-retry-cancel"]').trigger('click')

    expect(wrapper.emitted('update:visible')![0]).toEqual([false])
  })
})
