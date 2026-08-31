// Renders the whole site: every page of the parsed flow, once per language, plus the optional Browse
// All and Author Mode pages. `save` writes that tree to disk along with the application's own
// website-static, the library's browser assets and the merged fact dictionary.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.generators

import gov.irs.formbuilder.build.Flags
import gov.irs.formbuilder.parser.Flow
import gov.irs.formbuilder.FormBuilderApp
import gov.irs.formbuilder.FormBuilderAssets
import gov.irs.formbuilder.FormBuilderTemplateEngine
import io.circe.Printer
import org.jsoup.parser.Tag
import org.jsoup.Jsoup
import org.thymeleaf.context.Context
import os.Path
import scala.collection.immutable.ListMap
import scala.jdk.CollectionConverters.*

case class WebsitePage(route: String, content: String, languageCode: String) {

  /** Pretty-printed so view-source is readable. No effect on rendering. */
  def html(): String = {
    val document = Jsoup.parse(content)

    // Unknown tags, which jsoup treats as inline unless told otherwise.
    // https://github.com/jhy/jsoup/issues/2141#issuecomment-2795853753
    val setElement = document.selectFirst("fg-set")
    if (setElement != null) {
      val tag = setElement.tag()
      tag.set(Tag.Block)
      setElement.children().forEach(child => child.tag().set(Tag.Block))
    }

    var html = document.html()
    html = html.replace("</fg-set>", "</fg-set>\n")

    html
  }

  def filepath(root: Path, defaultLocale: String = "en"): Path = {
    val isTranslated = languageCode != defaultLocale
    val isNamedRoute = route != "/"

    var path = root
    if (isTranslated) path = path / languageCode
    if (isNamedRoute) {
      // A route may have several segments, and os.Path rejects "/" inside a chunk.
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
    formBuilderGraphJson: Option[String] = None,
) {
  def save(directoryPath: Path, app: FormBuilderApp): Unit = {
    os.remove.all(directoryPath)

    for (page <- this.pages) {
      val target = page.filepath(directoryPath, app.defaultLocale)
      os.write(target, page.html(), null, createFolders = true)
    }

    writeRootRedirects(directoryPath, app)

    val resourcesSource = app.websiteStaticDir
    val resourcesTarget = directoryPath / "resources"
    os.copy(resourcesSource, resourcesTarget)

    // The theme and the flow runtime, extracted out of this jar.
    FormBuilderAssets.extractInto(resourcesTarget)

    val dictionaryString = factDictionary.toString
    os.write(resourcesTarget / "fact-dictionary.xml", dictionaryString, null)

    flowManifestJson.foreach(json => os.write(resourcesTarget / "flow-manifest.json", json, null))

    // Where Fact Explorer fetches it.
    formBuilderGraphJson.foreach(json => os.write(resourcesTarget / "form-builder-graph.json", json, null))

    scenariosSourceDir.foreach { srcDir =>
      if (os.exists(srcDir)) {
        os.copy(srcDir, resourcesTarget / "scenarios")
      }
    }
  }

  /** A base path that does not resolve is not a usable front door: served from nginx with autoindex off, `/app/<id>/`
    * answers 403 rather than 404, which reads as a deployment fault rather than a missing page. An application avoids
    * that by declaring one `<page route="/">` — credit-assistant, tax-withholding-estimator and benefits-enrollment
    * each do — but nothing makes it a rule, and a flow ported from an application that starts at its first real screen
    * (Direct File) has no such page to port.
    *
    * So fill the gap rather than enforce the convention: for each locale with no page at "/", write a redirect at that
    * locale's root pointing at its own first page. A locale that *does* claim "/" has already written index.html there
    * and is left untouched — this only ever creates a file that would otherwise be absent.
    *
    * `<meta http-equiv="refresh">` rather than a server rule because `out/` is served by whatever the deployment
    * happens to be (nginx here, `python -m http.server` in a scratch check); the redirect has to live in the tree.
    * `<link rel="canonical">` keeps the destination the indexed URL.
    */
  private def writeRootRedirects(directoryPath: Path, app: FormBuilderApp): Unit =
    pages.groupBy(_.languageCode).foreach { (languageCode, localePages) =>
      val hasRootPage = localePages.exists(_.route == "/")
      if (!hasRootPage) localePages.headOption.foreach { first =>
        val localeSegment = if (languageCode == app.defaultLocale) "" else s"/$languageCode"
        val target = s"${app.basePath}$localeSegment${first.route.stripSuffix("/")}/"
        val root = if (languageCode == app.defaultLocale) directoryPath else directoryPath / languageCode
        val escaped = target.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        os.write(
          root / "index.html",
          s"""<!doctype html>
<html lang="$languageCode">
  <head>
    <meta charset="utf-8">
    <meta http-equiv="refresh" content="0; url=$escaped">
    <link rel="canonical" href="$escaped">
    <title>Redirecting</title>
  </head>
  <body>
    <p>Redirecting to <a href="$escaped">$escaped</a>.</p>
  </body>
</html>
""",
          null,
          createFolders = true,
        )
      }
    }
}

object Website {
  def generate(
      flow: Flow,
      dictionaryXml: xml.Elem,
      flags: Map[String, Boolean],
      formBuilderGraphJson: Option[String] = None,
  )(using app: FormBuilderApp): Website = {
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
      val templateEngine = new FormBuilderTemplateEngine(languageCode, app)
      val navPages = flow.pages.filter(p => !p.exclude)

      val topicReps: List[gov.irs.formbuilder.parser.Page] = {
        val seen = scala.collection.mutable.LinkedHashMap.empty[String, gov.irs.formbuilder.parser.Page]
        navPages.foreach(p => seen.getOrElseUpdate(p.stepperRoute, p))
        seen.values.toList
      }
      val topicIndex: Map[String, Int] = topicReps.zipWithIndex.map { case (p, i) => p.stepperRoute -> i }.toMap

      flow.pages.zipWithIndex.map { (page, index) =>
        val titleValue = templateEngine.messageResolver.resolveMessage(page.titleKey)
        val titlePrefix = templateEngine.messageResolver.resolveMessage("title.prefix")
        val titleSuffix = templateEngine.messageResolver.resolveMessage("title.suffix")

        // `title.format` in the locale file, so an application can reorder or drop any part without
        // a code change.
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
        // Active item in the workspace's global nav, if the application mounts one.
        context.setVariable("navActive", "product-experience")

        if (index < flow.pages.size - 1) {
          val nextPageHref = flow.pages(index + 1).href(languageCode, app)
          context.setVariable("nextPageHref", nextPageHref)
        }
        if (index > 0) {
          val lastPageHref = flow.pages(index - 1).href(languageCode, app)
          context.setVariable("lastPageHref", lastPageHref)
        } else {
          context.setVariable("first", true)
        }

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

    // Default locale only. Routes are identical across languages, and the navigation JS derives the
    // href prefix client-side.
    val manifestJson = Option.when(flags.contains(Flags.singleQuestionPerScreen)) {
      Printer.spaces2.print(FlowManifest.buildJson(flow, app.defaultLocale, app))
    }

    Website(pages, dictionaryXml, manifestJson, scenariosSource, formBuilderGraphJson)
  }
}
