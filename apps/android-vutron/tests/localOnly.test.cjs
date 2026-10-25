const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { test } = require('node:test')
const ts = require('typescript')
const vue = require('vue')
const pinia = require('pinia')
const mobileI18n = require('./helpers/i18n.cjs').loadMobileI18n()

const appRoot = path.resolve(__dirname, '..')
const account = { userId: 123, nickname: '测试账号', avatarUrl: '' }
const localTrack = { id: 51, name: '本地歌曲', localUri: 'content://music/51', source: 'local' }
const cachedTrack = { id: 61, name: '离线歌曲', localUri: 'file:///cache/61.audio', source: 'cache', cached: true }
const cachedPlaylist = { id: 71, name: '离线歌单', trackCount: 1 }

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

function fixture({ nativeMode = false, preference = false, sessionProfile, handlers = {} } = {}) {
  const calls = []
  const nativeState = { mode: nativeMode, account: sessionProfile }
  const localStorageValues = new Map([['vutron.mobile.local-only', String(preference)]])
  const timers = new Map()
  let nextTimer = 0
  const count = name => calls.filter(call => call.name === name).length
  const argsFor = name => calls.filter(call => call.name === name).map(call => call.argument)
  const method = (name, fallback) => async argument => {
    calls.push({ name, argument: argument === undefined ? undefined : JSON.parse(JSON.stringify(argument)) })
    const result = await (handlers[name] || fallback)(argument, nativeState)
    if (name === 'loginWithCookie' || name === 'checkQrLogin' && result.loggedIn) {
      nativeState.account = result.profile
      nativeState.mode = false
    }
    return result
  }
  const native = {
    getLocalOnly: method('getLocalOnly', () => ({ enabled: nativeState.mode })),
    setLocalOnly: method('setLocalOnly', argument => { nativeState.mode = argument.enabled; return { enabled: nativeState.mode } }),
    getSession: method('getSession', () => ({ loggedIn: Boolean(nativeState.account), profile: nativeState.account })),
    loginWithCookie: method('loginWithCookie', () => ({ profile: account })),
    createQrLogin: method('createQrLogin', () => ({ key: 'qr-test', url: 'https://example.invalid/qr', imageData: 'data:image/png;base64,test' })),
    checkQrLogin: method('checkQrLogin', () => ({ code: 801, loggedIn: false })),
    cancelQrLogin: method('cancelQrLogin', () => undefined),
    logout: method('logout', () => { nativeState.account = undefined }),
    restorePlayback: method('restorePlayback', () => undefined),
    play: method('play', () => undefined),
    getPlaybackState: method('getPlaybackState', () => ({ playing: false, trackId: 0, position: 0, duration: 0, error: '' })),
    getPlaybackQueue: method('getPlaybackQueue', () => ({ tracks: [], index: -1, upNext: [], mode: 0 })),
    getCachedPlaylists: method('getCachedPlaylists', () => ({ playlists: [cachedPlaylist] })),
    getCachedTracks: method('getCachedTracks', () => ({ tracks: [cachedTrack] })),
    getLocalMusicIndex: method('getLocalMusicIndex', () => ({ tracks: [localTrack], indexed: true, updatedAt: Date.now() })),
    scanLocalMusic: method('scanLocalMusic', () => ({ tracks: [localTrack] })),
    getLocalLibrary: method('getLocalLibrary', () => ({ folders: [{ id: 'folder:main', name: '本地目录', trackCount: 1 }], playlists: [] })),
    getCacheStatus: method('getCacheStatus', () => ({ capacityMb: 512, usedBytes: 1024, trackCount: 1, enabled: true, artworkCapacityMb: 96 })),
    getSourceSettings: method('getSourceSettings', () => ({ autoMatch: false })),
    getLyricOverlaySettings: method('getLyricOverlaySettings', () => ({ enabled: false, locked: true, fontSize: 26, permissionGranted: false })),
    getPlaylists: method('getPlaylists', () => {
      assert.equal(nativeState.mode, false, 'local mode cannot request online playlists')
      return { playlists: [{ id: 81, name: '网易云歌单' }] }
    }),
    getHomeData: method('getHomeData', () => {
      assert.equal(nativeState.mode, false, 'local mode cannot request online home data')
      return { banners: [], playlists: [], artists: [], albums: [], charts: [], dailyTracks: [] }
    }),
    search: method('search', () => { throw new Error('Unexpected online search') }),
    getEntityTracks: method('getEntityTracks', () => { throw new Error('Unexpected online entity') })
  }
  const modules = new Map()
  const globals = {
    Error, Date, console, setTimeout, clearTimeout,
    setInterval(callback, interval) { const id = ++nextTimer; timers.set(id, { callback, interval }); return id },
    clearInterval(id) { timers.delete(id) },
    window: { scrollY: 0 },
    document: { visibilityState: 'visible' },
    localStorage: {
      getItem: key => localStorageValues.get(key) ?? null,
      setItem: (key, value) => localStorageValues.set(key, value)
    }
  }

  function load(filename) {
    if (filename === path.join(appRoot, 'src/platform/i18n.ts')) return mobileI18n
    if (filename === path.join(appRoot, 'src/platform/vutronPlugin.ts')) return { vutronPlugin: native }
    if (modules.has(filename)) return modules.get(filename).exports
    const module = { exports: {} }
    modules.set(filename, module)
    const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      fileName: filename,
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true }
    }).outputText
    const requireModule = name => {
      if (name === 'vue') return vue
      if (name === 'pinia') return pinia
      if (name === '@vutronmusic/core/lyrics') return load(path.resolve(appRoot, '../../packages/core/src/lyrics.ts'))
      if (name.startsWith('.')) return load(path.resolve(path.dirname(filename), name + '.ts'))
      throw new Error('Unexpected dependency: ' + name)
    }
    new vm.Script(output, { filename }).runInNewContext({ ...globals, module, exports: module.exports, require: requireModule })
    return module.exports
  }

  const instance = pinia.createPinia()
  pinia.setActivePinia(instance)
  const store = load(path.join(appRoot, 'src/stores/androidShell.ts')).useAndroidShellStore(instance)
  return {
    store, count, argsFor, nativeState, timers,
    pageRefresh: libraryMode => load(path.join(appRoot, 'src/composables/pageRefresh.ts')).usePageRefresh(store, libraryMode),
    libraryPresentation: load(path.join(appRoot, 'src/composables/libraryPresentation.ts')),
    dispose() { store.stopPlaybackPolling(); store.$dispose() }
  }
}

