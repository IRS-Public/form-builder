// The flow runtime's entry point — the custom elements that make a generated questionnaire work.
//
// Importing this defines `<fg-set>`, `<fg-collection>`, `<fg-show>`, `<fg-reset>` and the modal
// elements, boots the Fact Graph from the application's own `fact-dictionary.xml`, and starts
// single-question navigation. Everything here is true of *any* flow the scaffold generates; nothing
// here knows which application it is.
//
// An application that needs more than this — its own knockout gates, its own destructive-change
// confirmations — imports this module first and then its own, registering through
// `registerContinueHandler()` and the ordinary DOM events. That is the seam: this bundle grew out
// of one host's `website-static/js/`, and the parts of it that were that host's business stayed
// behind rather than moving in here.

import './fg-fact-graph.js'
import { showOrHideAllElements } from './fg-conditions.js'
import './fg-set.js'
import './fg-collection.js'
import './fg-display.js'
import './continue-handlers.js'
import { initSingleQuestionNav } from './fg-navigator.js'

// Add show/hide functionality to all elements
document.addEventListener('fg-update', showOrHideAllElements)
showOrHideAllElements()

// #page-content-wrapper / #loading-spinner exist on the flow page template (page.html) but not
// on the /all-screens audit view, which renders all pages directly without a loading spinner.
document.querySelector('#page-content-wrapper')?.classList.remove('hidden')
document.querySelector('#loading-spinner')?.classList.add('hidden')

initSingleQuestionNav()

// Open all <details> elements that have a complete fact, so users can see information they've
// entered if they return to a page.
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
