<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)

/**
 * slug → 数値 teamId を解決する。同時にチームの閲覧可否（403/404）を判定する。
 * SharedFolderController の scope_id は数値文字列を要求するため、slug をそのまま渡すと
 * parseScopeId で NumberFormatException が発生し 404 になる。
 *
 * 403・404 のときは空表示に化けさせず、共通の「見つかりません」を表示し、
 * ファイル共有 API は呼ばない（CMP-261124-0159 PR-F1。403 の理由は出さない: CMP-261125-1108）。
 */
const { team, state, error, load } = useTeamAccessGate(teamSlug)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader title="ファイル共有" /></div>
    <template v-if="state === 'ok' && team && team.numericId != null">
      <!--
        scope-id には**数値 ID の string 表現**を渡す（slug では 404 になる）。
        `TeamResponse.id` は slug 移行後 URL 識別子（= slug）を返すため、ここで `id` を渡すと
        `GET /api/v1/files/folders?scope_type=TEAM&scope_id=fc-u-18` が
        404 FILE_SHARING_001 になり、フォルダが 1 件も表示されない（「ファイルがありません」）。
        内部 BIGINT が要るこの用途では `numericId` が正しい（types/team.ts の TeamResponse 参照）。
      -->
      <FileBrowser scope-type="TEAM" :scope-id="String(team.numericId)" />
    </template>
    <ScopeNotFound v-else-if="state === 'notFound'" />
    <div v-else-if="state === 'loading'" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
    <DashboardErrorState v-else-if="state === 'error'" :error="error" @retry="load" />
  </div>
</template>
