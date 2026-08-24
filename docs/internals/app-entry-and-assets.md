# Internals: application entry, configuration and browser assets

This document covers the handful of files at the root of `gov.irs.formbuilder` that turn one
application's configuration into a generated site: `FormBuilder`, `FormBuilderApp`,
`FormBuilderTemplateEngine`, `Locale`, `FactDictionaryLoader`, `FormBuilderAssets`, `Log`, and
`build/Flags`. An application touches this layer directly, so it is a reasonable place to start
reading.

[ARCHITECTURE.md](../ARCHITECTURE.md) describes the repository layout, the shape of a generated
site, and the five extension points from the outside. [ONBOARDING.md](../ONBOARDING.md) covers
requirements, publishing, and the `FormBuilderApp` field table. This document is the mechanical
counterpart: the call sequence, the lookup rules, and the invariants that are easy to break.

## Where these files sit

| File | Responsibility |
|---|---|
| `FormBuilder.scala` | `run`, `regenerate`, `parseFlow`, `resolvedFlowConfig`, `resolveModule`. The build pipeline and the process lifecycle. |
| `FormBuilderApp.scala` | The case class carrying everything that varies between applications, plus the paths derived from it. |
| `build/Flags.scala` | The string constants for every `--flag` the generator accepts. |
| `FactDictionaryLoader.scala` | `loadFactXml` and `loadFactDictionary`. Merges `facts/*.xml` into one dictionary. |
| `FormBuilderTemplateEngine.scala` | The two Thymeleaf resolvers, the message resolver, and the variables put on every context. |
| `Locale.scala` | Three-layer key lookup, plus generation and sync of the flow locale files. |
| `FormBuilderAssets.scala` | Copies the library's `website-static` tree out of the jar into a generated site. |
| `Log.scala` | Two functions that print to stderr. There is no logging framework here. |
| `exceptions/InvalidFormConfig.scala` | The single exception type the flow parser throws. |

## What `FormBuilder.run(app, args)` does

An application's `main` is one line. Everything below happens in the calling process.

1. **Parse the flags.** Each argument is matched against `--(\w*)` and collected into a
   `Map[String, Boolean]`. An argument that does not match raises `Error` immediately, so a typo
   fails at startup rather than being ignored.
2. **Run `regenerate(app, flags)`,** which is the whole read-side build. It returns `./out`, the
   directory the dev server will serve.
3. **Under `--authorMode`, start the authoring backend.** `authoring.AuthoringServer.start` binds
   `-Dsmol.author.host` (default `localhost`) and `-Dsmol.author.port` (default `3004`). A
   `BindException` is caught and reported as a warning, because an sbt `~run` cycle restarts the
   process while the previous server is still bound.
4. **Return unless `--serve` was passed.** A production build stops here, having written `./out`.
5. **Start `smol` in-process and do not block.** Host is always `localhost`. Port comes from
   `-Dsmol.port`, falling back to `app.defaultPort`. A shutdown hook stops the server, and here too
   a `BindException` is downgraded to a warning.

### Inside `regenerate`

`regenerate` is separate from `run` because Author Mode calls it again in-process after writing
edited XML back to disk. It re-reads every input on each call, so a caller only has to persist its
edits first.

1. `loadFactDictionary(app)` reads and merges the fact XML.
2. `parseFlow(app, dictionary)` resolves `flow/index.xml` and parses it against that dictionary.
3. Under `--singleQuestionPerScreen`, `PageSplitter.split` explodes each page into one question per
   screen. The result replaces the flow for rendering only.
4. `generateFlowLocaleFile` rewrites `locales/flow_{default}.yaml` from the translation map the
   parser accumulated.
5. Under `--formBuilderGraph`, `generators.FormBuilderGraph.buildJson` produces the Form Graph Model
   JSON. It is built from the **unsplit** flow, so the graph does not change shape when an unrelated
   rendering flag is set.
6. `Website.generate` renders every page in every language, and `site.save` writes the tree under
   `./out/<outSubdir>/`. `save` deletes that subdirectory first, so anything stale is removed.

### Flow module resolution

`resolvedFlowConfig` reads `flow/index.xml` and replaces each top-level `<module src="…"/>` with the
children of the file it names. Modules are only recognized at the top level. A module file whose
root element is not `FlowConfig` raises `InvalidFormConfig`.

Two details matter:

- A leading `./` in `src` is stripped, so an author can write a path their editor can follow.
- Each `<page>` coming out of a module is stamped with a `module` attribute holding the module
  filename without its extension, unless the page already declares one. Splicing is the only point
  at which the source file is still known, and Browse All groups pages by it. Deriving the module
  from the route does not work, because routes do not have to repeat the module name and the root
  route has no segment at all.

