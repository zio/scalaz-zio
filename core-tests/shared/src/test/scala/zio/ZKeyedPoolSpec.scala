package zio

import zio.test._
import zio.test.TestAspect._

object ZKeyedPoolSpec extends ZIOBaseSpec {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ZKeyedPoolSpec")(
      test("acquire release many successfully while other key is blocked") {
        for {
          pool <- ZKeyedPool.make((key: String) => ZIO.succeed(key), size = 4)
          _    <- pool.get("key1").repeatN(3).unit
          fiber <-
            ZIO
              .foreachParDiscard(1 to 400) { _ =>
                ZIO.scoped {
                  pool.get("key2") *> Clock.sleep(10.millis)
                }
              }
              .fork
          _ <- TestClock.adjust((10 * 400).millis)
          _ <- fiber.join
        } yield assertCompletes
      },
      test("acquire release many with invalidates") {
        for {
          counter <- Ref.make(0)
          pool    <- ZKeyedPool.make((key: String) => counter.modify(n => (s"$key-$n", n + 1)), size = 4)
          fiber <-
            ZIO
              .foreachParDiscard(1 to 400) { _ =>
                ZIO.scoped {
                  pool.get("key1").flatMap { value =>
                    pool.invalidate(value).whenZIO(Random.nextBoolean) *>
                      Random.nextIntBounded(15).flatMap(n => Clock.sleep(n.millis))
                  }
                }
              }
              .fork
          _ <- TestClock.adjust((15 * 400).millis)
          _ <- fiber.join
        } yield assertCompletes
      },
      test("invalidate does not cause memory leaks (i9306)") {
        ZKeyedPool
          .make[String, Any, Nothing, Array[Int]]((_: String) => ZIO.succeed(Array.ofDim[Int](1000000)), size = 1)
          .flatMap { pool =>
            ZIO
              .foreachDiscard(1 to 10000)(_ =>
                ZIO.scoped {
                  for {
                    item1 <- pool.get("key0")
                    _     <- ZIO.foreachDiscard(1 to 5)(i => pool.get(s"key$i"))
                    _     <- pool.invalidate(item1)
                  } yield ()
                }
              )
          }
          .as(assertCompletes)
      } @@ jvmOnly,
      test("finalizers of a resource don't leak to the pool's scope") {
        ZIO.scoped(
          ZKeyedPool
            .make[String, Scope, Nothing, Unit]((_: String) => Scope.addFinalizer(ZIO.unit).as(()), size = 1)
            .flatMap { pool =>
              ZIO.scoped(pool.get("key0").flatMap(pool.invalidate(_))).replicateZIODiscard(1000)
            }
            .flatMap(_ => ZIO.scope.map(_.asInstanceOf[Scope.Closeable].size))
            .map(v => assertTrue(v == 1)) // Pool's finalizer
        )
      }
    ) @@ exceptJS

}
