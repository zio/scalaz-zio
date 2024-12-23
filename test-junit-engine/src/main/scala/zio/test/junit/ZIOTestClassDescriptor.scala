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

import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.{TestDescriptor, UniqueId}
import zio._
import zio.test._
import zio.test.junit.ReflectionUtils._

/**
 * Represents a JUnit 5 test descriptor for a ZIO-based test class
 *
 * @param parent
 *   The parent TestDescriptor.
 * @param uniqueId
 *   A unique identifier for this TestDescriptor.
 * @param testClass
 *   The class representing the test.
 */
class ZIOTestClassDescriptor(parent: TestDescriptor, uniqueId: UniqueId, val testClass: Class[_])
    extends AbstractTestDescriptor(uniqueId, testClass.getName.stripSuffix("$"), ZIOTestSource(testClass)) {

  setParent(parent)
  val className: String = testClass.getName

  // reflection to get the spec implementation
  // by default, it would be an object, extending ZIOSpecDefault
  // but this also support class that needs to be instantiated
  val spec: ZIOSpecAbstract = getCompanionObject(testClass)
    .getOrElse(testClass.getDeclaredConstructor().newInstance())
    .asInstanceOf[ZIOSpecAbstract]

  def traverse[R, E](
    spec: Spec[R, E],
    description: TestDescriptor,
    path: Vector[String] = Vector.empty
  ): ZIO[R with Scope, Any, Unit] =
    spec.caseValue match {
      case Spec.ExecCase(_, spec: Spec[R, E]) => traverse(spec, description, path)
      case Spec.LabeledCase(label, spec: Spec[R, E]) =>
        traverse(spec, description, path :+ label)
      case Spec.ScopedCase(scoped) => scoped.flatMap((s: Spec[R, E]) => traverse(s, description, path))
      case Spec.MultipleCase(specs) =>
        val suiteDesc = new ZIOSuiteTestDescriptor(
          description,
          description.getUniqueId.append(ZIOSuiteTestDescriptor.segmentType, path.lastOption.getOrElse("")),
          path.lastOption.getOrElse(""),
          testClass
        )
        ZIO.succeed(description.addChild(suiteDesc)) *>
          ZIO.foreach(specs)((s: Spec[R, E]) => traverse(s, suiteDesc, path)).ignore
      case Spec.TestCase(_, annotations) =>
        ZIO.succeed(
          description.addChild(
            new ZIOTestDescriptor(
              description,
              description.getUniqueId.append(ZIOTestDescriptor.segmentType, path.lastOption.getOrElse("")),
              path.lastOption.getOrElse(""),
              testClass,
              annotations
            )
          )
        )
    }

  lazy val scoped: ZIO[spec.Environment with TestEnvironment, Any, Unit] =
    ZIO.scoped[spec.Environment with TestEnvironment](
      traverse(spec.spec, this)
    )

  Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe
      .run(
        scoped
          .provide(
            Scope.default >>> (liveEnvironment >>> TestEnvironment.live ++ ZLayer.environment[Scope]),
            spec.bootstrap
          )
      )
      .getOrThrowFiberFailure()
  }

  override def getType: TestDescriptor.Type = TestDescriptor.Type.CONTAINER
}

object ZIOTestClassDescriptor {
  val segmentType = "class"
}
