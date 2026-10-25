<script setup lang="ts">
import ButtonIcon from './ButtonIcon.vue'

withDefaults(defineProps<{
  active?: boolean
  variant?: 'desktop' | 'mobile'
  preventBlur?: boolean
}>(), { active: false, variant: 'desktop', preventBlur: false })
const emit = defineEmits<{ click: [event: MouseEvent] }>()
</script>

<template>
  <ButtonIcon :prevent-blur="preventBlur" class="side-nav-item" :class="[{ active }, 'side-nav-item-' + variant]" @click="emit('click', $event)"><slot /></ButtonIcon>
</template>

<style scoped>
/* Extracted from the original SideNav.vue; both platforms use these states. */
.side-nav-item {
  padding: 0;
  -webkit-app-region: no-drag;
  font-weight: 700;
  text-decoration: none;
  border-radius: 8px;
  background-color: var(--color-secondary-bg);
  transition: all 0.3s ease-in;
  -webkit-user-drag: none;
  position: relative;
}
.side-nav-item :deep(.svg-icon), .side-nav-item :deep(.original-icon) { transition: color 0.2s ease-in; }
.side-nav-item:active, .side-nav-item[data-original-pressed] { transform: scale(0.92); transition: 0.2s; }
.side-nav-item.active, .side-nav-item[data-original-feedback], .side-nav-item:focus-visible { background: var(--color-primary); color: white; transition: background 0.2s ease-in; }
.side-nav-item.active :deep(.icon), .side-nav-item.active :deep(.original-icon),
.side-nav-item[data-original-feedback] :deep(.icon), .side-nav-item[data-original-feedback] :deep(.original-icon),
.side-nav-item:focus-visible :deep(.icon), .side-nav-item:focus-visible :deep(.original-icon) { color: white; }
.side-nav-item-desktop {
  height: 40px;
  width: 60px;
  margin: 15px 0;
}
.side-nav-item-desktop :deep(.svg-icon) { width: 100px; height: 40px; }
.side-nav-item-desktop :deep(.icon) { width: 26px; height: 26px; }
.side-nav-item-desktop:hover { background: var(--color-primary); }
.side-nav-item-desktop:hover :deep(.icon) { color: white; }
.side-nav-item-mobile { margin: 0; touch-action: manipulation; }
@media (hover: hover) and (pointer: fine) {
  .side-nav-item-mobile:hover { background: var(--color-primary); color: white; }
}
@media (hover: none) and (pointer: coarse) {
  .side-nav-item-mobile:active:not([data-original-pressed]) { transform: none; transition: all .3s ease-in; }
}
</style>
