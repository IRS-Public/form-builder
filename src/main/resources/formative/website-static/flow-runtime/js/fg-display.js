import { storageKey } from './runtime-config.js'
import { factGraph, saveFactGraph } from './fg-fact-graph.js'
import { appBasePath } from './runtime-paths.js'

/*
 * <fg-show> - Display the current value and/or status of a fact.
 */
class FgShow extends HTMLElement {
  constructor () {
    super()
    this.updateListener = () => this.render()
  }

  connectedCallback () {
    this.path = this.getAttribute('path')
    document.addEventListener('fg-update', this.updateListener)
    this.render()
  }

  disconnectedCallback () {
    document.removeEventListener('fg-update', this.updateListener)
  }

  render () {
    // This is a temporary enhancement to allow showing all values of a fact without knowing the collection id
    const results = (this.path.indexOf('*') !== -1)
      ? factGraph.getVect(this.path).Lgov_irs_factgraph_monads_MaybeVector$Multiple__f_vect.sci_Vector__f_prefix1.u
      : [factGraph.get(this.path)]

    let outputHtml = ''
    for (const result of results) {
      if (outputHtml !== '') outputHtml += ', '
      if (result.hasValue) {
        const value = result.get.toString()
        if (result.get.s_math_BigDecimal__f_bigDecimal) {
          const minimumFractionDigits = (value % 1 === 0) ? 0 : 2
          const options = { style: 'currency', currency: 'USD', minimumFractionDigits }
          outputHtml += new Intl.NumberFormat('en-US', options).format(value)
        } else {
          outputHtml += value
        }
      } else {
        outputHtml += '<span class="text-base">-</span>'
      }
    }

    // Data-derived: a list of formatted fact values, with a placeholder span for the ones the
    // graph has no value for. There is no fixed markup here for a <template> to hold.
    // eslint-disable-next-line no-restricted-syntax
    this.innerHTML = outputHtml
  }
}
customElements.define('fg-show', FgShow)

/*
 * <fg-reset> - button to reset the Fact Graph.
 */
class FgReset extends HTMLElement {
  connectedCallback () {
    this.addEventListener('click', this)
  }

  handleEvent () {
    sessionStorage.removeItem(storageKey('factGraph'))
    // Reloading in place keeps the current mode context (Browse All, Scenario, Authoring),
    // including its query string. Only the linear flow needs to restart at the first page.
    const path = window.location.pathname
    if (path.includes('/all-screens/') || path.includes('/author/')) window.location.reload()
    else window.location = `${appBasePath()}/`
  }
}
customElements.define('fg-reset', FgReset)

/*
 * <fg-apply> - Write a literal into the Fact Graph as soon as the page renders it.
 *
 * The flow's way of asserting something the taxpayer was never asked: a page reached only under a
 * condition can state the fact that condition implies. It is the browser half of the scaffold's
 * `<fg-apply>` flow node.
 */
class FgApply extends HTMLElement {
  connectedCallback () {
    const path = this.getAttribute('path')
    const value = this.getAttribute('value')
    console.debug(`Setting fact ${path} to ${value} from fg-apply`)
    factGraph.set(path, value)
    saveFactGraph()
    document.dispatchEvent(new CustomEvent('fg-update'))
  }
}
customElements.define('fg-apply', FgApply)
