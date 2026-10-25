<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type ComponentPublicInstance } from 'vue'
import type { LyricFamily, LyricLine } from '@vutronmusic/core/lyrics'
import { lyricDisplayStyle, type LyricDisplaySettings } from '../platform/lyricDisplay'
import {
  boundaryScrollRemainder, interpolatedPosition, lyricClock, lyricDisplayLines, lyricIndexAt, lyricLineProgress,
  lyricCenteredScrollTop, lyricSeekPosition, lyricVisibleBand, lyricWordProgress, type LyricPlaybackAnchor
} from '../utils/lyricPlayback'

const props = withDefaults(defineProps<{
  family: LyricFamily
  position: number
  playing: boolean
  offsetMs: number
  duration?: number
  displaySettings?: LyricDisplaySettings
}>(), { duration: 0 })
const emit = defineEmits<{ seek: [position: number] }>()
const container = ref<HTMLElement>()
const activeIndex = ref(-1)
const manualScroll = ref(false)
const rows: HTMLElement[] = []
const hasWords = computed(() => props.family.original.some(line => line.words.length > 0))
const estimated = computed(() => props.displaySettings?.estimatedWordTiming === true)
const hasEstimates = computed(() => estimated.value && props.family.original.some(line => !line.words.length && line.text))
const displayLines = computed(() => lyricDisplayLines(props.family.original, estimated.value, props.duration))
const displayStyle = computed(() => lyricDisplayStyle(props.displaySettings))
let anchor: LyricPlaybackAnchor = { position: props.position, monotonicTime: performance.now(), playing: props.playing }
let frame = 0
let mounted = false
let highlightedRow = -1
let highlightedElement: HTMLElement | undefined
let activeWords: HTMLElement[] = []
let activeTranslation: HTMLElement | null = null
let followGeneration = 0
let lastFingerY: number | undefined
let touchPointerMoved = false
let handoffFrame = 0
let handoffRemainder = 0
let handoffWindowY = 0
let layoutFrame = 0
let layoutObserver: ResizeObserver | undefined
let observedRow: HTMLElement | undefined

function setRow(element: Element | ComponentPublicInstance | null, index: number) {
  if (element instanceof HTMLElement) rows[index] = element
  else delete rows[index]
}

function lineEnd(index: number): number { return props.family.original[index + 1]?.start ?? props.duration }

function selectHighlightedRow(index: number) {
  if (highlightedRow === index && highlightedElement === rows[index]) return
  if (highlightedRow >= 0 && rows[highlightedRow]) {
    rows[highlightedRow].querySelectorAll<HTMLElement>('.original-lyric-word, .original-lyric-translation').forEach(word => word.style.removeProperty('--lyric-progress'))
  }
  highlightedRow = index
  const row = rows[index]
  highlightedElement = row
  if (observedRow !== row) {
    if (observedRow) layoutObserver?.unobserve(observedRow)
    observedRow = row
    if (row) layoutObserver?.observe(row)
  }
  activeWords = row ? Array.from(row.querySelectorAll<HTMLElement>('.original-lyric-word')) : []
  activeTranslation = row?.querySelector<HTMLElement>('.original-lyric-translation') ?? null
}

function renderClock(now = performance.now()) {
  const position = interpolatedPosition(anchor, now, props.duration)
  const clock = lyricClock(position, props.offsetMs)
  const index = lyricIndexAt(props.family.original, clock)
  if (activeIndex.value !== index) {
    activeIndex.value = index
    if (!manualScroll.value) void followActive()
  }
  selectHighlightedRow(index)
  const line = displayLines.value[index]
  if (!line) return
  const progress = lyricLineProgress(line, clock, lineEnd(index))
  if (line.words.length) {
    // Vue does not render the whole lyric family on every animation frame.
    // Only spans in the current row receive a CSS progress update.
    for (let word = 0; word < activeWords.length; word++) {
      const value = lyricWordProgress(line.words[word], clock)
      activeWords[word].style.setProperty('--lyric-progress', value * 100 + '%')
    }
  } else activeWords[0]?.style.setProperty('--lyric-progress', '100%')
  activeTranslation?.style.setProperty('--lyric-progress', (line.words.length ? progress : 1) * 100 + '%')
}

