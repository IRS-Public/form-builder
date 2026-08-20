package gov.irs.formbuilder

import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path as JavaPath
import java.nio.file.StandardCopyOption
import scala.jdk.CollectionConverters.*

/** Anchor for the resource lookup in [[FormBuilderAssets]].
  *
  * A real, declared class of this library, for the reason `chromeLocaleContent` documents in Locale.scala: resource
  * lookups anchored on a synthetic holder do not reach this jar's resources. An `object`'s own `getClass` would in fact
  * work, but naming the anchor makes the requirement visible instead of incidental.
  */
final private class FormBuilderAssetsAnchor

/** The library's own browser assets, and the one mechanism that gets them out of this jar into a generated site.
  *
  * Form Builder ships the general front-end interface every app renders with — the theme stylesheet (design tokens,
  * page layout, and the styling of every element the generators emit) and the flow runtime (the custom elements a
  * generated questionnaire actually runs on). Neither has anything to do with which app it is, and neither is optional:
  * without them a generated site has no styling and no working questionnaire.
  *
  * They live on the classpath beside the templates that load them, so a template and the asset it references ship in
  * one versioned artifact. They used to come from the `taxpert` npm package instead, which made the *workspace* — a
  * thing an app can perfectly well ship without — a hard build dependency of every app.
  *
  * ==Why the classpath, when flow and facts are read from disk==
  *
  * [[FormBuilder.regenerate]] explains at length why flow, facts and an app's locales are read with `os.read`: Author
  * Mode patches them on disk and re-runs the build in-process, which makes sbt's `~run` watcher rebuild
  * `target/.../classes` underneath a running process. These assets are the documented exception, for the same reason
  * the library's templates and base locales are — nothing edits them at runtime.
  *
  * The one consequence worth knowing: editing a stylesheet under `theme/styles/` inside `/form-builder/` *during* a
  * `~run` session hits exactly the staleness that comment describes. Republish (`sbt publishLocal`) and restart the app
  * rather than expecting a live reload.
  */
object FormBuilderAssets {

  /** Where these assets live on the classpath. */
  val resourceRoot = "/form-builder/website-static"

  /** Where they land beneath a generated site's `resources/`.
    *
    * Spelled once here, but note that the templates loading these files hardcode the matching URL
    * (`${basePath}/resources/vendor/form-builder/...`) — Thymeleaf cannot read a Scala constant. Changing this means
    * grepping the templates for `vendor/form-builder`.
    *
    * `vendor/` rather than a directory of its own so the assets sit beside the app's other vendored packages, which is
    * also what keeps the theme's stylesheet-relative icon URLs (`../../../../uswds-3.13.0/img/…`) resolving: four
    * levels up from `vendor/form-builder/theme/styles/<dir>/` is `vendor/`, exactly as it was from
    * `vendor/taxpert/theme/styles/<dir>/`. Do not flatten the tree.
    */
  val vendorPath: Seq[String] = Seq("vendor", "form-builder")

  /** Extract the library's assets into a generated site's `resources/` directory. */
  def extractInto(resourcesDir: os.Path): Unit =
    extractTo(vendorPath.foldLeft(resourcesDir)(_ / _))

  /** As above, to an explicit destination. */
  def extractTo(target: os.Path): Unit = {
    val url = Option(classOf[FormBuilderAssetsAnchor].getResource(resourceRoot)).getOrElse {
      throw new IllegalStateException(
        s"$resourceRoot is missing from the form-builder jar. The theme and the flow runtime ship as classpath " +
          "resources; a build that cannot find them would silently generate a site with no CSS.",
      )
    }

    // Both branches are load-bearing. Running inside /form-builder (`sbt test`) the resources are loose files under
    // target/.../classes, so the URL is a `file:`; an app consuming the published jar gets a `jar:`.
    url.getProtocol match {
      case "file" => copyTree(java.nio.file.Paths.get(url.toURI), target)
      case "jar"  => withJarFileSystem(url.toURI)(fs => copyTree(fs.getPath(resourceRoot), target))
      case other  =>
        throw new IllegalStateException(s"Cannot read $resourceRoot from a '$other' URL ($url)")
    }
  }

  /** Run `body` against the filesystem of the jar `uri` points into.
    *
    * A jar's `FileSystem` is a JVM-wide singleton keyed on the jar, so opening one that is already open throws rather
    * than returning it. Close only the one we opened: Author Mode re-runs this whole pipeline in-process, and closing a
    * filesystem another caller still holds would break them rather than us.
    */
  private def withJarFileSystem[A](uri: java.net.URI)(body: FileSystem => A): A = {
    val opened =
      try Some(FileSystems.newFileSystem(uri, java.util.Collections.emptyMap[String, String]()))
      catch { case _: FileSystemAlreadyExistsException => None }

    opened match {
      case Some(fs) =>
        try body(fs)
        finally fs.close()
      case None => body(FileSystems.getFileSystem(uri))
    }
  }

  private def copyTree(source: JavaPath, target: os.Path): Unit = {
    os.makeDir.all(target)
    val entries = Files.walk(source)
    try
      entries.iterator().asScala.foreach { entry =>
        // Rebuild the path segment by segment rather than splitting a string: `entry` may belong to a jar
        // filesystem, whose paths cannot be resolved against an os.Path (a default-filesystem type) directly, and
        // whose separator is not necessarily the host's.
        //
        // The empty-segment filter is not defensive: `Files.walk` yields `source` itself first, and an empty
        // relative path iterates as a single empty name, which os-lib rejects as a path segment.
        val segments = source.relativize(entry).iterator().asScala.map(_.toString).filter(_.nonEmpty)
        val destination = segments.foldLeft(target)(_ / _)
        if (Files.isDirectory(entry)) os.makeDir.all(destination)
        else {
          os.makeDir.all(destination / os.up)
          Files.copy(entry, destination.toNIO, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    finally entries.close()
  }
}
