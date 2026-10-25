<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useLibraryManagement } from '../composables/libraryManagement'
import { onlineTrackId } from '../stores/domains/neteaseLibrary'
import { useAndroidShellStore } from '../stores/androidShell'
import { vutronPlugin, type VutronTrack } from '../platform/vutronPlugin'
import { createLiveSettingWriter } from '../platform/liveSettingWrites'
import OriginalIcon from './OriginalIcon.vue'
import CollapsibleSettingsCard from './CollapsibleSettingsCard.vue'

const props = defineProps<{ track: VutronTrack }>()
const emit = defineEmits<{ close: [] }>()
const store = useAndroidShellStore()
const management = useLibraryManagement()
const cacheTrackId = computed(() => props.track.neteaseId || (!props.track.localUri && !props.track.localPath || props.track.cached ? props.track.id : 0))
const hasCache = computed(() => cacheTrackId.value > 0 && store.cachedTracks.some(item => item.id === cacheTrackId.value))
const isLocal = computed(() => Boolean(props.track.localFolderId || props.track.localPlaylistId))
const canUseOnline = computed(() => store.onlineAvailable && onlineTrackId(props.track) > 0)
async function toggleLike() { await store.toggleTrackLike(props.track) }
function addToPlaylist() { store.openAddToOnlinePlaylist(props.track); close() }
async function removeFromOnlinePlaylist() { if (await store.removeTrackFromOnlinePlaylist(props.track)) close() }
async function deleteCache() { if (await management.deleteCachedTrack(props.track)) close() }
async function removeLocal() { if (await management.removeLocalTrack(props.track)) close() }
const dialog = ref<HTMLElement>()
const busy = ref(false)
const volume = ref<number | string>(100)
const notice = ref('')
let volumeRead = 0
let volumeEdit = 0
let mounted = true
function volumeIdentity(track: VutronTrack) {
  const savedKey = track.trackVolumeKey?.trim()
  if (savedKey) return 'volume:' + savedKey
  const uri = track.localUri?.trim() || track.localPath?.trim()
  const id = track.neteaseId ?? track.id
  if (id > 0 && (!uri || (track.neteaseId ?? 0) > 0)) return 'volume:track:id:' + id
  return 'volume:local-uri:' + uri
}
type VolumeLane = { writer: ReturnType<typeof createLiveSettingWriter<number>>; lastValue?: number }
const volumeLanes = new Map<string, VolumeLane>()

function volumeLane() {
  const key = volumeIdentity(props.track)
  let lane = volumeLanes.get(key)
  if (!lane) {
    // Capture the candidate, not whichever track is playing when a write finishes.
    const track = { ...props.track, artists: props.track.artists?.slice() }
    lane = { writer: createLiveSettingWriter<number>({
      async write(percent) {
        await vutronPlugin.setTrackVolume({ track, percent })
        if (mounted && volumeIdentity(props.track) === key && Number(volume.value) === percent) notice.value = `已保存这首歌曲的音量：${percent}%`
      },
      onError(error) { if (mounted && volumeIdentity(props.track) === key) notice.value = error instanceof Error ? error.message : '保存单曲音量失败' }
    }) }
    volumeLanes.set(key, lane)
  }
  return lane
}

function close() { emit('close') }
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); close(); return }
  if (event.key !== 'Tab') return
  const controls = Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),summary') ?? [])
    .filter(element => element.getClientRects().length > 0 && !element.closest('[inert]'))
  const first = controls[0], last = controls.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}

async function action(kind: 'next' | 'append' | 'play' | 'cache') {
  if (busy.value) return
  busy.value = true
  const track = props.track
  try {
    if (kind === 'next') { if (!await store.queueTrackNext(track)) { notice.value = store.error; return } }
    else if (kind === 'append') { if (!await store.appendTrackToQueue(track)) { notice.value = store.error; return } }
    else if (kind === 'play') { await store.play(track, track.playlistVolumeKey, [track]); if (store.error) { notice.value = store.error; return } }
    else {
      if (!vutronPlugin.cacheTrack) throw new Error('当前版本暂不支持缓存')
      const result = await vutronPlugin.cacheTrack({ track })
      store.queueNotice = result.cached ? `已保存或加入缓存：${track.name}` : `歌曲已在缓存队列：${track.name}`
    }
    close()
  } catch (error) { notice.value = error instanceof Error ? error.message : '歌曲操作失败' }
  finally { busy.value = false }
}

