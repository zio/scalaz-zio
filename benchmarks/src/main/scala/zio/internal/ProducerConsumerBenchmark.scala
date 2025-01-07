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
private[this] class ProducerConsumerBenchmark {
  val DELAY_PRODUCER: Long = 100L
  val DELAY_CONSUMER: Long = 100L

  type QueueElement = AnyRef

  def mkEl(): QueueElement  = new Object()
  val emptyEl: QueueElement = null.asInstanceOf[QueueElement]

  @Param(Array("65536"))
  var qCapacity: Int = _

  @Param(Array("RingBufferPow2", "RingBufferArb", "JCTools", "LinkedQueue", "JucBlocking"))
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
  @GroupThreads(4)
  def group2Offer(counters: OfferCounters): Unit = doOffer(counters)

  @Benchmark
  @Group("Group2ModerateContention")
  @GroupThreads(4)
  def group2Poll(counters: PollCounters): Unit = doPoll(counters)

  // @Benchmark
  @Group("Group3HighContention")
  @GroupThreads(8)
  def group3Offer(counters: OfferCounters): Unit = doOffer(counters)

  // @Benchmark
  @Group("Group3HighContention")
  @GroupThreads(8)
  def groupt3Poll(counters: PollCounters): Unit = doPoll(counters)

  // @Benchmark
  @Group("Group4ContendedOffer")
  @GroupThreads(6)
  def group4Offer(counters: OfferCounters): Unit = doOffer(counters)

  // @Benchmark
  @Group("Group4ContendedOffer")
  @GroupThreads(2)
  def group4Poll(counters: PollCounters): Unit = doPoll(counters)

  // @Benchmark
  @Group("Group5ContendedPoll")
  @GroupThreads(2)
  def group5Offer(counters: OfferCounters): Unit = doOffer(counters)

  // @Benchmark
  @Group("Group5ContendedPoll")
  @GroupThreads(6)
  def groupt5Poll(counters: PollCounters): Unit = doPoll(counters)

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

object ProducerConsumerBenchmark {
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
