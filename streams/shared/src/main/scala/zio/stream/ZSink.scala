/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
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
import zio.metrics.MetricLabel
import zio.stream.internal.CharacterSet._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.nio.charset.{Charset, StandardCharsets}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.reflect.ClassTag

final class ZSink[-R, +E, -In, +L, +Z] private (val channel: ZChannel[R, ZNothing, Chunk[In], Any, E, Chunk[L], Z])
    extends AnyVal {
  self =>

  /**
   * Operator alias for [[race]].
   */
  def |[R1 <: R, E1 >: E, In1 <: In, L1 >: L, Z1 >: Z](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    race(that)

  /**
   * Operator alias for [[zip]].
   */
  def <*>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit
    zippable: Zippable[Z, Z1],
    ev: L <:< In1,
    trace: Trace
  ): ZSink[R1, E1, In1, L1, zippable.Out] =
    zip(that)

  /**
   * Operator alias for [[zipPar]].
   */
  def <&>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit zippable: Zippable[Z, Z1], trace: Trace): ZSink[R1, E1, In1, L1, zippable.Out] =
    zipPar(that)

  /**
   * Operator alias for [[zipRight]].
   */
  def *>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    zipRight(that)

  /**
   * Operator alias for [[zipParRight]].
   */
  def &>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    zipParRight(that)

  /**
   * Operator alias for [[zipLeft]].
   */
  def <*[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z] =
    zipLeft(that)

  /**
   * Operator alias for [[zipParLeft]].
   */
  def <&[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z] =
    zipParLeft(that)

  /**
   * Replaces this sink's result with the provided value.
   */
  def as[Z2](z: => Z2)(implicit trace: Trace): ZSink[R, E, In, L, Z2] =
    map(_ => z)

  /** Repeatedly runs the sink and accumulates its results into a chunk */
  def collectAll(implicit ev: L <:< In, trace: Trace): ZSink[R, E, In, L, Chunk[Z]] =
    collectAllWhileWith[Chunk[Z]](Chunk.empty)(_ => true)((s, z) => s :+ z)

  /**
   * Repeatedly runs the sink for as long as its results satisfy the predicate
   * `p`. The sink's results will be accumulated using the stepping function
   * `f`.
   */
  def collectAllWhileWith[S](z: => S)(p: Z => Boolean)(f: (S, Z) => S)(implicit
    ev: L <:< In,
    trace: Trace
  ): ZSink[R, E, In, L, S] =
    new ZSink(
      ZChannel
        .fromZIO(Ref.make(Chunk[In]()).zip(Ref.make(false)))
        .flatMap { case (leftoversRef, upstreamDoneRef) =>
          lazy val upstreamMarker: ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
            ZChannel.readWithCause(
              (in: Chunk[In]) => ZChannel.write(in) *> upstreamMarker,
              ZChannel.refailCause,
              (x: Any) => ZChannel.fromZIO(upstreamDoneRef.set(true)).as(x)
            )

          def loop(currentResult: S): ZChannel[R, ZNothing, Chunk[In], Any, E, Chunk[L], S] =
            channel.collectElements
              .foldChannel(
                ZChannel.fail(_),
                { case (leftovers, doneValue) =>
                  if (p(doneValue)) {
                    ZChannel.fromZIO(leftoversRef.set(leftovers.flatten.asInstanceOf[Chunk[In]])) *>
                      ZChannel.fromZIO(upstreamDoneRef.get).flatMap { upstreamDone =>
                        val accumulatedResult = f(currentResult, doneValue)
                        if (upstreamDone) ZChannel.write(leftovers.flatten).as(accumulatedResult)
                        else loop(accumulatedResult)
                      }
                  } else ZChannel.write(leftovers.flatten).as(currentResult)
                }
              )

          upstreamMarker >>> ZChannel.bufferChunk(leftoversRef) >>> loop(z)
        }
    )

  /**
   * Collects the leftovers from the stream when the sink succeeds and returns
   * them as part of the sink's result
   */
  def collectLeftover(implicit trace: Trace): ZSink[R, E, In, Nothing, (Z, Chunk[L])] =
    new ZSink(channel.collectElements.map { case (chunks, z) => (z, chunks.flatten) })

  /**
   * Transforms this sink's input elements.
   */
  def contramap[In1](f: In1 => In)(implicit trace: Trace): ZSink[R, E, In1, L, Z] =
    contramapChunks(_.map(f))

  /**
   * Transforms this sink's input chunks. `f` must preserve chunking-invariance
   */
  def contramapChunks[In1](
    f: Chunk[In1] => Chunk[In]
  )(implicit trace: Trace): ZSink[R, E, In1, L, Z] = {
    lazy val loop: ZChannel[R, ZNothing, Chunk[In1], Any, Nothing, Chunk[In], Any] =
      ZChannel.readWithCause(
        chunk => ZChannel.write(f(chunk)) *> loop,
        ZChannel.refailCause,
        ZChannel.succeed(_)
      )
    new ZSink(loop >>> self.channel)
  }

  /**
   * Effectfully transforms this sink's input chunks. `f` must preserve
   * chunking-invariance
   */
  def contramapChunksZIO[R1 <: R, E1 >: E, In1](
    f: Chunk[In1] => ZIO[R1, E1, Chunk[In]]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L, Z] = {
    lazy val loop: ZChannel[R1, ZNothing, Chunk[In1], Any, E1, Chunk[In], Any] =
      ZChannel.readWithCause(
        chunk => ZChannel.fromZIO(f(chunk)).flatMap(ZChannel.write) *> loop,
        ZChannel.refailCause,
        ZChannel.succeed(_)
      )
    new ZSink(loop.pipeToOrFail(self.channel))
  }

  /**
   * Effectfully transforms this sink's input elements.
   */
  def contramapZIO[R1 <: R, E1 >: E, In1](
    f: In1 => ZIO[R1, E1, In]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L, Z] =
    contramapChunksZIO(_.mapZIO(f))

  /**
   * Transforms both inputs and result of this sink using the provided
   * functions.
   */
  def dimap[In1, Z1](f: In1 => In, g: Z => Z1)(implicit trace: Trace): ZSink[R, E, In1, L, Z1] =
    contramap(f).map(g)

  /**
   * Transforms both input chunks and result of this sink using the provided
   * functions.
   */
  def dimapChunks[In1, Z1](f: Chunk[In1] => Chunk[In], g: Z => Z1)(implicit
    trace: Trace
  ): ZSink[R, E, In1, L, Z1] =
    contramapChunks(f).map(g)

  /**
   * Effectfully transforms both input chunks and result of this sink using the
   * provided functions. `f` and `g` must preserve chunking-invariance
   */
  def dimapChunksZIO[R1 <: R, E1 >: E, In1, Z1](
    f: Chunk[In1] => ZIO[R1, E1, Chunk[In]],
    g: Z => ZIO[R1, E1, Z1]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L, Z1] =
    contramapChunksZIO(f).mapZIO(g)

  /**
   * Effectfully transforms both inputs and result of this sink using the
   * provided functions.
   */
  def dimapZIO[R1 <: R, E1 >: E, In1, Z1](
    f: In1 => ZIO[R1, E1, In],
    g: Z => ZIO[R1, E1, Z1]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L, Z1] =
    contramapZIO(f).mapZIO(g)

  /**
   * Returns a new sink with an attached finalizer. The finalizer is guaranteed
   * to be executed so long as the sink begins execution (and regardless of
   * whether or not it completes).
   */
  final def ensuringWith[R1 <: R](
    finalizer: Exit[E, Z] => URIO[R1, Any]
  )(implicit trace: Trace): ZSink[R1, E, In, L, Z] =
    new ZSink[R1, E, In, L, Z](
      channel.ensuringWith(finalizer)
    )

  /**
   * Returns a new sink with an attached finalizer. The finalizer is guaranteed
   * to be executed so long as the sink begins execution (and regardless of
   * whether or not it completes).
   */
  final def ensuring[R1 <: R](
    finalizer: => URIO[R1, Any]
  )(implicit trace: Trace): ZSink[R1, E, In, L, Z] =
    new ZSink[R1, E, In, L, Z](
      channel.ensuring(finalizer)
    )

  /** Filters the sink's input with the given predicate */
  def filterInput[In1 <: In](p: In1 => Boolean)(implicit trace: Trace): ZSink[R, E, In1, L, Z] =
    contramapChunks(_.filter(p))

  /** Filters the sink's input with the given ZIO predicate */
  def filterInputZIO[R1 <: R, E1 >: E, In1 <: In](
    p: In1 => ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L, Z] =
    contramapChunksZIO(_.filterZIO(p))

  /**
   * Creates a sink that produces values until one verifies the predicate `f`.
   */
  def findZIO[R1 <: R, E1 >: E](
    f: Z => ZIO[R1, E1, Boolean]
  )(implicit ev: L <:< In, trace: Trace): ZSink[R1, E1, In, L, Option[Z]] =
    new ZSink(
      ZChannel
        .fromZIO(Ref.make(Chunk[In]()).zip(Ref.make(false)))
        .flatMap { case (leftoversRef, upstreamDoneRef) =>
          lazy val upstreamMarker: ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
            ZChannel.readWithCause(
              (in: Chunk[In]) => ZChannel.write(in) *> upstreamMarker,
              ZChannel.refailCause,
              (x: Any) => ZChannel.fromZIO(upstreamDoneRef.set(true)).as(x)
            )

          lazy val loop: ZChannel[R1, ZNothing, Chunk[In], Any, E1, Chunk[L], Option[Z]] =
            channel.collectElements
              .foldChannel(
                ZChannel.fail(_),
                { case (leftovers, doneValue) =>
                  ZChannel.fromZIO(f(doneValue)).flatMap { satisfied =>
                    ZChannel.fromZIO(leftoversRef.set(leftovers.flatten.asInstanceOf[Chunk[In]])) *>
                      ZChannel
                        .fromZIO(upstreamDoneRef.get)
                        .flatMap { upstreamDone =>
                          if (satisfied) ZChannel.write(leftovers.flatten).as(Some(doneValue))
                          else if (upstreamDone)
                            ZChannel.write(leftovers.flatten).as(None)
                          else loop
                        }
                  }
                }
              )

          upstreamMarker >>> ZChannel.bufferChunk(leftoversRef) >>> loop
        }
    )

  /**
   * Runs this sink until it yields a result, then uses that result to create
   * another sink from the provided function which will continue to run until it
   * yields a result.
   *
   * This function essentially runs sinks in sequence.
   */
  def flatMap[R1 <: R, E1 >: E, In1 <: In, L1 >: L <: In1, Z1](
    f: Z => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    foldSink(ZSink.fail(_), f)

  /** Folds over the result of the sink */
  def foldSink[R1 <: R, E2, In1 <: In, L1 >: L <: In1, Z1](
    failure: E => ZSink[R1, E2, In1, L1, Z1],
    success: Z => ZSink[R1, E2, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E2, In1, L1, Z1] =
    this.foldCauseSink(
      _.failureOrCause match {
        case Left(err) => failure(err)
        case Right(c)  => ZSink.failCause(c)
      },
      success
    )

  def foldCauseSink[R1 <: R, E2, In1 <: In, L1 >: L <: In1, Z1](
    failure: Cause[E] => ZSink[R1, E2, In1, L1, Z1],
    success: Z => ZSink[R1, E2, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E2, In1, L1, Z1] =
    new ZSink(
      channel.collectElements.foldCauseChannel(
        failure(_).channel,
        { case (leftovers, z) =>
          ZChannel.suspend {
            val leftoversRef = new AtomicReference(leftovers.filter(_.nonEmpty))
            val refReader = ZChannel.succeed(leftoversRef.getAndSet(Chunk.empty)).flatMap { chunk =>
              // This cast is safe because of the L1 >: L <: In1 bound. It follows that
              // L <: In1 and therefore Chunk[L] can be safely cast to Chunk[In1].
              val widenedChunk = chunk.asInstanceOf[Chunk[Chunk[In1]]]
              ZChannel.writeChunk(widenedChunk)
            }

            val passthrough      = ZChannel.identity[ZNothing, Chunk[In1], Any]
            val continuationSink = (refReader *> passthrough) >>> success(z).channel

            continuationSink.collectElements.flatMap { case (newLeftovers, z1) =>
              ZChannel.succeed(leftoversRef.get).flatMap(ZChannel.writeChunk(_)) *>
                ZChannel.writeChunk(newLeftovers).as(z1)
            }
          }
        }
      )
    )

  /** Drains the remaining elements from the stream after the sink finishes */
  def ignoreLeftover(implicit trace: Trace): ZSink[R, E, In, Nothing, Z] =
    new ZSink(channel.drain)

  /**
   * Transforms this sink's result.
   */
  def map[Z2](f: Z => Z2)(implicit trace: Trace): ZSink[R, E, In, L, Z2] = new ZSink(channel.map(f))

  /**
   * Transforms the errors emitted by this sink using `f`.
   */
  def mapError[E2](f: E => E2)(implicit trace: Trace): ZSink[R, E2, In, L, Z] =
    new ZSink(channel.mapError(f))

  /**
   * Transforms the full causes of failures emitted by this sink.
   */
  def mapErrorCause[E2](f: Cause[E] => Cause[E2])(implicit trace: Trace): ZSink[R, E2, In, L, Z] =
    new ZSink(channel.mapErrorCause(f))

  /**
   * Transforms the errors emitted by this sink using `f`.
   */
  def mapErrorZIO[R1 <: R, E2](f: E => URIO[R1, E2])(implicit trace: Trace): ZSink[R1, E2, In, L, Z] =
    new ZSink(self.channel.mapErrorZIO(f))

  /**
   * Transforms the leftovers emitted by this sink using `f`.
   */
  def mapLeftover[L2](f: L => L2)(implicit trace: Trace): ZSink[R, E, In, L2, Z] =
    new ZSink(channel.mapOut(_.map(f)))

  /**
   * Effectfully transforms this sink's result.
   */
  def mapZIO[R1 <: R, E1 >: E, Z1](f: Z => ZIO[R1, E1, Z1])(implicit
    trace: Trace
  ): ZSink[R1, E1, In, L, Z1] =
    new ZSink(channel.mapZIO(f))

  /** Switch to another sink in case of failure */
  def orElse[R1 <: R, In1 <: In, E2, L1 >: L, Z1 >: Z](
    that: => ZSink[R1, E2, In1, L1, Z1]
  )(implicit trace: Trace): ZSink[R1, E2, In1, L1, Z1] =
    new ZSink[R1, E2, In1, L1, Z1](self.channel.orElse(that.channel))

  /**
   * Provides the sink with its required environment, which eliminates its
   * dependency on `R`.
   */
  def provideEnvironment(
    r: => ZEnvironment[R]
  )(implicit trace: Trace): ZSink[Any, E, In, L, Z] =
    new ZSink(channel.provideEnvironment(r))

  /**
   * Transforms the environment being provided to the sink with the specified
   * function.
   */
  def provideSomeEnvironment[R0](
    f: ZEnvironment[R0] => ZEnvironment[R]
  )(implicit trace: Trace): ZSink[R0, E, In, L, Z] =
    new ZSink(channel.provideSomeEnvironment(f))

  /**
   * Provides a layer to the sink, which translates it to another level.
   */
  def provideLayer[E1 >: E, R0](
    layer: => ZLayer[R0, E1, R]
  )(implicit trace: Trace): ZSink[R0, E1, In, L, Z] =
    new ZSink(channel.provideLayer(layer))

  /**
   * Splits the environment into two parts, providing one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val sink: ZSink[Logging with Database, Nothing, Unit] = ???
   *
   * val sink2 = sink.provideSomeLayer[Database](loggingLayer)
   * }}}
   */
  def provideSomeLayer[R0]: ZSink.ProvideSomeLayer[R0, R, E, In, L, Z] =
    new ZSink.ProvideSomeLayer[R0, R, E, In, L, Z](self.channel)

  /**
   * Runs both sinks in parallel on the input, , returning the result or the
   * error from the one that finishes first.
   */
  def race[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L, Z1 >: Z](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    self.raceBoth(that).map(_.merge)

  /**
   * Runs both sinks in parallel on the input, returning the result or the error
   * from the one that finishes first.
   */
  def raceBoth[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L, Z2](
    that: => ZSink[R1, E1, In1, L1, Z2],
    capacity: => Int = 16
  )(implicit trace: Trace): ZSink[R1, E1, In1, L1, Either[Z, Z2]] =
    self.raceWith(that, capacity)(
      selfDone => ZChannel.MergeDecision.done(selfDone.map(Left(_))),
      thatDone => ZChannel.MergeDecision.done(thatDone.map(Right(_)))
    )

  /**
   * Runs both sinks in parallel on the input, using the specified merge
   * function as soon as one result or the other has been computed.
   */
  def raceWith[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L, Z1, Z2](
    that: => ZSink[R1, E1, In1, L1, Z1],
    capacity: => Int = 16
  )(
    leftDone: Exit[E, Z] => ZChannel.MergeDecision[R1, E1, Z1, E1, Z2],
    rightDone: Exit[E1, Z1] => ZChannel.MergeDecision[R1, E, Z, E1, Z2]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L1, Z2] = {
    def scoped(scope: Scope) =
      for {
        hub   <- Hub.bounded[Either[Exit[Nothing, Any], Chunk[In1]]](capacity)
        s1    <- scope.extend(hub.subscribe)
        s2    <- scope.extend(hub.subscribe)
        reader = ZChannel.toHub[Nothing, Any, Chunk[In1]](hub)
        writer = (ZChannel.fromQueue(s1) >>> self.channel <* ZChannel.fromZIO(s1.shutdown))
                   .mergeWith((ZChannel.fromQueue(s2) >>> that.channel <* ZChannel.fromZIO(s2.shutdown)))(
                     leftDone,
                     rightDone
                   )
        channel = reader.mergeWith(writer)(
                    _ => ZChannel.MergeDecision.await(ZIO.identityFn),
                    done => ZChannel.MergeDecision.done(done)
                  )
      } yield new ZSink[R1, E1, In1, L1, Z2](channel)
    ZSink.unwrapScopedWith(scoped)
  }

  def refineOrDie[E1](
    pf: PartialFunction[E, E1]
  )(implicit ev1: E IsSubtypeOfError Throwable, ev2: CanFail[E], trace: Trace): ZSink[R, E1, In, L, Z] =
    refineOrDieWith(pf)(ev1(_))

  def refineOrDieWith[E1](
    pf: PartialFunction[E, E1]
  )(f: E => Throwable)(implicit ev: CanFail[E], trace: Trace): ZSink[R, E1, In, L, Z] =
    mapErrorCause(_.flatMap(pf.andThen(Cause.fail(_)).applyOrElse(_, (e: E) => Cause.die(f(e)))))

  /**
   * Returns the sink that executes this one and times its execution.
   */
  def timed(implicit trace: Trace): ZSink[R, E, In, L, (Z, Duration)] =
    summarized(Clock.nanoTime)((start, end) => Duration.fromNanos(end - start))

  /**
   * Splits the sink on the specified predicate, returning a new sink that
   * consumes elements until an element after the first satisfies the specified
   * predicate.
   */
  def splitWhere[In1 <: In](
    f: In1 => Boolean
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R, E, In1, In1, Z] = {

    def splitter(
      written: Boolean,
      leftovers: Ref[Chunk[In1]]
    ): ZChannel[R, ZNothing, Chunk[In1], Any, E, Chunk[In1], Any] =
      ZChannel.readWithCause(
        in =>
          if (in.isEmpty) splitter(written, leftovers)
          else if (written) {
            val index = in.indexWhere(f)
            if (index == -1)
              ZChannel.write(in) *> splitter(true, leftovers)
            else {
              val (left, right) = in.splitAt(index)
              ZChannel.write(left) *> ZChannel.fromZIO(leftovers.set(right))
            }
          } else {
            val index = in.indexWhere(f, 1)
            if (index == -1)
              ZChannel.write(in) *> splitter(true, leftovers)
            else {
              val (left, right) = in.splitAt(index max 1)
              ZChannel.write(left) *> ZChannel.fromZIO(leftovers.set(right))
            }
          },
        err => ZChannel.refailCause(err),
        done => ZChannel.succeed(done)
      )

    new ZSink(
      ZChannel.fromZIO(Ref.make[Chunk[In1]](Chunk.empty)).flatMap { ref =>
        splitter(false, ref)
          .pipeToOrFail(self.channel)
          .collectElements
          .flatMap { case (leftovers, z) =>
            ZChannel.fromZIO(ref.get).flatMap { leftover =>
              ZChannel.write(leftover ++ leftovers.flatten.map(ev)) *> ZChannel.succeed(z)
            }
          }
      }
    )
  }

  /**
   * Summarize a sink by running an effect when the sink starts and again when
   * it completes
   */
  def summarized[R1 <: R, E1 >: E, B, C](
    summary: => ZIO[R1, E1, B]
  )(f: (B, B) => C)(implicit trace: Trace): ZSink[R1, E1, In, L, (Z, C)] =
    new ZSink[R1, E1, In, L, (Z, C)](
      ZChannel.unwrap {
        ZIO.succeed(summary).map { summary =>
          ZChannel.fromZIO(summary).flatMap { start =>
            self.channel.flatMap { done =>
              ZChannel.fromZIO(summary).map { end =>
                (done, f(start, end))
              }
            }
          }
        }
      }
    )

  /** Converts ths sink to its underlying channel */
  def toChannel: ZChannel[R, ZNothing, Chunk[In], Any, E, Chunk[L], Z] =
    self.channel

  /**
   * Feeds inputs to this sink until it yields a result, then switches over to
   * the provided sink until it yields a result, finally combining the two
   * results into a tuple.
   */
  def zip[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit
    zippable: Zippable[Z, Z1],
    ev: L <:< In1,
    trace: Trace
  ): ZSink[R1, E1, In1, L1, zippable.Out] =
    zipWith[R1, E1, In1, L1, Z1, zippable.Out](that)(zippable.zip(_, _))

  /**
   * Like [[zip]], but keeps only the result from the `that` sink.
   */
  def zipLeft[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z] =
    zipWith[R1, E1, In1, L1, Z1, Z](that)((z, _) => z)

  /**
   * Runs both sinks in parallel on the input and combines the results in a
   * tuple.
   */
  def zipPar[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit zippable: Zippable[Z, Z1], trace: Trace): ZSink[R1, E1, In1, L1, zippable.Out] =
    zipWithPar[R1, E1, In1, L1, Z1, zippable.Out](that)(zippable.zip(_, _))

  /**
   * Like [[zipPar]], but keeps only the result from this sink.
   */
  def zipParLeft[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L1, Z] =
    zipWithPar[R1, E1, In1, L1, Z1, Z](that)((b, _) => b)

  /**
   * Like [[zipPar]], but keeps only the result from the `that` sink.
   */
  def zipParRight[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    zipWithPar[R1, E1, In1, L1, Z1, Z1](that)((_, c) => c)

  /**
   * Like [[zip]], but keeps only the result from this sink.
   */
  def zipRight[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z1] =
    zipWith[R1, E1, In1, L1, Z1, Z1](that)((_, z1) => z1)

  /**
   * Feeds inputs to this sink until it yields a result, then switches over to
   * the provided sink until it yields a result, finally combining the two
   * results with `f`.
   */
  def zipWith[R1 <: R, E1 >: E, In1 <: In, L1 >: L <: In1, Z1, Z2](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(f: (Z, Z1) => Z2)(implicit ev: L <:< In1, trace: Trace): ZSink[R1, E1, In1, L1, Z2] =
    flatMap(z => that.map(f(z, _)))

  /**
   * Runs both sinks in parallel on the input and combines the results using the
   * provided function.
   */
  def zipWithPar[R1 <: R, E1 >: E, In1 <: In, L1 >: L <: In1, Z1, Z2](
    that: => ZSink[R1, E1, In1, L1, Z1]
  )(f: (Z, Z1) => Z2)(implicit trace: Trace): ZSink[R1, E1, In1, L1, Z2] =
    self.raceWith(that)(
      {
        case Exit.Failure(err) => ZChannel.MergeDecision.done(Exit.failCause(err))
        case Exit.Success(lz) =>
          ZChannel.MergeDecision.await {
            case Exit.Failure(cause) => Exit.failCause(cause)
            case Exit.Success(rz)    => ZIO.succeed(f(lz, rz))
          }
      },
      {
        case Exit.Failure(err) => ZChannel.MergeDecision.done(Exit.failCause(err))
        case Exit.Success(rz) =>
          ZChannel.MergeDecision.await {
            case Exit.Failure(cause) => Exit.failCause(cause)
            case Exit.Success(lz)    => ZIO.succeed(f(lz, rz))
          }
      }
    )
}

object ZSink extends ZSinkPlatformSpecificConstructors {

  def collectAll[In](implicit trace: Trace): ZSink[Any, Nothing, In, Nothing, Chunk[In]] = {
    def loop(acc: Chunk[In]): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Nothing, Chunk[In]] =
      ZChannel.readWithCause(
        chunk => loop(acc ++ chunk),
        ZChannel.refailCause,
        _ => ZChannel.succeed(acc)
      )

    new ZSink(loop(Chunk.empty))
  }

  /**
   * A sink that collects first `n` elements into a chunk.
   */
  def collectAllN[In](n: => Int)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Chunk[In]] = {

    def collectAllChunksN(
      n: Int,
      acc: Chunk[In]
    ): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Chunk[In]] =
      ZChannel.readWithCause(
        chunk => {
          val (collected, leftovers) = chunk.splitAt(n)
          if (collected.length < n) collectAllChunksN(n - collected.length, acc ++ collected)
          else if (leftovers.isEmpty) ZChannel.succeed(acc ++ collected)
          else ZChannel.write(leftovers) *> ZChannel.succeed(acc ++ collected)
        },
        err => ZChannel.refailCause(err),
        _ => ZChannel.succeed(acc)
      )

    ZSink.suspend(ZSink.fromChannel(collectAllChunksN(n, Chunk.empty)))
  }

  /**
   * A sink that collects all of its inputs into a map. The keys are extracted
   * from inputs using the keying function `key`; if multiple inputs use the
   * same key, they are merged using the `f` function.
   */
  def collectAllToMap[In, K](
    key: In => K
  )(f: (In, In) => In)(implicit trace: Trace): ZSink[Any, Nothing, In, Nothing, Map[K, In]] =
    foldLeftChunks(Map[K, In]()) { (acc, as) =>
      as.foldLeft(acc) { (acc, a) =>
        val k = key(a)

        acc.updated(
          k,
          // Avoiding `get/getOrElse` here to avoid an Option allocation
          if (acc.contains(k)) f(acc(k), a)
          else a
        )
      }
    }

  /**
   * A sink that collects first `n` keys into a map. The keys are calculated
   * from inputs using the keying function `key`; if multiple inputs use the the
   * same key, they are merged using the `f` function.
   */
  def collectAllToMapN[Err, In, K](
    n: => Long
  )(key: In => K)(f: (In, In) => In)(implicit trace: Trace): ZSink[Any, Err, In, In, Map[K, In]] =
    foldWeighted[In, Map[K, In]](Map())((acc, in) => if (acc.contains(key(in))) 0 else 1, n) { (acc, in) =>
      val k = key(in)
      val v = if (acc.contains(k)) f(acc(k), in) else in

      acc.updated(k, v)
    }

  /**
   * A sink that collects all of its inputs into a map. The keys are extracted
   * from inputs using the keying function `key`; The values are extracted using
   * the value function `value` if multiple inputs use the same key, they are
   * merged using the `f` function.
   */
  def collectAllToMapValue[In, K, V](
    key: In => K
  )(value: In => V)(f: (V, V) => V)(implicit trace: Trace): ZSink[Any, Nothing, In, Nothing, Map[K, V]] =
    foldLeftChunks(Map[K, V]()) { (acc, as) =>
      as.foldLeft(acc) { (acc, a) =>
        val k = key(a)
        acc.updated(
          k,
          // Avoiding `get/getOrElse` here to avoid an Option allocation
          if (acc.contains(k)) f(acc(k), value(a))
          else value(a)
        )
      }
    }

  /**
   * A sink that collects first `n` keys into a map. The keys are calculated
   * from inputs using the keying function `key`; The values are extracted using
   * * the value function `value` if multiple inputs use the the same key, they
   * are merged using the `f` function.
   */
  def collectAllToMapValueN[Err, In, K, V](
    n: => Long
  )(key: In => K)(value: In => V)(f: (V, V) => V)(implicit trace: Trace): ZSink[Any, Err, In, In, Map[K, V]] =
    foldWeighted[In, Map[K, V]](Map())((acc, in) => if (acc.contains(key(in))) 0 else 1, n) { (acc, in) =>
      val k = key(in)
      val v = if (acc.contains(k)) f(acc(k), value(in)) else value(in)

      acc.updated(k, v)
    }

  /**
   * A sink that collects all of its inputs into a set.
   */
  def collectAllToSet[In](implicit trace: Trace): ZSink[Any, Nothing, In, Nothing, Set[In]] =
    foldLeftChunks(Set[In]())((acc, as) => as.foldLeft(acc)(_ + _))

  /**
   * A sink that collects first `n` distinct inputs into a set.
   */
  def collectAllToSetN[In](n: => Long)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Set[In]] =
    foldWeighted[In, Set[In]](Set())((acc, in) => if (acc.contains(in)) 0 else 1, n)(_ + _)

  /**
   * Accumulates incoming elements into a chunk until predicate `p` is
   * satisfied.
   */
  def collectAllUntil[In](p: In => Boolean)(implicit
    trace: Trace
  ): ZSink[Any, Nothing, In, In, Chunk[In]] =
    fold[In, (List[In], Boolean)]((Nil, true))(_._2) { case ((as, _), a) =>
      (a :: as, !p(a))
    }.map { case (is, _) =>
      Chunk.fromIterable(is.reverse)
    }

  /**
   * Accumulates incoming elements into a chunk until effectful predicate `p` is
   * satisfied.
   */
  def collectAllUntilZIO[Env, Err, In](p: In => ZIO[Env, Err, Boolean])(implicit
    trace: Trace
  ): ZSink[Env, Err, In, In, Chunk[In]] =
    foldZIO[Env, Err, In, (List[In], Boolean)]((Nil, true))(_._2) { case ((as, _), a) =>
      p(a).map(bool => (a :: as, !bool))
    }.map { case (is, _) =>
      Chunk.fromIterable(is.reverse)
    }

  /**
   * Accumulates incoming elements into a chunk as long as they verify predicate
   * `p`.
   */
  def collectAllWhile[In](p: In => Boolean)(implicit
    trace: Trace
  ): ZSink[Any, Nothing, In, In, Chunk[In]] = {

    def channel(done: Chunk[In]): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Chunk[In]] =
      ZChannel.readWithCause(
        in => {
          val (collected, leftovers) = in.span(p)
          if (leftovers.isEmpty) channel(done ++ collected)
          else ZChannel.write(leftovers) *> ZChannel.succeed(done ++ collected)
        },
        ZChannel.refailCause,
        _ => ZChannel.succeed(done)
      )

    ZSink.fromChannel(channel(Chunk.empty))
  }

  /**
   * Accumulates incoming elements into a chunk as long as they verify effectful
   * predicate `p`.
   */
  def collectAllWhileZIO[Env, Err, In](p: In => ZIO[Env, Err, Boolean])(implicit
    trace: Trace
  ): ZSink[Env, Err, In, In, Chunk[In]] = {

    def channel(done: Chunk[In]): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[In], Chunk[In]] =
      ZChannel.readWithCause(
        in => {
          ZChannel.fromZIO(in.takeWhileZIO(p)).flatMap { collected =>
            val leftovers = in.drop(collected.length)
            if (leftovers.isEmpty) channel(done ++ collected)
            else ZChannel.write(leftovers) *> ZChannel.succeed(done ++ collected)
          }
        },
        ZChannel.refailCause,
        _ => ZChannel.succeed(done)
      )

    ZSink.fromChannel(channel(Chunk.empty))
  }

  /**
   * A sink that counts the number of elements fed to it.
   */
  def count(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Long] =
    foldLeftChunks(0L)((s, chunk) => s + chunk.length)

  /**
   * Creates a sink halting with the specified `Throwable`.
   */
  def die(e: => Throwable)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Nothing] =
    ZSink.failCause(Cause.die(e))

  /**
   * Creates a sink halting with the specified message, wrapped in a
   * `RuntimeException`.
   */
  def dieMessage(m: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Nothing] =
    ZSink.failCause(Cause.die(new RuntimeException(m)))

  /**
   * A sink that ignores its inputs.
   */
  def drain(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    new ZSink(ZPipeline.drain.toChannel.unit)

  /**
   * Drops incoming elements until the predicate `p` is satisfied.
   */
  def dropUntil[In](p: In => Boolean)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Any] =
    new ZSink(ZPipeline.dropUntil(p).toChannel)

  /**
   * Drops incoming elements until the effectful predicate `p` is satisfied.
   */
  def dropUntilZIO[R, InErr, In](
    p: In => ZIO[R, InErr, Boolean]
  )(implicit trace: Trace): ZSink[R, InErr, In, In, Any] =
    new ZSink(ZPipeline.dropUntilZIO(p).toChannel)

  /**
   * Drops incoming elements as long as the predicate `p` is satisfied.
   */
  def dropWhile[In](p: In => Boolean)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Any] =
    new ZSink(ZPipeline.dropWhile(p).toChannel)

  /**
   * Drops incoming elements as long as the effectful predicate `p` is
   * satisfied.
   */
  def dropWhileZIO[R, InErr, In](
    p: In => ZIO[R, InErr, Boolean]
  )(implicit trace: Trace): ZSink[R, InErr, In, In, Any] =
    new ZSink(ZPipeline.dropWhileZIO(p).toChannel)

  /**
   * Accesses the whole environment of the sink.
   */
  def environment[R](implicit trace: Trace): ZSink[R, Nothing, Any, Nothing, ZEnvironment[R]] =
    fromZIO(ZIO.environment[R])

  /**
   * Accesses the environment of the sink.
   */
  def environmentWith[R]: EnvironmentWithPartiallyApplied[R] =
    new EnvironmentWithPartiallyApplied[R]

  /**
   * Accesses the environment of the sink in the context of an effect.
   */
  def environmentWithZIO[R]: EnvironmentWithZIOPartiallyApplied[R] =
    new EnvironmentWithZIOPartiallyApplied[R]

  /**
   * Accesses the environment of the sink in the context of a sink.
   */
  def environmentWithSink[R]: EnvironmentWithSinkPartiallyApplied[R] =
    new EnvironmentWithSinkPartiallyApplied[R]

  /**
   * A sink that returns whether an element satisfies the specified predicate.
   */
  def exists[In](f: In => Boolean)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Boolean] =
    fold(false)(!_)(_ || f(_))

  /**
   * A sink that always fails with the specified error.
   */
  def fail[E](e: => E)(implicit trace: Trace): ZSink[Any, E, Any, Nothing, Nothing] = new ZSink(
    ZChannel.fail(e)
  )

  /**
   * Creates a sink halting with a specified cause.
   */
  def failCause[E](e: => Cause[E])(implicit trace: Trace): ZSink[Any, E, Any, Nothing, Nothing] =
    new ZSink(ZChannel.failCause(e))

  /**
   * A sink that folds its inputs with the provided function, termination
   * predicate and initial state.
   */
  def fold[In, S](
    z: => S
  )(contFn: S => Boolean)(f: (S, In) => S)(implicit trace: Trace): ZSink[Any, Nothing, In, In, S] =
    ZSink.suspend {
      def foldChunkSplit(z: S, chunk: Chunk[In]): (S, Chunk[In]) = {
        @tailrec
        def fold(s: S, chunk: Chunk[In], idx: Int, len: Int): (S, Chunk[In]) =
          if (idx == len) (s, Chunk.empty)
          else {
            val s1 = f(s, chunk(idx))

            if (contFn(s1)) fold(s1, chunk, idx + 1, len)
            else (s1, chunk.drop(idx + 1))
          }

        fold(z, chunk, 0, chunk.length)
      }

      def reader(s: S): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], S] =
        if (!contFn(s)) ZChannel.succeedNow(s)
        else
          ZChannel.readWithCause(
            (in: Chunk[In]) => {
              val (nextS, leftovers) = foldChunkSplit(s, in)

              if (leftovers.nonEmpty) ZChannel.write(leftovers).as(nextS)
              else reader(nextS)
            },
            (err: Cause[ZNothing]) => ZChannel.refailCause(err),
            (_: Any) => ZChannel.succeedNow(s)
          )

      new ZSink(reader(z))
    }

  /**
   * A sink that folds its input chunks with the provided function, termination
   * predicate and initial state. `contFn` condition is checked only for the
   * initial value and at the end of processing of each chunk. `f` and `contFn`
   * must preserve chunking-invariance.
   */
  def foldChunks[In, S](
    z: => S
  )(
    contFn: S => Boolean
  )(f: (S, Chunk[In]) => S)(implicit trace: Trace): ZSink[Any, Nothing, In, Nothing, S] =
    ZSink.suspend {
      def reader(s: S): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Nothing, S] =
        if (!contFn(s)) ZChannel.succeedNow(s)
        else
          ZChannel.readWithCause(
            (in: Chunk[In]) => {
              val nextS = f(s, in)

              reader(nextS)
            },
            (err: Cause[ZNothing]) => ZChannel.refailCause(err),
            (_: Any) => ZChannel.succeedNow(s)
          )

      new ZSink(reader(z))
    }

  /**
   * A sink that effectfully folds its input chunks with the provided function,
   * termination predicate and initial state. `contFn` condition is checked only
   * for the initial value and at the end of processing of each chunk. `f` and
   * `contFn` must preserve chunking-invariance.
   */
  def foldChunksZIO[Env, Err, In, S](
    z: => S
  )(
    contFn: S => Boolean
  )(f: (S, Chunk[In]) => ZIO[Env, Err, S])(implicit trace: Trace): ZSink[Env, Err, In, In, S] =
    ZSink.suspend {
      def reader(s: S): ZChannel[Env, Err, Chunk[In], Any, Err, Nothing, S] =
        if (!contFn(s)) ZChannel.succeedNow(s)
        else
          ZChannel.readWithCause(
            (in: Chunk[In]) => ZChannel.fromZIO(f(s, in)).flatMap(reader),
            (err: Cause[Err]) => ZChannel.refailCause(err),
            (_: Any) => ZChannel.succeedNow(s)
          )

      new ZSink(reader(z))
    }

  /**
   * A sink that folds its inputs with the provided function and initial state.
   */
  def foldLeft[In, S](z: => S)(f: (S, In) => S)(implicit trace: Trace): ZSink[Any, Nothing, In, Nothing, S] =
    fold(z)(_ => true)(f).ignoreLeftover

  /**
   * A sink that folds its input chunks with the provided function and initial
   * state. `f` must preserve chunking-invariance.
   */
  def foldLeftChunks[In, S](z: => S)(f: (S, Chunk[In]) => S)(implicit
    trace: Trace
  ): ZSink[Any, Nothing, In, Nothing, S] =
    foldChunks[In, S](z)(_ => true)(f)

  /**
   * A sink that effectfully folds its input chunks with the provided function
   * and initial state. `f` must preserve chunking-invariance.
   */
  def foldLeftChunksZIO[R, Err, In, S](z: => S)(
    f: (S, Chunk[In]) => ZIO[R, Err, S]
  )(implicit trace: Trace): ZSink[R, Err, In, Nothing, S] =
    foldChunksZIO[R, Err, In, S](z)(_ => true)(f).ignoreLeftover

  /**
   * A sink that effectfully folds its inputs with the provided function and
   * initial state.
   */
  def foldLeftZIO[R, Err, In, S](z: => S)(
    f: (S, In) => ZIO[R, Err, S]
  )(implicit trace: Trace): ZSink[R, Err, In, Nothing, S] =
    foldZIO[R, Err, In, S](z)(_ => true)(f).ignoreLeftover

  /**
   * Creates a sink that folds elements of type `In` into a structure of type
   * `S` until `max` elements have been folded.
   *
   * Like [[foldWeighted]], but with a constant cost function of 1.
   */
  def foldUntil[In, S](z: => S, max: => Long)(f: (S, In) => S)(implicit
    trace: Trace
  ): ZSink[Any, Nothing, In, In, S] =
    ZSink.unwrap {
      ZIO.succeed(max).map { max =>
        fold[In, (S, Long)]((z, 0))(_._2 < max) { case ((o, count), i) =>
          (f(o, i), count + 1)
        }.map(_._1)
      }
    }

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S` until `max` elements have been folded.
   *
   * Like [[foldWeightedZIO]], but with a constant cost function of 1.
   */
  def foldUntilZIO[Env, Err, In, S](z: => S, max: => Long)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: Trace): ZSink[Env, Err, In, In, S] =
    foldZIO[Env, Err, In, (S, Long)]((z, 0))(_._2 < max) { case ((o, count), i) =>
      f(o, i).map((_, count + 1))
    }.map(_._1)

  /**
   * Creates a sink that folds elements of type `In` into a structure of type
   * `S`, until `max` worth of elements (determined by the `costFn`) have been
   * folded.
   *
   * @note
   *   Elements that have an individual cost larger than `max` will force the
   *   sink to cross the `max` cost. See [[foldWeightedDecompose]] for a variant
   *   that can handle these cases.
   */
  def foldWeighted[In, S](z: => S)(costFn: (S, In) => Long, max: => Long)(
    f: (S, In) => S
  )(implicit trace: Trace): ZSink[Any, Nothing, In, In, S] =
    foldWeightedDecompose[In, S](z)(costFn, max, Chunk.single(_))(f)

  /**
   * Creates a sink that folds elements of type `In` into a structure of type
   * `S`, until `max` worth of elements (determined by the `costFn`) have been
   * folded.
   *
   * The `decompose` function will be used for decomposing elements that cause
   * an `S` aggregate to cross `max` into smaller elements. For example:
   * {{{
   * Stream(1, 5, 1)
   *   .transduce(
   *     ZSink
   *       .foldWeightedDecompose(List[Int]())((i: Int) => i.toLong, 4,
   *         (i: Int) => Chunk(i - 1, 1)) { (acc, el) =>
   *         el :: acc
   *       }
   *       .map(_.reverse)
   *   )
   *   .runCollect
   * }}}
   *
   * The stream would emit the elements `List(1), List(4), List(1, 1)`.
   *
   * Be vigilant with this function, it has to generate "simpler" values or the
   * fold may never end. A value is considered indivisible if `decompose` yields
   * the empty chunk or a single-valued chunk. In these cases, there is no other
   * choice than to yield a value that will cross the threshold.
   *
   * The [[foldWeightedDecomposeZIO]] allows the decompose function to return a
   * `ZIO` value, and consequently it allows the sink to fail.
   */
  def foldWeightedDecompose[In, S](
    z: => S
  )(costFn: (S, In) => Long, max: => Long, decompose: In => Chunk[In])(
    f: (S, In) => S
  )(implicit trace: Trace): ZSink[Any, Nothing, In, In, S] =
    ZSink.suspend {
      def go(
        s: S,
        cost: Long,
        dirty: Boolean,
        max: Long
      ): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], S] =
        ZChannel.readWithCause(
          (in: Chunk[In]) => {
            def fold(in: Chunk[In], s: S, dirty: Boolean, cost: Long, idx: Int): (S, Long, Boolean, Chunk[In]) =
              if (idx == in.length) (s, cost, dirty, Chunk.empty)
              else {
                val elem  = in(idx)
                val total = cost + costFn(s, elem)

                if (total <= max) fold(in, f(s, elem), true, total, idx + 1)
                else {
                  val decomposed = decompose(elem)

                  if (decomposed.length <= 1 && !dirty)
                    // If `elem` cannot be decomposed, we need to cross the `max` threshold. To
                    // minimize "injury", we only allow this when we haven't added anything else
                    // to the aggregate (dirty = false).
                    (f(s, elem), total, true, in.drop(idx + 1))
                  else if (decomposed.length <= 1 && dirty)
                    // If the state is dirty and `elem` cannot be decomposed, we stop folding
                    // and include `elem` in th leftovers.
                    (s, cost, dirty, in.drop(idx))
                  else
                    // `elem` got decomposed, so we will recurse with the decomposed elements pushed
                    // into the chunk we're processing and see if we can aggregate further.
                    fold(decomposed ++ in.drop(idx + 1), s, dirty, cost, 0)
                }
              }

            val (nextS, nextCost, nextDirty, leftovers) = fold(in, s, dirty, cost, 0)

            if (leftovers.nonEmpty) ZChannel.write(leftovers) *> ZChannel.succeedNow(nextS)
            else if (cost > max) ZChannel.succeedNow(nextS)
            else go(nextS, nextCost, nextDirty, max)
          },
          (err: Cause[ZNothing]) => ZChannel.refailCause(err),
          (_: Any) => ZChannel.succeedNow(s)
        )

      new ZSink(go(z, 0, false, max))
    }

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S`, until `max` worth of elements (determined by the
   * `costFn`) have been folded.
   *
   * The `decompose` function will be used for decomposing elements that cause
   * an `S` aggregate to cross `max` into smaller elements. Be vigilant with
   * this function, it has to generate "simpler" values or the fold may never
   * end. A value is considered indivisible if `decompose` yields the empty
   * chunk or a single-valued chunk. In these cases, there is no other choice
   * than to yield a value that will cross the threshold.
   *
   * See [[foldWeightedDecompose]] for an example.
   */
  def foldWeightedDecomposeZIO[Env, Err, In, S](z: => S)(
    costFn: (S, In) => ZIO[Env, Err, Long],
    max: => Long,
    decompose: In => ZIO[Env, Err, Chunk[In]]
  )(f: (S, In) => ZIO[Env, Err, S])(implicit trace: Trace): ZSink[Env, Err, In, In, S] =
    ZSink.suspend {
      def go(s: S, cost: Long, dirty: Boolean, max: Long): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], S] =
        ZChannel.readWithCause(
          (in: Chunk[In]) => {
            def fold(
              in: Chunk[In],
              s: S,
              dirty: Boolean,
              cost: Long,
              idx: Int
            ): ZIO[Env, Err, (S, Long, Boolean, Chunk[In])] =
              if (idx == in.length) ZIO.succeed((s, cost, dirty, Chunk.empty))
              else {
                val elem = in(idx)
                costFn(s, elem).map(cost + _).flatMap { total =>
                  if (total <= max) f(s, elem).flatMap(fold(in, _, true, total, idx + 1))
                  else
                    decompose(elem).flatMap { decomposed =>
                      if (decomposed.length <= 1 && !dirty)
                        // If `elem` cannot be decomposed, we need to cross the `max` threshold. To
                        // minimize "injury", we only allow this when we haven't added anything else
                        // to the aggregate (dirty = false).
                        f(s, elem).map((_, total, true, in.drop(idx + 1)))
                      else if (decomposed.length <= 1 && dirty)
                        // If the state is dirty and `elem` cannot be decomposed, we stop folding
                        // and include `elem` in th leftovers.
                        ZIO.succeed((s, cost, dirty, in.drop(idx)))
                      else
                        // `elem` got decomposed, so we will recurse with the decomposed elements pushed
                        // into the chunk we're processing and see if we can aggregate further.
                        fold(decomposed ++ in.drop(idx + 1), s, dirty, cost, 0)
                    }
                }
              }

            ZChannel.fromZIO(fold(in, s, dirty, cost, 0)).flatMap { case (nextS, nextCost, nextDirty, leftovers) =>
              if (leftovers.nonEmpty) ZChannel.write(leftovers) *> ZChannel.succeedNow(nextS)
              else if (cost > max) ZChannel.succeedNow(nextS)
              else go(nextS, nextCost, nextDirty, max)
            }
          },
          (err: Cause[Err]) => ZChannel.refailCause(err),
          (_: Any) => ZChannel.succeedNow(s)
        )

      new ZSink(go(z, 0, false, max))
    }

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S`, until `max` worth of elements (determined by the
   * `costFn`) have been folded.
   *
   * @note
   *   Elements that have an individual cost larger than `max` will force the
   *   sink to cross the `max` cost. See [[foldWeightedDecomposeZIO]] for a
   *   variant that can handle these cases.
   */
  def foldWeightedZIO[Env, Err, In, S](
    z: => S
  )(costFn: (S, In) => ZIO[Env, Err, Long], max: Long)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: Trace): ZSink[Env, Err, In, In, S] =
    foldWeightedDecomposeZIO(z)(costFn, max, (i: In) => ZIO.succeed(Chunk.single(i)))(f)

  /**
   * A sink that effectfully folds its inputs with the provided function,
   * termination predicate and initial state.
   */
  def foldZIO[Env, Err, In, S](z: => S)(contFn: S => Boolean)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: Trace): ZSink[Env, Err, In, In, S] =
    ZSink.suspend {
      def foldChunkSplitZIO(z: S, chunk: Chunk[In])(
        contFn: S => Boolean
      )(f: (S, In) => ZIO[Env, Err, S]): ZIO[Env, Err, (S, Option[Chunk[In]])] = {
        def fold(s: S, chunk: Chunk[In], idx: Int, len: Int): ZIO[Env, Err, (S, Option[Chunk[In]])] =
          if (idx == len) ZIO.succeed((s, None))
          else
            f(s, chunk(idx)).flatMap { s1 =>
              if (contFn(s1)) {
                fold(s1, chunk, idx + 1, len)
              } else {
                ZIO.succeed((s1, Some(chunk.drop(idx + 1))))
              }
            }

        fold(z, chunk, 0, chunk.length)
      }

      def reader(s: S): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], S] =
        if (!contFn(s)) ZChannel.succeedNow(s)
        else
          ZChannel.readWithCause(
            (in: Chunk[In]) =>
              ZChannel.fromZIO(foldChunkSplitZIO(s, in)(contFn)(f)).flatMap { case (nextS, leftovers) =>
                leftovers match {
                  case Some(l) => ZChannel.write(l).as(nextS)
                  case None    => reader(nextS)
                }
              },
            (err: Cause[Err]) => ZChannel.refailCause(err),
            (_: Any) => ZChannel.succeedNow(s)
          )

      new ZSink(reader(z))
    }

  /**
   * A sink that returns whether all elements satisfy the specified predicate.
   */
  def forall[In](f: In => Boolean)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Boolean] =
    fold(true)(identity)(_ && f(_))

  /**
   * A sink that executes the provided effectful function for every element fed
   * to it.
   */
  def foreach[R, Err, In](
    f: In => ZIO[R, Err, Any]
  )(implicit trace: Trace): ZSink[R, Err, In, Nothing, Unit] = {

    lazy val process: ZChannel[R, Err, Chunk[In], Any, Err, Nothing, Unit] =
      ZChannel.readWithCause(
        in => ZChannel.fromZIO(ZIO.foreachDiscard(in)(f(_))) *> process,
        halt => ZChannel.refailCause(halt),
        _ => ZChannel.unit
      )

    new ZSink(process)
  }

  /**
   * A sink that executes the provided effectful function for every chunk fed to
   * it.
   */
  def foreachChunk[R, Err, In](
    f: Chunk[In] => ZIO[R, Err, Any]
  )(implicit trace: Trace): ZSink[R, Err, In, Nothing, Unit] = {
    lazy val process: ZChannel[R, Err, Chunk[In], Any, Err, Nothing, Unit] =
      ZChannel.readWithCause(
        in => ZChannel.fromZIO(f(in)) *> process,
        halt => ZChannel.refailCause(halt),
        _ => ZChannel.unit
      )

    new ZSink(process)
  }

  /**
   * A sink that executes the provided effectful function for every element fed
   * to it until `f` evaluates to `false`.
   */
  def foreachWhile[R, Err, In](
    f: In => ZIO[R, Err, Boolean]
  )(implicit trace: Trace): ZSink[R, Err, In, In, Unit] = {
    def go(
      chunk: Chunk[In],
      idx: Int,
      len: Int,
      cont: ZChannel[R, Err, Chunk[In], Any, Err, Chunk[In], Unit]
    ): ZChannel[R, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
      if (idx == len)
        cont
      else
        ZChannel
          .fromZIO(f(chunk(idx)))
          .flatMap(b => if (b) go(chunk, idx + 1, len, cont) else ZChannel.write(chunk.drop(idx)))
          .catchAll(e => ZChannel.write(chunk.drop(idx)) *> ZChannel.fail(e))

    lazy val process: ZChannel[R, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
      ZChannel.readWithCause(
        in => go(in, 0, in.length, process),
        halt => ZChannel.refailCause(halt),
        _ => ZChannel.unit
      )

    new ZSink(process)
  }

  /**
   * A sink that executes the provided effectful function for every chunk fed to
   * it until `f` evaluates to `false`.
   */
  def foreachChunkWhile[R, Err, In](
    f: Chunk[In] => ZIO[R, Err, Boolean]
  )(implicit trace: Trace): ZSink[R, Err, In, In, Unit] = {
    lazy val reader: ZChannel[R, Err, Chunk[In], Any, Err, Nothing, Unit] =
      ZChannel.readWithCause(
        (in: Chunk[In]) =>
          ZChannel.fromZIO(f(in)).flatMap { continue =>
            if (continue) reader
            else ZChannel.unit
          },
        (err: Cause[Err]) => ZChannel.refailCause(err),
        (_: Any) => ZChannel.unit
      )

    new ZSink(reader)
  }

  /**
   * Creates a sink from a [[zio.stream.ZChannel]]
   */
  def fromChannel[R, E, In, L, Z](
    channel: ZChannel[R, ZNothing, Chunk[In], Any, E, Chunk[L], Z]
  ): ZSink[R, E, In, L, Z] =
    new ZSink(channel)

  /**
   * Creates a sink from a chunk processing function.
   */
  def fromPush[R, E, I, L, Z](
    push: ZIO[Scope with R, Nothing, Option[Chunk[I]] => ZIO[R, (Either[E, Z], Chunk[L]), Unit]]
  )(implicit trace: Trace): ZSink[R, E, I, L, Z] = {

    def pull(
      push: Option[Chunk[I]] => ZIO[R, (Either[E, Z], Chunk[L]), Unit]
    ): ZChannel[R, ZNothing, Chunk[I], Any, E, Chunk[L], Z] =
      ZChannel.readWithCause(
        in =>
          ZChannel
            .fromZIO(push(Some(in)))
            .foldChannel(
              {
                case (Left(e), leftovers) =>
                  ZChannel.write(leftovers) *> ZChannel.fail(e)
                case (Right(z), leftovers) =>
                  ZChannel.write(leftovers) *> ZChannel.succeedNow(z)
              },
              _ => pull(push)
            ),
        err => ZChannel.refailCause(err),
        _ =>
          ZChannel
            .fromZIO(push(None))
            .foldChannel(
              {
                case (Left(e), leftovers) =>
                  ZChannel.write(leftovers) *> ZChannel.fail(e)
                case (Right(z), leftovers) =>
                  ZChannel.write(leftovers) *> ZChannel.succeedNow(z)
              },
              _ => ZChannel.fromZIO(ZIO.dieMessage("empty sink"))
            )
      )

    new ZSink(ZChannel.unwrapScoped[R](push.map(pull)))
  }

  /**
   * Creates a single-value sink produced from an effect
   */
  def fromZIO[R, E, Z](b: => ZIO[R, E, Z])(implicit trace: Trace): ZSink[R, E, Any, Nothing, Z] =
    new ZSink(ZChannel.fromZIO(b))

  /**
   * Create a sink which enqueues each element into the specified queue.
   */
  def fromQueue[I](queue: => Enqueue[I])(implicit trace: Trace): ZSink[Any, Nothing, I, Nothing, Unit] =
    ZSink.unwrap(ZIO.succeed(queue).map(queue => foreachChunk(queue.offerAll)))

  /**
   * Create a sink which enqueues each element into the specified queue. The
   * queue will be shutdown once the stream is closed.
   */
  def fromQueueWithShutdown[I](queue: => Enqueue[I])(implicit
    trace: Trace
  ): ZSink[Any, Nothing, I, Nothing, Unit] =
    ZSink.unwrapScoped(
      ZIO.acquireRelease(ZIO.succeed(queue))(_.shutdown).map(fromQueue[I](_))
    )

  /**
   * Create a sink which publishes each element to the specified hub.
   */
  def fromHub[I](hub: => Hub[I])(implicit
    trace: Trace
  ): ZSink[Any, Nothing, I, Nothing, Unit] =
    fromQueue(hub)

  /**
   * Create a sink which publishes each element to the specified hub. The hub
   * will be shutdown once the stream is closed.
   */
  def fromHubWithShutdown[I](hub: => Hub[I])(implicit
    trace: Trace
  ): ZSink[Any, Nothing, I, Nothing, Unit] =
    fromQueueWithShutdown(hub)

  /**
   * Creates a sink containing the first value.
   */
  def head[In](implicit trace: Trace): ZSink[Any, Nothing, In, In, Option[In]] =
    fold(None: Option[In])(_.isEmpty) {
      case (s @ Some(_), _) => s
      case (None, in)       => Some(in)
    }

  /**
   * Creates a sink containing the last value.
   */
  def last[In](implicit trace: Trace): ZSink[Any, Nothing, In, In, Option[In]] =
    foldLeftChunks[In, Option[In]](None)((s, in) => in.lastOption.orElse(s))

  /**
   * Creates a sink that does not consume any input but provides the given chunk
   * as its leftovers
   */
  def leftover[L](c: => Chunk[L])(implicit trace: Trace): ZSink[Any, Nothing, Any, L, Unit] =
    new ZSink(ZChannel.suspend(ZChannel.write(c)))

  /**
   * Logs the specified message at the current log level.
   */
  def log(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.log(message))

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate[R, E, In, L, Z](key: => String, value: => String)(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    logAnnotate(LogAnnotation(key, value))(sink)

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate[R, E, In, L, Z](annotation: => LogAnnotation, annotations: LogAnnotation*)(
    sink: ZSink[R, E, In, L, Z]
  )(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    logAnnotate(Set(annotation) ++ annotations.toSet)(sink)

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate[R, E, In, L, Z](annotations: => Set[LogAnnotation])(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    ZSink.unwrapScoped(ZIO.logAnnotateScoped(annotations).as(sink))

  /**
   * Retrieves the log annotations associated with the current scope.
   */
  def logAnnotations(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Map[String, String]] =
    ZSink.fromZIO(FiberRef.currentLogAnnotations.get)

  /**
   * Logs the specified message at the debug log level.
   */
  def logDebug(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logDebug(message))

  /**
   * Logs the specified message at the error log level.
   */
  def logError(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logError(message))

  /**
   * Logs the specified cause as an error.
   */
  def logErrorCause(cause: => Cause[Any])(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logErrorCause(cause))

  /**
   * Logs the specified message at the fatal log level.
   */
  def logFatal(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logFatal(message))

  /**
   * Logs the specified message at the informational log level.
   */
  def logInfo(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logInfo(message))

  /**
   * Sets the log level for streams composed after this.
   */
  def logLevel[R, E, In, L, Z](level: LogLevel)(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    ZSink.unwrapScoped(ZIO.logLevelScoped(level).as(sink))

  /**
   * Adjusts the label for the logging span for streams composed after this.
   */
  def logSpan[R, E, In, L, Z](label: => String)(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    ZSink.unwrapScoped(ZIO.logSpanScoped(label).as(sink))

  /**
   * Logs the specified message at the trace log level.
   */
  def logTrace(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logTrace(message))

  /**
   * Logs the specified message at the warning log level.
   */
  def logWarning(message: => String)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Unit] =
    ZSink.fromZIO(ZIO.logWarning(message))

  def mkString(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, String] =
    ZSink.suspend {
      val builder = new StringBuilder()

      foldLeftChunks[Any, Unit](())((_, els: Chunk[Any]) => els.foreach(el => builder.append(el.toString))).map(_ =>
        builder.result()
      )
    }

  def never(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Nothing] =
    ZSink.fromZIO(ZIO.never)

  /**
   * Accesses the specified service in the environment of the effect.
   */
  def service[Z: Tag](implicit trace: Trace): ZSink[Z, Nothing, Any, Nothing, Z] =
    ZSink.serviceWith(identity)

  /**
   * Accesses the service corresponding to the specified key in the environment.
   */
  def serviceAt[Service]: ZStream.ServiceAtPartiallyApplied[Service] =
    new ZStream.ServiceAtPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the sink.
   */
  def serviceWith[Service]: ServiceWithPartiallyApplied[Service] =
    new ServiceWithPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the sink in the
   * context of an effect.
   */
  def serviceWithZIO[Service]: ServiceWithZIOPartiallyApplied[Service] =
    new ServiceWithZIOPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the sink in the
   * context of a sink.
   */
  def serviceWithSink[Service]: ServiceWithSinkPartiallyApplied[Service] =
    new ServiceWithSinkPartiallyApplied[Service]

  /**
   * A sink that immediately ends with the specified value.
   */
  def succeed[Z](z: => Z)(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Z] =
    new ZSink(ZChannel.succeed(z))

  /**
   * Returns a lazily constructed sink that may require effects for its
   * creation.
   */
  def suspend[Env, E, In, Leftover, Done](
    sink: => ZSink[Env, E, In, Leftover, Done]
  )(implicit trace: Trace): ZSink[Env, E, In, Leftover, Done] =
    new ZSink(ZChannel.suspend(sink.channel))

  /**
   * A sink that sums incoming numeric values.
   */
  def sum[A](implicit A: Numeric[A], trace: Trace): ZSink[Any, Nothing, A, Nothing, A] =
    foldLeftChunks(A.zero)((acc, chunk) => A.plus(acc, chunk.sum))

  /**
   * Tags each metric in this sink with the specific tag.
   */
  def tagged[R, E, In, L, Z](key: => String, value: => String)(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    tagged(Set(MetricLabel(key, value)))(sink)

  /**
   * Tags each metric in this sink with the specific tag.
   */
  def tagged[R, E, In, L, Z](tag: => MetricLabel, tags: MetricLabel*)(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    tagged(Set(tag) ++ tags.toSet)(sink)

  /**
   * Tags each metric in this sink with the specific tag.
   */
  def tagged[R, E, In, L, Z](tags: => Set[MetricLabel])(sink: ZSink[R, E, In, L, Z])(implicit
    trace: Trace
  ): ZSink[R, E, In, L, Z] =
    ZSink.unwrapScoped(ZIO.taggedScoped(tags).as(sink))

  /**
   * Retrieves the metric tags associated with the current scope.
   */
  def tags(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Set[MetricLabel]] =
    ZSink.fromZIO(ZIO.tags)

  /**
   * A sink that takes the specified number of values.
   */
  def take[In](n: Int)(implicit trace: Trace): ZSink[Any, Nothing, In, In, Chunk[In]] =
    ZSink.unwrap {
      ZIO.succeed(n).map { n =>
        ZSink.foldChunks[In, Chunk[In]](Chunk.empty)(_.length < n)(_ ++ _).flatMap { acc =>
          val (taken, leftover) = acc.splitAt(n)
          new ZSink(
            ZChannel.write(leftover) *> ZChannel.succeedNow(taken)
          )
        }
      }
    }

  def timed(implicit trace: Trace): ZSink[Any, Nothing, Any, Nothing, Duration] =
    ZSink.drain.timed.map(_._2)

  /**
   * Creates a sink produced from an effect.
   */
  def unwrap[R, E, In, L, Z](
    zio: => ZIO[R, E, ZSink[R, E, In, L, Z]]
  )(implicit trace: Trace): ZSink[R, E, In, L, Z] =
    new ZSink(ZChannel.unwrap(zio.map(_.channel)))

  /**
   * Creates a sink produced from a scoped effect.
   */
  def unwrapScoped[R]: UnwrapScopedPartiallyApplied[R] =
    new UnwrapScopedPartiallyApplied[R]

  /**
   * Creates a sink produced from a scoped effect.
   */
  def unwrapScopedWith[R, E, In, L, Z](
    f: Scope => ZIO[R, E, ZSink[R, E, In, L, Z]]
  )(implicit trace: Trace): ZSink[R, E, In, L, Z] =
    new ZSink(ZChannel.unwrapScopedWith(scope => f(scope).map(_.channel)))

  final class EnvironmentWithPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[Z](
      f: ZEnvironment[R] => Z
    )(implicit trace: Trace): ZSink[R, Nothing, Any, Nothing, Z] =
      ZSink.environment[R].map(f)
  }

  final class EnvironmentWithZIOPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, Z](
      f: ZEnvironment[R] => ZIO[R1, E, Z]
    )(implicit trace: Trace): ZSink[R with R1, E, Any, Nothing, Z] =
      ZSink.environment[R].mapZIO(f)
  }

  final class EnvironmentWithSinkPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, In, L, Z](
      f: ZEnvironment[R] => ZSink[R1, E, In, L, Z]
    )(implicit trace: Trace): ZSink[R with R1, E, In, L, Z] =
      new ZSink(ZChannel.unwrap(ZIO.environmentWith[R](f(_).channel)))
  }

  final class ServiceAtPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Key](
      key: => Key
    )(implicit
      tag: EnvironmentTag[Map[Key, Service]],
      trace: Trace
    ): ZSink[Map[Key, Service], Nothing, Any, Nothing, Option[Service]] =
      ZSink.environmentWith(_.getAt(key))
  }

  final class ServiceWithPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Z](f: Service => Z)(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZSink[Service, Nothing, Any, Nothing, Z] =
      ZSink.fromZIO(ZIO.serviceWith[Service](f))
  }

  final class ServiceWithZIOPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, Z](f: Service => ZIO[R, E, Z])(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZSink[R with Service, E, Any, Nothing, Z] =
      ZSink.fromZIO(ZIO.serviceWithZIO[Service](f))
  }

  final class ServiceWithSinkPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, In, L, Z](f: Service => ZSink[R, E, In, L, Z])(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZSink[R with Service, E, In, L, Z] =
      new ZSink(ZChannel.unwrap(ZIO.serviceWith[Service](f(_).channel)))
  }

  final class UnwrapScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, In, L, Z](scoped: => ZIO[Scope with R, E, ZSink[R, E, In, L, Z]])(implicit
      trace: Trace
    ): ZSink[R, E, In, L, Z] =
      new ZSink(ZChannel.unwrapScoped[R](scoped.map(_.channel)))
  }

  final class ProvideSomeLayer[R0, -R, +E, -In, +L, +Z](
    private val channel: ZChannel[R, ZNothing, Chunk[In], Any, E, Chunk[L], Z]
  ) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: => ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: Trace
    ): ZSink[R0, E1, In, L, Z] =
      new ZSink(
        channel
          .asInstanceOf[ZChannel[R0 with R1, ZNothing, Chunk[In], Any, E, Chunk[L], Z]]
          .provideLayer(ZLayer.environment[R0] ++ layer)
      )
  }
}
