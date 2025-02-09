/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

package zio.managed

import zio._
import zio.stream._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.Future

private[managed] trait ZManagedCompatPlatformSpecific {

  implicit final class ZManagedZStreamCompanionPlatformSpecificSyntax(private val self: ZStream.type) {

    /**
     * Creates a stream from an asynchronous callback that can be called
     * multiple times. The registration of the callback itself returns an a
     * managed resource. The optionality of the error type `E` can be used to
     * signal the end of the stream, by setting it to `None`.
     */
    def asyncManaged[R, E, A](
      register: (ZIO[R, Option[E], Chunk[A]] => Future[Boolean]) => ZManaged[R, E, Any],
      outputBuffer: => Int = 16
    )(implicit trace: Trace): ZStream[R, E, A] =
      ZStream.asyncScoped[R, E, A](register(_).scoped, outputBuffer)
  }
}
