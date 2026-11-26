<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <ParkingScopePage scope-type="team" :scope-id="teamSlug" />
</template>
