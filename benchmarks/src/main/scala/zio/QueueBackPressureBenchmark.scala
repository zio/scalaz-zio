package zio

import cats.effect.unsafe.implicits.global
import cats.effect.{IO => CIO}
import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._
import zio.stm._

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 10, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 10, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(2)
/**
 * This benchmark offers and takes a number of items in parallel, with a very
 * small queue to enforce back pressure mechanism is used.
 */
class QueueBackPressureBenchmark {
  @Param(Array("2", "8", "16"))
  var queueSize   = 2
  val totalSize   = 1000
  val parallelism = 5

  var zioQ: Queue[Int]                      = _
  var fs2Q: cats.effect.std.Queue[CIO, Int] = _
  var zioTQ: TQueue[Int]                    = _
  // var monixQ: monix.catnap.ConcurrentQueue[MTask, Int] = _

  @Setup(Level.Trial)
  def createQueues(): Unit = {
    zioQ = unsafeRun(Queue.bounded[Int](queueSize))
    fs2Q = cats.effect.std.Queue.bounded[CIO, Int](queueSize).unsafeRunSync()
    zioTQ = unsafeRun(TQueue.bounded(queueSize).commit)
    // monixQ = monix.catnap.ConcurrentQueue.bounded[MTask, Int](queueSize).runSyncUnsafe()
  }

  @Benchmark
  def zioQueueMPMC(): Int = {

    val io = for {
      offers <- ZIO.forkAll(List.fill(parallelism)(repeat(totalSize / parallelism)(zioQ.offer(0).unit)))
      takes  <- ZIO.forkAll(List.fill(parallelism)(repeat(totalSize / parallelism)(zioQ.take.unit)))
      _      <- offers.join
      _      <- takes.join
    } yield 0

    unsafeRun(io)
  }

  @Benchmark
  def zioQueueSPMC(): Int = {

    val io = for {
      offers <- zioQ.offer(0).replicateZIO(totalSize).fork
      takes  <- ZIO.forkAll(List.fill(parallelism)(repeat(totalSize / parallelism)(zioQ.take.unit)))
      _      <- offers.join
      _      <- takes.join
    } yield 0

    unsafeRun(io)
  }

  @Benchmark
  def zioQueueMPSC(): Int = {

    val io = for {
      offers <- ZIO.forkAll(List.fill(parallelism)(repeat(totalSize / parallelism)(zioQ.offer(0).unit)))
      takes  <- zioQ.take.replicateZIO(totalSize).fork
      _      <- offers.join
      _      <- takes.join
    } yield 0

    unsafeRun(io)
  }

  @Benchmark
  def zioTQueue(): Int = {

    val io = for {
      offers <- ZIO.forkAll(List.fill(parallelism)(repeat(totalSize / parallelism)(zioTQ.offer(0).unit.commit)))
      takes  <- ZIO.forkAll(List.fill(parallelism)(repeat(totalSize / parallelism)(zioTQ.take.unit.commit)))
      _      <- offers.join
      _      <- takes.join
    } yield 0

    unsafeRun(io)
  }

  @Benchmark
  def fs2Queue(): Int = {

    val io = for {
      offers <- catsForkAll(List.fill(parallelism)(catsRepeat(totalSize / parallelism)(fs2Q.offer(0))))
      takes  <- catsForkAll(List.fill(parallelism)(catsRepeat(totalSize / parallelism)(fs2Q.take.void)))
      _      <- offers.join
      _      <- takes.join
    } yield 0

    io.unsafeRunSync()
  }

  // @Benchmark
  // def monixQueue(): Int = {
  //   import BenchmarkUtil.monixScheduler

  //   val io = for {
  //     offers <- monixForkAll(List.fill(parallelism)(monixRepeat(totalSize / parallelism)(monixQ.offer(0))))
  //     takes  <- monixForkAll(List.fill(parallelism)(monixRepeat(totalSize / parallelism)(monixQ.poll.map(_ => ()))))
  //     _      <- offers.join
  //     _      <- takes.join
  //   } yield 0

  //   io.runSyncUnsafe()
  // }
}
