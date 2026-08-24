package gov.irs.formbuilder

import scala.collection.immutable.ListMap

/** The application the library's own specs are generated against.
  *
  * Pet Planner is a fictional non-tax product, so this library cannot quietly grow a dependency on a real app's domain.
  * If a spec can only be made to pass by encoding something tax-specific, that behavior probably belongs in an app.
  *
  * Two locales rather than one so the rule that the default language is generated at the root and every other under its
  * own segment stays exercised, and rather than eight because each locale is a fixture file to maintain.
  */
object FixtureApp {
  val app: FormBuilderApp = FormBuilderApp(
    appId = "pet-planner",
    basePath = "/app/pet-planner",
    outSubdir = "app/pet-planner",
    locales = ListMap("en" -> "English", "es" -> "Español"),
    defaultPort = 3999,
    brand = "Pet Planner",
    resourceRoot = os.pwd / "src" / "test" / "resources",
  )

  given FormBuilderApp = app
}
