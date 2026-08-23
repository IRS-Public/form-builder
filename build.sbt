ThisBuild / scalaVersion := "3.7.2"

// Both this library and the gov.irs:factgraph it builds against are consumed from the local Ivy
// cache, published there from a checkout:
//
//   git clone <fact-graph> && cd fact-graph && sbt publishLocal
//
// which lands 3.1.0-SNAPSHOT in ~/.ivy2/local, already first in sbt's default resolver chain. That
// is why this build declares no resolvers at all: the one place these artifacts come from is
// already searched, and if either is later published to Maven Central — also a default resolver —
// this build picks it up with no change. See README.md.

scalafmtConfig := file(".scalafmt.conf")

// Form Builder: everything that turns Flow XML + a Fact Dictionary into a static site, with none of
// the tax product on top. Published to the local Ivy cache with `sbt publishLocal`, exactly as
// ../fact-graph is, and consumed by each app's build.sbt.
//
// The other library an app may depend on is `taxpert` (../taxpert), and the two answer different
// questions. Form Builder decides what the site *is* — including how it looks and how it behaves in a
// browser: `src/main/resources/form-builder/website-static/` carries the theme and the flow runtime, and
// FormBuilderAssets.scala extracts them from this jar as the generator runs. Taxpert is the workspace
// laid over a running Form Builder app to make it understandable — the nav, the inspector, the outcome
// tracker. An app can ship without Taxpert; it cannot exist without this.
//
// That sentence was false for a while: the theme and the flow runtime lived in taxpert, which made the
// optional package a hard build dependency of every app. Keep it true. Nothing in this library may
// name a path inside `vendor/taxpert/` — the workspace mounts through the empty
// `fragments/workspace-*.html` an app fills in.
//
// This code was credit-assistant's `src/main/scala/gov/irs/creditassistant/` — the same code the
// tax-withholding-estimator had a forked copy of, differing only by its package and import lines.
// The three places the two forks genuinely diverged are extension points here: custom flow node
// types, custom input types, and app-first template resolution. See FormBuilderApp.
lazy val root = (project in file("."))
  .settings(
    name := "form-builder",
    organization := "gov.irs",

    // A snapshot, matching gov.irs:factgraph, because `publishLocal` is the only way this library
    // reaches an application. A fixed release version is a promise that the artifact never changes,
    // and both Ivy and coursier hold consumers to it: edit the scaffold, `publishLocal` again, and
    // an app can keep resolving the copy already in its cache. `-SNAPSHOT` declares the artifact
    // changing, so the edit-and-republish loop this library is developed in actually reaches the
    // apps built on it. Cutting a real release means dropping the suffix here and in every consumer.
    version := "0.1.0-SNAPSHOT",

    // Core dependencies
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.4.0",
    libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.4",

    // Fact Graph!
    libraryDependencies += "gov.irs" %% "factgraph" % "3.1.0-SNAPSHOT",

    // Templating libraries
    libraryDependencies += "org.thymeleaf" % "thymeleaf" % "3.1.5.RELEASE",
    libraryDependencies += "org.jsoup" % "jsoup" % "1.21.1",

    // JSON and YAML utilities
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser",
      ).map(_ % "0.14.15"),
    libraryDependencies += "io.circe" %% "circe-yaml" % "0.16.0",
    libraryDependencies += "io.circe" %% "circe-yaml-scalayaml" % "0.16.0",

    // CSV library for parsing scenario spreadsheets
    libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "2.0.0",

    // Local server
    libraryDependencies += "org.smol-utils" %% "smol" % "0.1.2",

    // No scaladoc artifact. `publishLocal` would otherwise run scaladoc, which reads the TASTy of
    // every dependency — and factgraph's is cross-built for Scala.js, so its `@JSExport`
    // annotations fail to resolve on the JVM classpath. The jar and pom are what a consuming app
    // needs; the API docs are the source, which sits next door in this monorepo.
    Compile / packageDoc / publishArtifact := false,
    )
