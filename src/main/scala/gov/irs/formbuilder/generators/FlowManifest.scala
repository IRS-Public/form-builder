package gov.irs.formbuilder.generators

import gov.irs.formbuilder.parser.{ Flow, Page }
import gov.irs.formbuilder.FormBuilderApp
import io.circe.syntax.*
import io.circe.Json

/** Emits a JSON manifest used by client-side navigation in single-question-per-screen mode.
  *
  * Each entry describes one rendered page:
  *   - route: the static URL path (e.g. "/about-you/chosen-tax-year")
  *   - href: the full URL the browser navigates to (locale-aware)
  *   - gatePath / gateOperator: the gating condition for this page (the lone question's condition). Null when the page
  *     has no condition (e.g., the first question).
  *   - knockoutPaths: condition paths of any visible knockout alerts on this page; navigation evaluates them on every
  *     Next click to decide whether to block.
  *   - sourceRoute: the original (pre-explode) page route, used for topic-grouped stepper math.
  *   - exclude: whether the page is excluded from the stepper.
  *
  * The JS reads this once per session from `{basePath}/resources/flow-manifest.json`.
  */
object FlowManifest {
  def buildJson(flow: Flow, languageCode: String, app: FormBuilderApp): Json =
    Json.fromValues(flow.pages.map(page => pageJson(page, languageCode, app)))

  private def pageJson(page: Page, languageCode: String, app: FormBuilderApp): Json = {
    val gate = page.gatingCondition
    Json.obj(
      "route" -> Json.fromString(page.route),
      "href" -> Json.fromString(page.href(languageCode, app)),
      "gatePath" -> gate.map(c => Json.fromString(c.path)).getOrElse(Json.Null),
      "gateOperator" -> gate.map(c => Json.fromString(c.operator.toString)).getOrElse(Json.Null),
      "knockoutPaths" -> Json.fromValues(page.knockoutConditionPaths.map(Json.fromString)),
      "sourceRoute" -> Json.fromString(page.stepperRoute),
      "exclude" -> Json.fromBoolean(page.exclude),
    )
  }
}
