import { computed, reactive, ref } from 'vue'
import { App } from '@capacitor/app'
import { Capacitor, type PluginListenerHandle } from '@capacitor/core'
import { buildInfo } from './buildInfo'
import { localOnlyMode } from './localOnlyMode'
import { vutronPlugin, type VutronAppUpdateState } from './vutronPlugin'
import { isNewerAppVersion } from './appUpdatePresentation'

export const appUpdatesSupported = Capacitor.isNativePlatform()
export const appUpdateState = reactive<VutronAppUpdateState>({
  currentVersion: buildInfo.version, comparisonVersion: buildInfo.comparisonVersion, currentVersionCode: 0, autoCheck: false, status: 'idle',
  releaseUrl: 'https://github.com/EBG-bg/VutronMusic/releases', notes: '', progress: 0,
  downloadedBytes: 0, totalBytes: 0, error: '', checkedAt: 0, canInstall: false
})
export const appUpdateBusy = computed(() => appUpdateState.status === 'checking' || appUpdateState.status === 'downloading')
export const appUpdateAvailable = computed(() => isNewerAppVersion(appUpdateState.latestVersion, appUpdateState.comparisonVersion || appUpdateState.currentVersion))
export const appUpdateInstallAllowed = computed(() => buildInfo.edition === 'mobile' && appUpdateState.installAllowed !== false)
export const appUpdateAutomaticPaused = computed(() => localOnlyMode.value || appUpdateState.autoCheckPaused === true)
export const appUpdateSaving = ref(false)
const automaticNotice = ref(false)
const dismissedVersion = ref('')
export const showAppUpdateNotice = computed(() => !appUpdateAutomaticPaused.value && automaticNotice.value && appUpdateAvailable.value
  && dismissedVersion.value !== appUpdateState.latestVersion)
let initialization: Promise<void> | undefined
let stateListener: PluginListenerHandle | undefined
let resumeListener: PluginListenerHandle | undefined
let generation = 0

function applyState(state: VutronAppUpdateState) { Object.assign(appUpdateState, state) }
function showError(cause: unknown) {
  appUpdateState.status = 'error'
  appUpdateState.error = cause instanceof Error ? cause.message : '无法完成更新操作，请重试'
}

export async function checkAppUpdates(manual = true) {
  if (!appUpdatesSupported || appUpdateBusy.value) return
  if (!manual && (!appUpdateState.autoCheck || appUpdateAutomaticPaused.value)) return
  if (manual) automaticNotice.value = false
  const token = generation
  try {
    const state = await vutronPlugin.checkAppUpdate({ manual })
    if (token !== generation) return
    applyState(state)
    if (!manual && appUpdateState.autoCheck && appUpdateAvailable.value) automaticNotice.value = true
  } catch (cause) { showError(cause) }
}

export async function setAutomaticAppUpdates(enabled: boolean) {
  if (!appUpdatesSupported || appUpdateSaving.value) return
  appUpdateSaving.value = true
  try {
    applyState(await vutronPlugin.setAutoCheckUpdates({ enabled }))
    if (!enabled) automaticNotice.value = false
    else await checkAppUpdates(false)
  } catch (cause) { showError(cause) }
  finally { appUpdateSaving.value = false }
}

export async function updateApp() {
  if (!appUpdatesSupported || !appUpdateInstallAllowed.value || appUpdateBusy.value || !appUpdateAvailable.value) return
  try {
    if (appUpdateState.status !== 'ready' && appUpdateState.status !== 'permissionRequired') {
      applyState(await vutronPlugin.downloadAppUpdate())
    }
    if (appUpdateState.status === 'ready' || appUpdateState.status === 'permissionRequired') {
      applyState(await vutronPlugin.installAppUpdate())
    }
  } catch (cause) { showError(cause) }
}

export function dismissAppUpdateNotice() { dismissedVersion.value = appUpdateState.latestVersion ?? '' }

export function initializeAppUpdates(): Promise<void> {
  if (!appUpdatesSupported) return Promise.resolve()
  if (initialization) return initialization
  const token = ++generation
  initialization = (async () => {
    try {
      const listener = await vutronPlugin.addListener('appUpdateChanged', state => { if (token === generation) applyState(state) })
      if (token !== generation) { await listener.remove(); return }
      stateListener = listener
      const resumed = await App.addListener('appStateChange', async event => {
        if (!event.isActive || token !== generation) return
        try {
          const state = await vutronPlugin.getAppUpdateState()
          if (token !== generation) return
          applyState(state)
          if (appUpdateState.status !== 'ready' && appUpdateState.status !== 'permissionRequired') await checkAppUpdates(false)
        } catch (cause) { showError(cause) }
      })
      if (token !== generation) { await resumed.remove(); return }
      resumeListener = resumed
      const state = await vutronPlugin.getAppUpdateState()
      if (token !== generation) return
      applyState(state)
      if (token === generation) await checkAppUpdates(false)
    } catch (cause) { if (token === generation) showError(cause) }
  })()
  return initialization
}

export function disposeAppUpdates() {
  ++generation
  void stateListener?.remove()
  void resumeListener?.remove()
  stateListener = undefined
  resumeListener = undefined
  initialization = undefined
}
