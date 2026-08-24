// The fallthrough node type: an element with no registered parser is re-emitted as HTML.
//
// A leaf element stores its inner markup in the translation context and reads it back per language
// at render time. Anything else wraps its parsed children between the original tags.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.FormBuilderTemplateEngine
import scala.xml.Elem

abstract class Html extends FlowNode

case class HtmlLeafNode(htmlElement: Elem, openTag: String, closeTag: String, translationKey: String) extends Html {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val content = templateEngine.messageResolver.resolveMessage(translationKey)
    s"$openTag$content$closeTag"
  }
}

case class HtmlWithChildren(openTag: String, closeTag: String, children: Seq[FlowNode]) extends Html {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val childrenHtml = children.html(templateEngine)
    s"$openTag$childrenHtml$closeTag"
  }
}

object Html extends FlowNodeParser {
  override def fromXml(
      htmlElement: Elem,
      flowParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): Html = {
    val openTag = getOpenTag(htmlElement)
    val closeTag = getClosingTag(htmlElement)
    if (isLeafNode(htmlElement)) {
      val content = htmlElement.head.child.mkString.strip
      val childKey = parentTranslationContext.getHashKey(htmlElement.label, content)
      val translationKey = parentTranslationContext.fullKey(childKey)
      parentTranslationContext.updateValue(childKey, content)

      HtmlLeafNode(htmlElement, openTag, closeTag, translationKey)
    } else {
      // No translation-key level for these, so wrapping content in a <div> cannot re-key what is inside.
      val ignoredElements = List("div", "details", "summary")
      val translationContext =
        if (ignoredElements.contains(htmlElement.label)) parentTranslationContext
        else parentTranslationContext.forChildWithoutUniqueId(htmlElement.label)
      val children = flowParser.parseChildElements(htmlElement, translationContext)
      HtmlWithChildren(openTag, closeTag, children)
    }
  }

  private def getOpenTag(htmlElem: Elem): String = {
    val tag = htmlElem.label
    val attrs = htmlElem.attributes.asAttrMap
      .map { case (k, v) => s"""$k="$v"""" }
      .mkString(" ")
    val openTag = if (attrs.isEmpty) s"<$tag>" else s"<$tag $attrs>"
    openTag
  }

  private def getClosingTag(htmlElem: Elem): String = {
    val tag = htmlElem.label
    s"</$tag>"
  }

  private val LEAF_NODES = Set("p", "li", "caption", "th", "td", "h1", "h2", "h3", "h4", "h5", "h6", "button")
  private def isLeafNode(element: xml.Elem) = {
    LEAF_NODES.contains(element.label)
  }
}
