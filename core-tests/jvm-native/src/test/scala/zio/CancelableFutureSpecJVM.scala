package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object CancelableFutureSpecJVM extends ZIOBaseSpec {

  import ZIOTag._

  def spec =
    suite("CancelableFutureSpecJVM")(
      test("fromFuture/unsafeRunToFuture doesn't deadlock") {

        val tst =
          for {
            runtime <- ZIO.runtime[Any]
            r <- ZIO.fromFuture { _ =>
                   Unsafe.unsafe { implicit unsafe =>
                     runtime.unsafe.runToFuture(ZIO.succeed(0))
                   }
                 }
          } yield assert(r)(equalTo(0))

        val executor = Executor.fromExecutionContext(
          ExecutionContext.fromExecutor(Executors.newSingleThreadScheduledExecutor())
        )

        ZIO
          .runtime[Any]
          .map(runtime =>
            Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(tst.onExecutor(executor)).getOrThrowFiberFailure())
          )
      } @@ nonFlaky
    ) @@ zioTag(future)
}
