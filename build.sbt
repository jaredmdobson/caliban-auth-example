import Dependencies._

ThisBuild / scalaVersion     := "2.13.5"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "caliban-auth-eample",
    libraryDependencies ++= List(
      "com.github.ghostdogpr" %% "caliban" % "0.9.5",
      "com.github.ghostdogpr" %% "caliban-http4s"     % "0.9.5",
     "com.github.ghostdogpr" %% "caliban-cats"       % "0.9.5", // interop with cats effect
      scalaTest % Test
    )
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
