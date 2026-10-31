import { computed, ref, type Ref } from 'vue'
import { localOnlyMode, setLocalOnlyPreference } from '../../platform/localOnlyMode'
import { vutronPlugin, type VutronProfile } from '../../platform/vutronPlugin'

interface AuthSessionOptions {
  loading: Ref<boolean>
  error: Ref<string>
  formatError(cause: unknown, fallback: string): string
  restoreLocalState(): Promise<void>
  loadOnlineState(): Promise<void>
  resetToLocalLibrary(): void
  resetToOnlineHome(): void
  beforeLogout(): Promise<void>
  clearOnlineState(): void
  startPlaybackPolling(): void
  onAutomaticOffline?(): void
}

/** Owns authentication and mode transitions; library/player state stays with its domain. */
export function createAuthSession(options: AuthSessionOptions) {
  const profile = ref<VutronProfile>()
  const localOnly = localOnlyMode
  const manualLocalOnly = ref(localOnly.value)
  const networkConnected = ref(true)
  const loginRequested = ref(false)
  const localModeLoading = ref(false)
  const loggedIn = computed(() => Boolean(profile.value))
  const hasAccess = computed(() => loggedIn.value || localOnly.value)
  const loginVisible = computed(() => loginRequested.value || !hasAccess.value)
  const onlineAvailable = computed(() => loggedIn.value && !localOnly.value)
  const qrLoginKey = ref('')
  const qrLoginImage = ref('')
  const qrLoginStatus = ref('')
  const qrLoginLoading = ref(false)
  let loginGeneration = 0
  let networkRequest = 0, restored = false
  let networkListener: { remove(): Promise<void> } | undefined
  let monitoring = false
  let monitorGeneration = 0
  function applyMode(persist = false) {
    setLocalOnlyPreference(manualLocalOnly.value || !networkConnected.value || !profile.value, persist, manualLocalOnly.value)
  }
  function connected(value?: boolean) { return value ?? (typeof navigator === 'undefined' || navigator.onLine !== false) }
  async function initializeNetworkMonitoring() {
    if (monitoring) return
    monitoring = true
    const token = ++monitorGeneration
    if (typeof window !== 'undefined' && window.addEventListener) {
      window.addEventListener('online', onNetworkEvent); window.addEventListener('offline', onNetworkEvent)
    }
    if (vutronPlugin.addListener) {
      try { const listener = await vutronPlugin.addListener('musicNetworkChanged', onNetworkEvent); if (monitoring && token === monitorGeneration) networkListener = listener; else await listener.remove() }
      catch { /* Visibility and browser connectivity events can still refresh the native snapshot. */ }
    }
  }
  function onNetworkEvent() { if (restored) return refreshConnectivity() }
  function disposeNetworkMonitoring() {
    monitoring = false; restored = false; networkRequest++; monitorGeneration++
    if (typeof window !== 'undefined' && window.removeEventListener) {
      window.removeEventListener('online', onNetworkEvent); window.removeEventListener('offline', onNetworkEvent)
    }
    void networkListener?.remove(); networkListener = undefined
  }
  async function refreshConnectivity() {
    const request = ++networkRequest, generation = loginGeneration
    try {
      const mode = await vutronPlugin.getLocalOnly()
      if (request !== networkRequest || generation !== loginGeneration) return
      networkConnected.value = connected(mode.connected)
      if (!manualLocalOnly.value && networkConnected.value && !profile.value) {
        const session = await vutronPlugin.getSession()
        if (request !== networkRequest || generation !== loginGeneration) return
        if (session.loggedIn && session.profile) profile.value = session.profile
      }
      const wasOffline = localOnly.value
      applyMode()
      if (restored && wasOffline !== localOnly.value) {
        if (localOnly.value) (options.onAutomaticOffline || options.resetToLocalLibrary)()
        else await options.loadOnlineState()
      }
    } catch (cause) {
      if (request === networkRequest && generation === loginGeneration) options.error.value = options.formatError(cause, '读取网络状态失败')
    }
  }

  // Native explicit login can finish after the user has returned to local mode.
  async function reconcileCancelledLogin() {
    if (localOnly.value) {
      try { await vutronPlugin.setLocalOnly({ enabled: true, manual: manualLocalOnly.value }) }
      catch (cause) { options.error.value = options.formatError(cause, '恢复本地模式失败') }
    }
  }

  async function restore() {
    const generation = ++loginGeneration
    restored = false; networkRequest++
    await initializeNetworkMonitoring()
    try {
      const mode = await vutronPlugin.getLocalOnly()
      if (generation !== loginGeneration) return
      manualLocalOnly.value = mode.manual ?? (mode.enabled || manualLocalOnly.value)
      networkConnected.value = connected(mode.connected)
      const session = await vutronPlugin.getSession()
      if (generation !== loginGeneration) return
      if (session.loggedIn && session.profile) profile.value = session.profile
      else if (!session.restorePending) profile.value = undefined
      applyMode(true)
      await vutronPlugin.setLocalOnly({ enabled: localOnly.value, manual: manualLocalOnly.value })
      if (generation !== loginGeneration) return
      if (localOnly.value) options.resetToLocalLibrary()
    } catch (cause) {
      if (generation !== loginGeneration) return
      setLocalOnlyPreference(true, false)
      options.resetToLocalLibrary()
      options.error.value = options.formatError(cause, '无法连接 Android 原生适配器')
      try { await vutronPlugin.setLocalOnly({ enabled: true, manual: manualLocalOnly.value }) }
      catch { /* Keep the original bridge/session failure visible. */ }
    }
    if (generation !== loginGeneration) return
    await options.restoreLocalState()
    if (generation === loginGeneration) {
      restored = true
      if (onlineAvailable.value) await options.loadOnlineState()
    }
  }

  async function clearQrSession() {
    const key = qrLoginKey.value
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    qrLoginLoading.value = false
    if (key) {
      try { await vutronPlugin.cancelQrLogin?.({ key }) }
      catch { /* Session can expire independently. */ }
    }
  }

  async function cancelQrLogin() {
    ++loginGeneration
    await clearQrSession()
  }

  async function enterLocalOnly() {
    if (localModeLoading.value) return
    localModeLoading.value = true
    const generation = ++loginGeneration
    networkRequest++
    try {
      await vutronPlugin.setLocalOnly({ enabled: true, manual: true })
      if (generation !== loginGeneration) return
      manualLocalOnly.value = true
      setLocalOnlyPreference(true)
      loginRequested.value = false
      options.loading.value = false
      await clearQrSession()
      if (generation !== loginGeneration) return
      options.error.value = ''
      const reset = options.onAutomaticOffline || options.resetToLocalLibrary; reset()
      await options.restoreLocalState()
      options.startPlaybackPolling()
    } catch (cause) {
      if (generation === loginGeneration) options.error.value = options.formatError(cause, '无法进入仅本地模式')
    } finally { localModeLoading.value = false }
  }

  async function useAutomaticMode() {
    if (localModeLoading.value) return
    localModeLoading.value = true
    const generation = ++loginGeneration
    networkRequest++
    try {
      await vutronPlugin.setLocalOnly({ enabled: !networkConnected.value || !profile.value, manual: false })
      if (generation !== loginGeneration) return
      manualLocalOnly.value = false
      setLocalOnlyPreference(localOnly.value, true, false)
      await refreshConnectivity()
    } catch (cause) { if (generation === loginGeneration) options.error.value = options.formatError(cause, '恢复自动联网失败') }
    finally { localModeLoading.value = false }
  }

  function requestLogin() {
    loginRequested.value = true
    options.error.value = ''
  }

  async function completeLogin(account: VutronProfile, generation: number) {
    if (generation !== loginGeneration) { await reconcileCancelledLogin(); return }
    const mode = await vutronPlugin.setLocalOnly({ enabled: !networkConnected.value, manual: false })
    if (generation !== loginGeneration) { await reconcileCancelledLogin(); return }
    networkConnected.value = connected(mode.connected)
    networkRequest++; manualLocalOnly.value = false
    loginRequested.value = false
    profile.value = account
    applyMode(true)
    if (localOnly.value) options.resetToLocalLibrary()
    else options.resetToOnlineHome()
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    qrLoginStatus.value = '登录成功，正在载入音乐库…'
    restored = true
    await Promise.all([options.restoreLocalState(), ...(onlineAvailable.value ? [options.loadOnlineState()] : [])])
    if (generation === loginGeneration) options.startPlaybackPolling()
  }

  async function login(cookie: string) {
    if (!cookie.trim()) { options.error.value = '请输入网易云 Cookie'; return }
    options.loading.value = true
    options.error.value = ''
    const generation = ++loginGeneration
    try {
      const result = await vutronPlugin.loginWithCookie({ cookie: cookie.trim() })
      await completeLogin(result.profile, generation)
    } catch (cause) {
      if (generation === loginGeneration) options.error.value = options.formatError(cause, '网易云登录失败')
    } finally { if (generation === loginGeneration) options.loading.value = false }
  }

  async function createQrLogin() {
    if (!vutronPlugin.createQrLogin) { qrLoginStatus.value = '当前版本没有二维码登录支持'; return }
    qrLoginLoading.value = true
    const generation = ++loginGeneration
    qrLoginStatus.value = '正在获取二维码…'
    const previousKey = qrLoginKey.value
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    try {
      if (previousKey) {
        try { await vutronPlugin.cancelQrLogin?.({ key: previousKey }) }
        catch { /* Old QR session may already be expired. */ }
      }
      if (generation !== loginGeneration) return
      const result = await vutronPlugin.createQrLogin()
      if (generation !== loginGeneration) {
        await vutronPlugin.cancelQrLogin?.({ key: result.key })
        return
      }
      qrLoginKey.value = result.key
      qrLoginImage.value = result.imageData
      qrLoginStatus.value = '请使用网易云音乐扫一扫并确认登录'
    } catch (cause) {
      if (generation === loginGeneration) qrLoginStatus.value = options.formatError(cause, '二维码获取失败，请检查网络后重试')
    } finally { if (generation === loginGeneration) qrLoginLoading.value = false }
  }

  async function checkQrLogin() {
    if (!qrLoginKey.value || !vutronPlugin.checkQrLogin || qrLoginLoading.value) return
    qrLoginLoading.value = true
    const key = qrLoginKey.value
    const generation = loginGeneration
    try {
      const result = await vutronPlugin.checkQrLogin({ key })
      if (generation !== loginGeneration || key !== qrLoginKey.value) {
        if (result.loggedIn) await reconcileCancelledLogin()
        return
      }
      if (result.loggedIn && result.profile) await completeLogin(result.profile, generation)
      else if (result.code === 800) {
        qrLoginKey.value = ''
        qrLoginImage.value = ''
        qrLoginStatus.value = '二维码已过期，请刷新二维码'
      } else if (result.code === 802) qrLoginStatus.value = '已扫描，请在网易云音乐中确认登录'
      else if (result.code === 801) qrLoginStatus.value = '等待扫码…'
      else qrLoginStatus.value = result.message || '正在检查登录状态…'
    } catch (cause) {
      if (generation === loginGeneration) qrLoginStatus.value = options.formatError(cause, '网络暂时不可用') + '，将继续检查'
    } finally { if (generation === loginGeneration) qrLoginLoading.value = false }
  }

  async function logout() {
    ++loginGeneration
    await options.beforeLogout()
    await clearQrSession()
    try { await vutronPlugin.logout() }
    catch (cause) { options.error.value = options.formatError(cause, '退出登录失败'); return }
    profile.value = undefined
    options.clearOnlineState()
    options.resetToLocalLibrary()
    await enterLocalOnly()
  }

  return {
    profile, localOnly, manualLocalOnly, networkConnected, loginRequested, localModeLoading, loggedIn, hasAccess, loginVisible, onlineAvailable,
    qrLoginKey, qrLoginImage, qrLoginStatus, qrLoginLoading,
    restore, enterLocalOnly, useAutomaticMode, refreshConnectivity, disposeNetworkMonitoring, requestLogin, login, createQrLogin, checkQrLogin, cancelQrLogin, logout
  }
}
