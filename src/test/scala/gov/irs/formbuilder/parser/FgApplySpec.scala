package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.generators.Website
import gov.irs.formbuilder.FixtureApp
import gov.irs.formbuilder.FixtureApp.given
import org.jsoup.Jsoup
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers.*

/** `<fg-apply>` takes exactly one of `value` and `source`.
  *
  * `source` is the newer half — copying one fact into another, rather than writing a literal — and what is worth
  * pinning is the exclusivity in both directions. Neither attribute is a node that renders and does nothing; both is an
  * author who meant one of them, and a parser that quietly preferred either would turn a typo into a wrong answer on
  * the page.
  */
class FgApplySpec extends AnyFunSpec {

  private val dictionaryConfig = <FactDictionaryModule>
    <Facts>
      <Fact path="/filerState">
        <Name>Filer state</Name>
        <Writable><String/></Writable>
      </Fact>
      <Fact path="/spouseState">
        <Name>Spouse state</Name>
        <Writable><String/></Writable>
      </Fact>
      <Fact path="/isMarried">
        <Name>Married</Name>
        <Writable><Boolean/></Writable>
      </Fact>
    </Facts>
  </FactDictionaryModule>

  private val factDictionary = FactDictionary.fromXml(dictionaryConfig)

  private def parse(flowConfig: scala.xml.Elem) =
    Flow.fromXmlConfig(flowConfig, factDictionary, FixtureApp.app)

  private def render(flowConfig: scala.xml.Elem) =
    Jsoup.parse(Website.generate(parse(flowConfig), dictionaryConfig, Map()).pages.head.content)

  describe("a literal value") {
    val flowConfig = <FlowConfig>
      <page route="/" title="Literal">
        <section>
          <fg-apply path="/isMarried" value="true"/>
        </section>
      </page>
    </FlowConfig>

    it("renders the value and no source") {
      val applied = render(flowConfig).body().select("fg-apply").first()

      applied.attr("path") shouldBe "/isMarried"
      applied.attr("value") shouldBe "true"
      applied.hasAttr("source") shouldBe false
    }
  }

  describe("a source fact") {
    val flowConfig = <FlowConfig>
      <page route="/" title="Source">
        <section>
          <fg-apply path="/spouseState" source="/filerState"/>
        </section>
      </page>
    </FlowConfig>

    it("renders the source and no value") {
      val applied = render(flowConfig).body().select("fg-apply").first()

      applied.attr("path") shouldBe "/spouseState"
      applied.attr("source") shouldBe "/filerState"
      applied.hasAttr("value") shouldBe false
    }

    it("checks the source against the fact dictionary, like the path") {
      val unknownSource = <FlowConfig>
        <page route="/" title="Source">
          <section>
            <fg-apply path="/spouseState" source="/noSuchFact"/>
          </section>
        </page>
      </FlowConfig>

      val thrown = intercept[InvalidFormConfig](parse(unknownSource))
      thrown.getMessage should include("/noSuchFact")
    }
  }

  describe("neither, and both") {
    it("rejects an fg-apply with neither value nor source") {
      val neither = <FlowConfig>
        <page route="/" title="Neither">
          <section>
            <fg-apply path="/isMarried"/>
          </section>
        </page>
      </FlowConfig>

      val thrown = intercept[InvalidFormConfig](parse(neither))
      thrown.getMessage should include("has neither")
    }

    it("rejects an fg-apply carrying both") {
      val both = <FlowConfig>
        <page route="/" title="Both">
          <section>
            <fg-apply path="/spouseState" value="CA" source="/filerState"/>
          </section>
        </page>
      </FlowConfig>

      val thrown = intercept[InvalidFormConfig](parse(both))
      thrown.getMessage should include("exactly one")
    }
  }
}
