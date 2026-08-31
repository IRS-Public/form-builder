// `<fg-set>`: one question, binding a fact path to an input.
//
// Two things come from the dictionary rather than the flow. Optionality is the presence of a
// <Placeholder> on the fact, and expectedNodeType is checked against the fact's type here rather
// than inside each input, so a registered input type gets the same check as a built-in one.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.Utils.validateFact
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.jdk.CollectionConverters.IterableHasAsJava
import scala.xml.Elem
import scala.xml.NodeSeq

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
    /** The DOM id the input templates build theirs from. Claimed at parse time; see [[FgSet.fromXml]]. */
    controlId: String,
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
    context.setVariable("controlId", controlId)
    context.setVariable("hintId", if (hint.nonEmpty) s"$controlId-hint" else null)

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

    // `getDefinitionsAsNodes` is keyed by the path a fact is *declared* at, and `path` need not be
    // that path: it may reach the fact through a derived collection-item alias, the way
    // `/primaryFiler/firstName` reaches `/filers/*/firstName`. `getDefinition` resolves those; the
    // raw map does not, and looking the path up in it directly throws a `NoSuchElementException`
    // several frames from anything naming the question. So ask the definition where it lives.
    val factDefinition = factDictionary.getDefinition(path)
    val factDefinitionNode = factDictionary.getDefinitionsAsNodes().getOrElse(factDefinition.path, NodeSeq.Empty)
    val isOptional = (factDefinitionNode \ "Placeholder").nonEmpty

    val input = Input.extractFromFgSet(fgSetElement, isOptional, factDictionary, flowParser.app)
    val typeNode = factDefinition.typeNode
    if (input.expectedNodeType.exists(_ != typeNode)) {
      throw InvalidFormConfig(s"Path $path must be of type $input")
    }

    // .child.mkString rather than .text, to preserve markup in mixed content.
    val question = (fgSetElement \ "question").head.child.mkString.strip
    if (question.isEmpty) {
      throw InvalidFormConfig(s"fg-set at path: $path has an empty question tag. This is required.")
    }

    val condition = Condition.getCondition(fgSetElement, factDictionary)

    // The path is the key, unless a sibling `<fg-set>` on the same fact already claimed it with
    // different words — see forChildWithId's overload for what happens then. The signature is the
    // element's content rather than its question alone, because two questions can read the same and
    // still differ in their options: `credits-and-deductions/credits/ctc-odc` asks one thing and
    // offers "No, this hasn't happened to me" or "…to us" depending on the filing status.
    val translationContext = parentTranslationContext.forChildWithId(path, fgSetElement.child.mkString)
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

    // What the input templates build their `id` and `name` from, and the reason it is not the path.
    //
    // A page may hold two `<fg-set>`s on one fact — two conditional branches asking the same
    // question, only one of which shows — and ids built from the path alone would then repeat,
    // putting every `<label for>` on the first copy and leaving the second's labels pointing into a
    // hidden input. So the id starts from the key this child claimed (`path`, or `path-<hash>` when
    // a differently-worded sibling already had it) and is then made unique within the page, which
    // covers the case where the two are worded identically and rightly share one translation.
    //
    // `name` follows the id rather than staying the path. Nothing reads it as a path — the runtime
    // and the workspace both find inputs by walking the `<fg-set>` — and the one thing it does
    // decide, which radios belong to one group, is exactly a question's worth of controls. The
    // `<fg-set>`, `<fg-apply>` and `<fg-collection>` elements still carry the path itself.
    //
    // Claimed here rather than in `html`, which runs once per locale and once more for Browse All.
    val controlId = parentTranslationContext.claimControlId(translationContext.localKey)

    FgSet(path, condition, input, isOptional, hint, modalLink, translationContext, controlId)
  }
}
