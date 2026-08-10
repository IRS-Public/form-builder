package gov.irs.formative.parser

import gov.irs.factgraph.Path
import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.parser.Utils.validateFact
import gov.irs.formative.FormativeTemplateEngine
import org.thymeleaf.context.Context
import scala.jdk.CollectionConverters.IterableHasAsJava
import scala.xml.Elem

case class ThymeleafOption(name: String, value: String, description: String)

/** A `<hint>` or `<modal-link>`, minus its text.
  *
  * The text is not here on purpose: it goes into the translation context under `{contentKey}.hint`, and the template
  * reads it back through `#{...}` so the translated string wins. What is left is the optional condition that decides
  * whether the hint is on screen at all, handed to the browser as `condition`/`operator` attributes for the same
  * runtime machinery every other conditional element uses.
  */
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
  override def html(templateEngine: FormativeTemplateEngine): String = {
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
        // Whatever the registered parser chose to hand its own template. Typed, not stringified, so a template can
        // do arithmetic on a number the same way it can for a built-in input.
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
    // Each input knows the Fact Graph node type it binds to, so a registered input type gets the same check as a
    // built-in one — and one that binds to nothing says so rather than being unrepresentable.
    if (input.expectedNodeType.exists(_ != typeNode)) {
      throw InvalidFormConfig(s"Path $path must be of type $input")
    }

    // Use .child.mkString instead of .text to preserve XML tags (e.g., <span>, <fg-show>) in mixed content
    val question = (fgSetElement \ "question").head.child.mkString.strip
    if (question.isEmpty) {
      throw InvalidFormConfig(s"fg-set at path: $path has an empty question tag. This is required.")
    }

    val condition = Condition.getCondition(fgSetElement, factDictionary)

    val translationContext = parentTranslationContext.forChildWithId(path)
    translationContext.updateValue("question", question)

    /** Read an optional `condition`/`operator` pair off an element, the same way [[Condition]] does for the elements
      * that carry one as a first-class attribute.
      */
    def conditionOf(node: xml.Node): Option[Condition] = {
      val conditionPath = node \@ "condition"
      val conditionOperator = node \@ "operator"
      Option.when(conditionPath.nonEmpty && conditionOperator.nonEmpty)(
        Condition(conditionPath, ConditionOperator.fromAttribute(conditionOperator)),
      )
    }

    // Both texts go into the translation context and are read back through `#{...}` by the template, so a hint is
    // translated like everything else. What survives on the node is only the condition.
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
