# form-builder

`gov.irs::form-builder` is a Scala 3 library that turns **Flow XML plus a Fact Dictionary** into a
static, multi-language questionnaire site. You describe the questions in XML, describe the facts
behind them in a fact dictionary, and the library parses both, renders every page in every declared
language, and writes the result to `./out` as plain HTML.

It also ships the browser half of that site inside its own jar. The theme (design tokens, page
layout, and the styling for every element the generators emit) and the flow runtime (the `<fg-set>`,
`<fg-collection>` and `<fg-show>` custom elements, the Fact Graph bootstrap, navigation and
validation) live as classpath resources and are extracted into the generated site as it builds. One
Scala dependency is enough to get a styled, working questionnaire, with no npm step in the
application.

An application built on this library is called a **Form Builder app**. Its whole Scala surface is one
`FormBuilderApp` value and one call to `FormBuilder.run`.


### Contributing
Please see [CONTRIBUTING.md](./CONTRIBUTING.md) for details.

This codebase is dedicated to the public domain under the [Creative Commons Zero v1.0 Universal](LICENSE.md) license (CC0 1.0).

## Legal Disclaimer: Public Repository Access

> This repository contains draft and under-development source code. It is made available to the public solely for transparency, collaboration, and research purposes.
>
> **No Endorsement or Warranty**
>
> IRS does not endorse, maintain, or guarantee the accuracy, completeness, or functionality of the code in this repository. The IRS assumes no responsibility or liability for any use of the code by external parties, including individuals, developers, or organizations. This includes, but is not limited to, any tax consequences, computation errors, data loss, or other outcomes resulting from the use or modification of this code.
>
> Use of the code in this repository is at your own risk. This repository is not intended for production use or public consumption as a finalized product.


## Where it fits

These used to be directories in one monorepo. They are separate repositories now.

