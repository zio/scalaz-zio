package zio.test.mock

import zio.stream.{ZSink, ZStream}
import zio.test.mock.module.{StreamModule, StreamModuleMock}
import zio.test.{Annotations, Assertion, TestAspect, ZIOBaseSpec, ZSpec}
import zio.{Chunk, Has}

object BasicStreamMockSpec extends ZIOBaseSpec with MockSpecUtils[StreamModule] {

  import Assertion._
  import Expectation._
  import TestAspect._

  val A: ZStream[Any, Nothing, Int] = ZStream.fromIterable(List(1, 2, 3))

  def spec: ZSpec[Has[Annotations], Any] =
    suite("BasicStreamMockSpec")(
      suite("capabilities")(
        suite("sink")(
          testValue("success")(
            StreamModuleMock.Sink(equalTo(1), value(ZSink.collectAll.map(_.toList))),
            StreamModule.sink(1).flatMap(A.run(_)),
            equalTo(List(1, 2, 3))
          ),
          testError("failure")(
            StreamModuleMock.Sink(equalTo(1), failure("foo")),
            StreamModule.sink(1).flatMap(A.run(_)),
            equalTo("foo")
          )
        ),
        suite("stream")(
          testValue("success")(
            StreamModuleMock.Stream(equalTo(1), value(A)),
            StreamModule.stream(1).flatMap(_.runCollect),
            equalTo(Chunk(1, 2, 3))
          )
        )
      )
    ) @@ exceptJS
}
