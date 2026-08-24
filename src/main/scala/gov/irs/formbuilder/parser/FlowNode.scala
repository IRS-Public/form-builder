// The two interfaces the flow parser is built on. A node renders itself to a string of HTML, a
// parser builds one from an XML element. There is no intermediate document model and no visitor.
// An application registers its own tag through FormBuilderApp.nodeTypes.
// Long-form: docs/internals/flow-parsing-and-generation.md

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
