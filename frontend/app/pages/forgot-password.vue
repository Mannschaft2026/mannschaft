<script setup lang="ts">
import { useForm } from 'vee-validate'
import { toTypedSchema } from '~/utils/toTypedSchema'
import { z } from 'zod'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const schema = toTypedSchema(z.object({
  email: z.string().min(1, 'auth.register.err_email_required').email('auth.register.err_email_invalid'),
}))

const { defineField, handleSubmit, errors } = useForm({
  validationSchema: schema,
  // ハイドレーション前に入力された値（パスワードマネージャの自動入力を含む）を取り込む。
  // 空文字のままだとハイドレーション時に上書きされて消える。必ずセットアップ時に読むこと。
  initialValues: { email: readPrefilledInputValue('email') },
})
const [email, emailProps] = defineField('email')

const loading = ref(false)
const sent = ref(false)

// SSR 配信済み HTML に @submit.prevent が未結合の窓で送信ボタンを押されると、
// ブラウザ標準のフォーム送信が走って入力が失われるため、ハイドレーション完了まで送信を封じる。
const hydrated = useHydrated()
// ハイドレーション待ちの間もボタンをローディング表示にする（無反応に見える問題の解消）。
// :disabled="!hydrated" は Enter キーによる implicit submission 抑止のため別途維持する
// （PrimeVue の loading は内部的に disabled 相当になるが、明示指定で確実に塞ぐ）。
const submitting = computed(() => loading.value || !hydrated.value)

const api = useApi()

const onSubmit = handleSubmit(async (values) => {
  loading.value = true
  try {
    await api('/api/v1/auth/password-reset/request', {
      method: 'POST',
      body: { email: values.email },
    })
  }
  catch {
    // 列挙攻撃対策: 4xx・5xx・通信断のいずれでも成功と同じ完了画面を出す（BE の message も出さない）。
  }
  finally {
    loading.value = false
    sent.value = true
  }
})
</script>

<template>
  <div v-if="!sent">
    <p class="mb-4 text-sm text-surface-500">
      {{ $t('auth.password_reset.request_description') }}
    </p>
    <form novalidate @submit.prevent="onSubmit">
      <div class="flex flex-col gap-4">
        <div class="flex flex-col gap-2">
          <label for="email">{{ $t('auth.register.email_label') }}</label>
          <InputText
            id="email"
            v-model="email"
            v-bind="emailProps"
            type="email"
            placeholder="example@mannschaft.app"
            :invalid="!!errors.email"
          />
          <small v-if="errors.email" class="text-red-500">{{ $t(errors.email) }}</small>
        </div>
        <Button
          type="submit"
          :label="$t('auth.password_reset.request_submit')"
          icon="pi pi-envelope"
          :loading="submitting"
          :disabled="!hydrated"
          class="mt-2"
        />
        <div class="text-center">
          <NuxtLink to="/login" class="text-sm text-primary hover:underline">
            {{ $t('auth.password_reset.back_to_login') }}
          </NuxtLink>
        </div>
      </div>
    </form>
  </div>
  <div v-else class="flex flex-col items-center gap-4 text-center">
    <i class="pi pi-check-circle text-4xl text-green-500" />
    <p>{{ $t('auth.password_reset.request_done') }}</p>
    <NuxtLink to="/login" class="text-sm text-primary hover:underline">
      {{ $t('auth.password_reset.back_to_login') }}
    </NuxtLink>
  </div>
</template>