| Component | What it is |
|---|---|
| [`fact-graph`](https://github.com/IRS-Public/fact-graph) | `gov.irs::factgraph`, the evaluation engine. Cross-compiled: a JVM jar this library builds against, and a Scala.js bundle the browser runs. |
| **`form-builder`** (here) | The scaffold. Parser, generators, Thymeleaf engine, node templates, chrome locales, seed RELAX NG schemas, theme, and flow runtime. |
| [`taxpert`](https://github.com/IRS-Public/taxpert) | The workspace UI (`taxpert` on npm, in that repo's `packages/ui`): global nav, audit panel, tool panels. Optional. An application can ship without it. That repo's `packages/fact-explorer` is a React and Vite SPA that visualizes any Form Builder app's flow and facts as a graph, reading the JSON this library emits under `--formBuilderGraph`. |
| [`form-builder-template`](https://github.com/IRS-Public/form-builder-template) | A cookiecutter that generates a new Form Builder app. |
| [`form-builder-examples`](https://github.com/IRS-Public/form-builder-examples) | The two reference applications: Credit Assistant (EITC) and the Tax Withholding Estimator. Each is flow XML, facts, locales, brand CSS, and a small `Main.scala`. |

The dependency runs one way. This library names no path inside `vendor/taxpert/`, and nothing here
imports from the workspace package. Grep the template tree for `vendor/taxpert` and every hit is
prose in a comment.

## Requirements

| Tool | Version | Needed for |
|---|---|---|
| JDK | Not pinned by the build. The suite here was last run green on 25. | Everything |
| sbt | 1.11.4, pinned in `project/build.properties` | Everything |
| Scala | 3.7.2, set by `build.sbt` | Everything |
| Node | 18.18 or newer, which is what the pinned eslint 9 requires | Only for linting and testing the shipped browser assets |

### Getting `gov.irs::factgraph`

Fact Graph is a separate library and is consumed independently of any GitHub-native service. It is
on neither Maven Central nor GitHub Packages today, so the way to get it is a local publish from a
checkout:

```bash
git clone https://github.com/IRS-Public/fact-graph.git
cd fact-graph && sbt publishLocal    # -> ~/.ivy2/local/gov.irs/factgraph_3/3.1.0-SNAPSHOT
```

`~/.ivy2/local` is already first in sbt's default resolver chain, so `build.sbt` here carries no
resolver entry for it. If Fact Graph is later published to Maven Central, which is also a default
resolver, this build picks it up with no change.

### Publishing, and the authentication it requires

Form Builder publishes to **GitHub Packages** under the `gov.irs` group. Maven Central verifies that
namespace against DNS on `irs.gov`, which is not self-claimable, so Central was not an option. The
cost of the alternative is that GitHub Packages requires authentication even to *read* a public
package. Every consumer, including a stranger evaluating this library, has to add the resolver and
supply a token.

Three environment variables drive it, all read in `build.sbt`:

| Variable | Meaning | Default |
|---|---|---|
| `GITHUB_OWNER` | The org the packages live under. | `IRS-Public` |
| `GITHUB_ACTOR` | Your GitHub login. GitHub Actions supplies this already. | empty |
| `GITHUB_TOKEN` | A classic PAT. `read:packages` to consume, `write:packages` to publish. | empty |

```bash
GITHUB_OWNER=IRS-Public GITHUB_ACTOR=<login> GITHUB_TOKEN=<PAT with write:packages> \
  sbt publish
```

If you would rather not deal with tokens while evaluating the library, `sbt publishLocal` puts it in
`~/.ivy2/local` alongside Fact Graph, and a local application build will resolve it from there.

## Build and test

```bash
sbt test              # ScalaTest suite
sbt publishLocal      # -> ~/.ivy2/local/gov.irs/form-builder_3/0.1.0/
sbt publish           # -> GitHub Packages, with the credentials above
sbt scalafmtAll       # format the Scala
sbt scalafmtCheckAll  # check formatting, as CI does

npm install           # once, for the JS tooling
npm test              # node --test over tests/*.test.mjs
npm run lint          # eslint over the shipped browser assets
npm run format        # eslint --fix
```

`package.json` is named `form-builder-assets` and is marked `private`. Nothing here is published to
npm. It exists so the JavaScript inside
`src/main/resources/form-builder/website-static/` is held to the same lint and test standard as the
rest of the client code in this ecosystem. `eslint.config.js` builds on `neostandard` plus
`eslint-plugin-security`, and adds a rule that flags `innerHTML`, `outerHTML` and
`insertAdjacentHTML` assignments in the shipped assets, since that markup is supposed to come from
the Thymeleaf node templates. Two runtime files carry an inline disable with a written reason.

Scala formatting is scalafmt 3.9.9, configured in `.scalafmt.conf` (120 columns, Scala 3 dialect,
sorted imports, forced trailing commas). `project/plugins.sbt` has one plugin, `sbt-scalafmt`.

There is no scaladoc artifact. `publishLocal` would otherwise run scaladoc, which reads the TASTy of
every dependency, and factgraph's is cross-built for Scala.js, so its `@JSExport` annotations fail to
resolve on the JVM classpath. `build.sbt` sets `Compile / packageDoc / publishArtifact := false` for
that reason.

After publishing a change, re-run **both** reference applications' `make ci`. The second application
is what catches a change that quietly assumed something only the first one does.

### Dependencies

Declared in `build.sbt`:

| Dependency | Version | Used for |
|---|---|---|
| `gov.irs::factgraph` | 3.1.0-SNAPSHOT | The fact dictionary and evaluation engine |
| `org.scala-lang.modules::scala-xml` | 2.4.0 | Parsing flow and fact XML |
| `com.lihaoyi::os-lib` | 0.11.4 | All filesystem access |
| `org.thymeleaf:thymeleaf` | 3.1.5.RELEASE | Template rendering |
| `org.jsoup:jsoup` | 1.21.1 | Post-processing rendered HTML |
| `io.circe::circe-{core,generic,parser}` | 0.14.15 | JSON output |
| `io.circe::circe-yaml`, `circe-yaml-scalayaml` | 0.16.0 | Locale YAML |
| `com.github.tototoshi::scala-csv` | 2.0.0 | Scenario spreadsheets |
| `org.smol-utils::smol` | 0.1.2 | The embedded dev static server |
| `org.scalatest::scalatest` | 3.2.19 (Test) | The test suite |

## An app is configuration over this library

```scala
package gov.irs.hellotax

import gov.irs.formbuilder.{ FormBuilder, FormBuilderApp }
import scala.collection.immutable.ListMap

val app: FormBuilderApp = FormBuilderApp(
  appId = "hello-tax",             // resource directory under src/main/resources
  basePath = "/app/hello-tax",     // URL prefix, no trailing slash
  outSubdir = "app/hello-tax",     // where the site is written beneath ./out
  locales = ListMap("en" -> "English", "es" -> "Español"),  // first entry is the default
  defaultPort = 3010,
  brand = "Hello Tax",
)

@main def main(args: String*): Unit = FormBuilder.run(app, args)
```

Every `FormBuilderApp` field, in declaration order:

| Field | Default | Meaning |
|---|---|---|
| `appId` | required | The application's directory under `src/main/resources`, and the classpath prefix its own templates resolve from. |
| `basePath` | required | The URL prefix every generated link and asset href is built from. Templates read `${basePath}` rather than spelling it out. |
| `outSubdir` | required | Where the site is written beneath `./out`. Usually `basePath` without its leading slash, kept separate so a deployment can serve from a different prefix than it builds into. |
| `locales` | required | Language code to native display name, in language-switcher order. The first entry is the default: it is generated at the site root, and every other language under `/{code}/`. |
| `defaultPort` | required | The dev server's port when `-Dsmol.port` says nothing. |
| `brand` | required | The product name, used in the dev server's startup banner. |
| `storagePrefix` | `None` | Namespaces every browser storage key the generated site writes. Falls back to `appId`. Two Form Builder apps served from one origin will not rehydrate each other's fact graph. Override it only to keep an existing application's keys stable. |
| `nodeTypes` | empty | Extension point: flow XML element name to `FlowNodeParser`. |
| `inputTypes` | empty | Extension point: `<input type="…">` value to `InputParser`. |
| `resourceRoot` | `os.pwd / "src" / "main" / "resources"` | The source tree flow, facts, locales and static assets are read from. |

`FormBuilderApp` also derives a few paths from `appId` and `resourceRoot`, which is where the
generators look: `flowDir`, `factsDir`, `localesDir`, `websiteStaticDir` and `scenariosDir`.

`appId`, the URL segment and the sbt project name are independent on purpose. Credit Assistant lives
in `credit-assistant/`, keeps its resources under `credit-assistant/`, and serves from `/app/eitc`.

Adding an application's name, URL segment or storage prefix to a file in this library is a sign the
value belongs in that application's `FormBuilderApp` instead.

To start a new application, run the cookiecutter rather than copying an existing one:

```bash
cookiecutter gh:IRS-Public/form-builder-template
```

## Layout

| Path | What is in it |
|---|---|
| `build.sbt` | `gov.irs::form-builder` 0.1.0, the dependency list, and the publishing setup |
| `src/main/scala/gov/irs/formbuilder/FormBuilder.scala` | `run`, `regenerate`, `parseFlow`, `resolvedFlowConfig`. The whole entry point |
| `…/FormBuilderApp.scala` | The configuration case class above |
| `…/FormBuilderAssets.scala` | Extracts `website-static` out of this jar into a built site |
| `…/FormBuilderTemplateEngine.scala` | Two Thymeleaf resolvers, application first, plus the message resolver |
| `…/FactDictionaryLoader.scala` | Reads and merges `facts/*.xml` in sorted order |
| `…/Locale.scala` | The layered locale lookup, plus `generateFlowLocaleFile` and `syncTranslationLocales` |
| `…/Log.scala` | The build's console logging |
| `…/build/Flags.scala` | Every `--flag` the generator understands |
| `…/exceptions/InvalidFormConfig.scala` | The one exception type the parser throws |
| `…/parser/` (18 files) | Flow XML to a tree of `FlowNode` case classes. `FlowParser`, `Page`, `Section`, `FgSet`, `FgCollection`, `FgAlert`, `FgApply`, `FgDetail`, `FgSectionGate`, `Modal`, `Input`, `Condition`, `PageSplitter`, `TranslationContext`, `Html` and friends |
| `…/generators/` (5 files) | `Website`, `AllScreens`, `AuthorMode`, `FlowManifest`, `FormBuilderGraph` |
| `…/authoring/` (3 files) | `AuthoringServer`, `DerivedXml`, `DerivedGrammar`. The Author Mode HTTP backend |
| `src/main/resources/form-builder/templates/` | 36 Thymeleaf templates (see below) |
| `src/main/resources/form-builder/locales/` | Chrome strings in 8 languages: `en`, `es`, `ht`, `ko`, `ru`, `vi`, `zh-hans`, `zh-hant` |
| `src/main/resources/form-builder/schema/` | `FlowConfig.rng`, `FactDictionaryModule.rng`. Seed copies (see below) |
| `src/main/resources/form-builder/website-static/theme/styles/` | 15 CSS files, extracted into `vendor/form-builder/` at build time |
| `src/main/resources/form-builder/website-static/flow-runtime/js/` | 15 ESM files, the custom elements a generated questionnaire runs on |
| `src/test/` | ScalaTest suite, generated against the Pet Planner fixture |
| `tests/` | `node --test` suites for two of the browser-asset modules |

The 36 templates break down as 19 under `nodes/` (8 of those in `nodes/inputs/`), 13 under
`fragments/`, and four at the top level: `page.html`, `all-screens.html`, `author-mode.html` and
`errors.html`.

The two `.rng` files here are seed copies. Nothing in this library validates against them. A
generated application keeps its own at `<appId>/flow/FlowConfig.rng` and
`<appId>/facts/FactDictionaryModule.rng`, and its `make validate-xml` runs `xmllint --relaxng`
against those, because an application that registers a custom node type has to widen its own
grammar. `form-builder-template` copies these files into a new application, which owns them from
that point on. Author Mode also validates edits against the application's copy of
`FactDictionaryModule.rng`, not this one.

## What a build produces

`FormBuilder.run` parses the flow, regenerates the default-language flow locale file, renders every
page in every language, and writes the tree under `./out/<outSubdir>/`.

```
out/<outSubdir>/
├── index.html                 the default-language pages, one directory per route
├── es/…                       every other language under its own segment
├── all-screens/               only under --allScreens
├── author/                    only under --authorMode
└── resources/
    ├── styles/ js/ img/       the application's own website-static, copied verbatim
    ├── vendor/form-builder/   the theme and flow runtime, extracted from the jar
    ├── fact-dictionary.xml    the merged dictionary the browser engine loads
    ├── flow-manifest.json     only under --singleQuestionPerScreen
    ├── form-builder-graph.json  only under --formBuilderGraph
    └── scenarios/             only under --scenarioMode
```

Flags are positional arguments to `FormBuilder.run`, so an application passes them through `sbt run`.
Anything that does not match `--\w+` raises an error at startup.

| Flag | Effect |
|---|---|
| `--serve` | Start the embedded `smol` static server. Port from `-Dsmol.port`, else `defaultPort`. |
| `--allScreens` | Also generate the Browse All page at `/all-screens`, listing every screen at once. |
| `--auditMode` | Fill the workspace slot in `<head>` and at the end of `<body>`, and set `audit-mode` on the page. |
| `--singleQuestionPerScreen` | Split every page into one question per screen, and emit `flow-manifest.json`. |
| `--scenarioMode` | Copy `scenarios/` into the site and offer the scenarios in the workspace's Scenario modal. |
| `--authorMode` | Generate the Author Mode page at `/author` and start its HTTP backend. Host and port from `-Dsmol.author.host` and `-Dsmol.author.port`, default `localhost:3004`. |
| `--aiScenarioGeneration` | Build-time default for the workspace's AI scenario generation flag. |
| `--aiFactExplanation` | Build-time default for the workspace's AI fact explanation flag. |
| `--formBuilderGraph` | Emit `resources/form-builder-graph.json` for Fact Explorer. Off by default, since a production build is the flow and nothing else. |

A production build passes no flags at all.

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
