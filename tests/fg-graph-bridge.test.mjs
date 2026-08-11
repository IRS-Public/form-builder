// Tests for createFactGraphBridge — the relocated Fact Explorer live-sync bridge.
// Uses a minimal in-process BroadcastChannel stub so we can assert the wire protocol
// (channel name + { type:'factGraph', graph } message shape) and echo suppression without jsdom.
import { test, before, beforeEach } from 'node:test'
import assert from 'node:assert/strict'

// ── Minimal BroadcastChannel stub: broadcasts to every OTHER channel of the same name ──
const registry = new Map()
class FakeBroadcastChannel {
  constructor (name) {
    this.name = name
    this._listeners = []
    this.posted = []
    if (!registry.has(name)) registry.set(name, new Set())
    registry.get(name).add(this)
  }

  postMessage (data) {
    this.posted.push(data)
    for (const other of registry.get(this.name)) {
      if (other === this) continue
      for (const fn of other._listeners) fn({ data })
    }
  }

  addEventListener (_type, fn) {
    this._listeners.push(fn)
  }

  close () {
    registry.get(this.name)?.delete(this)
  }
}

let createFactGraphBridge
before(async () => {
  globalThis.BroadcastChannel = FakeBroadcastChannel
  ;({ createFactGraphBridge } = await import('../src/main/resources/formative/website-static/flow-runtime/js/fg-graph-bridge.js'))
})

beforeEach(() => registry.clear())

test('publish posts the exact wire protocol { type:"factGraph", graph } on taxpert:factGraph', () => {
  const bridge = createFactGraphBridge({})
  // A second channel on the default name acts as the "other side" (e.g. Fact Explorer).
  const peer = new FakeBroadcastChannel('taxpert:factGraph')
  const received = []
  peer.addEventListener('message', (ev) => received.push(ev.data))

  bridge.publish('{"facts":1}')
  assert.deepEqual(received, [{ type: 'factGraph', graph: '{"facts":1}' }])
})

test('onRemoteGraph fires for inbound graphs but not for our own published echo', () => {
  const inbound = []
  const bridge = createFactGraphBridge({ onRemoteGraph: (g) => inbound.push(g) })
  const peer = new FakeBroadcastChannel('taxpert:factGraph')

  // Inbound from the peer → delivered.
  peer.postMessage({ type: 'factGraph', graph: 'REMOTE' })
  assert.deepEqual(inbound, ['REMOTE'])

  // Our own publish must not call onRemoteGraph (echo suppression via lastSynced).
  bridge.publish('LOCAL')
  assert.deepEqual(inbound, ['REMOTE'])
})

test('publish is a no-op for a graph identical to the last one seen on the wire', () => {
  const bridge = createFactGraphBridge({})
  const peer = new FakeBroadcastChannel('taxpert:factGraph')
  const received = []
  peer.addEventListener('message', (ev) => received.push(ev.data))

  peer.postMessage({ type: 'factGraph', graph: 'SAME' }) // sets bridge.lastSynced = 'SAME'
  bridge.publish('SAME') // should be suppressed
  assert.equal(received.length, 0, 'no outbound message for a graph we just received')

  bridge.publish('DIFFERENT')
  assert.deepEqual(received, [{ type: 'factGraph', graph: 'DIFFERENT' }])
})

test('ignores malformed messages (wrong type or non-string graph)', () => {
  const inbound = []
  createFactGraphBridge({ onRemoteGraph: (g) => inbound.push(g) })
  const peer = new FakeBroadcastChannel('taxpert:factGraph')
  peer.postMessage({ type: 'other', graph: 'x' })
  peer.postMessage({ type: 'factGraph', graph: 42 })
  peer.postMessage(null)
  assert.deepEqual(inbound, [])
})

test('no-ops (does not throw) when BroadcastChannel is unavailable', async () => {
  const saved = globalThis.BroadcastChannel
  delete globalThis.BroadcastChannel
  try {
    const bridge = createFactGraphBridge({ onRemoteGraph: () => {} })
    assert.doesNotThrow(() => bridge.publish('x'))
  } finally {
    globalThis.BroadcastChannel = saved
  }
})
