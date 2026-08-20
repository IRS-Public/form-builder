ThisBuild / scalaVersion := "3.7.2"

// ── Publishing: GitHub Packages, under the gov.irs group ──────────────────────────────────────
//
// GitHub Packages was chosen over Maven Central because Central verifies the `gov.irs` namespace
// against DNS on irs.gov, which is not self-claimable. The cost, stated so it stays a choice:
// **GitHub Packages requires authentication even to *read* a public package**, so every consumer —
// including a stranger evaluating this library — must add the resolver below and supply a token.
// That friction is the trade for keeping the coordinate `gov.irs`.
//
// Set GITHUB_OWNER to the org these repos live under. GITHUB_ACTOR / GITHUB_TOKEN are what a
// GitHub Actions job already provides; locally, a classic PAT with `read:packages` (consume) or
// `write:packages` (publish) works.
val githubOwner = sys.env.getOrElse("GITHUB_OWNER", "REPLACE-ME-ORG")

ThisBuild / publishTo := Some(
  "GitHub Packages" at s"https://maven.pkg.github.com/$githubOwner/formative"
  )
ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_ACTOR", ""),
  sys.env.getOrElse("GITHUB_TOKEN", ""),
  )

// fact-graph is a separate, already-open-sourced repo and is no longer built beside this one, so
// `gov.irs:factgraph` has to resolve from a registry rather than from ~/.ivy2/local.
//
// OPEN: this assumes fact-graph publishes to the same org's GitHub Packages. If it publishes only
// source, this resolver finds nothing and the alternative is a documented
// `git clone … && sbt publishLocal` bootstrap in the README.
ThisBuild / resolvers += "factgraph" at s"https://maven.pkg.github.com/$githubOwner/fact-graph"

scalafmtConfig := file(".scalafmt.conf")

// Formative: everything that turns Flow XML + a Fact Dictionary into a static site, with none of
// the tax product on top. Published to the local Ivy cache with `sbt publishLocal`, exactly as
// ../fact-graph is, and consumed by each app's build.sbt.
//
// The other library an app may depend on is `taxpert` (../taxpert), and the two answer different
// questions. Formative decides what the site *is* — including how it looks and how it behaves in a
// browser: `src/main/resources/formative/website-static/` carries the theme and the flow runtime, and
// FormativeAssets.scala extracts them from this jar as the generator runs. Taxpert is the workspace
// laid over a running Formative app to make it understandable — the nav, the inspector, the outcome
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
// types, custom input types, and app-first template resolution. See FormativeApp.
lazy val root = (project in file("."))
  .settings(
    name := "formative",
    organization := "gov.irs",
    version := "0.1.0",

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
