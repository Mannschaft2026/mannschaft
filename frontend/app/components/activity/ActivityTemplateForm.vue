<script setup lang="ts">
import type { ActivityTemplate } from '~/types/activity'

interface FieldRow {
  /** 項目の識別子。保存済み項目は読み込み時の値を保持し、新規項目は追加時に一度だけ採番する（並べ替え・改名で変えない）。 */
  fieldKey: string
  /** 保存済み項目の単位・プレースホルダー。更新は無条件に上書きされるため、読み込んだ値を送り返す。 */
  unit: string | null
  placeholder: string | null
  label: string
  fieldType: 'TEXT' | 'NUMBER' | 'DATE' | 'DATETIME' | 'SELECT' | 'CHECKBOX' | 'TEXTAREA'
  required: boolean
  isAggregatable: boolean
  optionsRaw: string
}

const props = defineProps<{
  template?: ActivityTemplate | null
  visible: boolean
  /** 頑張り記録の指標セクションを出してよいか（ADMIN・委任 DEPUTY）。 */
  canManageEffortSettings?: boolean
  /** 新規作成時の保存先スコープ（クエリ scope_type / scope_id に使う。scopeId は数値 ID） */
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
  /** 最新のテンプレートを読み込み直してほしい（照合漏れのエラー後） */
  reload: []
}>()

const { createTemplate, updateTemplate } = useActivityApi()
const { showError, showSuccess } = useNotification()
const confirm = useConfirm()

const name = ref('')
const description = ref('')
const fields = ref<FieldRow[]>([])
const saving = ref(false)
/** 読み込み時点の既存 fieldKey（保存時に removedFieldKeys を求める） */
const originalKeys = ref<string[]>([])
/** fields[n].fieldKey のエラーになった行の添字 */
const badKeyRows = ref<Set<number>>(new Set())
/** fields / removedFieldKeys[n] のエラー（他の人が先に更新した可能性） */
const staleError = ref(false)

const { t } = useI18n()

const fieldTypeOptions = computed(() => [
  { label: t('activity.template.field_type.TEXT'), value: 'TEXT' },
  { label: t('activity.template.field_type.NUMBER'), value: 'NUMBER' },
  { label: t('activity.template.field_type.DATE'), value: 'DATE' },
  { label: t('activity.template.field_type.DATETIME'), value: 'DATETIME' },
  { label: t('activity.template.field_type.SELECT'), value: 'SELECT' },
  { label: t('activity.template.field_type.CHECKBOX'), value: 'CHECKBOX' },
  { label: t('activity.template.field_type.TEXTAREA'), value: 'TEXTAREA' },
])

/** 新規項目の fieldKey を採番する（f_ + 16 進12桁）。既存・今回の削除・入力中のキーと衝突したら引き直す。 */
function newFieldKey(): string {
  const taken = new Set([...originalKeys.value, ...fields.value.map((f) => f.fieldKey)])
  for (;;) {
    const bytes = crypto.getRandomValues(new Uint8Array(6))
    const key = 'f_' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    if (!taken.has(key)) return key
  }
}

function initForm() {
  badKeyRows.value = new Set()
  staleError.value = false
  originalKeys.value = (props.template?.fields ?? []).map((f) => f.fieldKey)
  if (props.template) {
    name.value = props.template.name
    description.value = props.template.description ?? ''
    fields.value = (props.template.fields ?? []).map((f) => ({
      fieldKey: f.fieldKey,
      unit: f.unit,
      placeholder: f.placeholder,
      label: f.fieldLabel,
      fieldType: f.fieldType as FieldRow['fieldType'],
      required: f.isRequired,
      isAggregatable: f.isAggregatable,
      optionsRaw: (() => {
        // SELECT 型の選択肢 JSON 文字列を編集用のカンマ区切りに展開する
        if (f.fieldType !== 'SELECT' || !f.optionsJson) return ''
        try {
          const parsed: unknown = JSON.parse(f.optionsJson)
          return Array.isArray(parsed) ? parsed.map((o) => String(o)).join(', ') : ''
        } catch {
          return ''
        }
      })(),
    }))
  } else {
    name.value = ''
    description.value = ''
    fields.value = []
  }
}

