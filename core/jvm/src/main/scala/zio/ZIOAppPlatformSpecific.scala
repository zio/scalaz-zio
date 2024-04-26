package zio

import zio.internal._
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] trait ZIOAppPlatformSpecific { self: ZIOApp =>

  /**
   * The Scala main function, intended to be called only by the Scala runtime.
   */
  final def main(args0: Array[String]): Unit = {
    implicit val trace: Trace   = Trace.empty
    implicit val unsafe: Unsafe = Unsafe.unsafe

    val newLayer =
      ZLayer.succeed(ZIOAppArgs(Chunk.fromIterable(args0))) >>>
        bootstrap +!+ ZLayer.environment[ZIOAppArgs]

    val workflow =
      (for {
        runtime <- ZIO.runtime[Environment with ZIOAppArgs]
        _       <- installSignalHandlers(runtime)
        result  <- runtime.run(ZIO.scoped[Environment with ZIOAppArgs](run)).tapErrorCause(ZIO.logErrorCause(_))
      } yield result).provideLayer(newLayer.tapErrorCause(ZIO.logErrorCause(_)))

    runtime.unsafe.run {
      ZIO.uninterruptibleMask { restore =>
        for {
          fiberId  <- ZIO.fiberId
          p        <- Promise.make[Nothing, Set[FiberId.Runtime]]
          interrupt = interruptRootFibers(p)
          fiber <- restore(workflow)
                     .foldCauseZIO(
                       cause => interrupt *> exit(ExitCode.failure) *> ZIO.refailCause(cause),
                       _ => interrupt *> exit(ExitCode.success)
                     )
                     .fork
          _ <-
            ZIO.succeed(Platform.addShutdownHook { () =>
              if (!shuttingDown.getAndSet(true)) {

                if (FiberRuntime.catastrophicFailure.get) {
                  println(
                    "**** WARNING ****\n" +
                      "Catastrophic error encountered. " +
                      "Application not safely interrupted. " +
                      "Resources may be leaked. " +
                      "Check the logs for more details and consider overriding `Runtime.reportFatal` to capture context."
                  )
                } else {
                  try {
                    val completePromise = ZIO.fiberIdWith(fid2 => p.succeed(Set(fiberId, fid2)))
                    runtime.unsafe.run(completePromise *> fiber.interrupt)
                  } catch {
                    case _: Throwable =>
                  }
                }

                ()
              }
            })
          result <- fiber.join
        } yield result
      }
    }.getOrThrowFiberFailure()
  }

  private def interruptRootFibers(p: Promise[Nothing, Set[FiberId.Runtime]])(implicit trace: Trace): UIO[Unit] =
    for {
      ignoredIds <- p.await
      roots      <- Fiber.roots
      _          <- Fiber.interruptAll(roots.view.filter(fiber => fiber.isAlive() && !ignoredIds(fiber.id)))
    } yield ()

}
