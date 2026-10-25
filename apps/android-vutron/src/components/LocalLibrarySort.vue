<script setup lang="ts">
import type { LocalTrackOrder } from '../composables/libraryPresentation'
defineProps<{ modelValue: LocalTrackOrder }>()
const emit = defineEmits<{ 'update:modelValue': [value: LocalTrackOrder] }>()
function change(event: Event) { emit('update:modelValue', (event.target as HTMLSelectElement).value as LocalTrackOrder) }
</script>

<template>
  <label class="local-sort-control"><span>{{ $t('mobile.library.sort') }}</span><select :value="modelValue" :aria-label="$t('mobile.library.sortLabel')" @change="change"><option value="original">{{ $t('mobile.library.originalOrder') }}</option><option value="title">{{ $t('mobile.library.title') }}</option><option value="artist">{{ $t('nav.artist') }}</option><option value="album">{{ $t('nav.album') }}</option></select></label>
</template>

<style scoped>
.local-sort-control { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin: 10px 0; color: var(--muted); font-size: 11px; }
.local-sort-control select { padding: 7px 10px; border: 1px solid var(--surface); border-radius: 10px; color: var(--ink); background: var(--color-body-bg); font: inherit; }
</style>
