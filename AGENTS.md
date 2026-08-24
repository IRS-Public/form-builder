# AGENTS.md: form-builder

`gov.irs::form-builder` is a Scala 3 library that turns Flow XML, a Fact Dictionary, and a set of
locale files into a multi-language static site. It also ships the browser half of that site inside
its own jar. The theme (design tokens, page layout, and the styling for every element the
generators emit) and the flow runtime (the `<fg-set>`, `<fg-collection>` and `<fg-show>` custom
elements, the Fact Graph bootstrap, navigation, validation) live as classpath resources and are
extracted into a generated site by `FormBuilderAssets.scala`. One Scala dependency is enough to get
a styled, working questionnaire, with no npm step in the application.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is the authoritative reference for the layout, the
build flags, and the extension seams. Read it before making a change. This file is a short
orientation plus the rules that are easiest to break.

## Where this fits

| Repository | What it is |
|---|---|
| [fact-graph](https://github.com/IRS-Public/fact-graph) | `gov.irs::factgraph`, the rules engine. Cross-compiled to a JVM jar and a Scala.js browser bundle. |
| **form-builder** (here) | The scaffold. Parsers, generators, the Thymeleaf engine, node templates, chrome locales, RELAX NG seeds, the theme, the flow runtime, Author Mode. |
| [taxpert](https://github.com/IRS-Public/taxpert) | The optional workspace UI and its companion services. An application runs the same without it. |
| [form-builder-template](https://github.com/IRS-Public/form-builder-template) | Cookiecutter that generates a new application. |
| [form-builder-examples](https://github.com/IRS-Public/form-builder-examples) | Credit Assistant (EITC) and the Tax Withholding Estimator. |

The dependency runs one way. An application requires this library. Taxpert is tooling laid over an
application, and neither package imports the other. Nothing in this repository may name a path
inside `vendor/taxpert/`. The workspace mounts through four fragments that this library ships
empty, listed under the extension points below. That constraint is enforced by reading rather than
by a test, so grep `src/main/resources/form-builder/templates` for `vendor/taxpert` and confirm
that every hit is prose.

## Requirements and commands

JDK 17 or newer, sbt, and Node 20 or newer for the JavaScript tooling.

```bash
sbt test            # ScalaTest. Four specs, 39 tests, all against the Pet Planner fixture
sbt publishLocal    # writes 0.1.0-SNAPSHOT to ~/.ivy2/local, where applications resolve it
npm test            # node --test over the browser assets in tests/
npm run lint        # eslint over website-static JavaScript
npm run format      # eslint --fix
```

Neither this library nor `gov.irs::factgraph` is published to a remote registry, so `publishLocal`
from a checkout is the only way either one reaches an application. `build.sbt` declares no
resolvers for that reason.

After a change here, run `sbt test publishLocal`, then run `make ci` in **both** example
applications. The second application is what catches an assumption that only holds for the first.

## Layout

| Path | What is in it |
|---|---|
| `src/main/scala/gov/irs/formbuilder/` | `FormBuilder.scala` (the entry point), `FormBuilderApp`, `FormBuilderAssets`, `FormBuilderTemplateEngine`, `FactDictionaryLoader`, `Locale`, `build/Flags` |
| `.../parser/` (18 files) | Flow XML into a tree of `FlowNode` case classes |
| `.../generators/` (5 files) | `Website`, `AllScreens`, `AuthorMode`, `FlowManifest`, `FormBuilderGraph` |
| `.../authoring/` (3 files) | The Author Mode HTTP backend |
| `src/main/resources/form-builder/templates/` | 36 Thymeleaf templates: 19 under `nodes/` (8 of those in `nodes/inputs/`), 13 under `fragments/`, and 4 at the top level |
| `.../locales/` | Chrome strings (`components.*`, `workspace.tools.*`) in 8 languages |
| `.../schema/` | `FlowConfig.rng` and `FactDictionaryModule.rng`, seed copies only |
| `.../website-static/theme/` | 15 CSS files, extracted into a built site at `vendor/form-builder/` |
| `.../website-static/flow-runtime/` | 15 ES modules, the custom elements a generated questionnaire runs on |
| `src/test/resources/pet-planner/` | A fictional non-tax fixture application |
| `tests/` | Two `node --test` suites for the browser assets |

## The five extension points

An application is configuration over this library. `FormBuilderApp` holds everything that varies
between applications, and `FormBuilder.run(app, args)` is the entire entry point. An application's
name, URL segment, and storage prefix belong in its own `FormBuilderApp`, never in a file here.

1. **Templates.** Two `ClassLoaderTemplateResolver`s, `/{appId}/templates/` at order 1 and
   `/form-builder/templates/` at order 2. An application overrides `nodes/inputs/dollar.html` by
   dropping a same-named file into its own resources and inherits the other 35 untouched.
2. **Locales.** Three layers, application first: the application's `locales/{lang}.yaml`, then this
   library's chrome locales, then the generated `locales/flow_{lang}.yaml`.
3. **Node types.** `FormBuilderApp.nodeTypes` maps a flow XML element name to a `FlowNodeParser`,
   merged over the built-ins. Unmatched elements fall through to `Html` and render as ordinary
   markup, which is what lets a flow use `<p>` and `<ul>` without registering anything.
4. **Input types.** `FormBuilderApp.inputTypes` maps an `<input type="...">` value to an
   `InputParser`, checked before the built-ins, so registering an existing name replaces it.
5. **Workspace mounts.** Four fragments shipped empty, which an application fills by putting a
   same-named file in its own `templates/fragments/`: `workspace-head.html`, `taxpert-config.html`,
   `workspace-enable.html`, and `workspace-all-screens.html`. A fifth, `app-head.html`, has the
   same shape with no workspace involved.

## Gotchas

- **Flow, facts, and application locales are read from disk with `os.read`, never
  `Source.fromResource`.** Author Mode patches those XML files on disk and calls `regenerate`
  in-process, which makes sbt's `~run` watcher rebuild `target/.../classes` underneath a running
  process. The classpath copy at that moment is stale or transiently missing. Only this library's
  own templates, chrome locales, and browser assets come off the classpath.
- **Editing theme CSS during a `~run` session serves the previous copy.** There is no live reload
  for `website-static/`. Run `sbt publishLocal` and restart the application.
- **`flow_{lang}.yaml` is generated on every build.** Authored text lives in the flow XML, and a
  hand edit to the generated file is lost. `syncTranslationLocales` runs only from an Author Mode
  save, never from a normal build, because it rewrites human-maintained files.
- **Locale tests have to compare the layered result rather than the application's file alone.**
  `chromeLocaleContent` is public for that purpose. An application that inherits the chrome in
  English and overrides it in Spanish has no missing keys, though a raw file-to-file comparison
  reports some.
- **A new build flag must not be a prefix of an existing one.** The cookiecutter's
  `post_gen_project.py` strips a flag from a generated Makefile with a bare string replace, so
  adding `--scenario` would leave `Mode` behind in every line that had `--scenarioMode`.
- **Author Mode binds loopback only by default.** It can patch source XML and commit to git, so it
  must not be reachable off-box.
- **`facts/*.xml` are merged in sorted filename order, and a duplicate `<Fact path="...">` is
  last-wins.** `File.listFiles` order is undefined and varies by OS, so the sort is what makes
  builds reproducible.
- **The extracted asset tree must not be flattened.** Theme stylesheets use relative icon URLs that
  walk four levels up from `vendor/form-builder/theme/styles/<dir>/` to reach
  `vendor/uswds-3.13.0/img/`. Templates hardcode `${basePath}/resources/vendor/form-builder/...`
  because Thymeleaf cannot read a Scala constant, so changing `vendorPath` means grepping them.
- **`makeCollectionIdPath` is duplicated in taxpert deliberately.** This is a Scala jar rather than
  an npm package, so taxpert cannot import from it. Keep the two one-line copies identical.
- **The two `.rng` files here are seeds, and nothing in this library validates against them.** A
  generated application keeps its own copies and owns them from that point on, because an
  application that registers a custom node type has to widen its own grammar.
- **Generator specs run against Pet Planner rather than either real application.** If a spec can
  only be made to pass by encoding something tax-specific, that behavior belongs in an application.

## Deeper reading

Four documents under `docs/internals/` cover one area each, and the source files point at them
rather than restating them: [app-entry-and-assets.md](docs/internals/app-entry-and-assets.md),
[flow-parsing-and-generation.md](docs/internals/flow-parsing-and-generation.md),
[flow-runtime.md](docs/internals/flow-runtime.md), and
[author-mode.md](docs/internals/author-mode.md).
