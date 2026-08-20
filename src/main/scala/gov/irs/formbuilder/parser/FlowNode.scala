package gov.irs.formbuilder.parser

import gov.irs.formbuilder.FormBuilderTemplateEngine
import scala.xml.Elem

trait FlowNode {
  def html(templateEngine: FormBuilderTemplateEngine): String
}

extension (flowNodes: Seq[FlowNode]) {
  def html(templateEngine: FormBuilderTemplateEngine): String =
    flowNodes.map(node => node.html(templateEngine)).mkString("")
}

trait FlowNodeParser {
  def fromXml(element: Elem, flowParser: FlowParser, parentTranslationContext: TranslationContext): FlowNode
}
