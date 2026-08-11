package gov.irs.formative.generators

import gov.irs.factgraph.FactDictionary
import gov.irs.formative.parser.Flow
import gov.irs.formative.FixtureApp
import gov.irs.formative.FixtureApp.given
import io.circe.Json
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers.*

/** The Formative Graph Model generator, against Pet Planner.
  *
  * Everything here is inline XML — `buildJson` reads no disk precisely so that this spec does not have to invent a
  * fixture app on the filesystem, and so the graph's shape can be asserted one construct at a time.
  *
  * The contract being defended is shared with another repo: `fact-explorer/src/model/fgm.js` validates this JSON and
  * will reject a node with an unknown tag, an edge whose endpoint does not resolve, or a duplicate id. These assertions
  * are that validator, restated on the producing side.
  */
class FormativeGraphSpec extends AnyFunSpec {

  // Flat paths: the fact-graph engine resolves a nested `/pet/x` only when `/pet` itself is a
  // declared fact, and this spec is about the graph generator rather than dictionary nesting.
  // `../seniorThreshold` is still a genuinely *relative* dependency from `/isSenior`.
  private val dictionaryConfig = <FactDictionaryModule>
    <Facts>
      <Fact path="/petName">
        <Writable><String/></Writable>
      </Fact>
      <Fact path="/ageInYears">
        <Writable><Int/></Writable>
      </Fact>
      <Fact path="/seniorThreshold">
        <Writable><Int/></Writable>
      </Fact>
      <Fact path="/isSenior">
        <Description>Whether the pet counts as a senior</Description>
        <Derived>
          <GreaterThan>
            <Left><Dependency path="/ageInYears"/></Left>
            <Right><Dependency path="../seniorThreshold"/></Right>
          </GreaterThan>
        </Derived>
      </Fact>
      <Fact path="/needsVetVisit">
        <Writable><Boolean/></Writable>
      </Fact>
    </Facts>
  </FactDictionaryModule>

  private val formConfig = <FlowConfig>
    <page route="/" title="About your pet">
      <section>
        <fg-set path="/petName">
          <question>What is your pet called?</question>
          <input type="text"/>
        </fg-set>
        <fg-set path="/ageInYears" if-true="/isSenior">
          <question>How old are they?</question>
          <input type="int"/>
        </fg-set>
        <fg-alert alert-type="warning" alert-key="senior-pet" condition="/isSenior" operator="isTrue">
          <heading>Senior pets need a check-up</heading>
          <p>Book a check-up.</p>
        </fg-alert>
      </section>
    </page>
    <page route="/results" title="Results">
      <section>
        <fg-alert alert-type="error" alert-key="no-vet" condition="/needsVetVisit" operator="isTrue" knockout="true">
          <heading>You need a vet</heading>
          <p>Please see a vet.</p>
        </fg-alert>
      </section>
    </page>
  </FlowConfig>

  private val factDictionary = FactDictionary.fromXml(dictionaryConfig)
  private val flow = Flow.fromXmlConfig(formConfig, factDictionary, FixtureApp.app)
  private val graph = FormativeGraph.buildJson(formConfig, dictionaryConfig, flow, FixtureApp.app)

  private def slice(name: String): List[Json] =
    graph.hcursor.downField(name).as[List[Json]].getOrElse(Nil)

  private def strAt(j: Json, field: String): Option[String] =
    j.hcursor.downField(field).as[String].toOption

  private def ids(name: String): Set[String] = slice(name).flatMap(strAt(_, "id")).toSet

  private lazy val nodeIds = ids("flowPages") ++ ids("flowElements") ++ ids("facts")

  describe("the four slices") {
    it("emits all of them") {
      slice("flowPages") should not be empty
      slice("flowElements") should not be empty
      slice("facts") should not be empty
      slice("edges") should not be empty
    }

    it("gives every node a unique id") {
      val all = List("flowPages", "flowElements", "facts").flatMap(slice).flatMap(strAt(_, "id"))
      all.distinct.length shouldBe all.length
    }

    it("resolves every edge endpoint to a declared node — the FGM's central invariant") {
      slice("edges").foreach { edge =>
        nodeIds should contain(strAt(edge, "source").get)
        nodeIds should contain(strAt(edge, "target").get)
      }
    }

    it("lists every page's elementIds among the emitted elements") {
      val elementIds = ids("flowElements")
      slice("flowPages").foreach { page =>
        page.hcursor.downField("elementIds").as[List[String]].getOrElse(Nil).foreach { id =>
          elementIds should contain(id)
        }
      }
    }
  }

