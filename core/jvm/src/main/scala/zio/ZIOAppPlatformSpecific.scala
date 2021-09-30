package zio

import zio.internal._

trait ZIOAppPlatformSpecific { self: ZIOApp =>

  /**
   * The Scala main function, intended to be called only by the Scala runtime.
   */
  final def main(args0: Array[String]): Unit =
    runtime.unsafeRun {
      (for {
        fiber <- invoke(Chunk.fromIterable(args0)).provide(runtime.environment).fork
        _ <-
          IO.succeed(Platform.addShutdownHook { () =>
            shuttingDown = true

            if (FiberContext.fatal.get) {
              println(
                "**** WARNING ****\n" +
                  "Catastrophic JVM error encountered. " +
                  "Application not safely interrupted. " +
                  "Resources may be leaked. " +
                  "Check the logs for more details and consider overriding `RuntimeConfig.reportFatal` to capture context."
              )
            } else {
              try runtime.unsafeRunSync(fiber.interrupt)
              catch { case _: Throwable => }
            }

            ()
          })
        result <- fiber.join.tapErrorCause(ZIO.logErrorCause(_)).exitCode
        _      <- exit(result)
      } yield ())
    }

}
