package gov.irs.formbuilder.generators

import gov.irs.formbuilder.parser.Flow
import gov.irs.formbuilder.FormBuilderApp
import io.circe.Json
import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq

/** Emits the **Form Graph Model** — the JSON Fact Explorer renders as an interactive graph.
  *
  * Four slices, and one contract:
  *   - `flowPages` — one per rendered page (route, title, source module, the ids of its elements)
  *   - `flowElements` — every `fg-*` element, with the fact it binds, the condition that gates it, and its own source
  *     XML for the explain popup
  *   - `facts` — every `<Fact>` in the dictionary, writable or derived, with its resolved dependency paths
  *   - `edges` — typed, cross-layer: `sequential`, `binds`, `gates`, `knocks-out`, `displays`, `depends`
  *
  * The consumer validates this shape (`fact-explorer/src/model/fgm.js`), so a change here is a change to a contract two
  * repos share. In particular `flowTags` is the *declared* extension point: an app that registers its own node types
  * via [[FormBuilderApp.nodeTypes]] has them listed there, and Fact Explorer accepts exactly those and no others — an
  * undeclared tag is still an error on both sides, which is what makes a typo'd tag findable.
  *
  * ==Why this reads XML rather than the parsed Flow==
  *
  * It takes both. `flowPages` comes from the parsed [[Flow]], which already knows each page's route, title key and
  * module. The *elements* are walked out of the resolved flow XML instead, because the parsed `FlowNode` case classes
  * do not retain their source `Elem` — and `rawXml` is the whole point of the explain popup. Adding a `sourceXml` field
  * to every node type would break the `FlowNodeParser` SPI that apps extend (TWE registers one), so this stays a pure
  * reader: it cannot regress site generation, and an app's custom element is handled by the same generic branch that
  * handles anything else this library has never heard of.
  *
  * ==Not yet at parity with the Node generator — do not switch apps over by default==
  *
  * Against tax-withholding-estimator this agrees with `fact-explorer/scripts/make-static-fgm.mjs` exactly on
  * `flowPages` (7), `flowElements` (157) and `facts` (1010), and on the `gates` edges. It does **not** yet emit three
  * things that generator does:
  *
  *   - `shows` edges, from the `<fg-show path="…"/>` references inside question and heading text
  *   - `exits` edges
  *   - the `displays` edges that come from those same `fg-show` paths (3 here versus 81)
  *
  * so its `depends` count also runs lower. Until those land, an app should reach this through its own
  * `make fact-explorer` target rather than through `make dev` — `load.js` prefers this file over the Node output
  * wherever it is served, so building with `--formBuilderGraph` by default would quietly hand Fact Explorer the sparser
  * graph. That preference is still the right long-term order (this comes from the parser that actually builds the
  * site); the gap is the reason the consumer's `overlay` mode exists.
  */
object FormBuilderGraph {

  /** Elements the scaffold knows how to read in detail. Anything else that an app registered is emitted through the
    * generic branch and declared in `flowTags`.
    */
  private val BuiltInTags = Set("fg-set", "fg-alert", "fg-collection", "fg-detail")

  private val GenericAttrs = Set("path", "condition", "operator", "if-true", "if-false")

  private def str(s: String): Json = Json.fromString(s)
  private def optStr(s: Option[String]): Json = s.map(str).getOrElse(Json.Null)
  private def attr(n: Node, name: String): Option[String] =
    n.attribute(name).map(_.text).filter(_.nonEmpty)

  /** Serialize one element without its children's whitespace exploding — the popup renders this verbatim. */
  private def rawXml(n: Node): String = n.toString

  private def factId(path: String): String = s"fact:$path"

  private def slug(s: String): String =
    s.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

  private def factBasename(path: Option[String]): String =
    path.map(_.split("/").filter(_.nonEmpty).lastOption.getOrElse("fact")).getOrElse("node")

  /** `/a/b` + `../c` → `/a/c`. The one algorithm shared with the Node generator. */
  private[generators] def resolveDependencyPath(raw: String, factPath: String): String =
    if (raw.startsWith("/")) raw
    else {
      val base = factPath.split("/").filter(_.nonEmpty).dropRight(1).toBuffer
      raw.split("/").filter(_.nonEmpty).foreach {
        case ".."    => if (base.nonEmpty) base.remove(base.length - 1)
        case "."     => ()
        case segment => base.append(segment)
      }
      "/" + base.mkString("/")
    }

