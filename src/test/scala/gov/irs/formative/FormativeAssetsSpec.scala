package gov.irs.formative

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers.*

/** Coverage for the one mechanism in this library that reads a resource *tree* rather than a single resource.
  *
  * Everything else on the classpath here is fetched one file at a time by a name someone typed (`Locale`'s
  * `getResourceAsStream`, Thymeleaf's template resolvers). Walking a directory is different in kind, because it behaves
  * differently depending on whether the library is a directory of loose classfiles (`sbt test`, which is what these
  * assertions run against) or a jar (every consuming app). A spec cannot easily reach the second case, so what it can
  * do is pin the invariants that a silent failure in either would break.
  *
  * "Silent" is the operative word: an extraction that quietly wrote nothing would produce a site whose every page loads
  * a stylesheet that 404s, and no build step would fail.
  */
class FormativeAssetsSpec extends AnyFunSpec {

  private def extractToTempDir(): os.Path = {
    val target = os.temp.dir(prefix = "formative-assets-spec")
    FormativeAssets.extractTo(target)
    target
  }

  describe("extractTo") {
    it("writes the theme's import root") {
      val target = extractToTempDir()
      assert(os.exists(target / "theme" / "styles" / "theme.css"))
    }

    it("writes every stylesheet the theme imports, not just the root") {
      val target = extractToTempDir()
      // The barrel is a list of @imports; a copy that stopped at the top level would leave each one 404ing.
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
      // this file lands in has to be the directory the extraction root itself sits in — i.e. `vendor/`. Flattening
      // the tree would leave the icons silently unstyled rather than erroring.
      val utilities = target / "theme" / "styles" / "utilities" / "utilities.css"
      assert(os.exists(utilities))
      assert(os.read(utilities).contains("../../../../uswds-3.13.0/img/"))

      // A relative URL in CSS resolves against the *directory* the stylesheet is in, so walk up from there.
      // `target` stands in for `vendor/formative`, which makes `target / os.up` the `vendor/` that has to be
      // where those four `../` land — it is where the app's own uswds-3.13.0 directory sits.
      val stylesheetDir = utilities / os.up
      assert(stylesheetDir / os.up / os.up / os.up / os.up == target / os.up)
    }

    it("copies file contents rather than creating empty placeholders") {
      val target = extractToTempDir()
      os.read(target / "theme" / "styles" / "theme.css") should include("@import \"variables.css\";")
    }

    it("is idempotent, because Author Mode re-runs the whole build in-process") {
      val target = os.temp.dir(prefix = "formative-assets-spec-twice")
      FormativeAssets.extractTo(target)
      FormativeAssets.extractTo(target)
      assert(os.exists(target / "theme" / "styles" / "theme.css"))
    }
  }

  describe("extractInto") {
    it("places the assets under vendor/formative, beside an app's own vendored packages") {
      val resources = os.temp.dir(prefix = "formative-assets-spec-resources")
      FormativeAssets.extractInto(resources)
      assert(os.exists(resources / "vendor" / "formative" / "theme" / "styles" / "theme.css"))
    }

    it("leaves the rest of the resources directory alone") {
      val resources = os.temp.dir(prefix = "formative-assets-spec-merge")
      // Stand in for what os.copy(app.websiteStaticDir, …) has already written by the time this runs — including
      // a sibling under vendor/, which is the case that would break if the extraction wiped the directory.
      os.write(resources / "styles" / "main.css", "@import \"x\";", createFolders = true)
      os.write(resources / "vendor" / "uswds-3.13.0" / "keep.txt", "keep", createFolders = true)

      FormativeAssets.extractInto(resources)

      assert(os.exists(resources / "styles" / "main.css"))
      assert(os.exists(resources / "vendor" / "uswds-3.13.0" / "keep.txt"))
      assert(os.exists(resources / "vendor" / "formative" / "theme" / "styles" / "theme.css"))
    }
  }
}
