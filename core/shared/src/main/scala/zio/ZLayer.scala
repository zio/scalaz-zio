/*
 * Copyright 2020-2022 John A. De Goes and the ZIO Contributors
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

import zio.ZManaged.ReleaseMap
import zio.duration.Duration
import zio.internal.Platform

import scala.collection.mutable.Builder

/**
 * A `ZLayer[A, E, B]` describes a layer of an application: every layer in an
 * application requires some services (the input) and produces some services
 * (the output).
 *
 * Layers can be thought of as recipes for producing bundles of services, given
 * their dependencies (other services).
 *
 * Construction of layers can be effectful and utilize resources that must be
 * acquired and safely released when the services are done being utilized.
 *
 * By default layers are shared, meaning that if the same layer is used twice
 * the layer will only be allocated a single time.
 *
 * Because of their excellent composition properties, layers are the idiomatic
 * way in ZIO to create services that depend on other services.
 */
sealed abstract class ZLayer[-RIn, +E, +ROut] { self =>

  /**
   * A symbolic alias for `orDie`.
   */
  final def !(implicit ev1: E <:< Throwable, ev2: CanFail[E]): ZLayer[RIn, Nothing, ROut] =
    self.orDie

  final def +!+[E1 >: E, RIn2, ROut1 >: ROut, ROut2](
    that: ZLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.UnionAll[ROut1, ROut2]): ZLayer[RIn with RIn2, E1, ROut1 with ROut2] =
    self.zipWithPar(that)(ev.unionAll)

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers.
   */
  final def ++[E1 >: E, RIn2, ROut1 >: ROut, ROut2](
    that: ZLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2]): ZLayer[RIn with RIn2, E1, ROut1 with ROut2] =
    self.zipWithPar(that)(ev.union)

  /**
   * A symbolic alias for `zipPar`.
  */
  final def <&>[E1 >: E, RIn2, ROut2](that: ZLayer[RIn2, E1, ROut2]): ZLayer[RIn with RIn2, E1, (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * A symbolic alias for `orElse`.
   */
  def <>[RIn1 <: RIn, E1, ROut1 >: ROut](
    that: ZLayer[RIn1, E1, ROut1]
  )(implicit ev: CanFail[E]): ZLayer[RIn1, E1, ROut1] =
    self.orElse(that)

  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of both this layer and the specified layer.
   */
  final def >+>[E1 >: E, RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: ZLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): ZLayer[RIn, E1, ROut1 with ROut2] =
    ZLayer.ZipWith(self, self >>> that, ev.union)

  /**
   * Feeds the output services of this layer into the input of the specified
   * layer, resulting in a new layer with the inputs of this layer, and the
   * outputs of the specified layer.
   */
  final def >>>[E1 >: E, ROut2](that: ZLayer[ROut, E1, ROut2]): ZLayer[RIn, E1, ROut2] =
    fold(ZLayer.fromFunctionManyM { case (_, cause) => ZIO.halt(cause) }, that)

  /**
   * A named alias for `++`.
   */
  final def and[E1 >: E, RIn2, ROut1 >: ROut, ROut2](
    that: ZLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): ZLayer[RIn with RIn2, E1, ROut1 with ROut2] =
    self.++[E1, RIn2, ROut1, ROut2](that)

  /**
   * A named alias for `>+>`.
   */
  final def andTo[E1 >: E, RIn2 >: ROut, ROut1 >: ROut, ROut2](
    that: ZLayer[RIn2, E1, ROut2]
  )(implicit ev: Has.Union[ROut1, ROut2], tagged: Tag[ROut2]): ZLayer[RIn, E1, ROut1 with ROut2] =
    self.>+>[E1, RIn2, ROut1, ROut2](that)

  /**
   * Builds a layer into a managed value.
   */
  final def build: ZManaged[RIn, E, ROut] =
    for {
      memoMap <- ZLayer.MemoMap.make.toManaged_
      run     <- self.scope
      value   <- run(memoMap)
    } yield value

  /**
   * Recovers from all errors.
   */
  final def catchAll[RIn1 <: RIn, E1, ROut1 >: ROut](
    handler: ZLayer[(RIn1, E), E1, ROut1]
  ): ZLayer[RIn1, E1, ROut1] = {
    val failureOrDie: ZLayer[(RIn1, Cause[E]), Nothing, (RIn1, E)] =
      ZLayer.fromFunctionManyM {
        case (r, cause) =>
          cause.failureOrCause.fold(
            e => ZIO.succeed((r, e)),
            c => ZIO.halt(c)
          )
      }
    fold(failureOrDie >>> handler, ZLayer.identity)
  }

  /**
    * Constructs a layer dynamically based on the output of this layer.
    */
   final def flatMap[RIn1 <: RIn, E1 >: E, ROut2](
     f: ROut => ZLayer[RIn1, E1, ROut2]
   ): ZLayer[RIn1, E1, ROut2] =
     ZLayer.Flatten(self.map(f))

  /**
   * Feeds the error or output services of this layer into the input of either
   * the specified `failure` or `success` layers, resulting in a new layer with
   * the inputs of this layer, and the error or outputs of the specified layer.
   */
  final def fold[E1, RIn1 <: RIn, ROut2](
    failure: ZLayer[(RIn1, Cause[E]), E1, ROut2],
    success: ZLayer[ROut, E1, ROut2]
  )(implicit ev: CanFail[E]): ZLayer[RIn1, E1, ROut2] =
    ZLayer.Fold(self, failure, success)

  /**
   * Creates a fresh version of this layer that will not be shared.
   */
  final def fresh: ZLayer[RIn, E, ROut] =
    ZLayer.Fresh(self)

  /**
    * Returns the hash code of this layer.
    */
  override final lazy val hashCode: Int =
    super.hashCode

  /**
   * Builds this layer and uses it until it is interrupted. This is useful when
   * your entire application is a layer, such as an HTTP server.
   */
  final def launch(implicit ev: Any <:< RIn): IO[E, Nothing] =
    build.provide(ev).useForever

  /**
   * Returns a new layer whose output is mapped by the specified function.
   */
  final def map[ROut1](f: ROut => ROut1): ZLayer[RIn, E, ROut1] =
    self >>> ZLayer.fromFunctionMany(f)

  /**
   * Returns a layer with its error channel mapped using the specified
   * function.
   */
  final def mapError[E1](f: E => E1)(implicit ev: CanFail[E]): ZLayer[RIn, E1, ROut] =
    catchAll(ZLayer.second >>> ZLayer.fromFunctionManyM(e => ZIO.fail(f(e))))

  /**
   * Returns a managed effect that, if evaluated, will return the lazily
   * computed result of this layer.
   */
  final def memoize: ZManaged[Any, Nothing, ZLayer[RIn, E, ROut]] =
    build.memoize.map(ZLayer(_))

  /**
   * Translates effect failure into death of the fiber, making all failures
   * unchecked and not a part of the type of the layer.
   */
  final def orDie(implicit ev1: E <:< Throwable, ev2: CanFail[E]): ZLayer[RIn, Nothing, ROut] =
    catchAll(ZLayer.second >>> ZLayer.fromFunctionManyM(ZIO.die(_)))

  /**
   * Executes this layer and returns its output, if it succeeds, but otherwise
   * executes the specified layer.
   */
  final def orElse[RIn1 <: RIn, E1, ROut1 >: ROut](
    that: ZLayer[RIn1, E1, ROut1]
  )(implicit ev: CanFail[E]): ZLayer[RIn1, E1, ROut1] =
    catchAll(ZLayer.first >>> that)

  /**
   * Retries constructing this layer according to the specified schedule.
   */
  final def retry[RIn1 <: RIn with clock.Clock](schedule: Schedule[RIn1, E, Any]): ZLayer[RIn1, E, ROut] = {
    import Schedule.StepFunction
    import Schedule.Decision._

    type S = StepFunction[RIn1, E, Any]

    lazy val loop: ZLayer[(RIn1, S), E, ROut] =
      (ZLayer.first >>> self).catchAll {
        val update: ZLayer[((RIn1, S), E), E, (RIn1, S)] =
          ZLayer.fromFunctionManyM {
            case ((r, s), e) =>
              clock.currentDateTime.orDie.flatMap(now => s(now, e).flatMap {
                case Done(_) => ZIO.fail(e)
                case Continue(_, interval, next) => clock.sleep(Duration.fromInterval(now, interval)) as ((r, next))
              }).provide(r)
          }
        update >>> ZLayer.suspend(loop.fresh)
      }
    ZLayer.identity <&> ZLayer.fromEffectMany(ZIO.succeed(schedule.step)) >>> loop
  }

  /**
    * Performs the specified effect if this layer succeeds.
    */
  final def tap[RIn1 <: RIn, E1 >: E](f: ROut => ZIO[RIn1, E1, Any]): ZLayer[RIn1, E1, ROut] =
    ZLayer.identity <&> self >>> ZLayer.fromFunctionManyM { case (in, out) => f(out).provide(in) *> ZIO.succeed(out) }

  /**
   * Performs the specified effect if this layer fails.
   */
  final def tapError[RIn1 <: RIn, E1 >: E](f: E => ZIO[RIn1, E1, Any]): ZLayer[RIn1, E1, ROut] =
    catchAll(ZLayer.fromFunctionManyM { case (r, e) => f(e).provide(r) *> ZIO.fail(e) })

  /**
   * A named alias for `>>>`.
   */
  final def to[E1 >: E, ROut2](that: ZLayer[ROut, E1, ROut2]): ZLayer[RIn, E1, ROut2] =
    self >>> that

  /**
   * Converts a layer that requires no services into a managed runtime, which
   * can be used to execute effects.
   */
  final def toRuntime(p: Platform)(implicit ev: Any <:< RIn): Managed[E, Runtime[ROut]] =
    build.provide(ev).map(Runtime(_, p))

  /**
   * Updates one of the services output by this layer.
   */
  final def update[A: Tag](f: A => A)(implicit ev1: Has.IsHas[ROut], ev2: ROut <:< Has[A]): ZLayer[RIn, E, ROut] =
    self >>> ZLayer.fromFunctionMany(ev1.update[ROut, A](_, f))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * into a tuple.
   */
  final def zipPar[E1 >: E, RIn2, ROut2](that: ZLayer[RIn2, E1, ROut2]): ZLayer[RIn with RIn2, E1, (ROut, ROut2)] =
    zipWithPar(that)((_, _))

  /**
   * Combines this layer with the specified layer, producing a new layer that
   * has the inputs of both layers, and the outputs of both layers combined
   * using the specified function.
   */
  final def zipWithPar[E1 >: E, RIn2, ROut1 >: ROut, ROut2, ROut3](
    that: ZLayer[RIn2, E1, ROut2]
  )(f: (ROut, ROut2) => ROut3): ZLayer[RIn with RIn2, E1, ROut3] =
    ZLayer.ZipWithPar(self, that, f)

  /**
    * Returns whether this layer is a fresh version that will not be shared.
    */
  private final def isFresh: Boolean =
    self match {
      case ZLayer.Fresh(_) => true
      case _ => false
    }

  private final def scope: Managed[Nothing, ZLayer.MemoMap => ZManaged[RIn, E, ROut]] =
    self match {
      case ZLayer.Flatten(self) =>
         ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).flatMap(memoMap.getOrElseMemoize))
      case ZLayer.Fold(self, failure, success) =>
        ZManaged.succeed(memoMap =>
          memoMap
            .getOrElseMemoize(self)
            .foldCauseM(
              e => ZManaged.environment[RIn].flatMap(r => memoMap.getOrElseMemoize(failure).provide((r, e))),
              r => memoMap.getOrElseMemoize(success).provide(r)(NeedsEnv.needsEnv)
            )
        )
      case ZLayer.Fresh(self) =>
        Managed.succeed(_ => self.build)
      case ZLayer.Managed(self) =>
        Managed.succeed(_ => self)
      case ZLayer.Suspend(self) =>
         ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self()))
      case ZLayer.ZipWith(self, that, f) =>
        ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).zipWith(memoMap.getOrElseMemoize(that))(f))   
      case ZLayer.ZipWithPar(self, that, f) =>
        ZManaged.succeed(memoMap => memoMap.getOrElseMemoize(self).zipWithPar(memoMap.getOrElseMemoize(that))(f))
    }
}

