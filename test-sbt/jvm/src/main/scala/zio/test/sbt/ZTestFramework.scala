/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test.sbt

import sbt.testing._

/**
 * =General Test Pieces=
 *
 * [[zio.test.ZIOSpecAbstract]]
 *
 * Contains test logic and how it should be executed. The most important method
 * is:
 *
 * `runSpec` is the most significant method in this class. It:
 *   - Interacts with the Runtime
 *   - Builds TestRunner
 *   - Folds aspects into logic
 *   - Builds `TestExecutor` and passes spec to it
 *   - Returns summary
 *
 * [[zio.test.TestExecutor]]
 *
 * Capable of executing specs that require an environment `R` and may fail with
 * an `E` Recursively traverses tree of specs, executing suites/tests in
 * parallel
 *
 * [[zio.test.TestRunner]]
 *
 * Encapsulates the logic necessary to run specs that require an environment `R`
 * and may fail with an error `E`.
 *
 * ==SBT-specific pieces==
 *
 * [[sbt.testing.Task]]
 *
 * SBT needs everything packaged in these to run tests/suites
 *
 * [[zio.test.sbt.ZTestTask]] extends [[Task]]
 *
 * Contains a ZIOSpecAbstract and everything that SBT needs to run/report it.
 *
 * [[sbt.testing.Runner]]
 *
 * SBT delegates to `Runner` clients for managing/executing test
 *
 * [[zio.test.sbt.ZioSpecFingerprint]] What SBT needs to find your tests. Finds
 * `ZIOSpecAbstract` implementations in your codebase.
 *
 * [[zio.test.sbt.ZTestRunnerJVM]] extends [[sbt.testing.Runner]]
 *
 * Receives all Specs found by the `FingerPrint` and merges them into a single
 * `ZTestTask`
 *
 * [[sbt.testing.Framework]] We need to implement this for SBT to recognize
 * ZIO-test as a legitimate test framework.
 *
 * [[zio.test.sbt.ZTestFramework]] extends [[sbt.testing.Framework]] Defines
 * `ZIOSpecFingerPrint` & `ZTestRunner` and passes them to SBT
 */
final class ZTestFramework extends Framework {
  override val name: String = s"${Console.UNDERLINED}ZIO Test${Console.RESET}"

  val fingerprints: Array[Fingerprint] = Array(ZioSpecFingerprint)

  override def runner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader): ZTestRunnerJVM =
    new ZTestRunnerJVM(args, remoteArgs, testClassLoader)
}
