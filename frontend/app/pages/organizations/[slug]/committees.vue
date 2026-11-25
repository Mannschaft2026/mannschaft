<script setup lang="ts">
import type { CommitteeSummary } from '~/types/committee'
import type { MemberResponse } from '~/types/member'
import type { components } from '~/types/generated'

/** BE CommitteeCreateRequest.purposeTag の enum（backend の定義順）。 */
type PurposeTag = NonNullable<components['schemas']['CommitteeCreateRequest']['purposeTag']>
/** 生成型の enum と網羅的に一致しないとコンパイルエラーになる（BE との不一致検出）。 */
const PURPOSE_TAG_LABEL_KEYS = {
  TOURNAMENT: true,
  GOVERNANCE: true,
  ELECTION: true,
  PROJECT: true,
  OTHER: true,
} satisfies Record<PurposeTag, true>
const PURPOSE_TAGS = Object.keys(PURPOSE_TAG_LABEL_KEYS) as PurposeTag[]
/** 委員長候補を全件取得するときの 1 ページの件数。 */
const MEMBER_PAGE_SIZE = 100
/** 作成フォームでエラーを表示できる BE フィールド名。 */
const DISPLAYED_FIELDS = ['name', 'description', 'purposeTag', 'initialChairUserId']

definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const committeeApi = useCommitteeApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()
const orgApi = useOrganizationApi()
const authStore = useAuthStore()
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

const committees = ref<CommitteeSummary[]>([])
const loading = ref(true)
const showCreateDialog = ref(false)
const creating = ref(false)
/** 取得失敗は「委員会なし」ではない。空状態へフォールバックせずエラー状態を出す。 */
const loadFailed = ref(false)
const { page, rows, totalRecords, onPage } = usePagination()

function changePage(event: { page: number; rows: number }) {
  onPage(event)
  loadCommittees()
}

// 作成フォームの値
const createForm = ref({
  name: '',
  description: '',
  purposeTag: null as PurposeTag | null,
  initialChairUserId: null as number | null,
  visibilityToOrg: 'NAME_ONLY' as 'HIDDEN' | 'NAME_ONLY' | 'NAME_AND_PURPOSE',
})
/** 400 で返った欄別エラー（キーは BE のフィールド名）。 */
const fieldErrors = ref<Record<string, string>>({})

const purposeTagOptions = computed(() =>
  PURPOSE_TAGS.map((value) => ({ value, label: t(`committee.purpose_tag.${value}`) })),
)

// 委員長候補（組織メンバーのみ。BE は委員長が組織メンバーであることを要求する）
const chairCandidates = ref<MemberResponse[]>([])
const chairLoading = ref(false)
const chairLoadError = shallowRef<unknown>()
const chairLoadFailed = ref(false)
let chairRequestId = 0

async function loadChairCandidates() {
  const requestId = ++chairRequestId
  chairLoading.value = true
  chairLoadFailed.value = false
  chairLoadError.value = undefined
  try {
    let all: MemberResponse[] = []
    let current = 0
    for (;;) {
      const res = await orgApi.getMembers(orgSlug, { page: current, size: MEMBER_PAGE_SIZE })
      all.push(...res.data)
      current++
      if (current >= res.meta.totalPages || res.data.length === 0) break
    }
    if (requestId !== chairRequestId) return
    // memberships 行を持たない GUEST は BE の checkMembership で 403 になる。退会者（displayName=null）は空ラベルになる。
    all = all.filter((m) => m.roleName !== 'GUEST' && m.displayName != null)
    chairCandidates.value = all
    const me = authStore.currentUser?.id
    if (createForm.value.initialChairUserId == null && me != null && all.some((m) => m.userId === me)) {
      createForm.value.initialChairUserId = me
    }
  } catch (err) {
    if (requestId !== chairRequestId) return
    chairCandidates.value = []
    chairLoadError.value = err
    chairLoadFailed.value = true
    handleApiError(err, 'listOrganizationMembers')
  } finally {
    if (requestId === chairRequestId) chairLoading.value = false
  }
}

function openCreateDialog() {
  fieldErrors.value = {}
  showCreateDialog.value = true
  void loadChairCandidates()
}

function resetCreateForm() {
  createForm.value = {
    name: '',
    description: '',
    purposeTag: null,
    initialChairUserId: null,
    visibilityToOrg: 'NAME_ONLY',
  }
}

const canSubmit = computed(() =>
  !!createForm.value.name.trim() && createForm.value.initialChairUserId != null && !creating.value,
)

