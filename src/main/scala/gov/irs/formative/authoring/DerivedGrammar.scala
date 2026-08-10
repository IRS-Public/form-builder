package gov.irs.formative.authoring

import io.circe.*
import io.circe.syntax.*

/** The editor-facing palette for the Derived computation-tree editor (T18).
  *
  * This is *UX metadata only*: it tells `author-mode.js` which node types are insertable, how to render each one (a
  * value input, a fact-path dropdown, an attribute field, or a container with children), and what child tags a
  * structural node expects. It is deliberately curated to the node types actually used in this codebase's fact
  * dictionaries (~40 of the 76 compnodes) plus the named "slot" wrappers (`Minuend`, `Left`, `When`, …).
  *
  * Crucially, this table is **not** the correctness authority — it never gates a save. A tree the author composes is
  * validated against the real `FactDictionaryModule.rng` + `FactDictionary.fromXml` before it is written (see
  * `AuthoringServer`), so an incomplete or slightly-too-permissive palette can only affect editor ergonomics, never
  * produce invalid XML on disk.
  */
object DerivedGrammar {

  /** How the editor renders a node.
    *   - `value` — a text-bearing leaf (edit its text); `valueKind` picks the input.
    *   - `empty` — no text, no children (True/False).
    *   - `dependency` — a `<Dependency>`: a fact-path dropdown, no children.
    *   - `container` — has ordered children; `childTags` (if non-empty) suggests what to insert.
    *   - `slot` — a named wrapper element that only appears inside a specific parent (Minuend, Left, When, …); rendered
    *     as a labeled container, not offered in the top palette.
    */
  final case class NodeSpec(
      tag: String,
      label: String,
      category: String,
      valueKind: Option[String] = None, // dollar | int | rational | day | days | string | enum
      attrs: List[AttrSpec] = Nil,
      childTags: List[String] = Nil, // suggested insertable child tags ([] = any value node)
      slotOnly: Boolean = false, // wrappers like Minuend/Left — not shown in the top-level palette
  )

  /** An attribute the editor must let the author set. `kind`: `text` | `factPath` | `optionsPath`. */
  final case class AttrSpec(name: String, label: String, kind: String)

  // Value-producing nodes an author can drop into a slot / list / Then / Left / Right, etc.
  private val valueNodes: List[NodeSpec] = List(
    NodeSpec("Dependency", "Another fact's value", "dependency", attrs = List(AttrSpec("path", "Fact", "factPath"))),
    NodeSpec("Dollar", "Dollar amount", "value", valueKind = Some("dollar")),
    NodeSpec("Int", "Whole number", "value", valueKind = Some("int")),
    NodeSpec("Rational", "Fraction (e.g. 31/250)", "value", valueKind = Some("rational")),
    NodeSpec("Day", "Date", "value", valueKind = Some("day")),
    NodeSpec("Days", "Number of days", "value", valueKind = Some("days")),
    NodeSpec("String", "Text", "value", valueKind = Some("string")),
    NodeSpec(
      "Enum",
      "Enum option",
      "value",
      valueKind = Some("enum"),
      attrs = List(AttrSpec("optionsPath", "Options fact", "optionsPath")),
    ),
    NodeSpec("True", "Always true", "empty"),
    NodeSpec("False", "Always false", "empty"),
  )

  // Operators / calculations.
  private val operatorNodes: List[NodeSpec] = List(
    NodeSpec("Add", "Add (sum of…)", "container"),
    NodeSpec("Subtract", "Subtract", "container", childTags = List("Minuend", "Subtrahends")),
    NodeSpec("Multiply", "Multiply (product of…)", "container"),
    NodeSpec("Divide", "Divide", "container", childTags = List("Dividend", "Divisors")),
    NodeSpec("StepwiseMultiply", "Stepwise multiply", "container", childTags = List("Multiplicand", "Rate")),
    NodeSpec("LesserOf", "Lesser of…", "container"),
    NodeSpec("GreaterOf", "Greater of…", "container"),
    NodeSpec("Minimum", "Minimum", "container"),
    NodeSpec("Maximum", "Maximum", "container"),
    NodeSpec("Round", "Round to nearest dollar", "container"),
    NodeSpec("RoundToInt", "Round to whole number", "container"),
    NodeSpec("TruncateCents", "Drop the cents", "container"),
    NodeSpec("Ceiling", "Round up", "container"),
    NodeSpec("Floor", "Round down", "container"),
    NodeSpec("CollectionSum", "Sum over a collection", "container"),
    NodeSpec("CollectionSize", "Count of a collection", "container"),
    NodeSpec("Count", "Count of true values", "container"),
    NodeSpec("Modulo", "Remainder (modulo)", "container"),
  )

