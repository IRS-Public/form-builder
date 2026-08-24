// Dispatches a flow XML element to the parser registered for its tag, and holds the fact dictionary
// and FormBuilderApp every node parser needs.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.FormBuilderApp
import scala.xml.Elem

/** [[FormBuilderApp.nodeTypes]] is merged over this map, so an application can add a tag or replace one. Anything
  * unmatched falls through to [[Html]].
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

  /** Named here so a nested `<page>` raises a specific error rather than rendering as HTML. */
  val pageLabel = "page"
}

case class FlowParser(
    factDictionary: FactDictionary,
    app: FormBuilderApp,
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
