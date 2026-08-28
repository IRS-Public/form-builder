// `<fg-apply path="...">`: writes into the Fact Graph as soon as the page renders, so a page reached
// only under a condition can assert the fact that condition implies.
//
// What it writes comes from exactly one of two attributes:
//
//   <fg-apply path="/isMarried" value="true"/>          a literal
//   <fg-apply path="/spouseState" source="/filerState"/> the current value of another fact
//
// `source` exists because copying one fact into another is a distinct and common flow action — the
// Direct File port alone carries 73 of them — and expressing it as a literal is impossible: the
// value is not known when the flow is authored. Both paths are checked against the fact dictionary
// at build time.

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.Utils.validateFact
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.xml.Elem

case class FgApply(path: String, value: Option[String], source: Option[String]) extends FlowNode {
  def html(templateEngine: FormBuilderTemplateEngine): String = {
    val context = new Context()
    context.setVariable("path", this.path)
    // Thymeleaf's th:attr drops an attribute whose expression is null, so an unused half of the
    // pair never reaches the markup as an empty string the runtime would then have to tell from
    // a deliberate one.
    context.setVariable("value", this.value.orNull)
    context.setVariable("source", this.source.orNull)
    templateEngine.process("nodes/fg-apply", context)
  }
}

object FgApply extends FlowNodeParser {
  override def fromXml(
      fgApplyElement: Elem,
      flowParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): FgApply = {
    val path = fgApplyElement \@ "path"
    if (path.isEmpty) {
      throw new InvalidFormConfig("fg-apply attribute `path` is required but was missing or empty")
    }
    validateFact(path, flowParser.factDictionary)

    val value = Option(fgApplyElement \@ "value").filter(_.nonEmpty)
    val source = Option(fgApplyElement \@ "source").filter(_.nonEmpty)

    // Exactly one, rejected in both directions. Neither is a flow node that does nothing; both is
    // an author who meant one of them, and silently preferring either would be a guess.
    (value, source) match {
      case (None, None) =>
        throw new InvalidFormConfig(
          s"fg-apply on `$path` needs either `value` or `source`, and has neither",
        )
      case (Some(_), Some(_)) =>
        throw new InvalidFormConfig(
          s"fg-apply on `$path` has both `value` and `source`; it takes exactly one",
        )
      case _ =>
    }

    source.foreach(validateFact(_, flowParser.factDictionary))

    FgApply(path, value, source)
  }
}
