# Internals: the flow runtime

The flow runtime is the browser half of a generated site. It is 15 ES modules under
`src/main/resources/form-builder/website-static/flow-runtime/js/`, shipped as classpath resources
inside the `gov.irs::form-builder` jar and extracted by `FormBuilderAssets` into a built site's
`resources/vendor/form-builder/flow-runtime/js/`.

It defines the custom elements the generated HTML is written in, boots the Fact Graph from the
application's own `fact-dictionary.xml`, and handles validation, conditional visibility and
navigation. Nothing in it knows which application it is running inside. Every application-specific
value is either derived from the page or read from a `<meta>` tag.

There is no build step. These are plain ES modules loaded with `<script type="module">`, which is
why they use no bundler syntax and import each other by relative path with the `.js` extension.

## Where it fits

| Layer | Owns |
|---|---|
| `parser/` and `generators/` (Scala) | Turns Flow XML into HTML containing `<fg-set>`, `<fg-collection>`, `<fg-show>` and friends |
| `templates/nodes/` (Thymeleaf) | The markup for each of those elements, including the per-input-type partials |
| `flow-runtime/js/` (this document) | The behaviour those elements have once the page is in a browser |
| `taxpert` (a separate npm package) | The optional workspace over the top. It reads the flow through the DOM and `window.factGraph`, and never imports from here |

The split matters when a change touches both halves. Every branch of `fg-set.js`'s `inputType`
switch has a matching template under `templates/nodes/inputs/`, so adding an input type **here** is
two edits, not one.

An **application** adding one does not touch either: it registers an `InputParser` on its
`FormBuilderApp`, drops a `nodes/inputs/{name}.html` in its own resources, and calls
`registerInputType()` from `input-types.js` for the browser half. The switches in `fg-set.js` are
the built-in types and are closed.

## The modules

| Module | Responsibility |
|---|---|
| `flow-runtime.js` | The entry point. Imports the elements into existence, runs the first visibility pass, reveals the page, and re-exports the public surface |
| `input-types.js` | The registry an application adds an `<fg-set>` input type through, without editing `fg-set.js` |
| `fact-graph-engine.js` | Loads the compiled Scala.js engine once, so every module shares one instance |
| `fg-fact-graph.js` | Owns the graph: loads the dictionary, rehydrates from `sessionStorage`, saves, resets |
| `fg-graph-bridge.js` | Publishes and receives the serialized graph over a `BroadcastChannel`, for a Fact Explorer embedding the app |
| `runtime-config.js` | The storage prefix and base path, layered over defaults |
| `runtime-paths.js` | Derives the application's mount point from this bundle's own URL |
| `fg-set.js` | `<fg-set>`, one question bound to one fact path |
| `fg-collection.js` | `<fg-collection>` and `<fg-collection-item>`, a repeating group |
| `fg-collection-utils.js` | Splicing a concrete item id into an abstract `/*/` path |
| `fg-conditions.js` | Evaluating `condition`/`operator` pairs and applying show/hide |
| `fg-display.js` | `<fg-show>`, `<fg-reset>`, `<fg-apply>` |
| `fg-validation.js` | Page-level validation, the summary alert, and the focus moves |
| `continue-handlers.js` | What pressing Continue does, and the hook an application extends it through |
| `fg-navigator.js` | Skipping pages under `--singleQuestionPerScreen` |
| `modals.js` | `<modal-link>` and its focus trap |

`tests/` at the repository root holds `node --test` suites for `runtime-config.js` and
`fg-graph-bridge.js`, the two modules with logic that can be tested without a DOM.

## Load order and the top-level await

`fg-fact-graph.js` fetches the fact dictionary and builds the graph at module scope, using a
top-level `await`. Every other module imports it directly or transitively, so the whole bundle
settles before any element's `connectedCallback` runs. Nothing has to defend against a missing
graph.

The cost is that the graph is a module-level binding rather than something passed in. `factGraph`
is exported as a `let` and reassigned by `loadFactGraph` and `resetEntireGraph`, both of which
reload the page immediately afterwards.

`window.factGraph` is set for the benefit of the workspace, which reads it through a duck-typed
adapter rather than importing anything.

## Configuration