function animationFrame(now: number) {
  frame = 0
  renderClock(now)
  if (props.playing && document.visibilityState !== 'hidden') frame = requestAnimationFrame(animationFrame)
}

function reanchor() {
  anchor = { position: props.position, monotonicTime: performance.now(), playing: props.playing }
  if (!mounted) return
  cancelAnimationFrame(frame)
  frame = 0
  renderClock()
  if (props.playing && document.visibilityState !== 'hidden') frame = requestAnimationFrame(animationFrame)
}

function unobscuredViewport(list: HTMLElement) {
  const visual = window.visualViewport
  const top = visual?.offsetTop ?? 0
  let bottom = top + (visual?.height ?? window.innerHeight)
  const listRect = list.getBoundingClientRect()
  for (const element of document.querySelectorAll<HTMLElement>('.bottom-nav, .mini-player')) {
    const style = getComputedStyle(element)
    if (style.display === 'none' || style.visibility === 'hidden' || style.position !== 'fixed') continue
    const rect = element.getBoundingClientRect()
    if (rect.height > 0 && rect.right > listRect.left && rect.left < listRect.right
      && rect.bottom > top && rect.top < bottom) bottom = Math.min(bottom, rect.top)
  }
  return { top, bottom }
}

async function followActive(instant = false, reveal = false) {
  const token = ++followGeneration
  await nextTick()
  if (token !== followGeneration || manualScroll.value) return
  const list = container.value
  const row = rows[Math.max(0, activeIndex.value)]
  if (!list || !row?.isConnected) return
  // Like the original LyricPage's block:center, align the whole row. Use one
  // coordinate space rather than subtracting unrelated offsetParent positions.
  list.style.setProperty('--lyric-edge-space', Math.ceil(list.clientHeight / 2) + 'px')
  let listRect = list.getBoundingClientRect()
  let rowRect = row.getBoundingClientRect()
  let viewport = unobscuredViewport(list)
  let band = lyricVisibleBand(listRect.top, listRect.bottom, viewport.top, viewport.bottom)
  if (reveal && Math.min(listRect.bottom, viewport.bottom) - Math.max(listRect.top, viewport.top)
    < Math.min(list.clientHeight, rowRect.height + 24)) {
    window.scrollBy({ top: (listRect.top + listRect.bottom - viewport.top - viewport.bottom) / 2, behavior: 'instant' as ScrollBehavior })
    listRect = list.getBoundingClientRect()
    rowRect = row.getBoundingClientRect()
    viewport = unobscuredViewport(list)
    band = lyricVisibleBand(listRect.top, listRect.bottom, viewport.top, viewport.bottom)
  }
  const top = lyricCenteredScrollTop(list.scrollTop, rowRect.top, rowRect.bottom, band)
  list.scrollTo({ top, behavior: instant ? 'instant' as ScrollBehavior : 'smooth' })
}

function returnToCurrent() {
  manualScroll.value = false
  void followActive(false, true)
}

function markManualScroll() {
  ++followGeneration
  manualScroll.value = true
}

function touchStart(event: TouchEvent) {
  if (event.touches.length !== 1) { lastFingerY = undefined; return }
  lastFingerY = event.touches[0].clientY
  touchPointerMoved = false
}

function touchMove(event: TouchEvent) {
  const list = container.value
  if (!list || lastFingerY === undefined || event.touches.length !== 1) return
  const nextY = event.touches[0].clientY
  const delta = lastFingerY - nextY
  lastFingerY = nextY
  if (Math.abs(delta) < .5) return
  touchPointerMoved = true
  markManualScroll()
  const remainder = boundaryScrollRemainder(list.scrollTop, list.clientHeight, list.scrollHeight, delta)
  if (Math.abs(remainder) < .5) return
  // Leave the event untouched so Chrome's normal scroll chaining can run.
  // Some Android WebViews keep a gesture latched to the nested lyric scroller;
  // only compensate after native scrolling had two frames to move the page.
  if (!handoffFrame) handoffWindowY = window.scrollY
  handoffRemainder += remainder
  if (handoffFrame) return
  handoffFrame = requestAnimationFrame(() => {
    handoffFrame = requestAnimationFrame(() => {
      handoffFrame = 0
      const remaining = handoffRemainder
      handoffRemainder = 0
      if (Math.abs(window.scrollY - handoffWindowY) < .5) window.scrollBy({ top: remaining, behavior: 'instant' as ScrollBehavior })
    })
  })
}

