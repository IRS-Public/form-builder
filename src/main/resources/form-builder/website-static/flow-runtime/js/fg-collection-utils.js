// Splicing a concrete item id into an abstract `/collection/*\/fact` path, and rewriting a cloned
// collection-item template to use it.
//
// makeCollectionIdPath is duplicated in taxpert's shared/js/collection-utils.js on purpose: this
// package ships in a Scala jar and cannot be imported from npm. Keep the two identical.

/**
 * Substitute a collection item's concrete id into an abstract collection path, e.g.
 * `("/familyAndHousehold/*\/firstName", "abc")` → `/familyAndHousehold/#abc/firstName`.
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
  // The 0s are placeholders. The 1 and the dashes are literal.
  return '00000000-0000-1000-0000-000000000000'.replace(/0/g, () => {
    return (Math.random() * 16 | 0).toString(16)
  })
}
