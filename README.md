# Form Builder

`gov.irs::form-builder` is a library used to create multi-language questionnaire applications with complex business 
logic with minimal dependencies. One
Scala dependency is enough to get a styled, working questionnaire, with no npm step in the
application. Form Builder extends the [Fact Graph]
(https://github.com/IRS-public/fact-graph) and takes the approach that you should  Bring Your Own Facts, Flows, and Locales("BYOFFL"). You describe 
the questions in XML, describe the facts behind them in a Fact Dictionary, translate your content. 
The library parses everything, renders every page in every declared language, and writes the result to `./out` as plain HTML. 
It also ships the browser half of that site inside its own jar. The theme (design tokens, page
layout, and the styling for every element the generators emit) and the flow runtime (the `<fg-set>`,
`<fg-collection>` and `<fg-show>` custom elements, the Fact Graph bootstrap, navigation and
validation) live as classpath resources and are extracted into the generated site as it builds.

Form Builder is a generalization of the Scala and presentation XML
first developed in [IRS Tax Withholding Estimator](https://github.com/IRS-Public/tax-withholding-estimator/) and 
expanded in [IRS EITC Assistant](https://github.com/IRS-Public/eitc-assistant/). 

To generate your own Form Builder application using [cookiecutter](https://github.com/cookiecutter/), see the [Form Builder Template repository](https://github.
com/IRS-Public/form-builder-template). To see examples of Form Builder applications leveraging optional tools like 
[Taxpert](https://github.com/IRS-Public/taxpert/), see [Form Builder Examples](https://github.
com/IRS-Public/form-builder-examples/)

## Form Builder in the Fact Graph Ecosystem

| Component | What it is                                                                                                                                                                                                                                                                                                                                          |
|---|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`fact-graph`](https://github.com/IRS-Public/fact-graph) | `gov.irs::factgraph`, the rules engine. Cross-compiled: a JVM jar this library builds against, and a Scala.js bundle the browser runs.                                                                                                                                                                                                              |
| **`form-builder`** (here) | `gov.irs::form-builder`, presentation generator, including parsers, Thymeleaf engine, node templates, locales, RELAX NG schemas, theme, and flow runtime.                                                                                                                                                                                 |
| [`taxpert`](https://github.com/IRS-Public/taxpert) | The workspace UI (`taxpert` on npm, in that repo's `packages/ui`): global nav, audit panel, tool panels. Optional. An application can ship without it. That repo's `packages/fact-explorer` is a React and Vite SPA that visualizes any Form Builder app's flow and facts as a graph, reading the JSON this library emits under `--formBuilderGraph`. |
| [`form-builder-template`](https://github.com/IRS-Public/form-builder-template) | A cookiecutter that generates a new Form Builder app, with optional extensions like Taxpert.                                                                                                                                                                                                                                                        |
| [`form-builder-examples`](https://github.com/IRS-Public/form-builder-examples) | Reference applications that leverage the three core libraries.                                                                                                                                                                                                                                                                                      |

# Contributing
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


# Authorities

Legal foundations for this work include:
- Source Code Harmonization And Reuse in Information Technology Act" of 2024, Public Law 118 - 187
- OMB Memorandum M-16-21, “Federal Source Code Policy: Achieving Efficiency, Transparency, and Innovation through 
Reusable and Open Source Software,” August 8, 2016
- Federal Acquisition Regulation (FAR) Part 27 – Patents, Data, and Copyrights
- Digital Government Strategy: “Digital Government: Building a 21st Century Platform to Better Serve the American 
People,” May 23, 2012
- Federal Information Technology Acquisition Reform Act (FITARA), December 2014 (National Defense Authorization Act 
for Fiscal Year 2015, Title VIII, Subtitle D)
- E-Government Act of 2002, Public Law 107-347
- Clinger-Cohen Act of 1996, Public Law 104-106
