<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAndroidShellStore } from '../stores/androidShell'
import OriginalIcon from './OriginalIcon.vue'

const store = useAndroidShellStore(), { t } = useI18n()
const dialog = ref<HTMLElement>(), name = ref(''), description = ref(''), tags = ref(''), privacy = ref(0)
let returnFocus: HTMLElement | null = null
const state = computed(() => store.onlinePlaylistDialog)
const disabled = computed(() => store.onlineMutationBusy || store.onlineNeedsRefresh)
const title = computed(() => state.value?.kind === 'create' ? t('mobile.netease.create') : state.value?.kind === 'add' ? t('mobile.netease.addTo') : state.value?.kind === 'more' ? state.value.playlist.name : t('mobile.netease.edit'))
watch(state, async value => {
  if (!value) { if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true }); return }
  const playlist = 'playlist' in value ? value.playlist : undefined
  name.value = playlist?.name || ''; description.value = playlist?.description || ''; tags.value = playlist?.tags?.join('; ') || ''; privacy.value = playlist?.privacy === 10 ? 10 : 0
  if (typeof document === 'undefined') return
  if (!returnFocus || !document.contains(dialog.value || null)) returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick(); dialog.value?.querySelector<HTMLElement>('input,button')?.focus({ preventScroll: true })
}, { immediate: true })
function keyboard(event: KeyboardEvent) {
  if (!state.value) return
  if (event.key === 'Escape') { event.preventDefault(); store.closeOnlinePlaylistDialog(); return }
  if (event.key !== 'Tab') return
  const controls = Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled)') || [])
  if (!controls.length) return
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
async function save() { await store.saveOnlinePlaylist({ name: name.value, description: description.value, tags: tags.value.split(/[;；,，]/).map(value => value.trim()).filter(Boolean), privacy: Number(privacy.value) }) }
onMounted(() => document.addEventListener('keydown', keyboard))
onBeforeUnmount(() => document.removeEventListener('keydown', keyboard))
</script>

<template>
  <Teleport to="body"><Transition name="online-dialog">
    <div v-if="state && store.onlineAvailable" class="online-dialog-layer" @click.self="store.closeOnlinePlaylistDialog">
      <section ref="dialog" class="online-playlist-dialog" role="dialog" aria-modal="true" :aria-label="title">
        <header><h2>{{ title }}</h2><button class="icon-button" type="button" :aria-label="$t('mobile.common.close')" @click="store.closeOnlinePlaylistDialog"><OriginalIcon name="close" /></button></header>
        <div v-if="state.kind === 'more'" class="online-dialog-actions"><button class="secondary-button" type="button" :disabled="disabled" @click="store.openEditOnlinePlaylist(state.playlist)">{{ $t('mobile.netease.edit') }}</button><button v-if="state.playlist.specialType !== 5" class="text-button danger-text" type="button" :disabled="disabled" @click="store.deleteOnlinePlaylist(state.playlist)">{{ $t('mobile.netease.delete') }}</button></div>
        <div v-else-if="state.kind === 'add'" class="online-playlist-picker">
          <p class="setting-note">{{ state.track.name }}</p>
          <button v-for="playlist in store.ownedPlaylists" :key="playlist.id" class="online-playlist-choice" type="button" :disabled="disabled" @click="store.addTrackToOnlinePlaylist(playlist)"><OriginalIcon name="library" /><span><b>{{ playlist.name }}</b><small>{{ $t('mobile.common.tracks', { count: playlist.trackCount || 0 }) }}</small></span><OriginalIcon name="plus" /></button>
          <p v-if="!store.ownedPlaylists.length" class="setting-note">{{ $t('mobile.netease.emptyOwn') }}</p>
        </div>
        <form v-else @submit.prevent="save">
          <fieldset :disabled="disabled"><label><span>{{ $t('mobile.netease.name') }}</span><input v-model="name" type="text" required maxlength="40" autocomplete="off" /></label>
            <template v-if="state.kind === 'edit'"><label><span>{{ $t('mobile.netease.description') }}</span><textarea v-model="description" rows="4" maxlength="10000"></textarea></label><label><span>{{ $t('mobile.netease.tags') }}</span><input v-model="tags" type="text" maxlength="194" /><small>{{ $t('mobile.netease.tagsHint') }}</small></label></template>
            <label v-else><span>{{ $t('mobile.netease.visibility') }}</span><select v-model.number="privacy"><option :value="0">{{ $t('mobile.netease.public') }}</option><option :value="10">{{ $t('mobile.netease.private') }}</option></select></label>
          </fieldset>
          <footer><button class="text-button" type="button" @click="store.closeOnlinePlaylistDialog">{{ $t('mobile.management.cancel') }}</button><button class="primary-button" type="submit" :disabled="disabled || !name.trim()">{{ $t('mobile.netease.save') }}</button></footer>
        </form>
        <p v-if="store.onlineMutationBusy" class="setting-note" role="status">{{ $t('mobile.netease.busy') }}</p>
        <div v-if="store.onlineNeedsRefresh" class="online-sync-message"><p>{{ store.onlineOperationNotice || $t('mobile.netease.refreshRequired') }}</p><button class="secondary-button" type="button" :disabled="store.onlineMutationBusy" @click="store.refreshOnlineLibrary"><OriginalIcon name="refresh" />{{ $t('mobile.netease.refresh') }}</button></div>
      </section>
    </div>
  </Transition></Teleport>
