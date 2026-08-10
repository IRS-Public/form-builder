package gov.irs.formative.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.generateFlowLocaleFile
import gov.irs.formative.FormativeApp
import gov.irs.formative.Log
import scala.xml.Elem

case class Flow(
    pages: List[Page],
    translationContext: TranslationContext,
)

object Flow {
  def fromXmlConfig(flowConfig: Elem, factDictionary: FactDictionary, app: FormativeApp): Flow = {
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
