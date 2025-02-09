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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.duration.{Duration => ScalaDuration}
import scala.concurrent.{CanAwait, ExecutionContext, Future}
import scala.util.Try

abstract class CancelableFuture[+A](val future: Future[A]) extends Future[A] with FutureTransformCompat[A] {

  /**
   * Immediately cancels the operation and returns a [[scala.concurrent.Future]]
   * containing the result
   */
  def cancel(): Future[Exit[Throwable, A]]

  final def onComplete[U](f: Try[A] => U)(implicit executor: ExecutionContext): Unit =
    future.onComplete(f)(executor)

  final def isCompleted: Boolean =
    future.isCompleted

  final def value: Option[Try[A]] =
    future.value

  final def ready(atMost: ScalaDuration)(implicit permit: CanAwait): this.type = {
    future.ready(atMost)(permit)
    this
  }

  final def result(atMost: ScalaDuration)(implicit permit: CanAwait): A =
    future.result(atMost)
}