function seekLine(line: LyricLine, event: MouseEvent) {
  // A finger drag must not seek when a WebView synthesizes its trailing click.
  if (touchPointerMoved && event.detail !== 0) { touchPointerMoved = false; return }
  const target = lyricSeekPosition(line, props.offsetMs, props.duration)
  anchor = { position: target, monotonicTime: performance.now(), playing: props.playing }
  manualScroll.value = false
  renderClock()
  void followActive(false, true)
  emit('seek', target)
}

function scrollKey(event: KeyboardEvent) {
  if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(event.key)) markManualScroll()
}

function onVisibility() {
  cancelAnimationFrame(frame)
  frame = 0
  if (document.visibilityState !== 'hidden') {
    renderClock()
    if (props.playing) frame = requestAnimationFrame(animationFrame)
  }
}

function onViewportChange() {
  if (layoutFrame || manualScroll.value) return
  layoutFrame = requestAnimationFrame(() => {
    layoutFrame = 0
    if (!manualScroll.value) void followActive(true)
  })
}

watch(() => [props.position, props.playing, props.offsetMs, props.duration], reanchor)
watch(displayLines, () => {
  highlightedRow = -1
  reanchor()
  if (!manualScroll.value) void followActive(true)
}, { deep: true, flush: 'post' })
watch(() => props.family, async () => {
  ++followGeneration
  highlightedRow = -1
  activeWords = []
  activeTranslation = null
  highlightedElement = undefined
  rows.length = 0
  activeIndex.value = -1
  manualScroll.value = false
  await nextTick()
  reanchor()
  void followActive(true)
})
onMounted(() => {
  mounted = true
  document.addEventListener('visibilitychange', onVisibility)
  window.addEventListener('resize', onViewportChange, { passive: true })
  window.addEventListener('scroll', onViewportChange, { passive: true })
  window.visualViewport?.addEventListener('resize', onViewportChange, { passive: true })
  window.visualViewport?.addEventListener('scroll', onViewportChange, { passive: true })
  layoutObserver = new ResizeObserver(onViewportChange)
  if (container.value) layoutObserver.observe(container.value)
  reanchor()
  void followActive(true)
})
onUnmounted(() => {
  mounted = false
  cancelAnimationFrame(frame)
  cancelAnimationFrame(handoffFrame)
  cancelAnimationFrame(layoutFrame)
  layoutObserver?.disconnect()
  ++followGeneration
  document.removeEventListener('visibilitychange', onVisibility)
  window.removeEventListener('resize', onViewportChange)
  window.removeEventListener('scroll', onViewportChange)
  window.visualViewport?.removeEventListener('resize', onViewportChange)
  window.visualViewport?.removeEventListener('scroll', onViewportChange)
})
</script>

<template>
  <section class="original-lyrics-view" :class="{ 'word-timed': hasWords || hasEstimates }" :style="displayStyle" aria-label="歌词">
    <header class="original-lyrics-heading"><b>歌词</b><span v-if="hasWords" class="original-lyrics-source">原始逐字</span><span v-if="hasEstimates" class="original-lyrics-source estimated-lyrics-note">估算逐字（计算生成，可能不准确）</span><button v-if="manualScroll" type="button" class="return-current-lyric" @click="returnToCurrent">返回当前句</button></header>
    <div v-if="!family.original.length" class="original-lyrics-empty">暂无歌词</div>
    <div v-else ref="container" class="original-lyrics-scroll" tabindex="0" aria-label="歌词滚动区域" @wheel.passive="markManualScroll" @keydown="scrollKey" @touchstart.passive="touchStart" @touchmove.passive="touchMove">
      <button v-for="(line, index) in displayLines" :key="line.start + '-' + index" :ref="element => setRow(element, index)" type="button" class="original-lyric-row" :class="{ active: activeIndex === index }" :aria-current="activeIndex === index ? 'true' : undefined" :aria-label="'跳到歌词：' + line.text" @click="seekLine(line, $event)">
        <span class="original-lyric-text"><span v-if="!line.words.length" class="original-lyric-word">{{ line.text || '♪' }}</span><template v-else><span v-for="(word, wordIndex) in line.words" :key="wordIndex" class="original-lyric-word">{{ word.text }}</span></template></span>
        <span v-if="family.pairedTranslations[index]" class="original-lyric-translation">{{ family.pairedTranslations[index] }}</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.original-lyrics-view { width: 100%; min-width: 0; color: var(--color-text); }
