const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const vueI18n = require('vue-i18n')

function loadMobileI18n({ storage = new Map(), locale } = {}) {
  if (locale) storage.set('vutron:language', locale)
  const filename = path.resolve(__dirname, '../../src/platform/i18n.ts')
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText
  const module = { exports: {} }, attributes = new Map()
  vm.runInNewContext(code, {
    module, exports: module.exports,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    document: { documentElement: { setAttribute: (key, value) => attributes.set(key, value) } },
    require(name) {
      if (name === 'vue') return vue
      if (name === 'vue-i18n') return vueI18n
      if (name.endsWith('.json')) return JSON.parse(fs.readFileSync(path.resolve(path.dirname(filename), name), 'utf8'))
      throw new Error('Unexpected i18n dependency: ' + name)
    }
  }, { filename })
  return { ...module.exports, storage, attributes }
}
module.exports = { loadMobileI18n }
