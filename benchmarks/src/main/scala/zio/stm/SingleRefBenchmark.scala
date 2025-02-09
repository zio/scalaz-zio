package zio.stm

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._
import zio._

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 1)
@Measurement(iterations = 4, timeUnit = TimeUnit.SECONDS, time = 1)
@Fork(4)
class SingleRefBenchmark {

  @Param(Array("1", "10"))
  var nSTM: Int = _

  @Param(Array("2", "10"))
  var fibers: Int = _

  val ops: Int = 1000

  @Benchmark
  def refContention(): Unit =
    unsafeRun(ZIO.foreachParDiscard(1 to nSTM) { _ =>
      for {
        ref   <- Ref.make(0)
        fiber <- ZIO.forkAll(List.fill(fibers)(repeat(ops)(ref.update(_ + 1))))
        _     <- fiber.join
      } yield ()
    })

  @Benchmark
  def trefContention(): Unit =
    unsafeRun(ZIO.foreachParDiscard(1 to nSTM) { _ =>
      for {
        tref  <- TRef.make(0).commit
        fiber <- ZIO.forkAll(List.fill(fibers)(repeat(ops)(tref.update(_ + 1).commit)))
        _     <- fiber.join
      } yield ()
    })

}
