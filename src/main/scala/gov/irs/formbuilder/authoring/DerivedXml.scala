package gov.irs.formbuilder.authoring

import io.circe.*
import io.circe.syntax.*
import scala.xml.{ Elem, MetaData, Node, Null, Text, TopScope, UnprefixedAttribute }

/** A generic, structure-preserving mirror of a fact-graph computation subtree (the child of a `<Derived>`,
  * `<Placeholder>`, `<Condition>`, a named slot like `<Minuend>`, and so on), with codecs to XML and to the editor's
  * JSON wire format.
  *
  * One generic model covers every node type because fact-graph's XML boundary is itself generic:
  * `CompNodeConfig.fromXml` takes the element label as the type name and turns attributes plus text into options, with
  * no per-node parse logic. Typing happens later, so a round trip here is structure-preserving but not type-checked.
  * Correctness is enforced by re-running `FactDictionary.fromXml` and `xmllint --relaxng`, as the build does.
  *
  * See docs/internals/author-mode.md.
  *
  * @param tag
  *   the element name, which is also the CompNode `typeName` (e.g. "Add", "Dependency", "Dollar").
  * @param attrs
  *   element attributes, sorted by name for deterministic output.
  * @param text
  *   leaf text content, captured only when the node has no element children.
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

  /** Parse an element into a [[DerivedNode]], mirroring `CompNodeConfig.fromXml`: comments and whitespace-only text are
    * dropped, and leaf text is kept only when there are no element children.
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

  /** Render a [[DerivedNode]] back to a `scala.xml.Elem`. `scala.xml` escapes `& < >` in both attribute values and
    * text, so callers must not pre-escape. The caller runs `xmllint --format` over the spliced file afterwards.
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

  def toJson(node: DerivedNode): Json =
    Json.obj(
      "tag" -> node.tag.asJson,
      "attrs" -> Json.fromValues(node.attrs.map { case (k, v) => Json.arr(k.asJson, v.asJson) }),
      "text" -> node.text.map(Json.fromString).getOrElse(Json.Null),
      "children" -> Json.fromValues(node.children.map(toJson)),
    )

  /** Build a [[DerivedNode]] from the editor's JSON. Tolerant of missing `attrs`/`text`/`children`. Throws
    * `IllegalArgumentException` on an absent or empty `tag`, so a malformed payload surfaces as a validation error
    * rather than a silently empty tree.
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
