import { ref, type Ref } from 'vue'
import { askAction } from '../../platform/actionDialog'
import { translate as t } from '../../platform/i18n'
import { vutronPlugin, type VutronLocalCollection, type VutronLocalLibrary, type VutronLocalTrack } from '../../platform/vutronPlugin'

interface LocalLibraryOptions {
  error: Ref<string>
  onlineAvailable: Ref<boolean>
  notice?(message: string): void
  formatError(cause: unknown, fallback: string): string
  openCollection(collection: VutronLocalCollection): Promise<void>
}

/** Owns the local index, scanning and import. Navigation remains in the shell. */
export function createLocalLibrary(options: LocalLibraryOptions) {
  const localLibrary = ref<VutronLocalLibrary>({ folders: [], playlists: [] })
  const libraryLoading = ref(false)
  const localScanLoading = ref(false)
  const localTracks = ref<VutronLocalTrack[]>([])
  const folderName = ref('未选择文件夹')
  let localScanTask: Promise<void> | undefined
  let localIndexTask: Promise<void> | undefined
  let localIndexRevision = 0

  async function pickMusicFolder() {
    if (!vutronPlugin.pickMusicFolder) { options.error.value = '当前 APK 尚未接入文件夹选择器'; return }
    try { const result = await vutronPlugin.pickMusicFolder(); if (result.name) { folderName.value = result.name; await loadLocalMusic(true) } }
    catch (cause) { options.error.value = options.formatError(cause, '文件夹授权失败') }
  }
  async function loadLocalMusic(forceScan = false, allowAutomaticScan = true): Promise<void> {
    if (forceScan) {
      if (localScanTask) return localScanTask
      if (!vutronPlugin.scanLocalMusic) return
      const revision = ++localIndexRevision
      localScanLoading.value = true
      localScanTask = (async () => {
        try {
          const result = await vutronPlugin.scanLocalMusic!()
          if (result.cancelled) return
          if (revision === localIndexRevision) localTracks.value = result.tracks ?? []
          await loadLocalLibrary()
        } catch (cause) { options.error.value = options.formatError(cause, '本地音乐扫描失败') }
        finally { localScanLoading.value = false; localScanTask = undefined }
      })()
      return localScanTask
    }
    if (localIndexTask) return localIndexTask
    const revision = localIndexRevision
    localIndexTask = (async () => {
      try {
        const index = await vutronPlugin.getLocalMusicIndex!()
        if (revision === localIndexRevision && !localScanLoading.value) localTracks.value = index.tracks ?? []
        await loadLocalLibrary()
        if (allowAutomaticScan && !localScanLoading.value && localLibrary.value.folders.length
          && (!index.indexed || Date.now() - index.updatedAt > 24 * 60 * 60 * 1000)) void loadLocalMusic(true)
      } catch (cause) { options.error.value = options.formatError(cause, '本地音乐索引读取失败') }
      finally { localIndexTask = undefined }
    })()
    return localIndexTask
  }
  async function loadLocalLibrary() {
    if (!vutronPlugin.getLocalLibrary) return
    try {
      localLibrary.value = await vutronPlugin.getLocalLibrary()
      folderName.value = localLibrary.value.folders.map(item => item.name).join('、') || '未选择文件夹'
    } catch (cause) { options.error.value = options.formatError(cause, '本地音乐资料读取失败') }
  }
  async function importLocalPlaylist() {
    if (!vutronPlugin.importLocalPlaylist || libraryLoading.value) return
    libraryLoading.value = true
    let pending = ''
    try {
      let result = await vutronPlugin.importLocalPlaylist()
      while (result.needsMode && result.importToken) {
        pending = result.importToken
        const choice = await askAction({ title: t('mobile.imports.chooseTitle'), message: t('mobile.imports.chooseHint'),
          confirmLabel: t('mobile.imports.localOnly'), secondaryLabel: t('mobile.imports.enrich'),
          secondaryDisabled: !options.onlineAvailable.value || result.canMatchOnline === false, cancelLabel: t('mobile.management.cancel') })
        if (choice === 'cancel') return
        if (!vutronPlugin.completeLocalPlaylistImport) throw new Error(t('mobile.imports.unavailable'))
        result = await vutronPlugin.completeLocalPlaylistImport({ importToken: pending, matchOnline: choice === 'secondary' })
      }
      if (result.cancelled) return
      await loadLocalLibrary()
      if (result.playlist) await options.openCollection(result.playlist)
      if (result.matchingCancelled || result.matchingPaused || result.matchingFailed) options.notice?.(t('mobile.imports.matchingStopped'))
      else if (result.matched !== undefined) options.notice?.(t('mobile.imports.matchSummary', { matched: result.matched, total: result.total ?? result.playlist?.trackCount ?? 0 }))
      else if (result.playlist) options.notice?.(t('mobile.imports.imported', { count: result.playlist.trackCount }))
    } catch (cause) { options.error.value = options.formatError(cause, '电脑本地歌单导入失败') }
    finally {
      try { if (pending) await vutronPlugin.discardLocalPlaylistImport?.({ importToken: pending }) }
      catch (cause) { if (!options.error.value) options.error.value = options.formatError(cause, '临时导入文件清理失败') }
      finally { libraryLoading.value = false }
    }
  }
  return { localLibrary, libraryLoading, localScanLoading, localTracks, folderName, pickMusicFolder, loadLocalMusic, loadLocalLibrary, importLocalPlaylist }
}
