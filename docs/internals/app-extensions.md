# App Extensions

## The five extension points

### 1. Templates, resolved application first

`FormBuilderTemplateEngine` registers two `ClassLoaderTemplateResolver`s: `/{appId}/templates/` at
order 1 and `/form-builder/templates/` at order 2, held in a `LinkedHashSet` so the declared order
survives. Both set `setCheckExistence(true)`, so a miss in the application's tree falls through to
the library's rather than claiming the name.

An application that wants a different money input drops `nodes/inputs/dollar.html` into its own
resources and inherits the remaining 35 templates untouched. The same works for `page.html`,
`all-screens.html` and any `fragments/*`.

`process` sets `basePath` and the whole `app` object on every context, so a template can write
`th:href="|${basePath}/resources/…|"` without its caller having to remember.

### 2. Locales, layered

`Locale` resolves a key across three sources, application first:

1. the application's own `locales/{lang}.yaml`, read from disk
2. this library's `/form-builder/locales/{lang}.yaml`, read from the classpath
3. the generated `locales/flow_{lang}.yaml`, extracted from the flow XML

The library carries the chrome that is identical in every application, `components.*` and
`workspace.tools.*`, in all eight languages. An application's `en.yaml` carries only its own words,
and declaring a chrome key there wins.

> Locale tests have to compare the layered result rather than the application's file on its own.
> `chromeLocaleContent` is public for exactly that reason. An application whose English inherits the
> chrome and whose Spanish overrides it has no missing keys, though a raw file-to-file comparison
> will report some. See either reference application's `YamlValidatorSpec`.

`generateFlowLocaleFile` rewrites `flow_{default}.yaml` on every build, so hand edits to it are lost.
`syncTranslationLocales` re-keys the translated files against it, seeding new entries with the
English text. It runs only from an Author Mode save, never from a normal build, because it rewrites
human-maintained files.

### 3. Node types

`FormBuilderApp.nodeTypes` maps a flow XML element name to a `FlowNodeParser`, merged over
`FlowNodeTypes.builtIn`, so an application can add an element or replace one. The built-ins are
`fg-alert`, `fg-apply`, `fg-collection`, `fg-detail`, `fg-set`, `modal-dialog` and `section`.
`<page>` is parsed only at the flow config root, and a nested one raises a specific error. Anything
unmatched falls through to `Html` and renders as ordinary markup, which is what lets a flow use `<p>`
and `<ul>` without registering anything.

The Tax Withholding Estimator's `fg-withholding-adjustments` is the worked example, and it lives in
that application rather than here.

### 4. Input types

`FormBuilderApp.inputTypes` maps an `<input type="…">` value to an `InputParser`. It is checked
before the built-in types, so registering an existing name replaces it. The built-ins are `text`,
`int`, `boolean`, `enum`, `multi-enum`, `dollar` and `date`. A `<select>` element inside an `fg-set`
is handled separately and does not go through `<input type="select">`.

A custom parser normally returns `Input.custom`, whose `name` also names the template that renders
it (`nodes/inputs/{name}.html`), supplied through the same application-first resolution. `nodeType`
on that case is how a custom input still gets the fact-type check every built-in gets: name the Fact
Graph node type it binds to, such as `"BooleanNode"`, or leave it `None` to opt out.
`suppliesOwnLabel` tells `fg-set` not to put a `<label>` in front of it.

That is the Scala half. The browser half is `registerInputType(name, { read, write, clear })` from
the flow runtime's `input-types.js`, which tells `<fg-set>` how to read the type's inputs, write a
fact value back into them and clear them. An input type needs both halves plus the template; see
[flow-runtime.md](flow-runtime.md#registering-an-input-type).

The Tax Withholding Estimator registers `single-checkbox` as a new type and `date` as a replacement.

### 5. The workspace mount fragments

Four fragments the library ships **empty**, which an application fills by putting a same-named file
in its own `templates/fragments/`:

| Fragment | Where it renders | What an application puts in it |
|---|---|---|
| `workspace-head.html` | `<head>`, under `--auditMode` | The audit panel stylesheet, a preload for the nav markup, the workspace element modules. |
| `taxpert-config.html` | `<head>`, under `--auditMode` | The `configure()` call: nav taxonomy, endpoints, determinations, feature flags. |
| `workspace-enable.html` | End of `<body>`, under `--auditMode` | `enable()` at load, after the flow markup and fact graph exist. |
| `workspace-all-screens.html` | Browse All, ungated | Two fragments, `-head` and `-body`, for the screens toolbar's stylesheet and module. |

A fifth fragment, `app-head.html`, has the same shape with no workspace involved: whatever else an
application wants in `<head>`.

The library decides that there is a workspace slot and when it is filled. What fills it is left to
the application, because the alternative is hardcoding the internal file layout of a package this
library neither depends on nor versions. It also keeps `include_taxpert_workspace: no` in the
cookiecutter a file that is simply not emitted, with no conditional inside a library template. The
cost is around 30 lines of mount markup living once per application rather than once here.

The library does place the workspace's element *tags* (`fragments/taxpert-global-nav.html`,
`fragments/audit-panel.html`, `fragments/tool-dock.html`). An undefined custom element name is inert
markup, so those are safe to emit without the module that upgrades them.

## The flow runtime's configuration

The flow runtime reads its configuration from `<meta>` tags that `fragments/head.html` renders
**ungated**, because a questionnaire runs whether or not it has a workspace over it:

```html
<meta name="form-builder:storage-prefix" th:content="${app.storageKeyPrefix}" />
<meta name="form-builder:base-path" th:content="${basePath}" />
```

