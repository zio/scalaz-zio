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

package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

sealed abstract class TestSuccess { self =>

  /**
   * Retrieves the annotations associated with this test success.
   */
  def annotations: TestAnnotationMap

  /**
   * Annotates this test success with the specified test annotations.
   */
  def annotated(annotations: TestAnnotationMap): TestSuccess =
    self match {
      case TestSuccess.Succeeded(_) => TestSuccess.Succeeded(self.annotations ++ annotations)
      case TestSuccess.Ignored(_)   => TestSuccess.Ignored(self.annotations ++ annotations)
    }
}

object TestSuccess {
  final case class Succeeded(annotations: TestAnnotationMap = TestAnnotationMap.empty) extends TestSuccess
  final case class Ignored(annotations: TestAnnotationMap = TestAnnotationMap.empty)   extends TestSuccess
}
