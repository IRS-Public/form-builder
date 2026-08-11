// What the flow runtime needs to know about the application it is running, and nothing else.
//
// Deliberately *not* taxpert's configure(): there is no nav here, no tools, no determinations, no
// feature flags. Those belong to the workspace, are meaningless without it, and live in
// taxpert/src/shared/js/config.js. This is the flow runtime's whole configuration surface, and the
// flow runtime is what every Formative app needs whether or not it has a workspace.
//
// Four values, because that is all the runtime ever read out of the workspace's 459-line config:
//
//   app.storagePrefix          namespaces the one storage key the runtime writes
//   endpoints.basePath         the route prefix, when self-location cannot derive it
//   endpoints.factGraphUrl     the compiled Fact Graph engine
//   endpoints.factDictionaryUrl the dictionary XML
//
// Every one has a working default, because nothing is obliged to call configureRuntime() at all —
// runtime-paths.js derives what it can from `import.meta.url`, and a site generated without
// `--auditMode` still has a fully working questionnaire.
//
// ── Why this exists at all ────────────────────────────────────────────────────────────────────
//
// The runtime used to read these from taxpert's config.js, whose only caller is the app-authored
// `fragments/taxpert-config.html` — which sits *inside* head.html's `--auditMode` block. So in any
// build without the workspace, configure() was never called and `app.storagePrefix` silently stayed
// 'taxpert', which is exactly the collision the prefix exists to prevent: two workspace-less
// Formative apps on one origin sharing `taxpert:factGraph` in sessionStorage. head.html now renders
// a call to configureRuntime() *ungated*, so the prefix reaches the runtime either way.
//
// ── Two layers: the server, then any programmatic override ────────────────────────────────────
//
//   defaults    this file                            what a runtime told nothing runs on
//   server      <meta> tags from fragments/head.html the build's real values
//   override    configureRuntime()                   a bundler or a test that knows better
//
// The server layer arrives as `<meta name="formative:*">` rather than as an inline module that calls
// configureRuntime(), and that is deliberate. fg-fact-graph.js reads the stored graph at its *top
// level* (it has a top-level await), so a configuring <script> would have to execute before it —
// which document order does give, but silently: put the block in the wrong place and the page still
// renders, just with the wrong storage prefix. Reading the DOM on first use has no such ordering to
// get wrong, because head.html's <meta> tags are parsed before any module executes.
//
// ── Read late, never capture ──────────────────────────────────────────────────────────────────
//
// Call getRuntimeConfig() at the point of use, never at module scope. storageKey() is the cautionary
// example: it was once captured into a module-scope `const GRAPH_KEY`, which made whether the
// configured prefix applied depend on module load order.
//
// The returned object's *identity is stable* — the merge rewrites it in place rather than replacing
// it — so even a caller that breaks the rule above sees current values.

/** The shape a runtime that has been told nothing runs on. */
function baseConfig () {
  return {
    app: {
      // 'taxpert' rather than '' so an unconfigured host keeps writing the `taxpert:`-prefixed keys
      // it already had, instead of a bare `:factGraph`.
      storagePrefix: 'taxpert',
    },
    endpoints: {
      // Empty means "derive it": runtime-paths.js locates the app from this module's own URL, which
      // is correct for every generated site. Setting this is an escape hatch for a bundler that
      // rewrote the URL, or a test.
      basePath: '',
      factGraphUrl: '',
      factDictionaryUrl: '',
    },
  }
}

const config = baseConfig()

/** Which `<meta name="…">` carries which key. The other half of this map is in fragments/head.html.
 *
 * Only the two the server knows better than the runtime can derive. `factGraphUrl` and
 * `factDictionaryUrl` are not here on purpose: both are built by resourceUrl(), which falls back
 * through `basePath`, so sending that one value already makes them resolve — whereas sending them
 * outright would put the vendored engine's version number in a second place to bump. They stay
 * settable through configureRuntime() for a bundler or a test.
 */
const META_KEYS = [
  ['formative:storage-prefix', 'app', 'storagePrefix'],
  ['formative:base-path', 'endpoints', 'basePath'],
]

// Whether the server layer has been read. Once, lazily, on first use rather than at module scope —
// this module may be imported by a bundler or a test where there is no document at all.
let seeded = false

/** Read the server's values off the document. Idempotent; a no-op outside a browser. */
function seed () {
  if (seeded) return
  seeded = true
  if (typeof document === 'undefined') return

  for (const [metaName, namespace, key] of META_KEYS) {
    const value = document.querySelector(`meta[name="${metaName}"]`)?.content
    if (typeof value === 'string' && value !== '') merge(namespace, key, value)
  }
}

/**
 * The current runtime configuration. Read it at the point of use.
 *
 * @returns {{app: {storagePrefix: string}, endpoints: {basePath: string, factGraphUrl: string, factDictionaryUrl: string}}}
 */
export function getRuntimeConfig () {
  seed()
  return config
}

/**
 * Merge a host's values over the defaults, one namespace deep.
 *
 * Unknown namespaces and unknown keys are ignored rather than written — the merge is a whitelist, so
 * a caller cannot introduce a key the runtime does not read, and cannot reach the prototype. Empty
 * and non-string values are skipped too, so a server that renders a blank `basePath` for a
 * root-mounted app leaves the default in place instead of overwriting it with nothing.
 *
 * @param {object} partial e.g. `{ app: { storagePrefix: 'eitc' }, endpoints: { basePath: '/app/eitc' } }`
 * @returns {object} the merged configuration
 */
export function configureRuntime (partial) {
  // Seed first, so an explicit call always lands *over* the server's values rather than under them.
  seed()
  if (!partial || typeof partial !== 'object') return config

  for (const [namespace, incoming] of Object.entries(partial)) {
    if (!Object.hasOwn(config, namespace)) continue
    if (!incoming || typeof incoming !== 'object') continue
    for (const [key, value] of Object.entries(incoming)) {
      if (typeof value !== 'string' || value === '') continue
      merge(namespace, key, value)
    }
  }

  return config
}

/** Write one known key, ignoring anything the runtime does not read. */
function merge (namespace, key, value) {
  const target = Object.getOwnPropertyDescriptor(config, namespace)?.value
  if (!target || !Object.hasOwn(target, key)) return
  // defineProperty rather than `target[key] = value`: the key can come from a caller-supplied
  // object, and a computed member assignment on one is the pattern the security lint forbids.
  Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true })
}

/**
 * The namespaced key for `name`, so two Formative apps served from one origin do not share
 * sessionStorage.
 *
 * The runtime writes exactly one key ('factGraph'). The workspace namespaces its own keys the same
 * way, through taxpert's own storageKey() — the two never share a key name, so the two prefixes are
 * independent by construction and neither package has to import the other to stay in step.
 *
 * @param {string} name the bare key, without a prefix
 * @returns {string} e.g. 'taxpert:factGraph', or 'eitc:factGraph' under a configured prefix
 */
export function storageKey (name) {
  const prefix = getRuntimeConfig().app.storagePrefix || 'taxpert'
  return `${prefix}:${name}`
}