  // Logic / comparison.
  private val logicNodes: List[NodeSpec] = List(
    NodeSpec("Switch", "Choose by case", "container", childTags = List("Case")),
    NodeSpec("All", "All of… (AND)", "container"),
    NodeSpec("Any", "Any of… (OR)", "container"),
    NodeSpec("Not", "Not", "container"),
    NodeSpec("Equal", "Equal", "container", childTags = List("Left", "Right")),
    NodeSpec("NotEqual", "Not equal", "container", childTags = List("Left", "Right")),
    NodeSpec("GreaterThan", "Greater than", "container", childTags = List("Left", "Right")),
    NodeSpec("GreaterThanOrEqual", "Greater than or equal", "container", childTags = List("Left", "Right")),
    NodeSpec("LessThan", "Less than", "container", childTags = List("Left", "Right")),
    NodeSpec("LessThanOrEqual", "Less than or equal", "container", childTags = List("Left", "Right")),
    NodeSpec("IsComplete", "Is answered/complete", "container"),
  )

  // Named-slot wrappers: containers that live only inside a specific parent. Not offered top-level.
  private val slotNodes: List[NodeSpec] = List(
    NodeSpec("Minuend", "Minuend (start value)", "slot", slotOnly = true),
    NodeSpec("Subtrahends", "Subtrahends (subtract these)", "slot", slotOnly = true),
    NodeSpec("Dividend", "Dividend (numerator)", "slot", slotOnly = true),
    NodeSpec("Divisors", "Divisors (denominator)", "slot", slotOnly = true),
    NodeSpec("Multiplicand", "Multiplicand", "slot", slotOnly = true),
    NodeSpec("Rate", "Rate", "slot", slotOnly = true),
    NodeSpec("Left", "Left", "slot", slotOnly = true),
    NodeSpec("Right", "Right", "slot", slotOnly = true),
    NodeSpec("Case", "Case", "slot", slotOnly = true, childTags = List("When", "Then")),
    NodeSpec("When", "When (condition)", "slot", slotOnly = true),
    NodeSpec("Then", "Then (result)", "slot", slotOnly = true),
    NodeSpec("Condition", "Condition", "slot", slotOnly = true),
    NodeSpec("Default", "Default", "slot", slotOnly = true),
  )

  val specs: List[NodeSpec] = valueNodes ++ operatorNodes ++ logicNodes ++ slotNodes

  private val byTag: Map[String, NodeSpec] = specs.map(s => s.tag -> s).toMap
  def specFor(tag: String): Option[NodeSpec] = byTag.get(tag)

  private def attrJson(a: AttrSpec): Json =
    Json.obj("name" -> a.name.asJson, "label" -> a.label.asJson, "kind" -> a.kind.asJson)

  private def specJson(s: NodeSpec): Json =
    Json.obj(
      "tag" -> s.tag.asJson,
      "label" -> s.label.asJson,
      "category" -> s.category.asJson,
      "valueKind" -> s.valueKind.map(Json.fromString).getOrElse(Json.Null),
      "attrs" -> Json.fromValues(s.attrs.map(attrJson)),
      "childTags" -> s.childTags.asJson,
      "slotOnly" -> Json.fromBoolean(s.slotOnly),
    )

  /** The palette payload the editor consumes (`GET /author/derived`). */
  def paletteJson: Json = Json.fromValues(specs.map(specJson))
}
