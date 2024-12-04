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

package zio.stream

import zio._
import zio.internal.SingleThreadedRingBuffer
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.encoding.EncodingException
import zio.stream.internal.CharacterSet.{BOM, CharsetUtf32BE, CharsetUtf32LE}
import zio.stream.internal.SingleProducerAsyncInput

import java.nio.{Buffer, ByteBuffer, CharBuffer}
import java.nio.charset.{
  CharacterCodingException,
  Charset,
  CharsetDecoder,
  CoderResult,
  MalformedInputException,
  StandardCharsets,
  UnmappableCharacterException
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * A `ZPipeline[Env, Err, In, Out]` is a polymorphic stream transformer.
 * Pipelines accept a stream as input, and return the transformed stream as
 * output.
 *
 * Pipelines can be thought of as a recipe for calling a bunch of methods on a
 * source stream, to yield a new (transformed) stream. A nice mental model is
 * the following type alias:
 *
 * {{{
 * type ZPipeline[Env, Err, In, Out] = ZStream[Env, Err, In] => ZStream[Env, Err, Out]
 * }}}
 *
 * This encoding of a pipeline with a type alias is not used because it does not
 * infer well. In its place, this trait captures the polymorphism inherent to
 * many pipelines, which can therefore be more flexible about the environment
 * and error types of the streams they transform.
 *
 * There is no fundamental requirement for pipelines to exist, because
 * everything pipelines do can be done directly on a stream. However, because
 * pipelines separate the stream transformation from the source stream itself,
 * it becomes possible to abstract over stream transformations at the level of
 * values, creating, storing, and passing around reusable transformation
 * pipelines that can be applied to many different streams.
 *
 * The most common way to create a pipeline is to convert a sink into a pipeline
 * (in general, transforming elements of a stream requires the power of a sink).
 * However, the companion object has lots of other pipeline constructors based
 * on the methods of stream.
 */
final class ZPipeline[-Env, +Err, -In, +Out] private (
  val channel: ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any]
) {
  self =>

  /** Attach this pipeline to the given stream */
  def apply[Env1 <: Env, Err1 >: Err](stream: => ZStream[Env1, Err1, In])(implicit
    trace: Trace
  ): ZStream[Env1, Err1, Out] =
    ZStream.suspend(stream).pipeThroughChannelOrFail(channel)

  /**
   * Composes two pipelines into one pipeline, by first applying the
   * transformation of this pipeline, and then applying the transformation of
   * the specified pipeline.
   */
  def >>>[Env1 <: Env, Err1 >: Err, Out2](
    that: => ZPipeline[Env1, Err1, Out, Out2]
  )(implicit trace: Trace): ZPipeline[Env1, Err1, In, Out2] =
    new ZPipeline(self.channel.pipeToOrFail(that.channel))

  /**
   * Compose this pipeline with a sink, resulting in a sink that processes
   * elements by piping them through this pipeline and piping the results into
   * the sink.
   */
  def >>>[Env1 <: Env, Err1 >: Err, Leftover, Out2](that: => ZSink[Env1, Err1, Out, Leftover, Out2])(implicit
    trace: Trace
  ): ZSink[Env1, Err1, In, Leftover, Out2] =
    ZSink.fromChannel(self.channel.pipeToOrFail(that.channel))

  /**
   * Composes two pipelines into one pipeline, by first applying the
   * transformation of the specified pipeline, and then applying the
   * transformation of this pipeline.
   */
  def <<<[Env1 <: Env, Err1 >: Err, In2](
    that: => ZPipeline[Env1, Err1, In2, In]
  )(implicit trace: Trace): ZPipeline[Env1, Err1, In2, Out] =
    ZPipeline.suspend(new ZPipeline(that.channel.pipeToOrFail(self.channel)))

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
  def aggregateAsync[Env1 <: Env, Err1 >: Err, Out1 >: Out, Out2](sink: ZSink[Env1, Err1, Out1, Out1, Out2])(implicit
    trace: Trace
  ): ZPipeline[Env1, Err1, In, Out2] =
    self >>> ZPipeline.aggregateAsync(sink)

  /**
   * Like `aggregateAsyncWithinEither`, but only returns the `Right` results.
   */
  def aggregateAsyncWithin[Env1 <: Env, Err1 >: Err, Out1 >: Out, Out2](
    sink: ZSink[Env1, Err1, Out1, Out1, Out2],
    schedule: Schedule[Env1, Option[Out2], Any]
  )(implicit trace: Trace): ZPipeline[Env1, Err1, In, Out2] =
    self >>> ZPipeline.aggregateAsyncWithin(sink, schedule)

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
   */
  def aggregateAsyncWithinEither[Env1 <: Env, Err1 >: Err, Out1 >: Out, Out2, Out3](
    sink: ZSink[Env1, Err1, Out1, Out1, Out2],
    schedule: Schedule[Env1, Option[Out2], Out3]
  )(implicit trace: Trace): ZPipeline[Env1, Err1, In, Either[Out3, Out2]] =
    self >>> ZPipeline.aggregateAsyncWithinEither(sink, schedule)

  /**
   * A named version of the `>>>` operator.
   */
  def andThen[Env1 <: Env, Err1 >: Err, Out2](
    that: => ZPipeline[Env1, Err1, Out, Out2]
  )(implicit trace: Trace): ZPipeline[Env1, Err1, In, Out2] =
    self >>> that

  /**
   * Returns a new pipeline that only emits elements that are not equal to the
   * previous element emitted, using natural equality to determine whether two
   * elements are equal.
   */
  def changes(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.changes

  /**
   * Returns a new pipeline that only emits elements that are not equal to the
   * previous element emitted, using the specified function to determine whether
   * two elements are equal.
   */
  def changesWith(f: (Out, Out) => Boolean)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.changesWith(f)

  /**
   * Returns a new pipeline that only emits elements that are not equal to the
   * previous element emitted, using the specified effectual function to
   * determine whether two elements are equal.
   */
  def changesWithZIO(f: (Out, Out) => UIO[Boolean])(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.changesWithZIO(f)

  /**
   * Exposes the underlying chunks of the stream as a stream of chunks of
   * elements.
   */
  def chunks(implicit trace: Trace): ZPipeline[Env, Err, In, Chunk[Out]] =
    self >>> ZPipeline.mapChunks(Chunk.single[Chunk[Out]])

  /**
   * Performs a filter and map in a single step.
   */
  def collect[Out2](pf: PartialFunction[Out, Out2])(implicit trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.collect(pf)

  /**
   * Filters any `Right` values.
   */
  def collectLeft[A, B](implicit ev: Out <:< Either[A, B], trace: Trace): ZPipeline[Env, Err, In, A] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Either[A, B]]] >>> ZPipeline.collectLeft[Err, A, B]

  /**
   * Filters any 'None' values.
   */
  def collectSome[Out2](implicit ev: Out <:< Option[Out2], trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Option[Out2]]] >>> ZPipeline.collectSome[Err, Out2]

  /**
   * Filters any `Exit.Failure` values.
   */
  def collectSuccess[Out2, L1](implicit
    ev: Out <:< Exit[L1, Out2],
    trace: Trace
  ): ZPipeline[Env, Err, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Exit[L1, Out2]]] >>> ZPipeline.collectSuccess

  /**
   * Filters any `Left` values.
   */
  def collectRight[A, B](implicit ev: Out <:< Either[A, B], trace: Trace): ZPipeline[Env, Err, In, B] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Either[A, B]]] >>> ZPipeline.collectRight[Err, A, B]

  /**
   * Transforms all elements of the pipeline for as long as the specified
   * partial function is defined.
   */
  def collectWhile[Out2](pf: PartialFunction[Out, Out2])(implicit trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.collectWhile(pf)

  /**
   * Terminates the pipeline when encountering the first `Right`.
   */
  def collectWhileLeft[A, B](implicit ev: Out <:< Either[A, B], trace: Trace): ZPipeline[Env, Err, In, A] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Either[A, B]]] >>> ZPipeline.collectWhileLeft

  /**
   * Terminates the pipeline when encountering the first `Left`.
   */
  def collectWhileRight[A, B](implicit ev: Out <:< Either[A, B], trace: Trace): ZPipeline[Env, Err, In, B] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Either[A, B]]] >>> ZPipeline.collectWhileRight

  /**
   * Terminates the pipeline when encountering the first `None`.
   */
  def collectWhileSome[Out2](implicit ev: Out <:< Option[Out2], trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Option[Out2]]] >>> ZPipeline.collectWhileSome

  /**
   * Terminates the pipeline when encountering the first `Exit.Failure`.
   */
  def collectWhileSuccess[Err2, Out2](implicit
    ev: Out <:< Exit[Err2, Out2],
    trace: Trace
  ): ZPipeline[Env, Err, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Exit[Err2, Out2]]] >>> ZPipeline.collectWhileSuccess

  /**
   * Effectfully transforms all elements of the pipeline for as long as the
   * specified partial function is defined.
   */
  def collectWhileZIO[Env2 <: Env, Err2 >: Err, Out2](pf: PartialFunction[Out, ZIO[Env2, Err2, Out2]])(implicit
    trace: Trace
  ): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.collectWhileZIO(pf)

  /**
   * A named version of the `<<<` operator.
   */
  def compose[Env1 <: Env, Err1 >: Err, In2](
    that: => ZPipeline[Env1, Err1, In2, In]
  )(implicit trace: Trace): ZPipeline[Env1, Err1, In2, Out] =
    self <<< that

  /**
   * Returns a new pipeline which is the same as this one but applies the given
   * function to the pipeline's input.
   */
  def contramap[In2](f: In2 => In)(implicit trace: Trace): ZPipeline[Env, Err, In2, Out] =
    ZPipeline.fromChannel(channel.contramapIn(_.map(f)))

  /**
   * Converts this pipeline to a pipeline that executes its effects but emits no
   * elements. Useful for sequencing effects using pipeline:
   *
   * {{{
   * (Stream(1, 2, 3).tap(i => ZIO(println(i))) ++
   *   (Stream.fromZIO(ZIO(println("Done!"))) >>> ZPipeline.drain) ++
   *   Stream(4, 5, 6).tap(i => ZIO(println(i)))).run(Sink.drain)
   * }}}
   */
  def drain(implicit trace: Trace): ZPipeline[Env, Err, In, Nothing] =
    self >>> ZPipeline.drain

  /**
   * Transforms the input and output types of this pipeline using the specified
   * functions.
   */
  def dimap[In2, Out2](f: In2 => In, g: Out => Out2)(implicit trace: Trace): ZPipeline[Env, Err, In2, Out2] =
    contramap(f).map(g)

  /**
   * Drops the specified number of elements from this stream.
   */
  def drop(n: => Int)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.drop(n)

  /**
   * Drops all elements of the pipeline until the specified predicate evaluates
   * to `true`.
   */
  def dropUntil(f: Out => Boolean)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.dropUntil(f)

  /**
   * Drops incoming elements until the effectful predicate `p` is satisfied.
   */
  def dropUntilZIO[Env1 <: Env, Err1 >: Err](f: Out => ZIO[Env1, Err1, Boolean])(implicit
    trace: Trace
  ): ZPipeline[Env1, Err1, In, Out] =
    self >>> ZPipeline.dropUntilZIO(f)

  /**
   * Drops the last specified number of elements from this pipeline.
   *
   * @note
   *   This combinator keeps `n` elements in memory. Be careful with big
   *   numbers.
   */
  def dropRight(n: => Int)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.dropRight(n)

  /**
   * Drops all elements of the pipeline for as long as the specified predicate
   * evaluates to `true`.
   */
  def dropWhile(f: Out => Boolean)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.dropWhile(f)

  /**
   * Drops incoming elements as long as the effectful predicate `p` is
   * satisfied.
   */
  def dropWhileZIO[Env1 <: Env, Err1 >: Err](f: Out => ZIO[Env1, Err1, Boolean])(implicit
    trace: Trace
  ): ZPipeline[Env1, Err1, In, Out] =
    self >>> ZPipeline.dropWhileZIO(f)

  /**
   * Filters the elements emitted by this pipeline using the provided function.
   */
  def filter(f: Out => Boolean)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.filter(f)

  /**
   * Effectfully filters the elements emitted by this pipeline.
   */
  def filterZIO[Env2 <: Env, Err2 >: Err](f: Out => ZIO[Env2, Err2, Boolean])(implicit
    trace: Trace
  ): ZPipeline[Env2, Err2, In, Out] =
    self >>> ZPipeline.filterZIO(f)

  /**
   * Submerges the chunks carried by this pipeline into the pipeline's
   * structure, while still preserving them.
   */
  def flattenChunks[Out2](implicit ev: Out <:< Chunk[Out2], trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Chunk[Out2]]] >>> ZPipeline.flattenChunks[Out2]

  /**
   * Flattens [[Exit]] values. `Exit.Failure` values translate to pipeline
   * failures while `Exit.Success` values translate to stream elements.
   */
  def flattenExit[Err2, Out2](implicit ev: Out <:< Exit[Err2, Out2], trace: Trace): ZPipeline[Env, Err2, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err2, In, Exit[Err2, Out2]]] >>> ZPipeline.flattenExit[Err2, Out2]

  /**
   * Submerges the iterables carried by this pipeline into the pipeline's
   * structure, while still preserving them.
   */
  def flattenIterables[Out2](implicit ev: Out <:< Iterable[Out2], trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err, In, Iterable[Out2]]] >>> ZPipeline.flattenIterables[Out2]

  /**
   * Flattens take values.
   */
  def flattenTake[Err1 >: Err, Out2](implicit
    ev: Out <:< Take[Err1, Out2],
    trace: Trace
  ): ZPipeline[Env, Err1, In, Out2] =
    self.asInstanceOf[ZPipeline[Env, Err1, In, Take[Err1, Out2]]] >>> ZPipeline.flattenTake

  /**
   * Partitions the pipeline with specified chunkSize
   *
   * @param chunkSize
   *   size of the chunk
   */
  def grouped(chunkSize: => Int)(implicit trace: Trace): ZPipeline[Env, Err, In, Chunk[Out]] =
    self >>> ZPipeline.grouped(chunkSize)

  /**
   * Partitions the stream with the specified chunkSize or until the specified
   * duration has passed, whichever is satisfied first.
   */
  def groupedWithin(chunkSize: => Int, within: => Duration)(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Chunk[Out]] =
    self >>> ZPipeline.groupedWithin(chunkSize, within)

  /**
   * Intersperse pipeline with provided element similar to
   * <code>List.mkString</code>.
   */
  def intersperse[Out2 >: Out](middle: => Out2)(implicit trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.intersperse(middle)

  /**
   * Intersperse and also add a prefix and a suffix
   */
  def intersperse[Out2 >: Out](start: => Out2, middle: => Out2, end: => Out2)(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.intersperse(start, middle, end)

  /**
   * Transforms the elements of this pipeline using the supplied function.
   */
  def map[Out2](f: Out => Out2)(implicit trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.map(f)

  /**
   * Statefully maps over the elements of this pipeline to produce new elements.
   */
  def mapAccum[State, Out2](
    s: => State
  )(f: (State, Out) => (State, Out2))(implicit trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.mapAccum(s)(f)

  /**
   * Statefully and effectfully maps over the elements of this pipeline to
   * produce new elements.
   */
  def mapAccumZIO[Env2 <: Env, Err2 >: Err, State, Out2](
    s: => State
  )(f: (State, Out) => ZIO[Env2, Err2, (State, Out2)])(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.mapAccumZIO(s)(f)

  /**
   * Transforms the chunks emitted by this stream.
   */
  def mapChunks[Out2](
    f: Chunk[Out] => Chunk[Out2]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out2] =
    self >>> ZPipeline.mapChunks(f)

  /**
   * Creates a pipeline that maps chunks of elements with the specified effect.
   */
  def mapChunksZIO[Env2 <: Env, Err2 >: Err, Out2](
    f: Chunk[Out] => ZIO[Env2, Err2, Chunk[Out2]]
  )(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.mapChunksZIO(f)

  /**
   * Creates a pipeline that maps elements with the specified function that
   * returns a stream.
   */
  def mapStream[Env2 <: Env, Err2 >: Err, Out2](
    f: Out => ZStream[Env2, Err2, Out2]
  )(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.mapStream(f)

  /**
   * Creates a pipeline that maps elements with the specified effectful
   * function.
   */
  def mapZIO[Env2 <: Env, Err2 >: Err, Out2](
    f: Out => ZIO[Env2, Err2, Out2]
  )(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.mapZIO(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   */
  def mapZIOPar[Env2 <: Env, Err2 >: Err, Out2](n: => Int)(f: Out => ZIO[Env2, Err2, Out2])(implicit
    trace: Trace
  ): ZPipeline[Env2, Err2, In, Out2] =
    mapZIOPar[Env2, Err2, Out2](n, 16)(f)

  def mapZIOPar[Env2 <: Env, Err2 >: Err, Out2](n: => Int, bufferSize: => Int = 16)(f: Out => ZIO[Env2, Err2, Out2])(
    implicit trace: Trace
  ): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.mapZIOPar(n, bufferSize)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  def mapZIOParUnordered[Env2 <: Env, Err2 >: Err, Out2](n: => Int)(f: Out => ZIO[Env2, Err2, Out2])(implicit
    trace: Trace
  ): ZPipeline[Env2, Err2, In, Out2] =
    mapZIOParUnordered[Env2, Err2, Out2](n, 16)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  def mapZIOParUnordered[Env2 <: Env, Err2 >: Err, Out2](n: => Int, bufferSize: => Int = 16)(
    f: Out => ZIO[Env2, Err2, Out2]
  )(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out2] =
    self >>> ZPipeline.mapZIOParUnordered(n, bufferSize)(f)

  /**
   * Transforms the errors emitted by this pipeline using `f`.
   */
  def mapError[Err2](
    f: Err => Err2
  )(implicit trace: Trace): ZPipeline[Env, Err2, In, Out] =
    new ZPipeline(self.channel.mapError(f))

  /**
   * A more powerful version of [[mapError]] which also surfaces the [[Cause]]
   * of the channel failure
   */
  def mapErrorCause[Err2](
    f: Cause[Err] => Cause[Err2]
  )(implicit trace: Trace): ZPipeline[Env, Err2, In, Out] =
    new ZPipeline(self.channel.mapErrorCause(f))

  /**
   * Transforms the errors emitted by this pipeline using `f`.
   */
  def mapErrorZIO[Env1 <: Env, Err2](
    f: Err => URIO[Env1, Err2]
  )(implicit trace: Trace): ZPipeline[Env1, Err2, In, Out] =
    new ZPipeline(self.channel.mapErrorZIO(f))

  /**
   * Translates pipeline failure into death of the fiber, making all failures
   * unchecked and not a part of the type of the effect.
   */
  def orDie(implicit
    ev: Err <:< Throwable,
    trace: Trace
  ): ZPipeline[Env, Nothing, In, Out] =
    orDieWith(ev)

  /**
   * Keeps none of the errors, and terminates the fiber with them, using the
   * specified function to convert the `E` into a `Throwable`.
   */
  def orDieWith(f: Err => Throwable)(implicit trace: Trace): ZPipeline[Env, Nothing, In, Out] =
    new ZPipeline(self.channel.orDieWith(f))

  /**
   * Takes the specified number of elements from this pipeline.
   */
  def take(n: => Long)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.take(n)

  /**
   * Takes all elements of the pipeline until the specified predicate evaluates
   * to `true`.
   */
  def takeUntil(f: Out => Boolean)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.takeUntil(f)

  /**
   * Takes all elements of the pipeline until the specified effectual predicate
   * evaluates to `true`.
   */
  def takeUntilZIO[Env1 <: Env, Err1 >: Err](f: Out => ZIO[Env1, Err1, Boolean])(implicit
    trace: Trace
  ): ZPipeline[Env1, Err1, In, Out] =
    self >>> ZPipeline.takeUntilZIO(f)

  /**
   * Takes all elements of the pipeline for as long as the specified predicate
   * evaluates to `true`.
   */
  def takeWhile(f: Out => Boolean)(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.takeWhile(f)

  /**
   * Takes all elements of the pipeline for as long as the specified effectual
   * predicate evaluates to `true`.
   */
  def takeWhileZIO[Env1 <: Env, Err1 >: Err](f: Out => ZIO[Env1, Err1, Boolean])(implicit
    trace: Trace
  ): ZPipeline[Env1, Err1, In, Out] =
    self >>> ZPipeline.takeWhileZIO(f)

  /**
   * Adds an effect to consumption of every element of the pipeline.
   */
  def tap[Env2 <: Env, Err2 >: Err](f: Out => ZIO[Env2, Err2, Any])(implicit
    trace: Trace
  ): ZPipeline[Env2, Err2, In, Out] =
    self >>> ZPipeline.tap(f)

  /**
   * Throttles the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` function.
   */
  def throttleEnforce(units: Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[Out] => Long
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.throttleEnforce(units, duration, burst)(costFn)

  /**
   * Throttles the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` effectful function.
   */
  def throttleEnforceZIO[Env2 <: Env, Err2 >: Err](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[Out] => ZIO[Env2, Err2, Long]
  )(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out] =
    self >>> ZPipeline.throttleEnforceZIO(units, duration, burst)(costFn)

  /**
   * Delays the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` function.
   */
  def throttleShape(units: => Long, duration: => Duration, burst: Long = 0)(
    costFn: Chunk[Out] => Long
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    self >>> ZPipeline.throttleShape(units, duration, burst)(costFn)

  /**
   * Delays the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` effectful function.
   */
  def throttleShapeZIO[Env2 <: Env, Err2 >: Err](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[Out] => ZIO[Env2, Err2, Long]
  )(implicit trace: Trace): ZPipeline[Env2, Err2, In, Out] =
    self >>> ZPipeline.throttleShapeZIO(units, duration, burst)(costFn)

  /** Converts this pipeline to its underlying channel */
  def toChannel: ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] =
    self.channel

  /**
   * Zips this pipeline together with the index of elements.
   */
  def zipWithIndex(implicit trace: Trace): ZPipeline[Env, Err, In, (Out, Long)] =
    self >>> ZPipeline.zipWithIndex

  def zipWithNext(implicit trace: Trace): ZPipeline[Env, Err, In, (Out, Option[Out])] =
    self >>> ZPipeline.zipWithNext

  def zipWithPrevious(implicit trace: Trace): ZPipeline[Env, Err, In, (Option[Out], Out)] =
    self >>> ZPipeline.zipWithPrevious

  def zipWithPreviousAndNext(implicit trace: Trace): ZPipeline[Env, Err, In, (Option[Out], Out, Option[Out])] =
    self >>> ZPipeline.zipWithPreviousAndNext

}

object ZPipeline extends ZPipelinePlatformSpecificConstructors {

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
  def aggregateAsync[Env, Err, In, Out](sink: => ZSink[Env, Err, In, In, Out])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] =
    ZPipeline.fromFunction(_.aggregateAsync(sink))

  /**
   * Like `aggregateAsyncWithinEither`, but only returns the `Right` results.
   */
  def aggregateAsyncWithin[Env, Err, In, Out](
    sink: => ZSink[Env, Err, In, In, Out],
    schedule: => Schedule[Env, Option[Out], Any]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    ZPipeline.fromFunction(_.aggregateAsyncWithin(sink, schedule))

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
   */
  def aggregateAsyncWithinEither[Env, Err, In, Out, Out2](
    sink: => ZSink[Env, Err, In, In, Out],
    schedule: => Schedule[Env, Option[Out], Out2]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Either[Out2, Out]] =
    ZPipeline.fromFunction(_.aggregateAsyncWithinEither(sink, schedule))

  /**
   * A shorter version of [[ZPipeline.identity]], which can facilitate more
   * compact definition of pipelines.
   *
   * {{{
   * ZPipeline[Int] >>> ZPipeline.filter(_ % 2 != 0)
   * }}}
   */
  def apply[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    identity[In]

  def append[In](values: => Chunk[In])(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    new ZPipeline(ZChannel.identity[Nothing, Chunk[In], Any] *> ZChannel.write(values))

  /**
   * A dynamic pipeline that first collects `n` elements from the stream, then
   * creates another pipeline with the function `f` and sends all the following
   * elements through that.
   */
  def branchAfter[Env, Err, In, Out](
    n: => Int
  )(f: Chunk[In] => ZPipeline[Env, Err, In, Out])(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    ZPipeline.suspend {
      def bufferring(acc: Chunk[In]): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] =
        ZChannel
          .readWithCause(
            (inElem: Chunk[In]) => {
              val nextSz = acc.size + inElem.size
              if (nextSz >= n) {
                val (b1, b2) = inElem.splitAt(n - acc.size)
                running(acc ++ b1, b2)
              } else {
                bufferring(acc ++ inElem)
              }
            },
            (err: Cause[Err]) => ZChannel.refailCause(err),
            (done: Any) => running(acc, Chunk.empty)
          )

      def running(
        prefix: Chunk[In],
        leftOver: Chunk[In]
      ): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] = {
        val nextUpstream = ZPipeline.prepend(leftOver)
        val pl           = f(prefix)
        val resPl        = nextUpstream >>> pl
        resPl.toChannel
      }

      ZPipeline.fromChannel(bufferring(Chunk.empty))
    }

  def changes[Err, In](implicit trace: Trace): ZPipeline[Any, Err, In, In] =
    changesWith(_ == _)

  def changesWith[Err, In](f: (In, In) => Boolean)(implicit trace: Trace): ZPipeline[Any, Err, In, In] = {
    def writer(last: Option[In]): ZChannel[Any, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
      ZChannel.readWithCause(
        (chunk: Chunk[In]) => {
          val (newLast, newChunk) =
            chunk.foldLeft[(Option[In], Chunk[In])]((last, Chunk.empty)) {
              case ((Some(o), os), o1) if (f(o, o1)) => (Some(o1), os)
              case ((_, os), o1)                     => (Some(o1), os :+ o1)
            }

          ZChannel.write(newChunk) *> writer(newLast)
        },
        (cause: Cause[Err]) => ZChannel.refailCause(cause),
        (_: Any) => ZChannel.unit
      )

    new ZPipeline(writer(None))
  }

  def changesWithZIO[Env, Err, In](
    f: (In, In) => ZIO[Env, Err, Boolean]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] = {
    def writer(last: Option[In]): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
      ZChannel.readWithCause(
        (chunk: Chunk[In]) =>
          ZChannel.fromZIO {
            chunk.foldZIO[Env, Err, (Option[In], Chunk[In])]((last, Chunk.empty)) {
              case ((Some(o), os), o1) =>
                f(o, o1).map(b => if (b) (Some(o1), os) else (Some(o1), os :+ o1))
              case ((_, os), o1) =>
                ZIO.succeed((Some(o1), os :+ o1))
            }
          }.flatMap { case (newLast, newChunk) =>
            ZChannel.write(newChunk) *> writer(newLast)
          },
        (cause: Cause[Err]) => ZChannel.refailCause(cause),
        (_: Any) => ZChannel.unit
      )

    new ZPipeline(writer(None))
  }

  /**
   * Creates a pipeline that exposes the chunk structure of the stream.
   */
  def chunks[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, Chunk[In]] =
    mapChunks(Chunk.single(_))

  /**
   * Creates a pipeline that collects elements with the specified partial
   * function.
   *
   * {{{
   * ZPipeline.collect[Option[Int], Int] { case Some(v) => v }
   * }}}
   */
  def collect[In, Out](f: PartialFunction[In, Out])(implicit trace: Trace): ZPipeline[Any, Nothing, In, Out] =
    new ZPipeline(ZChannel.identity[Nothing, Chunk[In], Any].mapOut(_.collect(f)))

  def collectLeft[Err, A, B](implicit trace: Trace): ZPipeline[Any, Err, Either[A, B], A] =
    collect { case Left(a) => a }

  def collectSome[Err, A](implicit trace: Trace): ZPipeline[Any, Err, Option[A], A] =
    collect { case Some(a) => a }

  def collectSuccess[A, B](implicit trace: Trace): ZPipeline[Any, Nothing, Exit[B, A], A] =
    collect { case Exit.Success(a) => a }

  def collectRight[Err, A, B](implicit trace: Trace): ZPipeline[Any, Err, Either[A, B], B] =
    collect { case Right(b) => b }

  def collectWhile[Err, In, Out](pf: PartialFunction[In, Out])(implicit trace: Trace): ZPipeline[Any, Err, In, Out] = {
    lazy val loop: ZChannel[Any, Err, Chunk[In], Any, Err, Chunk[Out], Any] =
      ZChannel.readWith[Any, Err, Chunk[In], Any, Err, Chunk[Out], Any](
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

    new ZPipeline(loop)
  }

  def collectWhileLeft[Err, A, B](implicit trace: Trace): ZPipeline[Any, Err, Either[A, B], A] =
    collectWhile { case Left(a) => a }

  def collectWhileRight[Err, A, B](implicit trace: Trace): ZPipeline[Any, Err, Either[A, B], B] =
    collectWhile { case Right(b) => b }

  def collectWhileSome[Err, A](implicit trace: Trace): ZPipeline[Any, Err, Option[A], A] =
    collectWhile { case Some(a) => a }

  def collectWhileSuccess[Err, A](implicit trace: Trace): ZPipeline[Any, Nothing, Exit[Err, A], A] =
    collectWhile { case Exit.Success(a) => a }

  def collectWhileZIO[Env, Err, In, Out](
    pf: PartialFunction[In, ZIO[Env, Err, Out]]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] = {
    def loop(
      chunkIterator: Chunk.ChunkIterator[In],
      index: Int
    ): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[Out], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          pf.andThen(_.map(a1 => ZChannel.write(Chunk.single(a1)) *> loop(chunkIterator, index + 1)))
            .applyOrElse(a, (_: In) => ZIO.succeed(ZChannel.unit))
        }
      else
        ZChannel.readWithCause(
          elem => loop(elem.chunkIterator, 0),
          err => ZChannel.refailCause(err),
          done => ZChannel.succeed(done)
        )

    new ZPipeline(loop(Chunk.ChunkIterator.empty, 0))
  }

  def drain[Err](implicit trace: Trace): ZPipeline[Any, Err, Any, Nothing] =
    new ZPipeline(ZChannel.identity[Err, Any, Any].drain)

  def dropRight[Err, In](n: => Int)(implicit trace: Trace): ZPipeline[Any, Err, In, In] =
    ZPipeline.suspend {
      if (n <= 0) ZPipeline.identity[In]
      else
        new ZPipeline({
          val queue = SingleThreadedRingBuffer[In](n)

          lazy val reader: ZChannel[Any, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
            ZChannel.readWithCause(
              (in: Chunk[In]) => {
                val outs = in.flatMap { elem =>
                  val head = queue.head
                  queue.put(elem)
                  head
                }

                ZChannel.write(outs) *> reader
              },
              ZChannel.refailCause,
              (_: Any) => ZChannel.unit
            )

          reader
        })
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
  def debounce[In](d: => Duration)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    ZPipeline.fromFunction(_.debounce(d))

  /**
   * Creates a pipeline that decodes a stream of bytes into a stream of strings
   * using the given charset
   */
  def decodeStringWith(
    charset: => Charset
  )(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeCharsWith(charset) >>> ZPipeline.mapChunks((chars: Chunk[Char]) => Chunk.single(new String(chars.toArray)))

  /**
   * Creates a pipeline that decodes a stream of bytes into a stream of
   * characters using the given charset
   */
  def decodeCharsWith(
    charset: => Charset,
    bufSize: => Int = 4096
  )(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, Char] =
    decodeCharsWithDecoder(charset.newDecoder(), bufSize)

  /**
   * Creates a pipeline that decodes a stream of bytes into a stream of
   * characters using the given charset decoder.
   */
  def decodeCharsWithDecoder(
    charsetDecoder: => CharsetDecoder,
    bufSize: => Int = 4096
  )(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, Char] =
    ZPipeline.suspend {
      val decoder    = charsetDecoder
      val byteBuffer = ByteBuffer.allocate(bufSize)
      val charBuffer = CharBuffer.allocate((bufSize.toFloat * decoder.averageCharsPerByte).round)

      def handleCoderResult(coderResult: CoderResult) =
        if (coderResult.isUnderflow || coderResult.isOverflow) {
          ZIO.succeed {
            byteBuffer.compact()
            charBuffer.flip()
            val array = new Array[Char](charBuffer.remaining)
            charBuffer.get(array)
            charBuffer.clear()
            Chunk.fromArray(array)
          }
        } else if (coderResult.isMalformed) {
          ZIO.fail(new MalformedInputException(coderResult.length()))
        } else if (coderResult.isUnmappable) {
          ZIO.fail(new UnmappableCharacterException(coderResult.length()))
        } else {
          ZIO.dieMessage(s"Unexpected coder result: $coderResult")
        }

      def decodeChunk(inBytes: Chunk[Byte]): IO[CharacterCodingException, Chunk[Char]] =
        for {
          remainingBytes <- ZIO.succeed {
                              val bufRemaining = byteBuffer.remaining
                              val (decodeBytes, remainingBytes) =
                                if (inBytes.length > bufRemaining)
                                  inBytes.splitAt(bufRemaining)
                                else
                                  (inBytes, Chunk.empty)
                              byteBuffer.put(decodeBytes.toArray)
                              byteBuffer.flip()
                              remainingBytes
                            }
          result         <- ZIO.succeed(decoder.decode(byteBuffer, charBuffer, false))
          decodedChars   <- handleCoderResult(result)
          remainderChars <- if (remainingBytes.isEmpty) Exit.emptyChunk else decodeChunk(remainingBytes)
        } yield decodedChars ++ remainderChars

      def endOfInput: IO[CharacterCodingException, Chunk[Char]] =
        for {
          result         <- ZIO.succeed(decoder.decode(byteBuffer, charBuffer, true))
          decodedChars   <- handleCoderResult(result)
          remainderChars <- if (result.isOverflow) endOfInput else Exit.emptyChunk
        } yield decodedChars ++ remainderChars

      def flushRemaining: IO[CharacterCodingException, Chunk[Char]] =
        for {
          result         <- ZIO.succeed(decoder.flush(charBuffer))
          decodedChars   <- handleCoderResult(result)
          remainderChars <- if (result.isOverflow) flushRemaining else Exit.emptyChunk
        } yield decodedChars ++ remainderChars

      val push: Option[Chunk[Byte]] => IO[CharacterCodingException, Chunk[Char]] = {
        case Some(inChunk) => decodeChunk(inChunk)
        case None =>
          for {
            _              <- ZIO.succeed(byteBuffer.flip())
            decodedChars   <- endOfInput
            remainingBytes <- flushRemaining
            result          = decodedChars ++ remainingBytes
            _ <- ZIO.succeed {
                   byteBuffer.clear()
                   charBuffer.clear()
                 }
          } yield result
      }

      val createPush: ZIO[Any, Nothing, Option[Chunk[Byte]] => IO[CharacterCodingException, Chunk[Char]]] =
        for {
          _ <- ZIO.succeed(decoder.reset)
        } yield push

      ZPipeline.fromPush(createPush)
    }

  /**
   * Creates a pipeline that drops n elements.
   */
  def drop[In](n: => Int)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    ZPipeline.suspend {
      def loop(r: Int): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
        ZChannel
          .readWithCause(
            (in: Chunk[In]) => {
              val dropped  = in.drop(r)
              val leftover = (r - in.length).max(0)
              val more     = in.isEmpty || leftover > 0

              if (more) loop(leftover)
              else ZChannel.write(dropped) *> ZChannel.identity
            },
            (e: Cause[ZNothing]) => ZChannel.refailCause(e),
            (_: Any) => ZChannel.unit
          )

      new ZPipeline(loop(n))
    }

  /**
   * Creates a pipeline that drops elements until the specified predicate
   * evaluates to true.
   *
   * {{{
   * ZPipeline.dropUntil[Int](_ > 100)
   * }}}
   */
  def dropUntil[In](f: In => Boolean)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    ZPipeline.dropWhile[In](!f(_)) >>> ZPipeline.drop(1)

  /**
   * Drops incoming elements until the effectful predicate `p` is satisfied.
   */
  def dropUntilZIO[Env, Err, In](
    p: In => ZIO[Env, Err, Boolean]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] = {
    lazy val loop: ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], Any] = ZChannel.readWithCause(
      (in: Chunk[In]) =>
        ZChannel.unwrap(in.dropUntilZIO(p).map { leftover =>
          val more = leftover.isEmpty
          if (more) loop else ZChannel.write(leftover) *> ZChannel.identity[Err, Chunk[In], Any]
        }),
      (e: Cause[Err]) => ZChannel.refailCause(e),
      (_: Any) => ZChannel.unit
    )

    new ZPipeline(loop)
  }

  /**
   * Creates a pipeline that drops elements while the specified predicate
   * evaluates to true.
   *
   * {{{
   * ZPipeline.dropWhile[Int](_ <= 100)
   * }}}
   */
  def dropWhile[In](f: In => Boolean)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] = {

    def dropWhile(f: In => Boolean): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
      ZChannel.readWithCause(
        in => {
          val out = in.dropWhile(f)
          if (out.isEmpty) dropWhile(f)
          else ZChannel.write(out) *> ZChannel.identity[ZNothing, Chunk[In], Any]
        },
        err => ZChannel.refailCause(err),
        out => ZChannel.succeedNow(out)
      )

    new ZPipeline(dropWhile(f))
  }

  /**
   * Drops incoming elements as long as the effectful predicate `p` is
   * satisfied.
   */
  def dropWhileZIO[Env, Err, In, Out](
    p: In => ZIO[Env, Err, Boolean]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] = {

    lazy val loop: ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], Any] = ZChannel.readWithCause(
      (in: Chunk[In]) =>
        ZChannel.unwrap(in.dropWhileZIO(p).map { leftover =>
          val more = leftover.isEmpty
          if (more) loop else ZChannel.write(leftover) *> ZChannel.identity[Err, Chunk[In], Any]
        }),
      (e: Cause[Err]) => ZChannel.refailCause(e),
      (_: Any) => ZChannel.unit
    )

    new ZPipeline(loop)
  }

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the given charset
   */
  def encodeStringWith(
    charset: => Charset,
    bom: => Chunk[Byte] = Chunk.empty
  )(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] = {
    val withoutBOM =
      ZPipeline.mapChunks((s: Chunk[String]) =>
        s.foldLeft[Chunk[Char]](Chunk.empty)((acc, str) => acc ++ Chunk.fromArray(str.toCharArray))
      ) >>> encodeCharsWith(charset)

    if (bom.isEmpty) withoutBOM
    else {
      ZPipeline.fromChannel(ZChannel.write(bom) *> withoutBOM.channel)
    }
  }

  /**
   * Creates a pipeline that converts a stream of characters into a stream of
   * bytes using the given charset
   */
  def encodeCharsWith(
    charset: => Charset,
    bufferSize: => Int = 4096
  )(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Char, Byte] =
    ZPipeline.suspend {
      val encoder    = charset.newEncoder()
      val charBuffer = CharBuffer.allocate((bufferSize.toFloat / encoder.averageBytesPerChar).round)
      val byteBuffer = ByteBuffer.allocate(bufferSize)

      def handleCoderResult(coderResult: CoderResult): ZIO[Any, CharacterCodingException, Chunk[Byte]] =
        if (coderResult.isUnderflow || coderResult.isOverflow) {
          ZIO.succeed {
            charBuffer.compact()
            byteBuffer.flip()
            val array = new Array[Byte](byteBuffer.remaining())
            byteBuffer.get(array)
            byteBuffer.clear()
            Chunk.fromArray(array)
          }
        } else if (coderResult.isMalformed) {
          ZIO.fail(new MalformedInputException(coderResult.length()))
        } else if (coderResult.isUnmappable) {
          ZIO.fail(new UnmappableCharacterException(coderResult.length()))
        } else {
          ZIO.dieMessage(s"Invalid CoderResult state")
        }

      def encodeChunk(inChars: Chunk[Char]): IO[CharacterCodingException, Chunk[Byte]] =
        for {
          remainingChars <- ZIO.succeed {
                              val bufRemaining = charBuffer.remaining()
                              val (decodeChars, remainingChars) = {
                                if (inChars.length > bufRemaining) {
                                  inChars.splitAt(bufRemaining)
                                } else
                                  (inChars, Chunk.empty)
                              }
                              charBuffer.put(decodeChars.toArray)
                              charBuffer.flip()
                              remainingChars
                            }
          result         <- ZIO.succeed(encoder.encode(charBuffer, byteBuffer, false))
          encodedBytes   <- handleCoderResult(result)
          remainderBytes <- if (remainingChars.isEmpty) Exit.emptyChunk else encodeChunk(remainingChars)

        } yield encodedBytes ++ remainderBytes

      def endOfInput: IO[CharacterCodingException, Chunk[Byte]] =
        for {
          result         <- ZIO.succeed(encoder.encode(charBuffer, byteBuffer, true))
          encodedBytes   <- handleCoderResult(result)
          remainderBytes <- if (result.isOverflow) endOfInput else Exit.emptyChunk
        } yield encodedBytes ++ remainderBytes

      def flushRemaining: IO[CharacterCodingException, Chunk[Byte]] =
        for {
          result         <- ZIO.succeed(encoder.flush(byteBuffer))
          encodedBytes   <- handleCoderResult(result)
          remainderBytes <- if (result.isOverflow) flushRemaining else Exit.emptyChunk
        } yield encodedBytes ++ remainderBytes

      val push: Option[Chunk[Char]] => IO[CharacterCodingException, Chunk[Byte]] = {
        case Some(inChunk: Chunk[Char]) =>
          encodeChunk(inChunk)
        case None =>
          for {
            _              <- ZIO.succeed(charBuffer.flip())
            encodedBytes   <- endOfInput
            remainingBytes <- flushRemaining
            result          = encodedBytes ++ remainingBytes
            _ <- ZIO.succeed {
                   charBuffer.clear()
                   byteBuffer.clear()
                 }
          } yield result
      }

      val createPush: ZIO[Any, Nothing, Option[Chunk[Char]] => IO[CharacterCodingException, Chunk[Byte]]] = {
        for {
          _ <- ZIO.succeed(encoder.reset)
        } yield push
      }

      ZPipeline.fromPush(createPush)
    }

  /**
   * Accesses the environment of the pipeline in the context of a pipeline.
   */
  def environmentWithPipeline[Env]: EnvironmentWithPipelinePartiallyApplied[Env] =
    new EnvironmentWithPipelinePartiallyApplied[Env]

  /**
   * Creates a pipeline that filters elements according to the specified
   * predicate.
   */
  def filter[In](f: In => Boolean)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    new ZPipeline(ZChannel.identity[Nothing, Chunk[In], Any].mapOut(_.filter(f)))

  def filterZIO[Env, Err, In](f: In => ZIO[Env, Err, Boolean])(implicit trace: Trace): ZPipeline[Env, Err, In, In] =
    ZPipeline.mapChunksZIO(_.filterZIO(f))

  /**
   * Creates a pipeline that submerges chunks into the structure of the stream.
   */
  def flattenChunks[In](implicit trace: Trace): ZPipeline[Any, Nothing, Chunk[In], In] =
    ZPipeline.mapChunks(_.flatten)

  /**
   * Creates a pipeline that converts exit results into a stream of values with
   * failure terminating the stream.
   */
  def flattenExit[Err, Out](implicit trace: Trace): ZPipeline[Any, Err, Exit[Err, Out], Out] =
    ZPipeline.mapZIO(ZIO.identityFn)

  /**
   * Creates a pipeline that submerges iterables into the structure of the
   * stream.
   */
  def flattenIterables[Out](implicit trace: Trace): ZPipeline[Any, Nothing, Iterable[Out], Out] =
    ZPipeline.mapChunks(_.flatMap(Chunk.fromIterable))

  /**
   * Creates a pipeline that flattens a stream of streams into a single stream
   * of values. The streams are merged in parallel up to the specified maximum
   * concurrency and will buffer up to output buffer size elements.
   */
  def flattenStreamsPar[Env, Err, Out](n: => Int, outputBuffer: => Int = 16)(implicit
    trace: Trace
  ): ZPipeline[Env, Err, ZStream[Env, Err, Out], Out] =
    new ZPipeline(
      ZChannel
        .identity[Nothing, Chunk[ZStream[Env, Err, Out]], Any]
        .concatMap(ZChannel.writeChunk(_))
        .mergeMap(n, outputBuffer)(_.channel)
    )

  /**
   * Creates a pipeline that flattens a stream of takes.
   */
  def flattenTake[Err, Out](implicit trace: Trace): ZPipeline[Any, Err, Take[Err, Out], Out] = {

    lazy val channel: ZChannel[Any, ZNothing, Chunk[Take[Err, Out]], Any, Err, Chunk[Out], Any] =
      ZChannel.readWithCause(
        in => {
          val chunkBuilder  = ChunkBuilder.make[Chunk[Out]]()
          val chunkIterator = in.chunkIterator
          var failureOption = Option.empty[Cause[Option[Err]]]
          var index         = 0
          var loop          = true
          while (loop && chunkIterator.hasNextAt(index)) {
            val take = chunkIterator.nextAt(index)
            take match {
              case Take(Exit.Success(chunk)) =>
                chunkBuilder += chunk
              case Take(Exit.Failure(cause)) =>
                failureOption = Some(cause)
                loop = false
            }
            index += 1
          }
          val chunk = chunkBuilder.result()
          failureOption match {
            case Some(cause) =>
              Cause.flipCauseOption(cause) match {
                case Some(err) =>
                  if (chunk.isEmpty) ZChannel.refailCause(err)
                  else ZChannel.writeChunk(chunk) *> ZChannel.refailCause(err)
                case None =>
                  if (chunk.isEmpty) ZChannel.unit
                  else ZChannel.writeChunk(chunk)
              }
            case None =>
              if (chunk.isEmpty) channel
              else ZChannel.writeChunk(chunk) *> channel
          }
        },
        err => ZChannel.refailCause(err),
        done => ZChannel.succeedNow(done)
      )

    ZPipeline.fromChannel(channel)
  }

  /**
   * Creates a pipeline that groups on adjacent keys, calculated by function f.
   */
  def groupAdjacentBy[In, Key](
    f: In => Key
  )(implicit trace: Trace): ZPipeline[Any, Nothing, In, (Key, NonEmptyChunk[In])] = {

    def groupAdjacentByChunk(
      state: Option[(Key, NonEmptyChunk[In])],
      chunk: Chunk[In]
    ): (Option[(Key, NonEmptyChunk[In])], Chunk[(Key, NonEmptyChunk[In])]) =
      if (chunk.isEmpty) (state, Chunk.empty)
      else {
        val chunkBuilder  = ChunkBuilder.make[(Key, NonEmptyChunk[In])]()
        val chunkIterator = chunk.chunkIterator
        var from          = 0
        var until         = 0
        var key           = null.asInstanceOf[Key]
        var previousChunk = Chunk.empty[In]
        state match {
          case Some((previousKey, nonEmptyChunk)) =>
            key = previousKey
            var loop = true
            while (chunkIterator.hasNextAt(until) && loop) {
              val in         = chunkIterator.nextAt(until)
              val updatedKey = f(in)
              if (key != updatedKey) {
                chunkBuilder += ((key, nonEmptyChunk ++ NonEmptyChunk.nonEmpty(chunk.slice(from, until))))
                key = updatedKey
                from = until
                loop = false
              }
              until += 1
            }
            if (loop) {
              previousChunk = nonEmptyChunk.toChunk
            }
          case None =>
            key = f(chunkIterator.nextAt(until))
            until += 1
        }
        while (chunkIterator.hasNextAt(until)) {
          val in         = chunkIterator.nextAt(until)
          val updatedKey = f(in)
          if (key != updatedKey) {
            chunkBuilder += key -> NonEmptyChunk.nonEmpty(chunk.slice(from, until))
            key = updatedKey
            from = until
          }
          until += 1
        }
        val nonEmptyChunk = previousChunk ++ NonEmptyChunk.nonEmpty(chunk.slice(from, until))
        val out           = chunkBuilder.result()
        (Some((key, nonEmptyChunk)), out)
      }

    def groupAdjacentBy(
      state: Option[(Key, NonEmptyChunk[In])]
    ): ZChannel[Any, ZNothing, Chunk[In], Any, ZNothing, Chunk[(Key, NonEmptyChunk[In])], Any] =
      ZChannel.readWithCause(
        in => {
          val (updatedState, out) = groupAdjacentByChunk(state, in)
          if (out.isEmpty) groupAdjacentBy(updatedState)
          else ZChannel.write(out) *> groupAdjacentBy(updatedState)
        },
        err =>
          state match {
            case Some(out) => ZChannel.write(Chunk.single(out)) *> ZChannel.refailCause(err)
            case None      => ZChannel.refailCause(err)
          },
        done =>
          state match {
            case Some(out) => ZChannel.write(Chunk.single(out)) *> ZChannel.succeedNow(done)
            case None      => ZChannel.succeedNow(done)
          }
      )

    ZPipeline.fromChannel(groupAdjacentBy(None))
  }

  def grouped[In](chunkSize: => Int)(implicit trace: Trace): ZPipeline[Any, Nothing, In, Chunk[In]] =
    rechunk(chunkSize).chunks

  /**
   * Partitions the stream with the specified chunkSize or until the specified
   * duration has passed, whichever is satisfied first.
   */
  def groupedWithin[In](chunkSize: => Int, within: => Duration)(implicit
    trace: Trace
  ): ZPipeline[Any, Nothing, In, Chunk[In]] =
    ZPipeline.fromFunction(_.groupedWithin(chunkSize, within))

  /**
   * Creates a pipeline that sends all the elements through the given channel.
   */
  def fromChannel[Env, Err, In, Out](
    channel: => ZChannel[Env, Nothing, Chunk[In], Any, Err, Chunk[Out], Any]
  ): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(channel)

  /**
   * Constructs a pipeline from a stream transformation function.
   */
  def fromFunction[Env, Err, In, Out](
    f: ZStream[Any, Nothing, In] => ZStream[Env, Err, Out]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] = {
    def fc(
      upstream: ZChannel[Any, Any, Any, Any, ZNothing, Chunk[In], Any]
    ): ZChannel[Env, Any, Any, Any, Err, Chunk[Out], Any] =
      f(upstream.toStream).toChannel

    val resCh: ZChannel.DeferedUpstream[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] =
      ZChannel.DeferedUpstream(fc)
    val resPl: ZPipeline[Env, Err, In, Out] = resCh.toPipeline
    resPl
  }

  /**
   * Creates a pipeline from a chunk processing function.
   */
  def fromPush[Env, Err, In, Out](
    push: => ZIO[Scope with Env, Nothing, Option[Chunk[In]] => ZIO[Env, Err, Chunk[Out]]]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] = {

    def pull(
      push: Option[Chunk[In]] => ZIO[Env, Err, Chunk[Out]]
    ): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] =
      ZChannel.readWithCause(
        in =>
          ZChannel
            .fromZIO(push(Some(in)))
            .flatMap(out => ZChannel.write(out)) *> pull(push),
        err => ZChannel.refailCause(err),
        _ => ZChannel.fromZIO(push(None)).flatMap(out => ZChannel.write(out))
      )

    val channel: ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] =
      ZChannel.unwrapScoped[Env](push.map(pull))

    new ZPipeline(channel)
  }

  /**
   * Creates a pipeline that repeatedly sends all elements through the given
   * sink.
   */
  def fromSink[Env, Err, In, Out](
    sink: => ZSink[Env, Err, In, In, Out]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(
      ZChannel.suspend {
        var leftover: Chunk[In]   = Chunk.empty
        var upstreamDone: Boolean = false

        lazy val upstream: ZChannel[Any, Err, Chunk[In], Any, Err, Chunk[In], Any] =
          ZChannel.suspend {
            val l = leftover

            if (l.isEmpty)
              ZChannel.readWithCause(
                (c: Chunk[In]) => ZChannel.write(c) *> upstream,
                (e: Cause[Err]) => ZChannel.refailCause(e),
                (done: Any) => {
                  upstreamDone = true
                  ZChannel.succeedNow(done)
                }
              )
            else {
              leftover = Chunk.empty
              ZChannel.write(l) *> upstream
            }
          }

        lazy val writeDone: ZChannel[Any, Err, Chunk[In], Out, Err, Chunk[Out], Any] =
          ZChannel.readWithCause(
            elem => {
              leftover ++= elem
              writeDone
            },
            err => ZChannel.refailCause(err),
            out => ZChannel.write(Chunk.single(out))
          )

        lazy val transducer: ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Unit] =
          sink.channel.pipeTo(writeDone).flatMap { _ =>
            if (upstreamDone && leftover.isEmpty) ZChannel.unit
            else transducer
          }

        upstream pipeToOrFail transducer
      }
    )

  /**
   * Decode each pair of hex digit input characters (both lower or upper case
   * letters are allowed) as one output byte.
   */
  def hexDecode(implicit trace: Trace): ZPipeline[Any, EncodingException, Char, Byte] = {
    def digitValue(c: Char): Int = c match {
      case d if d >= '0' && d <= '9' => d - '0'
      case l if l >= 'a' && l <= 'f' => 10 + l - 'a'
      case u if u >= 'A' && u <= 'F' => 10 + u - 'A'
      case _                         => -1
    }
    def decodeChannel(
      spare: Chunk[Char]
    ): ZChannel[Any, Any, Chunk[Char], Any, EncodingException, Chunk[Byte], Unit] = {
      def in(in: Chunk[Char]): ZChannel[Any, Any, Chunk[Char], Any, EncodingException, Chunk[Byte], Unit] = {
        val toProcess = spare ++ in
        if (toProcess.isEmpty) {
          ZChannel.unit
        } else {
          val l = toProcess.size
          val (cs, newSpare) = if (l % 2 == 0) {
            (toProcess, Chunk.empty[Char])
          } else {
            toProcess.splitAt(l - 1)
          }
          val temp: ChunkBuilder[Byte] = ChunkBuilder.make[Byte](l / 2)
          var bad: Option[Char]        = None
          for (i <- 0 until cs.size by 2) {
            if (bad.isEmpty) {
              val ch = cs(i)
              val h  = digitValue(ch)
              if (h < 0) {
                bad = Some(ch)
              } else {
                val cl = cs(i + 1)
                val l  = digitValue(cl)
                if (l < 0) {
                  bad = Some(cl)
                } else {
                  val v = (h << 4) | l
                  val b = v.toByte
                  temp += b
                }
              }
            }
          }
          bad match {
            case None    => ZChannel.write(temp.result()) *> decodeChannel(newSpare)
            case Some(e) => ZChannel.fail(EncodingException(s"Not a valid hex digit: '$e'"))
          }
        }
      }
      def err(z: Any): ZChannel[Any, Any, Chunk[Char], Any, EncodingException, Chunk[Byte], Unit] =
        ZChannel.fail(EncodingException("Input stream should be infallible"))

      def done(u: Any): ZChannel[Any, Any, Chunk[Char], Any, EncodingException, Chunk[Byte], Unit] =
        if (spare.isEmpty) {
          ZChannel.succeed(())
        } else {
          ZChannel.fail(EncodingException("Extra input at end after last fully encoded byte"))
        }

      ZChannel.readWith[Any, Any, zio.Chunk[Char], Any, EncodingException, zio.Chunk[Byte], Unit](
        in,
        err,
        done
      )
    }

    ZPipeline.fromChannel(decodeChannel(Chunk.empty[Char]))
  }

  /**
   * Encode each input byte as two output bytes as the hex representation of the
   * input byte.
   */
  def hexEncode(implicit trace: Trace): ZPipeline[Any, Nothing, Byte, Char] = {
    val DIGITS: Array[Char] = Array(
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
    ZPipeline.fromPush[Any, Nothing, Byte, Char](
      ZIO.succeed((inChunkOpt: Option[Chunk[Byte]]) =>
        inChunkOpt match {
          case None => ZIO.succeed(Chunk.empty[Char])
          case Some(bs) =>
            ZIO.succeed {
              val out = ChunkBuilder.make[Char](bs.size * 2)
              for (b <- bs) {
                out += DIGITS((b >>> 4) & 0x0f)
                out += DIGITS((b >>> 0) & 0x0f)
              }
              out.result()
            }
        }
      )
    )
  }

  /**
   * The identity pipeline, which does not modify streams in any way.
   */
  def identity[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    new ZPipeline(ZChannel.identity)

  def intersperse[Err, In](middle: => In)(implicit trace: Trace): ZPipeline[Any, Err, In, In] =
    new ZPipeline(
      ZChannel.suspend[Any, Err, Chunk[In], Any, Err, Chunk[In], Any] {
        def writer(isFirst: Boolean): ZChannel[Any, Err, Chunk[In], Any, Err, Chunk[In], Any] =
          ZChannel.readWithCause[Any, Err, Chunk[In], Any, Err, Chunk[In], Any](
            chunk => {
              val builder    = ChunkBuilder.make[In]()
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
            err => ZChannel.refailCause(err),
            _ => ZChannel.unit
          )

        writer(true)
      }
    )

  def intersperse[In](start: => In, middle: => In, end: => In)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    ZPipeline.intersperse(middle) >>> ZPipeline.prepend(Chunk.single(start)) >>> ZPipeline.append(Chunk.single(end))

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the ISO_8859_1 charset
   */
  def iso_8859_1Decode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(StandardCharsets.ISO_8859_1)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the ISO_8859_1 charset
   */
  def iso_8859_1Encode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.ISO_8859_1)

  /**
   * Creates a pipeline that maps elements with the specified function.
   */
  def map[In, Out](f: In => Out)(implicit trace: Trace): ZPipeline[Any, Nothing, In, Out] =
    new ZPipeline(ZChannel.identity.mapOut(_.map(f)))

  /**
   * Creates a pipeline that statefully maps elements with the specified
   * function.
   */
  def mapAccum[In, State, Out](
    s: => State
  )(f: (State, In) => (State, Out))(implicit trace: Trace): ZPipeline[Any, Nothing, In, Out] =
    mapAccumZIO(s)((s, in) => ZIO.succeed(f(s, in)))

  /**
   * Creates a pipeline that statefully maps elements with the specified effect.
   */
  def mapAccumZIO[Env, Err, In, State, Out](
    s: => State
  )(f: (State, In) => ZIO[Env, Err, (State, Out)])(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    ZPipeline.suspend {
      def accumulator(s: State): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[Out], Any] =
        ZChannel.readWithCause(
          (in: Chunk[In]) =>
            ZChannel.unwrap(
              ZIO.suspendSucceed {
                val outputChunk            = ChunkBuilder.make[Out](in.size)
                val emit: Out => UIO[Unit] = (o: Out) => ZIO.succeed(outputChunk += o).unit
                ZIO
                  .foldLeft[Env, Err, State, In](in)(s)((s1, a) => f(s1, a).flatMap(sa => emit(sa._2) as sa._1))
                  .fold(
                    failure => {
                      val partialResult = outputChunk.result()
                      if (partialResult.nonEmpty)
                        ZChannel.write(partialResult) *> ZChannel.fail(failure)
                      else
                        ZChannel.fail(failure)
                    },
                    out => ZChannel.write(outputChunk.result()) *> accumulator(out)
                  )
              }
            ),
          ZChannel.refailCause,
          (_: Any) => ZChannel.unit
        )

      new ZPipeline(accumulator(s))
    }

  /**
   * Creates a pipeline that maps chunks of elements with the specified
   * function.
   */
  def mapChunks[In, Out](
    f: Chunk[In] => Chunk[Out]
  )(implicit trace: Trace): ZPipeline[Any, Nothing, In, Out] =
    new ZPipeline(ZChannel.identity[Nothing, Chunk[In], Any].mapOut(f))

  /**
   * Creates a pipeline that maps chunks of elements with the specified effect.
   */
  def mapChunksZIO[Env, Err, In, Out](
    f: Chunk[In] => ZIO[Env, Err, Chunk[Out]]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(ZChannel.identity[Nothing, Chunk[In], Any].mapOutZIO(f))

  /**
   * Creates a pipeline that maps elements with the specified function that
   * returns a stream.
   */
  def mapStream[Env, Err, In, Out](
    f: In => ZStream[Env, Err, Out]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(
      ZChannel.identity[Nothing, Chunk[In], Any].concatMap(_.map(f).map(_.channel).fold(ZChannel.unit)(_ *> _))
    )

  /**
   * Creates a pipeline that maps elements with the specified effectful
   * function.
   */
  def mapZIO[Env, Err, In, Out](f: In => ZIO[Env, Err, Out])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] = {

    def loop(
      chunkIterator: Chunk.ChunkIterator[In],
      index: Int
    ): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[Out], Any] =
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

    new ZPipeline(loop(Chunk.ChunkIterator.empty, 0))
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
  def mapZIOPar[Env, Err, In, Out](n: => Int)(f: In => ZIO[Env, Err, Out])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] =
    mapZIOPar(n, 16)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. Transformed elements
   * will be emitted in the original order.
   *
   * @note
   *   This combinator destroys the chunking structure. It's recommended to use
   *   rechunk afterwards.
   */
  def mapZIOPar[Env, Err, In, Out](n: => Int, bufferSize: => Int = 16)(f: In => ZIO[Env, Err, Out])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(
      ZChannel
        .identity[Nothing, Chunk[In], Any]
        .concatMap(ZChannel.writeChunk(_))
        .mapOutZIOPar(n, bufferSize)(f)
        .mapOut(Chunk.single)
    )

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  def mapZIOParUnordered[Env, Err, In, Out](n: => Int)(f: In => ZIO[Env, Err, Out])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] =
    mapZIOParUnordered(n, 16)(f)

  /**
   * Maps over elements of the stream with the specified effectful function,
   * executing up to `n` invocations of `f` concurrently. The element order is
   * not enforced by this combinator, and elements may be reordered.
   */
  def mapZIOParUnordered[Env, Err, In, Out](n: => Int, bufferSize: => Int = 16)(f: In => ZIO[Env, Err, Out])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(
      ZChannel
        .identity[Nothing, Chunk[In], Any]
        .concatMap(ZChannel.writeChunk(_))
        .mapOutZIOParUnordered(n, bufferSize)(f)
        .mapOut(Chunk.single)
    )

  /**
   * Emits the provided chunk before emitting any other value.
   */
  def prepend[In](values: => Chunk[In])(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    new ZPipeline(ZChannel.write(values) *> ZChannel.identity)

  /**
   * A pipeline that rechunks the stream into chunks of the specified size.
   */
  def rechunk[In](n: => Int)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    new ZPipeline(ZChannel.succeed(new ZStream.Rechunker[In](scala.math.max(n, 1))).flatMap { rechunker =>
      lazy val loop: ZChannel[Any, ZNothing, Chunk[In], Any, ZNothing, Chunk[In], Any] =
        ZChannel.readWithCause(
          (in: Chunk[In]) => {
            val out = rechunker.rechunk(in)
            if (out ne null) out *> loop
            else loop
          },
          (cause: Cause[ZNothing]) => rechunker.done() *> ZChannel.refailCause(cause),
          (_: Any) => rechunker.done()
        )

      loop
    })

  /**
   * Creates a pipeline that randomly samples elements according to the
   * specified percentage.
   */
  def sample[In](p: => Double)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] = {
    val clamped = if (p.isNaN || p < 0.0d) 0.0d else if (p > 1.0d) 1.0d else p
    val channel = ZChannel
      .identity[Nothing, Chunk[In], Any]
      .mapOutZIO(_.filterZIO(_ => Random.nextDoubleBetween(0.0d, 1.0d).map(_ < clamped)))
    ZPipeline.fromChannel(channel)
  }

  /**
   * Creates a pipeline that scans elements with the specified function.
   */
  def scan[In, Out](s: => Out)(f: (Out, In) => Out)(implicit trace: Trace): ZPipeline[Any, Nothing, In, Out] =
    scanZIO(s)((out, in) => ZIO.succeed(f(out, in)))

  /**
   * Creates a pipeline that scans elements with the specified function.
   */
  def scanZIO[Env, Err, In, Out](
    s: => Out
  )(f: (Out, In) => ZIO[Env, Err, Out])(implicit trace: Trace): ZPipeline[Env, Err, In, Out] =
    ZPipeline.suspend {
      new ZPipeline(
        ZChannel.write(Chunk.single(s)) *>
          mapAccumZIO[Env, Err, In, Out, Out](s)((s, a) => f(s, a).map(s => (s, s))).channel
      )
    }

  /**
   * Accesses the specified service in the environment of the pipeline in the
   * context of a pipeline.
   */
  def serviceWithPipeline[Service]: ServiceWithPipelinePartiallyApplied[Service] =
    new ServiceWithPipelinePartiallyApplied[Service]

  /**
   * Splits strings on a delimiter.
   */
  def splitOn(delimiter: => String)(implicit trace: Trace): ZPipeline[Any, Nothing, String, String] =
    ZPipeline.mapChunks[String, Char](_.flatMap(string => Chunk.fromArray(string.toArray))) >>>
      ZPipeline.splitOnChunk[Char](Chunk.fromArray(delimiter.toArray)) >>>
      ZPipeline.mapChunks[Char, String](chunk => Chunk.single(chunk.mkString("")))

  /**
   * Splits strings on a delimiter.
   */
  def splitOnChunk[In](delimiter: => Chunk[In])(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    ZPipeline.suspend {

      def next(
        leftover: Option[Chunk[In]],
        delimiterIndex: Int
      ): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
        ZChannel.readWithCause(
          inputChunk => {
            var buffer = null.asInstanceOf[collection.mutable.ArrayBuffer[Chunk[In]]]
            inputChunk.foldLeft((leftover getOrElse Chunk.empty, delimiterIndex)) {
              case ((carry, delimiterCursor), a) =>
                val concatenated = carry :+ a
                if (delimiterCursor < delimiter.length && a == delimiter(delimiterCursor)) {
                  if (delimiterCursor + 1 == delimiter.length) {
                    if (buffer eq null) buffer = collection.mutable.ArrayBuffer[Chunk[In]]()
                    buffer += concatenated.take(concatenated.length - delimiter.length)
                    (Chunk.empty, 0)
                  } else (concatenated, delimiterCursor + 1)
                } else (concatenated, if (a == delimiter(0)) 1 else 0)
            } match {
              case (carry, delimiterCursor) =>
                ZChannel.writeChunk(if (buffer eq null) Chunk.empty else Chunk.fromArray(buffer.toArray)) *> next(
                  if (carry.nonEmpty) Some(carry) else None,
                  delimiterCursor
                )
            }
          },
          halt =>
            leftover match {
              case Some(chunk) => ZChannel.write(chunk) *> ZChannel.refailCause(halt)
              case None        => ZChannel.refailCause(halt)
            },
          done =>
            leftover match {
              case Some(chunk) => ZChannel.write(chunk) *> ZChannel.succeed(done)
              case None        => ZChannel.succeed(done)
            }
        )
      new ZPipeline(next(None, 0))
    }

  /**
   * Splits strings on newlines. Handles both Windows newlines (`\r\n`) and UNIX
   * newlines (`\n`).
   */
  def splitLines(implicit trace: Trace): ZPipeline[Any, Nothing, String, String] =
    ZPipeline.suspend {
      val stringBuilder = new StringBuilder
      var midCRLF       = false

      def splitLinesChunk(chunk: Chunk[String]): Chunk[String] = {
        val chunkBuilder = ChunkBuilder.make[String]()
        chunk.foreach { string =>
          if (string.nonEmpty) {
            var from      = 0
            var indexOfCR = string.indexOf('\r')
            var indexOfLF = string.indexOf('\n')
            if (midCRLF) {
              if (indexOfLF == 0) {
                chunkBuilder += stringBuilder.result()
                stringBuilder.clear()
                from = 1
                indexOfLF = string.indexOf('\n', from)
              } else {
                stringBuilder += '\r'
              }
              midCRLF = false
            }
            while (indexOfCR != -1 || indexOfLF != -1) {
              if (indexOfCR == -1 || (indexOfLF != -1 && indexOfLF < indexOfCR)) {
                if (stringBuilder.isEmpty) {
                  chunkBuilder += string.substring(from, indexOfLF)
                } else {
                  stringBuilder.append(string.substring(from, indexOfLF))
                  chunkBuilder += stringBuilder.result()
                  stringBuilder.clear()
                }
                from = indexOfLF + 1
                indexOfLF = string.indexOf('\n', from)
              } else {
                if (string.length == indexOfCR + 1) {
                  midCRLF = true
                  indexOfCR = -1
                } else {
                  if (indexOfLF == indexOfCR + 1) {
                    if (stringBuilder.isEmpty) {
                      chunkBuilder += string.substring(from, indexOfCR)
                    } else {
                      stringBuilder.append(string.substring(from, indexOfCR))
                      chunkBuilder += stringBuilder.result()
                      stringBuilder.clear()
                    }
                    from = indexOfCR + 2
                    indexOfCR = string.indexOf('\r', from)
                    indexOfLF = string.indexOf('\n', from)
                  } else {
                    indexOfCR = string.indexOf('\r', indexOfCR + 1)
                  }
                }
              }
            }

            if (midCRLF) stringBuilder.append(string.substring(from, string.length - 1))
            else stringBuilder.append(string.substring(from, string.length))
          }
        }
        chunkBuilder.result()
      }

      lazy val loop: ZChannel[Any, ZNothing, Chunk[String], Any, Nothing, Chunk[String], Any] =
        ZChannel.readWithCause(
          in => {
            val out = splitLinesChunk(in)
            if (out.isEmpty) loop else ZChannel.write(out) *> loop
          },
          err =>
            if (stringBuilder.isEmpty) ZChannel.refailCause(err)
            else ZChannel.write(Chunk.single(stringBuilder.result)) *> ZChannel.refailCause(err),
          done =>
            if (stringBuilder.isEmpty) ZChannel.succeed(done)
            else ZChannel.write(Chunk.single(stringBuilder.result)) *> ZChannel.succeed(done)
        )

      new ZPipeline(loop)
    }

  /**
   * Lazily constructs a pipeline.
   */
  def suspend[Env, Err, In, Out](pipeline: => ZPipeline[Env, Err, In, Out]): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(ZChannel.suspend(pipeline.channel))

  /**
   * Creates a pipeline that takes n elements.
   */
  def take[In](n: => Long)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    ZPipeline.suspend {

      def loop(n: Long): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
        ZChannel
          .readWithCause(
            (chunk: Chunk[In]) => {
              val taken    = chunk.take(n.min(Int.MaxValue).toInt)
              val leftover = (n - taken.length).max(0)
              val more     = leftover > 0

              if (more)
                ZChannel.write(taken) *> loop(leftover)
              else ZChannel.write(taken)
            },
            ZChannel.refailCause,
            ZChannel.succeed(_)
          )

      new ZPipeline(
        if (0 < n)
          loop(n)
        else
          ZChannel.unit
      )
    }

  /**
   * Creates a pipeline that takes elements until the specified predicate
   * evaluates to true.
   */
  def takeUntil[In](f: In => Boolean)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] = {
    lazy val loop: ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
      ZChannel
        .readWithCause(
          (chunk: Chunk[In]) => {
            val taken = chunk.takeWhile(!f(_))
            val last  = chunk.drop(taken.length).take(1)

            if (last.isEmpty) ZChannel.write(taken) *> loop
            else ZChannel.write(taken ++ last)
          },
          ZChannel.refailCause,
          ZChannel.succeed(_)
        )

    new ZPipeline(loop)
  }

  def takeUntilZIO[Env, Err, In](
    f: In => ZIO[Env, Err, Boolean]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] = {
    lazy val read: ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[In], Any] =
      ZChannel.readWithCause(
        elem => write(elem.chunkIterator, 0),
        err => ZChannel.refailCause(err),
        done => ZChannel.succeed(done)
      )

    def write(
      chunkIterator: Chunk.ChunkIterator[In],
      index: Int
    ): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[In], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          f(a).map { b =>
            if (b) ZChannel.write(Chunk.single(a))
            else ZChannel.write(Chunk.single(a)) *> write(chunkIterator, index + 1)
          }
        }
      else read

    new ZPipeline(read)
  }

  /**
   * Creates a pipeline that takes elements while the specified predicate
   * evaluates to true.
   */
  def takeWhile[In](f: In => Boolean)(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] = {
    lazy val loop: ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[In], Any] =
      ZChannel
        .readWithCause(
          (chunk: Chunk[In]) => {
            val taken = chunk.takeWhile(f)
            val more  = taken.length == chunk.length

            if (more) ZChannel.write(taken) *> loop
            else ZChannel.write(taken)
          },
          ZChannel.refailCause,
          ZChannel.succeed(_)
        )

    new ZPipeline(loop)
  }

  /**
   * Creates a pipeline that takes elements while the specified effectual
   * predicate evaluates to true.
   */
  def takeWhileZIO[Env, Err, In](
    f: In => ZIO[Env, Err, Boolean]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] = {
    lazy val read: ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[In], Any] =
      ZChannel.readWithCause(
        elem => write(elem.chunkIterator, 0),
        err => ZChannel.refailCause(err),
        done => ZChannel.succeed(done)
      )

    def write(
      chunkIterator: Chunk.ChunkIterator[In],
      index: Int
    ): ZChannel[Env, ZNothing, Chunk[In], Any, Err, Chunk[In], Any] =
      if (chunkIterator.hasNextAt(index))
        ZChannel.unwrap {
          val a = chunkIterator.nextAt(index)
          f(a).map { b =>
            if (b) ZChannel.write(Chunk.single(a)) *> write(chunkIterator, index + 1)
            else ZChannel.unit
          }
        }
      else read

    new ZPipeline(read)
  }

  /**
   * Adds an effect to consumption of every element of the pipeline.
   */
  def tap[Env, Err, In](f: In => ZIO[Env, Err, Any])(implicit trace: Trace): ZPipeline[Env, Err, In, In] =
    new ZPipeline(ZChannel.identity[Err, Chunk[In], Any].mapOutZIO(_.mapZIO(in => f(in).as(in))))

  /**
   * Throttles the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` function.
   */
  def throttleEnforce[In](units: Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[In] => Long
  )(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    throttleEnforceZIO(units, duration, burst)(costFn.andThen(ZIO.succeed(_)))

  /**
   * Throttles the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. Chunks that do not meet the bandwidth
   * constraints are dropped. The weight of each chunk is determined by the
   * `costFn` effectful function.
   */
  def throttleEnforceZIO[Env, Err, In](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[In] => ZIO[Env, Err, Long]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] =
    new ZPipeline(
      ZChannel.succeed((units, duration, burst)).flatMap { case (units, duration, burst) =>
        def loop(tokens: Long, timestamp: Long): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
          ZChannel.readWithCause[Env, Err, Chunk[In], Any, Err, Chunk[In], Unit](
            (in: Chunk[In]) =>
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
                  loop(tokens, timestamp)
              }),
            (e: Cause[Err]) => ZChannel.refailCause(e),
            (_: Any) => ZChannel.unit
          )

        ZChannel.unwrap(Clock.nanoTime.map(loop(units, _)))
      }
    )

  /**
   * Delays the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` function.
   */
  def throttleShape[In](units: => Long, duration: => Duration, burst: Long = 0)(
    costFn: Chunk[In] => Long
  )(implicit trace: Trace): ZPipeline[Any, Nothing, In, In] =
    throttleShapeZIO(units, duration, burst)(costFn.andThen(ZIO.succeed(_)))

  /**
   * Delays the chunks of this pipeline according to the given bandwidth
   * parameters using the token bucket algorithm. Allows for burst in the
   * processing of elements by allowing the token bucket to accumulate tokens up
   * to a `units + burst` threshold. The weight of each chunk is determined by
   * the `costFn` effectful function.
   */
  def throttleShapeZIO[Env, Err, In](units: => Long, duration: => Duration, burst: => Long = 0)(
    costFn: Chunk[In] => ZIO[Env, Err, Long]
  )(implicit trace: Trace): ZPipeline[Env, Err, In, In] = new ZPipeline(
    ZChannel.succeed((units, duration, burst)).flatMap { case (units, duration, burst) =>
      def loop(tokens: Long, timestamp: Long): ZChannel[Env, Err, Chunk[In], Any, Err, Chunk[In], Unit] =
        ZChannel.readWithCause(
          (in: Chunk[In]) =>
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
          (e: Cause[Err]) => ZChannel.refailCause(e),
          (_: Any) => ZChannel.unit
        )

      ZChannel.unwrap(Clock.nanoTime.map(loop(units, _)))
    }
  )

  /**
   * Creates a pipeline produced from an effect.
   */
  def unwrap[Env, Err, In, Out](zio: ZIO[Env, Err, ZPipeline[Env, Err, In, Out]])(implicit
    trace: Trace
  ): ZPipeline[Env, Err, In, Out] =
    new ZPipeline(ZChannel.unwrap(zio.map(_.channel)))

  /**
   * Created a pipeline produced from a scoped effect.
   */
  def unwrapScoped[Env]: UnwrapScopedPartiallyApplied[Env] =
    new UnwrapScopedPartiallyApplied[Env]

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the US ASCII charset
   */
  def usASCIIDecode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(StandardCharsets.US_ASCII)

  /**
   * utfDecode determines the right encoder to use based on the Byte Order Mark
   * (BOM). If it doesn't detect one, it defaults to utf8Decode. In the case of
   * utf16 and utf32 without BOM, `utf16Decode` and `utf32Decode` should be used
   * instead as both default to their own default decoder respectively.
   */
  def utfDecode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    utfDecodeDetectingBom(
      bomSize = 4,
      {
        case bytes @ BOM.Utf32BE if Charset.isSupported(CharsetUtf32BE.name) =>
          bytes -> utf32BEDecode
        case bytes @ BOM.Utf32LE if Charset.isSupported(CharsetUtf32LE.name) =>
          bytes -> utf32LEDecode
        case bytes if bytes.take(3) == BOM.Utf8 =>
          bytes.drop(3) -> utf8DecodeNoBom
        case bytes if bytes.take(2) == BOM.Utf16BE =>
          bytes.drop(2) -> utf16BEDecode
        case bytes if bytes.take(2) == BOM.Utf16LE =>
          bytes.drop(2) -> utf16LEDecode
        case bytes =>
          bytes -> utf8DecodeNoBom
      }
    )

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_8 charset
   */
  def utf8Decode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    utfDecodeDetectingBom(
      bomSize = 3,
      {
        case BOM.Utf8 =>
          Chunk.empty -> utf8DecodeNoBom
        case bytes =>
          bytes -> utf8DecodeNoBom
      }
    )

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_16 charset
   */
  def utf16Decode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    utfDecodeDetectingBom(
      bomSize = 2,
      {
        case BOM.Utf16BE =>
          Chunk.empty -> utf16BEDecode
        case BOM.Utf16LE =>
          Chunk.empty -> utf16LEDecode
        case bytes =>
          bytes -> utf16BEDecode
      }
    )

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_16BE charset
   */
  def utf16BEDecode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(StandardCharsets.UTF_16BE)

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_16LE charset
   */
  def utf16LEDecode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(StandardCharsets.UTF_16LE)

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_32 charset
   */
  def utf32Decode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    utfDecodeDetectingBom(
      bomSize = 4,
      {
        case bytes @ BOM.Utf32LE =>
          bytes -> utf32LEDecode
        case bytes =>
          bytes -> utf32BEDecode
      }
    )

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_32BE charset
   */
  def utf32BEDecode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(CharsetUtf32BE)

  /**
   * Creates a pipeline that converts a stream of bytes into a stream of strings
   * using the UTF_32LE charset
   */
  def utf32LEDecode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(CharsetUtf32LE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the US ASCII charset
   */
  def usASCIIEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.US_ASCII)

//    `utf*Encode` pipelines adhere to the same behavior of Java's
//    String#getBytes(charset)`, that is:
//      - utf8: No BOM
//      - utf16: Has BOM (the outlier)
//      - utf16BE & utf16LE: No BOM
//      - All utf32 variants: No BOM
//
//    If BOM is required, users can use the `*WithBomEncode` variants. (As
//    alluded above, `utf16Encode` always prepends BOM, just like
//    `getBytes("UTF-16")` in Java. In fact, it is an alias to both
//    `utf16BEWithBomEncode` and `utf16WithBomEncode`.

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_8 charset, without adding a BOM
   */
  def utf8Encode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.UTF_8)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_8 charset prefixing it with a BOM
   */
  def utf8WithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.UTF_8, bom = BOM.Utf8)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_16BE charset, without adding a BOM
   */
  def utf16BEEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.UTF_16BE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_16BE charset prefixing it with a BOM
   */
  def utf16BEWithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.UTF_16BE, bom = BOM.Utf16BE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_16LE charset, without adding a BOM
   */
  def utf16LEEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.UTF_16LE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_16LE charset prefixing it with a BOM
   */
  def utf16LEWithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(StandardCharsets.UTF_16LE, bom = BOM.Utf16LE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_16BE charset prefixing it with a BOM
   */
  def utf16Encode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    utf16BEWithBomEncode

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_16 charset prefixing it with a BOM
   */
  def utf16WithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    utf16BEWithBomEncode

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_32BE charset, without adding a BOM
   */
  def utf32BEEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(CharsetUtf32BE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_32BE charset prefixing it with a BOM
   */
  def utf32BEWithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(CharsetUtf32BE, bom = BOM.Utf32BE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_32LE charset, without adding a BOM
   */
  def utf32LEEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(CharsetUtf32LE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_32LE charset prefixing it with a BOM
   */
  def utf32LEWithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    encodeStringWith(CharsetUtf32LE, bom = BOM.Utf32LE)

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_32BE charset, without adding a BOM
   */
  def utf32Encode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    utf32BEEncode

  /**
   * Creates a pipeline that converts a stream of strings into a stream of bytes
   * using the UTF_32BE charset prefixing it with a BOM
   */
  def utf32WithBomEncode(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, String, Byte] =
    utf32BEWithBomEncode

  /**
   * Zips this pipeline together with the index of elements.
   */
  def zipWithIndex[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, (In, Long)] =
    ZPipeline.mapAccum(0L)((index, a) => (index + 1, (a, index)))

  /**
   * Zips each element with the next element if present.
   */
  def zipWithNext[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, (In, Option[In])] = {
    def process(last: Option[In]): ZChannel[Any, ZNothing, Chunk[In], Any, Nothing, Chunk[(In, Option[In])], Unit] =
      ZChannel.readWithCause(
        (in: Chunk[In]) => {
          val (newLast, chunk) = in.mapAccum(last)((prev, curr) => (Some(curr), prev.map((_, curr))))
          val out              = chunk.collect { case Some((prev, curr)) => (prev, Some(curr)) }
          ZChannel.write(out) *> process(newLast)
        },
        (err: Cause[ZNothing]) => ZChannel.refailCause(err),
        (_: Any) =>
          last match {
            case Some(value) =>
              ZChannel.write(Chunk.single((value, None))) *> ZChannel.unit
            case None =>
              ZChannel.unit
          }
      )

    new ZPipeline(process(None))
  }

  def zipWithPrevious[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, (Option[In], In)] =
    mapAccum[In, Option[In], (Option[In], In)](None)((prev, curr) => (Some(curr), (prev, curr)))

  def zipWithPreviousAndNext[In](implicit trace: Trace): ZPipeline[Any, Nothing, In, (Option[In], In, Option[In])] =
    (zipWithPrevious[In].zipWithNext).map { case ((prev, curr), next) => (prev, curr, next.map(_._2)) }

  private def utfDecodeDetectingBom(
    bomSize: => Int,
    processBom: Chunk[Byte] => (
      Chunk[Byte],
      ZPipeline[Any, CharacterCodingException, Byte, String]
    )
  )(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    ZPipeline.suspend {

      type DecodingChannel = ZChannel[Any, ZNothing, Chunk[Byte], Any, CharacterCodingException, Chunk[String], Any]

      def lookingForBom(buffer: Chunk[Byte], bomSize: Int): DecodingChannel =
        ZChannel.readWithCause(
          received => {
            val data = buffer ++ received

            if (data.length >= bomSize) {
              val (bom, rest)                      = data.splitAt(bomSize)
              val (bomRemainder, decodingPipeline) = processBom(bom)

              val decoderChannel = decodingPipeline.channel
              (ZChannel.write(bomRemainder ++ rest) *> ZChannel.identity[ZNothing, Chunk[Byte], Any]) >>> decoderChannel
            } else {
              lookingForBom(data, bomSize)
            }
          },
          halt = ZChannel.refailCause,
          done = _ =>
            if (buffer.isEmpty) ZChannel.unit
            else {
              val (bomRemainder, decodingPipeline) = processBom(buffer)
              (ZChannel
                .write(bomRemainder) *> ZChannel.identity[ZNothing, Chunk[Byte], Any]) >>> decodingPipeline.channel
            }
        )

      new ZPipeline(lookingForBom(Chunk.empty, bomSize))
    }

  private def utf8DecodeNoBom(implicit trace: Trace): ZPipeline[Any, CharacterCodingException, Byte, String] =
    decodeStringWith(StandardCharsets.UTF_8)

  final class EnvironmentWithPipelinePartiallyApplied[Env](private val dummy: Boolean = true) extends AnyVal {
    def apply[Env1 <: Env, Err, In, Out](f: ZEnvironment[Env] => ZPipeline[Env1, Err, In, Out])(implicit
      trace: Trace
    ): ZPipeline[Env with Env1, Err, In, Out] =
      ZPipeline.unwrap(ZIO.environmentWith(f))
  }

  final class ServiceWithPipelinePartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Env <: Service, Err, In, Out](f: Service => ZPipeline[Env, Err, In, Out])(implicit
      tag: Tag[Service],
      trace: Trace
    ): ZPipeline[Env with Service, Err, In, Out] =
      ZPipeline.unwrap(ZIO.serviceWith[Service](f))
  }

  final class UnwrapScopedPartiallyApplied[Env](private val dummy: Boolean = true) extends AnyVal {
    def apply[Err, In, Out](scoped: => ZIO[Scope with Env, Err, ZPipeline[Env, Err, In, Out]])(implicit
      trace: Trace
    ): ZPipeline[Env, Err, In, Out] =
      new ZPipeline(ZChannel.unwrapScoped[Env](scoped.map(_.channel)))
  }
}
