// Tests for visibleKnockoutAlert() — which knockout counts as on-screen.
//
// The bug this pins cost a flow that could not be left. A condition hides the element that carries
// it, and a knockout's condition is often on an ancestor rather than on the alert: a transpiled flow
// wraps each source screen in a conditional `<div>` and gives the alert inside it a condition that is
// always true. The wrapper gets `.hidden`, the alert does not, and a check that read only the alert's
// own class concluded the taxpayer was knocked out. Continue then refused to navigate and said
// nothing at all — every required field answered, no error message, no movement.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'

const MODULE = '../src/main/resources/form-builder/website-static/flow-runtime/js/fg-validation.js'

let nonce = 0

/**
 * The two DOM calls the function makes, over a list of stand-in alerts.
 *
 * Each alert is `{ hidden, hiddenAncestor }`: whether it carries `.hidden` itself, and whether
 * anything above it does. `closest('.hidden')` answers the second and, per the DOM spec, also matches
 * the element itself — which is why a self-hidden alert reports a hidden ancestor too.
 */
async function load (alerts) {
  globalThis.document = {
    querySelectorAll (selector) {
      assert.equal(selector, 'fg-alert[knockout="true"]:not(.hidden)')
      return alerts
        .filter((a) => !a.hidden)
        .map((a) => ({
          ...a,
          closest (selector) {
            assert.equal(selector, '.hidden')
            return a.hidden || a.hiddenAncestor ? {} : null
          },
        }))
    },
  }
  return import(`${MODULE}?t=${++nonce}`)
}

beforeEach(() => {
  delete globalThis.document
})

test('a knockout inside a hidden wrapper does not count as visible', async () => {
  const { visibleKnockoutAlert } = await load([{ id: 'age-ko', hidden: false, hiddenAncestor: true }])
  assert.equal(visibleKnockoutAlert(), null)
})

test('a knockout hidden in its own right does not count as visible', async () => {
  const { visibleKnockoutAlert } = await load([{ id: 'age-ko', hidden: true, hiddenAncestor: true }])
  assert.equal(visibleKnockoutAlert(), null)
})

test('a knockout with nothing hidden above it is the one returned', async () => {
  const { visibleKnockoutAlert } = await load([{ id: 'age-ko', hidden: false, hiddenAncestor: false }])
  assert.equal(visibleKnockoutAlert().id, 'age-ko')
})

test('a visible knockout is found past hidden ones, whichever order they sit in', async () => {
  const { visibleKnockoutAlert } = await load([
    { id: 'wrapped', hidden: false, hiddenAncestor: true },
    { id: 'real', hidden: false, hiddenAncestor: false },
  ])
  assert.equal(visibleKnockoutAlert().id, 'real')
})

test('no alerts at all is not a knockout', async () => {
  const { visibleKnockoutAlert } = await load([])
  assert.equal(visibleKnockoutAlert(), null)
})
