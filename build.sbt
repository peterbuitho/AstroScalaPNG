import scala.sys.process.*

ThisBuild / organization := "io.github.astroscalapng"
ThisBuild / version      := "0.7.0"
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
      "org.scalameta" %% "munit" % "1.0.3" % Test
    )
  )

// JDK 24+ warns (and will eventually restrict) native access without an
// explicit opt-in. A jar manifest attribute only auto-applies to `java -jar`
// launches, but sbt-native-packager's generated scripts build a classpath
// and invoke the main class directly, so the flag is added to the launcher
// itself instead.
lazy val enableNativeAccess =
  bashScriptExtraDefines += """addJava "--enable-native-access=ALL-UNNAMED""""

lazy val cli = (project in file("cli"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging, JlinkPlugin)
  .settings(
    name             := "astroscalapng",
    Compile / mainClass := Some("astroscalapng.cli.Main"),
    executableScriptName := "astroscalapng",
    enableNativeAccess,
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
    enableNativeAccess,
    libraryDependencies ++= javafxModules.map(m =>
      "org.openjfx" % s"javafx-$m" % javafxVersion classifier javafxClassifier
    ),
    fork := true
  )
