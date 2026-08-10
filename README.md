# formative

`gov.irs::formative` — everything that turns **Flow XML + a Fact Dictionary** into a static site,
with no tax product on top.

This is the half of the stack that decides what the site *is*. The other half, [`taxpert`](../taxpert),
is the workspace laid over the running result to make it understandable. An app needs this one to
exist; it needs Taxpert only to be inspectable.

```
gov.irs::factgraph   evaluation engine — JVM jar + Scala.js browser bundle
gov.irs::formative   ← you are here: parser, generators, Thymeleaf engine, node
                       templates, chrome locales, RNG schemas
taxpert              the workspace (nav / audit panel / tool panels) + flow runtime + theme
──────────────────────────────────────────────────────────────────────────────────────────
the app              flow/*.xml, facts/*.xml, locales/*.yaml, its brand CSS, a ~40-line Main.scala
```

## An app is configuration over this library

`FormativeApp` is the whole of what varies between apps, and `Formative.run(app, args)` is the whole
entry point. Here is a complete application:

```scala
package gov.irs.hellotax

import gov.irs.formative.{ Formative, FormativeApp }
import scala.collection.immutable.ListMap

val app: FormativeApp = FormativeApp(
  appId = "hello-tax",              // resource dir under src/main/resources
  basePath = "/app/hello-tax",      // URL prefix
  outSubdir = "app/hello-tax",      // where the site is written under ./out
  locales = ListMap("en" -> "English", "es" -> "Español"),  // first entry is the default
  defaultPort = 3010,
  brand = "Hello Tax",
)

@main def main(args: String*): Unit = Formative.run(app, args)
```

`appId`, the URL segment and the sbt project name are deliberately **independent**. credit-assistant
proves it: it lives in `credit-assistant/`, keeps its resources under `credit-assistant/`, and serves
from `/app/eitc`.

To start a new one, don't copy an existing app — run the cookiecutter:

```bash
cookiecutter ../formative-template
```

## The four seams

An app reaches through the library at exactly four points. If you find yourself adding an app's
name, URL segment or storage prefix to a file in here, that is the bug — it belongs in that app's
`FormativeApp`.

**1. Templates — app-first resolution.** Two `ClassLoaderTemplateResolver`s: `/{appId}/templates/`
is tried first (`setCheckExistence(true)`, so a miss falls through) and `/formative/templates/`
second. An app overrides `nodes/inputs/dollar.html` by dropping a same-named file into its own
resources and inherits the other 29 untouched. The same works for `page.html`, `all-screens.html`
and every `fragments/*`.

**2. Locales — layered.** App YAML wins over this library's YAML, which wins over the generated
`flow_{lang}.yaml`. The library carries the chrome that is identical everywhere — the whole
`components.*` block and `workspace.tools.*`; an app's `en.yaml` carries only its own words.

> **Locale tests must compare the layered result**, not the app's file on its own. `chromeLocaleContent`
> is public for exactly this reason: an app whose English inherits the chrome and whose Spanish
> overrides it is *not* missing 33 keys, and a raw file-to-file comparison will say it is. See either
> app's `YamlValidatorSpec`.

**3. Node types.** `FormativeApp.nodeTypes` maps a tag name to a `FlowNodeParser`, and the lookup
falls through to the built-ins and then to `HTML`, as it always did. TWE's
`fg-withholding-adjustments` is a ~50-line parser that lives in TWE.

**4. Input types.** `FormativeApp.inputTypes` maps an `inputtype` to an `InputParser`. A registration
may also *replace* a built-in: TWE registers `single-checkbox` (new) and `date` (its own
`YearRangeDate`, over the library's).

## Flow, facts and app locales are read from disk

Deliberately — via `os.read`, not the classpath. Author Mode patches XML on disk and re-runs
`regenerate` **in-process**, and `Source.fromResource` would keep serving sbt's stale
`target/…/classes` copy. Only the library's *own* templates and base locales come off the classpath,
because they are jar resources and never change at runtime.

## Building

```bash
sbt test          # the library's own specs
sbt publishLocal  # → ~/.ivy2/local/gov.irs/formative_3/0.1.0-SNAPSHOT/
```

There is no scaladoc artifact: `publishLocal` would run scaladoc, which reads the TASTy of every
dependency, and factgraph's is cross-built for Scala.js — its `@JSExport` annotations fail to
resolve on the JVM classpath. The jar and pom are what a consuming app needs.

After publishing, re-run **both** apps' `make ci`. The second app is what catches a change that
quietly assumed something only the first one does.

## Test fixtures

`src/test/resources/pet-planner/` is a fictional non-tax app, the same device
[`taxpert`](../taxpert)'s `tests/fixtures/host/` uses on the browser half. A generator spec that
needs an app builds one over Pet Planner rather than over credit-assistant, so the library cannot
quietly grow a dependency on the EITC.
