<script setup lang="ts">
/**
 * パスキー再認証の期限切れ（AUTH_087）後に出す「本人確認をやり直す」ダイアログ。
 *
 * iOS/Safari は navigator.credentials.get をユーザー操作の直後にしか受け付けない。期限切れの通知から自動で
 * 認証ダイアログを出すと操作との間に通信が挟まって拒否されうるため、利用者にボタンを押してもらい、
 * その押下を起点に begin → get を行う（再認証の呼び出しは親の `@retry` ハンドラ）。
 */
defineProps<{
  visible: boolean
  /** 登録の再試行のとき true。端末側に残ったパスキーの案内を出す（BE には登録されていない）。 */
  leftoverNote?: boolean
}>()

const emit = defineEmits<{
  retry: []
  'update:visible': [value: boolean]
}>()
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('settings.security.webauthn.reauth.title')"
    :modal="true"
    class="w-full max-w-md"
    data-testid="passkey-reauth-retry-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-4">
      <Message severity="warn" :closable="false" data-testid="passkey-reauth-retry-message">
        {{ $t('settings.security.webauthn.reauth.expired') }}
      </Message>
      <p v-if="leftoverNote" class="text-sm text-surface-500" data-testid="passkey-reauth-retry-leftover">
        {{ $t('settings.security.webauthn.reauth.leftover_passkey') }}
      </p>
      <div class="flex justify-end gap-2">
        <Button
          type="button"
          translate="no"
          :label="$t('button.cancel')"
          severity="secondary"
          data-testid="passkey-reauth-retry-cancel"
          @click="emit('update:visible', false)"
        />
        <Button
          type="button"
          translate="no"
          :label="$t('settings.security.webauthn.reauth.retry_button')"
          data-testid="passkey-reauth-retry-submit"
          @click="emit('retry')"
        />
      </div>
    </div>
  </Dialog>
</template>
