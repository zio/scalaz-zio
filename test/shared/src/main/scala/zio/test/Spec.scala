/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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
 * A `Spec[R, E]` is the backbone of _ZIO Test_. Every spec is either a suite,
 * which contains other specs, or a test. All specs require an environment of
 * type `R` and may potentially fail with an error of type `E`.
 */
final case class Spec[-R, +E](caseValue: SpecCase[R, E, Spec[R, E]]) extends SpecVersionSpecific[R, E] {
  self =>

  /**
   * Combines this spec with the specified spec.
   */
  def +[R1 <: R, E1 >: E](that: Spec[R1, E1]): Spec[R1, E1] =
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
  final def @@[R0 <: R1, R1 <: R, E0 >: E, E1 >: E0](aspect: TestAspect[R0, R1, E0, E1])(implicit
    trace: Trace
  ): Spec[R1, E0] =
    aspect(self)

  /**
   * Annotates each test in this spec with the specified test annotation.
   */
  final def annotate[V](key: TestAnnotation[V], value: V)(implicit trace: Trace): Spec[R, E] =
    transform[R, E] {
      case TestCase(test, annotations) => Spec.TestCase(test, annotations.annotate(key, value))
      case c                           => c
    }

  /**
   * Returns a new spec with the annotation map at each node.
   */
  final def annotated(implicit trace: Trace): Spec[R, E] =
    transform[R, E] {
      case ExecCase(exec, spec)     => ExecCase(exec, spec)
      case LabeledCase(label, spec) => LabeledCase(label, spec)
      case ScopedCase(scoped) =>
        ScopedCase[R, E, Spec[R, E]](
          scoped
        )
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(Annotations.withAnnotation(test), annotations)
    }

  /**
   * Returns an effect that models execution of this spec.
   */
  final def execute(defExec: ExecutionStrategy)(implicit
    trace: Trace
  ): ZIO[R with Scope, Nothing, Spec[Any, E]] =
    ZIO.environmentWithZIO(provideEnvironment(_).foreachExec(defExec)(Exit.failCause, ZIO.successFn))

  /**
   * Returns a new spec with only those tests with annotations satisfying the
   * specified predicate. If no annotations satisfy the specified predicate then
   * returns `Some` with an empty suite if this is a suite or `None` otherwise.
   */
  final def filterAnnotations[V](
    key: TestAnnotation[V]
  )(f: V => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
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
  final def filterLabels(f: String => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
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
  final def filterTags(f: String => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
    filterAnnotations(TestAnnotation.tagged)(_.exists(f))

  /**
   * Returns a new spec with only those suites and tests except for the ones
   * with tags satisfying the predicate. If all tests or suites have tags that
   * satisfy the specified predicate then returns `Some` with an empty suite
   * with the root label if this is a suite or `None` otherwise.
   */
  final def filterNotTags(f: String => Boolean)(implicit trace: Trace): Option[Spec[R, E]] =
    filterAnnotations(TestAnnotation.tagged)(!_.exists(f))

  /**
   * Effectfully folds over all nodes according to the execution strategy of
   * suites, utilizing the specified default for other cases.
   */
  final def foldScoped[R1 <: R, E1, Z](
    defExec: ExecutionStrategy
  )(f: SpecCase[R, E, Z] => ZIO[R1 with Scope, E1, Z])(implicit trace: Trace): ZIO[R1 with Scope, E1, Z] =
    caseValue match {
      case ExecCase(exec, spec)     => spec.foldScoped[R1, E1, Z](exec)(f).flatMap(z => f(ExecCase(exec, z)))
      case LabeledCase(label, spec) => spec.foldScoped[R1, E1, Z](defExec)(f).flatMap(z => f(LabeledCase(label, z)))
      case ScopedCase(scoped) =>
        scoped.foldCauseZIO(
          c => f(ScopedCase(Exit.failCause(c))),
          spec => spec.foldScoped[R1, E1, Z](defExec)(f).flatMap(z => f(ScopedCase(ZIO.succeed(z))))
        )
      case MultipleCase(specs) =>
        ZIO
          .foreachExec(specs)(defExec)(spec => ZIO.scoped[R1](spec.foldScoped[R1, E1, Z](defExec)(f)))
          .flatMap(zs => f(MultipleCase(zs)))
      case t @ TestCase(_, _) => f(t)
    }

  /**
   * Iterates over the spec with the specified default execution strategy, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreachExec[R1 <: R, E1](
    defExec: ExecutionStrategy
  )(
    failure: Cause[TestFailure[E]] => ZIO[R1, TestFailure[E1], TestSuccess],
    success: TestSuccess => ZIO[R1, E1, TestSuccess]
  )(implicit
    trace: Trace
  ): ZIO[R1 with Scope, Nothing, Spec[R1, E1]] =
    foldScoped[R1, Nothing, Spec[R1, E1]](defExec) {
      case ExecCase(exec, spec)     => ZIO.succeed(Spec.exec(exec, spec))
      case LabeledCase(label, spec) => ZIO.succeed(Spec.labeled(label, spec))
      case ScopedCase(scoped) =>
        scoped.foldCause(
          c => Spec.test(failure(c), TestAnnotationMap.empty),
          t => Spec.scoped(ZIO.succeed(t))
        )
      case MultipleCase(specs) => ZIO.succeed(Spec.multiple(specs))
      case TestCase(test, annotations) =>
        test
          .foldCause(
            e => Spec.test(failure(e), annotations),
            t => Spec.test(success(t).mapError(TestFailure.fail), annotations)
          )
    }

  /**
   * Iterates over the spec with the sequential strategy as the default, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreach[R1 <: R, E1](
    failure: Cause[TestFailure[E]] => ZIO[R1, TestFailure[E1], TestSuccess],
    success: TestSuccess => ZIO[R1, E1, TestSuccess]
  )(implicit trace: Trace): ZIO[R1 with Scope, Nothing, Spec[R1, E1]] =
    foreachExec(ExecutionStrategy.Sequential)(failure, success)

  /**
   * Iterates over the spec with the parallel strategy as the default, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreachPar[R1 <: R, E1](
    failure: Cause[TestFailure[E]] => ZIO[R1, TestFailure[E1], TestSuccess],
    success: TestSuccess => ZIO[R1, E1, TestSuccess]
  )(implicit trace: Trace): ZIO[R1 with Scope, Nothing, Spec[R1, E1]] =
    foreachExec(ExecutionStrategy.Parallel)(failure, success)

  /**
   * Iterates over the spec with the parallel (`n`) strategy as the default, and
   * effectfully transforming every test with the provided function, finally
   * reconstructing the spec with the same structure.
   */
  final def foreachParN[R1 <: R, E1](
    n: Int
  )(
    failure: Cause[TestFailure[E]] => ZIO[R1, TestFailure[E1], TestSuccess],
    success: TestSuccess => ZIO[R1, E1, TestSuccess]
  )(implicit
    trace: Trace
  ): ZIO[R1 with Scope, Nothing, Spec[R1, E1]] =
    foreachExec(ExecutionStrategy.ParallelN(n))(failure, success)

  /**
   * Returns a new spec with remapped errors.
   */
  final def mapError[E1](f: E => E1)(implicit ev: CanFail[E], trace: Trace): Spec[R, E1] =
    transform[R, E1] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(label, spec)
      case ScopedCase(scoped)          => ScopedCase[R, E1, Spec[R, E1]](scoped.mapError(_.map(f)))
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.mapError(_.map(f)), annotations)
    }

  /**
   * Returns a new spec with remapped labels.
   */
  final def mapLabel(f: String => String)(implicit trace: Trace): Spec[R, E] =
    transform[R, E] {
      case ExecCase(exec, spec)        => ExecCase(exec, spec)
      case LabeledCase(label, spec)    => LabeledCase(f(label), spec)
      case ScopedCase(scoped)          => ScopedCase[R, E, Spec[R, E]](scoped)
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test, annotations)
    }

  /**
   * Provides each test with the part of the environment that is not part of the
   * `TestEnvironment`, leaving a spec that only depends on the
   * `TestEnvironment`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val spec: Spec[TestEnvironment with Logging, Nothing] = ???
   *
   * val spec2 = spec.provideCustomLayer(loggingLayer)
   * }}}
   */
  @deprecated("use provideLayer", "2.0.2")
  def provideCustomLayer[E1 >: E, R1](layer: ZLayer[TestEnvironment, E1, R1])(implicit
    ev: TestEnvironment with R1 <:< R,
    tagged: EnvironmentTag[R1],
    trace: Trace
  ): Spec[TestEnvironment, E1] =
    provideSomeLayer[TestEnvironment](layer)

  /**
   * Provides all tests with a shared version of the part of the environment
   * that is not part of the `TestEnvironment`, leaving a spec that only depends
   * on the `TestEnvironment`.
   *
   * {{{
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val spec: Spec[TestEnvironment with Logging, Nothing] = ???
   *
   * val spec2 = spec.provideCustomLayerShared(loggingLayer)
   * }}}
   */
  @deprecated("use provideLayerShared", "2.0.2")
  def provideCustomLayerShared[E1 >: E, R1](layer: ZLayer[TestEnvironment, E1, R1])(implicit
    ev: TestEnvironment with R1 <:< R,
    tagged: EnvironmentTag[R1],
    trace: Trace
  ): Spec[TestEnvironment, E1] =
    provideSomeLayerShared(layer)

  /**
   * Provides each test in this spec with its required environment
   */
  final def provideEnvironment(r: ZEnvironment[R])(implicit trace: Trace): Spec[Any, E] =
    provideSomeEnvironment(_ => r)

  /**
   * Provides a layer to the spec, translating it up a level.
   */
  final def provideLayer[E1 >: E, R0](
    layer: ZLayer[R0, E1, R]
  )(implicit trace: Trace): Spec[R0, E1] =
    transform[R0, E1] {
      case ExecCase(exec, spec)     => ExecCase(exec, spec)
      case LabeledCase(label, spec) => LabeledCase(label, spec)
      case ScopedCase(scoped) =>
        ScopedCase[R0, E1, Spec[R0, E1]](
          layer.mapError(TestFailure.fail).build.flatMap(r => scoped.provideSomeEnvironment[Scope](r.union[Scope](_)))
        )
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.provideLayer(layer.mapError(TestFailure.fail)), annotations)
    }

  /**
   * Provides a layer to the spec, sharing services between all tests.
   */
  final def provideLayerShared[E1 >: E, R0](
    layer: ZLayer[R0, E1, R]
  )(implicit trace: Trace): Spec[R0, E1] =
    caseValue match {
      case ExecCase(exec, spec)     => Spec.exec(exec, spec.provideLayerShared(layer))
      case LabeledCase(label, spec) => Spec.labeled(label, spec.provideLayerShared(layer))
      case ScopedCase(scoped) =>
        Spec.scoped[R0](
          layer
            .mapError(TestFailure.fail)
            .build
            .flatMap(r => scoped.map(_.provideEnvironment(r)).provideSomeEnvironment[Scope](r.union[Scope]))
        )
      case MultipleCase(specs) =>
        Spec.scoped[R0](
          layer.memoize.flatMap(layer =>
            layer.mapError(TestFailure.fail).build.map(_ => Spec.multiple(specs.map(_.provideLayer(layer))))
          )
        )
      case TestCase(test, annotations) => Spec.test(test.provideLayer(layer.mapError(TestFailure.fail)), annotations)
    }

  /**
   * Transforms the environment being provided to each test in this spec with
   * the specified function.
   */
  final def provideSomeEnvironment[R0](
    f: ZEnvironment[R0] => ZEnvironment[R]
  )(implicit trace: Trace): Spec[R0, E] =
    transform[R0, E] {
      case ExecCase(exec, spec)     => ExecCase(exec, spec)
      case LabeledCase(label, spec) => LabeledCase(label, spec)
      case ScopedCase(scoped) =>
        ScopedCase[R0, E, Spec[R0, E]](
          scoped.provideSomeEnvironment[R0 with Scope](in => f(in).add[Scope](in.get[Scope]))
        )
      case MultipleCase(specs)         => MultipleCase(specs)
      case TestCase(test, annotations) => TestCase(test.provideSomeEnvironment(f), annotations)
    }

  /**
   * Splits the environment into two parts, providing each test with one part
   * using the specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec: Spec[Clock with Random, Nothing] = ???
   *
   * val spec2 = spec.provideSomeLayer[Random](clockLayer)
   * }}}
   */
  final def provideSomeLayer[R0]: Spec.ProvideSomeLayer[R0, R, E] =
    new Spec.ProvideSomeLayer[R0, R, E](self)

  /**
   * Splits the environment into two parts, providing all tests with a shared
   * version of one part using the specified layer and leaving the remainder
   * `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec: Spec[Clock with Random, Nothing] = ???
   *
   * val spec2 = spec.provideSomeLayerShared[Random](clockLayer)
   * }}}
   */
  final def provideSomeLayerShared[R0]: Spec.ProvideSomeLayerShared[R0, R, E] =
    new Spec.ProvideSomeLayerShared[R0, R, E](self)

  /**
   * Transforms the spec one layer at a time.
   */
  final def transform[R1, E1](
    f: SpecCase[R, E, Spec[R1, E1]] => SpecCase[R1, E1, Spec[R1, E1]]
  )(implicit trace: Trace): Spec[R1, E1] =
    caseValue match {
      case ExecCase(exec, spec)     => Spec(f(ExecCase(exec, spec.transform(f))))
      case LabeledCase(label, spec) => Spec(f(LabeledCase(label, spec.transform(f))))
      case ScopedCase(scoped)       => Spec(f(ScopedCase[R, E, Spec[R1, E1]](scoped.map(_.transform[R1, E1](f)))))
      case MultipleCase(specs)      => Spec(f(MultipleCase(specs.map(_.transform(f)))))
      case t @ TestCase(_, _)       => Spec(f(t))
    }

  /**
   * Updates a service in the environment of this effect.
   */
  final def updateService[M] =
    new Spec.UpdateService[R, E, M](self)

  /**
   * Updates a service at the specified key in the environment of this effect.
   */
  final def updateServiceAt[Service]: Spec.UpdateServiceAt[R, E, Service] =
    new Spec.UpdateServiceAt[R, E, Service](self)

  /**
   * Runs the spec only if the specified predicate is satisfied.
   */
  final def when(
    b: => Boolean
  )(implicit trace: Trace): Spec[R, E] =
    whenZIO(ZIO.succeed(b))

  /**
   * Runs the spec only if the specified effectual predicate is satisfied.
   */
  final def whenZIO[R1 <: R, E1 >: E](
    b: ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): Spec[R1, E1] =
    caseValue match {
      case ExecCase(exec, spec) =>
        Spec.exec(exec, spec.whenZIO(b))
      case LabeledCase(label, spec) =>
        Spec.labeled(label, spec.whenZIO(b))
      case ScopedCase(scoped) =>
        Spec.scoped[R1](
          b.mapError(TestFailure.fail).flatMap { b =>
            if (b) scoped
            else ZIO.succeed(Spec.empty)
          }
        )
      case MultipleCase(specs) =>
        Spec.multiple(specs.map(_.whenZIO(b)))
      case TestCase(test, annotations) =>
        Spec.test(
          b.mapError(TestFailure.fail).flatMap { b =>
            if (b) test
            else Annotations.annotate(TestAnnotation.ignored, 1).as(TestSuccess.Ignored())
          },
          annotations
        )
    }
}

