<script setup lang="ts">
import { playlistView, playlistColumns } from '../platform/libraryLayout'
import OriginalIcon from './OriginalIcon.vue'
</script>
<template>
  <div class="library-layout-controls" role="group" aria-label="歌单排版">
    <button type="button" :aria-pressed="playlistView === 'cards'" :class="{ selected: playlistView === 'cards' }" @click="playlistView = 'cards'"><OriginalIcon name="library" /><span>卡片</span></button>
    <button type="button" :aria-pressed="playlistView === 'list'" :class="{ selected: playlistView === 'list' }" @click="playlistView = 'list'"><OriginalIcon name="list" /><span>列表</span></button>
    <Transition name="layout-count"><label v-if="playlistView === 'cards'">每排<select v-model.number="playlistColumns" aria-label="每排歌单数量"><option v-for="count in 6" :key="count" :value="count">{{ count }}</option></select>个</label></Transition>
  </div>
</template>

<style scoped>
.library-layout-controls > button { transition: background-color 125ms ease-out, color 125ms ease-out, transform .2s ease; }
.layout-count-enter-active, .layout-count-leave-active { transition: opacity 125ms ease-out, transform 125ms ease-out; }
.layout-count-enter-from, .layout-count-leave-to { opacity: 0; transform: translateX(8px); }
html[data-motion="reduced"] .library-layout-controls > button, html[data-motion="reduced"] .layout-count-enter-active, html[data-motion="reduced"] .layout-count-leave-active { transition: none; }
</style>
