---
id: installation
title: "Installing ZIO Test"
sidebar_label: "Installation"
---

In order to use ZIO Test, we need to add the required configuration in our SBT settings:

````scala mdoc:passthrough

println("""```scala""")
println(s"""libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test"          % "${zio.BuildInfo.version.split('+').head}" % Test,
  "dev.zio" %% "zio-test-sbt"      % "${zio.BuildInfo.version.split('+').head}" % Test,
  "dev.zio" %% "zio-test-magnolia" % "${zio.BuildInfo.version.split('+').head}" % Test
)""")
println("""```""")
````

If our SBT version is older than 1.8.0, we also need to add the test framework manually:

````scala mdoc:passthrough
println("""```scala""")
println("""testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")""")
println("""```""")
````

**NOTE**: In order to use the live version of a service in our tests, we can use some new helpful test aspects e.g `withLiveClock`, `withLiveConsole`, `withLiveRandom`, `withLiveSystem`, etc.
