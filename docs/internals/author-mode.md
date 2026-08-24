# Author Mode

Author Mode is an in-browser editing surface over a running Form Builder application. It reads the
application's Flow XML and Fact Dictionary XML from disk, serves them to the browser as JSON, accepts
structured edits, validates each one with the same validators the build uses, writes the patched file
back, and re-runs the generator in the same process. The result is that an author can change a
constant, reword a question, rewire a gate or build a calculation and see the regenerated site
without restarting anything.

It is a development tool. It patches source files in the repository it is running against, so it
binds to loopback by default and is never started in a production build.

## Where it lives

| File | Responsibility |
|---|---|
| `src/main/scala/gov/irs/formbuilder/authoring/AuthoringServer.scala` | The HTTP backend: endpoints, the model builders, the validators, the preserve-and-patch writers |
| `src/main/scala/gov/irs/formbuilder/authoring/DerivedXml.scala` | A generic `(tag, attrs, text, children)` mirror of a computation subtree, plus its XML and JSON codecs |
| `src/main/scala/gov/irs/formbuilder/authoring/DerivedGrammar.scala` | The editor palette: which computation nodes are insertable and how each one is rendered |
| `src/main/scala/gov/irs/formbuilder/generators/AuthorMode.scala` | Renders the static shell page at `/author` from `templates/author-mode.html` |
| `src/test/scala/gov/irs/formbuilder/authoring/FactGraphMessageSpec.scala` | Covers the one piece of message formatting with a rule worth pinning down |

The browser half is the application's own `website-static/js/author-mode.js`, loaded by
`templates/author-mode.html`. This library ships the shell and the API, not the editor script.

## Turning it on

`--authorMode` does two things. `FormBuilder.run` generates the shell page under `out/<outSubdir>/author/`,
and it calls `AuthoringServer.start`. The two halves listen on different ports.

| Thing | Host and port | Set with |
|---|---|---|
| The generated site, served by `smol` | `localhost`, the application's `defaultPort` | `-Dsmol.port` |
| The authoring API | `localhost:3004` | `-Dsmol.author.host`, `-Dsmol.author.port` |

If port 3004 is already bound, `FormBuilder.run` catches the `BindException`, prints a warning and
carries on with the site running and no API behind it.

`AuthoringServer` is a per-process singleton. `start` stores the `FormBuilderApp` it was given in a
field, and every path in the file resolves against it (`app.factsDir`, `app.flowDir`). Calling any
other entry point first throws `IllegalStateException`. One process serves exactly one application,
which is also why it can own a fixed port.

### CORS

Two origins are allowed, and the request's own `Origin` is reflected back when it matches one of them.

| Origin | Why |
|---|---|
| `http://localhost:<app port>` | The application's own dev server, resolved the same way `FormBuilder.run` resolves it (`-Dsmol.port`, else `app.defaultPort`) |
| `http://localhost:5180` | Fact Explorer's dev server, which proxies the editor and whose `location.origin` the browser reports even though the HTML came from the application |

The application origin is derived rather than written down, because a hardcoded port would work for
one application and silently break every other one.

## Endpoints

Everything is under `/author`. All responses are `application/json; charset=utf-8`.

| Endpoint | Method | Input | Returns |
|---|---|---|---|
| `/author/health` | GET | none | `{ "status": "ok" }` |
| `/author/model` | GET | none | `screens`, `facts`, `writablePaths`, `booleanPaths`, `numericPaths`, `factFiles`, `flowModules` |
| `/author/lint` | GET | none | `warnings[]`, each `{ message, route }` |
| `/author/derived` | GET | `?path=` | `tree` (or null), `palette`, `allPaths`, `booleanPaths`, `numericPaths` |
| `/author/fact-usage` | GET | `?path=` | `exists`, `factDependents`, `flowReferences`, `canDelete` |
| `/author/validate` | POST | an edit envelope | `{ ok, errors[] }`, nothing written |
| `/author/save` | POST | an edit envelope | the same envelope, and on `ok` the file is written and the site regenerated |
| `/author/create-fact` | POST | `path`, `file`, `kind`, kind-specific fields, `save` | `{ ok, errors[] }` |
| `/author/create-screen` | POST | `module`, `route`, `title`, optional `firstQuestion`, `save` | `{ ok, errors[] }` |
| `/author/delete-fact` | POST | `path`, `save` | `{ ok, errors[] }` |

Two things about the response contract are easy to trip over.