The runtime reads two values: a storage prefix and a base path. Both have working defaults, so a
site is never obliged to configure anything.

| Layer | Mechanism | Set by |
|---|---|---|
| Defaults | `baseConfig()` in `runtime-config.js` | prefix `taxpert`, base path derived |
| Server | `<meta name="form-builder:storage-prefix">` and `<meta name="form-builder:base-path">` | `fragments/head.html`, rendered ungated |
| Override | `configureRuntime({ … })` | a bundler or a test |

**Why `<meta>` rather than a configuring `<script>`.** `fg-fact-graph.js` reads the stored graph at
its top level, so a configuring script would have to execute before it. Document order gives that,
but silently, and a later reordering would break it with no error. A `<meta>` tag is in the DOM
before any module runs.

**Why the tags are ungated.** A questionnaire runs whether or not it has a workspace over it, so
`fragments/head.html` renders them regardless of `--auditMode`.

Seeding is lazy. `seed()` runs on the first `getRuntimeConfig()` rather than at module scope,
because this module is also imported where there is no document. `configureRuntime` seeds first, so
an explicit call always lands over the server's values rather than under them.

`merge` uses `Object.defineProperty` rather than a member assignment, and ignores unknown
namespaces, unknown keys, non-strings and empty strings. A computed member assignment on a
caller-supplied key is the pattern the security lint forbids.

### The storage key

`storageKey(name)` returns `<prefix>:<name>`. The runtime writes exactly one key, `factGraph`. The
prefix exists so two Form Builder applications served from one origin do not rehydrate each other's
answers. It comes from `FormBuilderApp.storagePrefix`, defaulting to the `appId`.

This is not `taxpert`'s configuration. The workspace keeps its own config and its own prefix, the
two never share a key, and neither package imports the other.

## Path derivation

`runtime-paths.js` works out where the application is mounted from this bundle's own
`import.meta.url`, so no application name is written into the library.

```
https://host/app/eitc/resources/vendor/form-builder/flow-runtime/js/
                    ^^^^^^^^^^^                                       <- VENDOR_TAIL starts here
         /app/eitc                                                    <- appBasePath()
         /app/eitc/resources                                          <- resourcesBase()
```

`VENDOR_TAIL` must match `FormBuilderAssets.vendorPath`. A mismatch fails silently: `resourcesBase()`
returns null, and every derived link turns root-relative. If a generated site's asset URLs suddenly
lose their route prefix, check that pair first.

| Function | Returns | On failure |
|---|---|---|
| `resourcesBase()` | absolute URL of the app's `resources/`, no trailing slash | `null` |
| `appBasePath()` | the route prefix, no trailing slash | `''` |
| `resourceUrl(name)` | a URL under `resources/` | falls back to a root-relative `/resources/…` |

`appBasePath()` returns a path rather than a URL because callers put it straight into an `href` or
compare it against `window.location.pathname`. A configured `endpoints.basePath` wins over
derivation.

## Elements

### `<fg-set>`

One question, bound to one fact path. On connect it reads the fact into its inputs; on the event its
input type listens for it writes back and renders whatever the Fact Graph rejected.

Which event depends on the input type, and the switch is deliberately not exhaustive. An unlisted
type falls through to the default, which commits on `blur` and on `Tab`.

| Input type | Commits on |
|---|---|
| `date` | `change`, once all three fields are filled |
| `dollar` | `input` |
| `select`, `boolean`, `enum`, `multi-enum` | `change` |
| a registered type with an `attach` | whatever that `attach` wires |
| anything else | `blur`, and `keydown` when the key is Tab |

#### Registering an input type

`registerInputType(name, { read, write, clear, attach })` in `input-types.js` is the browser half of
`FormBuilderApp.inputTypes`. Without it, a custom type has to be a wrapper custom element that
duplicates `fg-set`'s error rendering, because the five `switch (this.inputType)` blocks each ended
in a warning an application could not extend.

| Handler | Does |
|---|---|
| `read(el)` | Return the value to hand the Fact Graph. `''` or `null` means unanswered, which deletes the fact rather than setting it empty |
| `write(el, value, fact)` | Put a fact value into the inputs. `value` is `''` when incomplete; `fact` is the raw result for a type whose DOM needs more than the string |
| `clear(el)` | Return the inputs to empty |
| `attach(el)` | Optional. Wire the events that call `el.onChange()`. Omit to inherit the blur/Tab default |

