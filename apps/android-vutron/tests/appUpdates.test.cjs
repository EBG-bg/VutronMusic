const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { test } = require('node:test')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { renderToString } = require('@vue/server-renderer')
const mobileI18n = require('./helpers/i18n.cjs').loadMobileI18n()

const appRoot = path.resolve(__dirname, '..')
const packageInfo = JSON.parse(fs.readFileSync(path.join(appRoot, 'package.json'), 'utf8'))

function evaluate(source, filename, dependencies) {
  const compiled = ts.transpileModule(source, {
    fileName: filename,
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      esModuleInterop: true
    }
  }).outputText
  const module = { exports: {} }
  const context = {
    module,
    exports: module.exports,
    Error,
    console,
    require(name) {
      if (Object.hasOwn(dependencies, name)) return dependencies[name]
      throw new Error('Unexpected dependency in ' + filename + ': ' + name)
    }
  }
  new vm.Script(compiled, { filename }).runInNewContext(context)
  return module.exports
}

function loadPlatform(name, dependencies = {}) {
  const filename = path.join(appRoot, 'src/platform', name)
  return evaluate(fs.readFileSync(filename, 'utf8'), filename, dependencies)
}

const presentation = loadPlatform('appUpdatePresentation.ts', { './i18n': mobileI18n })

function defaultState(changes = {}) {
  return {
    currentVersion: packageInfo.version,
    comparisonVersion: changes.comparisonVersion || changes.currentVersion || packageInfo.version,
    currentVersionCode: 15,
    autoCheck: false,
    status: 'idle',
    releaseUrl: 'https://github.com/EBG-bg/VutronMusic/releases',
    notes: '',
    progress: 0,
    downloadedBytes: 0,
    totalBytes: 0,
    error: '',
    checkedAt: 0,
    canInstall: false,
    ...changes
  }
}

function fixture({ state = {}, supported = true, handlers = {}, edition = 'mobile', localOnly = false } = {}) {
  const mode = vue.ref(localOnly)
  let nativeState = defaultState(state)
  const calls = []
  const stateListeners = new Set()
  const resumeListeners = new Set()
  const removed = { state: 0, resume: 0 }
  const count = name => calls.filter(call => call.name === name).length
  const argumentsFor = name => calls.filter(call => call.name === name).map(call => call.argument)

  function method(name, fallback) {
    return async argument => {
      calls.push({ name, argument: argument === undefined ? undefined : JSON.parse(JSON.stringify(argument)) })
      const next = await (handlers[name] || fallback)(argument, { ...nativeState })
      nativeState = { ...nativeState, ...next }
      return { ...nativeState }
    }
  }

  const plugin = {
    getAppUpdateState: method('getAppUpdateState', () => nativeState),
    checkAppUpdate: method('checkAppUpdate', () => ({ status: 'upToDate', checkedAt: 1000 })),
    setAutoCheckUpdates: method('setAutoCheckUpdates', argument => ({ autoCheck: argument.enabled })),
    downloadAppUpdate: method('downloadAppUpdate', () => ({ status: 'ready', progress: 100 })),
    installAppUpdate: method('installAppUpdate', () => ({})),
    async addListener(name, callback) {
      assert.equal(name, 'appUpdateChanged')
      stateListeners.add(callback)
      return { async remove() { stateListeners.delete(callback); removed.state++ } }
    }
  }
  const capacitorApp = {
    async addListener(name, callback) {
      assert.equal(name, 'appStateChange')
      resumeListeners.add(callback)
      return { async remove() { resumeListeners.delete(callback); removed.resume++ } }
    }
  }
  const controller = loadPlatform('appUpdates.ts', {
    vue,
    '@capacitor/app': { App: capacitorApp },
    '@capacitor/core': { Capacitor: { isNativePlatform: () => supported } },
    '../../package.json': packageInfo,
    './buildInfo': { buildInfo: { edition, version: packageInfo.version, comparisonVersion: packageInfo.version } },
    './localOnlyMode': { localOnlyMode: mode },
    './vutronPlugin': { vutronPlugin: plugin },
    './appUpdatePresentation': presentation
  })

  return {
    controller, calls, count, argumentsFor, removed,
    setLocalOnly(enabled) { mode.value = enabled },
    setNative(changes) { nativeState = { ...nativeState, ...changes } },
    emitState(changes) {
      nativeState = { ...nativeState, ...changes }
      for (const listener of stateListeners) listener({ ...nativeState })
    },
    async resume(isActive = true) {
      await Promise.all([...resumeListeners].map(listener => listener({ isActive })))
    }
  }
}

function availableState(changes = {}) {
  return { currentVersion: '1.0.9', latestVersion: '1.0.10', status: 'available', ...changes }
}

