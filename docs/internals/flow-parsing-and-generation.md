# Internals: flow parsing and site generation

This is the middle of the build: Flow XML and a Fact Dictionary go in, a directory of static HTML
comes out. Two packages do the work.

| Package | Turns |
|---|---|
| `gov.irs.formbuilder.parser` | Flow XML into a tree of `FlowNode` case classes, validating every fact path against the dictionary as it goes |
| `gov.irs.formbuilder.generators` | That tree into rendered pages, plus the optional Browse All, Author Mode, flow manifest and Form Graph Model outputs |

Entry, configuration and asset extraction are covered separately in
[app-entry-and-assets.md](app-entry-and-assets.md). The browser half of the generated site is in
[flow-runtime.md](flow-runtime.md).

## The pipeline

```
flow/index.xml
  FormBuilder.resolvedFlowConfig    splice every <module src="…"/> in, stamp each page's module
  Flow.fromXmlConfig                parse <page> elements, filling a TranslationContext
  PageSplitter.split                only under --singleQuestionPerScreen
  generateFlowLocaleFile            write flow_<default>.yaml from the accumulated translation map
  Website.generate                  render every page, once per language
  Website.save                      write ./out, copy static assets, extract the jar's own
```

Parsing and rendering are separate passes for one reason. Parsing accumulates authored text into a
translation map, and rendering reads text back out of the locale files by key. A page is therefore
parsed once and rendered once per language.

## The node model

Two interfaces, in `FlowNode.scala`:

```scala
trait FlowNode {
  def html(templateEngine: FormBuilderTemplateEngine): String
}

trait FlowNodeParser {
  def fromXml(element: Elem, flowParser: FlowParser, parentTranslationContext: TranslationContext): FlowNode
}
```

A node renders itself to a string. A parser builds one from an XML element. There is no
intermediate document model and no visitor.

`FlowParser` holds the fact dictionary and the `FormBuilderApp`, and dispatches an element to the
parser registered for its tag. `FlowNodeTypes.builtIn` is the library's map:

| Tag | Parser |
|---|---|
| `fg-alert` | `FgAlert` |
| `fg-apply` | `FgApply` |
| `fg-collection` | `FgCollection` |
| `fg-detail` | `FgDetail` |
| `fg-set` | `FgSet` |
| `modal-dialog` | `Modal` |
| `section` | `Section` |

`FormBuilderApp.nodeTypes` is merged over that map, so an application can add a tag or replace a
built-in one. Anything still unmatched falls through to `Html`, which is what lets a flow use `<p>`
and `<ul>` without registering anything.

`<page>` is parsed by `Page` and only at the flow config root. It is named in `FlowNodeTypes` so a
nested one raises a specific error instead of rendering as an HTML element.

### A note on `fg-section-gate`

`FgSectionGate` exists, has a template, and is not in `builtIn`. Nothing calls
`FgSectionGate.parse`. A flow that authors the tag today falls through to `Html`. Either wire it up
or delete it; do not assume it works.

## Pages

A `Page` is one route, and the unit the generator turns into one `index.html` per language.

| Attribute | Effect |
|---|---|
| `route` | required. The URL path |
| `title` | required. Stored in the translation context under `title` |
| `exclude-from-stepper` | keeps the page out of the step indicator |
| `group-by` | `"h3"` cuts the page along top-level headings under `--singleQuestionPerScreen` |
| `module` | not authored. Stamped on by `FormBuilder.resolveModule` while splicing `index.xml` |

`module` is stamped at splice time because that is the last point at which a page's source file is
known. Browse All groups pages by module, and a route need not carry the module name or any segment
at all. A page read from a single-file flow has none, and `AllScreens` falls back to the route's
first segment.

Two derived values drive navigation, and both are computed by walking the parsed children:

**`gatingCondition`** is the condition under which a page has anything to show. `fg-navigator.js`
reads it out of `flow-manifest.json` and skips the page when it evaluates false, so a page that would
render as an empty `<main>` is left out of the traversal instead. Three sources, in falling order of
confidence:

1. The `gate` `PageSplitter` set, when the page is one conditional block it cut out of a larger page.
2. Every piece of content on the page hanging on the same condition, so a false condition leaves
   nothing to render. This is the rule that catches a screen of pure prose, which has no question to
   read a condition off.
3. The page's one question being conditional. Only for a page the splitter emitted, because the rule
   ignores the rest of the page and only the splitter decides what the rest is. On an authored page
   that "rest" is content someone wrote, and credit-assistant's `/qualifying-children` is the
   counterexample: one conditional `<fg-collection>`, beside an alert that shows precisely when the
   collection does not.

A page with none of the three returns `None` and stays reachable. Each element's own condition still
drives show and hide inside the page.

**`knockoutConditionPaths`** collects the condition path of every `fg-alert[knockout=true]` reachable
from the page, in DOM order.

