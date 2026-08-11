package gov.irs.formative.generators

import gov.irs.formative.build.Flags
import gov.irs.formative.parser.Flow
import gov.irs.formative.FormativeApp
import gov.irs.formative.FormativeAssets
import gov.irs.formative.FormativeTemplateEngine
import io.circe.Printer
import org.jsoup.parser.Tag
import org.jsoup.Jsoup
import org.thymeleaf.context.Context
import os.Path
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

case class WebsitePage(route: String, content: String, languageCode: String) {
  def html(): String = {
    // This step largely serves to make the output easy to read in view-source
    val document = Jsoup.parse(content)

    // Set certain elements to "block" formatting
    // https://github.com/jhy/jsoup/issues/2141#issuecomment-2795853753
    val setElement = document.selectFirst("fg-set")
    if (setElement != null) {
      val tag = setElement.tag()
      tag.set(Tag.Block)
      setElement.children().forEach(child => child.tag().set(Tag.Block))
    }

    // Convert to an HTML string
    var html = document.html()
    // Adding a newline after each <fg-set> block to make them easier to see
    html = html.replace("</fg-set>", "</fg-set>\n")

    html
  }

  def filepath(root: Path, defaultLocale: String = "en"): Path = {
    val isTranslated = languageCode != defaultLocale
    val isNamedRoute = route != "/"

    var path = root
    if (isTranslated) path = path / languageCode
    if (isNamedRoute) {
      // In one question per screen mode, routes may have multiple segments (e.g. "/filing-status/knows-filing-status"
      // os.Path rejects "/" inside a chunk, so split it out.
      route.stripPrefix("/").split("/").filter(_.nonEmpty).foreach(seg => path = path / seg)
    }

    path / "index.html"
  }
}

case class Website(
    pages: List[WebsitePage],
    factDictionary: xml.Elem,
    flowManifestJson: Option[String] = None,
    scenariosSourceDir: Option[os.Path] = None,
    formativeGraphJson: Option[String] = None,
) {
  def save(directoryPath: Path, app: FormativeApp): Unit = {
    os.remove.all(directoryPath)

    // Write the pages
    for (page <- this.pages) {
      val target = page.filepath(directoryPath, app.defaultLocale)
      os.write(target, page.html(), null, createFolders = true)
    }

    val resourcesSource = app.websiteStaticDir
    val resourcesTarget = directoryPath / "resources"
    os.copy(resourcesSource, resourcesTarget)

    // The library's own browser assets — the theme and the flow runtime — extracted out of this jar into the same
    // `vendor/` shape the app's committed vendor directories use. See FormativeAssets.
    FormativeAssets.extractInto(resourcesTarget)

    val dictionaryString = factDictionary.toString
    os.write(resourcesTarget / "fact-dictionary.xml", dictionaryString, null)

    flowManifestJson.foreach(json => os.write(resourcesTarget / "flow-manifest.json", json, null))

    // The Formative Graph Model, served at {basePath}/resources/formative-graph.json. Fact
    // Explorer fetches it from the running app through its dev proxy — no copy step to go stale.
    formativeGraphJson.foreach(json => os.write(resourcesTarget / "formative-graph.json", json, null))

    scenariosSourceDir.foreach { srcDir =>
      if (os.exists(srcDir)) {
        os.copy(srcDir, resourcesTarget / "scenarios")
      }
    }
  }
}

