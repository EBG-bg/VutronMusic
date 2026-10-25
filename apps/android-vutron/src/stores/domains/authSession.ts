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
}

/** Owns authentication and mode transitions; library/player state stays with its domain. */
export function createAuthSession(options: AuthSessionOptions) {
  const profile = ref<VutronProfile>()
  const localOnly = localOnlyMode
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

  // Native explicit login can finish after the user has returned to local mode.
  async function reconcileCancelledLogin() {
    if (localOnly.value) {
      try { await vutronPlugin.setLocalOnly({ enabled: true }) }
      catch (cause) { options.error.value = options.formatError(cause, '恢复本地模式失败') }
    }
  }

  async function restore() {
    const generation = ++loginGeneration
    try {
      const mode = await vutronPlugin.getLocalOnly()
      if (generation !== loginGeneration) return
      if (mode.enabled) setLocalOnlyPreference(true)
      if (!localOnly.value) {
        const session = await vutronPlugin.getSession()
        if (generation !== loginGeneration) return
        profile.value = session.loggedIn ? session.profile : undefined
      }
      const enabled = localOnly.value || !profile.value
      await vutronPlugin.setLocalOnly({ enabled })
      if (generation !== loginGeneration) return
      setLocalOnlyPreference(enabled)
      if (enabled) options.resetToLocalLibrary()
    } catch (cause) {
      if (generation !== loginGeneration) return
      setLocalOnlyPreference(true)
      options.resetToLocalLibrary()
      options.error.value = options.formatError(cause, '无法连接 Android 原生适配器')
      try { await vutronPlugin.setLocalOnly({ enabled: true }) }
      catch { /* Keep the original bridge/session failure visible. */ }
    }
    if (generation !== loginGeneration) return
    await options.restoreLocalState()
    if (generation === loginGeneration && onlineAvailable.value) await options.loadOnlineState()
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
    try {
      await vutronPlugin.setLocalOnly({ enabled: true })
      if (generation !== loginGeneration) return
      setLocalOnlyPreference(true)
      loginRequested.value = false
      options.loading.value = false
      await clearQrSession()
      if (generation !== loginGeneration) return
      options.error.value = ''
      options.resetToLocalLibrary()
      await options.restoreLocalState()
      options.startPlaybackPolling()
    } catch (cause) {
      if (generation === loginGeneration) options.error.value = options.formatError(cause, '无法进入仅本地模式')
    } finally { localModeLoading.value = false }
  }

  function requestLogin() {
    loginRequested.value = true
    options.error.value = ''
  }

  async function completeLogin(account: VutronProfile, generation: number) {
    if (generation !== loginGeneration) { await reconcileCancelledLogin(); return }
    await vutronPlugin.setLocalOnly({ enabled: false })
    if (generation !== loginGeneration) { await reconcileCancelledLogin(); return }
    setLocalOnlyPreference(false)
    loginRequested.value = false
    profile.value = account
    options.resetToOnlineHome()
    qrLoginKey.value = ''
    qrLoginImage.value = ''
    qrLoginStatus.value = '登录成功，正在载入音乐库…'
    await Promise.all([options.restoreLocalState(), options.loadOnlineState()])
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
    await enterLocalOnly()
  }

  return {
    profile, localOnly, loginRequested, localModeLoading, loggedIn, hasAccess, loginVisible, onlineAvailable,
    qrLoginKey, qrLoginImage, qrLoginStatus, qrLoginLoading,
    restore, enterLocalOnly, requestLogin, login, createQrLogin, checkQrLogin, cancelQrLogin, logout
  }
}
