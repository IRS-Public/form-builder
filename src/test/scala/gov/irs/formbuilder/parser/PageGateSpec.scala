package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.FixtureApp
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers.*

/** `Page.gatingCondition`: the condition under which a page has anything to show.
  *
  * fg-navigator.js reads it out of flow-manifest.json and skips the page when it evaluates false, so what is pinned
  * here is which pages get one. The failure it exists to prevent is a page rendering as an empty `<main>` with a Next
  * button under it, which is what a page whose whole body is conditional does when the condition is false.
  */
class PageGateSpec extends AnyFunSpec {

  private val dictionaryConfig = <FactDictionaryModule>
    <Facts>
      <Fact path="/hasPet">
        <Name>Has a pet</Name>
        <Writable><Boolean/></Writable>
      </Fact>
      <Fact path="/hasDog">
        <Name>Has a dog</Name>
        <Writable><Boolean/></Writable>
      </Fact>
      <Fact path="/petName">
        <Name>Pet name</Name>
        <Writable><String/></Writable>
      </Fact>
    </Facts>
  </FactDictionaryModule>

  private val factDictionary = FactDictionary.fromXml(dictionaryConfig)

  private def pageOf(body: scala.xml.Elem): Page =
    Flow
      .fromXmlConfig(
        <FlowConfig><page route="/pets" title="Pets">{body}</page></FlowConfig>,
        factDictionary,
        FixtureApp.app,
      )
      .pages
      .head

  private def gateOf(body: scala.xml.Elem): Option[Condition] = pageOf(body).gatingCondition

  /** The same page as PageSplitter would have emitted it, which is the only shape the single-question rule applies to.
    */
  private def splitGateOf(body: scala.xml.Elem): Option[Condition] = {
    val page = pageOf(body)
    page.copy(sourcePageRoute = Some(page.route)).gatingCondition
  }

  private val nameQuestion = <fg-set path="/petName"><question>Name</question><input type="text"/></fg-set>

  describe("a page whose whole body is one conditional block") {
    it("is gated on that block's condition, with no question anywhere on it") {
      gateOf(<section>
        <div class="screen" condition="/hasPet" operator="isTrue">
          <h2>Thanks for confirming.</h2>
          <p>A few more questions about your pet.</p>
        </div>
      </section>) shouldBe Some(Condition("/hasPet", ConditionOperator.isTrue))
    }

    it("is gated when the block holds several questions, which the single-question rule would not gate") {
      gateOf(<section>
        <div class="screen" condition="/hasPet" operator="isTrue">
          {nameQuestion}
          <fg-set path="/hasDog"><question>A dog?</question><input type="boolean"/></fg-set>
        </div>
      </section>) shouldBe Some(Condition("/hasPet", ConditionOperator.isTrue))
    }

    it("sees through the <section> wrapper and past a modal") {
      gateOf(<section>
        <div class="screen" condition="/hasPet" operator="isTrue"><h2>Thanks.</h2></div>
        <modal-dialog id="m1"><modal-heading>Why?</modal-heading><modal-content><p>Because.</p></modal-content></modal-dialog>
      </section>) shouldBe Some(Condition("/hasPet", ConditionOperator.isTrue))
    }
  }

  describe("a page that still has something to show") {
    it("is ungated when a block carries no condition") {
      gateOf(<section>
        <div class="screen" condition="/hasPet" operator="isTrue"><h2>Thanks.</h2></div>
        <div class="screen"><h2>Read this either way.</h2></div>
      </section>) shouldBe None
    }

    it("is ungated when two blocks carry different conditions") {
      gateOf(<section>
        <div class="screen" condition="/hasPet" operator="isTrue"><h2>A pet.</h2></div>
        <div class="screen" condition="/hasDog" operator="isTrue"><h2>A dog.</h2></div>
      </section>) shouldBe None
    }

    it("is ungated when it is prose with no condition at all") {
      gateOf(<section><div class="screen"><h2>Welcome.</h2></div></section>) shouldBe None
    }
  }

  private val oneConditionalQuestion: scala.xml.Elem = <section>
      <p>Some unconditional introduction.</p>
      <fg-set path="/petName" if-true="/hasPet"><question>Name</question><input type="text"/></fg-set>
    </section>

  describe("the single-question rule, which only a split page gets") {
    it("gates a split page whose one question is conditional") {
      splitGateOf(oneConditionalQuestion) shouldBe Some(Condition("/hasPet", ConditionOperator.isTrue))
    }

    // The rule ignores the introduction, which is the splitter's own prose to ignore. On an authored page that
    // introduction is content, and credit-assistant's /qualifying-children puts a whole alert there.
    it("leaves the same page ungated when it was authored rather than split") {
      gateOf(oneConditionalQuestion) shouldBe None
    }

    it("leaves a two-question page ungated when one of the questions always shows") {
      splitGateOf(<section>
        <fg-set path="/petName" if-true="/hasPet"><question>Name</question><input type="text"/></fg-set>
        <fg-set path="/hasDog"><question>A dog?</question><input type="boolean"/></fg-set>
      </section>) shouldBe None
    }

    // The single-question rule declines this one, and sharedContentCondition catches it: both questions
    // hang on the same fact, so a false /hasPet leaves the page with nothing on it either way.
    it("gates a two-question page when both questions hang on the same condition") {
      gateOf(<section>
        <fg-set path="/petName" if-true="/hasPet"><question>Name</question><input type="text"/></fg-set>
        <fg-set path="/hasDog" if-true="/hasPet"><question>A dog?</question><input type="boolean"/></fg-set>
      </section>) shouldBe Some(Condition("/hasPet", ConditionOperator.isTrue))
    }
  }
}
