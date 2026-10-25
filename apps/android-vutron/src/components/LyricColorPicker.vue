<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  argbCss, argbHex, argbToHsv, clampUnit, hsvToArgb, lyricColorPresets, lyricColorTargets, parseArgbHex,
  type LyricColorKey, type LyricColorSettings
} from '../utils/lyricColors'

const props = defineProps<{ settings: LyricColorSettings }>()
const emit = defineEmits<{ change: [key: LyricColorKey, value: number]; commit: [] }>()
const selected = ref<LyricColorKey>('playedColor')
const colors = reactive({ ...props.settings })
const hsv = reactive(argbToHsv(props.settings.playedColor))
const hexInput = ref(argbHex(props.settings.playedColor))
const hexError = ref('')
const hexFocused = ref(false)
const activePointer = ref<number>()
const currentColor = computed(() => colors[selected.value] >>> 0)
const hueColor = computed(() => argbCss(hsvToArgb({ hue: hsv.hue, saturation: 1, value: 1, alpha: 255 })))
const opaqueColor = computed(() => argbCss((currentColor.value | 0xff000000) >>> 0))
const alphaPercent = computed(() => Math.round(hsv.alpha / 255 * 100))
const previewShadow = computed(() => props.settings.shadowEnabled !== false && (props.settings.shadowRadius ?? 2) > 0
  ? '0 1px ' + Math.max(0, Math.min(8, props.settings.shadowRadius ?? 2)) + 'px ' + argbCss(colors.shadowColor) : 'none')

watch(() => lyricColorTargets.map(target => props.settings[target.key]), values => {
  lyricColorTargets.forEach((target, index) => { colors[target.key] = values[index] >>> 0 })
  syncActiveColor()
})

function syncActiveColor() {
  Object.assign(hsv, argbToHsv(colors[selected.value], hsv.hue))
  if (!hexFocused.value) hexInput.value = argbHex(colors[selected.value])
}

function selectTarget(key: LyricColorKey) {
  selected.value = key
  hexFocused.value = false
  hexError.value = ''
  syncActiveColor()
}

function applyColor(key: LyricColorKey, value: number, fromHex = false) {
  const unsigned = value >>> 0
  const changed = (colors[key] >>> 0) !== unsigned
  colors[key] = unsigned
  if (key === selected.value) {
    Object.assign(hsv, argbToHsv(unsigned, hsv.hue))
    if (!fromHex) hexInput.value = argbHex(unsigned)
    hexError.value = ''
  }
  if (changed) emit('change', key, unsigned)
}

function commitHsv() { applyColor(selected.value, hsvToArgb(hsv)) }

function updateSurface(event: PointerEvent) {
  const surface = event.currentTarget as HTMLElement
  const bounds = surface.getBoundingClientRect()
  if (bounds.width <= 0 || bounds.height <= 0) return
  hsv.saturation = clampUnit((event.clientX - bounds.left) / bounds.width)
  hsv.value = 1 - clampUnit((event.clientY - bounds.top) / bounds.height)
  commitHsv()
}

function startSurfaceDrag(event: PointerEvent) {
  if (activePointer.value !== undefined || (event.pointerType === 'mouse' && event.button !== 0)) return
  activePointer.value = event.pointerId
  const surface = event.currentTarget as HTMLElement
  surface.focus({ preventScroll: true })
  surface.setPointerCapture(event.pointerId)
  event.preventDefault()
  updateSurface(event)
}

function moveSurfaceDrag(event: PointerEvent) {
  if (event.pointerId === activePointer.value) updateSurface(event)
}

function finishSurfaceDrag(event: PointerEvent) {
  if (event.pointerId !== activePointer.value) return
  if (event.type === 'pointerup') updateSurface(event)
  activePointer.value = undefined
  const surface = event.currentTarget as HTMLElement
  if (surface.hasPointerCapture(event.pointerId)) surface.releasePointerCapture(event.pointerId)
  emit('commit')
}

function keySurface(event: KeyboardEvent) {
  const amount = event.shiftKey ? .1 : .01
  if (event.key === 'ArrowLeft') hsv.saturation = clampUnit(hsv.saturation - amount)
  else if (event.key === 'ArrowRight') hsv.saturation = clampUnit(hsv.saturation + amount)
  else if (event.key === 'ArrowUp') hsv.value = clampUnit(hsv.value + amount)
  else if (event.key === 'ArrowDown') hsv.value = clampUnit(hsv.value - amount)
  else if (event.key === 'Home') { hsv.saturation = 0; hsv.value = 1 }
  else if (event.key === 'End') { hsv.saturation = 1; hsv.value = 0 }
  else return
  event.preventDefault()
  commitHsv()
}

function rangeInput(kind: 'hue' | 'alpha', event: Event) {
  hsv[kind] = Number((event.target as HTMLInputElement).value)
  commitHsv()
}

function editHex() {
  const parsed = parseArgbHex(hexInput.value)
  if (parsed !== undefined) applyColor(selected.value, parsed, true)
}

