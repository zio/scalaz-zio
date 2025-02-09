package zio.internal

import zio.ZIOBaseSpec
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test._

object PartitionedRingBufferSpec extends ZIOBaseSpec {

  def spec = suite("PartitionedRingBufferSpec")(
    test("capacity is the specified when divisible by the number of partitions") {
      val q = new PartitionedRingBuffer[String](4, 8, roundToPow2 = false)

      assertTrue(q.capacity == 8)
    },
    test("capacity rounds up when not divisible by the number of partitions") {
      val q = new PartitionedRingBuffer[String](4, 9, roundToPow2 = false)

      assertTrue(q.capacity == 12)
    },
    test("partitions round to nearest power of 2") {
      val q = new PartitionedRingBuffer[String](3, 9, roundToPow2 = false)

      assertTrue(q.nPartitions() == 4)
    },
    test("capacity of each partition rounds up to nearest power of 2 when specified") {
      val q = new PartitionedRingBuffer[String](3, 9, roundToPow2 = true)

      assertTrue(q.capacity == 16)
    },
    test("offered items are accepted up to capacity") {
      val q = new PartitionedRingBuffer[String](2, 2, roundToPow2 = true)

      assertTrue(
        q.offer("1"),
        q.offer("2"),
        q.offer("3"),
        q.offer("4"),
        !q.offer("5")
      )
    } @@ exceptJS(nonFlaky),
    test("polling items") {
      val q = new PartitionedRingBuffer[String](2, 2, roundToPow2 = true)

      (1 to 4).foreach(i => q.offer(i.toString))
      assertTrue(q.pollUpTo(4).toSet == Set("1", "2", "3", "4"))
    } @@ exceptJS(nonFlaky),
    test("iterating partitions") {
      val q = new PartitionedRingBuffer[String](2, 2, roundToPow2 = true)

      (1 to 4).foreach(i => q.offer(i.toString))
      val p = q.partitionIterator.toList
      assertTrue(p.size == 2, p.forall(_.size() == 2))
    },
    test("returns the default value when the queue is empty") {
      val q = new PartitionedRingBuffer[String](2, 2, roundToPow2 = true)

      assertTrue(q.poll("default") == "default")
    },
    test("returns the correct size") {
      val q = new PartitionedRingBuffer[String](2, 2, roundToPow2 = true)

      q.offerAll((1 to 3).map(_.toString))
      assertTrue(q.size() == 3)
    },
    test("underlying partitions can be RingBufferPowArb when roundToPow2 = false") {
      val q = new PartitionedRingBuffer[String](3, 9, roundToPow2 = false)

      val result = q.partitionIterator.toList.forall(_.isInstanceOf[RingBufferArb[_]])
      assertTrue(result)
    },
    test("underlying partitions are forced to RingBuggerPow2 when roundToPow2 = true") {
      val q = new PartitionedRingBuffer[String](3, 9, roundToPow2 = true)

      val result = q.partitionIterator.toList.forall(_.isInstanceOf[RingBufferPow2[_]])
      assertTrue(result)
    }
  )
}