watch(
  () => props.visible,
  (val) => {
    if (val) initForm()
  },
  { immediate: true },
)

// 再読込でテンプレートが差し替わったら、フォームを最新の内容で初期化し直す
watch(
  () => props.template,
  () => {
    if (props.visible) initForm()
  },
)

function addField() {
  fields.value.push({
    fieldKey: newFieldKey(),
    unit: null,
    placeholder: null,
    label: '',
    fieldType: 'TEXT',
    required: false,
    isAggregatable: false,
    optionsRaw: '',
  })
}

function removeField(index: number) {
  fields.value.splice(index, 1)
}

function onFieldTypeChange(index: number) {
  const f = fields.value[index]
  if (!f) return
  if (f.fieldType !== 'NUMBER') {
    f.isAggregatable = false
  }
  if (f.fieldType !== 'SELECT') {
    f.optionsRaw = ''
  }
}

/** 400 の fieldErrors から、行の強調と案内を出す。該当しないエラーなら false。 */
function applyFieldErrors(error: unknown): boolean {
  const data = (error as { data?: { error?: { code?: string; fieldErrors?: { field?: string }[] } } }).data
  if (data?.error?.code !== 'COMMON_001') return false
  const bad = new Set<number>()
  let stale = false
  for (const e of data.error.fieldErrors ?? []) {
    const m = /^fields\[(\d+)\]\.fieldKey$/.exec(e.field ?? '')
    if (m) bad.add(Number(m[1]))
    else if (e.field === 'fields' || /^removedFieldKeys/.test(e.field ?? '')) stale = true
  }
  badKeyRows.value = bad
  staleError.value = stale
  return bad.size > 0 || stale
}

/** 未保存の入力を捨てて最新を読み込み直す（確認してから） */
function confirmReload() {
  confirm.require({
    message: t('activity.template.error.reload_confirm'),
    header: t('activity.template.error.reload'),
    accept: () => emit('reload'),
  })
}

async function handleSave() {
  if (!name.value.trim()) {
    showError(t('activity.template.name_required'))
    return
  }
  saving.value = true
  try {
    const rows = fields.value.map((f) => ({
      fieldKey: f.fieldKey,
      fieldLabel: f.label,
      fieldType: f.fieldType,
      isRequired: f.required,
      isAggregatable: f.isAggregatable,
      optionsJson:
        f.fieldType === 'SELECT'
          ? JSON.stringify(
              f.optionsRaw
                .split(',')
                .map((s) => s.trim())
                .filter(Boolean),
            )
          : undefined,
      placeholder: f.placeholder,
      unit: f.unit,
    }))
    const body = {
      name: name.value.trim(),
      description: description.value.trim() || undefined,
      fields: rows,
    }
    if (props.template) {
      // 削除は明示する。編集操作（ラベル変更・並べ替え）から削除＋再追加を作らない
      const current = new Set(rows.map((r) => r.fieldKey))
      const removedFieldKeys = originalKeys.value.filter((k) => !current.has(k))
      await updateTemplate(props.template.id, { ...body, removedFieldKeys })
    } else {
      await createTemplate(props.scopeType, props.scopeId, body)
    }
    showSuccess(t('activity.template.save_success'))
    emit('saved')
    emit('update:visible', false)
  } catch (error: unknown) {
    if (!applyFieldErrors(error)) showError(t('activity.template.save_error'))
  } finally {
    saving.value = false
  }
}

