package zio.internal

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._
import zio._

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Threads(12)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
class ForeachParDiscardBenchmark {

  private val as = 1 to 10000

  @Benchmark
  def foreachParDiscard(): Unit =
    unsafeRun(ZIO.yieldNow *> ZIO.foreachParDiscard(as)(_ => ZIO.unit))

  @Benchmark
  def foreachForkJoinDiscard(): Unit =
    unsafeRun(ZIO.yieldNow *> ZIO.foreach(as)(_ => ZIO.unit.forkDaemon).flatMap(ZIO.foreach(_)(_.join)))

  @Benchmark
  def naiveForeachParDiscard(): Unit =
    unsafeRun(ZIO.yieldNow *> naiveForeachParDiscard(as)(_ => ZIO.unit))

  private def naiveForeachParDiscard[R, E, A](
    as: Iterable[A]
  )(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    as.foldLeft(ZIO.unit: ZIO[R, E, Unit])((acc, a) => acc.zipParLeft(f(a)))
}
