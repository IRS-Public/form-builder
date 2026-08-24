// Small helpers shared by the node parsers: empty-string-to-Option, and the fact-existence check
// every element that names a fact path runs.

package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig

object Utils {
  def optionString(string: String): Option[String] =
    if (string.isEmpty) None else Option(string)

  /** Throw [[InvalidFormConfig]] unless `path` (e.g. `/totalIncome`) names a fact in the dictionary. */
  def validateFact(path: String, factDictionary: FactDictionary): Unit = {
    if (path.isEmpty) {
      throw InvalidFormConfig("A fact path for validation was expected but not provided")
    }
    val factDefinition = factDictionary.getDefinition(path)
    if (factDefinition == null) {
      throw InvalidFormConfig(s"$path not found in the fact dictionary")
    }
  }
}
