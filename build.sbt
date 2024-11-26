import BuildHelper.*
import Dependencies.*
import MimaSettings.mimaSettings
import _root_.scalafix.sbt.BuildInfo.scalafixVersion
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

addCommandAlias("build", "; fmt; rootJVM/test")
addCommandAlias("fmt", "all root/scalafmtSbt root/scalafmtAll")
addCommandAlias("fmtCheck", "all root/scalafmtSbtCheck root/scalafmtCheckAll")
addCommandAlias(
  "check",
  "; scalafmtSbtCheck; scalafmtCheckAll"
)

addCommandAlias(
  "compileJVM",
  ";coreTestsJVM/Test/compile;stacktracerJVM/Test/compile;streamsTestsJVM/Test/compile;testTestsJVM/Test/compile;testMagnoliaTestsJVM/Test/compile;testRefinedJVM/Test/compile;testRunnerJVM/Test/compile;examplesJVM/Test/compile;macrosTestsJVM/Test/compile;concurrentJVM/Test/compile;managedTestsJVM/Test/compile"
)
addCommandAlias(
  "testNative",
  ";coreTestsNative/test;stacktracerNative/test;streamsTestsNative/test;testTestsNative/test;examplesNative/Test/compile;macrosTestsNative/test;concurrentNative/test"
)
addCommandAlias(
  "testJVM",
  ";coreTestsJVM/test;stacktracerJVM/test;streamsTestsJVM/test;testTestsJVM/test;testMagnoliaTestsJVM/test;testRefinedJVM/test;testRunnerJVM/test;testRunnerJVM/Test/run;examplesJVM/Test/compile;benchmarks/Test/compile;macrosTestsJVM/test;concurrentJVM/test;managedTestsJVM/test;set ThisBuild/isSnapshot:=true;testJunitRunnerTests/test;testJunitEngineTests/test;reload"
)
addCommandAlias(
  "testJVMNoBenchmarks",
  ";coreTestsJVM/test;stacktracerJVM/test;streamsTestsJVM/test;testTestsJVM/test;testMagnoliaTestsJVM/test;testRefinedJVM/Test/compile;testRunnerJVM/Test/run;examplesJVM/Test/compile;concurrentJVM/test;managedTestsJVM/test"
)
addCommandAlias(
  "testJS",
  ";coreTestsJS/test;stacktracerJS/test;streamsTestsJS/test;testTestsJS/test;testMagnoliaTestsJS/test;testRefinedJS/test;examplesJS/Test/compile;macrosTestsJS/test;concurrentJS/test"
)
addCommandAlias(
  "mimaChecks",
  "all coreJVM/mimaReportBinaryIssues streamsJVM/mimaReportBinaryIssues testsJVM/mimaReportBinaryIssues"
)

lazy val projectsCommon = List(
  concurrent,
  core,
  coreTests,
  examples,
  internalMacros,
  macros,
  macrosTests,
  managed,
  managedTests,
  stacktracer,
  streams,
  streamsTests,
  tests,
  testRunner,
  testTests
)

lazy val rootJVM = project.in(file("target/rootJVM")).settings(publish / skip := true).aggregate(rootJVM213)

lazy val rootJVM212 = project.in(file("target/rootJVM212")).settings(publish / skip := true).aggregate(rootJVM213)

lazy val rootJVM213 = project
  .in(file("target/rootJVM213"))
  .settings(publish / skip := true)
  .aggregate(projectsCommon.map(p => p.jvm: ProjectReference) *)
  .aggregate(
    List[ProjectReference](
      benchmarks,
      scalafixTests,
      testJunitRunner,
      testJunitRunnerTests,
      testJunitEngine,
      testJunitEngineTests,
      testMagnolia.jvm,
      testMagnoliaTests.jvm,
      testRefined.jvm,
      testScalaCheck.jvm
    ) *
  )

lazy val rootJVM3 = project
  .in(file("target/rootJVM3"))
  .settings(publish / skip := true)
  .aggregate(projectsCommon.map(p => p.jvm: ProjectReference) *)
  .aggregate(
    List[ProjectReference](
      testJunitRunner,
      testJunitEngine,
      testJunitRunnerTests,
      testJunitEngineTests,
      testMagnolia.jvm,
      testMagnoliaTests.jvm,
      testRefined.jvm,
      testScalaCheck.jvm
    ) *
  )

