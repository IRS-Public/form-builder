package gov.irs.formbuilder.generators

import gov.irs.formbuilder.authoring.AuthoringServer
import gov.irs.formbuilder.parser.Flow
import gov.irs.formbuilder.FormBuilderApp
import gov.irs.formbuilder.FormBuilderTemplateEngine
import org.thymeleaf.context.Context
import scala.jdk.CollectionConverters.*

/** The Author Mode page (`--authorMode`): a static shell. The editable model is fetched at runtime from the authoring
  * server, so nothing here reads `flow`. It stays a parameter to keep the signature interchangeable with
  * [[AllScreens]].
  */
object AuthorMode {
  def generate(
      flow: Flow,
      languageCode: String,
      supportedLocales: Map[String, String],
      flags: Map[String, Boolean] = Map.empty,
  )(using app: FormBuilderApp): WebsitePage = {
    val templateEngine = new FormBuilderTemplateEngine(languageCode, app)
    val context = new Context()
    context.setVariable("title", "Author Mode")
    context.setVariable("languageCode", languageCode)
    context.setVariable("supportedLocales", supportedLocales.asJava)
    // Trailing slash, matching the served directory (out/author/index.html).
    context.setVariable("currentPageRoute", "/author/")
    context.setVariable("flags", flags.asJava)
    // Active item in the workspace's global nav, if the application mounts one.
    context.setVariable("navActive", "authoring-suite")
    // The authoring API's port, rendered into the page as a <meta> for author-mode.js to read — the
    // same server-to-browser channel the flow runtime's storage-prefix and base-path take. Read from
    // AuthoringServer so it is the port FormBuilder.run will actually bind rather than a second
    // guess at it: an app moving its API off 3004 (two generated apps on one machine cannot both
    // hold it) would otherwise ship a page that calls the old port and never connects.
    context.setVariable("authorPort", AuthoringServer.configuredPort)

    val content = templateEngine.process("author-mode", context)
    WebsitePage("/author", content, languageCode)
  }
}
