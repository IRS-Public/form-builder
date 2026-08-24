// The flow runtime's configuration surface: a storage prefix and a base path.
//
// Two layers over the defaults below. The server layer is the `<meta name="form-builder:*">` tags
// fragments/head.html renders, read lazily on first use. The override layer is configureRuntime().
//
// See docs/internals/flow-runtime.md for the layering and why the server layer is meta tags.

function baseConfig () {
  return {
    app: {
      storagePrefix: 'taxpert',
    },
    endpoints: {
      // Empty means "derive it": runtime-paths.js locates the app from this module's own URL.
      basePath: '',
      factGraphUrl: '',
      factDictionaryUrl: '',
    },
  }
}

const config = baseConfig()

// The other half of this map is in fragments/head.html.
const META_KEYS = [
  ['form-builder:storage-prefix', 'app', 'storagePrefix'],
  ['form-builder:base-path', 'endpoints', 'basePath'],
]

// Lazily on first use, because this module may be imported where there is no document at all.
let seeded = false

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
 * The current runtime configuration. Read it at the point of use, never captured at module scope.
 *
 * @returns {{app: {storagePrefix: string}, endpoints: {basePath: string, factGraphUrl: string, factDictionaryUrl: string}}}
 */
export function getRuntimeConfig () {
  seed()
  return config
}

/**
 * Merge a host's values over the defaults, one namespace deep. Unknown namespaces, unknown keys,
 * non-strings and empty strings are ignored rather than written.
 *
 * @param {object} partial e.g. `{ app: { storagePrefix: 'eitc' }, endpoints: { basePath: '/app/eitc' } }`
 * @returns {object} the merged configuration
 */
export function configureRuntime (partial) {
  // Seed first, so an explicit call lands over the server's values rather than under them.
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

function merge (namespace, key, value) {
  const target = Object.getOwnPropertyDescriptor(config, namespace)?.value
  if (!target || !Object.hasOwn(target, key)) return
  // defineProperty rather than `target[key] = value`, which the security lint forbids on a
  // caller-supplied key.
  Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true })
}

/**
 * The namespaced key for `name`, so two apps on one origin do not share sessionStorage.
 *
 * @param {string} name the bare key, without a prefix
 * @returns {string} e.g. 'taxpert:factGraph', or 'eitc:factGraph' under a configured prefix
 */
export function storageKey (name) {
  const prefix = getRuntimeConfig().app.storagePrefix || 'taxpert'
  return `${prefix}:${name}`
}
