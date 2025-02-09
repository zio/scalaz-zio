package zio

import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Scope => JScope, _}

import java.util.concurrent.TimeUnit

/**
 * {{{
 * 17/12/2020
 * [info] Benchmark                         (n)   Mode  Cnt   Score   Error  Units
 * [info] ForkJoinBenchmark.catsForkJoin  10000  thrpt    5  64.582 ∩┐╜ 3.397  ops/s
 * [info] ForkJoinBenchmark.zioForkJoin   10000  thrpt    5  104.019 ∩┐╜ 3.902  ops/s
 * }}}
 *
 * {{{
 * [info] Benchmark                        (n)   Mode  Cnt    Score   Error  Units
 * [info] ForkJoinBenchmark.zioForkJoin  10000  thrpt    5  103.740 ∩┐╜ 2.252  ops/s
 * [info] ForkJoinBenchmark.zioForkJoin  10000  thrpt    5  99.841 ∩┐╜  1.353  ops/s
 * [info] ForkJoinBenchmark.zioForkJoin  10000  thrpt    5  105.782 ∩┐╜ 1.599  ops/s
 * }}}
 *
 * {{{
 * 13/04/2024
 *
 * JDK 17 (ZScheduler)
 * [info] Benchmark                                    (n)   Mode  Cnt     Score     Error  Units
 * [info] ForkJoinBenchmark.catsForkJoin             10000  thrpt   10  2906.462 ±  14.976  ops/s
 * [info] ForkJoinBenchmark.zioForkJoin              10000  thrpt   10  1931.372 ±  19.676  ops/s
 * [info] ForkJoinBenchmark.zioForkJoinNoFiberRoots  10000  thrpt   10  4812.394 ± 114.765  ops/s
 *
 * JDK 21 (Loom)
 * [info] Benchmark                                    (n)   Mode  Cnt     Score    Error  Units
 * [info] ForkJoinBenchmark.catsForkJoin             10000  thrpt   10  2913.651 ± 28.332  ops/s
 * [info] ForkJoinBenchmark.zioForkJoin              10000  thrpt   10   613.202 ±  5.053  ops/s
 * [info] ForkJoinBenchmark.zioForkJoinNoFiberRoots  10000  thrpt   10   764.049 ±  7.925  ops/s
 * }}}
 */
@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(16)
@Fork(1)
class ForkJoinBenchmark {
  import BenchmarkUtil.unsafeRun

  @Param(Array("10000"))
  var n: Int = _

  var range: List[Int] = _

  @Setup(Level.Trial)
  def setup(): Unit =
    range = (0 to n).toList

  private def zioEffect(forkFiber: UIO[Fiber.Runtime[Nothing, Unit]]) = {
    val forkAllFibers = ZIO.yieldNow *> ZIO.foreach(range)(_ => forkFiber)
    forkAllFibers.flatMap(fibers => ZIO.foreach(fibers)(_.await))
  }

  private val zioFork       = zioEffect(ZIO.unit.fork)
  private val zioForkDaemon = zioEffect(ZIO.unit.forkDaemon)

  @Benchmark
  def zioForkJoin(): Unit =
    unsafeRun(zioFork)

  @Benchmark
  def zioForkDaemonJoin(): Unit =
    unsafeRun(zioForkDaemon)

  @Benchmark
  def zioForkDaemonJoinNoFiberRoots(): Unit =
    unsafeRun(zioForkDaemon, fiberRootsEnabled = false)

  @Benchmark
  def catsForkJoin(): Unit = {
    import cats.effect._
    import BenchmarkUtil._

    val forkFiber     = IO.unit.start
    val forkAllFibers = catsForeach(range)(_ => forkFiber)

    val _ = forkAllFibers
      .flatMap(fibers => catsForeach(fibers)(_.join))
      .unsafeRunSync()
  }

}
