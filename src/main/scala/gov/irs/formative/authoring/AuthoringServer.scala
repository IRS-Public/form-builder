package gov.irs.formative.authoring

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import gov.irs.factgraph.FactDictionary
import gov.irs.formative.{ generatedFlowContentPath, syncTranslationLocales, Formative, FormativeApp, Log }
import gov.irs.formative.parser.Flow
import io.circe.*
import io.circe.syntax.*
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import scala.collection.mutable
import scala.util.{ Failure, Success, Try }
import scala.util.matching.Regex
import scala.xml.{ Elem, Node, NodeBuffer, Null, Text, TopScope, UnprefixedAttribute }

/** Embedded HTTP backend for Author Mode It serves the structured-form editor (which is hosted by `smol` on 3003) a
  * JSON model of the current on-disk Flow/FactDictionary, validates proposed value/text edits against the exact same
  * validators the build uses, saves them with a byte-for-byte preserve-and-patch writer, regenerates the site
  * in-process, and re-stubs the non-English locales. Saved changes are committed from the CLI, not through this API.
  *
  * Everything here is deliberately confined to the MVP surface: pure value/text patches (constant
  * `<Dollar>`/`<Rational>` values, a fact `<Description>`, and on-screen `<question>`/`<hint>`/`<fg-alert>` heading
  * text). No XML is ever re-serialized from a parsed model.
  */
object AuthoringServer {

  // ─── On-disk resource locations (read directly, not via the classpath, so the ──────────────
  // model + validation always reflect the very latest saved state) ────────────────────────────
  // The application being authored. Set once by `start`, because this server is a per-process
  // singleton that serves exactly one app for its whole life — the same reason it can own a port.
  private var authoredApp: FormativeApp = null
  private def app: FormativeApp =
    if (authoredApp != null) authoredApp
    else throw new IllegalStateException("AuthoringServer.start has not been called")

  private def resourcesDir = app.resourcesDir
  private def factsDir = app.factsDir
  private def flowDir = app.flowDir
  private def factsRng = factsDir / "FactDictionaryModule.rng"

  final private case class FieldError(field: String, message: String)
  final private case class EditTarget(
      kind: String,
      path: Option[String],
      file: Option[String],
      route: Option[String],
      field: Option[String],
      alertId: Option[String],
  )

  /** An in-memory patched candidate for the target resource file. Nothing is written until save. */
  final private case class Candidate(
      file: os.Path,
      content: String,
      sourceName: String,
      constantType: Option[String],
  )

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  Server lifecycle
  // ──────────────────────────────────────────────────────────────────────────────────────────

  /** Start the authoring server (non-blocking). `host` is normally "localhost" (loopback-only, since this API patches
    * source XML on disk); the docker-compose dev overlay passes "0.0.0.0" for the containerized watcher, since a
    * container's loopback interface isn't reachable through Docker's port-publishing NAT — see the call site in
    * `main.scala` for the full rationale.
    */
  def start(host: String, port: Int, flags: Map[String, Boolean], app: FormativeApp): HttpServer = {
    authoredApp = app
    val server = HttpServer.create(new InetSocketAddress(host, port), 0)

    server.createContext("/author/health", jsonHandler(_ => (200, Json.obj("status" -> "ok".asJson).noSpaces)))
    server.createContext("/author/model", jsonHandler(_ => (200, buildModelJson().noSpaces)))
    server.createContext("/author/lint", jsonHandler(_ => (200, buildLintJson().noSpaces)))
    // T18 — load a fact's <Derived> computation tree (+ the editor palette) for the calculation editor.
    server.createContext(
      "/author/derived",
      jsonHandler(ex => (200, buildDerivedJson(queryParam(ex, "path").getOrElse("")).noSpaces)),
    )
    // T19 — everything that references a fact, for the delete-impact preview.
    server.createContext(
      "/author/fact-usage",
      jsonHandler(ex => (200, buildFactUsageJson(queryParam(ex, "path").getOrElse("")).noSpaces)),
    )
    server.createContext(
      "/author/validate",
      jsonHandler(ex => (200, handleEdit(readBody(ex), save = false, flags).noSpaces)),
    )
    server.createContext(
      "/author/save",
      jsonHandler(ex => (200, handleEdit(readBody(ex), save = true, flags).noSpaces)),
    )
    // T17 — create a net-new fact / screen.
    server.createContext(
      "/author/create-fact",
      jsonHandler(ex => (200, handleCreateFact(readBody(ex), flags).noSpaces)),
    )
    server.createContext(
      "/author/create-screen",
      jsonHandler(ex => (200, handleCreateScreen(readBody(ex), flags).noSpaces)),
    )
    // T19 — delete a fact (hard-blocked if anything references it).
    server.createContext(
      "/author/delete-fact",
      jsonHandler(ex => (200, handleDeleteFact(readBody(ex), flags).noSpaces)),
    )
    server.setExecutor(null)
    server.start()

    val cyan = "\u001b[36m"
    val green = "\u001b[32m"
    val bold = "\u001b[1m"
    val reset = "\u001b[0m"
    println(
      s"${green}${bold}✓${reset} ${bold}Author Mode API${reset} ${cyan}ready${reset} on ${cyan}http://localhost:$port/author${reset}",
    )
    sys.addShutdownHook(server.stop(0))
    server
  }

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  HTTP plumbing (CORS, JSON, error envelope)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  private def jsonHandler(fn: HttpExchange => (Int, String)): HttpHandler =
    (ex: HttpExchange) => {
      addCors(ex)
      if (ex.getRequestMethod == "OPTIONS") respond(ex, 204, "")
      else {
        val (status, body) =
          try fn(ex)
          catch {
            case e: Throwable =>
              Log.info(s"Author endpoint error: $e")
              (500, serverErrorJson(e).noSpaces)
          }
        respond(ex, status, body)
      }
    }

  // The editor is loaded both directly from credit-assistant (:3003) and, via formative-studio's
  // Vite dev proxy for the scenario overlay (see formative-studio/vite.config.js), from :5180 — the
  // browser's location.origin is :5180 in that case even though the HTML came from CA, so a single
  // hardcoded Allow-Origin can't cover both. Reflect the request's Origin if it's one of these known
  // local dev origins; otherwise fall back to :3003 (still a same-origin no-op for direct CA usage).
  private val AllowedOrigins = Set("http://localhost:3003", "http://localhost:5180")

  private def addCors(ex: HttpExchange): Unit = {
    val h = ex.getResponseHeaders
    val requestOrigin = Option(ex.getRequestHeaders.getFirst("Origin"))
    val allowOrigin = requestOrigin.filter(AllowedOrigins.contains).getOrElse("http://localhost:3003")
    h.set("Access-Control-Allow-Origin", allowOrigin)
    h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    h.set("Access-Control-Allow-Headers", "Content-Type")
  }

  private def respond(ex: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
    if (bytes.nonEmpty) {
      val out = ex.getResponseBody
      out.write(bytes)
      out.close()
    }
    ex.close()
  }

  private def readBody(ex: HttpExchange): String =
    new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  /** A single query-string parameter (URL-decoded), e.g. `?path=/foo` → Some("/foo"). */
  private def queryParam(ex: HttpExchange, name: String): Option[String] =
    Option(ex.getRequestURI.getQuery).toList
      .flatMap(_.split("&"))
      .map(_.split("=", 2))
      .collectFirst { case Array(k, v) if k == name => java.net.URLDecoder.decode(v, StandardCharsets.UTF_8) }

  private def serverErrorJson(e: Throwable): Json =
    Json.obj(
      "ok" -> Json.False,
      "errors" -> Json.arr(Json.obj("field" -> "".asJson, "message" -> s"Server error: ${rootMsg(e)}".asJson)),
    )

  private def errorsJson(errors: List[FieldError]): Json =
    Json.obj(
      "ok" -> Json.fromBoolean(errors.isEmpty),
      "errors" -> Json.fromValues(errors.map(e => Json.obj("field" -> e.field.asJson, "message" -> e.message.asJson))),
    )

