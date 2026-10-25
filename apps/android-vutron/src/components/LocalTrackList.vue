<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { VutronTrack } from '../platform/vutronPlugin'
import { trackKey, moveTrackKey, useCollectionTrackOrder, type CollectionOrder } from '../composables/localTrackOrdering'
import { useProgressiveTracks, useTrackSearch } from '../composables/libraryPresentation'
import { vTrackMenu } from '../composables/trackActionMenu'
import TrackMenuButton from './TrackMenuButton.vue'
import TrackListPager from './TrackListPager.vue'
import OriginalIcon from './OriginalIcon.vue'

const props = withDefaults(defineProps<{ tracks: VutronTrack[]; collectionKey: string; query?: string; loading?: boolean; localOnly?: boolean }>(), { query: '' })
const emit = defineEmits<{ play: [track: VutronTrack, queue: VutronTrack[]]; menu: [track: VutronTrack]; orderChange: [keys: string[]] }>()
const { t } = useI18n()
const source = computed(() => props.tracks)
const { mode, ordered, commit, restore } = useCollectionTrackOrder(source, toRef(props, 'collectionKey'))
const normalizedQuery = computed(() => props.query.trim().toLowerCase())
const filtered = useTrackSearch(ordered, normalizedQuery)
const { visible, hasMore, showMore } = useProgressiveTracks(filtered, [toRef(props, 'collectionKey'), normalizedQuery, mode])
const message = ref('')
const list = ref<HTMLElement>()
const drag = ref<{ key: string; pointer: number; x: number; y: number; startX: number; startY: number; active: boolean; anchor: string; after: boolean; label: string }>()
let animation = 0
const canDrag = computed(() => mode.value === 'custom' && !normalizedQuery.value && !props.loading)
const playable = computed(() => ordered.value.filter(row => !row.missingLocal && (!props.localOnly || !row.onlineOnly)))
const missingCount = computed(() => ordered.value.filter(row => row.missingLocal).length)
function reportOrder() { emit('orderChange', ordered.value.map(trackKey)) }
function changeMode(event: Event) {
  cancelDrag()
  try { mode.value = (event.target as HTMLSelectElement).value as CollectionOrder; reportOrder(); message.value = '' }
  catch { message.value = t('mobile.ordering.saveFailed') }
}
function restoreOrder() {
  cancelDrag()
  try { restore(); reportOrder(); message.value = t('mobile.ordering.restored') }
  catch { message.value = t('mobile.ordering.saveFailed') }
}
function startDrag(event: PointerEvent, track: VutronTrack) {
  if (!canDrag.value || event.button !== 0 || !event.isPrimary) return
  const target = event.currentTarget as HTMLElement
  target.setPointerCapture(event.pointerId)
  drag.value = { key: trackKey(track), pointer: event.pointerId, x: event.clientX, y: event.clientY,
    startX: event.clientX, startY: event.clientY, active: false, anchor: '', after: false, label: track.name }
}
function findAnchor() {
  const state = drag.value
  if (!state?.active) return
  const row = document.elementFromPoint(state.x, state.y)?.closest<HTMLElement>('[data-local-track-key]')
  if (!row || !list.value?.contains(row) || row.dataset.localTrackKey === state.key) return
  state.anchor = row.dataset.localTrackKey || ''
  const bounds = row.getBoundingClientRect(); state.after = state.y > bounds.top + bounds.height / 2
}
function autoscroll() {
  const state = drag.value
  if (!state?.active) return
  const top = 90, bottom = window.innerHeight - 150
  const speed = state.y < top ? -Math.min(20, (top - state.y) / 3) : state.y > bottom ? Math.min(20, (state.y - bottom) / 3) : 0
  if (speed) { window.scrollBy({ top: speed, behavior: 'instant' }); findAnchor() }
  if (speed > 0 && hasMore.value && list.value && list.value.getBoundingClientRect().bottom < window.innerHeight + 100) showMore()
  animation = requestAnimationFrame(autoscroll)
}
function moveDrag(event: PointerEvent) {
  const state = drag.value
  if (!state || state.pointer !== event.pointerId) return
  state.x = event.clientX; state.y = event.clientY
  if (!state.active && Math.hypot(state.x - state.startX, state.y - state.startY) > 6) { state.active = true; animation = requestAnimationFrame(autoscroll) }
  findAnchor()
}
function finishDrag(event: PointerEvent) {
  const state = drag.value
  if (!state || state.pointer !== event.pointerId) return
  if (state.active && state.anchor) {
    try { commit(moveTrackKey(ordered.value.map(trackKey), state.key, state.anchor, state.after)); reportOrder(); message.value = t('mobile.ordering.saved') }
    catch { message.value = t('mobile.ordering.saveFailed') }
  }
  cancelDrag()
}
function cancelDrag() { cancelAnimationFrame(animation); drag.value = undefined }
function keyboard(event: KeyboardEvent) { if (event.key === 'Escape' && drag.value) { event.preventDefault(); event.stopImmediatePropagation(); cancelDrag() } }
function moveKeyboard(event: KeyboardEvent, track: VutronTrack) {
  if (!canDrag.value || !['ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault(); event.stopPropagation()
  const keys = ordered.value.map(trackKey), key = trackKey(track), index = keys.indexOf(key)
  const other = keys[index + (event.key === 'ArrowUp' ? -1 : 1)]
  if (!other) return
  try { commit(moveTrackKey(keys, key, other, event.key === 'ArrowDown')); reportOrder(); message.value = t('mobile.ordering.saved') }
  catch { message.value = t('mobile.ordering.saveFailed') }
}
function play(track: VutronTrack) {
  if (drag.value?.active) return
  if (track.missingLocal) { message.value = t('mobile.backup.missingHint'); return }
  if (props.localOnly && track.onlineOnly) { message.value = t('mobile.ordering.requiresOnline'); return }
  emit('play', track, playable.value)
}
watch(() => props.collectionKey, cancelDrag)
watch(source, cancelDrag)
onMounted(() => document.addEventListener('keydown', keyboard, true))
onBeforeUnmount(() => { cancelDrag(); document.removeEventListener('keydown', keyboard, true) })
</script>

<template>
  <div class="local-track-section">
    <div class="local-order-tools">
      <button class="text-button" type="button" :disabled="!playable.length || loading" @click="playable[0] && play(playable[0])"><OriginalIcon name="play" />{{ $t('mobile.ordering.playAll', { count: playable.length }) }}</button>
      <label><span>{{ $t('mobile.library.sort') }}</span><select :value="mode" :aria-label="$t('mobile.library.sortLabel')" @change="changeMode"><option value="original">{{ $t('mobile.library.originalOrder') }}</option><option value="title">{{ $t('mobile.library.title') }}</option><option value="artist">{{ $t('nav.artist') }}</option><option value="album">{{ $t('nav.album') }}</option><option value="custom">{{ $t('mobile.ordering.custom') }}</option></select></label>
    </div>
    <div v-if="mode === 'custom'" class="local-order-hint"><span>{{ query ? $t('mobile.ordering.clearFilter') : $t('mobile.ordering.dragHint') }}</span><button class="text-button" type="button" @click="restoreOrder">{{ $t('mobile.ordering.restore') }}</button><button v-if="drag" class="secondary-button sort-cancel-floating" type="button" @click="cancelDrag">{{ $t('mobile.management.cancel') }}</button></div>
    <p v-if="message" class="local-order-message" role="status">{{ message }}</p>
    <p v-if="missingCount" class="local-order-message">{{ $t('mobile.backup.missing', { count: missingCount }) }}</p>
    <div ref="list" class="track-list">
      <div v-for="(track, index) in visible" :key="trackKey(track)" :data-local-track-key="trackKey(track)" class="track-row" :class="{ 'sort-moving': drag?.active && drag.key === trackKey(track), 'sort-before': drag?.anchor === trackKey(track) && !drag.after, 'sort-after': drag?.anchor === trackKey(track) && drag.after }" role="button" tabindex="0" v-track-menu="() => emit('menu', track)" @click="play(track)" @keydown.enter.self.prevent="play(track)" @keydown.space.self.prevent="play(track)">
        <button v-if="mode === 'custom'" class="sort-handle icon-button" type="button" :disabled="!canDrag" :aria-label="$t('mobile.ordering.move', { name: track.name })" @click.stop @contextmenu.prevent.stop @pointerdown.stop.prevent="startDrag($event, track)" @pointermove.stop.prevent="moveDrag" @pointerup.stop.prevent="finishDrag" @pointercancel="cancelDrag" @lostpointercapture="cancelDrag" @keydown="moveKeyboard($event, track)"><span aria-hidden="true">⠿</span></button>
        <span v-else class="track-index">{{ index + 1 }}</span>
        <span class="track-copy"><b>{{ track.name }}</b><small>{{ track.artists?.join(' / ') || track.album || $t('mobile.common.local') }}</small></span>
        <span v-if="track.missingLocal" class="offline-badge">{{ $t('mobile.backup.unlinked') }}</span><span v-else-if="track.onlineOnly" class="offline-badge">{{ $t('mobile.ordering.online') }}</span>
        <TrackMenuButton :name="track.name" @open="emit('menu', track)" />
      </div>
      <div v-if="loading" class="empty-state">{{ $t('mobile.ordering.loading') }}</div>
      <div v-else-if="!visible.length" class="empty-state">{{ query ? $t('mobile.ordering.noMatches') : $t('mobile.ordering.empty') }}</div>
      <TrackListPager :more="hasMore" @more="showMore" />
    </div>
    <Teleport to="body"><div v-if="drag?.active" class="sort-floating-label" :style="{ left: drag.x + 12 + 'px', top: drag.y - 28 + 'px' }">{{ drag.label }}</div></Teleport>
  </div>
</template>

<style scoped>
.local-order-tools, .local-order-hint { display:flex; align-items:center; justify-content:space-between; gap:8px; margin:8px 0; }
.local-order-tools > button { display:flex; align-items:center; gap:5px; }
.local-order-tools label { display:flex; align-items:center; gap:6px; font-size:11px; color:var(--muted); }
.local-order-tools select { padding:7px 9px; border:1px solid var(--line); border-radius:10px; color:var(--ink); background:var(--color-body-bg); }
.local-order-hint, .local-order-message { font-size:11px; color:var(--muted); }
.local-order-hint { flex-wrap:wrap; }.sort-handle { flex:0 0 30px; width:30px; touch-action:none; cursor:grab; font-size:23px; }
.sort-moving { opacity:.45; }.sort-before { box-shadow:inset 0 2px var(--accent); }.sort-after { box-shadow:inset 0 -2px var(--accent); }
.sort-cancel-floating { position:fixed; right:16px; bottom:160px; z-index:10001; border-radius:12px; box-shadow:0 3px 14px #0002; }
.sort-floating-label { position:fixed; z-index:10000; pointer-events:none; padding:9px 12px; max-width:65vw; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; background:var(--color-body-bg); color:var(--ink); border:1px solid var(--accent); border-radius:12px; box-shadow:0 4px 20px #0003; }
</style>
