const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vue = require('vue')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { renderToString } = require('@vue/server-renderer')
const { loadMobileI18n } = require('./helpers/i18n.cjs')

function flatten(value, prefix = '', result = {}) {
  for (const [key, item] of Object.entries(value)) {
    const name = prefix ? prefix + '.' + key : key
    if (typeof item === 'string') result[name] = item
    else flatten(item, name, result)
  }
  return result
}

test('all three mobile languages have the same keys and resolve without exposing message keys', () => {
  const files = ['zh-hans', 'zh-hant', 'en'].map(name => flatten(JSON.parse(fs.readFileSync(path.resolve(__dirname, '../src/locales/' + name + '.json'), 'utf8'))))
  for (const messages of files.slice(1)) assert.deepEqual(Object.keys(messages).sort(), Object.keys(files[0]).sort())
  const f = loadMobileI18n()
  for (const locale of ['zh', 'zht', 'en']) {
    f.setAppLocale(locale)
    for (const key of Object.keys(files[0])) {
      const text = f.translate('mobile.' + key, { count: 2, version: '1.2.3', name: 'Original song title', progress: 42, time: '12:00' })
      assert.ok(text && text !== 'mobile.' + key, locale + ': ' + key)
    }
  }
})

test('changing language is immediate, updates document language, and restores independently of an account', () => {
  const storage = new Map(), f = loadMobileI18n({ storage })
  const label = vue.computed(() => f.translate('nav.home'))
  assert.equal(label.value, '首页')
  assert.equal(f.setAppLocale('en'), true)
  assert.equal(label.value, 'Home')
  assert.equal(f.attributes.get('lang'), 'en')
  assert.equal(storage.get('vutron:language'), 'en')
  const restored = loadMobileI18n({ storage })
  assert.equal(restored.appLocale.value, 'en')
  assert.equal(restored.translate('mobile.language.title'), 'Interface language')
  f.setAppLocale('zht')
  assert.equal(label.value, '首頁')
  assert.equal(f.attributes.get('lang'), 'zh-TW')
})

test('unsupported saved or requested languages use a supported fallback', () => {
  const f = loadMobileI18n({ storage: new Map([['vutron:language', 'unsupported']]) })
  assert.equal(f.appLocale.value, 'zh')
  assert.equal(f.setAppLocale('unsupported'), false)
  assert.equal(f.appLocale.value, 'zh')
  assert.equal(f.translate('mobile.updates.found', { version: '1.2.3' }), '发现新版本 1.2.3')
})

test('page refresh dispatch remains correct after switching to English', async () => {
  const f = loadMobileI18n({ locale: 'en' })
  const filename = path.resolve(__dirname, '../src/composables/pageRefresh.ts')
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, { module, exports: module.exports, require(name) {
    if (name === 'vue') return vue
    if (name === '../platform/i18n') return f
    throw new Error('Unexpected refresh dependency: ' + name)
  } })
  let home = 0, playlists = 0
  const store = vue.reactive({ onlineAvailable: true, loginVisible: false, activeTab: 'home', selectedPlaylist: undefined, homeLoading: false, playlistsLoading: false, error: '', queueNotice: '', loadHomeData: async () => { home++; return 'updated' }, loadPlaylists: async () => { playlists++; return 'updated' } })
  const refresh = module.exports.usePageRefresh(store, vue.ref('playlists'))
  assert.equal(refresh.headerRefreshLabel.value, 'Refresh home')
  await refresh.refreshCurrentView()
  assert.equal(home, 1)
  assert.match(store.queueNotice, /Home refreshed/)
  store.activeTab = 'library'
  await refresh.refreshCurrentView()
  assert.equal(playlists, 1)
  assert.equal(home, 1)
  assert.match(store.queueNotice, /Playlist collection refreshed/)
})

test('missing translations fall back to Chinese without changing track names or lyrics', () => {
  const f = loadMobileI18n({ locale: 'en' })
  f.i18n.global.missingWarn = false
  f.i18n.global.fallbackWarn = false
  f.i18n.global.setLocaleMessage('en', {})
  assert.equal(f.translate('nav.home'), '首页')
  const name = '原来的歌曲名'
  assert.equal(f.translate('mobile.player.removeTrack', { name }), '从播放列表移除 ' + name)
})

test('the actual language settings component renders English and Traditional Chinese using the installed plugin', async () => {
  const f = loadMobileI18n({ locale: 'en' })
  const filename = path.resolve(__dirname, '../src/components/LanguageSettings.vue')
  const descriptor = parse(fs.readFileSync(filename, 'utf8'), { filename }).descriptor
  const script = compileScript(descriptor, { id: 'language-test', inlineTemplate: true })
  const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
  const module = { exports: {} }
  const card = vue.defineComponent({ props: ['title', 'subtitle'], setup: (props, { slots }) => () => vue.h('section', [vue.h('h2', props.title), slots.default?.()]) })
  vm.runInNewContext(code, { module, exports: module.exports, require(name) {
    if (name === 'vue') return vue
    if (name === '../platform/i18n') return f
    if (name === './CollapsibleSettingsCard.vue') return { __esModule: true, default: card }
    throw new Error('Unexpected language settings dependency: ' + name)
  } })
  const render = () => renderToString(vue.createSSRApp(module.exports.default).use(f.i18n))
  const english = await render()
  assert.match(english, /Interface language/)
  assert.match(english, /id="app-language"/)
  assert.match(english, /Applies immediately/)
  f.setAppLocale('zht')
  assert.match(await render(), /介面語言/)
})