`resolvedFlowConfig` is public because `FormBuilderGraph` needs the raw source XML per element. The
parsed `FlowNode` case classes have discarded their `Elem` by then, so the generator re-reads rather
than threading a `sourceXml` field through every node type, including ones applications register.

`parseFlow` has a one-argument overload that loads its own dictionary and a two-argument overload
that takes one already loaded. `regenerate` uses the second, because loading the dictionary twice is
the most expensive accidental thing this file could do. Applications typically call the
one-argument version from a spec, so that a mistyped `path=` fails a test rather than a build.

## `FormBuilderApp`

`FormBuilderApp` is the entire Scala surface an application configures. Its fields are listed with
defaults in [ONBOARDING.md](../ONBOARDING.md). What follows is what each one actually controls.

| Field | Controls |
|---|---|
| `appId` | Two things: the directory under `resourceRoot` that flow, facts, locales and static assets are read from, and the classpath prefix `/{appId}/templates/` the first Thymeleaf resolver searches. |
| `basePath` | The value templates read as `${basePath}`. Every generated link and asset href is built from it. No trailing slash. |
| `outSubdir` | The path beneath `./out` the site is written to. Usually `basePath` without its leading slash, kept separate so a deployment can serve from a different prefix than it builds into. |
| `locales` | A `ListMap` from language code to native display name. Order is the language switcher's order, and the first entry is the default language. |
| `defaultPort` | The `smol` port when `-Dsmol.port` is not set. |
| `brand` | The product name in the dev server's startup banner. Nothing else reads it. |
| `storagePrefix` | Namespaces every browser storage key the site writes. Defaults to `appId` through `storageKeyPrefix`. Set it only to keep an existing application's keys stable. |
| `nodeTypes` | Flow XML element name to `FlowNodeParser`, merged over the built-ins, so an application can add an element or replace one. |
| `inputTypes` | `<input type="…">` value to `InputParser`, checked before the built-ins. |
| `resourceRoot` | The source tree the disk reads resolve against. Defaults to `os.pwd / "src" / "main" / "resources"`. The test fixture points it at `src/test/resources`. |

The derived paths (`resourcesDir`, `flowDir`, `factsDir`, `localesDir`, `websiteStaticDir`,
`scenariosDir`) are where every generator looks, and `defaultLocale`, `localeCodes` and
`translatedLocaleCodes` are how the generators decide which language goes at the site root.

`storageKeyPrefix` reaches the browser through a `<meta name="form-builder:storage-prefix">` tag
that `fragments/head.html` renders on every page, whether or not the workspace is mounted. See the
flow runtime configuration section of [ARCHITECTURE.md](../ARCHITECTURE.md).

If you find yourself adding an application's name, URL segment or storage prefix to a file in this
library, the value belongs in that application's `FormBuilderApp` instead.

## Build flags

`build/Flags.scala` holds nothing but string constants. The behavior each one selects lives in
`FormBuilder.run`, `regenerate`, and the generators. [ARCHITECTURE.md](../ARCHITECTURE.md) has the
table of effects. Three things about the flags are worth knowing here.

- Flags are positional arguments to `FormBuilder.run`, so an application passes them through
  `sbt run`. A production build passes none.
- `authorMode`, `aiScenarioGeneration` and `aiFactExplanation` are each also the **build-time
  default** for a matching runtime feature flag the workspace reads, handed to the panel by
  `fragments/audit-panel.html`. The Workspace settings modal can override those at runtime. The
  build flag sets the initial state of the page.
- `aiScenarioGeneration` and `aiFactExplanation` are separate flags because the two features do
  different things and ship on their own timelines. Generation writes a whole Fact Graph from a
  prompt, and explanation only reads facts back.

**A new flag name must not be a prefix of an existing one.** The cookiecutter's
`post_gen_project.py` strips a flag out of a generated Makefile with a bare string replace, so
adding `--scenario` would leave `Mode` stranded in every line that had `--scenarioMode`. None of the
current names collide that way.

## Template resolution

`FormBuilderTemplateEngine` is constructed once per language. It registers two
`ClassLoaderTemplateResolver`s:

| Order | Prefix | Holds |
|---|---|---|
| 1 | `/{appId}/templates/` | The application's own templates and overrides. Usually a handful of files, often none. |
| 2 | `/form-builder/templates/` | The library's 36 templates. |

`setCheckExistence(true)` on both is what makes the fallthrough work. Without it the first resolver
claims every name it is asked for, and an application would have to copy all 36 files to change one.
The resolvers are held in a `java.util.LinkedHashSet` so the declared order survives regardless of
whether Thymeleaf re-sorts by `getOrder()`.

