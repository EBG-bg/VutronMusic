<script setup lang="ts">
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
import { useUserBackup } from '../composables/userBackup'
const { busy, message, exportBackup, importBackup } = useUserBackup()
</script>
<template>
  <CollapsibleSettingsCard section-id="user-backup" :title="$t('mobile.backup.title')" :subtitle="$t('mobile.backup.subtitle')">
    <p class="setting-note">{{ $t('mobile.backup.description') }}</p>
    <p class="setting-note">{{ $t('mobile.backup.restoreHint') }}</p>
    <div class="timer-actions"><button class="secondary-button" type="button" :disabled="busy" @click="exportBackup">{{ $t('mobile.backup.export') }}</button><button class="secondary-button" type="button" :disabled="busy" @click="importBackup">{{ $t('mobile.backup.import') }}</button></div>
    <p v-if="busy || message" class="setting-note" role="status">{{ busy ? $t('mobile.backup.working') : message }}</p>
  </CollapsibleSettingsCard>
</template>