lazy val rootJS = project
  .in(file("target/rootJS"))
  .settings(publish / skip := true)
  .aggregate(projectsCommon.map(p => p.js: ProjectReference) *)
  .aggregate(
    List[ProjectReference](
      testMagnolia.js,
      testMagnoliaTests.js,
      testRefined.js,
      testScalaCheck.js
    ) *
  )

lazy val rootNative = project
  .in(file("target/rootNative"))
  .settings(publish / skip := true)
  .aggregate(projectsCommon.map(_.native: ProjectReference) *)
  .aggregate(
    List[ProjectReference](
      testScalaCheck.native
    ) *
  )

lazy val root212 = project.in(file("target/root212")).settings(publish / skip := true).aggregate(root213)

lazy val root213 = project
  .in(file("target/root213"))
  .settings(publish / skip := true)
  .aggregate(projectsCommon.flatMap(p => List[ProjectReference](p.jvm, p.js, p.native)) *)
  .aggregate(
    List(
      testScalaCheck
    ).flatMap(p => List[ProjectReference](p.jvm, p.js, p.native)) *
  )
  .aggregate(
    List(
      testMagnolia,
      testMagnoliaTests,
      testRefined
    ).flatMap(p => List[ProjectReference](p.jvm, p.js)) *
  )
  .aggregate(
    List[ProjectReference](
      benchmarks,
      scalafixTests,
      testJunitRunner,
      testJunitEngine,
      testJunitRunnerTests,
      testJunitEngineTests
    ) *
  )

lazy val root3 = project
  .in(file("target/root3"))
  .settings(publish / skip := true)
  .aggregate(projectsCommon.flatMap(p => List[ProjectReference](p.jvm, p.js, p.native)) *)
  .aggregate(
    List(
      testScalaCheck
    ).flatMap(p => List[ProjectReference](p.jvm, p.js, p.native)) *
  )
  .aggregate(
    List(
      testMagnolia,
      testMagnoliaTests,
      testRefined
    ).flatMap(p => List[ProjectReference](p.jvm, p.js)) *
  )
  .aggregate(
    List[ProjectReference](
      testJunitRunner,
      testJunitEngine,
      testJunitRunnerTests,
      testJunitEngineTests
    ) *
  )

