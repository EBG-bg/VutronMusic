const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript'), vue = require('vue')
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const own = { id: 42, name: 'Own', creatorUserId: 7, tags: ['流行'] }, other = { id: 99, name: 'Other', creatorUserId: 8 }
const song = { id: 2, name: 'Song', playlistVolumeKey: 'netease:42' }
const snapshot = (extra = {}) => ({ userId: 7, playlists: [own, other], likedIds: [], playlist: own, tracks: [song], ...extra })
function setup(overrides = {}, ask = async () => 'confirm') {
  const plugin = { getLikedTrackIds: async () => ({ userId: 7, likedIds: [2] }), getOnlineLibrary: async () => snapshot(), manageNeteaseLibrary: async () => snapshot(), ...overrides }
  const filename = path.join(__dirname, '../src/stores/domains/neteaseLibrary.ts'), module = { exports: {} }, applied = [], notices = [], questions = []
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const dependencies = { '../../platform/vutronPlugin': { vutronPlugin: plugin }, '../../platform/actionDialog': { askAction: q => { questions.push(q); return ask(q) } }, '../../platform/i18n': { translate: key => key } }
  vm.runInNewContext(code, { module, exports: module.exports, require: name => name === 'vue' ? vue : dependencies[name], Error, Date, Set, Number })
  const profile = vue.ref({ userId: 7, nickname: 'User' }), onlineAvailable = vue.ref(true), playlists = vue.ref([own, other]), selectedPlaylist = vue.ref(own), tracks = vue.ref([song]), error = vue.ref('')
  const scope = vue.effectScope(), api = scope.run(() => module.exports.createNeteaseLibrary({ profile, onlineAvailable, playlists, selectedPlaylist, tracks, error,
    invalidateReads: () => {}, applySnapshot: value => { applied.push(value); playlists.value = value.playlists; if (value.deletedPlaylistId === selectedPlaylist.value?.id) selectedPlaylist.value = undefined }, notice: value => notices.push(value), formatError: value => value.message }))
  return { api, scope, profile, onlineAvailable, playlists, selectedPlaylist, tracks, error, applied, notices, questions, onlineTrackId: module.exports.onlineTrackId }
}
test('online identity and own-playlist permissions do not infer ownership or local IDs', () => {
  const s = setup(); try {
    assert.equal(s.api.canManageOnlinePlaylist(own), true); assert.equal(s.api.canManageOnlinePlaylist(other), false)
    assert.deepEqual(Array.from(s.api.ownedPlaylists.value, row => row.id), [42])
    assert.equal(s.onlineTrackId({ id: 2, localUri: 'content://original' }), 0)
    assert.equal(s.onlineTrackId({ id: 2, localUri: 'file:///cache', cached: true }), 2)
    assert.equal(s.api.removableOnlinePlaylist({ ...song, playlistVolumeKey: 'netease:99' }), undefined)
    assert.equal(s.api.removableOnlinePlaylist({ ...song, id: 3 }), undefined)
    assert.equal(s.api.removableOnlinePlaylist(song).id, 42)
  } finally { s.scope.stop() }
})
test('liked status is unknown until likelist returns and ignores a song/detail liked flag', async () => {
  const s = setup({ getLikedTrackIds: async () => ({ userId: 7, likedIds: [9] }) }); try {
    assert.equal(s.api.likedLoaded.value, false); await s.api.loadLikedTracks()
    assert.equal(s.api.isTrackLiked({ id: 2, name: 'Fake detail', liked: true }), false)
    assert.equal(s.api.isTrackLiked({ id: 9, name: 'Server liked' }), true)
  } finally { s.scope.stop() }
})
test('duplicate like clicks send one write and never optimistically change state', async () => {
  const response = deferred(), writes = [], s = setup({ manageNeteaseLibrary: request => { writes.push(request); return response.promise } }); try {
    await s.api.loadLikedTracks(); const first = s.api.toggleTrackLike(song)
    assert.equal(await s.api.toggleTrackLike(song), false); assert.equal(writes.length, 1); assert.equal(writes[0].liked, false)
    assert.equal(s.api.isTrackLiked(song), true); response.resolve(snapshot()); assert.equal(await first, true)
    assert.equal(s.api.isTrackLiked(song), false); assert.equal(s.applied.length, 1)
  } finally { s.scope.stop() }
})
test('failed writes preserve old data and block resubmission until explicit sync', async () => {
  let writes = 0; const s = setup({ manageNeteaseLibrary: async () => { writes++; throw new Error('unconfirmed write') } }); try {
    await s.api.loadLikedTracks(); const before = JSON.stringify(s.playlists.value)
    assert.equal(await s.api.toggleTrackLike(song), false); assert.equal(s.api.isTrackLiked(song), true); assert.equal(JSON.stringify(s.playlists.value), before)
    await s.api.toggleTrackLike(song); assert.equal(writes, 1); assert.equal(s.api.onlineNeedsRefresh.value, true)
    assert.equal(await s.api.refreshOnlineLibrary(), true); assert.equal(s.api.onlineNeedsRefresh.value, false)
  } finally { s.scope.stop() }
})
test('account switches reject late likelist and mutation responses', async () => {
  const read = deferred(), s = setup({ getLikedTrackIds: () => read.promise }); try {
    const pending = s.api.loadLikedTracks(); s.profile.value = { userId: 8, nickname: 'New' }
    read.resolve({ userId: 7, likedIds: [2] }); assert.equal(await pending, false); assert.equal(s.api.likedLoaded.value, false)
    assert.deepEqual(Array.from(s.api.likedTrackIds.value), [])
  } finally { s.scope.stop() }
  const write = deferred(), second = setup({ manageNeteaseLibrary: () => write.promise }); try {
    await second.api.loadLikedTracks(); const pending = second.api.toggleTrackLike(song); second.onlineAvailable.value = false
    write.resolve(snapshot()); assert.equal(await pending, false); assert.equal(second.applied.length, 0); assert.equal(second.api.onlineMutationBusy.value, false)
  } finally { second.scope.stop() }
})
test('an older liked read cannot overwrite a newer mutation snapshot', async () => {
  const read = deferred(), s = setup({ getLikedTrackIds: () => read.promise }); try {
    s.api.likedLoaded.value = true; s.api.likedTrackIds.value = [2]
    const pending = s.api.loadLikedTracks(true); await s.api.toggleTrackLike(song)
    read.resolve({ userId: 7, likedIds: [2] }); assert.equal(await pending, false); assert.equal(s.api.isTrackLiked(song), false)
  } finally { s.scope.stop() }
})
test('delete confirmation cannot queue twice or act after the account changes', async () => {
  const answer = deferred(); let writes = 0; const s = setup({ manageNeteaseLibrary: async () => { writes++; return snapshot() } }, () => answer.promise); try {
    const first = s.api.deleteOnlinePlaylist(own); assert.equal(await s.api.deleteOnlinePlaylist(own), false); assert.equal(s.questions.length, 1)
    s.profile.value = { userId: 8, nickname: 'New' }; answer.resolve('confirm'); assert.equal(await first, false); assert.equal(writes, 0)
  } finally { s.scope.stop() }
})
test('local-only mode hides dialogs and rejects writes without invoking native networking', async () => {
  let calls = 0; const s = setup({ manageNeteaseLibrary: async () => { calls++; return snapshot() }, getLikedTrackIds: async () => { calls++; return { userId: 7, likedIds: [] } } }); try {
    s.onlineAvailable.value = false; s.api.openCreateOnlinePlaylist(); s.api.openAddToOnlinePlaylist(song); s.api.openOnlinePlaylistMore(own)
    assert.equal(s.api.onlinePlaylistDialog.value, undefined); assert.equal(await s.api.toggleTrackLike(song), false); assert.equal(await s.api.loadLikedTracks(), false); assert.equal(calls, 0)
  } finally { s.scope.stop() }
})
test('committed create with failed refresh is not submitted twice and closes after synchronization', async () => {
  let writes = 0; const s = setup({ manageNeteaseLibrary: async () => { writes++; return { userId: 7, committed: true, createdPlaylistId: 50, refreshFailed: true, message: 'sync required' } } }); try {
    s.api.openCreateOnlinePlaylist(); const form = { name: 'New', description: '', tags: [], privacy: 10 }
    assert.equal(await s.api.saveOnlinePlaylist(form), false); assert.equal(s.api.onlinePlaylistDialog.value.kind, 'create')
    await s.api.saveOnlinePlaylist(form); assert.equal(writes, 1); assert.equal(s.applied.length, 0)
    await s.api.refreshOnlineLibrary(); assert.equal(s.api.onlinePlaylistDialog.value, undefined)
  } finally { s.scope.stop() }
})
test('uncertain deletion resyncs the list first instead of repeatedly requesting a deleted detail', async () => {
  const reads = []; const s = setup({ manageNeteaseLibrary: async () => { throw new Error('connection lost after delete') }, getOnlineLibrary: async options => { reads.push(options); return snapshot({ playlists: [], playlist: undefined, tracks: undefined }) } }); try {
    await s.api.deleteOnlinePlaylist(own); assert.equal(s.api.onlineNeedsRefresh.value, true)
    assert.equal(await s.api.refreshOnlineLibrary(), true); assert.equal(reads[0].playlistId, undefined); assert.equal(s.selectedPlaylist.value, undefined)
  } finally { s.scope.stop() }
})
