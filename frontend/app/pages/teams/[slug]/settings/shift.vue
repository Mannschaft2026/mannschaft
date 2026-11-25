<script setup lang="ts">
import type { TeamShiftSettings, UpdateTeamShiftSettingsRequest } from '~/types/teamShiftSettings'

definePageMeta({ layout: 'team', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)

const notification = useNotification()
const { getShiftSettings, updateShiftSettings } = useTeamShiftSettingsApi()
// CMP-261125-2114: 設定の変更はチーム ADMIN または MANAGE_SHIFTS 付き DEPUTY_ADMIN だけ（BE が 403 を返す）。
const { canManageShift, isScopeAdmin, isDeputyWithoutManage, loadPermissions } = useShiftPermission(teamSlug, { autoLoad: false })

const settings = ref<TeamShiftSettings | null>(null)
const loading = ref(false)
const saving = ref(false)
const validationError = ref<string | null>(null)

// フォーム状態
const form = ref<UpdateTeamShiftSettingsRequest>({
  reminder48hEnabled: true,
  reminder24hEnabled: true,
  reminder12hEnabled: false,
})

// 副管理者への閲覧許可（MANAGE_SHIFTS 無しの副管理者に閲覧だけを許す。操作は常に MANAGE_SHIFTS が必要）
const deputyForm = ref({
  deputyShiftViewEnabled: false,
  deputyHourlyRateViewEnabled: false,
})

async function loadSettings() {
  loading.value = true
  try {
    const res = await getShiftSettings(teamSlug)
    settings.value = res
    form.value = {
      reminder48hEnabled: res.reminder48hEnabled,
      reminder24hEnabled: res.reminder24hEnabled,
      reminder12hEnabled: res.reminder12hEnabled,
    }
    deputyForm.value = {
      deputyShiftViewEnabled: res.deputyShiftViewEnabled,
      deputyHourlyRateViewEnabled: res.deputyHourlyRateViewEnabled,
    }
  } catch {
    notification.error(t('settings.notification.load_error'))
  } finally {
    loading.value = false
  }
}

function validateAtLeastOneEnabled(): boolean {
  if (
    !form.value.reminder48hEnabled
    && !form.value.reminder24hEnabled
    && !form.value.reminder12hEnabled
  ) {
    validationError.value = t('teamShiftSettings.atLeastOneRequired')
    return false
  }
  validationError.value = null
  return true
}

async function saveSettings() {
  if (!canManageShift.value) return
  if (!validateAtLeastOneEnabled()) return

  saving.value = true
  try {
    const res = await updateShiftSettings(teamSlug, { ...form.value, ...deputyForm.value })
    settings.value = res
    notification.success(t('teamShiftSettings.saved'))
  } catch {
    notification.error(t('memberProfile.saveFailed'))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  const perm = await loadPermissions()
  if (!perm.ok) notification.error(t('settings.notification.load_error'))
  await loadSettings()
})
</script>

<template>
  <div class="container mx-auto max-w-2xl p-4">
    <PageHeader :title="$t('teamShiftSettings.title')" class="mb-4" />

    <!-- 権限の変更を管理者へ告知し、MANAGE_SHIFTS 無しの副管理者には操作できない理由を示す -->
    <ShiftManageNotice v-if="isScopeAdmin" variant="admin" :team-slug="teamSlug" />
    <ShiftManageNotice v-else-if="isDeputyWithoutManage" variant="deputy" />

    <p class="mb-6 text-sm text-surface-500">
      {{ $t('teamShiftSettings.description') }}
    </p>

    <div v-if="loading" class="flex justify-center p-8">
      <LoadingBounce />
    </div>

    <div
      v-else
      data-testid="reminder-settings-form"
      class="rounded-lg border border-surface-200 p-5 dark:border-surface-700"
    >
      <!-- リマインド設定トグル群 -->
      <div class="flex flex-col gap-4">
        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">
            {{ $t('teamShiftSettings.reminder48h') }}
          </label>
          <ToggleSwitch
            v-model="form.reminder48hEnabled"
            data-testid="reminder-48h-toggle"
            :disabled="!canManageShift"
          />
        </div>

        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">
            {{ $t('teamShiftSettings.reminder24h') }}
          </label>
          <ToggleSwitch
            v-model="form.reminder24hEnabled"
            data-testid="reminder-24h-toggle"
            :disabled="!canManageShift"
          />
        </div>

        <div class="flex items-center justify-between border-t border-surface-100 pt-4 dark:border-surface-700">
          <div>
            <label class="text-sm font-medium text-surface-400 dark:text-surface-500">
              {{ $t('teamShiftSettings.reminder12h') }}
            </label>
          </div>
          <ToggleSwitch
            v-model="form.reminder12hEnabled"
            data-testid="reminder-12h-toggle"
            disabled
          />
        </div>
      </div>

      <!-- 副管理者への閲覧許可（閲覧だけ。操作は MANAGE_SHIFTS が必要） -->
      <div
        data-testid="deputy-view-settings"
        class="mt-6 flex flex-col gap-4 border-t border-surface-100 pt-4 dark:border-surface-700"
      >
        <div>
          <h2 class="text-sm font-semibold">{{ $t('teamShiftSettings.deputyViewTitle') }}</h2>
          <p class="mt-1 text-xs text-surface-500">{{ $t('teamShiftSettings.deputyViewDescription') }}</p>
        </div>
        <div class="flex items-start justify-between gap-4">
          <div>
            <label for="deputy-shift-view-toggle" class="text-sm font-medium">
              {{ $t('teamShiftSettings.deputyShiftView') }}
            </label>
            <p class="text-xs text-surface-500">{{ $t('teamShiftSettings.deputyShiftViewHint') }}</p>
          </div>
          <ToggleSwitch
            v-model="deputyForm.deputyShiftViewEnabled"
            input-id="deputy-shift-view-toggle"
            data-testid="deputy-shift-view-toggle"
            :disabled="!canManageShift"
          />
        </div>
        <div class="flex items-start justify-between gap-4">
          <div>
            <label for="deputy-hourly-rate-view-toggle" class="text-sm font-medium">
              {{ $t('teamShiftSettings.deputyHourlyRateView') }}
            </label>
            <p class="text-xs text-surface-500">{{ $t('teamShiftSettings.deputyHourlyRateViewHint') }}</p>
          </div>
          <ToggleSwitch
            v-model="deputyForm.deputyHourlyRateViewEnabled"
            input-id="deputy-hourly-rate-view-toggle"
            data-testid="deputy-hourly-rate-view-toggle"
            :disabled="!canManageShift"
          />
        </div>
      </div>

      <p
        v-if="!canManageShift"
        data-testid="shift-settings-readonly-notice"
        class="mt-4 text-xs text-surface-500"
      >
        {{ $t('teamShiftSettings.readOnlyNotice') }}
      </p>

      <!-- バリデーションエラー -->
      <div
        v-if="validationError"
        data-testid="reminder-validation-error"
        class="mt-4 rounded-md bg-red-50 px-4 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400"
      >
        {{ validationError }}
      </div>

      <!-- 保存ボタン -->
      <div class="mt-6">
        <Button
          v-if="canManageShift"
          data-testid="reminder-settings-save-btn"
          :label="$t('button.save')"
          icon="pi pi-save"
          :loading="saving"
          @click="saveSettings"
        />
      </div>
    </div>
  </div>
</template>
