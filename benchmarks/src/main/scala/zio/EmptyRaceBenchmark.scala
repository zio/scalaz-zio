package zio

import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Scope => JScope, _}

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
class EmptyRaceBenchmark {
  @Param(Array("1000"))
  var size: Int = _

  @Benchmark
  def catsEmptyRace(): Int = {
    import cats.effect.IO

    def loop(i: Int): IO[Int] =
      if (i < size) IO.race(IO.never, IO.delay(i + 1)).flatMap(_ => loop(i + 1))
      else IO.pure(i)

    loop(0).unsafeRunSync()
  }

  @Benchmark
  def zioEmptyRace(): Int = zioEmptyRace(BenchmarkUtil)

  private[this] def zioEmptyRace(runtime: Runtime[Any]): Int = {
    def loop(i: Int): UIO[Int] =
      if (i < size) ZIO.never.raceFirst(ZIO.succeed(i + 1)).flatMap(loop)
      else ZIO.succeed(i)

    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(loop(0)).getOrThrowFiberFailure()
    }
  }
}
