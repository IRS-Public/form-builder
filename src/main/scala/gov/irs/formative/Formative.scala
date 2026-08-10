package gov.irs.formative

import gov.irs.formative.build.Flags
import gov.irs.formative.exceptions.InvalidFormConfig
import gov.irs.formative.generators.Website
import gov.irs.formative.parser.Flow
import gov.irs.formative.parser.PageSplitter
import scala.util.matching.Regex
import scala.util.Try
import scala.xml.Elem
import scala.xml.NodeBuffer

/** The scaffold's entry point. An app's `main` is one line: `Formative.run(app, args)`.
  *
  * Parses the build flags, generates the site, and — under the right flags — starts the Author Mode API and the dev
  * server. Everything it needs to know about the application arrives in [[FormativeApp]].
  */
object Formative {
  private val flagRegex = new Regex("""--(\w*)""")

  def run(app: FormativeApp, args: Seq[String]): Unit = {
    val flags = Map.from(
      args.map {
        case flagRegex(name) => (name, true)
        case flag            =>
          throw new Error(s"Unable to recognize parameter: $flag")
      },
    )

    // Parse the flow + fact dictionary from resources and (re)generate the default-language flow
    // locale file and the static site under ./out. Extracted so the Author Mode save endpoint can
    // re-run the exact same pipeline in-process after writing edited XML to disk.
    val outDir = regenerate(app, flags)

    // Start the embedded Author Mode authoring backend only under --authorMode (via `make dev-author`).
    // It binds its own host/port (`-Dsmol.author.host`/`-Dsmol.author.port`, default localhost/3004),
    // separate from smol, and is never started in production. It re-invokes `regenerate` in-process
    // after each save.
    //
    // The host defaults to "localhost" (loopback-only — this API can patch source XML and commit to
    // git, so it must not be reachable off-box). A docker-compose dev overlay may override it to
    // "0.0.0.0" for a watch container: binding to loopback *inside* a container is invisible to
    // Docker's port-publishing NAT, so it must listen on all interfaces and rely on the host-side
    // port mapping (`127.0.0.1:3004:3004`) for the loopback-only guarantee instead.
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

    if !flags.contains(Flags.serve) then return // Only start smol if 'serve' flag is set

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

    // Start server in-process, but do not block.
    // If it's already running from a previous ~run cycle, starting again will throw BindException - ignore and continue.
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

  /** Re-parse the Flow + Fact Dictionary XML from resources, regenerate the auto-generated flow locale file, render the
    * static site with [[Website.generate]], and save it under `./out`.
    *
    * This is the whole read-side build pipeline, extracted from `run` so it can be invoked both at startup and
    * in-process afterward (e.g. by the Author Mode save endpoint, once it has written edited XML back to the resources
    * on disk). It re-reads all inputs from disk on every call, so callers only need to have persisted their edits
    * first.
    *
    * ==Why disk and not the classpath==
    *
    * Flow, facts and the app's locales are read with `os.read` against the source tree, never `Source.fromResource`.
    * Author Mode patches those files on disk and calls this again in-process, which makes sbt's `~run` watcher rebuild
    * `target/.../classes` underneath us; the classpath copy is either stale or transiently missing. Only the
    * *library's* own templates and base locales come off the classpath, because nothing edits those at runtime.
    *
    * @return
    *   the `./out` directory the site was saved under (the root the `smol` static server serves).
    */
  /** Read `flow/index.xml`, splice in every module it names, and parse the result against the app's fact dictionary.
    *
    * The first half of [[regenerate]], lifted out because a test wants it on its own: parsing is where a mistyped
    * `path=` is caught, and every app should assert that its flow parses. Before this existed each app's spec
    * re-implemented module resolution against `Source.fromResource`, which is both a copy of logic that lives here and
    * a subtly different one — the classpath copy, not the file on disk.
    */
  def parseFlow(app: FormativeApp): Flow = parseFlow(app, loadFactDictionary(app))

  /** As above, against a dictionary the caller has already loaded — [[regenerate]] needs the same one for the site it
    * then generates, and loading it twice is the most expensive thing this file could do by accident.
    */
  def parseFlow(app: FormativeApp, dictionary: LoadedFactDictionary): Flow = {
    given FormativeApp = app

    // Get flow root
    val flowFile = os.read(app.flowDir / "index.xml")
    val flowConfig = xml.XML.loadString(flowFile)
    val children = flowConfig \\ "FlowConfig" \ "_"

    // Resolve modules
    // Note that modules can only appear in the top level
    val resolvedChildren = children.map(child =>
      child.label match {
        case "module" => resolveModule(app, child)
        case _        => child
      },
    )

    val resolvedConfig = <FlowConfig>{resolvedChildren}</FlowConfig>

    Flow.fromXmlConfig(resolvedConfig, dictionary.factDictionary, app)
  }

  def regenerate(app: FormativeApp, flags: Map[String, Boolean]): os.Path = {
    given FormativeApp = app

    val loadedDictionary = loadFactDictionary(app)
    val parsedFlow = parseFlow(app, loadedDictionary)
    val flow =
      if (flags.contains(Flags.singleQuestionPerScreen))
        Flow(PageSplitter.split(parsedFlow.pages), parsedFlow.translationContext)
      else parsedFlow
    generateFlowLocaleFile(flow.translationContext.translationMap, app)
    val site = Website.generate(flow, loadedDictionary.xml, flags)

    // Delete out/ directory and add files to it
    val outDir = os.pwd / "out"
    var target = outDir
    app.outSubdir.split("/").filter(_.nonEmpty).foreach(segment => target = target / segment)
    site.save(target, app)
    outDir
  }

  def resolveModule(app: FormativeApp, node: xml.Node): xml.NodeSeq = {
    val src = node \@ "src"
    // Remove the ./ prefix in the src attribute
    // We support this so that people can use local file path resolution in their text editors
    val resolvedSrc = src.replaceAll("^\\./", "")
    val moduleFile = os.read(app.flowDir / resolvedSrc)

    val flowConfigModule = xml.XML.loadString(moduleFile)
    if (flowConfigModule.label != "FlowConfig") {
      throw InvalidFormConfig(s"Module file $src does not have a top-level FlowConfig")
    }

    // Splicing loses the one thing only this function knows: which file a page came from. Stamp it
    // on the way through, so a listing that groups pages by module (Browse All) does not have to
    // guess the module from the route. Guessing worked only for an app whose routes repeat the
    // module name — the second app's are `/income`, `/credits`, `/`, and the last of those has no
    // segment to guess from at all.
    val moduleSlug = resolvedSrc.split("/").last.stripSuffix(".xml")
    flowConfigModule \ "_" map {
      case page: xml.Elem if page.label == "page" && (page \@ "module").isEmpty =>
        page % xml.Attribute(None, "module", xml.Text(moduleSlug), xml.Null)
      case other => other
    }
  }
}
