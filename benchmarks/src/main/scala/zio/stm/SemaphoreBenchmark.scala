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
class SemaphoreBenchmark {

  @Param(Array("1", "10"))
  var nSTM: Int = _

  @Param(Array("2", "10"))
  var fibers: Int = _

  val ops: Int = 1000

  @Benchmark
  def semaphoreContention(): Unit =
    unsafeRun(ZIO.foreachParDiscard(1 to nSTM) { _ =>
      for {
        sem   <- Semaphore.make(math.max(1, fibers / 2L))
        fiber <- ZIO.forkAll(List.fill(fibers)(repeat(ops)(sem.withPermit(ZIO.succeed(1)))))
        _     <- fiber.join
      } yield ()
    })

  @Benchmark
  def tsemaphoreContention(): Unit =
    unsafeRun(ZIO.foreachParDiscard(1 to nSTM) { _ =>
      for {
        sem   <- TSemaphore.make(math.max(1, fibers / 2L)).commit
        fiber <- ZIO.forkAll(List.fill(fibers)(repeat(ops)(sem.withPermit(ZIO.succeed(1)))))
        _     <- fiber.join
      } yield ()
    })

  @Benchmark
  def catsSemaphoreContention(): Unit = {
    import cats.effect.std.Semaphore
    import cats.effect.unsafe.implicits.global
    import cats.effect.{Concurrent, IO => CIO}

    (for {
      sem   <- Semaphore(fibers / 2L)(Concurrent[CIO])
      fiber <- catsForkAll(List.fill(fibers)(catsRepeat(ops)(sem.permit.use(_ => CIO(1)))))
      _     <- fiber.join
    } yield ()).unsafeRunSync()
  }
}
