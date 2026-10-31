<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch, type StyleValue } from 'vue'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  value: number | string
  min?: number | string
  max?: number | string
  step?: number | string
  disabled?: boolean
  variant?: 'default' | 'color'
}>(), { min: 0, max: 100, step: 1, disabled: false, variant: 'default' })
const emit = defineEmits<{ input: [event: Event]; change: [event: Event] }>()
const input = ref<HTMLInputElement>()
const path = ref<HTMLElement>()
const thumb = ref<HTMLElement>()
const current = ref(Number(props.value))
const percent = computed(() => {
  const span = Number(props.max) - Number(props.min)
  return span > 0 ? Math.max(0, Math.min(100, (current.value - Number(props.min)) / span * 100)) : 0
})
let drag: { pointer: number; x: number; value: number; scale: number; changed: boolean } | undefined

function inputValue(event: Event) {
  current.value = (event.target as HTMLInputElement).valueAsNumber
  emit('input', event)
}
function beginDrag(event: PointerEvent) {
  if (props.disabled || drag || !event.isPrimary || event.button !== 0 || !input.value) return
  const width = path.value?.getBoundingClientRect().width ?? 0
  if (width <= 0) return
  event.preventDefault()
  input.value.focus({ preventScroll: true })
  drag = { pointer: event.pointerId, x: event.clientX, value: input.value.valueAsNumber, scale: (Number(props.max) - Number(props.min)) / width, changed: false }
  thumb.value?.setPointerCapture(event.pointerId)
}
function moveDrag(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.pointer || !input.value) return
  // The native input supplies step rounding and the existing input/change contract.
  input.value.value = String(drag.value + (event.clientX - drag.x) * drag.scale)
  if (input.value.valueAsNumber === current.value) return
  drag.changed = true
  input.value.dispatchEvent(new Event('input', { bubbles: true }))
}
function release(commit: boolean) {
  const previous = drag
  drag = undefined
  if (previous && thumb.value?.hasPointerCapture(previous.pointer)) thumb.value.releasePointerCapture(previous.pointer)
  if (commit && previous?.changed) input.value?.dispatchEvent(new Event('change', { bubbles: true }))
}
function endDrag(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.pointer) return
  if (event.type === 'pointerup') moveDrag(event)
  release(true)
}
watch(() => props.value, value => { if (!drag) current.value = Number(value) })
watch(() => props.disabled, disabled => { if (disabled) release(false) })
onBeforeUnmount(() => release(false))
</script>

<template>
  <span class="thumb-range" :class="[$attrs.class, { 'thumb-range-color': variant === 'color', 'thumb-range-disabled': disabled }]" :style="[$attrs.style as StyleValue, { '--range-position': `${percent}%` }]" @click.prevent>
    <input v-bind="$attrs" ref="input" class="thumb-range-input" type="range" :min="min" :max="max" :step="step" :value="current" :disabled="disabled" @input="inputValue" @change="emit('change', $event)" />
    <span ref="path" class="thumb-range-path" aria-hidden="true">
      <span class="thumb-range-rail"><span class="thumb-range-fill"></span></span>
      <span ref="thumb" class="thumb-range-thumb" @pointerdown="beginDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag" @lostpointercapture="endDrag"></span>
    </span>
  </span>
</template>

<style scoped>
.thumb-range { --range-thumb-width: 18px; position: relative; display: block; width: 100%; min-width: 0; max-width: 100%; height: 32px; margin: 0; padding: 0; border: 0; touch-action: pan-y; user-select: none; }
.thumb-range > .thumb-range-input { position: absolute; inset: 0; display: block; width: 100%; height: 100%; min-width: 0; margin: 0; padding: 0; border: 0; opacity: 0; pointer-events: none; }
.thumb-range-path { position: absolute; top: 50%; left: calc(var(--range-thumb-width) / 2 + 4px); right: calc(var(--range-thumb-width) / 2 + 4px); height: 0; pointer-events: none; }
.thumb-range-rail { position: absolute; top: -2px; left: 0; right: 0; height: 4px; border-radius: 2px; background: var(--line); }
.thumb-range-fill { display: block; width: var(--range-position); height: 100%; border-radius: inherit; background: var(--accent); }
.thumb-range-thumb { position: absolute; top: 0; left: var(--range-position); width: var(--range-thumb-width); height: 18px; border: 2px solid var(--color-body-bg); border-radius: 50%; background: var(--accent); box-shadow: 0 1px 3px rgb(0 0 0 / 20%); transform: translate(-50%, -50%); pointer-events: auto; touch-action: none; cursor: ew-resize; transition: box-shadow .12s ease; }
.thumb-range-input:focus-visible ~ .thumb-range-path .thumb-range-thumb { outline: 2px solid var(--accent); outline-offset: 2px; }
.thumb-range-color { --range-thumb-width: 12px; height: 34px; }
.thumb-range-color .thumb-range-rail { display: none; }
.thumb-range-color .thumb-range-thumb { height: 28px; border-color: #fff; border-radius: 5px; background: transparent; box-shadow: 0 0 0 1px #0009; }
.thumb-range-disabled { opacity: .5; }
.thumb-range-disabled .thumb-range-thumb { pointer-events: none; cursor: default; }
</style>
