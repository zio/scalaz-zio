package zio.stm

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio._

import java.util.concurrent.{ThreadLocalRandom, TimeUnit}

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 1)
@Measurement(iterations = 4, timeUnit = TimeUnit.SECONDS, time = 1)
@Fork(4)
class TMapContentionBenchmarks {
  import BenchmarkUtil.unsafeRun

  @Param(Array("2", "10"))
  var fibers: Int = _

  val ops: Int = 1000

  private var tmapUpdates: UIO[Unit] = _
  private var trefUpdates: UIO[Unit] = _
  private var refUpdates: UIO[Unit]  = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val keysToUpdate = (1 to fibers).toList
    val data         = (1 to ops).toList.zipWithIndex
    val tmap         = unsafeRun(TMap.fromIterable(data).commit)
    val tref         = TRef.unsafeMake(data.toMap)
    val ref          = Ref.unsafe.make(data.toMap)(Unsafe.unsafe)

    def ri() = ThreadLocalRandom.current().nextInt()

    tmapUpdates = ZIO.foreachParDiscard(keysToUpdate) { i =>
      ZIO.suspendSucceed(tmap.put(i, ri()).commit).repeatN(ops)
    }
    trefUpdates = ZIO.foreachParDiscard(keysToUpdate) { i =>
      ZIO.suspendSucceed(tref.update(_.updated(i, ri())).commit).repeatN(ops)
    }
    refUpdates = ZIO.foreachParDiscard(keysToUpdate) { i =>
      ZIO.suspendSucceed(ref.update(_.updated(i, ri()))).repeatN(ops)
    }
  }

  @Benchmark
  def refMapContention(): Unit =
    unsafeRun(refUpdates)

  @Benchmark
  def trefMapContention(): Unit =
    unsafeRun(trefUpdates)

  @Benchmark
  def tmapContention(): Unit =
    unsafeRun(tmapUpdates)
}