  // ---------------------------------------------------------------------------------------------
  // Facts
  // ---------------------------------------------------------------------------------------------

  private case class FactNode(path: String, json: Json, deps: List[String], rootOp: Option[String])

  /** Every `<Fact>` in the merged dictionary, **last definition per path wins**.
    *
    * The de-duplication is not a nicety. A real dictionary defines the same path more than once on purpose — an app
    * with `constants.xml` alongside `constants2025.xml` redefines each constant per tax year, and the loader resolves
    * that by order. Without this the graph carries two nodes with the same `fact:/…` id, which the consumer's
    * `validate()` rejects outright as a duplicate node id.
    */
  private def factsFrom(dictionaryXml: Elem, app: FormBuilderApp): List[FactNode] = {
    val all = (dictionaryXml \\ "Fact").toList.flatMap { fact =>
      attr(fact, "path").map { path =>
        val derived = (fact \ "Derived").headOption
        val writable = (fact \ "Writable").headOption
        val kind = if (derived.isDefined) "derived" else "writable"

        // The type is the first element under Writable, or the root operation under Derived.
        val rootOp = derived.flatMap(d => (d \ "_").headOption.map(_.label))
        val typeNode = writable.flatMap(w => (w \ "_").headOption.map(_.label)).orElse(rootOp)

        val deps = (fact \\ "Dependency").toList
          .flatMap(d => attr(d, "path"))
          .map(raw => (raw, resolveDependencyPath(raw, path)))

        val json = Json.obj(
          "id" -> str(factId(path)),
          "path" -> str(path),
          "name" -> str(path.split("/").filter(_.nonEmpty).lastOption.getOrElse(path)),
          "description" -> optStr((fact \ "Description").headOption.map(_.text.trim)),
          "kind" -> str(kind),
          "typeNode" -> optStr(typeNode),
          "taxYear" -> Json.Null,
          "dependencyPaths" -> Json.fromValues(deps.map { case (raw, resolved) =>
            Json.obj(
              "raw" -> str(raw),
              "resolvedAbstract" -> str(resolved),
              "wildcard" -> Json.fromBoolean(raw.contains("*")),
            )
          }),
          "rawXml" -> str(rawXml(fact)),
        )
        FactNode(path, json, deps.map(_._2), rootOp)
      }
    }
    // Keep the last definition of each path, but in first-seen order so the output is stable.
    val lastByPath = all.groupBy(_.path).view.mapValues(_.last).toMap
    all.map(_.path).distinct.map(lastByPath)
  }

  // ---------------------------------------------------------------------------------------------
  // Flow
  // ---------------------------------------------------------------------------------------------

  private case class ElementNode(id: String, json: Json, tag: String, factPath: Option[String])

  /** Walk one page's XML, emitting an element per recognised tag and recursing into everything else. */
  private def walkPage(
      pageXml: Node,
      pageId: String,
      prefix: String,
      knownTags: Set[String],
      used: scala.collection.mutable.Set[String],
  ): List[ElementNode] = {
    val order = Iterator.from(0)
    val acc = scala.collection.mutable.ListBuffer.empty[ElementNode]

    def unique(base: String): String = {
      var id = base
      var i = 2
      while (used.contains(id)) { id = s"$base-$i"; i += 1 }
      used.add(id)
      id
    }

    def visit(nodes: NodeSeq, parentId: Option[String]): Unit =
      nodes.foreach {
        case el: Elem if knownTags.contains(el.label) =>
          val node = makeElement(el, pageId, prefix, parentId, order.next(), unique)
          acc.append(node)
          visit(el.child, Some(node.id))
        case el: Elem => visit(el.child, parentId)
        case _        => ()
      }

    visit(pageXml \ "_", None)
    acc.toList
  }

