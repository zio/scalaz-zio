package zio.stream

import zio.{ZIO, _}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.internal.{AsyncInputConsumer, AsyncInputProducer, ChannelExecutor, SingleProducerAsyncInput}
import ChannelExecutor.ChannelState
import zio.internal.FiberScope

/**
 * A `ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]` is a nexus
 * of I/O operations, which supports both reading and writing. A channel may
 * read values of type `InElem` or process upstream failures of type `InErr`,
 * while it may write values of type `OutElem`. When the channel finishes, it
 * yields a value of type `OutDone`. A channel may fail with a value of type
 * `OutErr`.
 *
 * Channels are the foundation of ZIO Streams: both streams and sinks are built
 * on channels. Most users shouldn't have to use channels directly, as streams
 * and sinks are much more convenient and cover all common use cases. However,
 * when adding new stream and sink operators, or doing something highly
 * specialized, it may be useful to use channels directly.
 *
 * Channels compose in a variety of ways:
 *
 *   - Piping. One channel can be piped to another channel, assuming the input
 *     type of the second is the same as the output type of the first.
 *   - Sequencing. The terminal value of one channel can be used to create
 *     another channel, and both the first channel and the function that makes
 *     the second channel can be composed into a channel.
 *   - Concating. The output of one channel can be used to create other
 *     channels, which are all concatenated together. The first channel and the
 *     function that makes the other channels can be composed into a channel.
 */
sealed trait ZChannel[-Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone] { self =>

  /**
   * Returns a new channel that is the sequential composition of this channel
   * and the specified channel. The returned channel terminates with a tuple of
   * the terminal values of both channels.
   *
   * This is a symbol operator for [[ZChannel#zip]].
   */
  final def <*>[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit
    zippable: Zippable[OutDone, OutDone2],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, zippable.Out] =
    self.flatMap(z => that.map(z2 => zippable.zip(z, z2)))

  /**
   * Returns a new channel that is the sequential composition of this channel
   * and the specified channel. The returned channel terminates with the
   * terminal value of the other channel.
   *
   * This is a symbol operator for [[ZChannel#zipRight]].
   */
  final def *>[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2] =
    self.flatMap(_ => that)

  /**
   * Returns a new channel that is the sequential composition of this channel
   * and the specified channel. The returned channel terminates with the
   * terminal value of this channel.
   *
   * This is a symbol operator for [[ZChannel#zipLeft]].
   */
  final def <*[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone] =
    self.flatMap(z => that.as(z))

  /**
   * Returns a new channel that pipes the output of this channel into the
   * specified channel. The returned channel has the input type of this channel,
   * and the output type of the specified channel, terminating with the value of
   * the specified channel.
   *
   * This is a symbolic operator for [[ZChannel#pipeTo]].
   */
  final def >>>[Env1 <: Env, OutErr2, OutElem2, OutDone2](
    that: => ZChannel[Env1, OutErr, OutElem, OutDone, OutErr2, OutElem2, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr2, OutElem2, OutDone2] =
    self pipeTo that

  /**
   * Returns a new channel that is the same as this one, except the terminal
   * value of the channel is the specified constant value.
   *
   * This method produces the same result as mapping this channel to the
   * specified constant value.
   */
  final def as[OutDone2](z2: => OutDone2)(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone2] =
    self.map(_ => z2)

  /**
   * Returns a new channel that is the same as this one, except if this channel
   * errors for any typed error, then the returned channel will switch over to
   * using the fallback channel returned by the specified error handler.
   */
  final def catchAll[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr2,
    OutElem1 >: OutElem,
    OutDone1 >: OutDone
  ](
    f: OutErr => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone1] =
    catchAllCause((cause: Cause[OutErr]) => cause.failureOrCause.fold(f(_), ZChannel.refailCause))

  /**
   * Returns a new channel that is the same as this one, except if this channel
   * errors for any cause at all, then the returned channel will switch over to
   * using the fallback channel returned by the specified error handler.
   */
  final def catchAllCause[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr2,
    OutElem1 >: OutElem,
    OutDone1 >: OutDone
  ](
    f: Cause[OutErr] => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone1] =
    ZChannel.Fold(self, new ZChannel.Fold.K(ZChannel.Fold.successIdentity[OutDone1], f))

  /**
   * Returns a new channel whose outputs are fed to the specified factory
   * function, which creates new channels in response. These new channels are
   * sequentially concatenated together, and all their outputs appear as outputs
   * of the newly returned channel.
   */
  final def concatMap[Env1 <: Env, InErr1 <: InErr, InElem1 <: InElem, InDone1 <: InDone, OutErr1 >: OutErr, OutElem2](
    f: OutElem => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any] =
    concatMapWith(f)((_, _) => (), (_, _) => ())

  /**
   * Returns a new channel whose outputs are fed to the specified factory
   * function, which creates new channels in response. These new channels are
   * sequentially concatenated together, and all their outputs appear as outputs
   * of the newly returned channel. The provided merging function is used to
   * merge the terminal values of all channels into the single terminal value of
   * the returned channel.
   */
  final def concatMapWith[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem2,
    OutDone2,
    OutDone3
  ](
    f: OutElem => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, OutDone2]
  )(
    g: (OutDone2, OutDone2) => OutDone2,
    h: (OutDone2, OutDone) => OutDone3
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, OutDone3] =
    ZChannel.ConcatAll(
      g,
      h,
      () => self,
      f
    )

  /**
   * Returns a new channel, which is the same as this one, except its outputs
   * are filtered and transformed by the specified partial function.
   */
  final def collect[OutElem2](
    f: PartialFunction[OutElem, OutElem2]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone] = {
    val pf = f.lift
    lazy val collector: ZChannel[Env, OutErr, OutElem, OutDone, OutErr, OutElem2, OutDone] =
      ZChannel.readWithCause(
        pf(_: OutElem) match {
          case None       => collector
          case Some(out2) => ZChannel.write(out2) *> collector
        },
        (e: Cause[OutErr]) => ZChannel.refailCause(e),
        (z: OutDone) => ZChannel.succeedNow(z)
      )

    self pipeTo collector
  }

  /**
   * Returns a new channel, which is the concatenation of all the channels that
   * are written out by this channel. This method may only be called on channels
   * that output other channels.
   */
  final def concatOut[Env1 <: Env, InErr1 <: InErr, InElem1 <: InElem, InDone1 <: InDone, OutErr1 >: OutErr, OutElem2](
    implicit
    ev: OutElem <:< ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any] =
    ZChannel.concatAll(self.mapOut(ev))

  /**
   * Returns a new channel which is the same as this one but applies the given
   * function to the input channel's done value.
   */
  final def contramap[InDone0](
    f: InDone0 => InDone
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone0, OutErr, OutElem, OutDone] = {
    lazy val reader: ZChannel[Any, InErr, InElem, InDone0, InErr, InElem, InDone] =
      ZChannel.readWithCause(
        (in: InElem) => ZChannel.write(in) *> reader,
        (err: Cause[InErr]) => ZChannel.refailCause(err),
        (done0: InDone0) => ZChannel.succeedNow(f(done0))
      )

    reader >>> self
  }

  /**
   * Returns a new channel which is the same as this one but applies the given
   * function to the input channel's error value.
   */
  final def contramapError[InErr0](
    f: InErr0 => InErr
  )(implicit trace: Trace): ZChannel[Env, InErr0, InElem, InDone, OutErr, OutElem, OutDone] = {
    lazy val reader: ZChannel[Any, InErr0, InElem, InDone, InErr, InElem, InDone] =
      ZChannel.readWithCause(
        (in: InElem) => ZChannel.write(in) *> reader,
        (err0: Cause[InErr0]) => ZChannel.refailCause(err0.map(f)),
        (done: InDone) => ZChannel.succeedNow(done)
      )

    reader >>> self
  }

  /**
   * Returns a new channel which is the same as this one but applies the given
   * ZIO function to the input channel's error value.
   */
  final def contramapErrorZIO[InErr0, Env1 <: Env](
    f: InErr0 => ZIO[Env1, InErr, InDone]
  )(implicit trace: Trace): ZChannel[Env1, InErr0, InElem, InDone, OutErr, OutElem, OutDone] = {
    lazy val reader: ZChannel[Env1, InErr0, InElem, InDone, InErr, InElem, InDone] =
      ZChannel.readWith(
        (in: InElem) => ZChannel.write(in) *> reader,
        (err0: InErr0) => ZChannel.fromZIO(f(err0)),
        (done: InDone) => ZChannel.succeedNow(done)
      )

    reader >>> self
  }

  /**
   * Returns a new channel which is the same as this one but applies the given
   * function to the input channel's output elements
   */
  final def contramapIn[InElem0](
    f: InElem0 => InElem
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem0, InDone, OutErr, OutElem, OutDone] = {
    lazy val reader: ZChannel[Any, InErr, InElem0, InDone, InErr, InElem, InDone] =
      ZChannel.readWithCause(
        (in: InElem0) => ZChannel.write(f(in)) *> reader,
        (err: Cause[InErr]) => ZChannel.refailCause(err),
        (done: InDone) => ZChannel.succeedNow(done)
      )

    reader >>> self
  }

  /**
   * Returns a new channel which is the same as this one but applies the given
   * ZIO function to the input channel's output elements
   */
  final def contramapInZIO[InElem0, Env1 <: Env](
    f: InElem0 => ZIO[Env1, InErr, InElem]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem0, InDone, OutErr, OutElem, OutDone] = {
    lazy val reader: ZChannel[Env1, InErr, InElem0, InDone, InErr, InElem, InDone] =
      ZChannel.readWithCause(
        (in: InElem0) => ZChannel.fromZIO(f(in)).flatMap(ZChannel.write(_)) *> reader,
        (err: Cause[InErr]) => ZChannel.refailCause(err),
        (done: InDone) => ZChannel.succeedNow(done)
      )

    reader >>> self
  }

  /**
   * Returns a new channel which is the same as this one but applies the given
   * ZIO function to the input channel's done value.
   */
  final def contramapZIO[InDone0, Env1 <: Env](
    f: InDone0 => ZIO[Env1, InErr, InDone]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone0, OutErr, OutElem, OutDone] = {
    lazy val reader: ZChannel[Env1, InErr, InElem, InDone0, InErr, InElem, InDone] =
      ZChannel.readWithCause(
        (in: InElem) => ZChannel.write(in) *> reader,
        (err: Cause[InErr]) => ZChannel.refailCause(err),
        (done0: InDone0) => ZChannel.fromZIO(f(done0))
      )

    reader >>> self
  }

