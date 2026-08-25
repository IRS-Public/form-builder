ThisBuild / scalaVersion := "3.7.2"

scalafmtConfig := file(".scalafmt.conf")

lazy val root = (project in file("."))
  .settings(
    name := "form-builder",
    organization := "gov.irs",

    version := "0.1.0-SNAPSHOT",

    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "2.4.0",
    libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.11.4",
    libraryDependencies += "gov.irs" %% "factgraph" % "3.1.0-SNAPSHOT",
    libraryDependencies += "org.thymeleaf" % "thymeleaf" % "3.1.5.RELEASE",
    libraryDependencies += "org.jsoup" % "jsoup" % "1.21.1",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser",
      ).map(_ % "0.14.15"),
    libraryDependencies += "io.circe" %% "circe-yaml" % "0.16.0",
    libraryDependencies += "io.circe" %% "circe-yaml-scalayaml" % "0.16.0",
    libraryDependencies += "com.github.tototoshi" %% "scala-csv" % "2.0.0",

    // Local server
    libraryDependencies += "org.smol-utils" %% "smol" % "0.1.2",

    Compile / packageDoc / publishArtifact := false,
    )