lazy val root = project
  .in(file("."))
  .settings(
    name           := "zio",
    publish / skip := true,
    console        := (core.jvm / Compile / console).value,
    unusedCompileDependenciesFilter -= moduleFilter(
      "org.scala-js",
      "scalajs-library"
    ),
    welcomeMessage
  )
  .aggregate(root213)
  .enablePlugins(ScalaJSPlugin)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core"))
  .dependsOn(internalMacros, stacktracer)
  .settings(stdSettings("zio"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio"))
  .settings(libraryDependencies += "dev.zio" %%% "izumi-reflect" % IzumiReflectVersion)
  .enablePlugins(BuildInfoPlugin)
  .settings(macroDefinitionSettings)
  .settings(scalacOptions += "-Wconf:msg=[zio.stacktracer.TracingImplicits.disableAutoTrace]:silent")
  .jvmSettings(
    replSettings,
    mimaSettings(failOnProblem = true)
  )
  .jsSettings(
    jsSettings,
    libraryDependencies ++= List(
      "org.scala-js" %%% "scala-js-macrotask-executor" % "1.1.1",
      "org.scala-js" %%% "scalajs-weakreferences"      % "1.0.0" cross CrossVersion.for3Use2_13,
      "org.scala-js" %%% "scalajs-dom"                 % ScalaJsDomVersion
    ),
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        // Temporarily disable warning to use `MacrotaskExecutor` https://github.com/zio/zio/issues/6308
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )
  .nativeSettings(
    nativeSettings
  )

lazy val coreTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core-tests"))
  .dependsOn(core, tests, testRunner)
  .settings(stdSettings("core-tests"))
  .settings(crossProjectSettings)
  .settings(publish / skip := true)
  .settings(
    Compile / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
  .jvmConfigure(_.enablePlugins(JCStressPlugin))
  .jvmSettings(replSettings)
  .jsSettings(
    jsSettings,
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )
  .nativeSettings(nativeSettings)

lazy val managed = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("managed"))
  .dependsOn(core, streams)
  .settings(stdSettings("zio-managed"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.managed"))
  .settings(streamReplSettings)
  .enablePlugins(BuildInfoPlugin)
  .settings(macroDefinitionSettings)
  .settings(scalacOptions += "-Wconf:msg=[zio.stacktracer.TracingImplicits.disableAutoTrace]:silent")
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")
  .jvmSettings(
    mimaSettings(failOnProblem = false)
  )
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)

lazy val managedTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("managed-tests"))
  .dependsOn(managed, tests, testRunner)
  .settings(stdSettings("managed-tests"))
  .settings(crossProjectSettings)
  .settings(publish / skip := true)
  .settings(
    Compile / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
  .jvmConfigure(_.enablePlugins(JCStressPlugin))
  .jvmSettings(replSettings)
  .jsSettings(
    jsSettings,
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )
  .nativeSettings(nativeSettings)

lazy val macros = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("macros"))
  .dependsOn(core, managed)
  .settings(stdSettings("zio-macros"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")

lazy val macrosTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("macros-tests"))
  .dependsOn(macros, testRunner)
  .settings(stdSettings("macros-tests"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)
  .settings(publish / skip := true)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)

lazy val internalMacros = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("internal-macros"))
  .settings(stdSettings("zio-internal-macros"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)

lazy val streams = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("streams"))
  .dependsOn(core)
  .settings(stdSettings("zio-streams"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.stream"))
  .settings(streamReplSettings)
  .enablePlugins(BuildInfoPlugin)
  .settings(macroDefinitionSettings)
  .settings(scalacOptions += "-Wconf:msg=[zio.stacktracer.TracingImplicits.disableAutoTrace]:silent")
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")
  .jvmSettings(mimaSettings(failOnProblem = true))
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)

lazy val streamsTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("streams-tests"))
  .dependsOn(streams, concurrent, testRunner)
  .settings(stdSettings("streams-tests"))
  .settings(crossProjectSettings)
  .settings(publish / skip := true)
  .settings(
    Compile / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.AllLibraryJars
  )
  .jsSettings(
    jsSettings,
    scalacOptions ++= {
      if (scalaVersion.value == Scala3) {
        List()
      } else {
        List("-P:scalajs:nowarnGlobalExecutionContext")
      }
    }
  )
  .nativeSettings(nativeSettings)

lazy val tests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("test"))
  .dependsOn(core, streams)
  .settings(stdSettings("zio-test"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(macroExpansionSettings)
  .settings(
    libraryDependencies += "org.portable-scala" %%% "portable-scala-reflect" % "1.1.3" cross CrossVersion.for3Use2_13
  )
  .settings(scalacOptions += "-Wconf:msg=[zio.stacktracer.TracingImplicits.disableAutoTrace]:silent")
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")
  .jvmSettings(mimaSettings(failOnProblem = true))
  .jsSettings(
    jsSettings,
    libraryDependencies ++= List(
      "io.github.cquiroz" %%% "scala-java-time"      % ScalaJavaTimeVersion,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % ScalaJavaTimeVersion
    )
  )
  .nativeSettings(
    nativeSettings,
    libraryDependencies ++= List(
      "io.github.cquiroz" %%% "scala-java-time"      % ScalaJavaTimeVersion,
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % ScalaJavaTimeVersion,
      "com.github.lolgab" %%% "scala-native-crypto"  % ScalaNativeCryptoVersion
    )
  )

lazy val testTests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("test-tests"))
  .dependsOn(tests, testRunner)
  .settings(stdSettings("test-tests"))
  .settings(crossProjectSettings)
  .settings(publish / skip := true)
  .settings(macroExpansionSettings)
  .jsSettings(
    jsSettings,
    libraryDependencies ++= List(
      ("org.scala-js" %%% "scalajs-java-securerandom" % ScalaSecureRandomVersion).cross(CrossVersion.for3Use2_13)
    )
  )
  .nativeSettings(nativeSettings)

lazy val testMagnolia = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("test-magnolia"))
  .dependsOn(tests)
  .settings(stdSettings("zio-test-magnolia"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(
    scalacOptions ++= {
      if (scalaVersion.value == Scala3)
        Seq.empty
      else
        Seq("-language:experimental.macros")
    },
    libraryDependencies ++= {
      if (scalaVersion.value == Scala3)
        Seq(
          ("com.softwaremill.magnolia1_3" %%% "magnolia" % MagnoliaScala3Version)
            .exclude("org.scala-lang", "scala-compiler")
        )
      else
        Seq(
          ("com.softwaremill.magnolia1_2" %%% "magnolia" % MagnoliaScala2Version)
            .exclude("org.scala-lang", "scala-compiler")
        )
    }
  )
  .jsSettings(jsSettings)

lazy val testMagnoliaTests = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("test-magnolia-tests"))
  .dependsOn(testMagnolia, testTests % "test->test;compile->compile", testRunner)
  .settings(stdSettings("test-magnolia-tests"))
  .settings(crossProjectSettings)
  .settings(
    publish / skip := true
  )
  .jsSettings(jsSettings)

lazy val testRefined = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("test-refined"))
  .dependsOn(testMagnolia)
  .settings(stdSettings("zio-test-refined"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(
    libraryDependencies += "eu.timepit" %% "refined" % RefinedVersion cross CrossVersion.for3Use2_13
  )
  .jsSettings(jsSettings)

lazy val testScalaCheck = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("test-scalacheck"))
  .dependsOn(tests)
  .settings(stdSettings("zio-test-scalacheck"))
  .settings(crossProjectSettings)
  .settings(libraryDependencies += "org.scalacheck" %%% "scalacheck" % ScalaCheckVersion)
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)

lazy val stacktracer = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("stacktracer"))
  .settings(stdSettings("zio-stacktracer"))
  .settings(crossProjectSettings)
  .settings(macroDefinitionSettings)
  .settings(buildInfoSettings("zio.internal.stacktracer"))
  .enablePlugins(BuildInfoPlugin)
  .jvmSettings(replSettings)
  .jsSettings(jsSettings)
  .nativeSettings(
    nativeSettings,
    scalacOptions -= "-Xfatal-warnings" // Issue 3112
  )

