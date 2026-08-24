// Conditional visibility: evaluating a `condition`/`operator` pair against the Fact Graph, and
// applying the result to every element carrying one.
//
// Hiding an element deletes the facts inside it, which is what keeps a hidden answer from counting
// as complete. See docs/internals/flow-runtime.md.

import { factGraph } from './fg-fact-graph.js'

export function checkCondition (condition, operator) {
  let value
  // Default to showing, so an error never skips a necessary question.
  try {
    value = factGraph.get(condition)
  } catch (e) {
    console.error(`Error attempting to fetch ${condition}, ignoring condition:\n`, e)
    return true
  }

  switch (operator) {
    // Explicit rather than truthiness: an incomplete fact has no value to compare.
    case 'isTrue': {
      return value.hasValue && (value.get === true)
    } case 'isFalse': {
      return value.hasValue && (value.get === false)
    } case 'isTrueAndComplete': {
      return value.complete === true && value.hasValue && (value.get === true)
    } case 'isZero': {
      return value.hasValue && (value.get === 0)
    } case 'isGreaterThanZero': {
      return value.hasValue && (value.get > 0)
    } case 'isIncomplete': {
      return value.complete === false
    } case 'notHasValue': {
      return value.hasValue === false
    } default: {
      console.error(`Unknown condition operator ${operator}`)
      return false
    }
  }
}

/**
 * Show or hide every `[condition][operator]` element, deleting the facts inside anything hidden.
 * One pass in DOM order, so an `<fg-set>` must not depend on a fact set later in the document.
 */
export function showOrHideAllElements () {
  const hideableElements = document.querySelectorAll('[condition][operator]')
  for (const element of hideableElements) {
    const condition = element.getAttribute('condition')
    const operator = element.getAttribute('operator')
    const meetsCondition = checkCondition(condition, operator)

    if (!meetsCondition && !element.classList.contains('hidden')) {
      element.classList.add('hidden')
      if (element.tagName === 'FG-SET') {
        element?.deleteFactNoUpdate()
      } else {
        const fgSetChildren = element.querySelectorAll('fg-set')
        for (const fgSetChild of fgSetChildren) fgSetChild.deleteFactNoUpdate()
      }
    } else if (meetsCondition && element.classList.contains('hidden')) {
      element.classList.remove('hidden')
    }
  }
}
