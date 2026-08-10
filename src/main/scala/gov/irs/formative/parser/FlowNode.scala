package gov.irs.formative.parser

import gov.irs.formative.FormativeTemplateEngine
import scala.xml.Elem

trait FlowNode {
  def html(templateEngine: FormativeTemplateEngine): String
}

extension (flowNodes: Seq[FlowNode]) {
  def html(templateEngine: FormativeTemplateEngine): String =
    flowNodes.map(node => node.html(templateEngine)).mkString("")
}

trait FlowNodeParser {
  def fromXml(element: Elem, flowParser: FlowParser, parentTranslationContext: TranslationContext): FlowNode
}
