package zio.internal

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import zio.internal.BenchUtils._
import zio.internal.ProducerConsumerBenchmark.{OfferCounters, PollCounters}

import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(5)
@State(Scope.Group)
private[this] class OneElementQueueConcBenchmark {
  val DELAY_PRODUCER: Long = 100L
  val DELAY_CONSUMER: Long = 100L

  type QueueElement = AnyRef

  def mkEl(): QueueElement  = new Object()
  val emptyEl: QueueElement = null.asInstanceOf[QueueElement]

  val qCapacity: Int = 2 // because we want to construct RingBuffer* as well

  @Param(Array("OneElementQueue", "OneElementQueueNoMetric", "RingBufferPow2"))
  var qType: String = _

  var q: MutableConcurrentQueue[QueueElement] = _

  def backoff(): Unit = {
    // do backoff
  }

  @Setup(Level.Trial)
  def createQ(): Unit =
    q = queueByType(qType, qCapacity)

  @Benchmark
  @Group("Group1SPSC")
  @GroupThreads(1)
  def group1Offer(counters: OfferCounters): Unit = doOffer(counters)

  @Benchmark
  @Group("Group1SPSC")
  @GroupThreads(1)
  def group1Poll(counters: PollCounters): Unit = doPoll(counters)

  @Benchmark
  @Group("Group2ModerateContention")
  @GroupThreads(2)
  def group2Offer(counters: OfferCounters): Unit = doOffer(counters)

  @Benchmark
  @Group("Group2ModerateContention")
  @GroupThreads(2)
  def group2Poll(counters: PollCounters): Unit = doPoll(counters)

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  def doOffer(counters: OfferCounters): Unit = {
    val anEl = mkEl()

    if (!q.offer(anEl)) {
      counters.failedOffers += 1
      backoff()
    } else {
      counters.madeOffers += 1
    }

    if (DELAY_PRODUCER != 0) {
      Blackhole.consumeCPU(DELAY_PRODUCER)
    }
  }

  @CompilerControl(CompilerControl.Mode.DONT_INLINE)
  def doPoll(counters: PollCounters): Unit = {
    val polled = q.poll(emptyEl)

    if (polled == emptyEl) {
      counters.failedPolls += 1
      backoff()
    } else {
      counters.madePolls += 1
    }

    if (DELAY_CONSUMER != 0) {
      Blackhole.consumeCPU(DELAY_CONSUMER)
    }
  }
}

object OneElementQueueConcBenchmark {
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  class PollCounters(var failedPolls: Long, var madePolls: Long) {
    def this() = this(0, 0)
  }

  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.EVENTS)
  class OfferCounters(var failedOffers: Long, var madeOffers: Long) {
    def this() = this(0, 0)
  }
}
