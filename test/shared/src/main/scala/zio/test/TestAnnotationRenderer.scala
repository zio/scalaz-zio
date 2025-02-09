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
import zio.test.TestAnnotationRenderer._

/**
 * A `TestAnnotationRenderer` knows how to render test annotations.
 */
sealed abstract class TestAnnotationRenderer { self =>

  def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String]

  /**
   * A symbolic alias for `combine`.
   */
  final def <>(that: TestAnnotationRenderer): TestAnnotationRenderer =
    self.combine(that)

  /**
   * Combines this test annotation renderer with the specified test annotation
   * renderer to produce a new test annotation renderer that renders both sets
   * of test annotations.
   */
  final def combine(that: TestAnnotationRenderer): TestAnnotationRenderer =
    (self, that) match {
      case (CompositeRenderer(left), CompositeRenderer(right)) => CompositeRenderer(left ++ right)
      case (CompositeRenderer(left), leaf)                     => CompositeRenderer(left ++ Vector(leaf))
      case (leaf, CompositeRenderer(right))                    => CompositeRenderer(Vector(leaf) ++ right)
      case (left, right)                                       => CompositeRenderer(Vector(left, right))
    }
}

object TestAnnotationRenderer {

  /**
   * A test annotation renderer that renders a single test annotation.
   */
  sealed abstract class LeafRenderer extends TestAnnotationRenderer

  object LeafRenderer {
    def apply[V](annotation: TestAnnotation[V])(render: ::[V] => Option[String]): TestAnnotationRenderer =
      new LeafRenderer {
        def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String] =
          render(::(child.get(annotation), ancestors.map(_.get(annotation)))).toList
      }
  }

  /**
   * A test annotation renderer that combines multiple other test annotation
   * renderers.
   */
  final case class CompositeRenderer(renderers: Vector[TestAnnotationRenderer]) extends TestAnnotationRenderer {
    def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String] =
      renderers.toList.flatMap(_.run(ancestors, child))
  }

  /**
   * The default test annotation renderer used by the `DefaultTestReporter`.
   */
  lazy val default: TestAnnotationRenderer =
    CompositeRenderer(Vector(ignored, repeated, retried, tagged, timed))

  /**
   * A test annotation renderer that renders the number of ignored tests.
   */
  val ignored: TestAnnotationRenderer =
    LeafRenderer(TestAnnotation.ignored) { case (child :: _) =>
      if (child == 0) None
      else Some(s"ignored: $child")
    }

  /**
   * A test annotation renderer that renders how many times a test was repeated.
   */
  val repeated: TestAnnotationRenderer =
    LeafRenderer(TestAnnotation.repeated) { case (child :: _) =>
      if (child == 0) None
      else Some(s"repeated: $child")
    }

  /**
   * A test annotation renderer that renders how many times a test had to be
   * retried before it succeeded.
   */
  val retried: TestAnnotationRenderer =
    LeafRenderer(TestAnnotation.retried) { case (child :: _) =>
      if (child == 0) None
      else Some(s"retried: $child")
    }

  /**
   * A test annotation renderer that renders string tags.
   */
  val tagged: TestAnnotationRenderer =
    LeafRenderer(TestAnnotation.tagged) { case (child :: _) =>
      if (child.isEmpty) None
      else Some(s"tagged: ${child.map("\"" + _ + "\"").mkString(", ")}")
    }

  /**
   * A test annotation renderer that does not render any test annotations.
   */
  val silent: TestAnnotationRenderer =
    new TestAnnotationRenderer {
      def run(ancestors: List[TestAnnotationMap], child: TestAnnotationMap): List[String] =
        List.empty
    }

  /**
   * A test annotation renderer that renders the time taken to execute each test
   * or suite both in absolute duration and as a percentage of total execution
   * time.
   */
  val timed: TestAnnotationRenderer =
    LeafRenderer(TestAnnotation.timing) { case (child :: _) =>
      if (child.isZero) None
      else Some(f"${child.render}")
    }
}
