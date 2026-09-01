package gov.irs.formbuilder.generators

import gov.irs.formbuilder.parser.{ Flow, Page }
import gov.irs.formbuilder.FormBuilderApp
import io.circe.syntax.*
import io.circe.Json

/** The JSON array fg-navigator.js reads from `{basePath}/resources/flow-manifest.json`, one entry per rendered page.
  * Written by every build, in the default locale only. The field list is in
  * docs/internals/flow-parsing-and-generation.md.
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
