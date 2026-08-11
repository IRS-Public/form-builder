// The compiled Fact Graph engine, loaded once and shared by every module in this bundle.
//
// The engine is a Scala.js build that each application vendors for itself (`make copy-fg` pulls it
// out of the sibling fact-graph repo), so it is *not* part of taxpert and cannot be a normal
// dependency. It is fetched at runtime from the application's own resources, which is why this is a
// dynamic `import()` of a computed URL rather than a static specifier.
//
// One module does it so the URL is computed once and the module registry dedups the rest: every
// other file here imports `fg` from this module, and they all share a single engine instance —
// which matters, because the graph they hand around is stateful.

import { getRuntimeConfig } from './runtime-config.js'
import { resourceUrl } from './runtime-paths.js'

const url = getRuntimeConfig().endpoints.factGraphUrl ||
  resourceUrl('vendor/fact-graph/factgraph-3.1.0.js')

/** The engine's module namespace — `FactDictionaryFactory`, `GraphFactory`, the value types. */
export const fg = await import(/* @vite-ignore */ url)
