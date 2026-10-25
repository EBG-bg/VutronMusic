<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import OriginalIcon from './OriginalIcon.vue'
import { isMotionReduced } from '../platform/motion'

const props = withDefaults(defineProps<{
  sectionId: string
  title: string
  subtitle?: string
  remember?: boolean
}>(), { subtitle: '', remember: true })
const storageKey = 'vutron:settings-expanded:' + props.sectionId
const expanded = ref(props.remember && localStorage.getItem(storageKey) === 'true')
const renderedOpen = ref(expanded.value)
const mountedContent = ref(expanded.value)
const closing = ref(false)
const content = ref<HTMLElement>()
const card = ref<HTMLDetailsElement>()
let animation: Animation | undefined
let cardAnimation: Animation | undefined
let revision = 0

async function setExpanded(open: boolean) {
  if (open === expanded.value && !animation) return
  const token = ++revision
  const previous = content.value
  const startHeight = previous?.getBoundingClientRect().height ?? 0
  const startOpacity = previous ? Number(getComputedStyle(previous).opacity) : 0
  const startCardHeight = card.value?.getBoundingClientRect().height ?? 0
  animation?.cancel()
  cardAnimation?.cancel()
  expanded.value = open
  closing.value = !open
  if (props.remember) localStorage.setItem(storageKey, String(open))
  renderedOpen.value = true
  mountedContent.value = true
  await nextTick()
  if (token !== revision || !content.value) return
  const el = content.value
  el.inert = !open
  const endHeight = open ? el.scrollHeight : 0
  const endCardHeight = (card.value?.getBoundingClientRect().height ?? 0) - el.getBoundingClientRect().height + endHeight
  const duration = isMotionReduced() ? 0 : 100
  animation = el.animate([
    { height: startHeight + 'px', opacity: startOpacity },
    { height: endHeight + 'px', opacity: open ? 1 : 0 }
  ], { duration, easing: 'ease', fill: 'both' })
  cardAnimation = card.value?.animate([
    { height: startCardHeight + 'px' },
    { height: endCardHeight + 'px' }
  ], { duration, easing: 'ease', fill: 'both' })
  try { await animation.finished } catch { return }
  if (token !== revision) return
  animation.cancel()
  cardAnimation?.cancel()
  cardAnimation = undefined
  animation = undefined
  closing.value = false
  if (!open) {
    mountedContent.value = false
    renderedOpen.value = false
  }
}

function onToggle(event: Event) {
  const nativeOpen = (event.currentTarget as HTMLDetailsElement).open
  if (!animation && nativeOpen !== renderedOpen.value) void setExpanded(nativeOpen)
}
function close() { if (expanded.value) void setExpanded(false) }
onBeforeUnmount(() => { ++revision; animation?.cancel(); cardAnimation?.cancel() })
</script>

<template>
  <details ref="card" class="settings-card settings-collapse" :data-settings-section="sectionId" :data-closing="closing ? '' : undefined" :open="renderedOpen" @toggle="onToggle" @vutron-settings-close.stop="close">
    <summary :aria-controls="'settings-content-' + sectionId" :aria-expanded="expanded" @click.prevent="setExpanded(!expanded)">
      <div class="settings-title">
        <slot name="heading"><div><h3>{{ title }}</h3><p v-if="subtitle">{{ subtitle }}</p></div></slot>
      </div>
      <OriginalIcon class="settings-chevron" name="down" />
    </summary>
    <div v-if="mountedContent" :id="'settings-content-' + sectionId" ref="content" class="settings-collapse-content" role="region" :aria-label="title" :aria-hidden="!expanded">
      <div class="settings-collapse-inner"><slot /></div>
    </div>
  </details>
</template>

<style scoped>
.settings-collapse { margin: 0; overflow: clip; overflow-anchor: none; }
.settings-collapse > summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 38px; list-style: none; cursor: pointer; touch-action: manipulation; -webkit-tap-highlight-color: transparent; border-radius: 8px; transition: background-color .15s ease, color .15s ease; }
.settings-collapse > summary::-webkit-details-marker { display: none; }
.settings-collapse > summary:focus-visible { outline: 2px solid var(--accent); outline-offset: 5px; }
.settings-collapse > summary .settings-title { flex: 1; min-width: 0; margin: 0; }
.settings-chevron { flex-shrink: 0; font-size: 15px; color: var(--muted); transition: transform .1s ease; }
.settings-collapse > summary[aria-expanded='true'] .settings-chevron { transform: rotate(180deg); }
.settings-collapse-content { overflow: hidden; }
.settings-collapse-inner { padding-top: 12px; }
.settings-collapse .settings-collapse { background: var(--color-secondary-bg-for-transparent); border-color: var(--line); box-shadow: none; }
html[data-motion="reduced"] .settings-chevron { transition: none; }
</style>
