package gov.irs.formbuilder.parser

import gov.irs.factgraph.FactDictionary
import gov.irs.formbuilder.exceptions.InvalidFormConfig
import gov.irs.formbuilder.generators.Website
import gov.irs.formbuilder.FixtureApp
import gov.irs.formbuilder.FixtureApp.given
import org.jsoup.Jsoup
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers.*

/** `<fg-collection readonly="true">`: iterating a collection the taxpayer cannot change.
  *
  * A `<Derived>` collection's membership is decided by another fact, so the Add button and the per-item Remove control
  * are not merely redundant on one — they are wrong. Adding writes to the collection fact, which a derived fact does
  * not accept, so the button is a crash rather than a no-op.
  *
  * What is pinned here is that `readonly` removes both controls, that the default keeps them, and that an attribute
  * which only dresses the Add button is refused alongside it rather than silently ignored.
  */
class FgCollectionSpec extends AnyFunSpec {

  private val dictionaryConfig = <FactDictionaryModule>
    <Facts>
      <Fact path="/pets">
        <Name>Pets</Name>
        <Writable><Collection/></Writable>
      </Fact>
      <Fact path="/pets/*/needsWalking">
        <Name>Needs walking</Name>
        <Writable><Boolean/></Writable>
      </Fact>
      <Fact path="/dogs">
        <Name>Dogs</Name>
        <Derived>
          <Filter path="/pets"><Dependency path="needsWalking"/></Filter>
        </Derived>
      </Fact>
    </Facts>
  </FactDictionaryModule>

  private val factDictionary = FactDictionary.fromXml(dictionaryConfig)

  private def render(flowConfig: scala.xml.Elem) =
    Jsoup.parse(
      Website
        .generate(Flow.fromXmlConfig(flowConfig, factDictionary, FixtureApp.app), dictionaryConfig, Map())
        .pages
        .head
        .content,
    )

  private def page(collection: scala.xml.Elem) = <FlowConfig>
    <page route="/" title="Pets">
      <section>{collection}</section>
    </page>
  </FlowConfig>

  describe("an editable collection") {
    val html = render(page(<fg-collection path="/pets" item-name="pet" determiner="another">
      <fg-set path="/pets/*/needsWalking"><question>Needs walking?</question><input type="boolean"/></fg-set>
    </fg-collection>))

    it("renders the add button") {
      html.select(".fg-collection__add-item").size() shouldBe 1
    }

    it("renders the remove control and its confirmation modal") {
      // The item template is inert markup until the runtime clones it, so read it as text.
      html.select("template.fg-collection__item-template").html() should include("fg-collection-item__remove-item")
      html.select(".fg-collection__remove-item-modal__button-confirm").size() shouldBe 1
    }

    it("carries no readonly attribute") {
      html.select("fg-collection").first().hasAttr("readonly") shouldBe false
    }
  }

  describe("a readonly collection") {
    val html = render(page(<fg-collection path="/dogs" item-name="dog" readonly="true">
      <fg-set path="/pets/*/needsWalking"><question>Needs walking?</question><input type="boolean"/></fg-set>
    </fg-collection>))

    it("renders no add button") {
      html.select(".fg-collection__add-item").size() shouldBe 0
    }

    it("renders no remove control and no confirmation modal") {
      html.select("template.fg-collection__item-template").html() should not include "fg-collection-item__remove-item"
      html.select(".fg-collection__remove-item-modal__button-confirm").size() shouldBe 0
    }

    it("still renders the item template's fields, once") {
      html.select("template.fg-collection__item-template").html() should include("/pets/*/needsWalking")
    }

    // Nothing marks the element readonly in the rendered HTML, deliberately: the missing Add button is
    // the whole of the difference, and the runtime reads it by asking whether the button is there. An
    // attribute no one reads is the failure this library already refuses above.
  }

  describe("readonly alongside an attribute that only dresses the add button") {
    Seq(
      "determiner" -> <fg-collection path="/dogs" item-name="dog" readonly="true" determiner="another"/>,
      "disallow-empty" -> <fg-collection path="/dogs" item-name="dog" readonly="true" disallow-empty="true"/>,
      "seed-item-if-true" -> <fg-collection path="/dogs" item-name="dog" readonly="true" seed-item-if-true="/pets/*/needsWalking"/>,
    ).foreach { case (name, collection) =>
      it(s"refuses $name rather than ignoring it") {
        val thrown = the[InvalidFormConfig] thrownBy render(page(collection))
        thrown.getMessage should include(name)
      }
    }
  }
}
