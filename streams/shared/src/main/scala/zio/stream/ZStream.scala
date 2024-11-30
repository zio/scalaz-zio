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
import zio.internal.{PartitionedRingBuffer, SingleThreadedRingBuffer, UniqueKey}
import zio.metrics.MetricLabel
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stm._
import zio.stream.ZStream.{DebounceState, HandoffSignal, failCause, zipChunks}
import zio.stream.internal.{ZInputStream, ZReader}

import java.io.{IOException, InputStream}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

final class ZStream[-R, +E, +A] private (val channel: ZChannel[R, Any, Any, Any, E, Chunk[A], Any]) { self =>

  import ZStream.HaltStrategy

  /**
   * Returns a new ZStream that applies the specified aspect to this ZStream.
   * Aspects are "transformers" that modify the behavior of their input in some
   * well-defined way (for example, adding a timeout).
   */
  def @@[LowerR <: UpperR, UpperR <: R, LowerE >: E, UpperE >: LowerE, LowerA >: A, UpperA >: LowerA](
    aspect: => ZStreamAspect[LowerR, UpperR, LowerE, UpperE, LowerA, UpperA]
  )(implicit trace: Trace): ZStream[UpperR, LowerE, LowerA] =
    ZStream.suspend(aspect(self))

  /**
   * Symbolic alias for [[ZStream#cross]].
   */
  def <*>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    zippable: Zippable[A, A2],
    trace: Trace
  ): ZStream[R1, E1, zippable.Out] =
    self cross that

  /**
   * Symbolic alias for [[ZStream#crossLeft]].
   */
  def <*[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A] =
    self crossLeft that

  /**
   * Symbolic alias for [[ZStream#crossRight]].
   */
  def *>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A2] =
    self crossRight that

  /**
   * Symbolic alias for [[ZStream#zip]].
   */
  def <&>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    zippable: Zippable[A, A2],
    trace: Trace
  ): ZStream[R1, E1, zippable.Out] =
    self zip that

  /**
   * Symbolic alias for [[ZStream#zipLeft]].
   */
  def <&[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A] =
    self zipLeft that

  /**
   * Symbolic alias for [[ZStream#zipRight]].
   */
  def &>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A2] =
    self zipRight that

  /**
   * Symbolic alias for [[ZStream#via]].
   */
  def >>>[R1 <: R, E1 >: E, B](pipeline: => ZPipeline[R1, E1, A, B])(implicit
    trace: Trace
  ): ZStream[R1, E1, B] =
    via(pipeline)

  /**
   * Symbolic alias for [[[zio.stream.ZStream!.run[R1<:R,E1>:E,B]*]]].
   */
  def >>>[R1 <: R, E1 >: E, A2 >: A, Z](sink: => ZSink[R1, E1, A2, Any, Z])(implicit
    trace: Trace
  ): ZIO[R1, E1, Z] =
    self.run(sink)

  /**
   * Symbolic alias for [[ZStream#concat]].
   */
  def ++[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit trace: Trace): ZStream[R1, E1, A1] =
    self concat that

  /**
   * Symbolic alias for [[ZStream#orElse]].
   */
  def <>[R1 <: R, E2, A1 >: A](
    that: => ZStream[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E2, A1] =
    self orElse that

  /**
   * A symbolic alias for `orDie`.
   */
  def !(implicit ev1: E <:< Throwable, ev2: CanFail[E], trace: Trace): ZStream[R, Nothing, A] =
    self.orDie

  /**
   * Returns a stream that submerges the error case of an `Either` into the
   * `ZStream`.
   */
  def absolve[R1 <: R, E1, A1](implicit
    ev: ZStream[R, E, A] <:< ZStream[R1, E1, Either[E1, A1]],
    trace: Trace
  ): ZStream[R1, E1, A1] =
    ZStream.absolve(ev(self))

  /**
   * Aggregates elements of this stream using the provided sink for as long as
   * the downstream operators on the stream are busy.
   *
   * This operator divides the stream into two asynchronous "islands". Operators
   * upstream of this operator run on one fiber, while downstream operators run
   * on another. Whenever the downstream fiber is busy processing elements, the
   * upstream fiber will feed elements into the sink until it signals
   * completion.
   *
   * Any sink can be used here, but see [[ZSink.foldWeightedZIO]] and
   * [[ZSink.foldUntilZIO]] for sinks that cover the common usecases.
   */
  def aggregateAsync[R1 <: R, E1 >: E, A1 >: A, B](
    sink: => ZSink[R1, E1, A1, A1, B]
  )(implicit trace: Trace): ZStream[R1, E1, B] =
    aggregateAsyncWithin(sink, Schedule.forever)

  /**
   * Like `aggregateAsyncWithinEither`, but only returns the `Right` results.
   *
   * @param sink
   *   used for the aggregation
   * @param schedule
   *   signalling for when to stop the aggregation
   * @return
   *   `ZStream[R1, E2, B]`
   */
  def aggregateAsyncWithin[R1 <: R, E1 >: E, A1 >: A, B](
    sink: => ZSink[R1, E1, A1, A1, B],
    schedule: => Schedule[R1, Option[B], Any]
  )(implicit trace: Trace): ZStream[R1, E1, B] =
    aggregateAsyncWithinEither(sink, schedule).collectRight

  /**
   * Aggregates elements using the provided sink until it completes, or until
   * the delay signalled by the schedule has passed.
   *
   * This operator divides the stream into two asynchronous islands. Operators
   * upstream of this operator run on one fiber, while downstream operators run
   * on another. Elements will be aggregated by the sink until the downstream
   * fiber pulls the aggregated value, or until the schedule's delay has passed.
   *
   * Aggregated elements will be fed into the schedule to determine the delays
   * between pulls.
   *
   * @param sink
   *   used for the aggregation
   * @param schedule
   *   signalling for when to stop the aggregation
   * @return
   *   `ZStream[R1, E2, Either[C, B]]`
   */
  def aggregateAsyncWithinEither[R1 <: R, E1 >: E, A1 >: A, B, C](
    sink: => ZSink[R1, E1, A1, A1, B],
    schedule: => Schedule[R1, Option[B], C]
  )(implicit trace: Trace): ZStream[R1, E1, Either[C, B]] = {
    type HandoffSignal = ZStream.HandoffSignal[E1, A]
    import ZStream.HandoffSignal._
    type SinkEndReason = ZStream.SinkEndReason
    import ZStream.SinkEndReason._

    val layer =
      ZStream.Handoff.make[HandoffSignal] <*>
        Ref.make[SinkEndReason](ScheduleEnd) <*>
        Ref.make(Chunk[A1]()) <*>
        schedule.driver <*>
        Ref.make(false) <*>
        Ref.make(false)

    ZStream.fromZIO(layer).flatMap {
      case (handoff, sinkEndReason, sinkLeftovers, scheduleDriver, consumed, endAfterEmit) =>
        lazy val handoffProducer: ZChannel[Any, E1, Chunk[A], Any, Nothing, Nothing, Any] =
          ZChannel.readWithCause(
            (in: Chunk[A]) => ZChannel.fromZIO(handoff.offer(Emit(in)).when(in.nonEmpty)) *> handoffProducer,
            (cause: Cause[E1]) => ZChannel.fromZIO(handoff.offer(Halt(cause))),
            (_: Any) => ZChannel.fromZIO(handoff.offer(End(UpstreamEnd)))
          )

        lazy val handoffConsumer: ZChannel[Any, Any, Any, Any, E1, Chunk[A1], Unit] =
          ZChannel.unwrap(
            sinkLeftovers.getAndSet(Chunk.empty).flatMap { leftovers =>
              if (leftovers.nonEmpty) {
                consumed.set(true) *> ZIO.succeed(ZChannel.write(leftovers) *> handoffConsumer)
              } else
                handoff.take.map {
                  case Emit(chunk) =>
                    ZChannel.fromZIO(consumed.set(true)) *>
                      ZChannel.write(chunk) *>
                      ZChannel.fromZIO(endAfterEmit.get).flatMap {
                        case false => handoffConsumer
                        case true  => ZChannel.unit
                      }
                  case Halt(cause) => ZChannel.refailCause(cause)
                  case End(ScheduleEnd) =>
                    ZChannel.unwrap {
                      consumed.get.map { p =>
                        if (p) ZChannel.fromZIO(sinkEndReason.set(ScheduleEnd) *> endAfterEmit.set(true))
                        else
                          ZChannel
                            .fromZIO(sinkEndReason.set(ScheduleEnd) *> endAfterEmit.set(true)) *> handoffConsumer
                      }
                    }
                  case End(reason) => ZChannel.fromZIO(sinkEndReason.set(reason) *> endAfterEmit.set(true))
                }
            }
          )

        def timeout(lastB: Option[B]): ZIO[R1, None.type, C] =
          scheduleDriver.next(lastB)

        def scheduledAggregator(
          sinkFiber: Fiber.Runtime[E1, (Chunk[Chunk[A1]], B)],
          scheduleFiber: Fiber.Runtime[None.type, C],
          scope: Scope
        ): ZChannel[R1, Any, Any, Any, E1, Chunk[Either[C, B]], Any] = {

          val forkSink =
            consumed.set(false) *> endAfterEmit
              .set(false) *> (handoffConsumer pipeToOrFail sink.channel).collectElements.run.forkIn(scope)

          def handleSide(leftovers: Chunk[Chunk[A1]], b: B, c: Option[C]) =
            ZChannel.unwrap(
              sinkLeftovers.set(leftovers.flatten) *>
                sinkEndReason.get.map {
                  case ScheduleEnd =>
                    ZChannel.unwrap {
                      for {
                        consumed      <- consumed.get
                        sinkFiber     <- forkSink
                        scheduleFiber <- timeout(Some(b)).forkIn(scope)
                        toWrite        = c.fold[Chunk[Either[C, B]]](Chunk.single(Right(b)))(c => Chunk(Right(b), Left(c)))
                      } yield
                        if (consumed)
                          ZChannel
                            .write(toWrite) *> scheduledAggregator(sinkFiber, scheduleFiber, scope)
                        else scheduledAggregator(sinkFiber, scheduleFiber, scope)
                    }
                  case UpstreamEnd =>
                    ZChannel.unwrap {
                      consumed.get.map { p =>
                        if (p) ZChannel.write(Chunk.single(Right(b)))
                        else ZChannel.unit
                      }
                    }
                }
            )

          ZChannel.unwrap {
            sinkFiber.join.raceWith(scheduleFiber.join)(
              (sinkExit, _) =>
                scheduleFiber.interrupt *>
                  ZIO.done(sinkExit).map { case (leftovers, b) => handleSide(leftovers, b, None) },
              (scheduleExit, _) =>
                ZIO
                  .done(scheduleExit)
                  .foldCauseZIO(
                    _.failureOrCause match {
                      case Left(_) =>
                        handoff.offer(End(ScheduleEnd)).forkDaemon *> sinkFiber.join.map { case (leftovers, b) =>
                          handleSide(leftovers, b, None)
                        }
                      case Right(cause) =>
                        handoff.offer(Halt(cause)).forkDaemon *> sinkFiber.join.map { case (leftovers, b) =>
                          handleSide(leftovers, b, None)
                        }
                    },
                    c =>
                      handoff.offer(End(ScheduleEnd)).forkDaemon *>
                        sinkFiber.join.map { case (leftovers, b) =>
                          handleSide(leftovers, b, Some(c))
                        }
                  )
            )
          }
        }

        ZStream.unwrapScopedWith { scope =>
          for {
            _             <- (self.channel >>> handoffProducer).run.forkIn(scope)
            sinkFiber     <- (handoffConsumer pipeToOrFail sink.channel).collectElements.run.forkIn(scope)
            scheduleFiber <- timeout(None).forkIn(scope)
          } yield new ZStream(scheduledAggregator(sinkFiber, scheduleFiber, scope))
        }
    }
  }

  /**
   * Maps the success values of this stream to the specified constant value.
   */
  def as[A2](A2: => A2)(implicit trace: Trace): ZStream[R, E, A2] =
    map(_ => A2)

  /**
   * Fan out the stream, producing a list of streams that have the same elements
   * as this stream. The driver stream will only ever advance the `maximumLag`
   * chunks before the slowest downstream stream.
   */
  def broadcast(n: => Int, maximumLag: => Int)(implicit
    trace: Trace
  ): ZIO[R with Scope, Nothing, Chunk[ZStream[Any, E, A]]] =
    self
      .broadcastedQueues(n, maximumLag)
      .map(_.map(ZStream.fromQueueWithShutdown(_).flattenTake))

  /**
   * Fan out the stream, producing a dynamic number of streams that have the
   * same elements as this stream. The driver stream will only ever advance the
   * `maximumLag` chunks before the slowest downstream stream.
   */
  def broadcastDynamic(
    maximumLag: => Int
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, ZStream[Any, E, A]] =
    self
      .broadcastedQueuesDynamic(maximumLag)
      .map(ZStream.scoped(_).flatMap(ZStream.fromQueue(_)).flattenTake)

  /**
   * Converts the stream to a scoped list of queues. Every value will be
   * replicated to every queue with the slowest queue being allowed to buffer
   * `maximumLag` chunks before the driver is back pressured.
   *
   * Queues can unsubscribe from upstream by shutting down.
   */
  def broadcastedQueues(
    n: => Int,
    maximumLag: => Int
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Chunk[Dequeue[Take[E, A]]]] =
    for {
      hub    <- Hub.bounded[Take[E, A]](maximumLag)
      queues <- ZIO.collectAll(Chunk.fill(n)(hub.subscribe))
      _      <- self.runIntoHubScoped(hub).forkScoped
    } yield queues

  /**
   * Converts the stream to a scoped dynamic amount of queues. Every chunk will
   * be replicated to every queue with the slowest queue being allowed to buffer
   * `maximumLag` chunks before the driver is back pressured.
   *
   * Queues can unsubscribe from upstream by shutting down.
   */
  def broadcastedQueuesDynamic(
    maximumLag: => Int
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, ZIO[Scope, Nothing, Dequeue[Take[E, A]]]] =
    toHub(maximumLag).map(_.subscribe)

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` elements in a queue.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  def buffer(capacity: => Int)(implicit trace: Trace): ZStream[R, E, A] = {
    val queue = self.toQueueOfElements(capacity)
    new ZStream(
      ZChannel.unwrapScoped[R] {
        queue.map { queue =>
          lazy val process: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
            ZChannel.fromZIO {
              queue.take
            }.flatMap { (exit: Exit[Option[E], A]) =>
              exit.foldExit(
                Cause
                  .flipCauseOption(_)
                  .fold[ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit]](ZChannel.unit)(ZChannel.refailCause),
                value => ZChannel.write(Chunk.single(value)) *> process
              )
            }

          process
        }
      }
    )
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` chunks in a queue.
   *
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  def bufferChunks(capacity: => Int)(implicit trace: Trace): ZStream[R, E, A] = {
    val queue = self.toQueue(capacity)
    new ZStream(
      ZChannel.unwrapScoped[R] {
        queue.map { queue =>
          lazy val process: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
            ZChannel.fromZIO {
              queue.take
            }.flatMap { (take: Take[E, A]) =>
              take.fold(
                ZChannel.unit,
                error => ZChannel.refailCause(error),
                value => ZChannel.write(value) *> process
              )
            }

          process
        }
      }
    )
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` chunks in a dropping queue.
   *
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  def bufferChunksDropping(capacity: => Int)(implicit trace: Trace): ZStream[R, E, A] = {
    val queue =
      ZIO.acquireRelease(Queue.dropping[(Take[E, A], Promise[Nothing, Unit])](capacity))(_.shutdown)
    new ZStream(bufferSignal[R, E, A](queue, self.channel))
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` chunks in a sliding queue.
   *
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  def bufferChunksSliding(capacity: => Int)(implicit trace: Trace): ZStream[R, E, A] = {
    val queue =
      ZIO.acquireRelease(Queue.sliding[(Take[E, A], Promise[Nothing, Unit])](capacity))(_.shutdown)
    new ZStream(bufferSignal[R, E, A](queue, self.channel))
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` elements in a dropping queue.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  def bufferDropping(capacity: => Int)(implicit trace: Trace): ZStream[R, E, A] = {
    val queue =
      ZIO.acquireRelease(Queue.dropping[(Take[E, A], Promise[Nothing, Unit])](capacity))(_.shutdown)
    new ZStream(bufferSignal[R, E, A](queue, self.rechunk(1).channel))
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` elements in a sliding queue.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  def bufferSliding(capacity: => Int)(implicit trace: Trace): ZStream[R, E, A] = {
    val queue =
      ZIO.acquireRelease(Queue.sliding[(Take[E, A], Promise[Nothing, Unit])](capacity))(_.shutdown)
    new ZStream(bufferSignal[R, E, A](queue, self.rechunk(1).channel))
  }

  private def bufferSignal[R1 <: R, E1 >: E, A1 >: A](
    scoped: => ZIO[Scope, Nothing, Queue[(Take[E1, A1], Promise[Nothing, Unit])]],
    channel: => ZChannel[R1, Any, Any, Any, E1, Chunk[A1], Any]
  )(implicit trace: Trace): ZChannel[R1, Any, Any, Any, E1, Chunk[A1], Unit] = {
    def producer(
      queue: Queue[(Take[E1, A1], Promise[Nothing, Unit])],
      ref: Ref[Promise[Nothing, Unit]]
    ): ZChannel[R1, E1, Chunk[A1], Any, Nothing, Nothing, Any] = {
      def terminate(take: Take[E1, A1]): ZChannel[R1, E1, Chunk[A1], Any, Nothing, Nothing, Any] =
        ZChannel.fromZIO {
          for {
            latch <- ref.get
            _     <- latch.await
            p     <- Promise.make[Nothing, Unit]
            _     <- queue.offer((take, p))
            _     <- ref.set(p)
            _     <- p.await
          } yield ()
        }

      ZChannel.readWithCause[R1, E1, Chunk[A1], Any, Nothing, Nothing, Any](
        in =>
          ZChannel.fromZIO {
            for {
              p     <- Promise.make[Nothing, Unit]
              added <- queue.offer((Take.chunk(in), p))
              _     <- ref.set(p).when(added)
            } yield ()
          } *> producer(queue, ref),
        err => terminate(Take.failCause(err)),
        _ => terminate(Take.end)
      )
    }

    def consumer(
      queue: Queue[(Take[E1, A1], Promise[Nothing, Unit])]
    ): ZChannel[R1, Any, Any, Any, E1, Chunk[A1], Unit] = {
      lazy val process: ZChannel[Any, Any, Any, Any, E1, Chunk[A1], Unit] =
        ZChannel.fromZIO(queue.take).flatMap { case (take, promise) =>
          ZChannel.fromZIO(promise.succeed(())) *>
            take.fold(
              ZChannel.unit,
              error => ZChannel.refailCause(error),
              value => ZChannel.write(value) *> process
            )
        }

      process
    }

    ZChannel.unwrapScoped[R1] {
      for {
        queue <- scoped
        start <- Promise.make[Nothing, Unit]
        _     <- start.succeed(())
        ref   <- Ref.make(start)
        _     <- (channel >>> producer(queue, ref)).runScoped.forkScoped
      } yield consumer(queue)
    }
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering chunks into an unbounded queue.
   */
  def bufferUnbounded(implicit trace: Trace): ZStream[R, E, A] = {
    val queue = self.toQueueUnbounded
    new ZStream(
      ZChannel.unwrapScoped[R] {
        queue.map { queue =>
          lazy val process: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
            ZChannel.fromZIO {
              queue.take
            }.flatMap { (take: Take[E, A]) =>
              take.fold(
                ZChannel.unit,
                error => ZChannel.refailCause(error),
                value => ZChannel.write(value) *> process
              )
            }

          process
        }
      }
    )
  }

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails with a typed error.
   */
  def catchAll[R1 <: R, E2, A1 >: A](
    f: E => ZStream[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E2, A1] =
    catchAllCause(_.failureOrCause.fold(f, ZStream.failCause(_)))

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails. Allows recovery from all causes of failure, including
   * interruption if the stream is uninterruptible.
   */
  def catchAllCause[R1 <: R, E2, A1 >: A](f: Cause[E] => ZStream[R1, E2, A1])(implicit
    trace: Trace
  ): ZStream[R1, E2, A1] =
    new ZStream(channel.catchAllCause(f(_).channel))

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails with some typed error.
   */
  def catchSome[R1 <: R, E1 >: E, A1 >: A](pf: PartialFunction[E, ZStream[R1, E1, A1]])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    catchAll(pf.applyOrElse[E, ZStream[R1, E1, A1]](_, ZStream.fail(_)))

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails with some errors. Allows recovery from all causes of failure,
   * including interruption if the stream is uninterruptible.
   */
  def catchSomeCause[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[Cause[E], ZStream[R1, E1, A1]]
  )(implicit trace: Trace): ZStream[R1, E1, A1] =
    catchAllCause(pf.applyOrElse[Cause[E], ZStream[R1, E1, A1]](_, ZStream.failCause(_)))

  /**
   * Returns a new stream that only emits elements that are not equal to the
   * previous element emitted, using natural equality to determine whether two
   * elements are equal.
   */
  def changes(implicit trace: Trace): ZStream[R, E, A] =
    changesWith(_ == _)

  /**
   * Returns a new stream that only emits elements that are not equal to the
   * previous element emitted, using the specified function to determine whether
   * two elements are equal.
   */
  def changesWith(f: (A, A) => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.changesWith(f)

  /**
   * Returns a new stream that only emits elements that are not equal to the
   * previous element emitted, using the specified effectual function to
   * determine whether two elements are equal.
   */
  def changesWithZIO[R1 <: R, E1 >: E](
    f: (A, A) => ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): ZStream[R1, E1, A] =
    self >>> ZPipeline.changesWithZIO(f)

  /**
   * Exposes the underlying chunks of the stream as a stream of chunks of
   * elements.
   */
  def chunks(implicit trace: Trace): ZStream[R, E, Chunk[A]] =
    mapChunks(Chunk.single)

  /**
   * Performs the specified stream transformation with the chunk structure of
   * the stream exposed.
   */
  def chunksWith[R1, E1, A1](f: ZStream[R, E, Chunk[A]] => ZStream[R1, E1, Chunk[A1]])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    f(self.chunks).flattenChunks

  /**
   * Performs a filter and map in a single step.
   */
  def collect[B](f: PartialFunction[A, B])(implicit trace: Trace): ZStream[R, E, B] =
    mapChunks(_.collect(f))

  /**
   * Filters any `Right` values.
   */
  def collectLeft[L1, A1](implicit ev: A <:< Either[L1, A1], trace: Trace): ZStream[R, E, L1] =
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]] >>> ZPipeline.collectLeft

  /**
   * Filters any 'None' values.
   */
  def collectSome[A1](implicit ev: A <:< Option[A1], trace: Trace): ZStream[R, E, A1] =
    self.asInstanceOf[ZStream[R, E, Option[A1]]] >>> ZPipeline.collectSome[E, A1]

  /**
   * Filters any `Exit.Failure` values.
   */
  def collectSuccess[L1, A1](implicit ev: A <:< Exit[L1, A1], trace: Trace): ZStream[R, E, A1] =
    self.asInstanceOf[ZStream[R, E, Exit[L1, A1]]] >>> ZPipeline.collectSuccess

  /**
   * Filters any `Left` values.
   */
  def collectRight[L1, A1](implicit ev: A <:< Either[L1, A1], trace: Trace): ZStream[R, E, A1] =
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]] >>> ZPipeline.collectRight

  /**
   * Delays the emission of values by holding new values for a set duration. If
   * no new values arrive during that time the value is emitted, however if a
   * new value is received during the holding period the previous value is
   * discarded and the process is repeated with the new value.
   *
   * This operator is useful if you have a stream of "bursty" events which
   * eventually settle down and you only need the final event of the burst.
   *
   * @example
   *   A search engine may only want to initiate a search after a user has
   *   paused typing so as to not prematurely recommend results.
   */
  def debounce(d: => Duration)(implicit trace: Trace): ZStream[R, E, A] = {
    import DebounceState._
    import HandoffSignal._

    ZStream.unwrapScopedWith { scope =>
      for {
        d       <- ZIO.succeed(d)
        handoff <- ZStream.Handoff.make[HandoffSignal[E, A]]
      } yield {
        def enqueue(last: Chunk[A]) =
          for {
            f <- Clock.sleep(d).as(last).forkIn(scope)
          } yield consumer(Previous(f))

        lazy val producer: ZChannel[R, E, Chunk[A], Any, E, Nothing, Any] =
          ZChannel.readWithCause(
            (in: Chunk[A]) =>
              in.lastOption.fold(producer) { last =>
                ZChannel.fromZIO(handoff.offer(Emit(Chunk.single(last)))) *> producer
              },
            (cause: Cause[E]) => ZChannel.fromZIO(handoff.offer(Halt(cause))),
            (_: Any) => ZChannel.fromZIO(handoff.offer(End(ZStream.SinkEndReason.UpstreamEnd)))
          )

        def consumer(state: DebounceState[E, A]): ZChannel[R, Any, Any, Any, E, Chunk[A], Any] =
          ZChannel.unwrap(
            state match {
              case NotStarted =>
                handoff.take.map {
                  case HandoffSignal.Emit(last)  => ZChannel.unwrap(enqueue(last))
                  case HandoffSignal.Halt(error) => ZChannel.refailCause(error)
                  case HandoffSignal.End(_)      => ZChannel.unit
                }
              case Current(fiber) =>
                fiber.join.map {
                  case HandoffSignal.Emit(last)  => ZChannel.unwrap(enqueue(last))
                  case HandoffSignal.Halt(error) => ZChannel.refailCause(error)
                  case HandoffSignal.End(_)      => ZChannel.unit
                }
              case Previous(fiber) =>
                handoff.take.forkIn(scope).flatMap { handoffFib =>
                  fiber.join
                    .raceWith[R, E, E, HandoffSignal[E, A], ZChannel[
                      R,
                      Any,
                      Any,
                      Any,
                      E,
                      Chunk[A],
                      Any
                    ]](
                      handoffFib.join
                    )(
                      {
                        case (Exit.Success(a), current) =>
                          current.interrupt *> ZIO.succeed(ZChannel.write(a) *> consumer(Current(handoffFib)))
                        case (Exit.Failure(cause), current) =>
                          current.interrupt as ZChannel.refailCause(cause)
                      },
                      {
                        case (Exit.Success(Emit(last)), previous) =>
                          previous.interrupt *> enqueue(last)
                        case (Exit.Success(Halt(cause)), previous) =>
                          previous.interrupt as ZChannel.refailCause(cause)
                        case (Exit.Success(End(_)), previous) =>
                          previous.join.map(ZChannel.write(_) *> ZChannel.unit)
                        case (Exit.Failure(cause), previous) =>
                          previous.interrupt as ZChannel.refailCause(cause)
                      }
                    )
                }
            }
          )

        ZStream.scopedWith(scope => (self.channel >>> producer).runIn(scope).forkIn(scope)) *>
          new ZStream(consumer(NotStarted))
      }
    }
  }

  /**
   * Taps the stream, printing the result of calling `.toString` on the emitted
   * values.
   */
  def debug(implicit trace: Trace): ZStream[R, E, A] =
    self
      .tap(a => ZIO.debug(a))
      .tapErrorCause(e => ZIO.debug(s"<FAIL>: $e"))

  /**
   * Taps the stream, printing the result of calling `.toString` on the emitted
   * values. Prefixes the output with the given label.
   */
  def debug(label: String)(implicit trace: Trace): ZStream[R, E, A] =
    self
      .tap(a => ZIO.debug(s"$label: $a"))
      .tapErrorCause(e => ZIO.debug(s"<FAIL> $label: $e"))

  /**
   * Creates a pipeline that groups on adjacent keys, calculated by function f.
   */
  def groupAdjacentBy[K](
    f: A => K
  )(implicit trace: Trace): ZStream[R, E, (K, NonEmptyChunk[A])] =
    self >>> ZPipeline.groupAdjacentBy(f)

  /**
   * Performs an effectful filter and map in a single step.
   */
  def collectZIO[R1 <: R, E1 >: E, A1](pf: PartialFunction[A, ZIO[R1, E1, A1]])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] = {

    def loop(chunkIterator: Chunk.ChunkIterator[A], index: Int): ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A1], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          pf.andThen(_.map(a1 => ZChannel.write(Chunk.single(a1)) *> loop(chunkIterator, index + 1)))
            .applyOrElse(a, (_: A) => ZIO.succeed(loop(chunkIterator, index + 1)))
        }
      else
        ZChannel.readWithCause(
          elem => loop(elem.chunkIterator, 0),
          err => ZChannel.refailCause(err),
          done => ZChannel.succeed(done)
        )

    new ZStream(self.channel >>> loop(Chunk.ChunkIterator.empty, 0))
  }

  /**
   * Transforms all elements of the stream for as long as the specified partial
   * function is defined.
   */
  def collectWhile[A1](pf: PartialFunction[A, A1])(implicit trace: Trace): ZStream[R, E, A1] =
    self >>> ZPipeline.collectWhile(pf)

  /**
   * Terminates the stream when encountering the first `Right`.
   */
  def collectWhileLeft[L1, A1](implicit ev: A <:< Either[L1, A1], trace: Trace): ZStream[R, E, L1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]].collectWhile { case Left(a) => a }
  }

  /**
   * Terminates the stream when encountering the first `None`.
   */
  def collectWhileSome[A1](implicit ev: A <:< Option[A1], trace: Trace): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Option[A1]]].collectWhile { case Some(a) => a }
  }

  /**
   * Terminates the stream when encountering the first `Left`.
   */
  def collectWhileRight[L1, A1](implicit ev: A <:< Either[L1, A1], trace: Trace): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]].collectWhile { case Right(a) => a }
  }

  /**
   * Terminates the stream when encountering the first `Exit.Failure`.
   */
  def collectWhileSuccess[L1, A1](implicit ev: A <:< Exit[L1, A1], trace: Trace): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Exit[L1, A1]]].collectWhile { case Exit.Success(a) => a }
  }

  /**
   * Effectfully transforms all elements of the stream for as long as the
   * specified partial function is defined.
   */
  def collectWhileZIO[R1 <: R, E1 >: E, A1](
    pf: PartialFunction[A, ZIO[R1, E1, A1]]
  )(implicit trace: Trace): ZStream[R1, E1, A1] =
    self >>> ZPipeline.collectWhileZIO(pf)

  /**
   * Combines the elements from this stream and the specified stream by
   * repeatedly applying the function `f` to extract an element using both sides
   * and conceptually "offer" it to the destination stream. `f` can maintain
   * some internal state to control the combining process, with the initial
   * state being specified by `s`.
   *
   * Where possible, prefer [[ZStream#combineChunks]] for a more efficient
   * implementation.
   */
  def combine[R1 <: R, E1 >: E, S, A2, A3](that: => ZStream[R1, E1, A2])(s: => S)(
    f: (S, ZIO[R, Option[E], A], ZIO[R1, Option[E1], A2]) => ZIO[R1, Nothing, Exit[Option[E1], (A3, S)]]
  )(implicit trace: Trace): ZStream[R1, E1, A3] = {
    def producer[Err, Elem](
      handoff: ZStream.Handoff[Exit[Option[Err], Elem]],
      latch: ZStream.Handoff[Unit]
    ): ZChannel[R1, Err, Elem, Any, Nothing, Nothing, Any] =
      ZChannel.fromZIO(latch.take) *>
        ZChannel.readWithCause[R1, Err, Elem, Any, Nothing, Nothing, Any](
          value => ZChannel.fromZIO(handoff.offer(Exit.succeed(value))) *> producer(handoff, latch),
          cause => ZChannel.fromZIO(handoff.offer(Exit.failCause(cause.map(Some(_))))),
          _ => ZChannel.fromZIO(handoff.offer(Exit.failNone)) *> producer(handoff, latch)
        )

    new ZStream(
      ZChannel.unwrapScopedWith { scope =>
        for {
          left     <- ZStream.Handoff.make[Exit[Option[E], A]]
          right    <- ZStream.Handoff.make[Exit[Option[E1], A2]]
          latchL   <- ZStream.Handoff.make[Unit]
          latchR   <- ZStream.Handoff.make[Unit]
          _        <- (self.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(left, latchL)).runIn(scope).forkIn(scope)
          _        <- (that.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(right, latchR)).runIn(scope).forkIn(scope)
          pullLeft  = latchL.offer(()) *> left.take.flatMap(ZIO.done(_))
          pullRight = latchR.offer(()) *> right.take.flatMap(ZIO.done(_))
        } yield ZStream.unfoldZIO(s)(s => f(s, pullLeft, pullRight).flatMap(ZIO.done(_).unsome)).channel
      }
    )
  }

  /**
   * Combines the chunks from this stream and the specified stream by repeatedly
   * applying the function `f` to extract a chunk using both sides and
   * conceptually "offer" it to the destination stream. `f` can maintain some
   * internal state to control the combining process, with the initial state
   * being specified by `s`.
   */
  def combineChunks[R1 <: R, E1 >: E, S, A2, A3](that: => ZStream[R1, E1, A2])(s: => S)(
    f: (
      S,
      ZIO[R, Option[E], Chunk[A]],
      ZIO[R1, Option[E1], Chunk[A2]]
    ) => ZIO[R1, Nothing, Exit[Option[E1], (Chunk[A3], S)]]
  )(implicit trace: Trace): ZStream[R1, E1, A3] = {
    def producer[Err, Elem](
      handoff: ZStream.Handoff[Take[Err, Elem]],
      latch: ZStream.Handoff[Unit]
    ): ZChannel[R1, Err, Chunk[Elem], Any, Nothing, Nothing, Any] =
      ZChannel.fromZIO(latch.take) *>
        ZChannel.readWithCause[R1, Err, Chunk[Elem], Any, Nothing, Nothing, Any](
          chunk => ZChannel.fromZIO(handoff.offer(Take.chunk(chunk))) *> producer(handoff, latch),
          cause => ZChannel.fromZIO(handoff.offer(Take.failCause(cause))),
          _ => ZChannel.fromZIO(handoff.offer(Take.end))
        )

    new ZStream(
      ZChannel.unwrapScopedWith { scope =>
        for {
          left     <- ZStream.Handoff.make[Take[E, A]]
          right    <- ZStream.Handoff.make[Take[E1, A2]]
          latchL   <- ZStream.Handoff.make[Unit]
          latchR   <- ZStream.Handoff.make[Unit]
          _        <- (self.channel >>> producer(left, latchL)).runIn(scope).forkIn(scope)
          _        <- (that.channel >>> producer(right, latchR)).runIn(scope).forkIn(scope)
          pullLeft  = latchL.offer(()) *> left.take.flatMap(_.done)
          pullRight = latchR.offer(()) *> right.take.flatMap(_.done)
        } yield ZStream.unfoldChunkZIO(s)(s => f(s, pullLeft, pullRight).flatMap(ZIO.done(_).unsome)).channel
      }
    )
  }

  /**
   * Concatenates the specified stream with this stream, resulting in a stream
   * that emits the elements from this stream and then the elements from the
   * specified stream.
   */
  def concat[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    new ZStream(channel *> that.channel)

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements. The `that` stream would be run multiple times, for
   * every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  def cross[R1 <: R, E1 >: E, B](that: => ZStream[R1, E1, B])(implicit
    zippable: Zippable[A, B],
    trace: Trace
  ): ZStream[R1, E1, zippable.Out] =
    crossWith(that)((a, b) => zippable.zip(a, b))

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements, but keeps only elements from this stream. The `that`
   * stream would be run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  def crossLeft[R1 <: R, E1 >: E, B](that: => ZStream[R1, E1, B])(implicit
    trace: Trace
  ): ZStream[R1, E1, A] =
    crossWith(that)((a, _) => a)

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements, but keeps only elements from the other stream. The
   * `that` stream would be run multiple times, for every element in the `this`
   * stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  def crossRight[R1 <: R, E1 >: E, B](that: => ZStream[R1, E1, B])(implicit trace: Trace): ZStream[R1, E1, B] =
    self.flatMap(_ => that)

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements with a specified function. The `that` stream would be
   * run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  def crossWith[R1 <: R, E1 >: E, A2, C](that: => ZStream[R1, E1, A2])(f: (A, A2) => C)(implicit
    trace: Trace
  ): ZStream[R1, E1, C] =
    self.flatMap(l => that.map(r => f(l, r)))

  /**
   * More powerful version of `ZStream#broadcast`. Allows to provide a function
   * that determines what queues should receive which elements. The decide
   * function will receive the indices of the queues in the resulting list.
   */
  def distributedWith[E1 >: E](
    n: => Int,
    maximumLag: => Int,
    decide: A => UIO[Int => Boolean]
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, List[Dequeue[Exit[Option[E1], A]]]] =
    Promise.make[Nothing, A => UIO[UniqueKey => Boolean]].flatMap { prom =>
      distributedWithDynamic(maximumLag, (a: A) => prom.await.flatMap(_(a)), _ => ZIO.unit).flatMap { next =>
        ZIO.collectAll {
          Range(0, n).map(id => next.map { case (key, queue) => ((key -> id), queue) })
        }.flatMap { entries =>
          val (mappings, queues) =
            entries.foldRight((Map.empty[UniqueKey, Int], List.empty[Dequeue[Exit[Option[E1], A]]])) {
              case ((mapping, queue), (mappings, queues)) =>
                (mappings + mapping, queue :: queues)
            }
          prom.succeed((a: A) => decide(a).map(f => (key: UniqueKey) => f(mappings(key)))).as(queues)
        }
      }
    }

  /**
   * More powerful version of `ZStream#distributedWith`. This returns a function
   * that will produce new queues and corresponding indices. You can also
   * provide a function that will be executed after the final events are
   * enqueued in all queues. Shutdown of the queues is handled by the driver.
   * Downstream users can also shutdown queues manually. In this case the driver
   * will continue but no longer backpressure on them.
   */
  def distributedWithDynamic(
    maximumLag: => Int,
    decide: A => UIO[UniqueKey => Boolean],
    done: Exit[Option[E], Nothing] => UIO[Any] = (_: Any) => ZIO.unit
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, UIO[(UniqueKey, Dequeue[Exit[Option[E], A]])]] =
    for {
      queuesRef <- ZIO.acquireReleaseExit(Ref.make[Map[UniqueKey, Queue[Exit[Option[E], A]]]](Map()))((map, exit) =>
                     map.get.flatMap(qs => ZIO.foreach(qs.values)(_.shutdown))
                   )
      add <- {
        val offer = (a: A) =>
          for {
            shouldProcess <- decide(a)
            queues        <- queuesRef.get
            _ <- ZIO
                   .foldLeft(queues)(List[UniqueKey]()) { case (acc, (id, queue)) =>
                     if (shouldProcess(id)) {
                       queue
                         .offer(Exit.succeed(a))
                         .foldCauseZIO(
                           {
                             // we ignore all downstream queues that were shut down and remove them later
                             case c if c.isInterrupted => ZIO.succeed(id :: acc)
                             case c                    => Exit.failCause(c)
                           },
                           _ => ZIO.succeed(acc)
                         )
                     } else ZIO.succeed(acc)
                   }
                   .flatMap(ids => if (ids.nonEmpty) queuesRef.update(_ -- ids) else ZIO.unit)
          } yield ()

        for {
          queuesLock <- Semaphore.make(1)
          newQueue <- Ref
                        .make[UIO[(UniqueKey, Queue[Exit[Option[E], A]])]] {
                          for {
                            queue <- Queue.bounded[Exit[Option[E], A]](maximumLag)
                            id     = UniqueKey()
                            _     <- queuesRef.update(_.updated(id, queue))
                          } yield (id, queue)
                        }
          finalize = (endTake: Exit[Option[E], Nothing]) =>
                       // we need to make sure that no queues are currently being added
                       queuesLock.withPermit {
                         for {
                           // all newly created queues should end immediately
                           _ <- newQueue.set {
                                  for {
                                    queue <- Queue.bounded[Exit[Option[E], A]](1)
                                    _     <- queue.offer(endTake)
                                    id     = UniqueKey()
                                    _     <- queuesRef.update(_.updated(id, queue))
                                  } yield (id, queue)
                                }
                           queues <- queuesRef.get.map(_.values)
                           _ <- ZIO.foreach(queues) { queue =>
                                  queue.offer(endTake).catchSomeCause {
                                    case c if c.isInterrupted => ZIO.unit
                                  }
                                }
                           _ <- done(endTake)
                         } yield ()
                       }
          _ <- self
                 .runForeachScoped(offer)
                 .foldCauseZIO(
                   cause => finalize(Exit.failCause(cause.map(Some(_)))),
                   _ => finalize(Exit.failNone)
                 )
                 .forkScoped
        } yield queuesLock.withPermit(newQueue.get.flatten)
      }
    } yield add

  /**
   * Converts this stream to a stream that executes its effects but emits no
   * elements. Useful for sequencing effects using streams:
   *
   * {{{
   * (Stream(1, 2, 3).tap(i => ZIO(println(i))) ++
   *   Stream.fromZIO(ZIO(println("Done!"))).drain ++
   *   Stream(4, 5, 6).tap(i => ZIO(println(i)))).run(Sink.drain)
   * }}}
   */
  def drain(implicit trace: Trace): ZStream[R, E, Nothing] =
    new ZStream(channel.drain)

  /**
   * Drains the provided stream in the background for as long as this stream is
   * running. If this stream ends before `other`, `other` will be interrupted.
   * If `other` fails, this stream will fail with that error.
   */
  def drainFork[R1 <: R, E1 >: E](
    other: => ZStream[R1, E1, Any]
  )(implicit trace: Trace): ZStream[R1, E1, A] =
    ZStream.fromZIO(Promise.make[E1, Nothing]).flatMap { bgDied =>
      ZStream
        .scopedWith(scope => other.channel.drain.runIn(scope).catchAllCause(bgDied.failCause(_)).forkIn(scope)) *>
        self.interruptWhen(bgDied)
    }

  /**
   * Drops the specified number of elements from this stream.
   */
  def drop(n: => Int)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.drop(n)

  /**
   * Drops the last specified number of elements from this stream.
   *
   * @note
   *   This combinator keeps `n` elements in memory. Be careful with big
   *   numbers.
   */
  def dropRight(n: => Int)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.dropRight(n)

  /**
   * Drops all elements of the stream for as long as the specified predicate
   * evaluates to `true`.
   */
  def dropWhile(f: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.dropWhile(f)

  /**
   * Drops all elements of the stream until the specified predicate evaluates to
   * `true`.
   */
  def dropUntil(pred: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.dropUntil(pred)

  /**
   * Drops all elements of the stream for as long as the specified predicate
   * produces an effect that evalutates to `true`
   *
   * @see
   *   [[dropWhile]]
   */
  def dropWhileZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: Trace
  ): ZStream[R1, E1, A] =
    pipeThrough(ZSink.dropWhileZIO[R1, E1, A](f))

  /**
   * Returns a stream whose failures and successes have been lifted into an
   * `Either`. The resulting stream cannot fail, because the failures have been
   * exposed as part of the `Either` success case.
   *
   * @note
   *   the stream will end as soon as the first error occurs.
   */
  def either(implicit ev: CanFail[E], trace: Trace): ZStream[R, Nothing, Either[E, A]] =
    self.map(Right(_)).catchAll(e => ZStream(Left(e)))

  /**
   * Executes the provided finalizer after this stream's finalizers run.
   */
  def ensuring[R1 <: R](fin: => ZIO[R1, Nothing, Any])(implicit trace: Trace): ZStream[R1, E, A] =
    new ZStream(channel.ensuring(fin))

  /**
   * Executes the provided finalizer after this stream's finalizers run.
   */
  def ensuringWith[R1 <: R](fin: Exit[E, Any] => ZIO[R1, Nothing, Any])(implicit trace: Trace): ZStream[R1, E, A] =
    new ZStream(channel.ensuringWith(fin))

  /**
   * Filters the elements emitted by this stream using the provided function.
   */
  def filter(f: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    mapChunks(_.filter(f))

  /**
   * Finds the first element emitted by this stream that satisfies the provided
   * predicate.
   */
  def find(f: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] = {
    lazy val loop: ZChannel[R, E, Chunk[A], Any, E, Chunk[A], Any] =
      ZChannel.readWithCause(
        (in: Chunk[A]) => in.find(f).fold(loop)(i => ZChannel.write(Chunk.single(i))),
        (e: Cause[E]) => ZChannel.refailCause(e),
        (_: Any) => ZChannel.unit
      )

    new ZStream(self.channel >>> loop)
  }

  /**
   * Finds the first element emitted by this stream that satisfies the provided
   * effectful predicate.
   */
  def findZIO[R1 <: R, E1 >: E, S](
    f: A => ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): ZStream[R1, E1, A] = {
    lazy val loop: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A], Any] =
      ZChannel.readWithCause(
        (in: Chunk[A]) => ZChannel.unwrap(in.findZIO(f).map(_.fold(loop)(i => ZChannel.write(Chunk.single(i))))),
        (e: Cause[E]) => ZChannel.refailCause(e),
        (_: Any) => ZChannel.unit
      )

    new ZStream(self.channel >>> loop)
  }

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  def foreach[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: Trace): ZIO[R1, E1, Unit] =
    runForeach(f)

  /**
   * Repeats this stream forever.
   */
  def forever(implicit trace: Trace): ZStream[R, E, A] =
    new ZStream(channel.repeated)

  /**
   * Effectfully filters the elements emitted by this stream.
   */
  def filterZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit trace: Trace): ZStream[R1, E1, A] = {

    def loop(chunkIterator: Chunk.ChunkIterator[A], index: Int): ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          f(a).map { b =>
            if (b) ZChannel.write(Chunk.single(a)) *> loop(chunkIterator, index + 1)
            else loop(chunkIterator, index + 1)
          }
        }
      else
        ZChannel.readWithCause(
          elem => loop(elem.chunkIterator, 0),
          err => ZChannel.refailCause(err),
          done => ZChannel.succeed(done)
        )

    new ZStream(self.channel >>> loop(Chunk.ChunkIterator.empty, 0))
  }

  /**
   * Filters this stream by the specified predicate, removing all elements for
   * which the predicate evaluates to true.
   */
  def filterNot(pred: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] = filter(a => !pred(a))

  /**
   * Returns a stream made of the concatenation in strict order of all the
   * streams produced by passing each element of this stream to `f0`
   */
  def flatMap[R1 <: R, E1 >: E, B](f: A => ZStream[R1, E1, B])(implicit
    trace: Trace
  ): ZStream[R1, E1, B] =
    new ZStream(
      channel.concatMap(as =>
        as.foldLeft[ZChannel[R1, Any, Any, Any, E1, zio.Chunk[B], Any]](ZChannel.unit) { case (acc, a) =>
          acc *> f(a).channel
        }
      )
    )

  /**
   * Maps each element of this stream to another stream and returns the
   * non-deterministic merge of those streams, executing up to `n` inner streams
   * concurrently. Up to `bufferSize` elements of the produced streams may be
   * buffered in memory by this operator.
   */
  def flatMapPar[R1 <: R, E1 >: E, B](n: => Int, bufferSize: => Int = 16)(
    f: A => ZStream[R1, E1, B]
  )(implicit trace: Trace): ZStream[R1, E1, B] =
    new ZStream[R1, E1, B](
      channel.concatMap(ZChannel.writeChunk(_)).mergeMap[R1, Any, Any, Any, E1, Chunk[B]](n, bufferSize) {
        f(_).channel
      }
    )

  /**
   * Maps each element of this stream to another stream and returns the
   * non-deterministic merge of those streams, executing up to `n` inner streams
   * concurrently. When a new stream is created from an element of the source
   * stream, the oldest executing stream is cancelled. Up to `bufferSize`
   * elements of the produced streams may be buffered in memory by this
   * operator.
   */
  def flatMapParSwitch[R1 <: R, E1 >: E, B](n: => Int, bufferSize: => Int = 16)(
    f: A => ZStream[R1, E1, B]
  )(implicit trace: Trace): ZStream[R1, E1, B] =
    new ZStream[R1, E1, B](
      channel
        .concatMap(ZChannel.writeChunk(_))
        .mergeMap[R1, Any, Any, Any, E1, Chunk[B]](n, bufferSize, ZChannel.MergeStrategy.BufferSliding) {
          f(_).channel
        }
    )

  /**
   * Flattens this stream-of-streams into a stream made of the concatenation in
   * strict order of all the streams.
   */
  def flatten[R1 <: R, E1 >: E, A1](implicit ev: A <:< ZStream[R1, E1, A1], trace: Trace): ZStream[R1, E1, A1] =
    flatMap(ev(_))

  /**
   * Submerges the chunks carried by this stream into the stream's structure,
   * while still preserving them.
   */
  def flattenChunks[A1](implicit ev: A <:< Chunk[A1], trace: Trace): ZStream[R, E, A1] = {

    lazy val flatten: ZChannel[Any, E, Chunk[Chunk[A1]], Any, E, Chunk[A1], Any] =
      ZChannel.readWithCause(
        chunks => ZChannel.writeChunk(chunks) *> flatten,
        cause => ZChannel.refailCause(cause),
        _ => ZChannel.unit
      )

    new ZStream(self.asInstanceOf[ZStream[R, E, Chunk[A1]]].channel >>> flatten)
  }

  /**
   * Flattens [[Exit]] values. `Exit.Failure` values translate to stream
   * failures while `Exit.Success` values translate to stream elements.
   */
  def flattenExit[E1 >: E, A1](implicit ev: A <:< Exit[E1, A1], trace: Trace): ZStream[R, E1, A1] =
    mapZIO(a => ZIO.done(ev(a)))

  /**
   * Unwraps [[Exit]] values that also signify end-of-stream by failing with
   * `None`.
   *
   * For `Exit[E, A]` values that do not signal end-of-stream, prefer:
   * {{{
   * stream.mapZIO(ZIO.done(_))
   * }}}
   */
  def flattenExitOption[E1 >: E, A1](implicit
    ev: A <:< Exit[Option[E1], A1],
    trace: Trace
  ): ZStream[R, E1, A1] = {
    def processChunk(
      chunk: Chunk[Exit[Option[E1], A1]],
      cont: ZChannel[R, E, Chunk[Exit[Option[E1], A1]], Any, E1, Chunk[A1], Any]
    ): ZChannel[R, E, Chunk[Exit[Option[E1], A1]], Any, E1, Chunk[A1], Any] = {
      val (toEmit, rest) = chunk.splitWhere(!_.isSuccess)
      val next = rest.headOption match {
        case Some(Exit.Success(_)) => ZChannel.unit
        case Some(Exit.Failure(cause)) =>
          Cause.flipCauseOption(cause) match {
            case Some(cause) => ZChannel.refailCause(cause)
            case None        => ZChannel.unit
          }
        case None => cont
      }
      ZChannel.write(toEmit.collect { case Exit.Success(a) => a }) *> next
    }

    lazy val process: ZChannel[R, E, Chunk[Exit[Option[E1], A1]], Any, E1, Chunk[A1], Any] =
      ZChannel.readWithCause[R, E, Chunk[Exit[Option[E1], A1]], Any, E1, Chunk[A1], Any](
        chunk => processChunk(chunk, process),
        cause => ZChannel.refailCause(cause),
        _ => ZChannel.unit
      )

    new ZStream(channel.asInstanceOf[ZChannel[R, Any, Any, Any, E, Chunk[Exit[Option[E1], A1]], Any]] >>> process)
  }

  /**
   * Submerges the iterables carried by this stream into the stream's structure,
   * while still preserving them.
   */
  def flattenIterables[A1](implicit ev: A <:< Iterable[A1], trace: Trace): ZStream[R, E, A1] =
    map(a => Chunk.fromIterable(ev(a))).flattenChunks

  /**
   * Flattens a stream of streams into a stream by executing a non-deterministic
   * concurrent merge. Up to `n` streams may be consumed in parallel and up to
   * `outputBuffer` elements may be buffered by this operator.
   */
  def flattenPar[R1 <: R, E1 >: E, A1](n: => Int, outputBuffer: => Int = 16)(implicit
    ev: A <:< ZStream[R1, E1, A1],
    trace: Trace
  ): ZStream[R1, E1, A1] =
    flatMapPar[R1, E1, A1](n, outputBuffer)(ev(_))

  /**
   * Like [[flattenPar]], but executes all streams concurrently.
   */
  def flattenParUnbounded[R1 <: R, E1 >: E, A1](
    outputBuffer: => Int = 16
  )(implicit ev: A <:< ZStream[R1, E1, A1], trace: Trace): ZStream[R1, E1, A1] =
    flattenPar[R1, E1, A1](Int.MaxValue, outputBuffer)

  /**
   * Unwraps [[Exit]] values and flatten chunks that also signify end-of-stream
   * by failing with `None`.
   */
  def flattenTake[E1 >: E, A1](implicit ev: A <:< Take[E1, A1], trace: Trace): ZStream[R, E1, A1] =
    self.asInstanceOf[ZStream[R, E, Take[E1, A1]]] >>> ZPipeline.flattenTake

  def flattenZIO[R1 <: R, E1 >: E, A1](implicit ev: A <:< ZIO[R1, E1, A1], trace: Trace): ZStream[R1, E1, A1] =
    mapZIO(ev)

  /**
   * More powerful version of [[ZStream.groupByKey]]
   */
  def groupBy[R1 <: R, E1 >: E, K, V](
    f: A => ZIO[R1, E1, (K, V)],
    buffer: => Int = 16
  ): ZStream.GroupBy[R1, E1, K, V] =
    new ZStream.GroupBy[R1, E1, K, V] {
      def grouped(implicit trace: Trace): ZStream[R1, E1, (K, Dequeue[Take[E1, V]])] =
        ZStream.unwrapScoped[R1] {
          for {
            decider <- Promise.make[Nothing, (K, V) => UIO[UniqueKey => Boolean]]
            out <-
              ZIO.acquireRelease(Queue.bounded[Exit[Option[E1], (K, Dequeue[Take[E1, V]])]](buffer))(_.shutdown)
            ref <- Ref.make[Map[K, UniqueKey]](Map())
            add <- self
                     .mapZIO(f)
                     .distributedWithDynamic(
                       buffer,
                       (kv: (K, V)) => decider.await.flatMap(_.tupled(kv)),
                       out.offer
                     )
            _ <- decider.succeed { case (k, _) =>
                   ref.get.map(_.get(k)).flatMap {
                     case Some(idx) => ZIO.succeed(_ == idx)
                     case None =>
                       add.flatMap { case (idx, q) =>
                         (ref.update(_.updated(k, idx)) *>
                           out.offer(
                             Exit.succeed(
                               k -> ZStream.mapDequeue(q)(exit =>
                                 Take(exit.mapExit { case (_, value) => Chunk.single(value) })
                               )
                             )
                           )).as(_ == idx)
                       }
                   }
                 }
          } yield ZStream.fromQueueWithShutdown(out).flattenExitOption
        }
    }

  /**
   * Partition a stream using a function and process each stream individually.
   * This returns a data structure that can be used to further filter down which
   * groups shall be processed.
   *
   * After calling apply on the GroupBy object, the remaining groups will be
   * processed in parallel and the resulting streams merged in a
   * nondeterministic fashion.
   *
   * Up to `buffer` elements may be buffered in any group stream before the
   * producer is backpressured. Take care to consume from all streams in order
   * to prevent deadlocks.
   *
   * Example: Collect the first 2 words for every starting letter from a stream
   * of words.
   * {{{
   * ZStream.fromIterable(List("hello", "world", "hi", "holla"))
   *   .groupByKey(_.head) { case (k, s) => s.take(2).map((k, _)) }
   *   .runCollect
   *   .map(_ == List(('h', "hello"), ('h', "hi"), ('w', "world"))
   * }}}
   */
  def groupByKey[K](
    f: A => K,
    buffer: => Int = 16
  ): ZStream.GroupBy[R, E, K, A] =
    new ZStream.GroupBy[R, E, K, A] {
      def grouped(implicit trace: Trace): ZStream[R, E, (K, Dequeue[Take[E, A]])] = {

        def groupByKey(
          map: mutable.Map[K, Queue[Take[E, A]]],
          outerQueue: Queue[Take[E, (K, Queue[Take[E, A]])]]
        ): ZChannel[R, E, Chunk[A], Any, E, Any, Any] =
          ZChannel.readWithCause(
            in =>
              ZChannel.fromZIO {
                ZIO.foreachDiscard(ZStream.groupBy(in)(f)) { case (key, values) =>
                  map.get(key) match {
                    case Some(innerQueue) =>
                      innerQueue.offer(Take.chunk(values)).catchSomeCause {
                        case cause if cause.isInterruptedOnly => ZIO.unit
                      }
                    case None =>
                      Queue.bounded[Take[E, A]](buffer).flatMap { innerQueue =>
                        ZIO.succeed(map += key -> innerQueue) *>
                          outerQueue.offer(Take.single(key -> innerQueue)) *>
                          innerQueue.offer(Take.chunk(values)).catchSomeCause {
                            case cause if cause.isInterruptedOnly => ZIO.unit
                          }
                      }
                  }
                }
              } *> groupByKey(map, outerQueue),
            e => ZChannel.fromZIO(outerQueue.offer(Take.failCause(e))),
            _ =>
              ZChannel.fromZIO {
                ZIO.foreachDiscard(map) { case (_, innerQueue) =>
                  innerQueue.offer(Take.end).catchSomeCause { case cause if cause.isInterruptedOnly => ZIO.unit }
                } *> outerQueue.offer(Take.end)
              }
          )

        ZStream.unwrapScopedWith { scope =>
          for {
            map   <- ZIO.succeed(mutable.Map.empty[K, Queue[Take[E, A]]])
            queue <- Queue.unbounded[Take[E, (K, Queue[Take[E, A]])]]
            _     <- scope.addFinalizer(queue.shutdown)
            _     <- (self.channel >>> groupByKey(map, queue)).drain.runIn(scope).forkIn(scope)
          } yield ZStream.fromQueueWithShutdown(queue).flattenTake
        }
      }
    }

  /**
   * Partitions the stream with specified chunkSize
   * @param chunkSize
   *   size of the chunk
   */
  def grouped(chunkSize: => Int)(implicit trace: Trace): ZStream[R, E, Chunk[A]] =
    self >>> ZPipeline.grouped(chunkSize)

  /**
   * Partitions the stream with the specified chunkSize or until the specified
   * duration has passed, whichever is satisfied first.
   */
  def groupedWithin(chunkSize: => Int, within: => Duration)(implicit
    trace: Trace
  ): ZStream[R, E, Chunk[A]] =
    aggregateAsyncWithin(ZSink.collectAllN[A](chunkSize), Schedule.spaced(within))

  /**
   * Halts the evaluation of this stream when the provided IO completes. The
   * given IO will be forked as part of the returned stream, and its success
   * will be discarded.
   *
   * An element in the process of being pulled will not be interrupted when the
   * IO completes. See `interruptWhen` for this behavior.
   *
   * If the IO completes with a failure, the stream will emit that failure.
   */
  def haltWhen[R1 <: R, E1 >: E](io: ZIO[R1, E1, Any])(implicit trace: Trace): ZStream[R1, E1, A] = {
    def writer(fiber: Fiber[E1, Any]): ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[A], Unit] =
      ZChannel.unwrap {
        fiber.poll.map {
          case None =>
            ZChannel.readWith[R1, E1, Chunk[A], Any, E1, Chunk[A], Unit](
              in => ZChannel.write(in) *> writer(fiber),
              err => ZChannel.fail(err),
              _ => ZChannel.unit
            )

          case Some(exit) =>
            exit.foldExit(ZChannel.refailCause, _ => ZChannel.unit)
        }
      }

    new ZStream(
      ZChannel.unwrapScopedWith { scope =>
        io.forkIn(scope).map { fiber =>
          self.channel >>> writer(fiber)
        }
      }
    )
  }

  /**
   * Specialized version of haltWhen which halts the evaluation of this stream
   * after the given duration.
   *
   * An element in the process of being pulled will not be interrupted when the
   * given duration completes. See `interruptAfter` for this behavior.
   */
  def haltAfter(duration: => Duration)(implicit trace: Trace): ZStream[R, E, A] =
    haltWhen(Clock.sleep(duration))

  /**
   * Halts the evaluation of this stream when the provided promise resolves.
   *
   * If the promise completes with a failure, the stream will emit that failure.
   */
  def haltWhen[E1 >: E](p: Promise[E1, _])(implicit trace: Trace): ZStream[R, E1, A] = {
    lazy val writer: ZChannel[R, E1, Chunk[A], Any, E1, Chunk[A], Unit] =
      ZChannel.unwrap {
        p.poll.map {
          case None =>
            ZChannel.readWith[R, E1, Chunk[A], Any, E1, Chunk[A], Unit](
              in => ZChannel.write(in) *> writer,
              err => ZChannel.fail(err),
              _ => ZChannel.unit
            )

          case Some(io) =>
            ZChannel.unwrap(io.fold(ZChannel.fail(_), _ => ZChannel.unit))
        }
      }

    new ZStream(self.channel >>> writer)
  }

  /**
   * Interleaves this stream and the specified stream deterministically by
   * alternating pulling values from this stream and the specified stream. When
   * one stream is exhausted all remaining values in the other stream will be
   * pulled.
   */
  def interleave[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    self.interleaveWith(that)(ZStream(true, false).forever)

  /**
   * Combines this stream and the specified stream deterministically using the
   * stream of boolean values `b` to control which stream to pull from next.
   * `true` indicates to pull from this stream and `false` indicates to pull
   * from the specified stream. Only consumes as many elements as requested by
   * `b`. If either this stream or the specified stream are exhausted further
   * requests for values from that stream will be ignored.
   */
  def interleaveWith[R1 <: R, E1 >: E, A1 >: A](
    that: => ZStream[R1, E1, A1]
  )(b: => ZStream[R1, E1, Boolean])(implicit trace: Trace): ZStream[R1, E1, A1] = {
    def producer(handoff: ZStream.Handoff[Take[E1, A1]]): ZChannel[R1, E1, A1, Any, Nothing, Nothing, Unit] =
      ZChannel.readWithCause[R1, E1, A1, Any, Nothing, Nothing, Unit](
        value => ZChannel.fromZIO(handoff.offer(Take.single(value))) *> producer(handoff),
        cause => ZChannel.fromZIO(handoff.offer(Take.failCause(cause))),
        _ => ZChannel.fromZIO(handoff.offer(Take.end))
      )

    new ZStream(
      ZChannel.unwrapScopedWith { scope =>
        for {
          left  <- ZStream.Handoff.make[Take[E1, A1]]
          right <- ZStream.Handoff.make[Take[E1, A1]]
          _     <- (self.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(left)).runIn(scope).forkIn(scope)
          _     <- (that.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(right)).runIn(scope).forkIn(scope)
        } yield {
          def process(leftDone: Boolean, rightDone: Boolean): ZChannel[R1, E1, Boolean, Any, E1, Chunk[A1], Unit] =
            ZChannel.readWithCause[R1, E1, Boolean, Any, E1, Chunk[A1], Unit](
              bool =>
                (bool, leftDone, rightDone) match {
                  case (true, false, _) =>
                    ZChannel.fromZIO(left.take).flatMap { take =>
                      take.fold(
                        if (rightDone) ZChannel.unit else process(true, rightDone),
                        cause => ZChannel.refailCause(cause),
                        chunk => ZChannel.write(chunk) *> process(leftDone, rightDone)
                      )
                    }
                  case (false, _, false) =>
                    ZChannel.fromZIO(right.take).flatMap { take =>
                      take.fold(
                        if (leftDone) ZChannel.unit else process(leftDone, true),
                        cause => ZChannel.refailCause(cause),
                        chunk => ZChannel.write(chunk) *> process(leftDone, rightDone)
                      )
                    }
                  case _ =>
                    process(leftDone, rightDone)
                },
              cause => ZChannel.refailCause(cause),
              _ => ZChannel.unit
            )

          b.channel.concatMap(ZChannel.writeChunk(_)) >>> process(false, false)
        }
      }
    )
  }

  /**
   * Intersperse stream with provided element similar to
   * <code>List.mkString</code>.
   */
  def intersperse[A1 >: A](middle: => A1)(implicit trace: Trace): ZStream[R, E, A1] =
    self >>> ZPipeline.intersperse(middle)

  /**
   * Intersperse and also add a prefix and a suffix
   */
  def intersperse[A1 >: A](start: => A1, middle: => A1, end: => A1)(implicit
    trace: Trace
  ): ZStream[R, E, A1] =
    self >>> ZPipeline.intersperse(start, middle, end)

  /**
   * Interrupts the evaluation of this stream when the provided IO completes.
   * The given IO will be forked as part of this stream, and its success will be
   * discarded. This combinator will also interrupt any in-progress element
   * being pulled from upstream.
   *
   * If the IO completes with a failure before the stream completes, the
   * returned stream will emit that failure.
   */
  def interruptWhen[R1 <: R, E1 >: E](io: ZIO[R1, E1, Any])(implicit trace: Trace): ZStream[R1, E1, A] =
    new ZStream(channel.interruptWhen(io))

  /**
   * Interrupts the evaluation of this stream when the provided promise
   * resolves. This combinator will also interrupt any in-progress element being
   * pulled from upstream.
   *
   * If the promise completes with a failure, the stream will emit that failure.
   */
  def interruptWhen[E1 >: E](p: Promise[E1, _])(implicit trace: Trace): ZStream[R, E1, A] =
    new ZStream(channel.interruptWhen(p.asInstanceOf[Promise[E1, Any]]))

  /**
   * Specialized version of interruptWhen which interrupts the evaluation of
   * this stream after the given duration.
   */
  def interruptAfter(duration: => Duration)(implicit trace: Trace): ZStream[R, E, A] =
    interruptWhen(Clock.sleep(duration))

  /**
   * Returns a combined string resulting from concatenating each of the values
   * from the stream
   */
  def mkString(implicit trace: Trace): ZIO[R, E, String] =
    run(ZSink.mkString)

  /**
   * Returns a combined string resulting from concatenating each of the values
   * from the stream beginning with `before` interspersed with `middle` and
   * ending with `after`.
   */
  def mkString(before: => String, middle: => String, after: => String)(implicit
    trace: Trace
  ): ZIO[R, E, String] =
    intersperse(before, middle, after).mkString

  /**
   * Transforms the elements of this stream using the supplied function.
   */
  def map[B](f: A => B)(implicit trace: Trace): ZStream[R, E, B] =
    new ZStream(channel.mapOut(_.map(f)))

  /**
   * Statefully maps over the elements of this stream to produce new elements.
   */
  def mapAccum[S, A1](s: => S)(f: (S, A) => (S, A1))(implicit trace: Trace): ZStream[R, E, A1] =
    ZStream.succeed(s).flatMap { s =>
      def accumulator(currS: S): ZChannel[Any, E, Chunk[A], Any, E, Chunk[A1], Unit] =
        ZChannel.readWithCause(
          (in: Chunk[A]) => {
            val (nextS, a1s) = in.mapAccum(currS)(f)
            ZChannel.write(a1s) *> accumulator(nextS)
          },
          (err: Cause[E]) => ZChannel.refailCause(err),
          (_: Any) => ZChannel.unit
        )

      new ZStream(self.channel >>> accumulator(s))
    }

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * new elements.
   */
  def mapAccumZIO[R1 <: R, E1 >: E, S, A1](
    s: => S
  )(f: (S, A) => ZIO[R1, E1, (S, A1)])(implicit trace: Trace): ZStream[R1, E1, A1] =
    self >>> ZPipeline.mapAccumZIO(s)(f)

  /**
   * Returns a stream whose failure and success channels have been mapped by the
   * specified pair of functions, `f` and `g`.
   */
  def mapBoth[E1, A1](f: E => E1, g: A => A1)(implicit ev: CanFail[E], trace: Trace): ZStream[R, E1, A1] =
    mapError(f).map(g)

  /**
   * Transforms the chunks emitted by this stream.
   */
  def mapChunks[A2](f: Chunk[A] => Chunk[A2])(implicit trace: Trace): ZStream[R, E, A2] =
    new ZStream(channel.mapOut(f))

  /**
   * Effectfully transforms the chunks emitted by this stream.
   */
  def mapChunksZIO[R1 <: R, E1 >: E, A2](f: Chunk[A] => ZIO[R1, E1, Chunk[A2]])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    new ZStream(channel.mapOutZIO(f))

  /**
   * Maps each element to an iterable, and flattens the iterables into the
   * output of this stream.
   */
  def mapConcat[A2](f: A => Iterable[A2])(implicit trace: Trace): ZStream[R, E, A2] =
    mapConcatChunk(a => Chunk.fromIterable(f(a)))

  /**
   * Maps each element to a chunk, and flattens the chunks into the output of
   * this stream.
   */
  def mapConcatChunk[A2](f: A => Chunk[A2])(implicit trace: Trace): ZStream[R, E, A2] =
    mapChunks(_.flatMap(f))

  /**
   * Effectfully maps each element to a chunk, and flattens the chunks into the
   * output of this stream.
   */
  def mapConcatChunkZIO[R1 <: R, E1 >: E, A2](f: A => ZIO[R1, E1, Chunk[A2]])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    mapZIO(f).mapConcatChunk(identity)

  /**
   * Effectfully maps each element to an iterable, and flattens the iterables
   * into the output of this stream.
   */
  def mapConcatZIO[R1 <: R, E1 >: E, A2](f: A => ZIO[R1, E1, Iterable[A2]])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    mapZIO(a => f(a).map(Chunk.fromIterable(_))).mapConcatChunk(identity)

  /**
   * Transforms the errors emitted by this stream using `f`.
   */
  def mapError[E2](f: E => E2)(implicit trace: Trace): ZStream[R, E2, A] =
    new ZStream(self.channel.mapError(f))

  /**
   * Transforms the full causes of failures emitted by this stream.
   */
  def mapErrorCause[E2](f: Cause[E] => Cause[E2])(implicit trace: Trace): ZStream[R, E2, A] =
    new ZStream(self.channel.mapErrorCause(f))

  /**
   * Transforms the errors emitted by this stream using `f`.
   */
  def mapErrorZIO[R1 <: R, E2](f: E => URIO[R1, E2])(implicit trace: Trace): ZStream[R1, E2, A] =
    new ZStream(self.channel.mapErrorZIO(f))

  /**
   * Maps over elements of the stream with the specified effectful function.
   */
  def mapZIO[R1 <: R, E1 >: E, A1](f: A => ZIO[R1, E1, A1])(implicit trace: Trace): ZStream[R1, E1, A1] = {

    def loop(chunkIterator: Chunk.ChunkIterator[A], index: Int): ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A1], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          f(a).map { a1 =>
            ZChannel.write(Chunk.single(a1)) *> loop(chunkIterator, index + 1)
          }
        }
      else
        ZChannel.readWithCause(
          elem => loop(elem.chunkIterator, 0),
          err => ZChannel.refailCause(err),
          done => ZChannel.succeed(done)
        )

    new ZStream(self.channel >>> loop(Chunk.ChunkIterator.empty, 0))
  }

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   */
  def mapZIOPar[R1 <: R, E1 >: E, A2](n: => Int)(f: A => ZIO[R1, E1, A2])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    mapZIOPar[R1, E1, A2](n, 16)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   */
  def mapZIOPar[R1 <: R, E1 >: E, A2](n: => Int, bufferSize: Int = 16)(f: A => ZIO[R1, E1, A2])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] = {
    require(n > 0, "Parallelism level must be greater than 0")
    require(bufferSize > 0, "Buffer size must be greater than 0")
    val effectiveBufferSize = if (bufferSize == 16) Math.min(n * 2, 1024) else bufferSize

    self >>> ZPipeline.mapZIOPar(n, effectiveBufferSize)(f)
  }

  /**
   * Maps over elements of the stream with the specified effectful function,
   * partitioned by `p` executing invocations of `f` concurrently. The number of
   * concurrent invocations of `f` is determined by the number of different
   * outputs of type `K`. Up to `buffer` elements may be buffered per partition.
   * Transformed elements may be reordered but the order within a partition is
   * maintained.
   */
  def mapZIOParByKey[R1 <: R, E1 >: E, A2, K](
    keyBy: A => K,
    buffer: => Int = 16
  )(f: A => ZIO[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A2] =
    groupByKey(keyBy, buffer).apply { case (_, s) => s.mapZIO(f) }

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  def mapZIOParUnordered[R1 <: R, E1 >: E, A2](n: => Int)(f: A => ZIO[R1, E1, A2])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    mapZIOParUnordered[R1, E1, A2](n, 16)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  def mapZIOParUnordered[R1 <: R, E1 >: E, A2](n: => Int, bufferSize: => Int = 16)(f: A => ZIO[R1, E1, A2])(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    self >>> ZPipeline.mapZIOParUnordered(n, bufferSize)(f)

  /**
   * Merges this stream and the specified stream together.
   *
   * New produced stream will terminate when both specified stream terminate if
   * no termination strategy is specified.
   */
  def merge[R1 <: R, E1 >: E, A1 >: A](
    that: => ZStream[R1, E1, A1],
    strategy: => HaltStrategy = HaltStrategy.Both
  )(implicit trace: Trace): ZStream[R1, E1, A1] = {
    import HaltStrategy.{Either, Left, Right}

    def handler(terminate: Boolean)(exit: Exit[E1, Any]): ZChannel.MergeDecision[R1, E1, Any, E1, Any] =
      if (terminate || !exit.isSuccess) ZChannel.MergeDecision.done(ZIO.done(exit))
      else ZChannel.MergeDecision.await(ZIO.done(_))

    new ZStream(
      ZChannel.succeed(strategy).flatMap { strategy =>
        self.channel
          .mergeWith(that.channel)(
            handler(strategy == Either || strategy == Left),
            handler(strategy == Either || strategy == Right)
          )
      }
    )
  }

  /**
   * Merges this stream and the specified stream together. New produced stream
   * will terminate when either stream terminates.
   */
  def mergeHaltEither[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    self.merge[R1, E1, A1](that, HaltStrategy.Either)

  /**
   * Merges this stream and the specified stream together. New produced stream
   * will terminate when this stream terminates.
   */
  def mergeHaltLeft[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    self.merge[R1, E1, A1](that, HaltStrategy.Left)

  /**
   * Merges this stream and the specified stream together. New produced stream
   * will terminate when the specified stream terminates.
   */
  def mergeHaltRight[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    self.merge[R1, E1, A1](that, HaltStrategy.Right)

  /**
   * Merges this stream and the specified stream together to produce a stream of
   * eithers.
   */
  def mergeEither[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    trace: Trace
  ): ZStream[R1, E1, Either[A, A2]] =
    self.mergeWith(that)(Left(_), Right(_))

  /**
   * Merges this stream and the specified stream together, discarding the values
   * from the right stream.
   */
  def mergeLeft[R1 <: R, E1 >: E, A2](
    that: => ZStream[R1, E1, A2],
    strategy: HaltStrategy = HaltStrategy.Both
  )(implicit
    trace: Trace
  ): ZStream[R1, E1, A] =
    self.merge(that.drain, strategy)

  /**
   * Merges this stream and the specified stream together, discarding the values
   * from the left stream.
   */
  def mergeRight[R1 <: R, E1 >: E, A2](
    that: => ZStream[R1, E1, A2],
    strategy: HaltStrategy = HaltStrategy.Both
  )(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    self.drain.merge(that, strategy)

  /**
   * Merges this stream that is sorted and the specified stream that is sorted
   * to produce a new stream that is sorted.
   */
  def mergeSorted[R1 <: R, E1 >: E, A1 >: A](
    that: => ZStream[R1, E1, A1]
  )(implicit ord: Ordering[A1], trace: Trace): ZStream[R1, E1, A1] = {
    sealed trait State
    case object DrainLeft                            extends State
    case object DrainRight                           extends State
    case object PullBoth                             extends State
    final case class PullLeft(rightChunk: Chunk[A1]) extends State
    final case class PullRight(leftChunk: Chunk[A1]) extends State

    def pull(
      state: State,
      pullLeft: ZIO[R, Option[E], Chunk[A1]],
      pullRight: ZIO[R1, Option[E1], Chunk[A1]]
    ): ZIO[R1, Nothing, Exit[Option[E1], (Chunk[A1], State)]] =
      state match {
        case DrainLeft =>
          pullLeft.fold(
            e => Exit.fail(e),
            leftChunk => Exit.succeed(leftChunk -> DrainLeft)
          )
        case DrainRight =>
          pullRight.fold(
            e => Exit.fail(e),
            rightChunk => Exit.succeed(rightChunk -> DrainRight)
          )
        case PullBoth =>
          pullLeft.unsome
            .zipPar(pullRight.unsome)
            .foldZIO(
              e => ZIO.succeed(Exit.fail(Some(e))),
              {
                case (Some(leftChunk), Some(rightChunk)) =>
                  if (leftChunk.isEmpty && rightChunk.isEmpty) pull(PullBoth, pullLeft, pullRight)
                  else if (leftChunk.isEmpty) pull(PullLeft(rightChunk), pullLeft, pullRight)
                  else if (rightChunk.isEmpty) pull(PullRight(leftChunk), pullLeft, pullRight)
                  else ZIO.succeed(Exit.succeed(mergeSortedChunk(leftChunk, rightChunk)))
                case (Some(leftChunk), None) =>
                  if (leftChunk.isEmpty) pull(DrainLeft, pullLeft, pullRight)
                  else ZIO.succeed(Exit.succeed(leftChunk -> DrainLeft))
                case (None, Some(rightChunk)) =>
                  if (rightChunk.isEmpty) pull(DrainRight, pullLeft, pullRight)
                  else
                    ZIO.succeed(Exit.succeed(rightChunk -> DrainRight))
                case (None, None) => ZIO.succeed(Exit.fail(None))
              }
            )
        case PullLeft(rightChunk) =>
          pullLeft.foldZIO(
            {
              case Some(e) => ZIO.succeed(Exit.fail(Some(e)))
              case None =>
                ZIO.succeed(Exit.succeed(rightChunk -> DrainRight))
            },
            leftChunk =>
              if (leftChunk.isEmpty) pull(PullLeft(rightChunk), pullLeft, pullRight)
              else ZIO.succeed(Exit.succeed(mergeSortedChunk(leftChunk, rightChunk)))
          )
        case PullRight(leftChunk) =>
          pullRight.foldZIO(
            {
              case Some(e) => ZIO.succeed(Exit.fail(Some(e)))
              case None    => ZIO.succeed(Exit.succeed(leftChunk -> DrainLeft))
            },
            rightChunk =>
              if (rightChunk.isEmpty) pull(PullRight(leftChunk), pullLeft, pullRight)
              else ZIO.succeed(Exit.succeed(mergeSortedChunk(leftChunk, rightChunk)))
          )
      }

    def mergeSortedChunk(
      leftChunk: Chunk[A1],
      rightChunk: Chunk[A1]
    ): (Chunk[A1], State) = {
      val builder       = ChunkBuilder.make[A1]()
      var state         = null.asInstanceOf[State]
      val leftIterator  = leftChunk.iterator
      val rightIterator = rightChunk.iterator
      var left          = leftIterator.next()
      var right         = rightIterator.next()
      var loop          = true
      while (loop) {
        val compare = ord.compare(left, right)
        if (compare == 0) {
          builder += left
          builder += right
          if (leftIterator.hasNext && rightIterator.hasNext) {
            left = leftIterator.next()
            right = rightIterator.next()
          } else if (leftIterator.hasNext) {
            state = PullRight(Chunk.fromIterator(leftIterator))
            loop = false
          } else if (rightIterator.hasNext) {
            state = PullLeft(Chunk.fromIterator(rightIterator))
            loop = false
          } else {
            state = PullBoth
            loop = false
          }
        } else if (compare < 0) {
          builder += left
          if (leftIterator.hasNext) {
            left = leftIterator.next()
          } else {
            val rightBuilder = ChunkBuilder.make[A1]()
            rightBuilder += right
            rightBuilder ++= rightIterator
            state = PullLeft(rightBuilder.result())
            loop = false
          }
        } else {
          builder += right
          if (rightIterator.hasNext) {
            right = rightIterator.next()
          } else {
            val leftBuilder = ChunkBuilder.make[A1]()
            leftBuilder += left
            leftBuilder ++= leftIterator
            state = PullRight(leftBuilder.result())
            loop = false
          }
        }
      }
      (builder.result(), state)
    }

    self.combineChunks[R1, E1, State, A1, A1](that)(PullBoth)(pull)
  }

  /**
   * Merges this stream and the specified stream together to a common element
   * type with the specified mapping functions.
   *
   * New produced stream will terminate when both specified stream terminate if
   * no termination strategy is specified.
   */
  def mergeWith[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2],
    strategy: => HaltStrategy = HaltStrategy.Both
  )(l: A => A3, r: A2 => A3)(implicit trace: Trace): ZStream[R1, E1, A3] =
    self
      .map(l)
      .merge(that.map(r), strategy)

  /**
   * Runs the specified effect if this stream fails, providing the error to the
   * effect if it exists.
   *
   * Note: Unlike [[ZIO.onError]], there is no guarantee that the provided
   * effect will not be interrupted.
   */
  def onError[R1 <: R](cleanup: Cause[E] => URIO[R1, Any])(implicit trace: Trace): ZStream[R1, E, A] =
    catchAllCause(cause => ZStream.fromZIO(cleanup(cause) *> Exit.failCause(cause)))

  /**
   * Locks the execution of this stream to the specified executor. Any streams
   * that are composed after this one will automatically be shifted back to the
   * previous executor.
   */
  def onExecutor(executor: => Executor)(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.scoped(ZIO.onExecutorScoped(executor)) *> self

  /**
   * Translates any failure into a stream termination, making the stream
   * infallible and all failures unchecked.
   */
  def orDie(implicit ev1: E IsSubtypeOfError Throwable, ev2: CanFail[E], trace: Trace): ZStream[R, Nothing, A] =
    self.orDieWith(ev1)

  /**
   * Keeps none of the errors, and terminates the stream with them, using the
   * specified function to convert the `E` into a `Throwable`.
   */
  def orDieWith(f: E => Throwable)(implicit ev: CanFail[E], trace: Trace): ZStream[R, Nothing, A] =
    new ZStream(self.channel.orDieWith(f))

  /**
   * Switches to the provided stream in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  def orElse[R1 <: R, E1, A1 >: A](
    that: => ZStream[R1, E1, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E1, A1] =
    new ZStream(self.channel.orElse(that.channel))

  /**
   * Switches to the provided stream in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  def orElseEither[R1 <: R, E2, A2](
    that: => ZStream[R1, E2, A2]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E2, Either[A, A2]] =
    self.map(Left(_)) orElse that.map(Right(_))

  /**
   * Fails with given error in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  def orElseFail[E1](e1: => E1)(implicit ev: CanFail[E], trace: Trace): ZStream[R, E1, A] =
    orElse(ZStream.fail(e1))

  /**
   * Produces the specified element if this stream is empty.
   */
  def orElseIfEmpty[A1 >: A](a: A1)(implicit trace: Trace): ZStream[R, E, A1] =
    orElseIfEmpty(Chunk.single(a))

  /**
   * Produces the specified chunk if this stream is empty.
   */
  def orElseIfEmpty[A1 >: A](chunk: Chunk[A1])(implicit trace: Trace): ZStream[R, E, A1] =
    orElseIfEmpty(new ZStream(ZChannel.write(chunk)))

  /**
   * Switches to the provided stream in case this one is empty.
   */
  def orElseIfEmpty[R1 <: R, E1 >: E, A1 >: A](
    stream: ZStream[R1, E1, A1]
  )(implicit trace: Trace): ZStream[R1, E1, A1] = {
    lazy val writer: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A1], Any] =
      ZChannel.readWithCause(
        (in: Chunk[A]) => if (in.isEmpty) writer else ZChannel.write(in) *> ZChannel.identity[E, Chunk[A], Any],
        (e: Cause[E]) => ZChannel.refailCause(e),
        (_: Any) => stream.channel
      )

    new ZStream(self.channel >>> writer)
  }

  /**
   * Switches to the provided stream in case this one fails with the `None`
   * value.
   *
   * See also [[ZStream#catchAll]].
   */
  def orElseOptional[R1 <: R, E1, A1 >: A](
    that: => ZStream[R1, Option[E1], A1]
  )(implicit ev: E <:< Option[E1], trace: Trace): ZStream[R1, Option[E1], A1] =
    catchAll(ev(_).fold(that)(e => ZStream.fail(Some(e))))

  /**
   * Succeeds with the specified value if this one fails with a typed error.
   */
  def orElseSucceed[A1 >: A](A1: => A1)(implicit ev: CanFail[E], trace: Trace): ZStream[R, Nothing, A1] =
    orElse(ZStream.succeed(A1))

  /**
   * Partition a stream using a predicate. The first stream will contain all
   * element evaluated to true and the second one will contain all element
   * evaluated to false. The faster stream may advance by up to buffer elements
   * further than the slower one.
   */
  def partition(p: A => Boolean, buffer: => Int = 16)(implicit
    trace: Trace
  ): ZIO[R with Scope, Nothing, (ZStream[Any, E, A], ZStream[Any, E, A])] =
    self.partitionEither(a => if (p(a)) ZIO.succeed(Left(a)) else ZIO.succeed(Right(a)), buffer)

  /**
   * Split a stream by a predicate. The faster stream may advance by up to
   * buffer elements further than the slower one.
   */
  def partitionEither[R1 <: R, E1 >: E, A2, A3](
    p: A => ZIO[R1, E1, Either[A2, A3]],
    buffer: => Int = 16
  )(implicit trace: Trace): ZIO[R1 with Scope, Nothing, (ZStream[Any, E1, A2], ZStream[Any, E1, A3])] =
    self
      .mapZIO(p)
      .distributedWith(
        2,
        buffer,
        {
          case Left(_)  => ZIO.succeed(_ == 0)
          case Right(_) => ZIO.succeed(_ == 1)
        }
      )
      .flatMap {
        case q1 :: q2 :: Nil =>
          ZIO.succeed {
            (
              ZStream.fromQueueWithShutdown(q1).flattenExitOption.collectLeft,
              ZStream.fromQueueWithShutdown(q2).flattenExitOption.collectRight
            )
          }
        case otherwise => ZIO.dieMessage(s"partitionEither: expected two streams but got $otherwise")
      }

  /**
   * Peels off enough material from the stream to construct a `Z` using the
   * provided [[ZSink]] and then returns both the `Z` and the rest of the
   * [[ZStream]] in a scope. Like all scoped values, the provided stream is
   * valid only within the scope.
   */
  def peel[R1 <: R, E1 >: E, A1 >: A, Z](
    sink: => ZSink[R1, E1, A1, A1, Z]
  )(implicit trace: Trace): ZIO[R1 with Scope, E1, (Z, ZStream[Any, E, A1])] = {
    sealed trait Signal
    object Signal {
      case class Emit(els: Chunk[A1])  extends Signal
      case class Halt(cause: Cause[E]) extends Signal
      case object End                  extends Signal
    }

    (for {
      p       <- Promise.make[E1, Z]
      handoff <- ZStream.Handoff.make[Signal]
    } yield {
      val consumer: ZSink[R1, E1, A1, A1, Unit] = sink.collectLeftover
        .foldCauseSink(
          c => ZSink.fromZIO(p.failCause(c)) *> ZSink.failCause(c),
          { case (z1, leftovers) =>
            lazy val loop: ZChannel[Any, E, Chunk[A1], Any, E1, Chunk[A1], Unit] = ZChannel.readWithCause(
              (in: Chunk[A1]) => ZChannel.fromZIO(handoff.offer(Signal.Emit(in))) *> loop,
              (e: Cause[E]) => ZChannel.fromZIO(handoff.offer(Signal.Halt(e))) *> ZChannel.refailCause(e),
              (_: Any) => ZChannel.fromZIO(handoff.offer(Signal.End)) *> ZChannel.unit
            )

            ZSink.fromChannel(
              ZChannel.fromZIO(p.succeed(z1)) *>
                ZChannel.fromZIO(handoff.offer(Signal.Emit(leftovers))) *>
                loop
            )
          }
        )

      lazy val producer: ZChannel[Any, Any, Any, Any, E, Chunk[A1], Unit] = ZChannel.unwrap(
        handoff.take.map {
          case Signal.Emit(els)   => ZChannel.write(els) *> producer
          case Signal.Halt(cause) => ZChannel.refailCause(cause)
          case Signal.End         => ZChannel.unit
        }
      )

      for {
        _ <- self.tapErrorCause(cause => p.failCause(cause)).run(consumer).forkScoped
        z <- p.await
      } yield (z, new ZStream(producer))
    }).flatten
  }

  /**
   * Pipes all of the values from this stream through the provided sink.
   *
   * @see
   *   [[transduce]]
   */
  def pipeThrough[R1 <: R, E1 >: E, L, Z](sink: => ZSink[R1, E1, A, L, Z])(implicit
    trace: Trace
  ): ZStream[R1, E1, L] =
    new ZStream(self.channel pipeToOrFail sink.channel)

  /**
   * Pipes all the values from this stream through the provided channel
   */
  def pipeThroughChannel[R1 <: R, E2, A2](channel: => ZChannel[R1, E, Chunk[A], Any, E2, Chunk[A2], Any])(implicit
    trace: Trace
  ): ZStream[R1, E2, A2] =
    new ZStream(self.channel >>> channel)

  /**
   * Pipes all values from this stream through the provided channel, passing
   * through any error emitted by this stream unchanged.
   */
  def pipeThroughChannelOrFail[R1 <: R, E1 >: E, A2](channel: ZChannel[R1, Nothing, Chunk[A], Any, E1, Chunk[A2], Any])(
    implicit trace: Trace
  ): ZStream[R1, E1, A2] =
    new ZStream(self.channel pipeToOrFail channel)

  /**
   * Provides the stream with its required environment, which eliminates its
   * dependency on `R`.
   */
  def provideEnvironment(
    r: => ZEnvironment[R]
  )(implicit trace: Trace): ZStream[Any, E, A] =
    new ZStream(channel.provideEnvironment(r))

  /**
   * Provides a layer to the stream, which translates it to another level.
   */
  def provideLayer[E1 >: E, R0](
    layer: => ZLayer[R0, E1, R]
  )(implicit trace: Trace): ZStream[R0, E1, A] =
    new ZStream(
      ZChannel.unwrapScopedWith { scope =>
        layer.build(scope).map(r => self.channel.provideEnvironment(r))
      }
    )

  /**
   * Transforms the environment being provided to the stream with the specified
   * function.
   */
  def provideSomeEnvironment[R0](
    env: ZEnvironment[R0] => ZEnvironment[R]
  )(implicit trace: Trace): ZStream[R0, E, A] =
    ZStream.environmentWithStream[R0] { r0 =>
      self.provideEnvironment(env(r0))
    }

  /**
   * Splits the environment into two parts, providing one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val stream: ZStream[Logging with Database, Nothing, Unit] = ???
   *
   * val stream2 = stream.provideSomeLayer[Database](loggingLayer)
   * }}}
   */
  def provideSomeLayer[R0]: ZStream.ProvideSomeLayer[R0, R, E, A] =
    new ZStream.ProvideSomeLayer[R0, R, E, A](self)

  /**
   * Re-chunks the elements of the stream into chunks of `n` elements each. The
   * last chunk might contain less than `n` elements
   */
  def rechunk(n: => Int)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.rechunk(n)

  /**
   * Keeps some of the errors, and terminates the fiber with the rest
   */
  def refineOrDie[E1](
    pf: PartialFunction[E, E1]
  )(implicit ev1: E <:< Throwable, ev2: CanFail[E], trace: Trace): ZStream[R, E1, A] =
    refineOrDieWith(pf)(identity(_))

  /**
   * Keeps some of the errors, and terminates the fiber with the rest, using the
   * specified function to convert the `E` into a `Throwable`.
   */
  def refineOrDieWith[E1](
    pf: PartialFunction[E, E1]
  )(f: E => Throwable)(implicit ev: CanFail[E], trace: Trace): ZStream[R, E1, A] =
    mapErrorCause(_.flatMap(pf.andThen(Cause.fail(_)).applyOrElse(_, (e: E) => Cause.die(f(e)))))

  /**
   * Repeats the entire stream using the specified schedule. The stream will
   * execute normally, and then repeat again according to the provided schedule.
   */
  def repeat[R1 <: R, B](schedule: => Schedule[R1, Any, B])(implicit
    trace: Trace
  ): ZStream[R1, E, A] =
    repeatEither(schedule) collect { case Right(a) => a }

  /**
   * Repeats the entire stream using the specified schedule. The stream will
   * execute normally, and then repeat again according to the provided schedule.
   * The schedule output will be emitted at the end of each repetition.
   */
  def repeatEither[R1 <: R, B](schedule: => Schedule[R1, Any, B])(implicit
    trace: Trace
  ): ZStream[R1, E, Either[B, A]] =
    repeatWith(schedule)(Right(_), Left(_))

  /**
   * Repeats each element of the stream using the provided schedule. Repetitions
   * are done in addition to the first execution, which means using
   * `Schedule.recurs(1)` actually results in the original effect, plus an
   * additional recurrence, for a total of two repetitions of each value in the
   * stream.
   */
  def repeatElements[R1 <: R](schedule: => Schedule[R1, A, Any])(implicit
    trace: Trace
  ): ZStream[R1, E, A] =
    repeatElementsEither(schedule).collect { case Right(a) => a }

  /**
   * Repeats each element of the stream using the provided schedule. When the
   * schedule is finished, then the output of the schedule will be emitted into
   * the stream. Repetitions are done in addition to the first execution, which
   * means using `Schedule.recurs(1)` actually results in the original effect,
   * plus an additional recurrence, for a total of two repetitions of each value
   * in the stream.
   */
  def repeatElementsEither[R1 <: R, E1 >: E, B](
    schedule: => Schedule[R1, A, B]
  )(implicit trace: Trace): ZStream[R1, E1, Either[B, A]] =
    repeatElementsWith(schedule)(Right.apply, Left.apply)

  /**
   * Repeats each element of the stream using the provided schedule. When the
   * schedule is finished, then the output of the schedule will be emitted into
   * the stream. Repetitions are done in addition to the first execution, which
   * means using `Schedule.recurs(1)` actually results in the original effect,
   * plus an additional recurrence, for a total of two repetitions of each value
   * in the stream.
   *
   * This function accepts two conversion functions, which allow the output of
   * this stream and the output of the provided schedule to be unified into a
   * single type. For example, `Either` or similar data type.
   */
  def repeatElementsWith[R1 <: R, E1 >: E, B, C](
    schedule: => Schedule[R1, A, B]
  )(f: A => C, g: B => C)(implicit trace: Trace): ZStream[R1, E1, C] = new ZStream(
    self.channel >>> ZChannel.unwrap {
      for {
        driver <- schedule.driver
      } yield {
        def feed(in: Chunk[A]): ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[C], Unit] =
          in.headOption.fold(loop)(a => ZChannel.write(Chunk.single(f(a))) *> step(in.drop(1), a))

        def step(in: Chunk[A], a: A): ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[C], Unit] = {
          val advance = driver.next(a).as(ZChannel.write(Chunk.single(f(a))) *> step(in, a))
          val reset: ZIO[R1, Nothing, ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[C], Unit]] =
            for {
              b <- driver.last.orDie
              _ <- driver.reset
            } yield ZChannel.write(Chunk.single(g(b))) *> feed(in)

          ZChannel.unwrap(advance orElse reset)
        }

        lazy val loop: ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[C], Unit] =
          ZChannel.readWithCause(
            feed,
            ZChannel.refailCause,
            (_: Any) => ZChannel.unit
          )

        loop
      }
    }
  )

  /**
   * Repeats the entire stream using the specified schedule. The stream will
   * execute normally, and then repeat again according to the provided schedule.
   * The schedule output will be emitted at the end of each repetition and can
   * be unified with the stream elements using the provided functions.
   */
  def repeatWith[R1 <: R, B, C](
    schedule: => Schedule[R1, Any, B]
  )(f: A => C, g: B => C)(implicit trace: Trace): ZStream[R1, E, C] =
    ZStream.unwrap(
      for {
        driver <- schedule.driver
      } yield {
        val scheduleOutput = driver.last.orDie.map(g)
        val process        = self.map(f).channel
        lazy val loop: ZChannel[R1, Any, Any, Any, E, Chunk[C], Unit] =
          ZChannel.unwrap(
            driver
              .next(())
              .fold(
                _ => ZChannel.unit,
                _ => process *> ZChannel.unwrap(scheduleOutput.map(o => ZChannel.write(Chunk.single(o)))) *> loop
              )
          )

        new ZStream(process *> loop)
      }
    )

  /**
   * When the stream fails, retry it according to the given schedule
   *
   * This retries the entire stream, so will re-execute all of the stream's
   * acquire operations.
   *
   * The schedule is reset as soon as the first element passes through the
   * stream again.
   *
   * @param schedule
   *   Schedule receiving as input the errors of the stream
   * @return
   *   Stream outputting elements of all attempts of the stream
   */
  def retry[R1 <: R](
    schedule: => Schedule[R1, E, _]
  )(implicit trace: Trace): ZStream[R1, E, A] =
    ZStream.unwrap {
      for {
        driver <- schedule.driver
      } yield {
        def loop: ZStream[R1, E, A] = self.catchAll { e =>
          ZStream.unwrap(
            driver
              .next(e)
              .foldZIO(
                _ => Exit.failCause(Cause.fail(e)),
                _ => ZIO.succeed(loop.tap(_ => driver.reset))
              )
          )
        }
        loop
      }
    }

  /**
   * Fails with the error `None` if value is `Left`.
   */
  def right[A1, A2](implicit ev: A <:< Either[A1, A2], trace: Trace): ZStream[R, Option[E], A2] =
    self.mapError(Some(_)).rightOrFail(None)

  /**
   * Fails with given error 'e' if value is `Left`.
   */
  def rightOrFail[A1, A2, E1 >: E](
    e: => E1
  )(implicit ev: A <:< Either[A1, A2], trace: Trace): ZStream[R, E1, A2] =
    self.mapZIO(ev(_).fold(_ => ZIO.fail(e), ZIO.succeed(_)))

  /**
   * Runs the sink on the stream to produce either the sink's result or an
   * error.
   */
  def run[R1 <: R, E1 >: E, Z](sink: => ZSink[R1, E1, A, Any, Z])(implicit trace: Trace): ZIO[R1, E1, Z] =
    (channel pipeToOrFail sink.channel).runDrain

  def runScoped[R1 <: R, E1 >: E, B](sink: => ZSink[R1, E1, A, Any, B])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E1, B] =
    (channel pipeToOrFail sink.channel).drain.runScoped

  /**
   * Runs the stream and collects all of its elements to a chunk.
   */
  def runCollect(implicit trace: Trace): ZIO[R, E, Chunk[A]] =
    run(ZSink.collectAll)

  /**
   * Runs the stream and emits the number of elements processed
   *
   * Equivalent to `run(ZSink.count)`
   */
  def runCount(implicit trace: Trace): ZIO[R, E, Long] =
    run(ZSink.count)

  /**
   * Runs the stream only for its effects. The emitted elements are discarded.
   */
  def runDrain(implicit trace: Trace): ZIO[R, E, Unit] =
    run(ZSink.drain)

  /**
   * Executes a pure fold over the stream of values - reduces all elements in
   * the stream to a value of type `S`.
   */
  def runFold[S](s: => S)(f: (S, A) => S)(implicit trace: Trace): ZIO[R, E, S] =
    runFoldWhile(s)(_ => true)((s, a) => f(s, a))

  /**
   * Executes a pure fold over the stream of values. Returns a scoped value that
   * represents the scope of the stream.
   */
  def runFoldScoped[S](s: => S)(f: (S, A) => S)(implicit trace: Trace): ZIO[R with Scope, E, S] =
    runFoldWhileScoped(s)(_ => true)((s, a) => f(s, a))

  /**
   * Executes an effectful fold over the stream of values. Returns a scoped
   * value that represents the scope of the stream.
   */
  def runFoldScopedZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E1, S] =
    runFoldWhileScopedZIO[R1, E1, S](s)(_ => true)(f)

  /**
   * Reduces the elements in the stream to a value of type `S`. Stops the fold
   * early when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1).forever.foldWhile(0)(_ <= 4)(_ + _) // UIO[Int] == 5
   * }}}
   */
  def runFoldWhile[S](s: => S)(cont: S => Boolean)(f: (S, A) => S)(implicit trace: Trace): ZIO[R, E, S] =
    run(ZSink.fold(s)(cont)(f))

  /**
   * Executes an effectful fold over the stream of values. Returns a scoped
   * value that represents the scope of the stream. Stops the fold early when
   * the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                        // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => ZIO.succeed(s + a))  // URIO[Scope, Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  def runFoldWhileScopedZIO[R1 <: R, E1 >: E, S](
    s: => S
  )(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit trace: Trace): ZIO[R1 with Scope, E1, S] =
    runScoped(ZSink.foldZIO(s)(cont)(f))

  /**
   * Executes an effectful fold over the stream of values. Stops the fold early
   * when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                        // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => ZIO.succeed(s + a))  // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  def runFoldWhileZIO[R1 <: R, E1 >: E, S](s: => S)(cont: S => Boolean)(
    f: (S, A) => ZIO[R1, E1, S]
  )(implicit trace: Trace): ZIO[R1, E1, S] =
    run(ZSink.foldZIO(s)(cont)(f))

  /**
   * Executes a pure fold over the stream of values. Returns a scoped value that
   * represents the scope of the stream. Stops the fold early when the condition
   * is not fulfilled.
   */
  def runFoldWhileScoped[S](s: => S)(cont: S => Boolean)(f: (S, A) => S)(implicit
    trace: Trace
  ): ZIO[R with Scope, E, S] =
    runScoped(ZSink.fold(s)(cont)(f))

  /**
   * Executes an effectful fold over the stream of values.
   */
  def runFoldZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: Trace
  ): ZIO[R1, E1, S] =
    runFoldWhileZIO[R1, E1, S](s)(_ => true)(f)

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  def runForeach[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: Trace): ZIO[R1, E1, Unit] =
    run(ZSink.foreach(f))

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  def runForeachChunk[R1 <: R, E1 >: E](f: Chunk[A] => ZIO[R1, E1, Any])(implicit
    trace: Trace
  ): ZIO[R1, E1, Unit] =
    run(ZSink.foreachChunk(f))

  /**
   * Like [[ZStream#runForeachChunk]], but returns a scoped `ZIO` so the
   * finalization order can be controlled.
   */
  def runForeachChunkScoped[R1 <: R, E1 >: E](f: Chunk[A] => ZIO[R1, E1, Any])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E1, Unit] =
    runScoped(ZSink.foreachChunk(f))

  /**
   * Like [[ZStream#foreach]], but returns a scoped `ZIO` so the finalization
   * order can be controlled.
   */
  def runForeachScoped[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E1, Unit] =
    runScoped(ZSink.foreach(f))

  /**
   * Consumes elements of the stream, passing them to the specified callback,
   * and terminating consumption when the callback returns `false`.
   */
  def runForeachWhile[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: Trace
  ): ZIO[R1, E1, Unit] =
    run(ZSink.foreachWhile(f))

  /**
   * Like [[ZStream#runForeachWhile]], but returns a scoped `ZIO` so the
   * finalization order can be controlled.
   */
  def runForeachWhileScoped[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E1, Unit] =
    runScoped(ZSink.foreachWhile(f))

  /**
   * Runs the stream to completion and yields the first value emitted by it,
   * discarding the rest of the elements.
   */
  def runHead(implicit trace: Trace): ZIO[R, E, Option[A]] =
    run(ZSink.head)

  /**
   * Publishes elements of this stream to a hub. Stream failure and ending will
   * also be signalled.
   */
  def runIntoHub[E1 >: E, A1 >: A](
    hub: => Hub[Take[E1, A1]]
  )(implicit trace: Trace): ZIO[R, Nothing, Unit] =
    runIntoQueue(hub)

  /**
   * Like [[ZStream#runIntoHub]], but provides the result as a scoped [[ZIO]] to
   * allow for scope composition.
   */
  def runIntoHubScoped[E1 >: E, A1 >: A](
    hub: => Hub[Take[E1, A1]]
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Unit] =
    runIntoQueueScoped(hub)

  /**
   * Enqueues elements of this stream into a queue. Stream failure and ending
   * will also be signalled.
   */
  def runIntoQueue(
    queue: => Enqueue[Take[E, A]]
  )(implicit trace: Trace): ZIO[R, Nothing, Unit] =
    ZIO.scoped[R](runIntoQueueScoped(queue))

  /**
   * Like [[ZStream#runIntoQueue]], but provides the result as a scoped [[ZIO]
   * to allow for scope composition.
   */
  def runIntoQueueScoped(
    queue: => Enqueue[Take[E, A]]
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Unit] = {
    lazy val writer: ZChannel[R, E, Chunk[A], Any, Nothing, Take[E, A], Any] = ZChannel
      .readWithCause[R, E, Chunk[A], Any, Nothing, Take[E, A], Any](
        in => ZChannel.write(Take.chunk(in)) *> writer,
        cause => ZChannel.write(Take.failCause(cause)),
        _ => ZChannel.write(Take.end)
      )

    (self.channel >>> writer)
      .mapOutZIO(queue.offer)
      .drain
      .runScoped
      .unit
  }

  /**
   * Like [[ZStream#runIntoQueue]], but provides the result as a scoped [[ZIO]]
   * to allow for scope composition.
   */
  def runIntoQueueElementsScoped(
    queue: => Enqueue[Exit[Option[E], A]]
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Unit] = {
    lazy val writer: ZChannel[R, E, Chunk[A], Any, Nothing, Exit[Option[E], A], Any] =
      ZChannel.readWithCause[R, E, Chunk[A], Any, Nothing, Exit[Option[E], A], Any](
        in => ZChannel.fromZIO(queue.offerAll(in.map(Exit.succeed(_)))) *> writer,
        err => ZChannel.fromZIO(queue.offer(Exit.failCause(err.map(Some(_))))),
        _ => ZChannel.fromZIO(queue.offer(Exit.fail(None)))
      )

    (self.channel >>> writer).drain.runScoped.unit
  }

  /**
   * Runs the stream to completion and yields the last value emitted by it,
   * discarding the rest of the elements.
   */
  def runLast(implicit trace: Trace): ZIO[R, E, Option[A]] =
    run(ZSink.last)

  /**
   * Runs the stream to a sink which sums elements, provided they are Numeric.
   *
   * Equivalent to `run(Sink.sum[A])`
   */
  def runSum[A1 >: A](implicit ev: Numeric[A1], trace: Trace): ZIO[R, E, A1] =
    run(ZSink.sum[A1])

  /**
   * Statefully maps over the elements of this stream to produce all
   * intermediate results of type `S` given an initial S.
   */
  def scan[S](s: => S)(f: (S, A) => S)(implicit trace: Trace): ZStream[R, E, S] =
    scanZIO(s)((s, a) => ZIO.succeed(f(s, a)))

  /**
   * Statefully maps over the elements of this stream to produce all
   * intermediate results.
   *
   * See also [[ZStream#scan]].
   */
  def scanReduce[A1 >: A](f: (A1, A) => A1)(implicit trace: Trace): ZStream[R, E, A1] =
    scanReduceZIO[R, E, A1]((curr, next) => ZIO.succeed(f(curr, next)))

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * all intermediate results.
   *
   * See also [[ZStream#scanZIO]].
   */
  def scanReduceZIO[R1 <: R, E1 >: E, A1 >: A](
    f: (A1, A) => ZIO[R1, E1, A1]
  )(implicit trace: Trace): ZStream[R1, E1, A1] =
    mapAccumZIO[R1, E1, Option[A1], A1](Option.empty[A1]) {
      case (Some(a1), a) => f(a1, a).map(a2 => Some(a2) -> a2)
      case (None, a)     => ZIO.succeed(Some(a) -> a)
    }

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * all intermediate results of type `S` given an initial S.
   */
  def scanZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: Trace
  ): ZStream[R1, E1, S] =
    self >>> ZPipeline.scanZIO(s)(f)

  /**
   * Schedules the output of the stream using the provided `schedule` and emits
   * its output at the end (if `schedule` is finite).
   */
  def scheduleEither[R1 <: R, E1 >: E, B](
    schedule: => Schedule[R1, A, B]
  )(implicit trace: Trace): ZStream[R1, E1, Either[B, A]] =
    scheduleWith(schedule)(Right.apply, Left.apply)

  /**
   * Schedules the output of the stream using the provided `schedule`.
   */
  def schedule[R1 <: R](schedule: => Schedule[R1, A, Any])(implicit
    trace: Trace
  ): ZStream[R1, E, A] =
    scheduleEither(schedule).collect { case Right(a) => a }

  /**
   * Schedules the output of the stream using the provided `schedule` and emits
   * its output at the end (if `schedule` is finite). Uses the provided function
   * to align the stream and schedule outputs on the same type.
   */
  def scheduleWith[R1 <: R, E1 >: E, B, C](
    schedule: => Schedule[R1, A, B]
  )(f: A => C, g: B => C)(implicit trace: Trace): ZStream[R1, E1, C] = {

    def loop(
      driver: Schedule.Driver[Any, R1, A, B],
      chunkIterator: Chunk.ChunkIterator[A],
      index: Int
    ): ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[C], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          driver
            .next(a)
            .foldZIO(
              _ =>
                driver.last.orDie.map { b =>
                  ZChannel.write(Chunk(f(a), g(b))) *> loop(driver, chunkIterator, index + 1)
                } <* driver.reset,
              _ => ZIO.succeed(ZChannel.write(Chunk.single(f(a))) *> loop(driver, chunkIterator, index + 1))
            )
        }
      else
        ZChannel.readWithCause(
          chunk => loop(driver, chunk.chunkIterator, 0),
          ZChannel.refailCause,
          ZChannel.succeedNow(_)
        )

    new ZStream(ZChannel.fromZIO(schedule.driver).flatMap(self.channel >>> loop(_, Chunk.ChunkIterator.empty, 0)))
  }

  /**
   * Converts an option on values into an option on errors.
   */
  def some[A2](implicit ev: A <:< Option[A2], trace: Trace): ZStream[R, Option[E], A2] =
    self.mapError(Some(_)).someOrFail(None)

  /**
   * Extracts the optional value, or returns the given 'default'. Superseded by
   * `someOrElse` with better type inference. This method was left for binary
   * compatibility.
   */
  protected def someOrElse[A2](default: => A2)(implicit ev: A <:< Option[A2], trace: Trace): ZStream[R, E, A2] =
    map(_.getOrElse(default))

  /**
   * Extracts the optional value, or returns the given 'default'.
   */
  def someOrElse[A2, C](default: => C)(implicit ev0: A <:< Option[A2], ev1: C <:< A2, trace: Trace): ZStream[R, E, A2] =
    map(_.getOrElse(default))

  /**
   * Extracts the optional value, or fails with the given error 'e'.
   */
  def someOrFail[A2, E1 >: E](e: => E1)(implicit ev: A <:< Option[A2], trace: Trace): ZStream[R, E1, A2] =
    self.mapZIO(ev(_).fold[IO[E1, A2]](ZIO.fail(e))(ZIO.succeed(_)))

  /**
   * Emits a sliding window of n elements.
   * {{{
   *   Stream(1, 2, 3, 4).sliding(2).runCollect // Chunk(Chunk(1, 2), Chunk(2, 3), Chunk(3, 4))
   * }}}
   */
  def sliding(chunkSize0: => Int, stepSize0: Int = 1)(implicit trace: Trace): ZStream[R, E, Chunk[A]] =
    ZStream.suspend {
      val chunkSize = chunkSize0
      val stepSize  = stepSize0

      def slidingChunk(chunk: Chunk[A], in: Chunk[A]): (Chunk[A], Chunk[Chunk[A]]) = {
        val updatedChunk = chunk ++ in
        val length       = updatedChunk.length
        if (length >= chunkSize) {
          val array      = new Array[Chunk[A]]((length - chunkSize) / stepSize + 1)
          var arrayIndex = 0
          var chunkIndex = 0
          while (chunkIndex + chunkSize <= length) {
            array(arrayIndex) = updatedChunk.slice(chunkIndex, chunkIndex + chunkSize)
            arrayIndex += 1
            chunkIndex += stepSize
          }
          (updatedChunk.drop(chunkIndex), Chunk.fromArray(array))
        } else (updatedChunk, Chunk.empty)
      }

      def sliding(chunk: Chunk[A], written: Boolean): ZChannel[Any, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] =
        ZChannel.readWithCause(
          in => {
            val (updatedChunk, out) = slidingChunk(chunk, in)
            if (out.isEmpty) sliding(updatedChunk, written)
            else ZChannel.write(out) *> sliding(updatedChunk, true)
          },
          err => {
            val index = if (written && chunkSize > stepSize) chunkSize - stepSize else 0
            if (index >= chunk.length) ZChannel.refailCause(err)
            else ZChannel.write(Chunk.single(chunk)) *> ZChannel.refailCause(err)
          },
          done => {
            val index = if (written && chunkSize > stepSize) chunkSize - stepSize else 0
            if (index >= chunk.length) ZChannel.succeedNow(done)
            else ZChannel.write(Chunk.single(chunk)) *> ZChannel.succeedNow(done)
          }
        )

      ZStream.fromChannel(self.channel >>> sliding(Chunk.empty, false))
    }

  /**
   * Splits elements based on a predicate.
   * {{{
   *   ZStream.range(1, 10).split(_ % 4 == 0).runCollect // Chunk(Chunk(1, 2, 3), Chunk(5, 6, 7), Chunk(9))
   * }}}
   */
  def split(f: A => Boolean)(implicit trace: Trace): ZStream[R, E, Chunk[A]] = {
    def split(leftovers: Chunk[A])(in: Chunk[A]): ZChannel[R, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] = {
      val (chunk, remaining) = (leftovers ++ in).splitWhere(f)
      if (chunk.isEmpty || remaining.isEmpty) loop(chunk ++ remaining.drop(1))
      else ZChannel.write(Chunk.single(chunk)) *> split(Chunk.empty)(remaining.drop(1))
    }

    def loop(leftovers: Chunk[A]): ZChannel[R, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] =
      ZChannel.readWithCause(
        (in: Chunk[A]) => split(leftovers)(in),
        (e: Cause[E]) => ZChannel.refailCause(e),
        (_: Any) => {
          if (leftovers.isEmpty) ZChannel.unit
          else if (leftovers.find(f).isEmpty) ZChannel.write(Chunk.single(leftovers)) *> ZChannel.unit
          else split(Chunk.empty)(leftovers) *> ZChannel.unit
        }
      )

    new ZStream(self.channel >>> loop(Chunk.empty))
  }

  /**
   * Splits elements on a delimiter and transforms the splits into desired
   * output.
   */
  def splitOnChunk[A1 >: A](delimiter: => Chunk[A1])(implicit trace: Trace): ZStream[R, E, Chunk[A]] =
    ZStream.succeed(delimiter).flatMap { delimiter =>
      def next(
        leftover: Option[Chunk[A]],
        delimiterIndex: Int
      ): ZChannel[R, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] =
        ZChannel.readWithCause(
          inputChunk => {
            var buffer = null.asInstanceOf[collection.mutable.ArrayBuffer[Chunk[A]]]
            inputChunk.foldLeft((leftover getOrElse Chunk.empty, delimiterIndex)) {
              case ((carry, delimiterCursor), a) =>
                val concatenated = carry :+ a
                if (delimiterCursor < delimiter.length && a == delimiter(delimiterCursor)) {
                  if (delimiterCursor + 1 == delimiter.length) {
                    if (buffer eq null) buffer = collection.mutable.ArrayBuffer[Chunk[A]]()
                    buffer += concatenated.take(concatenated.length - delimiter.length)
                    (Chunk.empty, 0)
                  } else (concatenated, delimiterCursor + 1)
                } else (concatenated, if (a == delimiter(0)) 1 else 0)
            } match {
              case (carry, delimiterCursor) =>
                ZChannel.write(
                  if (buffer eq null) Chunk.empty
                  else Chunk.fromArray(buffer.toArray)
                ) *> next(if (carry.nonEmpty) Some(carry) else None, delimiterCursor)
            }
          },
          halt =>
            leftover match {
              case Some(chunk) => ZChannel.write(Chunk.single(chunk)) *> ZChannel.refailCause(halt)
              case None        => ZChannel.refailCause(halt)
            },
          done =>
            leftover match {
              case Some(chunk) => ZChannel.write(Chunk.single(chunk)) *> ZChannel.succeed(done)
              case None        => ZChannel.succeed(done)
            }
        )
      new ZStream(self.channel >>> next(None, 0))
    }

  /**
   * Takes the specified number of elements from this stream.
   */
  def take(n: => Long)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.take(n)

  /**
   * Takes the last specified number of elements from this stream.
   */
  def takeRight(n: => Int)(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.succeed(n).flatMap { n =>
      if (n <= 0) ZStream.empty
      else
        new ZStream(
          ZChannel.unwrap(
            for {
              queue <- ZIO.succeed(SingleThreadedRingBuffer[A](n))
            } yield {
              lazy val reader: ZChannel[Any, E, Chunk[A], Any, E, Chunk[A], Unit] = ZChannel.readWithCause(
                (in: Chunk[A]) => {
                  in.foreach(queue.put)
                  reader
                },
                ZChannel.refailCause,
                (_: Any) => ZChannel.write(queue.toChunk) *> ZChannel.unit
              )

              (self.channel >>> reader)
            }
          )
        )
    }

  /**
   * Takes all elements of the stream until the specified predicate evaluates to
   * `true`.
   */
  def takeUntil(f: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.takeUntil(f)

  /**
   * Takes all elements of the stream until the specified effectual predicate
   * evaluates to `true`.
   */
  def takeUntilZIO[R1 <: R, E1 >: E](
    f: A => ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): ZStream[R1, E1, A] =
    self >>> ZPipeline.takeUntilZIO(f)

  /**
   * Takes all elements of the stream for as long as the specified predicate
   * evaluates to `true`.
   */
  def takeWhile(f: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.takeWhile(f)

  /**
   * Takes all elements of the stream for as long as the specified effectual
   * predicate evaluates to `true`.
   */
  def takeWhileZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit trace: Trace): ZStream[R1, E1, A] =
    self >>> ZPipeline.takeWhileZIO(f)

  /**
   * Adds an effect to consumption of every element of the stream.
   */
  def tap[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: Trace): ZStream[R1, E1, A] =
    mapZIO(a => f(a).as(a))

  /**
   * Returns a stream that effectfully "peeks" at the failure and adds an effect
   * to consumption of every element of the stream
   */
  def tapBoth[R1 <: R, E1 >: E](
    f: E => ZIO[R1, E1, Any],
    g: A => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E1, A] =
    tapError(f).tap(g)

  /**
   * Returns a stream that effectfully "peeks" at the failure of the stream.
   */
  def tapError[R1 <: R, E1 >: E](
    f: E => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E1, A] =
    tapErrorCause(_.failureOrCause.fold(f, _ => ZIO.unit))

  /**
   * Returns a stream that effectfully "peeks" at the cause of failure of the
   * stream.
   */
  def tapErrorCause[R1 <: R, E1 >: E](
    f: Cause[E] => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E], trace: Trace): ZStream[R1, E1, A] = {
    lazy val tapErrorCause: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A], Any] =
      ZChannel.readWithCause(
        chunk => ZChannel.write(chunk) *> tapErrorCause,
        cause => ZChannel.fromZIO(f(cause)) *> ZChannel.refailCause(cause),
        done => ZChannel.succeedNow(done)
      )

    new ZStream(self.channel.pipeTo(tapErrorCause))
  }

  /**
   * Sends all elements emitted by this stream to the specified sink in addition
   * to emitting them.
   */
  def tapSink[R1 <: R, E1 >: E](
    sink: => ZSink[R1, E1, A, Any, Any]
  )(implicit trace: Trace): ZStream[R1, E1, A] =
    ZStream.fromZIO(Queue.bounded[Take[E1, A]](1) <*> Promise.make[Nothing, Unit]).flatMap { case (queue, promise) =>
      val right = ZStream.fromQueue(queue, 1).flattenTake
      lazy val loop: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A], Any] =
        ZChannel.readWithCause(
          chunk =>
            ZChannel
              .fromZIO(queue.offer(Take.chunk(chunk)))
              .foldCauseChannel(
                _ => ZChannel.write(chunk) *> ZChannel.identity,
                _ => ZChannel.write(chunk) *> loop
              ),
          cause =>
            ZChannel
              .fromZIO(queue.offer(Take.failCause(cause)))
              .foldCauseChannel(
                _ => ZChannel.refailCause(cause),
                _ => ZChannel.refailCause(cause)
              ),
          _ =>
            ZChannel
              .fromZIO(queue.offer(Take.end))
              .foldCauseChannel(
                _ => ZChannel.unit,
                _ => ZChannel.unit
              )
        )
      new ZStream(
        ZChannel.fromZIO(promise.await) *> self.channel
          .pipeTo(loop)
          .ensuring(queue.offer(Take.end).forkDaemon *> queue.awaitShutdown) *> ZChannel.unit
      )
        .merge(ZStream.execute((promise.succeed(()) *> right.run(sink)).ensuring(queue.shutdown)), HaltStrategy.Both)
    }

  /**
   * Throttles the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` function.
   */
  def throttleEnforce(units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => Long
  )(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.throttleEnforce(units, duration, burst)(costFn)

  /**
   * Throttles the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` effectful function.
   */
  def throttleEnforceZIO[R1 <: R, E1 >: E](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => ZIO[R1, E1, Long]
  )(implicit trace: Trace): ZStream[R1, E1, A] =
    self >>> ZPipeline.throttleEnforceZIO(units, duration, burst)(costFn)

  /**
   * Delays the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` function.
   */
  def throttleShape(units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => Long
  )(implicit trace: Trace): ZStream[R, E, A] =
    self >>> ZPipeline.throttleShape(units, duration, burst)(costFn)

  /**
   * Delays the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` effectful function.
   */
  def throttleShapeZIO[R1 <: R, E1 >: E](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => ZIO[R1, E1, Long]
  )(implicit trace: Trace): ZStream[R1, E1, A] =
    self >>> ZPipeline.throttleShapeZIO(units, duration, burst)(costFn)

  /**
   * Ends the stream if it does not produce a value after d duration.
   */
  def timeout(d: => Duration)(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.succeed(d).flatMap { d =>
      ZStream.fromPull[R, E, A] {
        self.toPull.map { pull =>
          // None case on the error channel of pull indicates completion/done.
          // Move it to success channel before calling timeout effect so that it does not log completion as failure.
          // In the end, move None case back to error channel for further stream processing
          pull.unsome.timeoutTo(None)(identity)(d).some
        }
      }
    }

  /**
   * Fails the stream with given error if it does not produce a value after d
   * duration.
   */
  def timeoutFail[E1 >: E](e: => E1)(d: Duration)(implicit
    trace: Trace
  ): ZStream[R, E1, A] =
    self.timeoutTo[R, E1, A](d)(ZStream.fail(e))

  /**
   * Fails the stream with given cause if it does not produce a value after d
   * duration.
   */
  def timeoutFailCause[E1 >: E](
    cause: => Cause[E1]
  )(d: => Duration)(implicit trace: Trace): ZStream[R, E1, A] =
    ZStream.succeed((cause, d)).flatMap { case (cause, d) =>
      ZStream.fromPull[R, E1, A] {
        self.toPull.map { pull =>
          // None case on the error channel of pull indicates completion/done.
          // Move it to success channel before calling timeout effect so that it does not log completion as failure.
          // In the end, move None case back to error channel for further stream processing
          pull.unsome.timeoutFailCause(cause)(d).some
        }
      }
    }

  /**
   * Switches the stream if it does not produce a value after d duration.
   */
  def timeoutTo[R1 <: R, E1 >: E, A2 >: A](
    d: => Duration
  )(that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A2] = {
    final case class StreamTimeout() extends Throwable
    self.timeoutFailCause(Cause.die(StreamTimeout()))(d).catchSomeCause { case Cause.Die(StreamTimeout(), _) => that }
  }

  /** Converts the stream to its underlying channel */
  def toChannel: ZChannel[R, Any, Any, Any, E, Chunk[A], Any] =
    self.channel

  /**
   * Converts the stream to a scoped hub of chunks. After the scope is closed,
   * the hub will never again produce values and should be discarded.
   */
  def toHub[E1 >: E, A1 >: A](
    capacity: => Int
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Hub[Take[E1, A1]]] =
    for {
      hub <- ZIO.acquireRelease(Hub.bounded[Take[E1, A1]](capacity))(_.shutdown)
      _   <- self.runIntoHubScoped(hub).forkScoped
    } yield hub

  /**
   * Converts this stream of bytes into a `java.io.InputStream` wrapped in a
   * scoped [[ZIO]]. The returned input stream will only be valid within the
   * scope.
   */
  def toInputStream(implicit
    ev0: E <:< Throwable,
    ev1: A <:< Byte,
    trace: Trace
  ): ZIO[R with Scope, E, java.io.InputStream] =
    for {
      runtime <- ZIO.runtime[R]
      pull    <- toPull.asInstanceOf[ZIO[R with Scope, Nothing, ZIO[R, Option[Throwable], Chunk[Byte]]]]
    } yield ZInputStream.fromPull(runtime, pull)

  /**
   * Converts this stream into a `scala.collection.Iterator` wrapped in a scoped
   * [[ZIO]]. The returned iterator will only be valid within the scope.
   */
  def toIterator(implicit trace: Trace): ZIO[R with Scope, Nothing, Iterator[Either[E, A]]] =
    for {
      runtime <- ZIO.runtime[R]
      pull    <- toPull
    } yield {
      def unfoldPull: Iterator[Either[E, A]] =
        runtime.unsafe.run(pull)(trace, Unsafe.unsafe) match {
          case Exit.Success(chunk) => chunk.iterator.map(Right(_)) ++ unfoldPull
          case Exit.Failure(cause) =>
            cause.failureOrCause match {
              case Left(None)    => Iterator.empty
              case Left(Some(e)) => Iterator.single(Left(e))
              case Right(c)      => throw FiberFailure(c)
            }
        }

      unfoldPull
    }

  /**
   * Returns in a scope a ZIO effect that can be used to repeatedly pull chunks
   * from the stream. The pull effect fails with None when the stream is
   * finished, or with Some error if it fails, otherwise it returns a chunk of
   * the stream's output.
   */
  def toPull(implicit trace: Trace): ZIO[R with Scope, Nothing, ZIO[R, Option[E], Chunk[A]]] =
    channel.toPull.map { pull =>
      pull.mapError(error => Some(error)).flatMap {
        case Left(done)  => ZIO.fail(None)
        case Right(elem) => ZIO.succeed(elem)
      }
    }

  /**
   * Converts this stream of chars into a `java.io.Reader` wrapped in a scoped
   * [[ZIO]]. The returned reader will only be valid within the scope.
   */
  def toReader(implicit
    ev0: E <:< Throwable,
    ev1: A <:< Char,
    trace: Trace
  ): ZIO[R with Scope, E, java.io.Reader] =
    for {
      runtime <- ZIO.runtime[R]
      pull    <- toPull.asInstanceOf[ZIO[R with Scope, Nothing, ZIO[R, Option[Throwable], Chunk[Char]]]]
    } yield ZReader.fromPull(runtime, pull)

  /**
   * Converts the stream to a scoped queue of chunks. After the scope is closed,
   * the queue will never again produce values and should be discarded.
   */
  def toQueue(
    capacity: => Int = 2
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- ZIO.acquireRelease(Queue.bounded[Take[E, A]](capacity))(_.shutdown)
      _     <- self.runIntoQueueScoped(queue).forkScoped
    } yield queue

  /**
   * Converts the stream to a dropping scoped queue of chunks. After the scope
   * is closed, the queue will never again produce values and should be
   * discarded.
   */
  def toQueueDropping(
    capacity: => Int = 2
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- ZIO.acquireRelease(Queue.dropping[Take[E, A]](capacity))(_.shutdown)
      _     <- self.runIntoQueueScoped(queue).forkScoped
    } yield queue

  /**
   * Converts the stream to a scoped queue of elements. After the scope is
   * closed, the queue will never again produce values and should be discarded.
   */
  def toQueueOfElements(
    capacity: => Int = 2
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Dequeue[Exit[Option[E], A]]] =
    for {
      queue <- ZIO.acquireRelease(Queue.bounded[Exit[Option[E], A]](capacity))(_.shutdown)
      _     <- self.runIntoQueueElementsScoped(queue).forkScoped
    } yield queue

  /**
   * Converts the stream to a sliding scoped queue of chunks. After the scope is
   * closed, the queue will never again produce values and should be discarded.
   */
  def toQueueSliding(
    capacity: => Int = 2
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- ZIO.acquireRelease(Queue.sliding[Take[E, A]](capacity))(_.shutdown)
      _     <- self.runIntoQueueScoped(queue).forkScoped
    } yield queue

  /**
   * Converts the stream into an unbounded scoped queue. After the scope is
   * closed, the queue will never again produce values and should be discarded.
   */
  def toQueueUnbounded(implicit trace: Trace): ZIO[R with Scope, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- ZIO.acquireRelease(Queue.unbounded[Take[E, A]])(_.shutdown)
      _     <- self.runIntoQueueScoped(queue).forkScoped
    } yield queue

  /**
   * Applies the transducer to the stream and emits its outputs.
   */
  def transduce[R1 <: R, E1 >: E, A1 >: A, Z](
    sink: => ZSink[R1, E1, A1, A1, Z]
  )(implicit trace: Trace): ZStream[R1, E1, Z] =
    self >>> ZPipeline.fromSink(sink)

  /**
   * Updates a service in the environment of this effect.
   */
  def updateService[M] =
    new ZStream.UpdateService[R, E, A, M](self)

  /**
   * Updates a service at the specified key in the environment of this effect.
   */
  def updateServiceAt[Service]: ZStream.UpdateServiceAt[R, E, A, Service] =
    new ZStream.UpdateServiceAt[R, E, A, Service](self)

  /**
   * Threads the stream through a transformation pipeline.
   */
  def via[R1 <: R, E1 >: E, B](pipeline: => ZPipeline[R1, E1, A, B])(implicit
    trace: Trace
  ): ZStream[R1, E1, B] =
    ZStream.suspend(pipeline(self))

  /**
   * Threads the stream through the transformation function `f`.
   */
  def viaFunction[R2, E2, B](f: ZStream[R, E, A] => ZStream[R2, E2, B])(implicit
    trace: Trace
  ): ZStream[R2, E2, B] =
    f(self)

  /**
   * Returns this stream if the specified condition is satisfied, otherwise
   * returns an empty stream.
   */
  def when(b: => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.when(b)(self)

  /**
   * Returns this stream if the specified effectful condition is satisfied,
   * otherwise returns an empty stream.
   */
  def whenZIO[R1 <: R, E1 >: E](b: => ZIO[R1, E1, Boolean])(implicit trace: Trace): ZStream[R1, E1, A] =
    ZStream.whenZIO(b)(self)

  /**
   * Equivalent to [[filter]] but enables the use of filter clauses in
   * for-comprehensions
   */
  def withFilter(predicate: A => Boolean)(implicit trace: Trace): ZStream[R, E, A] =
    filter(predicate)

  /**
   * Zips this stream with another point-wise and emits tuples of elements from
   * both streams.
   *
   * The new stream will end when one of the sides ends.
   */
  def zip[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    zippable: Zippable[A, A2],
    trace: Trace
  ): ZStream[R1, E1, zippable.Out] =
    zipWith(that)(zippable.zip(_, _))

  /**
   * Zips this stream with another point-wise, creating a new stream of pairs of
   * elements from both sides.
   *
   * The defaults `defaultLeft` and `defaultRight` will be used if the streams
   * have different lengths and one of the streams has ended before the other.
   */
  def zipAll[R1 <: R, E1 >: E, A1 >: A, A2](
    that: => ZStream[R1, E1, A2]
  )(defaultLeft: => A1, defaultRight: => A2)(implicit trace: Trace): ZStream[R1, E1, (A1, A2)] =
    zipAllWith(that)((_, defaultRight), (defaultLeft, _))((_, _))

  /**
   * Zips this stream with another point-wise, and keeps only elements from this
   * stream.
   *
   * The provided default value will be used if the other stream ends before
   * this one.
   */
  def zipAllLeft[R1 <: R, E1 >: E, A1 >: A, A2](that: => ZStream[R1, E1, A2])(default: => A1)(implicit
    trace: Trace
  ): ZStream[R1, E1, A1] =
    zipAllWith(that)(identity, _ => default)((o, _) => o)

  /**
   * Zips this stream with another point-wise, and keeps only elements from the
   * other stream.
   *
   * The provided default value will be used if this stream ends before the
   * other one.
   */
  def zipAllRight[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(default: => A2)(implicit
    trace: Trace
  ): ZStream[R1, E1, A2] =
    zipAllWith(that)(_ => default, identity)((_, A2) => A2)

  /**
   * Zips this stream with another point-wise. The provided functions will be
   * used to create elements for the composed stream.
   *
   * The functions `left` and `right` will be used if the streams have different
   * lengths and one of the streams has ended before the other.
   */
  def zipAllWith[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(left: A => A3, right: A2 => A3)(both: (A, A2) => A3)(implicit trace: Trace): ZStream[R1, E1, A3] = {

    sealed trait State[+A, +A2]
    case object DrainLeft                                extends State[Nothing, Nothing]
    case object DrainRight                               extends State[Nothing, Nothing]
    case object PullBoth                                 extends State[Nothing, Nothing]
    final case class PullLeft[A2](rightChunk: Chunk[A2]) extends State[Nothing, A2]
    final case class PullRight[A](leftChunk: Chunk[A])   extends State[A, Nothing]

    def pull(
      state: State[A, A2],
      pullLeft: ZIO[R, Option[E], Chunk[A]],
      pullRight: ZIO[R1, Option[E1], Chunk[A2]]
    ): ZIO[R1, Nothing, Exit[Option[E1], (Chunk[A3], State[A, A2])]] =
      state match {
        case DrainLeft =>
          pullLeft.foldZIO(
            err => ZIO.succeed(Exit.fail(err)),
            leftChunk => ZIO.succeed(Exit.succeed(leftChunk.map(left) -> DrainLeft))
          )
        case DrainRight =>
          pullRight.foldZIO(
            err => ZIO.succeed(Exit.fail(err)),
            rightChunk => ZIO.succeed(Exit.succeed(rightChunk.map(right) -> DrainRight))
          )
        case PullBoth =>
          pullLeft.unsome
            .zipPar(pullRight.unsome)
            .foldZIO(
              err => ZIO.succeed(Exit.fail(Some(err))),
              {
                case (Some(leftChunk), Some(rightChunk)) =>
                  if (leftChunk.isEmpty && rightChunk.isEmpty)
                    pull(PullBoth, pullLeft, pullRight)
                  else if (leftChunk.isEmpty)
                    pull(PullLeft(rightChunk), pullLeft, pullRight)
                  else if (rightChunk.isEmpty)
                    pull(PullRight(leftChunk), pullLeft, pullRight)
                  else
                    ZIO.succeed(Exit.succeed(zipWithChunks(leftChunk, rightChunk, both)))
                case (Some(leftChunk), None) =>
                  ZIO.succeed(Exit.succeed(leftChunk.map(left) -> DrainLeft))
                case (None, Some(rightChunk)) =>
                  ZIO.succeed(Exit.succeed(rightChunk.map(right) -> DrainRight))
                case (None, None) =>
                  ZIO.succeed(Exit.fail(None))
              }
            )
        case PullLeft(rightChunk) =>
          pullLeft.foldZIO(
            {
              case Some(err) => ZIO.succeed(Exit.fail(Some(err)))
              case None      => ZIO.succeed(Exit.succeed(rightChunk.map(right) -> DrainRight))
            },
            leftChunk =>
              if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else
                ZIO.succeed(Exit.succeed(zipWithChunks(leftChunk, rightChunk, both)))
          )
        case PullRight(leftChunk) =>
          pullRight.foldZIO(
            {
              case Some(err) => ZIO.succeed(Exit.fail(Some(err)))
              case None      => ZIO.succeed(Exit.succeed(leftChunk.map(left) -> DrainLeft))
            },
            rightChunk =>
              if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else
                ZIO.succeed(Exit.succeed(zipWithChunks(leftChunk, rightChunk, both)))
          )
      }

    def zipWithChunks(
      leftChunk: Chunk[A],
      rightChunk: Chunk[A2],
      f: (A, A2) => A3
    ): (Chunk[A3], State[A, A2]) =
      zipChunks(leftChunk, rightChunk, f) match {
        case (out, Left(leftChunk)) =>
          if (leftChunk.isEmpty)
            out -> PullBoth
          else
            out -> PullRight(leftChunk)
        case (out, Right(rightChunk)) =>
          if (rightChunk.isEmpty)
            out -> PullBoth
          else
            out -> PullLeft(rightChunk)
      }

    self.combineChunks[R1, E1, State[A, A2], A2, A3](that)(PullBoth)(pull)
  }

  /**
   * Zips the two streams so that when a value is emitted by either of the two
   * streams, it is combined with the latest value from the other stream to
   * produce a result.
   *
   * Note: tracking the latest value is done on a per-chunk basis. That means
   * that emitted elements that are not the last value in chunks will never be
   * used for zipping.
   */
  def zipLatest[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(implicit zippable: Zippable[A, A2], trace: Trace): ZStream[R1, E1, zippable.Out] =
    self.zipLatestWith(that)(zippable.zip(_, _))

  /**
   * Zips the two streams so that when a value is emitted by either of the two
   * streams, it is combined with the latest value from the other stream to
   * produce a result.
   *
   * Note: tracking the latest value is done on a per-chunk basis. That means
   * that emitted elements that are not the last value in chunks will never be
   * used for zipping.
   */
  def zipLatestWith[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(f: (A, A2) => A3)(implicit trace: Trace): ZStream[R1, E1, A3] = {
    def pullNonEmpty[R, E, O](pull: ZIO[R, Option[E], Chunk[O]]): ZIO[R, Option[E], Chunk[O]] =
      pull.flatMap(chunk => if (chunk.isEmpty) pullNonEmpty(pull) else ZIO.succeed(chunk))

    ZStream.fromPull[R1, E1, A3] {
      for {
        left  <- self.toPull.map(pullNonEmpty(_))
        right <- that.toPull.map(pullNonEmpty(_))
        pull <- (ZStream.fromZIOOption {
                  left.raceWith(right)(
                    (leftDone, rightFiber) => ZIO.done(leftDone).zipWith(rightFiber.join)((_, _, true)),
                    (rightDone, leftFiber) => ZIO.done(rightDone).zipWith(leftFiber.join)((r, l) => (l, r, false))
                  )
                }.flatMap { case (l, r, leftFirst) =>
                  ZStream.fromZIO(Ref.make(l(l.size - 1) -> r(r.size - 1))).flatMap { latest =>
                    ZStream.fromChunk(
                      if (leftFirst) r.map(f(l(l.size - 1), _))
                      else l.map(f(_, r(r.size - 1)))
                    ) ++
                      ZStream
                        .repeatZIOOption(left)
                        .mergeEither(ZStream.repeatZIOOption(right))
                        .mapZIO {
                          case Left(leftChunk) =>
                            latest.modify { case (_, rightLatest) =>
                              (leftChunk.map(f(_, rightLatest)), (leftChunk(leftChunk.size - 1), rightLatest))
                            }
                          case Right(rightChunk) =>
                            latest.modify { case (leftLatest, _) =>
                              (rightChunk.map(f(leftLatest, _)), (leftLatest, rightChunk(rightChunk.size - 1)))
                            }
                        }
                        .flatMap(ZStream.fromChunk(_))
                  }
                }).toPull

      } yield pull
    }
  }

  /**
   * Zips this stream with another point-wise, but keeps only the outputs of
   * this stream.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipLeft[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A] =
    zipWithChunks(that)((cl, cr) =>
      if (cl.size > cr.size)
        (cl.take(cr.size), Left(cl.drop(cr.size)))
      else
        (cl, Right(cr.drop(cl.size)))
    )

  /**
   * Zips this stream with another point-wise, but keeps only the outputs of the
   * other stream.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipRight[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: Trace): ZStream[R1, E1, A2] =
    zipWithChunks(that)((cl, cr) =>
      if (cl.size > cr.size)
        (cr, Left(cl.drop(cr.size)))
      else
        (cr.take(cl.size), Right(cr.drop(cl.size)))
    )

  /**
   * Zips this stream with another point-wise and applies the function to the
   * paired elements.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipWith[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(f: (A, A2) => A3)(implicit trace: Trace): ZStream[R1, E1, A3] =
    zipWithChunks(that)(zipChunks(_, _, f))

  /**
   * Zips this stream with another point-wise and applies the function to the
   * paired elements.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipWithChunks[R1 <: R, E1 >: E, A1 >: A, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(
    f: (Chunk[A1], Chunk[A2]) => (Chunk[A3], Either[Chunk[A1], Chunk[A2]])
  )(implicit trace: Trace): ZStream[R1, E1, A3] = {
    sealed trait State[+A1, +A2]
    case object PullBoth                                 extends State[Nothing, Nothing]
    final case class PullLeft[A2](rightChunk: Chunk[A2]) extends State[Nothing, A2]
    final case class PullRight[A1](leftChunk: Chunk[A1]) extends State[A1, Nothing]

    def pull(
      state: State[A1, A2],
      pullLeft: ZIO[R, Option[E], Chunk[A1]],
      pullRight: ZIO[R1, Option[E1], Chunk[A2]]
    ): ZIO[R1, Nothing, Exit[Option[E1], (Chunk[A3], State[A1, A2])]] =
      state match {
        case PullBoth =>
          pullLeft.unsome
            .zipPar(pullRight.unsome)
            .foldZIO(
              err => ZIO.succeed(Exit.fail(Some(err))),
              {
                case (Some(leftChunk), Some(rightChunk)) =>
                  if (leftChunk.isEmpty && rightChunk.isEmpty)
                    pull(PullBoth, pullLeft, pullRight)
                  else if (leftChunk.isEmpty)
                    pull(PullLeft(rightChunk), pullLeft, pullRight)
                  else if (rightChunk.isEmpty)
                    pull(PullRight(leftChunk), pullLeft, pullRight)
                  else
                    ZIO.succeed(Exit.succeed(zipWithChunks(leftChunk, rightChunk)))
                case _ => ZIO.succeed(Exit.fail(None))
              }
            )
        case PullLeft(rightChunk) =>
          pullLeft.foldZIO(
            err => ZIO.succeed(Exit.fail(err)),
            leftChunk =>
              if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else
                ZIO.succeed(Exit.succeed(zipWithChunks(leftChunk, rightChunk)))
          )
        case PullRight(leftChunk) =>
          pullRight.foldZIO(
            err => ZIO.succeed(Exit.fail(err)),
            rightChunk =>
              if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else
                ZIO.succeed(Exit.succeed(zipWithChunks(leftChunk, rightChunk)))
          )
      }

    def zipWithChunks(
      leftChunk: Chunk[A1],
      rightChunk: Chunk[A2]
    ): (Chunk[A3], State[A1, A2]) =
      f(leftChunk, rightChunk) match {
        case (out, Left(leftChunk)) =>
          if (leftChunk.isEmpty)
            out -> PullBoth
          else
            out -> PullRight(leftChunk)
        case (out, Right(rightChunk)) =>
          if (rightChunk.isEmpty)
            out -> PullBoth
          else
            out -> PullLeft(rightChunk)
      }

    self.combineChunks[R1, E1, State[A1, A2], A2, A3](that)(PullBoth)(pull)
  }

  /**
   * Zips this stream together with the index of elements.
   */
  def zipWithIndex(implicit trace: Trace): ZStream[R, E, (A, Long)] =
    mapAccum(0L)((index, a) => (index + 1, (a, index)))

  /**
   * Zips the two streams so that when a value is emitted by either of the two
   * streams, it is combined with the latest value from the other stream to
   * produce a result.
   *
   * Note: tracking the latest value is done on a per-chunk basis. That means
   * that emitted elements that are not the last value in chunks will never be
   * used for zipping.
   */
  @deprecated("use zipLatestWith", "2.0.3")
  def zipWithLatest[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(f: (A, A2) => A3)(implicit trace: Trace): ZStream[R1, E1, A3] =
    zipLatestWith(that)(f)

  /**
   * Zips each element with the next element if present.
   */
  def zipWithNext(implicit trace: Trace): ZStream[R, E, (A, Option[A])] =
    self >>> ZPipeline.zipWithNext[A]

  /**
   * Zips each element with the previous element. Initially accompanied by
   * `None`.
   */
  def zipWithPrevious(implicit trace: Trace): ZStream[R, E, (Option[A], A)] =
    self >>> ZPipeline.zipWithPrevious

  /**
   * Zips each element with both the previous and next element.
   */
  def zipWithPreviousAndNext(implicit trace: Trace): ZStream[R, E, (Option[A], A, Option[A])] =
    self >>> ZPipeline.zipWithPreviousAndNext
}

object ZStream extends ZStreamPlatformSpecificConstructors {

  /**
   * The default chunk size used by the various combinators and constructors of
   * [[ZStream]].
   */
  final val DefaultChunkSize = 4096

  /**
   * Submerges the error case of an `Either` into the `ZStream`.
   */
  def absolve[R, E, O](xs: ZStream[R, E, Either[E, O]])(implicit trace: Trace): ZStream[R, E, O] = {
    lazy val loop: ZChannel[Any, E, Chunk[Either[E, O]], Any, E, Chunk[O], Any] = ZChannel.readWithCause(
      (in: Chunk[Either[E, O]]) => {
        val mapped = in.collectWhile { case Right(o) => o }
        if (mapped.size == in.size) ZChannel.write(mapped) *> loop
        else {
          val firstError = in(mapped.size).asInstanceOf[Left[E, O]]
          ZChannel.write(mapped) *> ZChannel.fail(firstError.value)
        }
      },
      (cause: Cause[E]) => ZChannel.refailCause(cause),
      (_: Any) => ZChannel.unit
    )

    xs.pipeThroughChannel(loop)
  }

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  def acquireReleaseWith[R, E, A](acquire: => ZIO[R, E, A])(release: A => URIO[R, Any])(implicit
    trace: Trace
  ): ZStream[R, E, A] =
    scoped[R](ZIO.acquireRelease(acquire)(release))

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  def acquireReleaseExitWith[R, E, A](
    acquire: => ZIO[R, E, A]
  )(release: (A, Exit[Any, Any]) => URIO[R, Any])(implicit trace: Trace): ZStream[R, E, A] =
    scoped[R](ZIO.acquireReleaseExit(acquire)(release))

  /**
   * An infinite stream of random alphanumeric characters.
   */
  def alphanumeric(implicit trace: Trace): ZStream[Any, Nothing, Char] =
    ZStream.repeatZIO {
      val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
      Random.nextIntBounded(chars.length).map(chars.charAt)
    }

  /**
   * Creates a pure stream from a variable list of values
   */
  def apply[A](as: A*)(implicit trace: Trace): ZStream[Any, Nothing, A] = fromIterable(as)

  /**
   * Locks the execution of the specified stream to the blocking executor. Any
   * streams that are composed after this one will automatically be shifted back
   * to the previous executor.
   */
  def blocking[R, E, A](stream: => ZStream[R, E, A])(implicit trace: Trace): ZStream[R, E, A] =
    ZStream.fromZIO(ZIO.blockingExecutor).flatMap(stream.onExecutor(_))

  /**
   * Concatenates all of the streams in the chunk to one stream.
   */
  def concatAll[R, E, O](streams: => Chunk[ZStream[R, E, O]])(implicit trace: Trace): ZStream[R, E, O] =
    ZStream.suspend(streams.foldLeft[ZStream[R, E, O]](empty)(_ ++ _))

  /**
   * Prints the specified message to the console for debugging purposes.
   */
  def debug(value: => Any)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.debug(value))

  /**
   * The stream that dies with the `ex`.
   */
  def die(ex: => Throwable)(implicit trace: Trace): ZStream[Any, Nothing, Nothing] =
    fromZIO(ZIO.die(ex))

  /**
   * The stream that dies with an exception described by `msg`.
   */
  def dieMessage(msg: => String)(implicit trace: Trace): ZStream[Any, Nothing, Nothing] =
    fromZIO(ZIO.dieMessage(msg))

  /**
   * The stream that ends with the [[zio.Exit]] value `exit`.
   */
  def done[E, A](exit: => Exit[E, A])(implicit trace: Trace): ZStream[Any, E, A] =
    fromZIO(ZIO.done(exit))

  /**
   * The empty stream
   */
  def empty(implicit trace: Trace): ZStream[Any, Nothing, Nothing] =
    new ZStream(ZChannel.unit)

  /**
   * Accesses the whole environment of the stream.
   */
  def environment[R](implicit trace: Trace): ZStream[R, Nothing, ZEnvironment[R]] =
    fromZIO(ZIO.environment[R])

  /**
   * Accesses the environment of the stream.
   */
  def environmentWith[R]: EnvironmentWithPartiallyApplied[R] =
    new EnvironmentWithPartiallyApplied[R]

  /**
   * Accesses the environment of the stream in the context of an effect.
   */
  def environmentWithZIO[R]: EnvironmentWithZIOPartiallyApplied[R] =
    new EnvironmentWithZIOPartiallyApplied[R]

  /**
   * Accesses the environment of the stream in the context of a stream.
   */
  def environmentWithStream[R]: EnvironmentWithStreamPartiallyApplied[R] =
    new EnvironmentWithStreamPartiallyApplied[R]

  /**
   * Creates a stream that executes the specified effect but emits no elements.
   */
  def execute[R, E](zio: => ZIO[R, E, Any])(implicit trace: Trace): ZStream[R, E, Nothing] =
    ZStream.fromZIO(zio).drain

  /**
   * The stream that always fails with the `error`
   */
  def fail[E](error: => E)(implicit trace: Trace): ZStream[Any, E, Nothing] =
    fromZIO(ZIO.fail(error))

  /**
   * The stream that always fails with `cause`.
   */
  def failCause[E](cause: => Cause[E])(implicit trace: Trace): ZStream[Any, E, Nothing] =
    fromZIO(Exit.failCause(cause))

  /**
   * Creates a one-element stream that never fails and executes the finalizer
   * when it ends.
   */
  def finalizer[R](finalizer: => URIO[R, Any])(implicit trace: Trace): ZStream[R, Nothing, Any] =
    acquireReleaseWith[R, Nothing, Unit](ZIO.unit)(_ => finalizer)

  def from[Input](
    input: => Input
  )(implicit constructor: ZStreamConstructor[Input], trace: Trace): constructor.Out =
    constructor.make(input)

  /**
   * Creates a stream from a [[zio.stream.ZChannel]]
   */
  def fromChannel[R, E, A](channel: ZChannel[R, Any, Any, Any, E, Chunk[A], Any]): ZStream[R, E, A] =
    new ZStream(channel)

  /**
   * Creates a stream from a [[zio.Chunk]] of values
   *
   * @param c
   *   a chunk of values
   * @return
   *   a finite stream of values
   */
  def fromChunk[O](chunk: => Chunk[O])(implicit trace: Trace): ZStream[Any, Nothing, O] =
    new ZStream(
      ZChannel.succeed(chunk).flatMap { chunk =>
        if (chunk.isEmpty) ZChannel.unit else ZChannel.write(chunk)
      }
    )

  /**
   * Creates a stream from a subscription to a hub.
   */
  def fromChunkHub[O](hub: => Hub[Chunk[O]])(implicit
    trace: Trace
  ): ZStream[Any, Nothing, O] =
    scoped(hub.subscribe).flatMap(queue => fromChunkQueue(queue))

  /**
   * Creates a stream from a subscription to a hub in the context of a scoped
   * effect. The scoped effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   */
  def fromChunkHubScoped[O](
    hub: => Hub[Chunk[O]]
  )(implicit trace: Trace): ZIO[Scope, Nothing, ZStream[Any, Nothing, O]] =
    hub.subscribe.map(queue => fromChunkQueue(queue))

  /**
   * Creates a stream from a subscription to a hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromChunkHubWithShutdown[O](hub: => Hub[Chunk[O]])(implicit
    trace: Trace
  ): ZStream[Any, Nothing, O] =
    fromChunkHub(hub).ensuring(hub.shutdown)

  /**
   * Creates a stream from a subscription to a hub in the context of a scoped
   * effect. The scoped effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromChunkHubScopedWithShutdown[O](
    hub: => Hub[Chunk[O]]
  )(implicit trace: Trace): ZIO[Scope, Nothing, ZStream[Any, Nothing, O]] =
    fromChunkHubScoped(hub).map(_.ensuring(hub.shutdown))

  /**
   * Creates a stream from a queue of values
   */
  def fromChunkQueue[O](
    queue: => Dequeue[Chunk[O]]
  )(implicit trace: Trace): ZStream[Any, Nothing, O] =
    repeatZIOChunkOption {
      queue.take
        .catchAllCause(c =>
          queue.isShutdown.flatMap { down =>
            if (down && c.isInterrupted) Pull.end
            else Pull.failCause(c)
          }
        )
    }

  /**
   * Creates a stream from a queue of values. The queue will be shutdown once
   * the stream is closed.
   */
  def fromChunkQueueWithShutdown[O](queue: => Dequeue[Chunk[O]])(implicit
    trace: Trace
  ): ZStream[Any, Nothing, O] =
    fromChunkQueue(queue).ensuring(queue.shutdown)

  /**
   * Creates a stream from an arbitrary number of chunks.
   */
  def fromChunks[O](cs: Chunk[O]*)(implicit trace: Trace): ZStream[Any, Nothing, O] =
    fromIterable(cs).flatMap(fromChunk(_))

  /**
   * Creates a stream from a subscription to a hub.
   */
  def fromHub[A](
    hub: => Hub[A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: Trace): ZStream[Any, Nothing, A] =
    scoped(hub.subscribe).flatMap(queue => fromQueue(queue, maxChunkSize))

  /**
   * Creates a stream from a subscription to a hub in the context of a scoped
   * effect. The scoped effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   */
  def fromHubScoped[A](
    hub: => Hub[A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: Trace): ZIO[Scope, Nothing, ZStream[Any, Nothing, A]] =
    ZIO.suspendSucceed(hub.subscribe.map(queue => fromQueueWithShutdown(queue, maxChunkSize)))

  /**
   * Creates a stream from a subscription to a hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromHubWithShutdown[A](
    hub: => Hub[A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: Trace): ZStream[Any, Nothing, A] =
    fromHub(hub, maxChunkSize).ensuring(hub.shutdown)

  /**
   * Creates a stream from a subscription to a hub in the context of a scoped
   * effect. The scoped effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromHubScopedWithShutdown[A](
    hub: => Hub[A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: Trace): ZIO[Scope, Nothing, ZStream[Any, Nothing, A]] =
    fromHubScoped(hub, maxChunkSize).map(_.ensuring(hub.shutdown))

  /**
   * Creates a stream from a `java.io.InputStream`
   */
  def fromInputStream(
    is: => InputStream,
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: Trace): ZStream[Any, IOException, Byte] =
    ZStream.succeed((is, chunkSize)).flatMap { case (is, chunkSize) =>
      ZStream.repeatZIOChunkOption {
        for {
          bufArray  <- ZIO.succeed(Array.ofDim[Byte](chunkSize))
          bytesRead <- ZIO.attemptBlockingIO(is.read(bufArray)).asSomeError
          bytes <- if (bytesRead < 0)
                     ZIO.fail(None)
                   else if (bytesRead == 0)
                     ZIO.succeed(Chunk.empty)
                   else if (bytesRead < chunkSize)
                     ZIO.succeed(Chunk.fromArray(bufArray).take(bytesRead))
                   else
                     ZIO.succeed(Chunk.fromArray(bufArray))
        } yield bytes
      }
    }

  /**
   * Creates a stream from a `java.io.InputStream`. Ensures that the input
   * stream is closed after it is exhausted.
   */
  def fromInputStreamZIO[R](
    is: => ZIO[R, IOException, InputStream],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: Trace): ZStream[R, IOException, Byte] =
    fromInputStreamScoped[R](ZIO.acquireRelease(is)(is => ZIO.succeed(is.close())), chunkSize)

  /**
   * Creates a stream from a scoped `java.io.InputStream` value.
   */
  def fromInputStreamScoped[R](
    is: => ZIO[Scope with R, IOException, InputStream],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: Trace): ZStream[R, IOException, Byte] =
    ZStream.scoped[R](is).flatMap(fromInputStream(_, chunkSize))

  /**
   * Creates a stream from an iterable collection of values
   */
  def fromIterable[O](as: => Iterable[O])(implicit trace: Trace): ZStream[Any, Nothing, O] =
    fromIterable(as, DefaultChunkSize)

  /**
   * Creates a stream from an iterable collection of values
   */
  def fromIterable[O](as: => Iterable[O], chunkSize: => Int)(implicit trace: Trace): ZStream[Any, Nothing, O] =
    ZStream.suspend {
      as match {
        case chunk: Chunk[O]       => ZStream.fromChunk(chunk)
        case iterable: Iterable[O] => ZStream.fromIteratorSucceed(iterable.iterator, chunkSize)
      }
    }

  /**
   * Creates a stream from an effect producing a value of type `Iterable[A]`
   */
  def fromIterableZIO[R, E, O](iterable: => ZIO[R, E, Iterable[O]])(implicit trace: Trace): ZStream[R, E, O] =
    fromIterableZIO(iterable, DefaultChunkSize)

  /**
   * Creates a stream from an effect producing a value of type `Iterable[A]`
   */
  def fromIterableZIO[R, E, O](iterable: => ZIO[R, E, Iterable[O]], chunkSize: => Int)(implicit
    trace: Trace
  ): ZStream[R, E, O] =
    ZStream.unwrap(iterable.map(fromIterable(_, chunkSize)))

  /** Creates a stream from an iterator */
  def fromIterator[A](iterator: => Iterator[A], maxChunkSize: => Int = DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Throwable, A] =
    ZStream.succeed(maxChunkSize).flatMap { maxChunkSize =>
      if (maxChunkSize == 1) fromIteratorSingle(iterator)
      else {
        object StreamEnd extends Throwable

        ZStream
          .fromZIO(
            FiberRef.currentFatal.get <*> ZIO.attempt(iterator) <*> ZIO.runtime[Any] <*> ZIO
              .succeed(ChunkBuilder.make[A](maxChunkSize))
          )
          .flatMap { case (isFatal, it, rt, builder) =>
            ZStream.repeatZIOChunkOption {
              ZIO.attempt {
                builder.clear()
                var count = 0

                try {
                  while (count < maxChunkSize && it.hasNext) {
                    builder += it.next()
                    count += 1
                  }
                } catch {
                  case e: Throwable if !isFatal(e) =>
                    throw e
                }

                if (count > 0) {
                  builder.result()
                } else {
                  throw StreamEnd
                }
              }.mapError {
                case StreamEnd => None
                case e         => Some(e)
              }
            }
          }
      }
    }

  private def fromIteratorSingle[A](iterator: => Iterator[A])(implicit
    trace: Trace
  ): ZStream[Any, Throwable, A] = {
    object StreamEnd extends Throwable

    ZStream.fromZIO(FiberRef.currentFatal.get <*> ZIO.attempt(iterator) <*> ZIO.runtime[Any]).flatMap {
      case (isFatal, it, rt) =>
        ZStream.repeatZIOOption {
          ZIO.attempt {

            val hasNext: Boolean =
              try it.hasNext
              catch {
                case e: Throwable if !isFatal(e) =>
                  throw e
              }

            if (hasNext) {
              try it.next()
              catch {
                case e: Throwable if !isFatal(e) =>
                  throw e
              }
            } else throw StreamEnd
          }.mapError {
            case StreamEnd => None
            case e         => Some(e)
          }
        }
    }
  }

  /**
   * Creates a stream from a scoped iterator
   */
  def fromIteratorScoped[R, A](
    iterator: => ZIO[Scope with R, Throwable, Iterator[A]],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    scoped[R](iterator).flatMap(fromIterator(_, maxChunkSize))

  /**
   * Creates a stream from an iterator
   */
  def fromIteratorSucceed[A](iterator: => Iterator[A], maxChunkSize: => Int = DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Nothing, A] = {

    def writeOneByOne(iterator: Iterator[A]): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] =
      if (iterator.hasNext)
        ZChannel.write(Chunk.single(iterator.next())) *> writeOneByOne(iterator)
      else
        ZChannel.unit

    def writeChunks(iterator: Iterator[A]): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] =
      ZChannel.succeed(ChunkBuilder.make[A]()).flatMap { builder =>
        def loop(iterator: Iterator[A]): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] = {
          builder.clear()
          var count = 0
          while (count < maxChunkSize && iterator.hasNext) {
            builder += iterator.next()
            count += 1
          }
          if (count > 0)
            ZChannel.write(builder.result()) *> loop(iterator)
          else
            ZChannel.unit
        }

        loop(iterator)
      }

    ZStream.fromChannel {
      ZChannel.suspend {
        if (maxChunkSize == 1)
          writeOneByOne(iterator)
        else
          writeChunks(iterator)
      }
    }
  }

  /**
   * Creates a stream from an iterator that may potentially throw exceptions
   */
  def fromIteratorZIO[R, A](iterator: => ZIO[R, Throwable, Iterator[A]])(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    fromIteratorZIO(iterator, DefaultChunkSize)

  /**
   * Creates a stream from an iterator that may potentially throw exceptions
   */
  def fromIteratorZIO[R, A](
    iterator: => ZIO[R, Throwable, Iterator[A]],
    chunkSize: Int
  )(implicit trace: Trace): ZStream[R, Throwable, A] =
    fromZIO(iterator).flatMap(fromIterator(_, chunkSize))

  /**
   * Creates a stream from a Java iterator that may throw exceptions
   */
  def fromJavaIterator[A](iterator: => java.util.Iterator[A])(implicit trace: Trace): ZStream[Any, Throwable, A] =
    fromJavaIterator(iterator, DefaultChunkSize)

  /**
   * Creates a stream from a Java iterator that may throw exceptions
   */
  def fromJavaIterator[A](
    iterator: => java.util.Iterator[A],
    chunkSize: Int
  )(implicit trace: Trace): ZStream[Any, Throwable, A] =
    fromIterator(
      {
        val it = iterator // Scala 2.13 scala.collection.Iterator has `iterator` in local scope
        new Iterator[A] {
          def next(): A        = it.next
          def hasNext: Boolean = it.hasNext
        }
      },
      chunkSize
    )

  /**
   * Creates a stream from a scoped iterator
   */
  def fromJavaIteratorScoped[R, A](iterator: => ZIO[Scope with R, Throwable, java.util.Iterator[A]])(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    fromJavaIteratorScoped[R, A](iterator, DefaultChunkSize)

  /**
   * Creates a stream from a scoped iterator
   */
  def fromJavaIteratorScoped[R, A](
    iterator: => ZIO[Scope with R, Throwable, java.util.Iterator[A]],
    chunkSize: Int
  )(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    scoped[R](iterator).flatMap(fromJavaIterator(_, chunkSize))

  /**
   * Creates a stream from a Java iterator
   */
  def fromJavaIteratorSucceed[A](iterator: => java.util.Iterator[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
    fromJavaIteratorSucceed(iterator, DefaultChunkSize)

  /**
   * Creates a stream from a Java iterator
   */
  def fromJavaIteratorSucceed[A](
    iterator: => java.util.Iterator[A],
    chunkSize: Int
  )(implicit trace: Trace): ZStream[Any, Nothing, A] =
    fromIteratorSucceed(
      {
        val it = iterator // Scala 2.13 scala.collection.Iterator has `iterator` in local scope
        new Iterator[A] {
          def next(): A        = it.next
          def hasNext: Boolean = it.hasNext
        }
      },
      chunkSize
    )

  /**
   * Creates a stream from a Java iterator that may potentially throw exceptions
   */
  def fromJavaIteratorZIO[R, A](iterator: => ZIO[R, Throwable, java.util.Iterator[A]])(implicit
    trace: Trace
  ): ZStream[R, Throwable, A] =
    fromJavaIteratorZIO(iterator, DefaultChunkSize)

  /**
   * Creates a stream from a Java iterator that may potentially throw exceptions
   */
  def fromJavaIteratorZIO[R, A](
    iterator: => ZIO[R, Throwable, java.util.Iterator[A]],
    chunkSize: Int
  )(implicit trace: Trace): ZStream[R, Throwable, A] =
    fromZIO(iterator).flatMap(fromJavaIterator(_, chunkSize))

  /**
   * Creates a stream from a ZIO effect that pulls elements from another stream.
   * See `toPull` for reference
   */
  def fromPull[R, E, A](zio: ZIO[Scope with R, Nothing, ZIO[R, Option[E], Chunk[A]]])(implicit
    trace: Trace
  ): ZStream[R, E, A] =
    ZStream.unwrapScoped[R](zio.map(pull => repeatZIOChunkOption(pull)))

  /**
   * Creates a stream from a queue of values
   *
   * @param maxChunkSize
   *   Maximum number of queued elements to put in one chunk in the stream
   */
  def fromQueue[O](
    queue: => Dequeue[O],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: Trace): ZStream[Any, Nothing, O] =
    repeatZIOChunkOption {
      queue
        .takeBetween(1, maxChunkSize)
        .catchAllCause(c =>
          queue.isShutdown.flatMap { down =>
            if (down && c.isInterrupted) Pull.end
            else Pull.failCause(c)
          }
        )
    }

  /**
   * Creates a stream from a queue of values. The queue will be shutdown once
   * the stream is closed.
   *
   * @param maxChunkSize
   *   Maximum number of queued elements to put in one chunk in the stream
   */
  def fromQueueWithShutdown[O](
    queue: => Dequeue[O],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: Trace): ZStream[Any, Nothing, O] =
    fromQueue(queue, maxChunkSize).ensuring(queue.shutdown)

  /**
   * Creates a stream from a [[zio.Schedule]] that does not require any further
   * input. The stream will emit an element for each value output from the
   * schedule, continuing for as long as the schedule continues.
   */
  def fromSchedule[R, A](schedule: => Schedule[R, Any, A])(implicit
    trace: Trace
  ): ZStream[R, Nothing, A] =
    unwrap(schedule.driver.map(driver => repeatZIOOption(driver.next(()))))

  /**
   * Creates a stream from a [[zio.stm.TPriorityQueue]] of values.
   */
  def fromTPriorityQueue[A](queue: => TPriorityQueue[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
    repeatZIOChunk(queue.take.map(Chunk.single(_)).commit)

  /**
   * Creates a stream from a [[zio.stm.TQueue]] of values.
   */
  def fromTQueue[A](queue: => TDequeue[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
    repeatZIOChunk(queue.take.map(Chunk.single(_)).commit)

  /**
   * Creates a stream from an effect producing a value of type `A`
   */
  def fromZIO[R, E, A](fa: => ZIO[R, E, A])(implicit trace: Trace): ZStream[R, E, A] =
    fromZIOOption(fa.mapError(Some(_)))

  /**
   * Creates a stream from an effect producing a value of type `A` or an empty
   * Stream
   */
  def fromZIOOption[R, E, A](fa: => ZIO[R, Option[E], A])(implicit trace: Trace): ZStream[R, E, A] =
    new ZStream(
      ZChannel.unwrap(
        fa.fold(
          {
            case Some(e) => ZChannel.fail(e)
            case None    => ZChannel.unit
          },
          a => ZChannel.write(Chunk.single(a))
        )
      )
    )

  /**
   * The infinite stream of iterative function application: a, f(a), f(f(a)),
   * f(f(f(a))), ...
   */
  def iterate[A](a: => A)(f: A => A)(implicit trace: Trace): ZStream[Any, Nothing, A] =
    unfold(a)(a => Some((a, f(a))))

  /**
   * Logs the specified message at the current log level.
   */
  def log(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.log(message))

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate(key: => String, value: => String)(implicit
    trace: Trace
  ): ZStream[Any, Nothing, Unit] =
    logAnnotate(LogAnnotation(key, value))

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate(annotation: => LogAnnotation, annotations: LogAnnotation*)(implicit
    trace: Trace
  ): ZStream[Any, Nothing, Unit] =
    logAnnotate(Set(annotation) ++ annotations.toSet)

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate(annotations: => Set[LogAnnotation])(implicit
    trace: Trace
  ): ZStream[Any, Nothing, Unit] =
    ZStream.scoped(ZIO.logAnnotateScoped(annotations))

  /**
   * Retrieves the log annotations associated with the current scope.
   */
  def logAnnotations(implicit trace: Trace): ZStream[Any, Nothing, Map[String, String]] =
    ZStream.fromZIO(FiberRef.currentLogAnnotations.get)

  /**
   * Logs the specified message at the debug log level.
   */
  def logDebug(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logDebug(message))

  /**
   * Logs the specified message at the error log level.
   */
  def logError(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logError(message))

  /**
   * Logs the specified cause as an error.
   */
  def logErrorCause(cause: => Cause[Any])(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logErrorCause(cause))

  /**
   * Logs the specified message at the fatal log level.
   */
  def logFatal(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logFatal(message))

  /**
   * Logs the specified message at the informational log level.
   */
  def logInfo(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logInfo(message))

  /**
   * Sets the log level for streams composed after this.
   */
  def logLevel(level: LogLevel)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.scoped(ZIO.logLevelScoped(level))

  /**
   * Adjusts the label for the logging span for streams composed after this.
   */
  def logSpan(label: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.scoped(ZIO.logSpanScoped(label))

  /**
   * Logs the specified message at the trace log level.
   */
  def logTrace(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logTrace(message))

  /**
   * Logs the specified message at the warning log level.
   */
  def logWarning(message: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logWarning(message))

  /**
   * Creates a single-valued stream from a scoped resource
   */
  def scoped[R]: ScopedPartiallyApplied[R] =
    new ScopedPartiallyApplied[R]

  /**
   * Creates a single-valued stream from a scoped resource
   */
  def scopedWith[R, E, A](f: Scope => ZIO[R, E, A])(implicit trace: Trace): ZStream[R, E, A] =
    new ZStream(ZChannel.scopedWith(scope => f(scope).map(Chunk.single)))

  /**
   * Merges a variable list of streams in a non-deterministic fashion. Up to `n`
   * streams may be consumed in parallel and up to `outputBuffer` chunks may be
   * buffered by this operator.
   */
  def mergeAll[R, E, O](n: => Int, outputBuffer: => Int = 16)(
    streams: ZStream[R, E, O]*
  )(implicit trace: Trace): ZStream[R, E, O] =
    fromIterable(streams).flattenPar(n, outputBuffer)

  /**
   * Like [[mergeAll]], but runs all streams concurrently.
   */
  def mergeAllUnbounded[R, E, O](outputBuffer: => Int = 16)(
    streams: ZStream[R, E, O]*
  )(implicit trace: Trace): ZStream[R, E, O] = mergeAll(Int.MaxValue, outputBuffer)(streams: _*)

  /**
   * The stream that never produces any value or fails with any error.
   */
  def never(implicit trace: Trace): ZStream[Any, Nothing, Nothing] =
    ZStream.fromZIO(ZIO.never)

  /**
   * Like [[unfold]], but allows the emission of values to end one step further
   * than the unfolding of the state. This is useful for embedding paginated
   * APIs, hence the name.
   */
  def paginate[R, E, A, S](s: => S)(f: S => (A, Option[S]))(implicit trace: Trace): ZStream[Any, Nothing, A] =
    paginateChunk(s) { s =>
      val page = f(s)
      Chunk.single(page._1) -> page._2
    }

  /**
   * Like [[unfoldChunk]], but allows the emission of values to end one step
   * further than the unfolding of the state. This is useful for embedding
   * paginated APIs, hence the name.
   */
  def paginateChunk[A, S](
    s: => S
  )(f: S => (Chunk[A], Option[S]))(implicit trace: Trace): ZStream[Any, Nothing, A] = {
    def loop(s: S): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] =
      f(s) match {
        case (as, Some(s)) => ZChannel.write(as) *> loop(s)
        case (as, None)    => ZChannel.write(as) *> ZChannel.unit
      }

    new ZStream(loop(s))
  }

  /**
   * Like [[unfoldChunkZIO]], but allows the emission of values to end one step
   * further than the unfolding of the state. This is useful for embedding
   * paginated APIs, hence the name.
   */
  def paginateChunkZIO[R, E, A, S](
    s: => S
  )(f: S => ZIO[R, E, (Chunk[A], Option[S])])(implicit trace: Trace): ZStream[R, E, A] = {
    def loop(s: S): ZChannel[R, Any, Any, Any, E, Chunk[A], Any] =
      ZChannel.unwrap {
        f(s).map {
          case (as, Some(s)) => ZChannel.write(as) *> loop(s)
          case (as, None)    => ZChannel.write(as) *> ZChannel.unit
        }
      }

    new ZStream(ZChannel.suspend(loop(s)))
  }

  def provideLayer[RIn, E, ROut, RIn2, ROut2](layer: ZLayer[RIn, E, ROut])(
    stream: => ZStream[ROut with RIn2, E, ROut2]
  )(implicit
    ev: EnvironmentTag[RIn2],
    tag: EnvironmentTag[ROut],
    trace: Trace
  ): ZStream[RIn with RIn2, E, ROut2] =
    ZStream.suspend(stream.provideSomeLayer[RIn with RIn2](ZLayer.environment[RIn2] ++ layer))

  /**
   * Like [[unfoldZIO]], but allows the emission of values to end one step
   * further than the unfolding of the state. This is useful for embedding
   * paginated APIs, hence the name.
   */
  def paginateZIO[R, E, A, S](s: => S)(f: S => ZIO[R, E, (A, Option[S])])(implicit
    trace: Trace
  ): ZStream[R, E, A] =
    paginateChunkZIO(s)(f(_).map { case (a, s) => Chunk.single(a) -> s })

  /**
   * Constructs a stream from a range of integers (lower bound included, upper
   * bound not included)
   */
  def range(min: => Int, max: => Int, chunkSize: => Int = DefaultChunkSize)(implicit
    trace: Trace
  ): ZStream[Any, Nothing, Int] =
    ZStream.suspend {
      def go(min: Int, max: Int, chunkSize: Int): ZChannel[Any, Any, Any, Any, Nothing, Chunk[Int], Any] = {
        val remaining = max - min

        if (remaining > chunkSize)
          ZChannel.write(Chunk.fromArray(Array.range(min, min + chunkSize))) *> go(min + chunkSize, max, chunkSize)
        else {
          ZChannel.write(Chunk.fromArray(Array.range(min, min + remaining)))
        }
      }

      new ZStream(go(min, max, chunkSize))
    }

  /**
   * Repeats the provided value infinitely.
   */
  def repeat[A](a: => A)(implicit trace: Trace): ZStream[Any, Nothing, A] =
    new ZStream(ZChannel.succeed(a).flatMap(a => ZChannel.write(Chunk.single(a)).repeated))

  /**
   * Repeats the value using the provided schedule.
   */
  def repeatWithSchedule[R, A](a: => A, schedule: => Schedule[R, A, _])(implicit
    trace: Trace
  ): ZStream[R, Nothing, A] =
    repeatZIOWithSchedule(ZIO.succeed(a), schedule)

  /**
   * Creates a stream from an effect producing a value of type `A` which repeats
   * forever.
   */
  def repeatZIO[R, E, A](fa: => ZIO[R, E, A])(implicit trace: Trace): ZStream[R, E, A] =
    repeatZIOOption(fa.mapError(Some(_)))

  /**
   * Creates a stream from an effect producing chunks of `A` values which
   * repeats forever.
   */
  def repeatZIOChunk[R, E, A](fa: => ZIO[R, E, Chunk[A]])(implicit trace: Trace): ZStream[R, E, A] =
    repeatZIOChunkOption(fa.mapError(Some(_)))

  /**
   * Creates a stream from an effect producing chunks of `A` values until it
   * fails with None.
   */
  def repeatZIOChunkOption[R, E, A](
    fa: => ZIO[R, Option[E], Chunk[A]]
  )(implicit trace: Trace): ZStream[R, E, A] =
    unfoldChunkZIO(fa)(fa =>
      fa.map(chunk => Some((chunk, fa))).catchAll {
        case None    => Exit.none
        case Some(e) => Exit.fail(e)
      }
    )

  /**
   * Creates a stream from an effect producing values of type `A` until it fails
   * with None.
   */
  def repeatZIOOption[R, E, A](fa: => ZIO[R, Option[E], A])(implicit trace: Trace): ZStream[R, E, A] =
    repeatZIOChunkOption(fa.map(Chunk.single))

  /**
   * Creates a stream from an effect producing a value of type `A`, which is
   * repeated using the specified schedule.
   */
  def repeatZIOWithSchedule[R, E, A](
    effect: => ZIO[R, E, A],
    schedule: => Schedule[R, A, Any]
  )(implicit trace: Trace): ZStream[R, E, A] =
    ZStream((effect, schedule)).flatMap { case (effect, schedule) =>
      ZStream.fromZIO(effect zip schedule.driver).flatMap { case (a, driver) =>
        ZStream.succeed(a) ++
          ZStream.unfoldZIO(a)(driver.next(_).foldZIO(ZIO.succeed(_), _ => effect.map(nextA => Some(nextA -> nextA))))
      }
    }

  /**
   * Accesses the specified service in the environment of the effect.
   */
  def service[A: Tag](implicit trace: Trace): ZStream[A, Nothing, A] =
    ZStream.serviceWith(identity)

  /**
   * Accesses the service corresponding to the specified key in the environment.
   */
  def serviceAt[Service]: ZStream.ServiceAtPartiallyApplied[Service] =
    new ZStream.ServiceAtPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the stream.
   */
  def serviceWith[Service]: ServiceWithPartiallyApplied[Service] =
    new ServiceWithPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the stream in the
   * context of an effect.
   */
  def serviceWithZIO[Service]: ServiceWithZIOPartiallyApplied[Service] =
    new ServiceWithZIOPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the stream in the
   * context of a stream.
   */
  def serviceWithStream[Service]: ServiceWithStreamPartiallyApplied[Service] =
    new ServiceWithStreamPartiallyApplied[Service]

  /**
   * Creates a single-valued pure stream
   */
  def succeed[A](a: => A)(implicit trace: Trace): ZStream[Any, Nothing, A] =
    fromChunk(Chunk.single(a))

  /**
   * Returns a lazily constructed stream.
   */
  def suspend[R, E, A](stream: => ZStream[R, E, A]): ZStream[R, E, A] =
    new ZStream(ZChannel.suspend(stream.channel))

  /**
   * Annotates each metric in streams composed after this with the specified
   * tag.
   */
  def tagged(key: => String, value: => String)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    tagged(Set(MetricLabel(key, value)))

  /**
   * Annotates each metric in streams composed after this with the specified
   * tag.
   */
  def tagged(tag: => MetricLabel, tags: MetricLabel*)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    tagged(Set(tag) ++ tags.toSet)

  /**
   * Annotates each metric in streams composed after this with the specified
   * tag.
   */
  def tagged(tags: => Set[MetricLabel])(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    ZStream.scoped(ZIO.taggedScoped(tags))

  /**
   * Retrieves the metric tags associated with the current scope.
   */
  def tags(implicit trace: Trace): ZStream[Any, Nothing, Set[MetricLabel]] =
    ZStream.fromZIO(ZIO.tags)

  /**
   * A stream that emits Unit values spaced by the specified duration.
   */
  def tick(interval: => Duration)(implicit trace: Trace): ZStream[Any, Nothing, Unit] =
    repeatWithSchedule((), Schedule.spaced(interval))

  /**
   * A stream that contains a single `Unit` value.
   */
  val unit: ZStream[Any, Nothing, Unit] =
    succeed(())(Trace.empty)

  /**
   * Creates a stream by peeling off the "layers" of a value of type `S`
   */
  def unfold[S, A](s: => S)(f: S => Option[(A, S)])(implicit trace: Trace): ZStream[Any, Nothing, A] =
    unfoldChunk(s)(f(_).map { case (a, s) => Chunk.single(a) -> s })

  /**
   * Creates a stream by peeling off the "layers" of a value of type `S`.
   */
  def unfoldChunk[S, A](
    s: => S
  )(f: S => Option[(Chunk[A], S)])(implicit trace: Trace): ZStream[Any, Nothing, A] = {
    def loop(s: S): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] =
      f(s) match {
        case Some((as, s)) => ZChannel.write(as) *> loop(s)
        case None          => ZChannel.unit
      }

    new ZStream(ZChannel.suspend(loop(s)))
  }

  /**
   * Creates a stream by effectfully peeling off the "layers" of a value of type
   * `S`
   */
  def unfoldChunkZIO[R, E, A, S](
    s: => S
  )(f: S => ZIO[R, E, Option[(Chunk[A], S)]])(implicit trace: Trace): ZStream[R, E, A] = {
    def loop(s: S): ZChannel[R, Any, Any, Any, E, Chunk[A], Any] =
      ZChannel.unwrap {
        f(s).map {
          case Some((as, s)) => ZChannel.write(as) *> loop(s)
          case None          => ZChannel.unit
        }
      }

    new ZStream(loop(s))
  }

  /**
   * Creates a stream by effectfully peeling off the "layers" of a value of type
   * `S`
   */
  def unfoldZIO[R, E, A, S](s: => S)(f: S => ZIO[R, E, Option[(A, S)]])(implicit
    trace: Trace
  ): ZStream[R, E, A] =
    unfoldChunkZIO(s)(f(_).map(_.map { case (a, s) => Chunk.single(a) -> s }))

  /**
   * Creates a stream produced from an effect
   */
  def unwrap[R, E, A](fa: => ZIO[R, E, ZStream[R, E, A]])(implicit trace: Trace): ZStream[R, E, A] =
    fromZIO(fa).flatten

  /**
   * Creates a stream produced from a scoped [[ZIO]]
   */
  def unwrapScoped[R]: UnwrapScopedPartiallyApplied[R] =
    new UnwrapScopedPartiallyApplied[R]

  /**
   * Creates a stream produced from a scoped [[ZIO]]
   */
  def unwrapScopedWith[R, E, A](f: Scope => ZIO[R, E, ZStream[R, E, A]])(implicit trace: Trace): ZStream[R, E, A] =
    scopedWith(scope => f(scope)).flatten

  /**
   * Returns the specified stream if the given condition is satisfied, otherwise
   * returns an empty stream.
   */
  def when[R, E, O](b: => Boolean)(zStream: => ZStream[R, E, O])(implicit trace: Trace): ZStream[R, E, O] =
    whenZIO(ZIO.succeed(b))(zStream)

  /**
   * Returns the resulting stream when the given `PartialFunction` is defined
   * for the given value, otherwise returns an empty stream.
   */
  def whenCase[R, E, A, O](a: => A)(pf: PartialFunction[A, ZStream[R, E, O]])(implicit
    trace: Trace
  ): ZStream[R, E, O] =
    whenCaseZIO(ZIO.succeed(a))(pf)

  /**
   * Returns the resulting stream when the given `PartialFunction` is defined
   * for the given effectful value, otherwise returns an empty stream.
   */
  def whenCaseZIO[R, E, A](a: => ZIO[R, E, A]): WhenCaseZIO[R, E, A] =
    new WhenCaseZIO(() => a)

  /**
   * Returns the specified stream if the given effectful condition is satisfied,
   * otherwise returns an empty stream.
   */
  def whenZIO[R, E](b: => ZIO[R, E, Boolean]) =
    new WhenZIO(() => b)

  final class EnvironmentWithPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: ZEnvironment[R] => A)(implicit trace: Trace): ZStream[R, Nothing, A] =
      ZStream.environment[R].map(f)
  }

  final class EnvironmentWithZIOPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, A](f: ZEnvironment[R] => ZIO[R1, E, A])(implicit
      trace: Trace
    ): ZStream[R with R1, E, A] =
      ZStream.environment[R].mapZIO(f)
  }

  final class EnvironmentWithStreamPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, A](f: ZEnvironment[R] => ZStream[R1, E, A])(implicit
      trace: Trace
    ): ZStream[R with R1, E, A] =
      ZStream.environment[R].flatMap(f)
  }

  final class ScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](zio: => ZIO[Scope with R, E, A])(implicit trace: Trace): ZStream[R, E, A] =
      new ZStream(ZChannel.scoped[R](zio.map(Chunk.single)))
  }

  final class ServiceAtPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Key](
      key: => Key
    )(implicit
      tag: EnvironmentTag[Map[Key, Service]],
      trace: Trace
    ): ZStream[Map[Key, Service], Nothing, Option[Service]] =
      ZStream.environmentWith(_.getAt(key))
  }

  final class ServiceWithPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: Service => A)(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZStream[Service, Nothing, A] =
      ZStream.fromZIO(ZIO.serviceWith[Service](f))
  }

  final class ServiceWithZIOPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, A](f: Service => ZIO[R, E, A])(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZStream[R with Service, E, A] =
      ZStream.fromZIO(ZIO.serviceWithZIO[Service](f))
  }

  final class ServiceWithStreamPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, A](f: Service => ZStream[R, E, A])(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZStream[R with Service, E, A] =
      ZStream.service[Service].flatMap(f)
  }

  final class UnwrapScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](fa: => ZIO[Scope with R, E, ZStream[R, E, A]])(implicit
      trace: Trace
    ): ZStream[R, E, A] =
      scoped[R](fa).flatten
  }

  /**
   * Representation of a grouped stream. This allows to filter which groups will
   * be processed. Once this is applied all groups will be processed in parallel
   * and the results will be merged in arbitrary order.
   */
  sealed trait GroupBy[-R, +E, +K, +V] { self =>

    protected def grouped(implicit trace: Trace): ZStream[R, E, (K, Dequeue[Take[E, V]])]

    /**
     * Run the function across all groups, collecting the results in an
     * arbitrary order.
     */
    def apply[R1 <: R, E1 >: E, A](f: (K, ZStream[Any, E, V]) => ZStream[R1, E1, A], buffer: => Int = 16)(implicit
      trace: Trace
    ): ZStream[R1, E1, A] =
      grouped.flatMapPar[R1, E1, A](Int.MaxValue, buffer) { case (k, q) =>
        f(k, ZStream.fromQueueWithShutdown(q).flattenTake)
      }

    /**
     * Only consider the first n groups found in the stream.
     */
    def first(n: => Int): GroupBy[R, E, K, V] =
      new GroupBy[R, E, K, V] {
        override def grouped(implicit trace: Trace): ZStream[R, E, (K, Dequeue[Take[E, V]])] =
          self.grouped.zipWithIndex.filterZIO { case elem @ ((_, q), i) =>
            if (i < n) ZIO.succeed(elem).as(true)
            else q.shutdown.as(false)
          }.map(_._1)
      }

    /**
     * Filter the groups to be processed.
     */
    def filter(f: K => Boolean): GroupBy[R, E, K, V] =
      new GroupBy[R, E, K, V] {
        override def grouped(implicit trace: Trace): ZStream[R, E, (K, Dequeue[Take[E, V]])] =
          self.grouped.filterZIO { case elem @ (k, q) =>
            if (f(k)) ZIO.succeed(elem).as(true)
            else q.shutdown.as(false)
          }
      }
  }

  final class ProvideSomeLayer[R0, -R, +E, +A](private val self: ZStream[R, E, A]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: => ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: Trace
    ): ZStream[R0, E1, A] =
      self.asInstanceOf[ZStream[R0 with R1, E, A]].provideLayer(ZLayer.environment[R0] ++ layer)
  }

  final class UpdateService[-R, +E, +A, M](private val self: ZStream[R, E, A]) extends AnyVal {
    def apply[R1 <: R with M](
      f: M => M
    )(implicit tag: Tag[M], trace: Trace): ZStream[R1, E, A] =
      self.provideSomeEnvironment(_.update(f))
  }

  final class UpdateServiceAt[-R, +E, +A, Service](private val self: ZStream[R, E, A]) extends AnyVal {
    def apply[R1 <: R with Map[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit tag: Tag[Map[Key, Service]], trace: Trace): ZStream[R1, E, A] =
      self.provideSomeEnvironment(_.updateAt(key)(f))
  }

  /**
   * A `ZStreamConstructor[Input]` knows how to construct a `ZStream` value from
   * an input of type `Input`. This allows the type of the `ZStream` value
   * constructed to depend on `Input`.
   */
  trait ZStreamConstructor[Input] {

    /**
     * The type of the `ZStream` value.
     */
    type Out

    /**
     * Constructs a `ZStream` value from the specified input.
     */
    def make(input: => Input)(implicit trace: Trace): Out
  }

  object ZStreamConstructor extends ZStreamConstructorPlatformSpecific {

    /**
     * Constructs a `ZStream[R, E, A]` from a [[zio.stream.ZChannel]]
     */
    implicit def ChannelConstructor[R, E, A]: WithOut[ZChannel[R, Any, Any, Any, E, Chunk[A], Any], ZStream[R, E, A]] =
      new ZStreamConstructor[ZChannel[R, Any, Any, Any, E, Chunk[A], Any]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZChannel[R, Any, Any, Any, E, Chunk[A], Any])(implicit trace: Trace): ZStream[R, E, A] =
          ZStream.fromChannel(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `Hub[Chunk[A]]`.
     */
    implicit def ChunkHubConstructor[A]: WithOut[Hub[Chunk[A]], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Hub[Chunk[A]]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Hub[Chunk[A]])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromChunkHub(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a Queue[Chunk[A]]`.
     */
    implicit def ChunkQueueConstructor[A]: WithOut[Queue[Chunk[A]], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Queue[Chunk[A]]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Queue[Chunk[A]])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromChunkQueue(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from an `Iterable[Chunk[A]]`.
     */
    implicit def ChunksConstructor[A, Collection[Element] <: Iterable[Element]]
      : WithOut[Collection[Chunk[A]], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Collection[Chunk[A]]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Collection[Chunk[A]])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromIterable(input).flatMap(ZStream.fromChunk(_))
      }

    /**
     * Constructs a `ZStream[R, E, A]` from a `ZIO[R, E, Iterable[A]]`.
     */
    implicit def IterableZIOConstructor[R, E, A, Collection[Element] <: Iterable[Element]]
      : WithOut[ZIO[R, E, Collection[A]], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, E, Collection[A]]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, E, Collection[A]])(implicit trace: Trace): ZStream[R, E, A] =
          ZStream.fromIterableZIO(input)
      }

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from an `Iterator[A]`.
     */
    implicit def IteratorConstructor[A, IteratorLike[Element] <: Iterator[Element]]
      : WithOut[IteratorLike[A], ZStream[Any, Throwable, A]] =
      new ZStreamConstructor[IteratorLike[A]] {
        type Out = ZStream[Any, Throwable, A]
        def make(input: => IteratorLike[A])(implicit trace: Trace): ZStream[Any, Throwable, A] =
          ZStream.fromIterator(input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZIO[R with Scope,
     * Throwable, Iterator[A]]`.
     */
    implicit def IteratorScopedConstructor[R, E <: Throwable, A, IteratorLike[Element] <: Iterator[Element]]
      : WithOut[ZIO[Scope with R, E, IteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[Scope with R, E, IteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[Scope with R, E, IteratorLike[A]])(implicit
          trace: Trace
        ): ZStream[R, Throwable, A] =
          ZStream.fromIteratorScoped[R, A](input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZIO[R, Throwable,
     * Iterator[A]]`.
     */
    implicit def IteratorZIOConstructor[R, E <: Throwable, A, IteratorLike[Element] <: Iterator[Element]]
      : WithOut[ZIO[R, E, IteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[R, E, IteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[R, E, IteratorLike[A]])(implicit trace: Trace): ZStream[R, Throwable, A] =
          ZStream.fromIteratorZIO(input)
      }

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from a `java.util.Iterator[A]`.
     */
    implicit def JavaIteratorConstructor[A, JaveIteratorLike[Element] <: java.util.Iterator[Element]]
      : WithOut[JaveIteratorLike[A], ZStream[Any, Throwable, A]] =
      new ZStreamConstructor[JaveIteratorLike[A]] {
        type Out = ZStream[Any, Throwable, A]
        def make(input: => JaveIteratorLike[A])(implicit trace: Trace): ZStream[Any, Throwable, A] =
          ZStream.fromJavaIterator(input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZIO[R with Scope,
     * Throwable, java.util.Iterator[A]]`.
     */
    implicit def JavaIteratorScopedConstructor[R, E <: Throwable, A, JaveIteratorLike[Element] <: java.util.Iterator[
      Element
    ]]: WithOut[ZIO[Scope with R, E, JaveIteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[Scope with R, E, JaveIteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[Scope with R, E, JaveIteratorLike[A]])(implicit
          trace: Trace
        ): ZStream[R, Throwable, A] =
          ZStream.fromJavaIteratorScoped[R, A](input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZIO[R, Throwable,
     * java.util.Iterator[A]]`.
     */
    implicit def JavaIteratorZIOConstructor[R, E <: Throwable, A, JaveIteratorLike[Element] <: java.util.Iterator[
      Element
    ]]: WithOut[ZIO[R, E, JaveIteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[R, E, JaveIteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[R, E, JaveIteratorLike[A]])(implicit trace: Trace): ZStream[R, Throwable, A] =
          ZStream.fromJavaIteratorZIO(input)
      }

    /**
     * Constructs a `ZStream[R, Nothing, A]` from a `Schedule[R, Any, A]`.
     */
    implicit def ScheduleConstructor[R, A]: WithOut[Schedule[R, Any, A], ZStream[R, Nothing, A]] =
      new ZStreamConstructor[Schedule[R, Any, A]] {
        type Out = ZStream[R, Nothing, A]
        def make(input: => Schedule[R, Any, A])(implicit trace: Trace): ZStream[R, Nothing, A] =
          ZStream.fromSchedule(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `TQueue[A]`.
     */
    implicit def TQueueConstructor[A]: WithOut[TQueue[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[TQueue[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => TQueue[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromTQueue(input)
      }
  }

  trait ZStreamConstructorLowPriority1 extends ZStreamConstructorLowPriority2 {

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `Chunk[A]`.
     */
    implicit def ChunkConstructor[A]: WithOut[Chunk[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Chunk[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Chunk[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromChunk(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `Hub[A]`.
     */
    implicit def HubConstructor[A]: WithOut[Hub[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Hub[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Hub[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromHub(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `Iterable[A]`.
     */
    implicit def IterableConstructor[A, Collection[Element] <: Iterable[Element]]
      : WithOut[Collection[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Collection[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Collection[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromIterable(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `Queue[A]`.
     */
    implicit def QueueConstructor[A]: WithOut[Queue[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Queue[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Queue[A])(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.fromQueue(input)
      }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, Option[E], A]`.
     */
    implicit def ZIOOptionConstructor[R, E, A]: WithOut[ZIO[R, Option[E], A], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, Option[E], A]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, Option[E], A])(implicit trace: Trace): ZStream[R, E, A] =
          ZStream.fromZIOOption(input)
      }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, Option[E], A]`.
     */
    implicit def ZIOOptionNoneConstructor[R, A]: WithOut[ZIO[R, None.type, A], ZStream[R, Nothing, A]] =
      new ZStreamConstructor[ZIO[R, None.type, A]] {
        type Out = ZStream[R, Nothing, A]
        def make(input: => ZIO[R, None.type, A])(implicit trace: Trace): ZStream[R, Nothing, A] =
          ZStream.fromZIOOption(input)
      }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, Option[E], A]`.
     */
    implicit def ZIOOptionSomeConstructor[R, E, A]: WithOut[ZIO[R, Some[E], A], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, Some[E], A]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, Some[E], A])(implicit trace: Trace): ZStream[R, E, A] =
          ZStream.fromZIOOption(input)
      }
  }

  trait ZStreamConstructorLowPriority2 extends ZStreamConstructorLowPriority3 {

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, E, A]`.
     */
    implicit def ZIOConstructor[R, E, A]: WithOut[ZIO[R, E, A], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, E, A]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, E, A])(implicit trace: Trace): ZStream[R, E, A] =
          ZStream.fromZIO(input)
      }
  }

  trait ZStreamConstructorLowPriority3 {

    /**
     * The type of the `ZStreamConstructor` with the type of the `ZStream`
     * value.
     */
    type WithOut[In, Out0] = ZStreamConstructor[In] { type Out = Out0 }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, E, A]`.
     */
    implicit def SucceedConstructor[A]: WithOut[A, ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[A] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => A)(implicit trace: Trace): ZStream[Any, Nothing, A] =
          ZStream.succeed(input)
      }
  }

  type Pull[-R, +E, +A] = ZIO[R, Option[E], Chunk[A]]

  private[zio] object Pull {
    def emit[A](a: A)(implicit trace: Trace): IO[Nothing, Chunk[A]]         = ZIO.succeed(Chunk.single(a))
    def emit[A](as: Chunk[A])(implicit trace: Trace): IO[Nothing, Chunk[A]] = ZIO.succeed(as)
    def fromDequeue[E, A](d: Dequeue[stream.Take[E, A]])(implicit trace: Trace): IO[Option[E], Chunk[A]] =
      d.take.flatMap(_.done)
    def fail[E](e: E)(implicit trace: Trace): IO[Option[E], Nothing] = ZIO.fail(Some(e))
    def failCause[E](c: Cause[E])(implicit trace: Trace): IO[Option[E], Nothing] =
      Exit.failCause(c).mapError(Some(_))
    def empty[A](implicit trace: Trace): IO[Nothing, Chunk[A]]   = ZIO.succeed(Chunk.empty)
    def end(implicit trace: Trace): IO[Option[Nothing], Nothing] = Exit.failNone
  }

  private[zio] case class BufferedPull[R, E, A](
    upstream: ZIO[R, Option[E], Chunk[A]],
    done: Ref[Boolean],
    cursor: Ref[(Chunk[A], Int)]
  ) {
    def ifNotDone[R1, E1, A1](fa: ZIO[R1, Option[E1], A1])(implicit trace: Trace): ZIO[R1, Option[E1], A1] =
      done.get.flatMap(
        if (_) Pull.end
        else fa
      )

    def update(implicit trace: Trace): ZIO[R, Option[E], Unit] =
      ifNotDone {
        upstream.foldZIO(
          {
            case None    => done.set(true) *> Pull.end
            case Some(e) => Pull.fail(e)
          },
          chunk => cursor.set(chunk -> 0)
        )
      }

    def pullElement(implicit trace: Trace): ZIO[R, Option[E], A] =
      ifNotDone {
        cursor.modify { case (chunk, idx) =>
          if (idx >= chunk.size) (update *> pullElement, (Chunk.empty, 0))
          else (ZIO.succeed(chunk(idx)), (chunk, idx + 1))
        }.flatten
      }

    def pullChunk(implicit trace: Trace): ZIO[R, Option[E], Chunk[A]] =
      ifNotDone {
        cursor.modify { case (chunk, idx) =>
          if (idx >= chunk.size) (update *> pullChunk, (Chunk.empty, 0))
          else (ZIO.succeed(chunk.drop(idx)), (Chunk.empty, 0))
        }.flatten
      }

  }

  private[zio] object BufferedPull {
    def make[R, E, A](
      pull: ZIO[R, Option[E], Chunk[A]]
    )(implicit trace: Trace): ZIO[R, Nothing, BufferedPull[R, E, A]] =
      for {
        done   <- Ref.make(false)
        cursor <- Ref.make[(Chunk[A], Int)](Chunk.empty -> 0)
      } yield BufferedPull(pull, done, cursor)
  }

  /**
   * A synchronous queue-like abstraction that allows a producer to offer an
   * element and wait for it to be taken, and allows a consumer to wait for an
   * element to be available.
   */
  private[zio] class Handoff[A](ref: Ref[Handoff.State[A]]) {
    def offer(a: A)(implicit trace: Trace): UIO[Unit] =
      Promise.make[Nothing, Unit].flatMap { p =>
        ref.modify {
          case s @ Handoff.State.Full(_, notifyProducer) => (notifyProducer.await *> offer(a), s)
          case Handoff.State.Empty(notifyConsumer)       => (notifyConsumer.succeed(()) *> p.await, Handoff.State.Full(a, p))
        }.flatten
      }

    def take(implicit trace: Trace): UIO[A] =
      Promise.make[Nothing, Unit].flatMap { p =>
        ref.modify {
          case Handoff.State.Full(a, notifyProducer)   => (notifyProducer.succeed(()).as(a), Handoff.State.Empty(p))
          case s @ Handoff.State.Empty(notifyConsumer) => (notifyConsumer.await *> take, s)
        }.flatten
      }

    def poll(implicit trace: Trace): UIO[Option[A]] =
      Promise.make[Nothing, Unit].flatMap { p =>
        ref.modify {
          case Handoff.State.Full(a, notifyProducer) => (notifyProducer.succeed(()).as(Some(a)), Handoff.State.Empty(p))
          case s @ Handoff.State.Empty(_)            => (ZIO.succeed(None), s)
        }.flatten
      }
  }

  private[zio] object Handoff {
    def make[A](implicit trace: Trace): UIO[Handoff[A]] =
      Promise
        .make[Nothing, Unit]
        .flatMap(p => Ref.make[State[A]](State.Empty(p)))
        .map(new Handoff(_))

    sealed trait State[+A]
    object State {
      case class Empty(notifyConsumer: Promise[Nothing, Unit])          extends State[Nothing]
      case class Full[+A](a: A, notifyProducer: Promise[Nothing, Unit]) extends State[A]
    }
  }

  final class WhenZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, O](zStream: ZStream[R1, E1, O])(implicit trace: Trace): ZStream[R1, E1, O] =
      fromZIO(b()).flatMap(if (_) zStream else ZStream.empty)
  }

  final class WhenCaseZIO[R, E, A](private val a: () => ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, O](pf: PartialFunction[A, ZStream[R1, E1, O]])(implicit
      trace: Trace
    ): ZStream[R1, E1, O] =
      fromZIO(a()).flatMap(pf.applyOrElse(_, (_: A) => ZStream.empty))
  }

  sealed trait HaltStrategy
  object HaltStrategy {
    case object Left   extends HaltStrategy
    case object Right  extends HaltStrategy
    case object Both   extends HaltStrategy
    case object Either extends HaltStrategy
  }

  implicit final class RefineToOrDieOps[R, E <: Throwable, A](private val self: ZStream[R, E, A]) extends AnyVal {

    /**
     * Keeps some of the errors, and terminates the fiber with the rest.
     */
    def refineToOrDie[E1 <: E: ClassTag](implicit ev: CanFail[E], trace: Trace): ZStream[R, E1, A] =
      self.refineOrDie { case e: E1 => e }
  }

  implicit final class SyntaxOps[-R, +E, O](self: ZStream[R, E, O]) {
    /*
     * Collect elements of the given type flowing through the stream, and filters out others.
     */
    def collectType[O1 <: O](implicit tag: ClassTag[O1], trace: Trace): ZStream[R, E, O1] =
      self.collect { case o if tag.runtimeClass.isInstance(o) => o.asInstanceOf[O1] }
  }

  private[zio] class Rechunker[A](n: Int) {
    private var buffer: Chunk[A]              = Chunk.empty[A]
    private var chunkBuilder: ChunkBuilder[A] = null
    private var pos: Int                      = 0

    def isEmpty: Boolean = pos == 0

    final def rechunk(
      chunk: Chunk[A]
    )(implicit trace: Trace): ZChannel[Any, ZNothing, Any, Any, ZNothing, Chunk[A], Any] = {
      val chunkSize = chunk.size
      if (chunkSize == 0) {
        null
      } else if (isEmpty && chunkSize == n) {
        ZChannel.write(chunk)
      } else if (n == 1) {
        rechunk1(chunk, chunkSize)
      } else if (chunkSize < 25) {
        // Optimized for small chunks. The limit 25 comes from testing with benchmarks
        var i = 0

        var channel: ZChannel[Any, ZNothing, Any, Any, ZNothing, Chunk[A], Any] = null

        if (chunkBuilder == null) {
          chunkBuilder = ChunkBuilder.make(n)
        }

        while (i < chunkSize) {
          chunkBuilder.addOne(chunk(i))
          i += 1
          pos += 1
          if (pos == n) {
            buffer ++= chunkBuilder.result()
            chunkBuilder.clear()

            // Flush buffer
            val result = ZChannel.write(buffer)
            pos = 0
            buffer = Chunk.empty[A]
            if (channel == null) channel = result
            else channel = channel *> result
          }
        }

        channel
      } else {
        // Optimized for large chunks
        var channel: ZChannel[Any, ZNothing, Any, Any, ZNothing, Chunk[A], Any] = null
        var chunkOffset                                                         = 0

        if (chunkBuilder != null) {
          // Last chunk might have been small and written to chunkBuilder,
          // so add to buffer before continuing with large chunk
          buffer ++= chunkBuilder.result()
          chunkBuilder.clear()
        }

        while (chunkOffset < chunkSize) {
          val needed    = n - pos
          val available = chunkSize - chunkOffset
          val size      = math.min(needed, available)
          buffer ++= chunk.slice(chunkOffset, chunkOffset + size)
          pos += size
          chunkOffset += size

          if (size == needed) {
            // Flush buffer
            val result = ZChannel.write(buffer)
            pos = 0
            buffer = Chunk.empty[A]
            if (channel == null) channel = result
            else channel = channel *> result
          }
        }

        channel
      }
    }

    private def rechunk1(chunk: Chunk[A], len: Int)(implicit
      trace: Trace
    ): ZChannel[Any, ZNothing, Any, Any, ZNothing, Chunk[A], Any] = {
      var channel: ZChannel[Any, ZNothing, Any, Any, ZNothing, Chunk[A], Any] = ZChannel.write(Chunk.single(chunk.head))

      var i = 1
      while (i < len) {
        val c = Chunk.single(chunk(i))
        channel = channel *> ZChannel.write(c)
        i += 1
      }

      channel
    }

    def done()(implicit trace: Trace): ZChannel[Any, ZNothing, Any, Any, ZNothing, Chunk[A], Any] = {
      // Last chunk might have been small and written to chunkBuilder,
      // so add to buffer before last flush
      if (chunkBuilder != null) {
        buffer ++= chunkBuilder.result()
        chunkBuilder.clear()
      }

      if (isEmpty) ZChannel.unit
      else ZChannel.write(buffer)
    }

  }

  private[zio] sealed trait SinkEndReason
  private[zio] object SinkEndReason {
    case object ScheduleEnd extends SinkEndReason
    case object UpstreamEnd extends SinkEndReason
  }

  private[zio] sealed trait HandoffSignal[E, A]
  private[zio] object HandoffSignal {
    case class Emit[E, A](els: Chunk[A])        extends HandoffSignal[E, A]
    case class Halt[E, A](error: Cause[E])      extends HandoffSignal[E, A]
    case class End[E, A](reason: SinkEndReason) extends HandoffSignal[E, A]
  }

  private[zio] sealed trait DebounceState[+E, +A]
  private[zio] object DebounceState {
    case object NotStarted                                         extends DebounceState[Nothing, Nothing]
    case class Previous[A](fiber: Fiber[Nothing, Chunk[A]])        extends DebounceState[Nothing, A]
    case class Current[E, A](fiber: Fiber[E, HandoffSignal[E, A]]) extends DebounceState[E, A]
  }

  /**
   * An `Emit[R, E, A, B]` represents an asynchronous callback that can be
   * called multiple times. The callback can be called with a value of type
   * `ZIO[R, Option[E], Chunk[A]]`, where succeeding with a `Chunk[A]` indicates
   * to emit those elements, failing with `Some[E]` indicates to terminate with
   * that error, and failing with `None` indicates to terminate with an end of
   * stream signal.
   */
  trait Emit[+R, -E, -A, +B] extends (ZIO[R, Option[E], Chunk[A]] => B) {

    def apply(v1: ZIO[R, Option[E], Chunk[A]]): B

    /**
     * Emits a chunk containing the specified values.
     */
    def chunk(as: Chunk[A])(implicit trace: Trace): B =
      apply(ZIO.succeed(as))

    /**
     * Terminates with a cause that dies with the specified `Throwable`.
     */
    def die(t: Throwable)(implicit trace: Trace): B =
      apply(ZIO.die(t))

    /**
     * Terminates with a cause that dies with a `Throwable` with the specified
     * message.
     */
    def dieMessage(message: String)(implicit trace: Trace): B =
      apply(ZIO.dieMessage(message))

    /**
     * Either emits the specified value if this `Exit` is a `Success` or else
     * terminates with the specified cause if this `Exit` is a `Failure`.
     */
    def done(exit: Exit[E, A])(implicit trace: Trace): B =
      apply(ZIO.done(exit.mapBothExit(e => Some(e), a => Chunk.single(a))))

    /**
     * Terminates with an end of stream signal.
     */
    def end(implicit trace: Trace): B =
      apply(Exit.failNone)

    /**
     * Terminates with the specified error.
     */
    def fail(e: E)(implicit trace: Trace): B =
      apply(ZIO.fail(Some(e)))

    /**
     * Either emits the success value of this effect or terminates the stream
     * with the failure value of this effect.
     */
    def fromEffect(zio: ZIO[R, E, A])(implicit trace: Trace): B =
      apply(zio.mapBoth(e => Some(e), a => Chunk.single(a)))

    /**
     * Either emits the success value of this effect or terminates the stream
     * with the failure value of this effect.
     */
    def fromEffectChunk(zio: ZIO[R, E, Chunk[A]])(implicit trace: Trace): B =
      apply(zio.mapError(e => Some(e)))

    /**
     * Terminates the stream with the specified cause.
     */
    def halt(cause: Cause[E])(implicit trace: Trace): B =
      apply(Exit.failCause(cause.map(e => Some(e))))

    /**
     * Emits a chunk containing the specified value.
     */
    def single(a: A)(implicit trace: Trace): B =
      apply(ZIO.succeed(Chunk.single(a)))
  }

  /**
   * Provides extension methods for streams that are sorted by distinct keys.
   */
  implicit final class SortedByKey[R, E, K, A](private val self: ZStream[R, E, (K, A)]) {

    /**
     * Zips this stream that is sorted by distinct keys and the specified stream
     * that is sorted by distinct keys to produce a new stream that is sorted by
     * distinct keys. Combines values associated with each key into a tuple,
     * using the specified values `defaultLeft` and `defaultRight` to fill in
     * missing values.
     *
     * This allows zipping potentially unbounded streams of data by key in
     * constant space but the caller is responsible for ensuring that the
     * streams are sorted by distinct keys.
     */
    def zipAllSortedByKey[R1 <: R, E1 >: E, B](
      that: => ZStream[R1, E1, (K, B)]
    )(defaultLeft: => A, defaultRight: => B)(implicit
      ord: Ordering[K],
      trace: Trace
    ): ZStream[R1, E1, (K, (A, B))] =
      zipAllSortedByKeyWith(that)((_, defaultRight), (defaultLeft, _))((_, _))

    /**
     * Zips this stream that is sorted by distinct keys and the specified stream
     * that is sorted by distinct keys to produce a new stream that is sorted by
     * distinct keys. Keeps only values from this stream, using the specified
     * value `default` to fill in missing values.
     *
     * This allows zipping potentially unbounded streams of data by key in
     * constant space but the caller is responsible for ensuring that the
     * streams are sorted by distinct keys.
     */
    def zipAllSortedByKeyLeft[R1 <: R, E1 >: E, B](
      that: => ZStream[R1, E1, (K, B)]
    )(default: => A)(implicit ord: Ordering[K], trace: Trace): ZStream[R1, E1, (K, A)] =
      zipAllSortedByKeyWith(that)(identity, _ => default)((a, _) => a)

    /**
     * Zips this stream that is sorted by distinct keys and the specified stream
     * that is sorted by distinct keys to produce a new stream that is sorted by
     * distinct keys. Keeps only values from that stream, using the specified
     * value `default` to fill in missing values.
     *
     * This allows zipping potentially unbounded streams of data by key in
     * constant space but the caller is responsible for ensuring that the
     * streams are sorted by distinct keys.
     */
    def zipAllSortedByKeyRight[R1 <: R, E1 >: E, B](
      that: => ZStream[R1, E1, (K, B)]
    )(default: => B)(implicit ord: Ordering[K], trace: Trace): ZStream[R1, E1, (K, B)] =
      zipAllSortedByKeyWith(that)(_ => default, identity)((_, b) => b)

    /**
     * Zips this stream that is sorted by distinct keys and the specified stream
     * that is sorted by distinct keys to produce a new stream that is sorted by
     * distinct keys. Uses the functions `left`, `right`, and `both` to handle
     * the cases where a key and value exist in this stream, that stream, or
     * both streams.
     *
     * This allows zipping potentially unbounded streams of data by key in
     * constant space but the caller is responsible for ensuring that the
     * streams are sorted by distinct keys.
     */
    def zipAllSortedByKeyWith[R1 <: R, E1 >: E, B, C](
      that: => ZStream[R1, E1, (K, B)]
    )(left: A => C, right: B => C)(
      both: (A, B) => C
    )(implicit ord: Ordering[K], trace: Trace): ZStream[R1, E1, (K, C)] = {
      sealed trait State
      case object DrainLeft                                extends State
      case object DrainRight                               extends State
      case object PullBoth                                 extends State
      final case class PullLeft(rightChunk: Chunk[(K, B)]) extends State
      final case class PullRight(leftChunk: Chunk[(K, A)]) extends State

      def pull(
        state: State,
        pullLeft: ZIO[R, Option[E], Chunk[(K, A)]],
        pullRight: ZIO[R1, Option[E1], Chunk[(K, B)]]
      ): ZIO[R1, Nothing, Exit[Option[E1], (Chunk[(K, C)], State)]] =
        state match {
          case DrainLeft =>
            pullLeft.fold(
              e => Exit.fail(e),
              leftChunk => Exit.succeed(leftChunk.map { case (k, a) => (k, left(a)) } -> DrainLeft)
            )
          case DrainRight =>
            pullRight.fold(
              e => Exit.fail(e),
              rightChunk => Exit.succeed(rightChunk.map { case (k, b) => (k, right(b)) } -> DrainRight)
            )
          case PullBoth =>
            pullLeft.unsome
              .zipPar(pullRight.unsome)
              .foldZIO(
                e => ZIO.succeed(Exit.fail(Some(e))),
                {
                  case (Some(leftChunk), Some(rightChunk)) =>
                    if (leftChunk.isEmpty && rightChunk.isEmpty) pull(PullBoth, pullLeft, pullRight)
                    else if (leftChunk.isEmpty) pull(PullLeft(rightChunk), pullLeft, pullRight)
                    else if (rightChunk.isEmpty) pull(PullRight(leftChunk), pullLeft, pullRight)
                    else ZIO.succeed(Exit.succeed(mergeSortedByKeyChunk(leftChunk, rightChunk)))
                  case (Some(leftChunk), None) =>
                    if (leftChunk.isEmpty) pull(DrainLeft, pullLeft, pullRight)
                    else ZIO.succeed(Exit.succeed(leftChunk.map { case (k, a) => (k, left(a)) } -> DrainLeft))
                  case (None, Some(rightChunk)) =>
                    if (rightChunk.isEmpty) pull(DrainRight, pullLeft, pullRight)
                    else
                      ZIO.succeed(Exit.succeed(rightChunk.map { case (k, b) => (k, right(b)) } -> DrainRight))
                  case (None, None) => ZIO.succeed(Exit.fail(None))
                }
              )
          case PullLeft(rightChunk) =>
            pullLeft.foldZIO(
              {
                case Some(e) => ZIO.succeed(Exit.fail(Some(e)))
                case None =>
                  ZIO.succeed(Exit.succeed(rightChunk.map { case (k, b) => (k, right(b)) } -> DrainRight))
              },
              leftChunk =>
                if (leftChunk.isEmpty) pull(PullLeft(rightChunk), pullLeft, pullRight)
                else ZIO.succeed(Exit.succeed(mergeSortedByKeyChunk(leftChunk, rightChunk)))
            )
          case PullRight(leftChunk) =>
            pullRight.foldZIO(
              {
                case Some(e) => ZIO.succeed(Exit.fail(Some(e)))
                case None    => ZIO.succeed(Exit.succeed(leftChunk.map { case (k, a) => (k, left(a)) } -> DrainLeft))
              },
              rightChunk =>
                if (rightChunk.isEmpty) pull(PullRight(leftChunk), pullLeft, pullRight)
                else ZIO.succeed(Exit.succeed(mergeSortedByKeyChunk(leftChunk, rightChunk)))
            )
        }

      def mergeSortedByKeyChunk(
        leftChunk: Chunk[(K, A)],
        rightChunk: Chunk[(K, B)]
      ): (Chunk[(K, C)], State) = {
        val builder       = ChunkBuilder.make[(K, C)]()
        var state         = null.asInstanceOf[State]
        val leftIterator  = leftChunk.iterator
        val rightIterator = rightChunk.iterator
        var leftTuple     = leftIterator.next()
        var rightTuple    = rightIterator.next()
        var k1            = leftTuple._1
        var a             = leftTuple._2
        var k2            = rightTuple._1
        var b             = rightTuple._2
        var loop          = true
        while (loop) {
          val compare = ord.compare(k1, k2)
          if (compare == 0) {
            builder += k1 -> both(a, b)
            if (leftIterator.hasNext && rightIterator.hasNext) {
              leftTuple = leftIterator.next()
              rightTuple = rightIterator.next()
              k1 = leftTuple._1
              a = leftTuple._2
              k2 = rightTuple._1
              b = rightTuple._2
            } else if (leftIterator.hasNext) {
              state = PullRight(Chunk.fromIterator(leftIterator))
              loop = false
            } else if (rightIterator.hasNext) {
              state = PullLeft(Chunk.fromIterator(rightIterator))
              loop = false
            } else {
              state = PullBoth
              loop = false
            }
          } else if (compare < 0) {
            builder += k1 -> left(a)
            if (leftIterator.hasNext) {
              leftTuple = leftIterator.next()
              k1 = leftTuple._1
              a = leftTuple._2
            } else {
              val rightBuilder = ChunkBuilder.make[(K, B)]()
              rightBuilder += rightTuple
              rightBuilder ++= rightIterator
              state = PullLeft(rightBuilder.result())
              loop = false
            }
          } else {
            builder += k2 -> right(b)
            if (rightIterator.hasNext) {
              rightTuple = rightIterator.next()
              k2 = rightTuple._1
              b = rightTuple._2
            } else {
              val leftBuilder = ChunkBuilder.make[(K, A)]()
              leftBuilder += leftTuple
              leftBuilder ++= leftIterator
              state = PullRight(leftBuilder.result())
              loop = false
            }
          }
        }
        (builder.result(), state)
      }

      self.combineChunks[R1, E1, State, (K, B), (K, C)](that)(PullBoth)(pull)
    }
  }

  private def mapDequeue[A, B](dequeue: Dequeue[A])(f: A => B): Dequeue[B] =
    new Dequeue[B] {
      def awaitShutdown(implicit trace: Trace): UIO[Unit] =
        dequeue.awaitShutdown
      def capacity: Int =
        dequeue.capacity
      def isShutdown(implicit trace: Trace): UIO[Boolean] =
        dequeue.isShutdown
      def shutdown(implicit trace: Trace): UIO[Unit] =
        dequeue.shutdown
      def size(implicit trace: Trace): UIO[Int] =
        dequeue.size
      def take(implicit trace: Trace): UIO[B] =
        dequeue.take.map(f)
      def takeAll(implicit trace: Trace): UIO[Chunk[B]] =
        dequeue.takeAll.map(_.map(f))
      def takeUpTo(max: Int)(implicit trace: Trace): UIO[Chunk[B]] =
        dequeue.takeUpTo(max).map(_.map(f))
    }

  /**
   * A variant of `groupBy` that retains the insertion order of keys.
   */
  private def groupBy[K, V](values: Iterable[V])(f: V => K): Chunk[(K, Chunk[V])] = {

    class GroupedBuilder extends Function1[K, ChunkBuilder[V]] { self =>
      val builder = ChunkBuilder.make[(K, ChunkBuilder[V])]()
      def apply(key: K): ChunkBuilder[V] = {
        val builder = ChunkBuilder.make[V]()
        self.builder += key -> builder
        builder
      }
      def result(): Chunk[(K, Chunk[V])] = {
        val chunk = builder.result()
        chunk.map { case (key, builder) => key -> builder.result() }
      }
    }

    val groupedBuilder = new GroupedBuilder
    val iterator       = values.iterator
    val map            = mutable.Map.empty[K, ChunkBuilder[V]]
    while (iterator.hasNext) {
      val value   = iterator.next()
      val key     = f(value)
      val builder = map.getOrElseUpdate(key, groupedBuilder(key))
      builder += value
    }
    groupedBuilder.result()
  }

  private def zipChunks[A, B, C](cl: Chunk[A], cr: Chunk[B], f: (A, B) => C): (Chunk[C], Either[Chunk[A], Chunk[B]]) =
    if (cl.size > cr.size)
      (cl.take(cr.size).zipWith(cr)(f), Left(cl.drop(cr.size)))
    else
      (cl.zipWith(cr.take(cl.size))(f), Right(cr.drop(cl.size)))
}
