package gov.irs.formative.build

object Flags {
  val serve = "serve"
  val allScreens = "allScreens"
  val auditMode = "auditMode"
  val singleQuestionPerScreen = "singleQuestionPerScreen"
  val scenarioMode = "scenarioMode"
  val authorMode = "authorMode"
  // The two AI features are flagged separately — generation writes a whole Fact Graph from a
  // prompt, explanation only reads facts back, and they ship on their own timelines. Each is the
  // build-time default for the matching runtime flag in taxpert's feature-flags.js, handed to
  // the panel by fragments/audit-panel.html; the Workspace settings modal can override either.
  val aiScenarioGeneration = "aiScenarioGeneration"
  val aiFactExplanation = "aiFactExplanation"
}
