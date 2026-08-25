// Author Mode client — renders the structured-form editor from the embedded
// authoring server's model, and drives live validate / save. Commits happen
// from the CLI, not this UI.
//
// ─── API CONTRACT (must stay in sync with authoring/AuthoringServer.scala) ────
//
// Base URL: the authoring server runs on its own port (3004 by default, see AUTHOR_PORT below)
// while this page is served by the app's own, so every request is an absolute cross-origin fetch
// to AUTHOR_API_BASE. The backend must send permissive CORS headers (see below).
//
//   GET  /author/model    → {
//     screens:  [ { route, title,
//                   fgSets: [ { path, question, hint, inputType, factType,
//                               validInputTypes: [ '...' ], ifTrue, ifFalse } ],
//                   alerts: [ { id, text, alertType, knockout, condition, operator } ] } ],
//     facts:    [ { path, description, kind, constantValue, constantType,
//                   type, placeholder: { valueType, value } | null,
//                   limits: [ { limitType, valueType, value } ], file } ],
//     writablePaths: [ "/..." ],
//     booleanPaths:  [ "/..." ],
//     numericPaths:  [ "/..." ]
//   }
//
//   GET  /author/lint     → { warnings: [ { message, route } ] }
//
// A screen (page) can carry several `<fg-set>` blocks — one per collection item or
// income source — each with its own question/hint, so they're modeled as a list keyed
// by the fg-set's (unique) fact `path`, not a single question/hint per screen.
//
//   POST /author/validate  body { target, edit } → { ok, errors: [ { field, message } ] }
//   POST /author/save      body { target, edit } → { ok, errors: [ { field, message } ] }
//
// The edit-payload shape sent to /author/validate and /author/save:
//
//   target = {
//     kind:  'constant' | 'factDescription' | 'screenText'   // MVP
//          | 'screenAttr' | 'alertAttr' | 'factConfig'       // v1 structural
//          | 'derived',                                      // v2 computation-tree (T18)
//     path:  '<fact path>',      // constant | factDescription | factConfig | derived
//                                //   | screenText/screenAttr (question/hint/input/gating: identifies the fg-set)
//     file:  '<source file>',    // constant | factDescription | factConfig | derived (echoed from model)
//     route: '<screen route>',   // screenText | screenAttr | alertAttr
//     field: 'question' | 'hint' | 'alert'          // screenText
//          | 'inputType' | 'path' | 'gating'        // screenAttr (fg-set)
//          | 'alertType' | 'knockout' | 'condition' | 'operator'  // alertAttr
//          | 'placeholder' | 'limitMin' | 'limitMax',            // factConfig
//     alertId: '<alert id>'      // screenText (field==='alert') | alertAttr
//   }
//   edit = { value: '<new string>', polarity?: 'if-true' | 'if-false' | 'none',  // scalar/attribute edits
//            tree?: <DerivedNode> }                                              // kind==='derived' only
//
// A DerivedNode is { tag, attrs: [ [name, value] ], text: string|null, children: [ DerivedNode ] } —
// a generic, structure-preserving mirror of a fact-graph computation subtree.
//
// ─── v2 computation-tree endpoints (T18) ─────────────────────────────────────
//   GET  /author/derived?path=<fact> → {
//     path, tree: <DerivedNode>|null,
//     palette: [ { tag, label, category, valueKind, attrs:[{name,label,kind}], childTags, slotOnly } ],
//     allPaths: [ '/...' ], booleanPaths: [ '/...' ], numericPaths: [ '/...' ]
//   }
//
// ─── v2 create endpoints (T17) — each returns the { ok, errors } envelope ─────
//   POST /author/create-fact    body { path, file, description, kind, valueType?, constantValue?,
//                                       tree?, save } → { ok, errors }
//   POST /author/create-screen  body { module, route, title,
//                                       firstQuestion?: { path, question, inputType }, save } → { ok, errors }
//
// ─── v2 delete endpoints (T19) ────────────────────────────────────────────────
//   GET  /author/fact-usage?path=<fact> → {
//     path, exists, factDependents: [ '/...' ],
//     flowReferences: [ { route, where } ], canDelete
//   }
//   POST /author/delete-fact    body { path, save } → { ok, errors }   // hard-blocked if referenced
//
// One target === one editable field === one error slot. Errors in the response
// apply to whichever field the request was for; all messages are shown inline.
// ──────────────────────────────────────────────────────────────────────────────

// The authoring server's origin. Its port is the server's to state, not this file's: it is bound
// from -Dsmol.author.port (AuthoringServer.configuredPort) and rendered into the page by
// generators/AuthorMode as <meta name="form-builder:author-port">. This mirrors how the flow runtime
// takes form-builder:storage-prefix and form-builder:base-path — see runtime-config.js.
//
// Read once at module scope rather than lazily, because this page cannot exist without a document
// and the tag is in the <head> above this module. The fallback is the same default the server
// falls back to, so a page generated before the tag existed still works.
//
// The host stays localhost. The API is bound to loopback on purpose — it patches source XML on
// disk — so reaching it from another machine is not a thing to support here.
const AUTHOR_PORT =
  document.querySelector('meta[name="form-builder:author-port"]')?.content || '3004'
const AUTHOR_API_BASE = `http://localhost:${AUTHOR_PORT}`

// Centralized model-field access. The backend agent may rename fields; if so,
// update these accessors (one line each) and nothing else in this file breaks.
const field = {
  screens: (m) => m.screens || [],
  facts: (m) => m.facts || [],
  writablePaths: (m) => m.writablePaths || [],
  booleanPaths: (m) => m.booleanPaths || [],
  factPath: (f) => f.path,
  factDescription: (f) => f.description,
  factKind: (f) => f.kind,
  factFile: (f) => f.file,
  factType: (f) => f.type,
  factPlaceholder: (f) => f.placeholder,
  factLimits: (f) => f.limits || [],
  constantValue: (f) => f.constantValue,
  constantType: (f) => f.constantType,
  screenRoute: (s) => s.route,
  screenTitle: (s) => s.title,
  screenFgSets: (s) => s.fgSets || [],
  screenAlerts: (s) => s.alerts || [],
  fgSetPath: (b) => b.path,
  fgSetQuestion: (b) => b.question,
  fgSetHint: (b) => b.hint,
  fgSetInputType: (b) => b.inputType,
  fgSetValidInputTypes: (b) => b.validInputTypes || [],
  fgSetIfTrue: (b) => b.ifTrue,
  fgSetIfFalse: (b) => b.ifFalse,
  alertId: (a) => a.id,
  alertText: (a) => a.text,
  alertType: (a) => a.alertType,
  alertKnockout: (a) => a.knockout,
  alertCondition: (a) => a.condition,
  alertOperator: (a) => a.operator
}

// Fixed vocabularies mirrored from FlowConfig.rng (the schema is the source of truth;
// the backend re-validates every edit, so these only shape the dropdowns).
const ALL_INPUT_TYPES = ['dollar', 'text', 'date', 'int', 'boolean', 'enum', 'multi-enum', 'select']
const ALERT_TYPES = ['error', 'info', 'warning', 'success', 'emergency']
const ALERT_OPERATORS = ['isTrue', 'isFalse']
// fact-graph type node → the scalar type placeholders/limits use. A fact whose type
// isn't here (Boolean, Enum, Collection…) has no simple placeholder/limit to author.
const SCALAR_TYPE_NODES = {
  DollarNode: 'Dollar',
  IntNode: 'Int',
  RationalNode: 'Rational',
  DayNode: 'Day',
  StringNode: 'String'
}

const VALIDATE_DEBOUNCE_MS = 350