  private def rootMsg(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** The author-facing half of a fact-graph config error. `FactDictionary` validation appends the offending node's full
    * `CompNodeConfig(…)` toString after the human sentence — e.g. "&lt;Subtract&gt; must have at least one
    * &lt;Subtrahends&gt;: CompNodeConfig(Subtract,List(CompNodeConfig(Minuend,…". That dump is meaningless to an author
    * and, being one giant unbroken token, floods the inline error box during the normal mid-edit states (removing a
    * `<Subtrahends>` to swap it, an empty operator, …). Keep only the sentence before the dump.
    */
  private[authoring] def factGraphMessage(e: Throwable): String = {
    val msg = rootMsg(e)
    msg.indexOf("CompNodeConfig(") match {
      case i if i > 0 => msg.substring(0, i).replaceAll("[:\\s]+$", "")
      case _          => msg
    }
  }

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  GET /author/model
  // ──────────────────────────────────────────────────────────────────────────────────────────

  private def buildModelJson(): Json = {
    // The `path -> typeNode` map (e.g. "/foo" -> "DollarNode") lets the flow form show only the input types /
    // operators compatible with a bound or gating fact. Built once from the on-disk dictionary and threaded into both
    // model builders so nothing re-parses the whole tree per screen.
    val factTypes: Map[String, String] = Try {
      val dict = buildFactDictionary(Map.empty)
      dict
        .getPaths()
        .flatMap { p =>
          val s = p.toString
          Option(dict.getDefinition(s)).map(d => s -> d.typeNode)
        }
        .toMap
    }.getOrElse(Map.empty)

    val (facts, writablePaths) = buildFactsModel(factTypes)
    val screens = buildScreensModel(factTypes)

    val booleanPaths = factTypes.collect { case (p, "BooleanNode") => p }.toList.sorted
    val numericPaths = factTypes.collect { case (p, t) if NumericTypeNodes(t) => p }.toList.sorted

    // Fact files an author can add a new fact to, and flow modules to add a new screen to (T17).
    val factFileNames = sortedFactFiles().map(_.last).toList
    val flowModules = orderedModuleFiles().map(_._1)

    Json.obj(
      "screens" -> Json.fromValues(screens),
      "facts" -> Json.fromValues(facts),
      "writablePaths" -> writablePaths.toList.sorted.asJson,
      "booleanPaths" -> booleanPaths.asJson,
      "numericPaths" -> numericPaths.asJson,
      "factFiles" -> factFileNames.asJson,
      "flowModules" -> flowModules.asJson,
    )
  }

  /** Fact-graph type nodes that count as numeric — the operand types the numeric condition operators
    * (`isZero`/`isGreaterThanZero`) accept.
    */
  private val NumericTypeNodes = Set("DollarNode", "IntNode", "RationalNode")

  /** The input `type` string valid for a bound fact of the given type node (mirrors the FgSet.fromXml type check).
    * EnumNode accepts either `enum` or `select`; every other node maps to exactly one input type.
    */
  private def inputTypesForNode(typeNode: String): List[String] = typeNode match {
    case "StringNode"    => List("text")
    case "IntNode"       => List("int")
    case "BooleanNode"   => List("boolean")
    case "DollarNode"    => List("dollar")
    case "DayNode"       => List("date")
    case "EnumNode"      => List("enum", "select")
    case "MultiEnumNode" => List("multi-enum")
    case _               => Nil
  }

  /** Parse every fact XML file in `facts/` (alphabetical, last-wins on duplicate paths, matching the runtime merge)
    * into the editable fact model + the set of writable paths.
    */
  private def buildFactsModel(factTypes: Map[String, String]): (List[Json], mutable.LinkedHashSet[String]) = {
    val entries = mutable.LinkedHashMap[String, Json]()
    val writable = mutable.LinkedHashSet[String]()

    for (file <- sortedFactFiles()) {
      val fileName = file.last
      val root = xml.XML.loadString(os.read(file))
      for (factNode <- root \\ "Fact") {
        val path = factNode \@ "path"
        if (path.nonEmpty) {
          val description = (factNode \ "Description").headOption.map(_.text.trim).getOrElse("")
          val hasWritable = (factNode \ "Writable").nonEmpty
          val derived = factNode \ "Derived"

          val (kind, constantValue, constantType) =
            if (hasWritable) ("writable", None, None)
            else if (derived.nonEmpty) {
              val valueElems = derived.head.child.collect { case e: Elem => e }
              valueElems.headOption match {
                case Some(e) if valueElems.length == 1 && (e.label == "Dollar" || e.label == "Rational") =>
                  ("constant", Some(e.text.trim), Some(e.label))
                case _ => ("derived", None, None)
              }
            } else ("derived", None, None)

          if (hasWritable) writable += path

          entries.put(
            path,
            Json.obj(
              "path" -> path.asJson,
              "description" -> description.asJson,
              "kind" -> kind.asJson,
              "constantValue" -> constantValue.map(Json.fromString).getOrElse(Json.Null),
              "constantType" -> constantType.map(Json.fromString).getOrElse(Json.Null),
              // Writable-only config (T15). `placeholder` is the fact's default scalar (or null); `limits` are its
              // Min/Max validation bounds. Both are surfaced only for writable facts, since a Derived value can't carry
              // a placeholder/limit an author would set.
              "type" -> factTypes.getOrElse(path, "").asJson,
              "placeholder" -> scalarChildJson(factNode \ "Placeholder"),
              "limits" -> Json.fromValues(limitsJson(factNode)),
              "file" -> fileName.asJson,
            ),
          )
        }
      }
    }
    (entries.values.toList, writable)
  }

  /** The single scalar child of a `<Placeholder>`/`<Limit>` wrapper as `{ valueType, value }` (e.g.
    * `<Placeholder><Dollar>0</Dollar></Placeholder>` → `{valueType:"Dollar", value:"0"}`), or Json.Null if absent.
    */
  private def scalarChildJson(wrapper: xml.NodeSeq): Json =
    wrapper.headOption.flatMap(_.child.collect { case e: Elem => e }.headOption) match {
      case Some(e) => Json.obj("valueType" -> e.label.asJson, "value" -> e.text.trim.asJson)
      case None    => Json.Null
    }

  /** Every `<Limit type="Min|Max">` on a fact as `{ limitType, valueType, value }`. Limits live *inside* the
    * `<Writable>` element (interleaved with the type element), so we descend into it rather than reading Fact children.
    */
  private def limitsJson(factNode: xml.Node): List[Json] =
    (factNode \ "Writable" \ "Limit").toList.flatMap { lim =>
      lim.child.collect { case e: Elem => e }.headOption.map { e =>
        Json.obj(
          "limitType" -> (lim \@ "type").asJson,
          "valueType" -> e.label.asJson,
          "value" -> e.text.trim.asJson,
        )
      }
    }

  /** One entry per `<page>`, in `index.xml` module order. Every `<fg-set>` on the page is its own editable block (keyed
    * by its fact `path`, which is unique even when a page carries several — one per collection item or income source),
    * each with its own `question`/`hint`. `alerts` are likewise every `<fg-alert>` on the page, keyed by `alert-key`,
    * with their editable text being the `<heading>`. (Earlier this only surfaced the first `<question>`/`<hint>` found
    * anywhere on the page, silently dropping every screen's other questions.)
    */
  private def buildScreensModel(factTypes: Map[String, String]): List[Json] =
    orderedModuleFiles().flatMap { case (_, path) =>
      val root = xml.XML.loadString(os.read(path))
      (root \\ "page").map { page =>
        val fgSets = (page \\ "fg-set").map { fs =>
          val fgSetPath = fs \@ "path"
          val factType = factTypes.getOrElse(fgSetPath, "")
          // The `<input type>` (or a bare `<select>`, which is the enum-backed select input). Surfaced so the flow form
          // can pre-select the current type and offer only the types valid for the bound fact.
          val inputType =
            if ((fs \ "select").nonEmpty) "select"
            else (fs \ "input").headOption.map(_ \@ "type").getOrElse("")
          Json.obj(
            "path" -> fgSetPath.asJson,
            "question" -> (fs \ "question").headOption.map(_.child.mkString.strip).getOrElse("").asJson,
            "hint" -> (fs \ "hint").headOption.map(_.child.mkString.strip).getOrElse("").asJson,
            "inputType" -> inputType.asJson,
            "factType" -> factType.asJson,
            "validInputTypes" -> inputTypesForNode(factType).asJson,
            // Mutually-exclusive gating (only one is ever non-empty). Editing them is the polarity toggle in T11/T13.
            "ifTrue" -> (fs \@ "if-true").asJson,
            "ifFalse" -> (fs \@ "if-false").asJson,
          )
        }.toList
        val alerts = (page \\ "fg-alert").map { a =>
          Json.obj(
            "id" -> (a \@ "alert-key").asJson,
            "text" -> (a \ "heading").headOption.map(_.child.mkString.strip).getOrElse("").asJson,
            "alertType" -> (a \@ "alert-type").asJson,
            "knockout" -> Json.fromBoolean((a \@ "knockout") == "true"),
            "condition" -> (a \@ "condition").asJson,
            "operator" -> (a \@ "operator").asJson,
          )
        }.toList
        Json.obj(
          "route" -> (page \@ "route").asJson,
          "title" -> (page \@ "title").asJson,
          "fgSets" -> Json.fromValues(fgSets),
          "alerts" -> Json.fromValues(alerts),
        )
      }.toList
    }

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  GET /author/lint  (T14 soft lint — analysis over on-disk Flow/FactDictionary, never a hard block)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  final private case class LintWarning(message: String, route: Option[String])

  /** Soft-lint warnings surfaced in the Author Mode lint panel. Unlike /author/validate these never block a save; they
    * flag likely authoring mistakes (a question wired to a computed fact, a gate/knockout no question can ever
    * satisfy).
    */
  private def buildLintJson(): Json = Try {
    val writable = writableFactPaths()
    val existing = allFactPaths()

    // Every fact path a question actually writes.
    val boundPaths: Set[String] =
      orderedModuleFiles().flatMap { case (_, p) =>
        (xml.XML.loadString(os.read(p)) \\ "fg-set").map(_ \@ "path").filter(_.nonEmpty)
      }.toSet

    val warnings = mutable.ListBuffer[LintWarning]()

    for ((_, p) <- orderedModuleFiles()) {
      val root = xml.XML.loadString(os.read(p))
      for (page <- root \\ "page") {
        val route = (page \@ "route")

        for (fs <- page \\ "fg-set") {
          val path = fs \@ "path"
          // (a) A question bound to an existing-but-not-Writable (i.e. Derived/computed) fact — users can't write it.
          if (path.nonEmpty && existing.contains(path) && !writable.contains(path))
            warnings += LintWarning(
              s"Question binds to '$path', which is a computed (Derived) fact — it can't be answered.",
              Some(route),
            )

          // (b) A gate fact that is Writable but is never set by any question — the gate can never flip.
          for (gate <- Seq(fs \@ "if-true", fs \@ "if-false").filter(_.nonEmpty))
            if (writable.contains(gate) && !boundPaths.contains(gate))
              warnings += LintWarning(
                s"Question is gated on '$gate', a writable fact no question ever sets — it will never show.",
                Some(route),
              )
        }

        // (c) A knockout alert whose condition is a Writable fact no question sets — the gate can never fire/clear.
        for (a <- page \\ "fg-alert" if (a \@ "knockout") == "true") {
          val cond = a \@ "condition"
          if (cond.nonEmpty && writable.contains(cond) && !boundPaths.contains(cond))
            warnings += LintWarning(
              s"Knockout alert depends on '$cond', a writable fact no question sets — it can never trigger.",
              Some(route),
            )
        }
      }
    }

    Json.obj(
      "warnings" -> Json.fromValues(warnings.toList.map { w =>
        Json.obj("message" -> w.message.asJson, "route" -> w.route.map(Json.fromString).getOrElse(Json.Null))
      }),
    )
  }.getOrElse(Json.obj("warnings" -> Json.arr()))

  /** Every fact path defined on disk (existence set for lint). */
  private def allFactPaths(): Set[String] =
    Try {
      sortedFactFiles()
        .flatMap(f => (xml.XML.loadString(os.read(f)) \\ "Fact").map(_ \@ "path").filter(_.nonEmpty))
        .toSet
    }.getOrElse(Set.empty)

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  GET /author/derived  (T18 — a fact's computation tree + the editor palette)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  /** The `<Derived>` computation tree of `path` as an editable JSON tree (or null if the fact has no Derived block),
    * plus the node palette and the fact-path lists the Dependency pickers need.
    */
  private def buildDerivedJson(path: String): Json = Try {
    val treeJson: Json = findFactElem(path)
      .flatMap(fact => (fact \ "Derived").headOption)
      .flatMap(_.child.collect { case e: Elem => e }.headOption)
      .map(child => DerivedXml.toJson(DerivedXml.parse(child)))
      .getOrElse(Json.Null)

    val factTypes = factTypeMap()
    Json.obj(
      "path" -> path.asJson,
      "tree" -> treeJson,
      "palette" -> DerivedGrammar.paletteJson,
      // A <Dependency> may point at *any* fact, so the picker lists them all; boolean/numeric subsets
      // let the editor hint at type-appropriate choices for conditions vs. arithmetic operands.
      "allPaths" -> allFactPaths().toList.sorted.asJson,
      "booleanPaths" -> factTypes.collect { case (p, "BooleanNode") => p }.toList.sorted.asJson,
      "numericPaths" -> factTypes.collect { case (p, t) if NumericTypeNodes(t) => p }.toList.sorted.asJson,
    )
  }.getOrElse(Json.obj("path" -> path.asJson, "tree" -> Json.Null, "palette" -> DerivedGrammar.paletteJson))

  /** The `path -> typeNode` map from the on-disk dictionary (e.g. "/foo" -> "DollarNode"). */
  private def factTypeMap(): Map[String, String] =
    Try {
      val dict = buildFactDictionary(Map.empty)
      dict
        .getPaths()
        .flatMap(p => Option(dict.getDefinition(p.toString)).map(d => p.toString -> d.typeNode))
        .toMap
    }.getOrElse(Map.empty)

  /** The `<Fact path="…">` element, scanning fact files in the runtime (alphabetical, last-wins) order. */
  private def findFactElem(path: String): Option[xml.Node] =
    sortedFactFiles().reverse.iterator
      .flatMap(f => (xml.XML.loadString(os.read(f)) \\ "Fact").filter(fact => (fact \@ "path") == path))
      .nextOption()

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  GET /author/fact-usage  (T19 — dependents of a fact, for the delete-impact preview)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  /** Everything that references `path`: other facts that depend on it (via `<Dependency>`, `<Find>`, `<Filter>`,
    * `<CollectionItem collection>`), and flow references (`fg-set path`, `if-true`/ `if-false`, `fg-alert condition`,
    * `fg-collection path`). Drives the delete-impact preview and the hard-block guard: a fact with any dependent cannot
    * be deleted.
    */
  private def buildFactUsageJson(path: String): Json = Try {
    val deps = factDependents(path)
    val flow = flowReferences(path)
    Json.obj(
      "path" -> path.asJson,
      "exists" -> Json.fromBoolean(findFactElem(path).isDefined),
      "factDependents" -> deps.asJson,
      "flowReferences" -> Json.fromValues(flow.map { case (route, where) =>
        Json.obj("route" -> route.asJson, "where" -> where.asJson)
      }),
      "canDelete" -> Json.fromBoolean(deps.isEmpty && flow.isEmpty),
    )
  }.getOrElse(Json.obj("path" -> path.asJson, "canDelete" -> Json.False))

  /** Fact paths whose definition references `path` (excluding the fact itself). */
  private def factDependents(path: String): List[String] =
    Try {
      sortedFactFiles()
        .flatMap { f =>
          (xml.XML.loadString(os.read(f)) \\ "Fact").collect {
            case fact if (fact \@ "path") != path && factReferencesPath(fact, path) => fact \@ "path"
          }
        }
        .distinct
        .sorted
        .toList
    }.getOrElse(Nil)

  /** True if `fact` references `path` in any way that deleting `path` would break: a `<Dependency>`/
    * `<Find>`/`<Filter>` `path`, a `<CollectionItem collection>`, or an `<Enum>`/`<MultiEnum>` `optionsPath` (an enum's
    * option set is itself a fact).
    */
  private def factReferencesPath(fact: xml.Node, path: String): Boolean = {
    val refs =
      (fact \\ "Dependency").map(_ \@ "path") ++
        (fact \\ "Find").map(_ \@ "path") ++
        (fact \\ "Filter").map(_ \@ "path") ++
        (fact \\ "CollectionItem").map(_ \@ "collection") ++
        (fact \\ "Enum").map(_ \@ "optionsPath") ++
        (fact \\ "MultiEnum").map(_ \@ "optionsPath")
    refs.exists(_ == path)
  }

  /** Flow references to `path`, as `(route, human-readable location)` pairs. */
  private def flowReferences(path: String): List[(String, String)] =
    Try {
      orderedModuleFiles()
        .flatMap { case (_, p) =>
          val root = xml.XML.loadString(os.read(p))
          (root \\ "page").flatMap { page =>
            val route = page \@ "route"
            val fromSets = (page \\ "fg-set")
              .filter(fs => (fs \@ "path") == path || (fs \@ "if-true") == path || (fs \@ "if-false") == path)
              .map(_ => (route, "a question on this screen"))
            val fromAlerts =
              (page \\ "fg-alert").filter(a => (a \@ "condition") == path).map(_ => (route, "an alert condition"))
            val fromCollections =
              (page \\ "fg-collection").filter(c => (c \@ "path") == path).map(_ => (route, "a collection"))
            fromSets ++ fromAlerts ++ fromCollections
          }
        }
        .distinct
        .toList
    }.getOrElse(Nil)

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  POST /author/validate  &  POST /author/save
  // ──────────────────────────────────────────────────────────────────────────────────────────

  private def handleEdit(body: String, save: Boolean, flags: Map[String, Boolean]): Json = {
    val (target, payload) = parseTarget(body)
    computeCandidate(target, payload) match {
      case Left(err)        => errorsJson(List(err))
      case Right(candidate) =>
        val errors = validateCandidate(target, candidate, payload.value)
        if (errors.nonEmpty) errorsJson(errors)
        else if (!save) errorsJson(Nil)
        else {
          persist(candidate, flags)
          Log.info(s"Author Mode saved ${target.kind} edit to ${candidate.sourceName}")
          errorsJson(Nil)
        }
    }
  }

  /** Write a validated candidate file to disk and re-run the exact build pipeline. The translationMap driving
    * flow_en.yaml is built purely from flow XML, so most edit kinds (constant, factDescription, factConfig, derived)
    * can never change it, and structural flow edits change attributes, not translatable text. Only re-sync the 7
    * translated flow_{lang}.yaml files when this save actually changed flow_en.yaml's content, instead of rewriting
    * every locale on every save regardless.
    */
  private def persist(candidate: Candidate, flags: Map[String, Boolean]): Unit = {
    val previousFlowEn =
      if (os.exists(generatedFlowContentPath(app))) Some(os.read(generatedFlowContentPath(app))) else None
    os.write.over(candidate.file, candidate.content)
    Formative.regenerate(app, flags)
    if (!previousFlowEn.contains(os.read(generatedFlowContentPath(app)))) syncTranslationLocales(app)
  }

  /** A parsed `edit` payload. `value`/`polarity` cover the scalar/attribute edits; `tree` carries the whole `<Derived>`
    * computation tree for the calculation editor (T18).
    */
  final private case class EditPayload(value: String, polarity: String, tree: Option[Json])

  private def parseTarget(body: String): (EditTarget, EditPayload) = {
    val doc = io.circe.parser.parse(body).getOrElse(Json.Null)
    val t = doc.hcursor.downField("target")
    def s(name: String): Option[String] = t.get[String](name).toOption.filter(_.nonEmpty)
    val edit = doc.hcursor.downField("edit")
    val value = edit.get[String]("value").toOption.getOrElse("")
    // `polarity` (if-true | if-false | none) rides along only for fg-set gating edits.
    val polarity = edit.get[String]("polarity").toOption.getOrElse("")
    val tree = edit.downField("tree").focus.filterNot(_.isNull)
    (
      EditTarget(s("kind").getOrElse(""), s("path"), s("file"), s("route"), s("field"), s("alertId")),
      EditPayload(value, polarity, tree),
    )
  }

  /** Build the patched candidate content for the target file (no disk write). */
  private def computeCandidate(t: EditTarget, payload: EditPayload): Either[FieldError, Candidate] = {
    val value = payload.value
    val polarity = payload.polarity
    t.kind match {
      case "constant" =>
        factsFileOf(t).flatMap { file =>
          patchConstant(os.read(file), t.path.get, value).map { case (patched, ctype) =>
            Candidate(file, xmllintFormat(patched), file.last, Some(ctype))
          }
        }

      case "factDescription" =>
        factsFileOf(t).flatMap { file =>
          patchDescription(os.read(file), t.path.get, value).map { patched =>
            Candidate(file, xmllintFormat(patched), file.last, None)
          }
        }

      case "screenText" =>
        t.route match {
          case None        => Left(FieldError("value", "Missing screen route for this edit."))
          case Some(route) =>
            flowFileForRoute(route) match {
              case None               => Left(FieldError("value", s"No screen found for route $route."))
              case Some((file, name)) =>
                patchScreenText(os.read(file), route, t.field.getOrElse(""), t.path, t.alertId, value)
                  .map(patched => Candidate(file, patched, name, None))
            }
        }

      // ─── v1 structural edits (attribute patches) ─────────────────────────────────
      case "screenAttr" =>
        withFlowFile(t) { (file, name, route) =>
          val fgSetPath = t.path.getOrElse("")
          val patched = t.field.getOrElse("") match {
            case "inputType" => patchInputType(os.read(file), route, fgSetPath, value)
            case "path"      => patchFgSetAttr(os.read(file), route, fgSetPath, "path", Some(value))
            case "gating"    => patchGating(os.read(file), route, fgSetPath, polarity, value)
            case other       => Left(FieldError("field", s"Unknown fg-set field: $other"))
          }
          patched.map(p => Candidate(file, xmllintFormat(p), name, None))
        }

      case "alertAttr" =>
        withFlowFile(t) { (file, name, route) =>
          val alertId = t.alertId.getOrElse("")
          val patched = t.field.getOrElse("") match {
            case "alertType" => patchAlertAttr(os.read(file), route, alertId, "alert-type", Some(value))
            case "condition" => patchAlertAttr(os.read(file), route, alertId, "condition", optionalAttr(value))
            case "operator"  => patchAlertAttr(os.read(file), route, alertId, "operator", optionalAttr(value))
            case "knockout"  =>
              patchAlertAttr(os.read(file), route, alertId, "knockout", if (value == "true") Some("true") else None)
            case other => Left(FieldError("field", s"Unknown alert field: $other"))
          }
          patched.map(p => Candidate(file, xmllintFormat(p), name, None))
        }

      case "factConfig" =>
        factsFileOf(t).flatMap { file =>
          val path = t.path.get
          val content = os.read(file)
          writableScalarTag(content, path) match {
            case None =>
              Left(
                FieldError("value", s"$path is not a simple scalar writable, so it has no placeholder/limit to edit."),
              )
            case Some(scalarTag) =>
              val patched = t.field.getOrElse("") match {
                case "placeholder" => patchPlaceholder(content, path, scalarTag, value)
                case "limitMin"    => patchLimit(content, path, "Min", scalarTag, value)
                case "limitMax"    => patchLimit(content, path, "Max", scalarTag, value)
                case other         => Left(FieldError("field", s"Unknown fact-config field: $other"))
              }
              patched.map(p => Candidate(file, xmllintFormat(p), file.last, None))
          }
        }

      // ─── v2 derived computation-tree edit (T18) ──────────────────────────────────
      case "derived" =>
        factsFileOf(t).flatMap { file =>
          payload.tree match {
            case None       => Left(FieldError("value", "Missing calculation tree for this edit."))
            case Some(tree) =>
              patchDerived(os.read(file), t.path.get, tree).map(p => Candidate(file, xmllintFormat(p), file.last, None))
          }
        }

      case other => Left(FieldError("kind", s"Unknown edit kind: $other"))
    }
  }

  /** Replace the single computation-node child of a fact's `<Derived>` with a tree built from the editor JSON. Only the
    * `<Derived>…</Derived>` inner markup changes; the rest of the fact block — and every other fact in the file — is
    * preserved byte-for-byte (the splice reuses the same located-block strategy as every other writer).
    * `xmllint --format` re-tidies afterward.
    */
  private def patchDerived(content: String, path: String, tree: Json): Either[FieldError, String] =
    Try(DerivedXml.render(DerivedXml.fromJson(tree))) match {
      case Failure(e)    => Left(FieldError("value", rootMsg(e)))
      case Success(elem) =>
        withFactBlock(content, path) { block =>
          replaceElementInner(block, "Derived", elem.toString) match {
            case Some(nb) => Right(nb)
            case None     => Left(FieldError("value", s"$path has no <Derived> block to edit."))
          }
        }
    }

  /** Empty attribute value → remove the attribute; non-empty → set it. */
  private def optionalAttr(value: String): Option[String] = if (value.trim.isEmpty) None else Some(value.trim)

  /** Resolve the flow module + route for a screen-scoped edit, then run `patch(file, moduleName, route)`. */
  private def withFlowFile(t: EditTarget)(
      patch: (os.Path, String, String) => Either[FieldError, Candidate],
  ): Either[FieldError, Candidate] =
    t.route match {
      case None        => Left(FieldError("value", "Missing screen route for this edit."))
      case Some(route) =>
        flowFileForRoute(route) match {
          case None               => Left(FieldError("value", s"No screen found for route $route."))
          case Some((file, name)) => patch(file, name, route)
        }
    }

  /** The scalar element name inside a fact's `<Writable>` (Dollar/Int/Rational/Day/String), or None for other shapes.
    */
  private def writableScalarTag(content: String, path: String): Option[String] =
    factBlockRegex(path)
      .findFirstIn(content)
      .flatMap("(?s)<Writable>\\s*<(Dollar|Int|Rational|Day|String)\\b".r.findFirstMatchIn)
      .map(_.group(1))

  private def factsFileOf(t: EditTarget): Either[FieldError, os.Path] =
    (t.file, t.path) match {
      case (Some(f), Some(_)) =>
        val p = factsDir / f
        if (os.exists(p)) Right(p) else Left(FieldError("value", s"Source file $f not found."))
      case _ => Left(FieldError("value", "Missing fact path or file for this edit."))
    }

  /** Run the full validation stack over an in-memory candidate. Never touches disk. */
  private def validateCandidate(t: EditTarget, candidate: Candidate, value: String): List[FieldError] =
    t.kind match {
      case "constant" =>
        // Friendly type check first; short-circuit so a malformed value doesn't also surface a raw parser stack trace.
        constantTypeError(candidate.constantType, value) match {
          case Some(e) => List(e)
          case None    =>
            schemaError(candidate.content, factsRng).toList ++
              factGraphError(candidate.sourceName, candidate.content).toList
        }

      case "factDescription" =>
        schemaError(candidate.content, factsRng).toList ++
          factGraphError(candidate.sourceName, candidate.content).toList

      case "screenText" =>
        // NB: we deliberately do NOT RelaxNG-validate the flow module here. Individual flow modules are
        // fragments that don't independently satisfy FlowConfig.rng (several production modules — agi,
        // filing-status, qualifying-children — fail it), and the build never RNG-validates flow anyway:
        // the flow parser is the real gate. So we resolve + parse the whole flow with this module
        // swapped in (which also rejects malformed XML) rather than RNG-checking the fragment.
        flowWiringError(candidate.sourceName, candidate.content).toList ++
          t.route.toList.flatMap(route => modalLinkErrors(candidate.content, route))

      // Structural fg-set edits: the flow parser already enforces path-existence, input/type match, Boolean gating and
      // if-true/if-false mutual exclusion — so we just re-run it, and add the one rule it doesn't check (a bound path
      // must be Writable, not Derived).
      case "screenAttr" =>
        flowWiringError(candidate.sourceName, candidate.content).toList ++
          bindingWritableError(t, value).toList

      // fg-alert edits: FgAlert.fromXml doesn't validate the condition or the knockout/alert-type pairing, so those are
      // checked explicitly against the patched candidate (plus a full flow re-parse to catch anything structural).
      case "alertAttr" =>
        flowWiringError(candidate.sourceName, candidate.content).toList ++
          alertConfigErrors(candidate.content, t.route.getOrElse(""), t.alertId.getOrElse(""))

      case "factConfig" =>
        schemaError(candidate.content, factsRng).toList ++
          factGraphError(candidate.sourceName, candidate.content).toList

      // Derived edit: the RelaxNG schema enforces the compnode grammar (valid nesting, required
      // slots), and FactDictionary.fromXml type-checks the tree (bad deps, type mismatches, cycles) —
      // exactly the build's own gates, run against the spliced candidate. No re-serializer to trust.
      case "derived" =>
        schemaError(candidate.content, factsRng).toList ++
          factGraphError(candidate.sourceName, candidate.content).toList

      case _ => Nil
    }

  /** T11: a rebound fg-set `path` must resolve to a Writable fact (a Derived value can't be written to). Only enforced
    * on the path-rebind edit; the flow parser handles existence + type.
    */
  private def bindingWritableError(t: EditTarget, value: String): Option[FieldError] =
    if (t.field.contains("path") && value.trim.nonEmpty && !writableFactPaths().contains(value.trim))
      Some(FieldError("value", s"'$value' is not a writable fact — a question can only bind to a writable fact."))
    else None

  /** T11: knockout/alert-type pairing + fg-alert condition/operator validity, evaluated on the patched candidate. */
  private def alertConfigErrors(moduleContent: String, route: String, alertId: String): List[FieldError] =
    Try {
      val root = xml.XML.loadString(moduleContent)
      (root \\ "page")
        .find(p => (p \@ "route") == route)
        .flatMap(p => (p \\ "fg-alert").find(a => (a \@ "alert-key") == alertId)) match {
        case None    => Nil
        case Some(a) =>
          val alertType = a \@ "alert-type"
          val knockout = (a \@ "knockout") == "true"
          val condition = (a \@ "condition").trim
          val operator = (a \@ "operator").trim

          val knockoutErr =
            if (knockout && alertType != "error")
              List(FieldError("value", "A knockout alert must have alert-type \"error\"."))
            else Nil

          val conditionErr =
            if (condition.nonEmpty) {
              val dict = buildFactDictionary(Map.empty)
              if (operator.isEmpty) List(FieldError("value", "An alert condition also needs an operator."))
              else if (Option(dict.getDefinition(condition)).isEmpty)
                List(FieldError("value", s"Condition fact '$condition' does not exist."))
              else if (!dict.getDefinition(condition).isBoolean)
                List(FieldError("value", s"Condition '$condition' must be a Boolean fact for isTrue/isFalse."))
              else Nil
            } else if (operator.nonEmpty) List(FieldError("value", "An operator needs a condition to apply to."))
            else Nil

          knockoutErr ++ conditionErr
      }
    }.getOrElse(Nil)

  /** The set of Writable fact paths on disk (used by the path-rebind writability guard). */
  private def writableFactPaths(): Set[String] =
    Try {
      sortedFactFiles().flatMap { f =>
        val root = xml.XML.loadString(os.read(f))
        (root \\ "Fact").collect { case fact if (fact \ "Writable").nonEmpty => fact \@ "path" }
      }.toSet
    }.getOrElse(Set.empty)

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  POST /author/create-fact  &  /author/create-screen  (T17)  &  /author/delete-fact (T19)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  /** Shared JSON-body cursor helper. */
  private def bodyCursor(body: String): ACursor =
    io.circe.parser.parse(body).getOrElse(Json.Null).hcursor

  private def str(c: ACursor, name: String): String = c.get[String](name).toOption.map(_.trim).getOrElse("")
  private def flag(c: ACursor, name: String): Boolean = c.get[Boolean](name).toOption.getOrElse(false)

  /** Create a net-new fact (writable, constant, or full derived tree) in the chosen fact file. */
  private def handleCreateFact(body: String, flags: Map[String, Boolean]): Json = {
    val c = bodyCursor(body)
    val path = str(c, "path")
    val fileName = str(c, "file")
    val save = flag(c, "save")

    val guard: Either[FieldError, Unit] =
      if (!path.startsWith("/") || path.length < 2) Left(FieldError("path", "A fact path must start with '/'."))
      else if (path.matches(".*\\s.*")) Left(FieldError("path", "A fact path can't contain spaces."))
      else if (findFactElem(path).isDefined) Left(FieldError("path", s"A fact '$path' already exists."))
      else if (fileName.isEmpty || !os.exists(factsDir / fileName)) Left(FieldError("file", "Pick a fact file."))
      else Right(())

    val result = guard.flatMap(_ => buildFactElem(c)).flatMap { factElem =>
      val file = factsDir / fileName
      spliceBefore(os.read(file), "</Facts>", factElem.toString) match {
        case None          => Left(FieldError("file", s"$fileName has no <Facts> block to add to."))
        case Some(patched) => Right(Candidate(file, patched, fileName, None))
      }
    }

    result match {
      case Left(err)        => errorsJson(List(err))
      case Right(candidate) =>
        val errors = schemaError(candidate.content, factsRng).toList ++
          factGraphError(candidate.sourceName, candidate.content).toList
        if (errors.nonEmpty) errorsJson(errors)
        else if (!save) errorsJson(Nil)
        else { persist(candidate, flags); Log.info(s"Author Mode created fact $path in $fileName"); errorsJson(Nil) }
    }
  }

  /** Build the `<Fact>` element for a create-fact request from its `kind`. */
  private def buildFactElem(c: ACursor): Either[FieldError, Elem] = {
    val path = str(c, "path")
    val description = str(c, "description")
    val kind = str(c, "kind")
    val descNodes: Seq[Node] = if (description.nonEmpty) Seq(<Description>{description}</Description>) else Seq.empty

    def fact(body: Seq[Node]): Elem = <Fact path={path}>{descNodes ++ body}</Fact>

    kind match {
      case "writable" =>
        val t = str(c, "valueType")
        if (!WritableScalarTypes.contains(t)) Left(FieldError("valueType", s"Unsupported writable type '$t'."))
        else Right(fact(Seq(<Writable>{Elem(null, t, Null, TopScope, minimizeEmpty = true)}</Writable>)))

      case "constant" =>
        val vt = str(c, "valueType")
        val value = str(c, "constantValue")
        constantTypeError(Some(vt), value) match {
          case Some(e)                                    => Left(e)
          case None if vt != "Dollar" && vt != "Rational" =>
            Left(FieldError("valueType", "A constant must be a Dollar or Rational value."))
          case None =>
            Right(fact(Seq(<Derived>{Elem(null, vt, Null, TopScope, minimizeEmpty = true, Text(value))}</Derived>)))
        }

      case "derived" =>
        c.downField("tree").focus.filterNot(_.isNull) match {
          case None       => Left(FieldError("tree", "Build a calculation for this derived fact."))
          case Some(tree) =>
            Try(DerivedXml.render(DerivedXml.fromJson(tree))) match {
              case Failure(e)    => Left(FieldError("tree", rootMsg(e)))
              case Success(elem) => Right(fact(Seq(<Derived>{elem}</Derived>)))
            }
        }

      case other => Left(FieldError("kind", s"Unknown fact kind '$other'."))
    }
  }

  /** The writable scalar types the create-fact wizard offers (the simple, config-free ones). */
  private val WritableScalarTypes = Set("Dollar", "Int", "Boolean", "String", "Day")

  /** Create a net-new screen (a page shell, optionally with one first question) in a flow module. */
  private def handleCreateScreen(body: String, flags: Map[String, Boolean]): Json = {
    val c = bodyCursor(body)
    val module = str(c, "module")
    val route = str(c, "route")
    val title = str(c, "title")
    val save = flag(c, "save")

    val guard: Either[FieldError, Unit] =
      if (module.isEmpty || !os.exists(flowDir / module)) Left(FieldError("module", "Pick a flow module."))
      else if (!route.startsWith("/") || route.isEmpty) Left(FieldError("route", "A route must start with '/'."))
      else if (title.isEmpty) Left(FieldError("title", "Give the screen a title."))
      else if (routeExists(route)) Left(FieldError("route", s"A screen with route '$route' already exists."))
      else Right(())

    val result = guard.flatMap(_ => buildPageElem(c)).flatMap { page =>
      val file = flowDir / module
      spliceBefore(os.read(file), "</FlowConfig>", page.toString) match {
        case None          => Left(FieldError("module", s"$module has no <FlowConfig> to add to."))
        case Some(patched) => Right(Candidate(file, patched, module, None))
      }
    }

    result match {
      case Left(err)        => errorsJson(List(err))
      case Right(candidate) =>
        val errors = flowWiringError(candidate.sourceName, candidate.content).toList
        if (errors.nonEmpty) errorsJson(errors)
        else if (!save) errorsJson(Nil)
        else { persist(candidate, flags); Log.info(s"Author Mode created screen $route in $module"); errorsJson(Nil) }
    }
  }

  /** Build a page shell, optionally including one bound first question. */
  private def buildPageElem(c: ACursor): Either[FieldError, Elem] = {
    val route = str(c, "route")
    val title = str(c, "title")
    val q = c.downField("firstQuestion")

    val questionNodes: Either[FieldError, Seq[Node]] =
      if (q.focus.exists(_.isNull) || q.focus.isEmpty) Right(Seq.empty)
      else {
        val qPath = str(q, "path")
        val questionText = str(q, "question")
        val inputType = str(q, "inputType")
        if (qPath.isEmpty && questionText.isEmpty && inputType.isEmpty) Right(Seq.empty)
        else if (!writableFactPaths().contains(qPath))
          Left(FieldError("firstQuestion.path", s"'$qPath' is not a writable fact to bind a question to."))
        else if (questionText.isEmpty) Left(FieldError("firstQuestion.question", "Enter the question text."))
        else if (!FirstQuestionInputTypes.contains(inputType))
          Left(FieldError("firstQuestion.inputType", s"Unsupported input type '$inputType'."))
        else Right(Seq(buildFgSet(qPath, questionText, inputType)))
      }

    questionNodes.map { qs =>
      <page title={title} route={route}><section><h2>{title}</h2>{qs}</section></page>
    }
  }

  /** The input types the first-question sub-form offers (the ones needing no extra options wiring). */
  private val FirstQuestionInputTypes = Set("boolean", "dollar", "int", "text", "date")

  private def buildFgSet(path: String, question: String, inputType: String): Elem = {
    val input =
      if (inputType == "boolean")
        <input type="boolean"><option value="true">Yes</option><option value="false">No</option></input>
      else Elem(null, "input", new UnprefixedAttribute("type", inputType, Null), TopScope, minimizeEmpty = true)
    <fg-set path={path}><question>{question}</question>{input}</fg-set>
  }

  /** Delete a fact — hard-blocked if anything still references it (T19 policy). */
  private def handleDeleteFact(body: String, flags: Map[String, Boolean]): Json = {
    val c = bodyCursor(body)
    val path = str(c, "path")
    val save = flag(c, "save")

    if (findFactElem(path).isEmpty) errorsJson(List(FieldError("path", s"No fact '$path' to delete.")))
    else {
      val deps = factDependents(path)
      val flow = flowReferences(path)
      if (deps.nonEmpty || flow.nonEmpty) {
        val where = (deps.map(d => s"fact $d") ++ flow.map { case (r, w) => s"$w ($r)" }).take(8).mkString("; ")
        errorsJson(List(FieldError("path", s"'$path' is still used by: $where. Remove those references first.")))
      } else {
        // A path is normally defined in one file, but constants are split by tax year — remove it from
        // every file that defines it, then validate the merged dictionary once. The removal is surgical
        // (drop the <Fact> block plus the leading newline+indent that positioned it) so the rest of the
        // file stays byte-for-byte — no whole-file reformat, matching the other preserve-and-patch writers.
        val files = factFilesContaining(path)
        val overrides = files.map(f => f.last -> factBlockWithLeadingWs(path).replaceFirstIn(os.read(f), "")).toMap
        factGraphErrorWith(overrides) match {
          case Some(err) => errorsJson(List(err))
          case None      =>
            if (!save) errorsJson(Nil)
            else {
              files.foreach(f => os.write.over(f, overrides(f.last)))
              Formative.regenerate(app, flags)
              Log.info(s"Author Mode deleted fact $path from ${files.map(_.last).mkString(", ")}")
              errorsJson(Nil)
            }
        }
      }
    }
  }

  private def factFilesContaining(path: String): Seq[os.Path] =
    sortedFactFiles().filter(f => factBlockRegex(path).findFirstIn(os.read(f)).isDefined)

  /** Pretty-print a single XML fragment (a `<Fact>` or `<page>`) on its own, stripping the `<?xml?>` declaration
    * `xmllint` prepends. `--encode UTF-8` keeps non-ASCII literal. Returns None if the fragment doesn't parse.
    */
  private def formatFragment(fragmentXml: String): Option[String] = {
    val tmp = os.temp(contents = fragmentXml, suffix = ".xml")
    try {
      val r = os.proc("xmllint", "--format", "--encode", "UTF-8", tmp.toString).call(check = false)
      if (r.exitCode != 0) None
      else Some(r.out.text().linesIterator.filterNot(_.trim.startsWith("<?xml")).mkString("\n").trim)
    } finally os.remove(tmp)
  }

  /** Splice a new element in immediately before the last occurrence of `marker` (`</Facts>` / `</FlowConfig>`),
    * indented one level deeper than the marker, leaving the rest of the file byte-for-byte. This keeps a create-fact /
    * create-screen diff to exactly the new element — the whole point of preserve-and-patch — instead of reflowing the
    * entire file through `xmllint`.
    */
  private def spliceBefore(content: String, marker: String, fragmentXml: String): Option[String] =
    formatFragment(fragmentXml).flatMap { fragment =>
      val idx = content.lastIndexOf(marker)
      if (idx < 0) None
      else {
        val lineStart = content.lastIndexOf('\n', idx - 1) + 1
        val markerIndent = content.substring(lineStart, idx) // whitespace indenting the marker line
        val childIndent = markerIndent + "  "
        val indented = fragment.linesIterator.map(l => if (l.isEmpty) l else childIndent + l).mkString("\n")
        Some(content.substring(0, lineStart) + indented + "\n" + content.substring(lineStart))
      }
    }

  /** True if any flow page already uses `route`. */
  private def routeExists(route: String): Boolean =
    Try {
      orderedModuleFiles().exists { case (_, p) =>
        (xml.XML.loadString(os.read(p)) \\ "page").exists(pg => (pg \@ "route") == route)
      }
    }.getOrElse(false)

  // ─── individual validators ──────────────────────────────────────────────────────────────

  private def schemaError(content: String, rng: os.Path): Option[FieldError] =
    xmllintRelaxng(content, rng).map(m => FieldError("value", s"Schema validation failed: $m"))

  /** Rebuild the whole FactDictionary with this one file replaced by the candidate; throws on bad deps/types/cycles. */
  private def factGraphError(fileName: String, content: String): Option[FieldError] =
    factGraphErrorWith(Map(fileName -> content))

  /** Rebuild the whole FactDictionary with a set of files replaced by candidates; throws on bad deps/types/cycles. Used
    * by the delete path (a fact can span several files, e.g. tax-year constants).
    */
  private def factGraphErrorWith(overrides: Map[String, String]): Option[FieldError] =
    Try(buildFactDictionary(overrides)) match {
      case Success(_) => None
      case Failure(e) => Some(FieldError("value", s"Fact graph error: ${factGraphMessage(e)}"))
    }

  /** Re-run the flow parser (Flow.fromXmlConfig → Utils.validateFact / Condition.validateCondition / FgSet type checks)
    * with this module replaced by the candidate.
    */
  private def flowWiringError(fileName: String, content: String): Option[FieldError] =
    Try {
      val factDict = buildFactDictionary(Map.empty)
      Flow.fromXmlConfig(buildResolvedFlowConfig(Map(fileName -> content)), factDict, app)
    } match {
      case Success(_) => None
      case Failure(e) => Some(FieldError("value", s"Flow wiring error: ${rootMsg(e)}"))
    }

  /** Author-Mode-only guard the build silently skips: every `modal-link for` on the edited screen must resolve to a
    * `modal-dialog id` on that same screen.
    */
  private def modalLinkErrors(moduleContent: String, route: String): List[FieldError] =
    Try {
      val root = xml.XML.loadString(moduleContent)
      (root \\ "page").find(p => (p \@ "route") == route) match {
        case None       => Nil
        case Some(page) =>
          val ids = (page \\ "modal-dialog").map(_ \@ "id").filter(_.nonEmpty).toSet
          (page \\ "modal-link")
            .map(_ \@ "for")
            .filter(_.nonEmpty)
            .distinct
            .filterNot(ids.contains)
            .map(f =>
              FieldError("value", s"Modal link references '$f' but no <modal-dialog id=\"$f\"> exists on this screen."),
            )
            .toList
      }
    }.getOrElse(Nil)

  private def constantTypeError(constantType: Option[String], value: String): Option[FieldError] = {
    val v = value.trim
    constantType match {
      case Some("Dollar") =>
        if (v.matches("-?\\d+(\\.\\d+)?")) None
        else Some(FieldError("value", s"'$value' is not a valid Dollar amount (expected a number like 12200)."))
      case Some("Rational") =>
        val parts = v.split("/", -1)
        if (v.matches("-?\\d+/\\d+") && !parts(1).matches("0+")) None
        else Some(FieldError("value", s"'$value' is not a valid Rational (expected a fraction like 31/250)."))
      case _ => None
    }
  }

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  Preserve-and-patch writer (byte-for-byte surgical replacement of a single located element)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  private def factBlockRegex(path: String): Regex =
    ("(?s)<Fact\\s+path=\"" + Pattern.quote(path) + "\"[^>]*>.*?</Fact>").r

  /** The `<Fact>` block plus the leading newline + indentation that positioned it — matched so a delete removes the
    * whole line the fact opened on, leaving no dangling blank line and touching nothing else.
    */
  private def factBlockWithLeadingWs(path: String): Regex =
    ("(?s)\\n[ \\t]*<Fact\\s+path=\"" + Pattern.quote(path) + "\"[^>]*>.*?</Fact>").r

  private def pageBlockRegex(route: String): Regex =
    ("(?s)<page\\b[^>]*\\broute=\"" + Pattern.quote(route) + "\"[^>]*>.*?</page>").r

  private def alertBlockRegex(alertId: String): Regex =
    ("(?s)<fg-alert\\b[^>]*\\balert-key=\"" + Pattern.quote(alertId) + "\"[^>]*>.*?</fg-alert>").r

  private def fgSetBlockRegex(path: String): Regex =
    ("(?s)<fg-set\\b[^>]*\\bpath=\"" + Pattern.quote(path) + "\"[^>]*>.*?</fg-set>").r

  /** Replace the inner text of the first `<tag>…</tag>` found in `scope`, returning the new scope. */
  private def replaceElementInner(scope: String, tag: String, newInner: String): Option[String] = {
    val re = s"(?s)<$tag>(.*?)</$tag>".r
    re.findFirstMatchIn(scope).map { m =>
      replaceFirstLiteral(scope, m.matched, s"<$tag>$newInner</$tag>")
    }
  }

  private def replaceFirstLiteral(s: String, target: String, replacement: String): String = {
    val i = s.indexOf(target)
    if (i < 0) s else s.substring(0, i) + replacement + s.substring(i + target.length)
  }

  private def patchConstant(content: String, path: String, value: String): Either[FieldError, (String, String)] =
    factBlockRegex(path).findFirstIn(content) match {
      case None        => Left(FieldError("value", s"Could not locate fact $path in the source file."))
      case Some(block) =>
        val ctype =
          if (block.contains("<Rational>")) "Rational"
          else if (block.contains("<Dollar>")) "Dollar"
          else ""
        if (ctype.isEmpty) Left(FieldError("value", s"$path is not a simple Dollar or Rational constant."))
        else
          replaceElementInner(block, ctype, escapeBareAmpersands(value.trim)) match {
            case Some(newBlock) => Right((replaceFirstLiteral(content, block, newBlock), ctype))
            case None           => Left(FieldError("value", s"Could not locate the $ctype value for $path."))
          }
    }

  private def patchDescription(content: String, path: String, value: String): Either[FieldError, String] =
    factBlockRegex(path).findFirstIn(content) match {
      case None        => Left(FieldError("value", s"Could not locate fact $path in the source file."))
      case Some(block) =>
        val escaped = escapeBareAmpersands(value)
        val newBlock = replaceElementInner(block, "Description", escaped).getOrElse {
          // No existing <Description>: insert one right after the <Fact …> opening tag (xmllint --format fixes indent).
          val openTag = ("(?s)<Fact\\s+path=\"" + Pattern.quote(path) + "\"[^>]*>").r.findFirstIn(block).get
          replaceFirstLiteral(block, openTag, s"$openTag\n<Description>$escaped</Description>")
        }
        Right(replaceFirstLiteral(content, block, newBlock))
    }

  private def patchScreenText(
      content: String,
      route: String,
      field: String,
      fgSetPath: Option[String],
      alertId: Option[String],
      value: String,
  ): Either[FieldError, String] =
    pageBlockRegex(route).findFirstIn(content) match {
      case None       => Left(FieldError("value", s"Could not locate screen $route in the flow file."))
      case Some(page) =>
        // Question/hint/alert heading are mixed content (they may contain <fg-show/> etc.), so markup is preserved and
        // only bare ampersands are escaped; malformed XML is caught downstream by xmllint + the flow parser.
        val escaped = escapeBareAmpersands(value)
        val patchedPage: Either[FieldError, String] = field match {
          case "question" | "hint" =>
            // A page can carry several `<fg-set>` blocks (one per collection item / income source), so question/hint
            // edits are scoped to the specific fg-set by its (unique) fact path rather than the first match on the page.
            fgSetPath match {
              case None     => Left(FieldError("value", s"Missing fact path for this $field edit."))
              case Some(fp) =>
                fgSetBlockRegex(fp).findFirstIn(page) match {
                  case None             => Left(FieldError("value", s"Could not locate fg-set '$fp' on screen $route."))
                  case Some(fgSetBlock) =>
                    replaceElementInner(fgSetBlock, field, escaped) match {
                      case None           => Left(FieldError("value", s"fg-set '$fp' has no <$field> to edit."))
                      case Some(newFgSet) => Right(replaceFirstLiteral(page, fgSetBlock, newFgSet))
                    }
                }
            }
          case "alert" =>
            alertId match {
              case None     => Left(FieldError("value", "Missing alertId for an alert edit."))
              case Some(id) =>
                alertBlockRegex(id).findFirstIn(page) match {
                  case None             => Left(FieldError("value", s"Could not locate alert '$id' on screen $route."))
                  case Some(alertBlock) =>
                    replaceElementInner(alertBlock, "heading", escaped) match {
                      case None           => Left(FieldError("value", s"Alert '$id' has no <heading> to edit."))
                      case Some(newAlert) => Right(replaceFirstLiteral(page, alertBlock, newAlert))
                    }
                }
            }
          case other => Left(FieldError("field", s"Unknown screen text field: $other"))
        }
        patchedPage.map(newPage => replaceFirstLiteral(content, page, newPage))
    }

  /** Escape a bare `&` (one not already opening a numeric/named entity) so authors can type "AT&T" without breaking
    * XML, while leaving legitimate markup (`<fg-show/>`) and existing entities untouched.
    */
  private val bareAmpersand = "&(?!(#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);)".r
  private def escapeBareAmpersands(s: String): String = bareAmpersand.replaceAllIn(s, "&amp;")

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  Attribute-patch writer (v1 T12) — surgical patch of a single attribute on one opening tag.
  //  Structure/text around the attribute is preserved byte-for-byte; xmllint --format tidies whitespace.
  // ──────────────────────────────────────────────────────────────────────────────────────────

  /** The opening (or self-closing) tag of the first `<tag …>` in `scope`. */
  private def openingTagOf(scope: String, tag: String): Option[String] =
    ("(?s)<" + Pattern.quote(tag) + "\\b[^>]*?/?>").r.findFirstIn(scope)

  /** Set (or, with `newValue = None`, remove) a single attribute on the first `<tag …>` opening tag in `scope`. Returns
    * the rewritten scope, or a FieldError if the tag can't be located.
    */
  private def patchOpeningTagAttr(
      scope: String,
      tag: String,
      attr: String,
      newValue: Option[String],
  ): Either[FieldError, String] =
    openingTagOf(scope, tag) match {
      case None          => Left(FieldError("value", s"Could not locate a <$tag> element to edit."))
      case Some(openTag) =>
        val attrRe = ("\\s+" + Pattern.quote(attr) + "=\"[^\"]*\"").r
        val newOpen = newValue match {
          case Some(v) =>
            val replacement = s""" $attr="${escapeAttr(v)}""""
            if (attrRe.findFirstIn(openTag).isDefined)
              attrRe.replaceFirstIn(openTag, java.util.regex.Matcher.quoteReplacement(replacement))
            else if (openTag.endsWith("/>")) openTag.dropRight(2) + replacement + "/>"
            else openTag.dropRight(1) + replacement + ">"
          case None =>
            attrRe.replaceFirstIn(openTag, "")
        }
        Right(replaceFirstLiteral(scope, openTag, newOpen))
    }

  /** Escape the few characters that would break a double-quoted XML attribute value. */
  private def escapeAttr(s: String): String =
    escapeBareAmpersands(s).replace("\"", "&quot;").replace("<", "&lt;")

  /** Patch an attribute on the `<fg-set path="…">` block for `route`, located by its (unique) fact path. */
  private def patchFgSetAttr(
      content: String,
      route: String,
      fgSetPath: String,
      attr: String,
      newValue: Option[String],
  ): Either[FieldError, String] =
    withPage(content, route) { page =>
      fgSetBlockRegex(fgSetPath).findFirstIn(page) match {
        case None        => Left(FieldError("value", s"Could not locate fg-set '$fgSetPath' on screen $route."))
        case Some(block) =>
          patchOpeningTagAttr(block, "fg-set", attr, newValue).map(replaceFirstLiteral(page, block, _))
      }
    }

  /** Patch the `type` attribute of the `<input>` inside the `<fg-set path="…">` block for `route`. */
  private def patchInputType(
      content: String,
      route: String,
      fgSetPath: String,
      value: String,
  ): Either[FieldError, String] =
    withPage(content, route) { page =>
      fgSetBlockRegex(fgSetPath).findFirstIn(page) match {
        case None        => Left(FieldError("value", s"Could not locate fg-set '$fgSetPath' on screen $route."))
        case Some(block) =>
          if (openingTagOf(block, "input").isEmpty)
            Left(FieldError("value", "This question uses a <select>, whose input type can't be changed here."))
          else patchOpeningTagAttr(block, "input", "type", Some(value)).map(replaceFirstLiteral(page, block, _))
      }
    }

  /** Replace the gating on an fg-set. `polarity` is "if-true", "if-false", or "none"; both attributes are removed first
    * so the two never coexist (the mutual-exclusion rule the flow parser enforces).
    */
  private def patchGating(
      content: String,
      route: String,
      fgSetPath: String,
      polarity: String,
      gatePath: String,
  ): Either[FieldError, String] =
    for {
      c1 <- patchFgSetAttr(content, route, fgSetPath, "if-true", None)
      c2 <- patchFgSetAttr(c1, route, fgSetPath, "if-false", None)
      c3 <-
        if (polarity == "none" || gatePath.isEmpty) Right(c2)
        else if (polarity == "if-true" || polarity == "if-false")
          patchFgSetAttr(c2, route, fgSetPath, polarity, Some(gatePath))
        else Left(FieldError("value", s"Unknown gating polarity '$polarity'."))
    } yield c3

  /** Patch an attribute on the `<fg-alert alert-key="…">` block for `route`. */
  private def patchAlertAttr(
      content: String,
      route: String,
      alertId: String,
      attr: String,
      newValue: Option[String],
  ): Either[FieldError, String] =
    withPage(content, route) { page =>
      alertBlockRegex(alertId).findFirstIn(page) match {
        case None        => Left(FieldError("value", s"Could not locate alert '$alertId' on screen $route."))
        case Some(block) =>
          patchOpeningTagAttr(block, "fg-alert", attr, newValue).map(replaceFirstLiteral(page, block, _))
      }
    }

  /** Run `patch` against the located `<page route="…">` block and splice the result back into `content`. */
  private def withPage(content: String, route: String)(
      patch: String => Either[FieldError, String],
  ): Either[FieldError, String] =
    pageBlockRegex(route).findFirstIn(content) match {
      case None       => Left(FieldError("value", s"Could not locate screen $route in the flow file."))
      case Some(page) => patch(page).map(replaceFirstLiteral(content, page, _))
    }

  // ─── Fact <Placeholder> / <Limit> value patch (v1 T15) ──────────────────────────────────────

  /** fact-graph type node → the scalar element name used inside `<Placeholder>`/`<Limit>`. */
  private def scalarTagForNode(typeNode: String): Option[String] = typeNode match {
    case "DollarNode"   => Some("Dollar")
    case "IntNode"      => Some("Int")
    case "RationalNode" => Some("Rational")
    case "DayNode"      => Some("Day")
    case "StringNode"   => Some("String")
    case _              => None
  }

  /** Set, change, or (empty value) clear a fact's `<Placeholder>`. Placeholder is a Fact-level sibling of `<Writable>`;
    * a new one is inserted immediately after `</Writable>` so it stays schema-valid.
    */
  private def patchPlaceholder(
      content: String,
      path: String,
      scalarTag: String,
      value: String,
  ): Either[FieldError, String] =
    withFactBlock(content, path) { block =>
      val phRe = "(?s)<Placeholder>.*?</Placeholder>".r
      val v = value.trim
      phRe.findFirstIn(block) match {
        case Some(existing) if v.isEmpty => Right(stripBlank(replaceFirstLiteral(block, existing, "")))
        case Some(existing)              =>
          Right(replaceFirstLiteral(block, existing, s"<Placeholder><$scalarTag>$v</$scalarTag></Placeholder>"))
        case None if v.isEmpty => Right(block) // nothing to clear
        case None              =>
          "(?s)</Writable>".r.findFirstIn(block) match {
            case None    => Left(FieldError("value", s"$path has no <Writable> to attach a placeholder to."))
            case Some(_) =>
              Right(
                replaceFirstLiteral(
                  block,
                  "</Writable>",
                  s"</Writable><Placeholder><$scalarTag>$v</$scalarTag></Placeholder>",
                ),
              )
          }
      }
    }

  /** Set, change, or (empty value) clear a `<Limit type="Min|Max">`. Limits live *inside* `<Writable>`, so a new one is
    * inserted just before `</Writable>`.
    */
  private def patchLimit(
      content: String,
      path: String,
      limitType: String,
      scalarTag: String,
      value: String,
  ): Either[FieldError, String] =
    withFactBlock(content, path) { block =>
      val limitRe = ("(?s)<Limit\\b[^>]*\\btype=\"" + Pattern.quote(limitType) + "\"[^>]*>.*?</Limit>").r
      val v = value.trim
      limitRe.findFirstIn(block) match {
        case Some(existing) if v.isEmpty => Right(stripBlank(replaceFirstLiteral(block, existing, "")))
        case Some(existing)              =>
          Right(
            replaceFirstLiteral(block, existing, s"""<Limit type="$limitType"><$scalarTag>$v</$scalarTag></Limit>"""),
          )
        case None if v.isEmpty => Right(block)
        case None              =>
          "(?s)</Writable>".r.findFirstIn(block) match {
            case None    => Left(FieldError("value", s"$path has no <Writable> to attach a limit to."))
            case Some(_) =>
              Right(
                replaceFirstLiteral(
                  block,
                  "</Writable>",
                  s"""<Limit type="$limitType"><$scalarTag>$v</$scalarTag></Limit></Writable>""",
                ),
              )
          }
      }
    }

  /** Run `patch` against the located `<Fact path="…">` block and splice the result back into `content`. */
  private def withFactBlock(content: String, path: String)(
      patch: String => Either[FieldError, String],
  ): Either[FieldError, String] =
    factBlockRegex(path).findFirstIn(content) match {
      case None        => Left(FieldError("value", s"Could not locate fact $path in the source file."))
      case Some(block) => patch(block).map(replaceFirstLiteral(content, block, _))
    }

  /** Collapse a run of blank lines left by removing an element (cosmetic; xmllint --format re-tidies afterward). */
  private def stripBlank(s: String): String = s.replaceAll("(?m)^[ \\t]*\\r?\\n", "")

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  Candidate builders (rebuild the FactDictionary / resolved Flow with one file overridden)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  private def sortedFactFiles(): Seq[os.Path] =
    os.list(factsDir).filter(p => os.isFile(p) && p.ext == "xml").sortBy(_.last)

  private def orderedModuleFiles(): List[(String, os.Path)] = {
    val idx = xml.XML.loadString(os.read(flowDir / "index.xml"))
    (idx \\ "module").toList.map { m =>
      val name = (m \@ "src").replaceAll("^\\./", "")
      (name, flowDir / name)
    }
  }

  private def flowFileForRoute(route: String): Option[(os.Path, String)] =
    orderedModuleFiles()
      .find { case (_, p) =>
        (xml.XML.loadString(os.read(p)) \\ "page").exists(pg => (pg \@ "route") == route)
      }
      .map { case (name, p) => (p, name) }

  /** Merge all fact XML files in `facts/` (with `overrides` keyed by file name substituted) and build a FactDictionary;
    * throws on any integrity error, exactly as the build does.
    */
  private def buildFactDictionary(overrides: Map[String, String]): FactDictionary = {
    val buffer = new NodeBuffer()
    for (file <- sortedFactFiles()) {
      val content = overrides.getOrElse(file.last, os.read(file))
      buffer ++= (xml.XML.loadString(content) \ "Facts" \ "_")
    }
    val module = <FactDictionaryModule><Facts>{buffer}</Facts></FactDictionaryModule>
    FactDictionary.fromXml(module)
  }

  /** Resolve `flow/index.xml`'s modules into a single `<FlowConfig>` of pages, with `overrides` (keyed by module file
    * name) substituted. Mirrors `Formative.regenerate`'s module resolution, but reads from disk directly.
    */
  private def buildResolvedFlowConfig(overrides: Map[String, String]): Elem = {
    val idx = xml.XML.loadString(os.read(flowDir / "index.xml"))
    val resolved = (idx \\ "FlowConfig" \ "_").flatMap { child =>
      child.label match {
        case "module" =>
          val name = (child \@ "src").replaceAll("^\\./", "")
          val content = overrides.getOrElse(name, os.read(flowDir / name))
          xml.XML.loadString(content) \ "_"
        case _ => Seq(child)
      }
    }
    <FlowConfig>{resolved}</FlowConfig>
  }

  // ──────────────────────────────────────────────────────────────────────────────────────────
  //  xmllint shell-outs (match `make format` / `make validate-xml`)
  // ──────────────────────────────────────────────────────────────────────────────────────────

  /** Format XML content via `xmllint --format` (best-effort: returns the input unchanged if it fails to parse).
    * `--encode UTF-8` keeps non-ASCII characters (e.g. the typographic apostrophe `’` in alert copy) literal instead of
    * escaping them to `&#x2019;` numeric entities — otherwise every reformat churns the smart quotes across the whole
    * file.
    */
  private def xmllintFormat(content: String): String = {
    val tmp = os.temp(contents = content, suffix = ".xml")
    try {
      val r = os.proc("xmllint", "--format", "--encode", "UTF-8", tmp.toString).call(check = false)
      if (r.exitCode == 0) r.out.text() else content
    } finally os.remove(tmp)
  }

  /** Validate content against a RelaxNG schema. Returns None if valid, else a cleaned error message. */
  private def xmllintRelaxng(content: String, rng: os.Path): Option[String] = {
    val tmp = os.temp(contents = content, suffix = ".xml")
    try {
      val r = os.proc("xmllint", "--noout", "--relaxng", rng.toString, tmp.toString).call(check = false)
      if (r.exitCode == 0) None else Some(cleanXmllint(r.err.text(), tmp.toString))
    } finally os.remove(tmp)
  }

  private def cleanXmllint(stderr: String, tmpPath: String): String =
    stderr.linesIterator
      .map(
        _.replace(tmpPath, "")
          .replaceAll("^:\\d+:\\s*", "")
          .replaceFirst("^Relax-NG validity error\\s*:\\s*", "")
          .trim,
      )
      .filter(_.nonEmpty)
      .filterNot(_.matches(".*\\bfails to validate\\b.*"))
      .take(2)
      .mkString("; ")
      .take(400)
}
