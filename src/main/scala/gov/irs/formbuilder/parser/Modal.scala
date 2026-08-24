// `<modal-dialog id="...">`: a dialog referenced by a `<modal-link for="...">` elsewhere on the page. Requires a
// `<modal-heading>` and a `<modal-content>` wrapper; the content's children are parsed as ordinary flow nodes.

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.xml.Elem

case class Modal(
    id: String,
    translationContext: TranslationContext,
    modalElements: Seq[FlowNode],
) extends FlowNode {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val context = new Context()
    context.setVariable("modalId", this.id)
    val modalHeadingKey = translationContext.fullKey("heading")
    val modalHeading = templateEngine.messageResolver.resolveMessage(modalHeadingKey)
    context.setVariable("modalHeading", modalHeading)
    val modalContent = modalElements.html(templateEngine)
    context.setVariable("modalContent", modalContent)

    templateEngine.process("nodes/modal-dialog", context)
  }
}

object Modal extends FlowNodeParser {
  override def fromXml(
      modalElement: Elem,
      flowParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): Modal = {
    val id = modalElement \@ "id"
    if (id == null) {
      throw InvalidFormConfig(s"Modal is missing an id")
    }
    // headOption rather than .head, so a missing wrapper reports the message below instead of NoSuchElementException.
    val modalHeadingNode = (modalElement \ "modal-heading").headOption.getOrElse {
      throw InvalidFormConfig(s"Modal $id is missing a <modal-heading>")
    }
    val modalContentNode = (modalElement \ "modal-content").collect { case e: xml.Elem => e }.headOption.getOrElse {
      throw InvalidFormConfig(s"Modal $id is missing a <modal-content>")
    }

    val translationContext = parentTranslationContext.forChildWithId(id)
    translationContext.updateValue("heading", modalHeadingNode.child.mkString)
    val modalElements = flowParser.parseChildElements(modalContentNode, translationContext)
    Modal(id, translationContext, modalElements)
  }
}
