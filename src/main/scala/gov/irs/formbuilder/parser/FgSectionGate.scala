// `<fg-section-gate>`: a pending/complete banner keyed off a condition.
//
// NOT WIRED UP. This tag is absent from FlowNodeTypes.builtIn and nothing calls FgSectionGate.parse,
// so a flow that authors one falls through to Html. Either register it or delete it.

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context

case class FgSectionGate(
    condition: String,
    operator: String,
    state: String,
) {
  def html(templateEngine: FormBuilderTemplateEngine): String = {

    val context = new Context()
    context.setVariable("gateCondition", this.condition)
    context.setVariable("gateOperator", this.operator)
    context.setVariable("gateState", this.state)
    context.setVariable("gateId", "section-gates." + condition + "-" + operator)

    templateEngine.process("nodes/fg-section-gate", context)
  }
}

object FgSectionGate {
  def parse(node: xml.Node): FgSectionGate = {
    val condition = node \@ "condition"
    val operator = node \@ "operator"
    val state = node \@ "state"

    FgSectionGate(condition, operator, state)
  }
}
