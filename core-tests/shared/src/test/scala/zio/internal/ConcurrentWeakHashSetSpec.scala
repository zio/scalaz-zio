package zio.internal

import zio.test._
import zio.ZIOBaseSpec
import zio.test.Assertion.equalTo
import zio.test.TestAspect.flaky

object ConcurrentWeakHashSetSpec extends ZIOBaseSpec {

  final case class Wrapper[A](value: A) {
    override def toString: String = value.toString
  }

  def spec = suite("ConcurrentWeakHashSetSpec")(
    test("empty set is empty") {
      val set = ConcurrentWeakHashSet[Wrapper[Int]]()
      assertTrue(set.isEmpty)
    },
    test("add should insert elements") {
      val set  = ConcurrentWeakHashSet[Wrapper[Int]]()
      val refs = List(Wrapper(1), Wrapper(2), Wrapper(3))
      set.add(refs.head)
      set.add(refs(1))
      set.addAll(List(refs(2)))
      assert(set.size())(equalTo(3))
    },
    test("set resolves duplicated values") {
      val set = ConcurrentWeakHashSet[Wrapper[Int]]()
      val ref = Wrapper(1)
      set.add(ref)
      set.add(ref)
      assert(set.size())(equalTo(1))
    },
    test("adding an element to set makes it non-empty") {
      val set = ConcurrentWeakHashSet[Wrapper[Int]]()
      val ref = Wrapper(Int.MaxValue)
      set.add(ref)
      assertTrue(!set.isEmpty)
    },
    test("remove should delete reference from set") {
      val set  = ConcurrentWeakHashSet[Wrapper[Int]]()
      val refs = List(Wrapper(1), Wrapper(2))
      set.addAll(refs)
      set.remove(Wrapper(1))
      assert(set.size())(equalTo(1))
      set.remove(Wrapper(2))
      assert(set.size())(equalTo(0))
    },
    test("removing non-existent element should be allowed") {
      val set    = ConcurrentWeakHashSet[Wrapper[Int]]()
      val result = set.remove(Wrapper(1))
      assertTrue(!result)
    },
    test("contains should return true if set stores reference to the element") {
      val set = ConcurrentWeakHashSet[Wrapper[Int]]()
      val ref = Wrapper(1)
      set.add(ref)
      assertTrue(set.contains(ref))
    },
    test("clearing the set makes it empty") {
      val set = ConcurrentWeakHashSet[Wrapper[Int]]()
      val ref = Wrapper(1)
      set.add(ref)
      set.clear()
      assertTrue(set.isEmpty)
    },
    test("can iterate over elements") {
      val set  = ConcurrentWeakHashSet[Wrapper[Int]]()
      val refs = List(Wrapper(1), Wrapper(2))
      set.addAll(refs)
      import scala.jdk.CollectionConverters._
      val allValues = set.iterator.asScala.toList.sortBy(_.value)
      assert(allValues)(equalTo(refs))
    },
    test("removing element with the same index from chain deletes only matched reference") {
      val refs         = (0 to 8).map(Wrapper(_))
      val corruptedSet = ConcurrentWeakHashSet[Wrapper[Int]]()
      corruptedSet.addAll(refs)
      val removed = corruptedSet.remove(Wrapper(4)) // matched index (calculated from hashcode) is the same for 4 and 8
      assertTrue(removed)
      import scala.jdk.CollectionConverters._
      assert(corruptedSet.asScala.toList.map(_.value).sorted)(equalTo(List(0, 1, 2, 3, 5, 6, 7, 8)))
    },
    test("dead references are removed from set") {
      val set = ConcurrentWeakHashSet[Wrapper[Int]]()
      set.add(Wrapper(1)) // dead ref

      val ref = Wrapper(2)
      set.add(ref) // ref from scope

      System.gc()
      set.gc()

      assert(set.size())(equalTo(1))
      assert(set.iterator.next())(equalTo(ref))
    } @@ flaky
  )

}