.original-lyrics-heading { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-height: 36px; margin: 0 3px 8px; }
.original-lyrics-heading b { font-size: 17px; font-weight: 600; }
.original-lyrics-source { font-size: 10px; color: var(--color-secondary); padding: 4px 6px; border-radius: 6px; background: var(--color-secondary-bg); }
.return-current-lyric { margin-left: auto; min-height: 32px; padding: 6px 9px; border: 0; border-radius: 8px; color: var(--color-primary); background: var(--color-secondary-bg); font: inherit; font-size: 11px; }
.original-lyrics-scroll { position: relative; min-width: 0; height: clamp(260px, 50dvh, 420px); border-radius: 12px; background: var(--lyric-background, transparent); overflow-y: auto; overflow-x: hidden; overscroll-behavior-y: auto; touch-action: pan-y; scrollbar-width: none; scroll-padding-block: 20%; }
.original-lyrics-scroll::-webkit-scrollbar { display: none; }
.original-lyrics-scroll::before, .original-lyrics-scroll::after { content: ''; display: block; height: var(--lyric-edge-space, 130px); pointer-events: none; }
.original-lyric-row { display: block; width: 100%; min-width: 0; margin: 2px 0; padding: 13px 10px; border: 0; border-radius: 12px; color: var(--lyric-unplayed, var(--color-full-text-unplay)); background: transparent; text-align: left; font-family: inherit; font-weight: 600; cursor: pointer; -webkit-tap-highlight-color: transparent; }
.original-lyric-row:active { background: var(--color-secondary-bg-for-transparent); }
.original-lyric-text { display: block; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 21px; line-height: 1.5; filter: var(--lyric-shadow, none); transform: scale(.96); transform-origin: left center; transition: transform .3s cubic-bezier(.25,.46,.45,.94); }
.original-lyric-row.active .original-lyric-text { transform: scale(1); }
.original-lyric-word { --lyric-progress: 0%; background-image: linear-gradient(to right, var(--lyric-played, var(--color-wbw-text-played)) var(--lyric-progress), var(--lyric-unplayed, var(--color-wbw-text-unplay)) var(--lyric-progress)); background-clip: text; -webkit-background-clip: text; color: transparent; }
.original-lyric-row:not(.active) .original-lyric-word { background-image: none; color: var(--lyric-unplayed, var(--color-full-text-unplay)); }
.original-lyric-translation { --lyric-progress: 0%; display: block; width: fit-content; max-width: 100%; margin: 5px 0 0; font-size: 14px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; filter: var(--lyric-shadow, none); background-image: linear-gradient(to right, var(--lyric-played, var(--color-wbw-text)) var(--lyric-progress), var(--lyric-unplayed, var(--color-wbw-text-unplay)) var(--lyric-progress)); background-clip: text; -webkit-background-clip: text; color: transparent; }
.original-lyric-row:not(.active) .original-lyric-translation { background-image: none; color: var(--lyric-unplayed, var(--color-full-text-unplay)); }
.original-lyric-row:focus-visible, .return-current-lyric:focus-visible, .original-lyrics-scroll:focus-visible { outline: 2px solid var(--color-primary); outline-offset: -2px; }
.original-lyrics-empty { padding: 30px 8px; color: var(--color-secondary); text-align: center; font-size: 13px; }
@media (max-width: 340px) { .original-lyric-text { font-size: 19px; } .original-lyric-translation { font-size: 13px; } .original-lyric-row { padding-inline: 8px; } }
html[data-motion="reduced"] .original-lyric-text { transition: none; }
</style>
