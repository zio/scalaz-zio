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

import zio.internal.OneShot
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io
import java.io.IOException
import java.net.{URI, URL}
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

private[zio] trait ZIOPlatformSpecific[-R, +E, +A] { self: ZIO[R, E, A] =>
  def toCompletableFuture[A1 >: A](implicit
    ev: E IsSubtypeOfError Throwable,
    trace: Trace
  ): URIO[R, CompletableFuture[A1]] =
    toCompletableFutureWith(ev)

  def toCompletableFutureWith[A1 >: A](f: E => Throwable)(implicit
    trace: Trace
  ): URIO[R, CompletableFuture[A1]] =
    ZIO.uninterruptibleMask { restore =>
      for {
        future <- ZIO.succeed(new CompletableFuture[A1])
        _ <- restore(self)
               .foldCause(
                 cause => future.completeExceptionally(cause.squashTraceWith(f)),
                 a => future.complete(a)
               )
               .fork
      } yield future
    }
}

private[zio] trait ZIOCompanionPlatformSpecific extends ZIOPlatformSpecificJVM {

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
    ZIO.suspendSucceed {

      val lock   = new ReentrantLock()
      val thread = new AtomicReference[Option[Thread]](None)
      val begin  = OneShot.make[Unit]
      val end    = OneShot.make[Unit]

      def withMutex[B](b: => B): B =
        try {
          lock.lock(); b
        } finally lock.unlock()

      val interruptThread: UIO[Unit] =
        ZIO.succeed {
          begin.get()

          var looping = true
          var n       = 0L
          val base    = 2L
          while (looping) {
            withMutex(thread.get match {
              case None         => looping = false; ()
              case Some(thread) => thread.interrupt()
            })

            if (looping) {
              n += 1
              Thread.sleep(math.min(50, base * n))
            }
          }

          end.get()
        }

      ZIO.blocking(
        ZIO.uninterruptibleMask(restore =>
          for {
            fiber <- ZIO.suspend {
                       val current = Some(Thread.currentThread)

                       withMutex(thread.set(current))

                       begin.set(())

                       try {
                         val a = effect

                         ZIO.succeed(a)
                       } catch {
                         case _: InterruptedException =>
                           Thread.interrupted // Clear interrupt status
                           ZIO.interrupt
                         case t: Throwable =>
                           ZIO.fail(t)
                       } finally {
                         withMutex { thread.set(None); end.set(()) }
                       }
                     }.forkDaemon
            a <- restore(fiber.join).ensuring(interruptThread)
          } yield a
        )
      )
    }

  def readFile(path: => Path)(implicit trace: Trace, d: DummyImplicit): ZIO[Any, IOException, String] =
    readFile(path.toString)

  def readFile(name: => String)(implicit trace: Trace): ZIO[Any, IOException, String] =
    ZIO.acquireReleaseWith(ZIO.attemptBlockingIO(scala.io.Source.fromFile(name)))(s =>
      ZIO.attemptBlocking(s.close()).orDie
    ) { s =>
      ZIO.attemptBlockingIO(s.mkString)
    }

  def readFileInputStream(
    path: => Path
  )(implicit trace: Trace, d: DummyImplicit): ZIO[Scope, IOException, ZInputStream] =
    readFileInputStream(path.toString)

  def readFileInputStream(
    name: => String
  )(implicit trace: Trace): ZIO[Scope, IOException, ZInputStream] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlockingIO {
          val fis = new io.FileInputStream(name)
          (fis, ZInputStream.fromInputStream(fis))
        }
      )(tuple => ZIO.attemptBlocking(tuple._1.close()).orDie)
      .map(_._2)

  def readURLInputStream(
    url: => URL
  )(implicit trace: Trace, d: DummyImplicit): ZIO[Scope, IOException, ZInputStream] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlockingIO {
          val fis = url.openStream()
          (fis, ZInputStream.fromInputStream(fis))
        }
      )(tuple => ZIO.attemptBlocking(tuple._1.close()).orDie)
      .map(_._2)

  def readURLInputStream(
    url: => String
  )(implicit trace: Trace): ZIO[Scope, IOException, ZInputStream] =
    ZIO.succeed(new URL(url)).flatMap(readURLInputStream(_))

  def readURIInputStream(uri: => URI)(implicit trace: Trace): ZIO[Scope, IOException, ZInputStream] =
    for {
      uri        <- ZIO.succeed(uri)
      isAbsolute <- ZIO.attemptBlockingIO(uri.isAbsolute)
      is         <- if (isAbsolute) readURLInputStream(uri.toURL) else readFileInputStream(uri.toString)
    } yield is

  def writeFile(path: => String, content: => String)(implicit trace: Trace): ZIO[Any, IOException, Unit] =
    ZIO.acquireReleaseWith(ZIO.attemptBlockingIO(new java.io.FileWriter(path)))(f =>
      ZIO.attemptBlocking(f.close()).orDie
    ) { f =>
      ZIO.attemptBlockingIO(f.write(content))
    }

  def writeFile(path: => Path, content: => String)(implicit
    trace: Trace,
    d: DummyImplicit
  ): ZIO[Any, IOException, Unit] =
    writeFile(path.toString, content)

  def writeFileOutputStream(
    path: => String
  )(implicit trace: Trace): ZIO[Scope, IOException, ZOutputStream] =
    ZIO
      .acquireRelease(
        ZIO.attemptBlockingIO {
          val fos = new io.FileOutputStream(path)
          (fos, ZOutputStream.fromOutputStream(fos))
        }
      )(tuple => ZIO.attemptBlocking(tuple._1.close()).orDie)
      .map(_._2)

  def writeFileOutputStream(
    path: => Path
  )(implicit trace: Trace, d: DummyImplicit): ZIO[Scope, IOException, ZOutputStream] =
    writeFileOutputStream(path.toString)

}
