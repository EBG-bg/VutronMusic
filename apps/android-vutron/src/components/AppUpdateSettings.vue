<script setup lang="ts">
import { computed } from 'vue'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
import OriginalIcon from './OriginalIcon.vue'
import { appUpdateState as update, appUpdateAvailable, appUpdateBusy, appUpdateSaving, appUpdatesSupported, appUpdateInstallAllowed, appUpdateAutomaticPaused, checkAppUpdates, setAutomaticAppUpdates, updateApp } from '../platform/appUpdates'
import { appUpdateActionLabel, appUpdateSummary } from '../platform/appUpdatePresentation'
import { appLocale } from '../platform/i18n'

const emit = defineEmits<{ about: [] }>()
const summary = computed(() => appUpdateSummary(update))
const actionLabel = computed(() => appUpdateActionLabel(update))
const checkedAt = computed(() => update.checkedAt > 0 ? new Date(update.checkedAt).toLocaleString(appLocale.value === 'zh' ? 'zh-CN' : appLocale.value === 'zht' ? 'zh-TW' : 'en') : '')
</script>

<template>
  <CollapsibleSettingsCard section-id="app-update" :title="$t('mobile.updates.title')" :subtitle="$t('mobile.updates.current', { version: update.currentVersion })">
    <template #heading>
      <span class="settings-icon coral"><OriginalIcon name="refresh" /></span>
      <div><h3>{{ $t('mobile.updates.title') }}</h3><p>{{ $t('mobile.updates.current', { version: update.currentVersion }) }}<span v-if="appUpdateAvailable"> · {{ $t('mobile.updates.found', { version: update.latestVersion }) }}</span></p></div>
    </template>
    <div class="settings-detail">
      <span>{{ $t('mobile.updates.automatic') }}</span>
      <button class="switch" :class="{ on: update.autoCheck }" type="button" role="switch" :aria-checked="update.autoCheck" :aria-label="$t('mobile.updates.automatic')" :disabled="!appUpdatesSupported || appUpdateSaving" @click="setAutomaticAppUpdates(!update.autoCheck)"><i></i></button>
    </div>
    <p class="setting-note">{{ $t('mobile.updates.defaultOff') }}</p>
    <p v-if="appUpdateAutomaticPaused" class="setting-note">{{ $t('mobile.updates.localPaused') }}</p>
    <p v-if="!appUpdateInstallAllowed" class="setting-note">{{ $t('mobile.updates.informationOnly') }}</p>
    <p class="setting-note update-source">{{ $t('mobile.updates.source') }}<a href="https://github.com/EBG-bg/VutronMusic/releases" target="_blank" rel="noopener noreferrer">GitHub · EBG-bg/VutronMusic</a></p>
    <div class="update-status" :class="{ failed: update.status === 'error' }" role="status" aria-live="polite">{{ appUpdatesSupported ? summary : $t('mobile.updates.androidRequired') }}</div>
    <progress v-if="update.status === 'downloading'" class="update-progress" :value="update.progress" max="100" :aria-label="$t('mobile.updates.progress')"></progress>
    <div class="timer-actions update-actions">
      <button class="secondary-button" type="button" :disabled="!appUpdatesSupported || appUpdateBusy" @click="checkAppUpdates(true)">{{ update.status === 'checking' ? $t('mobile.updates.checking') : $t('mobile.updates.title') }}</button>
      <button v-if="appUpdateAvailable && appUpdateInstallAllowed" class="primary-button" type="button" :disabled="appUpdateBusy" @click="updateApp()">{{ actionLabel }}</button>
      <button class="text-button about-entry" type="button" @click="emit('about')">{{ $t('mobile.about.title') }}</button>
    </div>
    <p v-if="checkedAt" class="setting-note">{{ $t('mobile.updates.lastCheck', { time: checkedAt }) }}</p>
    <p v-if="appUpdateInstallAllowed && update.status === 'permissionRequired'" class="setting-note">{{ $t('mobile.updates.permission') }}</p>
    <details v-if="appUpdateAvailable && update.notes" class="update-notes"><summary>{{ $t('mobile.updates.notes', { version: update.latestVersion }) }}</summary><p>{{ update.notes }}</p></details>
  </CollapsibleSettingsCard>
</template>

<style scoped>
.update-source a { color: var(--accent); overflow-wrap: anywhere; }
.update-status { margin: 10px 0; color: var(--ink); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.update-status.failed { color: var(--accent); }
.update-progress { display: block; width: 100%; height: 6px; margin: 8px 0 13px; accent-color: var(--accent); border-radius: 8px; overflow: hidden; }
.update-actions { flex-wrap: wrap; }
.update-notes { margin-top: 12px; padding: 10px 12px; border-radius: 12px; background: var(--surface); font-size: 11px; }
.update-notes summary { cursor: pointer; color: var(--ink); }
.update-notes p { max-height: 220px; margin: 9px 0 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--muted); line-height: 1.7; }
</style>
