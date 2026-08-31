// The Browse All page (--allScreens): every screen on one page at /all-screens, grouped into sections
// by module. The toolbar and styling over it belong to the workspace package, mounted through
// fragments/workspace-all-screens.html, so this generator emits the markup and nothing else.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.generators

import gov.irs.formbuilder.parser.Flow
import gov.irs.formbuilder.parser.Page
import gov.irs.formbuilder.FormBuilderApp
import gov.irs.formbuilder.FormBuilderAssets
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import os.Path
import scala.collection.mutable.LinkedHashMap
import scala.jdk.CollectionConverters.*

case class AllScreens(pages: List[WebsitePage], factDictionary: xml.Elem) {
  def save(directoryPath: Path, app: FormBuilderApp): Unit = {
    os.remove.all(directoryPath)

    for (page <- this.pages) {
      val target = directoryPath / page.route
      os.write(target, page.html(), null, createFolders = true)
    }

    val resourcesSource = app.websiteStaticDir
    val resourcesTarget = directoryPath / "resources"
    os.copy(resourcesSource, resourcesTarget)

    FormBuilderAssets.extractInto(resourcesTarget)
  }
}

object AllScreens {
  // On Page since the step indicator groups by it too — see Page.moduleSlug for what it falls back to.
  private def moduleSlug(page: Page): String = page.moduleSlug

  private val conditionAttrRegex = """\bcondition="[^"]+"""".r
  private def countConditions(html: String): Int = conditionAttrRegex.findAllIn(html).size

  def generate(
      flow: Flow,
      languageCode: String,
      supportedLocales: Map[String, String],
      flags: Map[String, Boolean] = Map.empty,
      scenarios: java.util.List[java.util.Map[String, String]] = java.util.Collections.emptyList(),
  )(using app: FormBuilderApp): WebsitePage = {
    val templateEngine = new FormBuilderTemplateEngine(languageCode, app)
    val context = new Context()
    context.setVariable("title", "All Screens")

    context.setVariable("languageCode", languageCode)
    context.setVariable("supportedLocales", supportedLocales.asJava)
    // Trailing slash required: the language switcher compares the routes it builds from this against
    // location.pathname, which ends in one.
    context.setVariable("currentPageRoute", "/all-screens/")
    context.setVariable("flags", flags.asJava)
    context.setVariable("scenarios", scenarios)
    // Active item in the workspace's global nav. This one page backs both Browse All and Path Mode, told apart by
    // a `?mode=path` query the server cannot see, so the application's bootstrap JS re-points it client-side.
    context.setVariable("navActive", "browse-all")

    case class RenderedPage(page: Page, content: String, conditionCount: Int)
    val rendered = flow.pages.map { page =>
      val content = page.html(templateEngine)
      RenderedPage(page, content, countConditions(content))
    }

    // Grouped in the order modules first appear in flow.pages, which mirrors the include order in flow/index.xml.
    val grouped = LinkedHashMap.empty[String, List[RenderedPage]]
    rendered.foreach { r =>
      val slug = moduleSlug(r.page)
      grouped.update(slug, grouped.getOrElse(slug, List.empty) :+ r)
    }

    val sections = grouped.toList.map { case (slug, rps) =>
      val pages = rps.map { rp =>
        val gate = rp.page.gatingCondition
        Map(
          "route" -> rp.page.route,
          "title" -> templateEngine.messageResolver.resolveMessage(rp.page.titleKey),
          "content" -> rp.content,
          "conditionCount" -> Integer.valueOf(rp.conditionCount),
          "gateConditionPath" -> gate.map(_.path).orNull,
          "gateConditionOperator" -> gate.map(_.operator.toString).orNull,
        ).asJava
      }
      val sectionTitle = templateEngine.messageResolver.resolveMessage(s"all-screens.section.$slug")
      Map(
        "slug" -> slug,
        "title" -> sectionTitle,
        "pageCount" -> Integer.valueOf(rps.size),
        "conditionCount" -> Integer.valueOf(rps.map(_.conditionCount).sum),
        "pages" -> pages.asJava,
      ).asJava
    }

    context.setVariable("sections", sections.asJava)

    val content = templateEngine.process("all-screens", context)

    WebsitePage("/all-screens", content, languageCode)
  }
}