object Spec {
  sealed abstract class SpecCase[-R, +E, +A] { self =>
    final def map[B](f: A => B)(implicit trace: Trace): SpecCase[R, E, B] = self match {
      case ExecCase(label, spec)       => ExecCase(label, f(spec))
      case LabeledCase(label, spec)    => LabeledCase(label, f(spec))
      case ScopedCase(scoped)          => ScopedCase[R, E, B](scoped.map(f))
      case MultipleCase(specs)         => MultipleCase(specs.map(f))
      case TestCase(test, annotations) => TestCase(test, annotations)
    }
  }
  final case class ExecCase[+Spec](exec: ExecutionStrategy, spec: Spec) extends SpecCase[Any, Nothing, Spec]
  final case class LabeledCase[+Spec](label: String, spec: Spec)        extends SpecCase[Any, Nothing, Spec]
  final case class ScopedCase[-R, +E, +Spec](scoped: ZIO[Scope with R, TestFailure[E], Spec])
      extends SpecCase[R, E, Spec]
  final case class MultipleCase[+Spec](specs: Chunk[Spec]) extends SpecCase[Any, Nothing, Spec]
  final case class TestCase[-R, +E](test: ZIO[R, TestFailure[E], TestSuccess], annotations: TestAnnotationMap)
      extends SpecCase[R, E, Nothing]