`website-static/flow-runtime/js/runtime-config.js` seeds itself from those on first use, and exposes
`configureRuntime()` for a bundler or a test that knows better. An explicit `configureRuntime()` call
wins over the `<meta>` values whichever order they arrive in.

Meta tags are used here instead of a configuring `<script>` because `fg-fact-graph.js` reads the
stored graph at its top level. A script would have to execute before it, which document order does
give, but silently. Meta tags are parsed before any module runs, so there is no ordering to get
wrong.

This is separate from taxpert's `configure()`. The workspace keeps its own configuration and its own
storage prefix, the two never share a storage key, and neither package imports the other.

## Flow, facts and application locales are read from disk

`FormBuilder.regenerate` reads them with `os.read` against the source tree, never
`Source.fromResource`. Author Mode patches those XML files on disk and calls `regenerate` again
in-process, which makes sbt's `~run` watcher rebuild `target/…/classes` underneath a running
process. The classpath copy at that moment is either stale or transiently missing.

Only the library's own templates, chrome locales and browser assets come off the classpath, because
nothing edits those at runtime.

One consequence is worth knowing in advance. Editing a stylesheet under
`src/main/resources/form-builder/website-static/theme/` during a `~run` session hits exactly that
staleness, and the site keeps serving the previous copy. Run `sbt publishLocal` here and restart the
application. There is no live reload for these files.

## Browser assets

`FormBuilderAssets.extractInto` copies `/form-builder/website-static` out of this jar into a
generated site's `resources/vendor/form-builder/`. It handles a `file:` URL (running inside this repo
under `sbt test`, where the resources are loose files in `target/…/classes`) and a `jar:` URL (an
application consuming the published artifact). A missing resource root raises immediately, since an
extraction that quietly wrote nothing would produce a site whose every page loads a stylesheet that
404s, with no build step failing.

Two things about that destination are load-bearing:

- Templates hardcode the matching URL as `${basePath}/resources/vendor/form-builder/…`, because
  Thymeleaf cannot read a Scala constant. Changing `vendorPath` means grepping the templates for
  `vendor/form-builder`.
- The tree must not be flattened. The theme's stylesheet-relative icon URLs walk four levels up from
  `vendor/form-builder/theme/styles/<dir>/` to reach `vendor/uswds-3.13.0/img/`.

`makeCollectionIdPath` exists in both this package and taxpert on purpose. Form Builder is a Scala
jar rather than an npm package, so taxpert cannot import from it, and a relative path into
`vendor/form-builder/` exists only in a built application. Keep the two one-line copies identical.

## Test fixtures

`src/test/resources/pet-planner/` is a fictional non-tax application, and
`src/test/scala/gov/irs/formbuilder/FixtureApp.scala` is the `FormBuilderApp` built over it (`appId`
`pet-planner`, base path `/app/pet-planner`, port 3999, `resourceRoot` pointed at
`src/test/resources`). Generator specs run against Pet Planner rather than against either real
application, so this library cannot quietly grow a dependency on the EITC. If a spec can only be
made to pass by encoding something tax-specific, that behavior probably belongs in an application.

The fixture declares two locales rather than one, so the rule that the default language is generated
at the root and every other under its own segment stays exercised. Its checked-in files are only the
four locale YAMLs and an empty `website-static/`. The flow and dictionary XML each spec needs is
written inline in the spec itself, which is why the graph and website generators can be asserted one
construct at a time.

Four ScalaTest specs, all `AnyFunSpec`, 39 tests between them:

| Spec | Covers |
|---|---|
| `generators/WebsiteSpec.scala` | Page rendering, routes, per-locale output, `WebsitePage.filepath` |
| `generators/FormBuilderGraphSpec.scala` | The Form Graph Model JSON, restating the validation `fact-explorer/src/model/fgm.js` performs on the consuming side |
| `FormBuilderAssetsSpec.scala` | The one mechanism that reads a resource *tree*, asserting that every stylesheet the theme imports actually lands |
| `authoring/FactGraphMessageSpec.scala` | `AuthoringServer.factGraphMessage`, which strips the `CompNodeConfig(…)` dump off an inline authoring error |

`tests/` holds two `node --test` suites for the browser assets, 16 tests in total:
`runtime-config.test.mjs` and `fg-graph-bridge.test.mjs`. Both stub what they need (a fake
`BroadcastChannel`, a fake `document`) instead of pulling in jsdom.

## Gotchas

- **Nothing here may name a `vendor/taxpert/` path.** The workspace mounts through the empty
  `fragments/workspace-*.html` an application fills in. This is enforced by reading, not by a test.
- **`flow_{lang}.yaml` is generated.** Authored text lives in the flow XML. A hand edit to the
  generated file is lost on the next build.
- **A new build flag must not be a prefix of an existing one.** The cookiecutter's
  `post_gen_project.py` strips a flag from a generated Makefile with a bare string replace, so adding
  `--scenario` would leave `Mode` behind in every line that had `--scenarioMode`.
- **Author Mode binds loopback only by default.** It can patch source XML and commit to git, so it
  must not be reachable off-box. A docker overlay that sets `-Dsmol.author.host=0.0.0.0` relies on a
  host-side port mapping such as `127.0.0.1:3004:3004` for that guarantee instead.
- **Editing theme CSS during `~run` serves a stale copy.** See the disk-versus-classpath section
  above.
- **`facts/*.xml` are merged in sorted filename order**, and a duplicate `<Fact path="…">` is
  last-wins. `File.listFiles` order is undefined and varies by OS, so the sort is what makes builds
  reproducible.