A validation failure is a `200` carrying `{"ok": false, "errors": [...]}`, not an HTTP error status.
Only an unhandled exception produces a `500`, and even that carries the same envelope shape so the
editor has one path for rendering problems. Each error is `{ field, message }`, where `field` names
the form control to attach the message to.

`validate` and `save` share one handler and differ by a single boolean. `create-fact`, `create-screen`
and `delete-fact` fold the same distinction into a `save` flag in the request body. So the editor can
run any operation in dry-run mode, and a dry run does exactly the work a real save does minus the
`os.write` and the regenerate.

## The model the editor gets

`/author/model` is built fresh on every request by re-reading the XML from disk. There is no cache to
invalidate.

**Facts.** Every `<Fact>` across `facts/*.xml`, read in sorted filename order with last-wins on a
duplicate path, matching the runtime merge. Each fact is classified into one of three kinds:

| Kind | Shape on disk | What the editor offers |
|---|---|---|
| `writable` | has a `<Writable>` | placeholder and Min/Max limit editing |
| `constant` | a `<Derived>` whose only child is a `<Dollar>` or `<Rational>` | direct value editing |
| `derived` | anything else | the computation-tree editor |

Each entry also carries its `type` (the fact-graph type node, such as `DollarNode`), its
`description`, and the file it came from, since a save has to go back to the same file.

**Screens.** One entry per `<page>`, in `flow/index.xml` module order. Every `<fg-set>` on the page is
its own editable block keyed by its fact path, and every `<fg-alert>` is keyed by its `alert-key`. A
page can carry several questions, one per collection item or income source, so nothing here is scoped
to "the first question on the page".

**The type map.** A `path -> typeNode` map is built once from the on-disk dictionary and threaded into
both model builders, so the whole tree is not re-parsed per screen. It is what lets the editor offer
only the input types valid for a bound fact, and only the fact paths of the right type for a
condition or an arithmetic operand.

| Fact-graph node | Valid `<input type>` |
|---|---|
| `StringNode` | `text` |
| `IntNode` | `int` |
| `BooleanNode` | `boolean` |
| `DollarNode` | `dollar` |
| `DayNode` | `date` |
| `EnumNode` | `enum` or `select` |
| `MultiEnumNode` | `multi-enum` |

A `<select>` inside an `fg-set` is the enum-backed select input. It is reported as input type
`select`, and its type cannot be changed through the editor.

## Why flow and facts are read from disk

Every read in this file is `os.read` against the source tree, never `Source.fromResource`. That is a
requirement rather than a preference.

Author Mode writes to those same XML files and then calls `FormBuilder.regenerate` in the same
process. Under `sbt ~run`, the write also triggers sbt's file watcher, which starts recompiling and
rewriting `target/…/classes` underneath the running process. The classpath copy at that instant is
either the pre-edit version or transiently missing, so any validation or model build that went
through the classpath would be judging the wrong bytes. Reading from disk means the model, the
validators and the regenerated site all see the state that was just saved.

Only the library's own templates, chrome locales and browser assets come off the classpath, because
nothing edits those at runtime.

## The save loop

```
POST /author/save
  parse the target and payload
  compute a candidate  (patch the file content in memory, nothing written)
  validate the candidate  (the build's own validators, run against the in-memory content)
  errors?  ->  return them, disk untouched
  os.write.over(candidate.file, candidate.content)
  FormBuilder.regenerate(app, flags)
  if flow_<default>.yaml changed, syncTranslationLocales(app)
```

A `Candidate` is a file path plus its full proposed content. Every validator takes that content as a
string, so the entire validation stack runs before anything reaches disk, and a dry run through
`/author/validate` is the same code path with the last three lines skipped.

The locale re-sync at the end is conditional. `flow_<default>.yaml` is generated from the flow XML,
so a fact edit can never change it and a structural flow edit changes attributes rather than
translatable text. The content of the generated file is compared before and after the regenerate, and
the seven translated `flow_<lang>.yaml` files are only re-keyed when it actually moved. This matters
because `syncTranslationLocales` rewrites human-maintained files.

## Edit kinds

`/author/validate` and `/author/save` take one envelope:

```json
{
  "target": { "kind": "...", "path": "...", "file": "...", "route": "...", "field": "...", "alertId": "..." },
  "edit":   { "value": "...", "polarity": "...", "tree": { } }
}
```

`kind` selects both the writer and the validator.

