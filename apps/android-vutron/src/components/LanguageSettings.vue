<script setup lang="ts">
import { appLocale, setAppLocale } from '../platform/i18n'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
function change(event: Event) { setAppLocale((event.target as HTMLSelectElement).value) }
</script>

<template>
  <CollapsibleSettingsCard section-id="language" :title="$t('mobile.language.title')" :subtitle="$t('settings.general.language.text')">
    <div class="settings-detail"><label for="app-language">{{ $t('settings.general.language.text') }}</label><select id="app-language" :value="appLocale" @change="change"><option value="zh">简体中文</option><option value="zht">繁體中文</option><option value="en">English</option></select></div>
    <p class="setting-note">{{ $t('mobile.language.description') }}</p>
  </CollapsibleSettingsCard>
</template>

<style scoped>
select { max-width: 65%; padding: 7px 10px; border: 1px solid var(--surface); border-radius: 10px; color: var(--ink); background: var(--color-body-bg); font: inherit; }
</style>
