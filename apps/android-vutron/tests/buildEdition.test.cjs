const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { resolveBuildInfo } = require('../../../scripts/mobile-build.cjs')

function repository(run) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vutron-edition-'))
  const app = path.join(root, 'apps/android-vutron')
  fs.mkdirSync(path.join(app, '_private'), { recursive: true })
  const write = (name, value) => fs.writeFileSync(path.join(app, name), JSON.stringify(value))
  write('package.json', { version: '1.0.1' })
  write('mobile-release.json', { displayName: 'Vutron Music - Mobile', versionCode: 18, artifactPrefix: 'Vutron-Music-Mobile' })
  const personal = { edition: 'personal', displayName: 'Internal example', requiredDisplayName: 'Internal example', version: '0.3.11', versionCode: 17, comparisonVersion: '1.0.1', artifactPrefix: 'Example-Personal' }
  write('_private/build-personal.json', personal)
  try { return run(root, app, write, personal) }
  finally { fs.rmSync(root, { recursive: true, force: true }) }
}

test('local default selects the private sequence while an explicit official build stays 1.x', () => repository(root => {
  const personal = resolveBuildInfo(root, 'auto'), mobile = resolveBuildInfo(root, 'mobile')
  assert.equal(personal.version, '0.3.11')
  assert.equal(personal.displayName, 'Internal example')
  assert.equal(personal.comparisonVersion, mobile.version)
  assert.equal(mobile.displayName, 'Vutron Music - Mobile')
  assert.equal(mobile.version, '1.0.1')
  assert.ok(mobile.versionCode > personal.versionCode)
}))

test('private builds reject a 1.x version, wrong title, stale baseline and unsafe versionCode', () => repository((root, app, write, personal) => {
  for (const change of [{ version: '1.0.1' }, { displayName: 'Vutron Music - Mobile' }, { comparisonVersion: '1.0.0' }, { versionCode: 0 }, { versionCode: 17, previousVersionCode: 17 }, { artifactPrefix: '../escape' }]) {
    write('_private/build-personal.json', { ...personal, ...change })
    assert.throws(() => resolveBuildInfo(root, 'personal'))
  }
}))

test('a workspace with private rules cannot silently fall back to official branding when its configuration is missing', () => repository((root, app) => {
  fs.unlinkSync(path.join(app, '_private/build-personal.json'))
  fs.writeFileSync(path.join(app, '_private/BUILD_RULES.md'), 'Local rules')
  assert.throws(() => resolveBuildInfo(root, 'auto'), /configuration is missing/)
  assert.throws(() => resolveBuildInfo(root, 'personal'), /require/)
}))

test('a private build can advance without changing public metadata while a colliding public build is blocked', () => repository((root, app, write, personal) => {
  const before = fs.readFileSync(path.join(app, 'mobile-release.json'), 'utf8')
  write('_private/build-personal.json', { ...personal, version: '0.3.14', versionCode: 18, previousVersionCode: 17 })
  assert.equal(resolveBuildInfo(root, 'personal').versionCode, 18)
  assert.equal(fs.readFileSync(path.join(app, 'mobile-release.json'), 'utf8'), before)
  assert.throws(() => resolveBuildInfo(root, 'mobile'), /above the latest local build/)
}))

test('public clones use only official identity and reject a 0.x official version', () => repository((root, app, write) => {
  fs.unlinkSync(path.join(app, '_private/build-personal.json'))
  assert.equal(resolveBuildInfo(root, 'auto').edition, 'mobile')
  write('package.json', { version: '0.3.11' })
  assert.throws(() => resolveBuildInfo(root, 'mobile'), /1.x/)
}))
