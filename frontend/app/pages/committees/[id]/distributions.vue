<script setup lang="ts">
import dayjs from 'dayjs'
import type {
  CommitteeConfirmationMode,
  CommitteeDistributeRequest,
  CommitteeDistributionContentType,
  CommitteeDistributionLog,
  CommitteeDistributionScope,
  CommitteeRole,
} from '~/types/committee'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const committeeId = Number(route.params.id)
const committeeApi = useCommitteeApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { userTimezone } = useDatetime()

const distributions = ref<CommitteeDistributionLog[]>([])
const loading = ref(true)
/** 取得失敗は「配布履歴なし」ではない。空状態へフォールバックせずエラー状態を出す。 */
const loadFailed = ref(false)
const { page, rows, totalRecords, onPage } = usePagination()

// --- 配信操作（BE: CHAIR / VICE_CHAIR / SECRETARY のみ可） ---
const DISTRIBUTOR_ROLES: CommitteeRole[] = ['CHAIR', 'VICE_CHAIR', 'SECRETARY']
const CONTENT_TYPES: CommitteeDistributionContentType[] = ['SURVEY_RESULT', 'ACTIVITY_RECORD', 'CIRCULATION_RESULT', 'CUSTOM_MESSAGE']
const SCOPES: CommitteeDistributionScope[] = ['COMMITTEE_ONLY', 'PARENT_ORG', 'PARENT_ORG_AND_CHILDREN']
const CONFIRMATION_MODES: CommitteeConfirmationMode[] = ['NONE', 'OPTIONAL', 'REQUIRED']

const canDistribute = ref(false)
const showDialog = ref(false)
const submitting = ref(false)
const formError = ref(false)
const form = reactive({
  contentType: 'CUSTOM_MESSAGE' as CommitteeDistributionContentType,
  contentId: null as number | null,
  customTitle: '',
  customBody: '',
  customHeadline: '',
  targetScope: 'COMMITTEE_ONLY' as CommitteeDistributionScope,
  announcementEnabled: true,
  confirmationMode: 'NONE' as CommitteeConfirmationMode,
  confirmationDeadlineAt: null as Date | null,
})

const isCustomMessage = computed(() => form.contentType === 'CUSTOM_MESSAGE')

const contentTypeOptions = computed(() =>
  CONTENT_TYPES.map((v) => ({ label: t(`committee.distributions.content_types.${v}`), value: v })),
)
const scopeOptions = computed(() =>
  SCOPES.map((v) => ({ label: t(`committee.distributions.scopes.${v}`), value: v })),
)
const confirmationOptions = computed(() =>
  CONFIRMATION_MODES.map((v) => ({ label: t(`committee.distributions.confirmation_modes.${v}`), value: v })),
)

/** 権限判定は委員会詳細の myRole に従う。取得失敗時は操作を出さず、エラーは通知する。 */
async function loadPermission() {
  try {
    const res = await committeeApi.getCommittee(committeeId)
    const role = res.data.myRole
    canDistribute.value = role != null && DISTRIBUTOR_ROLES.includes(role)
    // 委員会の既定値をプリセット（設計書: 変更が必要な選択のみ触ればよい）
    form.targetScope = res.data.defaultDistributionScope ?? form.targetScope
    form.announcementEnabled = res.data.defaultAnnouncementEnabled ?? form.announcementEnabled
    form.confirmationMode = res.data.defaultConfirmationMode ?? form.confirmationMode
  } catch (err) {
    handleApiError(err, 'getCommittee')
    canDistribute.value = false
  }
}

function openDialog() {
  formError.value = false
  showDialog.value = true
}

function buildRequest(): CommitteeDistributeRequest | null {
  const custom = isCustomMessage.value
  if (custom && !form.customTitle.trim()) return null
  if (!custom && form.contentId == null) return null
  if (form.confirmationMode === 'REQUIRED' && !form.confirmationDeadlineAt) return null
  return {
    contentType: form.contentType,
    contentId: custom ? null : form.contentId,
    customTitle: custom ? form.customTitle.trim() : null,
    customBody: custom && form.customBody.trim() ? form.customBody.trim() : null,
    targetScope: form.targetScope,
    announcementEnabled: form.announcementEnabled,
    confirmationMode: form.confirmationMode,
    confirmationDeadlineAt: form.confirmationDeadlineAt
      ? dayjs(form.confirmationDeadlineAt).format('YYYY-MM-DDTHH:mm:ss')
      : null,
    customHeadline: form.customHeadline.trim() || null,
  }
}

async function submitDistribution() {
  const body = buildRequest()
  if (!body) {
    formError.value = true
    return
  }
  formError.value = false
  if (!confirm(t('committee.distributions.confirm_message'))) return
  submitting.value = true
  try {
    await committeeApi.distribute(committeeId, body)
    notification.success(t('committee.distributions.success'))
    showDialog.value = false
    page.value = 0
    await loadDistributions()
  } catch (err) {
    handleApiError(err, 'distribute')
  } finally {
    submitting.value = false
  }
}