To override a template, drop a file with the same relative name into the application's own
`templates/` tree. `nodes/inputs/dollar.html` overrides the money input and leaves the other 35
templates untouched. An application registering a new node type ships that node's template the same
way, next to the parser it registered in `nodeTypes`.

`process` sets two variables on every context before rendering: `basePath` and the whole `app`
object. That is why a template can write `th:href="|${basePath}/resources/…|"` without its caller
passing anything, and why the language switcher and the step indicator can reach `app.defaultLocale`
and the other derived values.

`FormBuilderMessageResolver` wraps a `Locale` for `th:text="#{key}"` lookups. A missing key logs a
warning and renders as `!!key!!`, which is visible in the page rather than silently empty. When
message parameters are present the value goes through `java.text.MessageFormat`, which strips single
quotes. Write `''` in a locale value that needs a literal apostrophe under formatting.

## Locale layering

`Locale(languageCode, app).get(key)` tries three sources in order and returns `Json.Null` if none
have the key.

| Order | Source | Read from | Holds |
|---|---|---|---|
| 1 | `locales/{lang}.yaml` | Disk, under the application's resources | The application's own words: title, layout, results, nav. |
| 2 | `/form-builder/locales/{lang}.yaml` | Classpath, this jar | The chrome every generated flow shares: `components.*`, `workspace.tools.*`, in eight languages. |
| 3 | `locales/flow_{lang}.yaml` | Disk, under the application's resources | Text lifted out of the flow XML. Generated. |

Application first, so declaring a chrome key in the application's own file wins, the same way an
application overrides a template.

The default language reads `flow_{defaultLocale}.yaml` and every other language reads
`flow_{code}.yaml`. `chromeLocaleContent` is a public top-level function so that an application's
locale tests can reach layer 2.

### What layering means for locale tests

**A locale test must compare the layered result, not the application's file on its own.** An
application whose English inherits the chrome from the library and whose Spanish overrides some of
it has no missing keys, but a raw file-to-file comparison of `en.yaml` against `es.yaml` will report
a long list. Either reference application's `YamlValidatorSpec` is the worked example.

### The generated flow locale files

`generateFlowLocaleFile` rewrites `flow_{default}.yaml` on every build from the translation map the
flow parser accumulated, with a `# DO NOT EDIT` header. Hand edits to that file are lost. Authored
text belongs in the flow XML. The write is skipped when the content is byte-identical, so an edit
that cannot affect flow text leaves the file's mtime and git status alone.

`syncTranslationLocales` re-keys every non-default `flow_{lang}.yaml` against the freshly written
default one. For each translated locale it keeps every existing translation whose key still exists,
adds any key that is missing, seeded with the default-language text and tagged `# TODO: translate`,
and drops keys that no longer exist. `mergeLocaleTree` does the work by walking the default-language
tree and reading the existing file opportunistically, which is what makes the key set and the key
order come from the default language alone. An unparseable translated file is logged and rebuilt
from scratch rather than failing the build.

The `# TODO: translate` marker is carried through serialization as a sentinel string prefixed onto
the value, then rewritten into a standalone comment line above the key. The circe YAML printer has
no way to emit comments directly.

**`syncTranslationLocales` is not part of the normal build.** It rewrites human-maintained files, so
it runs only from an Author Mode save, immediately after `generateFlowLocaleFile`.

## Fact dictionary loading

`loadFactXml` enumerates `facts/*.xml`, **sorted by filename**, reads each from the same on-disk
directory, and concatenates the children of each file's `<Facts>` element into a single
`<FactDictionaryModule>`. `loadFactDictionary` hands that XML to `FactDictionary.fromXml` and returns
both, since the generators need the raw XML as well as the built dictionary.

The sort is load-bearing. A duplicate `<Fact path="…">` across two files is last-wins, and
`File.listFiles` returns entries in an order that is undefined and varies by operating system.
Without the sort, which definition wins would depend on the machine running the build.

## Disk versus classpath

The split is deliberate and it comes up in every file here.

| Read from disk with `os.read` | Read from the classpath |
|---|---|
| `flow/*.xml` | The library's 36 Thymeleaf templates |
| `facts/*.xml` | The library's chrome locales, `/form-builder/locales/{lang}.yaml` |
| The application's `locales/*.yaml`, generated and hand-written alike | The library's browser assets, `/form-builder/website-static` |
| The application's `website-static/`, copied verbatim | |

Author Mode patches flow and fact XML on disk and then calls `regenerate` in-process. During an sbt
`~run` session that write also triggers the file watcher, which rebuilds `target/…/classes`
underneath the running process. A `Source.fromResource` read at that moment returns a stale copy or
fails outright. Reading the source tree, which is always present and always current, sidesteps the
race.