  private def makeElement(
      el: Elem,
      pageId: String,
      prefix: String,
      parentId: Option[String],
      order: Int,
      unique: String => String,
  ): ElementNode = {
    val tag = el.label
    val path = attr(el, "path")
    val gate = attr(el, "if-true")
      .map(p => ("if-true", p))
      .orElse(attr(el, "if-false").map(p => ("if-false", p)))

    val condition = attr(el, "condition").map(c => (c, attr(el, "operator")))

    val (id, extra) = tag match {
      case "fg-set" =>
        val input = (el \ "input").headOption
        (
          unique(s"$prefix:fg-set:${factBasename(path)}"),
          List(
            "inputType" -> optStr(input.flatMap(i => attr(i, "type"))),
            "optionsPath" -> optStr(input.flatMap(i => attr(i, "options-path"))),
            "questionText" -> optStr((el \ "question").headOption.map(_.text.trim.replaceAll("\\s+", " "))),
            "modalLinkId" -> optStr((el \ "modal-link").headOption.flatMap(m => attr(m, "for"))),
          ),
        )
      case "fg-alert" =>
        val key = attr(el, "alert-key").getOrElse("alert")
        (
          unique(s"$prefix:fg-alert:$key"),
          List(
            "alert" -> Json.obj(
              "alertType" -> optStr(attr(el, "alert-type")),
              "alertKey" -> optStr(attr(el, "alert-key")),
              "knockout" -> Json.fromBoolean(attr(el, "knockout").contains("true")),
            ),
            "headingText" -> optStr((el \ "heading").headOption.map(_.text.trim.replaceAll("\\s+", " "))),
          ),
        )
      case "fg-collection" =>
        (
          unique(s"$prefix:fg-collection:${factBasename(path)}"),
          List(
            "collection" -> Json.obj(
              "itemName" -> optStr(attr(el, "item-name")),
              "determiner" -> optStr(attr(el, "determiner")),
              "addItemIfTrue" -> optStr(attr(el, "add-item-if-true")),
            ),
          ),
        )
      case "fg-detail" =>
        val heading = (el \ "summary").headOption.map(_.text.trim.replaceAll("\\s+", " "))
        (
          unique(s"$prefix:fg-detail:${heading.map(slug).filter(_.nonEmpty).getOrElse("detail")}"),
          List("headingText" -> optStr(heading)),
        )
      case _ =>
        // An app-registered node type. Read only the vocabulary every flow node shares and keep the
        // rest verbatim — better a labelled box with its real XML behind it than a dropped element.
        val attrs = el.attributes.asAttrMap.filterNot { case (k, _) => GenericAttrs.contains(k) }
        (
          unique(s"$prefix:$tag:${factBasename(path)}"),
          List(
            "attrs" -> Json.obj(attrs.toList.map { case (k, v) => k -> str(v) }*),
          ),
        )
    }

    val base = List(
      "id" -> str(id),
      "pageId" -> str(pageId),
      "tag" -> str(tag),
      "parentId" -> optStr(parentId),
      "order" -> Json.fromInt(order),
      "factPath" -> optStr(path),
      "gate" -> gate
        .map { case (kind, p) => Json.obj("kind" -> str(kind), "factPath" -> str(p)) }
        .getOrElse(Json.Null),
      "condition" -> condition
        .map { case (c, op) => Json.obj("factPath" -> str(c), "operator" -> optStr(op)) }
        .getOrElse(Json.Null),
      "rawXml" -> str(rawXml(el)),
    )

    ElementNode(id, Json.obj((base ++ extra)*), tag, path)
  }

  // ---------------------------------------------------------------------------------------------
  // Edges
  // ---------------------------------------------------------------------------------------------

  private class EdgeBuilder {
    private var n = 0
    private val acc = scala.collection.mutable.ListBuffer.empty[Json]

    def add(kind: String, source: String, target: String, extra: (String, Json)*): Unit = {
      n += 1
      val base = List(
        "id" -> str(s"e-$kind-$n"),
        "source" -> str(source),
        "target" -> str(target),
        "kind" -> str(kind),
      )
      acc.append(Json.obj((base ++ extra)*))
    }

    def result: List[Json] = acc.toList
  }

  // ---------------------------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------------------------

