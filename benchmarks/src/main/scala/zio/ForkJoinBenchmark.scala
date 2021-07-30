package zio

import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._

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
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ForkJoinBenchmark {
  import BenchmarkUtil.unsafeRun

  @Param(Array("10000"))
  var n: Int = _

  var range: List[Int] = _

  @Setup(Level.Trial)
  def setup(): Unit =
    range = (0 to n).toList

  @Benchmark
  def zioForkJoin(): Unit = {
    val forkFiber     = ZIO.unit.forkDaemon
    val forkAllFibers = ZIO.foreach(range)(_ => forkFiber)

    val _ = unsafeRun(
      forkAllFibers.flatMap(fibers =>
        fibers.foldLeft[UIO[Any]](ZIO.unit) { case (acc, fiber) =>
          acc.flatMap(_ => fiber.await)
        }
      )
    )
  }

  @Benchmark
  def catsForkJoin(): Unit = {
    import cats.effect._
    import BenchmarkUtil._

    val forkFiber     = IO.unit.start
    val forkAllFibers = catsForeach(range)(_ => forkFiber)

    val _ = forkAllFibers
      .flatMap(fibers =>
        fibers.foldLeft[IO[Any]](IO.unit) { case (acc, fiber) =>
          acc.flatMap(_ => fiber.join)
        }
      )
      .unsafeRunSync()
  }

}
