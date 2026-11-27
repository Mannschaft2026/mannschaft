<script setup lang="ts">
/**
 * 旧 URL `/teams/{slug}/friends` の救済リダイレクト。
 * フレンドチームの実体は「フレンドチーム」タブ（`nav.vue`）に統合済み。
 * 使い方・フォロー・SUPPORTER 注意バナーはすべて nav.vue 側にある。
 */
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = String(route.params.slug)
const { state: gateState, error: gateError, load: reloadGate, onOk: onGateOk } = useTeamAccessGate(teamSlug)
onGateOk(() => navigateTo(`/teams/${teamSlug}/nav`, { replace: true }))
</script>

<template>
  <div>
    <ScopeNotFound v-if="gateState === 'notFound'" />
    <div v-else-if="gateState === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="gateState === 'error'" :error="gateError" @retry="reloadGate" />
    <template v-else>
      <div />
    </template>
  </div>
</template>
