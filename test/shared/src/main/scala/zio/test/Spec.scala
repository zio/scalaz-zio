/*
 * Copyright 2019-2022 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.Spec._

/**
 * A `Spec[R, E, T]` is the backbone of _ZIO Test_. Every spec is either a
 * suite, which contains other specs, or a test of type `T`. All specs require
 * an environment of type `R` and may potentially fail with an error of type
 * `E`.
 */
final case class Spec[-R, +E, +T](caseValue: SpecCase[R, E, T, Spec[R, E, T]]) extends SpecVersionSpecific[R, E, T] {
  self =>

  /**
   * Combines this spec with the specified spec.
   */
  def +[R1 <: R, E1 >: E, T1 >: T](that: Spec[R1, E1, T1]): Spec[R1, E1, T1] =
    (self.caseValue, that.caseValue) match {
      case (MultipleCase(self), MultipleCase(that)) => Spec.multiple(self ++ that)
      case (MultipleCase(self), _)                  => Spec.multiple(self :+ that)
      case (_, MultipleCase(that))                  => Spec.multiple(self +: that)
      case _                                        => Spec.multiple(Chunk(self, that))
    }

  /**
   * Syntax for adding aspects.
   * {{{
   * test("foo") { assert(42, equalTo(42)) } @@ ignore
   * }}}
   */
  final def @@[R0 <: R1, R1 <: R, E0, E1, E2 >: E0 <: E1](
    aspect: TestAspect[R0, R1, E0, E1]
  )(implicit ev1: E <:< TestFailure[E2], ev2: T <:< TestSuccess, trace: ZTraceElement): ZSpec[R1, E2] =
    aspect(self.asInstanceOf[ZSpec[R1, E2]])

  /**
   * Annotates each test in this spec with the specified test annotation.
   */
  final def annotate[V](key: TestAnnotation[V], value: V)(implicit trace: ZTraceElement): Spec[R, E, T] =
    transform[R, E, T] {
      case TestCase(test, annotations) => Spec.TestCase(test, annotations.annotate(key, value))
      case c                           => c
    }

  /**
   * Returns a new spec with the annotation map at each node.
   */
  final def annotated(implicit trace: ZTraceElement): Spec[R with Annotations, Annotated[E], Annotated[T]] =
    transform[R with Annotations, Annotated[E], Annotated[T]] {
      case ExecCase(exec, spec)     => ExecCase(exec, spec)
      case LabeledCase(label, spec) => LabeledCase(label, spec)
      case ScopedCase(scoped) =>
        ScopedCase[R with Annotations, Annotated[E], Spec[R with Annotations, Annotated[E], Annotated[T]]](
          scoped.mapError((_, TestAnnotationMap.empty))
        )
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(Annotations.withAnnotation(test), annotations)
    }

  /**
   * Returns a new spec with remapped errors and tests.
   */
  @deprecated("use mapBoth", "2.0.0")
  final def bimap[E1, T1](f: E => E1, g: T => T1)(implicit ev: CanFail[E], trace: ZTraceElement): Spec[R, E1, T1] =
    transform[R, E1, T1] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(label, spec)
      case ScopedCase(scoped)          => ScopedCase[R, E1, Spec[R, E1, T1]](scoped.mapError(f))
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.bimap(f, g), annotations)
    }

  /**
   * Transforms the environment being provided to each test in this spec with
   * the specified function.
   */
  final def provideSomeEnvironment[R0](
    f: ZEnvironment[R0] => ZEnvironment[R]
  )(implicit trace: ZTraceElement): Spec[R0, E, T] =
    transform[R0, E, T] {
      case ExecCase(exec, spec)     => ExecCase(exec, spec)
      case LabeledCase(label, spec) => LabeledCase(label, spec)
      case ScopedCase(scoped) =>
        ScopedCase[R0, E, Spec[R0, E, T]](
          scoped.provideSomeEnvironment[R0 with Scope](in => f(in).add[Scope](in.get[Scope]))
        )
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.provideSomeEnvironment(f), annotations)
    }

  /**
   * Returns the number of tests in the spec that satisfy the specified
   * predicate.
   */
  final def countTests(f: T => Boolean)(implicit trace: ZTraceElement): ZIO[R with Scope, E, Int] =
    fold[ZIO[R with Scope, E, Int]] {
      case ExecCase(_, spec)    => spec
      case LabeledCase(_, spec) => spec
      case ScopedCase(scoped)   => scoped.flatten
      case MultipleCase(specs)  => ZIO.collectAll(specs).map(_.sum)
      case TestCase(test, _)    => test.map(t => if (f(t)) 1 else 0)
    }

  /**
   * Returns an effect that models execution of this spec.
   */
  final def execute(defExec: ExecutionStrategy)(implicit
    trace: ZTraceElement
  ): ZIO[R with Scope, Nothing, Spec[Any, E, T]] =
    ZIO.environmentWithZIO(provideEnvironment(_).foreachExec(defExec)(ZIO.failCause(_), ZIO.succeedNow))

  /**
   * Determines if any node in the spec is satisfied by the given predicate.
   */
  final def exists[R1 <: R, E1 >: E](
    f: SpecCase[R, E, T, Any] => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZIO[R1 with Scope, E1, Boolean] =
    fold[ZIO[R1 with Scope, E1, Boolean]] {
      case c @ ExecCase(_, spec)    => spec.zipWith(f(c))(_ || _)
      case c @ LabeledCase(_, spec) => spec.zipWith(f(c))(_ || _)
      case c @ ScopedCase(scoped)   => scoped.flatMap(_.zipWith(f(c))(_ || _))
      case c @ MultipleCase(specs) =>
        ZIO.collectAll(specs).map(_.exists(identity)).zipWith(f(c))(_ || _)
      case c @ TestCase(_, _) => f(c)
    }

  /**
   * Returns a new spec with only those tests with annotations satisfying the
   * specified predicate. If no annotations satisfy the specified predicate then
   * returns `Some` with an empty suite if this is a suite or `None` otherwise.
   */
  final def filterAnnotations[V](
    key: TestAnnotation[V]
  )(f: V => Boolean)(implicit trace: ZTraceElement): Option[Spec[R, E, T]] =
    caseValue match {
      case ExecCase(exec, spec) =>
        spec.filterAnnotations(key)(f).map(spec => Spec.exec(exec, spec))
      case LabeledCase(label, spec) =>
        spec.filterAnnotations(key)(f).map(spec => Spec.labeled(label, spec))
      case ScopedCase(scoped) =>
        Some(Spec.scoped[R](scoped.map(_.filterAnnotations(key)(f).getOrElse(Spec.empty))))
      case MultipleCase(specs) =>
        val filtered = specs.flatMap(_.filterAnnotations(key)(f))
        if (filtered.isEmpty) None else Some(Spec.multiple(filtered))
      case TestCase(test, annotations) =>
        if (f(annotations.get(key))) Some(Spec.test(test, annotations)) else None
    }

  /**
   * Returns a new spec with only those suites and tests satisfying the
   * specified predicate. If a suite label satisfies the predicate the entire
   * suite will be included in the new spec. Otherwise only those specs in a
   * suite that satisfy the specified predicate will be included in the new
   * spec. If no labels satisfy the specified predicate then returns `Some` with
   * an empty suite if this is a suite or `None` otherwise.
   */
  final def filterLabels(f: String => Boolean)(implicit trace: ZTraceElement): Option[Spec[R, E, T]] =
    caseValue match {
      case ExecCase(exec, spec) =>
        spec.filterLabels(f).map(spec => Spec.exec(exec, spec))
      case LabeledCase(label, spec) =>
        if (f(label)) Some(Spec.labeled(label, spec))
        else spec.filterLabels(f).map(spec => Spec.labeled(label, spec))
      case ScopedCase(scoped) =>
        Some(Spec.scoped[R](scoped.map(_.filterLabels(f).getOrElse(Spec.empty))))
      case MultipleCase(specs) =>
        val filtered = specs.flatMap(_.filterLabels(f))
        if (filtered.isEmpty) None else Some(Spec.multiple(filtered))
      case TestCase(_, _) =>
        None
    }

  /**
   * Returns a new spec with only those suites and tests with tags satisfying
   * the specified predicate. If no tags satisfy the specified predicate then
   * returns `Some` with an empty suite with the root label if this is a suite
   * or `None` otherwise.
   */
  final def filterTags(f: String => Boolean)(implicit trace: ZTraceElement): Option[Spec[R, E, T]] =
    filterAnnotations(TestAnnotation.tagged)(_.exists(f))

  /**
   * Folds over all nodes to produce a final result.
   */
  final def fold[Z](f: SpecCase[R, E, T, Z] => Z)(implicit trace: ZTraceElement): Z =
    caseValue match {
      case ExecCase(exec, spec)     => f(ExecCase(exec, spec.fold(f)))
      case LabeledCase(label, spec) => f(LabeledCase(label, spec.fold(f)))
      case ScopedCase(scoped)       => f(ScopedCase[R, E, Z](scoped.map(_.fold(f))))
      case MultipleCase(specs)      => f(MultipleCase(specs.map((_.fold(f)))))
      case t @ TestCase(_, _)       => f(t)
    }

  /**
   * Effectfully folds over all nodes according to the execution strategy of
   * suites, utilizing the specified default for other cases.
   */
  @deprecated("use foldScoped", "2.0.0")
  final def foldM[R1 <: R, E1, Z](
    defExec: ExecutionStrategy
  )(f: SpecCase[R, E, T, Z] => ZIO[R1 with Scope, E1, Z])(implicit trace: ZTraceElement): ZIO[R1 with Scope, E1, Z] =
    foldScoped[R1, E1, Z](defExec)(f)

  /**
   * Effectfully folds over all nodes according to the execution strategy of
   * suites, utilizing the specified default for other cases.
   */
  final def foldScoped[R1 <: R, E1, Z](
    defExec: ExecutionStrategy
  )(f: SpecCase[R, E, T, Z] => ZIO[R1 with Scope, E1, Z])(implicit trace: ZTraceElement): ZIO[R1 with Scope, E1, Z] =
    caseValue match {
      case ExecCase(exec, spec)     => spec.foldScoped[R1, E1, Z](exec)(f).flatMap(z => f(ExecCase(exec, z)))
      case LabeledCase(label, spec) => spec.foldScoped[R1, E1, Z](defExec)(f).flatMap(z => f(LabeledCase(label, z)))
      case ScopedCase(scoped) =>
        scoped.foldCauseZIO(
          c => f(ScopedCase(ZIO.failCause(c))),
          spec => spec.foldScoped[R1, E1, Z](defExec)(f).flatMap(z => f(ScopedCase(ZIO.succeedNow(z))))
        )
      case MultipleCase(specs) =>
        ZIO
          .foreachExec(specs)(defExec)(spec => ZIO.scoped[R1](spec.foldScoped[R1, E1, Z](defExec)(f)))
          .flatMap(zs => f(MultipleCase(zs)))
      case t @ TestCase(_, _) => f(t)
    }

  /**
   * Determines if all node in the spec are satisfied by the given predicate.
   */
  final def forall[R1 <: R, E1 >: E](
    f: SpecCase[R, E, T, Any] => ZIO[R1, E1, Boolean]
  )(implicit trace: ZTraceElement): ZIO[R1 with Scope, E1, Boolean] =
    fold[ZIO[R1 with Scope, E1, Boolean]] {
      case c @ ExecCase(_, spec)    => spec.zipWith(f(c))(_ && _)
      case c @ LabeledCase(_, spec) => spec.zipWith(f(c))(_ && _)
      case c @ ScopedCase(scoped)   => scoped.flatMap(_.zipWith(f(c))(_ && _))
      case c @ MultipleCase(specs) =>
        ZIO.collectAll(specs).map(_.forall(identity)).zipWith(f(c))(_ && _)
      case c @ TestCase(_, _) => f(c)
    }

  /**
   * Iterates over the spec with the specified default execution strategy, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreachExec[R1 <: R, E1, A](
    defExec: ExecutionStrategy
  )(failure: Cause[E] => ZIO[R1, E1, A], success: T => ZIO[R1, E1, A])(implicit
    trace: ZTraceElement
  ): ZIO[R1 with Scope, Nothing, Spec[R1, E1, A]] =
    foldScoped[R1, Nothing, Spec[R1, E1, A]](defExec) {
      case ExecCase(exec, spec)     => ZIO.succeedNow(Spec.exec(exec, spec))
      case LabeledCase(label, spec) => ZIO.succeedNow(Spec.labeled(label, spec))
      case ScopedCase(scoped) =>
        scoped.foldCause(
          c => Spec.test(failure(c), TestAnnotationMap.empty),
          t => Spec.scoped(ZIO.succeedNow(t))
        )
      case MultipleCase(specs) => ZIO.succeedNow(Spec.multiple(specs))
      case TestCase(test, annotations) =>
        test
          .foldCause(e => Spec.test(failure(e), annotations), t => Spec.test(success(t), annotations))
    }

  /**
   * Iterates over the spec with the sequential strategy as the default, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreach[R1 <: R, E1, A](
    failure: Cause[E] => ZIO[R1, E1, A],
    success: T => ZIO[R1, E1, A]
  )(implicit trace: ZTraceElement): ZIO[R1 with Scope, Nothing, Spec[R1, E1, A]] =
    foreachExec(ExecutionStrategy.Sequential)(failure, success)

  /**
   * Iterates over the spec with the parallel strategy as the default, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreachPar[R1 <: R, E1, A](
    failure: Cause[E] => ZIO[R1, E1, A],
    success: T => ZIO[R1, E1, A]
  )(implicit trace: ZTraceElement): ZIO[R1 with Scope, Nothing, Spec[R1, E1, A]] =
    foreachExec(ExecutionStrategy.Parallel)(failure, success)

  /**
   * Iterates over the spec with the parallel (`n`) strategy as the default, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreachParN[R1 <: R, E1, A](
    n: Int
  )(failure: Cause[E] => ZIO[R1, E1, A], success: T => ZIO[R1, E1, A])(implicit
    trace: ZTraceElement
  ): ZIO[R1 with Scope, Nothing, Spec[R1, E1, A]] =
    foreachExec(ExecutionStrategy.ParallelN(n))(failure, success)

  /**
   * Returns a new spec with remapped errors and tests.
   */
  final def mapBoth[E1, T1](f: E => E1, g: T => T1)(implicit ev: CanFail[E], trace: ZTraceElement): Spec[R, E1, T1] =
    transform[R, E1, T1] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(label, spec)
      case ScopedCase(scoped)          => ScopedCase[R, E1, Spec[R, E1, T1]](scoped.mapError(f))
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.mapBoth(f, g), annotations)
    }

  /**
   * Returns a new spec with remapped errors.
   */
  final def mapError[E1](f: E => E1)(implicit ev: CanFail[E], trace: ZTraceElement): Spec[R, E1, T] =
    transform[R, E1, T] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(label, spec)
      case ScopedCase(scoped)          => ScopedCase[R, E1, Spec[R, E1, T]](scoped.mapError(f))
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.mapError(f), annotations)
    }

  /**
   * Returns a new spec with remapped labels.
   */
  final def mapLabel(f: String => String)(implicit trace: ZTraceElement): Spec[R, E, T] =
    transform[R, E, T] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(f(label), spec)
      case ScopedCase(scoped)          => ScopedCase[R, E, Spec[R, E, T]](scoped)
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test, annotations)
    }

  /**
   * Returns a new spec with remapped tests.
   */
  final def mapTest[T1](f: T => T1)(implicit trace: ZTraceElement): Spec[R, E, T1] =
    transform[R, E, T1] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(label, spec)
      case ScopedCase(scoped)          => ScopedCase[R, E, Spec[R, E, T1]](scoped)
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.map(f), annotations)
    }

  /**
   * Provides each test with the part of the environment that is not part of the
   * `TestEnvironment`, leaving a spec that only depends on the
   * `TestEnvironment`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val spec: ZSpec[TestEnvironment with Logging, Nothing] = ???
   *
   * val spec2 = spec.provideCustomLayer(loggingLayer)
   * }}}
   */
  def provideCustomLayer[E1 >: E, R1](layer: ZLayer[TestEnvironment, E1, R1])(implicit
    ev: TestEnvironment with R1 <:< R,
    tagged: EnvironmentTag[R1],
    trace: ZTraceElement
  ): Spec[TestEnvironment, E1, T] =
    provideSomeLayer[TestEnvironment](layer)

  /**
   * Provides all tests with a shared version of the part of the environment
   * that is not part of the `TestEnvironment`, leaving a spec that only depends
   * on the `TestEnvironment`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val spec: ZSpec[TestEnvironment with Logging, Nothing] = ???
   *
   * val spec2 = spec.provideCustomLayerShared(loggingLayer)
   * }}}
   */
  def provideCustomLayerShared[E1 >: E, R1](layer: ZLayer[TestEnvironment, E1, R1])(implicit
    ev: TestEnvironment with R1 <:< R,
    tagged: EnvironmentTag[R1],
    trace: ZTraceElement
  ): Spec[TestEnvironment, E1, T] =
    provideSomeLayerShared(layer)

  /**
   * Provides each test in this spec with its required environment
   */
  final def provideEnvironment(r: ZEnvironment[R])(implicit trace: ZTraceElement): Spec[Any, E, T] =
    provideSomeEnvironment(_ => r)

  /**
   * Provides each test in this spec with the single service it requires. If
   * this spec requires multiple services use `provideEnvironment` instead.
   */
  final def provideService[Service <: R](service: Service)(implicit
    tag: Tag[Service],
    trace: ZTraceElement
  ): Spec[Any, E, T] =
    provideEnvironment(ZEnvironment(service))

  /**
   * Provides a layer to the spec, translating it up a level.
   */
  final def provideLayer[E1 >: E, R0](
    layer: ZLayer[R0, E1, R]
  )(implicit trace: ZTraceElement): Spec[R0, E1, T] =
    transform[R0, E1, T] {
      case ExecCase(exec, spec)     => ExecCase(exec, spec)
      case LabeledCase(label, spec) => LabeledCase(label, spec)
      case ScopedCase(scoped) =>
        ScopedCase[R0, E1, Spec[R0, E1, T]](
          layer.build.flatMap(r => scoped.provideSomeEnvironment[Scope](r.union[Scope](_)))
        )
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.provideLayer(layer), annotations)
    }

  /**
   * Provides a layer to the spec, sharing services between all tests.
   */
  final def provideLayerShared[E1 >: E, R0](
    layer: ZLayer[R0, E1, R]
  )(implicit trace: ZTraceElement): Spec[R0, E1, T] =
    caseValue match {
      case ExecCase(exec, spec)     => Spec.exec(exec, spec.provideLayerShared(layer))
      case LabeledCase(label, spec) => Spec.labeled(label, spec.provideLayerShared(layer))
      case ScopedCase(scoped) =>
        Spec.scoped[R0](
          layer.build.flatMap(r => scoped.map(_.provideEnvironment(r)).provideSomeEnvironment[Scope](r.union[Scope]))
        )
      case MultipleCase(specs) =>
        Spec.scoped[R0](
          layer.build.map(r => Spec.multiple(specs.map(_.provideEnvironment(r))))
        )
      case TestCase(test, annotations) => Spec.test(test.provideLayer(layer), annotations)
    }

  /**
   * Splits the environment into two parts, providing each test with one part
   * using the specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec: ZSpec[Clock with Random, Nothing] = ???
   *
   * val spec2 = spec.provideSomeLayer[Random](clockLayer)
   * }}}
   */
  final def provideSomeLayer[R0]: Spec.ProvideSomeLayer[R0, R, E, T] =
    new Spec.ProvideSomeLayer[R0, R, E, T](self)

  /**
   * Splits the environment into two parts, providing all tests with a shared
   * version of one part using the specified layer and leaving the remainder
   * `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec: ZSpec[Clock with Random, Nothing] = ???
   *
   * val spec2 = spec.provideSomeLayerShared[Random](clockLayer)
   * }}}
   */
  final def provideSomeLayerShared[R0]: Spec.ProvideSomeLayerShared[R0, R, E, T] =
    new Spec.ProvideSomeLayerShared[R0, R, E, T](self)

  /**
   * Computes the size of the spec, i.e. the number of tests in the spec.
   */
  final def size(implicit trace: ZTraceElement): ZIO[R with Scope, E, Int] =
    fold[ZIO[R with Scope, E, Int]] {
      case ExecCase(_, counts)    => counts
      case LabeledCase(_, counts) => counts
      case ScopedCase(counts)     => counts.flatten
      case MultipleCase(counts)   => ZIO.collectAll(counts).map(_.sum)
      case TestCase(_, _)         => ZIO.succeedNow(1)
    }

  /**
   * Transforms the spec one layer at a time.
   */
  final def transform[R1, E1, T1](
    f: SpecCase[R, E, T, Spec[R1, E1, T1]] => SpecCase[R1, E1, T1, Spec[R1, E1, T1]]
  )(implicit trace: ZTraceElement): Spec[R1, E1, T1] =
    caseValue match {
      case ExecCase(exec, spec)     => Spec(f(ExecCase(exec, spec.transform(f))))
      case LabeledCase(label, spec) => Spec(f(LabeledCase(label, spec.transform(f))))
      case ScopedCase(scoped)       => Spec(f(ScopedCase[R, E, Spec[R1, E1, T1]](scoped.map(_.transform[R1, E1, T1](f)))))
      case MultipleCase(specs)      => Spec(f(MultipleCase(specs.map(_.transform(f)))))
      case t @ TestCase(_, _)       => Spec(f(t))
    }

  /**
   * Transforms the spec statefully, one layer at a time.
   */
  final def transformAccum[R1, E1, T1, Z](
    z0: Z
  )(
    f: (Z, SpecCase[R, E, T, Spec[R1, E1, T1]]) => (Z, SpecCase[R1, E1, T1, Spec[R1, E1, T1]])
  )(implicit trace: ZTraceElement): ZIO[R with Scope, E, (Z, Spec[R1, E1, T1])] =
    caseValue match {
      case ExecCase(exec, spec) =>
        spec.transformAccum(z0)(f).map { case (z, spec) =>
          f(z, ExecCase(exec, spec)) match {
            case (z, specs) => z -> Spec(specs)
          }
        }
      case LabeledCase(label, spec) =>
        spec.transformAccum(z0)(f).map { case (z, spec) =>
          f(z, LabeledCase(label, spec)) match {
            case (z, specs) => z -> Spec(specs)
          }
        }
      case ScopedCase(scoped) =>
        scoped.flatMap(_.transformAccum(z0)(f)).map { case (z, specs) =>
          f(z, ScopedCase(ZIO.succeedNow(specs))) match {
            case (z, specs) =>
              z -> Spec(specs)
          }
        }
      case MultipleCase(specs) =>
        ZIO
          .foldLeft[R with Scope, E, (Z, Chunk[Spec[R1, E1, T1]]), Spec[R, E, T]](specs)(z0 -> Chunk.empty) {
            case ((z, vector), spec) =>
              spec.transformAccum(z)(f).map { case (z1, spec1) => z1 -> (vector :+ spec1) }
          }
          .map { case (z, specs) =>
            f(z, MultipleCase(specs)) match {
              case (z, specs) => z -> Spec(specs)
            }
          }
      case t @ TestCase(_, _) =>
        val (z, caseValue) = f(z0, t)
        ZIO.succeedNow(z -> Spec(caseValue))
    }

  /**
   * Updates a service in the environment of this effect.
   */
  final def updateService[M] =
    new Spec.UpdateService[R, E, T, M](self)

  /**
   * Updates a service at the specified key in the environment of this effect.
   */
  final def updateServiceAt[Service]: Spec.UpdateServiceAt[R, E, T, Service] =
    new Spec.UpdateServiceAt[R, E, T, Service](self)

  /**
   * Runs the spec only if the specified predicate is satisfied.
   */
  final def when(
    b: => Boolean
  )(implicit ev: T <:< TestSuccess, trace: ZTraceElement): Spec[R with Annotations, E, TestSuccess] =
    whenZIO(ZIO.succeedNow(b))

  /**
   * Runs the spec only if the specified effectual predicate is satisfied.
   */
  @deprecated("use whenZIO", "2.0.0")
  final def whenM[R1 <: R, E1 >: E](
    b: ZIO[R1, E1, Boolean]
  )(implicit ev: T <:< TestSuccess, trace: ZTraceElement): Spec[R1 with Annotations, E1, TestSuccess] =
    whenZIO(b)

  /**
   * Runs the spec only if the specified effectual predicate is satisfied.
   */
  final def whenZIO[R1 <: R, E1 >: E](
    b: ZIO[R1, E1, Boolean]
  )(implicit ev: T <:< TestSuccess, trace: ZTraceElement): Spec[R1 with Annotations, E1, TestSuccess] =
    caseValue match {
      case ExecCase(exec, spec) =>
        Spec.exec(exec, spec.whenZIO(b))
      case LabeledCase(label, spec) =>
        Spec.labeled(label, spec.whenZIO(b))
      case ScopedCase(scoped) =>
        Spec.scoped[R1 with Annotations](
          b.flatMap { b =>
            if (b) scoped.map(_.mapTest(ev))
            else ZIO.succeedNow(Spec.empty)
          }
        )
      case MultipleCase(specs) =>
        Spec.multiple(specs.map(_.whenZIO(b)))
      case TestCase(test, annotations) =>
        Spec.test(
          b.flatMap { b =>
            if (b) test.map(ev)
            else Annotations.annotate(TestAnnotation.ignored, 1).as(TestSuccess.Ignored)
          },
          annotations
        )
    }
}