test('personal edition only displays public release information and never downloads or installs it', async () => {
  const f = fixture({ edition: 'personal', state: availableState() })
  await f.controller.initializeAppUpdates()
  assert.equal(f.controller.appUpdateAvailable.value, true)
  assert.equal(f.controller.appUpdateInstallAllowed.value, false)
  await f.controller.updateApp()
  assert.equal(f.count('downloadAppUpdate'), 0)
  assert.equal(f.count('installAppUpdate'), 0)
  const html = await renderComponent('AppUpdateSettings.vue', f.controller)
  assert.match(html, /只查看公开版本信息/)
  assert.doesNotMatch(html, />下载并更新</)
  f.emitState({ status: 'ready' })
  await f.controller.updateApp()
  assert.equal(f.count('installAppUpdate'), 0)
  f.controller.disposeAppUpdates()
})

test('native channel denial also prevents installation in a public frontend', async () => {
  const f = fixture({ state: availableState({ installAllowed: false }) })
  await f.controller.initializeAppUpdates()
  await f.controller.updateApp()
  assert.equal(f.count('downloadAppUpdate'), 0)
  assert.equal(f.count('installAppUpdate'), 0)
  f.controller.disposeAppUpdates()
})

test('local mode skips automatic startup, resume and opt-in checks while an explicit manual check works', async () => {
  const f = fixture({ localOnly: true, state: { autoCheck: true } })
  await f.controller.initializeAppUpdates()
  await f.resume()
  await f.controller.setAutomaticAppUpdates(true)
  assert.equal(f.count('checkAppUpdate'), 0)
  assert.equal(f.controller.appUpdateAutomaticPaused.value, true)
  const html = await renderComponent('AppUpdateSettings.vue', f.controller)
  assert.match(html, /仅本地模式已暂停自动检查/)
  await f.controller.checkAppUpdates(true)
  assert.equal(f.count('checkAppUpdate'), 1)
  assert.deepEqual(f.argumentsFor('checkAppUpdate'), [{ manual: true }])
  f.setLocalOnly(false)
  await f.resume()
  assert.equal(f.count('checkAppUpdate'), 2)
  f.controller.disposeAppUpdates()
})

test('restored native local mode pauses automatic checks even before frontend session restoration', async () => {
  const f = fixture({ state: { autoCheck: true, autoCheckPaused: true } })
  await f.controller.initializeAppUpdates()
  assert.equal(f.count('checkAppUpdate'), 0)
  await f.controller.checkAppUpdates(true)
  assert.equal(f.count('checkAppUpdate'), 1)
  f.controller.disposeAppUpdates()
})

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

async function renderComponent(name, controller) {
  const filename = path.join(appRoot, 'src/components', name)
  const descriptor = parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor
  const script = compileScript(descriptor, { id: name, inlineTemplate: true })
  const card = vue.defineComponent({
    setup(_, { slots }) {
      return () => vue.h('section', [slots.heading && slots.heading(), slots.default && slots.default()])
    }
  })
  const icon = vue.defineComponent({ setup: () => () => vue.h('span') })
  const component = evaluate(script.content, filename + '.ts', {
    vue,
    './CollapsibleSettingsCard.vue': { __esModule: true, default: card },
    './OriginalIcon.vue': { __esModule: true, default: icon },
    '../platform/appUpdates': controller,
    '../platform/appUpdatePresentation': presentation,
    '../platform/i18n': mobileI18n
  }).default
  return renderToString(vue.createSSRApp(component).use(mobileI18n.i18n))
}

test('initialization defaults to auto-check off and only reads native state once', async () => {
  const f = fixture()
  assert.equal(f.controller.appUpdateState.autoCheck, false)
  const first = f.controller.initializeAppUpdates()
  assert.equal(f.controller.initializeAppUpdates(), first)
  await first
  assert.equal(f.count('getAppUpdateState'), 1)
  assert.equal(f.count('checkAppUpdate'), 0)
  assert.equal(f.controller.showAppUpdateNotice.value, false)
  f.controller.disposeAppUpdates()
})

test('manual checking works with auto-check disabled while automatic checking does nothing', async () => {
  const f = fixture()
  await f.controller.initializeAppUpdates()
  await f.controller.checkAppUpdates(false)
  assert.equal(f.count('checkAppUpdate'), 0)
  await f.controller.checkAppUpdates(true)
  assert.deepEqual(f.argumentsFor('checkAppUpdate'), [{ manual: true }])
  assert.equal(f.controller.appUpdateState.status, 'upToDate')
  f.controller.disposeAppUpdates()
})

