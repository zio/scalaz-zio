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

import java.io._
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
      register: (ZIO[R, Option[E], Chunk[A]] => Unit) => ZManaged[R, E, Any],
      outputBuffer: => Int = 16
    )(implicit trace: Trace): ZStream[R, E, A] =
      ZStream.asyncScoped[R, E, A](register(_).scoped, outputBuffer)

    /**
     * Creates a stream from a managed Java stream
     */
    final def fromJavaStreamManaged[R, A](stream: => ZManaged[R, Throwable, java.util.stream.Stream[A]])(implicit
      trace: Trace
    ): ZStream[R, Throwable, A] =
      fromJavaStreamManaged(stream, ZStream.DefaultChunkSize)

    /**
     * Creates a stream from a managed Java stream
     */
    final def fromJavaStreamManaged[R, A](
      stream: => ZManaged[R, Throwable, java.util.stream.Stream[A]],
      chunkSize: Int
    )(implicit trace: Trace): ZStream[R, Throwable, A] =
      ZStream.fromJavaStreamScoped[R, A](stream.scoped, chunkSize)

    /**
     * Creates a stream from managed `java.io.Reader`.
     */
    def fromReaderManaged[R](
      reader: => ZManaged[R, IOException, Reader],
      chunkSize: => Int = ZStream.DefaultChunkSize
    )(implicit trace: Trace): ZStream[R, IOException, Char] =
      ZStream.fromReaderScoped[R](reader.scoped, chunkSize)
  }

  implicit final class ZManagedZSinkCompanionPlatformSpecificSyntax(private val self: ZStream.type) {

    /**
     * Uses the provided `OutputStream` resource to create a [[ZSink]] that
     * consumes byte chunks and writes them to the `OutputStream`. The sink will
     * yield the count of bytes written.
     *
     * The `OutputStream` will be automatically closed after the stream is
     * finished or an error occurred.
     */
    final def fromOutputStreamManaged(
      os: => ZManaged[Any, IOException, OutputStream]
    )(implicit trace: Trace): ZSink[Any, IOException, Byte, Byte, Long] =
      ZSink.fromOutputStreamScoped(os.scoped)
  }
}
