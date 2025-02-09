/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio._
import zio.internal.stacktracer.SourceLocation
import zio.stacktracer.TracingImplicits.disableAutoTrace

abstract class ZIOSpec[R: EnvironmentTag] extends ZIOSpecAbstract with ZIOSpecVersionSpecific[R] { self =>
  type Environment = R

  final val environmentTag: EnvironmentTag[R] = EnvironmentTag[R]

  /**
   * Builds a spec with a single test.
   */
  def test[In](label: String)(
    assertion: => In
  )(implicit
    testConstructor: TestConstructor[Nothing, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): testConstructor.Out =
    zio.test.test(label)(assertion)

  def suite[In](label: String)(specs: In*)(implicit
    suiteConstructor: SuiteConstructor[In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): Spec[suiteConstructor.OutEnvironment, suiteConstructor.OutError] =
    zio.test.suite(label)(specs: _*)

}
