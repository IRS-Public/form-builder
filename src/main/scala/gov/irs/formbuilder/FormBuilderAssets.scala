package gov.irs.formbuilder

import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path as JavaPath
import java.nio.file.StandardCopyOption
import scala.jdk.CollectionConverters.*

/** Anchor for the resource lookup in [[FormBuilderAssets]]. A real declared class, because a lookup anchored on a
  * synthetic holder does not reliably reach this jar's resources.
  */
final private class FormBuilderAssetsAnchor

/** Extracts the library's browser assets, the theme stylesheets and the flow runtime, out of this jar into a generated
  * site's `resources/vendor/form-builder/`.
  *
  * They ship as classpath resources beside the templates that reference them, so a template and the asset it loads stay
  * in one versioned artifact. Nothing edits them at runtime, which is why they do not take the from-disk path that
  * flow, facts and app locales do. The consequence is that editing a stylesheet during an sbt `~run` session serves the
  * previous copy. Run `sbt publishLocal` and restart the app.
  *
  * Two destination invariants, both pinned by `FormBuilderAssetsSpec` and explained in
  * `docs/internals/app-entry-and-assets.md`: templates hardcode the matching URL because Thymeleaf cannot read a Scala
  * constant, and the tree must not be flattened because the theme reaches USWDS icons through stylesheet-relative URLs.
  */
object FormBuilderAssets {

  val resourceRoot = "/form-builder/website-static"

  /** Changing this means grepping the template tree for `vendor/form-builder`. */
  val vendorPath: Seq[String] = Seq("vendor", "form-builder")

  def extractInto(resourcesDir: os.Path): Unit =
    extractTo(vendorPath.foldLeft(resourcesDir)(_ / _))

  def extractTo(target: os.Path): Unit = {
    val url = Option(classOf[FormBuilderAssetsAnchor].getResource(resourceRoot)).getOrElse {
      throw new IllegalStateException(
        s"$resourceRoot is missing from the form-builder jar. The theme and the flow runtime ship as classpath " +
          "resources; a build that cannot find them would silently generate a site with no CSS.",
      )
    }

    // `file:` inside this repo, `jar:` for an app consuming the published artifact. Both are reached.
    url.getProtocol match {
      case "file" => copyTree(java.nio.file.Paths.get(url.toURI), target)
      case "jar"  => withJarFileSystem(url.toURI)(fs => copyTree(fs.getPath(resourceRoot), target))
      case other  =>
        throw new IllegalStateException(s"Cannot read $resourceRoot from a '$other' URL ($url)")
    }
  }

  /** A jar's `FileSystem` is a JVM-wide singleton, so opening an already-open one throws. Close only the one this call
    * opened, since Author Mode re-runs the pipeline in-process and another caller may still hold it.
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
        // Segment by segment, because a jar filesystem's paths do not resolve against an os.Path and its
        // separator is not necessarily the host's. The empty filter is required rather than defensive:
        // `Files.walk` yields `source` first, and an empty relative path iterates as one empty name.
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
