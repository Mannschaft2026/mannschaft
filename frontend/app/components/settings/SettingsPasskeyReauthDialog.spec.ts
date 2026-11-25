import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { describe, expect, it } from 'vitest'
import SettingsPasskeyReauthDialog from './SettingsPasskeyReauthDialog.vue'

/**
 * パスキー登録・削除前の再認証ダイアログ（CMP-261021-1338 B5/B9）。手段ごとの入力欄と送信本文が BE の契約と揃うこと。
 * - PASSWORD: パスワードのみ
 * - PASSWORD_AND_SECOND_FACTOR: パスワード + 2FA 要素
 * - EMAIL_OTP: メールOTP のみ（method=EMAIL）
 */
const valueStub = (testIdFallback: string) => ({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: `<input :data-testid="$attrs['data-testid'] ?? '${testIdFallback}'" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  inheritAttrs: false,
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Message: { template: '<div><slot /></div>' },
  SelectButton: { props: ['modelValue', 'options'], template: '<div />' },
  Password: valueStub('password'),
  InputOtp: valueStub('otp'),
  InputText: valueStub('text'),
  Button: {
    props: ['label', 'disabled', 'type', 'loading', 'severity'],
    emits: ['click'],
    template:
      '<button :type="type ?? \'button\'" :disabled="disabled" :data-testid="$attrs[\'data-testid\']" @click="$emit(\'click\')">{{ label }}</button>',
    inheritAttrs: false,
  },
}

type Method = 'PASSWORD' | 'PASSWORD_AND_SECOND_FACTOR' | 'SECOND_FACTOR' | 'EMAIL_OTP'

async function mountDialog(method: Method, extra: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(SettingsPasskeyReauthDialog, {
    props: { visible: true, method, busy: false, errorMessage: '', reauthEmail: null, ...extra },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountDialog>>

const exists = (w: Wrapper, id: string) => w.find(`[data-testid="${id}"]`).exists()
const submitDisabled = (w: Wrapper) => w.get('[data-testid="passkey-reauth-submit"]').attributes('disabled') !== undefined

async function submit(w: Wrapper) {
  await w.get('form').trigger('submit')
  return w.emitted('submit')
}

describe('SettingsPasskeyReauthDialog', () => {
  describe('PASSWORD', () => {
    it('パスワード欄だけを出し、コード欄・メール送信は出さない', async () => {
      const w = await mountDialog('PASSWORD')
      expect(exists(w, 'passkey-reauth-password')).toBe(true)
      expect(exists(w, 'passkey-reauth-otp')).toBe(false)
      expect(exists(w, 'passkey-reauth-send-email')).toBe(false)
    })

    it('パスワード未入力では送信できず、入力すれば本文 {currentPassword} を送る', async () => {
      const w = await mountDialog('PASSWORD')
      expect(submitDisabled(w)).toBe(true)
      expect(await submit(w)).toBeUndefined()

      await w.get('[data-testid="passkey-reauth-password"]').setValue('secret')
      expect(submitDisabled(w)).toBe(false)
      expect((await submit(w))![0]).toEqual([{ currentPassword: 'secret' }])
    })
  })

  describe('PASSWORD_AND_SECOND_FACTOR', () => {
    it('パスワードと 2FA コードの両方が揃うまで送信できない', async () => {
      const w = await mountDialog('PASSWORD_AND_SECOND_FACTOR')
      expect(exists(w, 'passkey-reauth-factor')).toBe(true)

      await w.get('[data-testid="passkey-reauth-password"]').setValue('secret')
      expect(submitDisabled(w)).toBe(true)
      await w.get('[data-testid="passkey-reauth-otp"]').setValue('12345')
      expect(submitDisabled(w)).toBe(true)
      await w.get('[data-testid="passkey-reauth-otp"]').setValue('123456')
      expect(submitDisabled(w)).toBe(false)
    })

    it('パスワードと TOTP を本文に載せて送る', async () => {
      const w = await mountDialog('PASSWORD_AND_SECOND_FACTOR')
      await w.get('[data-testid="passkey-reauth-password"]').setValue('secret')
      await w.get('[data-testid="passkey-reauth-otp"]').setValue('123456')
      expect((await submit(w))![0]).toEqual([{ currentPassword: 'secret', method: 'TOTP', code: '123456' }])
    })
  })

  describe('SECOND_FACTOR（パスワードを持たない SNS 利用者 + 2FA 有効）', () => {
    it('パスワード欄は出さず、2FA 要素の選択とコード欄を出す', async () => {
      const w = await mountDialog('SECOND_FACTOR')
      expect(exists(w, 'passkey-reauth-password')).toBe(false)
      expect(exists(w, 'passkey-reauth-factor')).toBe(true)
      expect(exists(w, 'passkey-reauth-otp')).toBe(true)
    })

    it('コードが揃うまで送信できず、本文は {method, code} のみ（currentPassword を載せない）', async () => {
      const w = await mountDialog('SECOND_FACTOR')
      expect(submitDisabled(w)).toBe(true)
      expect(await submit(w)).toBeUndefined()
      await w.get('[data-testid="passkey-reauth-otp"]').setValue('123456')
      expect(submitDisabled(w)).toBe(false)
      expect((await submit(w))![0]).toEqual([{ method: 'TOTP', code: '123456' }])
    })

    it('2FA 方式が EMAIL ならメール要素が初期選択で、メール送信ボタンを出し、本文は method=EMAIL', async () => {
      const w = await mountDialog('SECOND_FACTOR', { twoFaMethod: 'EMAIL' })
      expect(exists(w, 'passkey-reauth-send-email')).toBe(true)
      await w.get('[data-testid="passkey-reauth-otp"]').setValue('654321')
      expect((await submit(w))![0]).toEqual([{ method: 'EMAIL', code: '654321' }])
    })
  })

  describe('EMAIL_OTP', () => {
    it('パスワード欄は出さず、メール送信ボタンとコード欄を出す', async () => {
      const w = await mountDialog('EMAIL_OTP')
      expect(exists(w, 'passkey-reauth-password')).toBe(false)
      expect(exists(w, 'passkey-reauth-send-email')).toBe(true)
      expect(exists(w, 'passkey-reauth-otp')).toBe(true)
    })

    it('送信ボタンで sendEmail を発火し、6 桁入力で {method: EMAIL, code} を送る', async () => {
      const w = await mountDialog('EMAIL_OTP')
      await w.get('[data-testid="passkey-reauth-send-email"]').trigger('click')
      expect(w.emitted('sendEmail')).toHaveLength(1)

      await w.get('[data-testid="passkey-reauth-otp"]').setValue('654321')
      expect((await submit(w))![0]).toEqual([{ method: 'EMAIL', code: '654321' }])
    })

    it('送信済みなら送信先の案内を出す', async () => {
      const w = await mountDialog('EMAIL_OTP', { reauthEmail: { maskedEmail: 'a***@example.com', expiresInSeconds: 600, resendAfterSeconds: 0 } })
      expect(exists(w, 'passkey-reauth-email-sent')).toBe(true)
    })
  })

  it('失敗は本文の先頭にエラー文言として出す', async () => {
    const w = await mountDialog('PASSWORD', { errorMessage: 'settings.security.webauthn.reauth.failed' })
    expect(w.get('[data-testid="passkey-reauth-error"]').text()).toContain('settings.security.webauthn.reauth.failed')
  })

  it('処理中（busy）は送信できない', async () => {
    const w = await mountDialog('PASSWORD', { busy: true })
    await w.get('[data-testid="passkey-reauth-password"]').setValue('secret')
    expect(submitDisabled(w)).toBe(true)
  })

  it('キャンセルで update:visible=false を発火する', async () => {
    const w = await mountDialog('PASSWORD')
    await w.get('[data-testid="passkey-reauth-cancel"]').trigger('click')
    expect(w.emitted('update:visible')![0]).toEqual([false])
  })
})