function handleCancel() {
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    style="width: 700px; max-width: 95vw"
    :header="template ? $t('activity.template.dialog_title_edit') : $t('activity.template.dialog_title_create')"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <Message v-if="badKeyRows.size > 0" severity="error" :closable="false">
        {{ $t('activity.template.error.field_key') }}
      </Message>
      <Message v-if="staleError" severity="warn" :closable="false">
        <div class="flex items-center gap-3">
          <span>{{ $t('activity.template.error.stale') }}</span>
          <Button :label="$t('activity.template.error.reload')" size="small" outlined @click="confirmReload" />
        </div>
      </Message>
      <!-- 基本情報 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('activity.template.name_label') }} <span class="text-red-500">*</span></label>
        <InputText v-model="name" :placeholder="$t('activity.template.name_placeholder')" class="w-full" />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('activity.template.description_label') }}</label>
        <Textarea v-model="description" rows="2" :placeholder="$t('activity.template.description_placeholder')" class="w-full" />
      </div>

      <!-- フィールド定義テーブル -->
      <div>
        <div class="mb-2 flex items-center justify-between">
          <span class="text-sm font-medium">{{ $t('activity.template.field_section_title') }}</span>
          <Button
            :label="$t('activity.template.add_field')"
            icon="pi pi-plus"
            size="small"
            outlined
            @click="addField"
          />
        </div>

        <div v-if="fields.length === 0" class="rounded-lg border border-dashed border-surface-300 py-6 text-center text-sm text-surface-400">
          {{ $t('activity.template.fields_empty') }}
        </div>

        <div v-else class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-surface-200 text-left text-xs text-surface-500">
                <th class="pb-2 pr-2">{{ $t('activity.template.field_name_label') }}</th>
                <th class="pb-2 pr-2">{{ $t('activity.template.table_type') }}</th>
                <th class="pb-2 pr-2 text-center">{{ $t('activity.template.table_required') }}</th>
                <th class="pb-2 pr-2 text-center">{{ $t('activity.template.table_aggregatable') }}</th>
                <th class="pb-2 pr-2">{{ $t('activity.template.table_options') }}</th>
                <th class="pb-2 text-center">{{ $t('activity.template.table_actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(field, index) in fields"
                :key="field.fieldKey"
                class="border-b border-surface-100"
                :class="{ 'bg-red-50 dark:bg-red-950': badKeyRows.has(index) }"
              >
                <td class="py-2 pr-2">
                  <InputText
                    v-model="field.label"
                    :placeholder="$t('activity.template.field_name_placeholder')"
                    class="w-full"
                    size="small"
                  />
                </td>
                <td class="py-2 pr-2">
                  <Select
                    v-model="field.fieldType"
                    :options="fieldTypeOptions"
                    option-label="label"
                    option-value="value"
                    class="w-32"
                    size="small"
                    @change="onFieldTypeChange(index)"
                  />
                </td>
                <td class="py-2 pr-2 text-center">
                  <Checkbox v-model="field.required" :binary="true" />
                </td>
                <td class="py-2 pr-2 text-center">
                  <Checkbox
                    v-model="field.isAggregatable"
                    :binary="true"
                    :disabled="field.fieldType !== 'NUMBER'"
                  />
                </td>
                <td class="py-2 pr-2">
                  <InputText
                    v-if="field.fieldType === 'SELECT'"
                    v-model="field.optionsRaw"
                    :placeholder="$t('activity.template.options_placeholder')"
                    class="w-full"
                    size="small"
                  />
                  <span v-else class="text-surface-300">—</span>
                </td>
                <td class="py-2 text-center">
                  <Button
                    icon="pi pi-trash"
                    text
                    rounded
                    severity="danger"
                    size="small"
                    @click="removeField(index)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 頑張り記録の指標（保存済みテンプレートのみ・別 API で別保存） -->
      <EffortTemplateSettingsSection
        v-if="template && canManageEffortSettings && template.scopeType === 'TEAM'"
        :team-id="template.scopeId"
        :template-id="template.id"
      />
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="$t('button.cancel')" text @click="handleCancel" />
        <Button :label="$t('button.save')" icon="pi pi-check" :loading="saving" @click="handleSave" />
      </div>
    </template>
  </Dialog>
</template>