async function loadCommittees() {
  loading.value = true
  loadFailed.value = false
  try {
    const res = await committeeApi.listCommittees(orgSlug, { page: page.value, size: rows.value })
    committees.value = res.data
    totalRecords.value = res.meta.total
  } catch {
    committees.value = []
    totalRecords.value = 0
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function onCreateCommittee() {
  if (creating.value) return
  if (!createForm.value.name.trim() || createForm.value.initialChairUserId == null) return
  creating.value = true
  fieldErrors.value = {}
  try {
    await committeeApi.createCommittee(orgSlug, {
      name: createForm.value.name,
      description: createForm.value.description || null,
      purposeTag: createForm.value.purposeTag,
      initialChairUserId: createForm.value.initialChairUserId,
      visibilityToOrg: createForm.value.visibilityToOrg,
    })
    notification.success(t('committee.label'), t('committee.list.create'))
    showCreateDialog.value = false
    resetCreateForm()
    await loadCommittees()
  } catch (err) {
    const errors = getFieldErrors(err)
    // 表示欄に対応しないキーだけの 400 は握りつぶさず handleApiError に回す
    const shown = Object.keys(errors).filter((k) => DISPLAYED_FIELDS.includes(k))
    if (shown.length > 0) {
      fieldErrors.value = errors
    } else {
      handleApiError(err, 'createCommittee')
    }
  } finally {
    creating.value = false
  }
}

function statusSeverity(status: CommitteeSummary['status']): string {
  switch (status) {
    case 'ACTIVE': return 'success'
    case 'CLOSED': return 'warning'
    case 'ARCHIVED': return 'danger'
    case 'CANCELLED_DRAFT': return 'danger'
    default: return 'secondary'
  }
}

function goToCommittee(id: number) {
  navigateTo(`/committees/${id}`)
}

onMounted(async () => {
  await loadPermissions()
  await loadCommittees()
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="$t('committee.list.title')" />
      <Button
        v-if="isAdminOrDeputy"
        :label="$t('committee.list.create')"
        icon="pi pi-plus"
        data-testid="committee-create-open"
        @click="openCreateDialog"
      />
    </div>

    <SectionCard v-if="loadFailed">
      <DashboardErrorState testid="committees-list-error-state" @retry="loadCommittees" />
    </SectionCard>

    <SectionCard v-else-if="committees.length === 0">
      <DashboardEmptyState icon="pi pi-users" :message="$t('committee.list.empty')" />
    </SectionCard>

    <SectionCard v-else>
      <div class="divide-y">
        <div
          v-for="committee in committees"
          :key="committee.id"
          class="flex cursor-pointer items-center justify-between py-3 px-1 hover:bg-gray-50 transition-colors rounded"
          @click="goToCommittee(committee.id)"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2">
              <span class="font-medium text-gray-900 truncate">{{ committee.name }}</span>
              <Tag
                :value="$t(`committee.status.${committee.status}`)"
                :severity="statusSeverity(committee.status)"
                class="text-xs"
              />
            </div>
            <p v-if="committee.description" class="mt-1 text-sm text-gray-500 truncate">
              {{ committee.description }}
            </p>
          </div>
          <div class="ml-4 flex items-center gap-3 text-sm text-gray-500 shrink-0">
            <span>
              <i class="pi pi-users mr-1" />{{ committee.memberCount }}
            </span>
            <i class="pi pi-chevron-right text-gray-400" />
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

    <!-- 委員会作成ダイアログ -->
    <Dialog
      v-model:visible="showCreateDialog"
      :header="$t('committee.create_dialog.title')"
      modal
      :closable="!creating"
      :close-on-escape="!creating"
      :style="{ width: '480px' }"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.name') }}<span class="ml-1 text-red-500">*</span>
          </label>
          <InputText
            v-model="createForm.name"
            class="w-full"
            :invalid="!!fieldErrors.name"
            data-testid="committee-create-name"
          />
          <small v-if="fieldErrors.name" class="mt-1 block text-red-500" data-testid="committee-create-name-error">
            {{ fieldErrors.name }}
          </small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.chair') }}<span class="ml-1 text-red-500">*</span>
          </label>
          <DashboardErrorState
            v-if="chairLoadFailed"
            :error="chairLoadError"
            :show-retry="true"
            testid="committee-create-chair-load-error"
            @retry="loadChairCandidates"
          />
          <Select
            v-else
            v-model="createForm.initialChairUserId"
            :options="chairCandidates"
            option-label="displayName"
            option-value="userId"
            :placeholder="$t('committee.create_dialog.chair_placeholder')"
            :loading="chairLoading"
            :invalid="!!fieldErrors.initialChairUserId"
            filter
            class="w-full"
            data-testid="committee-create-chair"
          />
          <small v-if="fieldErrors.initialChairUserId" class="mt-1 block text-red-500" data-testid="committee-create-chair-error">
            {{ fieldErrors.initialChairUserId }}
          </small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.description') }}
          </label>
          <Textarea
            v-model="createForm.description"
            class="w-full"
            rows="3"
            auto-resize
            :invalid="!!fieldErrors.description"
          />
          <small v-if="fieldErrors.description" class="mt-1 block text-red-500" data-testid="committee-create-description-error">
            {{ fieldErrors.description }}
          </small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.purpose_tag') }}
          </label>
          <Select
            v-model="createForm.purposeTag"
            :options="purposeTagOptions"
            option-label="label"
            option-value="value"
            :placeholder="$t('committee.create_dialog.purpose_placeholder')"
            show-clear
            :invalid="!!fieldErrors.purposeTag"
            class="w-full"
            data-testid="committee-create-purpose"
          />
          <small v-if="fieldErrors.purposeTag" class="mt-1 block text-red-500" data-testid="committee-create-purpose-error">
            {{ fieldErrors.purposeTag }}
          </small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium text-gray-700">
            {{ $t('committee.field.visibility_to_org') }}
          </label>
          <Select
            v-model="createForm.visibilityToOrg"
            :options="[
              { label: $t('committee.visibility.HIDDEN'), value: 'HIDDEN' },
              { label: $t('committee.visibility.NAME_ONLY'), value: 'NAME_ONLY' },
              { label: $t('committee.visibility.NAME_AND_PURPOSE'), value: 'NAME_AND_PURPOSE' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          :disabled="creating"
          @click="showCreateDialog = false"
        />
        <Button
          :label="$t('committee.list.create')"
          icon="pi pi-check"
          :loading="creating"
          :disabled="!canSubmit"
          data-testid="committee-create-submit"
          @click="onCreateCommittee"
        />
      </template>
    </Dialog>
  </div>
</template>
