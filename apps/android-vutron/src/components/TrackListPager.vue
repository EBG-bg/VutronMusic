<script setup lang="ts">
defineProps<{ more: boolean; label?: string }>()
const emit = defineEmits<{ more: [] }>()
</script>

<template>
  <button v-if="more" class="load-more-button track-list-pager" type="button" @click="emit('more')">{{ label || $t('mobile.library.moreTracks') }}</button>
</template>

<style scoped>
.track-list-pager { width: 100%; margin: 12px 0 4px; }
</style>