async function showVolume() {
  const request = ++volumeRead
  const revision = volumeEdit
  const track = { ...props.track }
  const key = volumeIdentity(track)
  try {
    const settings = await vutronPlugin.getVolumeSettings({ track })
    if (mounted && request === volumeRead && revision === volumeEdit && volumeIdentity(props.track) === key) {
      const draft = volumeLanes.get(key)?.lastValue
      volume.value = draft ?? settings.trackPercent
    }
  } catch (error) { if (mounted && request === volumeRead && revision === volumeEdit) notice.value = error instanceof Error ? error.message : '读取单曲音量失败' }
}
function volumeToggle(event: Event) {
  if ((event.target as HTMLDetailsElement).open) void showVolume()
}
function queueVolume(reportInvalid = false) {
  const percent = Number(volume.value)
  if (String(volume.value).trim() === '' || !Number.isInteger(percent) || percent < 0 || percent > 100) { if (reportInvalid) notice.value = '请输入 0–100 的整数百分比'; return }
  const lane = volumeLane()
  if (lane.lastValue !== percent) { lane.lastValue = percent; void lane.writer.queue(percent) }
  return lane
}
function volumeInput(event: Event) {
  volumeEdit++
  notice.value = ''
  volume.value = (event.target as HTMLInputElement).value
  queueVolume()
}
async function saveVolume() { await queueVolume(true)?.writer.flush() }
watch(() => props.track, () => { volumeRead++; volumeEdit++; notice.value = ''; volume.value = volumeLanes.get(volumeIdentity(props.track))?.lastValue ?? 100 })
onMounted(async () => { document.addEventListener('keydown', keyboard, true); if (canUseOnline.value) void store.loadLikedTracks(); await nextTick(); dialog.value?.querySelector<HTMLButtonElement>('button')?.focus({ preventScroll: true }) })
onUnmounted(() => { mounted = false; volumeRead++; for (const lane of volumeLanes.values()) void lane.writer.flush(); document.removeEventListener('keydown', keyboard, true) })
</script>

<template>
    <div class="track-action-layer" @click.self="close">
      <section ref="dialog" class="track-action-menu" role="dialog" aria-modal="true" aria-label="歌曲操作">
        <header><div><h2>{{ track.name }}</h2><p>{{ track.artists?.join(' / ') || track.album || '歌曲操作' }}</p></div><button class="icon-button" type="button" aria-label="关闭歌曲菜单" @click="close"><OriginalIcon name="close" /></button></header>
        <div class="track-action-options">
          <button v-if="canUseOnline" type="button" :disabled="busy || store.onlineMutationBusy || store.onlineNeedsRefresh || !store.likedLoaded" @click="toggleLike"><OriginalIcon :name="store.isTrackLiked(track) ? 'heartSolid' : 'heart'" /><span>{{ store.likedLoaded ? $t(store.isTrackLiked(track) ? 'mobile.netease.unlike' : 'mobile.netease.like') : $t('mobile.netease.likeLoading') }}</span></button>
          <button v-if="canUseOnline" type="button" :disabled="busy || store.onlineMutationBusy || store.onlineNeedsRefresh" @click="addToPlaylist"><OriginalIcon name="plus" /><span>{{ $t('mobile.netease.addTo') }}</span></button>
          <button v-if="store.removableOnlinePlaylist(track)" type="button" :disabled="busy || store.onlineMutationBusy || store.onlineNeedsRefresh" @click="removeFromOnlinePlaylist"><OriginalIcon name="close" /><span>{{ $t('mobile.netease.remove') }}</span></button>
          <button v-if="canUseOnline && (!store.likedLoaded || store.onlineNeedsRefresh)" type="button" :disabled="store.onlineMutationBusy || store.likesLoading" @click="store.onlineNeedsRefresh ? store.refreshOnlineLibrary() : store.loadLikedTracks(true)"><OriginalIcon name="refresh" /><span>{{ $t('mobile.netease.refresh') }}</span></button>
          <button v-if="isLocal" type="button" :disabled="busy" @click="removeLocal"><OriginalIcon name="close" /><span>{{ $t('mobile.management.removeTrack') }}<small>{{ $t('mobile.management.originalRetained') }}</small></span></button>
          <button v-if="hasCache" type="button" :disabled="busy" @click="deleteCache"><OriginalIcon name="close" /><span>{{ $t('mobile.management.deleteCache') }}<small>{{ $t('mobile.management.cacheRetained') }}</small></span></button>
          <button type="button" :disabled="busy" @click="action('next')"><OriginalIcon name="next" /><span>下一首播放<small>按添加顺序插队</small></span></button>
          <button type="button" :disabled="busy" @click="action('append')"><OriginalIcon name="list" /><span>添加到播放列表末尾<small>保留当前播放和插队顺序</small></span></button>
          <button type="button" :disabled="busy" @click="action('play')"><OriginalIcon name="play" /><span>立即播放</span></button>
          <button type="button" :disabled="busy" @click="action('cache')"><OriginalIcon name="down" /><span>缓存这首歌曲</span></button>
        </div>
        <CollapsibleSettingsCard :key="track.id + ':' + (track.localUri ?? '')" class="track-menu-volume-card" section-id="track-menu-volume" title="单曲音量" :subtitle="`仅调整「${track.name}」`" :remember="false" @toggle="volumeToggle">
          <form class="track-action-volume" @submit.prevent="saveVolume"><label>这首歌曲的音量<input :value="volume" type="range" min="0" max="100" step="1" aria-label="菜单歌曲音量" @input="volumeInput" @change="saveVolume" /></label><div><input :value="volume" type="number" min="0" max="100" step="1" inputmode="numeric" aria-label="菜单歌曲音量百分比" @input="volumeInput" @change="saveVolume" /><span>%</span><button class="secondary-button" type="submit">保存</button></div></form>
        </CollapsibleSettingsCard>
        <Transition name="track-feedback" mode="out-in"><p v-if="notice" :key="notice" class="track-action-notice" role="status">{{ notice }}</p></Transition>
      </section>
    </div>
