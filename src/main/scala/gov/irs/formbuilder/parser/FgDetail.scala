// `<fg-detail>`: a collapsible `<details>`-style disclosure with a translated summary. Carries an optional
// condition, which PageSplitter propagates down to any contained question that has none of its own.

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.{ Condition, FgDetail }
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.TemplateEngine
import scala.xml.Elem

case class FgDetail(
    translationContext: TranslationContext,
    children: Seq[FlowNode],
    useChevron: Boolean,
    detailsClass: Option[String],
    headingTag: String,
    open: Boolean,
    condition: Option[Condition],
) extends FlowNode {
  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val context = new Context()
    val summaryKey = translationContext.fullKey("summary")
    val summary = templateEngine.messageResolver.resolveMessage(summaryKey)
    context.setVariable("summary", summary)
    val childrenHtml = children.html(templateEngine)
    context.setVariable("childrenHtml", childrenHtml)
    context.setVariable("useChevron", java.lang.Boolean.valueOf(useChevron))
    context.setVariable("detailsClass", detailsClass.orNull)
    // Joined in Scala rather than the template: OGNL concatenation would leave a stray space when only one of the
    // two is present. Null when empty so th:classappend skips it.
    val extraClasses = (Option.when(useChevron)("fg-detail--chevron") ++ detailsClass).mkString(" ")
    context.setVariable("extraClasses", if (extraClasses.isEmpty) null else extraClasses)
    context.setVariable("headingTag", headingTag)
    context.setVariable("open", java.lang.Boolean.valueOf(open))
    context.setVariable("condition", condition.map(_.path).orNull)
    context.setVariable("operator", condition.map(c => c.operator.toString).orNull)

    templateEngine.process("nodes/fg-detail", context)
  }
}

object FgDetail extends FlowNodeParser {
  private val VALID_HEADING_TAGS = Set("h2", "h3", "h4", "h5", "h6")

  override def fromXml(
      fgDetailElement: Elem,
      flowNodeParser: FlowParser,
      parentTranslationContext: TranslationContext,
  ): FgDetail = {
    val summary = (fgDetailElement \ "summary").headOption match {
      case Some(summaryNode) => summaryNode.child.map(_.toString).mkString.strip
      case None              => throw InvalidFormConfig("Summary field required")
    }
    val useChevron = (fgDetailElement \@ "icon") == "chevron"
    val classAttribute = (fgDetailElement \@ "class").strip
    val detailsClass = if (classAttribute.nonEmpty) Some(classAttribute) else None
    val rawHeadingTag = (fgDetailElement \@ "heading-tag").strip.toLowerCase
    val headingTag = if (VALID_HEADING_TAGS.contains(rawHeadingTag)) rawHeadingTag else "h4"
    val open = (fgDetailElement \@ "open").strip.equalsIgnoreCase("true")
    val condition = Condition.getCondition(fgDetailElement, flowNodeParser.factDictionary)

    val translationContext = parentTranslationContext.forChildWithoutUniqueId(fgDetailElement.label, summary)
    translationContext.updateValue("summary", summary)

    val childrenHtml = flowNodeParser.parseChildElements(fgDetailElement, translationContext, List("summary"))

    FgDetail(translationContext, childrenHtml, useChevron, detailsClass, headingTag, open, condition)
  }
}
