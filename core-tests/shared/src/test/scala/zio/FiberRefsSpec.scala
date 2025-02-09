package zio

import zio.test.TestAspect.exceptScala212
import zio.test._

object FiberRefsSpec extends ZIOBaseSpec {
  private implicit val unsafe: Unsafe = Unsafe.unsafe

  private val id1 = FiberId.Runtime(0, 0, Trace.empty)
  private val id2 = FiberId.Runtime(0, 1, Trace.empty)
  private val id3 = FiberId.Runtime(0, 2, Trace.empty)

  def spec = suite("FiberRefSpec")(
    test("propagate FiberRef values across fiber boundaries") {
      for {
        fiberRef <- FiberRef.make(false)
        queue    <- Queue.unbounded[FiberRefs]
        producer <- (fiberRef.set(true) *> ZIO.getFiberRefs.flatMap(queue.offer)).fork
        consumer <- (queue.take.flatMap(ZIO.setFiberRefs(_)) *> fiberRef.get).fork
        _        <- producer.join
        value    <- consumer.join
      } yield assertTrue(value)
    } +
      test("interruptedCause") {
        val parent = Unsafe.unsafe(implicit unsafe => FiberId.Gen.Live.make(Trace.empty))
        val child  = Unsafe.unsafe(implicit unsafe => FiberId.Gen.Live.make(Trace.empty))

        val parentFiberRefs = FiberRefs.empty
        val childFiberRefs  = parentFiberRefs.updatedAs(child)(FiberRef.interruptedCause, Cause.interrupt(parent))

        val newParentFiberRefs = parentFiberRefs.joinAs(parent)(childFiberRefs)

        assertTrue(newParentFiberRefs.getOrDefault(FiberRef.interruptedCause) == Cause.empty)
      } +
      /*
        If any of the following tests fail, it is likely that the optimizations in FiberRefs are broken.
        Either ensure that the optimizations are no longer needed (and delete the tests) or fix the issue that
        is causing the underlying maps to return a different instance of `FiberRefs`
       */
      suite("deep joinAs")(
        test("object") {
          val ref = FiberRef.unsafe.make[String]("0")
          val cf  = FiberRefs.empty.updatedAs(id3)(ref, "1")
          val cf1 = FiberRefs.empty.joinAs(id2)(cf)
          val cf2 = FiberRefs.empty.joinAs(id1)(cf1)
          assertTrue(cf2.getOrDefault(ref) == "1")
        },
        test("runtimeFlags") {
          val ref = FiberRef.currentRuntimeFlags
          val cf  = FiberRefs.empty.updatedAs(id3)(ref, 1)
          val cf1 = FiberRefs.empty.joinAs(id2)(cf)
          val cf2 = FiberRefs.empty.joinAs(id1)(cf1)
          assertTrue(cf2.getOrDefault(ref) == 1)
        },
        test("custom join with modified value") {
          val ref = FiberRef.unsafe.make[String]("0", join = (a, b) => a + b)
          val fr1 = FiberRefs.empty.updatedAs(id1)(ref, "1")
          val fr2 = fr1.forkAs(id2)
          val cf  = fr2.forkAs(id3)
          val cf1 = fr2.joinAs(id2)(cf)
          val cf2 = fr1.joinAs(id1)(cf1)
          assertTrue(cf2.getOrDefault(ref) == "111")
        }
      ) +
      suite("optimizations") {
        val fiberId = FiberId.Gen.Live.make(implicitly)

        val fiberRefs = List(
          FiberRef.unsafe.make[Int](0, join = (a, b) => a + b),
          FiberRef.unsafe.make(1),
          FiberRef.unsafe.make(3),
          FiberRef.unsafe.make(4),
          FiberRef.unsafe.make[Int](5, fork = _ + 1)
        )

        def makeFiberRefs(f: List[FiberRef[Int]]) =
          f.foldLeft(FiberRefs.empty)((refs, ref) => refs.updatedAs(fiberId)(ref, 5))

        test("`delete` returns the same instance if the map was unchanged") {
          val fr   = makeFiberRefs(fiberRefs)
          val isEq = fr.delete(FiberRef.unsafe.make(6)) eq fr
          assertTrue(isEq)
        } +
          test("forkAs returns the same map if no fibers are modified during fork") {
            val fr   = makeFiberRefs(fiberRefs.take(4))
            val isEq = fr.forkAs(FiberId.Gen.Live.make(implicitly)) eq fr
            assertTrue(isEq)
          } @@ exceptScala212 +
          test("joinAs returns the same map when fiber refs are unchanged after joining") {
            val fr1  = makeFiberRefs(fiberRefs.drop(1))
            val fr2  = makeFiberRefs(fiberRefs.drop(2))
            val isEq = fr1.joinAs(FiberId.Gen.Live.make(implicitly))(fr2) eq fr1
            assertTrue(isEq)
          } +
          // Sanity checks
          test("forkAs returns a different map if forked fibers are modified") {
            val fr   = makeFiberRefs(fiberRefs)
            val isEq = fr.forkAs(FiberId.Gen.Live.make(implicitly)) ne fr
            assertTrue(isEq)
          } +
          test("joinAs returns a different map when fiber refs are changed after joining") {
            val fr1, fr2 = makeFiberRefs(fiberRefs)
            val isEq     = fr1.joinAs(FiberId.Gen.Live.make(implicitly))(fr2) ne fr1
            assertTrue(isEq)
          }
      }
  )
}
