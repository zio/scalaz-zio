package zio

import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] trait ZIOAppPlatformSpecific { self: ZIOApp =>

  /**
   * The Scala main function, intended to be called only by the Scala runtime.
   */
  final def main(args0: Array[String]): Unit = {
    implicit val trace  = Tracer.newTrace
    implicit val unsafe = Unsafe.unsafe

    val newLayer =
      Scope.default +!+ ZLayer.succeed(ZIOAppArgs(Chunk.fromIterable(args0))) >>>
        bootstrap +!+ ZLayer.environment[ZIOAppArgs with Scope]

    runtime.unsafe.fork {
      (for {
        runtime <- ZIO.runtime[Environment with ZIOAppArgs with Scope]
        _       <- installSignalHandlers(runtime)
        _       <- runtime.run(run)
      } yield ()).provideLayer(newLayer).tapErrorCause(ZIO.logErrorCause(_)).exitCode.tap(exit)
    }
  }
}