test('enabling and disabling automatic checks persists through the native setting method', async () => {
  const f = fixture({ handlers: { checkAppUpdate: () => availableState() } })
  await f.controller.initializeAppUpdates()
  await f.controller.setAutomaticAppUpdates(true)
  assert.deepEqual(f.argumentsFor('setAutoCheckUpdates'), [{ enabled: true }])
  assert.deepEqual(f.argumentsFor('checkAppUpdate'), [{ manual: false }])
  assert.equal(f.controller.appUpdateState.autoCheck, true)
  assert.equal(f.controller.showAppUpdateNotice.value, true)
  assert.equal(f.controller.appUpdateSaving.value, false)

  await f.controller.setAutomaticAppUpdates(false)
  assert.deepEqual(f.argumentsFor('setAutoCheckUpdates'), [{ enabled: true }, { enabled: false }])
  assert.equal(f.controller.appUpdateState.autoCheck, false)
  assert.equal(f.controller.showAppUpdateNotice.value, false)
  await f.controller.checkAppUpdates(false)
  assert.equal(f.count('checkAppUpdate'), 1)
  f.controller.disposeAppUpdates()
})

test('installation starts only after a download resolves to verified ready state', async () => {
  const downloaded = deferred()
  const f = fixture({
    state: availableState(),
    handlers: { downloadAppUpdate: () => downloaded.promise }
  })
  await f.controller.initializeAppUpdates()
  const updating = f.controller.updateApp()
  assert.equal(f.count('downloadAppUpdate'), 1)
  assert.equal(f.count('installAppUpdate'), 0)
  downloaded.resolve({ status: 'ready', progress: 100 })
  await updating
  assert.equal(f.count('installAppUpdate'), 1)
  assert.deepEqual(f.calls.filter(call => /AppUpdate$/.test(call.name)).map(call => call.name),
    ['downloadAppUpdate', 'installAppUpdate'])
  f.controller.disposeAppUpdates()
})

test('failed or unfinished downloads never launch the installer', async t => {
  for (const kind of ['reject', 'error-state', 'downloading']) {
    await t.test(kind, async () => {
      const f = fixture({
        state: availableState(),
        handlers: {
          downloadAppUpdate: () => {
            if (kind === 'reject') throw new Error('download verification failed')
            return kind === 'error-state'
              ? { status: 'error', error: 'download verification failed' }
              : { status: 'downloading', progress: 25 }
          }
        }
      })
      await f.controller.initializeAppUpdates()
      await f.controller.updateApp()
      assert.equal(f.count('downloadAppUpdate'), 1)
      assert.equal(f.count('installAppUpdate'), 0)
      assert.equal(f.controller.appUpdateState.status, kind === 'downloading' ? 'downloading' : 'error')
      if (kind !== 'downloading') assert.match(f.controller.appUpdateState.error, /verification failed/)
      f.controller.disposeAppUpdates()
    })
  }
})

test('permission-required continuation installs the existing APK and refreshes permission on resume', async () => {
  const f = fixture({ state: availableState({ status: 'permissionRequired', canInstall: false }) })
  await f.controller.initializeAppUpdates()
  await f.controller.updateApp()
  assert.equal(f.count('downloadAppUpdate'), 0)
  assert.equal(f.count('installAppUpdate'), 1)
  await f.resume(false)
  assert.equal(f.count('getAppUpdateState'), 1)

  f.setNative({ status: 'ready', canInstall: true })
  await f.resume(true)
  assert.equal(f.count('getAppUpdateState'), 2)
  assert.equal(f.controller.appUpdateState.canInstall, true)
  assert.equal(f.controller.appUpdateState.status, 'ready')
  assert.equal(f.count('checkAppUpdate'), 0)
  await f.controller.updateApp()
  assert.equal(f.count('installAppUpdate'), 2)
  assert.equal(f.count('downloadAppUpdate'), 0)
  f.controller.disposeAppUpdates()
})

test('dispose removes both listeners and allows a clean later initialization', async () => {
  const f = fixture()
  await f.controller.initializeAppUpdates()
  f.emitState({ status: 'checking' })
  assert.equal(f.controller.appUpdateBusy.value, true)
  f.controller.disposeAppUpdates()
  assert.deepEqual(f.removed, { state: 1, resume: 1 })
  f.emitState({ status: 'error', error: 'detached listener' })
  await f.resume()
  assert.equal(f.controller.appUpdateState.status, 'checking')
  assert.equal(f.count('getAppUpdateState'), 1)

  f.setNative({ status: 'idle', error: '' })
  await f.controller.initializeAppUpdates()
  assert.equal(f.count('getAppUpdateState'), 2)
  assert.equal(f.controller.appUpdateState.status, 'idle')
  f.controller.disposeAppUpdates()
  assert.deepEqual(f.removed, { state: 2, resume: 2 })
})

