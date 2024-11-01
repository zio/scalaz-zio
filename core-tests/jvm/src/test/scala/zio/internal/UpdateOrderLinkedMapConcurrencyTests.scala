package zio.internal

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.infra.results.LL_Result

object UpdateOrderLinkedMapConcurrencyTests {

  /*
   * Tests that there are no race conditions between publishing and taking..
   */
  @JCStressTest
  @Outcome.Outcomes(
    Array(
      new Outcome(id = Array("List(4, 3, 2, 1, 0), List(4, 3, 2, 1, 0)"), expect = Expect.ACCEPTABLE)
    )
  )
  @State
  class ReverseIteratorTest {
    val map: UpdateOrderLinkedMap[String, Int] =
      (0 until 1000).foldLeft(UpdateOrderLinkedMap.empty[String, Int]) { case (m, i) =>
        val ii = i % 5
        m.updated(ii.toString, ii)
      }

    var r1: List[Int] = null
    var r2: List[Int] = null

    @Actor
    def actor1(): Unit = {
      r1 = map.reverseIterator.map(_._2).toList
      ()
    }

    @Actor
    def actor2(): Unit = {
      r2 = map.reverseIterator.map(_._2).toList
      ()
    }

    @Arbiter
    def arbiter(r: LL_Result): Unit = {
      r.r1 = r1
      r.r2 = r2
    }
  }

}
