package zio.stm

import zio._
import zio.test.TestAspect.nonFlaky
import zio.test._

object ZSTMJvmSpec extends ZIOBaseSpec {

  def spec = suite("ZSTMJvmSpec")(
    test("doesn't deadlock under concurrency when transactions create new ZSTMs - i9215") {
      def transaction(arr: TArray[String]) =
        ZSTM.suspend {
          Thread.sleep(1) // Force ZSTM lock contention
          TArray.make(List.fill(1000)(scala.util.Random.nextString(5)): _*).flatMap { arr2 =>
            var i = -1
            arr2.foreach(v =>
              ZSTM.suspend {
                i += 1
                arr.update(i, v + _)
              }
            )
          }
        }
      for {
        arr <- TArray.make(List.fill(1000)("foo"): _*).commit
        stm  = transaction(arr)
        _   <- ZIO.foreachParDiscard(1 to 100)(_ => ZIO.blocking(stm.commit))
      } yield assertCompletes
    } @@ nonFlaky(5)
  )

}
