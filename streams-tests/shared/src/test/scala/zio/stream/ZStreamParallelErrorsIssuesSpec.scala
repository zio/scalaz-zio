package zio.stream

import zio._
import zio.test.TestAspect._
import zio.test._

object ZStreamParallelErrorsIssuesSpec extends ZIOBaseSpec {

  private val iters = (1 to 5000).toList

  def spec = suite("ZStreamParallelErrorsIssuesSpec")(
    test("mapZIOPar") {
      ZIO
        .foreachParDiscard(iters) { _ =>
          ZStream
            .fromIterable(1 to 50)
            .mapZIOPar(20)(i => if (i < 10) ZIO.succeed(i) else ZIO.fail("Boom"))
            .mapZIOPar(20)(ZIO.succeed(_))
            .runCollect
            .either
        }
        .as(assertCompletes)
    },
    test("mapZIOParUnordered") {
      ZIO
        .foreachParDiscard(iters) { _ =>
          ZStream
            .fromIterable(1 to 50)
            .mapZIOParUnordered(20)(i => if (i < 10) ZIO.succeed(i) else ZIO.fail("Boom"))
            .mapZIOParUnordered(20)(ZIO.succeed(_))
            .runCollect
            .either
        }
        .as(assertCompletes)
    }
  ) @@ timeout(10.seconds) @@ sequential @@ jvmOnly
}
