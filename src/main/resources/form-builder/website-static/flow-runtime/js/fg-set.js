// `<fg-set>`: one question, bound to one fact path.
//
// The markup it wires is rendered server-side, so every branch of the `inputType` switch has a
// matching template under templates/nodes/inputs/. Adding an input type here is two edits, not one.
//
// The switches below are the runtime's built-in types and are deliberately closed: an application
// adds a type through registerInputType() in input-types.js rather than by growing five switch
// statements it does not own. Each `default` consults that registry before warning, so an
// unregistered type still reports itself instead of failing silently.
//
// See docs/internals/flow-runtime.md.

import { fg } from './fact-graph-engine.js'
import { factGraph, saveFactGraph } from './fg-fact-graph.js'
import { showOrHideAllElements } from './fg-conditions.js'
import { getInputType } from './input-types.js'

class FgSet extends HTMLElement {
  constructor () {
    super()

    this.DEFAULT_ERROR_ELEMENT_ID = 'errors.Default'

    this.tabListener = (event) => {
      // Conditions must be re-evaluated before the keydown resolves, so focusable elements update
      // before focus moves. `blur` and `change` fire only after it already has.
      if (event.key === 'Tab') {
        // TODO: Prevent these from being called twice (once here, once through onChange)
        this.setFact()
        showOrHideAllElements()
      }
    }
  }

  connectedCallback () {
    this.condition = this.getAttribute('condition')
    this.operator = this.getAttribute('operator')
    this.inputType = this.getAttribute('inputtype')
    this.inputs = this.querySelectorAll('input, select')
    this.optional = this.getAttribute('optional') === 'true'

    this.attachInputListeners()

    this.path = this.getAttribute('path')
    this.error = null

    console.debug(`Adding fg-set with path ${this.path} of inputType ${this.inputType}`)

    // bind rather than an arrow function, so disconnectedCallback can remove the same reference.
    this.clear = this.clear.bind(this)
    document.addEventListener('fg-clear', this.clear)

    this.render()
  }

  /**
   * Wire whichever events commit this question's answer.
   *
   * A registered input type may supply its own `attach`; anything else — including a registered
   * type that does not — takes the blur/Tab default, recorded in `usingDefaultListeners` so
   * reattachInputListeners() can take it back off again.
   */
  attachInputListeners () {
    const handlers = getInputType(this.inputType)
    if (handlers?.attach) {
      this.usingDefaultListeners = false
      handlers.attach(this)
      return
    }

    switch (this.inputType) {
      // Intentionally not exhaustive. An unlisted type falls through to the blur/tab default.
      case 'date': {
        this.addEventListener('change', () => {
          const allFilled = Array.from(this.inputs).every(input => {
            return input.value.trim() !== '' && input.value !== '- Select -'
          })

          if (allFilled) {
            this.onChange()
          }
        })

        break
      }
      case 'dollar':
        this.addEventListener('input', () => {
          this.onChange()
        })
        break
      case 'select':
      case 'boolean':
      case 'enum':
      case 'multi-enum':
        for (const input of this.inputs) {
          input.addEventListener('change', () => {
            this.onChange()
            this.clearValidationError()
          })
        }
        break
      default:
        this.usingDefaultListeners = true
        this.blurListener = () => this.onChange()
        for (const input of this.inputs) {
          input.addEventListener('blur', this.blurListener)
          input.addEventListener('keydown', this.tabListener)
        }
    }
  }

  /**
   * Called by registerInputType() when a type is registered after this element connected.
   *
   * Only the default listeners are removable — the built-in switch branches above use anonymous
   * arrow functions, but no built-in type is ever re-registered, so they are never the ones being
   * replaced. A registration that arrives late for a type that took the default is the whole case
   * this handles.
   */
  reattachInputListeners () {
    if (this.usingDefaultListeners) {
      for (const input of this.inputs) {
        input.removeEventListener('blur', this.blurListener)
        input.removeEventListener('keydown', this.tabListener)
      }
    }
    this.attachInputListeners()
    this.setInputValueFromFactValue()
  }

  disconnectedCallback () {
    console.debug(`Removing fg-set with path ${this.path}`)
    document.removeEventListener('fg-clear', this.clear)
  }

  clearAlerts () {
    this.querySelector('div.alert--warning')?.remove()
  }

