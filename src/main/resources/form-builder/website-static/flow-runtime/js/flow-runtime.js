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

// Absent on the /all-screens view, which renders every page at once with no loading spinner.
document.querySelector('#page-content-wrapper')?.classList.remove('hidden')
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
