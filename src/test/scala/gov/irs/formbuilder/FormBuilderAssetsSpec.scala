package gov.irs.formbuilder

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers.*

/** Coverage for the one mechanism in this library that reads a resource *tree* rather than a single named resource.
  *
  * These assertions run against loose classfiles (`sbt test`); a consuming app gets the jar branch, which a spec here
  * cannot easily reach. What it can do is pin the invariants a silent failure in either branch would break: an
  * extraction that wrote nothing, wrote only the top level, wrote empty files, flattened the nesting, or wiped siblings
  * would all produce a site whose pages 404 their stylesheets with no build step failing.
  *
  * See `docs/internals/app-entry-and-assets.md`.
  */
class FormBuilderAssetsSpec extends AnyFunSpec {

  private def extractToTempDir(): os.Path = {
    val target = os.temp.dir(prefix = "form-builder-assets-spec")
    FormBuilderAssets.extractTo(target)
    target
  }

  describe("extractTo") {
    it("writes the theme's import root") {
      val target = extractToTempDir()
      assert(os.exists(target / "theme" / "styles" / "theme.css"))
    }

    it("writes every stylesheet the theme imports, not just the root") {
      val target = extractToTempDir()
      // theme.css is a list of @imports, so a copy that stopped at the top level would leave each one 404ing.
      val extracted = os.walk(target).filter(os.isFile).map(_.relativeTo(target).toString)
      extracted should contain allOf (
        "theme/styles/variables.css",
        "theme/styles/layout/header.css",
        "theme/styles/components/buttons.css",
        "theme/styles/utilities/utilities.css",
      )
    }

    it("preserves the nesting depth the theme's icon URLs are written against") {
      val target = extractToTempDir()
      // utilities.css reaches USWDS' icons as `../../../../uswds-3.13.0/img/…`. Four levels up from the directory
      // this file lands in has to be the directory the extraction root itself sits in, i.e. `vendor/`. Flattening the
      // tree would leave the icons silently unstyled rather than erroring.
      val utilities = target / "theme" / "styles" / "utilities" / "utilities.css"
      assert(os.exists(utilities))
      assert(os.read(utilities).contains("../../../../uswds-3.13.0/img/"))

      // A relative URL in CSS resolves against the *directory* the stylesheet is in, so walk up from there. `target`
      // stands in for `vendor/form-builder`, which makes `target / os.up` the `vendor/` those four `../` have to land
      // on, beside the app's own uswds-3.13.0 directory.
      val stylesheetDir = utilities / os.up
      assert(stylesheetDir / os.up / os.up / os.up / os.up == target / os.up)
    }

    it("copies file contents rather than creating empty placeholders") {
      val target = extractToTempDir()
      os.read(target / "theme" / "styles" / "theme.css") should include("@import \"variables.css\";")
    }

    it("is idempotent, because Author Mode re-runs the whole build in-process") {
      val target = os.temp.dir(prefix = "form-builder-assets-spec-twice")
      FormBuilderAssets.extractTo(target)
      FormBuilderAssets.extractTo(target)
      assert(os.exists(target / "theme" / "styles" / "theme.css"))
    }
  }

  describe("extractInto") {
    it("places the assets under vendor/form-builder, beside an app's own vendored packages") {
      val resources = os.temp.dir(prefix = "form-builder-assets-spec-resources")
      FormBuilderAssets.extractInto(resources)
      assert(os.exists(resources / "vendor" / "form-builder" / "theme" / "styles" / "theme.css"))
    }

    it("leaves the rest of the resources directory alone") {
      val resources = os.temp.dir(prefix = "form-builder-assets-spec-merge")
      // Stand in for what os.copy(app.websiteStaticDir, …) has already written by the time this runs, including a
      // sibling under vendor/, which is what would break if the extraction wiped the directory.
      os.write(resources / "styles" / "main.css", "@import \"x\";", createFolders = true)
      os.write(resources / "vendor" / "uswds-3.13.0" / "keep.txt", "keep", createFolders = true)

      FormBuilderAssets.extractInto(resources)

      assert(os.exists(resources / "styles" / "main.css"))
      assert(os.exists(resources / "vendor" / "uswds-3.13.0" / "keep.txt"))
      assert(os.exists(resources / "vendor" / "form-builder" / "theme" / "styles" / "theme.css"))
    }
  }
}
