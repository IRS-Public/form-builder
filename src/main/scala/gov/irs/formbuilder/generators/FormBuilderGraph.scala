// Emits the Form Graph Model, the JSON Fact Explorer renders as an interactive graph.
//
// Reads the resolved flow XML rather than the parsed FlowNode tree, because each element's own source
// XML is part of the output and the parsed nodes do not retain it. The shape is a contract with the
// consumer's validator. Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.generators

import gov.irs.formbuilder.parser.Flow
import gov.irs.formbuilder.FormBuilderApp
import io.circe.Json
import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq

/** Four slices: `flowPages`, `flowElements`, `facts` and `edges`.
  *
  * `flowTags` declares exactly the node types the application registered. The consumer accepts those and no others, so
  * a misspelled tag is an error on both sides rather than a dropped element.
  */
object FormBuilderGraph {

  /** An application-registered tag goes through the generic branch instead, and into `flowTags`. */
  private val BuiltInTags = Set("fg-set", "fg-alert", "fg-collection", "fg-detail")

  private val GenericAttrs = Set("path", "condition", "operator", "if-true", "if-false")

  private def str(s: String): Json = Json.fromString(s)
  private def optStr(s: Option[String]): Json = s.map(str).getOrElse(Json.Null)
  private def attr(n: Node, name: String): Option[String] =
    n.attribute(name).map(_.text).filter(_.nonEmpty)

  private def rawXml(n: Node): String = n.toString

  private def factId(path: String): String = s"fact:$path"

  private def slug(s: String): String =
    s.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")

  private def factBasename(path: Option[String]): String =
    path.map(_.split("/").filter(_.nonEmpty).lastOption.getOrElse("fact")).getOrElse("node")

  /** `/a/b` plus `../c` gives `/a/c`. */
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

  private case class FactNode(path: String, json: Json, deps: List[String], rootOp: Option[String])

  /** Last definition per path wins. Dictionaries redefine a path on purpose, one constants file per tax year, and
    * emitting both would give two nodes the same `fact:/…` id.
    */
  private def factsFrom(dictionaryXml: Elem, app: FormBuilderApp): List[FactNode] = {
    val all = (dictionaryXml \\ "Fact").toList.flatMap { fact =>
      attr(fact, "path").map { path =>
        val derived = (fact \ "Derived").headOption
        val writable = (fact \ "Writable").headOption
        val kind = if (derived.isDefined) "derived" else "writable"

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
    // First-seen order, so the output is stable.
    val lastByPath = all.groupBy(_.path).view.mapValues(_.last).toMap
    all.map(_.path).distinct.map(lastByPath)
  }

  private case class ElementNode(id: String, json: Json, tag: String, factPath: Option[String])

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
        // Read the attributes every flow node shares and keep the rest verbatim.
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

  /** Reads no disk, so a spec can hand it inline XML. `flowConfig` is the source of every element and its `rawXml`;
    * `flow` is the source of page routes, title keys and module grouping.
    */
  def buildJson(flowConfig: Elem, dictionaryXml: Elem, flow: Flow, app: FormBuilderApp): Json = {
    val facts = factsFrom(dictionaryXml, app)
    val factPaths = facts.map(_.path).toSet
    val edges = new EdgeBuilder

    val used = scala.collection.mutable.Set.empty[String]
    val pageXmlByRoute = (flowConfig \ "page").map(p => (p \@ "route") -> p).toMap

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

        el.factPath.filter(factPaths.contains).foreach(p => edges.add("binds", el.id, factId(p)))

        val elGate = el.json.hcursor.downField("gate").downField("factPath").as[String].toOption
        elGate.filter(factPaths.contains).foreach(p => edges.add("gates", el.id, factId(p)))

        val cond = el.json.hcursor.downField("condition").downField("factPath").as[String].toOption
        val isKnockout =
          el.json.hcursor.downField("alert").downField("knockout").as[Boolean].toOption.contains(true)
        cond.filter(factPaths.contains).foreach { p =>
          edges.add(if (isKnockout) "knocks-out" else "displays", el.id, factId(p))
        }
      }

      // Page order is chained through each page's first element.
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
      "flowTags" -> Json.fromValues(app.nodeTypes.keySet.toList.sorted.map(str)),
      "flowPages" -> Json.fromValues(pageJson.toList),
      "flowElements" -> Json.fromValues(elementJson.toList),
      "facts" -> Json.fromValues(facts.map(_.json)),
      "edges" -> Json.fromValues(edges.result),
    )
  }
}