</template>

<style scoped>
.track-action-layer { position: fixed; inset: 0; z-index: 120; display: flex; align-items: flex-end; justify-content: center; background: rgb(0 0 0 / 38%); }
.track-action-menu { width: min(100%, 640px); max-height: 88dvh; overflow-y: auto; overscroll-behavior: contain; padding: 18px 16px max(16px, env(safe-area-inset-bottom)); border-radius: 18px 18px 0 0; color: var(--color-text); background: var(--color-body-bg); box-shadow: 0 -8px 36px rgb(0 0 0 / 18%); }
header { display: flex; align-items: center; gap: 12px; padding: 0 2px 12px; border-bottom: 1px solid var(--line); }
header > div { min-width: 0; flex: 1; }
h2 { margin: 0; font-size: 17px; line-height: 1.4; overflow-wrap: anywhere; }
header p { margin: 5px 0 0; color: var(--muted); font-size: 12px; overflow-wrap: anywhere; }
.track-action-options { display: grid; gap: 5px; margin-top: 9px; }
.track-action-options > button { display: flex; align-items: center; gap: 14px; min-height: 52px; width: 100%; padding: 10px 12px; border: 0; border-radius: 10px; text-align: left; color: inherit; background: transparent; font: inherit; font-size: 14px; transition: background-color 125ms ease-out, color 125ms ease-out, opacity 125ms ease-out, transform .2s ease; }
.track-action-options > button:disabled { opacity: .45; }
.track-action-options > button:is([data-original-feedback], :focus-visible) { background-color: var(--accent-soft); }
@media (hover: hover) and (pointer: fine) { .track-action-options > button:hover { background-color: var(--accent-soft); } }
.track-action-options .original-icon { font-size: 20px; }
.track-action-options span { min-width: 0; overflow-wrap: anywhere; }
.track-action-options small { display: block; margin-top: 4px; color: var(--muted); font-size: 11px; }
.track-menu-volume-card { margin-top: 8px; padding: 10px 12px; box-shadow: none; background: var(--color-secondary-bg); }
.track-action-volume { padding: 0; font-size: 13px; }
.track-action-volume label, .track-action-volume label input { display: block; width: 100%; }
.track-action-volume label input { margin-top: 10px; }
.track-action-volume > div { display: flex; align-items: center; gap: 7px; margin-top: 10px; }
.track-action-volume input[type=number] { min-width: 0; width: 72px; }
.track-action-notice { margin: 10px 4px 0; color: var(--muted); font-size: 12px; }
.track-feedback-enter-active, .track-feedback-leave-active { transition: opacity 125ms ease-out, transform 125ms ease-out; }
.track-feedback-enter-from, .track-feedback-leave-to { opacity: 0; transform: translateY(5px); }
html[data-motion="reduced"] .track-action-options > button, html[data-motion="reduced"] .track-feedback-enter-active, html[data-motion="reduced"] .track-feedback-leave-active { transition: none; }
</style>

<style>
.track-menu-enter-active, .track-menu-leave-active { transition: opacity 125ms ease-out; }
.track-menu-enter-active .track-action-menu, .track-menu-leave-active .track-action-menu { transition: transform 125ms ease-out; }
.track-menu-enter-from, .track-menu-leave-to { opacity: 0; }
.track-menu-enter-from .track-action-menu, .track-menu-leave-to .track-action-menu { transform: translateY(28px); }
html[data-motion="reduced"] .track-menu-enter-active, html[data-motion="reduced"] .track-menu-leave-active, html[data-motion="reduced"] .track-menu-enter-active .track-action-menu, html[data-motion="reduced"] .track-menu-leave-active .track-action-menu { transition: none; }
</style>
