// Live-sync bridge between a questionnaire and a same-origin Fact Explorer embedding it.
//
// WIRE PROTOCOL, DO NOT CHANGE: the channel name `taxpert:factGraph` and the message shape
// `{ type: 'factGraph', graph: <string> }` are implemented on the other side by
// fact-explorer/src/model/bridge.js. Renaming either breaks the sync silently, with no error on
// either end. See docs/internals/flow-runtime.md.

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
  // The last graph seen on the wire, in either direction, so an echo is not reprocessed.
  let lastSynced = null

  try {
    if (typeof BroadcastChannel !== 'undefined') {
      channel = new BroadcastChannel(channelName)
      channel.addEventListener('message', (ev) => {
        const data = ev?.data
        if (!data || data.type !== 'factGraph' || typeof data.graph !== 'string') return
        if (data.graph === lastSynced) return
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
