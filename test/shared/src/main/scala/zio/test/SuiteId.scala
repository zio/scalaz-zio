package zio.test

import zio.{Random, Unsafe, ZIO}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * @param id
 *   Level of the spec nesting that you are at. Suites get new values, test
 *   cases inherit their suite's
 */
case class SuiteId(id: Int)

object SuiteId {
  val global: SuiteId = SuiteId(0)

  private val counter = new AtomicInteger(1)

  val newRandom: ZIO[Any, Nothing, SuiteId] =
    ZIO.succeed(newRandomUnsafe(Unsafe))

  private[test] def newRandomUnsafe(implicit unsafe: Unsafe): SuiteId =
    SuiteId(counter.getAndIncrement())
}
