package gov.irs.formative.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.parser.Condition
import gov.irs.formative.parser.Utils.optionString
import gov.irs.formative.parser.Utils.validateFact
import gov.irs.formative.FormativeTemplateEngine
import org.thymeleaf.context.Context
import scala.xml.Elem

case class FgCollection(
    path: String,
    itemName: String,
    disallowEmpty: String,
    condition: Option[Condition],
    translationContext: TranslationContext,
    children: Seq[FlowNode],
    determiner: String,
    addItemIfTrue: Option[String],
    seedItemIfTrue: Option[String],
) extends FlowNode {
  def html(templateEngine: FormativeTemplateEngine): String = {
    val context = new Context()
    context.setVariable("path", path)
    val translationKeyBase = translationContext.fullKey()
    context.setVariable("disallowEmpty", disallowEmpty)
    val childrenHtml = children.html(templateEngine)
    context.setVariable("collectionFacts", childrenHtml)
    context.setVariable("condition", condition.map(_.path).orNull)
    context.setVariable("operator", condition.map(_.operator.toString).orNull)
    context.setVariable("addItemIfTrue", addItemIfTrue.getOrElse(""))
    context.setVariable("seedItemIfTrue", seedItemIfTrue.getOrElse(""))
    context.setVariable("itemName", itemName)
    context.setVariable("determiner", determiner)
    // The key the template reads the *translated* item name from. `itemName` itself is still on the
    // context because two ids are derived from it (the remove-modal's, and the title-cased heading),
    // and those must not change per language.
    context.setVariable("contentKey", translationKeyBase)

    templateEngine.process("nodes/fg-collection", context)
  }
}

object FgCollection extends FlowNodeParser {
  override def fromXml(
      fgCollectionElement: Elem,
      flowParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): FgCollection = {
    val factDictionary = flowParser.factDictionary

    val path = fgCollectionElement \@ "path"
    val itemName = fgCollectionElement \@ "item-name"
    val disallowEmpty = fgCollectionElement \@ "disallow-empty"
    val condition = Condition.getCondition(fgCollectionElement, factDictionary)
    val determiner = fgCollectionElement \@ "determiner"
    val addItemIfTrue = optionString(fgCollectionElement \@ "add-item-if-true")
    val seedItemIfTrue = optionString(fgCollectionElement \@ "seed-item-if-true")

    if (itemName.isEmpty) {
      throw InvalidFormConfig("item-name is a required property of FgCollection but was blank")
    }

    validateFgCollection(path, factDictionary)

    addItemIfTrue.foreach { factPath =>
      validateFact(factPath, factDictionary)
      if (!factDictionary.getDefinition(factPath).isBoolean) {
        throw InvalidFormConfig(s"add-item-if-true $factPath must be a boolean fact")
      }
    }

    // Whether this collection opens with one empty row already showing. The runtime used to decide
    // that against a fact path written into the shared JS; declaring it here keeps the rule with
    // the flow it belongs to and out of taxpert.
    seedItemIfTrue.foreach { factPath =>
      validateFact(factPath, factDictionary)
      if (!factDictionary.getDefinition(factPath).isBoolean) {
        throw InvalidFormConfig(s"seed-item-if-true $factPath must be a boolean fact")
      }
    }

    val translationContext = parentTranslationContext.forChildWithId("collection" + path)
    // The item name is authored text — "child", "job", "pension or annuity income" — so it goes
    // through the translation context like every question and hint. Without that a Spanish page
    // reads "eliminar este job". The determiner does not: it is a closed vocabulary
    // (another/more) that the scaffold's own chrome locale already translates.
    translationContext.updateValue("itemName", itemName)

    val children = flowParser.parseChildElements(fgCollectionElement, translationContext)

    FgCollection(
      path,
      itemName,
      disallowEmpty,
      condition,
      translationContext,
      children,
      determiner,
      addItemIfTrue,
      seedItemIfTrue,
    )
  }

  private def validateFgCollection(path: String, factDictionary: FactDictionary): Unit = {
    validateFact(path, factDictionary)
    if (factDictionary.getDefinition(path).typeNode != "CollectionNode")
      throw InvalidFormConfig(s"Path $path must be of type CollectionNode")
  }
}