function assertLocalData(store) {
  assert.equal(store.localTracks[0].id, localTrack.id)
  assert.equal(store.cachedTracks[0].id, cachedTrack.id)
  assert.equal(store.cachedPlaylists[0].id, cachedPlaylist.id)
  assert.equal(store.cacheCapacityMb, 512)
  assert.equal(store.overlaySettings.fontSize, 26)
  assert.equal(store.autoMatchSources, false)
}

test('an open queue only fetches its full contents when queue state changes or a new source is played', async () => {
  const state = { trackId: 0, playing: false, position: 0, duration: 0, error: '', queueIndex: 0, queueCount: 2000, upNextCount: 0, mode: 0 }
  const f = fixture({ handlers: { getPlaybackState: () => ({ ...state }) } })
  f.store.queueOpen = true
  await f.store.refreshPlaybackState()
  assert.equal(f.count('getPlaybackQueue'), 1)
  state.position = 2000
  await f.store.refreshPlaybackState()
  await f.store.refreshPlaybackState()
  assert.equal(f.count('getPlaybackQueue'), 1)
  for (const [field, value] of [['queueIndex', 1], ['queueCount', 2001], ['upNextCount', 1], ['mode', 2], ['queueToken', 'another-source']]) {
    const previous = f.count('getPlaybackQueue')
    state[field] = value
    await f.store.refreshPlaybackState()
    assert.equal(f.count('getPlaybackQueue'), previous + 1)
  }
  const previous = f.count('getPlaybackQueue')
  await f.store.play(localTrack, 'local:folder', [localTrack])
  assert.equal(f.count('getPlaybackQueue'), previous + 1)
  f.dispose()
})

