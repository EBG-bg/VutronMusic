const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { renderToString } = require('@vue/server-renderer')
const mobileI18n = require('./helpers/i18n.cjs').loadMobileI18n()

function libraryHelpers(cleanup = [], storage = new Map()) {
  const file = path.join(__dirname, '../src/composables/libraryPresentation.ts')
  const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(compiled, { module, exports: module.exports, require: () => ({ ...vue, onBeforeUnmount: callback => cleanup.push(callback) }), setTimeout, clearTimeout, localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) } }, { filename: file })
  return module.exports
}

test('two thousand tracks render in batches while the complete source remains available for playback', async () => {
  const scope = vue.effectScope()
  try {
    const { useProgressiveTracks } = libraryHelpers()
    const source = vue.ref(Array.from({ length: 2000 }, (_, id) => ({ id, name: 'Track ' + id })))
    const key = vue.ref('local')
    const page = scope.run(() => useProgressiveTracks(vue.computed(() => source.value), key))
    assert.equal(page.visible.value.length, 80)
    assert.equal(page.hasMore.value, true)
    page.showMore()
    assert.equal(page.visible.value.length, 160)
    assert.equal(source.value.length, 2000)
    assert.equal(source.value[1999].id, 1999)
    key.value = 'different playlist'
    await vue.nextTick()
    assert.equal(page.visible.value.length, 80)
  } finally { scope.stop() }
})

test('local sorting supports title, artists and albums without mutating the original playlist', () => {
  const { useSortedTracks } = libraryHelpers()
  const source = vue.ref([{ id: 1, name: 'Track 10', artists: ['Beta'], album: 'Z' }, { id: 2, name: 'Track 2', artists: ['Alpha'], album: 'A' }, { id: 3, name: 'Track 1' }])
  const order = vue.ref('title')
  const sorted = useSortedTracks(vue.computed(() => source.value), order)
  assert.deepEqual(Array.from(sorted.value, item => item.id), [3, 2, 1])
  order.value = 'artist'
  assert.deepEqual(Array.from(sorted.value, item => item.id), [2, 1, 3])
  order.value = 'album'
  assert.deepEqual(Array.from(sorted.value, item => item.id), [2, 1, 3])
  assert.deepEqual(source.value.map(item => item.id), [1, 2, 3])
  order.value = 'original'
  assert.equal(sorted.value, source.value)
})

test('local sort preference restores and persists without requiring an account', async () => {
  const scope = vue.effectScope()
  try {
    const storage = new Map([['vutron:local-track-order', 'album']])
    const { useLocalTrackOrder } = libraryHelpers([], storage)
    const order = scope.run(useLocalTrackOrder)
    assert.equal(order.value, 'album')
    order.value = 'artist'
    await vue.nextTick()
    assert.equal(storage.get('vutron:local-track-order'), 'artist')
  } finally { scope.stop() }
})

test('the actual queue component renders at most eighty playlist and eighty inserted rows initially', async () => {
  const store = vue.reactive({
    queueOpen: true, playback: { trackId: 151, title: 'Currently playing' },
    playbackQueue: { tracks: Array.from({ length: 2000 }, (_, id) => ({ id, name: 'Track ' + id })), upNext: Array.from({ length: 200 }, (_, id) => ({ id, name: 'Inserted ' + id })), index: 151, mode: 0 }
  })
  function component(filename) {
    const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename })
    const script = compileScript(descriptor, { id: filename, inlineTemplate: true })
    const code = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
    const module = { exports: {} }
    const load = name => {
      if (name === 'vue') return vue
      if (name === '../stores/androidShell') return { useAndroidShellStore: () => store }
      if (name === '../composables/libraryPresentation') return libraryHelpers()
      if (name.endsWith('OriginalIcon.vue')) return { default: { render: () => vue.h('span') }, __esModule: true }
      if (name.endsWith('.vue')) return { default: component(path.resolve(path.dirname(filename), name)), __esModule: true }
      throw new Error('Unexpected queue dependency: ' + name)
    }
    vm.runInNewContext(code, { module, exports: module.exports, require: load })
    return module.exports.default
  }
  const html = await renderToString(vue.createSSRApp(component(path.resolve(__dirname, '../src/components/PlaybackQueuePanel.vue'))).use(mobileI18n.i18n))
  assert.equal((html.match(/class="queue-row(?: current)?"/g) ?? []).length, 160)
  assert.equal(store.playbackQueue.tracks.length, 2000)
  assert.match(html, /2000 首 ·/)
  assert.equal(store.playbackQueue.upNext.length, 200)
  assert.equal((html.match(/显示更多歌曲/g) ?? []).length, 2)
})

test('search indexes names, all artists and albums and reacts to a library replacement', () => {
  const scope = vue.effectScope()
  try {
    const { useTrackSearch } = libraryHelpers()
    const source = vue.ref([{ id: 1, name: 'Song', artists: ['A', 'Second Artist'], album: 'Live Album' }, { id: 2, name: '另一首歌', artists: ['歌手'] }])
    const query = vue.ref('second artist')
    const matches = scope.run(() => useTrackSearch(vue.computed(() => source.value), query))
    assert.equal(matches.value[0].id, 1)
    query.value = 'live album'
    assert.equal(matches.value[0].id, 1)
    query.value = '另一首'
    assert.equal(matches.value[0].id, 2)
    source.value = []
    assert.equal(matches.value.length, 0)
  } finally { scope.stop() }
})

test('local query changes debounce and pending work is cancelled when the view is disposed', async () => {
  const scope = vue.effectScope(), cleanup = []
  try {
    const { useDebouncedLibraryQuery } = libraryHelpers(cleanup)
    const query = vue.ref('')
    const debounced = scope.run(() => useDebouncedLibraryQuery(query))
    query.value = ' New SONG '
    await vue.nextTick()
    assert.equal(debounced.value, '')
    await new Promise(resolve => setTimeout(resolve, 210))
    assert.equal(debounced.value, 'new song')
    query.value = 'cancelled'
    await vue.nextTick()
    cleanup.forEach(callback => callback())
    await new Promise(resolve => setTimeout(resolve, 210))
    assert.equal(debounced.value, 'new song')
  } finally { scope.stop() }
})