object Website {
  def generate(
      flow: Flow,
      dictionaryXml: xml.Elem,
      flags: Map[String, Boolean],
      formativeGraphJson: Option[String] = None,
  )(using app: FormativeApp): Website = {
    val supportedLocales = app.locales
    val locales = app.localeCodes

    val scenariosDir = app.scenariosDir
    val scenarioList: java.util.List[java.util.Map[String, String]] =
      if (flags.contains(Flags.scenarioMode) && os.exists(scenariosDir)) {
        os.list(scenariosDir)
          .filter(_.ext == "json")
          .map(_.last)
          .sorted
          .map { filename =>
            val label = filename
              .stripSuffix(".json")
              .split("_")
              .map { word =>
                word.toLowerCase match {
                  case "ko" | "dq" => word.toUpperCase
                  case _           => word.capitalize
                }
              }
              .mkString(" ")
            Map("filename" -> filename, "label" -> label).asJava
          }
          .toList
          .asJava
      } else java.util.Collections.emptyList()

    val scenariosSource =
      if (flags.contains(Flags.scenarioMode) && os.exists(scenariosDir)) Some(scenariosDir) else None

    var pages = locales.flatMap { languageCode =>
      val templateEngine = new FormativeTemplateEngine(languageCode, app)
      val navPages = flow.pages.filter(p => !p.exclude)

      val topicReps: List[gov.irs.formative.parser.Page] = {
        val seen = scala.collection.mutable.LinkedHashMap.empty[String, gov.irs.formative.parser.Page]
        navPages.foreach(p => seen.getOrElseUpdate(p.stepperRoute, p))
        seen.values.toList
      }
      val topicIndex: Map[String, Int] = topicReps.zipWithIndex.map { case (p, i) => p.stepperRoute -> i }.toMap

      flow.pages.zipWithIndex.map { (page, index) =>
        val titleValue = templateEngine.messageResolver.resolveMessage(page.titleKey)
        val titlePrefix = templateEngine.messageResolver.resolveMessage("title.prefix")
        val titleSuffix = templateEngine.messageResolver.resolveMessage("title.suffix")

        // How the three parts make a <title> is `title.format` in the locale, not a decision here.
        // The two apps compose it differently — one wants the page name in the tab, the other only
        // the product — and that is a wording choice, which is what a locale file is for.
        val title = templateEngine.messageResolver.resolveMessage(
          null,
          null,
          "title.format",
          Array[Object](titlePrefix, titleValue, titleSuffix),
        )

        val stepIndex = topicIndex.getOrElse(page.stepperRoute, 0)
        val stepTotal = topicReps.size

        val context = new Context()
        val currentPageRoute = if (!page.route.endsWith("/")) {
          page.route + "/"
        } else {
          page.route
        }
        context.setVariable("exclude", page.exclude)
        context.setVariable("title", title)
        context.setVariable("stepTitle", titleValue)
        context.setVariable("stepIndex", stepIndex)
        context.setVariable("stepTotal", stepTotal)
        context.setVariable("pages", topicReps.asJava) // th:each requires Java Iterables
        context.setVariable("currentPageRoute", currentPageRoute)
        context.setVariable("flags", flags.asJava)
        context.setVariable("languageCode", languageCode)
        context.setVariable("supportedLocales", supportedLocales.asJava)
        // Active item in the shared global nav (see the `taxpert` package). The user-facing
        // questionnaire is the "Product Experience" under Experience Explorer.
        context.setVariable("navActive", "product-experience")

        // Add a link for the next page if it's not the last one
        if (index < flow.pages.size - 1) {
          val nextPageHref = flow.pages(index + 1).href(languageCode, app)
          context.setVariable("nextPageHref", nextPageHref)
        }
        // Add a link for the last page if it's not the first one
        if (index > 0) {
          val lastPageHref = flow.pages(index - 1).href(languageCode, app)
          context.setVariable("lastPageHref", lastPageHref)
        } else {
          context.setVariable("first", true)
        }

        // Turn all the pages into HTML representations and join them together
        val pageHtml = page.html(templateEngine)

        context.setVariable("pageHtml", pageHtml)
        context.setVariable("scenarios", scenarioList)

        val content = templateEngine.process("page", context)
        WebsitePage(page.route, content, languageCode)
      }
    }

    if (flags.contains(Flags.allScreens)) {
      val allScreensPages = locales.map { languageCode =>
        AllScreens.generate(flow, languageCode, supportedLocales, flags, scenarioList)
      }
      pages = pages ++ allScreensPages
    }

    if (flags.contains(Flags.authorMode)) {
      val authorPages = locales.map { languageCode =>
        AuthorMode.generate(flow, languageCode, supportedLocales, flags)
      }
      pages = pages ++ authorPages
    }

    // In single-question-per-screen mode, emit a manifest that the navigation JS uses to skip
    // pages whose gating condition is false against the live Fact Graph. The manifest is built
    // for "en" only because routes are identical across locales (only the href prefix differs);
    // the JS computes the localized href client-side from window.location.
    val manifestJson = Option.when(flags.contains(Flags.singleQuestionPerScreen)) {
      Printer.spaces2.print(FlowManifest.buildJson(flow, app.defaultLocale, app))
    }

    Website(pages, dictionaryXml, manifestJson, scenariosSource, formativeGraphJson)
  }
}