  /**
   * Returns a new channel, which is the same as this one, except that all the
   * outputs are collected and bundled into a tuple together with the terminal
   * value of this channel.
   *
   * As the channel returned from this channel collects all of this channel's
   * output into an in- memory chunk, it is not safe to call this method on
   * channels that output a large or unbounded number of values.
   */
  final def collectElements(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, OutErr, Nothing, (Chunk[OutElem], OutDone)] =
    ZChannel.suspend {
      val builder = ChunkBuilder.make[OutElem]()

      lazy val reader: ZChannel[Env, OutErr, OutElem, OutDone, OutErr, Nothing, OutDone] =
        ZChannel.readWithCause(
          (out: OutElem) => ZChannel.succeed(builder += out) *> reader,
          (e: Cause[OutErr]) => ZChannel.refailCause(e),
          (z: OutDone) => ZChannel.succeedNow(z)
        )

      (self pipeTo reader).flatMap(z => ZChannel.succeed((builder.result(), z)))
    }

  /**
   * Returns a new channel which reads all the elements from upstream's output
   * channel and ignores them, then terminates with the upstream result value.
   */
  final def drain(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, Nothing, OutDone] = {
    lazy val drainer: ZChannel[Env, OutErr, OutElem, OutDone, OutErr, Nothing, OutDone] =
      ZChannel.readWithCause(
        (_: OutElem) => drainer,
        (e: Cause[OutErr]) => ZChannel.refailCause(e),
        (z: OutDone) => ZChannel.succeedNow(z)
      )

    self.pipeTo(drainer)
  }

  /**
   * Returns a new channel which connects the given [[AsyncInputProducer]] as
   * this channel's input
   */
  private[zio] final def embedInput[InErr2, InElem2, InDone2](input: AsyncInputProducer[InErr2, InElem2, InDone2])(
    implicit
    noInputErrors: Any <:< InErr,
    noInputElements: Any <:< InElem,
    noInputDone: Any <:< InDone,
    trace: Trace
  ): ZChannel[Env, InErr2, InElem2, InDone2, OutErr, OutElem, OutDone] =
    ZChannel.Bridge(input, self.asInstanceOf[ZChannel[Env, Any, Any, Any, OutErr, OutElem, OutDone]])

