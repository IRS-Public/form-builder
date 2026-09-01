// The flow runtime's entry point. Defines the custom elements, boots the Fact Graph from the
// application's own fact-dictionary.xml, and starts single-question navigation.
//
// Nothing here knows which application it is. An app imports this first, then its own modules, and
// extends the runtime through registerContinueHandler() and DOM events.
//
// See docs/internals/flow-runtime.md.

import './fg-fact-graph.js'
import { showOrHideAllElements } from './fg-conditions.js'
import './fg-set.js'
import './fg-collection.js'
import './fg-display.js'
import './continue-handlers.js'
import { initFlowNavigation } from './fg-navigator.js'

document.addEventListener('fg-update', showOrHideAllElements)
showOrHideAllElements()

// DF-2. The page painted long ago; what lifts here is `inert`, so the flow becomes interactive at
// the moment it can answer for itself. Every <fg-set> has already read its value out of the graph
// by now — fg-set.js is imported above, and a custom element upgrades on import — so nothing the
// user types from here is overwritten. Absent on the /all-screens view, which renders every page at
// once with no spinner and no wrapper.
//
// Not awaiting initFlowNavigation() below first, though it is what corrects .form-actions' Next to
// the next *live* page: its fetch is a network call, and blocking interactivity on one would be a
// new way for a slow link to make the page unusable. Clicking Next in the window before it lands
// goes to a page that then bounces you forward itself, which is what happens today.
document.querySelector('#page-content-wrapper')?.removeAttribute('inert')
document.querySelector('#loading-spinner')?.classList.add('hidden')

initFlowNavigation()

// Open <details> elements whose fact is already complete, so a returning user sees their answers.
for (const fgSet of document.querySelectorAll('.fg-detail fg-set:not(.hidden)')) {
  if (fgSet.isComplete()) {
    fgSet.closest('.fg-detail').setAttribute('open', '')
  }
}

export { factGraph, saveFactGraph, loadFactGraph, factDictionary, resetEntireGraph } from './fg-fact-graph.js'
export { checkCondition, showOrHideAllElements } from './fg-conditions.js'
export { registerContinueHandler, revealOnContinue, handleSectionContinue } from './continue-handlers.js'
export { validateSectionForNavigation, focusKnockoutAlert, showValidationError } from './fg-validation.js'
export { makeCollectionIdPath, configureCollectionIds, generateUUID } from './fg-collection-utils.js'
export { appBasePath, resourceUrl, resourcesBase } from './runtime-paths.js'
