package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.generateFlowLocaleFile
import gov.irs.formbuilder.FormBuilderApp
import gov.irs.formbuilder.Log
import scala.xml.Elem

case class Flow(
    pages: List[Page],
    translationContext: TranslationContext,
)

object Flow {
  def fromXmlConfig(flowConfig: Elem, factDictionary: FactDictionary, app: FormBuilderApp): Flow = {
    if (flowConfig.label != "FlowConfig") {
      throw InvalidFormConfig(s"Expected a top-level <FlowConfig>, found ${flowConfig.label}")
    }

    val flowParser = FlowParser(factDictionary, app)
    val rootContext = TranslationContext()

    // FlowConfig is expected to have only `page` child elements relevant to parsing
    val pages = (flowConfig \ "page").collect { case pageElement: Elem =>
      Page.fromXml(pageElement, flowParser, rootContext)
    }.toList
    Log.info(s"Generated flow with ${pages.length} pages")

    Flow(pages, rootContext)
  }
}