object ZLayer {
  @deprecated("use Layer", "1.0.0")
  type NoDeps[+E, +B] = ZLayer[Any, E, B]

  private final case class Flatten[-RIn, +E, +ROut](
     self: ZLayer[RIn, E, ZLayer[RIn, E, ROut]]
   ) extends ZLayer[RIn, E, ROut]
  private final case class Fold[RIn, E, E1, ROut, ROut1](
    self: ZLayer[RIn, E, ROut],
    failure: ZLayer[(RIn, Cause[E]), E1, ROut1],
    success: ZLayer[ROut, E1, ROut1]
  ) extends ZLayer[RIn, E1, ROut1]
  private final case class Fresh[RIn, E, ROut](self: ZLayer[RIn, E, ROut])        extends ZLayer[RIn, E, ROut]
  private final case class Managed[-RIn, +E, +ROut](self: ZManaged[RIn, E, ROut]) extends ZLayer[RIn, E, ROut]
  private final case class Suspend[-RIn, +E, +ROut](self: () => ZLayer[RIn, E, ROut]) extends ZLayer[RIn, E, ROut]
  private final case class ZipWith[-RIn, +E, ROut, ROut2, ROut3](
    self: ZLayer[RIn, E, ROut],
    that: ZLayer[RIn, E, ROut2],
    f: (ROut, ROut2) => ROut3
  ) extends ZLayer[RIn, E, ROut3]
  private final case class ZipWithPar[-RIn, +E, ROut, ROut2, ROut3](
    self: ZLayer[RIn, E, ROut],
    that: ZLayer[RIn, E, ROut2],
    f: (ROut, ROut2) => ROut3
  ) extends ZLayer[RIn, E, ROut3]

