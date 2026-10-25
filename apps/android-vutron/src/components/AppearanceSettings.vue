<script setup lang="ts">
import { accent, accentPresets, appearance } from '../platform/appearance'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'
import { motionPreference, reducedMotion, systemReducesMotion } from '../platform/motion'
</script>

<template>
  <CollapsibleSettingsCard class="appearance-settings" section-id="appearance" title="外观与强调色" subtitle="沿用 VutronMusic 原版主题">
    <div class="segmented-control appearance-mode" role="group" aria-label="外观">
      <button type="button" :class="{ selected: appearance === 'light' }" @click="appearance = 'light'">浅色</button>
      <button type="button" :class="{ selected: appearance === 'dark' }" @click="appearance = 'dark'">深色</button>
      <button type="button" :class="{ selected: appearance === 'auto' }" @click="appearance = 'auto'">跟随系统</button>
    </div>
    <div class="segmented-control motion-mode" role="group" aria-label="界面动效">
      <button type="button" :class="{ selected: motionPreference === 'full' }" :aria-pressed="motionPreference === 'full'" @click="motionPreference = 'full'">完整动效</button>
      <button type="button" :class="{ selected: motionPreference === 'system' }" :aria-pressed="motionPreference === 'system'" @click="motionPreference = 'system'">跟随系统</button>
      <button type="button" :class="{ selected: motionPreference === 'reduced' }" :aria-pressed="motionPreference === 'reduced'" @click="motionPreference = 'reduced'">减少动效</button>
    </div>
    <p class="setting-note motion-status">当前：{{ reducedMotion ? '减少动效' : '完整动效' }} · 系统{{ systemReducesMotion ? '请求减少动态效果' : '允许动态效果' }}。完整动效保留按钮、设置收放与侧栏过渡。</p>
    <div class="accent-presets" role="group" aria-label="强调色">
      <button v-for="preset in accentPresets" :key="preset.name" type="button" :class="{ selected: accent.toLowerCase() === preset.color }" :aria-pressed="accent.toLowerCase() === preset.color" @click="accent = preset.color"><span :style="{ backgroundColor: preset.color }"></span><small>{{ preset.label }}</small></button>
      <label class="custom-accent"><input v-model="accent" type="color" aria-label="自定义强调色" /><small>自定义</small></label>
    </div>
  </CollapsibleSettingsCard>
</template>

<style scoped>
/* Reuse ContextMenu.vue's 125ms state easing and ButtonIcon.vue's 0.2s press. */
.appearance-mode button { transition: color 125ms ease-out, background-color 125ms ease-out, box-shadow 125ms ease-out, transform .2s ease; }
.accent-presets button > span { outline: 2px solid transparent; outline-offset: 3px; transition: outline-color 125ms ease-out, transform .2s ease; }
.accent-presets button.selected > span { outline-color: var(--color-text); }
html[data-motion="reduced"] .appearance-mode button, html[data-motion="reduced"] .accent-presets button > span { transition: none; }
</style>