defineExpose({ form, submitDistribution })

function changePage(event: { page: number; rows: number }) {
  onPage(event)
  loadDistributions()
}

async function loadDistributions() {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await committeeApi.listDistributions(committeeId, { page: page.value, size: rows.value })
    distributions.value = res.data
    totalRecords.value = res.meta.total
  } catch (err) {
    handleApiError(err, 'listDistributions')
    distributions.value = []
    totalRecords.value = 0
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

function formatDate(dateStr: string): string {
  return dayjs(dateStr).tz(userTimezone.value).format('YYYY/MM/DD HH:mm')
}

/** 履歴の見出し。件名が無い行（アンケート結果等）は種別名と対象IDで表す。 */
function logHeading(log: CommitteeDistributionLog): string {
  if (log.customTitle) return log.customTitle
  const label = t(`committee.distributions.content_types.${log.contentType}`)
  return log.contentId != null ? `${label} #${log.contentId}` : label
}

onMounted(async () => {
  await Promise.all([loadPermission(), loadDistributions()])
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <PageHeader :title="$t('committee.distributions.title')">
      <template v-if="canDistribute" #actions>
        <Button
          :label="$t('committee.distributions.open')"
          icon="pi pi-send"
          data-testid="distribution-open-button"
          @click="openDialog"
        />
      </template>
    </PageHeader>

    <SectionCard v-if="loadFailed">
      <DashboardErrorState testid="committee-distributions-error-state" @retry="loadDistributions" />
    </SectionCard>

    <SectionCard v-else-if="distributions.length === 0">
      <DashboardEmptyState icon="pi pi-list" :message="$t('committee.distributions.empty')" />
    </SectionCard>

    <SectionCard v-else>
      <div class="divide-y">
        <div
          v-for="log in distributions"
          :key="log.id"
          class="py-3 px-1"
          :data-testid="`distribution-row-${log.id}`"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-medium text-gray-900">{{ logHeading(log) }}</span>
                <Tag
                  :value="$t(`committee.distributions.scopes.${log.targetScope}`)"
                  severity="secondary"
                  class="text-xs"
                />
              </div>
              <p v-if="log.customBody" class="mt-1 text-sm text-gray-600 whitespace-pre-wrap">
                {{ log.customBody }}
              </p>
              <div class="mt-1 flex items-center gap-3 text-xs text-gray-500">
                <span>
                  {{ $t('committee.distributions.confirmation_mode') }}:
                  {{ $t(`committee.distributions.confirmation_modes.${log.confirmationMode}`) }}
                </span>
                <span v-if="log.announcementEnabled">
                  <i class="pi pi-megaphone mr-1" />{{ $t('committee.distributions.announced') }}
                </span>
              </div>
            </div>
            <div class="text-sm text-gray-500 shrink-0">
              {{ formatDate(log.createdAt) }}
            </div>
          </div>
        </div>
      </div>
      <Paginator
        :first="page * rows"
        :rows="rows"
        :total-records="totalRecords"
        template="PrevPageLink PageLinks NextPageLink"
        @page="changePage"
      />
    </SectionCard>

    <Dialog
      v-model:visible="showDialog"
      :header="$t('committee.distributions.dialog_title')"
      modal
      :style="{ width: '520px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_content_type') }}</label>
          <Select
            v-model="form.contentType"
            :options="contentTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <template v-if="isCustomMessage">
          <div>
            <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_custom_title') }}</label>
            <InputText v-model="form.customTitle" class="w-full" maxlength="200" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_custom_body') }}</label>
            <Textarea v-model="form.customBody" class="w-full" rows="4" maxlength="5000" />
          </div>
        </template>
        <div v-else>
          <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_content_id') }}</label>
          <InputNumber v-model="form.contentId" :use-grouping="false" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_scope') }}</label>
          <Select
            v-model="form.targetScope"
            :options="scopeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.announcementEnabled" input-id="distribution-announcement" />
          <label for="distribution-announcement" class="text-sm text-gray-700">{{ $t('committee.distributions.field_announcement') }}</label>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_confirmation') }}</label>
          <Select
            v-model="form.confirmationMode"
            :options="confirmationOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div v-if="form.confirmationMode === 'REQUIRED'">
          <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_deadline') }}</label>
          <DatePicker v-model="form.confirmationDeadlineAt" show-time hour-format="24" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">{{ $t('committee.distributions.field_headline') }}</label>
          <InputText v-model="form.customHeadline" class="w-full" maxlength="200" />
        </div>
        <p v-if="formError" class="text-sm text-red-600" data-testid="distribution-form-error">
          {{ $t('committee.distributions.required_missing') }}
        </p>
      </div>
      <template #footer>
        <Button :label="$t('button.cancel')" severity="secondary" @click="showDialog = false" />
        <Button
          :label="$t('committee.distributions.submit')"
          :loading="submitting"
          data-testid="distribution-submit-button"
          @click="submitDistribution"
        />
      </template>
    </Dialog>
  </div>
</template>
