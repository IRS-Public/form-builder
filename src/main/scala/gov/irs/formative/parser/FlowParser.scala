package gov.irs.formative.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.FormativeApp
import scala.xml.Elem

/** The flow elements this library knows how to parse, by XML element name.
  *
  * An app's [[FormativeApp.nodeTypes]] is merged *over* this, so an app can both add an element the scaffold has never
  * heard of and replace one it has. Anything still unmatched is rendered as ordinary HTML, exactly as before — that
  * fallthrough is what lets a flow use `<p>`, `<ul>` and friends without registering anything.
  *
  * This used to be an enum and two `match` blocks that had to be edited in lockstep, which is precisely why the second
  * Formative app could not add its one custom element without forking the file.
  */
object FlowNodeTypes {
  val builtIn: Map[String, FlowNodeParser] = Map(
    "fg-alert" -> FgAlert,
    "fg-apply" -> FgApply,
    "fg-collection" -> FgCollection,
    "fg-detail" -> FgDetail,
    "fg-set" -> FgSet,
    "modal-dialog" -> Modal,
    "section" -> Section,
  )

  /** `<page>` is parsed by [[Page]], but only ever at the flow config root — never as a child. Named here so the error
    * for a nested one stays specific instead of silently rendering as HTML.
    */
  val pageLabel = "page"
}

case class FlowParser(
    factDictionary: FactDictionary,
    app: FormativeApp,
) {
  private val nodeParsers: Map[String, FlowNodeParser] = FlowNodeTypes.builtIn ++ app.nodeTypes

  def parseChildElements(
      parent: Elem,
      parentTranslationContext: TranslationContext,
      excludedLabels: Seq[String] = Seq.empty[String],
  ): Seq[FlowNode] = {
    val childElements = (parent \ "_").filter(c => !excludedLabels.contains(c.label))

    if (childElements.isEmpty) {
      throw InvalidFormConfig(s"Encountered an empty element for which there is no parser configured: $parent")
    }
    childElements.collect { case element: Elem =>
      parseElement(element, parentTranslationContext)
    }
  }

  private def parseElement(
      element: Elem,
      parentTranslationContext: TranslationContext,
  ): FlowNode =
    if (element.label == FlowNodeTypes.pageLabel) {
      throw InvalidFormConfig(
        s"Encountered 'page' element outside of flow config root. Pages are only supported as top-level flow config elements.",
      )
    } else {
      nodeParsers.getOrElse(element.label, Html).fromXml(element, this, parentTranslationContext)
    }
}