**Registration order does not matter.** ESM hoists imports, so an application usually cannot register
before `flow-runtime.js` evaluates and `<fg-set>` upgrades. `read`/`write`/`clear` are looked up at
call time, and a late registration re-wires the elements already on the page — otherwise a type that
should commit on every keystroke would silently commit only on blur.

The Tab listener exists because conditions have to be re-evaluated before the keydown resolves, so
that focusable elements are updated before focus moves. `blur` and `change` both fire after focus
has already moved.

`onChange` dispatches a cancelable `fg-set-before-commit` event carrying `{ path, proposedValue }`
before writing. An application that wants to intervene, such as confirming a destructive change,
listens for it and calls `preventDefault()`, which reverts the input to the stored value.

Errors come back from the Fact Graph as an `errorName`. The runtime looks up
`errors.<errorName>` among the elements the page rendered, falling back to `errors.Default`, and
appends `expectedValue` so "Enter an amount more than" reads as a sentence. A `Match` limit's
expected value is a regular expression, so it is left off.

`isComplete()` is the single question-level predicate, and it is the Fact Graph's own `complete`
rather than anything the DOM knows.

### `<fg-collection>` and `<fg-collection-item>`

A repeating group. `<fg-collection>` renders one `<fg-collection-item>` per id in the Fact Graph
collection, and each item is a clone of the collection's
`<template class="fg-collection__item-template">` with the item's id spliced into every abstract
`/*/` path by `configureCollectionIds`.

Two flow attributes gate it:

| Attribute | Effect |
|---|---|
| `add-item-if-true` | Disables the Add button when the named fact is complete and false. An incomplete fact keeps it enabled, so a mid-edit answer does not block |
| `seed-item-if-true` | Starts the collection with one empty row already open |

`makeCollectionIdPath` is duplicated in `taxpert`'s `shared/js/collection-utils.js` on purpose. This
package ships in a Scala jar, so taxpert cannot import from it, and a relative path into
`vendor/form-builder/` exists only in a built site. Keep the two copies identical.

### `<fg-show>`, `<fg-reset>`, `<fg-apply>`

`<fg-show path="…">` renders a fact's current value, re-rendering on every `fg-update`. A path
containing `*` shows every item's value, which is why it reaches into the Scala.js
`MaybeVector.Multiple` encoding. Dollar values are formatted with `Intl.NumberFormat`.

`<fg-reset>` drops the stored graph. It reloads in place on the Browse All and Author views, which
keeps the current mode and query string, and sends the linear flow back to its first page.

`<fg-apply path="…">` writes as the page renders, so a page reached only under a condition can
assert the fact that condition implies. What it writes comes from exactly one of two attributes —
the parser rejects both and neither:

| | Writes |
|---|---|
| `value="true"` | that literal |
| `source="/otherPath"` | the current value of `/otherPath` |

`source` exists because copying one fact into another cannot be expressed as a literal: the value is
not known when the flow is authored. An incomplete source writes **nothing** rather than an empty
value, so an `<fg-apply source>` on a page rendered before its source is answered is inert rather
than a way to silently clear the target.

## Conditional visibility

`showOrHideAllElements()` walks every element carrying a `condition`/`operator` pair and shows or
hides it against the current graph. It runs on load and on every `fg-update`.

| Operator | True when |
|---|---|
| `isTrue` | the fact has a value and it is `true` |
| `isFalse` | the fact has a value and it is `false` |
| `isTrueAndComplete` | complete, has a value, and it is `true` |
| `isZero` | has a value equal to `0` |
| `isGreaterThanZero` | has a value greater than `0` |
| `isIncomplete` | `complete` is false |
| `notHasValue` | `hasValue` is false |

The true and false checks are explicit rather than truthiness tests, because an incomplete fact has
no value to compare.

