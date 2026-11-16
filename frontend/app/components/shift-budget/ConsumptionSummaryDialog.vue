<script setup lang="ts">
import type { AllocationResponse, ConsumptionSummaryResponse } from '~/types/shiftBudget'

/** 選択した割当だけを取得し、保存済みの人別・記録時給別内訳を表示する。 */
const props = defineProps<{
  organizationId: string
  allocation: AllocationResponse | null
}>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const api = useShiftBudgetApi()
const summary = ref<ConsumptionSummaryResponse | null>(null)
const loading = ref(false)
const failed = ref(false)
const loadError = shallowRef<unknown>()
let generation = 0

async function load(): Promise<void> {
  const currentGeneration = ++generation
  const allocation = props.allocation
  const organizationId = props.organizationId
  summary.value = null
  loadError.value = undefined
  failed.value = false
  loading.value = false
  if (!allocation) return

  loading.value = true
  try {
    const result = await api.getConsumptionSummary(organizationId, allocation.id)
    if (currentGeneration === generation) summary.value = result
  }
  catch (error) {
    if (currentGeneration === generation) {
      loadError.value = error
      failed.value = true
    }
  }
  finally {
    if (currentGeneration === generation) loading.value = false
  }
}

// 組織・選択の変更と権限喪失によるunmountで、未完了の旧応答を破棄する。
watch([() => props.organizationId, () => props.allocation], load, { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <Dialog
    :visible="allocation !== null"
    :header="t('shiftBudget.consumption.byUser')"
    class="w-[min(48rem,calc(100vw-2rem))]"
    modal
    @update:visible="value => { if (!value) emit('close') }"
  >
    <PageLoading v-if="loading" />
    <DashboardErrorState
      v-else-if="failed"
      :error="loadError"
      :message="t('shiftBudget.dashboard.summaryError')"
      testid="consumption-summary-error"
      @retry="load"
    />
    <template v-else-if="summary">
      <Message v-if="!summary.by_user || summary.flags.includes('BY_USER_HIDDEN')" severity="warn" :closable="false">
        {{ t('shiftBudget.consumption.byUserHidden') }}
      </Message>
      <DashboardEmptyState
        v-else-if="summary.by_user.length === 0"
        :message="t('shiftBudget.consumption.empty')"
      />
      <div v-else class="space-y-6">
        <section
          v-for="user in summary.by_user"
          :key="user.user_id"
          :data-testid="`consumption-summary-user-${user.user_id}`"
          class="min-w-0"
        >
          <h3 class="font-semibold">{{ t('shiftBudget.consumption.user', { id: user.user_id }) }}</h3>
          <p :data-testid="`consumption-summary-total-${user.user_id}`" class="mb-2 text-sm">
            {{ t('shiftBudget.consumption.total', {
              amount: user.amount.toLocaleString(),
              currency: allocation?.currency,
              hours: user.hours.toLocaleString(),
            }) }}
          </p>
          <div class="overflow-x-auto">
            <table class="w-full text-left text-sm">
              <thead>
                <tr>
                  <th class="p-2">{{ t('shiftBudget.consumption.recordedRate') }}</th>
                  <th class="p-2">{{ t('shiftBudget.consumption.hours') }}</th>
                  <th class="p-2">{{ t('shiftBudget.consumption.consumed') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="rate in user.rate_breakdown" :key="rate.hourly_rate_snapshot">
                  <td class="p-2">
                    <span v-if="rate.hourly_rate_snapshot === 0" data-testid="consumption-summary-zero-history">
                      {{ t('shiftBudget.consumption.zeroHistory') }}
                    </span>
                    <span v-else>{{ rate.hourly_rate_snapshot.toLocaleString() }} {{ allocation?.currency }}</span>
                  </td>
                  <td class="p-2">{{ rate.hours.toLocaleString() }}</td>
                  <td class="p-2">{{ rate.amount.toLocaleString() }} {{ allocation?.currency }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </template>
    <template #footer>
      <Button :label="t('shiftBudget.consumption.close')" severity="secondary" class="min-h-11 min-w-11" @click="emit('close')" />
    </template>
  </Dialog>
</template>
