// Turns an element's `if-true` / `if-false` attributes into a (path, operator) pair, checking that
// the named fact exists and is Boolean.
//
// ConditionOperator's case names reach the browser verbatim as `operator="..."`, so they are part of
// the runtime contract. Renaming one means changing fg-conditions.js in the same commit.
// Long-form: docs/internals/flow-parsing-and-generation.md

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.parser.Utils.optionString
import gov.irs.formbuilder.parser.Utils.validateFact

enum ConditionOperator {
  case isTrue
  case isFalse
  case isTrueAndComplete
  case isZero
  case isGreaterThanZero
  case isIncomplete
  case notHasValue
}

object ConditionOperator:
  def fromAttribute(operatorString: String): ConditionOperator =
    values
      .find(it => it.toString.equals(operatorString))
      .getOrElse(throw new IllegalArgumentException(s"operatorString $operatorString was invalid."))

case class Condition(path: String, operator: ConditionOperator)

object Condition {
  def getCondition(node: xml.Node, factDictionary: FactDictionary): Option[Condition] = {
    val path = optionString(node \@ "path")
    val ifTrue = optionString(node \@ "if-true")
    val ifFalse = optionString(node \@ "if-false")

    if (ifTrue.isDefined & ifFalse.isDefined) {
      throw InvalidFormConfig(s"Path $path has both an if-true condition and a if-false condition defined")
    }
    validateCondition(factDictionary, ifTrue)
    validateCondition(factDictionary, ifFalse)

    if (ifTrue.isDefined) {
      return Option(Condition(ifTrue.get, ConditionOperator.isTrue))
    } else if (ifFalse.isDefined) {
      return Option(Condition(ifFalse.get, ConditionOperator.isFalse))
    }

    None
  }

  private def validateCondition(factDictionary: FactDictionary, conditionPath: Option[String]): Unit =
    if (conditionPath.isDefined) {
      val condition = conditionPath.get
      validateFact(condition, factDictionary)

      if (factDictionary.getDefinition(condition).isBoolean == false) {
        throw InvalidFormConfig(s"Condition $condition must be of type Boolean")
      }
    }
}
