package gov.irs.formbuilder

import gov.irs.formbuilder.parser.FlowNodeParser
import gov.irs.formbuilder.parser.InputParser
import scala.collection.immutable.ListMap

/** Everything the scaffold needs to know about one application.
  *
  * An app builds one of these and hands it to [[FormBuilder.run]]. That is its entire Scala surface. Most fields are
  * names. `nodeTypes` and `inputTypes` are the two places an app extends the scaffold rather than configuring it,
  * registering a flow element or an `<input type>` ahead of the built-ins.
  *
  * `resourceRoot` is a disk path, not a classpath prefix: flow, facts and locales are read from the source tree so
  * Author Mode can rewrite them. See [[FormBuilder.regenerate]].
  *
  * Field-by-field notes: `docs/internals/app-entry-and-assets.md`.
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

  /** Namespaces browser storage keys so two apps on one origin cannot rehydrate each other. */
  def storageKeyPrefix: String = storagePrefix.getOrElse(appId)

  def resourcesDir: os.Path = resourceRoot / appId

  def flowDir: os.Path = resourcesDir / "flow"
  def factsDir: os.Path = resourcesDir / "facts"
  def localesDir: os.Path = resourcesDir / "locales"
  def websiteStaticDir: os.Path = resourcesDir / "website-static"
  def scenariosDir: os.Path = resourcesDir / "scenarios"

  /** Generated at the site root, without a locale segment. */
  def defaultLocale: String = locales.keys.head

  def localeCodes: List[String] = locales.keys.toList

  /** The languages that get a `/{code}/` subtree and a `flow_{code}.yaml`. */
  def translatedLocaleCodes: List[String] = localeCodes.tail
}