`Page.html` coerces `<fg-show …/>` into an open and close tag pair, because HTML does not allow
self-closing custom elements.

## Conditions

Most elements express visibility with `if-true` or `if-false`, which `Condition.getCondition` turns
into a `(path, operator)` pair. The two are mutually exclusive, and the named fact must exist and be
Boolean.

`fg-alert` is the exception: it reads a `condition`/`operator` attribute pair directly, which is how
it reaches the non-Boolean operators.

`ConditionOperator`'s case names reach the browser verbatim as `operator="…"` on rendered markup, so
they are part of the runtime contract. Renaming one means changing `fg-conditions.js` in the same
commit.

| Operator | Meaning |
|---|---|
| `isTrue`, `isFalse` | the fact has a value and it is that |
| `isTrueAndComplete` | complete as well as true |
| `isZero`, `isGreaterThanZero` | numeric comparisons |
| `isIncomplete` | the fact is not complete |
| `notHasValue` | the fact has no value |

## Questions

`FgSet` is one question, binding a fact path to an input. It carries the question text, an optional
hint, an optional modal link and an optional condition.

Two things are read from the dictionary rather than the flow:

- **Optionality.** A question is optional when its fact has a `<Placeholder>`. The flow does not say
  so.
- **Type agreement.** `Input.expectedNodeType` is checked against the fact's `typeNode`. Because the
  check happens here rather than inside each input, a registered input type gets the same check as a
  built-in one.

`Hint` and `ModalLink` are the condition attributes only. Their text lives in the translation
context, and the template reads it back through `#{…}`.

Mixed content is preserved with `.child.mkString` rather than `.text`, so a `<span>` or an
`<fg-show>` inside a question survives.

## Inputs

`Input` is a sealed enum, and the extension point applications use to add one. An
application-registered type arrives as `Input.custom`, whose `name` also names the template that
renders it at `nodes/inputs/{name}.html`.

Registrations from `FormBuilderApp.inputTypes` are checked **before** the built-ins, so an
application can replace a built-in type as well as add one.

Four things a custom input declares:

| Field | Meaning |
|---|---|
| `name` | the `<input type="…">` value, and the template name |
| `templateVariables` | values the parser wants its own template to read, passed through with their Scala types intact so a template can do arithmetic |
| `nodeType` | the Fact Graph node type to check against, or `None` to skip the check |
| `suppliesOwnLabel` | the template renders its own label, so `fg-set` must not put a `<label>` in front of it |

`suppliesOwnLabel` is true for the built-ins that wrap their options in a `<fieldset>` with the
question as its `<legend>`.

## Fallthrough HTML

`Html` re-emits an unregistered element as HTML. It splits on whether the element is a leaf:

**Leaf elements** (`p`, `li`, `caption`, `th`, `td`, `h1` to `h6`, `button`) have their inner markup
stored in the translation context under a content-hashed key, and read back per language at render
time.

**Everything else** wraps its parsed children between the original open and close tags.

`div`, `details` and `summary` do not get a level in the translation key. Wrapping content in a
`<div>` would otherwise shift the keys of everything inside it, invalidating every translation
underneath.

## Translation keys

`TranslationContext` builds the dotted keys `flow_<lang>.yaml` is keyed by, and collects the
authored default-language text under them as the parser walks.

Every context produced during one parse shares a single mutable map. An instance is a path into that
map plus the tag counters used to name unnamed children.

| Method | Key it produces |
|---|---|
| `forChildWithId(id)` | the id itself, for a page route or a fact path |
| `forChildWithoutUniqueId(label)` | `label-N`, counting occurrences of that tag |
| `forChildWithoutUniqueId(label, content)` | `label-<md5 prefix>`, for content-addressed leaf text |

**Keys must stay stable between builds**, because the translated locale files are keyed by them and
`syncTranslationLocales` drops any key that no longer exists. A key that moves silently discards its
translations.

The content hash is truncated to six hex characters. `updateValue` raises on a collision, meaning
the same key with different content, and the fix is to lengthen the truncation.

## Page splitting

`PageSplitter` rewrites the page list under `--singleQuestionPerScreen`. It runs after parsing and
before locale generation, on the parsed tree rather than on XML.

Every authored page becomes one or more emitted pages, each carrying `sourcePageRoute` so the
stepper can still group them by the page they came from.

| Input | Result |
|---|---|
| a page with one question | passes through with its original route, plus `sourcePageRoute` |
| a page with several | one page per question, routed `<original>/<question slug>` |
| `group-by="h3"` | cut along top-level `<h3>` headings instead |
| no question at all | passes through unchanged, plus `sourcePageRoute` |

Everything before the first question travels with the first emitted page. Modals are lifted out and
appended to every emitted page, since a `modal-link` and its `modal-dialog` have to end up on the
same page.

