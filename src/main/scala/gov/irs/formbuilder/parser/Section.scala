// `<section>`: a grouping wrapper with no behaviour of its own, rendered through nodes/section.html.

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.xml.Elem

case class Section(children: Seq[FlowNode]) extends FlowNode {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val childrenHtml = children.html(templateEngine)

    val context = new Context()
    context.setVariable("childrenHtml", childrenHtml)
    templateEngine.process("nodes/section", context)
  }
}
object Section extends FlowNodeParser {
  override def fromXml(section: Elem, flowParser: FlowParser, parentTranslationContext: TranslationContext): Section = {
    val children = flowParser.parseChildElements(section, parentTranslationContext)

    Section(children)
  }
}
