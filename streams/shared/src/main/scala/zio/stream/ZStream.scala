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
import zio.internal.{SingleThreadedRingBuffer, UniqueKey}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stm._
import zio.stream.ZStream.{DebounceState, HandoffSignal}
import zio.stream.internal.Utils.{zipChunks, zipLeftChunks, zipRightChunks}
import zio.stream.internal.{ZInputStream, ZReader}

import java.io.{IOException, InputStream}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.reflect.ClassTag

class ZStream[-R, +E, +A](val channel: ZChannel[R, Any, Any, Any, E, Chunk[A], Any]) { self =>

  import ZStream.TerminationStrategy

  /**
   * Symbolic alias for [[ZStream#cross]].
   */
  final def <*>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    zippable: Zippable[A, A2],
    trace: ZTraceElement
  ): ZStream[R1, E1, zippable.Out] =
    self cross that

  /**
   * Symbolic alias for [[ZStream#crossLeft]].
   */
  final def <*[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    self crossLeft that

  /**
   * Symbolic alias for [[ZStream#crossRight]].
   */
  final def *>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A2] =
    self crossRight that

  /**
   * Symbolic alias for [[ZStream#zip]].
   */
  final def <&>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    zippable: Zippable[A, A2],
    trace: ZTraceElement
  ): ZStream[R1, E1, zippable.Out] =
    self zip that

  /**
   * Symbolic alias for [[ZStream#zipLeft]].
   */
  final def <&[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    self zipLeft that

  /**
   * Symbolic alias for [[ZStream#zipRight]].
   */
  final def &>[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A2] =
    self zipRight that

  /**
   * Symbolic alias for [[ZStream#flatMap]].
   */
  @deprecated("use flatMap", "2.0.0")
  def >>=[R1 <: R, E1 >: E, A2](f: A => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A2] =
    flatMap(f)

  /**
   * Symbolic alias for [[ZStream#via]].
   */
  def >>>[R1 <: R, E1 >: E, B](pipeline: ZPipeline[R1, E1, A, B])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, B] =
    via(pipeline)

  /**
   * Symbolic alias for [[[zio.stream.ZStream!.run[R1<:R,E1>:E,B]*]]].
   */
  def >>>[R1 <: R, E1 >: E, A2 >: A, Z](sink: => ZSink[R1, E1, A2, Any, Z])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, Z] =
    self.run(sink)

  /**
   * Symbolic alias for [[ZStream#concat]].
   */
  def ++[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    self concat that

  /**
   * Symbolic alias for [[ZStream#orElse]].
   */
  final def <>[R1 <: R, E2, A1 >: A](
    that: => ZStream[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R1, E2, A1] =
    self orElse that

  /**
   * Returns a stream that submerges the error case of an `Either` into the
   * `ZStream`.
   */
  final def absolve[R1 <: R, E1, A1](implicit
    ev: ZStream[R, E, A] <:< ZStream[R1, E1, Either[E1, A1]],
    trace: ZTraceElement
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
   * Any sink can be used here, but see [[ZSink.foldWeightedM]] and
   * [[ZSink.foldUntilM]] for sinks that cover the common usecases.
   */
  final def aggregateAsync[R1 <: R, E1 >: E, A1 >: A, B](
    sink: => ZSink[R1, E1, A1, A1, B]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, B] =
    aggregateAsyncWithin(sink, Schedule.forever)

  /**
   * Like `aggregateAsyncWithinEither`, but only returns the `Right` results.
   *
   * @param sink
   *   used for the aggregation
   * @param schedule
   *   signalling for when to stop the aggregation
   * @return
   *   `ZStream[R1 with Clock, E2, B]`
   */
  final def aggregateAsyncWithin[R1 <: R, E1 >: E, A1 >: A, B](
    sink: => ZSink[R1, E1, A1, A1, B],
    schedule: => Schedule[R1, Option[B], Any]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, B] =
    aggregateAsyncWithinEither(sink, schedule).collect { case Right(v) =>
      v
    }

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
   *   `ZStream[R1 with Clock, E2, Either[C, B]]`
   */
  def aggregateAsyncWithinEither[R1 <: R, E1 >: E, A1 >: A, B, C](
    sink: => ZSink[R1, E1, A1, A1, B],
    schedule: => Schedule[R1, Option[B], C]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, Either[C, B]] = {
    type HandoffSignal = ZStream.HandoffSignal[C, E1, A]
    import ZStream.HandoffSignal._
    type SinkEndReason = ZStream.SinkEndReason[C]
    import ZStream.SinkEndReason._

    val layer =
      ZStream.Handoff.make[HandoffSignal] <*>
        Ref.make[SinkEndReason](SinkEnd) <*>
        Ref.make(Chunk[A1]()) <*>
        schedule.driver

    ZStream.fromZIO(layer).flatMap { case (handoff, sinkEndReason, sinkLeftovers, scheduleDriver) =>
      lazy val handoffProducer: ZChannel[Any, E1, Chunk[A], Any, Nothing, Nothing, Any] =
        ZChannel.readWithCause(
          (in: Chunk[A]) => ZChannel.fromZIO(handoff.offer(Emit(in))) *> handoffProducer,
          (cause: Cause[E1]) => ZChannel.fromZIO(handoff.offer(Halt(cause))),
          (_: Any) => ZChannel.fromZIO(handoff.offer(End(UpstreamEnd)))
        )

      lazy val handoffConsumer: ZChannel[Any, Any, Any, Any, E1, Chunk[A1], Unit] =
        ZChannel.unwrap(
          sinkLeftovers.getAndSet(Chunk.empty).flatMap { leftovers =>
            if (leftovers.nonEmpty) {
              UIO.succeed(ZChannel.write(leftovers) *> handoffConsumer)
            } else
              handoff.take.map {
                case Emit(chunk) => ZChannel.write(chunk) *> handoffConsumer
                case Halt(cause) => ZChannel.failCause(cause)
                case End(reason) => ZChannel.fromZIO(sinkEndReason.set(reason))
              }
          }
        )

      def scheduledAggregator(
        lastB: Option[B]
      ): ZChannel[R1 with Clock, Any, Any, Any, E1, Chunk[Either[C, B]], Any] = {
        val timeout =
          scheduleDriver
            .next(lastB)
            .foldCauseZIO(
              _.failureOrCause match {
                case Left(_)      => handoff.offer(End(ScheduleTimeout))
                case Right(cause) => handoff.offer(Halt(cause))
              },
              c => handoff.offer(End(ScheduleEnd(c)))
            )

        ZChannel
          .managed(timeout.forkManaged) { fiber =>
            (handoffConsumer pipeToOrFail sink.channel).doneCollect.flatMap { case (leftovers, b) =>
              ZChannel.fromZIO(fiber.interrupt *> sinkLeftovers.set(leftovers.flatten)) *>
                ZChannel.unwrap {
                  sinkEndReason.modify {
                    case ScheduleEnd(c) =>
                      (ZChannel.write(Chunk(Right(b), Left(c))).as(Some(b)), SinkEnd)

                    case ScheduleTimeout =>
                      (ZChannel.write(Chunk(Right(b))).as(Some(b)), SinkEnd)

                    case SinkEnd =>
                      (ZChannel.write(Chunk(Right(b))).as(Some(b)), SinkEnd)

                    case UpstreamEnd =>
                      (ZChannel.write(Chunk(Right(b))).as(None), UpstreamEnd) // leftovers??
                  }
                }
            }
          }
          .flatMap {
            case None        => ZChannel.unit
            case s @ Some(_) => scheduledAggregator(s)
          }
      }

      ZStream.managed((self.channel >>> handoffProducer).runManaged.fork) *>
        new ZStream(scheduledAggregator(None))
    }
  }

  /**
   * Maps the success values of this stream to the specified constant value.
   */
  def as[A2](A2: => A2)(implicit trace: ZTraceElement): ZStream[R, E, A2] =
    map(_ => A2)

  /**
   * Reads the first n values from the stream and uses them to choose the
   * pipeline that will be used for the remainder of the stream.
   */
  def branchAfter[R1 <: R, E1 >: E, B](
    n: Int
  )(f: Chunk[A] => ZPipeline[R1, E1, A, B])(implicit trace: ZTraceElement): ZStream[R1, E1, B] =
    self >>> ZPipeline.branchAfter(n)(f)

  /**
   * Returns a stream whose failure and success channels have been mapped by the
   * specified pair of functions, `f` and `g`.
   */
  @deprecated("use mapBoth", "2.0.0")
  def bimap[E1, A1](f: E => E1, g: A => A1)(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, E1, A1] =
    mapBoth(f, g)

  /**
   * Fan out the stream, producing a list of streams that have the same elements
   * as this stream. The driver stream will only ever advance the `maximumLag`
   * chunks before the slowest downstream stream.
   */
  final def broadcast(n: => Int, maximumLag: => Int)(implicit
    trace: ZTraceElement
  ): ZManaged[R, Nothing, Chunk[ZStream[Any, E, A]]] =
    self
      .broadcastedQueues(n, maximumLag)
      .map(_.map(ZStream.fromQueueWithShutdown(_).flattenTake))

  /**
   * Fan out the stream, producing a dynamic number of streams that have the
   * same elements as this stream. The driver stream will only ever advance the
   * `maximumLag` chunks before the slowest downstream stream.
   */
  final def broadcastDynamic(
    maximumLag: => Int
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, ZStream[Any, E, A]] =
    self
      .broadcastedQueuesDynamic(maximumLag)
      .map(ZStream.managed(_).flatMap(ZStream.fromQueue(_)).flattenTake)

  /**
   * Converts the stream to a managed list of queues. Every value will be
   * replicated to every queue with the slowest queue being allowed to buffer
   * `maximumLag` chunks before the driver is back pressured.
   *
   * Queues can unsubscribe from upstream by shutting down.
   */
  final def broadcastedQueues(
    n: => Int,
    maximumLag: => Int
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, Chunk[Dequeue[Take[E, A]]]] =
    for {
      hub    <- Hub.bounded[Take[E, A]](maximumLag).toManaged
      queues <- ZManaged.collectAll(Chunk.fill(n)(hub.subscribe))
      _      <- self.runIntoHubManaged(hub).fork
    } yield queues

  /**
   * Converts the stream to a managed dynamic amount of queues. Every chunk will
   * be replicated to every queue with the slowest queue being allowed to buffer
   * `maximumLag` chunks before the driver is back pressured.
   *
   * Queues can unsubscribe from upstream by shutting down.
   */
  final def broadcastedQueuesDynamic(
    maximumLag: => Int
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, ZManaged[Any, Nothing, Dequeue[Take[E, A]]]] =
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
  final def buffer(capacity: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = self.toQueueOfElements(capacity)
    new ZStream(
      ZChannel.managed(queue) { queue =>
        lazy val process: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
          ZChannel.fromZIO {
            queue.take
          }.flatMap { (exit: Exit[Option[E], A]) =>
            exit.fold(
              Cause
                .flipCauseOption(_)
                .fold[ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit]](ZChannel.unit)(ZChannel.failCause(_)),
              value => ZChannel.write(Chunk.single(value)) *> process
            )
          }

        process
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
  final def bufferChunks(capacity: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = self.toQueue(capacity)
    new ZStream(
      ZChannel.managed(queue) { queue =>
        lazy val process: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
          ZChannel.fromZIO {
            queue.take
          }.flatMap { (take: Take[E, A]) =>
            take.fold(
              ZChannel.unit,
              error => ZChannel.failCause(error),
              value => ZChannel.write(value) *> process
            )
          }

        process
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
  final def bufferChunksDropping(capacity: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = Queue.dropping[(Take[E, A], Promise[Nothing, Unit])](capacity).toManagedWith(_.shutdown)
    new ZStream(bufferSignal[R, E, A](queue, self.channel))
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering up to `capacity` chunks in a sliding queue.
   *
   * @note
   *   Prefer capacities that are powers of 2 for better performance.
   */
  final def bufferChunksSliding(capacity: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = Queue.sliding[(Take[E, A], Promise[Nothing, Unit])](capacity).toManagedWith(_.shutdown)
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
  final def bufferDropping(capacity: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = Queue.dropping[(Take[E, A], Promise[Nothing, Unit])](capacity).toManagedWith(_.shutdown)
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
  final def bufferSliding(capacity: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = Queue.sliding[(Take[E, A], Promise[Nothing, Unit])](capacity).toManagedWith(_.shutdown)
    new ZStream(bufferSignal[R, E, A](queue, self.rechunk(1).channel))
  }

  private def bufferSignal[R1 <: R, E1 >: E, A1 >: A](
    managed: => UManaged[Queue[(Take[E1, A1], Promise[Nothing, Unit])]],
    channel: => ZChannel[R1, Any, Any, Any, E1, Chunk[A1], Any]
  )(implicit trace: ZTraceElement): ZChannel[R1, Any, Any, Any, E1, Chunk[A1], Unit] = {
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

      ZChannel.readWith[R1, E1, Chunk[A1], Any, Nothing, Nothing, Any](
        in =>
          ZChannel.fromZIO {
            for {
              p     <- Promise.make[Nothing, Unit]
              added <- queue.offer((Take.chunk(in), p))
              _     <- ref.set(p).when(added)
            } yield ()
          } *> producer(queue, ref),
        err => terminate(Take.fail(err)),
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
              error => ZChannel.failCause(error),
              value => ZChannel.write(value) *> process
            )
        }

      process
    }

    ZChannel.managed {
      for {
        queue <- managed
        start <- Promise.makeManaged[Nothing, Unit]
        _     <- start.succeed(()).toManaged
        ref   <- Ref.makeManaged(start)
        _     <- (channel >>> producer(queue, ref)).runManaged.fork
      } yield queue
    } { queue =>
      consumer(queue)
    }
  }

  /**
   * Allows a faster producer to progress independently of a slower consumer by
   * buffering chunks into an unbounded queue.
   */
  final def bufferUnbounded(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    val queue = self.toQueueUnbounded
    new ZStream(
      ZChannel.managed(queue) { queue =>
        lazy val process: ZChannel[Any, Any, Any, Any, E, Chunk[A], Unit] =
          ZChannel.fromZIO {
            queue.take
          }.flatMap { (take: Take[E, A]) =>
            take.fold(
              ZChannel.unit,
              error => ZChannel.failCause(error),
              value => ZChannel.write(value) *> process
            )
          }

        process
      }
    )
  }

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails with a typed error.
   */
  final def catchAll[R1 <: R, E2, A1 >: A](
    f: E => ZStream[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R1, E2, A1] =
    catchAllCause(_.failureOrCause.fold(f, ZStream.failCause(_)))

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails. Allows recovery from all causes of failure, including
   * interruption if the stream is uninterruptible.
   */
  final def catchAllCause[R1 <: R, E2, A1 >: A](f: Cause[E] => ZStream[R1, E2, A1])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E2, A1] =
    new ZStream(channel.catchAllCause(f(_).channel))

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails with some typed error.
   */
  final def catchSome[R1 <: R, E1 >: E, A1 >: A](pf: PartialFunction[E, ZStream[R1, E1, A1]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    catchAll(pf.applyOrElse[E, ZStream[R1, E1, A1]](_, ZStream.fail(_)))

  /**
   * Switches over to the stream produced by the provided function in case this
   * one fails with some errors. Allows recovery from all causes of failure,
   * including interruption if the stream is uninterruptible.
   */
  final def catchSomeCause[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[Cause[E], ZStream[R1, E1, A1]]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    catchAllCause(pf.applyOrElse[Cause[E], ZStream[R1, E1, A1]](_, ZStream.failCause(_)))

  /**
   * Returns a new stream that only emits elements that are not equal to the
   * previous element emitted, using natural equality to determine whether two
   * elements are equal.
   */
  def changes(implicit trace: ZTraceElement): ZStream[R, E, A] =
    changesWith(_ == _)

  /**
   * Returns a new stream that only emits elements that are not equal to the
   * previous element emitted, using the specified function to determine whether
   * two elements are equal.
   */
  def changesWith(f: (A, A) => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    def writer(last: Option[A]): ZChannel[R, E, Chunk[A], Any, E, Chunk[A], Unit] =
      ZChannel.readWithCause[R, E, Chunk[A], Any, E, Chunk[A], Unit](
        chunk => {
          val (newLast, newChunk) =
            chunk.foldLeft[(Option[A], Chunk[A])]((last, Chunk.empty)) {
              case ((Some(o), os), o1) if (f(o, o1)) => (Some(o1), os)
              case ((_, os), o1)                     => (Some(o1), os :+ o1)
            }

          ZChannel.write(newChunk) *> writer(newLast)
        },
        cause => ZChannel.failCause(cause),
        _ => ZChannel.unit
      )

    new ZStream(self.channel >>> writer(None))
  }

  /**
   * Returns a new stream that only emits elements that are not equal to the
   * previous element emitted, using the specified effectual function to
   * determine whether two elements are equal.
   */
  def changesWithZIO[R1 <: R, E1 >: E](
    f: (A, A) => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A] = {
    def writer(last: Option[A]): ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[A], Unit] =
      ZChannel.readWithCause[R1, E1, Chunk[A], Any, E1, Chunk[A], Unit](
        chunk =>
          ZChannel.fromZIO {
            chunk.foldZIO[R1, E1, (Option[A], Chunk[A])]((last, Chunk.empty)) {
              case ((Some(o), os), o1) =>
                f(o, o1).map(b => if (b) (Some(o1), os) else (Some(o1), os :+ o1))
              case ((_, os), o1) =>
                ZIO.succeedNow((Some(o1), os :+ o1))
            }
          }.flatMap { case (newLast, newChunk) =>
            ZChannel.write(newChunk) *> writer(newLast)
          },
        cause => ZChannel.failCause(cause),
        _ => ZChannel.unit
      )

    new ZStream(self.channel >>> writer(None))
  }

  /**
   * Re-chunks the elements of the stream into chunks of `n` elements each. The
   * last chunk might contain less than `n` elements
   */
  @deprecated("use rechunk", "2.0.0")
  def chunkN(n: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    rechunk(n)

  /**
   * Exposes the underlying chunks of the stream as a stream of chunks of
   * elements.
   */
  def chunks(implicit trace: ZTraceElement): ZStream[R, E, Chunk[A]] =
    mapChunks(Chunk.single)

  /**
   * Performs a filter and map in a single step.
   */
  final def collect[B](f: PartialFunction[A, B])(implicit trace: ZTraceElement): ZStream[R, E, B] =
    mapChunks(_.collect(f))

  /**
   * Filters any `Right` values.
   */
  final def collectLeft[L1, A1](implicit ev: A <:< Either[L1, A1], trace: ZTraceElement): ZStream[R, E, L1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]].collect { case Left(a) => a }
  }

  /**
   * Filters any 'None' values.
   */
  final def collectSome[A1](implicit ev: A <:< Option[A1], trace: ZTraceElement): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Option[A1]]].collect { case Some(a) => a }
  }

  /**
   * Filters any `Exit.Failure` values.
   */
  final def collectSuccess[L1, A1](implicit ev: A <:< Exit[L1, A1], trace: ZTraceElement): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Exit[L1, A1]]].collect { case Exit.Success(a) => a }
  }

  /**
   * Filters any `Left` values.
   */
  final def collectRight[L1, A1](implicit ev: A <:< Either[L1, A1], trace: ZTraceElement): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]].collect { case Right(a) => a }
  }

  /**
   * Creates a pipeline that groups on adjacent keys, calculated by function f.
   */
  final def groupAdjacentBy[K](
    f: A => K
  )(implicit trace: ZTraceElement): ZStream[R, E, (K, NonEmptyChunk[A])] =
    self >>> ZPipeline.groupAdjacentBy(f)

  private def loopOnChunks[R1 <: R, E1 >: E, A1](
    f: Chunk[A] => ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[A1], Boolean]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] = {
    lazy val loop: ZChannel[R1, E1, Chunk[A], Any, E1, Chunk[A1], Boolean] =
      ZChannel.readWith[R1, E1, Chunk[A], Any, E1, Chunk[A1], Boolean](
        chunk => f(chunk).flatMap(continue => if (continue) loop else ZChannel.succeedNow(false)),
        ZChannel.fail(_),
        _ => ZChannel.succeed(false)
      )
    new ZStream(self.channel >>> loop)
  }

  private def loopOnPartialChunks[R1 <: R, E1 >: E, A1](
    f: (Chunk[A], A1 => UIO[Unit]) => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    loopOnChunks(chunk =>
      ZChannel.unwrap {
        ZIO.suspendSucceed {
          val outputChunk           = ChunkBuilder.make[A1](chunk.size)
          val emit: A1 => UIO[Unit] = (a: A1) => UIO(outputChunk += a).unit
          f(chunk, emit).map { continue =>
            ZChannel.write(outputChunk.result()) *> ZChannel.succeedNow(continue)
          }.catchAll { failure =>
            ZIO.succeed {
              val partialResult = outputChunk.result()
              if (partialResult.nonEmpty)
                ZChannel.write(partialResult) *> ZChannel.fail(failure)
              else
                ZChannel.fail(failure)
            }
          }
        }
      }
    )

  private def loopOnPartialChunksElements[R1 <: R, E1 >: E, A1](
    f: (A, A1 => UIO[Unit]) => ZIO[R1, E1, Unit]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    loopOnPartialChunks((chunk, emit) => ZIO.foreachDiscard(chunk)(value => f(value, emit)).as(true))

  /**
   * Performs an effectful filter and map in a single step.
   */
  @deprecated("use collectZIO", "2.0.0")
  final def collectM[R1 <: R, E1 >: E, A1](pf: PartialFunction[A, ZIO[R1, E1, A1]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    collectZIO(pf)

  /**
   * Performs an effectful filter and map in a single step.
   */
  final def collectZIO[R1 <: R, E1 >: E, A1](pf: PartialFunction[A, ZIO[R1, E1, A1]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    loopOnPartialChunksElements((a, emit) => pf.andThen(_.flatMap(emit).unit).applyOrElse(a, (_: A) => ZIO.unit))

  /**
   * Transforms all elements of the stream for as long as the specified partial
   * function is defined.
   */
  def collectWhile[A1](pf: PartialFunction[A, A1])(implicit trace: ZTraceElement): ZStream[R, E, A1] = {
    lazy val loop: ZChannel[R, E, Chunk[A], Any, E, Chunk[A1], Any] =
      ZChannel.readWith[R, E, Chunk[A], Any, E, Chunk[A1], Any](
        in => {
          val mapped = in.collectWhile(pf)
          if (mapped.size == in.size)
            ZChannel.write(mapped) *> loop
          else
            ZChannel.write(mapped)
        },
        ZChannel.fail(_),
        ZChannel.succeed(_)
      )
    new ZStream(self.channel >>> loop)
  }

  /**
   * Terminates the stream when encountering the first `Right`.
   */
  final def collectWhileLeft[L1, A1](implicit ev: A <:< Either[L1, A1], trace: ZTraceElement): ZStream[R, E, L1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]].collectWhile { case Left(a) => a }
  }

  /**
   * Effectfully transforms all elements of the stream for as long as the
   * specified partial function is defined.
   */
  @deprecated("use collectWhileZIO", "2.0.0")
  final def collectWhileM[R1 <: R, E1 >: E, A1](pf: PartialFunction[A, ZIO[R1, E1, A1]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    collectWhileZIO(pf)

  /**
   * Terminates the stream when encountering the first `None`.
   */
  final def collectWhileSome[A1](implicit ev: A <:< Option[A1], trace: ZTraceElement): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Option[A1]]].collectWhile { case Some(a) => a }
  }

  /**
   * Terminates the stream when encountering the first `Left`.
   */
  final def collectWhileRight[L1, A1](implicit ev: A <:< Either[L1, A1], trace: ZTraceElement): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Either[L1, A1]]].collectWhile { case Right(a) => a }
  }

  /**
   * Terminates the stream when encountering the first `Exit.Failure`.
   */
  final def collectWhileSuccess[L1, A1](implicit ev: A <:< Exit[L1, A1], trace: ZTraceElement): ZStream[R, E, A1] = {
    val _ = ev
    self.asInstanceOf[ZStream[R, E, Exit[L1, A1]]].collectWhile { case Exit.Success(a) => a }
  }

  /**
   * Effectfully transforms all elements of the stream for as long as the
   * specified partial function is defined.
   */
  final def collectWhileZIO[R1 <: R, E1 >: E, A1](
    pf: PartialFunction[A, ZIO[R1, E1, A1]]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    loopOnPartialChunks { (chunk, emit) =>
      val pfSome = (a: A) => pf.andThen(_.flatMap(emit).as(true)).applyOrElse(a, (_: A) => ZIO.succeed(false))

      def loop(chunk: Chunk[A]): ZIO[R1, E1, Boolean] =
        if (chunk.isEmpty) ZIO.succeed(true)
        else
          pfSome(chunk.head).flatMap(continue => if (continue) loop(chunk.tail) else ZIO.succeed(false))

      loop(chunk)
    }

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
  final def combine[R1 <: R, E1 >: E, S, A2, A3](that: => ZStream[R1, E1, A2])(s: => S)(
    f: (S, ZIO[R, Option[E], A], ZIO[R1, Option[E1], A2]) => ZIO[R1, Nothing, Exit[Option[E1], (A3, S)]]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A3] = {
    def producer[Err, Elem](
      handoff: ZStream.Handoff[Exit[Option[Err], Elem]],
      latch: ZStream.Handoff[Unit]
    ): ZChannel[R1, Err, Elem, Any, Nothing, Nothing, Any] =
      ZChannel.fromZIO(latch.take) *>
        ZChannel.readWithCause[R1, Err, Elem, Any, Nothing, Nothing, Any](
          value => ZChannel.fromZIO(handoff.offer(Exit.succeed(value))) *> producer(handoff, latch),
          cause => ZChannel.fromZIO(handoff.offer(Exit.failCause(cause.map(Some(_))))),
          _ => ZChannel.fromZIO(handoff.offer(Exit.fail(None))) *> producer(handoff, latch)
        )

    new ZStream(
      ZChannel.managed {
        for {
          left   <- ZStream.Handoff.make[Exit[Option[E], A]].toManaged
          right  <- ZStream.Handoff.make[Exit[Option[E1], A2]].toManaged
          latchL <- ZStream.Handoff.make[Unit].toManaged
          latchR <- ZStream.Handoff.make[Unit].toManaged
          _      <- (self.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(left, latchL)).runManaged.fork
          _      <- (that.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(right, latchR)).runManaged.fork
        } yield (left, right, latchL, latchR)
      } { case (left, right, latchL, latchR) =>
        val pullLeft: IO[Option[E], A]    = latchL.offer(()) *> left.take.flatMap(ZIO.done(_))
        val pullRight: IO[Option[E1], A2] = latchR.offer(()) *> right.take.flatMap(ZIO.done(_))
        ZStream.unfoldZIO(s)(s => f(s, pullLeft, pullRight).flatMap(ZIO.done(_).unsome)).channel
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
  final def combineChunks[R1 <: R, E1 >: E, S, A2, A3](that: => ZStream[R1, E1, A2])(s: => S)(
    f: (
      S,
      ZIO[R, Option[E], Chunk[A]],
      ZIO[R1, Option[E1], Chunk[A2]]
    ) => ZIO[R1, Nothing, Exit[Option[E1], (Chunk[A3], S)]]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A3] = {
    def producer[Err, Elem](
      handoff: ZStream.Handoff[Take[Err, Elem]],
      latch: ZStream.Handoff[Unit]
    ): ZChannel[R1, Err, Chunk[Elem], Any, Nothing, Nothing, Any] =
      ZChannel.fromZIO(latch.take) *>
        ZChannel.readWithCause[R1, Err, Chunk[Elem], Any, Nothing, Nothing, Any](
          chunk => ZChannel.fromZIO(handoff.offer(Take.chunk(chunk))) *> producer(handoff, latch),
          cause => ZChannel.fromZIO(handoff.offer(Take.failCause(cause))),
          _ => ZChannel.fromZIO(handoff.offer(Take.end)) *> producer(handoff, latch)
        )

    new ZStream(
      ZChannel.managed {
        for {
          left   <- ZStream.Handoff.make[Take[E, A]].toManaged
          right  <- ZStream.Handoff.make[Take[E1, A2]].toManaged
          latchL <- ZStream.Handoff.make[Unit].toManaged
          latchR <- ZStream.Handoff.make[Unit].toManaged
          _      <- (self.channel >>> producer(left, latchL)).runManaged.fork
          _      <- (that.channel >>> producer(right, latchR)).runManaged.fork
        } yield (left, right, latchL, latchR)
      } { case (left, right, latchL, latchR) =>
        val pullLeft  = latchL.offer(()) *> left.take.flatMap(_.done)
        val pullRight = latchR.offer(()) *> right.take.flatMap(_.done)
        ZStream.unfoldChunkZIO(s)(s => f(s, pullLeft, pullRight).flatMap(ZIO.done(_).unsome)).channel
      }
    )
  }

  /**
   * Concatenates the specified stream with this stream, resulting in a stream
   * that emits the elements from this stream and then the elements from the
   * specified stream.
   */
  def concat[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: ZTraceElement
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
  final def cross[R1 <: R, E1 >: E, B](that: => ZStream[R1, E1, B])(implicit
    zippable: Zippable[A, B],
    trace: ZTraceElement
  ): ZStream[R1, E1, zippable.Out] =
    new ZStream(self.channel.concatMap(a => that.channel.mapOut(b => a.flatMap(a => b.map(b => zippable.zip(a, b))))))

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements, but keeps only elements from this stream. The `that`
   * stream would be run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  final def crossLeft[R1 <: R, E1 >: E, B](that: => ZStream[R1, E1, B])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A] =
    (self cross that).map(_._1)

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements, but keeps only elements from the other stream. The
   * `that` stream would be run multiple times, for every element in the `this`
   * stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  def crossRight[R1 <: R, E1 >: E, B](that: => ZStream[R1, E1, B])(implicit trace: ZTraceElement): ZStream[R1, E1, B] =
    (self cross that).map(_._2)

  /**
   * Composes this stream with the specified stream to create a cartesian
   * product of elements with a specified function. The `that` stream would be
   * run multiple times, for every element in the `this` stream.
   *
   * See also [[ZStream#zip]] and [[ZStream#<&>]] for the more common point-wise
   * variant.
   */
  final def crossWith[R1 <: R, E1 >: E, A2, C](that: => ZStream[R1, E1, A2])(f: (A, A2) => C)(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, C] =
    self.flatMap(l => that.map(r => f(l, r)))

  /**
   * Produces the specified element if this stream is empty.
   */
  final def defaultIfEmpty[A1 >: A](a: A1)(implicit trace: ZTraceElement): ZStream[R, E, A1] =
    defaultIfEmpty(Chunk.single(a))

  /**
   * Produces the specified chunk if this stream is empty.
   */
  final def defaultIfEmpty[A1 >: A](chunk: Chunk[A1])(implicit trace: ZTraceElement): ZStream[R, E, A1] =
    defaultIfEmpty(new ZStream(ZChannel.write(chunk)))

  /**
   * Switches to the provided stream in case this one is empty.
   */
  final def defaultIfEmpty[R1 <: R, E1 >: E, A1 >: A](
    stream: ZStream[R1, E1, A1]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] = {
    lazy val writer: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A1], Any] =
      ZChannel.readWith(
        (in: Chunk[A]) => if (in.isEmpty) writer else ZChannel.write(in) *> ZChannel.identity[E, Chunk[A], Any],
        (e: E) => ZChannel.fail(e),
        (_: Any) => stream.channel
      )

    new ZStream(self.channel >>> writer)
  }

  /**
   * More powerful version of `ZStream#broadcast`. Allows to provide a function
   * that determines what queues should receive which elements. The decide
   * function will receive the indices of the queues in the resulting list.
   */
  final def distributedWith[E1 >: E](
    n: => Int,
    maximumLag: => Int,
    decide: A => UIO[Int => Boolean]
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, List[Dequeue[Exit[Option[E1], A]]]] =
    Promise.make[Nothing, A => UIO[UniqueKey => Boolean]].toManaged.flatMap { prom =>
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
        }.toManaged
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
  final def distributedWithDynamic(
    maximumLag: => Int,
    decide: A => UIO[UniqueKey => Boolean],
    done: Exit[Option[E], Nothing] => UIO[Any] = (_: Any) => UIO.unit
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, UIO[(UniqueKey, Dequeue[Exit[Option[E], A]])]] =
    for {
      queuesRef <- Ref
                     .make[Map[UniqueKey, Queue[Exit[Option[E], A]]]](Map())
                     .toManagedWith(_.get.flatMap(qs => ZIO.foreach(qs.values)(_.shutdown)))
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
                             case c if c.isInterrupted => ZIO.succeedNow(id :: acc)
                             case c                    => ZIO.failCause(c)
                           },
                           _ => ZIO.succeedNow(acc)
                         )
                     } else ZIO.succeedNow(acc)
                   }
                   .flatMap(ids => if (ids.nonEmpty) queuesRef.update(_ -- ids) else ZIO.unit)
          } yield ()

        for {
          queuesLock <- Semaphore.make(1).toManaged
          newQueue <- Ref
                        .make[UIO[(UniqueKey, Queue[Exit[Option[E], A]])]] {
                          for {
                            queue <- Queue.bounded[Exit[Option[E], A]](maximumLag)
                            id     = UniqueKey()
                            _     <- queuesRef.update(_ + (id -> queue))
                          } yield (id, queue)
                        }
                        .toManaged
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
                                    _     <- queuesRef.update(_ + (id -> queue))
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
                 .runForeachManaged(offer)
                 .foldCauseManaged(
                   cause => finalize(Exit.failCause(cause.map(Some(_)))).toManaged,
                   _ => finalize(Exit.fail(None)).toManaged
                 )
                 .fork
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
  final def drain(implicit trace: ZTraceElement): ZStream[R, E, Nothing] =
    new ZStream(channel.drain)

  /**
   * Drains the provided stream in the background for as long as this stream is
   * running. If this stream ends before `other`, `other` will be interrupted.
   * If `other` fails, this stream will fail with that error.
   */
  final def drainFork[R1 <: R, E1 >: E](
    other: => ZStream[R1, E1, Any]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    ZStream.fromZIO(Promise.make[E1, Nothing]).flatMap { bgDied =>
      ZStream
        .managed(other.runForeachManaged(_ => ZIO.unit).catchAllCause(bgDied.failCause(_).toManaged).fork) *>
        self.interruptWhen(bgDied)
    }

  /**
   * Drops the specified number of elements from this stream.
   */
  def drop(n: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.drop(n)

  /**
   * Drops the last specified number of elements from this stream.
   *
   * @note
   *   This combinator keeps `n` elements in memory. Be careful with big
   *   numbers.
   */
  def dropRight(n: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.succeed(n).flatMap { n =>
      if (n <= 0) new ZStream(self.channel)
      else
        new ZStream({
          val queue = SingleThreadedRingBuffer[A](n)

          lazy val reader: ZChannel[Any, E, Chunk[A], Any, E, Chunk[A], Unit] =
            ZChannel.readWith(
              (in: Chunk[A]) => {
                val outs = in.flatMap { elem =>
                  val head = queue.head
                  queue.put(elem)
                  head
                }

                ZChannel.write(outs) *> reader
              },
              ZChannel.fail(_),
              (_: Any) => ZChannel.unit
            )

          self.channel >>> reader
        })
    }

  /**
   * Drops all elements of the stream for as long as the specified predicate
   * evaluates to `true`.
   */
  final def dropWhile(f: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.dropWhile(f)

  /**
   * Drops all elements of the stream for as long as the specified predicate
   * produces an effect that evalutates to `true`
   *
   * @see
   *   [[dropWhile]]
   */
  @deprecated("use dropWhileZIO", "2.0.0")
  final def dropWhileM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A] =
    dropWhileZIO(f)

  /**
   * Drops all elements of the stream until the specified predicate evaluates to
   * `true`.
   */
  final def dropUntil(pred: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.dropUntil(pred)

  /**
   * Drops all elements of the stream for as long as the specified predicate
   * produces an effect that evalutates to `true`
   *
   * @see
   *   [[dropWhile]]
   */
  final def dropWhileZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
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
  final def either(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, Nothing, Either[E, A]] =
    self.map(Right(_)).catchAll(e => ZStream(Left(e)))

  /**
   * Executes the provided finalizer after this stream's finalizers run.
   */
  final def ensuring[R1 <: R](fin: => ZIO[R1, Nothing, Any])(implicit trace: ZTraceElement): ZStream[R1, E, A] =
    new ZStream(channel.ensuring(fin))

  /**
   * Filters the elements emitted by this stream using the provided function.
   */
  final def filter(f: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    mapChunks(_.filter(f))

  /**
   * Finds the first element emitted by this stream that satisfies the provided
   * predicate.
   */
  final def find(f: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    lazy val loop: ZChannel[R, E, Chunk[A], Any, E, Chunk[A], Any] =
      ZChannel.readWith(
        (in: Chunk[A]) => in.find(f).fold(loop)(i => ZChannel.write(Chunk.single(i))),
        (e: E) => ZChannel.fail(e),
        (_: Any) => ZChannel.unit
      )

    new ZStream(self.channel >>> loop)
  }

  /**
   * Finds the first element emitted by this stream that satisfies the provided
   * effectful predicate.
   */
  @deprecated("use findZIO", "2.0.0")
  final def findM[R1 <: R, E1 >: E, S](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A] =
    findZIO(f)

  /**
   * Finds the first element emitted by this stream that satisfies the provided
   * effectful predicate.
   */
  final def findZIO[R1 <: R, E1 >: E, S](
    f: A => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A] = {
    lazy val loop: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A], Any] =
      ZChannel.readWith(
        (in: Chunk[A]) => ZChannel.unwrap(in.findZIO(f).map(_.fold(loop)(i => ZChannel.write(Chunk.single(i))))),
        (e: E) => ZChannel.fail(e),
        (_: Any) => ZChannel.unit
      )

    new ZStream(self.channel >>> loop)
  }

  /**
   * Executes a pure fold over the stream of values - reduces all elements in
   * the stream to a value of type `S`.
   */
  @deprecated("use runFold", "2.0.0")
  final def fold[S](s: => S)(f: (S, A) => S)(implicit trace: ZTraceElement): ZIO[R, E, S] =
    runFold(s)(f)

  /**
   * Executes a pure fold over the stream of values - reduces all elements in
   * the stream to a value of type `S`.
   */
  final def runFold[S](s: => S)(f: (S, A) => S)(implicit trace: ZTraceElement): ZIO[R, E, S] =
    runFoldWhileManaged(s)(_ => true)((s, a) => f(s, a)).use(ZIO.succeedNow)

  /**
   * Executes an effectful fold over the stream of values.
   */
  @deprecated("use runFoldZIO", "2.0.0")
  final def foldM[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldZIO[R1, E1, S](s)(f)

  /**
   * Executes an effectful fold over the stream of values.
   */
  @deprecated("use runFoldZIO", "2.0.0")
  final def runFoldM[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldZIO[R1, E1, S](s)(f)

  /**
   * Executes a pure fold over the stream of values. Returns a Managed value
   * that represents the scope of the stream.
   */
  @deprecated("user runFoldManaged", "2.0.0")
  final def foldManaged[S](s: => S)(f: (S, A) => S)(implicit trace: ZTraceElement): ZManaged[R, E, S] =
    runFoldManaged(s)(f)

  /**
   * Executes a pure fold over the stream of values. Returns a Managed value
   * that represents the scope of the stream.
   */
  final def runFoldManaged[S](s: => S)(f: (S, A) => S)(implicit trace: ZTraceElement): ZManaged[R, E, S] =
    runFoldWhileManaged(s)(_ => true)((s, a) => f(s, a))

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream.
   */
  @deprecated("use runFoldManagedZIO", "2.0.0")
  final def foldManagedM[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, S] =
    runFoldManagedZIO[R1, E1, S](s)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream.
   */
  @deprecated("use runFoldManagedZIO", "2.0.0")
  final def runFoldManagedM[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, S] =
    runFoldManagedZIO[R1, E1, S](s)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream.
   */
  @deprecated("use runFoldManagedZIO", "2.0.0")
  final def foldManagedZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, S] =
    runFoldManagedZIO[R1, E1, S](s)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream.
   */
  final def runFoldManagedZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, S] =
    runFoldWhileManagedZIO[R1, E1, S](s)(_ => true)(f)

  /**
   * Reduces the elements in the stream to a value of type `S`. Stops the fold
   * early when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1).forever.foldWhile(0)(_ <= 4)(_ + _) // UIO[Int] == 5
   * }}}
   */
  @deprecated("use runFoldWhile", "2.0.0")
  final def foldWhile[S](s: => S)(cont: S => Boolean)(f: (S, A) => S)(implicit trace: ZTraceElement): ZIO[R, E, S] =
    runFoldWhile(s)(cont)(f)

  /**
   * Reduces the elements in the stream to a value of type `S`. Stops the fold
   * early when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1).forever.foldWhile(0)(_ <= 4)(_ + _) // UIO[Int] == 5
   * }}}
   */
  final def runFoldWhile[S](s: => S)(cont: S => Boolean)(f: (S, A) => S)(implicit trace: ZTraceElement): ZIO[R, E, S] =
    runFoldWhileManaged(s)(cont)((s, a) => f(s, a)).use(ZIO.succeedNow)

  /**
   * Executes an effectful fold over the stream of values. Stops the fold early
   * when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  @deprecated("use runFoldWhileZIO", "2.0.0")
  final def foldWhileM[R1 <: R, E1 >: E, S](s: => S)(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldWhileZIO[R1, E1, S](s)(cont)(f)

  /**
   * Executes an effectful fold over the stream of values. Stops the fold early
   * when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  @deprecated("use runFoldWhileZIO", "2.0.0")
  final def runFoldWhileM[R1 <: R, E1 >: E, S](s: => S)(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldWhileZIO[R1, E1, S](s)(cont)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream. Stops the fold early when
   * the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // Managed[Nothing, Int]
   *     .use(ZIO.succeed)                       // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  @deprecated("use runFoldWhileManagedZIO", "2.0.0")
  final def foldWhileManagedM[R1 <: R, E1 >: E, S](
    s: => S
  )(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit trace: ZTraceElement): ZManaged[R1, E1, S] =
    runFoldWhileManagedZIO[R1, E1, S](s)(cont)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream. Stops the fold early when
   * the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // Managed[Nothing, Int]
   *     .use(ZIO.succeed)                       // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  @deprecated("use runFoldWhileManagedZIO", "2.0.0")
  final def runFoldWhileManagedM[R1 <: R, E1 >: E, S](
    s: => S
  )(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit trace: ZTraceElement): ZManaged[R1, E1, S] =
    runFoldWhileManagedZIO[R1, E1, S](s)(cont)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream. Stops the fold early when
   * the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // Managed[Nothing, Int]
   *     .use(ZIO.succeed)                       // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  @deprecated("use runFoldWhileManagedZIO", "2.0.0")
  final def foldWhileManagedZIO[R1 <: R, E1 >: E, S](
    s: => S
  )(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit trace: ZTraceElement): ZManaged[R1, E1, S] =
    runFoldWhileManagedZIO[R1, E1, S](s)(cont)(f)

  /**
   * Executes an effectful fold over the stream of values. Returns a Managed
   * value that represents the scope of the stream. Stops the fold early when
   * the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // Managed[Nothing, Int]
   *     .use(ZIO.succeed)                       // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  final def runFoldWhileManagedZIO[R1 <: R, E1 >: E, S](
    s: => S
  )(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit trace: ZTraceElement): ZManaged[R1, E1, S] =
    runManaged(ZSink.foldZIO(s)(cont)(f))

  /**
   * Executes an effectful fold over the stream of values. Stops the fold early
   * when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  @deprecated("use runFoldWhileZIO", "2.0.0")
  final def foldWhileZIO[R1 <: R, E1 >: E, S](s: => S)(cont: S => Boolean)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldWhileZIO[R1, E1, S](s)(cont)(f)

  /**
   * Executes an effectful fold over the stream of values. Stops the fold early
   * when the condition is not fulfilled. Example:
   * {{{
   *   Stream(1)
   *     .forever                                // an infinite Stream of 1's
   *     .fold(0)(_ <= 4)((s, a) => UIO(s + a))  // UIO[Int] == 5
   * }}}
   *
   * @param cont
   *   function which defines the early termination condition
   */
  final def runFoldWhileZIO[R1 <: R, E1 >: E, S](s: => S)(cont: S => Boolean)(
    f: (S, A) => ZIO[R1, E1, S]
  )(implicit trace: ZTraceElement): ZIO[R1, E1, S] =
    runFoldWhileManagedZIO[R1, E1, S](s)(cont)(f).use(ZIO.succeedNow)

  /**
   * Executes a pure fold over the stream of values. Returns a Managed value
   * that represents the scope of the stream. Stops the fold early when the
   * condition is not fulfilled.
   */
  @deprecated("use runFoldWhileManaged", "2.0.0")
  final def foldWhileManaged[S](s: => S)(cont: S => Boolean)(f: (S, A) => S)(implicit
    trace: ZTraceElement
  ): ZManaged[R, E, S] =
    runFoldWhileManaged(s)(cont)(f)

  /**
   * Executes a pure fold over the stream of values. Returns a Managed value
   * that represents the scope of the stream. Stops the fold early when the
   * condition is not fulfilled.
   */
  final def runFoldWhileManaged[S](s: => S)(cont: S => Boolean)(f: (S, A) => S)(implicit
    trace: ZTraceElement
  ): ZManaged[R, E, S] =
    runManaged(ZSink.fold(s)(cont)(f))

  /**
   * Executes an effectful fold over the stream of values.
   */
  @deprecated("use runFoldZIO", "2.0.0")
  final def foldZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldZIO[R1, E1, S](s)(f)

  /**
   * Executes an effectful fold over the stream of values.
   */
  final def runFoldZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, S] =
    runFoldWhileManagedZIO[R1, E1, S](s)(_ => true)(f).use(ZIO.succeedNow)

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  final def foreach[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    runForeach(f)

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  final def runForeach[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    run(ZSink.foreach(f))

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  @deprecated("use runForeachChunk", "2.0.0")
  final def foreachChunk[R1 <: R, E1 >: E](f: Chunk[A] => ZIO[R1, E1, Any])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, Unit] =
    runForeachChunk[R1, E1](f)

  /**
   * Consumes all elements of the stream, passing them to the specified
   * callback.
   */
  final def runForeachChunk[R1 <: R, E1 >: E](f: Chunk[A] => ZIO[R1, E1, Any])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, Unit] =
    run(ZSink.foreachChunk(f))

  /**
   * Like [[ZStream#runForeachChunk]], but returns a `ZManaged` so the
   * finalization order can be controlled.
   */
  @deprecated("use runForeachChunkManaged", "2.0.0")
  final def foreachChunkManaged[R1 <: R, E1 >: E](f: Chunk[A] => ZIO[R1, E1, Any])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, Unit] =
    runForeachChunkManaged[R1, E1](f)

  /**
   * Like [[ZStream#runForeachChunk]], but returns a `ZManaged` so the
   * finalization order can be controlled.
   */
  final def runForeachChunkManaged[R1 <: R, E1 >: E](f: Chunk[A] => ZIO[R1, E1, Any])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, Unit] =
    runManaged(ZSink.foreachChunk(f))

  /**
   * Like [[ZStream#foreach]], but returns a `ZManaged` so the finalization
   * order can be controlled.
   */
  @deprecated("run runForeachManaged", "2.0.0")
  final def foreachManaged[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, Unit] =
    runForeachManaged[R1, E1](f)

  /**
   * Like [[ZStream#foreach]], but returns a `ZManaged` so the finalization
   * order can be controlled.
   */
  final def runForeachManaged[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, Unit] =
    runManaged(ZSink.foreach(f))

  /**
   * Consumes elements of the stream, passing them to the specified callback,
   * and terminating consumption when the callback returns `false`.
   */
  @deprecated("use runForeachWhile", "2.0.0")
  final def foreachWhile[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, Unit] =
    runForeachWhile[R1, E1](f)

  /**
   * Consumes elements of the stream, passing them to the specified callback,
   * and terminating consumption when the callback returns `false`.
   */
  final def runForeachWhile[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
  ): ZIO[R1, E1, Unit] =
    run(ZSink.foreachWhile(f))

  /**
   * Like [[ZStream#runForeachWhile]], but returns a `ZManaged` so the
   * finalization order can be controlled.
   */
  @deprecated("use runForeachWhileManaged", "2.0.0")
  final def foreachWhileManaged[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, Unit] =
    runForeachWhileManaged[R1, E1](f)

  /**
   * Like [[ZStream#runForeachWhile]], but returns a `ZManaged` so the
   * finalization order can be controlled.
   */
  final def runForeachWhileManaged[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, Unit] =
    runManaged(ZSink.foreachWhile(f))

  /**
   * Repeats this stream forever.
   */
  def forever(implicit trace: ZTraceElement): ZStream[R, E, A] =
    new ZStream(channel.repeated)

  /**
   * Effectfully filters the elements emitted by this stream.
   */
  @deprecated("use filterZIO", "2.0.0")
  def filterM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    filterZIO(f)

  /**
   * Effectfully filters the elements emitted by this stream.
   */
  def filterZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    loopOnPartialChunksElements((a, emit) => f(a).flatMap(r => if (r) emit(a) else ZIO.unit))

  /**
   * Filters this stream by the specified predicate, removing all elements for
   * which the predicate evaluates to true.
   */
  final def filterNot(pred: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] = filter(a => !pred(a))

  /**
   * Emits elements of this stream with a fixed delay in between, regardless of
   * how long it takes to produce a value.
   */
  final def fixed(duration: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    schedule(Schedule.fixed(duration))

  /**
   * Returns a stream made of the concatenation in strict order of all the
   * streams produced by passing each element of this stream to `f0`
   */
  final def flatMap[R1 <: R, E1 >: E, B](f: A => ZStream[R1, E1, B])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, B] =
    new ZStream(channel.concatMap(as => as.map(f).map(_.channel).fold(ZChannel.unit)(_ *> _)))

  /**
   * Maps each element of this stream to another stream and returns the
   * non-deterministic merge of those streams, executing up to `n` inner streams
   * concurrently. Up to `bufferSize` elements of the produced streams may be
   * buffered in memory by this operator.
   */
  def flatMapPar[R1 <: R, E1 >: E, B](n: => Int, bufferSize: => Int = 16)(
    f: A => ZStream[R1, E1, B]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, B] =
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
  final def flatMapParSwitch[R1 <: R, E1 >: E, B](n: => Int, bufferSize: => Int = 16)(
    f: A => ZStream[R1, E1, B]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, B] =
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
  def flatten[R1 <: R, E1 >: E, A1](implicit ev: A <:< ZStream[R1, E1, A1], trace: ZTraceElement): ZStream[R1, E1, A1] =
    flatMap(ev(_))

  /**
   * Submerges the chunks carried by this stream into the stream's structure,
   * while still preserving them.
   */
  def flattenChunks[A1](implicit ev: A <:< Chunk[A1], trace: ZTraceElement): ZStream[R, E, A1] =
    new ZStream(self.channel.mapOut(_.flatten))

  /**
   * Flattens [[Exit]] values. `Exit.Failure` values translate to stream
   * failures while `Exit.Success` values translate to stream elements.
   */
  def flattenExit[E1 >: E, A1](implicit ev: A <:< Exit[E1, A1], trace: ZTraceElement): ZStream[R, E1, A1] =
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
    trace: ZTraceElement
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
            case Some(cause) => ZChannel.failCause(cause)
            case None        => ZChannel.unit
          }
        case None => cont
      }
      ZChannel.write(toEmit.collect { case Exit.Success(a) => a }) *> next
    }

    lazy val process: ZChannel[R, E, Chunk[Exit[Option[E1], A1]], Any, E1, Chunk[A1], Any] =
      ZChannel.readWithCause[R, E, Chunk[Exit[Option[E1], A1]], Any, E1, Chunk[A1], Any](
        chunk => processChunk(chunk, process),
        cause => ZChannel.failCause(cause),
        _ => ZChannel.unit
      )

    new ZStream(channel.asInstanceOf[ZChannel[R, Any, Any, Any, E, Chunk[Exit[Option[E1], A1]], Any]] >>> process)
  }

  /**
   * Submerges the iterables carried by this stream into the stream's structure,
   * while still preserving them.
   */
  def flattenIterables[A1](implicit ev: A <:< Iterable[A1], trace: ZTraceElement): ZStream[R, E, A1] =
    map(a => Chunk.fromIterable(ev(a))).flattenChunks

  /**
   * Flattens a stream of streams into a stream by executing a non-deterministic
   * concurrent merge. Up to `n` streams may be consumed in parallel and up to
   * `outputBuffer` elements may be buffered by this operator.
   */
  def flattenPar[R1 <: R, E1 >: E, A1](n: => Int, outputBuffer: => Int = 16)(implicit
    ev: A <:< ZStream[R1, E1, A1],
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    flatMapPar[R1, E1, A1](n, outputBuffer)(ev(_))

  /**
   * Like [[flattenPar]], but executes all streams concurrently.
   */
  def flattenParUnbounded[R1 <: R, E1 >: E, A1](
    outputBuffer: => Int = 16
  )(implicit ev: A <:< ZStream[R1, E1, A1], trace: ZTraceElement): ZStream[R1, E1, A1] =
    flattenPar[R1, E1, A1](Int.MaxValue, outputBuffer)

  /**
   * Unwraps [[Exit]] values and flatten chunks that also signify end-of-stream
   * by failing with `None`.
   */
  final def flattenTake[E1 >: E, A1](implicit ev: A <:< Take[E1, A1], trace: ZTraceElement): ZStream[R, E1, A1] =
    map(_.exit).flattenExitOption[E1, Chunk[A1]].flattenChunks

  /**
   * More powerful version of [[ZStream.groupByKey]]
   */
  final def groupBy[R1 <: R, E1 >: E, K, V](
    f: A => ZIO[R1, E1, (K, V)],
    buffer0: => Int = 16
  ): ZStream.GroupBy[R1, E1, K, V] = {
    type A1 = A
    new ZStream.GroupBy[R1, E1, K, V] {
      type A = A1
      def stream = self
      def key    = f
      def buffer = buffer0
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
  final def groupByKey[K](
    f: A => K,
    buffer: => Int = 16
  ): ZStream.GroupBy[R, E, K, A] =
    self.groupBy(a => ZIO.succeedNow((f(a), a)), buffer)

  /**
   * Partitions the stream with specified chunkSize
   * @param chunkSize
   *   size of the chunk
   */
  def grouped(chunkSize: => Int)(implicit trace: ZTraceElement): ZStream[R, E, Chunk[A]] =
    rechunk(chunkSize).chunks

  /**
   * Partitions the stream with the specified chunkSize or until the specified
   * duration has passed, whichever is satisfied first.
   */
  def groupedWithin(chunkSize: => Int, within: => Duration)(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, E, Chunk[A]] =
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
  final def haltWhen[R1 <: R, E1 >: E](io: ZIO[R1, E1, Any])(implicit trace: ZTraceElement): ZStream[R1, E1, A] = {
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
            exit.fold(ZChannel.failCause(_), _ => ZChannel.unit)
        }
      }

    new ZStream(
      ZChannel.unwrapManaged {
        io.forkManaged.map { fiber =>
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
  final def haltAfter(duration: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    haltWhen(Clock.sleep(duration))

  /**
   * Halts the evaluation of this stream when the provided promise resolves.
   *
   * If the promise completes with a failure, the stream will emit that failure.
   */
  final def haltWhen[E1 >: E](p: Promise[E1, _])(implicit trace: ZTraceElement): ZStream[R, E1, A] = {
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
  final def interleave[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: ZTraceElement
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
  final def interleaveWith[R1 <: R, E1 >: E, A1 >: A](
    that: => ZStream[R1, E1, A1]
  )(b: => ZStream[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A1] = {
    def producer(handoff: ZStream.Handoff[Take[E1, A1]]): ZChannel[R1, E1, A1, Any, Nothing, Nothing, Unit] =
      ZChannel.readWithCause[R1, E1, A1, Any, Nothing, Nothing, Unit](
        value => ZChannel.fromZIO(handoff.offer(Take.single(value))) *> producer(handoff),
        cause => ZChannel.fromZIO(handoff.offer(Take.failCause(cause))),
        _ => ZChannel.fromZIO(handoff.offer(Take.end))
      )

    new ZStream(
      ZChannel.managed {
        for {
          left  <- ZStream.Handoff.make[Take[E1, A1]].toManaged
          right <- ZStream.Handoff.make[Take[E1, A1]].toManaged
          _     <- (self.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(left)).runManaged.fork
          _     <- (that.channel.concatMap(ZChannel.writeChunk(_)) >>> producer(right)).runManaged.fork
        } yield (left, right)
      } { case (left, right) =>
        def process(leftDone: Boolean, rightDone: Boolean): ZChannel[R1, E1, Boolean, Any, E1, Chunk[A1], Unit] =
          ZChannel.readWithCause[R1, E1, Boolean, Any, E1, Chunk[A1], Unit](
            bool =>
              (bool, leftDone, rightDone) match {
                case (true, false, _) =>
                  ZChannel.fromZIO(left.take).flatMap { take =>
                    take.fold(
                      if (rightDone) ZChannel.unit else process(true, rightDone),
                      cause => ZChannel.failCause(cause),
                      chunk => ZChannel.write(chunk) *> process(leftDone, rightDone)
                    )
                  }
                case (false, _, false) =>
                  ZChannel.fromZIO(right.take).flatMap { take =>
                    take.fold(
                      if (leftDone) ZChannel.unit else process(leftDone, true),
                      cause => ZChannel.failCause(cause),
                      chunk => ZChannel.write(chunk) *> process(leftDone, rightDone)
                    )
                  }
                case _ =>
                  process(leftDone, rightDone)
              },
            cause => ZChannel.failCause(cause),
            _ => ZChannel.unit
          )

        b.channel.concatMap(ZChannel.writeChunk(_)) >>> process(false, false)
      }
    )
  }

  /**
   * Intersperse stream with provided element similar to
   * <code>List.mkString</code>.
   */
  final def intersperse[A1 >: A](middle: => A1)(implicit trace: ZTraceElement): ZStream[R, E, A1] =
    ZStream.succeed(middle).flatMap { middle =>
      def writer(isFirst: Boolean): ZChannel[R, E, Chunk[A1], Any, E, Chunk[A1], Unit] =
        ZChannel.readWith[R, E, Chunk[A1], Any, E, Chunk[A1], Unit](
          chunk => {
            val builder    = ChunkBuilder.make[A1]()
            var flagResult = isFirst

            chunk.foreach { o =>
              if (flagResult) {
                flagResult = false
                builder += o
              } else {
                builder += middle
                builder += o
              }
            }

            ZChannel.write(builder.result()) *> writer(flagResult)
          },
          err => ZChannel.fail(err),
          _ => ZChannel.unit
        )

      new ZStream(self.channel >>> writer(true))
    }

  /**
   * Intersperse and also add a prefix and a suffix
   */
  final def intersperse[A1 >: A](start: => A1, middle: => A1, end: => A1)(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A1] =
    ZStream(start) ++ intersperse(middle) ++ ZStream(end)

  /**
   * Interrupts the evaluation of this stream when the provided IO completes.
   * The given IO will be forked as part of this stream, and its success will be
   * discarded. This combinator will also interrupt any in-progress element
   * being pulled from upstream.
   *
   * If the IO completes with a failure before the stream completes, the
   * returned stream will emit that failure.
   */
  final def interruptWhen[R1 <: R, E1 >: E](io: ZIO[R1, E1, Any])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    new ZStream(channel.interruptWhen(io))

  /**
   * Interrupts the evaluation of this stream when the provided promise
   * resolves. This combinator will also interrupt any in-progress element being
   * pulled from upstream.
   *
   * If the promise completes with a failure, the stream will emit that failure.
   */
  final def interruptWhen[E1 >: E](p: Promise[E1, _])(implicit trace: ZTraceElement): ZStream[R, E1, A] =
    new ZStream(channel.interruptWhen(p.asInstanceOf[Promise[E1, Any]]))

  /**
   * Specialized version of interruptWhen which interrupts the evaluation of
   * this stream after the given duration.
   */
  final def interruptAfter(duration: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    interruptWhen(Clock.sleep(duration))

  /**
   * Returns a combined string resulting from concatenating each of the values
   * from the stream
   */
  final def mkString(implicit trace: ZTraceElement): ZIO[R, E, String] =
    run(ZSink.mkString)

  /**
   * Returns a combined string resulting from concatenating each of the values
   * from the stream beginning with `before` interspersed with `middle` and
   * ending with `after`.
   */
  final def mkString(before: => String, middle: => String, after: => String)(implicit
    trace: ZTraceElement
  ): ZIO[R, E, String] =
    intersperse(before, middle, after).mkString

  /**
   * Enqueues elements of this stream into a queue. Stream failure and ending
   * will also be signalled.
   */
  @deprecated("use runIntoQueue", "2.0.0")
  final def runInto[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    runIntoQueue(queue)

  /**
   * Like [[ZStream#runInto]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  @deprecated("use runIntoQueueElementsManaged", "2.0.0")
  final def runIntoElementsManaged[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Exit[Option[E1], A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] =
    runIntoQueueElementsManaged(queue)

  /**
   * Publishes elements of this stream to a hub. Stream failure and ending will
   * also be signalled.
   */
  @deprecated("use runIntoHub", "2.0.0")
  final def intoHub[R1 <: R, E1 >: E](
    hub: => ZHub[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    runIntoHub(hub)

  /**
   * Publishes elements of this stream to a hub. Stream failure and ending will
   * also be signalled.
   */
  final def runIntoHub[R1 <: R, E1 >: E](
    hub: => ZHub[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    runIntoQueue(hub.toQueue)

  /**
   * Like [[ZStream#intoHub]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  @deprecated("use runIntoHubManaged", "2.0.0")
  final def intoHubManaged[R1 <: R, E1 >: E](
    hub: => ZHub[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] =
    runIntoHubManaged(hub)

  /**
   * Like [[ZStream#runIntoHub]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  final def runIntoHubManaged[R1 <: R, E1 >: E](
    hub: => ZHub[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] =
    runIntoQueueManaged(hub.toQueue)

  /**
   * Like [[ZStream#into]], but provides the result as a [[ZManaged]] to allow
   * for scope composition.
   */
  @deprecated("use runIntoQueueManaged", "2.0.0")
  final def intoManaged[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] =
    runIntoQueueManaged(queue)

  /**
   * Like [[ZStream#runInto]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  @deprecated("use runIntoQueueManaged", "2.0.0")
  final def runIntoManaged[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] =
    runIntoQueueManaged(queue)

  /**
   * Enqueues elements of this stream into a queue. Stream failure and ending
   * will also be signalled.
   */
  @deprecated("use runIntoQueue", "2.0.0")
  final def intoQueue[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    runIntoQueue(queue)

  /**
   * Enqueues elements of this stream into a queue. Stream failure and ending
   * will also be signalled.
   */
  final def runIntoQueue[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZIO[R1, E1, Unit] =
    runIntoQueueManaged(queue).useDiscard(UIO.unit)

  /**
   * Like [[ZStream#ntoQueue]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  @deprecated("use runIntoQueueManaged", "2.0.0")
  final def intoQueueManaged[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] =
    runIntoQueueManaged(queue)

  /**
   * Like [[ZStream#runIntoQueue]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  final def runIntoQueueManaged[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Take[E1, A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] = {
    lazy val writer: ZChannel[R, E, Chunk[A], Any, E, Take[E1, A], Any] = ZChannel
      .readWithCause[R, E, Chunk[A], Any, E, Take[E1, A], Any](
        in => ZChannel.write(Take.chunk(in)) *> writer,
        cause => ZChannel.write(Take.failCause(cause)),
        _ => ZChannel.write(Take.end)
      )

    (self.channel >>> writer)
      .mapOutZIO(queue.offer)
      .drain
      .runManaged
      .unit
  }

  /**
   * Like [[ZStream#runIntoQueue]], but provides the result as a [[ZManaged]] to
   * allow for scope composition.
   */
  final def runIntoQueueElementsManaged[R1 <: R, E1 >: E](
    queue: => ZQueue[R1, Nothing, Nothing, Any, Exit[Option[E1], A], Any]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, Unit] = {
    lazy val writer: ZChannel[R1, E1, Chunk[A], Any, Nothing, Exit[Option[E1], A], Any] =
      ZChannel.readWith[R1, E1, Chunk[A], Any, Nothing, Exit[Option[E1], A], Any](
        in =>
          in.foldLeft[ZChannel[R1, Any, Any, Any, Nothing, Exit[Option[E1], A], Any]](ZChannel.unit) {
            case (channel, a) =>
              channel *> ZChannel.write(Exit.succeed(a))
          } *> writer,
        err => ZChannel.write(Exit.fail(Some(err))),
        _ => ZChannel.write(Exit.fail(None))
      )

    (self.channel >>> writer)
      .mapOutZIO(queue.offer)
      .drain
      .runManaged
      .unit
  }

  /**
   * Locks the execution of this stream to the specified executor. Any streams
   * that are composed after this one will automatically be shifted back to the
   * previous executor.
   */
  @deprecated("use onExecutor", "2.0.0")
  def lock(executor: => Executor)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    onExecutor(executor)

  /**
   * Transforms the elements of this stream using the supplied function.
   */
  final def map[B](f: A => B)(implicit trace: ZTraceElement): ZStream[R, E, B] =
    new ZStream(channel.mapOut(_.map(f)))

  /**
   * Statefully maps over the elements of this stream to produce new elements.
   */
  def mapAccum[S, A1](s: => S)(f: (S, A) => (S, A1))(implicit trace: ZTraceElement): ZStream[R, E, A1] =
    ZStream.succeed(s).flatMap { s =>
      def accumulator(currS: S): ZChannel[Any, E, Chunk[A], Any, E, Chunk[A1], Unit] =
        ZChannel.readWith(
          (in: Chunk[A]) => {
            val (nextS, a1s) = in.mapAccum(currS)(f)
            ZChannel.write(a1s) *> accumulator(nextS)
          },
          (err: E) => ZChannel.fail(err),
          (_: Any) => ZChannel.unit
        )

      new ZStream(self.channel >>> accumulator(s))
    }

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * new elements.
   */
  @deprecated("use mapAccumZIO", "2.0.0")
  final def mapAccumM[R1 <: R, E1 >: E, S, A1](s: => S)(f: (S, A) => ZIO[R1, E1, (S, A1)])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    mapAccumZIO[R1, E1, S, A1](s)(f)

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * new elements.
   */
  final def mapAccumZIO[R1 <: R, E1 >: E, S, A1](
    s: => S
  )(f: (S, A) => ZIO[R1, E1, (S, A1)])(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    self >>> ZPipeline.mapAccumZIO(s)(f)

  /**
   * Returns a stream whose failure and success channels have been mapped by the
   * specified pair of functions, `f` and `g`.
   */
  def mapBoth[E1, A1](f: E => E1, g: A => A1)(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, E1, A1] =
    mapError(f).map(g)

  /**
   * Transforms the chunks emitted by this stream.
   */
  def mapChunks[A2](f: Chunk[A] => Chunk[A2])(implicit trace: ZTraceElement): ZStream[R, E, A2] =
    new ZStream(channel.mapOut(f))

  /**
   * Effectfully transforms the chunks emitted by this stream.
   */
  @deprecated("use mapChunksZIO", "2.0.0")
  def mapChunksM[R1 <: R, E1 >: E, A2](f: Chunk[A] => ZIO[R1, E1, Chunk[A2]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapChunksZIO(f)

  /**
   * Effectfully transforms the chunks emitted by this stream.
   */
  def mapChunksZIO[R1 <: R, E1 >: E, A2](f: Chunk[A] => ZIO[R1, E1, Chunk[A2]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    new ZStream(channel.mapOutZIO(f))

  /**
   * Maps each element to an iterable, and flattens the iterables into the
   * output of this stream.
   */
  def mapConcat[A2](f: A => Iterable[A2])(implicit trace: ZTraceElement): ZStream[R, E, A2] =
    mapConcatChunk(a => Chunk.fromIterable(f(a)))

  /**
   * Maps each element to a chunk, and flattens the chunks into the output of
   * this stream.
   */
  def mapConcatChunk[A2](f: A => Chunk[A2])(implicit trace: ZTraceElement): ZStream[R, E, A2] =
    mapChunks(_.flatMap(f))

  /**
   * Effectfully maps each element to a chunk, and flattens the chunks into the
   * output of this stream.
   */
  @deprecated("use mapConcatChunkZIO", "2.0.0")
  final def mapConcatChunkM[R1 <: R, E1 >: E, A2](f: A => ZIO[R1, E1, Chunk[A2]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapConcatChunkZIO(f)

  /**
   * Effectfully maps each element to a chunk, and flattens the chunks into the
   * output of this stream.
   */
  final def mapConcatChunkZIO[R1 <: R, E1 >: E, A2](f: A => ZIO[R1, E1, Chunk[A2]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapZIO(f).mapConcatChunk(identity)

  /**
   * Effectfully maps each element to an iterable, and flattens the iterables
   * into the output of this stream.
   */
  @deprecated("use mapConcatZIO", "2.0.0")
  final def mapConcatM[R1 <: R, E1 >: E, A2](f: A => ZIO[R1, E1, Iterable[A2]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapConcatZIO(f)

  /**
   * Effectfully maps each element to an iterable, and flattens the iterables
   * into the output of this stream.
   */
  final def mapConcatZIO[R1 <: R, E1 >: E, A2](f: A => ZIO[R1, E1, Iterable[A2]])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapZIO(a => f(a).map(Chunk.fromIterable(_))).mapConcatChunk(identity)

  /**
   * Transforms the errors emitted by this stream using `f`.
   */
  def mapError[E2](f: E => E2)(implicit trace: ZTraceElement): ZStream[R, E2, A] =
    new ZStream(self.channel.mapError(f))

  /**
   * Transforms the full causes of failures emitted by this stream.
   */
  def mapErrorCause[E2](f: Cause[E] => Cause[E2])(implicit trace: ZTraceElement): ZStream[R, E2, A] =
    new ZStream(self.channel.mapErrorCause(f))

  /**
   * Maps over elements of the stream with the specified effectful function.
   */
  @deprecated("use mapZIO", "2.0.0")
  def mapM[R1 <: R, E1 >: E, A1](f: A => ZIO[R1, E1, A1])(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    mapZIO(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   */
  @deprecated("use mapZIOPar", "2.0.0")
  final def mapMPar[R1 <: R, E1 >: E, A2](n: => Int)(f: A => ZIO[R1, E1, A2])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapZIOPar[R1, E1, A2](n)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  @deprecated("use mapZIOParUnordered", "2.0.0")
  final def mapMParUnordered[R1 <: R, E1 >: E, A2](n: => Int)(f: A => ZIO[R1, E1, A2])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    mapZIOParUnordered[R1, E1, A2](n)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * partitioned by `p` executing invocations of `f` concurrently. The number of
   * concurrent invocations of `f` is determined by the number of different
   * outputs of type `K`. Up to `buffer` elements may be buffered per partition.
   * Transformed elements may be reordered but the order within a partition is
   * maintained.
   */
  @deprecated("use mapZIOPartitioned", "2.0.0")
  final def mapMPartitioned[R1 <: R, E1 >: E, A2, K](
    keyBy: A => K,
    buffer: => Int = 16
  )(f: A => ZIO[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A2] =
    mapZIOPartitioned[R1, E1, A2, K](keyBy, buffer)(f)

  /**
   * Maps over elements of the stream with the specified effectful function.
   */
  def mapZIO[R1 <: R, E1 >: E, A1](f: A => ZIO[R1, E1, A1])(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    loopOnPartialChunksElements((a, emit) => f(a).flatMap(emit))

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   */
  final def mapZIOPar[R1 <: R, E1 >: E, A2](n: => Int)(f: A => ZIO[R1, E1, A2])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    new ZStream(self.channel.concatMap(ZChannel.writeChunk(_)).mapOutZIOPar[R1, E1, A2](n)(f).mapOut(Chunk.single))

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  final def mapZIOParUnordered[R1 <: R, E1 >: E, A2](n: => Int)(f: A => ZIO[R1, E1, A2])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    flatMapPar[R1, E1, A2](n)(a => ZStream.fromZIO(f(a)))

  /**
   * Maps over elements of the stream with the specified effectful function,
   * partitioned by `p` executing invocations of `f` concurrently. The number of
   * concurrent invocations of `f` is determined by the number of different
   * outputs of type `K`. Up to `buffer` elements may be buffered per partition.
   * Transformed elements may be reordered but the order within a partition is
   * maintained.
   */
  final def mapZIOPartitioned[R1 <: R, E1 >: E, A2, K](
    keyBy: A => K,
    buffer: => Int = 16
  )(f: A => ZIO[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A2] =
    groupByKey(keyBy, buffer).apply { case (_, s) => s.mapZIO(f) }

  /**
   * Merges this stream and the specified stream together.
   *
   * New produced stream will terminate when both specified stream terminate if
   * no termination strategy is specified.
   */
  final def merge[R1 <: R, E1 >: E, A1 >: A](
    that: => ZStream[R1, E1, A1],
    strategy: => TerminationStrategy = TerminationStrategy.Both
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    self.mergeWith[R1, E1, A1, A1](that, strategy)(identity, identity) // TODO: Dotty doesn't infer this properly

  /**
   * Merges this stream and the specified stream together. New produced stream
   * will terminate when either stream terminates.
   */
  final def mergeTerminateEither[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    self.merge[R1, E1, A1](that, TerminationStrategy.Either)

  /**
   * Merges this stream and the specified stream together. New produced stream
   * will terminate when this stream terminates.
   */
  final def mergeTerminateLeft[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    self.merge[R1, E1, A1](that, TerminationStrategy.Left)

  /**
   * Merges this stream and the specified stream together. New produced stream
   * will terminate when the specified stream terminates.
   */
  final def mergeTerminateRight[R1 <: R, E1 >: E, A1 >: A](that: => ZStream[R1, E1, A1])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    self.merge[R1, E1, A1](that, TerminationStrategy.Right)

  /**
   * Merges this stream and the specified stream together to produce a stream of
   * eithers.
   */
  final def mergeEither[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, Either[A, A2]] =
    self.mergeWith(that)(Left(_), Right(_))

  /**
   * Merges this stream and the specified stream together, discarding the values
   * from the right stream.
   */
  final def mergeLeft[R1 <: R, E1 >: E, A2](
    that: => ZStream[R1, E1, A2],
    strategy: TerminationStrategy = TerminationStrategy.Both
  )(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A] =
    self.merge(that.drain)

  /**
   * Merges this stream and the specified stream together, discarding the values
   * from the left stream.
   */
  final def mergeRight[R1 <: R, E1 >: E, A2](
    that: => ZStream[R1, E1, A2],
    strategy: TerminationStrategy = TerminationStrategy.Both
  )(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    self.drain.merge(that)

  /**
   * Merges this stream and the specified stream together to a common element
   * type with the specified mapping functions.
   *
   * New produced stream will terminate when both specified stream terminate if
   * no termination strategy is specified.
   */
  final def mergeWith[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2],
    strategy: => TerminationStrategy = TerminationStrategy.Both
  )(l: A => A3, r: A2 => A3)(implicit trace: ZTraceElement): ZStream[R1, E1, A3] = {
    import TerminationStrategy.{Either, Left, Right}

    def handler(terminate: Boolean)(exit: Exit[E1, Any]): ZChannel.MergeDecision[R1, E1, Any, E1, Any] =
      if (terminate || !exit.isSuccess) ZChannel.MergeDecision.done(ZIO.done(exit))
      else ZChannel.MergeDecision.await(ZIO.done(_))

    new ZStream(
      ZChannel.succeed(strategy).flatMap { strategy =>
        self
          .map(l)
          .channel
          .mergeWith(that.map(r).channel)(
            handler(strategy == Either || strategy == Left),
            handler(strategy == Either || strategy == Right)
          )
      }
    )
  }

  /**
   * Runs the specified effect if this stream fails, providing the error to the
   * effect if it exists.
   *
   * Note: Unlike [[ZIO.onError]], there is no guarantee that the provided
   * effect will not be interrupted.
   */
  final def onError[R1 <: R](cleanup: Cause[E] => URIO[R1, Any])(implicit trace: ZTraceElement): ZStream[R1, E, A] =
    catchAllCause(cause => ZStream.fromZIO(cleanup(cause) *> ZIO.failCause(cause)))

  /**
   * Locks the execution of this stream to the specified executor. Any streams
   * that are composed after this one will automatically be shifted back to the
   * previous executor.
   */
  def onExecutor(executor: => Executor)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.fromZIO(ZIO.descriptor).flatMap { descriptor =>
      ZStream.managed(ZManaged.onExecutor(executor)) *>
        self <*
        ZStream.fromZIO {
          if (descriptor.isLocked) ZIO.shift(descriptor.executor)
          else ZIO.unshift
        }
    }

  /**
   * Switches to the provided stream in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  def orElse[R1 <: R, E1, A1 >: A](
    that: => ZStream[R1, E1, A1]
  )(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R1, E1, A1] =
    new ZStream(self.channel.orElse(that.channel))

  /**
   * Switches to the provided stream in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  final def orElseEither[R1 <: R, E2, A2](
    that: => ZStream[R1, E2, A2]
  )(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R1, E2, Either[A, A2]] =
    self.map(Left(_)) orElse that.map(Right(_))

  /**
   * Fails with given error in case this one fails with a typed error.
   *
   * See also [[ZStream#catchAll]].
   */
  final def orElseFail[E1](e1: => E1)(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, E1, A] =
    orElse(ZStream.fail(e1))

  /**
   * Switches to the provided stream in case this one fails with the `None`
   * value.
   *
   * See also [[ZStream#catchAll]].
   */
  final def orElseOptional[R1 <: R, E1, A1 >: A](
    that: => ZStream[R1, Option[E1], A1]
  )(implicit ev: E <:< Option[E1], trace: ZTraceElement): ZStream[R1, Option[E1], A1] =
    catchAll(ev(_).fold(that)(e => ZStream.fail(Some(e))))

  /**
   * Succeeds with the specified value if this one fails with a typed error.
   */
  final def orElseSucceed[A1 >: A](A1: => A1)(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, Nothing, A1] =
    orElse(ZStream.succeed(A1))

  /**
   * Partition a stream using a predicate. The first stream will contain all
   * element evaluated to true and the second one will contain all element
   * evaluated to false. The faster stream may advance by up to buffer elements
   * further than the slower one.
   */
  def partition(p: A => Boolean, buffer: => Int = 16)(implicit
    trace: ZTraceElement
  ): ZManaged[R, E, (ZStream[Any, E, A], ZStream[Any, E, A])] =
    self.partitionEither(a => if (p(a)) ZIO.succeedNow(Left(a)) else ZIO.succeedNow(Right(a)), buffer)

  /**
   * Split a stream by a predicate. The faster stream may advance by up to
   * buffer elements further than the slower one.
   */
  final def partitionEither[R1 <: R, E1 >: E, A2, A3](
    p: A => ZIO[R1, E1, Either[A2, A3]],
    buffer: => Int = 16
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, (ZStream[Any, E1, A2], ZStream[Any, E1, A3])] =
    self
      .mapZIO(p)
      .distributedWith(
        2,
        buffer,
        {
          case Left(_)  => ZIO.succeedNow(_ == 0)
          case Right(_) => ZIO.succeedNow(_ == 1)
        }
      )
      .flatMap {
        case q1 :: q2 :: Nil =>
          ZManaged.succeedNow {
            (
              ZStream.fromQueueWithShutdown(q1).flattenExitOption.collectLeft,
              ZStream.fromQueueWithShutdown(q2).flattenExitOption.collectRight
            )
          }
        case otherwise => ZManaged.dieMessage(s"partitionEither: expected two streams but got $otherwise")
      }

  /**
   * Peels off enough material from the stream to construct a `Z` using the
   * provided [[ZSink]] and then returns both the `Z` and the rest of the
   * [[ZStream]] in a managed resource. Like all [[ZManaged]] values, the
   * provided stream is valid only within the scope of [[ZManaged]].
   */
  def peel[R1 <: R, E1 >: E, A1 >: A, Z](
    sink: => ZSink[R1, E1, A1, A1, Z]
  )(implicit trace: ZTraceElement): ZManaged[R1, E1, (Z, ZStream[Any, E, A1])] = {
    sealed trait Signal
    object Signal {
      case class Emit(els: Chunk[A1])  extends Signal
      case class Halt(cause: Cause[E]) extends Signal
      case object End                  extends Signal
    }

    (for {
      p       <- Promise.makeManaged[E1, Z]
      handoff <- ZStream.Handoff.make[Signal].toManaged
    } yield {
      val consumer: ZSink[R1, E1, A1, A1, Unit] = sink.exposeLeftover
        .foldSink(
          e => ZSink.fromZIO(p.fail(e)) *> ZSink.fail(e),
          { case (z1, leftovers) =>
            lazy val loop: ZChannel[Any, E, Chunk[A1], Any, E1, Chunk[A1], Unit] = ZChannel.readWithCause(
              (in: Chunk[A1]) => ZChannel.fromZIO(handoff.offer(Signal.Emit(in))) *> loop,
              (e: Cause[E]) => ZChannel.fromZIO(handoff.offer(Signal.Halt(e))) *> ZChannel.failCause(e),
              (_: Any) => ZChannel.fromZIO(handoff.offer(Signal.End)) *> ZChannel.unit
            )

            new ZSink(
              ZChannel.fromZIO(p.succeed(z1)) *>
                ZChannel.fromZIO(handoff.offer(Signal.Emit(leftovers))) *>
                loop
            )
          }
        )

      lazy val producer: ZChannel[Any, Any, Any, Any, E, Chunk[A1], Unit] = ZChannel.unwrap(
        handoff.take.map {
          case Signal.Emit(els)   => ZChannel.write(els) *> producer
          case Signal.Halt(cause) => ZChannel.failCause(cause)
          case Signal.End         => ZChannel.unit
        }
      )

      for {
        _ <- self.runManaged(consumer).fork
        z <- p.await.toManaged
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
    trace: ZTraceElement
  ): ZStream[R1, E1, L] =
    new ZStream(self.channel pipeToOrFail sink.channel)

  /**
   * Pipes all the values from this stream through the provided channel
   */
  def pipeThroughChannel[R1 <: R, E2, A2](channel: => ZChannel[R1, E, Chunk[A], Any, E2, Chunk[A2], Any])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E2, A2] =
    new ZStream(self.channel >>> channel)

  /**
   * Pipes all values from this stream through the provided channel, passing
   * through any error emitted by this stream unchanged.
   */
  def pipeThroughChannelOrFail[R1 <: R, E1 >: E, A2](channel: ZChannel[R1, Nothing, Chunk[A], Any, E1, Chunk[A2], Any])(
    implicit trace: ZTraceElement
  ): ZStream[R1, E1, A2] =
    new ZStream(self.channel pipeToOrFail channel)

  /**
   * Provides the part of the environment that is not part of the `ZEnv`,
   * leaving a stream that only depends on the `ZEnv`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val stream: ZStream[ZEnv with Logging, Nothing, Unit] = ???
   *
   * val stream2 = stream.provideCustomLayer(loggingLayer)
   * }}}
   */
  def provideCustomLayer[E1 >: E, R1](
    layer: => ZLayer[ZEnv, E1, R1]
  )(implicit
    ev: ZEnv with R1 <:< R,
    tagged: EnvironmentTag[R1],
    trace: ZTraceElement
  ): ZStream[ZEnv, E1, A] =
    provideSomeLayer[ZEnv](layer)

  /**
   * Provides the stream with its required environment, which eliminates its
   * dependency on `R`.
   */
  final def provideEnvironment(
    r: => ZEnvironment[R]
  )(implicit ev: NeedsEnv[R], trace: ZTraceElement): ZStream[Any, E, A] =
    new ZStream(channel.provideEnvironment(r))

  /**
   * Provides the stream with the single service it requires. If the stream
   * requires multiple services use `provideEnvironment` instead.
   */
  final def provideService[Service <: R](
    service: Service
  )(implicit
    ev1: NeedsEnv[R],
    tag: Tag[Service],
    trace: ZTraceElement
  ): ZStream[Any, E, A] =
    provideEnvironment(ZEnvironment(service))

  /**
   * Provides a layer to the stream, which translates it to another level.
   */
  final def provideLayer[E1 >: E, R0](
    layer: => ZLayer[R0, E1, R]
  )(implicit trace: ZTraceElement): ZStream[R0, E1, A] =
    new ZStream(ZChannel.managed(layer.build) { r =>
      self.channel.provideEnvironment(r)
    })

  /**
   * Transforms the environment being provided to the stream with the specified
   * function.
   */
  final def provideSomeEnvironment[R0](
    env: ZEnvironment[R0] => ZEnvironment[R]
  )(implicit ev: NeedsEnv[R], trace: ZTraceElement): ZStream[R0, E, A] =
    ZStream.environmentWithStream[R0] { r0 =>
      self.provideEnvironment(env(r0))
    }

  /**
   * Splits the environment into two parts, providing one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val stream: ZStream[Clock with Random, Nothing, Unit] = ???
   *
   * val stream2 = stream.provideSomeLayer[Random](clockLayer)
   * }}}
   */
  final def provideSomeLayer[R0]: ZStream.ProvideSomeLayer[R0, R, E, A] =
    new ZStream.ProvideSomeLayer[R0, R, E, A](self)

  /**
   * Re-chunks the elements of the stream into chunks of `n` elements each. The
   * last chunk might contain less than `n` elements
   */
  def rechunk(n: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.rechunk(n)

  /**
   * Keeps some of the errors, and terminates the fiber with the rest
   */
  final def refineOrDie[E1](
    pf: PartialFunction[E, E1]
  )(implicit ev1: E <:< Throwable, ev2: CanFail[E], trace: ZTraceElement): ZStream[R, E1, A] =
    refineOrDieWith(pf)(identity(_))

  /**
   * Keeps some of the errors, and terminates the fiber with the rest, using the
   * specified function to convert the `E` into a `Throwable`.
   */
  final def refineOrDieWith[E1](
    pf: PartialFunction[E, E1]
  )(f: E => Throwable)(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, E1, A] =
    new ZStream(
      channel.catchAll(e =>
        if (pf.isDefinedAt(e))
          ZChannel.fail(pf.apply(e))
        else
          ZChannel.failCause(Cause.die(f(e)))
      )
    )

  /**
   * Repeats the entire stream using the specified schedule. The stream will
   * execute normally, and then repeat again according to the provided schedule.
   */
  final def repeat[R1 <: R, B](schedule: => Schedule[R1, Any, B])(implicit
    trace: ZTraceElement
  ): ZStream[R1 with Clock, E, A] =
    repeatEither(schedule) collect { case Right(a) => a }

  /**
   * Repeats the entire stream using the specified schedule. The stream will
   * execute normally, and then repeat again according to the provided schedule.
   * The schedule output will be emitted at the end of each repetition.
   */
  final def repeatEither[R1 <: R, B](schedule: => Schedule[R1, Any, B])(implicit
    trace: ZTraceElement
  ): ZStream[R1 with Clock, E, Either[B, A]] =
    repeatWith(schedule)(Right(_), Left(_))

  /**
   * Repeats each element of the stream using the provided schedule. Repetitions
   * are done in addition to the first execution, which means using
   * `Schedule.recurs(1)` actually results in the original effect, plus an
   * additional recurrence, for a total of two repetitions of each value in the
   * stream.
   */
  final def repeatElements[R1 <: R](schedule: => Schedule[R1, A, Any])(implicit
    trace: ZTraceElement
  ): ZStream[R1 with Clock, E, A] =
    repeatElementsEither(schedule).collect { case Right(a) => a }

  /**
   * Repeats each element of the stream using the provided schedule. When the
   * schedule is finished, then the output of the schedule will be emitted into
   * the stream. Repetitions are done in addition to the first execution, which
   * means using `Schedule.recurs(1)` actually results in the original effect,
   * plus an additional recurrence, for a total of two repetitions of each value
   * in the stream.
   */
  final def repeatElementsEither[R1 <: R, E1 >: E, B](
    schedule: => Schedule[R1, A, B]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, Either[B, A]] =
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
  final def repeatElementsWith[R1 <: R, E1 >: E, B, C](
    schedule: => Schedule[R1, A, B]
  )(f: A => C, g: B => C)(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, C] = new ZStream(
    self.channel >>> ZChannel.unwrap {
      for {
        driver <- schedule.driver
      } yield {
        def feed(in: Chunk[A]): ZChannel[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[C], Unit] =
          in.headOption.fold(loop)(a => ZChannel.write(Chunk.single(f(a))) *> step(in.drop(1), a))

        def step(in: Chunk[A], a: A): ZChannel[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[C], Unit] = {
          val advance = driver.next(a).as(ZChannel.write(Chunk.single(f(a))) *> step(in, a))
          val reset: ZIO[R1 with Clock, Nothing, ZChannel[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[C], Unit]] =
            for {
              b <- driver.last.orDie
              _ <- driver.reset
            } yield ZChannel.write(Chunk.single(g(b))) *> feed(in)

          ZChannel.unwrap(advance orElse reset)
        }

        lazy val loop: ZChannel[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[C], Unit] =
          ZChannel.readWith(
            feed,
            ZChannel.fail(_),
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
  final def repeatWith[R1 <: R, B, C](
    schedule: => Schedule[R1, Any, B]
  )(f: A => C, g: B => C)(implicit trace: ZTraceElement): ZStream[R1 with Clock, E, C] =
    ZStream.unwrap(
      for {
        driver <- schedule.driver
      } yield {
        val scheduleOutput = driver.last.orDie.map(g)
        val process        = self.map(f).channel
        lazy val loop: ZChannel[R1 with Clock, Any, Any, Any, E, Chunk[C], Unit] =
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
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E, A] =
    ZStream.unwrap {
      for {
        driver <- schedule.driver
      } yield {
        def loop: ZStream[R1, E, A] = self.catchAll { e =>
          ZStream.unwrap(
            driver
              .next(e)
              .foldZIO(
                _ => ZIO.fail(e),
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
  final def right[A1, A2](implicit ev: A <:< Either[A1, A2], trace: ZTraceElement): ZStream[R, Option[E], A2] =
    self.mapError(Some(_)).rightOrFail(None)

  /**
   * Fails with given error 'e' if value is `Left`.
   */
  final def rightOrFail[A1, A2, E1 >: E](
    e: => E1
  )(implicit ev: A <:< Either[A1, A2], trace: ZTraceElement): ZStream[R, E1, A2] =
    self.mapZIO(ev(_).fold(_ => ZIO.fail(e), ZIO.succeedNow(_)))

  /**
   * Runs the sink on the stream to produce either the sink's result or an
   * error.
   */
  def run[R1 <: R, E1 >: E, Z](sink: => ZSink[R1, E1, A, Any, Z])(implicit trace: ZTraceElement): ZIO[R1, E1, Z] =
    (channel pipeToOrFail sink.channel).runDrain

  def runManaged[R1 <: R, E1 >: E, B](sink: => ZSink[R1, E1, A, Any, B])(implicit
    trace: ZTraceElement
  ): ZManaged[R1, E1, B] =
    (channel pipeToOrFail sink.channel).drain.runManaged

  /**
   * Runs the stream and collects all of its elements to a chunk.
   */
  def runCollect(implicit trace: ZTraceElement): ZIO[R, E, Chunk[A]] =
    run(ZSink.collectAll)

  /**
   * Runs the stream and emits the number of elements processed
   *
   * Equivalent to `run(ZSink.count)`
   */
  final def runCount(implicit trace: ZTraceElement): ZIO[R, E, Long] =
    run(ZSink.count)

  /**
   * Runs the stream only for its effects. The emitted elements are discarded.
   */
  def runDrain(implicit trace: ZTraceElement): ZIO[R, E, Unit] =
    run(ZSink.drain)

  /**
   * Runs the stream to completion and yields the first value emitted by it,
   * discarding the rest of the elements.
   */
  def runHead(implicit trace: ZTraceElement): ZIO[R, E, Option[A]] =
    run(ZSink.head)

  /**
   * Runs the stream to completion and yields the last value emitted by it,
   * discarding the rest of the elements.
   */
  def runLast(implicit trace: ZTraceElement): ZIO[R, E, Option[A]] =
    run(ZSink.last)

  /**
   * Runs the stream to a sink which sums elements, provided they are Numeric.
   *
   * Equivalent to `run(Sink.sum[A])`
   */
  final def runSum[A1 >: A](implicit ev: Numeric[A1], trace: ZTraceElement): ZIO[R, E, A1] =
    run(ZSink.sum[A1])

  /**
   * Statefully maps over the elements of this stream to produce all
   * intermediate results of type `S` given an initial S.
   */
  def scan[S](s: => S)(f: (S, A) => S)(implicit trace: ZTraceElement): ZStream[R, E, S] =
    scanZIO(s)((s, a) => ZIO.succeedNow(f(s, a)))

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * all intermediate results of type `S` given an initial S.
   */
  @deprecated("use scanZIO", "2.0.0")
  def scanM[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, S] =
    scanZIO[R1, E1, S](s)(f)

  /**
   * Statefully maps over the elements of this stream to produce all
   * intermediate results.
   *
   * See also [[ZStream#scan]].
   */
  def scanReduce[A1 >: A](f: (A1, A) => A1)(implicit trace: ZTraceElement): ZStream[R, E, A1] =
    scanReduceZIO[R, E, A1]((curr, next) => ZIO.succeedNow(f(curr, next)))

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * all intermediate results.
   *
   * See also [[ZStream#scanM]].
   */
  @deprecated("use scanReduceZIO", "2.0.0")
  def scanReduceM[R1 <: R, E1 >: E, A1 >: A](f: (A1, A) => ZIO[R1, E1, A1])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, A1] =
    scanReduceZIO(f)

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * all intermediate results.
   *
   * See also [[ZStream#scanM]].
   */
  def scanReduceZIO[R1 <: R, E1 >: E, A1 >: A](
    f: (A1, A) => ZIO[R1, E1, A1]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A1] =
    mapAccumZIO[R1, E1, Option[A1], A1](Option.empty[A1]) {
      case (Some(a1), a) => f(a1, a).map(a2 => Some(a2) -> a2)
      case (None, a)     => ZIO.succeedNow(Some(a) -> a)
    }

  /**
   * Statefully and effectfully maps over the elements of this stream to produce
   * all intermediate results of type `S` given an initial S.
   */
  def scanZIO[R1 <: R, E1 >: E, S](s: => S)(f: (S, A) => ZIO[R1, E1, S])(implicit
    trace: ZTraceElement
  ): ZStream[R1, E1, S] =
    self >>> ZPipeline.scanZIO(s)(f)

  /**
   * Schedules the output of the stream using the provided `schedule`.
   */
  final def schedule[R1 <: R](schedule: => Schedule[R1, A, Any])(implicit
    trace: ZTraceElement
  ): ZStream[R1 with Clock, E, A] =
    scheduleEither(schedule).collect { case Right(a) => a }

  /**
   * Schedules the output of the stream using the provided `schedule` and emits
   * its output at the end (if `schedule` is finite).
   */
  final def scheduleEither[R1 <: R, E1 >: E, B](
    schedule: => Schedule[R1, A, B]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, Either[B, A]] =
    scheduleWith(schedule)(Right.apply, Left.apply)

  /**
   * Schedules the output of the stream using the provided `schedule` and emits
   * its output at the end (if `schedule` is finite). Uses the provided function
   * to align the stream and schedule outputs on the same type.
   */
  final def scheduleWith[R1 <: R, E1 >: E, B, C](
    schedule: => Schedule[R1, A, B]
  )(f: A => C, g: B => C)(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, C] = {

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
              _ => ZIO.succeedNow(ZChannel.write(Chunk(f(a))) *> loop(driver, chunkIterator, index + 1))
            )
        }
      else
        ZChannel.readWithCause(
          chunk => loop(driver, chunk.chunkIterator, 0),
          ZChannel.failCause(_),
          ZChannel.succeedNow(_)
        )

    new ZStream(ZChannel.fromZIO(schedule.driver).flatMap(self.channel >>> loop(_, Chunk.ChunkIterator.empty, 0)))
  }

  /**
   * Converts an option on values into an option on errors.
   */
  final def some[A2](implicit ev: A <:< Option[A2], trace: ZTraceElement): ZStream[R, Option[E], A2] =
    self.mapError(Some(_)).someOrFail(None)

  /**
   * Extracts the optional value, or returns the given 'default'.
   */
  final def someOrElse[A2](default: => A2)(implicit ev: A <:< Option[A2], trace: ZTraceElement): ZStream[R, E, A2] =
    map(_.getOrElse(default))

  /**
   * Extracts the optional value, or fails with the given error 'e'.
   */
  final def someOrFail[A2, E1 >: E](e: => E1)(implicit ev: A <:< Option[A2], trace: ZTraceElement): ZStream[R, E1, A2] =
    self.mapZIO(ev(_).fold[IO[E1, A2]](ZIO.fail(e))(ZIO.succeedNow(_)))

  /**
   * Emits a sliding window of n elements.
   * {{{
   *   Stream(1, 2, 3, 4).sliding(2).runCollect // Chunk(Chunk(1, 2), Chunk(2, 3), Chunk(3, 4))
   * }}}
   */
  def sliding(chunkSize: => Int, stepSize: Int = 1)(implicit trace: ZTraceElement): ZStream[R, E, Chunk[A]] =
    ZStream.succeed(chunkSize).flatMap { chunkSize =>
      ZStream.succeed(stepSize).flatMap { stepSize =>
        if (chunkSize <= 0 || stepSize <= 0)
          ZStream.die(
            new IllegalArgumentException("invalid bounds. `chunkSize` and `stepSize` must be greater than zero")
          )
        else
          new ZStream({
            val queue = SingleThreadedRingBuffer[A](chunkSize)

            def emitOnStreamEnd(queueSize: Int)(channelEnd: ZChannel[Any, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any]) =
              if (queueSize < chunkSize) {
                val items  = queue.toChunk
                val result = if (items.isEmpty) Chunk.empty else Chunk.single(items)
                ZChannel.write(result) *> channelEnd
              } else {
                val lastEmitIndex = queueSize - (queueSize - chunkSize) % stepSize

                if (lastEmitIndex == queueSize) channelEnd
                else {
                  val leftovers = queueSize - (lastEmitIndex - chunkSize + stepSize)
                  val lastItems = queue.toChunk.takeRight(leftovers)
                  val result    = if (lastItems.isEmpty) Chunk.empty else Chunk.single(lastItems)
                  ZChannel.write(result) *> channelEnd
                }
              }

            def reader(queueSize: Int): ZChannel[Any, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] =
              ZChannel.readWithCause(
                (in: Chunk[A]) => {
                  ZChannel.write {
                    in.zipWithIndex.flatMap { case (i, idx) =>
                      queue.put(i)
                      val currentIndex = queueSize + idx + 1
                      if (currentIndex < chunkSize || (currentIndex - chunkSize) % stepSize > 0) None
                      else Some(queue.toChunk)
                    }
                  } *> reader(queueSize + in.length)
                },
                (cause: Cause[E]) => emitOnStreamEnd(queueSize)(ZChannel.failCause(cause)),
                (_: Any) => emitOnStreamEnd(queueSize)(ZChannel.unit)
              )

            self.channel >>> reader(0)
          })
      }
    }

  /**
   * Splits elements based on a predicate.
   * {{{
   *   ZStream.range(1, 10).split(_ % 4 == 0).runCollect // Chunk(Chunk(1, 2, 3), Chunk(5, 6, 7), Chunk(9))
   * }}}
   */
  final def split(f: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, Chunk[A]] = {
    def split(leftovers: Chunk[A])(in: Chunk[A]): ZChannel[R, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] = {
      val (chunk, remaining) = (leftovers ++ in).splitWhere(f)
      if (chunk.isEmpty || remaining.isEmpty) loop(chunk ++ remaining.drop(1))
      else ZChannel.write(Chunk.single(chunk)) *> split(Chunk.empty)(remaining.drop(1))
    }

    def loop(leftovers: Chunk[A]): ZChannel[R, E, Chunk[A], Any, E, Chunk[Chunk[A]], Any] =
      ZChannel.readWith(
        (in: Chunk[A]) => split(leftovers)(in),
        (e: E) => ZChannel.fail(e),
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
  final def splitOnChunk[A1 >: A](delimiter: => Chunk[A1])(implicit trace: ZTraceElement): ZStream[R, E, Chunk[A]] =
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
              case Some(chunk) => ZChannel.write(Chunk.single(chunk)) *> ZChannel.failCause(halt)
              case None        => ZChannel.failCause(halt)
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
  def take(n: => Long)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.take(n)

  /**
   * Takes the last specified number of elements from this stream.
   */
  def takeRight(n: => Int)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.succeed(n).flatMap { n =>
      if (n <= 0) ZStream.empty
      else
        new ZStream(
          ZChannel.unwrap(
            for {
              queue <- UIO(SingleThreadedRingBuffer[A](n))
            } yield {
              lazy val reader: ZChannel[Any, E, Chunk[A], Any, E, Chunk[A], Unit] = ZChannel.readWith(
                (in: Chunk[A]) => {
                  in.foreach(queue.put)
                  reader
                },
                ZChannel.fail(_),
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
  def takeUntil(f: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.takeUntil(f)

  /**
   * Takes all elements of the stream until the specified effectual predicate
   * evaluates to `true`.
   */
  @deprecated("use takeUntilZIO", "2.0.0")
  def takeUntilM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    takeUntilZIO(f)

  /**
   * Takes all elements of the stream until the specified effectual predicate
   * evaluates to `true`.
   */
  def takeUntilZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    loopOnPartialChunks { (chunk, emit) =>
      for {
        taken <- chunk.takeWhileZIO(v => emit(v) *> f(v).map(!_))
        last   = chunk.drop(taken.length).take(1)
      } yield last.isEmpty
    }

  /**
   * Takes all elements of the stream for as long as the specified predicate
   * evaluates to `true`.
   */
  def takeWhile(f: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    self >>> ZPipeline.takeWhile(f)

  /**
   * Adds an effect to consumption of every element of the stream.
   */
  final def tap[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    mapZIO(a => f(a).as(a))

  /**
   * Returns a stream that effectfully "peeks" at the failure of the stream.
   */
  final def tapError[R1 <: R, E1 >: E](
    f: E => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R1, E1, A] =
    catchAll(e => ZStream.fromZIO(f(e) *> ZIO.fail(e)))

  /**
   * Sends all elements emitted by this stream to the specified sink in addition
   * to emitting them.
   */
  final def tapSink[R1 <: R, E1 >: E](
    sink: => ZSink[R1, E1, A, Any, Any]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    ZStream.fromZIO(Queue.bounded[Take[E1, A]](1)).flatMap { queue =>
      val right = ZStream.fromQueueWithShutdown(queue, 1).flattenTake
      lazy val loop: ZChannel[R1, E, Chunk[A], Any, E1, Chunk[A], Any] =
        ZChannel.readWithCause(
          chunk =>
            ZChannel.fromZIO(queue.offer(Take.chunk(chunk))) *>
              ZChannel.write(chunk) *>
              loop,
          cause => ZChannel.fromZIO(queue.offer(Take.failCause(cause))),
          _ => ZChannel.fromZIO(queue.shutdown)
        )
      new ZStream(self.channel >>> loop)
        .merge(ZStream.execute(right.run(sink)), TerminationStrategy.Both)
    }

  /**
   * Throttles the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` function.
   */
  final def throttleEnforce(units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => Long
  )(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    throttleEnforceZIO(units, duration, burst)(as => UIO.succeedNow(costFn(as)))

  /**
   * Throttles the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` effectful function.
   */
  @deprecated("use throttleEnforceZIO", "2.0.0")
  final def throttleEnforceM[R1 <: R, E1 >: E](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => ZIO[R1, E1, Long]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, A] =
    throttleEnforceZIO[R1 with Clock, E1](units, duration, burst)(costFn)

  /**
   * Throttles the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` effectful function.
   */
  final def throttleEnforceZIO[R1 <: R, E1 >: E](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => ZIO[R1, E1, Long]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, A] =
    ZStream.succeed((units, duration, burst)).flatMap { case (units, duration, burst) =>
      def loop(tokens: Long, timestamp: Long): ZChannel[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[A], Unit] =
        ZChannel.readWith[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[A], Unit](
          (in: Chunk[A]) =>
            ZChannel.unwrap((costFn(in) <*> Clock.nanoTime).map { case (weight, current) =>
              val elapsed = current - timestamp
              val cycles  = elapsed.toDouble / duration.toNanos
              val available = {
                val sum = tokens + (cycles * units).toLong
                val max =
                  if (units + burst < 0) Long.MaxValue
                  else units + burst

                if (sum < 0) max
                else math.min(sum, max)
              }

              if (weight <= available)
                ZChannel.write(in) *> loop(available - weight, current)
              else
                loop(available, current)
            }),
          (e: E1) => ZChannel.fail(e),
          (_: Any) => ZChannel.unit
        )

      new ZStream(ZChannel.fromZIO(Clock.nanoTime).flatMap(self.channel >>> loop(units, _)))
    }

  /**
   * Delays the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` function.
   */
  final def throttleShape(units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => Long
  )(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    throttleShapeZIO(units, duration, burst)(os => UIO.succeedNow(costFn(os)))

  /**
   * Delays the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` effectful function.
   */
  @deprecated("use throttleShapeZIO", "2.0.0")
  final def throttleShapeM[R1 <: R, E1 >: E](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => ZIO[R1, E1, Long]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, A] =
    throttleShapeZIO[R1 with Clock, E1](units, duration, burst)(costFn)

  /**
   * Delays the chunks of this stream according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` effectful function.
   */
  final def throttleShapeZIO[R1 <: R, E1 >: E](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[A] => ZIO[R1, E1, Long]
  )(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, A] =
    ZStream.succeed((units, duration, burst)).flatMap { case (units, duration, burst) =>
      def loop(tokens: Long, timestamp: Long): ZChannel[R1 with Clock, E1, Chunk[A], Any, E1, Chunk[A], Unit] =
        ZChannel.readWith(
          (in: Chunk[A]) =>
            ZChannel.unwrap(for {
              weight  <- costFn(in)
              current <- Clock.nanoTime
            } yield {
              val elapsed = current - timestamp
              val cycles  = elapsed.toDouble / duration.toNanos
              val available = {
                val sum = tokens + (cycles * units).toLong
                val max =
                  if (units + burst < 0) Long.MaxValue
                  else units + burst

                if (sum < 0) max
                else math.min(sum, max)
              }

              val remaining = available - weight
              val waitCycles =
                if (remaining >= 0) 0
                else -remaining.toDouble / units

              val delay = Duration.Finite((waitCycles * duration.toNanos).toLong)

              if (delay > Duration.Zero)
                ZChannel.fromZIO(Clock.sleep(delay)) *> ZChannel.write(in) *> loop(remaining, current)
              else ZChannel.write(in) *> loop(remaining, current)
            }),
          (e: E1) => ZChannel.fail(e),
          (_: Any) => ZChannel.unit
        )

      new ZStream(ZChannel.fromZIO(Clock.nanoTime).flatMap(self.channel >>> loop(units, _)))
    }

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
  final def debounce(d: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] = {
    import DebounceState._
    import HandoffSignal._

    ZStream.unwrap(
      for {
        d       <- ZIO.succeed(d)
        scope   <- ZIO.forkScope
        handoff <- ZStream.Handoff.make[HandoffSignal[Unit, E, A]]
      } yield {
        def enqueue(last: Chunk[A]) =
          for {
            f <- Clock.sleep(d).as(last).forkIn(scope)
          } yield consumer(Previous(f))

        lazy val producer: ZChannel[R with Clock, E, Chunk[A], Any, E, Nothing, Any] =
          ZChannel.readWithCause(
            (in: Chunk[A]) =>
              in.lastOption.fold(producer) { last =>
                ZChannel.fromZIO(handoff.offer(Emit(Chunk.single(last)))) *> producer
              },
            (cause: Cause[E]) => ZChannel.fromZIO(handoff.offer(Halt(cause))),
            (_: Any) => ZChannel.fromZIO(handoff.offer(End(ZStream.SinkEndReason.UpstreamEnd)))
          )

        def consumer(state: DebounceState[E, A]): ZChannel[R with Clock, Any, Any, Any, E, Chunk[A], Any] =
          ZChannel.unwrap(
            state match {
              case NotStarted =>
                handoff.take.map {
                  case Emit(last) =>
                    ZChannel.unwrap(enqueue(last))
                  case HandoffSignal.Halt(error) =>
                    ZChannel.failCause(error)
                  case HandoffSignal.End(_) =>
                    ZChannel.unit
                }
              case Current(fiber) =>
                fiber.join.map {
                  case HandoffSignal.Emit(last)  => ZChannel.unwrap(enqueue(last))
                  case HandoffSignal.Halt(error) => ZChannel.failCause(error)
                  case HandoffSignal.End(_)      => ZChannel.unit
                }
              case Previous(fiber) =>
                fiber.join
                  .raceWith[R with Clock, E, E, HandoffSignal[Unit, E, A], ZChannel[
                    R with Clock,
                    Any,
                    Any,
                    Any,
                    E,
                    Chunk[A],
                    Any
                  ]](
                    handoff.take
                  )(
                    {
                      case (Exit.Success(a), current) =>
                        ZIO.succeedNow(ZChannel.write(a) *> consumer(Current(current)))
                      case (Exit.Failure(cause), current) =>
                        current.interrupt as ZChannel.failCause(cause)
                    },
                    {
                      case (Exit.Success(Emit(last)), previous) =>
                        previous.interrupt *> enqueue(last)
                      case (Exit.Success(Halt(cause)), previous) =>
                        previous.interrupt as ZChannel.failCause(cause)
                      case (Exit.Success(End(_)), previous) =>
                        previous.join.map(ZChannel.write(_) *> ZChannel.unit)
                      case (Exit.Failure(cause), previous) =>
                        previous.interrupt as ZChannel.failCause(cause)
                    }
                  )
            }
          )

        ZStream.managed((self.channel >>> producer).runManaged.fork) *>
          new ZStream(consumer(NotStarted))
      }
    )
  }

  /**
   * Ends the stream if it does not produce a value after d duration.
   */
  final def timeout(d: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    ZStream.succeed(d).flatMap { d =>
      ZStream.fromPull {
        self.toPull.map(pull => pull.timeoutFail(None)(d))
      }
    }

  /**
   * Fails the stream with given error if it does not produce a value after d
   * duration.
   */
  @deprecated("use timeoutFail", "2.0.0")
  final def timeoutError[E1 >: E](e: => E1)(d: Duration)(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, E1, A] =
    timeoutFail(e)(d)

  /**
   * Fails the stream with given error if it does not produce a value after d
   * duration.
   */
  final def timeoutFail[E1 >: E](e: => E1)(d: Duration)(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, E1, A] =
    self.timeoutTo[R with Clock, E1, A](d)(ZStream.fail(e))

  /**
   * Fails the stream with given cause if it does not produce a value after d
   * duration.
   */
  @deprecated("use timeoutFailCause", "2.0.0")
  final def timeoutErrorCause[E1 >: E](
    cause: => Cause[E1]
  )(d: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E1, A] =
    timeoutFailCause(cause)(d)

  /**
   * Fails the stream with given cause if it does not produce a value after d
   * duration.
   */
  final def timeoutFailCause[E1 >: E](
    cause: => Cause[E1]
  )(d: => Duration)(implicit trace: ZTraceElement): ZStream[R with Clock, E1, A] =
    ZStream.succeed((cause, d)).flatMap { case (cause, d) =>
      ZStream.fromPull {
        self.toPull.map(pull => pull.timeoutFailCause(cause.map(Some(_)))(d))
      }
    }

  /**
   * Halts the stream with given cause if it does not produce a value after d
   * duration.
   */
  @deprecated("use timeoutFailCause", "2.0.0")
  final def timeoutHalt[E1 >: E](cause: => Cause[E1])(d: => Duration)(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, E1, A] =
    timeoutFailCause(cause)(d)

  /**
   * Switches the stream if it does not produce a value after d duration.
   */
  final def timeoutTo[R1 <: R, E1 >: E, A2 >: A](
    d: => Duration
  )(that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1 with Clock, E1, A2] = {
    final case class StreamTimeout() extends Throwable
    self.timeoutFailCause(Cause.die(StreamTimeout()))(d).catchSomeCause { case Cause.Die(StreamTimeout(), _) => that }
  }

  /**
   * Converts the stream to a managed hub of chunks. After the managed hub is
   * used, the hub will never again produce values and should be discarded.
   */
  def toHub(
    capacity: => Int
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, ZHub[Nothing, Any, Any, Nothing, Nothing, Take[E, A]]] =
    for {
      hub <- Hub.bounded[Take[E, A]](capacity).toManagedWith(_.shutdown)
      _   <- self.runIntoHubManaged(hub).fork
    } yield hub

  /**
   * Converts this stream of bytes into a `java.io.InputStream` wrapped in a
   * [[ZManaged]]. The returned input stream will only be valid within the scope
   * of the ZManaged.
   */
  def toInputStream(implicit
    ev0: E <:< Throwable,
    ev1: A <:< Byte,
    trace: ZTraceElement
  ): ZManaged[R, E, java.io.InputStream] =
    for {
      runtime <- ZIO.runtime[R].toManaged
      pull    <- toPull.asInstanceOf[ZManaged[R, Nothing, ZIO[R, Option[Throwable], Chunk[Byte]]]]
    } yield ZInputStream.fromPull(runtime, pull)

  /**
   * Converts this stream into a `scala.collection.Iterator` wrapped in a
   * [[ZManaged]]. The returned iterator will only be valid within the scope of
   * the ZManaged.
   */
  def toIterator(implicit trace: ZTraceElement): ZManaged[R, Nothing, Iterator[Either[E, A]]] =
    for {
      runtime <- ZIO.runtime[R].toManaged
      pull    <- toPull
    } yield {
      def unfoldPull: Iterator[Either[E, A]] =
        runtime.unsafeRunSync(pull) match {
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

  def toPull(implicit trace: ZTraceElement): ZManaged[R, Nothing, ZIO[R, Option[E], Chunk[A]]] =
    channel.toPull.map { pull =>
      pull.mapError(error => Some(error)).flatMap {
        case Left(done)  => ZIO.fail(None)
        case Right(elem) => ZIO.succeed(elem)
      }
    }

  /**
   * Converts this stream of chars into a `java.io.Reader` wrapped in a
   * [[ZManaged]]. The returned reader will only be valid within the scope of
   * the ZManaged.
   */
  def toReader(implicit ev0: E <:< Throwable, ev1: A <:< Char, trace: ZTraceElement): ZManaged[R, E, java.io.Reader] =
    for {
      runtime <- ZIO.runtime[R].toManaged
      pull    <- toPull.asInstanceOf[ZManaged[R, Nothing, ZIO[R, Option[Throwable], Chunk[Char]]]]
    } yield ZReader.fromPull(runtime, pull)

  /**
   * Converts the stream to a managed queue of chunks. After the managed queue
   * is used, the queue will never again produce values and should be discarded.
   */
  final def toQueue(capacity: => Int = 2)(implicit trace: ZTraceElement): ZManaged[R, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- Queue.bounded[Take[E, A]](capacity).toManagedWith(_.shutdown)
      _     <- self.runIntoQueueManaged(queue).fork
    } yield queue

  /**
   * Converts the stream to a dropping managed queue of chunks. After the
   * managed queue is used, the queue will never again produce values and should
   * be discarded.
   */
  final def toQueueDropping(
    capacity: => Int = 2
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- Queue.dropping[Take[E, A]](capacity).toManagedWith(_.shutdown)
      _     <- self.runIntoQueueManaged(queue).fork
    } yield queue

  /**
   * Converts the stream to a managed queue of elements. After the managed queue
   * is used, the queue will never again produce values and should be discarded.
   */
  final def toQueueOfElements(
    capacity: => Int = 2
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, Dequeue[Exit[Option[E], A]]] =
    for {
      queue <- Queue.bounded[Exit[Option[E], A]](capacity).toManagedWith(_.shutdown)
      _     <- self.runIntoQueueElementsManaged(queue).fork
    } yield queue

  /**
   * Converts the stream to a sliding managed queue of chunks. After the managed
   * queue is used, the queue will never again produce values and should be
   * discarded.
   */
  final def toQueueSliding(
    capacity: => Int = 2
  )(implicit trace: ZTraceElement): ZManaged[R, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- Queue.sliding[Take[E, A]](capacity).toManagedWith(_.shutdown)
      _     <- self.runIntoQueueManaged(queue).fork
    } yield queue

  /**
   * Converts the stream into an unbounded managed queue. After the managed
   * queue is used, the queue will never again produce values and should be
   * discarded.
   */
  final def toQueueUnbounded(implicit trace: ZTraceElement): ZManaged[R, Nothing, Dequeue[Take[E, A]]] =
    for {
      queue <- Queue.unbounded[Take[E, A]].toManagedWith(_.shutdown)
      _     <- self.runIntoQueueManaged(queue).fork
    } yield queue

  /**
   * Applies the transducer to the stream and emits its outputs.
   */
  def transduce[R1 <: R, E1 >: E, A1 >: A, Z](
    sink: => ZSink[R1, E1, A1, A1, Z]
  )(implicit trace: ZTraceElement): ZStream[R1, E1, Z] =
    self >>> ZPipeline.fromSink(sink)

  /**
   * Updates a service in the environment of this effect.
   */
  final def updateService[M] =
    new ZStream.UpdateService[R, E, A, M](self)

  /**
   * Updates a service at the specified key in the environment of this effect.
   */
  final def updateServiceAt[Service]: ZStream.UpdateServiceAt[R, E, A, Service] =
    new ZStream.UpdateServiceAt[R, E, A, Service](self)

  /**
   * Threads the stream through a transformation pipeline.
   */
  def via[R1 <: R, E1 >: E, B](pipeline: ZPipeline[R1, E1, A, B])(implicit trace: ZTraceElement): ZStream[R1, E1, B] =
    pipeline(self)

  /**
   * Threads the stream through the transformation function `f`.
   */
  def viaFunction[R2, E2, B](f: ZStream[R, E, A] => ZStream[R2, E2, B])(implicit
    trace: ZTraceElement
  ): ZStream[R2, E2, B] =
    f(self)

  /**
   * Returns this stream if the specified condition is satisfied, otherwise
   * returns an empty stream.
   */
  def when(b: => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.when(b)(self)

  /**
   * Returns this stream if the specified effectful condition is satisfied,
   * otherwise returns an empty stream.
   */
  @deprecated("use whenZIO", "2.0.0")
  def whenM[R1 <: R, E1 >: E](b: => ZIO[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    whenZIO(b)

  /**
   * Returns this stream if the specified effectful condition is satisfied,
   * otherwise returns an empty stream.
   */
  def whenZIO[R1 <: R, E1 >: E](b: => ZIO[R1, E1, Boolean])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    ZStream.whenZIO(b)(self)

  /**
   * Equivalent to [[filter]] but enables the use of filter clauses in
   * for-comprehensions
   */
  def withFilter(predicate: A => Boolean)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    filter(predicate)

  /**
   * Runs this stream on the specified runtime configuration. Any streams that
   * are composed after this one will be run on the previous executor.
   */
  def withRuntimeConfig(runtimeConfig: => RuntimeConfig)(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.fromZIO(ZIO.runtimeConfig).flatMap { currentRuntimeConfig =>
      ZStream.managed(ZManaged.withRuntimeConfig(runtimeConfig)) *>
        self <*
        ZStream.fromZIO(ZIO.setRuntimeConfig(currentRuntimeConfig))
    }

  /**
   * Zips this stream with another point-wise, but keeps only the outputs of
   * this stream.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipLeft[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A] =
    zipWithChunks(that)(zipLeftChunks)

  /**
   * Zips this stream with another point-wise, but keeps only the outputs of the
   * other stream.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipRight[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit trace: ZTraceElement): ZStream[R1, E1, A2] =
    zipWithChunks(that)(zipRightChunks)

  /**
   * Zips this stream with another point-wise and emits tuples of elements from
   * both streams.
   *
   * The new stream will end when one of the sides ends.
   */
  def zip[R1 <: R, E1 >: E, A2](that: => ZStream[R1, E1, A2])(implicit
    zippable: Zippable[A, A2],
    trace: ZTraceElement
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
  )(defaultLeft: => A1, defaultRight: => A2)(implicit trace: ZTraceElement): ZStream[R1, E1, (A1, A2)] =
    zipAllWith(that)((_, defaultRight), (defaultLeft, _))((_, _))

  /**
   * Zips this stream with another point-wise, and keeps only elements from this
   * stream.
   *
   * The provided default value will be used if the other stream ends before
   * this one.
   */
  def zipAllLeft[R1 <: R, E1 >: E, A1 >: A, A2](that: => ZStream[R1, E1, A2])(default: => A1)(implicit
    trace: ZTraceElement
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
    trace: ZTraceElement
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
  )(left: A => A3, right: A2 => A3)(both: (A, A2) => A3)(implicit trace: ZTraceElement): ZStream[R1, E1, A3] = {

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
            err => ZIO.succeedNow(Exit.fail(err)),
            leftChunk => ZIO.succeedNow(Exit.succeed(leftChunk.map(left) -> DrainLeft))
          )
        case DrainRight =>
          pullRight.foldZIO(
            err => ZIO.succeedNow(Exit.fail(err)),
            rightChunk => ZIO.succeedNow(Exit.succeed(rightChunk.map(right) -> DrainRight))
          )
        case PullBoth =>
          pullLeft.unsome
            .zipPar(pullRight.unsome)
            .foldZIO(
              err => ZIO.succeedNow(Exit.fail(Some(err))),
              {
                case (Some(leftChunk), Some(rightChunk)) =>
                  if (leftChunk.isEmpty && rightChunk.isEmpty)
                    pull(PullBoth, pullLeft, pullRight)
                  else if (leftChunk.isEmpty)
                    pull(PullLeft(rightChunk), pullLeft, pullRight)
                  else if (rightChunk.isEmpty)
                    pull(PullRight(leftChunk), pullLeft, pullRight)
                  else
                    ZIO.succeedNow(Exit.succeed(zipWithChunks(leftChunk, rightChunk, both)))
                case (Some(leftChunk), None) =>
                  ZIO.succeedNow(Exit.succeed(leftChunk.map(left) -> DrainLeft))
                case (None, Some(rightChunk)) =>
                  ZIO.succeedNow(Exit.succeed(rightChunk.map(right) -> DrainRight))
                case (None, None) =>
                  ZIO.succeedNow(Exit.fail(None))
              }
            )
        case PullLeft(rightChunk) =>
          pullLeft.foldZIO(
            {
              case Some(err) => ZIO.succeedNow(Exit.fail(Some(err)))
              case None      => ZIO.succeedNow(Exit.succeed(rightChunk.map(right) -> DrainRight))
            },
            leftChunk =>
              if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else
                ZIO.succeedNow(Exit.succeed(zipWithChunks(leftChunk, rightChunk, both)))
          )
        case PullRight(leftChunk) =>
          pullRight.foldZIO(
            {
              case Some(err) => ZIO.succeedNow(Exit.fail(Some(err)))
              case None      => ZIO.succeedNow(Exit.succeed(leftChunk.map(left) -> DrainLeft))
            },
            rightChunk =>
              if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else
                ZIO.succeedNow(Exit.succeed(zipWithChunks(leftChunk, rightChunk, both)))
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
   * Zips this stream with another point-wise and applies the function to the
   * paired elements.
   *
   * The new stream will end when one of the sides ends.
   */
  def zipWith[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(f: (A, A2) => A3)(implicit trace: ZTraceElement): ZStream[R1, E1, A3] =
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
  )(implicit trace: ZTraceElement): ZStream[R1, E1, A3] = {
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
          pullLeft
            .zipPar(pullRight)
            .foldZIO(
              err => ZIO.succeedNow(Exit.fail(err)),
              { case (leftChunk, rightChunk) =>
                if (leftChunk.isEmpty && rightChunk.isEmpty)
                  pull(PullBoth, pullLeft, pullRight)
                else if (leftChunk.isEmpty)
                  pull(PullLeft(rightChunk), pullLeft, pullRight)
                else if (rightChunk.isEmpty)
                  pull(PullRight(leftChunk), pullLeft, pullRight)
                else
                  ZIO.succeedNow(Exit.succeed(zipWithChunks(leftChunk, rightChunk)))
              }
            )
        case PullLeft(rightChunk) =>
          pullLeft.foldZIO(
            err => ZIO.succeedNow(Exit.fail(err)),
            leftChunk =>
              if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else
                ZIO.succeedNow(Exit.succeed(zipWithChunks(leftChunk, rightChunk)))
          )
        case PullRight(leftChunk) =>
          pullRight.foldZIO(
            err => ZIO.succeedNow(Exit.fail(err)),
            rightChunk =>
              if (rightChunk.isEmpty)
                pull(PullRight(leftChunk), pullLeft, pullRight)
              else if (leftChunk.isEmpty)
                pull(PullLeft(rightChunk), pullLeft, pullRight)
              else
                ZIO.succeedNow(Exit.succeed(zipWithChunks(leftChunk, rightChunk)))
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
  final def zipWithIndex(implicit trace: ZTraceElement): ZStream[R, E, (A, Long)] =
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
  final def zipWithLatest[R1 <: R, E1 >: E, A2, A3](
    that: => ZStream[R1, E1, A2]
  )(f: (A, A2) => A3)(implicit trace: ZTraceElement): ZStream[R1, E1, A3] = {
    def pullNonEmpty[R, E, O](pull: ZIO[R, Option[E], Chunk[O]]): ZIO[R, Option[E], Chunk[O]] =
      pull.flatMap(chunk => if (chunk.isEmpty) pullNonEmpty(pull) else UIO.succeedNow(chunk))

    ZStream.fromPull {
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
   * Zips each element with the next element if present.
   */
  final def zipWithNext(implicit trace: ZTraceElement): ZStream[R, E, (A, Option[A])] = {
    def process(last: Option[A]): ZChannel[Any, E, Chunk[A], Any, E, Chunk[(A, Option[A])], Unit] =
      ZChannel.readWith(
        in => {
          val (newLast, chunk) = in.mapAccum(last)((prev, curr) => (Some(curr), prev.map((_, curr))))
          val out              = chunk.collect { case Some((prev, curr)) => (prev, Some(curr)) }
          ZChannel.write(out) *> process(newLast)
        },
        err => ZChannel.fail(err),
        _ =>
          last match {
            case Some(value) =>
              ZChannel.write(Chunk.single((value, None))) *> ZChannel.unit
            case None =>
              ZChannel.unit
          }
      )

    new ZStream(self.channel >>> process(None))
  }

  /**
   * Zips each element with the previous element. Initially accompanied by
   * `None`.
   */
  final def zipWithPrevious(implicit trace: ZTraceElement): ZStream[R, E, (Option[A], A)] =
    mapAccum[Option[A], (Option[A], A)](None)((prev, next) => (Some(next), (prev, next)))

  /**
   * Zips each element with both the previous and next element.
   */
  final def zipWithPreviousAndNext(implicit trace: ZTraceElement): ZStream[R, E, (Option[A], A, Option[A])] =
    zipWithPrevious.zipWithNext.map { case ((prev, curr), next) => (prev, curr, next.map(_._2)) }
}

object ZStream extends ZStreamPlatformSpecificConstructors {
  def fromPull[R, E, A](zio: ZManaged[R, Nothing, ZIO[R, Option[E], Chunk[A]]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    ZStream.unwrapManaged(zio.map(pull => repeatZIOChunkOption(pull)))

  /**
   * The default chunk size used by the various combinators and constructors of
   * [[ZStream]].
   */
  final val DefaultChunkSize = 4096

  /**
   * Submerges the error case of an `Either` into the `ZStream`.
   */
  def absolve[R, E, O](xs: ZStream[R, E, Either[E, O]])(implicit trace: ZTraceElement): ZStream[R, E, O] =
    xs.mapZIO(ZIO.fromEither(_))

  /**
   * Accesses the environment of the stream in the context of an effect.
   */
  @deprecated("use environmentWith", "2.0.0")
  def access[R]: EnvironmentWithPartiallyApplied[R] =
    environmentWith

  /**
   * Accesses the environment of the stream in the context of an effect.
   */
  @deprecated("use environmentWithZIO", "2.0.0")
  def accessM[R]: EnvironmentWithZIOPartiallyApplied[R] =
    environmentWithZIO

  /**
   * Accesses the environment of the stream in the context of an effect.
   */
  @deprecated("use environmentWithZIO", "2.0.0")
  def accessZIO[R]: EnvironmentWithZIOPartiallyApplied[R] =
    environmentWithZIO

  /**
   * Accesses the environment of the stream in the context of a stream.
   */
  @deprecated("use environmentWithStream", "2.0.0")
  def accessStream[R]: EnvironmentWithStreamPartiallyApplied[R] =
    environmentWithStream

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  def acquireReleaseWith[R, E, A](acquire: ZIO[R, E, A])(release: A => URIO[R, Any])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    managed(ZManaged.acquireReleaseWith(acquire)(release))

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  def acquireReleaseExitWith[R, E, A](
    acquire: ZIO[R, E, A]
  )(release: (A, Exit[Any, Any]) => URIO[R, Any])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    managed(ZManaged.acquireReleaseExitWith(acquire)(release))

  /**
   * Creates a pure stream from a variable list of values
   */
  def apply[A](as: A*)(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] = fromIterable(as)

  /**
   * Locks the execution of the specified stream to the blocking executor. Any
   * streams that are composed after this one will automatically be shifted back
   * to the previous executor.
   */
  def blocking[R, E, A](stream: ZStream[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    ZStream.fromZIO(ZIO.blockingExecutor).flatMap(stream.onExecutor(_))

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  def bracket[R, E, A](acquire: => ZIO[R, E, A])(release: A => URIO[R, Any])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    acquireReleaseWith(acquire)(release)

  /**
   * Creates a stream from a single value that will get cleaned up after the
   * stream is consumed
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[R, E, A](
    acquire: => ZIO[R, E, A]
  )(release: (A, Exit[Any, Any]) => URIO[R, Any])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    acquireReleaseExitWith(acquire)(release)

  /**
   * Composes the specified streams to create a cartesian product of elements
   * with a specified function. Subsequent streams would be run multiple times,
   * for every combination of elements in the prior streams.
   *
   * See also [[ZStream#zipN[R,E,A,B,C]*]] for the more common point-wise
   * variant.
   */
  @deprecated("use instance method cross", "2.0.0")
  def crossN[R, E, A, B, C](zStream1: => ZStream[R, E, A], zStream2: => ZStream[R, E, B])(
    f: (A, B) => C
  )(implicit trace: ZTraceElement): ZStream[R, E, C] =
    ZStream.suspend(zStream1.crossWith(zStream2)(f))

  /**
   * Composes the specified streams to create a cartesian product of elements
   * with a specified function. Subsequent stream would be run multiple times,
   * for every combination of elements in the prior streams.
   *
   * See also [[ZStream#zipN[R,E,A,B,C,D]*]] for the more common point-wise
   * variant.
   */
  @deprecated("use cross", "2.0.0")
  def crossN[R, E, A, B, C, D](
    zStream1: => ZStream[R, E, A],
    zStream2: => ZStream[R, E, B],
    zStream3: => ZStream[R, E, C]
  )(
    f: (A, B, C) => D
  )(implicit trace: ZTraceElement): ZStream[R, E, D] =
    ZStream.suspend {
      for {
        a <- zStream1
        b <- zStream2
        c <- zStream3
      } yield f(a, b, c)
    }

  /**
   * Composes the specified streams to create a cartesian product of elements
   * with a specified function. Subsequent stream would be run multiple times,
   * for every combination of elements in the prior streams.
   *
   * See also [[ZStream#zipN[R,E,A,B,C,D,F]*]] for the more common point-wise
   * variant.
   */
  @deprecated("use cross", "2.0.0")
  def crossN[R, E, A, B, C, D, F](
    zStream1: => ZStream[R, E, A],
    zStream2: => ZStream[R, E, B],
    zStream3: => ZStream[R, E, C],
    zStream4: => ZStream[R, E, D]
  )(
    f: (A, B, C, D) => F
  )(implicit trace: ZTraceElement): ZStream[R, E, F] =
    ZStream.suspend {
      for {
        a <- zStream1
        b <- zStream2
        c <- zStream3
        d <- zStream4
      } yield f(a, b, c, d)
    }

  /**
   * Concatenates all of the streams in the chunk to one stream.
   */
  def concatAll[R, E, O](streams: => Chunk[ZStream[R, E, O]])(implicit trace: ZTraceElement): ZStream[R, E, O] =
    ZStream.suspend(streams.foldLeft[ZStream[R, E, O]](empty)(_ ++ _))

  /**
   * Prints the specified message to the console for debugging purposes.
   */
  def debug(value: => Any)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.debug(value))

  /**
   * The stream that dies with the `ex`.
   */
  def die(ex: => Throwable)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Nothing] =
    fromZIO(ZIO.die(ex))

  /**
   * The stream that dies with an exception described by `msg`.
   */
  def dieMessage(msg: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Nothing] =
    fromZIO(ZIO.dieMessage(msg))

  /**
   * The stream that ends with the [[zio.Exit]] value `exit`.
   */
  def done[E, A](exit: => Exit[E, A])(implicit trace: ZTraceElement): ZStream[Any, E, A] =
    fromZIO(ZIO.done(exit))

  /**
   * The empty stream
   */
  def empty(implicit trace: ZTraceElement): ZStream[Any, Nothing, Nothing] =
    new ZStream(ZChannel.write(Chunk.empty))

  /**
   * Accesses the whole environment of the stream.
   */
  def environment[R](implicit trace: ZTraceElement): ZStream[R, Nothing, ZEnvironment[R]] =
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
  def execute[R, E](zio: => ZIO[R, E, Any])(implicit trace: ZTraceElement): ZStream[R, E, Nothing] =
    ZStream.fromZIO(zio).drain

  /**
   * The stream that always fails with the `error`
   */
  def fail[E](error: => E)(implicit trace: ZTraceElement): ZStream[Any, E, Nothing] =
    fromZIO(ZIO.fail(error))

  /**
   * The stream that always fails with `cause`.
   */
  def failCause[E](cause: => Cause[E])(implicit trace: ZTraceElement): ZStream[Any, E, Nothing] =
    fromZIO(ZIO.failCause(cause))

  /**
   * Creates a one-element stream that never fails and executes the finalizer
   * when it ends.
   */
  def finalizer[R](finalizer: => URIO[R, Any])(implicit trace: ZTraceElement): ZStream[R, Nothing, Any] =
    acquireReleaseWith[R, Nothing, Unit](UIO.unit)(_ => finalizer)

  def from[Input](
    input: => Input
  )(implicit constructor: ZStreamConstructor[Input], trace: ZTraceElement): constructor.Out =
    constructor.make(input)

  /**
   * Creates a stream from an blocking iterator that may throw exceptions.
   */
  @deprecated("use blocking(fromIterator())", "2.0.0")
  def fromBlockingIterator[A](iterator: => Iterator[A])(implicit trace: ZTraceElement): ZStream[Any, Throwable, A] =
    blocking(fromIterator(iterator))

  /**
   * Creates a stream from an blocking Java iterator that may throw exceptions.
   */
  @deprecated("use blocking(fromJavaIterator())", "2.0.0")
  def fromBlockingJavaIterator[A](iter: => java.util.Iterator[A])(implicit
    trace: ZTraceElement
  ): ZStream[Any, Throwable, A] =
    blocking(fromJavaIterator(iter))

  /**
   * Creates a stream from a [[zio.Chunk]] of values
   *
   * @param c
   *   a chunk of values
   * @return
   *   a finite stream of values
   */
  def fromChunk[O](chunk: => Chunk[O])(implicit trace: ZTraceElement): ZStream[Any, Nothing, O] =
    new ZStream(
      ZChannel.succeed(chunk).flatMap { chunk =>
        if (chunk.isEmpty) ZChannel.unit else ZChannel.write(chunk)
      }
    )

  /**
   * Creates a stream from a subscription to a hub.
   */
  def fromChunkHub[R, E, O](hub: => ZHub[Nothing, R, Any, E, Nothing, Chunk[O]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, O] =
    managed(hub.subscribe).flatMap(queue => fromChunkQueue(queue))

  /**
   * Creates a stream from a subscription to a hub in the context of a managed
   * effect. The managed effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   */
  def fromChunkHubManaged[R, E, O](
    hub: => ZHub[Nothing, R, Any, E, Nothing, Chunk[O]]
  )(implicit trace: ZTraceElement): ZManaged[Any, Nothing, ZStream[R, E, O]] =
    hub.subscribe.map(queue => fromChunkQueue(queue))

  /**
   * Creates a stream from a subscription to a hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromChunkHubWithShutdown[R, E, O](hub: => ZHub[Nothing, R, Any, E, Nothing, Chunk[O]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, O] =
    fromChunkHub(hub).ensuring(hub.shutdown)

  /**
   * Creates a stream from a subscription to a hub in the context of a managed
   * effect. The managed effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromChunkHubManagedWithShutdown[R, E, O](
    hub: => ZHub[Nothing, R, Any, E, Nothing, Chunk[O]]
  )(implicit trace: ZTraceElement): ZManaged[Any, Nothing, ZStream[R, E, O]] =
    fromChunkHubManaged(hub).map(_.ensuring(hub.shutdown))

  /**
   * Creates a stream from a queue of values
   */
  def fromChunkQueue[R, E, O](
    queue: => ZQueue[Nothing, R, Any, E, Nothing, Chunk[O]]
  )(implicit trace: ZTraceElement): ZStream[R, E, O] =
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
  def fromChunkQueueWithShutdown[R, E, O](queue: => ZQueue[Nothing, R, Any, E, Nothing, Chunk[O]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, O] =
    fromChunkQueue(queue).ensuring(queue.shutdown)

  /**
   * Creates a stream from an arbitrary number of chunks.
   */
  def fromChunks[O](cs: Chunk[O]*)(implicit trace: ZTraceElement): ZStream[Any, Nothing, O] =
    fromIterable(cs).flatMap(fromChunk(_))

  /**
   * Creates a stream from an effect producing a value of type `A`
   */
  @deprecated("use fromZIO", "2.0.0")
  def fromEffect[R, E, A](fa: => ZIO[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    fromZIO(fa)

  /**
   * Creates a stream from an effect producing a value of type `A` or an empty
   * Stream
   */
  @deprecated("use fromZIOOption", "2.0.0")
  def fromEffectOption[R, E, A](fa: => ZIO[R, Option[E], A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    fromZIOOption(fa)

  /**
   * Creates a stream from a subscription to a hub.
   */
  def fromHub[R, E, A](
    hub: => ZHub[Nothing, R, Any, E, Nothing, A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    managed(hub.subscribe).flatMap(queue => fromQueue(queue, maxChunkSize))

  /**
   * Creates a stream from a subscription to a hub in the context of a managed
   * effect. The managed effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   */
  def fromHubManaged[R, E, A](
    hub: => ZHub[Nothing, R, Any, E, Nothing, A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: ZTraceElement): ZManaged[Any, Nothing, ZStream[R, E, A]] =
    ZManaged.suspend(hub.subscribe.map(queue => fromQueueWithShutdown(queue, maxChunkSize)))

  /**
   * Creates a stream from a subscription to a hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromHubWithShutdown[R, E, A](
    hub: => ZHub[Nothing, R, Any, E, Nothing, A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    fromHub(hub, maxChunkSize).ensuring(hub.shutdown)

  /**
   * Creates a stream from a subscription to a hub in the context of a managed
   * effect. The managed effect describes subscribing to receive messages from
   * the hub while the stream describes taking messages from the hub.
   *
   * The hub will be shut down once the stream is closed.
   */
  def fromHubManagedWithShutdown[R, E, A](
    hub: => ZHub[Nothing, R, Any, E, Nothing, A],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: ZTraceElement): ZManaged[Any, Nothing, ZStream[R, E, A]] =
    fromHubManaged(hub, maxChunkSize).map(_.ensuring(hub.shutdown))

  /**
   * Creates a stream from a `java.io.InputStream`
   */
  def fromInputStream(
    is: => InputStream,
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[Any, IOException, Byte] =
    ZStream.succeed((is, chunkSize)).flatMap { case (is, chunkSize) =>
      ZStream.repeatZIOChunkOption {
        for {
          bufArray  <- UIO(Array.ofDim[Byte](chunkSize))
          bytesRead <- ZIO.attemptBlockingIO(is.read(bufArray)).asSomeError
          bytes <- if (bytesRead < 0)
                     ZIO.fail(None)
                   else if (bytesRead == 0)
                     UIO(Chunk.empty)
                   else if (bytesRead < chunkSize)
                     UIO(Chunk.fromArray(bufArray).take(bytesRead))
                   else
                     UIO(Chunk.fromArray(bufArray))
        } yield bytes
      }
    }

  /**
   * Creates a stream from a `java.io.InputStream`. Ensures that the input
   * stream is closed after it is exhausted.
   */
  @deprecated("use fromInputStreamZIO", "2.0.0")
  def fromInputStreamEffect[R](
    is: => ZIO[R, IOException, InputStream],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, IOException, Byte] =
    fromInputStreamZIO(is, chunkSize)

  /**
   * Creates a stream from a `java.io.InputStream`. Ensures that the input
   * stream is closed after it is exhausted.
   */
  def fromInputStreamZIO[R](
    is: => ZIO[R, IOException, InputStream],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, IOException, Byte] =
    fromInputStreamManaged(is.toManagedWith(is => ZIO.succeed(is.close())), chunkSize)

  /**
   * Creates a stream from a managed `java.io.InputStream` value.
   */
  def fromInputStreamManaged[R](
    is: => ZManaged[R, IOException, InputStream],
    chunkSize: => Int = ZStream.DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, IOException, Byte] =
    ZStream.managed(is).flatMap(fromInputStream(_, chunkSize))

  /**
   * Creates a stream from an iterable collection of values
   */
  def fromIterable[O](as: => Iterable[O])(implicit trace: ZTraceElement): ZStream[Any, Nothing, O] =
    fromChunk(Chunk.fromIterable(as))

  /**
   * Creates a stream from an effect producing a value of type `Iterable[A]`
   */
  @deprecated("use fromIterableZIO", "2.0.0")
  def fromIterableM[R, E, O](iterable: => ZIO[R, E, Iterable[O]])(implicit trace: ZTraceElement): ZStream[R, E, O] =
    fromIterableZIO(iterable)

  /**
   * Creates a stream from an effect producing a value of type `Iterable[A]`
   */
  def fromIterableZIO[R, E, O](iterable: => ZIO[R, E, Iterable[O]])(implicit trace: ZTraceElement): ZStream[R, E, O] =
    fromZIO(iterable).mapConcat(identity)

  def fromIterator[A](iterator: => Iterator[A], maxChunkSize: Int = DefaultChunkSize)(implicit
    trace: ZTraceElement
  ): ZStream[Any, Throwable, A] =
    if (maxChunkSize == 1) fromIteratorSingle(iterator)
    else {
      object StreamEnd extends Throwable

      ZStream.fromZIO(Task(iterator) <*> ZIO.runtime[Any] <*> UIO(ChunkBuilder.make[A](maxChunkSize))).flatMap {
        case (it, rt, builder) =>
          ZStream.repeatZIOChunkOption {
            Task {
              builder.clear()
              var count = 0

              try {
                while (count < maxChunkSize && it.hasNext) {
                  builder += it.next()
                  count += 1
                }
              } catch {
                case e: Throwable if !rt.runtimeConfig.fatal(e) =>
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

  private def fromIteratorSingle[A](iterator: => Iterator[A])(implicit
    trace: ZTraceElement
  ): ZStream[Any, Throwable, A] = {
    object StreamEnd extends Throwable

    ZStream.fromZIO(Task(iterator) <*> ZIO.runtime[Any]).flatMap { case (it, rt) =>
      ZStream.repeatZIOOption {
        Task {
          val hasNext: Boolean =
            try it.hasNext
            catch {
              case e: Throwable if !rt.runtimeConfig.fatal(e) =>
                throw e
            }

          if (hasNext) {
            try it.next()
            catch {
              case e: Throwable if !rt.runtimeConfig.fatal(e) =>
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
   * Creates a stream from an iterator that may potentially throw exceptions
   */
  @deprecated("use fromIteratorZIO", "2.0.0")
  def fromIteratorEffect[R, A](
    iterator: => ZIO[R, Throwable, Iterator[A]]
  )(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
    fromIteratorZIO(iterator)

  /**
   * Creates a stream from a managed iterator
   */
  def fromIteratorManaged[R, A](
    iterator: => ZManaged[R, Throwable, Iterator[A]],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit
    trace: ZTraceElement
  ): ZStream[R, Throwable, A] =
    managed(iterator).flatMap(fromIterator(_, maxChunkSize))

  /**
   * Creates a stream from an iterator
   */
  def fromIteratorSucceed[A](iterator: => Iterator[A], maxChunkSize: => Int = DefaultChunkSize)(implicit
    trace: ZTraceElement
  ): ZStream[Any, Nothing, A] =
    ZStream.unwrap {
      UIO(ChunkBuilder.make[A]()).map { builder =>
        def loop(iterator: Iterator[A]): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] =
          ZChannel.unwrap {
            UIO {
              if (maxChunkSize == 1) {
                if (iterator.hasNext) {
                  ZChannel.write(Chunk.single(iterator.next())) *> loop(iterator)
                } else ZChannel.unit
              } else {
                builder.clear()
                var count = 0
                while (count < maxChunkSize && iterator.hasNext) {
                  builder += iterator.next()
                  count += 1
                }
                if (count > 0) {
                  ZChannel.write(builder.result()) *> loop(iterator)
                } else ZChannel.unit
              }
            }
          }

        new ZStream(loop(iterator))
      }
    }

  /**
   * Creates a stream from an iterator
   */
  @deprecated("use fromIteratorSucceed", "2.0.0")
  def fromIteratorTotal[A](iterator: => Iterator[A], maxChunkSize: => Int = DefaultChunkSize)(implicit
    trace: ZTraceElement
  ): ZStream[Any, Nothing, A] =
    fromIteratorSucceed(iterator, maxChunkSize)

  /**
   * Creates a stream from an iterator that may potentially throw exceptions
   */
  def fromIteratorZIO[R, A](
    iterator: => ZIO[R, Throwable, Iterator[A]]
  )(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
    fromZIO(iterator).flatMap(fromIterator(_))

  /**
   * Creates a stream from a Java iterator that may throw exceptions
   */
  def fromJavaIterator[A](
    iterator: => java.util.Iterator[A]
  )(implicit trace: ZTraceElement): ZStream[Any, Throwable, A] =
    fromIterator {
      val it = iterator // Scala 2.13 scala.collection.Iterator has `iterator` in local scope
      new Iterator[A] {
        def next(): A        = it.next
        def hasNext: Boolean = it.hasNext
      }
    }

  /**
   * Creates a stream from a Java iterator that may potentially throw exceptions
   */
  @deprecated("use fromJavaIteratorZIO", "2.0.0")
  def fromJavaIteratorEffect[R, A](
    iterator: => ZIO[R, Throwable, java.util.Iterator[A]]
  )(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
    fromJavaIteratorZIO(iterator)

  /**
   * Creates a stream from a managed iterator
   */
  def fromJavaIteratorManaged[R, A](iterator: => ZManaged[R, Throwable, java.util.Iterator[A]])(implicit
    trace: ZTraceElement
  ): ZStream[R, Throwable, A] =
    managed(iterator).flatMap(fromJavaIterator(_))

  /**
   * Creates a stream from a Java iterator
   */
  def fromJavaIteratorSucceed[A](
    iterator: => java.util.Iterator[A]
  )(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
    fromIteratorSucceed {
      val it = iterator // Scala 2.13 scala.collection.Iterator has `iterator` in local scope
      new Iterator[A] {
        def next(): A        = it.next
        def hasNext: Boolean = it.hasNext
      }
    }

  /**
   * Creates a stream from a Java iterator
   */
  @deprecated("use fromJavaIteratorSucceed", "2.0.0")
  def fromJavaIteratorTotal[A](iterator: => java.util.Iterator[A])(implicit
    trace: ZTraceElement
  ): ZStream[Any, Nothing, A] =
    fromJavaIteratorSucceed(iterator)

  /**
   * Creates a stream from a Java iterator that may potentially throw exceptions
   */
  def fromJavaIteratorZIO[R, A](
    iterator: => ZIO[R, Throwable, java.util.Iterator[A]]
  )(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
    fromZIO(iterator).flatMap(fromJavaIterator(_))

  /**
   * Creates a stream from a queue of values
   *
   * @param maxChunkSize
   *   Maximum number of queued elements to put in one chunk in the stream
   */
  def fromQueue[R, E, O](
    queue: => ZQueue[Nothing, R, Any, E, Nothing, O],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, E, O] =
    repeatZIOChunkOption {
      queue
        .takeBetween(1, maxChunkSize)
        .map(Chunk.fromIterable)
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
  def fromQueueWithShutdown[R, E, O](
    queue: => ZQueue[Nothing, R, Any, E, Nothing, O],
    maxChunkSize: => Int = DefaultChunkSize
  )(implicit trace: ZTraceElement): ZStream[R, E, O] =
    fromQueue(queue, maxChunkSize).ensuring(queue.shutdown)

  /**
   * Creates a stream from a [[zio.Schedule]] that does not require any further
   * input. The stream will emit an element for each value output from the
   * schedule, continuing for as long as the schedule continues.
   */
  def fromSchedule[R, A](schedule: => Schedule[R, Any, A])(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, Nothing, A] =
    unwrap(schedule.driver.map(driver => repeatZIOOption(driver.next(()))))

  /**
   * Creates a stream from a [[zio.stm.TQueue]] of values.
   */
  def fromTQueue[A](queue: => TQueue[A])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
    repeatZIOChunk(queue.take.map(Chunk.single(_)).commit)

  /**
   * Creates a stream from an effect producing a value of type `A`
   */
  def fromZIO[R, E, A](fa: => ZIO[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    fromZIOOption(fa.mapError(Some(_)))

  /**
   * Creates a stream from an effect producing a value of type `A` or an empty
   * Stream
   */
  def fromZIOOption[R, E, A](fa: => ZIO[R, Option[E], A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    new ZStream(
      ZChannel.unwrap(
        fa.fold(
          {
            case Some(e) => ZChannel.fail(e)
            case None    => ZChannel.unit
          },
          a => ZChannel.write(Chunk(a))
        )
      )
    )

  /**
   * The stream that always halts with `cause`.
   */
  @deprecated("use failCause", "2.0.0")
  def halt[E](cause: => Cause[E])(implicit trace: ZTraceElement): ZStream[Any, E, Nothing] =
    failCause(cause)

  /**
   * The infinite stream of iterative function application: a, f(a), f(f(a)),
   * f(f(f(a))), ...
   */
  def iterate[A](a: => A)(f: A => A)(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
    unfold(a)(a => Some((a, f(a))))

  /**
   * Logs the specified message at the current log level.
   */
  def log(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.log(message))

  /**
   * Annotates each log in streams composed after this with the specified log
   * annotation.
   */
  def logAnnotate(key: => String, value: => String)(implicit
    trace: ZTraceElement
  ): ZStream[Any, Nothing, Unit] =
    ZStream.managed(ZManaged.logAnnotate(key, value))

  /**
   * Retrieves the log annotations associated with the current scope.
   */
  def logAnnotations(implicit trace: ZTraceElement): ZStream[Any, Nothing, Map[String, String]] =
    ZStream.fromZIO(ZFiberRef.currentLogAnnotations.get)

  /**
   * Logs the specified message at the debug log level.
   */
  def logDebug(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logDebug(message))

  /**
   * Logs the specified message at the error log level.
   */
  def logError(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logError(message))

  /**
   * Logs the specified cause as an error.
   */
  def logErrorCause(cause: => Cause[Any])(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logErrorCause(cause))

  /**
   * Logs the specified message at the fatal log level.
   */
  def logFatal(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logFatal(message))

  /**
   * Logs the specified message at the informational log level.
   */
  def logInfo(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logInfo(message))

  /**
   * Sets the log level for streams composed after this.
   */
  def logLevel(level: LogLevel)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.managed(ZManaged.logLevel(level))

  /**
   * Adjusts the label for the logging span for streams composed after this.
   */
  def logSpan(label: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.managed(ZManaged.logSpan(label))

  /**
   * Logs the specified message at the trace log level.
   */
  def logTrace(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logTrace(message))

  /**
   * Logs the specified message at the warning log level.
   */
  def logWarning(message: => String)(implicit trace: ZTraceElement): ZStream[Any, Nothing, Unit] =
    ZStream.fromZIO(ZIO.logWarning(message))

  /**
   * Creates a single-valued stream from a managed resource
   */
  def managed[R, E, A](managed: => ZManaged[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    new ZStream(ZChannel.managedOut(managed.map(Chunk.single)))

  /**
   * Merges a variable list of streams in a non-deterministic fashion. Up to `n`
   * streams may be consumed in parallel and up to `outputBuffer` chunks may be
   * buffered by this operator.
   */
  def mergeAll[R, E, O](n: => Int, outputBuffer: => Int = 16)(
    streams: ZStream[R, E, O]*
  )(implicit trace: ZTraceElement): ZStream[R, E, O] =
    fromIterable(streams).flattenPar(n, outputBuffer)

  /**
   * Like [[mergeAll]], but runs all streams concurrently.
   */
  def mergeAllUnbounded[R, E, O](outputBuffer: => Int = 16)(
    streams: ZStream[R, E, O]*
  )(implicit trace: ZTraceElement): ZStream[R, E, O] = mergeAll(Int.MaxValue, outputBuffer)(streams: _*)

  /**
   * The stream that never produces any value or fails with any error.
   */
  def never(implicit trace: ZTraceElement): ZStream[Any, Nothing, Nothing] =
    ZStream.fromZIO(ZIO.never)

  /**
   * Like [[unfold]], but allows the emission of values to end one step further
   * than the unfolding of the state. This is useful for embedding paginated
   * APIs, hence the name.
   */
  def paginate[R, E, A, S](s: => S)(f: S => (A, Option[S]))(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
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
  )(f: S => (Chunk[A], Option[S]))(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] = {
    def loop(s: S): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Any] =
      f(s) match {
        case (as, Some(s)) => ZChannel.write(as) *> loop(s)
        case (as, None)    => ZChannel.write(as) *> ZChannel.unit
      }

    new ZStream(loop(s))
  }

  /**
   * Like [[unfoldChunkM]], but allows the emission of values to end one step
   * further than the unfolding of the state. This is useful for embedding
   * paginated APIs, hence the name.
   */
  @deprecated("use paginateChunkZIO", "2.0.0")
  def paginateChunkM[R, E, A, S](s: => S)(f: S => ZIO[R, E, (Chunk[A], Option[S])])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    paginateChunkZIO(s)(f)

  /**
   * Like [[unfoldChunkZIO]], but allows the emission of values to end one step
   * further than the unfolding of the state. This is useful for embedding
   * paginated APIs, hence the name.
   */
  def paginateChunkZIO[R, E, A, S](
    s: => S
  )(f: S => ZIO[R, E, (Chunk[A], Option[S])])(implicit trace: ZTraceElement): ZStream[R, E, A] = {
    def loop(s: S): ZChannel[R, Any, Any, Any, E, Chunk[A], Any] =
      ZChannel.unwrap {
        f(s).map {
          case (as, Some(s)) => ZChannel.write(as) *> loop(s)
          case (as, None)    => ZChannel.write(as) *> ZChannel.unit
        }
      }

    new ZStream(ZChannel.suspend(loop(s)))
  }

  /**
   * Like [[unfoldM]], but allows the emission of values to end one step further
   * than the unfolding of the state. This is useful for embedding paginated
   * APIs, hence the name.
   */
  @deprecated("use paginateZIO", "2.0.0")
  def paginateM[R, E, A, S](s: => S)(f: S => ZIO[R, E, (A, Option[S])])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    paginateZIO(s)(f)

  def provideLayer[RIn, E, ROut, RIn2, ROut2](layer: ZLayer[RIn, E, ROut])(
    stream: => ZStream[ROut with RIn2, E, ROut2]
  )(implicit
    ev: EnvironmentTag[RIn2],
    tag: EnvironmentTag[ROut],
    trace: ZTraceElement
  ): ZStream[RIn with RIn2, E, ROut2] =
    ZStream.suspend(stream.provideSomeLayer[RIn with RIn2](ZLayer.environment[RIn2] ++ layer))

  /**
   * Like [[unfoldZIO]], but allows the emission of values to end one step
   * further than the unfolding of the state. This is useful for embedding
   * paginated APIs, hence the name.
   */
  def paginateZIO[R, E, A, S](s: => S)(f: S => ZIO[R, E, (A, Option[S])])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    paginateChunkZIO(s)(f(_).map { case (a, s) => Chunk.single(a) -> s })

  /**
   * Constructs a stream from a range of integers (lower bound included, upper
   * bound not included)
   */
  def range(min: => Int, max: => Int, chunkSize: => Int = DefaultChunkSize)(implicit
    trace: ZTraceElement
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
  def repeat[A](a: => A)(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
    new ZStream(ZChannel.succeed(a).flatMap(a => ZChannel.write(Chunk.single(a)).repeated))

  /**
   * Creates a stream from an effect producing a value of type `A` which repeats
   * forever.
   */
  @deprecated("use repeatZIO", "2.0.0")
  def repeatEffect[R, E, A](fa: => ZIO[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    repeatZIO(fa)

  /**
   * Creates a stream from an effect producing values of type `A` until it fails
   * with None.
   */
  @deprecated("use repeatZIOOption", "2.0.0")
  def repeatEffectOption[R, E, A](fa: => ZIO[R, Option[E], A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    repeatZIOOption(fa)

  /**
   * Creates a stream from an effect producing chunks of `A` values which
   * repeats forever.
   */
  @deprecated("use repeatZIOChunk", "2.0.0")
  def repeatEffectChunk[R, E, A](fa: => ZIO[R, E, Chunk[A]])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    repeatZIOChunk(fa)

  /**
   * Creates a stream from an effect producing chunks of `A` values until it
   * fails with None.
   */
  @deprecated("use repeatZIOChunkOption", "2.0.0")
  def repeatEffectChunkOption[R, E, A](fa: => ZIO[R, Option[E], Chunk[A]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    repeatZIOChunkOption(fa)

  /**
   * Creates a stream from an effect producing a value of type `A`, which is
   * repeated using the specified schedule.
   */
  @deprecated("use repeatZIOWith", "2.0.0")
  def repeatEffectWith[R, E, A](effect: => ZIO[R, E, A], schedule: => Schedule[R, A, Any])(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, E, A] =
    repeatZIOWithSchedule(effect, schedule)

  /**
   * Repeats the value using the provided schedule.
   */
  @deprecated("use repeatWithSchedule", "2.0.0")
  def repeatWith[R, A](a: => A, schedule: Schedule[R, A, _])(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, Nothing, A] =
    repeatWithSchedule(a, schedule)

  /**
   * Repeats the value using the provided schedule.
   */
  def repeatWithSchedule[R, A](a: => A, schedule: => Schedule[R, A, _])(implicit
    trace: ZTraceElement
  ): ZStream[R with Clock, Nothing, A] =
    repeatZIOWithSchedule(UIO.succeedNow(a), schedule)

  /**
   * Creates a stream from an effect producing a value of type `A` which repeats
   * forever.
   */
  def repeatZIO[R, E, A](fa: => ZIO[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    repeatZIOOption(fa.mapError(Some(_)))

  /**
   * Creates a stream from an effect producing chunks of `A` values which
   * repeats forever.
   */
  def repeatZIOChunk[R, E, A](fa: => ZIO[R, E, Chunk[A]])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    repeatZIOChunkOption(fa.mapError(Some(_)))

  /**
   * Creates a stream from an effect producing chunks of `A` values until it
   * fails with None.
   */
  def repeatZIOChunkOption[R, E, A](
    fa: => ZIO[R, Option[E], Chunk[A]]
  )(implicit trace: ZTraceElement): ZStream[R, E, A] =
    unfoldChunkZIO(fa)(fa =>
      fa.map(chunk => Some((chunk, fa))).catchAll {
        case None    => ZIO.none
        case Some(e) => ZIO.fail(e)
      }
    )

  /**
   * Creates a stream from an effect producing values of type `A` until it fails
   * with None.
   */
  def repeatZIOOption[R, E, A](fa: => ZIO[R, Option[E], A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    repeatZIOChunkOption(fa.map(Chunk.single(_)))

  /**
   * Creates a stream from an effect producing a value of type `A`, which is
   * repeated using the specified schedule.
   */
  def repeatZIOWithSchedule[R, E, A](
    effect: => ZIO[R, E, A],
    schedule: => Schedule[R, A, Any]
  )(implicit trace: ZTraceElement): ZStream[R with Clock, E, A] =
    ZStream((effect, schedule)).flatMap { case (effect, schedule) =>
      ZStream.fromZIO(effect zip schedule.driver).flatMap { case (a, driver) =>
        ZStream.succeed(a) ++
          ZStream.unfoldZIO(a)(driver.next(_).foldZIO(ZIO.succeed(_), _ => effect.map(nextA => Some(nextA -> nextA))))
      }
    }

  /**
   * Accesses the specified service in the environment of the effect.
   */
  def service[A: Tag](implicit trace: ZTraceElement): ZStream[A, Nothing, A] =
    ZStream.serviceWith(identity)

  /**
   * Accesses the service corresponding to the specified key in the environment.
   */
  def serviceAt[Service]: ZStream.ServiceAtPartiallyApplied[Service] =
    new ZStream.ServiceAtPartiallyApplied[Service]

  /**
   * Accesses the specified services in the environment of the effect.
   */
  @deprecated("use service", "2.0.0")
  def services[A: Tag, B: Tag](implicit
    trace: ZTraceElement
  ): ZStream[A with B, Nothing, (A, B)] =
    ZStream.environmentWith(r => (r.get[A], r.get[B]))

  /**
   * Accesses the specified services in the environment of the effect.
   */
  @deprecated("use service", "2.0.0")
  def services[A: Tag, B: Tag, C: Tag](implicit
    trace: ZTraceElement
  ): ZStream[A with B with C, Nothing, (A, B, C)] =
    ZStream.environmentWith(r => (r.get[A], r.get[B], r.get[C]))

  /**
   * Accesses the specified services in the environment of the effect.
   */
  @deprecated("use service", "2.0.0")
  def services[
    A: Tag,
    B: Tag,
    C: Tag,
    D: Tag
  ](implicit
    trace: ZTraceElement
  ): ZStream[A with B with C with D, Nothing, (A, B, C, D)] =
    ZStream.environmentWith(r => (r.get[A], r.get[B], r.get[C], r.get[D]))

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
  def succeed[A](a: => A)(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
    fromChunk(Chunk.single(a))

  /**
   * Returns a lazily constructed stream.
   */
  def suspend[R, E, A](stream: => ZStream[R, E, A]): ZStream[R, E, A] =
    new ZStream(ZChannel.suspend(stream.channel))

  /**
   * A stream that emits Unit values spaced by the specified duration.
   */
  def tick(interval: => Duration)(implicit trace: ZTraceElement): ZStream[Clock, Nothing, Unit] =
    repeatWithSchedule((), Schedule.spaced(interval))

  /**
   * A stream that contains a single `Unit` value.
   */
  val unit: ZStream[Any, Nothing, Unit] =
    succeed(())(ZTraceElement.empty)

  /**
   * Creates a stream by peeling off the "layers" of a value of type `S`
   */
  def unfold[S, A](s: => S)(f: S => Option[(A, S)])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
    unfoldChunk(s)(f(_).map { case (a, s) => Chunk.single(a) -> s })

  /**
   * Creates a stream by peeling off the "layers" of a value of type `S`.
   */
  def unfoldChunk[S, A](
    s: => S
  )(f: S => Option[(Chunk[A], S)])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] = {
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
  @deprecated("use unfoldChunkZIO", "2.0.0")
  def unfoldChunkM[R, E, A, S](s: => S)(f: S => ZIO[R, E, Option[(Chunk[A], S)]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    unfoldChunkZIO(s)(f)

  /**
   * Creates a stream by effectfully peeling off the "layers" of a value of type
   * `S`
   */
  def unfoldChunkZIO[R, E, A, S](
    s: => S
  )(f: S => ZIO[R, E, Option[(Chunk[A], S)]])(implicit trace: ZTraceElement): ZStream[R, E, A] = {
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
  @deprecated("use unfoldZIO", "2.0.0")
  def unfoldM[R, E, A, S](s: => S)(f: S => ZIO[R, E, Option[(A, S)]])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    unfoldZIO(s)(f)

  /**
   * Creates a stream by effectfully peeling off the "layers" of a value of type
   * `S`
   */
  def unfoldZIO[R, E, A, S](s: => S)(f: S => ZIO[R, E, Option[(A, S)]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, A] =
    unfoldChunkZIO(s)(f(_).map(_.map { case (a, s) => Chunk.single(a) -> s }))

  /**
   * Creates a stream produced from an effect
   */
  def unwrap[R, E, A](fa: => ZIO[R, E, ZStream[R, E, A]])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    fromZIO(fa).flatten

  /**
   * Creates a stream produced from a [[ZManaged]]
   */
  def unwrapManaged[R, E, A](fa: => ZManaged[R, E, ZStream[R, E, A]])(implicit trace: ZTraceElement): ZStream[R, E, A] =
    managed(fa).flatten

  /**
   * Returns the specified stream if the given condition is satisfied, otherwise
   * returns an empty stream.
   */
  def when[R, E, O](b: => Boolean)(zStream: => ZStream[R, E, O])(implicit trace: ZTraceElement): ZStream[R, E, O] =
    whenZIO(ZIO.succeedNow(b))(zStream)

  /**
   * Returns the resulting stream when the given `PartialFunction` is defined
   * for the given value, otherwise returns an empty stream.
   */
  def whenCase[R, E, A, O](a: => A)(pf: PartialFunction[A, ZStream[R, E, O]])(implicit
    trace: ZTraceElement
  ): ZStream[R, E, O] =
    whenCaseZIO(ZIO.succeedNow(a))(pf)

  /**
   * Returns the resulting stream when the given `PartialFunction` is defined
   * for the given effectful value, otherwise returns an empty stream.
   */
  @deprecated("use whenCaseZIO", "2.0.0")
  def whenCaseM[R, E, A](a: => ZIO[R, E, A]): WhenCaseZIO[R, E, A] =
    whenCaseZIO(a)

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
  @deprecated("use whenZIO", "2.0.0")
  def whenM[R, E](b: => ZIO[R, E, Boolean]): WhenZIO[R, E] =
    whenZIO(b)

  /**
   * Returns the specified stream if the given effectful condition is satisfied,
   * otherwise returns an empty stream.
   */
  def whenZIO[R, E](b: => ZIO[R, E, Boolean]) =
    new WhenZIO(() => b)

  /**
   * Zips the specified streams together with the specified function.
   */
  @deprecated("use zip", "2.0.0")
  def zipN[R, E, A, B, C](zStream1: => ZStream[R, E, A], zStream2: => ZStream[R, E, B])(
    f: (A, B) => C
  )(implicit trace: ZTraceElement): ZStream[R, E, C] =
    ZStream.suspend(zStream1.zipWith(zStream2)(f))

  /**
   * Zips with specified streams together with the specified function.
   */
  @deprecated("use zip", "2.0.0")
  def zipN[R, E, A, B, C, D](
    zStream1: => ZStream[R, E, A],
    zStream2: => ZStream[R, E, B],
    zStream3: => ZStream[R, E, C]
  )(
    f: (A, B, C) => D
  )(implicit trace: ZTraceElement): ZStream[R, E, D] =
    ZStream.suspend((zStream1 <&> zStream2 <&> zStream3).map(f.tupled))

  /**
   * Returns an effect that executes the specified effects in parallel,
   * combining their results with the specified `f` function. If any effect
   * fails, then the other effects will be interrupted.
   */
  @deprecated("use zip", "2.0.0")
  def zipN[R, E, A, B, C, D, F](
    zStream1: ZStream[R, E, A],
    zStream2: ZStream[R, E, B],
    zStream3: ZStream[R, E, C],
    zStream4: ZStream[R, E, D]
  )(f: (A, B, C, D) => F)(implicit trace: ZTraceElement): ZStream[R, E, F] =
    ZStream.suspend((zStream1 <&> zStream2 <&> zStream3 <&> zStream4).map(f.tupled))

  final class EnvironmentWithPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: ZEnvironment[R] => A)(implicit trace: ZTraceElement): ZStream[R, Nothing, A] =
      ZStream.environment[R].map(f)
  }

  final class EnvironmentWithZIOPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, A](f: ZEnvironment[R] => ZIO[R1, E, A])(implicit
      trace: ZTraceElement
    ): ZStream[R with R1, E, A] =
      ZStream.environment[R].mapZIO(f)
  }

  final class EnvironmentWithStreamPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, A](f: ZEnvironment[R] => ZStream[R1, E, A])(implicit
      trace: ZTraceElement
    ): ZStream[R with R1, E, A] =
      ZStream.environment[R].flatMap(f)
  }

  final class ServiceAtPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Key](
      key: => Key
    )(implicit
      tag: EnvironmentTag[Map[Key, Service]],
      trace: ZTraceElement
    ): ZStream[Map[Key, Service], Nothing, Option[Service]] =
      ZStream.environmentWith(_.getAt(key))
  }

  final class ServiceWithPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: Service => A)(implicit
      tag: Tag[Service],
      trace: ZTraceElement
    ): ZStream[Service, Nothing, A] =
      ZStream.fromZIO(ZIO.serviceWith[Service](f))
  }

  final class ServiceWithZIOPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, A](f: Service => ZIO[R, E, A])(implicit
      tag: Tag[Service],
      trace: ZTraceElement
    ): ZStream[R with Service, E, A] =
      ZStream.fromZIO(ZIO.serviceWithZIO[Service](f))
  }

  final class ServiceWithStreamPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, A](f: Service => ZStream[R, E, A])(implicit
      tag: Tag[Service],
      trace: ZTraceElement
    ): ZStream[R with Service, E, A] =
      ZStream.service[Service].flatMap(f)
  }

  /**
   * Representation of a grouped stream. This allows to filter which groups will
   * be processed. Once this is applied all groups will be processed in parallel
   * and the results will be merged in arbitrary order.
   */
  sealed trait GroupBy[-R, +E, +K, +V] { self =>

    type A

    protected def stream: ZStream[R, E, A]
    protected def key: A => ZIO[R, E, (K, V)]
    protected def buffer: Int

    def grouped(implicit trace: ZTraceElement): ZStream[R, E, (K, Dequeue[Exit[Option[E], V]])] =
      ZStream.unwrapManaged {
        for {
          decider <- Promise.make[Nothing, (K, V) => UIO[UniqueKey => Boolean]].toManaged
          out <- Queue
                   .bounded[Exit[Option[E], (K, Dequeue[Exit[Option[E], V]])]](buffer)
                   .toManagedWith(_.shutdown)
          ref <- Ref.make[Map[K, UniqueKey]](Map()).toManaged
          add <- stream
                   .mapZIO(key)
                   .distributedWithDynamic(
                     buffer,
                     (kv: (K, V)) => decider.await.flatMap(_.tupled(kv)),
                     out.offer
                   )
          _ <- decider.succeed { case (k, _) =>
                 ref.get.map(_.get(k)).flatMap {
                   case Some(idx) => ZIO.succeedNow(_ == idx)
                   case None =>
                     add.flatMap { case (idx, q) =>
                       (ref.update(_ + (k -> idx)) *>
                         out.offer(Exit.succeed(k -> q.map(_.map(_._2))))).as(_ == idx)
                     }
                 }
               }.toManaged
        } yield ZStream.fromQueueWithShutdown(out).flattenExitOption
      }

    /**
     * Only consider the first n groups found in the stream.
     */
    def first(n: => Int): GroupBy[R, E, K, V] =
      new GroupBy[R, E, K, V] {
        type A = self.A
        def stream: ZStream[R, E, A]    = self.stream
        def key: A => ZIO[R, E, (K, V)] = self.key
        def buffer: Int                 = self.buffer
        override def grouped(implicit trace: ZTraceElement): ZStream[R, E, (K, Dequeue[Exit[Option[E], V]])] =
          self.grouped.zipWithIndex.filterZIO { case elem @ ((_, q), i) =>
            if (i < n) ZIO.succeedNow(elem).as(true)
            else q.shutdown.as(false)
          }.map(_._1)
      }

    /**
     * Filter the groups to be processed.
     */
    def filter(f: K => Boolean): GroupBy[R, E, K, V] =
      new GroupBy[R, E, K, V] {
        type A = self.A
        def stream: ZStream[R, E, A]    = self.stream
        def key: A => ZIO[R, E, (K, V)] = self.key
        def buffer: Int                 = self.buffer
        override def grouped(implicit trace: ZTraceElement): ZStream[R, E, (K, Dequeue[Exit[Option[E], V]])] =
          self.grouped.filterZIO { case elem @ (k, q) =>
            if (f(k)) ZIO.succeedNow(elem).as(true)
            else q.shutdown.as(false)
          }
      }

    /**
     * Run the function across all groups, collecting the results in an
     * arbitrary order.
     */
    def apply[R1 <: R, E1 >: E, A](f: (K, ZStream[Any, E, V]) => ZStream[R1, E1, A])(implicit
      trace: ZTraceElement
    ): ZStream[R1, E1, A] =
      grouped.flatMapPar[R1, E1, A](Int.MaxValue, buffer) { case (k, q) =>
        f(k, ZStream.fromQueueWithShutdown(q).flattenExitOption)
      }
  }

  final class ProvideSomeLayer[R0, -R, +E, +A](private val self: ZStream[R, E, A]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: => ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: ZTraceElement
    ): ZStream[R0, E1, A] =
      self.asInstanceOf[ZStream[R0 with R1, E, A]].provideLayer(ZLayer.environment[R0] ++ layer)
  }

  final class UpdateService[-R, +E, +A, M](private val self: ZStream[R, E, A]) extends AnyVal {
    def apply[R1 <: R with M](
      f: M => M
    )(implicit tag: Tag[M], trace: ZTraceElement): ZStream[R1, E, A] =
      self.provideSomeEnvironment(_.update(f))
  }

  final class UpdateServiceAt[-R, +E, +A, Service](private val self: ZStream[R, E, A]) extends AnyVal {
    def apply[R1 <: R with Map[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit tag: Tag[Map[Key, Service]], trace: ZTraceElement): ZStream[R1, E, A] =
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
    def make(input: => Input)(implicit trace: ZTraceElement): Out
  }

  object ZStreamConstructor extends ZStreamConstructorPlatformSpecific {

    /**
     * Constructs a `ZStream[RB, EB, B]` from a `ZHub[RA, RB, EA, EB, A,
     * Chunk[B]]`.
     */
    implicit def ChunkHubConstructor[RA, RB, EA, EB, A, B]
      : WithOut[ZHub[RA, RB, EA, EB, A, Chunk[B]], ZStream[RB, EB, B]] =
      new ZStreamConstructor[ZHub[RA, RB, EA, EB, A, Chunk[B]]] {
        type Out = ZStream[RB, EB, B]
        def make(input: => ZHub[RA, RB, EA, EB, A, Chunk[B]])(implicit trace: ZTraceElement): ZStream[RB, EB, B] =
          ZStream.fromChunkHub(input)
      }

    /**
     * Constructs a `ZStream[RB, EB, B]` from a `ZQueue[RA, RB, EA, EB, A,
     * Chunk[B]]`.
     */
    implicit def ChunkQueueConstructor[RA, RB, EA, EB, A, B]
      : WithOut[ZQueue[RA, RB, EA, EB, A, Chunk[B]], ZStream[RB, EB, B]] =
      new ZStreamConstructor[ZQueue[RA, RB, EA, EB, A, Chunk[B]]] {
        type Out = ZStream[RB, EB, B]
        def make(input: => ZQueue[RA, RB, EA, EB, A, Chunk[B]])(implicit trace: ZTraceElement): ZStream[RB, EB, B] =
          ZStream.fromChunkQueue(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from an `Iterable[Chunk[A]]`.
     */
    implicit def ChunksConstructor[A, Collection[Element] <: Iterable[Element]]
      : WithOut[Collection[Chunk[A]], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Collection[Chunk[A]]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Collection[Chunk[A]])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
          ZStream.fromIterable(input).flatMap(ZStream.fromChunk(_))
      }

    /**
     * Constructs a `ZStream[R, E, A]` from a `ZIO[R, E, Iterable[A]]`.
     */
    implicit def IterableZIOConstructor[R, E, A, Collection[Element] <: Iterable[Element]]
      : WithOut[ZIO[R, E, Collection[A]], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, E, Collection[A]]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, E, Collection[A]])(implicit trace: ZTraceElement): ZStream[R, E, A] =
          ZStream.fromIterableZIO(input)
      }

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from an `Iterator[A]`.
     */
    implicit def IteratorConstructor[A, IteratorLike[Element] <: Iterator[Element]]
      : WithOut[IteratorLike[A], ZStream[Any, Throwable, A]] =
      new ZStreamConstructor[IteratorLike[A]] {
        type Out = ZStream[Any, Throwable, A]
        def make(input: => IteratorLike[A])(implicit trace: ZTraceElement): ZStream[Any, Throwable, A] =
          ZStream.fromIterator(input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZManaged[R, Throwable,
     * Iterator[A]]`.
     */
    implicit def IteratorManagedConstructor[R, E <: Throwable, A, IteratorLike[Element] <: Iterator[Element]]
      : WithOut[ZManaged[R, E, IteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZManaged[R, E, IteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZManaged[R, E, IteratorLike[A]])(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
          ZStream.fromIteratorManaged(input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZIO[R, Throwable,
     * Iterator[A]]`.
     */
    implicit def IteratorZIOConstructor[R, E <: Throwable, A, IteratorLike[Element] <: Iterator[Element]]
      : WithOut[ZIO[R, E, IteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZIO[R, E, IteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZIO[R, E, IteratorLike[A]])(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
          ZStream.fromIteratorZIO(input)
      }

    /**
     * Constructs a `ZStream[Any, Throwable, A]` from a `java.util.Iterator[A]`.
     */
    implicit def JavaIteratorConstructor[A, JaveIteratorLike[Element] <: java.util.Iterator[Element]]
      : WithOut[JaveIteratorLike[A], ZStream[Any, Throwable, A]] =
      new ZStreamConstructor[JaveIteratorLike[A]] {
        type Out = ZStream[Any, Throwable, A]
        def make(input: => JaveIteratorLike[A])(implicit trace: ZTraceElement): ZStream[Any, Throwable, A] =
          ZStream.fromJavaIterator(input)
      }

    /**
     * Constructs a `ZStream[R, Throwable, A]` from a `ZManaged[R, Throwable,
     * java.util.Iterator[A]]`.
     */
    implicit def JavaIteratorManagedConstructor[R, E <: Throwable, A, JaveIteratorLike[Element] <: java.util.Iterator[
      Element
    ]]: WithOut[ZManaged[R, E, JaveIteratorLike[A]], ZStream[R, Throwable, A]] =
      new ZStreamConstructor[ZManaged[R, E, JaveIteratorLike[A]]] {
        type Out = ZStream[R, Throwable, A]
        def make(input: => ZManaged[R, E, JaveIteratorLike[A]])(implicit
          trace: ZTraceElement
        ): ZStream[R, Throwable, A] =
          ZStream.fromJavaIteratorManaged(input)
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
        def make(input: => ZIO[R, E, JaveIteratorLike[A]])(implicit trace: ZTraceElement): ZStream[R, Throwable, A] =
          ZStream.fromJavaIteratorZIO(input)
      }

    /**
     * Constructs a `ZStream[R, Nothing, A]` from a `Schedule[R, Any, A]`.
     */
    implicit def ScheduleConstructor[R, A]: WithOut[Schedule[R, Any, A], ZStream[R with Clock, Nothing, A]] =
      new ZStreamConstructor[Schedule[R, Any, A]] {
        type Out = ZStream[R with Clock, Nothing, A]
        def make(input: => Schedule[R, Any, A])(implicit trace: ZTraceElement): ZStream[R with Clock, Nothing, A] =
          ZStream.fromSchedule(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `TQueue[A]`.
     */
    implicit def TQueueConstructor[A]: WithOut[TQueue[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[TQueue[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => TQueue[A])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
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
        def make(input: => Chunk[A])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
          ZStream.fromChunk(input)
      }

    /**
     * Constructs a `ZStream[RB, EB, B]` from a `ZHub[RA, RB, EA, EB, A, B]`.
     */
    implicit def HubConstructor[RA, RB, EA, EB, A, B]: WithOut[ZHub[RA, RB, EA, EB, A, B], ZStream[RB, EB, B]] =
      new ZStreamConstructor[ZHub[RA, RB, EA, EB, A, B]] {
        type Out = ZStream[RB, EB, B]
        def make(input: => ZHub[RA, RB, EA, EB, A, B])(implicit trace: ZTraceElement): ZStream[RB, EB, B] =
          ZStream.fromHub(input)
      }

    /**
     * Constructs a `ZStream[Any, Nothing, A]` from a `Iterable[A]`.
     */
    implicit def IterableConstructor[A, Collection[Element] <: Iterable[Element]]
      : WithOut[Collection[A], ZStream[Any, Nothing, A]] =
      new ZStreamConstructor[Collection[A]] {
        type Out = ZStream[Any, Nothing, A]
        def make(input: => Collection[A])(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
          ZStream.fromIterable(input)
      }

    /**
     * Constructs a `ZStream[RB, EB, B]` from a `ZQueue[RA, RB, EA, EB, A, B]`.
     */
    implicit def QueueConstructor[RA, RB, EA, EB, A, B]: WithOut[ZQueue[RA, RB, EA, EB, A, B], ZStream[RB, EB, B]] =
      new ZStreamConstructor[ZQueue[RA, RB, EA, EB, A, B]] {
        type Out = ZStream[RB, EB, B]
        def make(input: => ZQueue[RA, RB, EA, EB, A, B])(implicit trace: ZTraceElement): ZStream[RB, EB, B] =
          ZStream.fromQueue(input)
      }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, Option[E], A]`.
     */
    implicit def ZIOOptionConstructor[R, E, A]: WithOut[ZIO[R, Option[E], A], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, Option[E], A]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, Option[E], A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
          ZStream.fromZIOOption(input)
      }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, Option[E], A]`.
     */
    implicit def ZIOOptionNoneConstructor[R, A]: WithOut[ZIO[R, None.type, A], ZStream[R, Nothing, A]] =
      new ZStreamConstructor[ZIO[R, None.type, A]] {
        type Out = ZStream[R, Nothing, A]
        def make(input: => ZIO[R, None.type, A])(implicit trace: ZTraceElement): ZStream[R, Nothing, A] =
          ZStream.fromZIOOption(input)
      }

    /**
     * Construct a `ZStream[R, E, A]` from a `ZIO[R, Option[E], A]`.
     */
    implicit def ZIOOptionSomeConstructor[R, E, A]: WithOut[ZIO[R, Some[E], A], ZStream[R, E, A]] =
      new ZStreamConstructor[ZIO[R, Some[E], A]] {
        type Out = ZStream[R, E, A]
        def make(input: => ZIO[R, Some[E], A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
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
        def make(input: => ZIO[R, E, A])(implicit trace: ZTraceElement): ZStream[R, E, A] =
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
        def make(input: => A)(implicit trace: ZTraceElement): ZStream[Any, Nothing, A] =
          ZStream.succeed(input)
      }
  }

  type Pull[-R, +E, +A] = ZIO[R, Option[E], Chunk[A]]

  private[zio] object Pull {
    def emit[A](a: A)(implicit trace: ZTraceElement): IO[Nothing, Chunk[A]]         = UIO(Chunk.single(a))
    def emit[A](as: Chunk[A])(implicit trace: ZTraceElement): IO[Nothing, Chunk[A]] = UIO(as)
    def fromDequeue[E, A](d: Dequeue[stream.Take[E, A]])(implicit trace: ZTraceElement): IO[Option[E], Chunk[A]] =
      d.take.flatMap(_.done)
    def fail[E](e: E)(implicit trace: ZTraceElement): IO[Option[E], Nothing] = IO.fail(Some(e))
    def failCause[E](c: Cause[E])(implicit trace: ZTraceElement): IO[Option[E], Nothing] =
      IO.failCause(c).mapError(Some(_))
    @deprecated("use failCause", "2.0.0")
    def halt[E](c: Cause[E])(implicit trace: ZTraceElement): IO[Option[E], Nothing] = failCause(c)
    def empty[A](implicit trace: ZTraceElement): IO[Nothing, Chunk[A]]   = UIO(Chunk.empty)
    def end(implicit trace: ZTraceElement): IO[Option[Nothing], Nothing] = IO.fail(None)
  }

  @deprecated("use zio.stream.Take instead", "1.0.0")
  type Take[+E, +A] = Exit[Option[E], Chunk[A]]

  object Take {
    @deprecated("use zio.stream.Take.end instead", "1.0.0")
    val End: Exit[Option[Nothing], Nothing] = Exit.fail(None)
  }

  private[zio] case class BufferedPull[R, E, A](
    upstream: ZIO[R, Option[E], Chunk[A]],
    done: Ref[Boolean],
    cursor: Ref[(Chunk[A], Int)]
  ) {
    def ifNotDone[R1, E1, A1](fa: ZIO[R1, Option[E1], A1])(implicit trace: ZTraceElement): ZIO[R1, Option[E1], A1] =
      done.get.flatMap(
        if (_) Pull.end
        else fa
      )

    def update(implicit trace: ZTraceElement): ZIO[R, Option[E], Unit] =
      ifNotDone {
        upstream.foldZIO(
          {
            case None    => done.set(true) *> Pull.end
            case Some(e) => Pull.fail(e)
          },
          chunk => cursor.set(chunk -> 0)
        )
      }

    def pullElement(implicit trace: ZTraceElement): ZIO[R, Option[E], A] =
      ifNotDone {
        cursor.modify { case (chunk, idx) =>
          if (idx >= chunk.size) (update *> pullElement, (Chunk.empty, 0))
          else (UIO.succeedNow(chunk(idx)), (chunk, idx + 1))
        }.flatten
      }

    def pullChunk(implicit trace: ZTraceElement): ZIO[R, Option[E], Chunk[A]] =
      ifNotDone {
        cursor.modify { case (chunk, idx) =>
          if (idx >= chunk.size) (update *> pullChunk, (Chunk.empty, 0))
          else (UIO.succeedNow(chunk.drop(idx)), (Chunk.empty, 0))
        }.flatten
      }

  }

  private[zio] object BufferedPull {
    def make[R, E, A](
      pull: ZIO[R, Option[E], Chunk[A]]
    )(implicit trace: ZTraceElement): ZIO[R, Nothing, BufferedPull[R, E, A]] =
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
    def offer(a: A)(implicit trace: ZTraceElement): UIO[Unit] =
      Promise.make[Nothing, Unit].flatMap { p =>
        ref.modify {
          case s @ Handoff.State.Full(_, notifyProducer) => (notifyProducer.await *> offer(a), s)
          case Handoff.State.Empty(notifyConsumer)       => (notifyConsumer.succeed(()) *> p.await, Handoff.State.Full(a, p))
        }.flatten
      }

    def take(implicit trace: ZTraceElement): UIO[A] =
      Promise.make[Nothing, Unit].flatMap { p =>
        ref.modify {
          case Handoff.State.Full(a, notifyProducer)   => (notifyProducer.succeed(()).as(a), Handoff.State.Empty(p))
          case s @ Handoff.State.Empty(notifyConsumer) => (notifyConsumer.await *> take, s)
        }.flatten
      }

    def poll(implicit trace: ZTraceElement): UIO[Option[A]] =
      Promise.make[Nothing, Unit].flatMap { p =>
        ref.modify {
          case Handoff.State.Full(a, notifyProducer) => (notifyProducer.succeed(()).as(Some(a)), Handoff.State.Empty(p))
          case s @ Handoff.State.Empty(_)            => (ZIO.succeedNow(None), s)
        }.flatten
      }
  }

  private[zio] object Handoff {
    def make[A](implicit trace: ZTraceElement): UIO[Handoff[A]] =
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
    def apply[R1 <: R, E1 >: E, O](zStream: ZStream[R1, E1, O])(implicit trace: ZTraceElement): ZStream[R1, E1, O] =
      fromZIO(b()).flatMap(if (_) zStream else ZStream.empty)
  }

  final class WhenCaseZIO[R, E, A](private val a: () => ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, O](pf: PartialFunction[A, ZStream[R1, E1, O]])(implicit
      trace: ZTraceElement
    ): ZStream[R1, E1, O] =
      fromZIO(a()).flatMap(pf.applyOrElse(_, (_: A) => ZStream.empty))
  }

  sealed trait TerminationStrategy
  object TerminationStrategy {
    case object Left   extends TerminationStrategy
    case object Right  extends TerminationStrategy
    case object Both   extends TerminationStrategy
    case object Either extends TerminationStrategy
  }

  implicit final class RefineToOrDieOps[R, E <: Throwable, A](private val self: ZStream[R, E, A]) extends AnyVal {

    /**
     * Keeps some of the errors, and terminates the fiber with the rest.
     */
    def refineToOrDie[E1 <: E: ClassTag](implicit ev: CanFail[E], trace: ZTraceElement): ZStream[R, E1, A] =
      self.refineOrDie { case e: E1 => e }
  }

  implicit final class SyntaxOps[-R, +E, O](self: ZStream[R, E, O]) {
    /*
     * Collect elements of the given type flowing through the stream, and filters out others.
     */
    def collectType[O1 <: O](implicit tag: ClassTag[O1], trace: ZTraceElement): ZStream[R, E, O1] =
      self.collect { case o if tag.runtimeClass.isInstance(o) => o.asInstanceOf[O1] }
  }

  private[zio] class Rechunker[A](n: Int) {
    private var builder: ChunkBuilder[A] = ChunkBuilder.make(n)
    private var pos: Int                 = 0

    def write(elem: A): Chunk[A] = {
      builder += elem
      pos += 1
      if (pos == n) {
        val result = builder.result()
        builder = ChunkBuilder.make(n)
        pos = 0
        result
      } else {
        null
      }
    }

    def isEmpty: Boolean =
      pos == 0

    def emitIfNotEmpty()(implicit trace: ZTraceElement): ZChannel[Any, Any, Any, Any, Nothing, Chunk[A], Unit] =
      if (pos != 0) {
        ZChannel.write(builder.result())
      } else {
        ZChannel.unit
      }
  }

  private[zio] sealed trait SinkEndReason[+C]
  private[zio] object SinkEndReason {
    case object SinkEnd             extends SinkEndReason[Nothing]
    case object ScheduleTimeout     extends SinkEndReason[Nothing]
    case class ScheduleEnd[C](c: C) extends SinkEndReason[C]
    case object UpstreamEnd         extends SinkEndReason[Nothing]
  }

  private[zio] sealed trait HandoffSignal[C, E, A]
  private[zio] object HandoffSignal {
    case class Emit[C, E, A](els: Chunk[A])           extends HandoffSignal[C, E, A]
    case class Halt[C, E, A](error: Cause[E])         extends HandoffSignal[C, E, A]
    case class End[C, E, A](reason: SinkEndReason[C]) extends HandoffSignal[C, E, A]
  }

  private[zio] sealed trait DebounceState[+E, +A]
  private[zio] object DebounceState {
    case object NotStarted                                               extends DebounceState[Nothing, Nothing]
    case class Previous[A](fiber: Fiber[Nothing, Chunk[A]])              extends DebounceState[Nothing, A]
    case class Current[E, A](fiber: Fiber[E, HandoffSignal[Unit, E, A]]) extends DebounceState[E, A]
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
    def chunk(as: Chunk[A])(implicit trace: ZTraceElement): B =
      apply(ZIO.succeedNow(as))

    /**
     * Terminates with a cause that dies with the specified `Throwable`.
     */
    def die(t: Throwable)(implicit trace: ZTraceElement): B =
      apply(ZIO.die(t))

    /**
     * Terminates with a cause that dies with a `Throwable` with the specified
     * message.
     */
    def dieMessage(message: String)(implicit trace: ZTraceElement): B =
      apply(ZIO.dieMessage(message))

    /**
     * Either emits the specified value if this `Exit` is a `Success` or else
     * terminates with the specified cause if this `Exit` is a `Failure`.
     */
    def done(exit: Exit[E, A])(implicit trace: ZTraceElement): B =
      apply(ZIO.done(exit.mapBoth(e => Some(e), a => Chunk(a))))

    /**
     * Terminates with an end of stream signal.
     */
    def end(implicit trace: ZTraceElement): B =
      apply(ZIO.fail(None))

    /**
     * Terminates with the specified error.
     */
    def fail(e: E)(implicit trace: ZTraceElement): B =
      apply(ZIO.fail(Some(e)))

    /**
     * Either emits the success value of this effect or terminates the stream
     * with the failure value of this effect.
     */
    def fromEffect(zio: ZIO[R, E, A])(implicit trace: ZTraceElement): B =
      apply(zio.mapBoth(e => Some(e), a => Chunk(a)))

    /**
     * Either emits the success value of this effect or terminates the stream
     * with the failure value of this effect.
     */
    def fromEffectChunk(zio: ZIO[R, E, Chunk[A]])(implicit trace: ZTraceElement): B =
      apply(zio.mapError(e => Some(e)))

    /**
     * Terminates the stream with the specified cause.
     */
    def halt(cause: Cause[E])(implicit trace: ZTraceElement): B =
      apply(ZIO.failCause(cause.map(e => Some(e))))

    /**
     * Emits a chunk containing the specified value.
     */
    def single(a: A)(implicit trace: ZTraceElement): B =
      apply(ZIO.succeedNow(Chunk(a)))
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
    final def zipAllSortedByKey[R1 <: R, E1 >: E, B](
      that: => ZStream[R1, E1, (K, B)]
    )(defaultLeft: => A, defaultRight: => B)(implicit
      ord: Ordering[K],
      trace: ZTraceElement
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
    final def zipAllSortedByKeyLeft[R1 <: R, E1 >: E, B](
      that: => ZStream[R1, E1, (K, B)]
    )(default: => A)(implicit ord: Ordering[K], trace: ZTraceElement): ZStream[R1, E1, (K, A)] =
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
    final def zipAllSortedByKeyRight[R1 <: R, E1 >: E, B](
      that: => ZStream[R1, E1, (K, B)]
    )(default: => B)(implicit ord: Ordering[K], trace: ZTraceElement): ZStream[R1, E1, (K, B)] =
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
    final def zipAllSortedByKeyWith[R1 <: R, E1 >: E, B, C](
      that: => ZStream[R1, E1, (K, B)]
    )(left: A => C, right: B => C)(
      both: (A, B) => C
    )(implicit ord: Ordering[K], trace: ZTraceElement): ZStream[R1, E1, (K, C)] = {
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
                e => ZIO.succeedNow(Exit.fail(Some(e))),
                {
                  case (Some(leftChunk), Some(rightChunk)) =>
                    if (leftChunk.isEmpty && rightChunk.isEmpty) pull(PullBoth, pullLeft, pullRight)
                    else if (leftChunk.isEmpty) pull(PullLeft(rightChunk), pullLeft, pullRight)
                    else if (rightChunk.isEmpty) pull(PullRight(leftChunk), pullLeft, pullRight)
                    else ZIO.succeedNow(Exit.succeed(mergeSortedByKeyChunk(leftChunk, rightChunk)))
                  case (Some(leftChunk), None) =>
                    if (leftChunk.isEmpty) pull(DrainLeft, pullLeft, pullRight)
                    else ZIO.succeedNow(Exit.succeed(leftChunk.map { case (k, a) => (k, left(a)) } -> DrainLeft))
                  case (None, Some(rightChunk)) =>
                    if (rightChunk.isEmpty) pull(DrainRight, pullLeft, pullRight)
                    else
                      ZIO.succeedNow(Exit.succeed(rightChunk.map { case (k, b) => (k, right(b)) } -> DrainRight))
                  case (None, None) => ZIO.succeedNow(Exit.fail(None))
                }
              )
          case PullLeft(rightChunk) =>
            pullLeft.foldZIO(
              {
                case Some(e) => ZIO.succeedNow(Exit.fail(Some(e)))
                case None =>
                  ZIO.succeedNow(Exit.succeed(rightChunk.map { case (k, b) => (k, right(b)) } -> DrainRight))
              },
              leftChunk =>
                if (leftChunk.isEmpty) pull(PullLeft(rightChunk), pullLeft, pullRight)
                else ZIO.succeedNow(Exit.succeed(mergeSortedByKeyChunk(leftChunk, rightChunk)))
            )
          case PullRight(leftChunk) =>
            pullRight.foldZIO(
              {
                case Some(e) => ZIO.succeedNow(Exit.fail(Some(e)))
                case None    => ZIO.succeedNow(Exit.succeed(leftChunk.map { case (k, a) => (k, left(a)) } -> DrainLeft))
              },
              rightChunk =>
                if (rightChunk.isEmpty) pull(PullRight(leftChunk), pullLeft, pullRight)
                else ZIO.succeedNow(Exit.succeed(mergeSortedByKeyChunk(leftChunk, rightChunk)))
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
}