**Hiding an element deletes the facts inside it.** That is what keeps a hidden answer from counting
as complete, and it is the reason a knockout can be cleared by going back and changing the answer
that caused it. `<fg-set>` exposes a delete that does not dispatch `fg-update`, because this runs
while handling an `fg-update` and hiding one element cascades into hiding others.

An unreadable condition defaults to showing, so a necessary question is never skipped by an error.

The pass is single and in DOM order, so an `<fg-set>` must not carry a condition on a fact set later
in the same document.

## Validation and Continue

`window.handleSectionContinue` is wired into the generated Continue link as
`onclick="return handleSectionContinue(event)"`, so it has to exist in every application. Returning
`false` blocks navigation.

It runs in two stages:

1. `validateSectionForNavigation()`. Every visible, non-optional, incomplete `<fg-set>` is a
   blocker. So is any visible `fg-alert[knockout="true"]`. On failure it inserts the summary alert
   (cloned from `#validate-alert-template`, rendered by `fragments/js-templates.html`), scrolls to
   the first problem and moves focus there.
2. The application handler chain, in registration order. A handler returning `true` claims the event
   and stops both navigation and later handlers.

`registerContinueHandler(fn)` is the extension point. `revealOnContinue({ route, gatePath,
clickedPath })` is a prebuilt handler for the common knockout pattern: the first Continue reveals
the knockout alert and stays put, and the second is blocked by validation from then on. It swallows
read errors rather than throwing, because a throw would take the whole chain down.

Focus handling sets `tabindex="-1"` on the target and removes it on `blur`, so the outline does not
persist on later clicks.

## Single-question navigation

Under `--singleQuestionPerScreen` each page holds one question, so the show and hide that would
happen inside a page happens between pages instead. `fg-navigator.js` rewrites the Next and Back
hrefs to skip pages whose gate is false against the live graph, and on load redirects off a page
whose gate has since become false.

It reads `resources/flow-manifest.json`, which only that build flag emits, and no-ops without it.
The locale segment is read off `<html lang>` rather than matched against a list of locale codes,
because the generator already stamps the page's own language into the document.

## The Fact Explorer bridge

`fg-graph-bridge.js` keeps a questionnaire and a same-origin Fact Explorer embedding it in sync.
Both sides publish the serialized graph over a `BroadcastChannel`, and each filters out echoes of
its own messages.

**The wire protocol is a contract with another repository.** The channel name `taxpert:factGraph`
and the message shape `{ type: 'factGraph', graph: <string> }` are implemented on the other side by
`fact-explorer/src/model/bridge.js`. Renaming either breaks the sync silently, with no error on
either end.

The bridge is feature-detected and no-ops where `BroadcastChannel` is unavailable.

`loadFactGraph` defers its page reload by one task, so the publish is flushed before the frame
unloads. An immediate reload races the in-flight message and drops it.

## Events

| Event | Dispatched on | Meaning |
|---|---|---|
| `fg-load` | `document` | The graph is built and `window.factGraph` is set |
| `fg-update` | `document` | The graph changed. Triggers a visibility pass and re-renders every `<fg-show>` |
| `fg-clear` | `document` | Clear input state. Listened for by `<fg-set>` and `<fg-collection-item>` |
| `fg-set-before-commit` | the `<fg-set>`, bubbling, cancelable | A value is about to be written. `preventDefault()` reverts the input |

An application extends the runtime through these events and `registerContinueHandler`. It imports
`flow-runtime.js` first and then its own modules.

## Gotchas

| Symptom | Cause |
|---|---|
| Every asset URL lost its route prefix | `VENDOR_TAIL` no longer matches `FormBuilderAssets.vendorPath` |
| An edit to a runtime module has no effect under `sbt ~run` | These ship as classpath resources. Run `sbt publishLocal` and restart the application |
| Two applications on one origin overwrite each other's answers | They resolve to the same `storagePrefix` |
| A question stays complete after the answer that revealed it changed | Its element was not inside the one being hidden, so its fact was never deleted |
| Live sync with Fact Explorer stopped, with no error | The channel name or message shape changed on one side |
| A new input type renders but never commits | It has no `registerInputType()` call, so it took the default blur handler |
| A registered input type warns `Unknown input type` in the console | `registerInputType()` ran with a different `name` than the `<input type>` in the Flow XML |