  clearValidationError () {
    const errorElement = this.querySelector('.usa-error-message')
    const errorId = errorElement?.id

    const elementWithDescription = this.querySelector('[aria-describedby]')
    const ariaDescription = elementWithDescription?.getAttribute('aria-describedby')

    if (elementWithDescription) {
      const updatedIds = ariaDescription
        .split(' ')
        .filter(id => id.trim() && id !== errorId)
        .join(' ')

      updatedIds
        ? elementWithDescription.setAttribute('aria-describedby', updatedIds)
        : elementWithDescription.removeAttribute('aria-describedby')
    }

    errorElement?.remove()
    this.querySelector('.validate-alert')?.remove()
    this.querySelector('.usa-form-group')?.classList.remove('usa-form-group--error')
    this.querySelector('.usa-label--error')?.classList.remove('usa-label--error')
    this.querySelectorAll('.usa-input-group, .usa-select, .usa-input').forEach(item => {
      item.classList.remove('usa-input--error')
      item.removeAttribute('aria-describedby')
    })
    this.querySelectorAll('.usa-input[aria-invalid="true"], .usa-select[aria-invalid="true"]').forEach(item => item?.setAttribute('aria-invalid', 'false'))
  }

  setValidationError (errorText) {
    this.clearValidationError()
    const errorId = `${this.path}-error`

    const errorDiv = document.createElement('div')
    errorDiv.classList.add('usa-error-message')
    errorDiv.setAttribute('id', errorId)
    errorDiv.textContent = errorText

    const elementWithDescription = this.querySelector('.usa-fieldset, .usa-select, .usa-input')
    const errorLocation = this.querySelector('.usa-radio, .usa-memorable-date, .usa-checkbox, .usa-select, .usa-input-group, .usa-input')

    errorLocation.insertAdjacentElement('beforebegin', errorDiv)

    // Open the surrounding accordion, so the error is not hidden inside a closed <details>.
    const detailsContent = this.closest('details')
    if (detailsContent && detailsContent.open === false) {
      detailsContent.open = true
    }

    const existingAriaDescribedby = elementWithDescription.getAttribute('aria-describedby')
    elementWithDescription.setAttribute('aria-describedby', `${existingAriaDescribedby || ''} ${errorId}`.trim())

    this.querySelector('.usa-form-group')?.classList.add('usa-form-group--error')
    this.querySelector('.usa-legend, .usa-label')?.classList.add('usa-label--error')
    this.querySelectorAll('.usa-input-group, .usa-select, .usa-input').forEach(item => {
      item.classList.add('usa-input--error')
      item.setAttribute('aria-describedby', `${errorId}`)
    })
    this.querySelectorAll('.usa-input[aria-invalid="false"], .usa-select[aria-invalid="false"]').forEach(item => {
      item.setAttribute('aria-invalid', 'true')
    })
  }

  validateRequiredFields () {
    const isMissing = !this.isComplete()
    if (isMissing) {
      this.setValidationError('This question is required')
    } else {
      this.clearValidationError()
    }
    return isMissing
  }

  render () {
    this.setInputValueFromFactValue()
  }

  onChange () {
    const proposedValue = this.getFactValueFromInputValue()
    const beforeCommit = new CustomEvent('fg-set-before-commit', {
      bubbles: true,
      cancelable: true,
      detail: { path: this.path, proposedValue }
    })
    this.dispatchEvent(beforeCommit)
    if (beforeCommit.defaultPrevented) {
      this.setInputValueFromFactValue()
      return
    }
    try {
      const res = this.setFact()
      if (res.errorType) {
        const errorTextKey = `errors.${res.errorName}`
        const errorElement = document.getElementById(errorTextKey) || document.getElementById(this.DEFAULT_ERROR_ELEMENT_ID)
        // Appended so "Enter an amount more than" reads as a sentence. A Match limit's expected
        // value is a regular expression, so it is left off.
        const suffix = res.errorName === 'Match' ? '' : ' ' + (res.expectedValue || '')
        this.setValidationError(errorElement.innerText + suffix)
      } else {
        this.clearValidationError()
      }
    } catch (error) {
      this.setValidationError(error.message)
    }
  }

  isComplete () {
    return factGraph.get(this.path).complete
  }

  clear () {
    switch (this.inputType) {
      case 'boolean':
      case 'enum': {
        const checkedRadio = this.querySelector('input:checked')
        if (checkedRadio) {
          checkedRadio.checked = false
        };
        break
      }
      case 'multi-enum': {
        const checkedBoxes = this.querySelectorAll('input:checked')
        for (const checkbox of checkedBoxes) {
          checkbox.checked = false
        }
        break
      }
      case 'select': {
        this.querySelector('select').value = ''
        break
      }
      case 'text':
      case 'date': {
        this.querySelector('select[name*="-month"]').value = ''
        this.querySelector('input[name*="-day"]').value = ''
        this.querySelector('input[name*="-year"]').value = ''
        break
      }
      case 'int':
      case 'dollar': {
        this.querySelector('input').value = ''
        break
      }
      default: {
        const handlers = getInputType(this.inputType)
        if (handlers) handlers.clear(this)
        else console.warn(`Unknown input type "${this.inputType}" for input with path "${this.path}"`)
      }
    }

    this.error = null
    this.clearAlerts()
    this.clearValidationError()
  }

