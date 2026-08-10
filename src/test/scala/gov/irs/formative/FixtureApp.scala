package gov.irs.formative

import scala.collection.immutable.ListMap

/** The application the library's own specs are generated against.
  *
  * Pet Planner is not a tax product and shares nothing with either of the real ones. That is the point: it is the same
  * device `taxpert`'s `tests/fixtures/host/` uses on the browser half, and it holds this half to the same standard. A
  * spec that can only be made to pass by knowing about tax has found something that does not belong in a scaffold.
  *
  * Two locales rather than one, because "the default language is generated at the root and every other under its own
  * segment" is a rule worth exercising, and rather than eight because each one is a fixture file to maintain.
  */
object FixtureApp {
  val app: FormativeApp = FormativeApp(
    appId = "pet-planner",
    basePath = "/app/pet-planner",
    outSubdir = "app/pet-planner",
    locales = ListMap("en" -> "English", "es" -> "Español"),
    defaultPort = 3999,
    brand = "Pet Planner",
    resourceRoot = os.pwd / "src" / "test" / "resources",
  )

  given FormativeApp = app
}