  /**
   * Constructs a layer from a managed resource.
   */
  def apply[RIn, E, ROut](managed: ZManaged[RIn, E, ROut]): ZLayer[RIn, E, ROut] =
    Managed(managed)

  /**
    * Gathers up the ZLayers inside of the given collection, and combines them into a single ZLayer containing
    * an equivalent collection of results.
    */
  def collectAll[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZLayer[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZLayer[R, E, A]], A, Collection[A]]): ZLayer[R, E, Collection[A]] =
    foreach(in)(i => i)

  /**
   * Constructs a layer that fails with the specified value.
   */
  def fail[E](e: E): Layer[E, Nothing] =
    ZLayer(ZManaged.fail(e))

  /**
   * A layer that passes along the first element of a tuple.
   */
  def first[A]: ZLayer[(A, Any), Nothing, A] =
    ZLayer.fromFunctionMany(_._1)

  /**
    * Applies the function `f` to each element of the `Collection[A]` and
    * returns the results in a new `Collection[B]`.
    */
  def foreach[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => ZLayer[R, E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZLayer[R, E, Collection[B]] =
    in.foldLeft[ZLayer[R, E, Builder[B, Collection[B]]]](ZLayer.succeedMany(bf.newBuilder(in)))((io, a) =>
      io.zipWithPar(f(a))(_ += _)
    ).map(_.result())

  /**
   * Constructs a layer from acquire and release actions. The acquire and
   * release actions will be performed uninterruptibly.
   */
  def fromAcquireRelease[R, E, A: Tag](acquire: ZIO[R, E, A])(release: A => URIO[R, Any]): ZLayer[R, E, Has[A]] =
    fromManaged(ZManaged.make(acquire)(release))

  /**
   * Constructs a layer from acquire and release actions, which must return one
   * or more services. The acquire and release actions will be performed
   * uninterruptibly.
   */
  def fromAcquireReleaseMany[R, E, A](acquire: ZIO[R, E, A])(release: A => URIO[R, Any]): ZLayer[R, E, A] =
    fromManagedMany(ZManaged.make(acquire)(release))

  /**
   * Constructs a layer from the specified effect.
   */
  def fromEffect[R, E, A: Tag](zio: ZIO[R, E, A]): ZLayer[R, E, Has[A]] =
    fromEffectMany(zio.asService)

  /**
   * Constructs a layer from the specified effect, which must return one or
   * more services.
   */
  def fromEffectMany[R, E, A](zio: ZIO[R, E, A]): ZLayer[R, E, A] =
    ZLayer(ZManaged.fromEffect(zio))

  /**
   * Constructs a layer from the environment using the specified function.
   */
  def fromFunction[A, B: Tag](f: A => B): ZLayer[A, Nothing, Has[B]] =
    fromFunctionM(a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer from the environment using the specified effectful
   * function.
   */
  def fromFunctionM[A, E, B: Tag](f: A => IO[E, B]): ZLayer[A, E, Has[B]] =
    fromFunctionManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer from the environment using the specified effectful
   * resourceful function.
   */
  def fromFunctionManaged[A, E, B: Tag](f: A => ZManaged[Any, E, B]): ZLayer[A, E, Has[B]] =
    fromManaged(ZManaged.fromFunctionM(f))

  /**
   * Constructs a layer from the environment using the specified function,
   * which must return one or more services.
   */
  def fromFunctionMany[A, B](f: A => B): ZLayer[A, Nothing, B] =
    fromFunctionManyM(a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer from the environment using the specified effectful
   * function, which must return one or more services.
   */
  def fromFunctionManyM[A, E, B](f: A => IO[E, B]): ZLayer[A, E, B] =
    fromFunctionManyManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer from the environment using the specified effectful
   * resourceful function, which must return one or more services.
   */
  def fromFunctionManyManaged[A, E, B](f: A => ZManaged[Any, E, B]): ZLayer[A, E, B] =
    ZLayer(ZManaged.fromFunctionM(f))

  /**
   * Constructs a layer that purely depends on the specified service.
   */
  def fromService[A: Tag, B: Tag](f: A => B): ZLayer[Has[A], Nothing, Has[B]] =
    fromServiceM[A, Any, Nothing, B](a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, B: Tag](
    f: (A0, A1) => B
  ): ZLayer[Has[A0] with Has[A1], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, B: Tag](
    f: (A0, A1, A2) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, B: Tag](
    f: (A0, A1, A2, A3) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services.
   */
  def fromServices[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], Nothing, Has[B]] = {
    val layer = fromServicesM(andThen(f)(ZIO.succeedNow(_)))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified service.
   */
  def fromServiceM[A: Tag, R, E, B: Tag](f: A => ZIO[R, E, B]): ZLayer[R with Has[A], E, Has[B]] =
    fromServiceManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, R, E, B: Tag](
    f: (A0, A1) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, R, E, B: Tag](
    f: (A0, A1, A2) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services.
   */
  def fromServicesM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], E, Has[B]] = {
    val layer = fromServicesManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified service.
   */
  def fromServiceManaged[A: Tag, R, E, B: Tag](f: A => ZManaged[R, E, B]): ZLayer[R with Has[A], E, Has[B]] =
    fromServiceManyManaged(a => f(a).asService)

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, R, E, B: Tag](
    f: (A0, A1) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, R, E, B: Tag](
    f: (A0, A1, A2) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], E, Has[B]] =
    fromServicesManyManaged[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, R, E, Has[B]]((a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20) => f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20).asService)

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services.
   */
  def fromServicesManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, E, B: Tag](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], E, Has[B]] = {
    val layer = fromServicesManyManaged(andThen(f)(_.asService))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified service, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromService`.
   */
  def fromServiceMany[A: Tag, B](f: A => B): ZLayer[Has[A], Nothing, B] =
    fromServiceManyM[A, Any, Nothing, B](a => ZIO.succeedNow(f(a)))

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, B](
    f: (A0, A1) => B
  ): ZLayer[Has[A0] with Has[A1], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, B](
    f: (A0, A1, A2) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, B](
    f: (A0, A1, A2, A3) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, B](
    f: (A0, A1, A2, A3, A4) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, B](
    f: (A0, A1, A2, A3, A4, A5) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that purely depends on the specified services, which
   * must return one or more services. For the more common variant that returns
   * a single service see `fromServices`.
   */
  def fromServicesMany[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => B
  ): ZLayer[Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], Nothing, B] = {
    val layer = fromServicesManyM(andThen(f)(ZIO.succeedNow))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified service,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServiceM`.
   */
  def fromServiceManyM[A: Tag, R, E, B](f: A => ZIO[R, E, B]): ZLayer[R with Has[A], E, B] =
    fromServiceManyManaged(a => f(a).toManaged_)

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, R, E, B](
    f: (A0, A1) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, R, E, B](
    f: (A0, A1, A2) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, E, B](
    f: (A0, A1, A2, A3) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that effectfully depends on the specified services,
   * which must return one or more services. For the more common variant that
   * returns a single service see `fromServicesM`.
   */
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], E, B] = {
    val layer = fromServicesManyManaged(andThen(f)(_.toManaged_))
    layer
  }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified service, which must return one or more services. For the more
   * common variant that returns a single service see `fromServiceManaged`.
   */
  def fromServiceManyManaged[A: Tag, R, E, B](f: A => ZManaged[R, E, B]): ZLayer[R with Has[A], E, B] =
    ZLayer(ZManaged.accessManaged[R with Has[A]](m => f(m.get[A])))

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, R, E, B](
    f: (A0, A1) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        b  <- f(a0, a1)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, R, E, B](
    f: (A0, A1, A2) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        b  <- f(a0, a1, a2)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, R, E, B](
    f: (A0, A1, A2, A3) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        b  <- f(a0, a1, a2, a3)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        b  <- f(a0, a1, a2, a3, a4)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        b  <- f(a0, a1, a2, a3, a4, a5)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        b  <- f(a0, a1, a2, a3, a4, a5, a6)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7 <- ZManaged.environment[Has[A7]].map(_.get[A7])
        b  <- f(a0, a1, a2, a3, a4, a5, a6, a7)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7 <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8 <- ZManaged.environment[Has[A8]].map(_.get[A8])
        b  <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9], E, B] =
    ZLayer {
      for {
        a0 <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1 <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2 <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3 <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4 <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5 <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6 <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7 <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8 <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9 <- ZManaged.environment[Has[A9]].map(_.get[A9])
        b  <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        a19 <- ZManaged.environment[Has[A19]].map(_.get[A19])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        a19 <- ZManaged.environment[Has[A19]].map(_.get[A19])
        a20 <- ZManaged.environment[Has[A20]].map(_.get[A20])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20)
      } yield b
    }

  /**
   * Constructs a layer that resourcefully and effectfully depends on the
   * specified services, which must return one or more services. For the more
   * common variant that returns a single service see `fromServicesManaged`.
   */
  def fromServicesManyManaged[A0: Tag, A1: Tag, A2: Tag, A3: Tag, A4: Tag, A5: Tag, A6: Tag, A7: Tag, A8: Tag, A9: Tag, A10: Tag, A11: Tag, A12: Tag, A13: Tag, A14: Tag, A15: Tag, A16: Tag, A17: Tag, A18: Tag, A19: Tag, A20: Tag, A21: Tag, R, E, B](
    f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => ZManaged[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3] with Has[A4] with Has[A5] with Has[A6] with Has[A7] with Has[A8] with Has[A9] with Has[A10] with Has[A11] with Has[A12] with Has[A13] with Has[A14] with Has[A15] with Has[A16] with Has[A17] with Has[A18] with Has[A19] with Has[A20] with Has[A21], E, B] =
    ZLayer {
      for {
        a0  <- ZManaged.environment[Has[A0]].map(_.get[A0])
        a1  <- ZManaged.environment[Has[A1]].map(_.get[A1])
        a2  <- ZManaged.environment[Has[A2]].map(_.get[A2])
        a3  <- ZManaged.environment[Has[A3]].map(_.get[A3])
        a4  <- ZManaged.environment[Has[A4]].map(_.get[A4])
        a5  <- ZManaged.environment[Has[A5]].map(_.get[A5])
        a6  <- ZManaged.environment[Has[A6]].map(_.get[A6])
        a7  <- ZManaged.environment[Has[A7]].map(_.get[A7])
        a8  <- ZManaged.environment[Has[A8]].map(_.get[A8])
        a9  <- ZManaged.environment[Has[A9]].map(_.get[A9])
        a10 <- ZManaged.environment[Has[A10]].map(_.get[A10])
        a11 <- ZManaged.environment[Has[A11]].map(_.get[A11])
        a12 <- ZManaged.environment[Has[A12]].map(_.get[A12])
        a13 <- ZManaged.environment[Has[A13]].map(_.get[A13])
        a14 <- ZManaged.environment[Has[A14]].map(_.get[A14])
        a15 <- ZManaged.environment[Has[A15]].map(_.get[A15])
        a16 <- ZManaged.environment[Has[A16]].map(_.get[A16])
        a17 <- ZManaged.environment[Has[A17]].map(_.get[A17])
        a18 <- ZManaged.environment[Has[A18]].map(_.get[A18])
        a19 <- ZManaged.environment[Has[A19]].map(_.get[A19])
        a20 <- ZManaged.environment[Has[A20]].map(_.get[A20])
        a21 <- ZManaged.environment[Has[A21]].map(_.get[A21])
        b   <- f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21)
      } yield b
    }

  /**
   * Constructs a layer from a managed resource.
   */
  def fromManaged[R, E, A: Tag](m: ZManaged[R, E, A]): ZLayer[R, E, Has[A]] =
    ZLayer(m.asService)

  /**
   * Constructs a layer from a managed resource, which must return one or more
   * services.
   */
  def fromManagedMany[R, E, A](m: ZManaged[R, E, A]): ZLayer[R, E, A] =
    ZLayer(m)

  /**
   * An identity layer that passes along its inputs. Note that this represents
   * an identity with respect to the `>>>` operator. It represents an identity
   * with respect to the `++` operator when the environment type is `Any`.
   */
  def identity[A]: ZLayer[A, Nothing, A] =
    ZLayer.requires[A]

  /**
   * Constructs a layer that passes along the specified environment as an
   * output.
   */
  def requires[A]: ZLayer[A, Nothing, A] =
    ZLayer(ZManaged.environment[A])

  /**
   * A layer that passes along the second element of a tuple.
   */
  def second[A]: ZLayer[(Any, A), Nothing, A] =
    ZLayer.fromFunctionMany(_._2)

  /**
   * Constructs a layer that accesses and returns the specified service from
   * the environment.
   */
  def service[A]: ZLayer[Has[A], Nothing, Has[A]] =
    ZLayer(ZManaged.environment[Has[A]])

  /**
   * Constructs a layer from the specified value.
   */
  def succeed[A: Tag](a: A): ULayer[Has[A]] =
    ZLayer(ZManaged.succeedNow(Has(a)))

  /**
   * Constructs a layer from the specified value, which must return one or more
   * services.
   */
  def succeedMany[A](a: A): ULayer[A] =
    ZLayer(ZManaged.succeedNow(a))

  /**
    * Lazily constructs a layer. This is useful to avoid infinite recursion when
    * creating layers that refer to themselves.
    */
   def suspend[RIn, E, ROut](layer: => ZLayer[RIn, E, ROut]): ZLayer[RIn, E, ROut] = {
     lazy val self = layer
     Suspend(() => self)
   }

  implicit final class ZLayerPassthroughOps[RIn, E, ROut](private val self: ZLayer[RIn, E, ROut]) extends AnyVal {

    /**
     * Returns a new layer that produces the outputs of this layer but also
     * passes through the inputs to this layer.
     */
    def passthrough(implicit ev: Has.Union[RIn, ROut], tag: Tag[ROut]): ZLayer[RIn, E, RIn with ROut] =
      ZLayer.identity[RIn] ++ self
  }

  implicit final class ZLayerProjectOps[R, E, A](private val self: ZLayer[R, E, Has[A]]) extends AnyVal {

    /**
     * Projects out part of one of the layers output by this layer using the
     * specified function
     */
    final def project[B: Tag](f: A => B)(implicit tag: Tag[A]): ZLayer[R, E, Has[B]] =
      self >>> ZLayer.fromFunction(r => f(r.get))
  }

  /**
   * A `MemoMap` memoizes dependencies.
   */
  private abstract class MemoMap { self =>

    /**
     * Checks the memo map to see if a dependency exists. If it is, immediately
     * returns it. Otherwise, obtains the dependency, stores it in the memo map,
     * and adds a finalizer to the outer `Managed`.
     */
    def getOrElseMemoize[E, A, B](layer: ZLayer[A, E, B]): ZManaged[A, E, B]
  }

  private object MemoMap {

    /**
     * Constructs an empty memo map.
     */
    def make: UIO[MemoMap] =
      RefM
        .make[Map[ZLayer[Nothing, Any, Any], (IO[Any, Any], ZManaged.Finalizer)]](Map.empty)
        .map { ref =>
          new MemoMap { self =>
            final def getOrElseMemoize[E, A, B](layer: ZLayer[A, E, B]): ZManaged[A, E, B] =
              ZManaged {
                ref.modify { map =>
                  map.get(layer) match {
                    case Some((acquire, release)) =>
                      val cached =
                        ZIO.accessM[(A, ReleaseMap)] {
                          case (_, releaseMap) =>
                            acquire
                              .asInstanceOf[IO[E, B]]
                              .onExit {
                                case Exit.Success(_) => releaseMap.add(release)
                                case Exit.Failure(_) => UIO.unit
                              }
                              .map((release, _))
                        }

                      UIO.succeed((cached, map))

                    case None =>
                      for {
                        observers    <- Ref.make(0)
                        promise      <- Promise.make[E, B]
                        finalizerRef <- Ref.make[ZManaged.Finalizer](ZManaged.Finalizer.noop)

                        resource = ZIO.uninterruptibleMask { restore =>
                          for {
                            env                  <- ZIO.environment[(A, ReleaseMap)]
                            (a, outerReleaseMap) = env
                            innerReleaseMap      <- ZManaged.ReleaseMap.make
                            tp <- restore(layer.scope.flatMap(_.apply(self)).zio.provide((a, innerReleaseMap))).run.flatMap {
                                   case e @ Exit.Failure(cause) =>
                                     promise.halt(cause) *> innerReleaseMap.releaseAll(e, ExecutionStrategy.Sequential) *> ZIO
                                       .halt(cause)

                                   case Exit.Success((_, b)) =>
                                     for {
                                       _ <- finalizerRef.set { (e: Exit[Any, Any]) =>
                                             ZIO.whenM(observers.modify(n => (n == 1, n - 1)))(
                                               innerReleaseMap.releaseAll(e, ExecutionStrategy.Sequential)
                                             )
                                           }
                                       _              <- observers.update(_ + 1)
                                       outerFinalizer <- outerReleaseMap.add(e => finalizerRef.get.flatMap(_.apply(e)))
                                       _              <- promise.succeed(b)
                                     } yield (outerFinalizer, b)
                                 }
                          } yield tp
                        }

                        memoized = (
                          promise.await.onExit {
                            case Exit.Failure(_) => UIO.unit
                            case Exit.Success(_) => observers.update(_ + 1)
                          },
                          (exit: Exit[Any, Any]) => finalizerRef.get.flatMap(_(exit))
                        )
                      } yield (resource, if (layer.isFresh) map else map + (layer -> memoized))

                  }
                }.flatten
              }
          }
        }
  }

  private def andThen[A0, A1, B, C](f: (A0, A1) => B)(g: B => C): (A0, A1) => C =
    (a0, a1) => g(f(a0, a1))

  private def andThen[A0, A1, A2, B, C](f: (A0, A1, A2) => B)(g: B => C): (A0, A1, A2) => C =
    (a0, a1, a2) => g(f(a0, a1, a2))

  private def andThen[A0, A1, A2, A3, B, C](f: (A0, A1, A2, A3) => B)(g: B => C): (A0, A1, A2, A3) => C =
    (a0, a1, a2, a3) => g(f(a0, a1, a2, a3))

  private def andThen[A0, A1, A2, A3, A4, B, C](f: (A0, A1, A2, A3, A4) => B)(g: B => C): (A0, A1, A2, A3, A4) => C =
    (a0, a1, a2, a3, a4) => g(f(a0, a1, a2, a3, a4))

  private def andThen[A0, A1, A2, A3, A4, A5, B, C](f: (A0, A1, A2, A3, A4, A5) => B)(g: B => C): (A0, A1, A2, A3, A4, A5) => C =
    (a0, a1, a2, a3, a4, a5) => g(f(a0, a1, a2, a3, a4, a5))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, B, C](f: (A0, A1, A2, A3, A4, A5, A6) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6) => C =
    (a0, a1, a2, a3, a4, a5, a6) => g(f(a0, a1, a2, a3, a4, a5, a6))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7) => g(f(a0, a1, a2, a3, a4, a5, a6, a7))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20))

  private def andThen[A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, B, C](f: (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => B)(g: B => C): (A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21) => C =
    (a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21) => g(f(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21))
}
