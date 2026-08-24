package gov.irs.formbuilder

import gov.irs.formbuilder.build.Flags
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.generators.Website
import gov.irs.formbuilder.parser.Flow
import gov.irs.formbuilder.parser.PageSplitter
import scala.util.matching.Regex
import scala.util.Try
import scala.xml.Elem
import scala.xml.NodeBuffer

/** The scaffold's entry point. An app's `main` is one line: `FormBuilder.run(app, args)`.
  *
  * Parses the build flags, generates the site, and under the right flags starts the Author Mode API and the dev server.
  * Everything it needs to know about the application arrives in [[FormBuilderApp]].
  *
  * Flow, facts and the app's locales are read from disk rather than the classpath, because Author Mode rewrites them at
  * runtime. Long-form: `docs/internals/app-entry-and-assets.md`.
  */
object FormBuilder {
  private val flagRegex = new Regex("""--(\w*)""")

  def run(app: FormBuilderApp, args: Seq[String]): Unit = {
    val flags = Map.from(
      args.map {
        case flagRegex(name) => (name, true)
        case flag            =>
          throw new Error(s"Unable to recognize parameter: $flag")
      },
    )

    val outDir = regenerate(app, flags)

    // Loopback by default, because this API patches source XML on disk. A container overlay setting
    // `-Dsmol.author.host=0.0.0.0` relies on a host-side `127.0.0.1:3004:3004` mapping instead.
    if flags.contains(Flags.authorMode) then {
      val authorHost = sys.props.get("smol.author.host").getOrElse("localhost")
      val authorPort = sys.props
        .get("smol.author.port")
        .flatMap(s => Try(s.toInt).toOption)
        .getOrElse(3004)
      try authoring.AuthoringServer.start(authorHost, authorPort, flags, app)
      catch {
        case _: java.net.BindException =>
          println(s"\u001b[33m\u001b[1m⚠\u001b[0m Author Mode API already running on port ${authorPort}")
      }
    }

    if !flags.contains(Flags.serve) then return

    val host = "localhost"
    val port = sys.props
      .get("smol.port")
      .flatMap(s => Try(s.toInt).toOption)
      .getOrElse(app.defaultPort)
    val config = smol.Config(outDir.toString(), host, port, logEnabled = true)

    val url = s"http://${host}:${port}${app.basePath}"
    val green = "\u001b[32m"
    val yellow = "\u001b[33m"
    val cyan = "\u001b[36m"
    val bold = "\u001b[1m"
    val reset = "\u001b[0m"

    // In-process and non-blocking. A BindException here means a previous `~run` cycle's server is still bound.
    try
      val server = smol.Smol.start(config)
      sys.addShutdownHook(server.stop(0))
      println(s"\n${green}${bold}✓${reset} ${bold}${app.brand} Server${reset} ${cyan}ready${reset}")
      println(s"  ${bold}Local:${reset}   ${cyan}${url}${reset}\n")
    catch
      case _: java.net.BindException =>
        println(s"\n${yellow}${bold}⚠${reset} ${bold}Server${reset} ${yellow}already running${reset}")
        println(s"  ${bold}Local:${reset}   ${cyan}${url}${reset}\n")
  }

  /** Public so an app's spec can assert that its flow parses, which is where a mistyped `path=` is caught. */
  def parseFlow(app: FormBuilderApp): Flow = parseFlow(app, loadFactDictionary(app))

  /** Against a dictionary the caller already loaded. Loading it twice is the most expensive thing this file could do by
    * accident.
    */
  def parseFlow(app: FormBuilderApp, dictionary: LoadedFactDictionary): Flow = {
    given FormBuilderApp = app
    Flow.fromXmlConfig(resolvedFlowConfig(app), dictionary.factDictionary, app)
  }

  /** `flow/index.xml` with every `<module src="…"/>` spliced in. Also read by [[generators.FormBuilderGraph]], which
    * needs each element's source XML after the parsed `FlowNode` case classes have discarded their `Elem`.
    */
  def resolvedFlowConfig(app: FormBuilderApp): xml.Elem = {
    val flowFile = os.read(app.flowDir / "index.xml")
    val flowConfig = xml.XML.loadString(flowFile)
    val children = flowConfig \\ "FlowConfig" \ "_"

    // Modules are only recognized at the top level.
    val resolvedChildren = children.map(child =>
      child.label match {
        case "module" => resolveModule(app, child)
        case _        => child
      },
    )

    <FlowConfig>{resolvedChildren}</FlowConfig>
  }

  /** The whole read-side build. Separate from [[run]] because the Author Mode save endpoint calls it again in-process
    * after writing edited XML. Every input is re-read on each call, so a caller only has to have persisted its edits
    * first. Returns the `./out` root `smol` serves.
    */
  def regenerate(app: FormBuilderApp, flags: Map[String, Boolean]): os.Path = {
    given FormBuilderApp = app

    val loadedDictionary = loadFactDictionary(app)
    val parsedFlow = parseFlow(app, loadedDictionary)
    val flow =
      if (flags.contains(Flags.singleQuestionPerScreen))
        Flow(PageSplitter.split(parsedFlow.pages), parsedFlow.translationContext)
      else parsedFlow
    generateFlowLocaleFile(flow.translationContext.translationMap, app)

    // Built from `parsedFlow` so the graph keeps the authored shape under --singleQuestionPerScreen.
    val formBuilderGraphJson = Option.when(flags.contains(Flags.formBuilderGraph)) {
      io.circe.Printer.spaces2.print(
        generators.FormBuilderGraph.buildJson(
          resolvedFlowConfig(app),
          loadedDictionary.xml,
          parsedFlow,
          app,
        ),
      )
    }

    val site = Website.generate(flow, loadedDictionary.xml, flags, formBuilderGraphJson)

    // `site.save` removes `target` before writing it.
    val outDir = os.pwd / "out"
    var target = outDir
    app.outSubdir.split("/").filter(_.nonEmpty).foreach(segment => target = target / segment)
    site.save(target, app)
    outDir
  }

  def resolveModule(app: FormBuilderApp, node: xml.Node): xml.NodeSeq = {
    val src = node \@ "src"
    // A leading ./ is stripped so authors can write a path their editor can follow.
    val resolvedSrc = src.replaceAll("^\\./", "")
    val moduleFile = os.read(app.flowDir / resolvedSrc)

    val flowConfigModule = xml.XML.loadString(moduleFile)
    if (flowConfigModule.label != "FlowConfig") {
      throw InvalidFormConfig(s"Module file $src does not have a top-level FlowConfig")
    }

    // The last point at which a page's source file is known, and Browse All groups pages by module.
    val moduleSlug = resolvedSrc.split("/").last.stripSuffix(".xml")
    flowConfigModule \ "_" map {
      case page: xml.Elem if page.label == "page" && (page \@ "module").isEmpty =>
        page % xml.Attribute(None, "module", xml.Text(moduleSlug), xml.Null)
      case other => other
    }
  }
}