  /** Build the graph.
    *
    * Takes everything as arguments and reads no disk, so a spec can hand it inline XML the way `WebsiteSpec` does.
    *
    * @param flowConfig
    *   the resolved flow XML (`FormBuilder.resolvedFlowConfig`) — the source of every element and its `rawXml`
    * @param dictionaryXml
    *   the merged fact dictionary
    * @param flow
    *   the parsed flow — the source of page routes, titles and module grouping
    */
  def buildJson(flowConfig: Elem, dictionaryXml: Elem, flow: Flow, app: FormBuilderApp): Json = {
    val facts = factsFrom(dictionaryXml, app)
    val factPaths = facts.map(_.path).toSet
    val edges = new EdgeBuilder

    val used = scala.collection.mutable.Set.empty[String]
    val pageXmlByRoute = (flowConfig \ "page").map(p => (p \@ "route") -> p).toMap

    // Pages come from the parsed flow (routes, titles, module); elements from that page's XML.
    val pageJson = scala.collection.mutable.ListBuffer.empty[Json]
    val elementJson = scala.collection.mutable.ListBuffer.empty[Json]
    var previousPageLastId: Option[String] = None

    flow.pages.foreach { page =>
      val pageId = s"page:${slug(page.route).filter(_ != ' ')}".stripSuffix(":") match {
        case s if s == "page:" => "page:root"
        case s                 => s
      }
      val prefix = if (slug(page.route).isEmpty) "root" else slug(page.route)
      val xmlForPage = pageXmlByRoute.get(page.route)

      val elements = xmlForPage
        .map(px => walkPage(px, pageId, prefix, BuiltInTags ++ app.nodeTypes.keySet, used))
        .getOrElse(Nil)

      elements.foreach { el =>
        elementJson.append(el.json)

        // binds: the element ↔ the fact it writes or reads.
        el.factPath.filter(factPaths.contains).foreach(p => edges.add("binds", el.id, factId(p)))

        // gates / knocks-out / displays: the condition an element depends on.
        val elGate = el.json.hcursor.downField("gate").downField("factPath").as[String].toOption
        elGate.filter(factPaths.contains).foreach(p => edges.add("gates", el.id, factId(p)))

        val cond = el.json.hcursor.downField("condition").downField("factPath").as[String].toOption
        val isKnockout =
          el.json.hcursor.downField("alert").downField("knockout").as[Boolean].toOption.contains(true)
        cond.filter(factPaths.contains).foreach { p =>
          edges.add(if (isKnockout) "knocks-out" else "displays", el.id, factId(p))
        }
      }

      // sequential: page order, chained through each page's first element.
      elements.headOption.foreach { first =>
        previousPageLastId.foreach(prev => edges.add("sequential", prev, first.id))
      }
      elements.sliding(2).foreach {
        case Seq(a, b) => edges.add("sequential", a.id, b.id)
        case _         => ()
      }
      previousPageLastId = elements.lastOption.map(_.id)

      pageJson.append(
        Json.obj(
          "id" -> str(pageId),
          "route" -> str(page.route),
          "title" -> str(page.titleKey),
          "sourceFile" -> optStr(page.module),
          "elementIds" -> Json.fromValues(elements.map(e => str(e.id))),
        ),
      )
    }

    // depends: fact → fact, via the source fact's root operation.
    facts.foreach { f =>
      f.deps.filter(factPaths.contains).foreach { dep =>
        f.rootOp match {
          case Some(op) => edges.add("depends", factId(f.path), factId(dep), "via" -> str(op))
          case None     => edges.add("depends", factId(f.path), factId(dep))
        }
      }
    }

    Json.obj(
      "version" -> str("1.0-scala"),
      "generatedAt" -> str(java.time.Instant.now().toString),
      // Declared, not open — see the note on this object. Exactly the node types this app registered.
      "flowTags" -> Json.fromValues(app.nodeTypes.keySet.toList.sorted.map(str)),
      "flowPages" -> Json.fromValues(pageJson.toList),
      "flowElements" -> Json.fromValues(elementJson.toList),
      "facts" -> Json.fromValues(facts.map(_.json)),
      "edges" -> Json.fromValues(edges.result),
    )
  }
}
