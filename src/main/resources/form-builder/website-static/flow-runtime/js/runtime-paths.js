// Where this bundle is served from, and therefore where the application running it is mounted.
//
// FormBuilderAssets extracts the bundle into `{appBase}/resources/vendor/form-builder/`, so
// `import.meta.url` carries the app's route prefix and no app name is written here. VENDOR_TAIL
// must match FormBuilderAssets.vendorPath. A mismatch fails silently, with every link turning
// root-relative. See docs/internals/flow-runtime.md.

import { getRuntimeConfig } from './runtime-config.js'

// Everything before this tail is the application's `resources/` directory.
const VENDOR_TAIL = '/vendor/form-builder/flow-runtime/js/'

/**
 * The application's `resources/` directory, as an absolute URL with no trailing slash.
 *
 * @returns {string|null} null when it cannot be derived (a bundler inlined this module, or a test)
 */
export function resourcesBase () {
  const here = new URL('.', import.meta.url).href
  const at = here.lastIndexOf(VENDOR_TAIL)
  return at === -1 ? null : here.slice(0, at)
}

/**
 * The route prefix the application is mounted under, with no trailing slash. A path rather than a
 * URL, because callers put it straight into an `href` or compare it to `location.pathname`.
 *
 * @returns {string} the mounted route prefix, or '' when it cannot be derived
 */
export function appBasePath () {
  const configured = getRuntimeConfig().endpoints.basePath
  if (configured) return String(configured).replace(/\/$/, '')

  const resources = resourcesBase()
  if (!resources) return ''
  // `{appBase}/resources` → `{appBase}`, then drop the origin.
  const appUrl = resources.replace(/\/resources$/, '')
  try {
    return new URL(appUrl).pathname.replace(/\/$/, '')
  } catch {
    return ''
  }
}

/**
 * A URL for a file the application publishes under `resources/`.
 *
 * @param {string} name e.g. 'fact-dictionary.xml', 'vendor/fact-graph/factgraph-3.1.0.js'
 * @returns {string}
 */
export function resourceUrl (name) {
  const base = resourcesBase()
  // Without a derived base, fall back to the app path, giving a root-relative '/resources/…'.
  return `${base ?? `${appBasePath()}/resources`}/${name}`
}
