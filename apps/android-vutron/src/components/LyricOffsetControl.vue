<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import back5s from '../../../../src/renderer/assets/icons/back5s.svg?url'
import forward5s from '../../../../src/renderer/assets/icons/forward5s.svg?url'
import OriginalIcon from './OriginalIcon.vue'

const props = withDefaults(defineProps<{ modelValue: number; disabled?: boolean; trackKey?: string | number }>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [milliseconds: number]; commit: [milliseconds: number] }>()
const limit = 30000
const earlierIcon = { '--offset-icon': `url("${back5s}")` }
const laterIcon = { '--offset-icon': `url("${forward5s}")` }
const normalize = (value: number) => Math.max(-limit, Math.min(limit, Math.round((Number.isFinite(value) ? value : 0) / 10) * 10))
const value = ref(normalize(props.modelValue))
const numeric = ref(String(value.value / 1000))
const editing = ref(false)
const slider = ref<HTMLElement>()
const center = ref(value.value)
const lower = computed(() => Math.max(-limit, center.value - 1000))
const upper = computed(() => Math.min(limit, center.value + 1000))
const percent = computed(() => (value.value - lower.value) / (upper.value - lower.value) * 100)
const signedSeconds = (milliseconds: number) => `${milliseconds > 0 ? '+' : ''}${(milliseconds / 1000).toFixed(2)}`
let drag: { pointer: number; x: number; value: number; scale: number } | undefined

function update(next: number) {
  value.value = normalize(next)
  if (!editing.value) numeric.value = String(value.value / 1000)
  emit('update:modelValue', value.value)
}
function commit() {
  numeric.value = String(value.value / 1000)
  center.value = value.value
  emit('commit', value.value)
}
function releaseCapture() {
  const pointer = drag?.pointer
  drag = undefined
  if (pointer !== undefined && slider.value?.hasPointerCapture(pointer)) slider.value.releasePointerCapture(pointer)
}
function beginDrag(event: PointerEvent) {
  if (props.disabled || drag || (event.pointerType === 'mouse' && event.button !== 0)) return
  event.preventDefault()
  slider.value?.focus({ preventScroll: true })
  const width = slider.value?.getBoundingClientRect().width ?? 0
  if (width <= 0) return
  // Start at the saved value even when the finger lands away from the thumb.
  drag = { pointer: event.pointerId, x: event.clientX, value: value.value, scale: (upper.value - lower.value) / width }
  slider.value?.setPointerCapture(event.pointerId)
}
function moveDrag(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.pointer) return
  const next = Math.max(lower.value, Math.min(upper.value, drag.value + (event.clientX - drag.x) * drag.scale))
  update(next)
}
function endDrag(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.pointer) return
  if (event.type !== 'pointercancel' && event.type !== 'lostpointercapture') moveDrag(event)
  releaseCapture()
  commit()
}
function step(milliseconds: number) {
  if (props.disabled) return
  update(value.value + milliseconds)
  commit()
}
function keyboard(event: KeyboardEvent) {
  if (props.disabled) return
  const small = event.shiftKey ? 100 : 10
  const next: Record<string, number> = { ArrowLeft: value.value - small, ArrowDown: value.value - small, ArrowRight: value.value + small, ArrowUp: value.value + small, PageDown: value.value - 500, PageUp: value.value + 500, Home: -limit, End: limit }
  if (!(event.key in next)) return
  event.preventDefault()
  update(next[event.key])
  commit()
}
function inputNumber(event: Event) {
  const input = event.target as HTMLInputElement
  numeric.value = input.value
  if (input.value.trim() !== '' && Number.isFinite(input.valueAsNumber)) update(input.valueAsNumber * 1000)
}
function finishNumber() { editing.value = false; commit() }
watch(() => props.modelValue, next => {
  value.value = normalize(next)
  if (!editing.value) numeric.value = String(value.value / 1000)
  if (!drag) center.value = value.value
})
watch(() => props.trackKey, () => {
  releaseCapture()
  editing.value = false
  value.value = normalize(props.modelValue)
  numeric.value = String(value.value / 1000)
  center.value = value.value
})
watch(() => props.disabled, disabled => { if (disabled) releaseCapture() })
onBeforeUnmount(releaseCapture)
</script>

