package zio.internal

import zio._
import zio.test._

object UpdateOrderLinkedMapSpec extends ZIOBaseSpec {
  private val empty = UpdateOrderLinkedMap.empty[String, Int]

  def spec = suite("UpdateOrderLinkedMap")(
    test("updating with new elements") {
      val m = empty.updated("a", 1).updated("b", 2).updated("c", 3)
      assertTrue(m.iterator.toList == List("a" -> 1, "b" -> 2, "c" -> 3))
    },
    test("updating existing elements") {
      val m = empty.updated("a", 1).updated("b", 2).updated("c", 3).updated("b", 4).updated("a", 5)
      assertTrue(m.iterator.toList == List("c" -> 3, "b" -> 4, "a" -> 5))
    },
    test("reverseIterator") {
      val m = empty.updated("a", 1).updated("b", 2).updated("c", 3).updated("b", 4).updated("d", 5).updated("a", 6)
      assertTrue(m.reverseIterator.toList == List("a" -> 6, "d" -> 5, "b" -> 4, "c" -> 3))
    },
    test("builder") {
      val m =
        UpdateOrderLinkedMap
          .newBuilder[String, Int]
          .addOne("a" -> 1)
          .addOne("b" -> 2)
          .addOne("c" -> 3)
          .addOne("b" -> 4)
          .addOne("d" -> 5)
          .result()

      assertTrue(m.reverseIterator.toList == List("d" -> 5, "b" -> 4, "c" -> 3, "a" -> 1))

    },
    test("iterator is stack-safe") {
      (1 to 100000)
        .foldLeft(empty) { case (m, i) =>
          m.updated(i.toString, i)
        }
        .iterator
        .foreach(_ => ())
      assertCompletes
    },
    test("reverseIterator is stack-safe") {
      (1 to 100000)
        .foldLeft(empty) { case (m, i) =>
          m.updated(i.toString, i)
        }
        .reverseIterator
        .foreach(_ => ())
      assertCompletes
    },
    test("a,b,c,b") {
      val m0 = UpdateOrderLinkedMap.empty[String, String]
      val m1 = m0.updated("a", "a1")
      val m2 = m1.updated("b", "b1")
      val m3 = m2.updated("c", "c1")
      val m4 = m3.updated("b", "b2")

      val asSeq = m4.iterator.toSeq

      zio.test.assert(asSeq)(Assertion.equalTo(Seq("a" -> "a1", "c" -> "c1", "b" -> "b2")))
    }
  )
}
