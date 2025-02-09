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

import zio._
import zio.internal.stacktracer.SourceLocation
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.SortedSet

/**
 * A type of annotation.
 */
final class TestAnnotation[V] private (
  val identifier: String,
  val initial: V,
  val combine: (V, V) => V,
  private val tag: EnvironmentTag[V]
) extends Serializable {

  override def equals(that: Any): Boolean = (that: @unchecked) match {
    case that: TestAnnotation[_] => (identifier, tag) == ((that.identifier, that.tag))
  }

  override lazy val hashCode: Int =
    (identifier, tag).hashCode
}

object TestAnnotation {

  def apply[V](identifier: String, initial: V, combine: (V, V) => V)(implicit
    tag: EnvironmentTag[V]
  ): TestAnnotation[V] =
    new TestAnnotation(identifier, initial, combine, tag)

  /**
   * An annotation which counts ignored tests.
   */
  val ignored: TestAnnotation[Int] =
    TestAnnotation("ignored", 0, _ + _)

  /**
   * An annotation which tracks output produced by a test.
   */
  val output: TestAnnotation[Chunk[ConsoleIO]] =
    TestAnnotation("output", Chunk.empty, _ ++ _)

  /**
   * An annotation which counts repeated tests.
   */
  val repeated: TestAnnotation[Int] =
    TestAnnotation("repeated", 0, _ + _)

  /**
   * An annotation which counts retried tests.
   */
  val retried: TestAnnotation[Int] =
    TestAnnotation("retried", 0, _ + _)

  /**
   * An annotation which tags tests with strings.
   */
  val tagged: TestAnnotation[Set[String]] =
    TestAnnotation("tagged", Set.empty, _ union _)

  /**
   * An annotation for timing.
   */
  val timing: TestAnnotation[TestDuration] =
    TestAnnotation("timing", TestDuration.zero, _ <> _)

  /**
   * An annotation for capturing the trace information, including source
   * location (i.e. file name and line number) of the calling test.
   */
  private[zio] val trace: TestAnnotation[List[SourceLocation]] =
    TestAnnotation("trace", List.empty, _ ++ _)

  val fibers: TestAnnotation[Either[Int, Chunk[AtomicReference[SortedSet[Fiber.Runtime[Any, Any]]]]]] =
    TestAnnotation("fibers", Left(0), compose)

  def compose[A](left: Either[Int, Chunk[A]], right: Either[Int, Chunk[A]]): Either[Int, Chunk[A]] =
    (left, right) match {
      case (Left(n), Left(m))           => Left(n + m)
      case (Right(refs1), Right(refs2)) => Right(refs1 ++ refs2)
      case (Right(_), Left(n))          => Left(n)
      case (Left(_), Right(refs))       => Right(refs)
    }
}
