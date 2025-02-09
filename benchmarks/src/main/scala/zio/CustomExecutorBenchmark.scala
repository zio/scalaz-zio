package zio

import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._

import java.util.concurrent.TimeUnit
import scala.concurrent.Await

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(16)
@Fork(1)
class CustomExecutorBenchmark {
  @Param(Array("20"))
  var depth: Int = _

  lazy val defaultRuntime: Runtime[Any] =
    Runtime.default

  lazy val customRuntime = {
    implicit val unsafe: Unsafe = Unsafe.unsafe(identity)
    val kExecutorLayer =
      Runtime.setExecutor(Runtime.defaultExecutor) ++
        Runtime.setBlockingExecutor(Runtime.defaultBlockingExecutor)
    Runtime.unsafe.fromLayer(kExecutorLayer)
  }

  @Benchmark
  def defaultExecutor(): BigInt = zioFib(defaultRuntime)

  @Benchmark
  def customExecutor(): BigInt = zioFib(customRuntime)

  @Benchmark
  def defaultExecutorInitBoth(): BigInt = {
    customRuntime
    zioFib(defaultRuntime)
  }

  @Benchmark
  def customExecutorInitBoth(): BigInt = {
    defaultRuntime
    zioFib(customRuntime)
  }

  private[this] def zioFib(runtime: Runtime[Any]): BigInt = {
    def fib(n: Int): UIO[BigInt] =
      if (n <= 1) ZIO.succeed[BigInt](n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => ZIO.succeed(a + b)))

    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(ZIO.yieldNow.flatMap(_ => fib(depth))).getOrThrowFiberFailure()
    }
  }
}
