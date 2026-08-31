// `<fg-alert>`: a USWDS alert whose visibility is driven by a fact. It reads a `condition`/`operator`
// pair directly rather than going through Condition, which is how it reaches the non-Boolean
// operators. `knockout="true"` blocks navigation; Page and FlowManifest collect those paths.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.parser.{ Condition, ConditionOperator }
import gov.irs.formbuilder.parser.{ FgAlert, FlowNode, FlowNodeParser, FlowParser }
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.xml.Elem

case class FgAlert(
    condition: Option[Condition],
    alertType: String,
    knockout: Boolean,
    translationContext: TranslationContext,
    children: Seq[FlowNode],
) extends FlowNode {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val context = new Context()
    context.setVariable("condition", condition.map(_.path).orNull)
    context.setVariable("operator", condition.map(_.operator.toString).orNull)
    context.setVariable("alertType", alertType)
    val headingKey = translationContext.fullKey("heading")
    val heading = templateEngine.messageResolver.resolveMessage(headingKey)
    context.setVariable("heading", heading)
    val childrenHtml = children.html(templateEngine)
    context.setVariable("children", childrenHtml)
    context.setVariable("knockout", java.lang.Boolean.valueOf(knockout))

    templateEngine.process("nodes/fg-alert", context)
  }
}

object FgAlert extends FlowNodeParser {
  override def fromXml(
      fgAlertElement: Elem,
      flowParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): FgAlert = {
    val alertType = fgAlertElement \@ "alert-type"

    val heading = (fgAlertElement \ "heading").head.child.mkString.strip

    val conditionPath = fgAlertElement \@ "condition"
    val conditionOperator = fgAlertElement \@ "operator"
    val condition = Option.when(conditionPath.nonEmpty && conditionOperator.nonEmpty)(
      Condition(conditionPath, ConditionOperator.fromAttribute(conditionOperator)),
    )

    val knockout = (fgAlertElement \@ "knockout") == "true"

    val translationContext = parentTranslationContext.forChildWithoutUniqueId(fgAlertElement.label, heading)
    translationContext.updateValue("heading", heading)
    // A heading is a complete alert; the body is optional. See parseChildElements' `required`.
    val children = flowParser.parseChildElements(fgAlertElement, translationContext, List("heading"), required = false)

    FgAlert(condition, alertType, knockout, translationContext, children)
  }
}
