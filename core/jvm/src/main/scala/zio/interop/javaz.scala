package zio.interop

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

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import _root_.java.nio.channels.CompletionHandler
import _root_.java.util.concurrent._
import scala.concurrent.ExecutionException

private[zio] object javaz {

  def asyncWithCompletionHandler[T](op: CompletionHandler[T, Any] => Any)(implicit trace: Trace): Task[T] =
    ZIO.isFatalWith[Any, Throwable, T] { isFatal =>
      ZIO.async { k =>
        val handler = new CompletionHandler[T, Any] {
          def completed(result: T, u: Any): Unit = k(Exit.succeed(result))

          def failed(t: Throwable, u: Any): Unit = t match {
            case e if !isFatal(e) => k(ZIO.fail(e))
            case _                => k(ZIO.die(t))
          }
        }

        try {
          op(handler)
        } catch {
          case e if !isFatal(e) => k(ZIO.fail(e))
        }
      }
    }

  private def catchFromGet(
    isFatal: Throwable => Boolean
  )(implicit trace: Trace): PartialFunction[Throwable, Task[Nothing]] = {
    case e: CompletionException =>
      ZIO.fail(e.getCause)
    case e: ExecutionException =>
      ZIO.fail(e.getCause)
    case _: InterruptedException =>
      ZIO.interrupt
    case _: CancellationException =>
      ZIO.interrupt
    case e if !isFatal(e) =>
      ZIO.fail(e)
  }

  def unwrapDone[A](isFatal: Throwable => Boolean)(f: Future[A])(implicit trace: Trace): Task[A] =
    try {
      Exit.succeed(f.get())
    } catch catchFromGet(isFatal)

  def fromCompletionStage[A](thunk: => CompletionStage[A])(implicit trace: Trace): Task[A] =
    ZIO.uninterruptibleMask { restore =>
      ZIO.attempt(thunk).flatMap { cs =>
        ZIO.isFatalWith { isFatal =>
          val cf = cs.toCompletableFuture
          if (cf.isDone) {
            unwrapDone(isFatal)(cf)
          } else {
            val cancel = ZIO.succeed(cf.cancel(false))
            restore {
              ZIO.asyncInterrupt[Any, Throwable, A] { cb =>
                val _ = cs.handle[Unit] { (v: A, t: Throwable) =>
                  val io =
                    if (t eq null) Exit.succeed(v)
                    else catchFromGet(isFatal).applyOrElse(t, (d: Throwable) => ZIO.die(d))
                  cb(io)
                }
                Left(cancel)
              }
            }.onInterrupt(cancel)
          }
        }
      }
    }

  /**
   * WARNING: this uses the blocking Future#get, consider using
   * `fromCompletionStage`
   */
  def fromFutureJava[A](thunk: => Future[A])(implicit trace: Trace): Task[A] =
    ZIO.uninterruptibleMask { restore =>
      ZIO.attempt(thunk).flatMap { future =>
        ZIO.isFatalWith { isFatal =>
          if (future.isDone) {
            unwrapDone(isFatal)(future)
          } else {
            restore {
              ZIO.blocking(ZIO.suspend(unwrapDone(isFatal)(future)))
            }.onInterrupt(ZIO.succeed(future.cancel(false)))
          }
        }
      }
    }

  /**
   * CompletableFuture#failedFuture(Throwable) available only since Java 9
   */
  object CompletableFuture_ {
    def failedFuture[A](e: Throwable): CompletableFuture[A] = {
      val f = new CompletableFuture[A]
      f.completeExceptionally(e)
      f
    }
  }
}