| `kind` | Targeted by | What it rewrites | Validated with |
|---|---|---|---|
| `constant` | `path`, `file` | the text of the `<Dollar>` or `<Rational>` inside `<Derived>` | value format, then RelaxNG and `FactDictionary.fromXml` |
| `factDescription` | `path`, `file` | `<Description>`, inserted after the `<Fact>` opening tag if absent | RelaxNG and `FactDictionary.fromXml` |
| `factConfig` | `path`, `file`, `field` = `placeholder`, `limitMin`, `limitMax` | `<Placeholder>` (sibling of `<Writable>`) or `<Limit type="Min\|Max">` (inside `<Writable>`) | RelaxNG and `FactDictionary.fromXml` |
| `derived` | `path`, `file`, `edit.tree` | the single computation-node child of `<Derived>` | RelaxNG and `FactDictionary.fromXml` |
| `screenText` | `route`, `field` = `question`, `hint`, `alert`, plus `path` or `alertId` | the inner markup of `<question>`, `<hint>` or an alert `<heading>` | full flow re-parse, plus the modal-link check |
| `screenAttr` | `route`, `path`, `field` = `inputType`, `path`, `gating` | attributes on `<fg-set>` or its `<input type>` | full flow re-parse, plus the writable-binding check |
| `alertAttr` | `route`, `alertId`, `field` = `alertType`, `condition`, `operator`, `knockout` | attributes on `<fg-alert>` | full flow re-parse, plus the alert config checks |

`polarity` rides along only on a `gating` edit. It is `if-true`, `if-false` or `none`. The writer
removes both attributes first and then sets at most one, so the mutual exclusion the flow parser
enforces cannot be violated halfway through a patch.

An empty `value` on an optional attribute removes the attribute rather than setting it to the empty
string. That is how the editor clears a condition, an operator or a knockout flag.

## The validation stack

Author Mode reuses the build's gates rather than reimplementing them. Three run against candidate
content.

**RelaxNG**, via `xmllint --noout --relaxng`, against the *application's* `facts/FactDictionaryModule.rng`.
An application that registers a custom node type widens its own grammar, so validating against the
library's seed copy would be wrong. Only fact files are schema-checked.

**`FactDictionary.fromXml`**, which merges every fact file (with the candidate substituted for the one
being edited) and throws on a bad dependency, a type mismatch or a cycle. The delete path can
substitute several files at once, since a path split across tax-year constant files is defined in
more than one.

**The flow parser**, `Flow.fromXmlConfig` over a resolved `<FlowConfig>` with the edited module
substituted. This is what enforces fact existence, input and fact type agreement, boolean gating and
`if-true`/`if-false` mutual exclusion.

Flow modules are deliberately **not** RelaxNG-validated here. An individual module is a fragment that
does not independently satisfy `FlowConfig.rng`, several real modules fail it, and the build does not
RNG-validate flow either. The flow parser is the gate, and re-parsing the whole flow also rejects
malformed XML.

Four checks exist only in Author Mode, because the parser does not perform them:

| Check | Rule |
|---|---|
| Writable binding | a rebound `fg-set path` must resolve to a fact with a `<Writable>`, since a question cannot write a computed value |
| Alert pairing | a `knockout="true"` alert must have `alert-type="error"` |
| Alert condition | a condition needs an operator, must name an existing fact, and that fact must be Boolean |
| Modal links | every `modal-link for` on the edited screen must resolve to a `modal-dialog id` on that same screen |

### Error messages

`FactDictionary` validation appends the offending node's entire `CompNodeConfig(…)` toString after
its human sentence. That dump is one unbroken token, and it floods the inline error box during the
ordinary mid-edit states, such as an emptied slot while an operand is being swapped.
`factGraphMessage` keeps only the sentence before it. `FactGraphMessageSpec` covers the three cases:
a message with a dump, a message without one, and an exception with no message at all.

`xmllint` stderr is cleaned before it reaches the author. The temp file path is stripped, the
`Relax-NG validity error:` prefix is dropped, the summary "fails to validate" line is filtered out,
and at most two lines and 400 characters survive.

## The `<Derived>` grammar

The calculation editor is built on one observation about fact-graph. Its XML to config layer,
`CompNodeConfig.fromXml`, is generic: the type name is the element label, element children recurse,
and attributes plus element text become options. There is no per-node parse logic at the XML
boundary, since typing happens later in each node's `fromDerivedConfig`. So a single generic model is
enough for all of them, and there is no need for per-node serializers.

