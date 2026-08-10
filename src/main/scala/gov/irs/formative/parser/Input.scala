package gov.irs.formative.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.FormativeApp
import gov.irs.formative.Log
import scala.xml.Node

case class HtmlOption(name: String, value: String, description: Option[String] = None)

/** Everything an input parser is given about the `<input>` it is looking at.
  *
  * @param inputNode
  *   the `<input>` element itself, so a parser can read its own attributes
  * @param path
  *   the enclosing `<fg-set>`'s fact path — only ever used to make errors say where they came from
  */
case class InputContext(
    inputNode: Node,
    path: String,
    optional: Boolean,
    factDictionary: FactDictionary,
)

/** How an app teaches the scaffold an input type it does not ship.
  *
  * Registered by `<input type="...">` value in [[FormativeApp.inputTypes]] and merged over the built-ins, so an app can
  * add a type or reshape one. A parser normally returns [[Input.custom]], whose `typeString` also names the template
  * that renders it (`nodes/inputs/{typeString}.html`) — which the app supplies through the same app-first resolution
  * that serves its overrides.
  */
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

  /** An input type an app registered.
    *
    * The scaffold does not know what it means; it knows its name, whether it is optional, and whatever the app's parser
    * chose to carry in `templateVariables` for its template to read. The `Input` enum is sealed — an app cannot add a
    * case to it — so this is the case that makes the enum extensible without making it open.
    *
    * `nodeType` is how a custom input still gets the fact-type check every built-in gets: name the Fact Graph node type
    * it binds to (`"BooleanNode"`), or leave it `None` to opt out.
    *
    * `suppliesOwnLabel` says the template renders its own label — a fieldset with a legend, or a checkbox with its
    * label beside it — so `fg-set` should not put a `<label>` in front of it.
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

  /** Whether this input is declared optional — a `<Placeholder>` on the fact it binds to. */
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

  /** The Fact Graph node type this input must bind to, or `None` when it does not care. */
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

  /** Whether the input's own template supplies the question label, so `fg-set` must not render one in front of it.
    *
    * True for the inputs that wrap their options in a `<fieldset>` with the question as its `<legend>`; a registered
    * input says so for itself, because a checkbox with the question beside it needs the same suppression without being
    * a fieldset at all.
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
      app: FormativeApp,
  ): Input = {
    val path = node \@ "path"

    // Handle the <select> as a special case
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

    // Otherwise parse the <input>
    val inputNode = node \ "input"
    if (inputNode.isEmpty) {
      throw InvalidFormConfig(s"Missing an input for question $path")
    }

    val typeString = inputNode \@ "type"

    // An app's registrations win over the built-ins, so registering "date" reshapes the scaffold's own date input
    // rather than sitting alongside it.
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
