package zio

import org.openjdk.jmh.annotations.{
  Benchmark,
  BenchmarkMode,
  Fork,
  Measurement,
  Mode,
  OutputTimeUnit,
  Param,
  Scope,
  State,
  Threads,
  Warmup
}
import zio.BenchmarkUtil.verify

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
class FiberRefBenchmarks {
  @Param(Array("32"))
  var n: Int = _

  @Benchmark
  def tracedCreateUpdateAndRead(): Unit =
    createUpdateAndRead(BenchmarkUtil.TracedRuntime)

  @Benchmark
  def unTracedCreateUpdateAndRead(): Unit =
    createUpdateAndRead(BenchmarkUtil)

  @Benchmark
  def unTracedJustYield(): Unit =
    justYield(BenchmarkUtil)

  @Benchmark
  def unTracedCreateFiberRefsAndYield(): Unit =
    createFiberRefsAndYield(BenchmarkUtil)

  private def justYield(runtime: Runtime[Any]) = runtime.unsafeRun {
    for {
      _ <- ZIO.foreachDiscard(1.to(n))(_ => ZIO.yieldNow)
    } yield ()
  }

  private def createFiberRefsAndYield(runtime: Runtime[Any]) = runtime.unsafeRun {
    for {
      fiberRefs <- ZIO.foreach(1.to(n))(i => FiberRef.make(i))
      _         <- ZIO.foreachDiscard(1.to(n))(_ => ZIO.yieldNow)
      values    <- ZIO.foreachPar(fiberRefs)(_.get)
      _         <- verify(values == 1.to(n))(s"Got $values")
    } yield ()
  }

  private def createUpdateAndRead(runtime: Runtime[Any]) = runtime.unsafeRun {
    for {
      fiberRefs <- ZIO.foreach(1.to(n))(i => FiberRef.make(i))
      values1   <- ZIO.foreachPar(fiberRefs)(ref => ref.update(-_) *> ref.get)
      values2   <- ZIO.foreachPar(fiberRefs)(_.get)
      _ <- verify(values1.forall(_ < 0) && values1.size == values2.size)(
             s"Got \nvalues1: $values1, \nvalues2: $values2"
           )
    } yield ()
  }
}