  /**
   * Returns a new channel, which is the same as this one, except it will be
   * interrupted when the specified effect completes. If the effect completes
   * successfully before the underlying channel is done, then the returned
   * channel will yield the success value of the effect as its terminal value.
   * On the other hand, if the underlying channel finishes first, then the
   * returned channel will yield the success value of the underlying channel as
   * its terminal value.
   */
  final def interruptWhen[Env1 <: Env, OutErr1 >: OutErr, OutDone1 >: OutDone](
    io: ZIO[Env1, OutErr1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem, OutDone1] =
    self.mergeWith(ZChannel.fromZIO(io))(
      ZChannel.MergeDecision.done,
      ZChannel.MergeDecision.done
    )

  /**
   * Returns a new channel, which is the same as this one, except it will be
   * interrupted when the specified promise is completed. If the promise is
   * completed before the underlying channel is done, then the returned channel
   * will yield the value of the promise. Otherwise, if the underlying channel
   * finishes first, then the returned channel will yield the value of the
   * underlying channel.
   */
  final def interruptWhen[OutErr1 >: OutErr, OutDone1 >: OutDone](
    promise: Promise[OutErr1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr1, OutElem, OutDone1] =
    interruptWhen(promise.await)

  /**
   * Returns a new channel that collects the output and terminal value of this
   * channel, which it then writes as output of the returned channel.
   */
  final def emitCollect(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, OutErr, (Chunk[OutElem], OutDone), Unit] =
    collectElements.flatMap(t => ZChannel.write(t))

  /**
   * Returns a new channel with an attached finalizer. The finalizer is
   * guaranteed to be executed so long as the channel begins execution (and
   * regardless of whether or not it completes).
   */
  final def ensuringWith[Env1 <: Env](
    finalizer: Exit[OutErr, OutDone] => URIO[Env1, Any]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ZChannel.Ensuring(self, finalizer)

  /**
   * Returns a new channel with an attached finalizer. The finalizer is
   * guaranteed to be executed so long as the channel begins execution (and
   * regardless of whether or not it completes).
   */
  final def ensuring[Env1 <: Env](
    finalizer: => URIO[Env1, Any]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ensuringWith(_ => finalizer)

  /**
   * Returns a new channel, which sequentially combines this channel, together
   * with the provided factory function, which creates a second channel based on
   * the terminal value of this channel. The result is a channel that will first
   * perform the functions of this channel, before performing the functions of
   * the created channel (including yielding its terminal value).
   */
  final def flatMap[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    f: OutDone => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2] =
    ZChannel.Fold(self, new ZChannel.Fold.K(f, ZChannel.Fold.failCauseIdentity[OutErr1]))

  /**
   * Returns a new channel, which flattens the terminal value of this channel.
   * This function may only be called if the terminal value of this channel is
   * another channel of compatible types.
   */
  final def flatten[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](implicit
    ev: OutDone <:< ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2] =
    self.flatMap(ev)

  /**
   * Folds over the result of this channel
   */
  final def foldChannel[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1,
    OutElem1 >: OutElem,
    OutDone1
  ](
    onErr: OutErr => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone1],
    onSucc: OutDone => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone1] =
    foldCauseChannel(
      _.failureOrCause match {
        case Left(err)    => onErr(err)
        case Right(cause) => ZChannel.refailCause(cause)
      },
      onSucc
    )

  /**
   * Folds over the result of this channel including any cause of termination
   */
  final def foldCauseChannel[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1,
    OutElem1 >: OutElem,
    OutDone1
  ](
    onErr: Cause[OutErr] => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone1],
    onSucc: OutDone => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone1] =
    ZChannel.Fold(self, new ZChannel.Fold.K(onSucc, onErr))

  /**
   * Returns a new channel that will perform the operations of this one, until
   * failure, and then it will switch over to the operations of the specified
   * fallback channel.
   */
  final def orElse[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr2,
    OutElem1 >: OutElem,
    OutDone1 >: OutDone
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone1]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone1] =
    self.catchAll(_ => that)

  /**
   * Returns a new channel, which is the same as this one, except the terminal
   * value of the returned channel is created by applying the specified function
   * to the terminal value of this channel.
   */
  final def map[OutDone2](f: OutDone => OutDone2)(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone2] =
    self.flatMap(z => ZChannel.succeed(f(z)))

  /**
   * Returns a new channel, which is the same as this one, except the failure
   * value of the returned channel is created by applying the specified function
   * to the failure value of this channel.
   */
  final def mapError[OutErr2](f: OutErr => OutErr2)(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem, OutDone] =
    mapErrorCause(_.map(f))

  /**
   * A more powerful version of [[mapError]] which also surfaces the [[Cause]]
   * of the channel failure
   */
  final def mapErrorCause[OutErr2](
    f: Cause[OutErr] => Cause[OutErr2]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem, OutDone] = {
    lazy val mapErrorCause: ZChannel[Env, OutErr, OutElem, OutDone, OutErr2, OutElem, OutDone] =
      ZChannel.readWithCause(
        chunk => ZChannel.write(chunk) *> mapErrorCause,
        cause => ZChannel.refailCause(f(cause)),
        done => ZChannel.succeedNow(done)
      )

    self >>> mapErrorCause
  }

  /**
   * Returns a new channel, which is the same as this one, except the failure
   * value of the returned channel is created by applying the specified function
   * to the failure value of this channel.
   */
  final def mapErrorZIO[Env1 <: Env, OutErr2](
    f: OutErr => URIO[Env1, OutErr2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr2, OutElem, OutDone] = {
    lazy val mapErrorZIO: ZChannel[Env1, OutErr, OutElem, OutDone, OutErr2, OutElem, OutDone] =
      ZChannel.readWith(
        chunk => ZChannel.write(chunk) *> mapErrorZIO,
        error => ZChannel.fromZIO(f(error).flip),
        done => ZChannel.succeedNow(done)
      )

    self >>> mapErrorZIO
  }

  /**
   * Returns a new channel, which is the same as this one, except the terminal
   * value of the returned channel is created by applying the specified
   * effectful function to the terminal value of this channel.
   */
  final def mapZIO[Env1 <: Env, OutErr1 >: OutErr, OutDone2](
    f: OutDone => ZIO[Env1, OutErr1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem, OutDone2] =
    self.flatMap(z => ZChannel.fromZIO(f(z)))

  /**
   * Creates a channel that is like this channel but the given function gets
   * applied to each emitted output element
   */
  final def mapOut[OutElem2](
    f: OutElem => OutElem2
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone] = {
    lazy val reader: ZChannel[Env, OutErr, OutElem, OutDone, OutErr, OutElem2, OutDone] =
      ZChannel.readWithCause(
        out => ZChannel.write(f(out)) *> reader,
        (e: Cause[OutErr]) => ZChannel.refailCause(e),
        (z: OutDone) => ZChannel.succeedNow(z)
      )

    self >>> reader
  }

  /**
   * Creates a channel that is like this channel but the given ZIO function gets
   * applied to each emitted output element
   */
  final def mapOutZIO[Env1 <: Env, OutErr1 >: OutErr, OutElem2](
    f: OutElem => ZIO[Env1, OutErr1, OutElem2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem2, OutDone] = {
    lazy val reader: ZChannel[Env1, OutErr, OutElem, OutDone, OutErr1, OutElem2, OutDone] =
      ZChannel.readWithCause(
        (out: OutElem) => ZChannel.fromZIO(f(out)).flatMap(ZChannel.write(_)) *> reader,
        (e: Cause[OutErr1]) => ZChannel.refailCause(e),
        (z: OutDone) => ZChannel.succeedNow(z)
      )

    self >>> reader
  }

  /**
   * Creates a channel that is like this channel but the given ZIO function gets
   * applied to each emitted output element, taking `n` elements at once and
   * mapping them in parallel
   */
  final def mapOutZIOPar[Env1 <: Env, OutErr1 >: OutErr, OutElem2](n: Int)(
    f: OutElem => ZIO[Env1, OutErr1, OutElem2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem2, OutDone] =
    mapOutZIOPar[Env1, OutErr1, OutElem2](n, 16)(f)

  /**
   * Creates a channel that is like this channel but the given ZIO function gets
   * applied to each emitted output element, taking `n` elements at once and
   * mapping them in parallel
   * @param n
   *   The maximum number of elements to map in parallel
   * @param bufferSize
   *   Number of elements that can be buffered downstream
   */
  final def mapOutZIOPar[Env1 <: Env, OutErr1 >: OutErr, OutElem2](n: Int, bufferSize: Int = 16)(
    f: OutElem => ZIO[Env1, OutErr1, OutElem2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem2, OutDone] =
    ZChannel.unwrapScopedWith { scope =>
      for {
        input       <- SingleProducerAsyncInput.make[InErr, InElem, InDone]
        queueReader  = ZChannel.fromInput(input)
        outgoing    <- Queue.bounded[Fiber[Either[Unit, OutDone], OutElem2]](bufferSize)
        _           <- scope.addFinalizer(outgoing.shutdown)
        errorSignal <- Promise.make[Nothing, Unit]
        permits     <- Semaphore.make(n.toLong)
        failure      = Ref.unsafe.make[Cause[OutErr1]](Cause.empty)(Unsafe)
        pull        <- (queueReader >>> self).toPullInAlt(scope)
        childScope  <- scope.fork
        fiberId     <- ZIO.fiberId
        _ <-
          pull.flatMap { outElem =>
            val latch = Promise.unsafe.make[Nothing, Unit](fiberId)(Unsafe)
            for {
              f <- permits
                     .withPermit(
                       latch.succeed(()) *> f(outElem)
                         .catchAllCause(cause =>
                           failure.update(_ && cause).unless(cause.isInterruptedOnly) *>
                             errorSignal.succeed(()) *>
                             ZChannel.failLeftUnit
                         )
                     )
                     .interruptible
                     .forkIn(childScope)
              _ <- latch.await
              _ <- outgoing.offer(f)
            } yield ()
          }.forever.interruptible
            .onError(_.failureOrCause match {
              case Left(x: Left[OutErr, OutDone]) =>
                failure.update(_ && Cause.fail(x.value)) *>
                  outgoing.offer(Fiber.done(ZChannel.failLeftUnit))
              case Left(x: Right[OutErr, OutDone]) =>
                permits.withPermits(n.toLong)(ZIO.unit).interruptible *>
                  outgoing.offer(Fiber.fail(x.asInstanceOf[Either[Unit, OutDone]]))
              case Right(cause) =>
                failure.update(_ && cause).unless(cause.isInterruptedOnly) *>
                  outgoing.offer(Fiber.done(ZChannel.failLeftUnit))
            })
            .ignore
            .raceFirst(ZChannel.awaitErrorSignal(childScope, fiberId)(errorSignal))
            .forkIn(scope)
      } yield {
        lazy val writer: ZChannel[Env1, Any, Any, Any, OutErr1, OutElem2, OutDone] =
          ZChannel.unwrap[Env1, Any, Any, Any, OutErr1, OutElem2, OutDone] {
            outgoing.take.flatMap(_.await).map {
              case s: Exit.Success[OutElem2] => ZChannel.write(s.value) *> writer
              case f: Exit.Failure[Either[Unit, OutDone]] =>
                val failure0 = failure.unsafe.get(Unsafe)
                f.cause.failureOrCause match {
                  case Left(_: Left[Unit, OutDone]) => ZChannel.refailCause(failure0)
                  case Left(x: Right[Unit, OutDone]) =>
                    if (failure0 eq Cause.empty) ZChannel.succeedNow(x.value)
                    else ZChannel.refailCause(failure0)
                  case Right(c) if c.isInterruptedOnly => ZChannel.refailCause(failure0)
                  case Right(cause)                    => ZChannel.refailCause(cause)
                }
            }
          }

        writer.embedInput(input)
      }
    }

  /**
   * Creates a channel that is like this channel but the given ZIO function gets
   * applied to each emitted output element, taking `n` elements at once and
   * mapping them in parallel. Order of elements downstream is not guaranteed to
   * be preserved.
   * @param n
   *   The maximum number of elements to map in parallel
   * @param bufferSize
   *   Number of elements that can be buffered downstream
   */
  final def mapOutZIOParUnordered[Env1 <: Env, OutErr1 >: OutErr, OutElem2](n: Int, bufferSize: Int = 16)(
    f: OutElem => ZIO[Env1, OutErr1, OutElem2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem2, OutDone] =
    ZChannel.unwrapScopedWith { scope =>
      for {
        input       <- SingleProducerAsyncInput.make[InErr, InElem, InDone]
        queueReader  = ZChannel.fromInput(input)
        outgoing    <- Queue.bounded[Exit[Either[Unit, OutDone], OutElem2]](bufferSize)
        _           <- scope.addFinalizer(outgoing.shutdown)
        errorSignal <- Promise.make[Nothing, Unit]
        permits     <- Semaphore.make(n.toLong)
        failure      = Ref.unsafe.make[Cause[OutErr1]](Cause.empty)(Unsafe)
        pull        <- (queueReader >>> self).toPullInAlt(scope)
        childScope  <- scope.fork
        fiberId     <- ZIO.fiberId
        _ <-
          pull.flatMap { outElem =>
            val latch = Promise.unsafe.make[Nothing, Unit](fiberId)(Unsafe)
            for {
              _ <- permits
                     .withPermit(
                       latch.succeed(()) *> f(outElem)
                         .foldCauseZIO(
                           cause =>
                             failure.update(_ && cause).unless(cause.isInterruptedOnly) *>
                               errorSignal.succeed(()) *>
                               outgoing.offer(ZChannel.failLeftUnit),
                           elem => outgoing.offer(Exit.succeed(elem))
                         )
                     )
                     .interruptible
                     .forkIn(childScope)
              _ <- latch.await
            } yield ()
          }.forever.interruptible
            .onError(_.failureOrCause match {
              case Left(x: Left[OutErr, OutDone]) =>
                failure.update(_ && Cause.fail(x.value)) *>
                  outgoing.offer(ZChannel.failLeftUnit)
              case Left(x: Right[OutErr, OutDone]) =>
                permits.withPermits(n.toLong)(ZIO.unit).interruptible *>
                  outgoing.offer(Exit.fail(x.asInstanceOf[Either[Unit, OutDone]]))
              case Right(cause) =>
                failure.update(_ && cause).unless(cause.isInterruptedOnly) *>
                  outgoing.offer(ZChannel.failLeftUnit)
            })
            .ignore
            .raceFirst(ZChannel.awaitErrorSignal(childScope, fiberId)(errorSignal))
            .forkIn(scope)
      } yield {
        lazy val writer: ZChannel[Env1, Any, Any, Any, OutErr1, OutElem2, OutDone] =
          ZChannel.unwrap[Env1, Any, Any, Any, OutErr1, OutElem2, OutDone] {
            outgoing.take.map {
              case s: Exit.Success[OutElem2] => ZChannel.write(s.value) *> writer
              case f: Exit.Failure[Either[Unit, OutDone]] =>
                val failure0 = failure.unsafe.get(Unsafe)
                f.cause.failureOrCause match {
                  case Left(_: Left[Unit, OutDone]) => ZChannel.refailCause(failure0)
                  case Left(x: Right[Unit, OutDone]) =>
                    if (failure0 eq Cause.empty) ZChannel.succeedNow(x.value)
                    else ZChannel.refailCause(failure0)
                  case Right(c) if c.isInterruptedOnly => ZChannel.refailCause(failure0)
                  case Right(cause)                    => ZChannel.refailCause(cause)
                }
            }
          }

        writer.embedInput(input)
      }
    }

  /**
   * Returns a new channel which creates a new channel for each emitted element
   * and merges some of them together. Different merge strategies control what
   * happens if there are more than the given maximum number of channels gets
   * created. See [[ZChannel.mergeAll]].
   * @param n
   *   The maximum number of channels to merge
   * @param bufferSize
   *   Number of elements that can be buffered from upstream for the merging
   * @param mergeStrategy
   *   Merge strategy, either back pressure or sliding.
   * @param f
   *   The function that creates a new channel from each emitted element
   */
  final def mergeMap[Env1 <: Env, InErr1 <: InErr, InElem1 <: InElem, InDone1 <: InDone, OutErr1 >: OutErr, OutElem2](
    n: => Int,
    bufferSize: => Int = 16,
    mergeStrategy: => ZChannel.MergeStrategy = ZChannel.MergeStrategy.BackPressure
  )(
    f: OutElem => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any] =
    ZChannel.mergeAll(self.mapOut(f), n, bufferSize, mergeStrategy)

  /**
   * Returns a new channel which merges a number of channels emitted by this
   * channel using the back pressuring merge strategy. See [[ZChannel.mergeAll]]
   */
  final def mergeOut[Env1 <: Env, InErr1 <: InErr, InElem1 <: InElem, InDone1 <: InDone, OutErr1 >: OutErr, OutElem2](
    n: => Int
  )(implicit
    ev: OutElem <:< ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, Any] =
    ZChannel.mergeAll(self.mapOut(ev), n)

  /**
   * Returns a new channel which merges a number of channels emitted by this
   * channel using the back pressuring merge strategy and uses a given function
   * to merge each completed subchannel's result value. See
   * [[ZChannel.mergeAll]]
   */
  final def mergeOutWith[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem2,
    OutDone1 >: OutDone
  ](n: => Int)(f: (OutDone1, OutDone1) => OutDone1)(implicit
    ev: OutElem <:< ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, OutDone1],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem2, OutDone1] =
    ZChannel.mergeAllWith(self.mapOut(ev), n)(f)

  /**
   * Returns a new channel, which is the merge of this channel and the specified
   * channel, where the behavior of the returned channel on left or right early
   * termination is decided by the specified `leftDone` and `rightDone` merge
   * decisions.
   */
  final def mergeWith[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr2,
    OutErr3,
    OutElem1 >: OutElem,
    OutDone2,
    OutDone3
  ](that: ZChannel[Env1, InErr1, InElem1, InDone1, OutErr2, OutElem1, OutDone2])(
    leftDone: Exit[OutErr, OutDone] => ZChannel.MergeDecision[Env1, OutErr2, OutDone2, OutErr3, OutDone3],
    rightDone: Exit[OutErr2, OutDone2] => ZChannel.MergeDecision[Env1, OutErr, OutDone, OutErr3, OutDone3]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr3, OutElem1, OutDone3] = {
    type MergeState = ZChannel.MergeState[Env1, OutErr, OutErr2, OutErr3, OutElem1, OutDone, OutDone2, OutDone3]
    import ZChannel.MergeState._

    def m(scope: Scope) =
      for {
        input      <- SingleProducerAsyncInput.make[InErr1, InElem1, InDone1]
        queueReader = ZChannel.fromInput(input)
        pullL      <- (queueReader >>> self).toPullIn(scope)
        pullR      <- (queueReader >>> that).toPullIn(scope)
      } yield {
        def handleSide[Err, Done, Err2, Done2](
          exit: Exit[Err, Either[Done, OutElem1]],
          fiber: Fiber.Runtime[Err2, Either[Done2, OutElem1]],
          pull: ZIO[Env1, Err, Either[Done, OutElem1]]
        )(
          done: Exit[Err, Done] => ZChannel.MergeDecision[Env1, Err2, Done2, OutErr3, OutDone3],
          both: (
            Fiber.Runtime[Err, Either[Done, OutElem1]],
            Fiber.Runtime[Err2, Either[Done2, OutElem1]]
          ) => MergeState,
          single: (Exit[Err2, Done2] => ZIO[Env1, OutErr3, OutDone3]) => MergeState
        ): ZIO[Env1, Nothing, ZChannel[Env1, Any, Any, Any, OutErr3, OutElem1, OutDone3]] = {
          def onDecision(
            decision: ZChannel.MergeDecision[Env1, Err2, Done2, OutErr3, OutDone3]
          ): ZIO[Any, Nothing, ZChannel[Env1, Any, Any, Any, OutErr3, OutElem1, OutDone3]] =
            decision match {
              case ZChannel.MergeDecision.Done(zio) =>
                ZIO.succeed(ZChannel.fromZIO(fiber.interrupt *> zio))
              case ZChannel.MergeDecision.Await(f) =>
                fiber.await.map {
                  case Exit.Success(Right(elem)) =>
                    ZChannel.write(elem) *> go(single(f))
                  case Exit.Success(Left(z)) =>
                    ZChannel.fromZIO(f(Exit.succeed(z)))
                  case Exit.Failure(cause) =>
                    ZChannel.fromZIO(f(Exit.failCause(cause)))
                }
            }

          exit match {
            case Exit.Success(Right(elem)) =>
              ZIO.succeed {
                ZChannel.write(elem) *> ZChannel.fromZIO(pull.forkIn(scope)).flatMap { leftFiber =>
                  go(both(leftFiber, fiber))
                }
              }

            case Exit.Success(Left(z)) =>
              onDecision(done(Exit.succeed(z)))

            case Exit.Failure(failure) =>
              onDecision(done(Exit.failCause(failure)))
          }
        }

        def go(state: MergeState): ZChannel[Env1, Any, Any, Any, OutErr3, OutElem1, OutDone3] =
          state match {
            case BothRunning(leftFiber, rightFiber, preferLeft) =>
              val lj: ZIO[Env1, OutErr, Either[OutDone, OutElem1]]   = leftFiber.join.interruptible
              val rj: ZIO[Env1, OutErr2, Either[OutDone2, OutElem1]] = rightFiber.join.interruptible

              ZChannel.unwrap {
                (leftFiber.unsafe.poll(Unsafe.unsafe), rightFiber.unsafe.poll(Unsafe.unsafe)) match {
                  case (Some(leftEx), opt) if preferLeft || opt.isEmpty =>
                    handleSide(leftEx, rightFiber, pullL)(
                      leftDone,
                      BothRunning(_, _, false),
                      LeftDone(_)
                    )
                  case (_, Some(rightEx)) =>
                    handleSide(rightEx, leftFiber, pullR)(
                      rightDone,
                      (l, r) => BothRunning(r, l, true),
                      RightDone(_)
                    )
                  case _ =>
                    lj.raceWith(rj)(
                      (leftEx, rf) =>
                        rf.interrupt *>
                          handleSide(leftEx, rightFiber, pullL)(
                            leftDone,
                            BothRunning(_, _, false),
                            LeftDone(_)
                          ),
                      (rightEx, lf) =>
                        lf.interrupt *>
                          handleSide(rightEx, leftFiber, pullR)(
                            rightDone,
                            (l, r) => BothRunning(r, l, true),
                            RightDone(_)
                          )
                    )
                }
              }

            case LeftDone(f) =>
              ZChannel.unwrap {
                pullR.exit.map {
                  case Exit.Success(Right(elem)) => ZChannel.write(elem) *> go(LeftDone(f))
                  case Exit.Success(Left(z)) =>
                    ZChannel.fromZIO(f(Exit.succeed(z)))
                  case Exit.Failure(cause) =>
                    ZChannel.fromZIO(f(Exit.failCause(cause)))
                }
              }

            case RightDone(f) =>
              ZChannel.unwrap {
                pullL.exit.map {
                  case Exit.Success(Right(elem)) => ZChannel.write(elem) *> go(RightDone(f))
                  case Exit.Success(Left(z)) =>
                    ZChannel.fromZIO(f(Exit.succeed(z)))
                  case Exit.Failure(cause) =>
                    ZChannel.fromZIO(f(Exit.failCause(cause)))
                }
              }
          }

        ZChannel.fromZIO {
          ZIO.transplant[Env1, Nothing, MergeState] { graft =>
            val fL = graft.applyOnExit(pullL).forkIn(scope)
            val fR = graft.applyOnExit(pullR).forkIn(scope)
            fL.zipWith(fR)(BothRunning(_, _, true))
          }
        }
          .flatMap(go)
          .embedInput(input)
      }

    ZChannel.unwrapScopedWith(m)
  }

  /** Returns a channel that never completes */
  @deprecated("use ZChannel.never", "3.0.0")
  final def never(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Nothing, Nothing] =
    ZChannel.fromZIO(ZIO.never)

  /**
   * Translates channel failure into death of the fiber, making all failures
   * unchecked and not a part of the type of the channel.
   */
  final def orDie(implicit
    ev: OutErr <:< Throwable,
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, Nothing, OutElem, OutDone] =
    orDieWith(ev)

  /**
   * Keeps none of the errors, and terminates the fiber with them, using the
   * specified function to convert the `OutErr` into a `Throwable`.
   */
  final def orDieWith(f: OutErr => Throwable)(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, Nothing, OutElem, OutDone] =
    self.catchAll(e => throw f(e))

  /**
   * Returns a new channel that pipes the output of this channel into the
   * specified channel. The returned channel has the input type of this channel,
   * and the output type of the specified channel, terminating with the value of
   * the specified channel.
   *
   * Same as the symbolic operator [[>>>]]
   */
  final def pipeTo[Env1 <: Env, OutErr2, OutElem2, OutDone2](
    that: => ZChannel[Env1, OutErr, OutElem, OutDone, OutErr2, OutElem2, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr2, OutElem2, OutDone2] =
    ZChannel.PipeTo(() => self, () => that)

  /**
   * Returns a new channel that pipes the output of this channel into the
   * specified channel and preserves this channel's failures without providing
   * them to the other channel for observation.
   */
  final def pipeToOrFail[Env1 <: Env, OutErr1 >: OutErr, OutElem2, OutDone2](
    that: => ZChannel[Env1, Nothing, OutElem, OutDone, OutErr1, OutElem2, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr1, OutElem2, OutDone2] =
    ZChannel.suspend {

      class ChannelFailure(val err: Cause[OutErr1]) extends Throwable(null, null, true, false) {
        override def getMessage: String = err.unified.headOption.fold("<unknown>")(_.message)

        override def getStackTrace(): Array[StackTraceElement] =
          err.unified.headOption.fold[Chunk[StackTraceElement]](Chunk.empty)(_.trace).toArray

        override def getCause(): Throwable =
          err.find { case Cause.Die(throwable, _) => throwable }
            .orElse(err.find { case Cause.Fail(value: Throwable, _) => value })
            .orNull

        def fillSuppressed()(implicit unsafe: Unsafe): Unit =
          if (getSuppressed().length == 0) {
            err.unified.iterator.drop(1).foreach(unified => addSuppressed(unified.toThrowable))
          }

        override def toString =
          err.prettyPrint
      }
      var channelFailure: ChannelFailure = null

      lazy val reader: ZChannel[Env, OutErr, OutElem, OutDone, Nothing, OutElem, OutDone] =
        ZChannel.readWithCause(
          elem => ZChannel.write(elem) *> reader,
          err => {
            channelFailure = new ChannelFailure(err)
            ZChannel.refailCause(Cause.die(channelFailure))
          },
          done => ZChannel.succeedNow(done)
        )

      lazy val writer: ZChannel[Env1, OutErr1, OutElem2, OutDone2, OutErr1, OutElem2, OutDone2] =
        ZChannel.readWithCause(
          elem => ZChannel.write(elem) *> writer,
          cause =>
            ZChannel.refailCause(
              if (channelFailure eq null)
                cause
              else
                cause.fold[Cause[OutErr1]](
                  Cause.empty,
                  (e, st) => Cause.fail(e, st),
                  (t, st) =>
                    t match {
                      case t: ChannelFailure if t == channelFailure =>
                        t.err
                      case t => Cause.die(t, st)
                    },
                  (fid, st) => Cause.interrupt(fid, st)
                )(_ ++ _, _ && _, (cause, _) => cause)
            ),
          done => ZChannel.succeedNow(done)
        )

      self >>> reader >>> that >>> writer
    }

  /**
   * Provides the channel with its required environment, which eliminates its
   * dependency on `Env`.
   */
  final def provideEnvironment(env: => ZEnvironment[Env])(implicit
    trace: Trace
  ): ZChannel[Any, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ZChannel.Provide(() => env, self)

  /**
   * Provides a layer to the channel, which translates it to another level.
   */
  final def provideLayer[OutErr1 >: OutErr, Env0](
    layer: => ZLayer[Env0, OutErr1, Env]
  )(implicit trace: Trace): ZChannel[Env0, InErr, InElem, InDone, OutErr1, OutElem, OutDone] =
    ZChannel.unwrapScopedWith(scope => layer.build(scope).map(env => self.provideEnvironment(env)))

  /**
   * Transforms the environment being provided to the channel with the specified
   * function.
   */
  final def provideSomeEnvironment[Env0](
    f: ZEnvironment[Env0] => ZEnvironment[Env]
  )(implicit trace: Trace): ZChannel[Env0, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ZChannel.environmentWithChannel[Env0] { env =>
      self.provideEnvironment(f(env))
    }

  /**
   * Splits the environment into two parts, providing one part using the
   * specified layer and leaving the remainder `Env0`.
   */
  final def provideSomeLayer[Env0]
    : ZChannel.ProvideSomeLayer[Env0, Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    new ZChannel.ProvideSomeLayer[Env0, Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](self)

  /** Creates a channel which repeatedly runs this channel */
  final def repeated(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Nothing] = {
    lazy val loop: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Nothing] = self *> loop
    loop
  }

  /**
   * Run the channel until it finishes with a done value or fails with an error.
   * The channel must not read any input or write any output.
   */
  final def run(implicit ev1: Any <:< InElem, ev2: OutElem <:< Nothing, trace: Trace): ZIO[Env, OutErr, OutDone] =
    ZIO.scopedWith(scope => self.runIn(scope))

  /**
   * Run the channel until it finishes with a done value or fails with an error
   * and collects its emitted output elements.
   *
   * The channel must not read any input.
   */
  final def runCollect(implicit ev1: Any <:< InElem, trace: Trace): ZIO[Env, OutErr, (Chunk[OutElem], OutDone)] =
    collectElements.run

  /**
   * Run the channel until it finishes with a done value or fails with an error
   * and ignores its emitted output elements.
   *
   * The channel must not read any input.
   */
  final def runDrain(implicit ev1: Any <:< InElem, trace: Trace): ZIO[Env, OutErr, OutDone] =
    self.drain.run

  final def runIn(
    scope: => Scope
  )(implicit ev1: Any <:< InElem, ev2: OutElem <:< Nothing, trace: Trace): ZIO[Env, OutErr, OutDone] = {
    def run(channelPromise: Promise[OutErr, OutDone], scopePromise: Promise[Nothing, Unit], scope: Scope) = ZIO
      .acquireReleaseExitWith(
        ZIO.succeed(
          new ChannelExecutor[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
            () => self,
            null,
            executeCloseLastSubstream = identity[URIO[Env, Any]]
          )
        )
      ) { (exec, exit: Exit[OutErr, OutDone]) =>
        val finalize = exec.close(exit)
        if (finalize ne null) finalize.tapErrorCause(cause => scope.addFinalizer(Exit.failCause(cause)))
        else ZIO.unit
      } { exec =>
        ZIO.suspendSucceed {
          def interpret(channelState: ChannelExecutor.ChannelState[Env, OutErr]): ZIO[Env, OutErr, OutDone] =
            channelState match {
              case ChannelState.Effect(zio) =>
                zio *> interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]])
              case ChannelState.Emit =>
                // Can't really happen because Out <:< Nothing. So just skip ahead.
                interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]])
              case ChannelState.Done =>
                exec.getDone
              case r @ ChannelState.Read(upstream, onEffect, onEmit, onDone) =>
                ChannelExecutor.readUpstream[Env, OutErr, OutErr, OutDone](
                  r.asInstanceOf[ChannelState.Read[Env, OutErr]],
                  () => interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]]),
                  Exit.failCause
                )
            }

          interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]])
            .intoPromise(channelPromise) *> channelPromise.await <* scopePromise.await
        }
      }

    ZIO.uninterruptibleMask { restore =>
      for {
        parent         <- ZIO.succeed(scope)
        child          <- parent.fork
        channelPromise <- Promise.make[OutErr, OutDone]
        scopePromise   <- Promise.make[Nothing, Unit]
        fiber          <- restore(run(channelPromise, scopePromise, child)).forkDaemon
        _ <- parent.addFinalizer {
               channelPromise.isDone.flatMap { isDone =>
                 if (isDone) scopePromise.succeed(()) *> fiber.await *> fiber.inheritAll
                 else scopePromise.succeed(()) *> fiber.interrupt *> fiber.inheritAll
               }
             }
        done <- restore(channelPromise.await)
      } yield done
    }
  }

  /**
   * Run the channel until it finishes with a done value or fails with an error.
   * The channel must not read any input or write any output.
   *
   * Closing the channel, which includes execution of all the finalizers
   * attached to the channel will be added to the current scope as a finalizer.
   */
  final def runScoped(implicit
    ev1: Any <:< InElem,
    ev2: OutElem <:< Nothing,
    trace: Trace
  ): ZIO[Env with Scope, OutErr, OutDone] =
    ZIO.scopeWith(scope => self.runIn(scope))

  /**
   * Creates a new channel which is like this channel but returns with the unit
   * value when succeeds
   */
  final def unit(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Unit] =
    self.as(())

  /**
   * Updates a service in the environment of this channel.
   */
  final def updateService[Service]
    : ZChannel.UpdateService[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone, Service] =
    new ZChannel.UpdateService[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone, Service](self)

  /**
   * Updates a service at the specified key in the environment of this channel.
   */
  final def updateServiceAt[Service]
    : ZChannel.UpdateServiceAt[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone, Service] =
    new ZChannel.UpdateServiceAt[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone, Service](self)

  /**
   * Returns in a scope a ZIO effect that can be used to repeatedly pull
   * elements from the channel. The pull effect fails with the channel's failure
   * in case the channel fails, or returns either the channel's done value or an
   * emitted element.
   */
  final def toPull(implicit trace: Trace): ZIO[Env with Scope, Nothing, ZIO[Env, OutErr, Either[OutDone, OutElem]]] =
    ZIO.scope.flatMap(scope => self.toPullIn(scope))

  final def toPullIn(
    scope: => Scope
  )(implicit trace: Trace): ZIO[Env, Nothing, ZIO[Env, OutErr, Either[OutDone, OutElem]]] =
    ZIO.uninterruptible {
      val exec = new ChannelExecutor[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
        () => self,
        null,
        identity[URIO[Env, Any]]
      )
      for {
        environment <- ZIO.environment[Env]
        scope       <- ZIO.succeed(scope)
        _ <- scope.addFinalizerExit { exit =>
               val finalizer = exec.close(exit)
               if (finalizer ne null) finalizer.provideEnvironment(environment)
               else ZIO.unit
             }
      } yield exec
    }.map { exec =>
      def interpret(
        channelState: ChannelExecutor.ChannelState[Env, OutErr]
      ): ZIO[Env, OutErr, Either[OutDone, OutElem]] =
        channelState match {
          case ChannelState.Done =>
            exec.getDone match {
              case Exit.Success(done)  => ZIO.succeed(Left(done))
              case Exit.Failure(cause) => Exit.failCause(cause)
            }
          case ChannelState.Emit =>
            ZIO.succeed(Right(exec.getEmit))
          case ChannelState.Effect(zio) =>
            zio *> interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]])
          case r @ ChannelState.Read(upstream, onEffect, onEmit, onDone) =>
            ChannelExecutor.readUpstream[Env, OutErr, OutErr, Either[OutDone, OutElem]](
              r.asInstanceOf[ChannelState.Read[Env, OutErr]],
              () => interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]]),
              Exit.failCause
            )
        }

      ZIO.suspendSucceed(interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]]))
    }

  final def toPullInAlt(
    scope: => Scope
  )(implicit trace: Trace): ZIO[Env, Nothing, ZIO[Env, Either[OutErr, OutDone], OutElem]] =
    ZIO.uninterruptible {
      val exec = new ChannelExecutor[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
        () => self,
        null,
        ZIO.identityFn[URIO[Env, Any]]
      )
      ZIO.environmentWithZIO[Env] { environment =>
        scope.addFinalizerExit { exit =>
          val finalizer = exec.close(exit)
          if (finalizer ne null) finalizer.provideEnvironment(environment)
          else ZIO.unit
        }
      } *> Exit.succeed(exec)
    }.map { exec =>
      def interpret(
        channelState: ChannelExecutor.ChannelState[Env, OutErr]
      ): ZIO[Env, Either[OutErr, OutDone], OutElem] =
        channelState match {
          case ChannelState.Done =>
            exec.getDone match {
              case s: Exit.Success[OutDone] => Exit.fail(Right(s.value))
              case f: Exit.Failure[OutErr]  => Exit.failCause(f.cause.map(Left(_)))
            }
          case ChannelState.Emit =>
            Exit.succeed(exec.getEmit)
          case ChannelState.Effect(zio) =>
            zio.mapError(Left(_)) *> interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]])
          case r @ ChannelState.Read(upstream, onEffect, onEmit, onDone) =>
            ChannelExecutor.readUpstream[Env, OutErr, Either[OutErr, OutDone], OutElem](
              r.asInstanceOf[ChannelState.Read[Env, OutErr]],
              () => interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]]),
              Exit.failCause(_).mapError(Left(_))
            )
        }

      ZIO.suspendSucceed(interpret(exec.run().asInstanceOf[ChannelState[Env, OutErr]]))
    }

  /** Converts this channel to a [[ZPipeline]] */
  final def toPipeline[In, Out](implicit
    In: Chunk[In] <:< InElem,
    Out: OutElem <:< Chunk[Out],
    InDone: Any <:< InDone,
    trace: Trace
  ): ZPipeline[Env, OutErr, In, Out] =
    ZPipeline.fromChannel[Env, OutErr, In, Out](
      self.asInstanceOf[ZChannel[Env, Nothing, Chunk[In], Any, OutErr, Chunk[Out], Any]]
    )

  /** Converts this channel to a [[ZSink]] */
  final def toSink[In, Out](implicit
    In: Chunk[In] <:< InElem,
    Out: OutElem <:< Chunk[Out],
    InDone: Any <:< InDone,
    trace: Trace
  ): ZSink[Env, OutErr, In, Out, OutDone] =
    ZSink.fromChannel[Env, OutErr, In, Out, OutDone](
      self.asInstanceOf[ZChannel[Env, Nothing, Chunk[In], Any, OutErr, Chunk[Out], OutDone]]
    )

  /** Converts this channel to a [[ZStream]] */
  final def toStream[Out](implicit
    Out: OutElem <:< Chunk[Out],
    InErr: Any <:< InErr,
    InElem: Any <:< InElem,
    InDone: Any <:< InDone,
    trace: Trace
  ): ZStream[Env, OutErr, Out] =
    ZStream.fromChannel[Env, OutErr, Out](
      self.asInstanceOf[ZChannel[Env, Any, Any, Any, OutErr, Chunk[Out], Any]]
    )

  /**
   * Creates a new channel which first runs this channel and if it succeeds runs
   * the other channel and finally finishes with the zipped done value of both
   * channels
   */
  final def zip[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit
    zippable: Zippable[OutDone, OutDone2],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, zippable.Out] =
    self.flatMap(l => that.map(r => zippable.zip(l, r)))

  /**
   * Creates a new channel which first runs this channel and if it succeeds runs
   * the other channel and finally finishes with the done value of the first
   * channel
   */
  final def zipLeft[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone] =
    (self zip that).map(_._1)

  /**
   * Creates a new channel which runs in parallel this and the other channel and
   * when both succeeds finishes with a tuple of both channel's done value
   */
  final def zipPar[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit
    zippable: Zippable[OutDone, OutDone2],
    trace: Trace
  ): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, zippable.Out] =
    self.mergeWith(that)(
      exit1 => ZChannel.MergeDecision.Await[Env1, OutErr1, OutDone2, OutErr1, zippable.Out](exit2 => exit1.zip(exit2)),
      exit2 => ZChannel.MergeDecision.Await[Env1, OutErr1, OutDone, OutErr1, zippable.Out](exit1 => exit1.zip(exit2))
    )

  /**
   * Creates a new channel which runs in parallel this and the other channel and
   * when both succeeds finishes with the first one's done value
   */
  final def zipParLeft[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone] =
    (self zipPar that).map(_._1)

  /**
   * Creates a new channel which runs in parallel this and the other channel and
   * when both succeeds finishes with the second one's done value
   */
  def zipParRight[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2] =
    (self zipPar that).map(_._2)

  /**
   * Creates a new channel which first runs this channel and if it succeeds runs
   * the other channel and finally finishes with the done value of the second
   * channel
   */
  final def zipRight[
    Env1 <: Env,
    InErr1 <: InErr,
    InElem1 <: InElem,
    InDone1 <: InDone,
    OutErr1 >: OutErr,
    OutElem1 >: OutElem,
    OutDone2
  ](
    that: => ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2]
  )(implicit trace: Trace): ZChannel[Env1, InErr1, InElem1, InDone1, OutErr1, OutElem1, OutDone2] =
    (self zip that).map(_._2)
}