object Spec {
  sealed abstract class SpecCase[-R, +E, +T, +A] { self =>
    final def map[B](f: A => B)(implicit trace: ZTraceElement): SpecCase[R, E, T, B] = self match {
      case ExecCase(label, spec)       => ExecCase(label, f(spec))
      case LabeledCase(label, spec)    => LabeledCase(label, f(spec))
      case ScopedCase(scoped)          => ScopedCase[R, E, B](scoped.map(f))
      case MultipleCase(specs)         => MultipleCase(specs.map(f))
      case TestCase(test, annotations) => TestCase(test, annotations)
    }
  }
  final case class ExecCase[+Spec](exec: ExecutionStrategy, spec: Spec)          extends SpecCase[Any, Nothing, Nothing, Spec]
  final case class LabeledCase[+Spec](label: String, spec: Spec)                 extends SpecCase[Any, Nothing, Nothing, Spec]
  final case class ScopedCase[-R, +E, +Spec](scoped: ZIO[Scope with R, E, Spec]) extends SpecCase[R, E, Nothing, Spec]
  final case class MultipleCase[+Spec](specs: Chunk[Spec])                       extends SpecCase[Any, Nothing, Nothing, Spec]
  final case class TestCase[-R, +E, +T](test: ZIO[R, E, T], annotations: TestAnnotationMap)
      extends SpecCase[R, E, T, Nothing]

