/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.scalajs.js
import scala.scalajs.js.{Function1, Promise => JSPromise, Thenable, |}

private[zio] trait ZIOPlatformSpecific[-R, +E, +A] { self: ZIO[R, E, A] =>

  /**
   * Converts the current `ZIO` to a Scala.js promise.
   */
  def toPromiseJS(implicit ev: E IsSubtypeOfError Throwable, trace: Trace): URIO[R, JSPromise[A]] =
    toPromiseJSWith(ev)

  /**
   * Converts the current `ZIO` to a Scala.js promise and maps the error type
   * with `f`.
   */
  def toPromiseJSWith(f: E => Throwable)(implicit trace: Trace): URIO[R, JSPromise[A]] =
    self.foldCause(c => JSPromise.reject(c.squashWith(f)), JSPromise.resolve[A](_))
}

private[zio] trait ZIOCompanionPlatformSpecific { self: ZIO.type =>

  /**
   * Imports a synchronous effect that does blocking IO into a pure value.
   *
   * If the returned `ZIO` is interrupted, the blocked thread running the
   * synchronous effect will be interrupted via `Thread.interrupt`.
   *
   * Note that this adds significant overhead. For performance sensitive
   * applications consider using `attemptBlocking` or
   * `attemptBlockingCancelable`.
   */
  def attemptBlockingInterrupt[A](effect: => A)(implicit trace: Trace): Task[A] =
    ZIO.attemptBlocking(effect)

  /**
   * Imports a Scala.js promise into a `ZIO`.
   */
  def fromPromiseJS[A](promise: => JSPromise[A])(implicit trace: Trace): Task[A] =
    self.async { callback =>
      val onFulfilled: Function1[A, Unit | Thenable[Unit]] = new scala.Function1[A, Unit | Thenable[Unit]] {
        def apply(a: A): Unit | Thenable[Unit] = callback(ZIO.succeed(a))
      }
      val onRejected: Function1[Any, Unit | Thenable[Unit]] = new scala.Function1[Any, Unit | Thenable[Unit]] {
        def apply(e: Any): Unit | Thenable[Unit] =
          callback(ZIO.fail(e match {
            case t: Throwable => t
            case _            => js.JavaScriptException(e)
          }))
      }
      promise.`then`[Unit](onFulfilled, js.defined(onRejected))
    }

  def writeFile(file: => String, content: => String)(implicit trace: Trace): ZIO[Any, Throwable, Unit] = {
    import scalajs.js.Dynamic.{global => g}
    val fs = g.require("fs")
    ZIO.attemptBlocking(fs.writeFileSync(file, content))
  }

  def readFile(file: => String)(implicit trace: Trace): ZIO[Any, Throwable, String] = {
    import scalajs.js.Dynamic.{global => g}
    val fs = g.require("fs")
    ZIO.attemptBlocking(fs.readFileSync(file).toString)
  }

}
