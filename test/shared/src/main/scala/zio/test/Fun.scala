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

import zio.{Executor, Runtime, RuntimeFlag, RuntimeFlags, Trace, URIO, Unsafe, ZIO}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.ExecutionContext

/**
 * A `Fun[A, B]` is a referentially transparent version of a potentially
 * effectual function from `A` to `B`. Each invocation of the function will be
 * memoized so the function is guaranteed to return the same value for any given
 * input. The function should not involve asynchronous effects.
 */
private[test] final case class Fun[-A, +B] private (private val f: A => B, private val hash: A => Int)
    extends (A => B) {

  def apply(a: A): B =
    map.getOrElseUpdate(hash(a), (a, f(a)))._2

  override def toString: String = {
    val mappings = map.foldLeft(List.empty[String]) { case (acc, (_, (a, b))) => s"$a -> $b" :: acc }
    mappings.mkString("Fun(", ", ", ")")
  }

  private[this] val map = ConcurrentHashMap.empty[Int, (A, B)]
}

private[test] object Fun {

  /**
   * Constructs a new `Fun` from an effectual function. The function should not
   * involve asynchronous effects.
   */
  def make[R, A, B](f: A => URIO[R, B])(implicit trace: Trace): ZIO[R, Nothing, Fun[A, B]] =
    makeHash(f)(_.hashCode)

  /**
   * Constructs a new `Fun` from an effectual function and a hashing function.
   * This is useful when the domain of the function does not implement
   * `hashCode` in a way that is consistent with equality.
   */
  def makeHash[R, A, B](f: A => URIO[R, B])(hash: A => Int)(implicit trace: Trace): ZIO[R, Nothing, Fun[A, B]] =
    ZIO.executor.flatMap { executor =>
      ZIO.acquireReleaseWith {
        ZIO.shift(funExecutor)
      } { _ =>
        ZIO.shift(executor) *> ZIO.unshift
      } { _ =>
        funRuntime[R].map { runtime =>
          Fun(
            a => runtime.unsafe.run(f(a))(trace, Unsafe.unsafe).getOrThrowFiberFailure()(Unsafe.unsafe),
            hash
          )
        }
      }
    }

  /**
   * Constructs a new `Fun` from a pure function.
   */
  def fromFunction[A, B](f: A => B): Fun[A, B] =
    Fun(f, _.hashCode)

  /**
   * Constructs a new runtime that synchronously executes effects.
   */
  private val funExecutor: Executor =
    Executor.fromExecutionContext {
      new ExecutionContext {
        def execute(runnable: Runnable): Unit =
          runnable.run()
        def reportFailure(cause: Throwable): Unit =
          cause.printStackTrace()
      }
    }

  private def funRuntime[R](implicit trace: Trace): ZIO[R, Nothing, Runtime[R]] =
    ZIO.runtime[R].map { runtime =>
      Runtime(
        runtime.environment,
        runtime.fiberRefs,
        RuntimeFlags.disable(runtime.runtimeFlags)(RuntimeFlag.CooperativeYielding)
      )
    }
}