  final def exec[R, E, T](exec: ExecutionStrategy, spec: Spec[R, E, T]): Spec[R, E, T] =
    Spec(ExecCase(exec, spec))

  final def labeled[R, E, T](label: String, spec: Spec[R, E, T]): Spec[R, E, T] =
    Spec(LabeledCase(label, spec))

  final def scoped[R]: ScopedPartiallyApplied[R] =
    new ScopedPartiallyApplied[R]

  final def multiple[R, E, T](specs: Chunk[Spec[R, E, T]]): Spec[R, E, T] =
    Spec(MultipleCase(specs))

  final def test[R, E, T](test: ZIO[R, E, T], annotations: TestAnnotationMap): Spec[R, E, T] =
    Spec(TestCase(test, annotations))

  val empty: Spec[Any, Nothing, Nothing] =
    Spec.multiple(Chunk.empty)

  final class ProvideSomeLayer[R0, -R, +E, +T](private val self: Spec[R, E, T]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: ZTraceElement
    ): Spec[R0, E1, T] =
      self.asInstanceOf[Spec[R0 with R1, E, T]].provideLayer(ZLayer.environment[R0] ++ layer)
  }

  final class ProvideSomeLayerShared[R0, -R, +E, +T](private val self: Spec[R, E, T]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: ZTraceElement
    ): Spec[R0, E1, T] =
      self.caseValue match {
        case ExecCase(exec, spec)     => Spec.exec(exec, spec.provideSomeLayerShared(layer))
        case LabeledCase(label, spec) => Spec.labeled(label, spec.provideSomeLayerShared(layer))
        case ScopedCase(scoped) =>
          Spec.scoped[R0](
            layer.build.flatMap { r =>
              scoped
                .map(_.provideSomeLayer[R0](ZLayer.succeedEnvironment(r)))
                .provideSomeEnvironment[R0 with Scope](in => in.union[R1](r).asInstanceOf[ZEnvironment[R with Scope]])
            }
          )
        case MultipleCase(specs) =>
          Spec.scoped[R0](
            layer.build.map(r => Spec.multiple(specs.map(_.provideSomeLayer[R0](ZLayer.succeedEnvironment(r)))))
          )
        case TestCase(test, annotations) =>
          Spec.test(test.provideSomeLayer(layer), annotations)
      }
  }

  final class ScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, T](scoped: => ZIO[Scope with R, E, Spec[R, E, T]]): Spec[R, E, T] =
      Spec(ScopedCase[R, E, Spec[R, E, T]](scoped))
  }

  final class UpdateService[-R, +E, +T, M](private val self: Spec[R, E, T]) extends AnyVal {
    def apply[R1 <: R with M](
      f: M => M
    )(implicit tag: Tag[M], trace: ZTraceElement): Spec[R1, E, T] =
      self.provideSomeEnvironment(_.update(f))
  }

  final class UpdateServiceAt[-R, +E, +T, Service](private val self: Spec[R, E, T]) extends AnyVal {
    def apply[R1 <: R with Map[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit tag: Tag[Map[Key, Service]], trace: ZTraceElement): Spec[R1, E, T] =
      self.provideSomeEnvironment(_.updateAt(key)(f))
  }
}
