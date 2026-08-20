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
      // MessageFormat.format makes it so ' are removed we would need to use '' if we want one to be displayed
      MessageFormat.format(rawMsg, messageParameters*)
    } else {
      rawMsg
    }

  def resolveMessage(key: String): String = Option(resolveMessage(null, null, key, null)).getOrElse(s"??$key??")

/** Renders the scaffold's Thymeleaf templates for one language.
  *
  * ==Templates resolve app-first==
  *
  * Two resolvers, in order. The app's own `templates/` directory wins; the library's is the fallback. So an app that
  * wants a different money input drops `nodes/inputs/dollar.html` into its own resources and inherits the other
  * twenty-nine untouched — and an app with a flow element the scaffold has never heard of ships that element's template
  * next to the parser it registered in [[FormBuilderApp.nodeTypes]].
  *
  * This is the mechanism that let two forked template trees, which differed by 0–2 lines in most files, become one tree
  * plus a handful of genuine overrides. `setCheckExistence(true)` is what makes the fallthrough work: without it the
  * first resolver claims every name and the app would have to copy all thirty files to change one.
  *
  * ==Templates never spell out a URL prefix==
  *
  * `process` puts `basePath` on every context on the way through, so any template — page or node — can write
  * `th:href="|${basePath}/resources/…|"` without its caller having to remember. That single injection replaced
  * fifty-odd hardcoded route prefixes across the template tree, which is the only reason moving them into a shared jar
  * was possible at all.
  */
class FormBuilderTemplateEngine(languageCode: String, val app: FormBuilderApp) {
  private def resolverFor(prefix: String, order: Int) = {
    val resolver = new ClassLoaderTemplateResolver()
    resolver.setTemplateMode(TemplateMode.HTML)
    resolver.setCharacterEncoding("UTF-8")
    resolver.setPrefix(prefix)
    resolver.setSuffix(".html")
    resolver.setOrder(order)
    // Report "not found" instead of claiming the name, so the next resolver gets a turn.
    resolver.setCheckExistence(true)
    resolver
  }

  private val appResolver = resolverFor(s"/${app.appId}/templates/", 1)
  private val libraryResolver = resolverFor("/form-builder/templates/", 2)

  private val locale = Locale(languageCode, app)
  private val templateEngine = new TemplateEngine()
  val messageResolver = FormBuilderMessageResolver(locale)
  // A LinkedHashSet so the declared order survives even where Thymeleaf does not re-sort by
  // getOrder(); app-first is the whole point and must not depend on hash iteration order.
  private val resolvers = new java.util.LinkedHashSet[org.thymeleaf.templateresolver.ITemplateResolver]()
  resolvers.add(appResolver)
  resolvers.add(libraryResolver)
  templateEngine.setTemplateResolvers(resolvers)
  templateEngine.addMessageResolver(messageResolver)

  def process(templateName: String, context: Context): String = {
    context.setVariable("basePath", app.basePath)
    // The whole configuration, so a template can ask about the default locale (the language switcher
    // does) or call a model method that needs it (the step indicator's page.href does).
    context.setVariable("app", app)
    templateEngine.process(templateName, context)
  }
}
