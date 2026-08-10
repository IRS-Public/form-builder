package gov.irs.formative.parser

import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.FormativeTemplateEngine
import org.thymeleaf.context.Context
import scala.xml.Elem

case class Modal(
    id: String,
    translationContext: TranslationContext,
    modalElements: Seq[FlowNode],
) extends FlowNode {
  override def html(templateEngine: FormativeTemplateEngine): String = {
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
    // Checked before `.head` rather than after it. Both of these used to read `val n = (…).head; if
    // (n.isEmpty) throw InvalidFormConfig(…)`, so a modal written without the wrapper elements —
    // the obvious mistake, since every other node in a flow takes its content directly — died on
    // `NoSuchElementException: next on empty iterator` and the message naming the actual problem
    // was unreachable.
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
