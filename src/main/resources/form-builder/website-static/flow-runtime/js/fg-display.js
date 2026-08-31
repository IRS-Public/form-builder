// The read-only and side-effecting display elements: `<fg-show>`, `<fg-reset>` and `<fg-apply>`.
//
// See docs/internals/flow-runtime.md.

import { storageKey } from './runtime-config.js'
import { factGraph, saveFactGraph } from './fg-fact-graph.js'
import { appBasePath } from './runtime-paths.js'

/** `<fg-show path="…">`: the current value of a fact, formatted, re-rendered on every fg-update. */
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
    // The mangled field names are the Scala.js encoding of MaybeVector.Multiple.
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

/** `<fg-reset>`: a button that drops the stored Fact Graph. */
class FgReset extends HTMLElement {
  connectedCallback () {
    this.addEventListener('click', this)
  }

  handleEvent () {
    sessionStorage.removeItem(storageKey('factGraph'))
    // In place keeps the current mode and query string. Only the linear flow restarts at page one.
    const path = window.location.pathname
    if (path.includes('/all-screens/') || path.includes('/author/')) window.location.reload()
    else window.location = `${appBasePath()}/`
  }
}
customElements.define('fg-reset', FgReset)

/**
 * `<fg-apply path="…">`: write into the graph as the page renders it, so a page reached only under
 * a condition can assert the fact that condition implies.
 *
 * The value comes from either `value="…"`, a literal, or `source="/otherPath"`, the current value of
 * another fact. The parser guarantees exactly one of the two is present.
 *
 * An incomplete source writes nothing rather than writing an empty value. The alternative would
 * make `<fg-apply source>` a way to silently clear a fact whenever the page happens to render
 * before the source is answered, which is the opposite of what copying one fact into another means.
 */
class FgApply extends HTMLElement {
  connectedCallback () {
    const path = this.getAttribute('path')
    const source = this.getAttribute('source')

    let value
    if (source) {
      const fact = factGraph.get(source)
      if (!fact.complete) {
        console.debug(`Not setting fact ${path} from fg-apply: source ${source} is incomplete`)
        return
      }
      value = fact.get?.toString()
    } else {
      value = this.getAttribute('value')
    }

    console.debug(`Setting fact ${path} to ${value} from fg-apply`)
    factGraph.set(path, value)
    saveFactGraph()
    document.dispatchEvent(new CustomEvent('fg-update'))
  }
}
customElements.define('fg-apply', FgApply)
