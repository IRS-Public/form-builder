package gov.irs.formbuilder

import io.circe.*
import io.circe.syntax.*
import io.circe.yaml.Printer
import scala.collection.mutable
import scala.io.Source

/** Locale lookup for the generated site, and the two functions that maintain the flow locale files.
  *
  * [[Locale.get]] resolves a key across three layers, app first: the app's own `{lang}.yaml`, then this library's
  * chrome `{lang}.yaml`, then the generated `flow_{lang}.yaml`. App-first, so an app that wants different wording for a
  * shared component declares the key and wins, the same way it overrides a template. A locale test therefore has to
  * compare the layered result rather than the app's file alone.
  *
  * The app's own files are read from disk rather than the classpath, because Author Mode rewrites them and re-runs the
  * build in-process. The chrome layer comes off the classpath, because nothing edits it at runtime.
  *
  * `docs/internals/app-entry-and-assets.md` covers the layering, the generated files and the TODO-marker mechanism.
  */

def generatedFlowContentPath(app: FormBuilderApp) = app.localesDir / s"flow_${app.defaultLocale}.yaml"
private def translatedFlowContentPath(app: FormBuilderApp, languageCode: String) =
  app.localesDir / s"flow_$languageCode.yaml"

/** The chrome strings the scaffold's own templates ask for, shipped in this jar in every supported language. Public so
  * an app's locale tests can reach this layer; see `YamlValidatorSpec`.
  */
def chromeLocaleContent(languageCode: String): Option[Json] =
  // Anchored on a real class of this library. A top-level def compiles into a synthetic
  // `Locale$package` holder whose resource lookup does not reach this jar's resources.
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

/** Rewrite the default language's `flow_{lang}.yaml` from the map the flow parser accumulated. Hand edits to that file
  * are lost on the next build. Authored text belongs in the flow XML.
  */
def generateFlowLocaleFile(translationMap: mutable.LinkedHashMap[String, Any], app: FormBuilderApp): Unit = {
  val generatedPath = generatedFlowContentPath(app)
  assertKeysArePrintable(translationMap, Nil)
  val json = translationMap.asJson
  val yamlString = Printer(dropNullKeys = true, preserveOrder = true).pretty(json)
  val content = s"# DO NOT EDIT, THIS IS A GENERATED FILE\n$yamlString"
  // Skip an unchanged write, so an edit that cannot affect flow text leaves git status alone.
  if (!os.exists(generatedPath) || os.read(generatedPath) != content) {
    os.write.over(generatedPath, content)
    Log.info(s"Generated flow content at ${generatedPath}")
  }
}

/** The longest mapping key the YAML printer will write on one line with its value.
  *
  * Past it the printer switches to YAML's explicit-key form — `? key` on its own line, then `: value` — which the
  * parser [[Locale]] reads these files back with does not accept. So a key this long makes the build write a file it
  * cannot load, and the failure surfaces on the next run as a parse error pointing at a line that looks fine.
  */
private val MaxPrintableKeyLength = 128

/** Fail on a translation key too long to round-trip, naming it and where it sits. */
private def assertKeysArePrintable(map: mutable.LinkedHashMap[String, Any], context: List[String]): Unit =
  map.foreach { case (key, value) =>
    if (key.length > MaxPrintableKeyLength) {
      val where = (context :+ key).mkString(".")
      throw new Exception(
        s"Translation key is ${key.length} characters, over the $MaxPrintableKeyLength the flow locale " +
          s"file can hold: $where. Keys come from the flow XML — shorten the id or the heading that " +
          s"names this one.",
      )
    }
    value match {
      case child: mutable.LinkedHashMap[String, Any] @unchecked => assertKeysArePrintable(child, context :+ key)
      case _                                                    => ()
    }
  }

// Prefixed onto a stubbed value, then rewritten into a comment line above the key, because the circe
// YAML printer cannot emit comments directly.
private val TodoTranslateSentinel = "@@TODO_TRANSLATE@@"
private val TodoTranslateComment = "# TODO: translate"

/** Re-key every translated `flow_{lang}.yaml` against the default language's. Deliberately not wired into
  * [[FormBuilder.regenerate]], because it rewrites human-maintained files. It runs only on an Author Mode save.
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

  // splitLines = false keeps the TODO sentinel on the key's own line.
  val yamlString = Printer(dropNullKeys = true, preserveOrder = true, splitLines = false).pretty(merged)
  val withTodoComments = yamlString.linesIterator
    .flatMap { line =>
      if (line.contains(TodoTranslateSentinel)) {
        val indent = line.takeWhile(_ == ' ')
        List(s"$indent$TodoTranslateComment", line.replace(TodoTranslateSentinel, ""))
      } else List(line)
    }
    .mkString("\n")

  val header = s"# Auto-synced from flow_en.yaml. Do not add or remove keys here.\n" +
    s"# Human translations are preserved; entries marked \"$TodoTranslateComment\" still need translation.\n"
  val content = s"$header$withTodoComments\n"
  if (!os.exists(localePath) || os.read(localePath) != content) {
    os.write.over(localePath, content)
    Log.info(s"Synced flow locale $locale at ${localePath}")
  }
}

/** Shaped exactly like `english`, so orphaned locale keys are dropped. A leaf keeps its existing translation, or takes
  * the English value prefixed with [[TodoTranslateSentinel]].
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