function finishHex() {
  hexFocused.value = false
  const parsed = parseArgbHex(hexInput.value)
  if (parsed === undefined) { hexError.value = '请输入 #RRGGBB 或 #AARRGGBB'; return }
  applyColor(selected.value, parsed)
  hexInput.value = argbHex(parsed)
  emit('commit')
}

function presetColors(values: Partial<Record<LyricColorKey, number>>) {
  for (const target of lyricColorTargets) {
    const value = values[target.key]
    if (value !== undefined) applyColor(target.key, value)
  }
}
</script>

<template>
  <section class="lyric-color-picker" aria-label="歌词颜色与背景">
    <div class="lyric-color-heading"><b>歌词颜色与背景</b><small>有效颜色自动保存</small></div>
    <div class="lyric-color-preview checkerboard" aria-label="原文与译文颜色预览">
      <div :style="{ backgroundColor: argbCss(colors.backgroundColor), textShadow: previewShadow }">
        <p><span :style="{ color: argbCss(colors.playedColor) }">已唱歌词</span><span :style="{ color: argbCss(colors.unplayedColor) }"> · 未唱歌词</span></p>
        <small><span :style="{ color: argbCss(colors.playedColor) }">同步译文</span><span :style="{ color: argbCss(colors.unplayedColor) }"> · 颜色预览</span></small>
      </div>
    </div>
    <div class="lyric-color-targets" role="group" aria-label="选择调色目标">
      <button v-for="target in lyricColorTargets" :key="target.key" type="button" :class="{ selected: selected === target.key }" :aria-pressed="selected === target.key" @click="selectTarget(target.key)">
        <span class="color-swatch checkerboard"><i :style="{ backgroundColor: argbCss(colors[target.key]) }"></i></span>{{ target.label }}
      </button>
    </div>
    <div class="sv-caption"><label :for="'lyric-sv-' + selected">饱和度 / 明度</label><small>{{ Math.round(hsv.saturation * 100) }}% / {{ Math.round(hsv.value * 100) }}%</small></div>
    <div :id="'lyric-sv-' + selected" class="lyric-sv-surface" tabindex="0" role="slider" aria-label="饱和度与明度" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.round(hsv.saturation * 100)" :aria-valuetext="'饱和度 ' + Math.round(hsv.saturation * 100) + '%，明度 ' + Math.round(hsv.value * 100) + '%。左右调整饱和度，上下调整明度。'" :style="{ backgroundColor: hueColor }" @pointerdown="startSurfaceDrag" @pointermove="moveSurfaceDrag" @pointerup="finishSurfaceDrag" @pointercancel="finishSurfaceDrag" @lostpointercapture="activePointer = undefined" @keydown="keySurface">
      <span class="sv-thumb" :style="{ left: hsv.saturation * 100 + '%', top: (1 - hsv.value) * 100 + '%', backgroundColor: opaqueColor }"></span>
    </div>
    <label class="lyric-channel"><span>色相 <small>{{ Math.round(hsv.hue) }}°</small></span><input type="range" min="0" max="360" step="1" :value="hsv.hue" class="hue-channel" aria-label="歌词色相" @input="rangeInput('hue', $event)" @change="emit('commit')" /></label>
    <label class="lyric-channel"><span>不透明度 <small>{{ alphaPercent }}%</small></span><span class="alpha-channel checkerboard"><i :style="{ background: 'linear-gradient(to right, transparent, ' + opaqueColor + ')' }"></i><input type="range" min="0" max="255" step="1" :value="hsv.alpha" :aria-valuetext="alphaPercent + '%'" aria-label="歌词颜色不透明度" @input="rangeInput('alpha', $event)" @change="emit('commit')" /></span></label>
    <label class="lyric-hex-field"><span>{{ lyricColorTargets.find(target => target.key === selected)?.label }}颜色值</span><input v-model="hexInput" type="text" inputmode="text" autocomplete="off" spellcheck="false" maxlength="9" aria-label="歌词十六进制颜色" :aria-invalid="Boolean(hexError)" @focus="hexFocused = true" @input="editHex" @blur="finishHex" @keydown.enter.prevent="finishHex" /></label>
    <Transition name="lyric-color-feedback"><p v-if="hexError" class="lyric-color-error" role="alert">{{ hexError }}</p></Transition>
    <p class="lyric-color-help">#AARRGGBB 开头两位控制不透明度：00 透明，FF 不透明。键盘方向键微调，Shift 加大步长。</p>
    <div class="lyric-color-presets"><button v-for="preset in lyricColorPresets" :key="preset.name" type="button" @click="presetColors(preset.values)">{{ preset.name }}</button></div>
  </section>
</template>