test('paged offline playback receives all sorted matches instead of only the first rendered batch', async () => {
  const f = fixture()
  const scope = vue.effectScope()
  try {
    f.store.cachedTracks = Array.from({ length: 2000 }, (_, id) => ({ id: id + 1, name: 'Track ' + (2000 - id), localUri: 'file:///cache/' + id }))
    const { useSortedTracks, useProgressiveTracks } = f.libraryPresentation
    const sorted = useSortedTracks(vue.computed(() => f.store.cachedTracks), vue.ref('title'))
    const page = scope.run(() => useProgressiveTracks(sorted, vue.ref('cache')))
    assert.equal(page.visible.value.length, 80)
    await f.store.play(page.visible.value[0], 'cache:all', sorted.value)
    const request = f.argsFor('play')[0]
    assert.equal(request.queue.length, 2000)
    assert.equal(request.queue[0].name, 'Track 1')
    assert.equal(request.queue[1999].name, 'Track 2000')
    assert.equal(request.index, 0)
    assert.equal(f.store.cachedTracks[0].name, 'Track 2000')
  } finally { scope.stop(); f.dispose() }
})

test('page refresh: home updates displayed recommendations, keeps content while busy and ignores repeated clicks', async () => {
  const pending = deferred()
  const f = fixture({ handlers: { getHomeData: () => pending.promise } })
  f.store.profile = account
  f.store.homeData.banners = [{ id: 1 }]
  const refresh = f.pageRefresh(vue.ref('playlists'))
  assert.equal(refresh.headerRefreshLabel.value, '刷新首页')
  const task = refresh.refreshCurrentView()
  assert.equal(refresh.headerRefreshing.value, true)
  assert.equal(f.store.homeData.banners[0].id, 1)
  await refresh.refreshCurrentView()
  await f.store.loadHomeData()
  assert.equal(f.count('getHomeData'), 1)
  pending.resolve({ banners: [{ id: 2 }], playlists: [{ id: 3 }], artists: [], albums: [], charts: [], dailyTracks: [{ id: 4 }] })
  await task
  assert.equal(f.store.homeData.banners[0].id, 2)
  assert.equal(f.store.homeData.playlists[0].id, 3)
  assert.equal(f.store.homeData.dailyTracks[0].id, 4)
  assert.equal(f.count('getPlaylists'), 0)
  assert.equal(refresh.headerRefreshing.value, false)
  assert.match(f.store.queueNotice, /首页已刷新/)
  f.dispose()
})

test('page refresh: playlist root refreshes its collection instead of the homepage and deduplicates requests', async () => {
  const pending = deferred()
  const f = fixture({ handlers: { getPlaylists: () => pending.promise } })
  f.store.profile = account
  f.store.activeTab = 'library'
  const refresh = f.pageRefresh(vue.ref('playlists'))
  assert.equal(refresh.headerRefreshLabel.value, '刷新歌单')
  const task = refresh.refreshCurrentView()
  assert.equal(refresh.headerRefreshing.value, true)
  await refresh.refreshCurrentView()
  await f.store.loadPlaylists()
  assert.equal(f.count('getPlaylists'), 1)
  pending.resolve({ playlists: [{ id: 91, name: 'Updated playlist' }] })
  await task
  assert.equal(f.store.playlists[0].id, 91)
  assert.equal(f.count('getHomeData'), 0)
  assert.equal(refresh.headerRefreshing.value, false)
  assert.match(f.store.queueNotice, /歌单列表已刷新/)
  f.dispose()
})

