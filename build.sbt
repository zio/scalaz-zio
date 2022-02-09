import BuildHelper._
import MimaSettings.mimaSettings
import explicitdeps.ExplicitDepsPlugin.autoImport.moduleFilterRemoveValue
import sbt.Keys

Global / onChangedBuildSource := ReloadOnSourceChanges

inThisBuild(
  List(
    organization := "dev.zio",
    homepage     := Some(url("https://zio.dev")),
    licenses := List(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    developers := List(
      Developer(
        "jdegoes",
        "John De Goes",
        "john@degoes.net",
        url("http://degoes.net")
      )
    )
  )
)

addCommandAlias("build", "; fmt; testJVM")
addCommandAlias("fmt", "all root/scalafmtSbt root/scalafmtAll")
addCommandAlias("fmtCheck", "all root/scalafmtSbtCheck root/scalafmtCheckAll")
addCommandAlias(
  "check",
  "; scalafmtSbtCheck; scalafmtCheckAll; Test/compile"
)
addCommandAlias(
  "compileJVM",
  ";coreTestsJVM/test:compile;stacktracerJVM/test:compile;streamsTestsJVM/test:compile;testTestsJVM/test:compile;testMagnoliaTestsJVM/test:compile;testRefinedJVM/test:compile;testRunnerJVM/test:compile;examplesJVM/test:compile;macrosTestsJVM/test:compile;concurrentJVM/test:compile"
)
addCommandAlias(
  "testNative",
  ";coreNative/test;stacktracerNative/test;streamsNative/test;testNative/test;testRunnerNative/test;concurrentNative/test" // `test` currently executes only compilation, see `nativeSettings` in `BuildHelper`
)
addCommandAlias(
  "testJVM",
  ";coreTestsJVM/test;stacktracerJVM/test;streamsTestsJVM/test;testTestsJVM/test;testMagnoliaTestsJVM/test;testRefinedJVM/test;testRunnerJVM/test:run;examplesJVM/test:compile;benchmarks/test:compile;macrosTestsJVM/test;testJunitRunnerTestsJVM/test;concurrentJVM/test"
)
addCommandAlias(
  "testJVMNoBenchmarks",
  ";coreTestsJVM/test;stacktracerJVM/test;streamsTestsJVM/test;testTestsJVM/test;testMagnoliaTestsJVM/test;testRefinedJVM/test:compile;testRunnerJVM/test:run;examplesJVM/test:compile;concurrentJVM/test"
)
addCommandAlias(
  "testJVMDotty",
  ";coreTestsJVM/test;stacktracerJVM/test:compile;streamsTestsJVM/test;testTestsJVM/test;testMagnoliaTestsJVM/test;testRefinedJVM/test;testRunnerJVM/test:run;examplesJVM/test:compile;concurrentJVM/test"
)
addCommandAlias(
  "testJSDotty",
  ";coreTestsJS/test;stacktracerJS/test;streamsTestsJS/test;testTestsJS/test;testMagnoliaTestsJS/test;testRefinedJS/test;examplesJS/test:compile;concurrentJS/test"
)
addCommandAlias(
  "testJVM211",
  ";coreTestsJVM/test;stacktracerJVM/test;streamsTestsJVM/test;testTestsJVM/test;testRunnerJVM/test:run;examplesJVM/test:compile;macrosTestsJVM/test;concurrentJVM/test"
)
addCommandAlias(
  "testJS",
  ";coreTestsJS/test;stacktracerJS/test;streamsTestsJS/test;testTestsJS/test;testMagnoliaTestsJS/test;testRefinedJS/test;examplesJS/test:compile;macrosTestsJS/test;concurrentJS/test"
)
addCommandAlias(
  "testJS211",
  ";coreTestsJS/test;stacktracerJS/test;streamsTestsJS/test;testTestsJS/test;examplesJS/test:compile;macrosJS/test;concurrentJS/test"
)
addCommandAlias(
  "mimaChecks",
  "all coreJVM/mimaReportBinaryIssues streamsJVM/mimaReportBinaryIssues testJVM/mimaReportBinaryIssues"
)

lazy val root = project
  .in(file("."))
  .settings(
    name           := "zio",
    publish / skip := true,
    console        := (coreJVM / Compile / console).value,
    unusedCompileDependenciesFilter -= moduleFilter(
      "org.scala-js",
      "scalajs-library"
    ),
    welcomeMessage
  )
  .aggregate(
    benchmarks,
    concurrentJS,
    concurrentJVM,
    concurrentNative,
    coreJS,
    coreJVM,
    coreNative,
    coreTestsJS,
    coreTestsJVM,
    docs,
    examplesJS,
    examplesJVM,
    macrosJS,
    macrosJVM,
    macrosTestsJS,
    macrosTestsJVM,
    stacktracerJS,
    stacktracerJVM,
    stacktracerNative,
    streamsJS,
    streamsJVM,
    streamsNative,
    streamsTestsJS,
    streamsTestsJVM,
    testJS,
    testJVM,
    testNative,
    testJunitRunnerJVM,
    testJunitRunnerTestsJVM,
    testMagnoliaJS,
    testMagnoliaJVM,
    testMagnoliaTestsJS,
    testMagnoliaTestsJVM,
    testRefinedJS,
    testRefinedJVM,
    testRunnerJS,
    testRunnerJVM,
    testRunnerNative,
    testScalaCheckJS,
    testScalaCheckJVM,
    testScalaCheckNative,
    testTestsJS,
    testTestsJVM
  )
  .enablePlugins(ScalaJSPlugin)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core"))
  .dependsOn(stacktracer)
  .settings(stdSettings("zio"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio"))
  .settings(libraryDependencies += "dev.zio" %%% "izumi-reflect" % "1.1.3")
  .enablePlugins(BuildInfoPlugin)

lazy val coreJVM = core.jvm
  .settings(dottySettings)
  .settings(replSettings)
  .settings(mimaSettings(failOnProblem = true))

lazy val coreJS = core.js
  .settings(dottySettings)
  .settings(libraryDependencies += "org.scala-js" %%% "scala-js-macrotask-executor" % "1.0.0")
  .settings(
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        // Temporarily disable warning to use `MacrotaskExecutor` https://github.com/zio/zio/issues/6308
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )

lazy val coreNative = core.native
  .settings(nativeSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.lolgab" %%% "native-loop-core" % "0.2.1"
    )
  )

lazy val coreTests = crossProject(JSPlatform, JVMPlatform)
  .in(file("core-tests"))
  .dependsOn(core)
  .dependsOn(test)
  .settings(stdSettings("core-tests"))
  .settings(crossProjectSettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .dependsOn(testRunner)
  .settings(buildInfoSettings("zio"))
  .settings(publish / skip := true)
  .settings(
    Compile / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
  .enablePlugins(BuildInfoPlugin)

lazy val coreTestsJVM = coreTests.jvm
  .settings(dottySettings)
  .configure(_.enablePlugins(JCStressPlugin))
  .settings(replSettings)

lazy val coreTestsJS = coreTests.js
  .settings(dottySettings)
  .settings(
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )

lazy val macros = crossProject(JSPlatform, JVMPlatform)
  .in(file("macros"))
  .dependsOn(core)
  .settings(stdSettings("zio-macros"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)

lazy val macrosJVM = macros.jvm
lazy val macrosJS  = macros.js

lazy val macrosTests = crossProject(JSPlatform, JVMPlatform)
  .in(file("macros-tests"))
  .dependsOn(macros)
  .settings(stdSettings("macros-tests"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .dependsOn(testRunner)
  .settings(buildInfoSettings("zio"))
  .settings(publish / skip := true)
  .enablePlugins(BuildInfoPlugin)

lazy val macrosTestsJVM = macrosTests.jvm
lazy val macrosTestsJS  = macrosTests.js

lazy val streams = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("streams"))
  .dependsOn(core)
  .settings(stdSettings("zio-streams"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.stream"))
  .settings(streamReplSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val streamsJVM = streams.jvm
  .settings(dottySettings)
  // No bincompat on streams yet
  .settings(mimaSettings(failOnProblem = false))

lazy val streamsJS = streams.js
  .settings(dottySettings)

lazy val streamsNative = streams.native
  .settings(nativeSettings)

lazy val streamsTests = crossProject(JSPlatform, JVMPlatform)
  .in(file("streams-tests"))
  .dependsOn(streams)
  .dependsOn(coreTests % "test->test;compile->compile")
  .settings(stdSettings("streams-tests"))
  .settings(crossProjectSettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .dependsOn(testRunner)
  .settings(buildInfoSettings("zio.stream"))
  .settings(publish / skip := true)
  .settings(
    Compile / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.AllLibraryJars
  )
  .enablePlugins(BuildInfoPlugin)

lazy val streamsTestsJVM = streamsTests.jvm
  .dependsOn(coreTestsJVM % "test->compile")
  .settings(dottySettings)

lazy val streamsTestsJS = streamsTests.js
  .settings(dottySettings)
  .settings(
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )

lazy val test = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("test"))
  .dependsOn(core, streams)
  .settings(stdSettings("zio-test"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)
  .settings(
    libraryDependencies ++= Seq(
      ("org.portable-scala" %%% "portable-scala-reflect" % "1.1.1")
        .cross(CrossVersion.for3Use2_13)
    )
  )

lazy val testJVM = test.jvm
  .settings(dottySettings)
  // No bincompat on zio-test yet
  .settings(mimaSettings(failOnProblem = false))
lazy val testJS = test.js
  .settings(dottySettings)
  .settings(
    libraryDependencies ++= List(
      "io.github.cquiroz" %%% "scala-java-time"      % "2.4.0-M1",
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.4.0-M1"
    )
  )
lazy val testNative = test.native
  .settings(nativeSettings)
  .settings(libraryDependencies += "org.ekrich" %%% "sjavatime" % "1.1.5")

lazy val testTests = crossProject(JSPlatform, JVMPlatform)
  .in(file("test-tests"))
  .dependsOn(test)
  .settings(stdSettings("test-tests"))
  .settings(crossProjectSettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .dependsOn(testRunner)
  .settings(buildInfoSettings("zio.test"))
  .settings(publish / skip := true)
  .settings(macroExpansionSettings)
  .enablePlugins(BuildInfoPlugin)

lazy val testTestsJVM = testTests.jvm.settings(dottySettings)
lazy val testTestsJS  = testTests.js.settings(dottySettings)

lazy val testMagnolia = crossProject(JVMPlatform, JSPlatform)
  .in(file("test-magnolia"))
  .dependsOn(test)
  .settings(stdSettings("zio-test-magnolia"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(
    crossScalaVersions --= Seq(Scala211),
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        Seq.empty
      } else {
        Seq("-language:experimental.macros")
      }
    },
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3) {
        Seq.empty
      } else {
        Seq(
          ("com.propensive" %%% "magnolia" % "0.17.0")
            .exclude("org.scala-lang", "scala-compiler")
        )
      }
    }
  )

lazy val testMagnoliaJVM = testMagnolia.jvm
  .settings(dottySettings)
lazy val testMagnoliaJS = testMagnolia.js
  .settings(dottySettings)

lazy val testMagnoliaTests = crossProject(JVMPlatform, JSPlatform)
  .in(file("test-magnolia-tests"))
  .dependsOn(testMagnolia)
  .dependsOn(testTests % "test->test;compile->compile")
  .settings(stdSettings("test-magnolia-tests"))
  .settings(crossProjectSettings)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .dependsOn(testRunner)
  .settings(buildInfoSettings("zio.test"))
  .settings(
    publish / skip := true,
    crossScalaVersions --= Seq(Scala211)
  )
  .enablePlugins(BuildInfoPlugin)

lazy val testMagnoliaTestsJVM = testMagnoliaTests.jvm
  .settings(dottySettings)
lazy val testMagnoliaTestsJS = testMagnoliaTests.js
  .settings(dottySettings)

lazy val testRefined = crossProject(JVMPlatform, JSPlatform)
  .in(file("test-refined"))
  .dependsOn(testMagnolia)
  .settings(stdSettings("zio-test-refined"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(
    crossScalaVersions --= Seq(Scala211),
    libraryDependencies ++=
      Seq(
        ("eu.timepit" %% "refined" % "0.9.27").cross(CrossVersion.for3Use2_13)
      )
  )

lazy val testRefinedJVM = testRefined.jvm
  .settings(dottySettings)
lazy val testRefinedJS = testRefined.js
  .settings(dottySettings)

lazy val testScalaCheck = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("test-scalacheck"))
  .dependsOn(test)
  .settings(stdSettings("zio-test-scalacheck"))
  .settings(crossProjectSettings)
  .settings(
    crossScalaVersions --= Seq(Scala211),
    libraryDependencies ++= Seq(
      ("org.scalacheck" %%% "scalacheck" % "1.15.4")
    )
  )

lazy val testScalaCheckJVM    = testScalaCheck.jvm.settings(dottySettings)
lazy val testScalaCheckJS     = testScalaCheck.js.settings(dottySettings)
lazy val testScalaCheckNative = testScalaCheck.native.settings(nativeSettings)

lazy val stacktracer = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("stacktracer"))
  .settings(stdSettings("zio-stacktracer"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.internal.stacktracer"))
  .enablePlugins(BuildInfoPlugin)

lazy val stacktracerJS = stacktracer.js
  .settings(dottySettings)
lazy val stacktracerJVM = stacktracer.jvm
  .settings(dottySettings)
  .settings(replSettings)

lazy val stacktracerNative = stacktracer.native
  .settings(nativeSettings)
  .settings(scalacOptions -= "-Xfatal-warnings") // Issue 3112

lazy val testRunner = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("test-sbt"))
  .settings(stdSettings("zio-test-sbt"))
  .settings(crossProjectSettings)
  .settings(Test / run / mainClass := Some("zio.test.sbt.TestMain"))
  .dependsOn(core)
  .dependsOn(test)

lazy val testRunnerJVM = testRunner.jvm
  .settings(dottySettings)
  .settings(libraryDependencies ++= Seq("org.scala-sbt" % "test-interface" % "1.0"))
lazy val testRunnerJS = testRunner.js
  .settings(dottySettings)
  .settings(
    libraryDependencies ++= Seq(
      ("org.scala-js" %% "scalajs-test-interface" % scalaJSVersion).cross(CrossVersion.for3Use2_13)
    )
  )
lazy val testRunnerNative = testRunner.native
  .settings(nativeSettings)
  .settings(libraryDependencies ++= Seq("org.scala-native" %%% "test-interface" % nativeVersion))

lazy val testJunitRunner = crossProject(JVMPlatform)
  .in(file("test-junit"))
  .settings(stdSettings("zio-test-junit"))
  .settings(crossProjectSettings)
  .settings(libraryDependencies ++= Seq("junit" % "junit" % "4.13.2"))
  .dependsOn(test)

lazy val testJunitRunnerJVM = testJunitRunner.jvm.settings(dottySettings)

lazy val testJunitRunnerTests = crossProject(JVMPlatform)
  .in(file("test-junit-tests"))
  .settings(stdSettings("test-junit-tests"))
  .settings(crossProjectSettings)
  .settings(Test / fork := true)
  .settings(Test / javaOptions ++= {
    Seq(
      s"-Dproject.dir=${baseDirectory.value}",
      s"-Dproject.version=${version.value}",
      s"-Dscala.version=${scalaVersion.value}",
      s"-Dscala.compat.version=${scalaBinaryVersion.value}"
    )
  })
  .settings(publish / skip := true)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .settings(
    crossScalaVersions --= List(Scala211),
    libraryDependencies ++= Seq(
      "junit"                   % "junit"     % "4.13.2" % Test,
      "org.scala-lang.modules" %% "scala-xml" % "2.0.1"  % Test,
      // required to run embedded maven in the tests
      "org.apache.maven"       % "maven-embedder"         % "3.8.3"  % Test,
      "org.apache.maven"       % "maven-compat"           % "3.8.3"  % Test,
      "org.apache.maven.wagon" % "wagon-http"             % "3.4.3"  % Test,
      "org.eclipse.aether"     % "aether-connector-basic" % "1.1.0"  % Test,
      "org.eclipse.aether"     % "aether-transport-wagon" % "1.1.0"  % Test,
      "org.slf4j"              % "slf4j-simple"           % "1.7.32" % Test
    )
  )
  .dependsOn(test)
  .dependsOn(testRunner)

lazy val testJunitRunnerTestsJVM = testJunitRunnerTests.jvm
  .settings(dottySettings)
  // publish locally so embedded maven runs against locally compiled zio
  .settings(
    Test / Keys.test :=
      (Test / Keys.test)
        .dependsOn(testJunitRunnerJVM / publishM2)
        .dependsOn(testJVM / publishM2)
        .dependsOn(coreJVM / publishM2)
        .dependsOn(streamsJVM / publishM2)
        .dependsOn(stacktracerJVM / publishM2)
        .value
  )

lazy val concurrent = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("concurrent"))
  .dependsOn(core)
  .settings(stdSettings("zio-concurrent"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.concurrent"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(testRunner % Test)
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))

lazy val concurrentJVM = concurrent.jvm
  .settings(dottySettings)
  .settings(mimaSettings(failOnProblem = false))

lazy val concurrentJS = concurrent.js
  .settings(dottySettings)

lazy val concurrentNative = concurrent.native
  .settings(nativeSettings)

/**
 * Examples sub-project that is not included in the root project.
 *
 * To run tests: `sbt "examplesJVM/test"`
 */
lazy val examples = crossProject(JVMPlatform, JSPlatform)
  .in(file("examples"))
  .settings(stdSettings("examples"))
  .settings(crossProjectSettings)
  .settings(macroExpansionSettings)
  .settings(scalacOptions += "-Xfatal-warnings")
  .settings(testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"))
  .settings(publish / skip := true)
  .dependsOn(macros, testRunner)

lazy val examplesJS = examples.js
  .settings(dottySettings)

lazy val examplesJVM = examples.jvm
  .settings(dottySettings)
  .dependsOn(testJunitRunnerJVM)

lazy val benchmarks = project.module
  .dependsOn(coreJVM, streamsJVM, testJVM)
  .enablePlugins(JmhPlugin)
  .settings(replSettings)
  .settings(
    // skip 2.11 benchmarks because akka stop supporting scala 2.11 in 2.6.x
    crossScalaVersions --= List(Scala211, Scala3),
    //
    publish / skip := true,
    libraryDependencies ++=
      Seq(
        "co.fs2"                    %% "fs2-core"       % "2.5.10",
        "com.google.code.findbugs"   % "jsr305"         % "3.0.2",
        "com.twitter"               %% "util-core"      % "21.9.0",
        "com.typesafe.akka"         %% "akka-stream"    % "2.6.16",
        "io.monix"                  %% "monix"          % "3.4.0",
        "io.projectreactor"          % "reactor-core"   % "3.4.11",
        "io.reactivex.rxjava2"       % "rxjava"         % "2.2.21",
        "org.jctools"                % "jctools-core"   % "3.3.0",
        "org.ow2.asm"                % "asm"            % "9.2",
        "org.scala-lang"             % "scala-compiler" % scalaVersion.value % Provided,
        "org.scala-lang"             % "scala-reflect"  % scalaVersion.value,
        "org.typelevel"             %% "cats-effect"    % "2.5.4",
        "org.scalacheck"            %% "scalacheck"     % "1.15.4",
        "qa.hedgehog"               %% "hedgehog-core"  % "0.7.0",
        "com.github.japgolly.nyaya" %% "nyaya-gen"      % "0.10.0"
      ),
    unusedCompileDependenciesFilter -= libraryDependencies.value
      .map(moduleid =>
        moduleFilter(
          organization = moduleid.organization,
          name = moduleid.name
        )
      )
      .reduce(_ | _),
    Compile / console / scalacOptions := Seq(
      "-Ypartial-unification",
      "-language:higherKinds",
      "-language:existentials",
      "-Yno-adapted-args",
      "-Xsource:2.13",
      "-Yrepl-class-based"
    )
  )

lazy val jsdocs = project
  .settings(libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "1.0.0")
  .enablePlugins(ScalaJSPlugin)

val http4sV     = "0.23.6"
val doobieV     = "1.0.0-RC1"
val catsEffectV = "3.2.9"
val zioActorsV  = "0.0.9"

lazy val docs = project.module
  .in(file("zio-docs"))
  .settings(
    publish / skip := true,
    moduleName     := "zio-docs",
    unusedCompileDependenciesFilter -= moduleFilter("org.scalameta", "mdoc"),
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions ~= { _ filterNot (_ startsWith "-Ywarn") },
    scalacOptions ~= { _ filterNot (_ startsWith "-Xlint") },
    crossScalaVersions --= List(Scala211, Scala3),
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      coreJVM,
      streamsJVM,
      testJVM,
      testMagnoliaJVM,
      testRefinedJVM,
      testScalaCheckJVM
    ),
    ScalaUnidoc / unidoc / target := (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
    cleanFiles += (ScalaUnidoc / unidoc / target).value,
    docusaurusCreateSite     := docusaurusCreateSite.dependsOn(Compile / unidoc).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(Compile / unidoc).value,
    libraryDependencies ++= Seq(
      "commons-io"                     % "commons-io"                    % "2.11.0" % "provided",
      "io.7mind.izumi"                %% "distage-core"                  % "1.0.8",
      "io.7mind.izumi"                %% "logstage-core"                 % "1.0.8",
      "org.jsoup"                      % "jsoup"                         % "1.14.3" % "provided",
      "org.reactivestreams"            % "reactive-streams-examples"     % "1.0.3"  % "provided",
      "org.typelevel"                 %% "cats-effect"                   % catsEffectV,
      "dev.zio"                       %% "zio-actors"                    % zioActorsV,
      "dev.zio"                       %% "zio-akka-cluster"              % "0.2.0",
      "dev.zio"                       %% "zio-cache"                     % "0.1.0",
      "dev.zio"                       %% "zio-config-magnolia"           % "1.0.10",
      "dev.zio"                       %% "zio-config-typesafe"           % "1.0.10",
      "dev.zio"                       %% "zio-config-refined"            % "1.0.10",
      "dev.zio"                       %% "zio-ftp"                       % "0.3.3",
      "dev.zio"                       %% "zio-json"                      % "0.1.5",
      "dev.zio"                       %% "zio-kafka"                     % "0.17.0",
      "dev.zio"                       %% "zio-logging"                   % "0.5.12",
      "dev.zio"                       %% "zio-metrics-prometheus"        % "1.0.12",
      "dev.zio"                       %% "zio-nio"                       % "1.0.0-RC11",
      "dev.zio"                       %% "zio-optics"                    % "0.1.0",
      "dev.zio"                       %% "zio-prelude"                   % "1.0.0-RC6",
      "dev.zio"                       %% "zio-process"                   % "0.5.0",
      "dev.zio"                       %% "zio-rocksdb"                   % "0.3.0",
      "dev.zio"                       %% "zio-s3"                        % "0.3.7",
      "dev.zio"                       %% "zio-schema"                    % "0.1.1",
      "dev.zio"                       %% "zio-sqs"                       % "0.4.2",
      "dev.zio"                       %% "zio-opentracing"               % "0.8.2",
      "io.laserdisc"                  %% "tamer-db"                      % "0.16.1",
      "io.jaegertracing"               % "jaeger-core"                   % "1.6.0",
      "io.jaegertracing"               % "jaeger-client"                 % "1.6.0",
      "io.jaegertracing"               % "jaeger-zipkin"                 % "1.6.0",
      "io.zipkin.reporter2"            % "zipkin-reporter"               % "2.16.3",
      "io.zipkin.reporter2"            % "zipkin-sender-okhttp3"         % "2.16.3",
      "dev.zio"                       %% "zio-interop-cats"              % "3.1.1.0",
      "dev.zio"                       %% "zio-interop-scalaz7x"          % "7.3.3.0",
      "dev.zio"                       %% "zio-interop-reactivestreams"   % "1.3.7",
      "dev.zio"                       %% "zio-interop-twitter"           % "20.10.0.0",
      "dev.zio"                       %% "zio-zmx"                       % "0.0.9",
      "dev.zio"                       %% "zio-query"                     % "0.2.10",
      "org.polynote"                  %% "uzhttp"                        % "0.2.8",
      "org.tpolecat"                  %% "doobie-core"                   % doobieV,
      "org.tpolecat"                  %% "doobie-h2"                     % doobieV,
      "org.tpolecat"                  %% "doobie-hikari"                 % doobieV,
      "org.http4s"                    %% "http4s-blaze-server"           % http4sV,
      "org.http4s"                    %% "http4s-blaze-client"           % http4sV,
      "org.http4s"                    %% "http4s-dsl"                    % http4sV,
      "com.github.ghostdogpr"         %% "caliban"                       % "1.2.0",
      "com.github.ghostdogpr"         %% "caliban-zio-http"              % "1.2.0",
      "org.scalameta"                 %% "munit"                         % "0.7.29",
      "com.github.poslegm"            %% "munit-zio"                     % "0.0.3",
      "nl.vroste"                     %% "rezilience"                    % "0.7.0",
      "io.github.gaelrenoux"          %% "tranzactio"                    % "2.1.0",
      "io.github.neurodyne"           %% "zio-arrow"                     % "0.2.1",
      "nl.vroste"                     %% "zio-amqp"                      % "0.2.2",
      "io.github.vigoo"               %% "zio-aws-core"                  % "3.17.58.1",
      "io.github.vigoo"               %% "zio-aws-ec2"                   % "3.17.58.1",
      "io.github.vigoo"               %% "zio-aws-elasticbeanstalk"      % "3.17.58.1",
      "io.github.vigoo"               %% "zio-aws-netty"                 % "3.17.58.1",
      "io.github.neurodyne"           %% "zio-aws-s3"                    % "0.4.13",
      "io.d11"                        %% "zhttp"                         % "1.0.0.0-RC17",
      "com.coralogix"                 %% "zio-k8s-client"                % "1.3.4",
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.3.14",
      "nl.vroste"                     %% "zio-kinesis"                   % "0.20.0",
      "com.vladkopanev"               %% "zio-saga-core"                 % "0.4.0",
      "io.scalac"                     %% "zio-slick-interop"             % "0.4",
      "com.typesafe.slick"            %% "slick-hikaricp"                % "3.3.3",
      "info.senia"                    %% "zio-test-akka-http"            % "1.0.3",
      "io.getquill"                   %% "quill-jdbc-zio"                % "3.10.0"
    ),
    resolvers += "Confluent" at "https://packages.confluent.io/maven"
  )
  .settings(macroDefinitionSettings)
  .settings(mdocJS := Some(jsdocs))
  .dependsOn(coreJVM, streamsJVM, concurrentJVM, testJVM, testMagnoliaJVM, testRefinedJVM, testScalaCheckJVM, coreJS)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
