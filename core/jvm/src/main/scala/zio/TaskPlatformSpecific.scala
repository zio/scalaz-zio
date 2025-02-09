/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

import zio.interop.javaz
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.nio.channels.CompletionHandler
import java.util.concurrent.{CompletableFuture, CompletionStage}

private[zio] trait TaskPlatformSpecific {

  def asyncWithCompletionHandler[T](op: CompletionHandler[T, Any] => Any)(implicit trace: Trace): Task[T] =
    javaz.asyncWithCompletionHandler(op)

  /**
   * Alias for `formCompletionStage` for a concrete implementation of
   * CompletionStage
   */
  def fromCompletableFuture[A](cs: => CompletableFuture[A])(implicit trace: Trace): Task[A] =
    fromCompletionStage(cs)

  def fromCompletionStage[A](cs: => CompletionStage[A])(implicit trace: Trace): Task[A] =
    javaz.fromCompletionStage(cs)

}