  setInputValueFromFactValue () {
    console.debug(`Setting input value for ${this.path} of type ${this.inputType}`)
    const fact = factGraph.get(this.path)

    let value
    if (fact.complete === false) {
      value = ''
    } else {
      value = fact.get?.toString()
    }

    switch (this.inputType) {
      case 'boolean':
      case 'enum': {
        if (value !== '') {
          // CSS.escape, so a numeric value such as 2024 is still a valid selector.
          const input = this.querySelector(`input[value="${CSS.escape(value)}"]`)
          if (input) input.checked = true
        }
        break
      }
      case 'multi-enum': {
        const selectedValues = fact.hasValue ? fg.scalaSetToJsSet(fact.get.getValue()) : new Set()
        const checkboxes = this.querySelectorAll('input[type="checkbox"]')
        for (const checkbox of checkboxes) {
          checkbox.checked = selectedValues.has(checkbox.value)
        }
        break
      }
      case 'select': {
        this.querySelector('select').value = value
        break
      }
      case 'text':
      case 'int': {
        this.querySelector('input').value = value
        break
      }
      case 'date': {
        const monthSelect = this.querySelector('select[name*="-month"]')
        const dayInput = this.querySelector('input[name*="-day"]')
        const yearInput = this.querySelector('input[name*="-year"]')

        if (value) {
          const [year, month, day] = value.split('-')
          monthSelect.value = month
          dayInput.value = day
          yearInput.value = year
        } else if (!monthSelect.value && !dayInput.value && !yearInput.value) {
          // Clear only when the inputs are genuinely empty, so a partly typed date survives fg-update.
          monthSelect.value = ''
          dayInput.value = ''
          yearInput.value = ''
        }
        break
      }
      case 'dollar': {
        this.querySelector('input').value = value
        break
      }
      default: {
        const handlers = getInputType(this.inputType)
        if (handlers) handlers.write(this, value, fact)
        else console.warn(`Unknown input type "${this.inputType}" for input with path "${this.path}"`)
      }
    }
  }

  getFactValueFromInputValue () {
    console.debug(`Getting input value for ${this.path} of type ${this.inputType}`)
    switch (this.inputType) {
      case 'boolean':
      case 'enum': {
        return this.querySelector('input:checked')?.value
      }
      case 'multi-enum': {
        const checkboxes = this.querySelectorAll('input[type="checkbox"]:checked')
        const values = new Set(Array.from(checkboxes).map(cb => cb.value))
        // null rather than an empty Set, so an unanswered multi-enum reads as absent.
        return values.size > 0 ? fg.MultiEnum(fg.jsSetToScalaSet(values), '') : null
      }
      case 'select': {
        return this.querySelector('select')?.value
      }
      case 'date': {
        const month = this.querySelector('select[name*="-month"]')?.value
        const day = this.querySelector('input[name*="-day"]')?.value
        const year = this.querySelector('input[name*="-year"]')?.value
        // padStart, so a day typed as 1 is sent as 01.
        return `${year}-${month}-${day.padStart(2, '0')}`
      }
      case 'text':
      case 'int':
      case 'dollar': {
        return this.querySelector('input')?.value
      }
      default: {
        const handlers = getInputType(this.inputType)
        if (handlers) return handlers.read(this)
        console.warn(`Unknown input type "${this.inputType}" for input with path "${this.path}"`)
        return undefined
      }
    }
  }

  setFact () {
    console.debug(`Setting fact ${this.path}`)
    const value = this.getFactValueFromInputValue()

    let res = {}
    if (value === '' || value === null) {
      console.debug('Value was blank, deleting fact')
      factGraph.delete(this.path)
    } else {
      res = factGraph.set(this.path, value)
    }

    saveFactGraph()
    document.dispatchEvent(new CustomEvent('fg-update'))
    return res
  }

  /** Dispatching fg-update from here would recurse: this runs while handling one. */
  deleteFactNoUpdate () {
    console.debug(`Deleting fact ${this.path}`)

    switch (this.inputType) {
      case 'boolean':
      case 'enum': {
        const input = this.querySelector('input:checked')
        if (input) input.checked = false
        break
      }
      case 'multi-enum': {
        const checkboxes = this.querySelectorAll('input[type="checkbox"]:checked')
        for (const checkbox of checkboxes) {
          checkbox.checked = false
        }
        break
      }
      case 'select': {
        this.querySelector('select').value = ''
        break
      }
      case 'text':
      case 'date':
      case 'int':
      case 'dollar': {
        this.querySelector('input').value = ''
        break
      }
      default: {
        const handlers = getInputType(this.inputType)
        if (handlers) handlers.clear(this)
        else console.warn(`Unknown input type "${this.inputType}" for input with path "${this.path}"`)
      }
    }
    factGraph.delete(this.path)
    saveFactGraph()
  }
}
customElements.define('fg-set', FgSet)
