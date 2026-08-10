package gov.irs.formative.authoring

import io.circe.*
import io.circe.syntax.*
import scala.xml.{ Elem, MetaData, Node, Null, Text, TopScope, UnprefixedAttribute }

/** A generic, structure-preserving mirror of a fact-graph computation subtree (the child of a `<Derived>`,
  * `<Placeholder>`, `<Default>`, `<Condition>`, `<When>`, `<Then>`, a named slot like `<Minuend>`, etc.).
  *
  * The whole reason a single generic model is sufficient — rather than 76 hand-written per-node serializers — is that
  * fact-graph's XML→config layer is itself generic: `gov.irs.factgraph.definitions.fact.CompNodeConfig.fromXml` does
  * `typeName = node.label`, recurses over element children, and turns every attribute + the element text into
  * `options`. There is *no* per-node-type parse logic at the XML boundary (typing happens later, in each
  * `fromDerivedConfig`). So any node's XML can be captured as `(tag, attrs, text, children)` and rendered back
  * losslessly; type-correctness of the result is enforced downstream by re-running `FactDictionary.fromXml` +
  * `xmllint --relaxng`, exactly as the build does.
  *
  * @param tag
  *   the element name (== the CompNode `typeName`), e.g. "Add", "Dependency", "Dollar".
  * @param attrs
  *   element attributes, sorted by name for deterministic output (e.g. Dependency `path`, Enum `optionsPath`, Paste
  *   `sep`, Find `path`).
  * @param text
  *   leaf text content (present only for text-bearing leaves: Dollar/Int/Rational/Day/Days/String/ Enum/TaxYear).
  *   Captured only when the node has no element children, matching the leaf/container split fact-graph itself relies
  *   on.
  * @param children
  *   ordered child computation nodes.
  */
final case class DerivedNode(
    tag: String,
    attrs: List[(String, String)],
    text: Option[String],
    children: List[DerivedNode],
)

object DerivedXml {

  // ── XML → DerivedNode ─────────────────────────────────────────────────────────────────────

  /** Parse an element into a [[DerivedNode]]. Mirrors `CompNodeConfig.fromXml`: comments and whitespace-only text nodes
    * are dropped; attributes are sorted for determinism; leaf text is kept only when there are no element children.
    */
  def parse(elem: Elem): DerivedNode = {
    val childElems = elem.child.collect { case e: Elem => e }.toList
    val attrs = elem.attributes.asAttrMap.toList.sortBy(_._1)
    val text =
      if (childElems.nonEmpty) None
      else {
        val direct = elem.child.collect { case t: Text => t.data }.mkString.trim
        if (direct.isEmpty) None else Some(direct)
      }
    DerivedNode(elem.label, attrs, text, childElems.map(parse))
  }

  // ── DerivedNode → XML ─────────────────────────────────────────────────────────────────────

  /** Render a [[DerivedNode]] back to a `scala.xml.Elem`. `scala.xml` escapes `& < >` in both attribute values and
    * text, so no manual escaping is needed. Emitted XML is subsequently run through `xmllint --format` (matching
    * `make format`) by the caller.
    */
  def render(node: DerivedNode): Elem = {
    val metadata: MetaData =
      node.attrs.foldRight(Null: MetaData) { case ((k, v), acc) =>
        new UnprefixedAttribute(k, v, acc)
      }
    val kids: Seq[Node] =
      if (node.children.nonEmpty) node.children.map(render)
      else node.text.map(t => Text(t)).toSeq
    Elem(null, node.tag, metadata, TopScope, minimizeEmpty = true, kids*)
  }

  // ── DerivedNode ⇄ JSON (the editor wire format) ────────────────────────────────────────────

  def toJson(node: DerivedNode): Json =
    Json.obj(
      "tag" -> node.tag.asJson,
      "attrs" -> Json.fromValues(node.attrs.map { case (k, v) => Json.arr(k.asJson, v.asJson) }),
      "text" -> node.text.map(Json.fromString).getOrElse(Json.Null),
      "children" -> Json.fromValues(node.children.map(toJson)),
    )

  /** Build a [[DerivedNode]] from the editor's JSON. Tolerant of missing `attrs`/`text`/`children`. Throws
    * `IllegalArgumentException` if `tag` is absent or empty, so a malformed payload surfaces as a validation error
    * rather than a silently-empty tree.
    */
  def fromJson(json: Json): DerivedNode = {
    val c = json.hcursor
    val tag = c.get[String]("tag").toOption.map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalArgumentException("Each calculation node needs a type.")
    }
    val attrs = c.downField("attrs").as[List[List[String]]].getOrElse(Nil).collect {
      case k :: v :: _ if k.trim.nonEmpty => (k.trim, v)
    }
    val text = c.get[String]("text").toOption.map(_.trim).filter(_.nonEmpty)
    val children = c.downField("children").values.map(_.toList).getOrElse(Nil).map(fromJson)
    DerivedNode(tag, attrs, text, children)
  }
}