// Connecting to the Author Mode backend is retried with backoff: on `make up` the backend
// (the application’s `-watch` container running `sbt ~run`) only binds its port after a cold JVM
// start + full Scala compile + site regeneration — often a minute or two — and it restarts on
// every source edit. Rather than fail on the first miss, poll `/author/health` with growing
// backoff so the panel connects as soon as the server is up. The schedule (geometric, capped)
// spans well over two minutes to cover a cold Docker compile.
const CONNECT_MAX_ATTEMPTS = 24
const CONNECT_BACKOFF_START_MS = 750
const CONNECT_BACKOFF_MAX_MS = 6000

/** Build an absolute authoring-server URL for a path like '/author/model'. */
function apiUrl (path) {
  return `${AUTHOR_API_BASE}${path}`
}

// Every /author/* endpoint responds with a JSON envelope even on failure (ok:false
// plus errors/stderr) — see AuthoringServer's jsonHandler, which turns even an
// uncaught server exception into a 500 with a JSON body. So postJson/getJson only
// throw when the request never reached a server at all (fetch's own TypeError) or
// the body genuinely isn't JSON; an HTTP error status alone is not grounds to throw,
// since callers need the envelope's own error detail, not a generic "unreachable".

/** POST JSON and parse the JSON response, regardless of HTTP status. */
async function postJson (path, body) {
  const response = await fetch(apiUrl(path), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  return response.json()
}

/** GET a JSON resource from the authoring server, regardless of HTTP status. */
async function getJson (path) {
  const response = await fetch(apiUrl(path))
  return response.json()
}

/** Show the top-of-page status banner with a message and an alert flavor. */
function setStatus (message, kind = 'info') {
  const banner = document.getElementById('author-status')
  const text = document.getElementById('author-status-text')
  if (!banner || !text) return
  banner.hidden = false
  banner.classList.remove('usa-alert--info', 'usa-alert--warning', 'usa-alert--error', 'usa-alert--success')
  banner.classList.add(`usa-alert--${kind}`)
  text.textContent = message
}

let debounceTimer = null
function debounce (fn) {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(fn, VALIDATE_DEBOUNCE_MS)
}

/** Join the messages from an errors array into a single inline string. */
function errorText (errors) {
  if (!Array.isArray(errors) || errors.length === 0) return ''
  return errors.map((e) => e.message).join(' ')
}

/** Warn in the status banner when a mid-session request can't reach the backend — most often
 * because it's briefly restarting after a source edit (`sbt ~run` recompiles + regenerates, which
 * drops its port for a few seconds). The panel doesn't auto-reconnect mid-session, so guide the
 * user to retry the action once it's back.
 */
function reportUnreachable (verb) {
  setStatus(
    `Couldn’t reach the authoring server to ${verb}. If you just edited a source file it’s probably ` +
    `restarting (recompiling on port ${AUTHOR_PORT}) — wait a few seconds and try again, or reload the page. ` +
    'Otherwise make sure `make dev-author` (or the application’s watcher container) is running.',
    'warning'
  )
}

let fieldCounter = 0

/**
 * The shared validate/save workflow behind every editor. Renders a labeled row of one
 * or more controls + an inline error + a Save button, and wires:
 *   - on input/change of any control → debounced POST /author/validate
 *   - Save click → POST /author/save
 * The Save button enables only when the edit is valid AND changed from its saved state.
 *
 * @param {object} opts
 * @param {string} opts.label
 * @param {HTMLElement[]} opts.controls  controls to render, in order
 * @param {() => string} opts.signature  a string snapshot of the current control values (dirty check)
 * @param {() => object} opts.getPayload builds the { target, edit } sent to validate/save
 * @param {string} [opts.hint]           small helper text under the label
 * @returns {HTMLElement}
 */
function buildEditor (opts) {
  const id = `author-field-${fieldCounter++}`
  const wrapper = document.createElement('div')
  wrapper.className = 'author__field usa-form-group'

  const label = document.createElement('label')
  label.className = 'usa-label'
  label.setAttribute('for', opts.controls[0].id || id)
  label.textContent = opts.label
  wrapper.appendChild(label)

  if (opts.hint) {
    const hint = document.createElement('span')
    hint.className = 'usa-hint author__field-hint'
    hint.textContent = opts.hint
    wrapper.appendChild(hint)
  }

  const errorId = `${id}-error`
  for (const control of opts.controls) {
    control.setAttribute('aria-describedby', errorId)
    wrapper.appendChild(control)
  }

  const error = document.createElement('span')
  error.className = 'author__field-error usa-error-message'
  error.id = errorId
  error.hidden = true
  wrapper.appendChild(error)

  const actions = document.createElement('div')
  actions.className = 'author__field-actions'
  const saveBtn = document.createElement('button')
  saveBtn.type = 'button'
  saveBtn.className = 'usa-button usa-button--outline'
  saveBtn.textContent = 'Save'
  saveBtn.disabled = true
  const savedNote = document.createElement('span')
  savedNote.className = 'author__field-saved'
  savedNote.setAttribute('role', 'status')
  actions.appendChild(saveBtn)
  actions.appendChild(savedNote)
  wrapper.appendChild(actions)

  const original = opts.signature()
  let valid = true

  const showErrors = (errors) => {
    const message = errorText(errors)
    if (message) {
      error.textContent = message
      error.hidden = false
      valid = false
    } else {
      error.textContent = ''
      error.hidden = true
      valid = true
    }
    for (const control of opts.controls) {
      control.setAttribute('aria-invalid', String(!valid))
    }
    saveBtn.disabled = !valid || opts.signature() === original
  }

  // Debounced validates can overlap; a stale run's result must never overwrite a newer one, or a
  // transient error (a momentarily-invalid intermediate edit) sticks after the field is valid
  // again. Only the latest run, tracked by this monotonic token, paints the error state.
  let validateSeq = 0
  const runValidate = async () => {
    const seq = ++validateSeq
    try {
      const result = await postJson('/author/validate', opts.getPayload())
      if (seq !== validateSeq) return
      showErrors(result.ok ? [] : result.errors)
    } catch (err) {
      if (seq !== validateSeq) return
      console.error('validate failed', err)
      reportUnreachable('validate')
    }
  }

  for (const control of opts.controls) {
    for (const evt of ['input', 'change']) {
      control.addEventListener(evt, () => {
        savedNote.textContent = ''
        saveBtn.disabled = true
        debounce(runValidate)
      })
    }
  }

  saveBtn.addEventListener('click', async () => {
    saveBtn.disabled = true
    try {
      const result = await postJson('/author/save', opts.getPayload())
      if (result.ok) {
        showErrors([])
        savedNote.textContent = 'Saved. Refresh Product Experience or Browse All to preview.'
        saveBtn.disabled = true
      } else {
        showErrors(result.errors)
      }
    } catch (err) {
      console.error('save failed', err)
      reportUnreachable('save')
      saveBtn.disabled = false
    }
  })

  return wrapper
}

// ─── Control factories ────────────────────────────────────────────────────────

/** A text <input> or <textarea>, pre-filled with `value`. */
function makeTextControl (value, { multiline, numeric } = {}) {
  const control = multiline ? document.createElement('textarea') : document.createElement('input')
  control.id = `author-field-${fieldCounter++}`
  control.className = multiline ? 'usa-textarea' : 'usa-input'
  control.value = value == null ? '' : String(value)
  control.setAttribute('aria-invalid', 'false')
  if (!multiline && numeric) control.setAttribute('inputmode', 'decimal')
  return control
}

/**
 * A <select> pre-selecting `selected`. `options` is an array of strings or
 * { value, label } objects. When `blankLabel` is given, a leading empty option is added.
 */
function makeSelectControl (options, selected, { blankLabel } = {}) {
  const control = document.createElement('select')
  control.id = `author-field-${fieldCounter++}`
  control.className = 'usa-select'
  if (blankLabel != null) {
    const blank = document.createElement('option')
    blank.value = ''
    blank.textContent = blankLabel
    control.appendChild(blank)
  }
  for (const opt of options) {
    const value = typeof opt === 'string' ? opt : opt.value
    const text = typeof opt === 'string' ? opt : opt.label
    const option = document.createElement('option')
    option.value = value
    option.textContent = text
    control.appendChild(option)
  }
  control.value = selected == null ? '' : String(selected)
  return control
}

// ─── Editor builders (compose controls + the shared workflow) ───────────────────

/** Single free-text field editor (question, hint, alert text, description). */
function buildTextEditor ({ label, hint, initialValue, multiline, numeric, target }) {
  const control = makeTextControl(initialValue, { multiline, numeric })
  return buildEditor({
    label,
    hint,
    controls: [control],
    signature: () => control.value,
    getPayload: () => ({ target, edit: { value: control.value } })
  })
}

/** Single-select field editor (input type, alert type, knockout, condition, operator, path rebind). */
function buildSelectEditor ({ label, hint, options, selected, blankLabel, target }) {
  const control = makeSelectControl(options, selected, { blankLabel })
  return buildEditor({
    label,
    hint,
    controls: [control],
    signature: () => control.value,
    getPayload: () => ({ target, edit: { value: control.value } })
  })
}

/**
 * The fg-set gating editor: a polarity select ("Always shown" / "Show when true" /
 * "Show when false") plus a Boolean-fact select. They're saved together as one
 * `screenAttr/gating` edit so if-true and if-false never coexist.
 */
function buildGatingEditor ({ route, fgSetPath, booleanPaths, ifTrue, ifFalse }) {
  const initialPolarity = ifTrue ? 'if-true' : ifFalse ? 'if-false' : 'none'
  const initialPath = ifTrue || ifFalse || ''

  const polarity = makeSelectControl(
    [
      { value: 'none', label: 'Always shown' },
      { value: 'if-true', label: 'Show only when a fact is true' },
      { value: 'if-false', label: 'Show only when a fact is false' }
    ],
    initialPolarity
  )
  const gatePath = makeSelectControl(booleanPaths, initialPath, { blankLabel: 'Select a Boolean fact…' })
  gatePath.disabled = initialPolarity === 'none'

  polarity.addEventListener('change', () => {
    gatePath.disabled = polarity.value === 'none'
    if (polarity.value === 'none') gatePath.value = ''
  })

  return buildEditor({
    label: 'Show this question when…',
    hint: 'Gate the question on a Boolean fact. "Always shown" clears any condition.',
    controls: [polarity, gatePath],
    signature: () => `${polarity.value}|${gatePath.value}`,
    getPayload: () => ({
      target: { kind: 'screenAttr', route, path: fgSetPath, field: 'gating' },
      edit: { value: polarity.value === 'none' ? '' : gatePath.value, polarity: polarity.value }
    })
  })
}

/** Replace the children of a container element with a single node (or clear it). */
function replaceChildren (container, node) {
  container.textContent = ''
  if (node) container.appendChild(node)
}

/** Populate a <select> with options built from items, keyed by a Map for lookup. */
function populateSelect (select, items, valueOf, labelOf) {
  const lookup = new Map()
  for (const item of items) {
    const value = valueOf(item)
    lookup.set(value, item)
    const option = document.createElement('option')
    option.value = value
    option.textContent = labelOf(item)
    select.appendChild(option)
  }
  return lookup
}

// ─── Section renderers ────────────────────────────────────────────────────────

function renderConstantEditor (model) {
  const select = document.getElementById('author-constant-select')
  const fields = document.getElementById('author-constant-fields')
  if (!select || !fields) return

  const constants = field.facts(model).filter((f) => field.factKind(f) === 'constant')
  const lookup = populateSelect(
    select,
    constants,
    (f) => field.factPath(f),
    (f) => `${field.factPath(f)}  (${field.constantType(f) || 'value'})`
  )

  select.addEventListener('change', () => {
    const fact = lookup.get(select.value)
    if (!fact) return replaceChildren(fields, null)
    const editor = buildTextEditor({
      label: `Value — ${field.constantType(fact) || 'constant'} in ${field.factFile(fact) || 'source XML'}`,
      initialValue: field.constantValue(fact),
      numeric: true,
      target: {
        kind: 'constant',
        path: field.factPath(fact),
        file: field.factFile(fact)
      }
    })
    replaceChildren(fields, editor)
  })
}

function renderDescriptionEditor (model) {
  const select = document.getElementById('author-description-select')
  const fields = document.getElementById('author-description-fields')
  if (!select || !fields) return

  const facts = field.facts(model)
  const lookup = populateSelect(
    select,
    facts,
    (f) => field.factPath(f),
    (f) => field.factPath(f)
  )

  select.addEventListener('change', () => {
    const fact = lookup.get(select.value)
    if (!fact) return replaceChildren(fields, null)
    const editor = buildTextEditor({
      label: `Description — ${field.factPath(fact)}`,
      initialValue: field.factDescription(fact),
      multiline: true,
      target: {
        kind: 'factDescription',
        path: field.factPath(fact),
        file: field.factFile(fact)
      }
    })
    replaceChildren(fields, editor)
  })
}

/**
 * Fact configuration (T15): edit a writable scalar fact's default (<Placeholder>) and
 * its Min/Max validation bounds (<Limit>). Only writable facts with a simple scalar
 * type (Dollar/Int/Rational/Day/String) are offered. Clearing a field removes it.
 */
function renderFactConfigEditor (model) {
  const select = document.getElementById('author-factconfig-select')
  const fields = document.getElementById('author-factconfig-fields')
  if (!select || !fields) return

  const facts = field
    .facts(model)
    .filter((f) => field.factKind(f) === 'writable' && SCALAR_TYPE_NODES[field.factType(f)])
  const lookup = populateSelect(
    select,
    facts,
    (f) => field.factPath(f),
    (f) => `${field.factPath(f)}  (${SCALAR_TYPE_NODES[field.factType(f)]})`
  )

  select.addEventListener('change', () => {
    const fact = lookup.get(select.value)
    fields.textContent = ''
    if (!fact) return

    const path = field.factPath(fact)
    const file = field.factFile(fact)
    const numeric = ['Dollar', 'Int', 'Rational'].includes(SCALAR_TYPE_NODES[field.factType(fact)])
    const limits = field.factLimits(fact)
    const placeholder = field.factPlaceholder(fact)
    const limitOf = (type) => (limits.find((l) => l.limitType === type) || {}).value || ''

    fields.appendChild(buildTextEditor({
      label: 'Default value (placeholder)',
      hint: 'The value used when the filer leaves this blank. Clear to remove.',
      initialValue: placeholder ? placeholder.value : '',
      numeric,
      target: { kind: 'factConfig', path, file, field: 'placeholder' }
    }))
    fields.appendChild(buildTextEditor({
      label: 'Minimum allowed (Limit type="Min")',
      hint: 'Reject answers below this. Clear to remove.',
      initialValue: limitOf('Min'),
      numeric,
      target: { kind: 'factConfig', path, file, field: 'limitMin' }
    }))
    fields.appendChild(buildTextEditor({
      label: 'Maximum allowed (Limit type="Max")',
      hint: 'Reject answers above this. Clear to remove.',
      initialValue: limitOf('Max'),
      numeric,
      target: { kind: 'factConfig', path, file, field: 'limitMax' }
    }))
  })
}

/** Trim display text for a <select> option label. */
function truncateLabel (text, max = 70) {
  if (!text) return ''
  const collapsed = text.replace(/\s+/g, ' ').trim()
  return collapsed.length > max ? `${collapsed.slice(0, max - 1)}…` : collapsed
}

/** Reset a <select> to just its disabled placeholder option. */
function resetSelectToPlaceholder (select, placeholderText, disabled) {
  select.textContent = ''
  select.disabled = disabled
  const placeholder = document.createElement('option')
  placeholder.value = ''
  placeholder.disabled = true
  placeholder.selected = true
  placeholder.textContent = placeholderText
  select.appendChild(placeholder)
}

/**
 * Screen text/structure is edited in two steps: pick a screen, then pick one of its
 * editable blocks — an `<fg-set>` (question, hint, input type, bound fact, gating) or an
 * `<fg-alert>` (heading, alert type, knockout, condition) — and every editor within that
 * block is rendered at once.
 */
function renderScreenEditor (model) {
  const screenSelect = document.getElementById('author-screen-select')
  const blockSelect = document.getElementById('author-block-select')
  const fields = document.getElementById('author-screen-fields')
  if (!screenSelect || !blockSelect || !fields) return

  const screens = field.screens(model)
  const writablePaths = field.writablePaths(model)
  const booleanPaths = field.booleanPaths(model)
  const screenLookup = populateSelect(
    screenSelect,
    screens,
    (s) => field.screenRoute(s),
    (s) => `${field.screenTitle(s) || field.screenRoute(s)}  —  ${field.screenRoute(s)}`
  )

  let blockLookup = new Map()

  function renderFgSetBlock (route, data) {
    const fgSetPath = field.fgSetPath(data)

    fields.appendChild(buildTextEditor({
      label: 'Question',
      initialValue: field.fgSetQuestion(data),
      multiline: true,
      target: { kind: 'screenText', route, field: 'question', path: fgSetPath }
    }))

    if (field.fgSetHint(data) != null && field.fgSetHint(data) !== '') {
      fields.appendChild(buildTextEditor({
        label: 'Hint',
        initialValue: field.fgSetHint(data),
        multiline: true,
        target: { kind: 'screenText', route, field: 'hint', path: fgSetPath }
      }))
    }

    // Structural (v1): input type, bound fact, gating.
    const validTypes = field.fgSetValidInputTypes(data)
    fields.appendChild(buildSelectEditor({
      label: 'Input type',
      hint: `Bound fact is ${field.factType(data) || 'unknown'}; only compatible types will validate.`,
      options: validTypes.length ? validTypes : ALL_INPUT_TYPES,
      selected: field.fgSetInputType(data),
      target: { kind: 'screenAttr', route, path: fgSetPath, field: 'inputType' }
    }))

    fields.appendChild(buildSelectEditor({
      label: 'Bound fact (writable)',
      hint: 'The writable fact this question answers.',
      options: writablePaths,
      selected: fgSetPath,
      blankLabel: writablePaths.includes(fgSetPath) ? undefined : `${fgSetPath} (current)`,
      target: { kind: 'screenAttr', route, path: fgSetPath, field: 'path' }
    }))

    fields.appendChild(buildGatingEditor({
      route,
      fgSetPath,
      booleanPaths,
      ifTrue: field.fgSetIfTrue(data),
      ifFalse: field.fgSetIfFalse(data)
    }))
  }

  function renderAlertBlock (route, data) {
    const alertId = field.alertId(data)

    fields.appendChild(buildTextEditor({
      label: `Alert text — ${alertId}`,
      initialValue: field.alertText(data),
      multiline: true,
      target: { kind: 'screenText', route, field: 'alert', alertId }
    }))

    fields.appendChild(buildSelectEditor({
      label: 'Alert type',
      hint: 'Knockout alerts must be "error".',
      options: ALERT_TYPES,
      selected: field.alertType(data),
      target: { kind: 'alertAttr', route, alertId, field: 'alertType' }
    }))

    fields.appendChild(buildSelectEditor({
      label: 'Knockout (blocks navigation)',
      options: [{ value: 'false', label: 'No' }, { value: 'true', label: 'Yes' }],
      selected: String(field.alertKnockout(data)),
      target: { kind: 'alertAttr', route, alertId, field: 'knockout' }
    }))

    fields.appendChild(buildSelectEditor({
      label: 'Condition fact (Boolean)',
      hint: 'Show the alert based on this Boolean fact.',
      options: booleanPaths,
      selected: field.alertCondition(data),
      blankLabel: 'No condition',
      target: { kind: 'alertAttr', route, alertId, field: 'condition' }
    }))

    fields.appendChild(buildSelectEditor({
      label: 'Condition operator',
      options: ALERT_OPERATORS,
      selected: field.alertOperator(data),
      blankLabel: 'No operator',
      target: { kind: 'alertAttr', route, alertId, field: 'operator' }
    }))
  }

  function renderBlock (block) {
    fields.textContent = ''
    if (!block) return
    if (block.kind === 'fgSet') renderFgSetBlock(block.route, block.data)
    else if (block.kind === 'alert') renderAlertBlock(block.route, block.data)
  }

  screenSelect.addEventListener('change', () => {
    const screen = screenLookup.get(screenSelect.value)
    blockLookup = new Map()
    fields.textContent = ''

    if (!screen) {
      resetSelectToPlaceholder(blockSelect, 'Select a screen first…', true)
      return
    }

    const route = field.screenRoute(screen)
    resetSelectToPlaceholder(blockSelect, 'Select a question or alert…', false)

    const fgSets = field.screenFgSets(screen)
    if (fgSets.length > 0) {
      const group = document.createElement('optgroup')
      group.label = 'Questions'
      for (const fgSet of fgSets) {
        const key = `fgSet:${field.fgSetPath(fgSet)}`
        blockLookup.set(key, { kind: 'fgSet', route, data: fgSet })
        const option = document.createElement('option')
        option.value = key
        option.textContent = truncateLabel(field.fgSetQuestion(fgSet)) || field.fgSetPath(fgSet)
        group.appendChild(option)
      }
      blockSelect.appendChild(group)
    }

    const alerts = field.screenAlerts(screen)
    if (alerts.length > 0) {
      const group = document.createElement('optgroup')
      group.label = 'Alerts'
      for (const alert of alerts) {
        const key = `alert:${field.alertId(alert)}`
        blockLookup.set(key, { kind: 'alert', route, data: alert })
        const option = document.createElement('option')
        option.value = key
        option.textContent = `Alert — ${field.alertId(alert)}`
        group.appendChild(option)
      }
      blockSelect.appendChild(group)
    }
  })

  blockSelect.addEventListener('change', () => {
    renderBlock(blockLookup.get(blockSelect.value))
  })
}

/** Soft-lint panel (T14): fetch and list non-blocking authoring warnings on demand. */
function wireLintPanel () {
  const button = document.getElementById('author-lint-btn')
  const results = document.getElementById('author-lint-results')
  if (!button || !results) return

  button.addEventListener('click', async () => {
    button.disabled = true
    results.textContent = 'Checking…'
    try {
      const { warnings } = await getJson('/author/lint')
      results.textContent = ''
      if (!warnings || warnings.length === 0) {
        const ok = document.createElement('p')
        ok.className = 'author__lint-ok'
        ok.textContent = 'No lint warnings. 🎉'
        results.appendChild(ok)
        return
      }
      const list = document.createElement('ul')
      list.className = 'author__lint-list'
      for (const w of warnings) {
        const item = document.createElement('li')
        item.className = 'author__lint-item'
        const message = document.createElement('span')
        message.textContent = w.message
        item.appendChild(message)
        if (w.route) {
          const route = document.createElement('code')
          route.className = 'author__lint-route'
          route.textContent = w.route
          item.appendChild(route)
        }
        list.appendChild(item)
      }
      results.appendChild(list)
    } catch (err) {
      console.error('lint failed', err)
      results.textContent = 'Could not reach the authoring server to lint. Is `make dev-author` running?'
    } finally {
      button.disabled = false
    }
  })
}

// ══════════════════════════════════════════════════════════════════════════════
//  v2 — Derived computation-tree editor (T18) + create wizards (T17)
// ══════════════════════════════════════════════════════════════════════════════

// Small DOM helpers, local to the v2 editors.
function el (tag, className) {
  const node = document.createElement(tag)
  if (className) node.className = className
  return node
}
function clone (obj) {
  return obj == null ? null : JSON.parse(JSON.stringify(obj))
}
function getAttr (node, name) {
  const pair = (node.attrs || []).find((a) => a[0] === name)
  return pair ? pair[1] : ''
}
function setAttr (node, name, value) {
  node.attrs = node.attrs || []
  const pair = node.attrs.find((a) => a[0] === name)
  if (pair) pair[1] = value
  else node.attrs.push([name, value])
}

// Default structure for a freshly-inserted node, so the author starts from something
// valid-ish rather than an empty shell. The backend schema + fact-graph validate the
// result, so these seeds only need to be sensible starting points.
function slotNode (tag, children) {
  return { tag, attrs: [], text: null, children }
}
function newCase () {
  return slotNode('Case', [slotNode('When', [newNode('True')]), slotNode('Then', [newNode('Dollar')])])
}
function newNode (tag) {
  const base = { tag, attrs: [], text: null, children: [] }
  switch (tag) {
    case 'Dependency': base.attrs = [['path', '']]; break
    case 'Enum': base.attrs = [['optionsPath', '']]; base.text = ''; break
    case 'Dollar': case 'Int': case 'Days': base.text = '0'; break
    case 'Rational': base.text = '1/1'; break
    case 'Day': base.text = '2025-01-01'; break
    case 'String': base.text = ''; break
    case 'True': case 'False': break
    case 'Switch': base.children = [newCase()]; break
    case 'Subtract':
      base.children = [slotNode('Minuend', [newNode('Dollar')]), slotNode('Subtrahends', [newNode('Dollar')])]; break
    case 'Divide':
      base.children = [slotNode('Dividend', [newNode('Dollar')]), slotNode('Divisors', [newNode('Int')])]; break
    case 'StepwiseMultiply':
      base.children = [slotNode('Multiplicand', [newNode('Dependency')]), slotNode('Rate', [newNode('Rational')])]; break
    case 'Equal': case 'NotEqual': case 'GreaterThan':
    case 'GreaterThanOrEqual': case 'LessThan': case 'LessThanOrEqual':
      base.children = [slotNode('Left', [newNode('Dependency')]), slotNode('Right', [newNode('Dependency')])]; break
    case 'Add': case 'Multiply': case 'LesserOf': case 'GreaterOf':
    case 'Minimum': case 'Maximum': case 'All': case 'Any':
      base.children = [newNode('Dependency'), newNode('Dependency')]; break
    case 'Not': case 'Round': case 'RoundToInt': case 'TruncateCents': case 'Ceiling': case 'Floor':
    case 'IsComplete': case 'CollectionSum': case 'CollectionSize': case 'Count':
      base.children = [newNode('Dependency')]; break
    default: break
  }
  return base
}

/**
 * The reusable recursive tree editor. Renders a DerivedNode tree as nested fieldsets and
 * mutates an internal copy in place; `onChange` fires on every edit (leaf or structural).
 * Owns no validate/save workflow — the T18 editor and the create-fact wizard wrap it with
 * their own submit paths.
 *
 * @returns {{ element: HTMLElement, getTree: () => object|null, onChange: (cb: () => void) => void }}
 */
function createTreeEditor ({ initialTree, palette, allPaths }) {
  const byTag = new Map(palette.map((s) => [s.tag, s]))
  const insertable = palette.filter((s) => !s.slotOnly)
  let tree = clone(initialTree)
  let changeCb = () => {}

  const root = el('div', 'author-tree')

  const notify = () => changeCb() // leaf edit: no re-render, just revalidate
  const restructure = () => { render(); changeCb() } // structural edit: re-render + revalidate

  // A <select> of insertable node types, grouped, preselecting `current`.
  function paletteSelect (current) {
    const select = el('select', 'usa-select author-tree__type')
    const groups = {
      'Values & references': insertable.filter((s) => s.category !== 'container'),
      'Operators & logic': insertable.filter((s) => s.category === 'container')
    }
    for (const [label, specs] of Object.entries(groups)) {
      if (!specs.length) continue
      const group = el('optgroup')
      group.label = label
      for (const spec of specs) {
        const option = el('option')
        option.value = spec.tag
        option.textContent = spec.label
        group.appendChild(option)
      }
      select.appendChild(group)
    }
    if (current != null) select.value = current
    return select
  }

  // A fact-path <select> that always includes the current value (even if not in `allPaths`).
  function pathSelect (current, options) {
    const list = options.includes(current) || !current ? options : [current, ...options]
    return makeSelectControl(list, current, { blankLabel: current ? undefined : 'Select a fact…' })
  }

  function attrControl (node, attr) {
    const wrap = el('label', 'author-tree__attr')
    wrap.textContent = attr.label
    let control
    if (attr.kind === 'factPath' || attr.kind === 'optionsPath') {
      control = pathSelect(getAttr(node, attr.name), allPaths)
    } else {
      control = makeTextControl(getAttr(node, attr.name))
    }
    control.addEventListener('input', () => { setAttr(node, attr.name, control.value); notify() })
    control.addEventListener('change', () => { setAttr(node, attr.name, control.value); notify() })
    wrap.appendChild(control)
    return wrap
  }

  function valueControl (node, spec) {
    const wrap = el('label', 'author-tree__value')
    wrap.textContent = 'Value'
    const numeric = ['dollar', 'int', 'rational', 'days'].includes(spec.valueKind)
    const control = makeTextControl(node.text, { numeric })
    if (spec.valueKind === 'day') control.setAttribute('placeholder', 'YYYY-MM-DD')
    control.addEventListener('input', () => { node.text = control.value; notify() })
    wrap.appendChild(control)
    return wrap
  }

  // A container's children, editing mode chosen by the node's grammar spec.
  function childrenRegion (node, spec) {
    const region = el('div', 'author-tree__children')
    if (node.tag === 'Switch') renderSwitch(node, region)
    else if ((spec.childTags || []).length) renderNamedSlots(node, spec, region)
    else renderFreeList(node, region)
    return region
  }

  // Fixed, required named children (Minuend/Subtrahends, Left/Right, When/Then, …).
  function renderNamedSlots (node, spec, region) {
    for (const slotTag of spec.childTags) {
      let child = node.children.find((c) => c.tag === slotTag)
      if (!child) { child = slotNode(slotTag, []); node.children.push(child) }
      region.appendChild(renderNode(child, {}))
    }
  }

  // Switch: a list of removable Cases plus an "Add case" button.
  function renderSwitch (node, region) {
    node.children = node.children.filter((c) => c.tag === 'Case')
    if (!node.children.length) node.children.push(newCase())
    node.children.forEach((caseNode, i) => {
      const removable = node.children.length > 1
      region.appendChild(renderNode(caseNode, {
        remove: removable ? () => { node.children.splice(i, 1); restructure() } : null
      }))
    })
    const add = el('button', 'usa-button usa-button--outline author-tree__add')
    add.type = 'button'
    add.textContent = '+ Add case'
    add.addEventListener('click', () => { node.children.push(newCase()); restructure() })
    region.appendChild(add)
  }

  // Swap two entries of a child list (used by the reorder controls), then re-render.
  function reorder (list, from, to) {
    const moved = list.splice(from, 1)[0]
    list.splice(to, 0, moved)
    restructure()
  }

  // A free, reorderable list of child nodes with an add-node picker.
  function renderFreeList (node, region) {
    node.children.forEach((child, i) => {
      region.appendChild(renderNode(child, {
        remove: () => { node.children.splice(i, 1); restructure() },
        moveUp: i > 0 ? () => reorder(node.children, i, i - 1) : null,
        moveDown: i < node.children.length - 1 ? () => reorder(node.children, i, i + 1) : null
      }))
    })
    const adder = el('div', 'author-tree__adder')
    const select = paletteSelect('Dependency')
    const add = el('button', 'usa-button usa-button--outline author-tree__add')
    add.type = 'button'
    add.textContent = '+ Add'
    add.addEventListener('click', () => { node.children.push(newNode(select.value)); restructure() })
    adder.appendChild(select)
    adder.appendChild(add)
    region.appendChild(adder)
  }

  // Render one node (recursive). `h` supplies position handlers: remove / moveUp / moveDown /
  // replace (retype). Slot wrappers show a label instead of a type selector.
  function renderNode (node, h) {
    const spec = byTag.get(node.tag) || { tag: node.tag, category: 'container', attrs: [], childTags: [], slotOnly: true }
    const box = el('div', `author-tree__node author-tree__node--${spec.category}`)

    const header = el('div', 'author-tree__node-header')
    if (spec.slotOnly) {
      const label = el('span', 'author-tree__slot-label')
      label.textContent = spec.label || node.tag
      header.appendChild(label)
    } else {
      const select = paletteSelect(node.tag)
      select.addEventListener('change', () => h.replace(newNode(select.value)))
      header.appendChild(select)
    }
    const controls = el('span', 'author-tree__node-controls')
    if (h.moveUp) controls.appendChild(iconButton('↑', 'Move up', h.moveUp))
    if (h.moveDown) controls.appendChild(iconButton('↓', 'Move down', h.moveDown))
    if (h.remove) controls.appendChild(iconButton('×', 'Remove', h.remove))
    header.appendChild(controls)
    box.appendChild(header)

    const body = el('div', 'author-tree__node-body')
    for (const attr of (spec.attrs || [])) body.appendChild(attrControl(node, attr))
    if (spec.category === 'value') body.appendChild(valueControl(node, spec))
    if (spec.category === 'container' || spec.category === 'slot') body.appendChild(childrenRegion(node, spec))
    box.appendChild(body)
    return box
  }

  function iconButton (glyph, title, onClick) {
    const button = el('button', 'author-tree__icon')
    button.type = 'button'
    button.title = title
    button.setAttribute('aria-label', title)
    button.textContent = glyph
    button.addEventListener('click', onClick)
    return button
  }

  // The empty-tree state: pick a root node type to begin.
  function rootPalette () {
    const adder = el('div', 'author-tree__adder')
    const select = paletteSelect('Switch')
    const add = el('button', 'usa-button author-tree__add')
    add.type = 'button'
    add.textContent = 'Start calculation'
    add.addEventListener('click', () => { tree = newNode(select.value); restructure() })
    adder.appendChild(select)
    adder.appendChild(add)
    return adder
  }

  function render () {
    root.textContent = ''
    if (!tree) { root.appendChild(rootPalette()); return }
    root.appendChild(renderNode(tree, { remove: () => { tree = null; restructure() } }))
  }

  render()
  return { element: root, getTree: () => tree, onChange: (cb) => { changeCb = cb } }
}

/** T18 — the "Edit a calculation" section: pick a derived fact, edit its <Derived> tree, save. */
function renderDerivedEditor (model) {
  const select = document.getElementById('author-derived-select')
  const host = document.getElementById('author-derived-fields')
  if (!select || !host) return

  const derived = field.facts(model).filter((f) => field.factKind(f) === 'derived')
  const lookup = populateSelect(select, derived, (f) => field.factPath(f), (f) => field.factPath(f))

  select.addEventListener('change', async () => {
    const fact = lookup.get(select.value)
    host.textContent = ''
    if (!fact) return
    host.textContent = 'Loading calculation…'
    let data
    try {
      data = await getJson(`/author/derived?path=${encodeURIComponent(field.factPath(fact))}`)
    } catch (err) {
      console.error('load derived failed', err)
      host.textContent = ''
      reportUnreachable('load the calculation')
      return
    }
    host.textContent = ''
    host.appendChild(buildDerivedWorkspace(fact, data))
  })
}

/** Wrap a tree editor with the derived validate/save workflow (target kind 'derived'). */
function buildDerivedWorkspace (fact, data) {
  const wrapper = el('div', 'author__field')
  const editor = createTreeEditor({ initialTree: data.tree, palette: data.palette, allPaths: data.allPaths })
  wrapper.appendChild(editor.element)

  const error = el('span', 'author__field-error usa-error-message')
  error.hidden = true
  wrapper.appendChild(error)

  const actions = el('div', 'author__field-actions')
  const saveBtn = el('button', 'usa-button')
  saveBtn.type = 'button'
  saveBtn.textContent = 'Save calculation'
  saveBtn.disabled = true
  const savedNote = el('span', 'author__field-saved')
  savedNote.setAttribute('role', 'status')
  actions.appendChild(saveBtn)
  actions.appendChild(savedNote)
  wrapper.appendChild(actions)

  const original = JSON.stringify(data.tree)
  const target = { kind: 'derived', path: field.factPath(fact), file: field.factFile(fact) }
  const payload = () => ({ target, edit: { tree: editor.getTree() } })

  const showErrors = (errors) => {
    const message = errorText(errors)
    error.textContent = message
    error.hidden = !message
    saveBtn.disabled = Boolean(message) || JSON.stringify(editor.getTree()) === original
  }

  // Validate runs are async and debounced, so more than one can be in flight while the author
  // edits (e.g. emptying a <Subtrahends> slot, then immediately re-filling it). Responses can
  // land out of order, so a stale run's result must never overwrite a newer one — otherwise a
  // transient "invalid" error (empty slot) sticks even after the tree is valid again. Only the
  // latest run, tracked by this monotonic token, is allowed to paint the error state.
  let validateSeq = 0
  const runValidate = async () => {
    const seq = ++validateSeq
    try {
      const result = await postJson('/author/validate', payload())
      if (seq !== validateSeq) return
      showErrors(result.ok ? [] : result.errors)
    } catch (err) {
      if (seq !== validateSeq) return
      console.error('validate failed', err)
      reportUnreachable('validate')
    }
  }

  editor.onChange(() => { savedNote.textContent = ''; saveBtn.disabled = true; debounce(runValidate) })

  saveBtn.addEventListener('click', async () => {
    saveBtn.disabled = true
    try {
      const result = await postJson('/author/save', payload())
      if (result.ok) {
        showErrors([])
        savedNote.textContent = 'Saved. Refresh Product Experience or Browse All to preview.'
        saveBtn.disabled = true
      } else {
        showErrors(result.errors)
      }
    } catch (err) {
      console.error('save failed', err)
      reportUnreachable('save')
      saveBtn.disabled = false
    }
  })

  runValidate()
  return wrapper
}

// The types the create wizards offer (kept in step with AuthoringServer's allow-lists).
const WRITABLE_FACT_TYPES = ['Dollar', 'Int', 'Boolean', 'String', 'Day']
const CONSTANT_FACT_TYPES = ['Dollar', 'Rational']
const FIRST_QUESTION_INPUT_TYPES = ['boolean', 'dollar', 'int', 'text', 'date']

let derivedPaletteCache = null
/** Lazily fetch the node palette + fact-path lists (for the create-fact 'derived' branch). */
async function getDerivedPalette () {
  if (derivedPaletteCache) return derivedPaletteCache
  derivedPaletteCache = await getJson('/author/derived?path=/__new__')
  return derivedPaletteCache
}

/** T17 — create a net-new fact (writable / constant / derived). */
function renderCreateFactEditor (model) {
  const host = document.getElementById('author-create-fact-fields')
  if (!host) return

  const files = [...new Set(field.facts(model).map((f) => field.factFile(f)).filter(Boolean))].sort()
  const modelFiles = model.factFiles && model.factFiles.length ? model.factFiles : files

  const pathInput = makeTextControl('')
  pathInput.setAttribute('placeholder', '/newFactPath')
  const fileSelect = makeSelectControl(modelFiles, modelFiles[0])
  const descInput = makeTextControl('', { multiline: true })
  const kindSelect = makeSelectControl(
    [{ value: 'writable', label: 'Writable (an answer the filer provides)' },
      { value: 'constant', label: 'Constant (a fixed Dollar / Rational value)' },
      { value: 'derived', label: 'Derived (a calculation)' }],
    'writable'
  )
  const typeSelect = makeSelectControl(WRITABLE_FACT_TYPES, 'Dollar')
  const valueInput = makeTextControl('', { numeric: true })

  host.appendChild(labeledRow('New fact path', pathInput, 'Must start with "/" and be unique.'))
  host.appendChild(labeledRow('Fact file', fileSelect))
  host.appendChild(labeledRow('Description (optional)', descInput))
  host.appendChild(labeledRow('Kind', kindSelect))
  const typeRow = labeledRow('Value type', typeSelect)
  const valueRow = labeledRow('Value', valueInput, 'e.g. 12200 for Dollar, 31/250 for Rational.')
  host.appendChild(typeRow)
  host.appendChild(valueRow)

  // The derived branch embeds a tree editor, created on demand.
  const treeHost = el('div', 'author__tree-host')
  host.appendChild(treeHost)
  let treeEditor = null

  const error = el('span', 'author__field-error usa-error-message')
  error.hidden = true
  const actions = el('div', 'author__field-actions')
  const createBtn = el('button', 'usa-button')
  createBtn.type = 'button'
  createBtn.textContent = 'Create fact'
  const createdNote = el('span', 'author__field-saved')
  createdNote.setAttribute('role', 'status')
  actions.appendChild(createBtn)
  actions.appendChild(createdNote)
  host.appendChild(error)
  host.appendChild(actions)

  const syncKind = async () => {
    const kind = kindSelect.value
    typeRow.hidden = kind === 'derived'
    valueRow.hidden = kind !== 'constant'
    typeSelect.textContent = ''
    for (const t of (kind === 'constant' ? CONSTANT_FACT_TYPES : WRITABLE_FACT_TYPES)) {
      const option = el('option'); option.value = t; option.textContent = t; typeSelect.appendChild(option)
    }
    treeHost.textContent = ''
    treeEditor = null
    if (kind === 'derived') {
      treeHost.textContent = 'Loading calculation builder…'
      try {
        const palette = await getDerivedPalette()
        treeHost.textContent = ''
        treeEditor = createTreeEditor({ initialTree: null, palette: palette.palette, allPaths: palette.allPaths })
        treeHost.appendChild(treeEditor.element)
      } catch (err) {
        console.error('palette load failed', err)
        treeHost.textContent = ''
        reportUnreachable('load the calculation builder')
      }
    }
  }
  kindSelect.addEventListener('change', syncKind)
  syncKind()

  const payload = (save) => ({
    path: pathInput.value.trim(),
    file: fileSelect.value,
    description: descInput.value.trim(),
    kind: kindSelect.value,
    valueType: typeSelect.value,
    constantValue: valueInput.value.trim(),
    tree: treeEditor ? treeEditor.getTree() : null,
    save
  })

  const showErrors = (errors) => {
    const message = errorText(errors)
    error.textContent = message
    error.hidden = !message
  }

  createBtn.addEventListener('click', async () => {
    createdNote.textContent = ''
    createBtn.disabled = true
    try {
      const result = await postJson('/author/create-fact', payload(true))
      if (result.ok) {
        showErrors([])
        createdNote.textContent = `Created ${pathInput.value.trim()}. Reload to edit it.`
      } else {
        showErrors(result.errors)
      }
    } catch (err) {
      console.error('create fact failed', err)
      reportUnreachable('create the fact')
    } finally {
      createBtn.disabled = false
    }
  })
}

/** T17 — create a net-new screen (page shell + optional first question). */
function renderCreateScreenEditor (model) {
  const host = document.getElementById('author-create-screen-fields')
  if (!host) return

  const modules = model.flowModules || []
  const writablePaths = field.writablePaths(model)

  const moduleSelect = makeSelectControl(modules, modules[0])
  const routeInput = makeTextControl('')
  routeInput.setAttribute('placeholder', '/new-screen')
  const titleInput = makeTextControl('')

  host.appendChild(labeledRow('Flow module', moduleSelect))
  host.appendChild(labeledRow('Route', routeInput, 'Must start with "/" and be unique.'))
  host.appendChild(labeledRow('Title', titleInput))

  const includeQuestion = el('input')
  includeQuestion.type = 'checkbox'
  includeQuestion.id = `author-field-${fieldCounter++}`
  const qToggle = el('label', 'author__checkbox')
  qToggle.setAttribute('for', includeQuestion.id)
  qToggle.appendChild(includeQuestion)
  qToggle.appendChild(document.createTextNode(' Add a first question to this screen'))
  host.appendChild(qToggle)

  const qPathSelect = makeSelectControl(writablePaths, '', { blankLabel: 'Select a writable fact…' })
  const qText = makeTextControl('', { multiline: true })
  const qInputType = makeSelectControl(FIRST_QUESTION_INPUT_TYPES, 'boolean')
  const qRows = el('div', 'author__subform')
  qRows.appendChild(labeledRow('Bound writable fact', qPathSelect))
  qRows.appendChild(labeledRow('Question text', qText))
  qRows.appendChild(labeledRow('Input type', qInputType))
  qRows.hidden = true
  host.appendChild(qRows)
  includeQuestion.addEventListener('change', () => { qRows.hidden = !includeQuestion.checked })

  const error = el('span', 'author__field-error usa-error-message')
  error.hidden = true
  const actions = el('div', 'author__field-actions')
  const createBtn = el('button', 'usa-button')
  createBtn.type = 'button'
  createBtn.textContent = 'Create screen'
  const createdNote = el('span', 'author__field-saved')
  createdNote.setAttribute('role', 'status')
  actions.appendChild(createBtn)
  actions.appendChild(createdNote)
  host.appendChild(error)
  host.appendChild(actions)

  const payload = (save) => {
    const body = { module: moduleSelect.value, route: routeInput.value.trim(), title: titleInput.value.trim(), save }
    if (includeQuestion.checked) {
      body.firstQuestion = { path: qPathSelect.value, question: qText.value.trim(), inputType: qInputType.value }
    }
    return body
  }
  const showErrors = (errors) => {
    const message = errorText(errors)
    error.textContent = message
    error.hidden = !message
  }

  createBtn.addEventListener('click', async () => {
    createdNote.textContent = ''
    createBtn.disabled = true
    try {
      const result = await postJson('/author/create-screen', payload(true))
      if (result.ok) {
        showErrors([])
        createdNote.textContent = `Created ${routeInput.value.trim()}. Reload to edit it.`
      } else {
        showErrors(result.errors)
      }
    } catch (err) {
      console.error('create screen failed', err)
      reportUnreachable('create the screen')
    } finally {
      createBtn.disabled = false
    }
  })
}

/** T19 — delete a fact, gated on a dependent-impact preview. */
function renderDeleteFactEditor (model) {
  const select = document.getElementById('author-delete-select')
  const host = document.getElementById('author-delete-fields')
  if (!select || !host) return

  const facts = field.facts(model)
  const lookup = populateSelect(select, facts, (f) => field.factPath(f), (f) => field.factPath(f))

  select.addEventListener('change', async () => {
    const fact = lookup.get(select.value)
    host.textContent = ''
    if (!fact) return
    const path = field.factPath(fact)
    host.textContent = 'Checking references…'
    let usage
    try {
      usage = await getJson(`/author/fact-usage?path=${encodeURIComponent(path)}`)
    } catch (err) {
      console.error('fact-usage failed', err)
      host.textContent = ''
      reportUnreachable('check references')
      return
    }
    host.textContent = ''
    host.appendChild(buildDeleteWorkspace(path, usage))
  })
}

/** Render the impact preview for a fact + a Delete button enabled only when nothing references it. */
function buildDeleteWorkspace (path, usage) {
  const wrapper = el('div', 'author__field')

  const deps = usage.factDependents || []
  const flow = usage.flowReferences || []
  const blocked = deps.length > 0 || flow.length > 0

  if (blocked) {
    const heading = el('p', 'author__delete-heading')
    heading.textContent = `${path} can't be deleted yet — it's still used by:`
    wrapper.appendChild(heading)
    const list = el('ul', 'author__lint-list')
    for (const dep of deps) {
      const item = el('li', 'author__lint-item')
      item.textContent = `Fact: ${dep}`
      list.appendChild(item)
    }
    for (const ref of flow) {
      const item = el('li', 'author__lint-item')
      item.textContent = `${ref.where}`
      if (ref.route) {
        const route = el('code', 'author__lint-route')
        route.textContent = ref.route
        item.appendChild(document.createTextNode(' '))
        item.appendChild(route)
      }
      list.appendChild(item)
    }
    wrapper.appendChild(list)
  } else {
    const ok = el('p', 'author__delete-ok')
    ok.textContent = `Nothing references ${path}. It's safe to delete.`
    wrapper.appendChild(ok)
  }

  const error = el('span', 'author__field-error usa-error-message')
  error.hidden = true
  wrapper.appendChild(error)

  const actions = el('div', 'author__field-actions')
  const deleteBtn = el('button', 'usa-button usa-button--secondary')
  deleteBtn.type = 'button'
  deleteBtn.textContent = 'Delete fact'
  deleteBtn.disabled = blocked
  const deletedNote = el('span', 'author__field-saved')
  deletedNote.setAttribute('role', 'status')
  actions.appendChild(deleteBtn)
  actions.appendChild(deletedNote)
  wrapper.appendChild(actions)

  deleteBtn.addEventListener('click', async () => {
    if (!window.confirm(`Delete ${path}? This edits the source XML. You can still undo it with git before committing.`)) return
    deleteBtn.disabled = true
    try {
      const result = await postJson('/author/delete-fact', { path, save: true })
      if (result.ok) {
        error.hidden = true
        deletedNote.textContent = `Deleted ${path}. Reload to refresh the fact list.`
      } else {
        error.textContent = errorText(result.errors)
        error.hidden = false
        deleteBtn.disabled = false
      }
    } catch (err) {
      console.error('delete fact failed', err)
      reportUnreachable('delete the fact')
      deleteBtn.disabled = false
    }
  })

  return wrapper
}

/** A labeled control row (label + optional hint + control) for the create forms. */
function labeledRow (label, control, hint) {
  const wrapper = el('div', 'author__field usa-form-group')
  const labelEl = el('label', 'usa-label')
  labelEl.setAttribute('for', control.id || '')
  labelEl.textContent = label
  wrapper.appendChild(labelEl)
  if (hint) {
    const hintEl = el('span', 'usa-hint author__field-hint')
    hintEl.textContent = hint
    wrapper.appendChild(hintEl)
  }
  wrapper.appendChild(control)
  return wrapper
}

/** Resolve after `ms` milliseconds. */
function delay (ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Backoff before the Nth (1-based) connect retry: geometric growth, capped. */
function connectBackoffMs (attempt) {
  return Math.min(CONNECT_BACKOFF_START_MS * 1.6 ** (attempt - 1), CONNECT_BACKOFF_MAX_MS)
}

/** Cheap readiness probe. Throws (network error, bad status, or non-"ok" body) until the
 * authoring backend is actually up — deliberately lighter than /author/model, which rebuilds
 * the whole dictionary, so polling it costs the server nothing.
 */
async function probeHealth () {
  const body = await getJson('/author/health')
  if (!body || body.status !== 'ok') throw new Error('authoring server not ready')
}

/** Render every editor section from a loaded model. */
function renderAll (model) {
  renderConstantEditor(model)
  renderDescriptionEditor(model)
  renderFactConfigEditor(model)
  renderScreenEditor(model)
  renderDerivedEditor(model)
  renderCreateFactEditor(model)
  renderCreateScreenEditor(model)
  renderDeleteFactEditor(model)
  wireLintPanel()
}

/** Connect to the backend (retrying with backoff while it compiles/boots), then render. Exposed
 * for tests / manual re-init.
 */
export async function init () {
  const startedAt = Date.now()
  for (let attempt = 1; attempt <= CONNECT_MAX_ATTEMPTS; attempt++) {
    try {
      await probeHealth()
      const model = await getModel()
      renderAll(model)
      document.getElementById('author-status').hidden = true
      return
    } catch (err) {
      const elapsedS = Math.round((Date.now() - startedAt) / 1000)
      if (attempt === CONNECT_MAX_ATTEMPTS) {
        console.error('Author Mode could not reach the authoring server', err)
        setStatus(
          `Still can’t reach the authoring server on port ${AUTHOR_PORT} after ${elapsedS}s (${attempt} attempts). ` +
          'It’s started by `make dev-author`, or by the application’s watcher container under `make up` — ' +
          'a cold start compiles Scala and regenerates the site before the API comes up. ' +
          'Once it’s running, reload this page.',
          'error'
        )
        return
      }
      setStatus(
        `Connecting to the authoring server on port ${AUTHOR_PORT}… retrying (attempt ${attempt} of ${CONNECT_MAX_ATTEMPTS}, ${elapsedS}s). ` +
        'The backend may still be compiling — a cold start in Docker can take a minute or two.',
        'info'
      )
      await delay(connectBackoffMs(attempt))
    }
  }
}

/** GET the current editable model. Throws on network failure; guards against a non-model body
 * (e.g. an error envelope) so init's retry loop keeps waiting instead of rendering junk.
 */
async function getModel () {
  const model = await getJson('/author/model')
  if (!model || !Array.isArray(model.facts) || !Array.isArray(model.screens)) {
    throw new Error('authoring server returned no editing model')
  }
  return model
}

window.initAuthorMode = init

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true })
} else {
  init()
}