`DerivedXml.DerivedNode` is that model.

| Field | Meaning |
|---|---|
| `tag` | the element name, which is also the CompNode type name |
| `attrs` | element attributes, sorted by name so output is deterministic |
| `text` | leaf text, captured only when the node has no element children |
| `children` | ordered child computation nodes |

`parse` mirrors `CompNodeConfig.fromXml`: comments and whitespace-only text are dropped, and the
leaf-versus-container split is the same one fact-graph relies on. `render` builds a `scala.xml.Elem`,
which escapes `&`, `<` and `>` in both attributes and text on its own. `toJson` and `fromJson` are
the editor's wire format, and `fromJson` throws `IllegalArgumentException` on a node with no `tag`, so
a malformed payload surfaces as a validation error rather than a silently empty tree.

Round-tripping through this model is structure-preserving but not type-checked. Correctness comes
from re-running RelaxNG and `FactDictionary.fromXml` over the spliced result, which is exactly what
the build does.

### The palette

`DerivedGrammar` is UX metadata. It tells the editor which nodes are insertable, how to render each
one, and what child tags a structural node expects. It never gates a save, so a palette that is
incomplete or slightly too permissive can only affect editor ergonomics. It cannot put invalid XML on
disk.

Five render categories:

| Category | Editor renders it as |
|---|---|
| `value` | a text-bearing leaf, with `valueKind` picking the input control |
| `empty` | no text and no children |
| `dependency` | a fact-path dropdown |
| `container` | ordered children, with `childTags` suggesting what to insert |
| `slot` | a named wrapper that appears only inside a specific parent, rendered as a labeled container and never offered in the top-level palette |

**Values and dependencies**

| Tag | Category | Notes |
|---|---|---|
| `Dependency` | dependency | `path` attribute, picked from a fact-path dropdown |
| `Dollar`, `Int`, `Rational`, `Day`, `Days`, `String` | value | `valueKind` names the input control |
| `Enum` | value | also carries an `optionsPath` attribute naming the fact that holds the option set |
| `True`, `False` | empty | |

**Arithmetic**

| Tag | Child slots |
|---|---|
| `Add`, `Multiply` | a list of operands |
| `Subtract` | `Minuend`, `Subtrahends` |
| `Divide` | `Dividend`, `Divisors` |
| `StepwiseMultiply` | `Multiplicand`, `Rate` |
| `LesserOf`, `GreaterOf`, `Minimum`, `Maximum` | a list of operands |
| `Round`, `RoundToInt`, `TruncateCents`, `Ceiling`, `Floor` | one operand |
| `CollectionSum`, `CollectionSize`, `Count` | one operand |
| `Modulo` | operands |

**Logic and comparison**

| Tag | Child slots |
|---|---|
| `All`, `Any`, `Not` | a list of conditions |
| `Equal`, `NotEqual`, `GreaterThan`, `GreaterThanOrEqual`, `LessThan`, `LessThanOrEqual` | `Left`, `Right` |
| `Switch` | `Case`, each holding `When` and `Then` |
| `IsComplete` | one operand |

**Slot wrappers**, offered only inside their parent: `Minuend`, `Subtrahends`, `Dividend`, `Divisors`,
`Multiplicand`, `Rate`, `Left`, `Right`, `Case`, `When`, `Then`, `Condition`, `Default`.

The palette covers roughly the node types these dictionaries actually use, not all of fact-graph's
compnodes. Adding one is a single `NodeSpec` entry, and nothing downstream needs to change, because
`DerivedXml` is generic and the real validation is the schema plus the dictionary build.

## How edits are written back

Nothing is ever re-serialized from a parsed model. Every writer locates the smallest enclosing block
with a regex, patches inside it, and splices the result back into the original string, so the rest of
the file stays byte-for-byte identical and the diff is the edit.

The block locators are `<Fact path="…">`, `<page route="…">`, `<fg-set path="…">` and
`<fg-alert alert-key="…">`. Each is anchored on an attribute value that is unique within its scope,
which is why a page carrying five questions can have exactly one of them edited.

`xmllint --format --encode UTF-8` runs over the patched file afterwards, matching `make format` in a
generated application. The `--encode UTF-8` is load-bearing. Without it, `xmllint` escapes non-ASCII
characters to numeric entities, and a typographic apostrophe in alert copy would churn across the
whole file on every save.

