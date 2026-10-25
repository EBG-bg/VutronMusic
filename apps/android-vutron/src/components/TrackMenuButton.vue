<script setup lang="ts">
import OriginalIcon from './OriginalIcon.vue'
import ButtonIcon from '../../../../packages/ui/src/ButtonIcon.vue'
defineProps<{ name: string }>()
defineEmits<{ open: [] }>()
</script>

<template>
  <ButtonIcon :prevent-blur="true" class="row-play track-menu-button" type="button" data-track-menu-button aria-haspopup="dialog" :aria-label="`歌曲菜单 ${name}`" @click.stop="$emit('open')" @keydown.enter.stop @keydown.space.stop>
    <OriginalIcon name="more" />
  </ButtonIcon>
</template>

<style scoped>
.track-menu-button.button-icon { display: grid; place-items: center; width: 38px; height: 38px; flex: 0 0 38px; margin: 0; padding: 0; border: 0; border-radius: 8px; background: transparent; color: var(--color-secondary); transition: background-color .15s ease, color .15s ease, transform .2s ease; }
.track-menu-button .original-icon { font-size: 20px; }
.track-menu-button.button-icon:is([data-original-feedback], :focus-visible) { background-color: var(--accent-soft); color: var(--accent); }
@media (hover: hover) and (pointer: fine) { .track-menu-button.button-icon:hover { background-color: var(--accent-soft); color: var(--accent); } }
html[data-motion="reduced"] .track-menu-button { transition: none; }
</style>
