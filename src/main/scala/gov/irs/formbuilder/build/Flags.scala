package gov.irs.formbuilder.build

/** The string constants for every `--flag` [[gov.irs.formbuilder.FormBuilder.run]] accepts.
  *
  * A NEW NAME MUST NOT BE A PREFIX OF AN EXISTING ONE. The cookiecutter's `post_gen_project.py` strips a flag out of a
  * generated Makefile with a bare string replace, so `--scenario` would leave `Mode` stranded in every line that had
  * `--scenarioMode`.
  *
  * What each flag does: `docs/internals/app-entry-and-assets.md` and `docs/ARCHITECTURE.md`.
  */
object Flags {
  val serve = "serve"
  val allScreens = "allScreens"
  val auditMode = "auditMode"
  val singleQuestionPerScreen = "singleQuestionPerScreen"
  val scenarioMode = "scenarioMode"
  // Also the build-time default for the runtime `author-mode` feature flag the workspace reads, handed to the panel
  // by fragments/audit-panel.html.
  val authorMode = "authorMode"
  // Separate flags: the two features do different things and ship on their own timelines. Each is the build-time
  // default for the matching runtime flag, which the Workspace settings modal can override.
  val aiScenarioGeneration = "aiScenarioGeneration"
  val aiFactExplanation = "aiFactExplanation"

  /** Emit `resources/form-builder-graph.json`, the Form Graph Model that Fact Explorer reads. Off by default, since a
    * production build carries the flow and nothing else.
    */
  val formBuilderGraph = "formBuilderGraph"
}
