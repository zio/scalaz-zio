/*
 * Copyright 2018-2022 John A. De Goes and the ZIO Contributors
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

package zio.stream

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.Future

trait ZStreamPlatformSpecificConstructors {
  self: ZStream.type =>

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The optionality of the error type `E` can be used to signal the end
   * of the stream, by setting it to `None`.
   */
  def async[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => Unit,
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    asyncMaybe(
      callback => {
        register(callback)
        None
      },
      outputBuffer
    )

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback returns either a canceler or
   * synchronously returns a stream. The optionality of the error type `E` can
   * be used to signal the end of the stream, by setting it to `None`.
   */
  def asyncInterrupt[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => Either[Canceler[R], ZStream[R, E, A]],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.unwrapScoped[R](for {
      output  <- ZIO.acquireRelease(Queue.bounded[stream.Take[E, A]](outputBuffer))(_.shutdown)
      runtime <- ZIO.runtime[R]
      eitherStream <- ZIO.succeed {
                        register { k =>
                          try {
                            runtime.unsafeRunToFuture(stream.Take.fromPull(k).flatMap(output.offer))
                          } catch {
                            case FiberFailure(c) if c.isInterrupted =>
                              Future.successful(false)
                          }
                        }
                      }
    } yield {
      eitherStream match {
        case Right(value) => ZStream.unwrap(output.shutdown as value)
        case Left(canceler) =>
          lazy val loop: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
            ZChannel.unwrap(
              output.take
                .flatMap(_.done)
                .fold(
                  maybeError =>
                    ZChannel.fromZIO(output.shutdown) *>
                      maybeError
                        .fold[ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit]](ZChannel.unit)(ZChannel.fail(_)),
                  a => ZChannel.write(a) *> loop
                )
            )

          new ZStream(loop).ensuring(canceler)
      }
    })

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback itself returns an a scoped
   * resource. The optionality of the error type `E` can be used to signal the
   * end of the stream, by setting it to `None`.
   */
  def asyncScoped[R, E, A](
    register: (ZIO[R, Option[E], Chunk[A]] => Future[Boolean]) => ZIO[R with Scope, E, Any],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    scoped[R] {
      for {
        output  <- ZIO.acquireRelease(Queue.bounded[stream.Take[E, A]](outputBuffer))(_.shutdown)
        runtime <- ZIO.runtime[R]
        _ <- register { k =>
               try {
                 runtime.unsafeRunToFuture(stream.Take.fromPull(k).flatMap(output.offer))
               } catch {
                 case FiberFailure(c) if c.isInterrupted =>
                   Future.successful(false)
               }
             }
        done <- Ref.make(false)
        pull = done.get.flatMap {
                 if (_)
                   Pull.end
                 else
                   output.take.flatMap(_.done).onError(_ => done.set(true) *> output.shutdown)
               }
      } yield pull
    }.flatMap(repeatZIOChunkOption(_))

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times The registration of the callback itself returns an effect. The
   * optionality of the error type `E` can be used to signal the end of the
   * stream, by setting it to `None`.
   */
  def asyncZIO[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => ZIO[R, E, Any],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    new ZStream(ZChannel.unwrapScoped[R](for {
      output  <- ZIO.acquireRelease(Queue.bounded[stream.Take[E, A]](outputBuffer))(_.shutdown)
      runtime <- ZIO.runtime[R]
      _ <- register { k =>
             try {
               runtime.unsafeRunToFuture(stream.Take.fromPull(k).flatMap(output.offer))
             } catch {
               case FiberFailure(c) if c.isInterrupted =>
                 Future.successful(false)
             }
           }
    } yield {
      lazy val loop: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] = ZChannel.unwrap(
        output.take
          .flatMap(_.done)
          .fold(
            maybeError =>
              ZChannel.fromZIO(output.shutdown) *>
                maybeError.fold[ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit]](ZChannel.unit)(ZChannel.fail(_)),
            a => ZChannel.write(a) *> loop
          )
      )

      loop
    }))

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback can possibly return the stream
   * synchronously. The optionality of the error type `E` can be used to signal
   * the end of the stream, by setting it to `None`.
   */
  def asyncMaybe[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => Option[ZStream[R, E, A]],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    asyncInterrupt(k => register(k).toRight(UIO.unit), outputBuffer)

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The optionality of the error type `E` can be used to signal the end
   * of the stream, by setting it to `None`.
   */
  @deprecated("use async", "2.0.0")
  def effectAsync[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => Unit,
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    async(register, outputBuffer)

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback returns either a canceler or
   * synchronously returns a stream. The optionality of the error type `E` can
   * be used to signal the end of the stream, by setting it to `None`.
   */
  @deprecated("use asyncInterrupt", "2.0.0")
  def effectAsyncInterrupt[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => Either[Canceler[R], ZStream[R, E, A]],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    asyncInterrupt(register, outputBuffer)

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times The registration of the callback itself returns an effect. The
   * optionality of the error type `E` can be used to signal the end of the
   * stream, by setting it to `None`.
   */
  @deprecated("use asyncZIO", "2.0.0")
  def effectAsyncM[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => ZIO[R, E, Any],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    asyncZIO(register, outputBuffer)

  /**
   * Creates a stream from an asynchronous callback that can be called multiple
   * times. The registration of the callback can possibly return the stream
   * synchronously. The optionality of the error type `E` can be used to signal
   * the end of the stream, by setting it to `None`.
   */
  @deprecated("use asyncMaybe", "2.0.0")
  def effectAsyncMaybe[R, E, A](
    register: ZStream.Emit[R, E, A, Future[Boolean]] => Option[ZStream[R, E, A]],
    outputBuffer: => Int = 16
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    asyncMaybe(register, outputBuffer)

  trait ZStreamConstructorPlatformSpecific extends ZStreamConstructorLowPriority1
}

trait ZSinkPlatformSpecificConstructors

trait ZPipelinePlatformSpecificConstructors
