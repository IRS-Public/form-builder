// Where this bundle is served from — and therefore where the application it is running inside is.
//
// The flow runtime has to build four URLs that used to be written as one app's own route prefix,
// spelled out as a literal: the fact dictionary, the flow manifest, the compiled fact-graph engine,
// and the href of the flow's first page. An app's name or route in this library is exactly the
// coupling FormBuilderApp exists to prevent, so the bundle locates *itself* instead.
//
// It can, because the path is a fixed contract rather than a per-app choice: FormBuilderAssets.scala
// extracts this bundle out of the form-builder jar into `<resources>/vendor/form-builder/`, and the
// generator publishes an app's `website-static` as its `resources/` directory. So this module is
// always served from
//
//     {appBase}/resources/vendor/form-builder/flow-runtime/js/
//
// and `import.meta.url` carries `{appBase}` as a prefix. Two applications mounted under different
// route prefixes both work with nothing configured, which is the property being bought here — not
// merely "the literal is gone".
//
// `VENDOR_TAIL` below therefore has to match FormBuilderAssets.vendorPath. A mismatch does not throw:
// `resourcesBase()` returns null, `appBasePath()` returns '', and every link silently becomes
// root-relative. The app served from a non-root prefix is the one that catches it.
//
// A host that serves this bundle from somewhere else (a bundler that rewrote the URL, a test) can
// say so with `configureRuntime({ endpoints: { basePath } })`. That is an escape hatch, not the
// normal path: if it is ever needed in a generated app, the derivation above is wrong and should be
// fixed rather than papered over.

import { getRuntimeConfig } from './runtime-config.js'

// The tail this module's URL ends with under the vendoring contract above. Everything before it is
// the application's `resources/` directory.
const VENDOR_TAIL = '/vendor/form-builder/flow-runtime/js/'

/**
 * The application's `resources/` directory, as an absolute URL with no trailing slash.
 *
 * `null` when it cannot be derived — a bundler has inlined this module, or it is under test. Every
 * caller has a documented fallback for that case; none of them guess.
 *
 * @returns {string|null}
 */
export function resourcesBase () {
  const here = new URL('.', import.meta.url).href
  const at = here.lastIndexOf(VENDOR_TAIL)
  return at === -1 ? null : here.slice(0, at)
}

/**
 * The application's base path — the route prefix it is mounted under, with no trailing slash.
 *
 * A *path*, not a URL, because every caller puts it straight into an `href` or compares it against
 * `window.location.pathname`.
 *
 * Empty string when it cannot be derived, which is the correct value for an app served at the
 * origin root and the least surprising one otherwise: links stay root-relative, exactly as they
 * were before this bundle moved.
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
  // Without a derived base, fall back to the app path — which is '' for a root-mounted app, giving
  // the root-relative '/resources/…' that host was already serving.
  return `${base ?? `${appBasePath()}/resources`}/${name}`
}
