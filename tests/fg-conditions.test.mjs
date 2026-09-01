// Tests for showOrHideAllElements() — and, in particular, for when it lifts DF-2's paint gate.
//
// Since DF-2 the generated page is no longer hidden behind the spinner: it paints while the Fact
// Graph is still booting, and the only thing held back is any element whose condition nothing has
// answered yet. `body.fg-conditions-pending` is what holds them, and this function is what takes it
// off — at the end of the pass, once every one of them has a real answer.
//
// The ordering is the whole point and is easy to lose to a tidy-up. Clearing the gate before the
// loop, or in a `finally`, would reveal elements the pass had not reached; on a transpiled flow that
// includes the wrapper carrying a knockout screen's own condition, which is to say it would tell a
// taxpayer they are ineligible before anything had decided that they were.

import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { registerHooks } from 'node:module'

const HERE = new URL('.', import.meta.url)
const RUNTIME = new URL('../src/main/resources/form-builder/website-static/flow-runtime/js/', HERE)
const MODULE = new URL('fg-conditions.js', RUNTIME)

// fg-conditions.js imports the graph from fg-fact-graph.js, which cannot load outside a browser: it
// fetches a fact dictionary at top-level await and dynamically imports each application's own
// compiled engine bundle, which does not exist in this repository. Substituting the one export it
// actually uses is cheaper than a fixture, and keeps the module under test the real file rather
// than a copy that can drift.
registerHooks({
  load (url, context, nextLoad) {
    if (!url.endsWith('/fg-fact-graph.js')) return nextLoad(url, context)
    return {
      format: 'module',
      shortCircuit: true,
      // Read through to the global on every call rather than captured once: the stub's own URL never
      // changes, so it is evaluated once and shared by every test in this file.
      source: 'export const factGraph = { get: (path) => globalThis.__factGraph.get(path) }',
    }
  },
})

let nonce = 0

/** A stand-in for one `[condition][operator]` element. `descendants` are its `<fg-set>` children. */
function el ({ condition, operator, tagName = 'DIV', hidden = false, descendants = [], onAdd }) {
  const classes = new Set(hidden ? ['hidden'] : [])
  return {
    tagName,
    condition,
    operator,
    cleared: 0,
    classList: {
      contains: (c) => classes.has(c),
      add: (c) => { onAdd?.(); classes.add(c) },
      remove: (c) => classes.delete(c),
    },
    getAttribute: (name) => (name === 'condition' ? condition : operator),
    querySelectorAll: () => descendants,
    deleteFactNoUpdate () { this.cleared += 1 },
    get hidden () { return classes.has('hidden') },
  }
}

function fgSet (props = {}) {
  return el({ condition: null, operator: null, tagName: 'FG-SET', ...props })
}

/**
 * Install a document and a graph, and hand back the module.
 *
 * `facts` is `[path, value]` pairs — what the graph reports for each condition. A path that is not
 * in the list throws out of `factGraph.get`, which is the shape checkCondition() defends against.
 */
async function load (elements, facts = []) {
  const bodyClasses = new Set(['fg-conditions-pending'])
  const values = new Map(facts)
  globalThis.__factGraph = {
    get (path) {
      if (!values.has(path)) throw new Error(`no such fact ${path}`)
      return values.get(path)
    },
  }
  globalThis.document = {
    querySelectorAll: (selector) => {
      assert.equal(selector, '[condition][operator]')
      return elements
    },
    body: {
      classList: {
        remove: (c) => bodyClasses.delete(c),
        has: (c) => bodyClasses.has(c),
      },
    },
  }
  const mod = await import(`${MODULE.href}?t=${++nonce}`)
  return { ...mod, gateIsUp: () => bodyClasses.has('fg-conditions-pending') }
}

const isTrue = { hasValue: true, get: true, complete: true }
const isFalse = { hasValue: true, get: false, complete: true }

beforeEach(() => { delete globalThis.document; delete globalThis.__factGraph })

test('a decided pass hides what is false, shows what is true, and lifts the gate', async () => {
  const shown = el({ condition: '/a', operator: 'isTrue', hidden: true })
  const gone = el({ condition: '/b', operator: 'isTrue' })
  const { showOrHideAllElements, gateIsUp } = await load([shown, gone], [['/a', isTrue], ['/b', isFalse]])

  showOrHideAllElements()

  assert.equal(shown.hidden, false)
  assert.equal(gone.hidden, true)
  assert.equal(gateIsUp(), false, 'the gate is lifted once every element has been decided')
})

test('the gate stays up when the pass throws part-way', async () => {
  // Any DOM call in the loop failing stands for the same thing: elements after the throw were never
  // looked at. Whatever they are, hidden is the safe state to leave them in — so this must not be
  // reachable from a `finally`.
  const explodes = el({ condition: '/a', operator: 'isTrue', onAdd () { throw new Error('boom') } })
  const never = el({ condition: '/b', operator: 'isTrue' })
  const { showOrHideAllElements, gateIsUp } = await load([explodes, never], [['/a', isFalse], ['/b', isFalse]])

  assert.throws(() => showOrHideAllElements(), /boom/)

  assert.equal(never.hidden, false, 'the element after the throw was never reached')
  assert.equal(gateIsUp(), true, 'so the gate that was hiding it is still in force')
})

test('an unreadable condition shows the element, and still lifts the gate', async () => {
  // checkCondition() defaults to showing, so a question is never skipped because a path did not
  // resolve. That is a decided pass, not a failed one.
  const orphan = el({ condition: '/missing', operator: 'isTrue', hidden: true })
  const { showOrHideAllElements, gateIsUp } = await load([orphan])
  const errors = []
  const realError = console.error
  console.error = (...args) => errors.push(args)
  try { showOrHideAllElements() } finally { console.error = realError }

  assert.equal(orphan.hidden, false)
  assert.equal(errors.length, 1)
  assert.equal(gateIsUp(), false)
})

test('hiding an element clears the facts inside it', async () => {
  const child = fgSet()
  const section = el({ condition: '/a', operator: 'isTrue', descendants: [child] })
  const question = fgSet({ condition: '/a', operator: 'isTrue' })
  const { showOrHideAllElements } = await load([section, question], [['/a', isFalse]])

  showOrHideAllElements()

  assert.equal(child.cleared, 1, 'an <fg-set> inside a hidden section')
  assert.equal(question.cleared, 1, 'and a hidden <fg-set> itself')
})

test('the gate class is spelled the same way in the template, the stylesheet and here', async () => {
  // Three files have to agree on this string for the page to paint correctly, and two of them are
  // not JavaScript, so nothing else would notice a rename.
  const CLASS = 'fg-conditions-pending'
  // eslint-disable-next-line security/detect-non-literal-fs-filename -- three paths in this file
  const read = async (rel) => readFile(new URL(rel, HERE), 'utf8')

  const source = await read('../src/main/resources/form-builder/website-static/flow-runtime/js/fg-conditions.js')
  const page = await read('../src/main/resources/form-builder/templates/page.html')
  const css = await read('../src/main/resources/form-builder/website-static/theme/styles/layout/main-content.css')

  assert.match(source, new RegExp(`classList\\.remove\\('${CLASS}'\\)`))
  assert.match(page, new RegExp(`<body class="${CLASS}"`))
  assert.match(css, new RegExp(`body\\.${CLASS} \\[condition\\]\\[operator\\]`))
})