test('page refresh: unrelated screens, playlist details and local-only mode do not expose a misleading refresh', async () => {
  const f = fixture()
  const mode = vue.ref('playlists')
  const refresh = f.pageRefresh(mode)
  f.store.profile = account
  for (const tab of ['settings', 'player', 'search', 'entity', 'localCollection']) {
    f.store.activeTab = tab
    assert.equal(refresh.headerRefreshLabel.value, '')
    await refresh.refreshCurrentView()
  }
  f.store.activeTab = 'library'
  for (const value of ['local', 'offline']) {
    mode.value = value
    assert.equal(refresh.headerRefreshLabel.value, '')
    await refresh.refreshCurrentView()
  }
  mode.value = 'playlists'
  f.store.selectedPlaylist = cachedPlaylist
  assert.equal(refresh.headerRefreshLabel.value, '')
  await refresh.refreshCurrentView()
  f.store.activeTab = 'home'
  f.store.localOnly = true
  assert.equal(refresh.headerRefreshLabel.value, '')
  await refresh.refreshCurrentView()
  f.store.localOnly = false
  f.store.profile = undefined
  assert.equal(refresh.headerRefreshLabel.value, '')
  await refresh.refreshCurrentView()
  assert.equal(f.count('getHomeData'), 0)
  assert.equal(f.count('getPlaylists'), 0)
  f.dispose()
})

test('page refresh: home failure keeps existing content, shows the error and allows a retry', async () => {
  let fail = true
  const f = fixture({ handlers: { getHomeData: () => {
    if (fail) throw new Error('home unavailable')
    return { banners: [{ id: 2 }], playlists: [], artists: [], albums: [], charts: [], dailyTracks: [] }
  } } })
  f.store.profile = account
  f.store.homeData.banners = [{ id: 1 }]
  f.store.queueNotice = 'Previous success'
  const refresh = f.pageRefresh(vue.ref('playlists'))
  await refresh.refreshCurrentView()
  assert.equal(f.store.homeData.banners[0].id, 1)
  assert.equal(f.store.error, 'home unavailable')
  assert.equal(f.store.queueNotice, '')
  assert.equal(refresh.headerRefreshing.value, false)
  fail = false
  await refresh.refreshCurrentView()
  assert.equal(f.store.error, '')
  assert.equal(f.store.homeData.banners[0].id, 2)
  assert.match(f.store.queueNotice, /首页已刷新/)
  f.dispose()
})

test('page refresh: cache fallback keeps the complete loaded home and reports cached content truthfully', async () => {
  const f = fixture({ handlers: { getHomeData: () => ({ banners: [], playlists: [cachedPlaylist], artists: [], albums: [], charts: [], dailyTracks: [], cached: true }) } })
  f.store.profile = account
  f.store.homeData.banners = [{ id: 1 }]
  f.store.homeData.dailyTracks = [{ id: 2 }]
  const refresh = f.pageRefresh(vue.ref('playlists'))
  await refresh.refreshCurrentView()
  assert.equal(f.store.homeData.banners[0].id, 1)
  assert.equal(f.store.homeData.dailyTracks[0].id, 2)
  assert.match(f.store.queueNotice, /显示已保存的内容/)
  assert.doesNotMatch(f.store.queueNotice, /已刷新/)
  f.dispose()
})

test('page refresh: an empty home can display its cached playlist snapshot', async () => {
  const f = fixture({ handlers: { getHomeData: () => ({ banners: [], playlists: [cachedPlaylist], artists: [], albums: [], charts: [], dailyTracks: [], cached: true }) } })
  f.store.profile = account
  await f.pageRefresh(vue.ref('playlists')).refreshCurrentView()
  assert.equal(f.store.homeData.playlists[0].id, cachedPlaylist.id)
  assert.match(f.store.queueNotice, /显示已保存的内容/)
  f.dispose()
})

