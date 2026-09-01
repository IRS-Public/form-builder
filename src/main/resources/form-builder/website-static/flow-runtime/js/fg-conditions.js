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
 * Clear one hidden `<fg-set>`'s fact, surviving a path the graph cannot resolve.
 *
 * The delete is guarded for the same reason the read in checkCondition() is, and the asymmetry was a
 * real bug: a path whose collection item does not exist — `/primaryFiler/willBeClaimed` while
 * `/filers` is empty, `/primaryFiler` being a `<Find>` over it — throws `requirement failed` out of
 * the engine. Thrown from here it escaped showOrHideAllElements(), which flow-runtime.js calls at
 * module scope, so the module aborted: no navigation, no input wiring, and on an --auditMode build
 * no workspace either, because the nav and its modals wire up after it. One unanswerable question
 * took down every page of the site.
 *
 * Hiding still happens; only the cleanup is skipped. That is the safe half to lose — the fact has no
 * resolvable home to hold a stale answer in, which is the same condition that made the delete throw.
 */
function clearHiddenFact (fgSet) {
  try {
    fgSet.deleteFactNoUpdate()
  } catch (e) {
    console.error(`Error clearing ${fgSet.getAttribute('path')} while hiding it, ignoring:\n`, e)
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
        clearHiddenFact(element)
      } else {
        const fgSetChildren = element.querySelectorAll('fg-set')
        for (const fgSetChild of fgSetChildren) clearHiddenFact(fgSetChild)
      }
    } else if (meetsCondition && element.classList.contains('hidden')) {
      element.classList.remove('hidden')
    }
  }

  // DF-2. Every conditional element now carries its real answer, so the CSS gate that has kept all
  // of them hidden since first paint can go. Only here, and deliberately not in a `finally`: if the
  // loop above ever throws part-way, the elements it did not reach have not been decided, and
  // leaving those hidden is the safe half to lose. A knockout alert that appears because a loop
  // broke is worse than a section that stays hidden.
  document.body.classList.remove('fg-conditions-pending')
}
