package gov.irs.formbuilder

import gov.irs.formbuilder.parser.FlowNodeParser
import gov.irs.formbuilder.parser.InputParser
import scala.collection.immutable.ListMap

/** Everything the scaffold needs to know about one application.
  *
  * This is the whole of what varied between the two Form Builder apps that existed when this library was extracted from
  * one of them. Most of it is *names* — the resource directory, the URL prefix, the display languages — which were
  * string literals scattered across ten Scala files and sixty-odd templates. The last two fields are the only places
  * where an application genuinely extends the scaffold rather than configuring it.
  *
  * An app builds one of these and hands it to [[FormBuilder.run]]; that is its entire Scala surface.
  *
  * @param appId
  *   the application's directory under `src/main/resources`, and the classpath prefix its own templates resolve from.
  * @param basePath
  *   the URL prefix every generated link and asset href is built from, with no trailing slash (e.g. `/app/something`).
  *   Templates read it as `${basePath}` rather than spelling it out.
  * @param outSubdir
  *   where the generated site is written beneath `./out`. Normally `basePath` without its leading slash — kept separate
  *   because the two are free to differ, and a deployment that serves from a different prefix than it builds into is a
  *   configuration question, not a code change.
  * @param locales
  *   language code -> native display name, in the order the language switcher shows them. The first entry is the
  *   default language: it is generated at the site root, and every other language under `/{code}/`.
  * @param defaultPort
  *   the dev server's port when `-Dsmol.port` says nothing.
  * @param brand
  *   the product name, used in the dev server's startup banner.
  * @param storagePrefix
  *   namespaces every browser storage key the generated site writes, so two Form Builder apps served from one origin —
  *   each under its own route prefix, sharing one `sessionStorage` — do not rehydrate each other's fact graph. Defaults
  *   to [[appId]], which is already unique per app; override it only to keep an existing app's keys stable.
  *
  * It reaches the browser from `fragments/head.html`, ungated, which is the point: it used to arrive only through the
  * workspace's own configuration fragment, so a site built without `--auditMode` silently fell back to a shared
  * default. The workspace namespaces its own keys separately — see `runtime-config.js`.
  * @param nodeTypes
  *   flow XML element name -> parser, for elements this library does not know. Merged over the built-ins, so an app can
  *   also *replace* one. Anything still unmatched is treated as ordinary HTML, exactly as before.
  * @param inputTypes
  *   `<input type="...">` value -> parser, same rules. This is how an app adds an input the scaffold has never heard
  *   of, or reshapes one it has.
  * @param resourceRoot
  *   the source tree the flow, facts, locales and static assets are read from. Read from *disk* rather than the
  *   classpath, deliberately — see the comment on [[FormBuilder.regenerate]].
  */
case class FormBuilderApp(
    appId: String,
    basePath: String,
    outSubdir: String,
    locales: ListMap[String, String],
    defaultPort: Int,
    brand: String,
    storagePrefix: Option[String] = None,
    nodeTypes: Map[String, FlowNodeParser] = Map.empty,
    inputTypes: Map[String, InputParser] = Map.empty,
    resourceRoot: os.Path = os.pwd / "src" / "main" / "resources",
) {

  /** The prefix every browser storage key this site writes is namespaced with. Defaults to [[appId]]. */
  def storageKeyPrefix: String = storagePrefix.getOrElse(appId)

  /** This app's resource directory — the parent of `flow/`, `facts/`, `locales/`, `website-static/`, `scenarios/`. */
  def resourcesDir: os.Path = resourceRoot / appId

  def flowDir: os.Path = resourcesDir / "flow"
  def factsDir: os.Path = resourcesDir / "facts"
  def localesDir: os.Path = resourcesDir / "locales"
  def websiteStaticDir: os.Path = resourcesDir / "website-static"
  def scenariosDir: os.Path = resourcesDir / "scenarios"

  /** The language the site is generated at its root, without a locale segment. */
  def defaultLocale: String = locales.keys.head

  /** Every language code, default first. */
  def localeCodes: List[String] = locales.keys.toList

  /** The non-default languages — the ones that get a `/{code}/` subtree and a `flow_{code}.yaml`. */
  def translatedLocaleCodes: List[String] = localeCodes.tail
}
