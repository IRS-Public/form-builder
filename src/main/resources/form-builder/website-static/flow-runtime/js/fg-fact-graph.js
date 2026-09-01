// The Fact Graph itself: loads the dictionary, rehydrates from sessionStorage, and owns the one
// storage key the runtime writes.
//
// The top-level await here settles before any other module in the bundle runs, so nothing has to
// defend against a missing graph. See docs/internals/flow-runtime.md.

import { createFactGraphBridge } from './fg-graph-bridge.js'
import { getRuntimeConfig, storageKey } from './runtime-config.js'
import { fg } from './fact-graph-engine.js'
import { resourceUrl } from './runtime-paths.js'

// A function, not a const. Captured at module scope, the configured prefix would apply or not by
// load order.
const graphKey = () => storageKey('factGraph')

const res = await fetch(getRuntimeConfig().endpoints.factDictionaryUrl || resourceUrl('fact-dictionary.xml'))
const text = await res.text()
export const factDictionary = fg.FactDictionaryFactory.importFromXml(text)

const serializedGraphJSON = sessionStorage.getItem(graphKey())
export let factGraph = serializedGraphJSON
  ? fg.GraphFactory.fromJSON(factDictionary, serializedGraphJSON)
  : fg.GraphFactory.apply(factDictionary)

window.factGraph = factGraph
document.dispatchEvent(new CustomEvent('fg-load'))

// DF-6. There was an empty `unload` listener here, under a comment saying that its presence
// disables bfcache in Firefox. It disabled it in Chrome too, so every Back re-paid the whole boot
// instead of restoring a live page. `git log -S` puts it in 88526db, the commit that first created
// this file, with an empty body and no caller then or since; nothing was moved to `pagehide`
// because there was no work to move.
//
// What the deletion makes necessary is below. A page restored from bfcache comes back holding the
// `factGraph` it was frozen with, which is older than sessionStorage whenever an answer was given
// further down the flow — and the next `saveFactGraph()` from that page would write the older graph
// back over those answers.
//
// A reload rather than rehydrating in place: everything that read the graph at connect time would
// have to read it again, and <fg-collection> is not re-runnable — it builds its rows in
// connectedCallback from collection ids it also mints, so a second pass adds rows rather than
// replacing them. The identical case is the common one and stays instant: Back after simply passing
// through a screen leaves the stored graph untouched, and only Back after answering elsewhere pays
// for a reload.
window.addEventListener('pageshow', (event) => {
  if (!event.persisted) return
  const stored = sessionStorage.getItem(graphKey())
  if (stored === null || stored === factGraph.toJSON()) return
  window.location.reload()
})

const fgBridge = createFactGraphBridge({
  onRemoteGraph: (graph) => {
    if (graph === sessionStorage.getItem(graphKey())) return
    sessionStorage.setItem(graphKey(), graph)
    window.location.reload()
  },
})

export function saveFactGraph () {
  const serialized = factGraph.toJSON()
  sessionStorage.setItem(graphKey(), serialized)
  fgBridge.publish(serialized)
}

export function loadFactGraph (factGraphAsString) {
  factGraph = fg.GraphFactory.fromJSON(factDictionary, factGraphAsString)
  saveFactGraph()
  // Defer one task so the BroadcastChannel publish flushes before this frame unloads. An immediate
  // reload races the in-flight message and drops it.
  setTimeout(() => window.location.reload(), 0)
}
window.loadFactGraph = loadFactGraph

/**
 * Throw the graph away and start again, carrying a few answers across. An app's destructive-change
 * confirmation resets, then re-asserts the answer just given.
 *
 * @param {Record<string, unknown>} [facts] answers to set on the fresh graph before saving
 */
export function resetEntireGraph (facts = {}) {
  sessionStorage.removeItem(graphKey())
  factGraph = fg.GraphFactory.apply(factDictionary)
  window.factGraph = factGraph
  for (const [path, value] of Object.entries(facts)) factGraph.set(path, value)
  saveFactGraph()
  window.location.reload()
}
