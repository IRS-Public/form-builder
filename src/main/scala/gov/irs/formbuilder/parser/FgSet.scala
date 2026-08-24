// `<fg-set>`: one question, binding a fact path to an input.
//
// Two things come from the dictionary rather than the flow. Optionality is the presence of a
// <Placeholder> on the fact, and expectedNodeType is checked against the fact's type here rather
// than inside each input, so a registered input type gets the same check as a built-in one.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.factgraph.Path
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.Utils.validateFact
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.jdk.CollectionConverters.IterableHasAsJava
import scala.xml.Elem

case class ThymeleafOption(name: String, value: String, description: String)

/** A `<hint>` or `<modal-link>`, minus its text, which the template reads back through `#{...}`. */
case class Hint(conditionPath: String, conditionOperator: String)
case class ModalLink(conditionPath: String, conditionOperator: String)

case class FgSet(
    path: String,
    condition: Option[Condition],
    input: Input,
    optional: Boolean,
    hint: Option[Hint],
    modalLink: Option[ModalLink],
    translationContext: TranslationContext,
) extends FlowNode {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val usesFieldset = input.usesFieldset

    val context = new Context()
    context.setVariable("path", this.path)
    context.setVariable("condition", this.condition.map(_.path).orNull)
    context.setVariable("operator", this.condition.map(_.operator.toString).orNull)
    context.setVariable("typeString", input.typeString)
    context.setVariable("optional", optional)
    context.setVariable("usesFieldset", usesFieldset)
    val contentKey = translationContext.fullKey()
    context.setVariable("contentKey", contentKey)
    context.setVariable("hint", hint.orNull)
    context.setVariable("modalLink", modalLink.orNull)
    context.setVariable("hintId", if (hint.nonEmpty) s"$path-hint" else null)

    input match {
      case Input.select(options, optionsPath, _) =>
        context.setVariable("options", options.asJava)
        context.setVariable("optionsPath", optionsPath)
      case Input.enumInput(options, optionsPath, _) =>
        val javaOptions = options.map { opt =>
          ThymeleafOption(opt.name, opt.value, opt.description.orNull)
        }
        context.setVariable("options", javaOptions.asJava)
        context.setVariable("optionsPath", optionsPath)
      case Input.multiEnumInput(options, optionsPath, _) =>
        val javaOptions = options.map { opt =>
          ThymeleafOption(opt.name, opt.value, opt.description.orNull)
        }
        context.setVariable("options", javaOptions.asJava)
        context.setVariable("optionsPath", optionsPath)
      case Input.custom(_, _, templateVariables, _, _) =>
        // Values pass through with their Scala types intact, so a custom template can do arithmetic on a number.
        templateVariables.foreach { case (name, value) => context.setVariable(name, value) }
      case Input.boolean(_, options) =>
        if (options.nonEmpty) {
          val trueOption = options.find(_.value == "true")
          val falseOption = options.find(_.value == "false")
          context.setVariable("trueLabel", trueOption.map(_.name).orNull)
          context.setVariable("falseLabel", falseOption.map(_.name).orNull)
        }

      case _ =>
    }

    templateEngine.process("nodes/fg-set", context)
  }
}

case class FgSetOption(
    value: String,
    name: String,
    description: Option[String],
)

object FgSet extends FlowNodeParser {
  override def fromXml(
      fgSetElement: Elem,
      flowParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): FgSet = {
    val factDictionary = flowParser.factDictionary
    val path = fgSetElement \@ "path"
    if (path.isEmpty) {
      throw InvalidFormConfig("fg-set attribute `path` is required but was missing or empty")
    }
    validateFact(path, factDictionary)

    val factDefinitionNode = factDictionary.getDefinitionsAsNodes()(Path(path))
    val isOptional = (factDefinitionNode \ "Placeholder").nonEmpty

    val input = Input.extractFromFgSet(fgSetElement, isOptional, factDictionary, flowParser.app)
    val typeNode = factDictionary.getDefinition(path).typeNode
    if (input.expectedNodeType.exists(_ != typeNode)) {
      throw InvalidFormConfig(s"Path $path must be of type $input")
    }

    // .child.mkString rather than .text, to preserve markup in mixed content.
    val question = (fgSetElement \ "question").head.child.mkString.strip
    if (question.isEmpty) {
      throw InvalidFormConfig(s"fg-set at path: $path has an empty question tag. This is required.")
    }

    val condition = Condition.getCondition(fgSetElement, factDictionary)

    val translationContext = parentTranslationContext.forChildWithId(path)
    translationContext.updateValue("question", question)

    def conditionOf(node: xml.Node): Option[Condition] = {
      val conditionPath = node \@ "condition"
      val conditionOperator = node \@ "operator"
      Option.when(conditionPath.nonEmpty && conditionOperator.nonEmpty)(
        Condition(conditionPath, ConditionOperator.fromAttribute(conditionOperator)),
      )
    }

    val hint = (fgSetElement \ "hint").headOption.map { node =>
      translationContext.updateValue("hint", node.child.mkString.strip)
      val hintCondition = conditionOf(node)
      Hint(hintCondition.map(_.path).orNull, hintCondition.map(_.operator.toString).orNull)
    }
    val modalLink = (fgSetElement \ "modal-link").headOption.map { node =>
      translationContext.updateValue("modalLink", node.toString.strip)
      val linkCondition = conditionOf(node)
      ModalLink(linkCondition.map(_.path).orNull, linkCondition.map(_.operator.toString).orNull)
    }

    val options = (fgSetElement \\ "option").map { option =>
      val value = option \@ "value"
      val name = option.head.child.mkString.strip
      val description = option \@ "description-key"
      val descriptionValue = Option(description).filter(_.nonEmpty)
      FgSetOption(value, name, descriptionValue)
    }

    if (options.nonEmpty) {
      val optionsContext = translationContext.forChildWithId("options")
      options.foreach(option => {
        val specificOptionContext = optionsContext.forChildWithId(option.value)
        specificOptionContext.updateValue("name", option.name)
        if (option.description.isDefined) specificOptionContext.updateValue("description", option.description.get)
      })
    }

    FgSet(path, condition, input, isOptional, hint, modalLink, translationContext)
  }
}