<style scoped>
.lyric-color-picker { min-width: 0; display: grid; gap: 10px; color: var(--color-text); }
.lyric-color-heading, .sv-caption, .lyric-channel > span:first-child { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.lyric-color-heading b { font-size: 13px; font-weight: 600; }
.lyric-color-heading small, .sv-caption, .lyric-channel > span:first-child, .lyric-hex-field { font-size: 12px; }
.lyric-color-heading small, .sv-caption small, .lyric-channel small, .lyric-color-help { color: var(--color-secondary); }
.checkerboard { background-color: #fff; background-image: linear-gradient(45deg, #ccc 25%, transparent 25%), linear-gradient(-45deg, #ccc 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #ccc 75%), linear-gradient(-45deg, transparent 75%, #ccc 75%); background-size: 12px 12px; background-position: 0 0, 0 6px, 6px -6px, -6px 0; }
.lyric-color-preview { overflow: hidden; border: 1px solid var(--line); border-radius: 10px; }
.lyric-color-preview > div { min-height: 72px; padding: 14px 8px; text-align: center; }
.lyric-color-preview p { margin: 0 0 5px; font-size: 18px; font-weight: 600; line-height: 1.4; }
.lyric-color-preview small { font-size: 12px; }
.lyric-color-targets { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 5px; }
.lyric-color-targets button { min-width: 0; min-height: 36px; display: flex; justify-content: center; align-items: center; gap: 5px; padding: 6px 4px; border: 1px solid transparent; border-radius: 8px; background: var(--color-secondary-bg); color: var(--color-text); font: inherit; font-size: 12px; }
.lyric-color-targets button.selected { border-color: var(--color-primary); color: var(--color-primary); background: var(--accent-soft); }
.lyric-color-targets button, .lyric-color-presets button { transition: background-color 125ms ease-out, color 125ms ease-out, border-color 125ms ease-out, transform .2s ease; }
.lyric-color-feedback-enter-active, .lyric-color-feedback-leave-active { transition: opacity 125ms ease-out, transform 125ms ease-out; }
.lyric-color-feedback-enter-from, .lyric-color-feedback-leave-to { opacity: 0; transform: translateY(5px); }
.color-swatch { flex: 0 0 13px; width: 13px; height: 13px; overflow: hidden; border: 1px solid var(--line); border-radius: 3px; }
.color-swatch i { display: block; width: 100%; height: 100%; }
.lyric-sv-surface { position: relative; min-width: 0; width: 100%; height: 144px; border: 1px solid var(--line); border-radius: 8px; background-image: linear-gradient(to top, #000, transparent), linear-gradient(to right, #fff, transparent); touch-action: none; cursor: crosshair; }
.sv-thumb { position: absolute; width: 12px; height: 12px; border: 2px solid #fff; border-radius: 50%; box-shadow: 0 0 0 1px #0009; transform: translate(-50%, -50%); pointer-events: none; }
.lyric-channel { display: grid; gap: 2px; min-width: 0; }
.lyric-channel input { display: block; width: 100%; min-width: 0; height: 30px; margin: 0; padding: 0; -webkit-appearance: none; appearance: none; border: 0; background: transparent; touch-action: pan-y; cursor: pointer; }
.hue-channel { border-radius: 8px; background-image: linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00) !important; }
.alpha-channel { display: block; width: 100%; height: 30px; position: relative; overflow: hidden; border-radius: 8px; }
.alpha-channel > i { position: absolute; inset: 0; pointer-events: none; }
.alpha-channel > input { position: relative; }
.lyric-channel input::-webkit-slider-runnable-track { height: 30px; border: 0; background: transparent; }
.lyric-channel input::-webkit-slider-thumb { width: 11px; height: 30px; -webkit-appearance: none; appearance: none; border: 2px solid #fff; border-radius: 5px; background: transparent; box-shadow: 0 0 0 1px #0009; }
.lyric-channel input::-moz-range-track { height: 30px; background: transparent; border: 0; }
.lyric-channel input::-moz-range-thumb { width: 9px; height: 26px; border: 2px solid #fff; border-radius: 5px; background: transparent; box-shadow: 0 0 0 1px #0009; }
.lyric-hex-field { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.lyric-hex-field input { min-width: 0; width: 132px; height: 36px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; color: var(--color-text); background: var(--color-secondary-bg); font: inherit; font-family: ui-monospace, monospace; }
.lyric-hex-field input[aria-invalid='true'] { border-color: #c94953; }
.lyric-color-error { margin: 0; color: #c94953; font-size: 11px; }
.lyric-color-help { margin: 0; font-size: 10px; line-height: 1.6; overflow-wrap: anywhere; }
.lyric-color-presets { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.lyric-color-presets button { min-width: 0; min-height: 34px; padding: 6px 3px; border: 0; border-radius: 8px; background: var(--color-secondary-bg); color: var(--color-text); font: inherit; font-size: 12px; }
.lyric-color-picker button:focus-visible, .lyric-color-picker input:focus-visible, .lyric-sv-surface:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.lyric-color-picker button:active { transform: scale(.97); }
@media (max-width: 340px) { .lyric-color-heading { align-items: start; flex-direction: column; gap: 3px; } .lyric-color-targets button { gap: 3px; font-size: 11px; } }
html[data-motion="reduced"] .lyric-color-targets button, html[data-motion="reduced"] .lyric-color-presets button, html[data-motion="reduced"] .lyric-color-feedback-enter-active, html[data-motion="reduced"] .lyric-color-feedback-leave-active { transition: none; }
</style>
