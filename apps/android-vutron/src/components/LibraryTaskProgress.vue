<script setup lang="ts">
import { libraryTask, cancelLibraryTask } from '../platform/libraryTasks'
</script>
<template>
  <Transition name="notice-fade"><section v-if="libraryTask.running" class="library-task-progress" role="status" aria-live="polite"><div><b>{{ $t('mobile.management.task_' + libraryTask.kind) }}</b><span>{{ libraryTask.done }}<template v-if="libraryTask.total > 0"> / {{ libraryTask.total }}</template></span></div><progress v-if="libraryTask.total > 0" :value="libraryTask.done" :max="libraryTask.total"></progress><button class="text-button" type="button" :disabled="libraryTask.cancelled" @click="cancelLibraryTask">{{ $t(libraryTask.cancelled ? 'mobile.management.cancelling' : 'mobile.management.cancelTask') }}</button></section></Transition>
</template>
<style scoped>
.library-task-progress { position: fixed; z-index: 280; left: 16px; right: 16px; bottom: calc(100px + env(safe-area-inset-bottom)); max-width: 540px; margin: auto; display: flex; align-items: center; gap: 12px; border: 1px solid var(--surface); padding: 12px 16px; border-radius: 14px; color: var(--ink); background: var(--color-body-bg); box-shadow: 0 6px 24px #0003; }
div { flex: 1; display: flex; gap: 8px; flex-wrap: wrap; font-size: 12px; } progress { max-width: 30%; height: 5px; accent-color: var(--accent); }
</style>