<template>
  <div class="lyric-offset-control" :class="{ disabled }">
    <div class="offset-heading"><span>当前歌曲歌词延迟</span><output>{{ signedSeconds(value) }} 秒</output></div>
    <div ref="slider" class="offset-slider" role="slider" :tabindex="disabled ? -1 : 0" aria-label="歌词延迟微调" :aria-disabled="disabled" :aria-valuemin="-limit" :aria-valuemax="limit" :aria-valuenow="value" :aria-valuetext="`${signedSeconds(value)} 秒，正数晚显示`" :style="{ '--offset-position': `${percent}%` }" @pointerdown="beginDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag" @lostpointercapture="endDrag" @keydown="keyboard">
      <span class="offset-rail"><span class="offset-fill"></span><span class="offset-thumb"></span></span>
    </div>
    <div class="offset-scale"><span>{{ signedSeconds(lower) }} 秒</span><span>拖动微调 · 每格 10 毫秒</span><span>{{ signedSeconds(upper) }} 秒</span></div>
    <div class="offset-actions">
      <button class="secondary-button" type="button" :disabled="disabled" aria-label="歌词提前半秒" @click="step(-500)"><span class="offset-original-icon" :style="earlierIcon" aria-hidden="true"></span>提前 0.5 秒</button>
      <button class="secondary-button" type="button" :disabled="disabled" @click="update(0); commit()"><OriginalIcon name="refresh" />恢复同步</button>
      <button class="secondary-button" type="button" :disabled="disabled" aria-label="歌词延后半秒" @click="step(500)"><span class="offset-original-icon" :style="laterIcon" aria-hidden="true"></span>延后 0.5 秒</button>
    </div>
    <label class="offset-exact"><span>精确输入</span><input type="number" inputmode="decimal" min="-30" max="30" step="0.01" :disabled="disabled" :value="numeric" aria-label="歌词延迟秒数" @focus="editing = true" @input="inputNumber" @blur="finishNumber" @keydown.enter.prevent="finishNumber" /><span>秒 · 正数晚显示，负数早显示</span></label>
  </div>
</template>

<style scoped>
.lyric-offset-control { display: grid; gap: 3px; min-width: 0; }
.offset-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; font-size: 13px; }
.offset-heading output { color: var(--color-primary, var(--accent)); font-variant-numeric: tabular-nums; white-space: nowrap; }
.offset-slider { position: relative; height: 32px; margin: 0 9px; cursor: ew-resize; touch-action: none; user-select: none; outline: none; }
.offset-rail { position: absolute; inset: 14px 0 auto; height: 4px; border-radius: 2px; background: var(--color-secondary-bg, var(--line)); }
.offset-fill { position: absolute; inset: 0 auto 0 0; width: var(--offset-position); border-radius: inherit; background: var(--color-primary, var(--accent)); }
.offset-thumb { position: absolute; top: 50%; left: var(--offset-position); width: 17px; height: 17px; border: 2px solid var(--color-body-bg); border-radius: 50%; background: var(--color-primary, var(--accent)); box-shadow: 0 1px 4px rgb(0 0 0 / 18%); transform: translate(-50%, -50%); }
.offset-slider:focus-visible .offset-thumb { outline: 2px solid var(--color-primary, var(--accent)); outline-offset: 3px; }
.offset-scale { display: flex; justify-content: space-between; gap: 6px; margin-top: -5px; color: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; }
.offset-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.offset-actions button { display: inline-flex; align-items: center; justify-content: center; flex: 1 1 auto; gap: 5px; min-height: 34px; padding: 6px 8px; font-size: 12px; white-space: nowrap; }
.offset-original-icon { width: 17px; height: 17px; flex: 0 0 auto; background: currentColor; mask: var(--offset-icon) center / contain no-repeat; -webkit-mask: var(--offset-icon) center / contain no-repeat; }
.offset-exact { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-top: 8px; font-size: 12px; }
.offset-exact input { width: 78px; min-width: 0; padding: 6px 8px; font-size: 13px; font-variant-numeric: tabular-nums; }
.offset-exact > span:last-child { color: var(--muted); font-size: 11px; }
.disabled { opacity: .5; }
.disabled .offset-slider { cursor: default; }
</style>
