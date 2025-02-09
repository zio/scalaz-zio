package zio.internal

import zio.ZIOBaseSpec
import zio.test.Assertion.equalTo
import zio.test._

object ConcurrentWeakHashSetSpecJVM extends ZIOBaseSpec {

  final case class Wrapper[A](value: A) {
    override def toString: String = value.toString
  }

  def spec = suite("ConcurrentWeakHashSetSpec")(
    test("check if set is thread-safe with truly concurrent race condition between add & remove") {
      import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}

      val sampleSize      = 1000000
      val executorService = Executors.newFixedThreadPool(2)
      val refs            = new ConcurrentLinkedQueue[Wrapper[Int]]()
      val set             = ConcurrentWeakHashSet[Wrapper[Int]]()
      val lock            = new CountDownLatch(1)

      executorService.submit(new Runnable {
        override def run(): Unit =
          (0 to sampleSize).foreach { idx =>
            val element = Wrapper(idx)
            refs.offer(element)
            set.add(element)
          }
      })

      // remove half of the elements with even indices
      // it'll iterate multiple times over the set (often locking segments),
      // because removing is faster than adding
      executorService.submit(new Runnable {
        override def run(): Unit = {
          var removedCount = 0
          while (removedCount < (sampleSize / 2) + 1) {
            for (idx <- 0 to sampleSize if idx % 2 == 0) {
              if (set.remove(Wrapper(idx))) removedCount += 1
            }
          }
          lock.countDown()
        }
      })

      lock.await(30, TimeUnit.SECONDS) // await for the removal to finish
      assert(set.size())(equalTo((sampleSize / 2) + 1))

      // make sure only odd elements are left from full range
      val expected = (0 to sampleSize).filter(_ % 2 == 1).map(Wrapper(_)).toList
      val actual   = set.iterator.toList.sortBy(_.value)
      assert(actual)(equalTo(expected))
    }
  )

}
