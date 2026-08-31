// What happens when Continue is pressed.
//
// `window.handleSectionContinue` is wired into the generated Continue link as
// `onclick="return handleSectionContinue(event)"`, so it must exist in every application. Anything
// app-specific is added through registerContinueHandler().
//
// See docs/internals/flow-runtime.md.

import { validateSectionForNavigation, focusKnockoutAlert, visibleKnockoutAlert } from './fg-validation.js'
import { factGraph, saveFactGraph } from './fg-fact-graph.js'

const handlers = []

/**
 * Add an application handler to the Continue chain. Handlers run in registration order, after
 * validation passes. Returning `true` claims the event and stops both navigation and later
 * handlers. Anything falsy passes the event along.
 *
 * @param {(event: Event) => boolean} handler
 */
export function registerContinueHandler (handler) {
  if (typeof handler === 'function') handlers.push(handler)
}

/** A test seam. */
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
 * Build a "reveal the knockout instead of navigating, once" handler. The first Continue reveals the
 * alert and stays put; the second is blocked by validateSectionForNavigation() from then on.
 *
 * @param {object} spec
 * @param {string} spec.route          substring of `location.pathname` this applies to
 * @param {string} spec.gatePath       fact path deciding whether the knockout applies
 * @param {string} spec.clickedPath    boolean fact path recording that Continue was pressed once
 * @param {(value: unknown) => boolean} [spec.revealWhen] how to read `gatePath`, defaulting to
 *   "the gate is true". Pass your own for a gate phrased the other way round.
 * @returns {(event: Event) => boolean} a handler for registerContinueHandler()
 */
export function revealOnContinue ({ route, gatePath, clickedPath, revealWhen = (v) => v === true }) {
  return function revealKnockoutOnce (event) {
    if (!window.location.pathname.includes(route)) return false

    // A throw would take the whole Continue chain down, so a read failure means "do not reveal".
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

    const knockoutAlert = visibleKnockoutAlert()
    if (knockoutAlert) focusKnockoutAlert(knockoutAlert)

    event.preventDefault()
    return true
  }
}

window.handleSectionContinue = handleSectionContinue