lazy val testRunner = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("test-sbt"))
  .settings(stdSettings("zio-test-sbt"))
  .settings(crossProjectSettings)
  .settings(Test / run / mainClass := Some("zio.test.sbt.TestMain"))
  .settings(scalacOptions += "-Wconf:msg=[zio.stacktracer.TracingImplicits.disableAutoTrace]:silent")
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")
  .dependsOn(core, tests)
  .jvmSettings(libraryDependencies ++= Seq("org.scala-sbt" % "test-interface" % "1.0"))
  .jsSettings(
    jsSettings,
    libraryDependencies ++= Seq(
      ("org.scala-js" %% "scalajs-test-interface" % scalaJSVersion).cross(CrossVersion.for3Use2_13)
    )
  )
  .nativeSettings(
    nativeSettings,
    libraryDependencies ++= Seq("org.scala-native" %%% "test-interface" % nativeVersion)
  )

lazy val testJunitRunner = project.module
  .in(file("test-junit"))
  .settings(stdSettings("zio-test-junit"))
  .settings(libraryDependencies ++= Seq("junit" % "junit" % JunitVersion))
  .dependsOn(tests.jvm)

lazy val commonJunitTestSettings = Seq(
  Test / fork    := true,
  publish / skip := true,
  Test / javaOptions ++= Seq(
    s"-Dproject.dir=${baseDirectory.value}",
    s"-Dproject.version=${version.value}",
    s"-Dscala.version=${scalaVersion.value}",
    s"-Dscala.compat.version=${scalaBinaryVersion.value}"
  ),
  libraryDependencies ++= Seq(
    "junit"                     % "junit"                          % "4.13.2" % Test,
    "org.scala-lang.modules"   %% "scala-xml"                      % "2.3.0"  % Test,
    "org.apache.maven"          % "maven-embedder"                 % "3.9.9"  % Test,
    "org.apache.maven"          % "maven-compat"                   % "3.9.9"  % Test,
    "com.google.inject"         % "guice"                          % "6.0.0"  % Test,
    "org.eclipse.sisu"          % "org.eclipse.sisu.inject"        % "0.3.5"  % Test,
    "org.apache.maven.resolver" % "maven-resolver-connector-basic" % "1.9.22" % Test,
    "org.apache.maven.resolver" % "maven-resolver-transport-http"  % "1.9.22" % Test,
    "org.codehaus.plexus"       % "plexus-component-annotations"   % "2.2.0"  % Test,
    "org.slf4j"                 % "slf4j-simple"                   % "2.0.16" % Test
  )
)