  describe("facts") {
    it("distinguishes writable from derived") {
      val byPath = slice("facts").map(f => strAt(f, "path").get -> strAt(f, "kind").get).toMap
      byPath("/petName") shouldBe "writable"
      byPath("/isSenior") shouldBe "derived"
    }

    it("carries the description and the source XML") {
      val senior = slice("facts").find(f => strAt(f, "path").contains("/isSenior")).get
      strAt(senior, "description") shouldBe Some("Whether the pet counts as a senior")
      strAt(senior, "rawXml").get should include("GreaterThan")
    }

    it("resolves a relative <Dependency path=\"../x\"/> against the owning fact") {
      // The one ported algorithm, and the only place a subtle bug would be invisible in the UI.
      val senior = slice("facts").find(f => strAt(f, "path").contains("/isSenior")).get
      val resolved = senior.hcursor
        .downField("dependencyPaths")
        .as[List[Json]]
        .getOrElse(Nil)
        .flatMap(strAt(_, "resolvedAbstract"))
      resolved should contain("/seniorThreshold")
      resolved should contain("/ageInYears")
    }

    it("keeps only the last definition of a redefined path") {
      // Real dictionaries redefine a path on purpose: constants.xml alongside constants2025.xml, one
      // entry per tax year, resolved by order. Emitting both produces two nodes with the same id,
      // which the consumer's validate() rejects — this is how credit-assistant first failed it.
      val redefined = <FactDictionaryModule>
        <Facts>
          <Fact path="/seniorThreshold"><Description>2024</Description><Writable><Int/></Writable></Fact>
          <Fact path="/seniorThreshold"><Description>2025</Description><Writable><Int/></Writable></Fact>
        </Facts>
      </FactDictionaryModule>
      val g = FormativeGraph.buildJson(formConfig, redefined, flow, FixtureApp.app)
      val facts = g.hcursor.downField("facts").as[List[Json]].getOrElse(Nil)
      facts.length shouldBe 1
      strAt(facts.head, "description") shouldBe Some("2025")
    }

    it("emits a depends edge per resolvable dependency, tagged with the root operation") {
      val depends = slice("edges").filter(e => strAt(e, "kind").contains("depends"))
      val fromSenior = depends.filter(e => strAt(e, "source").contains("fact:/isSenior"))
      fromSenior.map(e => strAt(e, "target").get) should contain("fact:/seniorThreshold")
      strAt(fromSenior.head, "via") shouldBe Some("GreaterThan")
    }
  }

  describe("flow elements") {
    it("binds an fg-set to the fact it writes") {
      val binds = slice("edges").filter(e => strAt(e, "kind").contains("binds"))
      binds.map(e => strAt(e, "target").get) should contain("fact:/petName")
    }

    it("emits a gates edge for if-true and a displays edge for a plain condition") {
      val kinds = slice("edges").map(e => strAt(e, "kind").get).toSet
      kinds should contain("gates")
      kinds should contain("displays")
    }

    it("distinguishes a knockout alert from an ordinary one") {
      val knocks = slice("edges").filter(e => strAt(e, "kind").contains("knocks-out"))
      knocks.map(e => strAt(e, "target").get) should contain("fact:/needsVetVisit")
    }

    it("carries each element's own source XML for the explain popup") {
      slice("flowElements").foreach { el =>
        strAt(el, "rawXml").get should not be empty
      }
    }

    it("chains elements in document order with sequential edges") {
      slice("edges").count(e => strAt(e, "kind").contains("sequential")) should be > 0
    }
  }

  describe("the flowTags contract") {
    it("declares exactly the node types this app registered — none, for the fixture") {
      graph.hcursor.downField("flowTags").as[List[String]] shouldBe Right(Nil)
    }

    it("declares an app-registered node type, so the consumer's validate() accepts it") {
      // The scaffold cannot know what a custom element means, but it must not drop it: this is the
      // producing half of the same contract tax-withholding-estimator exercises for real.
      val custom = FixtureApp.app.copy(nodeTypes = Map("pp-feeding-schedule" -> gov.irs.formative.parser.FgDetail))
      val withCustom = <FlowConfig>
        <page route="/" title="About your pet">
          <pp-feeding-schedule path="/petName" meals-per-day="2"/>
        </page>
      </FlowConfig>
      val g = FormativeGraph.buildJson(withCustom, dictionaryConfig, flow, custom)

      g.hcursor.downField("flowTags").as[List[String]] shouldBe Right(List("pp-feeding-schedule"))
      val els = g.hcursor.downField("flowElements").as[List[Json]].getOrElse(Nil)
      val tags = els.flatMap(strAt(_, "tag"))
      tags should contain("pp-feeding-schedule")

      // Unrecognised attributes survive verbatim rather than being parsed or dropped.
      val el = els.find(e => strAt(e, "tag").contains("pp-feeding-schedule")).get
      el.hcursor.downField("attrs").downField("meals-per-day").as[String] shouldBe Right("2")
    }
  }

  describe("path resolution") {
    it("handles absolute, dotted and parent-relative forms") {
      FormativeGraph.resolveDependencyPath("/a/b", "/x/y") shouldBe "/a/b"
      FormativeGraph.resolveDependencyPath("../c", "/a/b") shouldBe "/c"
      FormativeGraph.resolveDependencyPath("./c", "/a/b") shouldBe "/a/c"
      FormativeGraph.resolveDependencyPath("c", "/a/b") shouldBe "/a/c"
    }
  }
}