The library's own resources take the other branch because nothing edits them at runtime.

**The consequence to know in advance:** editing a stylesheet under
`src/main/resources/form-builder/website-static/theme/` during a `~run` session hits exactly that
staleness, and the site keeps serving the previous copy. Run `sbt publishLocal` here and restart the
consuming application. There is no live reload for these files.

## Browser assets

Form Builder ships the front end every generated site renders with, as classpath resources under
`/form-builder/website-static`:

| Tree | Contents |
|---|---|
| `theme/styles/` | Design tokens, page layout, and the styling of every element the generators emit. |
| `flow-runtime/js/` | The custom elements a generated questionnaire runs on, and its Fact Graph bootstrap. |

Neither is optional and neither has anything to do with which application it is. They live on the
classpath beside the templates that reference them, so a template and the asset it loads ship in one
versioned artifact.

`FormBuilderAssets.extractInto(resourcesDir)` copies that tree into
`resourcesDir / "vendor" / "form-builder"`. `Website.save` and `AllScreens` both call it, after the
application's own `website-static/` has been copied into place, and it merges into that directory
rather than replacing it.

### How the extraction handles both packagings

`extractTo` resolves the resource root through `classOf[FormBuilderAssetsAnchor].getResource`, then
branches on the URL protocol:

| Protocol | When | Handling |
|---|---|---|
| `file:` | Running inside this repository, where the resources are loose files under `target/…/classes`. This is what `sbt test` exercises. | Walk the directory directly. |
| `jar:` | An application consuming the published artifact. | Open a jar `FileSystem` and walk inside it. |
| anything else | Not expected. | `IllegalStateException`. |

A missing resource root throws immediately rather than writing nothing, because an extraction that
quietly wrote nothing would produce a site whose every page loads a stylesheet that 404s, with no
build step failing.

`FormBuilderAssetsAnchor` is a real declared class of this library rather than an anonymous holder.
A resource lookup anchored on a synthetic class does not necessarily reach this jar's resources.
`Locale`'s `chromeLocaleContent` anchors on `classOf[Locale]` for the same reason: a top-level
`def` compiles into a synthetic `Locale$package` holder.

A jar's `FileSystem` is a JVM-wide singleton keyed on the jar, so opening one that is already open
throws rather than returning it. `withJarFileSystem` catches that, and closes only the filesystem it
opened itself. Author Mode re-runs this whole pipeline in-process, and closing a filesystem another
caller still holds would break that caller.

`copyTree` rebuilds each destination path segment by segment rather than splitting a string. A jar
filesystem's paths cannot be resolved against an `os.Path` directly, and its separator is not
necessarily the host's. The empty-segment filter is required rather than defensive: `Files.walk`
yields the source root itself first, and an empty relative path iterates as a single empty name,
which os-lib rejects as a path segment.

### Two invariants about the destination

- **The URL is hardcoded in the templates.** Thymeleaf cannot read a Scala constant, so templates
  write `${basePath}/resources/vendor/form-builder/…` literally. Changing `vendorPath` means
  grepping the template tree for `vendor/form-builder`.
- **Do not flatten the tree.** The theme's stylesheets reach USWDS icons with relative URLs like
  `../../../../uswds-3.13.0/img/…`. A CSS relative URL resolves against the directory the stylesheet
  is in, so four levels up from `vendor/form-builder/theme/styles/<dir>/` has to land on `vendor/`,
  where the application's own `uswds-3.13.0` directory sits.

`FormBuilderAssetsSpec` pins both. It is the only spec here that exercises a mechanism reading a
resource *tree* rather than a single named resource, and its assertions are aimed at failures that
would otherwise be silent: an extraction that wrote only the top level, one that created empty
files, one that flattened the nesting, or one that wiped sibling directories. It also runs the
extraction twice, because Author Mode does.

## Logging

`Log.info` and `Log.warn` print to **stderr** with an `[INFO]` or `[WARN]` prefix. There is no
logging framework, no levels beyond those two, and no configuration. Build output that must not be
mixed into a redirected stdout goes here.

## Test fixture

`FixtureApp` is the `FormBuilderApp` the library's own specs generate against: `appId` `pet-planner`,
base path `/app/pet-planner`, port 3999, `resourceRoot` pointed at `src/test/resources`. Pet Planner
is a fictional non-tax application, so this library cannot quietly grow a dependency on any real
product's domain. If a spec can only be made to pass by encoding something tax-specific, that behavior probably
belongs in an application.

It declares two locales rather than one, so that the rule about the default language living at the
root and every other language under its own segment stays exercised, and rather than eight because
each locale is a fixture file to maintain. `FixtureApp` also exposes the app as a `given`, which is
what lets a spec call `Website.generate` without threading it through by hand.