Three details in the writers are worth knowing:

- **Bare ampersands are escaped, markup is not.** Question, hint and alert-heading text is mixed
  content that may legitimately contain `<fg-show/>`. Only an `&` that is not already opening a
  numeric or named entity is turned into `&amp;`, so an author can type "AT&T" without breaking the
  file. Malformed markup is caught downstream by `xmllint` and the flow parser.
- **A missing element is inserted at a schema-valid position.** A `<Description>` goes right after the
  `<Fact>` opening tag, a `<Placeholder>` right after `</Writable>` because it is a Fact-level
  sibling, and a `<Limit>` just before `</Writable>` because limits live inside it. `xmllint --format`
  fixes the indentation.
- **Creates and deletes are surgical too.** `create-fact` and `create-screen` format the new fragment
  on its own, indent it one level deeper than the closing `</Facts>` or `</FlowConfig>`, and insert it
  immediately before that marker. `delete-fact` matches the `<Fact>` block plus the leading newline
  and indentation that positioned it, so no blank line is left behind and no other line moves.

## Create and delete

`create-fact` builds one of three shapes. A `writable` fact takes a scalar type from
`Dollar`, `Int`, `Boolean`, `String`, `Day`. A `constant` takes a `Dollar` or `Rational` value, checked
for format before anything else. A `derived` fact takes a whole computation tree in `tree`. In every
case the path must start with `/`, contain no whitespace, and not already exist, and the target file
must exist under `facts/`.

`create-screen` writes a `<page>` shell into a chosen flow module, optionally with one bound first
question. The route must start with `/` and must not already be used by any page in any module. The
first-question sub-form offers only the input types that need no extra options wiring: `boolean`,
`dollar`, `int`, `text`, `date`. A `boolean` gets a Yes/No option pair written for it.

`delete-fact` is hard-blocked by anything that still references the fact. `/author/fact-usage` returns
the same analysis so the editor can show the impact before the author commits to it.

| Reference kind | Where it is looked for |
|---|---|
| Fact dependents | `<Dependency path>`, `<Find path>`, `<Filter path>`, `<CollectionItem collection>`, `<Enum optionsPath>`, `<MultiEnum optionsPath>` |
| Flow references | `fg-set path`, `fg-set if-true`, `fg-set if-false`, `fg-alert condition`, `fg-collection path` |

`canDelete` is true only when both lists are empty. The delete itself removes the fact from every file
that defines it, since tax-year constants split one path across several, and then validates the merged
dictionary once.

## The lint pass

`/author/lint` is analysis, never a block. It reports three patterns that are valid XML and a valid
fact graph but almost certainly an authoring mistake.

| Warning | Condition |
|---|---|
| Question binds to a computed fact | the `fg-set path` exists but has no `<Writable>`, so nobody can answer it |
| Gate can never flip | an `if-true` or `if-false` names a writable fact that no question anywhere sets |
| Knockout can never fire | a knockout alert's `condition` is a writable fact that no question anywhere sets |

The whole builder is wrapped so that a parse failure anywhere returns an empty warning list rather
than breaking the panel.

## Failure modes

| Symptom | Cause |
|---|---|
| `IllegalStateException: AuthoringServer.start has not been called` | something reached the API before `start` stored the application |
| "Author Mode API already running on port 3004" at startup | port 3004 is bound. The site runs, the editor loads, and every request to the API fails |
| CORS failure in the browser | the page is served from an origin that is neither the application's dev port nor `http://localhost:5180` |
| "Could not locate fact `/x` in the source file" | the block regex did not match. The editor's model and the file on disk have diverged, usually because the file was edited by hand since the model was fetched |
| A save is rejected with a schema error | `xmllint` must be on `PATH`. A missing binary produces a nonzero exit and is reported as a validation failure |
| Non-ASCII characters churn across a whole file | something formatted the XML without `--encode UTF-8` |
| A `<select>`-backed question refuses an input type change | correct. Only `<input type>` is patchable |

Two boundaries are worth stating outright.

**Author Mode does not touch git.** It writes files and regenerates the site. Committing, reviewing
and reverting are done from the command line as with any other source edit.

**The model is read fresh per request, and there is no locking.** Two editors pointed at the same
process, or an editor plus a hand edit in a text editor, will produce last-write-wins on a whole
file. The block regexes fail loudly rather than corrupting the file when the located block has moved,
which is the safe half of that behavior, but there is no concurrency control beyond that.
