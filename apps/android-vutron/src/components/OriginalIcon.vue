<script setup lang="ts">
import { computed } from 'vue'
import logo from '../assets/icons/original-logo.svg?url'
import library from '../../../../src/renderer/assets/icons/library.svg?url'
import search from '../../../../src/renderer/assets/icons/search.svg?url'
import settings from '../../../../src/renderer/assets/icons/settings.svg?url'
import previous from '../../../../src/renderer/assets/icons/previous.svg?url'
import next from '../../../../src/renderer/assets/icons/next.svg?url'
import play from '../../../../src/renderer/assets/icons/play.svg?url'
import pause from '../../../../src/renderer/assets/icons/pause.svg?url'
import back from '../../../../src/renderer/assets/icons/arrow-left.svg?url'
import refresh from '../../../../src/renderer/assets/icons/recovery.svg?url'
import down from '../../../../src/renderer/assets/icons/arrow-down.svg?url'
import local from '../../../../src/renderer/assets/icons/local-music.svg?url'
import folder from '../../../../src/renderer/assets/icons/folder.svg?url'
import lyrics from '../../../../src/renderer/assets/icons/osd-lyrics.svg?url'
import close from '../../../../src/renderer/assets/icons/close.svg?url'
import explore from '../../../../src/renderer/assets/icons/explore.svg?url'
import list from '../../../../src/renderer/assets/icons/list.svg?url'
import repeat from '../../../../src/renderer/assets/icons/repeat.svg?url'
import repeatOne from '../../../../src/renderer/assets/icons/repeat-1.svg?url'
import shuffle from '../../../../src/renderer/assets/icons/shuffle.svg?url'
import more from '../../../../src/renderer/assets/icons/more.svg?url'
import heart from '../../../../src/renderer/assets/icons/heart.svg?url'
import heartSolid from '../../../../src/renderer/assets/icons/heart-solid.svg?url'
import plus from '../../../../src/renderer/assets/icons/plus.svg?url'

// Timer means pause at the deadline, so it reuses the original pause symbol.
const icons = { logo, library, search, settings, previous, next, play, pause, back, refresh, down, local, folder, lyrics, close, explore, list, repeat, repeatOne, shuffle, more, heart, heartSolid, plus, timer: pause }
const props = defineProps<{ name: keyof typeof icons }>()
const iconStyle = computed(() => ({ '--original-icon': `url("${icons[props.name]}")` }))
</script>

<template>
  <span class="original-icon" :data-original-icon="name" :style="iconStyle" aria-hidden="true"></span>
</template>

<style scoped>
.original-icon {
  display: inline-block;
  width: 1em;
  height: 1em;
  flex: 0 0 auto;
  vertical-align: -0.125em;
  background-color: currentColor;
  -webkit-mask: var(--original-icon) center / contain no-repeat;
  mask: var(--original-icon) center / contain no-repeat;
  pointer-events: none;
}
</style>
