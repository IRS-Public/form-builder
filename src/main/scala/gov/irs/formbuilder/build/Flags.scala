package gov.irs.formbuilder.build

object Flags {
  val serve = "serve"
  val allScreens = "allScreens"
  val auditMode = "auditMode"
  val singleQuestionPerScreen = "singleQuestionPerScreen"
  val scenarioMode = "scenarioMode"
  // Also the build-time default for the runtime `author-mode` feature flag in taxpert's
  // feature-flags.js (handed to the panel by fragments/audit-panel.html), which an app can use to
  // gate its "Authoring Suite" nav item — see credit-assistant's taxpert-config.html.
  val authorMode = "authorMode"
  // The two AI features are flagged separately — generation writes a whole Fact Graph from a
  // prompt, explanation only reads facts back, and they ship on their own timelines. Each is the
  // build-time default for the matching runtime flag in taxpert's feature-flags.js, handed to
  // the panel by fragments/audit-panel.html; the Workspace settings modal can override either.
  val aiScenarioGeneration = "aiScenarioGeneration"
  val aiFactExplanation = "aiFactExplanation"

  /** Emit `resources/form-builder-graph.json` — the Form Builder Graph Model that Fact Explorer reads. Off by default:
    * it is a development aid, and a production build is the flow and nothing else.
    */
  val formBuilderGraph = "formBuilderGraph"

  // A note for whoever adds the next flag. The cookiecutter's post_gen_project.py removes a flag from the generated
  // Makefile with a bare `text.replace(" " + flag, "")`, so a new name that is a *prefix* of an existing one silently
  // corrupts it — adding `--scenario` would leave `Mode` behind in every `sbt run` line that had `--scenarioMode`.
  // None of the names above collide that way; keep it so.
}
