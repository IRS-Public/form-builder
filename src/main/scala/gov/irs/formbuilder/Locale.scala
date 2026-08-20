package gov.irs.formbuilder

import io.circe.*
import io.circe.syntax.*
import io.circe.yaml.Printer
import scala.collection.mutable
import scala.io.Source

def generatedFlowContentPath(app: FormBuilderApp) = app.localesDir / s"flow_${app.defaultLocale}.yaml"
private def translatedFlowContentPath(app: FormBuilderApp, languageCode: String) =
  app.localesDir / s"flow_$languageCode.yaml"

/** The chrome strings the scaffold's own templates ask for, shipped in this jar.
  *
  * Everything under `components.*` and `workspace.tools.*` — the wording on a collection's add/remove buttons, a date
  * field's month names, a modal's close button. It is the same text in every Form Builder app, and it was duplicated
  * verbatim in each one's locale files.
  *
  * Read off the classpath rather than from disk, unlike the app's own locales: this is library content, it never
  * changes while the dev server runs, and Author Mode has no reason to edit it.
  *
  * Public because an app's locale tests have to reason about the layered result rather than its own file alone — see
  * `YamlValidatorSpec` in either app.
  */
def chromeLocaleContent(languageCode: String): Option[Json] =
  // Anchored on a real class of this library rather than on `getClass`: a top-level def compiles into
  // a synthetic `Locale$package` holder whose resource lookup does not reach this jar's resources.
  Option(classOf[Locale].getResourceAsStream(s"/form-builder/locales/$languageCode.yaml")).map { stream =>
    val text =
      try Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    yaml.scalayaml.Parser.parse(text) match {
      case Right(parsed) => parsed
      case Left(error)   =>
        throw new Exception(s"Failed to parse the library locale for $languageCode: ${error.getMessage}", error)
    }
  }

case class Locale(languageCode: String, app: FormBuilderApp) {
  // Read the static locale file from disk (not the classpath). Author Mode re-runs the whole build
  // pipeline in-process after writing edited XML to `src/main/resources`, which makes sbt's `~run`
  // watcher rebuild `target/.../classes` underneath us; `Source.fromResource` then transiently fails
  // to find these files. Reading from the source tree (always present) sidesteps that race.
  private val localeFilePath = app.localesDir / s"${languageCode}.yaml"
  private val mainContent = yaml.scalayaml.Parser.parse(os.read(localeFilePath)) match {
    case Right(parsedData) =>
      parsedData
    case Left(error) =>
      throw new Exception(s"Failed to parse the content at $localeFilePath: ${error.getMessage}", error)
  }
  private val flowContentPath =
    if (languageCode == app.defaultLocale) generatedFlowContentPath(app)
    else translatedFlowContentPath(app, languageCode)
  private val flowContentString = os.read(flowContentPath)
  private val flowContent = yaml.scalayaml.Parser.parse(flowContentString) match {
    case Right(parsedData) =>
      parsedData
    case Left(error) =>
      throw new Exception(s"Failed to parse the content at $flowContentPath: ${error.getMessage}", error)
  }

  private val libraryContent = chromeLocaleContent(languageCode)

  /** Resolve a key across the three layers, app first.
    *
    *   1. the app's own `{lang}.yaml` — its title, layout, results, nav
    *   1. this library's `{lang}.yaml` — the chrome every generated flow shares
    *   1. the generated `flow_{lang}.yaml` — text lifted out of the flow XML
    *
    * App-first, so an app that wants different wording for a shared component just declares the key and wins, the same
    * way it overrides a template.
    */
  def get(key: String): Json =
    GetValueFromLocaleJson(key, mainContent)
      .orElse(libraryContent.flatMap(GetValueFromLocaleJson(key, _)))
      .orElse(GetValueFromLocaleJson(key, flowContent))
      .getOrElse(Json.Null)
}

implicit val anyEncoder: Encoder[Any] = Encoder.instance {
  case m: mutable.LinkedHashMap[_, _] => Json.obj(m.map { case (k, v) => (k.toString, anyEncoder(v)) }.toSeq*)
  case s: String                      => Json.fromString(s)
}

/** Generate the flow_en.yaml locale file.
  *
  * @param translationMap
  *   A populated map of all of the key-value pairs for translations
  */
def generateFlowLocaleFile(translationMap: mutable.LinkedHashMap[String, Any], app: FormBuilderApp): Unit = {
  val generatedPath = generatedFlowContentPath(app)
  val json = translationMap.asJson
  val yamlString = Printer(dropNullKeys = true, preserveOrder = true).pretty(json)
  val content = s"# DO NOT EDIT, THIS IS A GENERATED FILE\n$yamlString"
  // Skip the write when content is unchanged so an edit that can't affect flow text (e.g. a
  // constant or fact-description save) doesn't touch this file's mtime/git status.
  if (!os.exists(generatedPath) || os.read(generatedPath) != content) {
    os.write.over(generatedPath, content)
    Log.info(s"Generated flow content at ${generatedPath}")
  }
}

