import scala.sys.process.*

ThisBuild / organization := "io.github.astroscalapng"
ThisBuild / version      := "0.5.0"
ThisBuild / scalaVersion := "3.3.7"

// This is an application, not a published library: no sources/javadoc
// artifacts (they only slow packaging down).
ThisBuild / Compile / packageDoc / publishArtifact := false
ThisBuild / Compile / packageSrc / publishArtifact := false

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-source:3.3"
)

// JavaFX ships per-platform native jars; pick the classifier for the machine
// that is building (CI builds one artifact per OS).
lazy val javafxClassifier: String = {
  val os = System.getProperty("os.name").toLowerCase
  if (os.contains("win")) "win"
  else if (os.contains("mac")) "mac"
  else "linux"
}
lazy val javafxVersion = "21.0.5"
lazy val javafxModules = Seq("base", "graphics", "controls")

lazy val root = (project in file("."))
  .aggregate(core, cli, gui)
  .settings(
    name           := "astroscalapng",
    publish / skip := true
  )

lazy val core = (project in file("core"))
  .settings(
    name := "astroscalapng-core",
    libraryDependencies ++= Seq(
      "org.lz4"          % "lz4-java" % "1.8.0",
      "com.github.luben" % "zstd-jni" % "1.5.6-9",
      "org.scalameta"   %% "munit"    % "1.0.3" % Test
    )
  )

lazy val cli = (project in file("cli"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging, JlinkPlugin)
  .settings(
    name             := "astroscalapng",
    Compile / mainClass := Some("astroscalapng.cli.Main"),
    executableScriptName := "astroscalapng",
    // zstd-jni and lz4-java are plain classpath jars (automatic modules); the
    // jlink image only needs the JDK modules they and we actually touch.
    jlinkIgnoreMissingDependency := JlinkIgnore.everything,
    jlinkModules ++= Seq(
      "java.base",
      "java.desktop",
      "java.net.http",
      "java.xml",
      "java.logging"
    )
  )

lazy val gui = (project in file("gui"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging)
  .settings(
    name                := "astroscalapng-gui",
    Compile / mainClass := Some("astroscalapng.gui.Launcher"),
    executableScriptName := "astroscalapng-gui",
    libraryDependencies ++= javafxModules.map(m =>
      "org.openjfx" % s"javafx-$m" % javafxVersion classifier javafxClassifier
    ),
    fork := true
  )
