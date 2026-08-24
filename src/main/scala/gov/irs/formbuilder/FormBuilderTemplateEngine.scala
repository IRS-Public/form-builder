package gov.irs.formbuilder

import gov.irs.formbuilder.Locale
import java.text.MessageFormat
import org.thymeleaf.context.{ Context, ITemplateContext }
import org.thymeleaf.messageresolver.AbstractMessageResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.TemplateEngine

case class FormBuilderMessageResolver(locale: Locale) extends AbstractMessageResolver:
  def createAbsentMessageRepresentation(
      context: ITemplateContext,
      origin: Class[?],
      key: String,
      messageParameters: Array[Object],
  ): String = {
    Log.warn(s"Could not find key ${key}")
    s"!!${key}!!"
  }

  override def resolveMessage(
      context: ITemplateContext,
      origin: Class[?],
      key: String,
      messageParameters: Array[Object],
  ): String =
    val rawMsg = locale.get(key).as[String].getOrElse(null)
    if (messageParameters != null && messageParameters.nonEmpty) {
      // MessageFormat strips single quotes. A locale value needing a literal apostrophe writes ''.
      MessageFormat.format(rawMsg, messageParameters*)
    } else {
      rawMsg
    }

  def resolveMessage(key: String): String = Option(resolveMessage(null, null, key, null)).getOrElse(s"??$key??")

/** Renders the scaffold's Thymeleaf templates for one language.
  *
  * Two resolvers in order: the app's own `/{appId}/templates/` first, the library's second. An app overrides one
  * template by dropping a same-named file into its own resources and inherits the rest.
  *
  * [[process]] puts `basePath` and the whole [[FormBuilderApp]] on every context, so no template has to be handed a URL
  * prefix by its caller. Long-form: `docs/internals/app-entry-and-assets.md`.
  */
class FormBuilderTemplateEngine(languageCode: String, val app: FormBuilderApp) {
  private def resolverFor(prefix: String, order: Int) = {
    val resolver = new ClassLoaderTemplateResolver()
    resolver.setTemplateMode(TemplateMode.HTML)
    resolver.setCharacterEncoding("UTF-8")
    resolver.setPrefix(prefix)
    resolver.setSuffix(".html")
    resolver.setOrder(order)
    // Report "not found" rather than claiming the name, so the next resolver gets a turn.
    resolver.setCheckExistence(true)
    resolver
  }

  private val appResolver = resolverFor(s"/${app.appId}/templates/", 1)
  private val libraryResolver = resolverFor("/form-builder/templates/", 2)

  private val locale = Locale(languageCode, app)
  private val templateEngine = new TemplateEngine()
  val messageResolver = FormBuilderMessageResolver(locale)
  // A LinkedHashSet, so app-first ordering survives where Thymeleaf does not re-sort by getOrder().
  private val resolvers = new java.util.LinkedHashSet[org.thymeleaf.templateresolver.ITemplateResolver]()
  resolvers.add(appResolver)
  resolvers.add(libraryResolver)
  templateEngine.setTemplateResolvers(resolvers)
  templateEngine.addMessageResolver(messageResolver)

  def process(templateName: String, context: Context): String = {
    context.setVariable("basePath", app.basePath)
    // The language switcher reads app.defaultLocale, and the step indicator's page.href needs it.
    context.setVariable("app", app)
    templateEngine.process(templateName, context)
  }
}
