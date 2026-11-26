<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgSlug = String(route.params.slug)
</script>

<template>
  <ParkingScopePage scope-type="organization" :scope-id="orgSlug" />
</template>
