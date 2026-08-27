# ONBOARDING

This document covers working on the Form Builder library itself: the toolchain it needs, its build
and test commands, its dependencies, and the `FormBuilderApp` an application configures it through.

**Setting up and running an application is not documented here.** That lives in one place for the
whole ecosystem, the
[QUICKSTART.md](https://github.com/IRS-Public/taxpert/blob/main/docs/QUICKSTART.md) in the taxpert
repository, which covers the Docker path, the native path, and what to run after changing a library
so the change reaches everything that consumes it. To start a new application rather than copying an
existing one, see
[Form Builder Template](https://github.com/IRS-Public/form-builder-template).

## Requirements

| Tool | Version | Needed for |
|---|---|---|
| JDK | Not pinned by the build. The suite here was last run green on 25. | Everything |
| sbt | 1.11.4, pinned in `project/build.properties` | Everything |
| Scala | 3.7.2, set by `build.sbt` | Everything |
| Node | 18.18 or newer, which is what the pinned eslint 9 requires | Only for linting and testing the shipped browser assets |

This library depends on `gov.irs::factgraph`, which isn't on a public artifact registry, so building
here needs a local publish of it first:

```bash
git clone https://github.com/IRS-Public/fact-graph.git
cd fact-graph && sbt publishLocal     # -> ~/.ivy2/local/gov.irs/factgraph_3/3.1.0-SNAPSHOT
```

## Build and test

```bash
sbt test              # ScalaTest suite
sbt publishLocal      # -> ~/.ivy2/local/gov.irs/form-builder_3/0.1.0-SNAPSHOT/
sbt scalafmtAll       # format the Scala
sbt scalafmtCheckAll  # check formatting, as CI does

npm install           # once, for the JS tooling
npm test              # node --test, covering the browser assets shipped in the jar
npm run lint          # eslint over those assets
npm run format        # eslint --fix
```

Publishing this library is how every consumer in the ecosystem consumes it. Nothing watches across
repository boundaries, so each consumer needs a rebuild afterwards.  The
[QUICKSTART.md](https://github.com/IRS-Public/taxpert/blob/main/docs/QUICKSTART.md) has the commands per
consumer.

## Application configuration

An application is configuration over this library: `FormBuilderApp` holds everything that varies
between applications (its ID, URL prefix, locales, and any custom node or input types), and
`FormBuilder.run(app, args)` is the entry point. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full extension model and every build flag, and
[internals/app-entry-and-assets.md](internals/app-entry-and-assets.md) for what each field
actually controls at runtime.

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
