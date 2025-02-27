package zio

import org.openjdk.jmh.annotations.{Scope => JScope, _}

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@Fork(1)
class ScopeBenchmark {
  import BenchmarkUtil.unsafeRun

  @Param(Array("0", "1", "5", "20", "100", "1000"))
  var nFinalizers: Int = _

  def makeScope =
    Scope.unsafe.make(Unsafe)

  @Benchmark
  def closeBenchmark(): Unit = {
    val scope = makeScope
    val f = nFinalizers match {
      case 0 => scope.close(Exit.unit)
      case 1 => scope.addFinalizer(ZIO.unit) *> scope.close(Exit.unit)
      case n => scope.addFinalizer(ZIO.unit).repeatN(n - 1) *> scope.close(Exit.unit)
    }
    unsafeRun(f)
  }

}