// Marker prefixed onto a stubbed value before serialization, then rewritten into a
// standalone `# TODO: translate` comment above the key in the emitted YAML. Chosen to
// never collide with real translation text.
private val TodoTranslateSentinel = "@@TODO_TRANSLATE@@"
private val TodoTranslateComment = "# TODO: translate"

/** Re-sync every non-English `flow_{lang}.yaml` to the current `flow_en.yaml` key set.
  *
  * Intended to be called in-process by the Author Mode save endpoint (package `gov.irs.formbuilder.authoring`)
  * immediately after [[generateFlowLocaleFile]] has rewritten `flow_en.yaml`, so that `YamlValidatorSpec` / CI stay
  * green after any on-screen-text or option edit. For each of the 7 locales it, using `flow_en.yaml` as the source of
  * truth for the key structure:
  *
  *   - keeps every existing human translation whose key still exists in `flow_en.yaml`, byte-for-byte in text (only
  *     YAML formatting is normalized),
  *   - adds any key present in `flow_en.yaml` but missing from the locale, seeded with the English value and tagged
  *     with a `# TODO: translate` comment, and
  *   - drops any orphaned key that is no longer present in `flow_en.yaml`.
  *
  * Reads the freshly-written `flow_en.yaml` from disk, so callers only need to have regenerated it first. This is
  * deliberately NOT wired into the normal build pipeline ([[regenerate]]): it rewrites human-maintained files and so
  * should only run on an authoring save, never on every dev build.
  */
def syncTranslationLocales(app: FormBuilderApp): Unit = {
  val generatedPath = generatedFlowContentPath(app)
  yaml.parser.parse(os.read(generatedPath)) match {
    case Left(error) =>
      throw new Exception(s"Failed to parse $generatedPath for locale sync: ${error.getMessage}", error)
    case Right(defaultContent) =>
      app.translatedLocaleCodes.foreach(locale => syncTranslationLocale(app, locale, defaultContent))
  }
}

/** Rewrite a single `flow_{lang}.yaml` so its key set matches `englishContent`. */
private def syncTranslationLocale(app: FormBuilderApp, locale: String, englishContent: Json): Unit = {
  val localePath = translatedFlowContentPath(app, locale)
  val existing = yaml.parser.parse(os.read(localePath)) match {
    case Right(parsed) => Some(parsed)
    case Left(error)   =>
      Log.info(s"Could not parse ${localePath} (${error.getMessage}); rebuilding it from flow_en.yaml")
      None
  }

  val merged = mergeLocaleTree(englishContent, existing)

  // splitLines = false keeps each value on a single line (matching the existing
  // hand-written translation files, and keeping the TODO sentinel on the key's line).
  val yamlString = Printer(dropNullKeys = true, preserveOrder = true, splitLines = false).pretty(merged)
  val withTodoComments = yamlString.linesIterator
    .flatMap { line =>
      if (line.contains(TodoTranslateSentinel)) {
        val indent = line.takeWhile(_ == ' ')
        List(s"$indent$TodoTranslateComment", line.replace(TodoTranslateSentinel, ""))
      } else List(line)
    }
    .mkString("\n")

  val header = s"# Auto-synced from flow_en.yaml — do not add/remove keys here.\n" +
    s"# Human translations are preserved; entries marked \"$TodoTranslateComment\" still need translation.\n"
  val content = s"$header$withTodoComments\n"
  // Skip the write when content is unchanged so locales unaffected by the edit that triggered
  // this sync aren't rewritten (and don't show up as touched in git).
  if (!os.exists(localePath) || os.read(localePath) != content) {
    os.write.over(localePath, content)
    Log.info(s"Synced flow locale $locale at ${localePath}")
  }
}

/** Build a locale tree shaped exactly like `english` (the source of truth for keys/order):
  *   - object nodes recurse, iterating English keys only (so orphaned locale keys are dropped),
  *   - leaf nodes keep the existing translated string when present, otherwise fall back to the English value prefixed
  *     with [[TodoTranslateSentinel]] to mark it for translation.
  */
private def mergeLocaleTree(english: Json, existing: Option[Json]): Json =
  english.asObject match {
    case Some(enObj) =>
      val exObj = existing.flatMap(_.asObject)
      Json.fromFields(
        enObj.toList.map { case (key, enChild) =>
          key -> mergeLocaleTree(enChild, exObj.flatMap(_(key)))
        },
      )
    case None =>
      existing match {
        case Some(translated) if translated.isString => translated
        case _ => Json.fromString(TodoTranslateSentinel + english.asString.getOrElse(""))
      }
  }

private def GetValueFromLocaleJson(key: String, content: Json): Option[Json] = {
  val keyParts = key.split('.')
  val cursor = content.hcursor.downFields(keyParts.head, keyParts.tail*)

  cursor.as[String] match {
    case Right(str) => Some(Json.fromString(str))
    case Left(_)    => cursor.focus
  }
}
