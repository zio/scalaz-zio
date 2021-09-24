/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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
import zio.test.environment._

private[test] abstract class PlatformSpecific {
  type TestEnvironment =
    Has[Annotations]
      with Has[Live]
      with Has[Sized]
      with Has[TestClock]
      with Has[TestConfig]
      with Has[TestConsole]
      with Has[TestRandom]
      with Has[TestSystem]
      with ZEnv

  object TestEnvironment {
    val any: ZLayer[TestEnvironment, Nothing, TestEnvironment] =
      ZLayer.environment[TestEnvironment]
    val live: ZLayer[ZEnv, Nothing, TestEnvironment] =
      Annotations.live ++
        Live.default ++
        Sized.live(100) ++
        ((Live.default ++ Annotations.live) >>> TestClock.default) ++
        TestConfig.live(100, 100, 200, 1000) ++
        (Live.default >>> TestConsole.debug) ++
        TestRandom.deterministic ++
        TestSystem.default
  }
}