</template>

<style scoped>
.online-dialog-layer { position:fixed; inset:0; z-index:450; display:grid; align-items:center; padding:16px; background:#0006; }
.online-playlist-dialog { width:min(100%,440px); max-height:calc(100dvh - 40px); overflow:auto; box-sizing:border-box; margin:auto; padding:16px; border:1px solid var(--line); border-radius:18px; background:var(--color-body-bg); color:var(--ink); box-shadow:0 14px 48px #0004; }
header { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:12px; } h2 { margin:0; font-size:17px; overflow-wrap:anywhere; }
fieldset { display:grid; gap:12px; border:0; padding:0; margin:0; min-width:0; } label { display:grid; gap:6px; font-size:12px; } label small { color:var(--muted); font-size:10px; }
input,textarea,select { box-sizing:border-box; min-width:0; width:100%; padding:9px 11px; color:var(--ink); background:var(--surface); border:1px solid var(--line); border-radius:10px; font:inherit; outline:none; transition:border-color .1s,background .1s; } textarea { resize:vertical; } input:focus,textarea:focus,select:focus { border-color:var(--accent); }
footer,.online-dialog-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:16px; }.online-dialog-actions { flex-direction:column; }
.online-playlist-picker { display:grid; gap:7px; }.online-playlist-choice { display:flex; align-items:center; gap:10px; width:100%; padding:11px; border:1px solid var(--line); border-radius:12px; background:var(--surface); color:var(--ink); text-align:left; }.online-playlist-choice span { flex:1; min-width:0; }.online-playlist-choice b,.online-playlist-choice small { display:block; overflow:hidden; text-overflow:ellipsis; }.online-playlist-choice small { margin-top:4px; color:var(--muted); font-size:10px; }
.online-sync-message { margin-top:12px; padding:10px; border-radius:12px; background:var(--surface); font-size:12px; line-height:1.6; }.online-sync-message button { display:flex; align-items:center; gap:6px; }
.online-dialog-enter-active,.online-dialog-leave-active { transition:opacity .1s ease; }.online-dialog-enter-active .online-playlist-dialog,.online-dialog-leave-active .online-playlist-dialog { transition:transform .1s ease; }.online-dialog-enter-from,.online-dialog-leave-to { opacity:0; }.online-dialog-enter-from .online-playlist-dialog,.online-dialog-leave-to .online-playlist-dialog { transform:translateY(8px) scale(.98); }
</style>
