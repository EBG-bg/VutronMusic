import { computed, ref, shallowRef, watch, type Ref } from 'vue'
import { askAction } from '../../platform/actionDialog'
import { translate as t } from '../../platform/i18n'
import { vutronPlugin, type VutronNeteaseMutation, type VutronOnlineLibrary, type VutronPlaylist, type VutronProfile, type VutronTrack } from '../../platform/vutronPlugin'

export type OnlinePlaylistDialog = { kind: 'create' } | { kind: 'more' | 'edit'; playlist: VutronPlaylist } | { kind: 'add'; track: VutronTrack }
interface Options {
  profile: Ref<VutronProfile | undefined>
  onlineAvailable: Ref<boolean>
  playlists: Ref<VutronPlaylist[]>
  tracks: Ref<VutronTrack[]>
  selectedPlaylist: Ref<VutronPlaylist | undefined>
  error: Ref<string>
  invalidateReads(): void
  applySnapshot(snapshot: VutronOnlineLibrary): void
  notice(message: string): void
  formatError(cause: unknown, fallback: string): string
}
export function onlineTrackId(track: VutronTrack): number {
  if (track.missingLocal) return 0
  const id = track.neteaseId || (!track.localUri && !track.localPath || track.cached ? track.id : 0)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

/** Account relations and writes are separate from local playback and its cache. */
export function createNeteaseLibrary(options: Options) {
  const likedTrackIds = ref<number[]>([]), likedLoaded = ref(false), likesLoading = ref(false)
  const onlineMutationBusy = ref(false), onlineNeedsRefresh = ref(false), onlineOperationNotice = ref('')
  const onlinePlaylistDialog = shallowRef<OnlinePlaylistDialog>()
  const liked = computed(() => new Set(likedTrackIds.value))
  const ownedPlaylists = computed(() => options.playlists.value.filter(canManageOnlinePlaylist))
  let generation = 0, likeRequest = 0, lastLikedAt = 0
  let likedTask: Promise<boolean> | undefined
  let pendingCommitted: VutronOnlineLibrary | undefined
  let confirmationPending = false
  const sameAccount = (token: number, uid: number) => generation === token && options.onlineAvailable.value && options.profile.value?.userId === uid
  watch(() => [options.profile.value?.userId || 0, options.onlineAvailable.value], () => {
    generation++; likeRequest++; lastLikedAt = 0; likedTask = undefined
    likedTrackIds.value = []; likedLoaded.value = false; likesLoading.value = false
    onlineNeedsRefresh.value = false; pendingCommitted = undefined; onlinePlaylistDialog.value = undefined; onlineOperationNotice.value = ''
  }, { flush: 'sync' })

  function isOwnedOnlinePlaylist(playlist: VutronPlaylist | undefined): boolean {
    return Boolean(options.onlineAvailable.value && options.profile.value?.userId && playlist?.creatorUserId === options.profile.value.userId)
  }
  function canManageOnlinePlaylist(playlist: VutronPlaylist | undefined): boolean {
    return isOwnedOnlinePlaylist(playlist) && playlist?.specialType !== 5
  }
  function isTrackLiked(track: VutronTrack) { return likedLoaded.value && liked.value.has(onlineTrackId(track)) }
  function removableOnlinePlaylist(track: VutronTrack) {
    const playlist = options.selectedPlaylist.value
    return canManageOnlinePlaylist(playlist) && onlineTrackId(track) > 0 && track.playlistVolumeKey === 'netease:' + playlist!.id
      && options.tracks.value.some(item => onlineTrackId(item) === onlineTrackId(track)) ? playlist : undefined
  }
  function onlineFailure(cause: unknown) {
    const message = options.formatError(cause, t('mobile.netease.failed'))
    onlineOperationNotice.value = message; options.error.value = message
  }
  async function loadLikedTracks(force = false): Promise<boolean> {
    if (!options.onlineAvailable.value || !vutronPlugin.getLikedTrackIds || onlineMutationBusy.value) return false
    if (!force && likedLoaded.value && Date.now() - lastLikedAt < 60000) return true
    if (likedTask) return likedTask
    const token = generation, uid = options.profile.value!.userId, request = ++likeRequest
    likesLoading.value = true
    const task = (async () => {
      try {
        const result = await vutronPlugin.getLikedTrackIds!()
        if (!sameAccount(token, uid) || request !== likeRequest || result.userId !== uid) return false
        likedTrackIds.value = result.likedIds.filter(id => Number.isSafeInteger(id) && id > 0)
        likedLoaded.value = true; lastLikedAt = Date.now(); return true
      } catch (cause) { if (sameAccount(token, uid) && request === likeRequest) onlineFailure(cause); return false }
      finally { if (request === likeRequest) likesLoading.value = false }
    })()
    likedTask = task
    try { return await task } finally { if (likedTask === task) likedTask = undefined }
  }
  function apply(result: VutronOnlineLibrary) {
    if (!Array.isArray(result.playlists) || !Array.isArray(result.likedIds)) throw new Error(t('mobile.netease.incomplete'))
    options.applySnapshot({ ...result, deletedPlaylistId: result.deletedPlaylistId || pendingCommitted?.deletedPlaylistId })
    likedTrackIds.value = result.likedIds.filter(id => Number.isSafeInteger(id) && id > 0)
    likedLoaded.value = true; lastLikedAt = Date.now()
  }
  async function refreshOnlineLibrary(): Promise<boolean> {
    if (!options.onlineAvailable.value || !vutronPlugin.getOnlineLibrary || onlineMutationBusy.value) return false
    const token = generation, uid = options.profile.value!.userId
    const recovering = onlineNeedsRefresh.value
    onlineMutationBusy.value = true; likeRequest++; likesLoading.value = false; options.invalidateReads()
    try {
      let playlistId = options.selectedPlaylist.value?.id
      if (playlistId === pendingCommitted?.deletedPlaylistId) playlistId = undefined
      const uncertain = onlineNeedsRefresh.value && !pendingCommitted
      let result = await vutronPlugin.getOnlineLibrary({ playlistId: uncertain ? undefined : playlistId })
      if (!sameAccount(token, uid) || result.userId !== uid) return false
      const selected = options.selectedPlaylist.value
      if (uncertain && selected && isOwnedOnlinePlaylist(selected)) {
        if (result.playlists?.some(item => item.id === selected.id)) result = await vutronPlugin.getOnlineLibrary({ playlistId: selected.id })
        else result = { ...result, deletedPlaylistId: selected.id }
        if (!sameAccount(token, uid) || result.userId !== uid) return false
      }
      apply(result)
      if (pendingCommitted || recovering) onlinePlaylistDialog.value = undefined
      pendingCommitted = undefined; onlineNeedsRefresh.value = false; onlineOperationNotice.value = ''; options.error.value = ''
      return true
    } catch (cause) { if (sameAccount(token, uid)) onlineFailure(cause); return false }
    finally { onlineMutationBusy.value = false }
  }
  async function mutate(request: VutronNeteaseMutation): Promise<boolean> {
    if (!options.onlineAvailable.value) { onlineFailure(new Error(t('mobile.netease.onlineRequired'))); return false }
    if (!vutronPlugin.manageNeteaseLibrary) { onlineFailure(new Error(t('mobile.netease.unavailable'))); return false }
    if (onlineMutationBusy.value) return false
    if (onlineNeedsRefresh.value) { onlineFailure(new Error(t('mobile.netease.refreshRequired'))); return false }
    const token = generation, uid = options.profile.value!.userId, dialog = onlinePlaylistDialog.value
    onlineMutationBusy.value = true; likeRequest++; likesLoading.value = false; options.invalidateReads(); options.error.value = ''
    try {
      const result = await vutronPlugin.manageNeteaseLibrary({ ...request, refreshPlaylistId: options.selectedPlaylist.value?.id })
      if (!sameAccount(token, uid) || result.userId !== uid) return false
      if (result.refreshFailed) {
        pendingCommitted = result; onlineNeedsRefresh.value = true
        onlineFailure(new Error(result.message || t('mobile.netease.refreshRequired'))); return false
      }
      apply(result); pendingCommitted = undefined; onlineNeedsRefresh.value = false; onlineOperationNotice.value = ''
      if (onlinePlaylistDialog.value === dialog) onlinePlaylistDialog.value = undefined
      options.notice(t('mobile.netease.completed')); return true
    } catch (cause) {
      if (sameAccount(token, uid)) { onlineNeedsRefresh.value = true; onlineFailure(cause) }
      return false
    } finally { onlineMutationBusy.value = false }
  }
  async function toggleTrackLike(track: VutronTrack) {
    const id = onlineTrackId(track)
    if (!id || !options.onlineAvailable.value || onlineMutationBusy.value) return false
    if (!likedLoaded.value && !await loadLikedTracks(true)) return false
    return mutate({ action: 'like', trackId: id, liked: !liked.value.has(id) })
  }
  function openCreateOnlinePlaylist() { if (options.onlineAvailable.value && !onlineMutationBusy.value) onlinePlaylistDialog.value = { kind: 'create' } }
  function openOnlinePlaylistMore(playlist: VutronPlaylist) { if (canManageOnlinePlaylist(playlist) && !onlineMutationBusy.value) onlinePlaylistDialog.value = { kind: 'more', playlist: { ...playlist, tags: playlist.tags?.slice() } } }
  function openEditOnlinePlaylist(playlist: VutronPlaylist) { if (canManageOnlinePlaylist(playlist) && !onlineMutationBusy.value) onlinePlaylistDialog.value = { kind: 'edit', playlist: { ...playlist, tags: playlist.tags?.slice() } } }
  function openAddToOnlinePlaylist(track: VutronTrack) {
    if (options.onlineAvailable.value && onlineTrackId(track) && !onlineMutationBusy.value) {
      onlinePlaylistDialog.value = { kind: 'add', track: { ...track } }
      if (!options.playlists.value.length && !onlineNeedsRefresh.value) void refreshOnlineLibrary()
    }
  }
  async function saveOnlinePlaylist(form: { name: string; description: string; tags: string[]; privacy: number }) {
    const dialog = onlinePlaylistDialog.value
    if (dialog?.kind === 'create') return mutate({ action: 'create', name: form.name, privacy: form.privacy })
    if (dialog?.kind === 'edit' && canManageOnlinePlaylist(dialog.playlist)) return mutate({ action: 'edit', playlistId: dialog.playlist.id, name: form.name, description: form.description, tags: form.tags })
    return false
  }
  async function addTrackToOnlinePlaylist(playlist: VutronPlaylist) {
    const dialog = onlinePlaylistDialog.value
    if (dialog?.kind !== 'add' || !canManageOnlinePlaylist(playlist)) return false
    return mutate({ action: 'add', playlistId: playlist.id, trackId: onlineTrackId(dialog.track) })
  }
  async function deleteOnlinePlaylist(playlist: VutronPlaylist) {
    if (!canManageOnlinePlaylist(playlist) || confirmationPending || onlineMutationBusy.value) return false
    const token = generation, uid = options.profile.value!.userId
    confirmationPending = true
    try {
      const choice = await askAction({ title: t('mobile.netease.delete'), message: t('mobile.netease.deleteHint', { name: playlist.name }), confirmLabel: t('mobile.netease.delete'), cancelLabel: t('mobile.management.cancel') })
      if (choice !== 'confirm' || !sameAccount(token, uid) || !canManageOnlinePlaylist(playlist)) return false
      return await mutate({ action: 'delete', playlistId: playlist.id })
    } finally { confirmationPending = false }
  }
  async function removeTrackFromOnlinePlaylist(track: VutronTrack) {
    const playlist = removableOnlinePlaylist(track)
    if (!playlist || confirmationPending || onlineMutationBusy.value) return false
    const token = generation, uid = options.profile.value!.userId
    confirmationPending = true
    try {
      const choice = await askAction({ title: t('mobile.netease.remove'), message: t('mobile.netease.removeHint', { name: playlist.name }), confirmLabel: t('mobile.netease.remove'), cancelLabel: t('mobile.management.cancel') })
      if (choice !== 'confirm' || !sameAccount(token, uid) || removableOnlinePlaylist(track)?.id !== playlist.id) return false
      return await mutate({ action: 'remove', playlistId: playlist.id, trackId: onlineTrackId(track) })
    } finally { confirmationPending = false }
  }
  return { likedTrackIds, likedLoaded, likesLoading, onlineMutationBusy, onlineNeedsRefresh, onlineOperationNotice, onlinePlaylistDialog, ownedPlaylists,
    canManageOnlinePlaylist, isTrackLiked, removableOnlinePlaylist, loadLikedTracks, refreshOnlineLibrary, toggleTrackLike,
    openCreateOnlinePlaylist, openOnlinePlaylistMore, openEditOnlinePlaylist, openAddToOnlinePlaylist, saveOnlinePlaylist, addTrackToOnlinePlaylist,
    deleteOnlinePlaylist, removeTrackFromOnlinePlaylist, closeOnlinePlaylistDialog: () => { onlinePlaylistDialog.value = undefined } }
}
