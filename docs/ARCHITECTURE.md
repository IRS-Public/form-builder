# ARCHITECTURE

## Layout

| Path | What is in it |
|---|---|
| `build.sbt` | `gov.irs::form-builder` 0.1.0-SNAPSHOT and the dependency list |
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

> A production build passes no flags at all.


## Additional Documentation

Five documents under `docs/internals/` cover one area each in detail:

| Document                                                                   | Covers                                                                                                                                         |
|----------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| [app-entry-and-assets.md](internals/app-entry-and-assets.md)               | `FormBuilder.run`, `FormBuilderApp`, build flags, template resolution, locale layering, fact dictionary loading, browser-asset extraction      |
| [flow-parsing-and-generation.md](internals/flow-parsing-and-generation.md) | The `parser` and `generators` packages: the node model, conditions, translation keys, page splitting, and what each generator emits            |
| [flow-runtime.md](internals/flow-runtime.md)                               | The browser half: the custom elements, runtime configuration, path derivation, conditional visibility, validation and the Fact Explorer bridge |
| [author-mode.md](internals/author-mode.md)                                 | The authoring server: endpoints, edit kinds, the validation stack, and the preserve-and-patch writers                                          |
| [app-extensions.md](internals/app-extensions.md)                           | Various configuration and extension points for Form Building                                                                                   |