lazy val testJunitRunnerTests = project.module
  .in(file("test-junit-tests"))
  .settings(stdSettings("test-junit-tests"))
  .settings(commonJunitTestSettings)
  .dependsOn(tests.jvm, testRunner.jvm)
  // publish locally so embedded maven runs against locally compiled zio
  .settings(
    Test / Keys.test :=
      (Test / Keys.test)
        .dependsOn(
          testJunitRunner / publishM2,
          tests.jvm / publishM2,
          core.jvm / publishM2,
          internalMacros.jvm / publishM2,
          streams.jvm / publishM2,
          stacktracer.jvm / publishM2
        )
        .value
  )

lazy val testJunitEngine = project.module
  .in(file("test-junit-engine"))
  .settings(stdSettings("zio-test-junit-engine"))
  .settings(
    libraryDependencies ++= Seq(
      "org.junit.platform"      % "junit-platform-engine"   % JunitPlatformEngineVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % ScalaCollectionCompatVersion
    )
  )
  .dependsOn(tests.jvm)

lazy val testJunitEngineTests = project.module
  .in(file("test-junit-engine-tests"))
  .settings(stdSettings("test-junit-engine-tests"))
  .settings(commonJunitTestSettings)
  .dependsOn(tests.jvm, testRunner.jvm)
  // publish locally so embedded maven runs against locally compiled zio
  .settings(
    Test / Keys.test :=
      (Test / Keys.test)
        .dependsOn(
          testJunitEngine / publishM2,
          tests.jvm / publishM2,
          core.jvm / publishM2,
          internalMacros.jvm / publishM2,
          streams.jvm / publishM2,
          stacktracer.jvm / publishM2
        )
        .value
  )

lazy val concurrent = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("concurrent"))
  .dependsOn(core, testRunner % Test)
  .settings(stdSettings("zio-concurrent"))
  .settings(crossProjectSettings)
  .settings(buildInfoSettings("zio.concurrent"))
  .enablePlugins(BuildInfoPlugin)
  .jvmSettings(mimaSettings(failOnProblem = false))
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")

/**
 * Examples sub-project that is not included in the root project.
 *
 * To run tests: `sbt "examplesJVM/test"`
 */
lazy val examples = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("examples"))
  .settings(stdSettings("examples"))
  .settings(crossProjectSettings)
  .settings(macroExpansionSettings)
  .settings(publish / skip := true)
  .settings(Test / test := (Test / compile).value)
  .settings(
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    libraryDependencies ++= List(
      `zio-http`,
      `zio-metrics-connectors`,
      `zio-metrics-connectors-prometheus`
    )
  )
  .dependsOn(macros, testRunner)
  .jvmConfigure(_.dependsOn(testJunitRunner))
  .jsSettings(
    jsSettings,
    libraryDependencies ++= List(
      ("org.scala-js" %%% "scalajs-java-securerandom" % ScalaSecureRandomVersion).cross(CrossVersion.for3Use2_13)
    )
  )
  .nativeSettings(nativeSettings)

