package zio.internal

import org.openjdk.jmh.annotations.{Scope => JScope, _}

import java.util.concurrent.{TimeUnit, ThreadLocalRandom}
import java.util.concurrent.atomic.AtomicLong

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(16)
class WeakConcurrentBagBenchmark {

  @Param(Array("1000"))
  var capacity: Int = _

  @Param(Array("1000"))
  var elements: Int = _

  var bag: WeakConcurrentBag[BagEntry] = null

  @Setup(Level.Iteration)
  def createBag() = {
    WeakConcurrentBagBenchmark.alive.set(0L)
    WeakConcurrentBagBenchmark.dead.set(0L)

    bag = WeakConcurrentBag(1000, _.isAlive())
  }

  @Benchmark
  def add(): Any =
    bag.add(BagEntry())

  @TearDown(Level.Iteration)
  def printStats(): Unit = {
    val alive = WeakConcurrentBagBenchmark.alive.get
    val dead  = WeakConcurrentBagBenchmark.dead.get
    println(s"dead: ${dead}")
    println(s"alive: ${dead}")
    println(s"total: ${dead + alive}")
    println(s"aliveness: ${alive.toDouble / (alive.toDouble + dead.toDouble)}")
  }
}

object WeakConcurrentBagBenchmark {
  val alive: AtomicLong = new AtomicLong(0L)
  val dead: AtomicLong  = new AtomicLong(0L)

  final val instrument = true
}

final case class BagEntry(expiration: Long) {
  import WeakConcurrentBagBenchmark._

  def isAlive(): Boolean = {
    val result = System.nanoTime() <= expiration

    if (instrument) {
      if (result) alive.incrementAndGet() else dead.incrementAndGet()
    }

    result
  }
}
object BagEntry {
  def apply(): BagEntry = {
    val random = ThreadLocalRandom.current()

    BagEntry(System.nanoTime() + random.nextInt(100000))
  }
}
