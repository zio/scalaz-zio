package zio.internal

import zio.ZIOBaseSpec
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test._

object PartitionedLinkedQueueSpec extends ZIOBaseSpec {

  def spec = suite("PartitionedLinkedQueueSpec")(
    test("partitions round to nearest power of 2") {
      val q = new PartitionedLinkedQueue[String](9)

      assertTrue(q.nPartitions() == 16)
    },
    test("offer and poll items") {
      val q = new PartitionedLinkedQueue[String](9)

      val oneToHundred = (1 to 100).map(_.toString)
      oneToHundred.foreach(q.offer)
      val polled = (1 to 100).map(_ => q.poll()).toSet

      assertTrue(polled == oneToHundred.toSet)
    } @@ exceptJS(nonFlaky),
    test("queue size") {
      val q = new PartitionedLinkedQueue[String](9)

      q.offerAll((1 to 3).map(_.toString))
      assertTrue(q.size() == 3)
    }
  )
}
