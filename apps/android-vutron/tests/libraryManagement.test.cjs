const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), ts = require('typescript'), vue = require('vue');
function load(relative, dependencies) {
  const filename = path.join(__dirname, '../src', relative), module = { exports: {} };
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  vm.runInNewContext(code, { module, exports: module.exports, require: name => name === 'vue' ? vue : dependencies[name], setTimeout, clearTimeout, Date, Error }); return module.exports;
}
function management(plugin, ask = async () => 'confirm', extra = {}) {
  const calls = [], busy = vue.ref(false), store = { error: '', queueNotice: '', async reloadManagedLibrary() { calls.push('reload'); }, async loadLocalMusic() { calls.push('scan'); }, async loadCacheStatus() { calls.push('cache'); }, ...extra };
  const api = load('composables/libraryManagement.ts', { '../stores/androidShell': { useAndroidShellStore: () => store }, '../platform/vutronPlugin': { vutronPlugin: plugin }, '../platform/i18n': { translate: key => key }, '../platform/actionDialog': { askAction: ask }, '../platform/libraryActivity': { libraryMutationBusy: busy } }).useLibraryManagement();
  return { api, calls, busy, store };
}
test('deleting a cache in use informs the user and never stops playback', async () => {
  const notices = [], state = management({ deleteCachedTrack: async () => ({ inUse: true }) }, async question => { notices.push(question); return 'confirm'; });
  assert.equal(await state.api.deleteCachedTrack({ id: 12, name: 'Playing' }), false);
  assert.equal(notices.length, 1); assert.match(notices[0].message, /cacheInUse/); assert.deepEqual(state.calls, []); assert.equal(state.busy.value, false);
});
test('an unrelated local numeric ID cannot delete an online cache with the same ID', async () => {
  let deleted = false; const state = management({ deleteCachedTrack: async () => { deleted = true; return {}; } });
  assert.equal(await state.api.deleteCachedTrack({ id: 12, name: 'Local', localUri: 'content://music/a' }), false); assert.equal(deleted, false);
  assert.equal(await state.api.deleteCachedTrack({ id: 12, name: 'Cache', localUri: 'file:///cache/a', cached: true }), true); assert.equal(deleted, true); assert.deepEqual(state.calls, ['cache']);
});
test('local removal requires confirmation and refreshes library and queue together', async () => {
  let payload; const state = management({ manageLocalLibrary: async value => { payload = value; return { changed: true }; } });
  const track = { id: 3, name: 'Local', localUri: 'content://music/a', localFolderId: 'folder:a' };
  assert.equal(await state.api.removeLocalTrack(track), true); assert.equal(payload.action, 'removeTrack'); assert.equal(payload.stopCurrent, true); assert.deepEqual(state.calls, ['reload']); assert.equal(state.busy.value, false);
});
test('cancelled removal does not call native mutation and original failure stays visible', async () => {
  let changed = false; const cancelled = management({ manageLocalLibrary: async () => { changed = true; } }, async () => 'cancel');
  assert.equal(await cancelled.api.removeCollection({ id: 'folder:a' }, 'forgetFolder'), false); assert.equal(changed, false);
  const failure = management({ manageLocalLibrary: async () => { throw new Error('native diagnostic'); } }, undefined, { reloadManagedLibrary: async () => { throw new Error('secondary refresh failure'); } });
  assert.equal(await failure.api.removeCollection({ id: 'folder:a' }, 'forgetFolder'), false); assert.equal(failure.store.error, 'native diagnostic'); assert.equal(failure.busy.value, false);
});
function library(plugin, choice = 'confirm', online = false) {
  const questions = [], opened = [], notices = [], error = vue.ref('');
  const options = { error, onlineAvailable: vue.ref(online), formatError: cause => cause.message, openCollection: async row => opened.push(row), notice: text => notices.push(text) };
  const api = load('stores/domains/localLibrary.ts', { '../../platform/vutronPlugin': { vutronPlugin: { getLocalLibrary: async () => ({ folders: [], playlists: [] }), ...plugin } }, '../../platform/actionDialog': { askAction: async question => { questions.push(question); return choice; } }, '../../platform/i18n': { translate: key => key } }).createLocalLibrary(options);
  return { api, questions, opened, notices, error };
}
test('audio ZIP defaults to offline import and always releases its staged token', async () => {
  const completed = [], discarded = [];
  const state = library({ importLocalPlaylist: async () => ({ needsMode: true, importToken: 'token', canMatchOnline: false }), completeLocalPlaylistImport: async value => { completed.push(value); return { playlist: { id: 'bundle:a', trackCount: 2 } }; }, discardLocalPlaylistImport: async value => discarded.push(value) });
  await state.api.importLocalPlaylist(); assert.equal(state.questions[0].secondaryDisabled, true); assert.equal(completed[0].matchOnline, false); assert.equal(discarded[0].importToken, 'token'); assert.equal(state.opened.length, 1); assert.equal(state.api.libraryLoading.value, false);
});
test('cancelling ZIP selection cleans staging and cancelled matching retains imported audio', async () => {
  let discarded = 0, completed = 0;
  const cancelled = library({ importLocalPlaylist: async () => ({ needsMode: true, importToken: 'a' }), completeLocalPlaylistImport: async () => { completed++; }, discardLocalPlaylistImport: async () => discarded++ }, 'cancel');
  await cancelled.api.importLocalPlaylist(); assert.equal(discarded, 1); assert.equal(completed, 0); assert.equal(cancelled.opened.length, 0);
  const matching = library({ importLocalPlaylist: async () => ({ playlist: { id: 'bundle:a', trackCount: 2 }, matchingCancelled: true }) });
  await matching.api.importLocalPlaylist(); assert.equal(matching.opened.length, 1); assert.match(matching.notices[0], /matchingStopped/);
});
test('cancelled scan preserves the complete prior index and manual refresh does not rescan', async () => {
  let scans = 0; const state = library({ scanLocalMusic: async () => { scans++; return { cancelled: true, tracks: [] }; }, getLocalMusicIndex: async () => ({ tracks: [{ id: 1 }], indexed: true, updatedAt: 0 }), getLocalLibrary: async () => ({ folders: [{ id: 'folder:a', name: 'Music' }], playlists: [] }) });
  state.api.localTracks.value = [{ id: 9, name: 'Previous', localUri: 'content://previous' }];
  await state.api.loadLocalMusic(true); assert.equal(state.api.localTracks.value[0].id, 9); assert.equal(state.api.localScanLoading.value, false);
  await state.api.loadLocalMusic(false, false); assert.equal(scans, 1); assert.equal(state.api.localTracks.value[0].id, 1);
});
