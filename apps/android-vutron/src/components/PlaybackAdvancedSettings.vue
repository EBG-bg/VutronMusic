<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { vutronPlugin, type VutronPlaybackTuning } from '../platform/vutronPlugin'
import { useAndroidShellStore } from '../stores/androidShell'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
const store = useAndroidShellStore()
const settings = ref<VutronPlaybackTuning>({ speed: 1, pitch: 1, prefetchWifi: false, actualSpeed: 1, actualPitch: 1, warning: '' })
const busy = ref(false), error = ref('')
async function update(values: Partial<Pick<VutronPlaybackTuning, 'speed' | 'pitch' | 'prefetchWifi'>>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    settings.value = await vutronPlugin.updatePlaybackTuning(values)
    const playback = await vutronPlugin.getPlaybackState()
    store.playback = { ...store.playback, ...playback }
  } catch (e) { error.value = e instanceof Error ? e.message : '保存播放设置失败' }
  finally { busy.value = false }
}
onMounted(async () => { try { settings.value = await vutronPlugin.getPlaybackTuning() } catch (e) { error.value = e instanceof Error ? e.message : '读取播放设置失败' } })
</script>
<template>
  <CollapsibleSettingsCard section-id="playback-advanced" :title="$t('mobile.advancedPlayback.title')" :subtitle="$t('mobile.advancedPlayback.subtitle')">
    <label class="settings-detail"><span>{{ $t('mobile.advancedPlayback.speed') }}</span><select :value="settings.speed" :disabled="busy" :aria-label="$t('mobile.advancedPlayback.speed')" @change="update({ speed: Number(($event.target as HTMLSelectElement).value) })"><option v-for="rate in [.5, .75, 1, 1.25, 1.5, 1.75, 2]" :key="rate" :value="rate">{{ rate }}×</option></select></label>
    <p class="setting-note">{{ $t('mobile.advancedPlayback.speedHint') }}</p>
    <CollapsibleSettingsCard section-id="pitch-advanced" :title="$t('mobile.advancedPlayback.pitch')" :subtitle="$t('mobile.advancedPlayback.pitchHint')" :remember="false">
      <label class="settings-detail"><span>{{ $t('mobile.advancedPlayback.pitchRatio') }}</span><select :value="settings.pitch" :disabled="busy" :aria-label="$t('mobile.advancedPlayback.pitchRatio')" @change="update({ pitch: Number(($event.target as HTMLSelectElement).value) })"><option v-for="pitch in [.5, .75, 1, 1.25, 1.5, 2]" :key="pitch" :value="pitch">{{ pitch }}×</option></select></label>
      <p class="setting-note">{{ $t('mobile.advancedPlayback.pitchFallback') }}</p>
    </CollapsibleSettingsCard>
    <label class="settings-detail"><span>{{ $t('mobile.advancedPlayback.prefetch') }}</span><input type="checkbox" :checked="settings.prefetchWifi" :disabled="busy" @change="update({ prefetchWifi: ($event.target as HTMLInputElement).checked })" /></label>
    <p class="setting-note">{{ $t('mobile.advancedPlayback.prefetchHint') }}</p>
    <p v-if="settings.warning || store.playback.tuningWarning" class="setting-note" role="status">{{ settings.warning || store.playback.tuningWarning }}</p>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
  </CollapsibleSettingsCard>
</template>
<style scoped>select { max-width:45%; padding:7px 12px; border:1px solid var(--line); border-radius:9px; color:inherit; background:var(--color-secondary-bg); }</style>
