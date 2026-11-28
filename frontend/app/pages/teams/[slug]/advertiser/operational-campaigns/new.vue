<script setup lang="ts">
// F09.19.4b 運用型キャンペーン（バナー広告）新規作成（チームスコープ）
import type { ScopeType } from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { state: gateState, error: gateError, load: reloadGate } = useTeamAccessGate(teamSlug)
const scopeType: ScopeType = 'TEAM'
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
        <OperationalCampaignFormView
          :scope-type="scopeType"
          :scope-id="teamSlug"
          :base-prefix="`/teams/${teamSlug}/advertiser`"
        />
    </template>
  </div>
</template>
