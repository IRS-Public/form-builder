package gov.irs.formbuilder.authoring

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** `FactDictionary` validation appends the offending node's whole `CompNodeConfig(…)` toString after its human
  * sentence. As one unbroken token it floods Author Mode's inline error box during ordinary mid-edit states, so
  * `factGraphMessage` must keep only the sentence. See docs/internals/author-mode.md.
  */
class FactGraphMessageSpec extends AnyFunSpec with Matchers {

  describe("AuthoringServer.factGraphMessage") {
    it("keeps only the sentence before the CompNodeConfig dump") {
      val raw =
        "<Subtract> must have at least one <Subtrahends>: " +
          "CompNodeConfig(Subtract,List(CompNodeConfig(Minuend,List(CompNodeConfig(Dependency,List(),List())))," +
          "CompNodeConfig(Subtrahends,List(),List())),List())"
      AuthoringServer.factGraphMessage(new RuntimeException(raw)) shouldBe
        "<Subtract> must have at least one <Subtrahends>"
    }

    it("leaves a message with no CompNodeConfig dump untouched") {
      val raw = "Cyclic dependency detected at /foo"
      AuthoringServer.factGraphMessage(new RuntimeException(raw)) shouldBe raw
    }

    it("falls back to the exception class name when there is no message") {
      AuthoringServer.factGraphMessage(new RuntimeException()) shouldBe "RuntimeException"
    }
  }
}
