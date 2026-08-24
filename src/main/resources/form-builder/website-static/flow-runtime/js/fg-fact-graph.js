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

// Presence of an unload event listener will disable bfcache in Firefox.
window.addEventListener('unload', () => {})

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
