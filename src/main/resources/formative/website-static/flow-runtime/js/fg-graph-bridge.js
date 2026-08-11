// Fact Explorer live-sync bridge, relocated out of credit-assistant's core fg-fact-graph.js.
//
// When a Formative app's questionnaire is embedded same-origin in Fact Explorer (via its Vite
// proxy), the two surfaces share the serialized fact graph over a BroadcastChannel: publishing here
// lets Fact Explorer's scenario overlay update live as the user answers questions, and inbound
// messages let a scenario loaded in Fact Explorer rehydrate the questionnaire.
//
// It sits in the flow runtime rather than the audit panel because a flow publishing its own
// serialized graph is a runtime concern, not a tool one — nothing here reads or draws a panel. As the
// audit panel's, it was the single import that made the runtime depend on the workspace, which is
// what kept the workspace from being optional.
//
// HARD COMPATIBILITY CONSTRAINT: the channel name `taxpert:factGraph` and the message shape
// `{ type: 'factGraph', graph: <string> }` must stay byte-for-byte identical —
// fact-explorer/src/model/bridge.js implements the other side of this exact protocol. The
// `taxpert:` prefix therefore stays even though this module no longer belongs to the workspace:
// renaming the channel would break Fact Explorer silently, with no error on either side.
//
// Feature-detected so it no-ops (and stays node-testable) where BroadcastChannel is unavailable.

const DEFAULT_CHANNEL_NAME = 'taxpert:factGraph'

/**
 * Create a fact-graph bridge.
 * @param {object} [opts]
 * @param {string} [opts.channelName='taxpert:factGraph'] BroadcastChannel name (keep the default).
 * @param {(serializedGraphJSON: string) => void} [opts.onRemoteGraph] called with an inbound graph
 *        (echoes of graphs this bridge just published are already filtered out).
 * @returns {{ publish(serializedGraphJSON: string): void }}
 */
export function createFactGraphBridge ({ channelName = DEFAULT_CHANNEL_NAME, onRemoteGraph } = {}) {
  let channel = null
  // The last graph seen on the wire (published or received), so we ignore the echo of a graph we
  // just published and don't re-broadcast a graph we just received.
  let lastSynced = null

  try {
    if (typeof BroadcastChannel !== 'undefined') {
      channel = new BroadcastChannel(channelName)
      channel.addEventListener('message', (ev) => {
        const data = ev?.data
        if (!data || data.type !== 'factGraph' || typeof data.graph !== 'string') return
        if (data.graph === lastSynced) return // ignore our own echo / no-op if unchanged
        lastSynced = data.graph
        onRemoteGraph?.(data.graph)
      })
    }
  } catch (e) {
    console.warn('factGraph bridge unavailable:', e)
  }

  return {
    publish (serializedGraphJSON) {
      if (!channel || typeof serializedGraphJSON !== 'string') return
      if (serializedGraphJSON === lastSynced) return
      lastSynced = serializedGraphJSON
      channel.postMessage({ type: 'factGraph', graph: serializedGraphJSON })
    },
  }
}
