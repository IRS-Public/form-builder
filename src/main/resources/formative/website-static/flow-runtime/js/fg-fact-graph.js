import { createFactGraphBridge } from './fg-graph-bridge.js'
import { getRuntimeConfig, storageKey } from './runtime-config.js'
import { fg } from './fact-graph-engine.js'
import { resourceUrl } from './runtime-paths.js'

// The key the serialized graph is kept under, namespaced by `app.storagePrefix`. It was the bare
// 'factGraph', which is fine while one Formative app exists per origin and silently wrong the moment
// two are served together: same origin, same sessionStorage, one app's answers rehydrating the
// other's dictionary.
//
// A function, not a `const`: storageKey() reads the runtime config, and this module's top level runs
// during the same <script type="module"> race as the block that configures it. Captured here, the
// configured prefix applied or not depending on module load order — see runtime-config.js's
// "read late, never capture".
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

// ── Fact Explorer live-sync bridge (additive, feature-detected) ─────────────
// When this page is embedded same-origin in Fact Explorer (via its Vite proxy), Fact Explorer and
// the questionnaire share the serialized fact graph over a BroadcastChannel. Publishing lets Fact
// Explorer's scenario overlay update live as the user answers questions; the onRemoteGraph callback
// lets a scenario loaded in Fact Explorer rehydrate this page. The bridge (channel name + message
// shape) lives in taxpert; it feature-detects BroadcastChannel and suppresses the echo of graphs we
// publish.
const fgBridge = createFactGraphBridge({
  onRemoteGraph: (graph) => {
    // No-op if this graph is already the active one; otherwise adopt it and reload.
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
  // Defer the reload one task so the BroadcastChannel publish in saveFactGraph()
  // is flushed to other same-origin surfaces (e.g. Fact Explorer's overlay)
  // before this frame begins unloading. An immediate reload races the in-flight
  // message and drops it — which is why answering a question syncs but loading a
  // scenario did not.
  setTimeout(() => window.location.reload(), 0)
}
window.loadFactGraph = loadFactGraph

/**
 * Throw the graph away and start again, carrying a few answers across.
 *
 * The destructive-change confirmations are built on this: changing an answer that invalidates
 * everything downstream is implemented as "reset, then re-assert the answer they just gave".
 *
 * `facts` is a path → value map rather than the one named fact this used to take, because *which*
 * answer survives a reset is the application's rule, not the runtime's.
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
