// Page-level validation and the focus moves that go with it: the summary alert, the first invalid
// field, and a visible knockout. See docs/internals/flow-runtime.md.

/**
 * The knockout alert that is actually on screen, or null.
 *
 * `:not(.hidden)` is not enough on its own, and the difference is a page nobody can leave. A
 * condition hides the element that *carries* it, and a knockout's condition is often on an ancestor
 * rather than on the alert — a transpiled flow wraps each source screen in a conditional `<div>` and
 * gives the alert inside it a condition that is always true. So the wrapper gets `.hidden`, the alert
 * does not, and a selector that only reads the alert's own class finds a knockout the user cannot
 * see. Continue then refuses to navigate and says nothing, because from its point of view the
 * taxpayer is knocked out.
 *
 * `closest('.hidden')` is how the `<fg-set>` check in validateSectionForNavigation has always read
 * the same question; this makes the knockout check agree with it.
 */
export function visibleKnockoutAlert () {
  for (const alert of document.querySelectorAll('fg-alert[knockout="true"]:not(.hidden)')) {
    if (!alert.closest('.hidden')) return alert
  }
  return null
}

export function focusKnockoutAlert (knockoutAlert) {
  const heading = knockoutAlert.querySelector('.usa-alert__heading')
  const target = heading ?? knockoutAlert
  target.scrollIntoView({ behavior: 'instant', block: 'center' })
  target.setAttribute('tabindex', '-1')
  target.focus()
  target.addEventListener('blur', () => {
    target.removeAttribute('tabindex')
  }, { once: true })
}

export function showValidationError () {
  const existingAlert = document.querySelector('.validate-alert')
  if (existingAlert) {
    existingAlert.remove()
  }
  // #validate-alert-template is rendered by fragments/js-templates.html.
  const template = document.getElementById('validate-alert-template')
  const alertElement = template.content.cloneNode(true)
  const mainContent = document.getElementById('main-content')
  mainContent.insertBefore(alertElement, mainContent.firstChild)

  const firstErrorFocusTarget = document.querySelector(
    'fg-alert[blocking]:not(.hidden) :is(.usa-alert__heading, .usa-alert__text),' +
    'fg-set:not(.hidden) .usa-form-group--error .usa-fieldset,' +
    'fg-set:not(.hidden) [aria-invalid="true"]'
  )

  firstErrorFocusTarget.scrollIntoView({ behavior: 'instant', block: 'center' })
  if (firstErrorFocusTarget instanceof HTMLFieldSetElement || firstErrorFocusTarget.closest('fg-alert')) {
    firstErrorFocusTarget.setAttribute('tabindex', '-1')
    firstErrorFocusTarget.focus()

    // Removed after focus, so the outline does not persist on later clicks.
    firstErrorFocusTarget.addEventListener('blur', () => {
      firstErrorFocusTarget.removeAttribute('tabindex')
    }, { once: true })
  } else { firstErrorFocusTarget.focus() }
}

export function validateSectionForNavigation () {
  const fgSets = document.querySelectorAll('fg-set:not(.hidden)')
  const missingFields = []
  let hasValidationErrors = false

  for (const fgSet of fgSets) {
    // Blocking only if not optional, not complete, and not inside a hidden element.
    if (!fgSet.optional && !fgSet.isComplete() && !fgSet.closest('.hidden')) {
      const fieldName = fgSet.path
      missingFields.push(fieldName)
      if (!fgSet.validateRequiredFields()) {
        hasValidationErrors = false
      }
    }
  }
  if (missingFields.length > 0 || hasValidationErrors) {
    showValidationError()
    return false
  }

  const knockoutAlert = visibleKnockoutAlert()
  if (knockoutAlert) {
    focusKnockoutAlert(knockoutAlert)
    return false
  }

  return true
}
