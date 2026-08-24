// The `<input>` (or `<select>`) inside an `<fg-set>`, and the extension point applications add one
// through. An application-registered type arrives as `Input.custom`, whose name also names the
// template that renders it. Registrations are checked BEFORE the built-ins, so an application can
// replace a built-in type as well as add one.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.FormBuilderApp
import gov.irs.formbuilder.Log
import scala.xml.Node

case class HtmlOption(name: String, value: String, description: Option[String] = None)

/** `path` is the enclosing `<fg-set>`'s fact path, used only in error messages. */
case class InputContext(
    inputNode: Node,
    path: String,
    optional: Boolean,
    factDictionary: FactDictionary,
)

/** An input type an application registers under an `<input type="...">` value in [[FormBuilderApp.inputTypes]]. */
trait InputParser {
  def parse(context: InputContext): Input
}

enum Input {
  case select(options: List[HtmlOption], optionsPath: Option[String], optional: Boolean = false)
  case text(optional: Boolean = false)
  case int(optional: Boolean = false)
  case boolean(optional: Boolean = false, options: List[HtmlOption] = List.empty)
  case enumInput(options: List[HtmlOption], optionsPath: String, optional: Boolean = false)
  case multiEnumInput(options: List[HtmlOption], optionsPath: String, optional: Boolean = false)
  case dollar(optional: Boolean = false)
  case date(optional: Boolean = false)

  /** An input type an application registered.
    *
    * @param name
    *   the `<input type="...">` value, which also names the template at `nodes/inputs/{name}.html`
    * @param templateVariables
    *   values for the registered template, passed through with their Scala types intact
    * @param nodeType
    *   the Fact Graph node type to check against, or `None` to skip the check
    * @param suppliesOwnLabel
    *   the template renders its own label, so `fg-set` must not put one in front of it
    */
  case custom(
      name: String,
      optional: Boolean = false,
      templateVariables: Map[String, Any] = Map.empty,
      nodeType: Option[String] = None,
      suppliesOwnLabel: Boolean = false,
  )

  def typeString: String = this match {
    case Input.text(_)                  => "text"
    case Input.int(_)                   => "int"
    case Input.boolean(_, _)            => "boolean"
    case Input.enumInput(_, _, _)       => "enum"
    case Input.multiEnumInput(_, _, _)  => "multi-enum"
    case Input.dollar(_)                => "dollar"
    case Input.select(_, _, _)          => "select"
    case Input.date(_)                  => "date"
    case Input.custom(name, _, _, _, _) => name
  }

  /** Optional means a `<Placeholder>` on the fact it binds to. */
  def isOptional: Boolean = this match {
    case Input.text(o)                 => o
    case Input.int(o)                  => o
    case Input.boolean(o, _)           => o
    case Input.enumInput(_, _, o)      => o
    case Input.multiEnumInput(_, _, o) => o
    case Input.dollar(o)               => o
    case Input.select(_, _, o)         => o
    case Input.date(o)                 => o
    case Input.custom(_, o, _, _, _)   => o
  }

  /** `None` when the input does not care. */
  def expectedNodeType: Option[String] = this match {
    case Input.text(_)                      => Some("StringNode")
    case Input.int(_)                       => Some("IntNode")
    case Input.boolean(_, _)                => Some("BooleanNode")
    case Input.dollar(_)                    => Some("DollarNode")
    case Input.date(_)                      => Some("DayNode")
    case Input.select(_, _, _)              => Some("EnumNode")
    case Input.enumInput(_, _, _)           => Some("EnumNode")
    case Input.multiEnumInput(_, _, _)      => Some("MultiEnumNode")
    case Input.custom(_, _, _, nodeType, _) => nodeType
  }

  /** True for the built-ins that wrap their options in a `<fieldset>` with the question as its `<legend>`. A registered
    * input answers for itself through `suppliesOwnLabel`.
    */
  def usesFieldset: Boolean = this match {
    case Input.boolean(_, _)                        => true
    case Input.date(_)                              => true
    case Input.enumInput(_, _, _)                   => true
    case Input.multiEnumInput(_, _, _)              => true
    case Input.custom(_, _, _, _, suppliesOwnLabel) => suppliesOwnLabel
    case _                                          => false
  }
}

object Input {
  def extractFromFgSet(
      node: xml.Node,
      isOptional: Boolean,
      factDictionary: FactDictionary,
      app: FormBuilderApp,
  ): Input = {
    val path = node \@ "path"

    // Handled here rather than as an `<input type="select">`.
    val selectNode = node \ "select"
    if (selectNode.nonEmpty) {
      val optionsPath = Option(selectNode \@ "options-path").filter(_.nonEmpty)
      val options = (selectNode \ "option").map { node =>
        val name = node.text
        var value = node \@ "value"
        if (value == "") value = name

        HtmlOption(name, value)
      }.toList
      // TODO validate that the options match the num path

      if (options.isEmpty) {
        Log.warn(s"Empty options for fg-set: $path")
      }
      return Input.select(options, optionsPath, isOptional)
    }

    val inputNode = node \ "input"
    if (inputNode.isEmpty) {
      throw InvalidFormConfig(s"Missing an input for question $path")
    }

    val typeString = inputNode \@ "type"

    // Before the built-ins, so registering an existing name replaces that type.
    app.inputTypes.get(typeString) match {
      case Some(parser) =>
        return parser.parse(InputContext(inputNode.head, path, isOptional, factDictionary))
      case None => ()
    }

    typeString match {
      case "text"    => Input.text(isOptional)
      case "int"     => Input.int(isOptional)
      case "boolean" =>
        val options = (inputNode \ "option").map { node =>
          val name = node.mkString.strip
          val value = node \@ "value"

          if (value != "true" && value != "false") {
            throw InvalidFormConfig(s"Boolean option must have value 'true' or 'false', got '$value' at path $path")
          }
          HtmlOption(name, value)
        }.toList

        Input.boolean(isOptional, options)
      case "enum" =>
        val optionsPath = inputNode \@ "optionsPath"
        val options = (inputNode \ "option").map { node =>
          val name = node.text
          val value = node \@ "value"
          val finalValue = if (value.isEmpty) name else value
          val descriptionKey = node \@ "description-key"
          val description = if (descriptionKey.nonEmpty) Some(descriptionKey) else None
          HtmlOption(name, finalValue, description)
        }.toList
        Input.enumInput(options, optionsPath, isOptional)
      case "multi-enum" =>
        val optionsPath = inputNode \@ "optionsPath"
        val options = (inputNode \ "option").map { node =>
          val name = node.text
          val value = node \@ "value"
          val finalValue = if (value.isEmpty) name else value
          val descriptionKey = node \@ "description-key"
          val description = if (descriptionKey.nonEmpty) Some(descriptionKey) else None
          HtmlOption(name, finalValue, description)
        }.toList
        Input.multiEnumInput(options, optionsPath, isOptional)
      case "dollar" => Input.dollar(isOptional)
      case "date"   => Input.date(isOptional)
      case x        => throw InvalidFormConfig(s"Unexpected input type \"$x\" for question $path")
    }
  }
}