test('page refresh: playlist cache and errors do not report a fresh network update', async () => {
  let fail = false
  const f = fixture({ handlers: { getPlaylists: () => {
    if (fail) throw new Error('playlists unavailable')
    return { playlists: [cachedPlaylist], cached: true }
  } } })
  f.store.profile = account
  f.store.activeTab = 'library'
  const refresh = f.pageRefresh(vue.ref('playlists'))
  await refresh.refreshCurrentView()
  assert.equal(f.store.playlists[0].id, cachedPlaylist.id)
  assert.match(f.store.queueNotice, /显示已保存的内容/)
  fail = true
  await refresh.refreshCurrentView()
  assert.equal(f.store.playlists[0].id, cachedPlaylist.id)
  assert.equal(f.store.error, 'playlists unavailable')
  assert.equal(f.store.queueNotice, '')
  assert.equal(refresh.headerRefreshing.value, false)
  f.dispose()
})

test('restore without a profile still restores local indexes, offline playlists and settings', async () => {
  const f = fixture()
  await f.store.restore()
  assertLocalData(f.store)
  assert.equal(f.store.loggedIn, false)
  assert.equal(f.store.localOnly, true)
  assert.equal(f.store.hasAccess, true)
  assert.equal(f.store.loginVisible, false)
  assert.equal(f.store.activeTab, 'library')
  assert.equal(f.count('getSession'), 1)
  assert.equal(f.count('getLocalMusicIndex'), 1)
  assert.equal(f.count('scanLocalMusic'), 0)
  assert.equal(f.count('getPlaylists'), 0)
  assert.equal(f.count('createQrLogin'), 0)
  f.dispose()
})

test('persisted local mode never restores an online session or starts NetEase and QR requests', async () => {
  const f = fixture({ nativeMode: true, sessionProfile: account })
  await f.store.restore()
  assertLocalData(f.store)
  assert.equal(f.store.localOnly, true)
  assert.equal(f.store.onlineAvailable, false)
  for (const name of ['getSession', 'getPlaylists', 'getHomeData', 'createQrLogin', 'checkQrLogin']) assert.equal(f.count(name), 0, name)
  assert.equal(f.count('scanLocalMusic'), 0)
  f.dispose()
})

test('session restoration failure keeps the local library usable', async () => {
  const f = fixture({ handlers: { getSession: () => { throw new Error('session unavailable') } } })
  await f.store.restore()
  assertLocalData(f.store)
  assert.equal(f.store.hasAccess, true)
  assert.equal(f.nativeState.mode, true)
  assert.match(f.store.error, /session unavailable/)
  assert.equal(f.count('getPlaylists'), 0)
  f.dispose()
})

test('explicit Cookie login switches to online without discarding local data', async () => {
  const f = fixture({ nativeMode: true })
  await f.store.restore()
  f.store.requestLogin()
  assert.equal(f.store.loginVisible, true)
  await f.store.login('test-session')
  assert.equal(f.store.onlineAvailable, true)
  assert.equal(f.store.localOnly, false)
  assert.equal(f.nativeState.mode, false)
  assert.equal(f.store.profile.userId, account.userId)
  assert.equal(f.store.activeTab, 'home')
  assertLocalData(f.store)
  assert.equal(f.count('getPlaylists'), 1)
  assert.equal(f.count('getHomeData'), 1)
  assert.equal(f.count('scanLocalMusic'), 0)
  f.dispose()
})

test('explicit QR login is allowed from local mode and success enables online capabilities', async () => {
  const f = fixture({ nativeMode: true, handlers: { checkQrLogin: () => ({ code: 803, loggedIn: true, profile: account }) } })
  await f.store.restore()
  f.store.requestLogin()
  await f.store.createQrLogin()
  assert.equal(f.store.qrLoginKey, 'qr-test')
  assert.equal(f.nativeState.mode, true)
  await f.store.checkQrLogin()
  assert.equal(f.store.onlineAvailable, true)
  assert.equal(f.store.qrLoginKey, '')
  assert.equal(f.count('getSession'), 0)
  assertLocalData(f.store)
  f.dispose()
})

