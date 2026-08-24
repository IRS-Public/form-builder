# Form Builder

`gov.irs::form-builder` is a Scala 3 library that turns a flow definition, a fact dictionary, and a
set of locale files into a multi-language questionnaire site. You describe the pages and questions
in Flow XML, describe the data behind them in a Fact Dictionary, and provide translations as YAML
locale files. The library parses all of it, renders every page in every declared language, and
writes the result to `./out` as plain HTML.

It also ships the browser half of that site inside its own jar. The theme (design tokens, page
layout, and the styling for every element the generators emit) and the flow runtime (the
`<fg-set>`, `<fg-collection>`, and `<fg-show>` custom elements, the Fact Graph bootstrap,
navigation, and validation) live as classpath resources and are extracted into the generated site
as it builds. One Scala dependency is enough to get a styled, working questionnaire, with no npm
step required in the application that consumes it.

Form Builder extends [Fact Graph](https://github.com/IRS-Public/fact-graph), a separate rules
engine library, and does not duplicate its evaluation logic. Form Builder is a generalization of
the Scala and presentation code originally developed for two IRS applications, the Tax Withholding
Estimator and the EITC Assistant (now Credit Assistant); both live today as reference applications
in [form-builder-examples](https://github.com/IRS-Public/form-builder-examples).

## Where this fits

| Repository | What it is |
|---|---|
| [fact-graph](https://github.com/IRS-Public/fact-graph) | `gov.irs::factgraph`, the rules engine. Cross-compiled to a JVM jar and a Scala.js browser bundle. |
| **form-builder** (here) | The scaffold: parsers, generators, the Thymeleaf engine, node templates, chrome locales, RELAX NG schema seeds, the theme, and the flow runtime. |
| [taxpert](https://github.com/IRS-Public/taxpert) | The optional workspace UI and its companion services (global nav, audit panel, tool panels). An application runs the same without it, and neither package imports the other. |
| [form-builder-template](https://github.com/IRS-Public/form-builder-template) | A cookiecutter that generates a new Form Builder application, with optional extensions such as the Taxpert workspace. |
| [form-builder-examples](https://github.com/IRS-Public/form-builder-examples) | Reference applications built on this library: Credit Assistant and the Tax Withholding Estimator. |

The dependency runs one way: an application requires this library, and this library requires
Fact Graph. Nothing in this repository imports from an application or from Taxpert.

## Getting started

Requirements: JDK 17 or newer, sbt (pinned to 1.11.4 in `project/build.properties`), and Node 18.18
or newer for the JavaScript tooling.

Neither this library nor `gov.irs::factgraph` is published to a remote Maven registry, so a local
publish from a checkout is how every consumer gets them:

```bash
git clone https://github.com/IRS-Public/fact-graph.git
cd fact-graph && sbt publishLocal      # -> ~/.ivy2/local/gov.irs/factgraph_3/3.1.0-SNAPSHOT

git clone https://github.com/IRS-Public/form-builder.git
cd form-builder && sbt publishLocal    # -> ~/.ivy2/local/gov.irs/form-builder_3/0.1.0-SNAPSHOT
```

`~/.ivy2/local` is already first in sbt's default resolver chain, so `build.sbt` declares no
resolver entries for either artifact. The version is a `-SNAPSHOT` deliberately: it is a promise
that the artifact changes, so the edit, `publishLocal`, and rebuild loop this library is developed
in actually reaches the applications built on it.

```bash
sbt test              # ScalaTest suite (4 specs, 39 tests) against the Pet Planner fixture
sbt publishLocal      # writes 0.1.0-SNAPSHOT to ~/.ivy2/local
sbt scalafmtCheckAll  # check Scala formatting, as CI does

npm install           # once, for the JS lint/test tooling
npm test              # node --test over tests/*.test.mjs (browser assets)
npm run lint          # eslint over the shipped theme and flow-runtime JavaScript
npm run format        # eslint --fix
```

`package.json` (named `form-builder-assets`) is private and not published to npm. It exists only so
the JavaScript under `src/main/resources/form-builder/website-static/` is held to the same lint and
test standard as the rest of the ecosystem's client code.

To start a new application rather than copying an existing one:

```bash
cookiecutter github.com/IRS-Public/form-builder-template
```

## Repository layout

| Path | What is in it |
|---|---|
| `build.sbt` | `gov.irs::form-builder`, version `0.1.0-SNAPSHOT`, and the dependency list |
| `src/main/scala/gov/irs/formbuilder/` | The entry point (`FormBuilder`, `FormBuilderApp`), asset extraction, the Thymeleaf engine, fact dictionary loading, locale layering, and build flags |
| `.../parser/` | Flow XML parsed into a tree of `FlowNode` case classes |
| `.../generators/` | `Website`, `AllScreens`, `AuthorMode`, `FlowManifest`, `FormBuilderGraph` |
| `.../authoring/` | The Author Mode HTTP backend |
| `src/main/resources/form-builder/templates/` | The Thymeleaf templates: page shells, node templates (`nodes/`, including `nodes/inputs/`), and shared fragments (`fragments/`) |
| `.../locales/` | Chrome strings shared by every application, in 8 languages |
| `.../schema/` | `FlowConfig.rng` and `FactDictionaryModule.rng`, seed copies only; a generated application keeps and owns its own |
| `.../website-static/theme/` | The CSS extracted into a built site's `vendor/form-builder/` |
| `.../website-static/flow-runtime/` | The ES modules behind the flow's custom elements |
| `src/test/resources/pet-planner/` | A fictional, non-tax fixture application used by the generator specs |
| `tests/` | `node --test` suites for the browser assets |
| `docs/` | Architecture reference, onboarding guide, and internals documentation (see below) |

An application is configuration over this library: `FormBuilderApp` holds everything that varies
between applications (its ID, URL prefix, locales, and any custom node or input types), and
`FormBuilder.run(app, args)` is the entire entry point. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full extension model.

## Documentation

| Document | Covers |
|---|---|
| [docs/ONBOARDING.md](docs/ONBOARDING.md) | Environment setup, dependency versions, build and test commands, and a worked example of a minimal `FormBuilderApp` |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Full repository layout, build flags, and the five extension points an application uses to customize this library |
| [docs/internals/app-entry-and-assets.md](docs/internals/app-entry-and-assets.md) | `FormBuilder.run`, template resolution, locale layering, and browser-asset extraction |
| [docs/internals/flow-parsing-and-generation.md](docs/internals/flow-parsing-and-generation.md) | The parser and generator packages: the node model, conditions, and page splitting |
| [docs/internals/flow-runtime.md](docs/internals/flow-runtime.md) | The browser half: custom elements, runtime configuration, and validation |
| [docs/internals/author-mode.md](docs/internals/author-mode.md) | The authoring server, its endpoints, and how it patches source XML |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to open issues and pull requests, and
[GOVERNANCE.md](GOVERNANCE.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for how the project is
run. Security issues should follow [SECURITY.md](SECURITY.md).

This codebase is dedicated to the public domain under the Creative Commons Zero v1.0 Universal
license (CC0 1.0). See [LICENSE.md](LICENSE.md).

## Legal disclaimer: public repository access

> This repository contains draft and under-development source code. It is made available to the
> public solely for transparency, collaboration, and research purposes.
>
> **No endorsement or warranty.** IRS does not endorse, maintain, or guarantee the accuracy,
> completeness, or functionality of the code in this repository. The IRS assumes no responsibility
> or liability for any use of the code by external parties, including individuals, developers, or
> organizations. This includes, but is not limited to, any tax consequences, computation errors,
> data loss, or other outcomes resulting from the use or modification of this code.
>
> Use of the code in this repository is at your own risk. This repository is not intended for
> production use or public consumption as a finalized product.
>
> Artificial intelligence was used in generating portions of this codebase.

## Authorities

Legal foundations for this work include:

- The Source Code Harmonization And Reuse in Information Technology Act of 2024, Public Law 118-187
- OMB Memorandum M-16-21, "Federal Source Code Policy: Achieving Efficiency, Transparency, and
  Innovation through Reusable and Open Source Software," August 8, 2016
- Federal Acquisition Regulation (FAR) Part 27, Patents, Data, and Copyrights
- Digital Government Strategy: "Digital Government: Building a 21st Century Platform to Better
  Serve the American People," May 23, 2012
- Federal Information Technology Acquisition Reform Act (FITARA), December 2014 (National Defense
  Authorization Act for Fiscal Year 2015, Title VIII, Subtitle D)
- E-Government Act of 2002, Public Law 107-347
- Clinger-Cohen Act of 1996, Public Law 104-106
