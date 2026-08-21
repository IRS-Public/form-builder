# form-builder

`gov.irs::form-builder` is a Scala 3 library that turns **Flow XML plus a Fact Dictionary** into a
static, multi-language questionnaire site. You describe the questions in XML, describe the tax facts
behind them in a fact dictionary, and the library parses both, renders every page in every language,
and writes the result to `./out` as plain HTML.

It also ships the browser half of that site inside its own jar. The theme (design tokens, page
layout, the styling for every element the generators emit) and the flow runtime (the `<fg-set>` /
`<fg-collection>` / `<fg-show>` custom elements, the Fact Graph bootstrap, navigation and validation)
live as classpath resources and are extracted into the generated site as it builds. One Scala
dependency is enough to get a styled, working questionnaire, with no npm step in the app.

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
> IRS does not endorse, maintain, or guarantee the accuracy, completeness, or functionality of the code in this repository. The IRS assumes no responsibility or liability for any use of the code by external parties, including individuals, developers, or organizations. This includes—but is not limited to—any tax consequences, computation errors, data loss, or other outcomes resulting from the use or modification of this code.
>
> Use of the code in this repository is at your own risk. This repository is not intended for production use or public consumption as a finalized product.


## Where it fits

| Component | What it is |
|---|---|
| [`fact-graph`](https://github.com/IRS-Public/fact-graph) | `gov.irs::factgraph`, the evaluation engine. Cross-compiled: a JVM jar this library builds against, and a Scala.js bundle the browser runs. |
| **`form-builder`** (here) | The scaffold. Parser, generators, Thymeleaf engine, node templates, chrome locales, RELAX NG schemas, theme and flow runtime. |
| [`taxpert`](https://github.com/IRS-Public/taxpert) | The workspace UI (`taxpert` on npm, in that repo's `packages/ui`): global nav, audit panel, tool panels. Optional. An app can ship without it. Its `packages/fact-explorer` is a React/Vite SPA that visualizes any Form Builder app's flow and facts as a graph, reading the JSON this library emits under `--formBuilderGraph`. |
| [`form-builder-template`](https://github.com/IRS-Public/form-builder-template) | A cookiecutter that generates a new Form Builder app. |
| [`form-builder-example`](https://github.com/IRS-Public/form-builder-example) | The two Form Builder applications that exist — Credit Assistant (EITC) and the Tax Withholding Estimator. Each is flow XML, facts, locales, brand CSS, and a small `Main.scala`. |

The dependency runs one way. This library names no path inside `vendor/taxpert/`, and nothing here
imports from the workspace package. Grep the template tree for `vendor/taxpert` and every hit is
prose in a comment.

## Requirements

| Tool | Version |
|---|---|
| JDK | 21, which is what the apps' CI runs on |
| sbt | 1.11.4 (see `project/build.properties`) |
| Scala | 3.7.2, set by `build.sbt` |
| Node | 22, only for linting and testing the browser assets |

### Getting `gov.irs::factgraph`

Fact Graph is a separate library, consumed independently of any GitHub-native service.

```bash
git clone https://github.com/IRS-Public/fact-graph.git
cd fact-graph && make publish        # -> ~/.ivy2/local/gov.irs/factgraph_3/3.1.0-SNAPSHOT
```

`~/.ivy2/local` is already first in sbt's default resolver chain, so nothing here needs a resolver
entry. If Fact Graph is later published to Maven Central — also a default resolver — this build
picks it up with no change.

## Publishing

Form Builder publishes to **GitHub Packages** under `gov.irs`, because Maven Central verifies that
namespace against DNS on `irs.gov`, which is not self-claimable. The trade, stated so it stays a
choice: GitHub Packages requires authentication even to *read* a public package, so every consumer
adds a resolver and a token.

```bash
GITHUB_OWNER=IRS-Public GITHUB_ACTOR=<login> GITHUB_TOKEN=<PAT with write:packages> \
  sbt publish
```

Consumers need `read:packages` and the matching resolver — see either example app's `build.sbt` in
the [taxpert](https://github.com/IRS-Public/taxpert) repo.

## Build and test

```bash
sbt test              # ScalaTest suite
sbt publishLocal      # → ~/.ivy2/local/gov.irs/form-builder_3/0.1.0/
sbt scalafmtAll       # format the Scala
sbt scalafmtCheckAll  # check it, as CI does

npm install           # once, for the JS tooling
npm test              # node --test over tests/*.test.mjs
npm run lint          # eslint over the shipped browser assets
npm run format        # eslint --fix
```

There is no scaladoc artifact. `publishLocal` would otherwise run scaladoc, which reads the TASTy of
every dependency, and factgraph's is cross-built for Scala.js, so its `@JSExport` annotations fail to
resolve on the JVM classpath. `build.sbt` disables it deliberately.

After publishing, re-run **both** apps' `make ci`. The second app is what catches a change that
quietly assumed something only the first one does.

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
  storagePrefix = Some("hello-tax"),
)

@main def main(args: String*): Unit = FormBuilder.run(app, args)
```

Every `FormBuilderApp` field:

| Field | Meaning |
|---|---|
| `appId` | The app's directory under `src/main/resources`, and the classpath prefix its own templates resolve from. |
| `basePath` | The URL prefix every generated link and asset href is built from. Templates read `${basePath}` rather than spelling it out. |
| `outSubdir` | Where the site is written beneath `./out`. Usually `basePath` without the leading slash, kept separate so a deployment can differ. |
| `locales` | Language code to native display name, in switcher order. The first entry is generated at the site root, every other one under `/{code}/`. |
| `defaultPort` | The dev server's port when `-Dsmol.port` says nothing. |
| `brand` | The product name, used in the dev server's startup banner. |
| `storagePrefix` | Namespaces every browser storage key the generated site writes. Defaults to `appId`. Two Form Builder apps on one origin will not rehydrate each other's fact graph. |
| `nodeTypes` | Extension point: flow XML element name to `FlowNodeParser`. |
| `inputTypes` | Extension point: `<input type="…">` value to `InputParser`. |
| `resourceRoot` | The source tree flow, facts, locales and static assets are read from. Defaults to `os.pwd / "src" / "main" / "resources"`. |

`appId`, the URL segment and the sbt project name are independent on purpose. credit-assistant lives
in `credit-assistant/`, keeps its resources under `credit-assistant/`, and serves from `/app/eitc`.

Adding an app's name, URL segment or storage prefix to a file in this library is a sign the value
belongs in that app's `FormBuilderApp` instead.

To start a new app, run the cookiecutter rather than copying an existing one:

```bash
cookiecutter gh:IRS-Public/form-builder-template
```

## Layout

```
form-builder/
├── build.sbt                       gov.irs::form-builder, version 0.1.0
├── package.json                    lint + test tooling for the shipped browser assets
├── eslint.config.js
├── src/main/scala/gov/irs/formbuilder/
│   ├── FormBuilder.scala           run / regenerate / parseFlow: the whole entry point
│   ├── FormBuilderApp.scala        the configuration case class above
│   ├── FormBuilderAssets.scala     extracts website-static out of this jar into a built site
│   ├── FormBuilderTemplateEngine.scala  two Thymeleaf resolvers, app-first
│   ├── FactDictionaryLoader.scala  reads and merges facts/*.xml
│   ├── Locale.scala                the three-layer locale lookup and the generated flow_*.yaml
│   ├── build/Flags.scala           every --flag the generator understands
│   ├── parser/                     Flow XML to a tree of FlowNode case classes
│   ├── generators/                 Website, AllScreens, AuthorMode, FlowManifest, FormBuilderGraph
│   └── authoring/                  the Author Mode HTTP backend
├── src/main/resources/form-builder/
│   ├── templates/                  36 Thymeleaf templates (see below)
│   ├── locales/                    chrome strings in 8 languages
│   ├── schema/                     FlowConfig.rng, FactDictionaryModule.rng
│   └── website-static/
│       ├── theme/styles/           the theme, extracted into vendor/form-builder/ at build time
│       └── flow-runtime/js/        the custom elements a generated questionnaire runs on
├── src/test/                       ScalaTest suite, generated against the Pet Planner fixture
└── tests/                          node --test suites for the browser assets
```

The 36 templates break down as 19 under `nodes/` (8 of those are `nodes/inputs/`), 13 under
`fragments/`, and four at the top level: `page.html`, `all-screens.html`, `author-mode.html` and
`errors.html`.

## What a build produces

`FormBuilder.run` parses the flow, regenerates the default-language flow locale file, renders every
page in every language, and writes the tree under `./out/<outSubdir>/`.

```
out/<outSubdir>/
├── index.html                 the default-language pages, one directory per route
├── es/…                       every other language under its own segment
└── resources/
    ├── styles/ js/ img/       the app's own website-static, copied verbatim
    ├── vendor/form-builder/   the theme and flow runtime, extracted from the jar
    ├── fact-dictionary.xml    the merged dictionary the browser engine loads
    ├── flow-manifest.json     only under --singleQuestionPerScreen
    ├── form-builder-graph.json  only under --formBuilderGraph
    └── scenarios/             only under --scenarioMode
```

Flags are positional arguments to `FormBuilder.run`, so an app passes them through `sbt run`:

| Flag | Effect |
|---|---|
| `--serve` | Start the embedded `smol` static server. Port from `-Dsmol.port`, else `defaultPort`. |
| `--allScreens` | Also generate the Browse All page listing every screen at once. |
| `--auditMode` | Fill the workspace slot in `<head>` and at the end of `<body>`. |
| `--singleQuestionPerScreen` | Split every page into one question per screen, and emit `flow-manifest.json`. |
| `--scenarioMode` | Copy `scenarios/*.json` into the site and offer them in the Scenario modal. |
| `--authorMode` | Generate the Author Mode page and start its HTTP backend on `-Dsmol.author.port` (default 3004, loopback only). |
| `--aiScenarioGeneration` | Build-time default for the workspace's AI scenario generation flag. |
| `--aiFactExplanation` | Build-time default for the workspace's AI fact explanation flag. |
| `--formBuilderGraph` | Emit `resources/form-builder-graph.json` for Fact Explorer. Off by default, since a production build is the flow and nothing else. |

A production build passes no flags at all.

## The five extension points

### 1. Templates, resolved app-first

`FormBuilderTemplateEngine` registers two `ClassLoaderTemplateResolver`s: `/{appId}/templates/` at
order 1 and `/form-builder/templates/` at order 2. Both set `setCheckExistence(true)`, so a miss in the
app's tree falls through to the library's rather than claiming the name.

An app that wants a different money input drops `nodes/inputs/dollar.html` into its own resources and
inherits the remaining 35 templates untouched. The same works for `page.html`, `all-screens.html` and
any `fragments/*`.

`process` sets `basePath` and the whole `app` on every context, so a template writes
`th:href="|${basePath}/resources/…|"` without its caller having to remember.

### 2. Locales, layered

`Locale.get` resolves a key across three layers, app first:

1. the app's own `locales/{lang}.yaml`, read from disk
2. this library's `/form-builder/locales/{lang}.yaml`, read from the classpath
3. the generated `locales/flow_{lang}.yaml`, extracted from the flow XML

The library carries the chrome that is identical everywhere, `components.*` and `workspace.tools.*`,
in `en`, `es`, `ht`, `ko`, `ru`, `vi`, `zh-hans` and `zh-hant`. An app's `en.yaml` carries only its
own words, and declaring a chrome key in it wins.

> **Locale tests must compare the layered result**, not the app's file on its own. `chromeLocaleContent`
> is public for that reason. An app whose English inherits the chrome and whose Spanish overrides it
> is not missing keys, though a raw file-to-file comparison will say it is. See either app's
> `YamlValidatorSpec`.

`generateFlowLocaleFile` rewrites `flow_{default}.yaml` on every build, so hand edits to it are lost.
`syncTranslationLocales` re-keys the translated files against it, seeding new entries with the English
text under a `# TODO: translate` comment. That one runs only from an Author Mode save, never from a
normal build, because it rewrites human-maintained files.

### 3. Node types

`FormBuilderApp.nodeTypes` maps a flow XML element name to a `FlowNodeParser`, merged **over**
`FlowNodeTypes.builtIn`, so an app can add an element or replace one. The built-ins are `fg-alert`,
`fg-apply`, `fg-collection`, `fg-detail`, `fg-set`, `modal-dialog` and `section`. `<page>` is parsed
only at the flow config root. Anything unmatched renders as ordinary HTML, which is what lets a flow
use `<p>` and `<ul>` without registering anything.

tax-withholding-estimator's `fg-withholding-adjustments` is the worked example, and it lives in that
app, not here.

### 4. Input types

`FormBuilderApp.inputTypes` maps an `<input type="…">` value to an `InputParser`, checked before the
built-in `text`, `int`, `boolean`, `enum`, `multi-enum`, `dollar` and `date`. Registering an existing
name replaces it. TWE registers `single-checkbox` as a new type and `date` as a replacement.

### 5. The workspace mount fragments

Four fragments the library ships **empty** and an app fills in by putting a same-named file in its own
`templates/fragments/`:

| Fragment | Where it renders | What an app puts in it |
|---|---|---|
| `workspace-head.html` | `<head>`, under `--auditMode` | The audit panel stylesheet, a preload for the nav markup, the workspace element modules. |
| `taxpert-config.html` | `<head>`, under `--auditMode` | The `configure()` call: nav taxonomy, endpoints, determinations, feature flags. |
| `workspace-enable.html` | End of `<body>`, under `--auditMode` | `enable()` at load, after the flow markup and fact graph exist. |
| `workspace-all-screens.html` | Browse All, ungated | Two fragments, `-head` and `-body`, for the screens toolbar's stylesheet and module. |

A fifth fragment, `app-head.html`, is the same shape without the workspace: whatever else an app wants
in `<head>`.

The library decides *that* there is a workspace slot and when it is filled. It does not decide what
fills it, because that would mean hardcoding the internal file layout of a package it neither depends
on nor versions. The cost is about 30 lines of mount markup living once per app rather than once here.
The benefit is that `include_taxpert_workspace: no` in the cookiecutter is a file that is simply not
emitted, rather than a conditional inside a library template.

## The flow runtime's configuration

The flow runtime reads its configuration from `<meta>` tags that `fragments/head.html` renders
**ungated**, because a questionnaire runs whether or not it has a workspace over it:

```html
<meta name="form-builder:storage-prefix" th:content="${app.storageKeyPrefix}" />
<meta name="form-builder:base-path" th:content="${basePath}" />
```

`website-static/flow-runtime/js/runtime-config.js` seeds itself from those on first use, and
`configureRuntime()` is available for a bundler or a test that knows better. Two more values,
`endpoints.factGraphUrl` and `endpoints.factDictionaryUrl`, exist in the config but are not sent as
meta tags. Both are derived by `resourceUrl()` from `basePath`, and sending them would put the
vendored engine's version number in a second place to bump.

Meta tags rather than a configuring `<script>`, because `fg-fact-graph.js` reads the stored graph at
its top level. A script would have to execute before it, which document order does give, but silently.
Meta tags are parsed before any module runs, so there is no order to get wrong.

This is separate from taxpert's `configure()`. The workspace keeps its own configuration and its own
storage prefix, and the two never share a storage key, so the two prefixes stay independent without
either package importing the other.

## Flow, facts and app locales are read from disk

`FormBuilder.regenerate` reads them with `os.read` against the source tree, never `Source.fromResource`.
Author Mode patches those XML files on disk and calls `regenerate` again in-process, which makes sbt's
`~run` watcher rebuild `target/…/classes` underneath a running process. The classpath copy is then
either stale or transiently missing.

Only the library's own templates, chrome locales and browser assets come off the classpath, because
nothing edits those at runtime.

One consequence: editing a stylesheet under `website-static/theme/` during a `~run` session hits
exactly that staleness. Run `sbt publishLocal` and restart the app rather than expecting a live
reload.

## Browser assets

`FormBuilderAssets.extractInto` copies `/form-builder/website-static` out of this jar into a generated
site's `resources/vendor/form-builder/`. It handles both a `file:` URL (running inside this repo under
`sbt test`, where the resources are loose files in `target/…/classes`) and a `jar:` URL (an app
consuming the published artifact).

Two things about that destination are load-bearing:

- Templates hardcode the matching URL as `${basePath}/resources/vendor/form-builder/…`, because Thymeleaf
  cannot read a Scala constant. Changing `vendorPath` means grepping the templates for
  `vendor/form-builder`.
- The tree must not be flattened. The theme's stylesheet-relative icon URLs walk four levels up from
  `vendor/form-builder/theme/styles/<dir>/` to reach `vendor/uswds-3.13.0/img/`.

`makeCollectionIdPath` exists in both this package and taxpert on purpose. Form Builder is a Scala jar
rather than an npm package, so taxpert cannot import from it, and a relative path into
`vendor/form-builder/` exists only in a built app. Keep the two one-line copies identical.

## Test fixtures

`src/test/resources/pet-planner/` is a fictional non-tax app, and `FixtureApp.scala` is the
`FormBuilderApp` built over it. Generator specs run against Pet Planner rather than credit-assistant, so
the library cannot quietly grow a dependency on the EITC. If a spec can only be made to pass by
encoding something tax-specific, that behavior probably belongs in an app.

The fixture declares two locales rather than one, so "the default language is generated at the root
and every other under its own segment" stays exercised.

## Gotchas

- **Nothing here may name a `vendor/taxpert/` path.** The workspace mounts through the empty
  `fragments/workspace-*.html` an app fills in. This is enforced by reading, not by a test.
- **`flow_{lang}.yaml` is generated.** Authored text lives in the flow XML. A hand edit to the
  generated file is lost on the next build.
- **A new build flag must not be a prefix of an existing one.** The cookiecutter's
  `post_gen_project.py` strips a flag from a generated Makefile with a bare string replace, so adding
  `--scenario` would leave `Mode` behind in every line that had `--scenarioMode`.
- **Author Mode binds loopback only by default.** It can patch source XML, so it must not be reachable
  off-box. A docker overlay that sets `-Dsmol.author.host=0.0.0.0` relies on the host-side port
  mapping for that guarantee instead.
