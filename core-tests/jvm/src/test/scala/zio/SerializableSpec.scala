package zio

import zio.SerializableSpecHelpers._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.{test => testSync, _}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

object SerializableSpec extends ZIOBaseSpec {

  def spec = suite("SerializableSpec")(
    test("Semaphore is serializable") {
      val n = 20L
      for {
        semaphore   <- Semaphore.make(n)
        count       <- semaphore.available
        returnSem   <- serializeAndBack(semaphore)
        returnCount <- returnSem.available
      } yield assert(returnCount)(equalTo(count))
    },
    test("ClockLive is serializable") {
      Live.live(ZIO.clock).flatMap(clockTest)
    },
    test("ClockJava is serializable") {
      ZIO.succeed(Clock.ClockJava(java.time.Clock.systemUTC())).flatMap(clockTest)
    },
    test("System is serializable") {
      for {
        system    <- Live.live(ZIO.system)
        props1    <- system.properties
        returnSem <- serializeAndBack(system)
        props2    <- returnSem.properties
      } yield assert(props1)(equalTo(props2))
    },
    test("Queue is serializable") {
      for {
        queue       <- Queue.bounded[Int](100)
        _           <- queue.offer(10)
        returnQueue <- serializeAndBack(queue)
        v1          <- returnQueue.take
        _           <- returnQueue.offer(20)
        v2          <- returnQueue.take
      } yield assert(v1)(equalTo(10)) &&
        assert(v2)(equalTo(20))
    },
    test("Hub is serializable") {
      for {
        hub                     <- Hub.bounded[Int](100)
        queue                   <- hub.subscribe
        _                       <- hub.publish(10)
        tuple                   <- serializeAndBack((hub, queue))
        (returnHub, returnQueue) = tuple
        v1                      <- returnQueue.take
        _                       <- returnHub.publish(20)
        v2                      <- returnQueue.take
      } yield assert(v1)(equalTo(10)) &&
        assert(v2)(equalTo(20))
    },
    test("Ref is serializable") {
      val current = "This is some value"
      for {
        ref       <- Ref.make(current)
        returnRef <- serializeAndBack(ref)
        value     <- returnRef.get
      } yield assert(value)(equalTo(current))
    },
    test("IO is serializable") {
      val list = List("1", "2", "3")
      val io   = ZIO.succeed(list)
      for {
        returnIO <- serializeAndBack(io)
        result   <- returnIO
      } yield assert(result)(equalTo(list))
    },
    test("ZIO is serializable") {
      val v = ZIO.service[Int].map(_ + 1).provideEnvironment(ZEnvironment(9))
      for {
        returnZIO <- serializeAndBack(v)
        computeV  <- returnZIO
      } yield assert(computeV)(equalTo(10))
    } @@ exceptScala212,
    test("ZEnvironment is serializable") {
      val env = ZEnvironment(10)
      for {
        returnedEnv <- serializeAndBack(env)
        computeV     = returnedEnv.get[Int]
      } yield assert(computeV)(equalTo(10))
    } @@ exceptScala212,
    test("FiberStatus is serializable") {
      val list = List("1", "2", "3")
      val io   = ZIO.succeed(list)
      for {
        fiber          <- io.fork
        status         <- fiber.await
        returnedStatus <- serializeAndBack(status)
      } yield {
        assert(returnedStatus.getOrElse(_ => List.empty))(equalTo(list))
      }
    },
    test("Duration is serializable") {
      val duration = Duration.fromNanos(1)
      for {
        returnDuration <- serializeAndBack(duration)
      } yield assert(returnDuration)(equalTo(duration))
    },
    testSync("Cause.die is serializable") {
      val cause = Cause.die(TestException("test"))
      assert(serializeAndDeserialize(cause))(equalTo(cause))
    },
    testSync("Cause.fail is serializable") {
      val cause = Cause.fail("test")
      assert(serializeAndDeserialize(cause))(equalTo(cause))
    },
    testSync("Cause.&& is serializable") {
      val cause = Cause.fail("test") && Cause.fail("Another test")
      assert(serializeAndDeserialize(cause))(equalTo(cause))
    },
    testSync("Cause.++ is serializable") {
      val cause = Cause.fail("test") ++ Cause.fail("Another test")
      assert(serializeAndDeserialize(cause))(equalTo(cause))
    },
    testSync("Exit.succeed is serializable") {
      val exit = Exit.succeed("test")
      assert(serializeAndDeserialize(exit))(equalTo(exit))
    },
    testSync("Exit.fail is serializable") {
      val exit = Exit.fail("test")
      assert(serializeAndDeserialize(exit))(equalTo(exit))
    },
    testSync("Exit.die is serializable") {
      val exit = Exit.die(TestException("test"))
      assert(serializeAndDeserialize(exit))(equalTo(exit))
    },
    testSync("FiberFailure is serializable") {
      val failure = FiberFailure(Cause.fail("Uh oh"))
      assert(serializeAndDeserialize(failure))(equalTo(failure))
    },
    testSync("InterruptStatus.interruptible is serializable") {
      val interruptStatus = InterruptStatus.interruptible
      assert(serializeAndDeserialize(interruptStatus))(equalTo(interruptStatus))
    },
    testSync("InterruptStatus.uninterruptible is serializable") {
      val interruptStatus = InterruptStatus.uninterruptible
      assert(serializeAndDeserialize(interruptStatus))(equalTo(interruptStatus))
    },
    test("Promise is serializable") {
      for {
        promise           <- Promise.make[Nothing, String]
        _                 <- promise.succeed("test")
        value             <- promise.await
        deserialized      <- serializeAndBack(promise)
        deserializedValue <- deserialized.await
        _                 <- deserialized.poll
      } yield assert(deserializedValue)(equalTo(value))
    },
    test("Schedule is serializable") {
      val schedule = Schedule.recurs(5)
      for {
        out1 <- ZIO.unit.repeat(schedule)
        out2 <- ZIO.unit.repeat(serializeAndDeserialize(schedule))
      } yield assert(out2)(equalTo(out1))
    },
    test("Chunk.single is serializable") {
      val chunk = Chunk.single(1)
      for {
        deserialized <- serializeAndBack(chunk)
      } yield assert(deserialized)(equalTo(chunk))
    },
    test("Chunk.fromArray is serializable") {
      val chunk = Chunk.fromArray(Array(1, 2, 3))
      for {
        deserialized <- serializeAndBack(chunk)
      } yield assert(deserialized)(equalTo(chunk))
    },
    test("Chunk.++ is serializable") {
      val chunk = Chunk.single(1) ++ Chunk.single(2)
      for {
        deserialized <- serializeAndBack(chunk)
      } yield assert(deserialized)(equalTo(chunk))
    },
    test("Chunk slice is serializable") {
      val chunk = Chunk.fromArray((1 to 100).toArray).take(10)
      for {
        deserialized <- serializeAndBack(chunk)
      } yield assert(deserialized)(equalTo(chunk))
    },
    test("Chunk.empty is serializable") {
      val chunk = Chunk.empty
      for {
        deserialized <- serializeAndBack(chunk)
      } yield assert(deserialized)(equalTo(chunk))
    },
    test("Chunk.fromIterable is serializable") {
      val chunk = Chunk.fromIterable(Vector(1, 2, 3))
      for {
        deserialized <- serializeAndBack(chunk)
      } yield assert(deserialized)(equalTo(chunk))
    },
    test("FiberRef is serializable") {
      val value = 10

      for {
        init   <- FiberRef.make(value)
        ref    <- serializeAndBack(init)
        result <- ref.get
      } yield assert(result)(equalTo(value))
    },
    test("Chunk is serializable") {
      val chunk =
        Chunk(1, 2, 3, 4, 5).take(4) ++ Chunk(9, 92, 2, 3) ++ Chunk.fromIterable(List(1, 2, 3))

      for {
        chunk  <- ZIO.succeed(chunk)
        result <- serializeAndBack(chunk)
      } yield assertTrue(chunk == result)
    },
    test("NonEmptyChunk is serializable") {
      val nonEmptyChunk = NonEmptyChunk(1, 2, 3)
      for {
        nonEmptyChunk <- ZIO.succeed(nonEmptyChunk)
        result        <- serializeAndBack(nonEmptyChunk)
      } yield assertTrue(nonEmptyChunk == result)
    }
  )

  def clockTest(clock: Clock) =
    for {
      time1       <- clock.nanoTime
      returnClock <- serializeAndBack(clock)
      time2       <- returnClock.nanoTime
    } yield assert(time1)(isLessThanEqualTo(time2))
}

object SerializableSpecHelpers {
  def serializeAndBack[T](a: T): IO[Any, T] =
    for {
      obj       <- ZIO.succeed(serializeToBytes(a))
      returnObj <- ZIO.succeed(getObjFromBytes[T](obj))
    } yield returnObj

  def serializeToBytes[T](a: T): Array[Byte] = {
    val bf  = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bf)
    oos.writeObject(a)
    oos.close()
    bf.toByteArray
  }

  def getObjFromBytes[T](bytes: Array[Byte]): T = {
    val ios = new ObjectInputStream(new ByteArrayInputStream(bytes))
    ios.readObject().asInstanceOf[T]
  }

  def serializeAndDeserialize[T](a: T): T = getObjFromBytes(serializeToBytes(a))

  case class TestException(msg: String) extends RuntimeException(msg)

}
