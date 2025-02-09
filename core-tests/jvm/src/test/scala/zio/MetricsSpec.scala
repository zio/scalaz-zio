package zio

import zio.metrics.jvm.DefaultJvmMetrics
import zio.test._

object MetricsSpec extends ZIOBaseSpec {

  def spec: Spec[Any, Any] = suite("MetricsSpec")(
    suite("Attach default JVM metrics to a layer and check that")(
      test("The layer can be interrupted") {
        val run =
          for {
            latch <- Promise.make[Nothing, Unit]
            frk    = (latch.succeed(()) *> ZIO.never).provideLayer(DefaultJvmMetrics.live.unit)
            _     <- frk.forkDaemon.flatMap(f => latch.await *> f.interrupt *> f.await)
          } yield ()

        run *> assertCompletes
      }
    )
  )
}
