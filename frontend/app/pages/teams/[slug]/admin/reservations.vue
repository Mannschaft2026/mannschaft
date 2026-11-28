<script setup lang="ts">
// 子ページはNuxt既定のslugを含むpage keyで再生成され、別チームの取得状態を共有しない。
definePageMeta({
  layout: 'team',
  middleware: ['auth', 'admin-console'],
})

const route = useRoute()
const teamSlug = String(route.params.slug)
const { team, state: gateState, error: gateError, load: reloadGate } = useTeamAccessGate(teamSlug)
const { t } = useI18n()

useHead({ title: () => `${t('adminConsole.cards.reservations.title')} | Mannschaft` })
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
        <div class="space-y-6 p-4 md:p-6" data-testid="team-admin-reservations">
          <PageHeader
            :title="t('adminConsole.cards.reservations.title')"
            :back-to="`/teams/${teamSlug}/admin`"
            :back-label="t('adminConsole.team.title')"
          />
          <TeamReservationsPanel
            v-if="team"
            :team-id="teamSlug"
            :team-timezone="team.timezone ?? 'Asia/Tokyo'"
            :management-view="true"
          />
        </div>
    </template>
  </div>
</template>
