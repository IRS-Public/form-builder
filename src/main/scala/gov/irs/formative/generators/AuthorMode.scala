package gov.irs.formative.generators

import gov.irs.formative.parser.Flow
import gov.irs.formative.FormativeApp
import gov.irs.formative.FormativeTemplateEngine
import org.thymeleaf.context.Context
import scala.jdk.CollectionConverters.*

// Author Mode view — the read/UI shell for the in-app structured-form editor.
// Modeled on `AllScreens.generate`: it renders a single static page from
// `templates/author-mode.html`. The editable model itself is fetched at runtime
// by `author-mode.js` from the embedded authoring server (GET /author/model on
// port 3004), so this generator only needs to emit the shell + page chrome.
//
// The parsed `Flow` is threaded in to mirror the AllScreens idiom (and to keep the
// door open for server-side pre-render), even though the MVP shell does not read it.
object AuthorMode {
  def generate(
      flow: Flow,
      languageCode: String,
      supportedLocales: Map[String, String],
      flags: Map[String, Boolean] = Map.empty,
  )(using app: FormativeApp): WebsitePage = {
    val templateEngine = new FormativeTemplateEngine(languageCode, app)
    val context = new Context()
    context.setVariable("title", "Author Mode")
    context.setVariable("languageCode", languageCode)
    context.setVariable("supportedLocales", supportedLocales.asJava)
    // Trailing slash so the header language switcher builds `{basePath}/author/`,
    // matching the served directory (out/author/index.html).
    context.setVariable("currentPageRoute", "/author/")
    context.setVariable("flags", flags.asJava)
    // Active item in the shared global nav (see the `taxpert` package). Author Mode is the
    // "Authoring Suite" destination in the nav taxonomy.
    context.setVariable("navActive", "authoring-suite")

    val content = templateEngine.process("author-mode", context)
    WebsitePage("/author", content, languageCode)
  }
}
