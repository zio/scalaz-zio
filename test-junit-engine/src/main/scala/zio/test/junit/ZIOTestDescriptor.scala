/*
 * Copyright 2024-2024 Vincent Raman and the ZIO Contributors
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

package zio.test.junit

import org.junit.platform.engine.{TestDescriptor, UniqueId}
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import zio.test.TestAnnotationMap

/**
 * Describes a JUnit 5 test descriptor for a single ZIO tests.
 *
 * @constructor
 *   Creates a new ZIOTestDescriptor instance.
 * @param parent
 *   The parent descriptor of this test descriptor.
 * @param uniqueId
 *   A unique identifier for this test descriptor.
 * @param label
 *   The display name or label for this test descriptor.
 * @param testClass
 *   The test class associated with this descriptor.
 * @param annotations
 *   A map of annotations applied to this test descriptor.
 */
class ZIOTestDescriptor(
  parent: TestDescriptor,
  uniqueId: UniqueId,
  label: String,
  testClass: Class[_],
  annotations: TestAnnotationMap
) extends AbstractTestDescriptor(uniqueId, label, ZIOTestSource(testClass, annotations)) {
  setParent(parent)
  override def getType: TestDescriptor.Type = TestDescriptor.Type.TEST
}

object ZIOTestDescriptor {
  val segmentType = "test"
}
