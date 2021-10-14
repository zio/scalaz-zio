package zio

import zio.test._

object FiberRefsSpec extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] = suite("FiberRefSpec")(
    test("propagate FiberRef values across fiber boundaries") {
      for {
        fiberRef <- FiberRef.make(false)
        queue    <- Queue.unbounded[FiberRefs]
        producer <- (fiberRef.set(true) *> ZIO.getFiberRefs.flatMap(queue.offer)).fork
        consumer <- (queue.take.flatMap(ZIO.setFiberRefs(_)) *> fiberRef.get).fork
        _        <- producer.join
        value    <- consumer.join
      } yield assertTrue(value)
    }
  )
}