test('returning from login to local mode cancels QR and preserves the indexed library', async () => {
  const f = fixture({ nativeMode: true })
  await f.store.restore()
  f.store.requestLogin()
  await f.store.createQrLogin()
  await f.store.enterLocalOnly()
  assert.equal(f.store.loginVisible, false)
  assert.equal(f.store.localOnly, true)
  assert.equal(f.store.qrLoginKey, '')
  assert.equal(f.count('cancelQrLogin'), 1)
  assert.equal(f.count('logout'), 0)
  assert.equal(f.count('scanLocalMusic'), 0)
  assertLocalData(f.store)
  f.dispose()
})

test('a QR creation arriving after cancellation cannot reopen the login session', async () => {
  const qr = deferred()
  const started = deferred()
  const f = fixture({ nativeMode: true, handlers: { createQrLogin: () => { started.resolve(); return qr.promise } } })
  await f.store.restore()
  f.store.requestLogin()
  const creating = f.store.createQrLogin()
  await started.promise
  await f.store.enterLocalOnly()
  qr.resolve({ key: 'late-qr', url: '', imageData: 'late-image' })
  await creating
  assert.equal(f.store.qrLoginKey, '')
  assert.equal(f.store.qrLoginLoading, false)
  assert.equal(f.store.loginVisible, false)
  assert.deepEqual(f.argsFor('cancelQrLogin'), [{ key: 'late-qr' }])
  assertLocalData(f.store)
  f.dispose()
})

test('late successful Cookie login reasserts native local mode after the user cancelled', async () => {
  const login = deferred()
  const started = deferred()
  const f = fixture({ nativeMode: true, handlers: { loginWithCookie: () => { started.resolve(); return login.promise } } })
  await f.store.restore()
  f.store.requestLogin()
  const signingIn = f.store.login('test-session')
  await started.promise
  await f.store.enterLocalOnly()
  login.resolve({ profile: account })
  await signingIn
  assert.equal(f.nativeState.mode, true)
  assert.equal(f.store.onlineAvailable, false)
  assert.equal(f.store.profile, undefined)
  assert.equal(f.store.loginVisible, false)
  assert.equal(f.count('getPlaylists'), 0)
  assert.equal(f.count('getHomeData'), 0)
  assertLocalData(f.store)
  f.dispose()
})

test('logout preserves frontend offline data and subsequent index reads do not rescan folders', async () => {
  const f = fixture({ sessionProfile: account })
  await f.store.restore()
  assert.equal(f.store.onlineAvailable, true)
  await f.store.logout()
  assert.equal(f.store.profile, undefined)
  assert.equal(f.store.playlists.length, 0)
  assert.equal(f.store.localOnly, true)
  assertLocalData(f.store)
  await Promise.all([f.store.loadLocalMusic(), f.store.loadLocalMusic(), f.store.loadLocalMusic()])
  assert.equal(f.count('scanLocalMusic'), 0)
  assert.equal(f.count('getLocalMusicIndex'), 3)
  assert.equal(f.count('logout'), 1)
  f.dispose()
})

test('local capabilities block online navigation and actions while local navigation works', async () => {
  const f = fixture({ nativeMode: true })
  await f.store.restore()
  for (const tab of ['home', 'search', 'entity']) {
    f.store.navigateTo(tab)
    assert.equal(f.store.activeTab, 'library')
  }
  await f.store.loadPlaylists()
  await f.store.loadHomeData()
  await f.store.runSearch('tracks', 'anything')
  await f.store.openEntityTracks('albums', 123, 'album')
  for (const name of ['getPlaylists', 'getHomeData', 'search', 'getEntityTracks']) assert.equal(f.count(name), 0, name)
  f.store.navigateTo('settings')
  assert.equal(f.store.activeTab, 'settings')
  assert.equal(f.store.navigateBack(), true)
  assert.equal(f.store.activeTab, 'library')
  f.dispose()
})
