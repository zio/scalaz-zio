package zio.test.mock

import zio._
import zio.test.mock.internal.MockException
import zio.test.{Assertion, ZIOBaseSpec, ZSpec}

import java.io.IOException

object EmptyMockSpec extends ZIOBaseSpec with MockSpecUtils[Has[Console]] {

  import Assertion._
  import MockException._

  def spec: ZSpec[Environment, Failure] = suite("EmptyMockSpec")(
    suite("expect no calls on empty mocks")(
      testValue("should succeed when no call")(
        MockConsole.empty,
        ZIO.when(false)(Console.printLine("foo")),
        isUnit
      ), {

        type M = Capability[Has[Console], Any, IOException, Unit]
        type X = UnexpectedCallException[Has[Console], Any, IOException, Unit]

        testDied("should fail when call happened")(
          MockConsole.empty,
          ZIO.when(true)(Console.printLine("foo")),
          isSubtype[X](
            hasField[X, M]("capability", _.capability, equalTo(MockConsole.PrintLine)) &&
              hasField[X, Any]("args", _.args, equalTo("foo"))
          )
        )
      }
    )
  )
}
