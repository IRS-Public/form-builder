ThisBuild / scalaVersion := "3.7.2"

scalafmtConfig := file(".scalafmt.conf")

// Formative: everything that turns Flow XML + a Fact Dictionary into a static site, with none of
// the tax product on top. Published to the local Ivy cache with `sbt publishLocal`, exactly as
// ../fact-graph is, and consumed by each app's build.sbt.
//
// The other library an app depends on is `taxpert` (../taxpert), and the two answer different
// questions. Formative decides what the site *is*; Taxpert is the workspace laid over a running
// Formative app to make it understandable — the nav, the inspector, the outcome tracker. An app
// can ship without Taxpert; it cannot exist without this.
//
// This code was credit-assistant's `src/main/scala/gov/irs/creditassistant/` — the same code the
// tax-withholding-estimator had a forked copy of, differing only by its package and import lines.
// The three places the two forks genuinely diverged are extension points here: custom flow node
// types, custom input types, and app-first template resolution. See FormativeApp.
lazy val root = (project in file("."))
  .settings(
    name := "formative",
    organization := "gov.irs",
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