lazy val benchmarks = project.module
  .dependsOn(core.jvm, streams.jvm, tests.jvm)
  .enablePlugins(JmhPlugin)
  .settings(replSettings)
  .settings(
    publish / skip := true,
    libraryDependencies ++= {
      val nyanaVersion = if (scalaVersion.value == Scala212) "0.10.0" else "1.1.0"
      Seq(
        "co.fs2"                    %% "fs2-core"      % Fs2Version,
        "com.twitter"               %% "util-core"     % "24.2.0",
        "com.typesafe.akka"         %% "akka-stream"   % "2.8.8",
        "io.github.timwspence"      %% "cats-stm"      % "0.13.4",
        "io.projectreactor"          % "reactor-core"  % "3.7.0",
        "io.reactivex.rxjava2"       % "rxjava"        % "2.2.21",
        "org.jctools"                % "jctools-core"  % "4.0.5",
        "org.typelevel"             %% "cats-effect"   % CatsEffectVersion,
        "org.scalacheck"            %% "scalacheck"    % ScalaCheckVersion,
        "qa.hedgehog"               %% "hedgehog-core" % "0.11.0",
        "com.github.japgolly.nyaya" %% "nyaya-gen"     % nyanaVersion,
        "org.springframework"        % "spring-core"   % "6.2.0"
      )
    },
    excludeDependencies ++= {
      if (scalaVersion.value == Scala3) List(ExclusionRule("org.scala-lang.modules", "scala-collection-compat_2.13"))
      else Nil
    },
    Compile / console / scalacOptions := Seq(
      "-language:higherKinds",
      "-language:existentials",
      "-Xsource:2.13",
      "-Yrepl-class-based"
    )
  )
  .settings(scalacOptions += "-Wconf:msg=[@nowarn annotation does not suppress any warnings]:silent")
  .settings(
    assembly / assemblyJarName := "benchmarks.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class") => MergeStrategy.discard
      case path                          => MergeStrategy.defaultMergeStrategy(path)
    },
    assembly / fullClasspath := (Jmh / fullClasspath).value,
    assembly / mainClass     := Some("org.openjdk.jmh.Main")
  )

lazy val jsdocs = project
  .settings(libraryDependencies += "org.scala-js" %%% "scalajs-dom" % ScalaJsDomVersion)
  .enablePlugins(ScalaJSPlugin)

lazy val scalafixSettings = List(
  scalaVersion   := Scala213,
  ideSkipProject := true,
  addCompilerPlugin(scalafixSemanticdb),
  crossScalaVersions --= List(Scala212, Scala3),
  scalacOptions ++= List(
    "-Yrangepos",
    "-P:semanticdb:synthetics:on"
  )
)

lazy val scalafixRules = project.module
  .in(file("scalafix/rules")) // TODO .in needed when name matches?
  .settings(
    scalafixSettings,
    semanticdbEnabled                      := true, // enable SemanticDB
    libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % scalafixVersion
  )

lazy val scalafixInput = project
  .in(file("scalafix/input"))
  .settings(
    scalafixSettings,
    publish / skip := true,
    libraryDependencies ++= {
      val zio1Version = "1.0.18"
      Seq(
        "dev.zio" %% "zio"         % zio1Version,
        "dev.zio" %% "zio-streams" % zio1Version,
        "dev.zio" %% "zio-test"    % zio1Version
      )
    }
  )

lazy val scalafixOutput = project
  .in(file("scalafix/output"))
  .settings(
    scalafixSettings,
    publish / skip := true
  )
  .dependsOn(core.jvm, tests.jvm, streams.jvm, managed.jvm)

lazy val scalafixTests = project
  .in(file("scalafix/tests"))
  .settings(
    scalafixSettings,
    publish / skip                        := true,
    libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % scalafixVersion % Test cross CrossVersion.full,
    Compile / compile :=
      (Compile / compile).dependsOn(scalafixInput / Compile / compile).value,
    scalafixTestkitOutputSourceDirectories :=
      (scalafixOutput / Compile / sourceDirectories).value,
    scalafixTestkitInputSourceDirectories :=
      (scalafixInput / Compile / sourceDirectories).value,
    scalafixTestkitInputClasspath :=
      (scalafixInput / Compile / fullClasspath).value
  )
  .dependsOn(scalafixRules)
  .enablePlugins(ScalafixTestkitPlugin)

