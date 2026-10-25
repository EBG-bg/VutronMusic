const fs = require('node:fs')
const path = require('node:path')

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''))
}

function requireBuild(condition, message) { if (!condition) throw new Error(message) }

function resolveBuildInfo(repository, requested = process.env.VUTRON_BUILD_EDITION || 'auto') {
  const app = path.join(repository, 'apps/android-vutron')
  const pkg = readJson(path.join(app, 'package.json'))
  const mobile = readJson(path.join(app, 'mobile-release.json'))
  const personalPath = path.join(app, '_private/build-personal.json')
  const privateRules = path.join(app, '_private/BUILD_RULES.md')
  const hasPersonal = fs.existsSync(personalPath)
  requireBuild(['auto', 'mobile', 'personal'].includes(requested), 'Unknown build edition')
  if (requested === 'auto' && fs.existsSync(privateRules) && !hasPersonal) throw new Error('Private build rules exist but the personal configuration is missing')
  const edition = requested === 'auto' ? (hasPersonal ? 'personal' : 'mobile') : requested
  requireBuild(/^1[.][0-9]+[.][0-9]+$/.test(pkg.version), 'Official version must use the 1.x release series')
  requireBuild(mobile.displayName === 'Vutron Music - Mobile', 'Official display name is fixed')
  requireBuild(Number.isSafeInteger(mobile.versionCode) && mobile.versionCode > 0, 'Official versionCode must be a positive integer')
  let selected = { ...mobile, version: pkg.version, comparisonVersion: pkg.version }
  if (edition === 'personal') {
    requireBuild(hasPersonal, 'Personal builds require the ignored private configuration')
    selected = readJson(personalPath)
    requireBuild(selected.edition === 'personal' && /^0[.]3[.][0-9]+$/.test(selected.version), 'Personal builds must continue the 0.3.x series, never a 1.x version')
    requireBuild(selected.displayName && selected.displayName === selected.requiredDisplayName && selected.displayName !== mobile.displayName, 'Personal display name must match its private branding rule')
    requireBuild(selected.comparisonVersion === pkg.version, 'Personal comparison baseline must match the current official feature version')
    const previous = selected.previousVersionCode ?? 0
    requireBuild(Number.isSafeInteger(previous) && previous >= 0 && Number.isSafeInteger(selected.versionCode) && selected.versionCode > previous, 'Personal versionCode must exceed its recorded previous build')
  } else if (hasPersonal) {
    requireBuild(mobile.versionCode > readJson(personalPath).versionCode, 'A public build must reserve a versionCode above the latest local build; update public metadata only when explicitly releasing')
  }
  requireBuild(/^[A-Za-z0-9._-]+$/.test(selected.artifactPrefix), 'Invalid artifact filename prefix')
  return {
    edition, displayName: selected.displayName, version: selected.version,
    versionCode: selected.versionCode, comparisonVersion: selected.comparisonVersion,
    artifactName: selected.artifactPrefix + '-' + selected.version + (edition === 'personal' ? '-Android13.apk' : '-arm64.apk')
  }
}

module.exports = { resolveBuildInfo }
if (require.main === module) {
  try { process.stdout.write(JSON.stringify(resolveBuildInfo(path.resolve(__dirname, '..'), process.argv[2] || 'auto')) + '\n') }
  catch (error) { process.stderr.write(error.message + '\n'); process.exitCode = 1 }
}
