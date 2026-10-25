<script setup lang="ts">
import { computed } from 'vue'
import { appUpdateState as update, appUpdateBusy, appUpdateInstallAllowed, showAppUpdateNotice, dismissAppUpdateNotice, updateApp } from '../platform/appUpdates'
import { appUpdateActionLabel, appUpdateSummary } from '../platform/appUpdatePresentation'
const summary = computed(() => appUpdateSummary(update))
const actionLabel = computed(() => appUpdateActionLabel(update))
</script>

<template>
  <Transition name="notice-fade">
    <aside v-if="showAppUpdateNotice" class="app-update-notice" :aria-label="$t('mobile.updates.title')" role="status" aria-live="polite">
      <div><b>{{ $t('mobile.updates.found', { version: update.latestVersion }) }}</b><p>{{ summary }}</p></div>
      <progress v-if="update.status === 'downloading'" :value="update.progress" max="100" :aria-label="$t('mobile.updates.progress')"></progress>
      <p v-if="!appUpdateInstallAllowed">{{ $t('mobile.updates.informationOnly') }}</p>
      <div class="app-update-notice-actions"><button class="text-button" type="button" @click="dismissAppUpdateNotice()">{{ $t('mobile.updates.later') }}</button><button v-if="appUpdateInstallAllowed" class="secondary-button" type="button" :disabled="appUpdateBusy" @click="updateApp()">{{ actionLabel }}</button></div>
    </aside>
  </Transition>
</template>

<style scoped>
.app-update-notice { position: fixed; z-index: 190; left: 16px; right: 16px; bottom: calc(112px + env(safe-area-inset-bottom)); max-width: 440px; margin-inline: auto; padding: 14px; border: 1px solid var(--surface); border-radius: 16px; color: var(--ink); background: var(--color-body-bg); box-shadow: 0 6px 24px rgb(0 0 0 / 18%); }
.app-update-notice b { font-size: 13px; }
.app-update-notice p { margin: 6px 0 0; color: var(--muted); font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.app-update-notice progress { width: 100%; height: 6px; margin-top: 10px; accent-color: var(--accent); }
.app-update-notice-actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 10px; }
</style>