lazy val docs_make_zio_app_configurable =
  project
    .in(file("documentation/guides/tutorials/make-a-zio-app-configurable"))
    .settings(
      mdocSettings("docs", "website/docs/guides/tutorials/"),
      fork           := true,
      publish / skip := true,
      scalaVersion   := Scala213,
      unusedCompileDependenciesFilter -= moduleFilter("org.scalameta", "mdoc"),
      scalacOptions -= "-Yno-imports",
      scalacOptions -= "-Xfatal-warnings",
      scalacOptions += "-Wconf:any:s",
      Compile / fork := false,
      scalacOptions ~= { _.filterNot(_.startsWith("-Ywarn")).filterNot(_.startsWith("-Xlint")) },
      crossScalaVersions --= List(Scala212, Scala3),
      libraryDependencies ++= Seq(
        `zio-json`,
        `zio-http`,
        `zio-config`,
        `zio-config-typesafe`,
        `zio-config-magnolia`,
        "io.getquill"   %% "quill-zio"      % QuillVersion,
        "io.getquill"   %% "quill-jdbc-zio" % QuillVersion,
        "com.h2database" % "h2"             % "2.3.232"
      )
    )
    .dependsOn(core.jvm, streams.jvm)
    .enablePlugins(MdocPlugin)

lazy val docs = project.module
  .in(file("zio-docs"))
  .settings(
    publish / skip := true,
    moduleName     := "zio-docs",
    scalaVersion   := Scala213,
    ideSkipProject := true,
    unusedCompileDependenciesFilter -= moduleFilter("org.scalameta", "mdoc"),
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-Wconf:any:s",
    scalacOptions ~= { _ filterNot (_ startsWith "-Ywarn") },
    scalacOptions ~= { _ filterNot (_ startsWith "-Xlint") },
    crossScalaVersions --= List(Scala212, Scala3),
    mdocIn  := (LocalRootProject / baseDirectory).value / "docs",
    mdocOut := (LocalRootProject / baseDirectory).value / "website" / "docs",
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      core.jvm,
      streams.jvm,
      tests.jvm,
      testMagnolia.jvm,
      testRefined.jvm,
      testScalaCheck.jvm
    ),
    ScalaUnidoc / unidoc / target := (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
    cleanFiles += (ScalaUnidoc / unidoc / target).value,
    docusaurusCreateSite     := docusaurusCreateSite.dependsOn(Compile / unidoc).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(Compile / unidoc).value,
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    mdocVariables ++= Map(
      "ZIO_METRICS_CONNECTORS_VERSION" -> ZioMetricsConnectorsVersion,
      "ZIO_CONFIG_VERSION"             -> ZioConfigVersion,
      "ZIO_JSON_VERSION"               -> ZioJsonVersion
    ),
    libraryDependencies ++= Seq(
      `zio-http`,
      `distage-core`,
      `logstage-core`,
      `zio-config`,
      `zio-config-magnolia`,
      `zio-config-typesafe`,
      `zio-config-refined`,
      `zio-ftp`,
      `zio-json`,
      `zio-nio`,
      `zio-optics`,
      `zio-akka-cluster`,
      `zio-cache`,
      `zio-kafka`,
      `zio-logging`,
      `zio-logging-slf4j`,
      `zio-metrics-connectors`,
      `zio-metrics-connectors-prometheus`,
      `zio-prelude`,
      `zio-process`,
      `zio-rocksdb`,
      `zio-s3`,
      `zio-schema`,
      `zio-sqs`,
      `zio-opentracing`,
      `zio-interop-cats`,
      `zio-interop-scalaz7x`,
      `zio-interop-reactivestreams`,
      `zio-interop-twitter`,
      `zio-zmx`,
      `zio-query`,
      `zio-mock`,
      "commons-io"             % "commons-io"                % "2.17.0" % "provided",
      "org.jsoup"              % "jsoup"                     % "1.18.1" % "provided",
      "org.reactivestreams"    % "reactive-streams-examples" % "1.0.4"  % "provided",
      "org.typelevel"         %% "cats-effect"               % CatsEffectVersion,
      "dev.zio"               %% "zio-actors"                % ZioActorsVersion,
      "io.laserdisc"          %% "tamer-db"                  % "0.21.3",
      "io.jaegertracing"       % "jaeger-core"               % "1.8.1",
      "io.jaegertracing"       % "jaeger-client"             % "1.8.1",
      "io.jaegertracing"       % "jaeger-zipkin"             % "1.8.1",
      "io.zipkin.reporter2"    % "zipkin-reporter"           % "3.4.2",
      "io.zipkin.reporter2"    % "zipkin-sender-okhttp3"     % "3.4.2",
      "org.polynote"          %% "uzhttp"                    % "0.3.0-RC1",
      "org.tpolecat"          %% "doobie-core"               % DoobieVersion,
      "org.tpolecat"          %% "doobie-h2"                 % DoobieVersion,
      "org.tpolecat"          %% "doobie-hikari"             % DoobieVersion,
      "org.http4s"            %% "http4s-ember-server"       % Http4sVersion,
      "org.http4s"            %% "http4s-ember-client"       % Http4sVersion,
      "org.http4s"            %% "http4s-dsl"                % Http4sVersion,
      "com.github.ghostdogpr" %% "caliban-quick"             % "2.9.0",
      "org.scalameta"         %% "munit"                     % "1.0.2",
      "com.github.poslegm"    %% "munit-zio"                 % "0.3.0",
      "nl.vroste"             %% "rezilience"                % "0.10.3",
      "io.github.gaelrenoux"  %% "tranzactio"                % "4.2.0",
      "io.github.neurodyne"   %% "zio-arrow"                 % "0.2.1",
      "nl.vroste"             %% "zio-amqp"                  % "0.5.0",
//      "dev.zio"                       %% "zio-aws-core"                  % "5.17.102.7",
//      "dev.zio"                       %% "zio-aws-ec2"                   % "5.17.102.7",
//      "dev.zio"                       %% "zio-aws-elasticbeanstalk"      % "5.17.102.7",
//      "dev.zio"                       %% "zio-aws-netty"                 % "5.17.102.7",
      "io.github.neurodyne"           %% "zio-aws-s3"                    % "0.4.13",
      "com.coralogix"                 %% "zio-k8s-client"                % "3.1.0",
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.10.1",
      "nl.vroste"                     %% "zio-kinesis"                   % "0.35.0",
      "com.vladkopanev"               %% "zio-saga-core"                 % "0.6.0",
      "io.scalac"                     %% "zio-slick-interop"             % "0.6.0",
      "com.typesafe.slick"            %% "slick-hikaricp"                % "3.5.2",
      "info.senia"                    %% "zio-test-akka-http"            % "2.0.14",
      "io.getquill"                   %% "quill-jdbc-zio"                % QuillVersion,
      "com.typesafe.akka"             %% "akka-http"                     % "10.5.2",
      "com.typesafe.akka"             %% "akka-cluster-typed"            % "2.8.4",
      "com.typesafe.akka"             %% "akka-cluster-sharding-typed"   % "2.8.4",
      "com.devsisters"                %% "shardcake-core"                % ShardcakeVersion,
      "com.devsisters"                %% "shardcake-storage-redis"       % ShardcakeVersion,
      "com.devsisters"                %% "shardcake-protocol-grpc"       % ShardcakeVersion,
      "com.devsisters"                %% "shardcake-entities"            % ShardcakeVersion,
      "com.devsisters"                %% "shardcake-manager"             % ShardcakeVersion,
      "com.devsisters"                %% "shardcake-serialization-kryo"  % ShardcakeVersion,
      "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core"                 % "0.6.3",
      "dev.hnaderi"                   %% "scala-k8s-zio"                 % "0.20.1"
    ),
    resolvers += "Confluent" at "https://packages.confluent.io/maven",
    fork           := true,
    Compile / fork := false
  )
  .settings(macroDefinitionSettings)
  .settings(mdocJS := Some(jsdocs))
  .dependsOn(
    core.jvm,
    streams.jvm,
    concurrent.jvm,
    tests.jvm,
    testJunitRunner,
    testJunitEngine,
    testMagnolia.jvm,
    testRefined.jvm,
    testScalaCheck.jvm,
    core.js,
    macros.jvm
  )
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
  .aggregate(docs_make_zio_app_configurable)

Global / excludeLintKeys += ideSkipProject