object ZChannel {
  private val failLeftUnit = Exit.fail(Left(()))
  private val failUnit     = Exit.failCause(Cause.unit)

  private[zio] final case class PipeTo[
    Env,
    InErr,
    InElem,
    InDone,
    OutErr,
    OutErr2,
    OutElem,
    OutElem2,
    OutDone,
    OutDone2
  ](
    left: () => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    right: () => ZChannel[Env, OutErr, OutElem, OutDone, OutErr2, OutElem2, OutDone2]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem2, OutDone2]

  private[zio] final case class Read[Env, InErr, InElem, InDone, OutErr, OutErr2, OutElem, OutElem2, OutDone, OutDone2](
    more: InElem => ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem2, OutDone2],
    done: Fold.K[Env, InErr, InElem, InDone, OutErr, OutErr2, OutElem2, OutDone, OutDone2]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem2, OutDone2]
  private[zio] final case class SucceedNow[OutDone](terminal: OutDone)
      extends ZChannel[Any, Any, Any, Any, Nothing, Nothing, OutDone]
  private[zio] final case class Fail[OutErr](error: () => Cause[OutErr])
      extends ZChannel[Any, Any, Any, Any, OutErr, Nothing, Nothing]
  private[zio] final case class FromZIO[Env, OutErr, OutDone](zio: () => ZIO[Env, OutErr, OutDone])
      extends ZChannel[Env, Any, Any, Any, OutErr, Nothing, OutDone]
  private[zio] final case class Emit[OutElem](out: OutElem) extends ZChannel[Any, Any, Any, Any, Nothing, OutElem, Unit]
  private[zio] final case class Succeed[OutDone](effect: () => OutDone)
      extends ZChannel[Any, Any, Any, Any, Nothing, Nothing, OutDone]
  private[zio] final case class Suspend[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    effect: () => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  private[zio] final case class Ensuring[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channel: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    finalizer: Exit[OutErr, OutDone] => ZIO[Env, Nothing, Any]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  private[zio] final case class ConcatAll[
    Env,
    InErr,
    InElem,
    InDone,
    OutErr,
    OutElem,
    OutElem2,
    OutDone,
    OutDone2,
    OutDone3
  ](
    combineInners: (OutDone, OutDone) => OutDone,
    combineAll: (OutDone, OutDone2) => OutDone3,
    value: () => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone2],
    k: OutElem => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone3]
  private[zio] final case class Fold[Env, InErr, InElem, InDone, OutErr, OutErr2, OutElem, OutDone, OutDone2](
    value: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    k: Fold.K[Env, InErr, InElem, InDone, OutErr, OutErr2, OutElem, OutDone, OutDone2]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem, OutDone2]
  object Fold {
    sealed abstract class Continuation[-Env, -InErr, -InElem, -InDone, OutErr, +OutErr2, +OutElem, OutDone, +OutDone2]
    private[zio] final case class K[-Env, -InErr, -InElem, -InDone, OutErr, +OutErr2, +OutElem, OutDone, +OutDone2](
      onSuccess: OutDone => ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem, OutDone2],
      onHalt: Cause[OutErr] => ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem, OutDone2]
    ) extends Continuation[Env, InErr, InElem, InDone, OutErr, OutErr2, OutElem, OutDone, OutDone2] {
      def onExit(exit: Exit[OutErr, OutDone]): ZChannel[Env, InErr, InElem, InDone, OutErr2, OutElem, OutDone2] =
        exit match {
          case Exit.Success(value) => onSuccess(value)
          case Exit.Failure(cause) => onHalt(cause)
        }
    }
    private[zio] final case class Finalizer[Env, OutErr, OutDone](finalizer: Exit[OutErr, OutDone] => URIO[Env, Any])
        extends Continuation[Env, Any, Any, Any, OutErr, Nothing, Nothing, OutDone, Nothing]

    private[this] def SuccessIdentity(implicit
      trace: Trace
    ): Any => ZChannel[Any, Any, Any, Any, Nothing, Nothing, Any] =
      ZChannel.succeedNow(_)

    private[zio] def successIdentity[Z](implicit trace: Trace): Z => ZChannel[Any, Any, Any, Any, Nothing, Nothing, Z] =
      SuccessIdentity.asInstanceOf[Z => ZChannel[Any, Any, Any, Any, Nothing, Nothing, Z]]

    private[this] def FailCauseIdentity(implicit
      trace: Trace
    ): Cause[Any] => ZChannel[Any, Any, Any, Any, Any, Nothing, Nothing] =
      ZChannel.refailCause

    private[zio] def failCauseIdentity[E](implicit
      trace: Trace
    ): Cause[E] => ZChannel[Any, Any, Any, Any, E, Nothing, Nothing] =
      FailCauseIdentity.asInstanceOf[Cause[E] => ZChannel[Any, Any, Any, Any, E, Nothing, Nothing]]
  }

  private[zio] final case class Bridge[-Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone](
    input: AsyncInputProducer[InErr, InElem, InDone],
    channel: ZChannel[Env, Any, Any, Any, OutErr, OutElem, OutDone]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]

  private[zio] final case class DeferedUpstream[-Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone](
    mkChannel: ZChannel[Any, Any, Any, Any, InErr, InElem, InDone] => ZChannel[
      Env,
      Any,
      Any,
      Any,
      OutErr,
      OutElem,
      OutDone
    ]
  ) extends ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]

  private[zio] final case class BracketOut[R, E, Z](
    acquire: () => ZIO[R, E, Z],
    finalizer: (Z, Exit[Any, Any]) => URIO[R, Any]
  ) extends ZChannel[R, Any, Any, Any, E, Z, Unit]

  private[zio] final case class Provide[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    environment: () => ZEnvironment[Env],
    inner: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  ) extends ZChannel[Any, InErr, InElem, InDone, OutErr, OutElem, OutDone]

  def acquireReleaseOutWith[Env, OutErr, Acquired](acquire: => ZIO[Env, OutErr, Acquired])(
    release: Acquired => URIO[Env, Any]
  )(implicit trace: Trace): ZChannel[Env, Any, Any, Any, OutErr, Acquired, Unit] =
    acquireReleaseOutExitWith(acquire)((z, _) => release(z))

  def acquireReleaseOutExitWith[Env, OutErr, Acquired](acquire: => ZIO[Env, OutErr, Acquired])(
    release: (Acquired, Exit[Any, Any]) => URIO[Env, Any]
  )(implicit trace: Trace): ZChannel[Env, Any, Any, Any, OutErr, Acquired, Unit] =
    BracketOut(() => acquire, release)

  def acquireReleaseWith[Env, InErr, InElem, InDone, OutErr, Acquired, OutElem2, OutDone](
    acquire: => ZIO[Env, OutErr, Acquired]
  )(release: Acquired => URIO[Env, Any])(
    use: Acquired => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone] =
    acquireReleaseExitWith[Env, InErr, InElem, InDone, OutErr, Acquired, OutElem2, OutDone](acquire)((a, _) =>
      release(a)
    )(use)

  def acquireReleaseExitWith[Env, InErr, InElem, InDone, OutErr, Acquired, OutElem2, OutDone](
    acquire: => ZIO[Env, OutErr, Acquired]
  )(release: (Acquired, Exit[OutErr, OutDone]) => URIO[Env, Any])(
    use: Acquired => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem2, OutDone] =
    fromZIO(Ref.make[Exit[OutErr, OutDone] => URIO[Env, Any]](_ => ZIO.unit)).flatMap { ref =>
      fromZIO(acquire.tap(a => ref.set(release(a, _))).uninterruptible)
        .flatMap(use)
        .ensuringWith(ex => ref.get.flatMap(_.apply(ex)))
    }

  private def awaitErrorSignal(
    scope: Scope.Closeable,
    fiberId: FiberId
  )(
    signal: Promise[Nothing, Unit]
  )(implicit trace: Trace): UIO[Unit] =
    signal.await.interruptible.onExit {
      case _: Exit.Success[?] => scope.close(Exit.interrupt(fiberId))
      case _                  => Exit.unit
    }

  /**
   * Creates a channel backed by a buffer. When the buffer is empty, the channel
   * will simply passthrough its input as output. However, when the buffer is
   * non-empty, the value inside the buffer will be passed along as output.
   */
  def buffer[InErr, InElem, InDone](
    empty: => InElem,
    isEmpty: InElem => Boolean,
    ref: => Ref[InElem]
  )(implicit trace: Trace): ZChannel[Any, InErr, InElem, InDone, InErr, InElem, InDone] =
    ZChannel.suspend {
      def buffer(
        empty: InElem,
        isEmpty: InElem => Boolean,
        ref: Ref[InElem]
      ): ZChannel[Any, InErr, InElem, InDone, InErr, InElem, InDone] =
        ZChannel.unwrap {
          ref.modify { v =>
            if (isEmpty(v))
              (
                ZChannel.readWithCause(
                  (in: InElem) => ZChannel.write(in) *> buffer(empty, isEmpty, ref),
                  (err: Cause[InErr]) => ZChannel.refailCause(err),
                  (done: InDone) => ZChannel.succeedNow(done)
                ),
                v
              )
            else
              (ZChannel.write(v) *> buffer(empty, isEmpty, ref), empty)
          }
        }

      buffer(empty, isEmpty, ref)
    }

  def bufferChunk[InErr, InElem, InDone](
    ref: => Ref[Chunk[InElem]]
  )(implicit trace: Trace): ZChannel[Any, InErr, Chunk[InElem], InDone, InErr, Chunk[InElem], InDone] =
    buffer[InErr, Chunk[InElem], InDone](Chunk.empty, _.isEmpty, ref)

  def concatAll[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channels: => ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Any],
      Any
    ]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Any] =
    concatAllWith(channels)((_, _) => (), (_, _) => ())

  def concatAllWith[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone, OutDone2, OutDone3](
    channels: => ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
      OutDone2
    ]
  )(
    f: (OutDone, OutDone) => OutDone,
    g: (OutDone, OutDone2) => OutDone3
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone3] =
    ConcatAll(
      f,
      g,
      () => channels,
      (channel: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]) => channel
    )

  def succeedNow[Z](result: Z)(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Nothing, Z] =
    SucceedNow(result)

  def succeedWith[R, Z](f: ZEnvironment[R] => Z)(implicit
    trace: Trace
  ): ZChannel[R, Any, Any, Any, Nothing, Nothing, Z] =
    ZChannel.fromZIO(ZIO.environmentWith[R](f))

  def write[Out](out: Out)(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Out, Unit] =
    Emit(out)

  def writeAll[Out](outs: Out*)(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Out, Unit] =
    writeChunk(Chunk.fromIterable(outs))

  def writeChunk[Out](
    outs: Chunk[Out]
  )(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Out, Unit] = {
    def writer(idx: Int, len: Int): ZChannel[Any, Any, Any, Any, Nothing, Out, Unit] =
      if (idx == len) ZChannel.unit
      else ZChannel.write(outs(idx)) *> writer(idx + 1, len)

    writer(0, outs.size)
  }

  /**
   * Accesses the whole environment of the channel.
   */
  def environment[Env](implicit
    trace: Trace
  ): ZChannel[Env, Any, Any, Any, Nothing, Nothing, ZEnvironment[Env]] =
    ZChannel.fromZIO(ZIO.environment)

  /**
   * Accesses the environment of the channel.
   */
  def environmentWith[Env]: ZChannel.EnvironmentWithPartiallyApplied[Env] =
    new ZChannel.EnvironmentWithPartiallyApplied[Env]

  /**
   * Accesses the environment of the channel in the context of a channel.
   */
  def environmentWithChannel[Env]: ZChannel.EnvironmentWithChannelPartiallyApplied[Env] =
    new ZChannel.EnvironmentWithChannelPartiallyApplied[Env]

  /**
   * Accesses the environment of the channel in the context of an effect.
   */
  def environmentWithZIO[Env]: ZChannel.EnvironmentWithZIOPartiallyApplied[Env] =
    new ZChannel.EnvironmentWithZIOPartiallyApplied[Env]

  def fail[E](e: => E)(implicit trace: Trace): ZChannel[Any, Any, Any, Any, E, Nothing, Nothing] =
    failCause(Cause.fail(e))

  def fromEither[E, A](either: => Either[E, A])(implicit
    trace: Trace
  ): ZChannel[Any, Any, Any, Any, E, Nothing, A] =
    ZChannel.suspend(either.fold(ZChannel.fail(_), ZChannel.succeed(_)))

  def fromHub[Err, Done, Elem](
    hub: => Hub[Either[Exit[Err, Done], Elem]]
  )(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Err, Elem, Done] =
    ZChannel.unwrapScoped(hub.subscribe.map(fromQueue(_)))

  def fromHubScoped[Err, Done, Elem](
    hub: => Hub[Either[Exit[Err, Done], Elem]]
  )(implicit trace: Trace): ZIO[Scope, Nothing, ZChannel[Any, Any, Any, Any, Err, Elem, Done]] =
    hub.subscribe.map(fromQueue(_))

  def fromOption[A](option: => Option[A])(implicit
    trace: Trace
  ): ZChannel[Any, Any, Any, Any, None.type, Nothing, A] =
    ZChannel.suspend(
      option.fold[ZChannel[Any, Any, Any, Any, None.type, Nothing, A]](ZChannel.fail(None))(ZChannel.succeed(_))
    )

  def fromQueue[Err, Done, Elem](
    queue: => Dequeue[Either[Exit[Err, Done], Elem]]
  )(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Err, Elem, Done] =
    ZChannel.suspend {
      def fromQueue(queue: Dequeue[Either[Exit[Err, Done], Elem]]): ZChannel[Any, Any, Any, Any, Err, Elem, Done] =
        ZChannel.fromZIO(queue.take).flatMap {
          case Right(elem) => write(elem) *> fromQueue(queue)
          case Left(exit) =>
            exit.foldExit(
              failCause(_),
              succeedNow(_)
            )
        }

      fromQueue(queue)
    }

  def fromZIO[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZChannel[R, Any, Any, Any, E, Nothing, A] =
    FromZIO(() => zio)

  def failCause[E](cause: => Cause[E])(implicit
    trace: Trace
  ): ZChannel[Any, Any, Any, Any, E, Nothing, Nothing] =
    Fail(() => cause)

  def identity[Err, Elem, Done](implicit trace: Trace): ZChannel[Any, Err, Elem, Done, Err, Elem, Done] =
    readWithCause(
      (in: Elem) => write(in) *> identity[Err, Elem, Done],
      (err: Cause[Err]) => failCause(err),
      (done: Done) => succeedNow(done)
    )

  def interruptAs(fiberId: => FiberId)(implicit
    trace: Trace
  ): ZChannel[Any, Any, Any, Any, Nothing, Nothing, Nothing] =
    failCause(Cause.interrupt(fiberId))

  def scoped[R]: ScopedPartiallyApplied[R] =
    new ScopedPartiallyApplied[R]

  def scopedWith[R, E, A](f: Scope => ZIO[R, E, A])(implicit trace: Trace): ZChannel[R, Any, Any, Any, E, A, Any] =
    ZChannel.unwrapScoped(ZIO.scope.map(scope => ZChannel.fromZIO(f(scope)).flatMap(ZChannel.write)))

  def mergeAll[Env, InErr, InElem, InDone, OutErr, OutElem](
    channels: => ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Any],
      Any
    ],
    n: => Int,
    bufferSize: => Int = 16,
    mergeStrategy: => MergeStrategy = MergeStrategy.BackPressure
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Any] =
    mergeAllWith(channels, n, bufferSize, mergeStrategy)((_, _) => ())

  def mergeAllUnbounded[Env, InErr, InElem, InDone, OutErr, OutElem](
    channels: => ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Any],
      Any
    ]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, Any] =
    mergeAll(channels, Int.MaxValue)

  def mergeAllUnboundedWith[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channels: => ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
      OutDone
    ]
  )(f: (OutDone, OutDone) => OutDone)(implicit
    trace: Trace
  ): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    mergeAllWith(channels, Int.MaxValue)(f)

  def mergeAllWith[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channels: ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
      OutDone
    ],
    n: => Int,
    bufferSize: => Int = 16,
    mergeStrategy: => MergeStrategy = MergeStrategy.BackPressure
  )(
    f: (OutDone, OutDone) => OutDone
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    unwrapScopedWith { scope =>
      for {
        input         <- SingleProducerAsyncInput.make[InErr, InElem, InDone]
        incoming       = ZChannel.fromInput(input)
        n             <- ZIO.succeed(n)
        bufferSize    <- ZIO.succeed(bufferSize)
        mergeStrategy <- ZIO.succeed(mergeStrategy)
        outgoing      <- Queue.bounded[ZIO[Env, Either[OutErr, OutDone], OutElem]](bufferSize)
        _             <- scope.addFinalizer(outgoing.shutdown)
        cancelers     <- Queue.unbounded[Promise[Nothing, Unit]]
        _             <- scope.addFinalizer(cancelers.shutdown)
        lastDone      <- Ref.make[Option[OutDone]](None)
        errorSignal   <- Promise.make[Nothing, Unit]
        permits       <- Semaphore.make(n.toLong)
        pull          <- (incoming >>> channels).toPullInAlt(scope)
        childScope    <- scope.fork
        fiberId       <- ZIO.fiberId
        evaluatePull = (pull: ZIO[Env, Either[OutErr, OutDone], OutElem]) =>
                         pull
                           .flatMap(outElem => outgoing.offer(Exit.succeed(outElem)))
                           .forever
                           .catchAllCause(cause =>
                             cause.failureOrCause match {
                               case Left(_: Left[OutErr, OutDone]) =>
                                 outgoing.offer(Exit.failCause(cause)) *> errorSignal.succeed(())
                               case Left(x: Right[OutErr, OutDone]) =>
                                 lastDone.update {
                                   case Some(lastDone) => Some(f(lastDone, x.value))
                                   case None           => Some(x.value)
                                 }
                               case Right(cause) =>
                                 outgoing.offer(Exit.failCause(cause)) *> errorSignal.succeed(())
                             }
                           )
        pullStrategy = mergeStrategy match {
                         case MergeStrategy.BackPressure =>
                           (fiberId: FiberId) =>
                             pull.flatMap { channel =>
                               val latch = Promise.unsafe.make[Nothing, Unit](fiberId)(Unsafe.unsafe)
                               val raceIOs =
                                 ZIO.scopedWith { scope =>
                                   (incoming >>> channel)
                                     .toPullInAlt(scope)
                                     .flatMap(evaluatePull)
                                 }
                               for {
                                 _ <- permits
                                        .withPermit(latch.succeed(()) *> raceIOs)
                                        .interruptible
                                        .forkIn(childScope)
                                 _ <- latch.await
                               } yield ()
                             }
                         case MergeStrategy.BufferSliding =>
                           (fiberId: FiberId) =>
                             pull.flatMap { channel =>
                               val canceler = Promise.unsafe.make[Nothing, Unit](fiberId)(Unsafe.unsafe)
                               val latch    = Promise.unsafe.make[Nothing, Unit](fiberId)(Unsafe.unsafe)
                               for {
                                 size <- cancelers.size
                                 _    <- ZIO.when(size >= n)(cancelers.take.flatMap(_.succeed(())))
                                 _    <- cancelers.offer(canceler)
                                 raceIOs =
                                   ZIO.scopedWith { scope =>
                                     (incoming >>> channel)
                                       .toPullInAlt(scope)
                                       .flatMap(evaluatePull(_).race(canceler.await.interruptible))
                                   }
                                 _ <- permits
                                        .withPermit(latch.succeed(()) *> raceIOs)
                                        .interruptible
                                        .forkIn(childScope)
                                 _ <- latch.await
                               } yield ()
                             }
                       }
        _ <- pullStrategy(fiberId).forever
               .catchAllCause(cause =>
                 cause.failureOrCause match {
                   case Left(_: Left[OutErr, OutDone]) => outgoing.offer(Exit.failCause(cause))
                   case Left(x: Right[OutErr, OutDone]) =>
                     permits.withPermits(n.toLong)(ZIO.unit).interruptible *>
                       lastDone.get.flatMap {
                         case Some(lastDone) => outgoing.offer(Exit.fail(Right(f(lastDone, x.value))))
                         case None           => outgoing.offer(Exit.fail(Right(x.value)))
                       }
                   case Right(cause) => outgoing.offer(Exit.failCause(cause.map(Left(_))))
                 }
               )
               .raceFirst(awaitErrorSignal(childScope, fiberId)(errorSignal))
               .forkIn(scope)
      } yield {
        lazy val consumer: ZChannel[Env, Any, Any, Any, OutErr, OutElem, OutDone] =
          unwrap[Env, Any, Any, Any, OutErr, OutElem, OutDone] {
            outgoing.take.flatten.foldCause(
              cause =>
                cause.failureOrCause match {
                  case Left(Left(outErr))   => ZChannel.fail(outErr)
                  case Left(Right(outDone)) => ZChannel.succeedNow(outDone)
                  case Right(cause)         => ZChannel.refailCause(cause)
                },
              outElem => ZChannel.write(outElem) *> consumer
            )
          }

        consumer.embedInput(input)
      }
    }

  @deprecated("use mergeAllWith with `MergeStrategy`", "2.1.7")
  def mergeAllWith[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channels: ZChannel[
      Env,
      InErr,
      InElem,
      InDone,
      OutErr,
      ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
      OutDone
    ],
    n: => Int,
    bufferSize: => Int /* = 16*/,
    mergeStrategy: MergeStrategy.BackPressure.type
  )(
    f: (OutDone, OutDone) => OutDone
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    mergeAllWith(channels, n, bufferSize, mergeStrategy: MergeStrategy)(f)

  /** Returns a channel that never completes */
  final def never(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Nothing, Nothing] =
    ZChannel.fromZIO(ZIO.never)

  def provideLayer[Env0, Env, Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone](layer: ZLayer[Env0, OutErr, Env])(
    channel: => ZChannel[Env with Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  )(implicit
    ev: EnvironmentTag[Env],
    tag: EnvironmentTag[Env1],
    trace: Trace
  ): ZChannel[Env0 with Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ZChannel.suspend(channel.provideSomeLayer[Env0 with Env1](ZLayer.environment[Env1] ++ layer))

  def readWithCause[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    in: InElem => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    halt: Cause[InErr] => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    done: InDone => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    Read[Env, InErr, InElem, InDone, InErr, OutErr, OutElem, OutElem, InDone, OutDone](in, new Fold.K(done, halt))

  def readWith[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    in: InElem => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    error: InErr => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
    done: InDone => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    readWithCause(in, (c: Cause[InErr]) => c.failureOrCause.fold(error, ZChannel.refailCause(_)), done)

  def readOrFail[E, In](e: => E)(implicit trace: Trace): ZChannel[Any, Any, In, Any, E, Nothing, In] =
    Read[Any, Any, In, Any, Any, E, Nothing, Nothing, In, In](
      in => SucceedNow(in),
      new Fold.K((_: Any) => ZChannel.fail(e), (_: Any) => ZChannel.fail(e))
    )

  def read[In](implicit trace: Trace): ZChannel[Any, Any, In, Any, None.type, Nothing, In] =
    readOrFail(None)

  def refailCause[E](cause: Cause[E]): ZChannel[Any, Any, Any, Any, E, Nothing, Nothing] =
    Fail(() => cause)

  /**
   * Accesses the specified service in the environment of the channel.
   */
  def service[Service: Tag](implicit
    trace: Trace
  ): ZChannel[Service, Any, Any, Any, Nothing, Nothing, Service] =
    ZChannel.fromZIO(ZIO.service)

  /**
   * Accesses the service corresponding to the specified key in the environment.
   */
  def serviceAt[Service]: ZChannel.ServiceAtPartiallyApplied[Service] =
    new ZChannel.ServiceAtPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the channel.
   */
  def serviceWith[Service]: ZChannel.ServiceWithPartiallyApplied[Service] =
    new ZChannel.ServiceWithPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the channel in the
   * context of a channel.
   */
  def serviceWithChannel[Service]: ZChannel.ServiceWithChannelPartiallyApplied[Service] =
    new ZChannel.ServiceWithChannelPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the channel in the
   * context of an effect.
   */
  def serviceWithZIO[Service]: ZChannel.ServiceWithZIOPartiallyApplied[Service] =
    new ZChannel.ServiceWithZIOPartiallyApplied[Service]

  /** Creates a channel that succeeds immediately with the given value */
  def succeed[Z](z: => Z)(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Nothing, Nothing, Z] =
    Succeed(() => z)

  /**
   * Returns a lazily constructed channel.
   */
  def suspend[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channel: => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  ): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    Suspend(() => channel)

  /** Channel that succeeds with the unit value */
  val unit: ZChannel[Any, Any, Any, Any, Nothing, Nothing, Unit] =
    succeedNow(())(Trace.empty)

  def unwrap[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    channel: => ZIO[Env, OutErr, ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ZChannel.fromZIO(channel).flatten

  def unwrapScoped[Env]: UnwrapScopedPartiallyApplied[Env] =
    new UnwrapScopedPartiallyApplied[Env]

  def unwrapScopedWith[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
    f: Scope => ZIO[Env, OutErr, ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]]
  )(implicit trace: Trace): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
    ZChannel.concatAllWith(ZChannel.scopedWith(f))((d, _) => d, (d, _) => d)

  def toHub[Err, Done, Elem](
    hub: => Hub[Either[Exit[Err, Done], Elem]]
  )(implicit trace: Trace): ZChannel[Any, Err, Elem, Done, Nothing, Nothing, Any] =
    toQueue(hub)

  def toQueue[Err, Done, Elem](
    queue: => Enqueue[Either[Exit[Err, Done], Elem]]
  )(implicit trace: Trace): ZChannel[Any, Err, Elem, Done, Nothing, Nothing, Any] =
    ZChannel.suspend {
      def toQueue(
        queue: Enqueue[Either[Exit[Err, Done], Elem]]
      ): ZChannel[Any, Err, Elem, Done, Nothing, Nothing, Any] =
        ZChannel.readWithCause(
          (in: Elem) => ZChannel.fromZIO(queue.offer(Right(in))) *> toQueue(queue),
          (cause: Cause[Err]) => ZChannel.fromZIO(queue.offer(Left(Exit.failCause(cause)))),
          (done: Done) => ZChannel.fromZIO(queue.offer(Left(Exit.succeed(done))))
        )

      toQueue(queue)
    }

  private[zio] def fromInput[Err, Elem, Done](
    input: => AsyncInputConsumer[Err, Elem, Done]
  )(implicit trace: Trace): ZChannel[Any, Any, Any, Any, Err, Elem, Done] =
    ZChannel.suspend {
      def fromInput[Err, Elem, Done](
        input: => AsyncInputConsumer[Err, Elem, Done]
      ): ZChannel[Any, Any, Any, Any, Err, Elem, Done] =
        ZChannel.unwrap(
          input.takeWith(
            ZChannel.refailCause,
            ZChannel.write(_) *> fromInput(input),
            ZChannel.succeedNow(_)
          )
        )

      fromInput(input)
    }

  private[zio] sealed trait MergeState[Env, Err, Err1, Err2, Elem, Done, Done1, Done2]
  private[zio] object MergeState {
    case class BothRunning[Env, Err, Err1, Err2, Elem, Done, Done1, Done2](
      left: Fiber.Runtime[Err, Either[Done, Elem]],
      right: Fiber.Runtime[Err1, Either[Done1, Elem]],
      preferLeft: Boolean //to maintain fairness when polling fibers
    ) extends MergeState[Env, Err, Err1, Err2, Elem, Done, Done1, Done2]
    case class LeftDone[Env, Err, Err1, Err2, Elem, Done, Done1, Done2](
      f: Exit[Err1, Done1] => ZIO[Env, Err2, Done2]
    ) extends MergeState[Env, Err, Err1, Err2, Elem, Done, Done1, Done2]
    case class RightDone[Env, Err, Err1, Err2, Elem, Done, Done1, Done2](
      f: Exit[Err, Done] => ZIO[Env, Err2, Done2]
    ) extends MergeState[Env, Err, Err1, Err2, Elem, Done, Done1, Done2]
  }

  sealed abstract class MergeDecision[-R, -E0, -Z0, +E, +Z]
  object MergeDecision {
    case class Done[R, E, Z](zio: ZIO[R, E, Z])                        extends MergeDecision[R, Any, Any, E, Z]
    case class Await[R, E0, Z0, E, Z](f: Exit[E0, Z0] => ZIO[R, E, Z]) extends MergeDecision[R, E0, Z0, E, Z]

    def done[R, E, Z](zio: ZIO[R, E, Z]): MergeDecision[R, Any, Any, E, Z]                      = Done(zio)
    def await[R, E0, Z0, E, Z](f: Exit[E0, Z0] => ZIO[R, E, Z]): MergeDecision[R, E0, Z0, E, Z] = Await(f)
    def awaitConst[R, E, Z](zio: ZIO[R, E, Z]): MergeDecision[R, Any, Any, E, Z]                = Await(_ => zio)
  }

  sealed trait MergeStrategy
  object MergeStrategy {
    case object BackPressure  extends MergeStrategy
    case object BufferSliding extends MergeStrategy
  }

  final class EnvironmentWithPartiallyApplied[Env](private val dummy: Boolean = true) extends AnyVal {
    def apply[OutDone](f: ZEnvironment[Env] => OutDone)(implicit
      trace: Trace
    ): ZChannel[Env, Any, Any, Any, Nothing, Nothing, OutDone] =
      ZChannel.environment.map(f)
  }

  final class EnvironmentWithChannelPartiallyApplied[Env](private val dummy: Boolean = true) extends AnyVal {
    def apply[Env1 <: Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
      f: ZEnvironment[Env] => ZChannel[Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone]
    )(implicit trace: Trace): ZChannel[Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
      ZChannel.environment.flatMap(f)
  }

  final class EnvironmentWithZIOPartiallyApplied[Env](private val dummy: Boolean = true) extends AnyVal {
    def apply[Env1 <: Env, OutErr, OutDone](f: ZEnvironment[Env] => ZIO[Env1, OutErr, OutDone])(implicit
      trace: Trace
    ): ZChannel[Env1, Any, Any, Any, OutErr, Nothing, OutDone] =
      ZChannel.environment.mapZIO(f)
  }

  final class ProvideSomeLayer[Env0, -Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone](
    private val self: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  ) extends AnyVal {
    def apply[OutErr1 >: OutErr, Env1](
      layer: => ZLayer[Env0, OutErr1, Env1]
    )(implicit
      ev: Env0 with Env1 <:< Env,
      tagged: EnvironmentTag[Env1],
      trace: Trace
    ): ZChannel[Env0, InErr, InElem, InDone, OutErr1, OutElem, OutDone] =
      self
        .asInstanceOf[ZChannel[Env0 with Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone]]
        .provideLayer(ZLayer.environment[Env0] ++ layer)
  }

  final class ScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](
      zio: => ZIO[Scope with R, E, A]
    )(implicit trace: Trace): ZChannel[R, Any, Any, Any, E, A, Any] =
      ZChannel.unwrap {
        ZIO.uninterruptibleMask { restore =>
          Scope.make.map { scope =>
            acquireReleaseOutExitWith {
              restore(scope.extend[R](zio)).tapErrorCause(cause => scope.close(Exit.failCause(cause)))
            } { (_, exit) =>
              scope.close(exit)
            }
          }
        }
      }
  }

  final class ServiceAtPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Key](
      key: => Key
    )(implicit
      tag: EnvironmentTag[Map[Key, Service]],
      trace: Trace
    ): ZChannel[Map[Key, Service], Any, Any, Any, Nothing, Nothing, Option[Service]] =
      ZChannel.environmentWith(_.getAt(key))
  }

  final class ServiceWithPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[OutDone](f: Service => OutDone)(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZChannel[Service, Any, Any, Any, Nothing, Nothing, OutDone] =
      ZChannel.service[Service].map(f)
  }

  final class ServiceWithChannelPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Env <: Service, InErr, InElem, InDone, OutErr, OutElem, OutDone](
      f: Service => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
    )(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
      ZChannel.service[Service].flatMap(f)
  }

  final class ServiceWithZIOPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Env <: Service, OutErr, OutDone](f: Service => ZIO[Env, OutErr, OutDone])(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZChannel[Env, Any, Any, Any, OutErr, Nothing, OutDone] =
      ZChannel.service[Service].mapZIO(f)
  }

  final class UnwrapScopedPartiallyApplied[Env](private val dummy: Boolean = true) extends AnyVal {
    def apply[InErr, InElem, InDone, OutErr, OutElem, OutDone](
      channel: => ZIO[Scope with Env, OutErr, ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]]
    )(implicit
      trace: Trace
    ): ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
      ZChannel.concatAllWith(scoped[Env](channel))((d, _) => d, (d, _) => d)
  }

  final class UpdateService[-Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone, Service](
    private val self: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  ) extends AnyVal {
    def apply[Env1 <: Env with Service](
      f: Service => Service
    )(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZChannel[Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
      self.provideSomeEnvironment(_.update(f))
  }

  final class UpdateServiceAt[-Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone, Service](
    private val self: ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone]
  ) extends AnyVal {
    def apply[Env1 <: Env with Map[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit
      tag: Tag[Map[Key, Service]],
      trace: Trace
    ): ZChannel[Env1, InErr, InElem, InDone, OutErr, OutElem, OutDone] =
      self.provideSomeEnvironment(_.updateAt(key)(f))
  }
}
