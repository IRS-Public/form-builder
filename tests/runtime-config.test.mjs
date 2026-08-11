// Tests for runtime-config.js — the flow runtime's whole configuration surface.
//
// Two things here are worth pinning rather than trusting. The first is the defect this module exists
// to fix: a build without the workspace never called configure() at all, so `storagePrefix` silently
// stayed 'taxpert' and two such apps on one origin shared `taxpert:factGraph` in sessionStorage.
// There was no test for that, in either package. The second is layering — the server's <meta> values
// must lose to an explicit configureRuntime() call, whichever order they arrive in.
//
// Each case re-imports the module with a cache-busting query so it gets a fresh module scope; the
// config object is module state by design (its identity has to be stable for callers that read it
// late), which means a spec cannot share one.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'

const MODULE = '../src/main/resources/formative/website-static/flow-runtime/js/runtime-config.js'

let nonce = 0
/** A fresh copy of the module, with `metas` standing in for what fragments/head.html rendered. */
async function load (metas = null) {
  if (metas === null) {
    delete globalThis.document
  } else {
    // A Map rather than an object literal, so looking a name up is not a computed member access on
    // caller-supplied data — the pattern the security lint flags.
    const byName = new Map(Object.entries(metas))
    globalThis.document = {
      querySelector (selector) {
        const name = selector.match(/meta\[name="(.+)"\]/)?.[1]
        return byName.has(name) ? { content: byName.get(name) } : null
      },
    }
  }
  return import(`${MODULE}?t=${++nonce}`)
}

beforeEach(() => {
  delete globalThis.document
})

test('an unconfigured runtime still produces a usable, prefixed key', async () => {
  const { storageKey } = await load()
  assert.equal(storageKey('factGraph'), 'taxpert:factGraph')
})

test('the server\'s storage prefix reaches the runtime with nothing else configured', async () => {
  // The defect: this is the workspace-less build, where configure() was never called at all.
  const { storageKey } = await load({ 'formative:storage-prefix': 'eitc' })
  assert.equal(storageKey('factGraph'), 'eitc:factGraph')
})

test('two apps on one origin do not collide', async () => {
  // Read each key while that app's own document is the installed one: the seed is lazy, so a
  // deferred read would see whichever document was swapped in last. In a browser the question does
  // not arise — each app is its own page — but it makes the order here load-bearing.
  const eitcKey = (await load({ 'formative:storage-prefix': 'eitc' })).storageKey('factGraph')
  const tweKey = (await load({ 'formative:storage-prefix': 'twe' })).storageKey('factGraph')

  assert.equal(eitcKey, 'eitc:factGraph')
  assert.equal(tweKey, 'twe:factGraph')
  assert.notEqual(eitcKey, tweKey)
})

test('the server\'s base path is readable, and is what hardens URL derivation', async () => {
  const { getRuntimeConfig } = await load({ 'formative:base-path': '/app/tax-withholding-estimator' })
  assert.equal(getRuntimeConfig().endpoints.basePath, '/app/tax-withholding-estimator')
})

test('an empty meta leaves the default alone rather than blanking it', async () => {
  // A root-mounted app renders an empty basePath; that must not clobber anything.
  const { getRuntimeConfig, storageKey } = await load({
    'formative:base-path': '',
    'formative:storage-prefix': '',
  })
  assert.equal(getRuntimeConfig().endpoints.basePath, '')
  assert.equal(storageKey('factGraph'), 'taxpert:factGraph')
})

test('configureRuntime beats the server, whichever is touched first', async () => {
  const first = await load({ 'formative:storage-prefix': 'from-meta' })
  first.configureRuntime({ app: { storagePrefix: 'from-code' } })
  assert.equal(first.storageKey('factGraph'), 'from-code:factGraph')

  // And with the seed already triggered by an earlier read, rather than by configureRuntime itself.
  const second = await load({ 'formative:storage-prefix': 'from-meta' })
  assert.equal(second.storageKey('factGraph'), 'from-meta:factGraph')
  second.configureRuntime({ app: { storagePrefix: 'from-code' } })
  assert.equal(second.storageKey('factGraph'), 'from-code:factGraph')
})

test('the config object keeps one identity, so a late reader is never stale', async () => {
  const { getRuntimeConfig, configureRuntime } = await load()
  const captured = getRuntimeConfig()
  configureRuntime({ endpoints: { basePath: '/app/later' } })
  assert.equal(captured.endpoints.basePath, '/app/later')
  assert.equal(captured, getRuntimeConfig())
})

test('keys and namespaces the runtime does not read are ignored, not written', async () => {
  const { getRuntimeConfig, configureRuntime } = await load()
  configureRuntime({
    endpoints: { notAThing: 'x' },
    nav: { menu: ['a'] }, // a workspace concept; this module has no business holding it
  })
  const config = getRuntimeConfig()
  assert.equal(config.endpoints.notAThing, undefined)
  assert.equal(config.nav, undefined)
})

test('a prototype-polluting key is ignored', async () => {
  const { getRuntimeConfig, configureRuntime } = await load()
  configureRuntime({ __proto__: { polluted: 'yes' }, app: { constructor: 'no' } })
  assert.equal(getRuntimeConfig().polluted, undefined)
  assert.equal({}.polluted, undefined)
})

test('non-string and absent values are skipped', async () => {
  const { getRuntimeConfig, configureRuntime } = await load()
  configureRuntime({ app: { storagePrefix: 42 } })
  assert.equal(getRuntimeConfig().app.storagePrefix, 'taxpert')
  configureRuntime(null)
  assert.equal(getRuntimeConfig().app.storagePrefix, 'taxpert')
})

test('no document at all is not an error — a bundler or a test may import this', async () => {
  const { getRuntimeConfig } = await load()
  assert.equal(getRuntimeConfig().app.storagePrefix, 'taxpert')
})
