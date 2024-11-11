package zio

import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Param, State, Scope => JScope}

import java.util.concurrent.TimeUnit
import scala.concurrent.TimeoutException

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class TimeoutBenchmark {
  import BenchmarkUtil.unsafeRun

  @Param(Array("0", "100", "10000"))
  var n: Int = _

  val effect = ZIO.foldLeft(0 until n)(0) { case (prev, x) =>
    ZIO.succeed(prev + x)
  }

  @Benchmark
  def zioBaseline = {
    val _ = unsafeRun {
      effect
    }
  }

  @Benchmark
  def zioTimeoutAlt = {
    val _ = unsafeRun {
      effect
        .timeoutTo(ZIO.fail(new TimeoutException))
        .apply(ZIO.succeed(_))(100.minutes)
        .flatten
    }
  }

  @Benchmark
  def zioTimeoutOrig = {
    val _ = unsafeRun {
      effect
        .timeoutTo(ZIO.fail(new TimeoutException))
        .applyOrig(ZIO.succeed(_))(100.minutes)
        .flatten
    }
  }

}
