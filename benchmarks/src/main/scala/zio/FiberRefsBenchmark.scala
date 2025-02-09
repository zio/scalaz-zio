package zio

import org.openjdk.jmh.annotations.{Scope => JScope, _}

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
class FiberRefsBenchmark {
  import BenchmarkUtil._
  implicit val us: Unsafe = Unsafe.unsafe

  def commonList = List.fill(10)("foo") :+ scala.util.Random.alphanumeric.take(10).mkString
  val ref        = FiberRef.unsafe.make(commonList)
  val ref2       = FiberRef.unsafe.make("foo")

  val id1 = FiberId.Runtime(0, 0, Trace.empty)
  val id2 = FiberId.Runtime(0, 1, Trace.empty)
  val id3 = FiberId.Runtime(0, 2, Trace.empty)
  val id4 = FiberId.Runtime(0, 3, Trace.empty)
  val id5 = FiberId.Runtime(0, 4, Trace.empty)

  val f1  = FiberRefs.empty.updatedAs(id1)(ref, commonList).updatedAs(id1)(ref2, "bar")
  val f2n = f1.forkAs(id2)
  val f2  = f2n.updatedAs(id2)(ref, commonList)

  val list = commonList

  @Benchmark
  def updateRef() =
    f1.updatedAs(id1)(ref, list)

  @Benchmark
  def joinRef() =
    f1.joinAs(id1)(f2n)

  @Benchmark
  def joinUpdatedRef() =
    f1.joinAs(id1)(f2)

  @Benchmark
  def forkRef() =
    // To prevent optimizations in forkAs we recreate the FiberRefs
    // This benchmark is mostly for optimizing the forkAs method after the FiberRefs are modified
    FiberRefs(f1.fiberRefLocals).forkAs(id2)

  // Checking performance on a chain of joins. This can happen when there are levels of forking from the parent fiber
  val frs1 = FiberRefs.empty.updatedAs(id1)(ref2, "bar").updatedAs(id1)(ref, commonList)
  val frs2 = frs1.forkAs(id2)
  val frs3 = frs2.forkAs(id3)
  val frs4 = frs3.forkAs(id4)
  val frs5 = frs4.forkAs(id5)

  @Benchmark
  def joinChain() = {
    val f05 = frs4.joinAs(id4)(frs5)
    val f04 = frs3.joinAs(id3)(f05)
    val f03 = frs2.joinAs(id2)(f04)
    val f02 = frs1.joinAs(id1)(f03)
    frs1.joinAs(id1)(f02)
  }

  private val ref0          = FiberRef.unsafe.make("foo")
  private val updateLocally = List.fill(10000)(ref0.locally("bar")(ZIO.unit))

  @Benchmark
  @OperationsPerInvocation(10000)
  def locally() = unsafeRun {
    ZIO.setFiberRefs(frs5) *>
      ZIO.collectAllDiscard(updateLocally)
  }

}