  final def exec[R, E](exec: ExecutionStrategy, spec: Spec[R, E]): Spec[R, E] =
    Spec(ExecCase(exec, spec))

  final def labeled[R, E](label: String, spec: Spec[R, E]): Spec[R, E] =
    Spec(LabeledCase(label, spec))

  final def scoped[R]: ScopedPartiallyApplied[R] =
    new ScopedPartiallyApplied[R]

  final def multiple[R, E](specs: Chunk[Spec[R, E]]): Spec[R, E] =
    Spec(MultipleCase(specs))

  final def test[R, E](test: ZIO[R, TestFailure[E], TestSuccess], annotations: TestAnnotationMap)(implicit
    trace: Trace
  ): Spec[R, E] =
    Spec(TestCase(test, annotations))

  val empty: Spec[Any, Nothing] =
    Spec.multiple(Chunk.empty)

  final class ProvideSomeLayer[R0, -R, +E](private val self: Spec[R, E]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: Trace
    ): Spec[R0, E1] =
      self.asInstanceOf[Spec[R0 with R1, E]].provideLayer(ZLayer.environment[R0] ++ layer)
  }

  final class ProvideSomeLayerShared[R0, -R, +E](private val self: Spec[R, E]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: Trace
    ): Spec[R0, E1] =
      self.caseValue match {
        case ExecCase(exec, spec)     => Spec.exec(exec, spec.provideSomeLayerShared(layer))
        case LabeledCase(label, spec) => Spec.labeled(label, spec.provideSomeLayerShared(layer))
        case ScopedCase(scoped) =>
          Spec.scoped[R0](
            layer.mapError(TestFailure.fail).build.flatMap { r =>
              scoped
                .map(_.provideSomeLayer[R0](ZLayer.succeedEnvironment(r)))
                .provideSomeEnvironment[R0 with Scope](in => in.union[R1](r).asInstanceOf[ZEnvironment[R with Scope]])
            }
          )
        case MultipleCase(specs) =>
          Spec.scoped[R0](
            layer.memoize
              .flatMap(layer =>
                layer.mapError(TestFailure.fail).build.map(_ => Spec.multiple(specs.map(_.provideSomeLayer[R0](layer))))
              )
          )
        case TestCase(test, annotations) =>
          Spec.test(test.provideSomeLayer(layer.mapError(TestFailure.fail)), annotations)
      }
  }

  final class ScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, T](scoped: => ZIO[Scope with R, TestFailure[E], Spec[R, E]]): Spec[R, E] =
      Spec(ScopedCase[R, E, Spec[R, E]](scoped))
  }

  final class UpdateService[-R, +E, M](private val self: Spec[R, E]) extends AnyVal {
    def apply[R1 <: R with M](
      f: M => M
    )(implicit tag: Tag[M], trace: Trace): Spec[R1, E] =
      self.provideSomeEnvironment(_.update(f))
  }

  final class UpdateServiceAt[-R, +E, Service](private val self: Spec[R, E]) extends AnyVal {
    def apply[R1 <: R with Map[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit tag: Tag[Map[Key, Service]], trace: Trace): Spec[R1, E] =
      self.provideSomeEnvironment(_.updateAt(key)(f))
  }
}
