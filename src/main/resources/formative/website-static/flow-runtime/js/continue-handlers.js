// What happens when someone presses Continue — and where an application adds to it.
//
// `window.handleSectionContinue` is wired straight into the generated page's Continue link
// (`onclick="return handleSectionContinue(event)"`), so it has to exist in every application. Most
// of what it does is neutral: validate the visible questions, and refuse to navigate past a visible
// knockout. That part ships here.
//
// The rest is not neutral at all. The version this replaced hard-coded four of one application's
// eligibility gates — twelve of its fact paths and four of its routes, inline. Those are exactly
// the thing this package must not know (see tests/no-host-identity.test.mjs), so an application now
// *registers* them and this file stays empty of them.
//
// A host with no handlers registered still gets validation, which is what a new application wants
// on day one.

import { validateSectionForNavigation, focusKnockoutAlert } from './fg-validation.js'
import { factGraph, saveFactGraph } from './fg-fact-graph.js'

const handlers = []

/**
 * Add an application handler to the Continue chain.
 *
 * Handlers run in registration order, after validation has passed. A handler returns `true` to
 * claim the event — it has done something instead of navigating, and no later handler runs.
 * Returning anything falsy passes the event along.
 *
 * @param {(event: Event) => boolean} handler
 */
export function registerContinueHandler (handler) {
  if (typeof handler === 'function') handlers.push(handler)
}

/** Drop every registered handler. A test seam, matching the other `_reset*` helpers. */
export function _resetContinueHandlers () {
  handlers.length = 0
}

/**
 * The Continue link's onclick. `false` blocks navigation.
 *
 * @param {Event} event
 * @returns {boolean}
 */
export function handleSectionContinue (event) {
  if (!validateSectionForNavigation()) {
    event.preventDefault()
    return false
  }
  for (const handler of handlers) {
    if (handler(event)) return false
  }
  return true
}

/**
 * Build a "reveal the knockout instead of navigating, once" handler.
 *
 * This is the shape every one of those four application handlers had, and the only reason they were
 * four functions rather than four rows of data: on the first Continue from a given page, while some
 * gate fact says the knockout applies, set a "they clicked next" fact, re-render, move focus to the
 * revealed alert, and stay put. On the second Continue the clicked fact is already true, so this
 * declines and `validateSectionForNavigation` — which refuses to pass a visible knockout — is what
 * blocks navigation from then on.
 *
 * @param {object} spec
 * @param {string} spec.route          substring of `location.pathname` this applies to
 * @param {string} spec.gatePath       fact path deciding whether the knockout applies
 * @param {string} spec.clickedPath    boolean fact path recording that Continue was pressed once
 * @param {(value: unknown) => boolean} [spec.revealWhen] how to read `gatePath`; defaults to
 *   "the gate is true". Pass your own for a gate phrased the other way round (a `below the limit`
 *   fact reveals when it is *false*).
 * @returns {(event: Event) => boolean} a handler for registerContinueHandler()
 */
export function revealOnContinue ({ route, gatePath, clickedPath, revealWhen = (v) => v === true }) {
  return function revealKnockoutOnce (event) {
    if (!window.location.pathname.includes(route)) return false

    // An unreadable fact is not the same as a false one, and neither reveals anything — but a
    // throw here would take the whole Continue chain down, so it is caught and logged like the
    // handlers this generalizes.
    let shouldReveal
    try {
      const gate = factGraph.get(gatePath)
      if (!gate.hasValue) return false
      shouldReveal = revealWhen(gate.get)
    } catch (e) {
      console.error(`Error reading ${gatePath} while handling Continue on ${route}`, e)
      return false
    }
    if (!shouldReveal) return false

    try {
      const clicked = factGraph.get(clickedPath)
      if (clicked.hasValue && clicked.get === true) return false
    } catch (e) {
      console.error(`Error reading ${clickedPath} while handling Continue on ${route}`, e)
      return false
    }

    factGraph.set(clickedPath, true)
    saveFactGraph()
    document.dispatchEvent(new CustomEvent('fg-update'))

    const knockoutAlert = document.querySelector('fg-alert[knockout="true"]:not(.hidden)')
    if (knockoutAlert) focusKnockoutAlert(knockoutAlert)

    event.preventDefault()
    return true
  }
}

window.handleSectionContinue = handleSectionContinue
