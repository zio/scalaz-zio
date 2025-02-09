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

trait GenZIO {

  /**
   * A generator of `Cause` values
   */
  final def causes[R, E](e: Gen[R, E], t: Gen[R, Throwable])(implicit
    trace: Trace
  ): Gen[R, Cause[E]] = {
    val fiberId           = (Gen.int zip Gen.int zip Gen.const(Trace.empty)).map { case (a, b, c) => FiberId(a, b, c) }
    val zTraceElement     = Gen.string.map(_.asInstanceOf[Trace])
    val zTrace            = fiberId.zipWith(Gen.chunkOf(zTraceElement))(StackTrace(_, _))
    val failure           = e.zipWith(zTrace)(Cause.fail(_, _))
    val die               = t.zipWith(zTrace)(Cause.die(_, _))
    val empty             = Gen.const(Cause.empty)
    val interrupt         = fiberId.zipWith(zTrace)(Cause.interrupt(_, _))
    def stackless(n: Int) = Gen.suspend(causesN(n - 1).flatMap(c => Gen.elements(Cause.stack(c), Cause.stackless(c))))

    def sequential(n: Int) = Gen.suspend {
      for {
        i <- Gen.int(1, n - 1)
        l <- causesN(i)
        r <- causesN(n - i)
      } yield Cause.Then(l, r)
    }

    def parallel(n: Int) = Gen.suspend {
      for {
        i <- Gen.int(1, n - 1)
        l <- causesN(i)
        r <- causesN(n - i)
      } yield Cause.Both(l, r)
    }

    def causesN(n: Int): Gen[R, Cause[E]] = Gen.suspend {
      if (n == 1) Gen.oneOf(empty, failure, die, interrupt)
      else if (n == 2) stackless(n)
      else Gen.oneOf(stackless(n), sequential(n), parallel(n))
    }

    Gen.small(causesN, 1)
  }

  /**
   * A generator of effects that are the result of chaining the specified effect
   * with itself a random number of times.
   */
  final def chained[R, Env, E, A](gen: Gen[R, ZIO[Env, E, A]])(implicit
    trace: Trace
  ): Gen[R, ZIO[Env, E, A]] =
    Gen.small(chainedN(_)(gen))

  /**
   * A generator of effects that are the result of chaining the specified effect
   * with itself a given number of times.
   */
  final def chainedN[R, Env, E, A](n: Int)(zio: Gen[R, ZIO[Env, E, A]])(implicit
    trace: Trace
  ): Gen[R, ZIO[Env, E, A]] =
    Gen.listOfN(n min 1)(zio).map(_.reduce(_ *> _))

  /**
   * A generator of effects that are the result of applying concurrency
   * combinators to the specified effect that are guaranteed not to change its
   * value.
   */
  final def concurrent[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): Gen[Any, ZIO[R, E, A]] =
    Gen.const(zio.race(ZIO.never))

  /**
   * A generator of effects that have died with a `Throwable`.
   */
  final def died[R](gen: Gen[R, Throwable])(implicit trace: Trace): Gen[R, UIO[Nothing]] =
    gen.map(ZIO.die(_))

  /**
   * A generator of effects that have failed with an error.
   */
  final def failures[R, E](gen: Gen[R, E])(implicit trace: Trace): Gen[R, IO[E, Nothing]] =
    gen.map(ZIO.fail(_))

  /**
   * A generator of effects that are the result of applying parallelism
   * combinators to the specified effect that are guaranteed not to change its
   * value.
   */
  final def parallel[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): Gen[Any, ZIO[R, E, A]] =
    successes(Gen.unit).map(_.zipParRight(zio))

  /**
   * A generator of successful effects.
   */
  final def successes[R, A](gen: Gen[R, A])(implicit trace: Trace): Gen[R, UIO[A]] =
    gen.map(ZIO.succeed(_))
}
