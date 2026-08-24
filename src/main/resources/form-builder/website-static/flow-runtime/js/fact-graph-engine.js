// The compiled Fact Graph engine, loaded once and shared by every module in this bundle.
//
// Each application vendors its own Scala.js build, so this is a dynamic import of a computed URL
// rather than a static specifier. Importing it here once means every module shares one engine
// instance, which matters because the graph they pass around is stateful.

import { getRuntimeConfig } from './runtime-config.js'
import { resourceUrl } from './runtime-paths.js'

const url = getRuntimeConfig().endpoints.factGraphUrl ||
  resourceUrl('vendor/fact-graph/factgraph-3.1.0.js')

/** The engine's namespace: `FactDictionaryFactory`, `GraphFactory`, the value types. */
export const fg = await import(/* @vite-ignore */ url)
