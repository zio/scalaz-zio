package zio.stream

import zio._
import zio.stream.internal.CharacterSet._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.nio.charset.{Charset, StandardCharsets}
import java.util.concurrent.atomic.AtomicReference

class ZSink[-R, +E, -In, +L, +Z](val channel: ZChannel[R, Nothing, Chunk[In], Any, E, Chunk[L], Z]) extends AnyVal {
  self =>

  /**
   * Operator alias for [[race]].
   */
  final def |[R1 <: R, E1 >: E, In1 <: In, L1 >: L, Z1 >: Z](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    race(that)

  /**
   * Operator alias for [[zip]].
   */
  final def <*>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit
    zippable: Zippable[Z, Z1],
    ev: L <:< In1,
    trace: ZTraceElement
  ): ZSink[R1, E1, In1, L1, zippable.Out] =
    zip(that)

  /**
   * Operator alias for [[zipPar]].
   */
  final def <&>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit zippable: Zippable[Z, Z1], trace: ZTraceElement): ZSink[R1, E1, In1, L1, zippable.Out] =
    zipPar(that)

  /**
   * Operator alias for [[zipRight]].
   */
  final def *>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    zipRight(that)

  /**
   * Operator alias for [[zipParRight]].
   */
  final def &>[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    zipParRight(that)

  /**
   * Operator alias for [[zipLeft]].
   */
  final def <*[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z] =
    zipLeft(that)

  /**
   * Operator alias for [[zipParLeft]].
   */
  final def <&[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z] =
    zipParLeft(that)

  /**
   * Replaces this sink's result with the provided value.
   */
  def as[Z2](z: => Z2)(implicit trace: ZTraceElement): ZSink[R, E, In, L, Z2] =
    map(_ => z)

  /**
   * Repeatedly runs the sink for as long as its results satisfy the predicate
   * `p`. The sink's results will be accumulated using the stepping function
   * `f`.
   */
  def collectAllWhileWith[S](z: S)(p: Z => Boolean)(f: (S, Z) => S)(implicit
    ev: L <:< In,
    trace: ZTraceElement
  ): ZSink[R, E, In, L, S] =
    new ZSink(
      ZChannel
        .fromZIO(Ref.make(Chunk[In]()).zip(Ref.make(false)))
        .flatMap[R, Nothing, Chunk[In], Any, E, Chunk[L], S] { case (leftoversRef, upstreamDoneRef) =>
          lazy val upstreamMarker: ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
            ZChannel.readWith[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any](
              (in: Chunk[In]) =>
                ZChannel.write(in).zipRight[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any](upstreamMarker),
              ZChannel.fail(_: Nothing),
              (x: Any) => ZChannel.fromZIO(upstreamDoneRef.set(true)).as(x)
            )

          def loop(currentResult: S): ZChannel[R, Nothing, Chunk[In], Any, E, Chunk[L], S] =
            channel.doneCollect
              .foldChannel(
                ZChannel.fail(_),
                { case (leftovers, doneValue) =>
                  if (p(doneValue)) {
                    ZChannel
                      .fromZIO(leftoversRef.set(leftovers.flatten.asInstanceOf[Chunk[In]]))
                      .flatMap[R, Nothing, Chunk[In], Any, E, Chunk[L], S] { _ =>
                        ZChannel.fromZIO(upstreamDoneRef.get).flatMap[R, Nothing, Chunk[In], Any, E, Chunk[L], S] {
                          upstreamDone =>
                            val accumulatedResult = f(currentResult, doneValue)
                            if (upstreamDone) ZChannel.write(leftovers.flatten).as(accumulatedResult)
                            else loop(accumulatedResult)
                        }
                      }
                  } else ZChannel.write(leftovers.flatten).as(currentResult)
                }
              )

          upstreamMarker >>> ZChannel.bufferChunk(leftoversRef) >>> loop(z)
        }
    )

  /**
   * Transforms this sink's input elements.
   */
  def contramap[In1](f: In1 => In)(implicit trace: ZTraceElement): ZSink[R, E, In1, L, Z] =
    contramapChunks(_.map(f))

  /**
   * Transforms this sink's input chunks. `f` must preserve chunking-invariance
   */
  def contramapChunks[In1](
    f: Chunk[In1] => Chunk[In]
  )(implicit trace: ZTraceElement): ZSink[R, E, In1, L, Z] = {
    lazy val loop: ZChannel[R, Nothing, Chunk[In1], Any, Nothing, Chunk[In], Any] =
      ZChannel.readWith[R, Nothing, Chunk[In1], Any, Nothing, Chunk[In], Any](
        chunk => ZChannel.write(f(chunk)).zipRight[R, Nothing, Chunk[In1], Any, Nothing, Chunk[In], Any](loop),
        ZChannel.fail(_),
        ZChannel.succeed(_)
      )
    new ZSink(loop >>> self.channel)
  }

  /**
   * Effectfully transforms this sink's input chunks. `f` must preserve
   * chunking-invariance
   */
  @deprecated("use contramapChunksZIO", "2.0.0")
  def contramapChunksM[R1 <: R, E1 >: E, In1](
    f: Chunk[In1] => ZIO[R1, E1, Chunk[In]]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z] =
    contramapChunksZIO(f)

  /**
   * Effectfully transforms this sink's input chunks. `f` must preserve
   * chunking-invariance
   */
  def contramapChunksZIO[R1 <: R, E1 >: E, In1](
    f: Chunk[In1] => ZIO[R1, E1, Chunk[In]]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z] = {
    lazy val loop: ZChannel[R1, Nothing, Chunk[In1], Any, E1, Chunk[In], Any] =
      ZChannel.readWith[R1, Nothing, Chunk[In1], Any, E1, Chunk[In], Any](
        chunk =>
          ZChannel
            .fromZIO(f(chunk))
            .flatMap(ZChannel.write)
            .zipRight[R1, Nothing, Chunk[In1], Any, E1, Chunk[In], Any](loop),
        ZChannel.fail(_),
        ZChannel.succeed(_)
      )
    new ZSink(loop.pipeToOrFail(self.channel))
  }

  /**
   * Effectfully transforms this sink's input elements.
   */
  @deprecated("use contramapZIO", "2.0.0")
  def contramapM[R1 <: R, E1 >: E, In1](
    f: In1 => ZIO[R1, E1, In]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z] =
    contramapZIO(f)

  /**
   * Effectfully transforms this sink's input elements.
   */
  def contramapZIO[R1 <: R, E1 >: E, In1](
    f: In1 => ZIO[R1, E1, In]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z] =
    contramapChunksZIO(_.mapZIO(f))

  /**
   * Transforms both inputs and result of this sink using the provided
   * functions.
   */
  def dimap[In1, Z1](f: In1 => In, g: Z => Z1)(implicit trace: ZTraceElement): ZSink[R, E, In1, L, Z1] =
    contramap(f).map(g)

  /**
   * Transforms both input chunks and result of this sink using the provided
   * functions.
   */
  def dimapChunks[In1, Z1](f: Chunk[In1] => Chunk[In], g: Z => Z1)(implicit
    trace: ZTraceElement
  ): ZSink[R, E, In1, L, Z1] =
    contramapChunks(f).map(g)

  /**
   * Effectfully transforms both input chunks and result of this sink using the
   * provided functions. `f` and `g` must preserve chunking-invariance
   */
  @deprecated("use dimapChunksZIO", "2.0.0")
  def dimapChunksM[R1 <: R, E1 >: E, In1, Z1](
    f: Chunk[In1] => ZIO[R1, E1, Chunk[In]],
    g: Z => ZIO[R1, E1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z1] =
    dimapChunksZIO(f, g)

  /**
   * Effectfully transforms both input chunks and result of this sink using the
   * provided functions. `f` and `g` must preserve chunking-invariance
   */
  def dimapChunksZIO[R1 <: R, E1 >: E, In1, Z1](
    f: Chunk[In1] => ZIO[R1, E1, Chunk[In]],
    g: Z => ZIO[R1, E1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z1] =
    contramapChunksZIO(f).mapZIO(g)

  /**
   * Effectfully transforms both inputs and result of this sink using the
   * provided functions.
   */
  @deprecated("use dimapZIO", "2.0.0")
  def dimapM[R1 <: R, E1 >: E, In1, Z1](
    f: In1 => ZIO[R1, E1, In],
    g: Z => ZIO[R1, E1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z1] =
    dimapZIO(f, g)

  /**
   * Effectfully transforms both inputs and result of this sink using the
   * provided functions.
   */
  def dimapZIO[R1 <: R, E1 >: E, In1, Z1](
    f: In1 => ZIO[R1, E1, In],
    g: Z => ZIO[R1, E1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z1] =
    contramapZIO(f).mapZIO(g)

  def filterInput[In1 <: In](p: In1 => Boolean)(implicit trace: ZTraceElement): ZSink[R, E, In1, L, Z] =
    contramapChunks(_.filter(p))

  @deprecated("use filterInputZIO", "2.0.0")
  def filterInputM[R1 <: R, E1 >: E, In1 <: In](
    p: In1 => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z] =
    filterInputZIO(p)

  def filterInputZIO[R1 <: R, E1 >: E, In1 <: In](
    p: In1 => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L, Z] =
    contramapChunksZIO(_.filterZIO(p))

  /**
   * Runs this sink until it yields a result, then uses that result to create
   * another sink from the provided function which will continue to run until it
   * yields a result.
   *
   * This function essentially runs sinks in sequence.
   */
  def flatMap[R1 <: R, E1 >: E, In1 <: In, L1 >: L <: In1, Z1](
    f: Z => ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    foldSink(ZSink.fail(_), f)

  @deprecated("use foldSink", "2.0.0")
  def foldM[R1 <: R, E2, In1 <: In, L1 >: L <: In1, Z1](
    failure: E => ZSink[R1, E2, In1, L1, Z1],
    success: Z => ZSink[R1, E2, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E2, In1, L1, Z1] =
    foldSink(failure, success)

  def foldSink[R1 <: R, E2, In1 <: In, L1 >: L <: In1, Z1](
    failure: E => ZSink[R1, E2, In1, L1, Z1],
    success: Z => ZSink[R1, E2, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E2, In1, L1, Z1] =
    new ZSink(
      channel.doneCollect.foldChannel[R1, Nothing, Chunk[In1], Any, E2, Chunk[L1], Z1](
        failure(_).channel,
        { case (leftovers, z) =>
          ZChannel.effectSuspendTotal[R1, Nothing, Chunk[In1], Any, E2, Chunk[L1], Z1] {
            val leftoversRef = new AtomicReference(leftovers.filter(_.nonEmpty))
            val refReader = ZChannel.effectTotal(leftoversRef.getAndSet(Chunk.empty)).flatMap { chunk =>
              // This cast is safe because of the L1 >: L <: In1 bound. It follows that
              // L <: In1 and therefore Chunk[L] can be safely cast to Chunk[In1].
              val widenedChunk = chunk.asInstanceOf[Chunk[Chunk[In1]]]
              ZChannel.writeChunk(widenedChunk)
            }

            val passthrough = ZChannel.identity[Nothing, Chunk[In1], Any]
            val continuationSink =
              (refReader.zipRight[Any, Nothing, Chunk[In1], Any, Nothing, Chunk[In1], Any](passthrough)) >>> success(
                z
              ).channel

            continuationSink.doneCollect.flatMap[R1, Nothing, Chunk[In1], Any, E2, Chunk[L1], Z1] {
              case (newLeftovers, z1) =>
                ZChannel
                  .effectTotal(leftoversRef.get)
                  .flatMap(ZChannel.writeChunk(_))
                  .flatMap[R1, Nothing, Chunk[In1], Any, E2, Chunk[L1], Z1] { _ =>
                    ZChannel.writeChunk(newLeftovers).as(z1)
                  }
            }
          }
        }
      )
    )

  /**
   * Transforms this sink's result.
   */
  def map[Z2](f: Z => Z2)(implicit trace: ZTraceElement): ZSink[R, E, In, L, Z2] = new ZSink(channel.map(f))

  /**
   * Transforms the errors emitted by this sink using `f`.
   */
  def mapError[E2](f: E => E2)(implicit trace: ZTraceElement): ZSink[R, E2, In, L, Z] =
    new ZSink(channel.mapError(f))

  /**
   * Effectfully transforms this sink's result.
   */
  @deprecated("use mapZIO", "2.0.0")
  def mapM[R1 <: R, E1 >: E, Z1](f: Z => ZIO[R1, E1, Z1])(implicit
    trace: ZTraceElement
  ): ZSink[R1, E1, In, L, Z1] =
    mapZIO(f)

  /**
   * Effectfully transforms this sink's result.
   */
  def mapZIO[R1 <: R, E1 >: E, Z1](f: Z => ZIO[R1, E1, Z1])(implicit
    trace: ZTraceElement
  ): ZSink[R1, E1, In, L, Z1] =
    new ZSink(channel.mapZIO(f))

  /**
   * Runs both sinks in parallel on the input, , returning the result or the
   * error from the one that finishes first.
   */
  final def race[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L, Z1 >: Z](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    self.raceBoth(that).map(_.merge)

  /**
   * Runs both sinks in parallel on the input, returning the result or the error
   * from the one that finishes first.
   */
  final def raceBoth[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L, Z2](
    that: ZSink[R1, E1, In1, L1, Z2],
    capacity: Int = 16
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Either[Z, Z2]] =
    self.raceWith(that, capacity)(
      selfDone => ZChannel.MergeDecision.done(ZIO.done(selfDone).map(Left(_))),
      thatDone => ZChannel.MergeDecision.done(ZIO.done(thatDone).map(Right(_)))
    )

  /**
   * Runs both sinks in parallel on the input, using the specified merge
   * function as soon as one result or the other has been computed.
   */
  final def raceWith[R1 <: R, E1 >: E, A0, In1 <: In, L1 >: L, Z1, Z2](
    that: ZSink[R1, E1, In1, L1, Z1],
    capacity: Int = 16
  )(
    leftDone: Exit[E, Z] => ZChannel.MergeDecision[R1, E1, Z1, E1, Z2],
    rightDone: Exit[E1, Z1] => ZChannel.MergeDecision[R1, E, Z, E1, Z2]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z2] = {
    val managed =
      for {
        hub   <- ZHub.bounded[Either[Exit[Nothing, Any], Chunk[In1]]](capacity).toManaged
        c1    <- ZChannel.fromHubManaged(hub)
        c2    <- ZChannel.fromHubManaged(hub)
        reader = ZChannel.toHub[Nothing, Any, Chunk[In1]](hub)
        writer = (c1 >>> self.channel).mergeWith(c2 >>> that.channel)(
                   leftDone,
                   rightDone
                 )
        channel = reader.mergeWith(writer)(
                    _ => ZChannel.MergeDecision.await(ZIO.done(_)),
                    done => ZChannel.MergeDecision.done(ZIO.done(done))
                  )
      } yield new ZSink[R1, E1, In1, L1, Z2](channel)
    ZSink.unwrapManaged(managed)
  }

  /**
   * Returns the sink that executes this one and times its execution.
   */
  final def timed(implicit trace: ZTraceElement): ZSink[R with Clock, E, In, L, (Z, Duration)] =
    summarized(Clock.nanoTime)((start, end) => Duration.fromNanos(end - start))

  def repeat(implicit ev: L <:< In, trace: ZTraceElement): ZSink[R, E, In, L, Chunk[Z]] =
    collectAllWhileWith[Chunk[Z]](Chunk.empty)(_ => true)((s, z) => s :+ z)

  /**
   * Summarize a sink by running an effect when the sink starts and again when
   * it completes
   */
  final def summarized[R1 <: R, E1 >: E, B, C](
    summary: ZIO[R1, E1, B]
  )(f: (B, B) => C)(implicit trace: ZTraceElement) =
    new ZSink[R1, E1, In, L, (Z, C)](
      ZChannel.fromZIO(summary).flatMap[R1, Nothing, Chunk[In], Any, E1, Chunk[L], (Z, C)] { start =>
        self.channel.flatMap[R1, Nothing, Chunk[In], Any, E1, Chunk[L], (Z, C)] { done =>
          ZChannel.fromZIO(summary).map { end =>
            (done, f(start, end))
          }
        }
      }
    )

  def orElse[R1 <: R, In1 <: In, E2 >: E, L1 >: L, Z1 >: Z](
    that: => ZSink[R1, E2, In1, L1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E2, In1, L1, Z1] =
    new ZSink[R1, E2, In1, L1, Z1](self.channel.orElse(that.channel))

  def zip[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit
    zippable: Zippable[Z, Z1],
    ev: L <:< In1,
    trace: ZTraceElement
  ): ZSink[R1, E1, In1, L1, zippable.Out] =
    zipWith[R1, E1, In1, L1, Z1, zippable.Out](that)(zippable.zip(_, _))

  /**
   * Like [[zip]], but keeps only the result from the `that` sink.
   */
  final def zipLeft[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z] =
    zipWith[R1, E1, In1, L1, Z1, Z](that)((z, _) => z)

  /**
   * Runs both sinks in parallel on the input and combines the results in a
   * tuple.
   */
  final def zipPar[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit zippable: Zippable[Z, Z1], trace: ZTraceElement): ZSink[R1, E1, In1, L1, zippable.Out] =
    zipWithPar[R1, E1, In1, L1, Z1, zippable.Out](that)(zippable.zip(_, _))

  /**
   * Like [[zipPar]], but keeps only the result from this sink.
   */
  final def zipParLeft[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z] =
    zipWithPar[R1, E1, In1, L1, Z1, Z](that)((b, _) => b)

  /**
   * Like [[zipPar]], but keeps only the result from the `that` sink.
   */
  final def zipParRight[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    zipWithPar[R1, E1, In1, L1, Z1, Z1](that)((_, c) => c)

  /**
   * Like [[zip]], but keeps only the result from this sink.
   */
  final def zipRight[R1 <: R, In1 <: In, E1 >: E, L1 >: L <: In1, Z1](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z1] =
    zipWith[R1, E1, In1, L1, Z1, Z1](that)((_, z1) => z1)

  /**
   * Feeds inputs to this sink until it yields a result, then switches over to
   * the provided sink until it yields a result, finally combining the two
   * results with `f`.
   */
  final def zipWith[R1 <: R, E1 >: E, In1 <: In, L1 >: L <: In1, Z1, Z2](
    that: ZSink[R1, E1, In1, L1, Z1]
  )(f: (Z, Z1) => Z2)(implicit ev: L <:< In1, trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z2] =
    flatMap(z => that.map(f(z, _)))

  /**
   * Runs both sinks in parallel on the input and combines the results using the
   * provided function.
   */
  final def zipWithPar[R1 <: R, E1 >: E, In1 <: In, L1 >: L <: In1, Z1, Z2](
    that: ZSink[R1, E1, In1, L1, Z1],
    capacity: Int = 16
  )(f: (Z, Z1) => Z2)(implicit trace: ZTraceElement): ZSink[R1, E1, In1, L1, Z2] =
    self.raceWith(that)(
      {
        case Exit.Failure(err) => ZChannel.MergeDecision.done(ZIO.failCause(err))
        case Exit.Success(lz) =>
          ZChannel.MergeDecision.await {
            case Exit.Failure(cause) => ZIO.failCause(cause)
            case Exit.Success(rz)    => ZIO.succeedNow(f(lz, rz))
          }
      },
      {
        case Exit.Failure(err) => ZChannel.MergeDecision.done(ZIO.failCause(err))
        case Exit.Success(rz) =>
          ZChannel.MergeDecision.await {
            case Exit.Failure(cause) => ZIO.failCause(cause)
            case Exit.Success(lz)    => ZIO.succeedNow(f(lz, rz))
          }
      }
    )

  def exposeLeftover(implicit trace: ZTraceElement): ZSink[R, E, In, Nothing, (Z, Chunk[L])] =
    new ZSink(channel.doneCollect.map { case (chunks, z) => (z, chunks.flatten) })

  def dropLeftover(implicit trace: ZTraceElement): ZSink[R, E, In, Nothing, Z] =
    new ZSink(channel.drain)

  /**
   * Creates a sink that produces values until one verifies the predicate `f`.
   */
  @deprecated("use untilOutputZIO", "2.0.0")
  def untilOutputM[R1 <: R, E1 >: E](
    f: Z => ZIO[R1, E1, Boolean]
  )(implicit ev: L <:< In, trace: ZTraceElement): ZSink[R1, E1, In, L, Option[Z]] =
    untilOutputZIO(f)

  /**
   * Creates a sink that produces values until one verifies the predicate `f`.
   */
  def untilOutputZIO[R1 <: R, E1 >: E](
    f: Z => ZIO[R1, E1, Boolean]
  )(implicit ev: L <:< In, trace: ZTraceElement): ZSink[R1, E1, In, L, Option[Z]] =
    new ZSink(
      ZChannel
        .fromZIO(Ref.make(Chunk[In]()).zip(Ref.make(false)))
        .flatMap[R1, Nothing, Chunk[In], Any, E1, Chunk[L], Option[Z]] { case (leftoversRef, upstreamDoneRef) =>
          lazy val upstreamMarker: ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
            ZChannel.readWith[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any](
              (in: Chunk[In]) =>
                ZChannel.write(in).zipRight[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any](upstreamMarker),
              ZChannel.fail(_: Nothing),
              (x: Any) => ZChannel.fromZIO(upstreamDoneRef.set(true)).as(x)
            )

          lazy val loop: ZChannel[R1, Nothing, Chunk[In], Any, E1, Chunk[L], Option[Z]] =
            channel.doneCollect
              .foldChannel[R1, Nothing, Chunk[In], Any, E1, Chunk[L], Option[Z]](
                ZChannel.fail(_),
                { case (leftovers, doneValue) =>
                  ZChannel.fromZIO(f(doneValue)).flatMap[R1, Nothing, Chunk[In], Any, E1, Chunk[L], Option[Z]] {
                    satisfied =>
                      ZChannel
                        .fromZIO(leftoversRef.set(leftovers.flatten.asInstanceOf[Chunk[In]]))
                        .flatMap[R1, Nothing, Chunk[In], Any, E1, Chunk[L], Option[Z]] { _ =>
                          ZChannel
                            .fromZIO(upstreamDoneRef.get)
                            .flatMap[R1, Nothing, Chunk[In], Any, E1, Chunk[L], Option[Z]] { upstreamDone =>
                              if (satisfied) ZChannel.write(leftovers.flatten).as(Some(doneValue))
                              else if (upstreamDone)
                                ZChannel.write(leftovers.flatten).as(None)
                              else loop
                            }
                        }
                  }
                }
              )

          upstreamMarker >>> ZChannel.bufferChunk(leftoversRef) >>> loop
        }
    )

  /**
   * Provides the sink with its required environment, which eliminates its
   * dependency on `R`.
   */
  def provideEnvironment(
    r: ZEnvironment[R]
  )(implicit ev: NeedsEnv[R], trace: ZTraceElement): ZSink[Any, E, In, L, Z] =
    new ZSink(channel.provideEnvironment(r))
}

object ZSink extends ZSinkPlatformSpecificConstructors {

  /**
   * Accesses the environment of the sink in the context of a sink.
   */
  def environmentWithSink[R]: EnvironmentWithSinkPartiallyApplied[R] =
    new EnvironmentWithSinkPartiallyApplied[R]

  def collectAll[In](implicit trace: ZTraceElement): ZSink[Any, Nothing, In, Nothing, Chunk[In]] = {
    def loop(acc: Chunk[In]): ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Nothing, Chunk[In]] =
      ZChannel.readWithCause[Any, Nothing, Chunk[In], Any, Nothing, Nothing, Chunk[In]](
        chunk => loop(acc ++ chunk),
        ZChannel.failCause(_),
        _ => ZChannel.succeed(acc)
      )
    new ZSink(loop(Chunk.empty))
  }

  /**
   * A sink that collects first `n` elements into a chunk. Note that the chunk
   * is preallocated and must fit in memory.
   */
  def collectAllN[In](n: Int)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, Chunk[In]] =
    fromZIO(UIO(ChunkBuilder.make[In](n)))
      .flatMap(cb => foldUntil[In, ChunkBuilder[In]](cb, n.toLong)(_ += _))
      .map(_.result())

  /**
   * A sink that collects all of its inputs into a map. The keys are extracted
   * from inputs using the keying function `key`; if multiple inputs use the
   * same key, they are merged using the `f` function.
   */
  def collectAllToMap[In, K](
    key: In => K
  )(f: (In, In) => In)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, Nothing, Map[K, In]] =
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
    n: Long
  )(key: In => K)(f: (In, In) => In)(implicit trace: ZTraceElement): ZSink[Any, Err, In, In, Map[K, In]] =
    foldWeighted[In, Map[K, In]](Map())((acc, in) => if (acc.contains(key(in))) 0 else 1, n) { (acc, in) =>
      val k = key(in)
      val v = if (acc.contains(k)) f(acc(k), in) else in

      acc.updated(k, v)
    }

  /**
   * A sink that collects all of its inputs into a set.
   */
  def collectAllToSet[In](implicit trace: ZTraceElement): ZSink[Any, Nothing, In, Nothing, Set[In]] =
    foldLeftChunks(Set[In]())((acc, as) => as.foldLeft(acc)(_ + _))

  /**
   * A sink that collects first `n` distinct inputs into a set.
   */
  def collectAllToSetN[In](n: Long)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, Set[In]] =
    foldWeighted[In, Set[In]](Set())((acc, in) => if (acc.contains(in)) 0 else 1, n)(_ + _)

  /**
   * Accumulates incoming elements into a chunk as long as they verify predicate
   * `p`.
   */
  def collectAllWhile[In](p: In => Boolean)(implicit
    trace: ZTraceElement
  ): ZSink[Any, Nothing, In, In, Chunk[In]] =
    fold[In, (List[In], Boolean)]((Nil, true))(_._2) { case ((as, _), a) =>
      if (p(a)) (a :: as, true)
      else (as, false)
    }.map { case (is, _) =>
      Chunk.fromIterable(is.reverse)
    }

  /**
   * Accumulates incoming elements into a chunk as long as they verify effectful
   * predicate `p`.
   */
  @deprecated("use collectAllWhileZIO", "2.0.0")
  def collectAllWhileM[Env, Err, In](p: In => ZIO[Env, Err, Boolean])(implicit
    trace: ZTraceElement
  ): ZSink[Env, Err, In, In, Chunk[In]] =
    collectAllWhileZIO(p)

  /**
   * Accumulates incoming elements into a chunk as long as they verify effectful
   * predicate `p`.
   */
  def collectAllWhileZIO[Env, Err, In](p: In => ZIO[Env, Err, Boolean])(implicit
    trace: ZTraceElement
  ): ZSink[Env, Err, In, In, Chunk[In]] =
    foldZIO[Env, Err, In, (List[In], Boolean)]((Nil, true))(_._2) { case ((as, _), a) =>
      p(a).map(if (_) (a :: as, true) else (as, false))
    }.map { case (is, _) =>
      Chunk.fromIterable(is.reverse)
    }

  /**
   * A sink that counts the number of elements fed to it.
   */
  def count(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, Long] =
    foldLeft(0L)((s, _) => s + 1)

  /**
   * Creates a sink halting with the specified `Throwable`.
   */
  def die(e: => Throwable)(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, Nothing] =
    ZSink.failCause(Cause.die(e))

  /**
   * Creates a sink halting with the specified message, wrapped in a
   * `RuntimeException`.
   */
  def dieMessage(m: => String)(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, Nothing] =
    ZSink.failCause(Cause.die(new RuntimeException(m)))

  /**
   * A sink that ignores its inputs.
   */
  def drain(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, Unit] =
    new ZSink(ZChannel.read[Any].unit.repeated.catchAll(_ => ZChannel.unit))

  def dropWhile[In](p: In => Boolean)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, Any] = {
    lazy val loop: ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
      ZChannel.readWith[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any](
        (in: Chunk[In]) => {
          val leftover = in.dropWhile(p)
          val more     = leftover.isEmpty
          if (more) loop
          else
            ZChannel
              .write(leftover)
              .zipRight[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], Any](
                ZChannel.identity[Nothing, Chunk[In], Any]
              )
        },
        (e: Nothing) => ZChannel.fail(e),
        (_: Any) => ZChannel.unit
      )
    new ZSink(loop)
  }

  @deprecated("use dropWhileZIO", "2.0.0")
  def dropWhileM[R, InErr, In](p: In => ZIO[R, InErr, Boolean])(implicit
    trace: ZTraceElement
  ): ZSink[R, InErr, In, In, Any] =
    dropWhileZIO(p)

  def dropWhileZIO[R, InErr, In](
    p: In => ZIO[R, InErr, Boolean]
  )(implicit trace: ZTraceElement): ZSink[R, InErr, In, In, Any] = {
    lazy val loop: ZChannel[R, InErr, Chunk[In], Any, InErr, Chunk[In], Any] = ZChannel.readWith(
      (in: Chunk[In]) =>
        ZChannel.unwrap(in.dropWhileZIO(p).map { leftover =>
          val more = leftover.isEmpty
          if (more) loop else ZChannel.write(leftover) *> ZChannel.identity[InErr, Chunk[In], Any]
        }),
      (e: InErr) => ZChannel.fail(e),
      (_: Any) => ZChannel.unit
    )

    new ZSink(loop)
  }

  /**
   * Returns a lazily constructed sink that may require effects for its
   * creation.
   */
  def effectSuspendTotal[Env, E, In, Leftover, Done](
    sink: => ZSink[Env, E, In, Leftover, Done]
  )(implicit trace: ZTraceElement): ZSink[Env, E, In, Leftover, Done] =
    new ZSink(ZChannel.effectSuspendTotal[Env, Nothing, Chunk[In], Any, E, Chunk[Leftover], Done](sink.channel))

  /**
   * Returns a sink that executes a total effect and ends with its result.
   */
  def effectTotal[A](a: => A)(implicit trace: ZTraceElement): ZSink[Any, Any, Nothing, Nothing, A] =
    new ZSink(ZChannel.effectTotal(a))

  /**
   * A sink that always fails with the specified error.
   */
  def fail[E](e: => E)(implicit trace: ZTraceElement): ZSink[Any, E, Any, Nothing, Nothing] = new ZSink(
    ZChannel.fail(e)
  )

  /**
   * Creates a sink halting with a specified cause.
   */
  def failCause[E](e: => Cause[E])(implicit trace: ZTraceElement): ZSink[Any, E, Any, Nothing, Nothing] =
    new ZSink(ZChannel.failCause(e))

  /**
   * A sink that folds its inputs with the provided function, termination
   * predicate and initial state.
   */
  def fold[In, S](
    z: S
  )(contFn: S => Boolean)(f: (S, In) => S)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, S] = {
    def foldChunkSplit(z: S, chunk: Chunk[In])(
      contFn: S => Boolean
    )(f: (S, In) => S): (S, Chunk[In]) = {
      def fold(s: S, chunk: Chunk[In], idx: Int, len: Int): (S, Chunk[In]) =
        if (idx == len) {
          (s, Chunk.empty)
        } else {
          val s1 = f(s, chunk(idx))
          if (contFn(s1)) {
            fold(s1, chunk, idx + 1, len)
          } else {
            (s1, chunk.drop(idx + 1))
          }
        }

      fold(z, chunk, 0, chunk.length)
    }

    def reader(s: S): ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], S] =
      if (!contFn(s)) ZChannel.end(s)
      else
        ZChannel.readWith[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], S](
          (in: Chunk[In]) => {
            val (nextS, leftovers) = foldChunkSplit(s, in)(contFn)(f)

            if (leftovers.nonEmpty) ZChannel.write(leftovers).as(nextS)
            else reader(nextS)
          },
          (err: Nothing) => ZChannel.fail(err),
          (x: Any) => ZChannel.end(s)
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
    z: S
  )(
    contFn: S => Boolean
  )(f: (S, Chunk[In]) => S)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, Nothing, S] = {
    def reader(s: S): ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Nothing, S] =
      ZChannel.readWith[Any, Nothing, Chunk[In], Any, Nothing, Nothing, S](
        (in: Chunk[In]) => {
          val nextS = f(s, in)

          if (contFn(nextS)) reader(nextS)
          else ZChannel.end(nextS)
        },
        (err: Nothing) => ZChannel.fail(err),
        (_: Any) => ZChannel.end(s)
      )

    new ZSink(
      if (contFn(z)) reader(z)
      else ZChannel.end(z)
    )
  }

  /**
   * A sink that effectfully folds its input chunks with the provided function,
   * termination predicate and initial state. `contFn` condition is checked only
   * for the initial value and at the end of processing of each chunk. `f` and
   * `contFn` must preserve chunking-invariance.
   */
  @deprecated("use foldChunksZIO", "2.0.0")
  def foldChunksM[Env, Err, In, S](
    z: S
  )(contFn: S => Boolean)(f: (S, Chunk[In]) => ZIO[Env, Err, S])(implicit
    trace: ZTraceElement
  ): ZSink[Env, Err, In, In, S] =
    foldChunksZIO(z)(contFn)(f)

  /**
   * A sink that effectfully folds its input chunks with the provided function,
   * termination predicate and initial state. `contFn` condition is checked only
   * for the initial value and at the end of processing of each chunk. `f` and
   * `contFn` must preserve chunking-invariance.
   */
  def foldChunksZIO[Env, Err, In, S](
    z: S
  )(
    contFn: S => Boolean
  )(f: (S, Chunk[In]) => ZIO[Env, Err, S])(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] = {
    def reader(s: S): ZChannel[Env, Err, Chunk[In], Any, Err, Nothing, S] =
      ZChannel.readWith(
        (in: Chunk[In]) =>
          ZChannel.fromZIO(f(s, in)).flatMap { nextS =>
            if (contFn(nextS)) reader(nextS)
            else ZChannel.end(nextS)
          },
        (err: Err) => ZChannel.fail(err),
        (_: Any) => ZChannel.end(s)
      )

    new ZSink(
      if (contFn(z)) reader(z)
      else ZChannel.end(z)
    )
  }

  /**
   * A sink that folds its inputs with the provided function and initial state.
   */
  def foldLeft[In, S](z: S)(f: (S, In) => S)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, Nothing, S] =
    fold(z)(_ => true)(f).dropLeftover

  /**
   * A sink that folds its input chunks with the provided function and initial
   * state. `f` must preserve chunking-invariance.
   */
  def foldLeftChunks[In, S](z: S)(f: (S, Chunk[In]) => S)(implicit
    trace: ZTraceElement
  ): ZSink[Any, Nothing, In, Nothing, S] =
    foldChunks[In, S](z)(_ => true)(f)

  /**
   * A sink that effectfully folds its input chunks with the provided function
   * and initial state. `f` must preserve chunking-invariance.
   */
  @deprecated("use foldLeftChunksZIO", "2.0.0")
  def foldLeftChunksM[R, Err, In, S](z: S)(
    f: (S, Chunk[In]) => ZIO[R, Err, S]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, Nothing, S] =
    foldLeftChunksZIO[R, Err, In, S](z)(f)

  /**
   * A sink that effectfully folds its input chunks with the provided function
   * and initial state. `f` must preserve chunking-invariance.
   */
  def foldLeftChunksZIO[R, Err, In, S](z: S)(
    f: (S, Chunk[In]) => ZIO[R, Err, S]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, Nothing, S] =
    foldChunksZIO[R, Err, In, S](z)(_ => true)(f).dropLeftover

  /**
   * A sink that effectfully folds its inputs with the provided function and
   * initial state.
   */
  @deprecated("use foldLeftZIO", "2.0.0")
  def foldLeftM[R, Err, In, S](z: S)(
    f: (S, In) => ZIO[R, Err, S]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, In, S] =
    foldLeftZIO(z)(f)

  /**
   * A sink that effectfully folds its inputs with the provided function and
   * initial state.
   */
  def foldLeftZIO[R, Err, In, S](z: S)(
    f: (S, In) => ZIO[R, Err, S]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, In, S] =
    foldZIO[R, Err, In, S](z)(_ => true)(f)

  /**
   * A sink that effectfully folds its inputs with the provided function,
   * termination predicate and initial state.
   */
  @deprecated("use foldZIO", "2.0.0")
  def foldM[Env, Err, In, S](z: S)(contFn: S => Boolean)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] =
    foldZIO(z)(contFn)(f)

  /**
   * Creates a sink that folds elements of type `In` into a structure of type
   * `S` until `max` elements have been folded.
   *
   * Like [[foldWeighted]], but with a constant cost function of 1.
   */
  def foldUntil[In, S](z: S, max: Long)(f: (S, In) => S)(implicit
    trace: ZTraceElement
  ): ZSink[Any, Nothing, In, In, S] =
    fold[In, (S, Long)]((z, 0))(_._2 < max) { case ((o, count), i) =>
      (f(o, i), count + 1)
    }.map(_._1)

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S` until `max` elements have been folded.
   *
   * Like [[foldWeightedM]], but with a constant cost function of 1.
   */
  @deprecated("use foldUntilZIO", "2.0.0")
  def foldUntilM[Env, Err, In, S](z: S, max: Long)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] =
    foldUntilZIO(z, max)(f)

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S` until `max` elements have been folded.
   *
   * Like [[foldWeightedM]], but with a constant cost function of 1.
   */
  def foldUntilZIO[Env, Err, In, S](z: S, max: Long)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] =
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
  def foldWeighted[In, S](z: S)(costFn: (S, In) => Long, max: Long)(
    f: (S, In) => S
  )(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, S] =
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
   * The [[foldWeightedDecomposeM]] allows the decompose function to return a
   * `ZIO` value, and consequently it allows the sink to fail.
   */
  def foldWeightedDecompose[In, S](
    z: S
  )(costFn: (S, In) => Long, max: Long, decompose: In => Chunk[In])(
    f: (S, In) => S
  )(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, S] = {
    def go(s: S, cost: Long, dirty: Boolean): ZChannel[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], S] =
      ZChannel.readWith[Any, Nothing, Chunk[In], Any, Nothing, Chunk[In], S](
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

          if (leftovers.nonEmpty) ZChannel.write(leftovers) *> ZChannel.end(nextS)
          else if (cost > max) ZChannel.end(nextS)
          else go(nextS, nextCost, nextDirty)
        },
        (err: Nothing) => ZChannel.fail(err),
        (_: Any) => ZChannel.end(s)
      )

    new ZSink(go(z, 0, false))
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
  @deprecated("use foldWeightedDecomposeZIO", "2.0.0")
  def foldWeightedDecomposeM[Env, Err, In, S](z: S)(
    costFn: (S, In) => ZIO[Env, Err, Long],
    max: Long,
    decompose: In => ZIO[Env, Err, Chunk[In]]
  )(f: (S, In) => ZIO[Env, Err, S])(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] =
    foldWeightedDecomposeZIO(z)(costFn, max, decompose)(f)

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
  def foldWeightedDecomposeZIO[Env, Err, In, S](z: S)(
    costFn: (S, In) => ZIO[Env, Err, Long],
    max: Long,
    decompose: In => ZIO[Env, Err, Chunk[In]]
  )(f: (S, In) => ZIO[Env, Err, S])(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] = {
    def go(s: S, cost: Long, dirty: Boolean): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], S] =
      ZChannel.readWith(
        (in: Chunk[In]) => {
          def fold(
            in: Chunk[In],
            s: S,
            dirty: Boolean,
            cost: Long,
            idx: Int
          ): ZIO[Env, Err, (S, Long, Boolean, Chunk[In])] =
            if (idx == in.length) UIO.succeed((s, cost, dirty, Chunk.empty))
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
                      UIO.succeed((s, cost, dirty, in.drop(idx)))
                    else
                      // `elem` got decomposed, so we will recurse with the decomposed elements pushed
                      // into the chunk we're processing and see if we can aggregate further.
                      fold(decomposed ++ in.drop(idx + 1), s, dirty, cost, 0)
                  }
              }
            }

          ZChannel.fromZIO(fold(in, s, dirty, cost, 0)).flatMap { case (nextS, nextCost, nextDirty, leftovers) =>
            if (leftovers.nonEmpty) ZChannel.write(leftovers) *> ZChannel.end(nextS)
            else if (cost > max) ZChannel.end(nextS)
            else go(nextS, nextCost, nextDirty)
          }
        },
        (err: Err) => ZChannel.fail(err),
        (_: Any) => ZChannel.end(s)
      )

    new ZSink(go(z, 0, false))
  }

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S`, until `max` worth of elements (determined by the
   * `costFn`) have been folded.
   *
   * @note
   *   Elements that have an individual cost larger than `max` will force the
   *   sink to cross the `max` cost. See [[foldWeightedDecomposeM]] for a
   *   variant that can handle these cases.
   */
  @deprecated("use foldWeightedZIO", "2.0.0-")
  def foldWeightedM[Env, Err, In, S](
    z: S
  )(costFn: (S, In) => ZIO[Env, Err, Long], max: Long)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] =
    foldWeightedZIO(z)(costFn, max)(f)

  /**
   * Creates a sink that effectfully folds elements of type `In` into a
   * structure of type `S`, until `max` worth of elements (determined by the
   * `costFn`) have been folded.
   *
   * @note
   *   Elements that have an individual cost larger than `max` will force the
   *   sink to cross the `max` cost. See [[foldWeightedDecomposeM]] for a
   *   variant that can handle these cases.
   */
  def foldWeightedZIO[Env, Err, In, S](
    z: S
  )(costFn: (S, In) => ZIO[Env, Err, Long], max: Long)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] =
    foldWeightedDecomposeZIO(z)(costFn, max, (i: In) => UIO.succeedNow(Chunk.single(i)))(f)

  /**
   * A sink that effectfully folds its inputs with the provided function,
   * termination predicate and initial state.
   */
  def foldZIO[Env, Err, In, S](z: S)(contFn: S => Boolean)(
    f: (S, In) => ZIO[Env, Err, S]
  )(implicit trace: ZTraceElement): ZSink[Env, Err, In, In, S] = {
    def foldChunkSplitM(z: S, chunk: Chunk[In])(
      contFn: S => Boolean
    )(f: (S, In) => ZIO[Env, Err, S]): ZIO[Env, Err, (S, Option[Chunk[In]])] = {
      def fold(s: S, chunk: Chunk[In], idx: Int, len: Int): ZIO[Env, Err, (S, Option[Chunk[In]])] =
        if (idx == len) UIO.succeed((s, None))
        else
          f(s, chunk(idx)).flatMap { s1 =>
            if (contFn(s1)) {
              fold(s1, chunk, idx + 1, len)
            } else {
              UIO.succeed((s1, Some(chunk.drop(idx + 1))))
            }
          }

      fold(z, chunk, 0, chunk.length)
    }

    def reader(s: S): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], S] =
      ZChannel.readWith(
        (in: Chunk[In]) =>
          ZChannel.fromZIO(foldChunkSplitM(s, in)(contFn)(f)).flatMap { case (nextS, leftovers) =>
            leftovers match {
              case Some(l) => ZChannel.write(l).as(nextS)
              case None    => reader(nextS)
            }
          },
        (err: Err) => ZChannel.fail(err),
        (_: Any) => ZChannel.end(s)
      )

    new ZSink(
      if (contFn(z)) reader(z)
      else ZChannel.end(z)
    )
  }

  /**
   * A sink that executes the provided effectful function for every element fed
   * to it.
   */
  def foreach[R, Err, In](
    f: In => ZIO[R, Err, Any]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, Nothing, Unit] = {

    lazy val process: ZChannel[R, Err, Chunk[In], Any, Err, Nothing, Unit] =
      ZChannel.readWithCause[R, Err, Chunk[In], Any, Err, Nothing, Unit](
        in => ZChannel.fromZIO(ZIO.foreachDiscard(in)(f(_))) *> process,
        halt => ZChannel.failCause(halt),
        _ => ZChannel.end(())
      )

    new ZSink(process)
  }

  /**
   * A sink that executes the provided effectful function for every chunk fed to
   * it.
   */
  def foreachChunk[R, Err, In](
    f: Chunk[In] => ZIO[R, Err, Any]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, Nothing, Unit] = {
    lazy val process: ZChannel[R, Err, Chunk[In], Any, Err, Nothing, Unit] =
      ZChannel.readWithCause(
        in => ZChannel.fromZIO(f(in)) *> process,
        halt => ZChannel.failCause(halt),
        _ => ZChannel.end(())
      )

    new ZSink(process)
  }

  /**
   * A sink that executes the provided effectful function for every element fed
   * to it until `f` evaluates to `false`.
   */
  final def foreachWhile[R, Err, In](
    f: In => ZIO[R, Err, Boolean]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, In, Unit] = {
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
      ZChannel.readWithCause[R, Err, Chunk[In], Any, Err, Chunk[In], Unit](
        in => go(in, 0, in.length, process),
        halt => ZChannel.failCause(halt),
        _ => ZChannel.end(())
      )

    new ZSink(process)
  }

  /**
   * A sink that executes the provided effectful function for every chunk fed to
   * it until `f` evaluates to `false`.
   */
  def foreachChunkWhile[R, Err, In](
    f: Chunk[In] => ZIO[R, Err, Boolean]
  )(implicit trace: ZTraceElement): ZSink[R, Err, In, In, Unit] = {
    lazy val reader: ZChannel[R, Err, Chunk[In], Any, Err, Nothing, Unit] =
      ZChannel.readWith(
        (in: Chunk[In]) =>
          ZChannel.fromZIO(f(in)).flatMap { continue =>
            if (continue) reader
            else ZChannel.end(())
          },
        (err: Err) => ZChannel.fail(err),
        (_: Any) => ZChannel.unit
      )

    new ZSink(reader)
  }

  /**
   * Creates a single-value sink produced from an effect
   */
  @deprecated("use fromZIO", "2.0.0")
  def fromEffect[R, E, Z](b: => ZIO[R, E, Z])(implicit trace: ZTraceElement): ZSink[R, E, Any, Nothing, Z] =
    fromZIO(b)

  /**
   * Creates a single-value sink produced from an effect
   */
  def fromZIO[R, E, Z](b: => ZIO[R, E, Z])(implicit trace: ZTraceElement): ZSink[R, E, Any, Nothing, Z] =
    new ZSink(ZChannel.fromZIO(b))

  /**
   * Create a sink which enqueues each element into the specified queue.
   */
  def fromQueue[R, E, I](queue: ZEnqueue[R, E, I])(implicit trace: ZTraceElement): ZSink[R, E, I, Nothing, Unit] =
    foreachChunk(queue.offerAll)

  /**
   * Create a sink which enqueues each element into the specified queue. The
   * queue will be shutdown once the stream is closed.
   */
  def fromQueueWithShutdown[R, E, I](queue: ZQueue[R, Nothing, E, Any, I, Any])(implicit
    trace: ZTraceElement
  ): ZSink[R, E, I, Nothing, Unit] =
    ZSink.unwrapManaged(
      ZManaged.acquireReleaseWith(ZIO.succeedNow(queue))(_.shutdown).map(fromQueue[R, E, I])
    )

  /**
   * Create a sink which publishes each element to the specified hub.
   */
  def fromHub[R, E, I](hub: ZHub[R, Nothing, E, Any, I, Any])(implicit
    trace: ZTraceElement
  ): ZSink[R, E, I, Nothing, Unit] =
    fromQueue(hub.toQueue)

  /**
   * Create a sink which publishes each element to the specified hub. The hub
   * will be shutdown once the stream is closed.
   */
  def fromHubWithShutdown[R, E, I](hub: ZHub[R, Nothing, E, Any, I, Any])(implicit
    trace: ZTraceElement
  ): ZSink[R, E, I, Nothing, Unit] =
    fromQueueWithShutdown(hub.toQueue)

  /**
   * Creates a sink halting with a specified cause.
   */
  @deprecated("use failCause", "2.0.0")
  def halt[E](e: => Cause[E])(implicit trace: ZTraceElement): ZSink[Any, Any, E, Nothing, Nothing] =
    failCause(e)

  /**
   * Creates a sink containing the first value.
   */
  def head[In](implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, Option[In]] =
    fold(None: Option[In])(_.isEmpty) {
      case (s @ Some(_), _) => s
      case (None, in)       => Some(in)
    }

  /**
   * Creates a sink containing the last value.
   */
  def last[In](implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, Option[In]] =
    foldLeft(None: Option[In])((_, in) => Some(in))

  def leftover[L](c: Chunk[L])(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, L, Unit] =
    new ZSink(ZChannel.write(c))

  def mkString(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, String] =
    ZSink.effectSuspendTotal {
      val builder = new StringBuilder()

      foldLeftChunks[Any, Unit](())((_, els: Chunk[Any]) => els.foreach(el => builder.append(el.toString))).map(_ =>
        builder.result()
      )
    }

  @deprecated("use unwrapManaged", "2.0.0")
  def managed[R, E, In, A, L <: In, Z](resource: ZManaged[R, E, A])(
    fn: A => ZSink[R, E, In, L, Z]
  )(implicit trace: ZTraceElement): ZSink[R, E, In, In, Z] =
    new ZSink(ZChannel.managed[R, Nothing, Chunk[In], Any, E, Chunk[L], Z, A](resource)(fn(_).channel))

  def never(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, Nothing] = new ZSink(
    ZChannel.fromZIO(ZIO.never)
  )

  /**
   * A sink that immediately ends with the specified value.
   */
  def succeed[Z](z: => Z)(implicit trace: ZTraceElement): ZSink[Any, Nothing, Any, Nothing, Z] = new ZSink(
    ZChannel.succeed(z)
  )

  /**
   * A sink that sums incoming numeric values.
   */
  def sum[A](implicit A: Numeric[A], trace: ZTraceElement): ZSink[Any, Nothing, A, Nothing, A] =
    foldLeft(A.zero)(A.plus)

  /**
   * A sink that takes the specified number of values.
   */
  def take[In](n: Int)(implicit trace: ZTraceElement): ZSink[Any, Nothing, In, In, Chunk[In]] =
    ZSink.foldChunks[In, Chunk[In]](Chunk.empty)(_.length < n)(_ ++ _).flatMap { acc =>
      val (taken, leftover) = acc.splitAt(n)
      new ZSink(
        ZChannel.write(leftover) *> ZChannel.end(taken)
      )
    }

  def timed(implicit trace: ZTraceElement): ZSink[Clock, Nothing, Any, Nothing, Duration] =
    ZSink.drain.timed.map(_._2)

  /**
   * Creates a sink produced from an effect.
   */
  def unwrap[R, E, In, L, Z](
    zio: ZIO[R, E, ZSink[R, E, In, L, Z]]
  )(implicit trace: ZTraceElement): ZSink[R, E, In, L, Z] =
    new ZSink(ZChannel.unwrap[R, Nothing, Chunk[In], Any, E, Chunk[L], Z](zio.map(_.channel)))

  /**
   * Creates a sink produced from a managed effect.
   */
  def unwrapManaged[R, E, In, L, Z](
    managed: ZManaged[R, E, ZSink[R, E, In, L, Z]]
  )(implicit trace: ZTraceElement): ZSink[R, E, In, L, Z] =
    new ZSink(ZChannel.unwrapManaged[R, Nothing, Chunk[In], Any, E, Chunk[L], Z](managed.map(_.channel)))

  final class EnvironmentWithSinkPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, In, L, Z](
      f: ZEnvironment[R] => ZSink[R1, E, In, L, Z]
    )(implicit trace: ZTraceElement): ZSink[R with R1, E, In, L, Z] =
      new ZSink(ZChannel.unwrap[R1, Nothing, Chunk[In], Any, E, Chunk[L], Z](ZIO.environmentWith[R](f(_).channel)))
  }
}
