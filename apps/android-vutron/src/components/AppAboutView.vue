<script setup lang="ts">
import { buildInfo } from '../platform/buildInfo'
import originalLogo from '../../../../src/renderer/assets/images/vutronmusic-icon.png?url'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
import AboutLegalDocument from './AboutLegalDocument.vue'
const documents = ['privacy', 'usage', 'license', 'notice', 'thirdParty'] as const
</script>

<template>
  <section class="page settings-page about-page">
    <div class="section-heading"><div><div class="eyebrow">VUTRON MUSIC</div><h2>{{ $t('mobile.about.title') }}</h2></div></div>
    <section class="settings-card about-identity"><img :src="originalLogo" alt="" width="72" height="72" /><h2>{{ buildInfo.displayName }}</h2><p>{{ $t('mobile.updates.current', { version: buildInfo.version }) }} · {{ $t('mobile.about.versionCode', { code: buildInfo.versionCode }) }}</p><p>{{ $t('mobile.about.maintainer') }} EBG-bg</p></section>
    <section class="settings-card about-project"><h3>{{ $t('mobile.about.project') }}</h3><p>{{ $t('mobile.about.description') }}</p><p>{{ $t('mobile.about.licenseSummary') }}</p><div class="about-links"><a class="secondary-button" href="https://github.com/EBG-bg/VutronMusic" target="_blank" rel="noopener noreferrer">{{ $t('mobile.about.repository') }}</a><a class="secondary-button" href="https://github.com/stark81/VutronMusic" target="_blank" rel="noopener noreferrer">{{ $t('mobile.about.upstream') }}</a><a class="text-button" href="https://github.com/EBG-bg/VutronMusic/issues" target="_blank" rel="noopener noreferrer">{{ $t('mobile.about.feedback') }}</a></div><p class="setting-note">{{ $t('mobile.about.externalHint') }}</p></section>
    <CollapsibleSettingsCard v-for="documentId in documents" :key="documentId" :section-id="'about-' + documentId" :title="$t('mobile.about.' + documentId)" :remember="false"><AboutLegalDocument :document-id="documentId" /></CollapsibleSettingsCard>
  </section>
</template>

<style scoped>
.about-identity { text-align:center; }.about-identity img { display:block; margin:2px auto 14px; border-radius:18px; }.about-identity h2 { font-size:19px; overflow-wrap:anywhere; }.about-identity p,.about-project p { color:var(--muted); font-size:12px; line-height:1.7; }.about-project h3 { margin-top:0; font-size:15px; }.about-links { display:flex; flex-wrap:wrap; gap:8px; margin-top:14px; }.about-links a { display:inline-flex; align-items:center; text-decoration:none; }.about-page { padding-bottom:180px; }
</style>
