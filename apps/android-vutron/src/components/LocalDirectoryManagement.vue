<script setup lang="ts">
import { useAndroidShellStore } from '../stores/androidShell'
import { useLibraryManagement } from '../composables/libraryManagement'
import { libraryMutationBusy } from '../platform/libraryActivity'
import { libraryTask } from '../platform/libraryTasks'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
const store = useAndroidShellStore()
const management = useLibraryManagement()
</script>
<template>
  <CollapsibleSettingsCard section-id="directory-management" :title="$t('mobile.management.directories')"><div class="directory-management"><div v-for="folder in store.localLibrary.folders" :key="folder.id" class="directory-row"><span>{{ folder.name }}<small>{{ $t('mobile.common.tracks', { count: folder.trackCount }) }}</small></span><button class="text-button danger-text" type="button" :disabled="libraryMutationBusy" @click="management.removeCollection(folder, 'forgetFolder')">{{ $t('mobile.management.forgetFolder') }}</button></div><button v-if="store.localLibrary.excludedTrackCount" class="wide-outline-button" type="button" :disabled="libraryMutationBusy || libraryTask.running" @click="management.restoreRemoved">{{ $t('mobile.management.restoreRemoved') }} · {{ store.localLibrary.excludedTrackCount }}</button></div></CollapsibleSettingsCard>
</template>
<style scoped>
.directory-row { display: flex; align-items: center; gap: 8px; padding: 8px 0; border-top: 1px solid var(--surface); }.directory-row > span { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: 12px; }small { display: block; margin-top: 3px; color: var(--muted); }
</style>
