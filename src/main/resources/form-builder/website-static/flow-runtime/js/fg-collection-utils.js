// Collection-path helpers for the flow runtime.
//
// ── On makeCollectionIdPath being in two packages ─────────────────────────────────────────────
//
// taxpert has its own copy, in `shared/js/collection-utils.js`, for its two tooling consumers
// (`audit-panel/js/fact-dictionary.js` and `tool-panels/js/watchlist-store.js`). This module used to
// import that one and re-export it, which is what made the flow runtime — a thing every Form Builder
// app needs — depend on the workspace, a thing no app needs.
//
// Sharing one copy is not available here, in either direction. Form Builder ships as a Scala jar, not
// an npm package, so taxpert cannot `import` from it; and a relative path into `vendor/form-builder/`
// exists only in a *built* app, so it would break taxpert's own `node --test` run and
// fact-explorer's Vite bundle, neither of which has a vendor tree.
//
// So: two copies of one pure line, deliberately, each commented to name the other. The function is
// `abstractPath.replace('*', '#' + id)` — no state, no config, nothing to drift semantically. If it
// ever grows past that, this decision needs revisiting rather than extending.

/**
 * Substitute a collection item's concrete id into an abstract collection path, e.g.
 * `("/familyAndHousehold/*\/firstName", "abc")` → `/familyAndHousehold/#abc/firstName`.
 *
 * Kept byte-identical to taxpert's `shared/js/collection-utils.js` — see the note above.
 *
 * @param {string} abstractPath the abstract path containing a `*` collection wildcard
 * @param {string} id the collection item id to splice in
 * @returns {string} the concrete path
 */
export function makeCollectionIdPath (abstractPath, id) {
  return abstractPath.replace('*', `#${id}`)
}

export const COLLECTION_ID_PLACEHOLDER = '{{COLLECTION_ID}}'

export function configureCollectionIds (template, collectionId) {
  const attributes = ['path', 'condition', 'id', 'for', 'name', 'aria-describedby']
  const nodesWithAbstractPaths = template.querySelectorAll(attributes.map(attr => `[${attr}*="/*/"]`).join(','))
  for (const node of nodesWithAbstractPaths) {
    for (const attribute of attributes) {
      const path = node.getAttribute(attribute)
      if (path) {
        node.setAttribute(attribute, makeCollectionIdPath(path, collectionId))
      }
    }
  }

  for (const button of template.querySelectorAll('button.pdf-download')) {
    const onclick = button.getAttribute('onclick')
    button.setAttribute('onclick', onclick.replaceAll(COLLECTION_ID_PLACEHOLDER, collectionId))
  }
}

export function generateUUID () {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  // 0 is the placeholder, 1 and - are static
  return '00000000-0000-1000-0000-000000000000'.replace(/0/g, () => {
    return (Math.random() * 16 | 0).toString(16)
  })
}