test('dispose discards a pending initialization state response', async () => {
  const state = deferred()
  const started = deferred()
  const f = fixture({ handlers: { getAppUpdateState: () => { started.resolve(); return state.promise } } })
  const initializing = f.controller.initializeAppUpdates()
  await started.promise
  f.controller.disposeAppUpdates()
  state.resolve({ status: 'available', latestVersion: '9.0.0', autoCheck: true })
  await initializing
  assert.equal(f.controller.appUpdateState.status, 'idle')
  assert.equal(f.controller.appUpdateState.latestVersion, undefined)
  assert.equal(f.controller.appUpdateState.autoCheck, false)
  assert.equal(f.count('checkAppUpdate'), 0)
  assert.deepEqual(f.removed, { state: 1, resume: 1 })
})

test('version availability and presentation compare numeric segments and expose actionable status text', () => {
  assert.equal(presentation.isNewerAppVersion('1.0.10', '1.0.9'), true)
  assert.equal(presentation.isNewerAppVersion('1.0.9', '1.0.10'), false)
  assert.equal(presentation.isNewerAppVersion('1.0.10', '1.0.10'), false)
  assert.equal(presentation.isNewerAppVersion('2.0.0', '1.99.99'), true)
  assert.equal(presentation.isNewerAppVersion('1.0.10-beta', '1.0.9'), false)
  assert.equal(presentation.isNewerAppVersion(undefined, '1.0.9'), false)
  assert.equal(presentation.appUpdateSummary(defaultState({ status: 'upToDate' })), '当前已是最新版本')
  assert.equal(presentation.appUpdateSummary(defaultState({ status: 'downloading', progress: 42.6 })),
    '正在下载更新 · 43%')
  assert.equal(presentation.appUpdateActionLabel(defaultState({ status: 'ready' })), '安装更新')
  assert.equal(presentation.appUpdateActionLabel(defaultState({ status: 'permissionRequired' })), '继续安装')
  assert.equal(presentation.appUpdateActionLabel(defaultState({ status: 'downloading' })), '正在下载…')
  assert.match(presentation.appUpdateSummary(defaultState({ status: 'error', error: '校验失败' })), /校验失败/)
})

test('settings renders the default-off control and the existing downloaded APK continuation', async () => {
  const f = fixture({ state: availableState({ status: 'permissionRequired', notes: '<img src=x onerror=alert(1)>' }) })
  await f.controller.initializeAppUpdates()
  const html = await renderComponent('AppUpdateSettings.vue', f.controller)
  assert.match(html, /role="switch" aria-checked="false"/)
  assert.match(html, /默认关闭/)
  assert.match(html, /继续安装/)
  assert.match(html, /检查更新/)
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/)
  assert.equal(html.includes('<img src=x'), false)
  f.controller.disposeAppUpdates()
})

test('automatic notices display the available version, progress and button label, then dismiss', async () => {
  const f = fixture({ state: { autoCheck: true }, handlers: { checkAppUpdate: () => availableState() } })
  await f.controller.initializeAppUpdates()
  assert.equal(f.controller.showAppUpdateNotice.value, true)
  const available = await renderComponent('AppUpdateNotice.vue', f.controller)
  assert.match(available, /发现新版本 1[.]0[.]10/)
  assert.match(available, /下载并更新/)

  f.emitState({ status: 'downloading', progress: 37 })
  const downloading = await renderComponent('AppUpdateNotice.vue', f.controller)
  assert.match(downloading, /value="37"/)
  assert.match(downloading, /disabled[^>]*>正在下载…/)
  f.emitState({ status: 'permissionRequired' })
  const permission = await renderComponent('AppUpdateNotice.vue', f.controller)
  assert.match(permission, /继续安装/)
  f.controller.dismissAppUpdateNotice()
  assert.equal(f.controller.showAppUpdateNotice.value, false)
  assert.equal((await renderComponent('AppUpdateNotice.vue', f.controller)).includes('app-update-notice'), false)
  f.controller.disposeAppUpdates()
})

test('personal numbering uses the official feature baseline instead of offering an older 1.x APK', async () => {
  const f = fixture({ state: { currentVersion: '0.3.11', comparisonVersion: '1.0.1', latestVersion: '1.0.0', status: 'upToDate' } })
  await f.controller.initializeAppUpdates()
  assert.equal(f.controller.appUpdateState.currentVersion, '0.3.11')
  assert.equal(f.controller.appUpdateAvailable.value, false)
  f.emitState({ latestVersion: '1.0.2', status: 'available' })
  assert.equal(f.controller.appUpdateAvailable.value, true)
  f.controller.disposeAppUpdates()
})