The generator builds the Form Graph Model from the pre-split `parsedFlow`, so the graph keeps the
authored shape regardless of this flag.

## Rendering

`Website.generate` renders every page once per language through `templates/page.html`.

The step indicator counts *topics*, not pages. Pages are grouped by `stepperRoute`, which is the
source page for anything `PageSplitter` produced, so a page split into five questions is still one
step.

Titles compose from three parts, and how they compose is `title.format` in the locale file rather
than string concatenation in Scala. An application can reorder or drop any of them without a code
change.

`WebsitePage.html()` pretty-prints through jsoup so view-source is readable. `fg-set` and its
children are unknown tags, which jsoup treats as inline unless told otherwise, so they are marked
as block first.

### What `save` writes

| Output | Condition |
|---|---|
| one `index.html` per page per language | always |
| the application's `website-static/`, copied to `resources/` | always |
| the library's theme and flow runtime, extracted into `resources/vendor/form-builder/` | always |
| `resources/fact-dictionary.xml`, the merged dictionary | always |
| `resources/flow-manifest.json` | always |
| `resources/form-builder-graph.json` | `--formBuilderGraph` |
| `resources/scenarios/` | `--scenarioMode`, and the directory exists |

`save` removes the target directory first.

### The other generators

**`AllScreens`** (`--allScreens`) renders every screen onto one page at `/all-screens`, grouped into
sections by module. The toolbar and styling over it belong to the workspace package and are mounted
through `fragments/workspace-all-screens.html`, so this generator emits the markup and nothing else.

**`AuthorMode`** (`--authorMode`) renders a static shell from `templates/author-mode.html`. The
editable model is fetched at runtime from the authoring server, so nothing here reads `flow`. It is
still a parameter, to keep the signature interchangeable with `AllScreens`.

**`FlowManifest`** emits the JSON array the navigation JS reads, one
entry per rendered page: `route`, `href`, `gatePath`, `gateOperator`, `knockoutPaths`, `sourceRoute`,
`exclude`. Built for the default locale only, because routes are identical across languages and only
the href prefix differs, which the navigation JS derives client-side.

**`FormBuilderGraph`** (`--formBuilderGraph`) emits the Form Graph Model that Fact Explorer renders.
It reads the resolved flow XML rather than the parsed tree, because each element's own source XML is
part of the output and the parsed case classes do not retain it. Four slices:

| Slice | Contents |
|---|---|
| `flowPages` | one per rendered page: route, title key, source module, the ids of its elements |
| `flowElements` | every recognised element, with the fact it binds, its condition, and its source XML |
| `facts` | every `<Fact>` in the merged dictionary, with resolved dependency paths |
| `edges` | `sequential`, `binds`, `gates`, `knocks-out`, `displays`, `depends` |

`flowTags` declares exactly the node types the application registered. The consumer accepts those
and no others, so a misspelled tag is an error on both sides rather than a silently dropped element.

Facts are emitted last-definition-wins per path. Dictionaries redefine a path on purpose, one
constants file per tax year, and emitting both would give two nodes the same `fact:/…` id.

## Templates

36 Thymeleaf templates under `src/main/resources/form-builder/templates/`: 19 under `nodes/` (8 of
those in `nodes/inputs/`), 13 under `fragments/`, and four at the top level (`page.html`,
`all-screens.html`, `author-mode.html`, `errors.html`).

Two `ClassLoaderTemplateResolver`s run in order. `/{appId}/templates/` is tried first and
`/form-builder/templates/` second, so an application overrides one template by dropping a same-named
file into its own resources and inherits the other 35. Both resolvers set `checkExistence`, so the
first reports "not found" rather than claiming the name.

`process` puts `basePath` and the whole `FormBuilderApp` on every context, so no template has to be
handed a URL prefix by its caller.

Four fragments are the workspace mount points, and the library ships all four empty:
`workspace-head`, `workspace-enable`, `workspace-all-screens`, `taxpert-config`. No template here
names a `vendor/taxpert/` path.

## Gotchas

| Symptom | Cause |
|---|---|
| A translated string vanished after a flow edit | Its translation key moved. Wrapping content in a new element, or reordering unnamed siblings, re-keys everything under it |
| "Collision detected. Expected unique translation key" | Two leaf elements hashed to the same six characters with different content. Lengthen the truncation in `getHashKey` |
| An `<fg-section-gate>` renders as literal markup | It is not in `FlowNodeTypes.builtIn`, so it falls through to `Html` |
| A registered input type renders but fails its type check | `nodeType` disagrees with the bound fact. Set it to `None` to opt out |
| A page is unreachable | Its `gatingCondition` is false. Read the three sources above to see which one gave it one |
| A custom template cannot see a value the parser computed | It is not in `templateVariables` |
| `!!some.key!!` in the output | The message resolver found no such key in any of the three locale layers |
