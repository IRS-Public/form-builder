package gov.irs.formative.generators

import gov.irs.formative.parser.Flow
import gov.irs.formative.parser.Page
import gov.irs.formative.FormativeApp
import gov.irs.formative.FormativeAssets
import gov.irs.formative.FormativeTemplateEngine
import org.thymeleaf.context.Context
import os.Path
import scala.collection.mutable.LinkedHashMap
import scala.jdk.CollectionConverters.*

case class AllScreens(pages: List[WebsitePage], factDictionary: xml.Elem) {
  def save(directoryPath: Path, app: FormativeApp): Unit = {
    os.remove.all(directoryPath)

    for (page <- this.pages) {
      val target = directoryPath / page.route
      os.write(target, page.html(), null, createFolders = true)
    }

    val resourcesSource = app.websiteStaticDir
    val resourcesTarget = directoryPath / "resources"
    os.copy(resourcesSource, resourcesTarget)

    // As in Website.save — this page links main.css and the flow runtime too. See FormativeAssets.
    FormativeAssets.extractInto(resourcesTarget)
  }
}

object AllScreens {
  // Which flow module a page belongs to, and so which section of the listing it lands in.
  //
  // `page.module` is stamped on by Formative.resolveModule and is the answer whenever a flow is
  // assembled from index.xml — i.e. always, in practice. The route fallback is for a page parsed
  // out of a single-file flow: routes like "/about-you/marital-status" → "about-you".
  private def moduleSlug(page: Page): String = page.module.getOrElse {
    val parts = page.route.stripPrefix("/").split("/", 2)
    if (parts.nonEmpty && parts(0).nonEmpty) parts(0) else "other"
  }

  // Counts the number of `condition="..."` attributes Thymeleaf wrote into the rendered HTML.
  // This includes fg-set, fg-alert, fg-collection, fg-detail, and any conditional HTML elements.
  private val conditionAttrRegex = """\bcondition="[^"]+"""".r
  private def countConditions(html: String): Int = conditionAttrRegex.findAllIn(html).size

  def generate(
      flow: Flow,
      languageCode: String,
      supportedLocales: Map[String, String],
      flags: Map[String, Boolean] = Map.empty,
      scenarios: java.util.List[java.util.Map[String, String]] = java.util.Collections.emptyList(),
  )(using app: FormativeApp): WebsitePage = {
    val templateEngine = new FormativeTemplateEngine(languageCode, app)
    val context = new Context()
    context.setVariable("title", "All Screens")

    context.setVariable("languageCode", languageCode)
    context.setVariable("supportedLocales", supportedLocales.asJava)
    context.setVariable("currentPageRoute", "/all-screens")
    context.setVariable("flags", flags.asJava)
    context.setVariable("scenarios", scenarios)
    // Active item in the shared global nav (see the `taxpert` package). This one generated page backs both
    // "Browse All" and "Path Mode" under Experience Explorer, told apart by `?mode=path` — which
    // the server can't see, so all-screens-bootstrap.js re-points this at Path Mode when the URL
    // asks for it.
    context.setVariable("navActive", "browse-all")

    // Pre-render every page once, alongside its module slug and condition count.
    case class RenderedPage(page: Page, content: String, conditionCount: Int)
    val rendered = flow.pages.map { page =>
      val content = page.html(templateEngine)
      RenderedPage(page, content, countConditions(content))
    }

    // Group by module while preserving the order in which modules first appear in flow.pages
    // (which mirrors the include order in flow/index.xml).
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
