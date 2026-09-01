# Form Builder

Form Builder is a Scala 3 library that turns a flow definition, a fact dictionary, and a
set of locale files into a multi-language questionnaire site. 
Form Builder extends [Fact Graph](https://github.com/IRS-Public/fact-graph), a separate rules
engine library, and does not duplicate its evaluation logic. You describe the pages and questions
in Flow XML, describe the data and business logic behind them in a Fact Dictionary XML, and provide content translations as YAML
locale files. The library parses all of it, renders every page in every declared language, and
writes the result to `./out` as plain HTML.

Form Builder is a generalization of
the Scala and presentation code originally developed for two IRS applications, the Tax Withholding
Estimator (TWE) and the EITC Assistant; both live today as reference applications
in [form-builder-examples](https://github.com/IRS-Public/form-builder-examples). See TWE's [ADR 001](https://github.com/IRS-Public/tax-withholding-estimator/blob/main/docs/adr/001-twe-architecture.md) for a deeper understanding of the architectural choices behind Form Builder.

To understand the difference between Taxpert, Form Builder and the Fact Graph, see
[this doc](https://github.com/IRS-Public/taxpert/blob/main/docs/adr/taxpert-form-builder-fact-graph.md).

To start a new application rather than copying an existing one, see [Form Builder Template](https://github.com/IRS-Public/form-builder-template), which is a cookiecutter scaffold for Form Builder applications.

## Quickstart
[docs/ONBOARDING.md](docs/ONBOARDING.md) covers working on this library itself, including its toolchain, its
build and test commands, its dependencies, and a worked example of a minimal `FormBuilderApp`.

**Setting up and running a whole application is documented in one place for the whole ecosystem**,
the [QUICKSTART.md](https://github.com/IRS-Public/taxpert/blob/main/docs/QUICKSTART.md) in the taxpert
repository. It covers the prerequisites, the Docker path, the native path, what to run after
changing a library so the change reaches everything that consumes it, and the failure modes you are
most likely to hit.

## Where this fits

| Repository | What it is |
|---|---|
| [fact-graph](https://github.com/IRS-Public/fact-graph) | `gov.irs::factgraph`, the rules engine. Cross-compiled to a JVM jar and a Scala.js browser bundle. |
| form-builder | The scaffold: parsers, generators, the Thymeleaf engine, node templates, chrome locales, RELAX NG schema seeds, the theme, and the flow runtime. |
| [taxpert](https://github.com/IRS-Public/taxpert) | The optional workspace UI and its companion services (global nav, audit panel, tool panels). An application runs the same without it, and neither package imports the other. |
| [form-builder-template](https://github.com/IRS-Public/form-builder-template) | A cookiecutter that generates a new Form Builder application, with optional extensions such as the Taxpert workspace. |
| [form-builder-examples](https://github.com/IRS-Public/form-builder-examples) | Reference applications built on this library: Credit Assistant and the Tax Withholding Estimator. |

The dependency runs one way: an application requires this library, and this library requires
Fact Graph. Nothing in this repository imports from an application or from Taxpert.

### Propagating changes to this library

Nothing watches across repository boundaries. Republish, then rebuild whatever consumes it:

```bash
sbt test publishLocal
npm test && npm run lint      # the browser assets shipped in the jar
```

The theme, the flow runtime and the Author Mode editor ship inside the jar and are extracted into
each generated site's `resources/vendor/form-builder/` on every build, so there is no separate copy
step for them. What each consumer needs afterwards is in the taxpert
[QUICKSTART.md](https://github.com/IRS-Public/taxpert/blob/main/docs/QUICKSTART.md#propagating-a-change).

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
