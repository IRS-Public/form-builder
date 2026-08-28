// Tests for input-types.js — the registry that lets an application add an `<fg-set>` input type
// without editing the runtime.
//
// What is worth pinning here is the ordering guarantee, because it is the part that is easy to get
// wrong and impossible to notice: ESM hoists imports, so an application's registration usually
// lands *after* flow-runtime.js has evaluated and `<fg-set>` has upgraded. If a late registration
// did not re-wire the elements already on the page, an input meant to commit on every keystroke
// would quietly commit only on blur — working, but wrong, and only on the app that needed it.
//
// Each case re-imports the module with a cache-busting query so it gets a fresh registry; the Map
// is module state by design.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'

const MODULE = '../src/main/resources/form-builder/website-static/flow-runtime/js/input-types.js'

let nonce = 0
/** A fresh copy of the module, with `elements` standing in for the `<fg-set>`s on the page. */
async function load (elements = []) {
  globalThis.document = {
    querySelectorAll (selector) {
      assert.equal(selector, 'fg-set')
      return elements
    },
  }
  return import(`${MODULE}?t=${++nonce}`)
}

const handlers = () => ({ read: () => 'x', write: () => {}, clear: () => {} })

beforeEach(() => {
  delete globalThis.document
})

test('a registered type is returned by name, and an unregistered one is undefined', async () => {
  const { registerInputType, getInputType } = await load()
  const tin = handlers()

  registerInputType('tin', tin)

  assert.equal(getInputType('tin'), tin)
  assert.equal(getInputType('ein'), undefined)
})

test('each of read, write and clear is required', async () => {
  const { registerInputType } = await load()
  const { read, write, clear } = handlers()

  assert.throws(() => registerInputType('tin', { write, clear }), /needs a read\(\) function/)
  assert.throws(() => registerInputType('tin', { read, clear }), /needs a write\(\) function/)
  assert.throws(() => registerInputType('tin', { read, write }), /needs a clear\(\) function/)
  assert.throws(() => registerInputType('tin', undefined), TypeError)
})

test('attach is optional — a type that omits it inherits the blur/Tab default', async () => {
  const { registerInputType, getInputType } = await load()

  registerInputType('tin', handlers())

  assert.equal(getInputType('tin').attach, undefined)
})

test('a late registration re-wires the fg-sets already on the page', async () => {
  // The case ESM's import hoisting makes the normal one: flow-runtime.js has evaluated, <fg-set>
  // has upgraded, and only then does the app's registration arrive.
  let reattached = 0
  const matching = { inputType: 'tin', reattachInputListeners: () => { reattached += 1 } }
  const otherType = { inputType: 'ein', reattachInputListeners: () => { reattached += 100 } }

  const { registerInputType } = await load([matching, otherType])
  registerInputType('tin', handlers())

  assert.equal(reattached, 1, 'only the fg-sets of the registered type are re-wired')
})

test('a registration before any fg-set has upgraded re-wires nothing, and does not throw', async () => {
  // The other order: an app that registers from a module imported ahead of the runtime. The
  // elements exist in the DOM but are not upgraded, so they carry no reattachInputListeners.
  const notYetUpgraded = { inputType: 'tin' }

  const { registerInputType, getInputType } = await load([notYetUpgraded])
  registerInputType('tin', handlers())

  assert.ok(getInputType('tin'), 'the registration still lands')
})

test('no document at all is not an error — a bundler or a test may import this', async () => {
  delete globalThis.document
  const { registerInputType, getInputType } = await import(`${MODULE}?t=nodoc`)

  registerInputType('tin', handlers())

  assert.ok(getInputType('tin'))
})
