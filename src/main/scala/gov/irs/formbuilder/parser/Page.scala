// `<page>`: one route, and the unit the generator turns into one index.html per language. Also where
// navigation gets its inputs: `gatingCondition` and `knockoutConditionPaths` are read by FlowManifest
// and by the Browse All listing. Only parsed at the flow config root; see FlowParser.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.Utils.optionString
import gov.irs.formbuilder.FormBuilderApp
import gov.irs.formbuilder.FormBuilderTemplateEngine
import scala.util.matching.Regex
import scala.xml.Elem

case class Page(
    translationContext: TranslationContext,
    route: String,
    exclude: Boolean,
    children: Seq[FlowNode],
    sourcePageRoute: Option[String] = None,
    groupBy: Option[String] = None,
    module: Option[String] = None,
    /** Set by [[PageSplitter]] when this page is one block of a split page, and the block carried the condition that
      * decides whether it shows. Read through [[gatingCondition]].
      */
    gate: Option[Condition] = None,
) extends FlowNode {
  val titleKey: String = translationContext.fullKey("title")

  /** The source page when PageSplitter produced this one, otherwise the route itself. */
  def stepperRoute: String = sourcePageRoute.getOrElse(route)

  /** Which flow module this page came from, as a slug — the unit Browse All and the section-level step
    * indicator both group by.
    *
    * `module` is stamped by `FormBuilder.resolveModule` as each `<module src>` is spliced in, so it is
    * set whenever the flow was assembled from an index. The route fallback covers a single-file flow:
    * `/about-you/marital-status` gives `about-you`.
    */
  def moduleSlug: String = module.getOrElse {
    val parts = route.stripPrefix("/").split("/", 2)
    if (parts.nonEmpty && parts(0).nonEmpty) parts(0) else "other"
  }

  /** The gate used to skip a page in single-question-per-screen mode.
    *
    * An explicit `gate` wins: PageSplitter sets it when the page is one conditional block of a split page, and that
    * block's condition is exactly what decides whether the screen is in the traversal at all. Without it a block whose
    * condition is false would render as a page with nothing on it and a Next button.
    *
    * Otherwise it is derived, and only a page with exactly one question has one — so a multi-question page stays
    * reachable and the user lands on at least one visible question.
    */
  def gatingCondition: Option[Condition] =
    gate.orElse(derivedGatingCondition)

  private def derivedGatingCondition: Option[Condition] =
    if (countQuestions(children) != 1) None
    else {
      def find(nodes: Seq[FlowNode]): Iterator[Condition] = nodes.iterator.flatMap {
        case s: Section       => find(s.children)
        case d: FgDetail      => find(d.children)
        case fg: FgSet        => fg.condition.iterator
        case fg: FgCollection => fg.condition.iterator
        case _                => Iterator.empty
      }
      find(children).nextOption()
    }

  private def countQuestions(nodes: Seq[FlowNode]): Int = nodes.iterator.map {
    case _: FgSet        => 1
    case _: FgCollection => 1
    case s: Section      => countQuestions(s.children)
    case d: FgDetail     => countQuestions(d.children)
    case a: FgAlert      => countQuestions(a.children)
    case _               => 0
  }.sum

  /** In DOM order. */
  def knockoutConditionPaths: Seq[String] = {
    def find(nodes: Seq[FlowNode]): Iterator[String] = nodes.iterator.flatMap {
      case s: Section               => find(s.children)
      case d: FgDetail              => find(d.children)
      case a: FgAlert if a.knockout => a.condition.map(_.path).iterator
      case _                        => Iterator.empty
    }
    find(children).toSeq
  }

  def href(languageCode: String, app: FormBuilderApp): String = {
    val languagePortion = if (languageCode == app.defaultLocale) "" else s"/$languageCode"
    val routePortion = if (route == "/") "/" else s"$route/"
    s"${app.basePath}$languagePortion$routePortion"
  }

  override def html(templateEngine: FormBuilderTemplateEngine): String = {
    val pageContent = children.html(templateEngine)
    // HTML does not allow self-closing custom tags.
    val regex = new Regex("""<fg-show ([^>]*)>""", "attributes")
    val pageHtml = regex.replaceAllIn(
      pageContent,
      m => s"<fg-show \\${m group "attributes"}></fg-show>",
    )

    pageHtml
  }
}

object Page extends FlowNodeParser {
  override def fromXml(page: Elem, flowParser: FlowParser, parentTranslationContext: TranslationContext): Page = {
    val route =
      optionString(page \@ "route").getOrElse(throw InvalidFormConfig("<page> is missing a route attribute"))
    val title =
      optionString(page \@ "title").getOrElse(throw InvalidFormConfig("<page> is missing a title attribute"))
    val exclude = (page \@ "exclude-from-stepper").toBooleanOption.getOrElse(false)
    val groupBy = optionString(page \@ "group-by")
    // Stamped on by FormBuilder.resolveModule. A page from a single-file flow has none.
    val module = optionString(page \@ "module")

    val translationContext = parentTranslationContext.forChildWithId(route)
    translationContext.updateValue("title", title)

    val children = flowParser.parseChildElements(page, translationContext)
    Page(translationContext, route, exclude, children, groupBy = groupBy, module = module)
  }
}
