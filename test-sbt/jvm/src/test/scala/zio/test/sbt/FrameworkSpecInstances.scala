package zio.test.sbt

import zio.{UIO, ZIO, ZLayer, durationInt}
import zio.test._

import java.net.BindException
import java.util.concurrent.atomic.AtomicInteger

object FrameworkSpecInstances {

  val dummyHandler: ZTestEventHandler = new ZTestEventHandler {
    override def handle(event: ExecutionEvent): UIO[Unit] = ZIO.unit
  }

  val counter = new AtomicInteger(0)

  lazy val sharedLayer: ZLayer[Any, Nothing, Int] =
    ZLayer.fromZIO(ZIO.succeed(counter.getAndUpdate(value => value + 1)))

  def numberedTest(specIdx: Int, suiteIdx: Int, testIdx: Int) =
    zio.test.test(s"spec $specIdx suite $suiteIdx test $testIdx") {
      assertCompletes
    }

  object SimpleSpec extends zio.test.ZIOSpec[Int] {
    override def bootstrap = ZLayer.succeed(1)

    def spec =
      suite("simple suite")(
        numberedTest(specIdx = 1, suiteIdx = 1, 1)
      ) @@ TestAspect.parallel
  }

  object CombinedWithPlusSpec extends ZIOSpecDefault {
    override def spec = suite("spec A") {
      test("successful test") {
        assertTrue(true)
      } +
        test("failing test") {
          assertTrue(false)
        } @@ TestAspect.ignore
    }
  }

  object CombinedWithCommasSpec extends ZIOSpecDefault {
    override def spec = suite("spec A")(
      test("successful test") {
        assertTrue(true)
      },
      test("failing test") {
        assertTrue(false)
      } @@ TestAspect.ignore
    )
  }

  object TimeOutSpec extends zio.test.ZIOSpecDefault {

    def spec =
      suite("simple suite")(
        test("slow test")(
          for {
            _ <- ZIO.sleep(3.seconds)
          } yield assertCompletes
        )
      ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(1.second)
  }

  object RuntimeExceptionSpec extends zio.test.ZIOSpecDefault {

    def spec =
      suite("exploding suite")(
        test("boom") {
          if (true) throw new RuntimeException("Good luck ;)") else ()
          assertCompletes
        }
      )
  }

  object RuntimeExceptionDuringLayerConstructionSpec extends zio.test.ZIOSpec[Int] {
    override val bootstrap = ZLayer.fromZIO(
      ZIO.attempt(throw new BindException("Other Kafka container already grabbed your port"))
    )

    def spec =
      suite("kafka suite")(
        test("does stuff with a live kafka cluster") {
          assertCompletes
        }
      )
  }

  object Spec1UsingSharedLayer extends zio.test.ZIOSpec[Int] {
    override def bootstrap = sharedLayer

    def spec =
      suite("suite with shared layer")(
        numberedTest(specIdx = 1, suiteIdx = 1, 1),
        numberedTest(specIdx = 1, suiteIdx = 1, 2),
        numberedTest(specIdx = 1, suiteIdx = 1, 3),
        numberedTest(specIdx = 1, suiteIdx = 1, 4)
      ) @@ TestAspect.parallel
  }

  object Spec2UsingSharedLayer extends zio.test.ZIOSpec[Int] {
    override def bootstrap = sharedLayer

    def spec =
      zio.test.test("test completes with shared layer 2") {
        assertCompletes
      }
  }

  object MultiLineSharedSpec extends ZIOSpecDefault {
    def spec = test("multi-line test") {
      zio.test.assert("Hello,\nWorld!")(Assertion.equalTo("Hello, World!"))
    }
  }

  lazy val failingSpecFQN = SimpleFailingSharedSpec.getClass.getName

  object SimpleFailingSharedSpec extends ZIOSpecDefault {
    def spec: Spec[Any, TestFailure[Any]] = zio.test.suite("some suite")(
      test("failing test") {
        zio.test.assert(1)(Assertion.equalTo(2))
      },
      test("passing test") {
        zio.test.assert(1)(Assertion.equalTo(1))
      },
      test("ignored test") {
        zio.test.assert(1)(Assertion.equalTo(2))
      } @@ TestAspect.ignore
    )
  }

  object TagsSpec extends ZIOSpecDefault {
    def spec: Spec[Any, TestFailure[Any]] = zio.test.suite("tag suite")(
      test("integration test") {
        zio.test.assertCompletes
      }.annotate(TestAnnotation.tagged, Set("IntegrationTest")),
      test("unit test") {
        zio.test.assert(1)(Assertion.equalTo(1))
      }.annotate(TestAnnotation.tagged, Set("UnitTest"))
    )
  }

}
