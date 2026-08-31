// `<fg-collection>`: a repeating group bound to a CollectionNode fact. Children are parsed as
// ordinary flow nodes and rendered once per item by the browser runtime.
// `add-item-if-true` and `seed-item-if-true` must name Boolean facts, validated here.
// `readonly` iterates a collection the taxpayer cannot change, and is what a <Derived> collection needs.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.Condition
import gov.irs.formbuilder.parser.Utils.optionString
import gov.irs.formbuilder.parser.Utils.validateFact
import gov.irs.formbuilder.FormBuilderTemplateEngine
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
    readonly: Boolean,
) extends FlowNode {
  def html(templateEngine: FormBuilderTemplateEngine): String = {
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
    context.setVariable("readonly", readonly)
    // The template reads the translated item name from this key. The raw `itemName` stays on the context because
    // two element ids are derived from it and must not change per language.
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
    val readonly = (fgCollectionElement \@ "readonly") == "true"

    if (itemName.isEmpty) {
      throw InvalidFormConfig("item-name is a required property of FgCollection but was blank")
    }

    // Every one of these only ever dresses the Add button, and a readonly collection has no Add button. A declared
    // attribute that moves nothing is the failure mode this library has been bitten by before, so it is an error
    // rather than a silent no-op.
    if (readonly) {
      val addOnly = Seq(
        "add-item-if-true" -> addItemIfTrue.isDefined,
        "seed-item-if-true" -> seedItemIfTrue.isDefined,
        "disallow-empty" -> (disallowEmpty == "true"),
        "determiner" -> determiner.nonEmpty,
      ).collect { case (name, true) => name }

      if (addOnly.nonEmpty) {
        throw InvalidFormConfig(
          s"fg-collection $path is readonly, so ${addOnly.mkString(" and ")} would have no effect. Remove " +
            s"${if (addOnly.length == 1) "it" else "them"}, or drop readonly.",
        )
      }
    }

    validateFgCollection(path, factDictionary)

    addItemIfTrue.foreach { factPath =>
      validateFact(factPath, factDictionary)
      if (!factDictionary.getDefinition(factPath).isBoolean) {
        throw InvalidFormConfig(s"add-item-if-true $factPath must be a boolean fact")
      }
    }

    seedItemIfTrue.foreach { factPath =>
      validateFact(factPath, factDictionary)
      if (!factDictionary.getDefinition(factPath).isBoolean) {
        throw InvalidFormConfig(s"seed-item-if-true $factPath must be a boolean fact")
      }
    }

    val translationContext = parentTranslationContext.forChildWithId("collection" + path)
    // The item name is authored text, so it is translated. The determiner is not: it is a closed vocabulary
    // (another/more) that the library's own chrome locale already covers.
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
      readonly,
    )
  }

  private def validateFgCollection(path: String, factDictionary: FactDictionary): Unit = {
    validateFact(path, factDictionary)
    if (factDictionary.getDefinition(path).typeNode != "CollectionNode")
      throw InvalidFormConfig(s"Path $path must be of type CollectionNode")
  }
}
