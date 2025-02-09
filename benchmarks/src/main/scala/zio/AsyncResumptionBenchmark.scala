package zio

import cats.effect.unsafe.implicits.global
import cats.effect.{IO => CIO}
import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 1)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 1)
@Fork(1)
class AsyncResumptionBenchmark {

  final val size = 1000

  val ints: List[Int] = List.range(0, size)

  @Benchmark
  @OperationsPerInvocation(size)
  def zioAsyncResumptionBenchmark(): Unit = {

    val io = ZIO.foreachDiscard(ints) { _ =>
      ZIO.succeed("foo") *> ZIO.yieldNow *> ZIO.succeed("bar")
    }

    unsafeRun(io)
  }

  @Benchmark
  @OperationsPerInvocation(size)
  def catsAsyncResumptionBenchmark(): Unit = {

    val io = catsForeachDiscard(ints) { _ =>
      CIO("foo") *> CIO.cede *> CIO("bar")
    }

    io.unsafeRunSync()
  }

}
