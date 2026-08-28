// The registry that lets an application teach `<fg-set>` an input type the runtime has never heard
// of — the browser half of the seam `FormBuilderApp.inputTypes` opens on the Scala side.
//
// Registering an input type is otherwise five edits to fg-set.js, one per `switch (this.inputType)`,
// and none of them is an application's to make. With this, an app registers once:
//
//     import { registerInputType } from '.../flow-runtime/js/input-types.js'
//
//     registerInputType('tin', {
//       read: (el) => el.querySelector('input').value.replaceAll('-', ''),
//       write: (el, value) => { el.querySelector('input').value = formatTin(value) },
//       clear: (el) => { el.querySelector('input').value = '' },
//     })
//
// and `<input type="tin">` in the Flow XML, an `InputParser` in Main.scala, and
// `templates/nodes/inputs/tin.html` complete the type.
//
// **Registration order does not matter.** ESM hoists imports, so an app cannot reliably register
// before flow-runtime.js evaluates and `fg-set` upgrades. read/write/clear are therefore looked up
// at call time rather than at connect time, and a registration that arrives late re-attaches the
// listeners of any `<fg-set>` already on the page (see attachLate below). An app that registers
// from a separate module imported ahead of the runtime gets the same result by a shorter route.
//
// See docs/internals/flow-runtime.md.

/** @type {Map<string, InputTypeHandlers>} */
const registry = new Map()

/**
 * @typedef {object} InputTypeHandlers
 * @property {(el: HTMLElement) => unknown} read
 *   Read the element's inputs and return the value to hand the Fact Graph. Return `''` or `null`
 *   for "the user has not answered", which is what makes `<fg-set>` delete the fact rather than set
 *   it to an empty value.
 * @property {(el: HTMLElement, value: string, fact: object) => void} write
 *   Put a fact value into the element's inputs. `value` is `''` when the fact is incomplete; `fact`
 *   is the raw Fact Graph result, for a type whose DOM needs more than the string form.
 * @property {(el: HTMLElement) => void} clear
 *   Return the element's inputs to empty. Called both by `fg-clear` and when a hidden `<fg-set>`
 *   has its fact deleted.
 * @property {((el: HTMLElement) => void)=} attach
 *   Optional. Wire whichever events should commit the answer, each calling `el.onChange()`. Omit it
 *   to inherit the default — commit on blur, and on Tab before focus moves.
 */

/**
 * Teach `<fg-set>` an input type.
 *
 * @param {string} name The `<input type>` from the Flow XML, as `InputParser` names it.
 * @param {InputTypeHandlers} handlers
 */
export function registerInputType (name, handlers) {
  // Named rather than looped over a list of keys: reading `handlers[key]` for a caller-supplied key
  // is the computed member access the security lint flags, and three lines is a cheap way to keep
  // the check literal.
  if (typeof handlers?.read !== 'function') {
    throw new TypeError(`registerInputType("${name}") needs a read() function`)
  }
  if (typeof handlers.write !== 'function') {
    throw new TypeError(`registerInputType("${name}") needs a write() function`)
  }
  if (typeof handlers.clear !== 'function') {
    throw new TypeError(`registerInputType("${name}") needs a clear() function`)
  }
  registry.set(name, handlers)
  attachLate(name)
}

/**
 * The handlers for an input type, or undefined if the type is not registered.
 *
 * @param {string} name
 * @returns {InputTypeHandlers | undefined}
 */
export function getInputType (name) {
  return registry.get(name)
}

/**
 * Re-wire any `<fg-set>` of this type that connected before the registration arrived.
 *
 * Without this, an app whose registration lands after flow-runtime.js — the ordering ESM's import
 * hoisting makes hard to avoid — would get its read/write/clear but keep the default blur/Tab
 * listeners, so an input meant to commit on every keystroke would commit only on blur. The elements
 * being re-attached are the ones that took the default, so removing it first is what stops the
 * listeners doubling up.
 */
function attachLate (name) {
  // A bundler or a test may import this module with no DOM at all, the same way runtime-config.js
  // tolerates a missing document. Registering without a page is not an error; there is simply
  // nothing on it to re-wire.
  if (typeof document === 'undefined') return

  for (const el of document.querySelectorAll('fg-set')) {
    if (el.inputType === name && typeof el.reattachInputListeners === 'function') {
      el.reattachInputListeners()
    }
  }
}
