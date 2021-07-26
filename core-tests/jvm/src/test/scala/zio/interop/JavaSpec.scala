package zio
package interop

import zio.interop.javaz._
import zio.test.Assertion._
import zio.test._

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousSocketChannel}
import java.util.concurrent.{CompletableFuture, CompletionStage, Future}

object JavaSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec: Spec[Any, TestFailure[Any], TestSuccess] = suite("JavaSpec")(
    suite("`Task.fromFutureJava` must")(
      test("be lazy on the `Future` parameter") {
        var evaluated         = false
        def ftr: Future[Unit] = CompletableFuture.supplyAsync(() => evaluated = true)
        assertM(ZIO.fromFutureJava(ftr).when(false).as(evaluated))(isFalse)
      },
      test("execute the `Future` parameter only once") {
        var count            = 0
        def ftr: Future[Int] = CompletableFuture.supplyAsync { () => count += 1; count }
        assertM(ZIO.fromFutureJava(ftr).exit)(succeeds(equalTo(1)))
      },
      test("catch exceptions thrown by lazy block") {
        val ex                          = new Exception("no future for you!")
        lazy val noFuture: Future[Unit] = throw ex
        assertM(ZIO.fromFutureJava(noFuture).exit)(fails(equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (failedFuture)") {
        val ex                         = new Exception("no value for you!")
        lazy val noValue: Future[Unit] = CompletableFuture_.failedFuture(ex)
        assertM(ZIO.fromFutureJava(noValue).exit)(fails(equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (supplyAsync)") {
        val ex                         = new Exception("no value for you!")
        lazy val noValue: Future[Unit] = CompletableFuture.supplyAsync(() => throw ex)
        assertM(ZIO.fromFutureJava(noValue).exit)(fails(equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that produces the value from `Future`") {
        lazy val someValue: Future[Int] = CompletableFuture.completedFuture(42)
        assertM(ZIO.fromFutureJava(someValue))(equalTo(42))
      },
      test("handle null produced by the completed `Future`") {
        lazy val someValue: Future[String] = CompletableFuture.completedFuture[String](null)
        assertM(ZIO.fromFutureJava(someValue).map(Option(_)))(isNone)
      } @@ zioTag(errors),
      test("be referentially transparent") {
        var n    = 0
        val task = ZIO.fromFutureJava(CompletableFuture.supplyAsync(() => n += 1))
        for {
          _ <- task
          _ <- task
        } yield assert(n)(equalTo(2))
      }
    ) @@ zioTag(future),
    suite("`Task.fromCompletionStage` must")(
      test("be lazy on the `Future` parameter") {
        var evaluated                 = false
        def cs: CompletionStage[Unit] = CompletableFuture.supplyAsync(() => evaluated = true)
        assertM(ZIO.fromCompletionStage(cs).when(false).as(evaluated))(isFalse)
      },
      test("catch exceptions thrown by lazy block") {
        val ex                                   = new Exception("no future for you!")
        lazy val noFuture: CompletionStage[Unit] = throw ex
        assertM(ZIO.fromCompletionStage(noFuture).exit)(fails(equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (failedFuture)") {
        val ex                                  = new Exception("no value for you!")
        lazy val noValue: CompletionStage[Unit] = CompletableFuture_.failedFuture(ex)
        assertM(ZIO.fromCompletionStage(noValue).exit)(fails[Throwable](equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (supplyAsync)") {
        val ex                                  = new Exception("no value for you!")
        lazy val noValue: CompletionStage[Unit] = CompletableFuture.supplyAsync(() => throw ex)
        assertM(ZIO.fromCompletionStage(noValue).exit)(fails[Throwable](equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that produces the value from `Future`") {
        lazy val someValue: CompletionStage[Int] = CompletableFuture.completedFuture(42)
        assertM(ZIO.fromCompletionStage(someValue))(equalTo(42))
      },
      test("handle null produced by the completed `Future`") {
        lazy val someValue: CompletionStage[String] = CompletableFuture.completedFuture[String](null)
        assertM(ZIO.fromCompletionStage(someValue).map(Option(_)))(isNone)
      } @@ zioTag(errors),
      test("be referentially transparent") {
        var n    = 0
        val task = ZIO.fromCompletionStage(CompletableFuture.supplyAsync(() => n += 1))
        for {
          _ <- task
          _ <- task
        } yield assert(n)(equalTo(2))
      }
    ) @@ zioTag(future),
    suite("`Task.toCompletableFuture` must")(
      test("produce always a successful `IO` of `Future`") {
        val failedIO = IO.fail[Throwable](new Exception("IOs also can fail"))
        assertM(failedIO.toCompletableFuture)(isSubtype[CompletableFuture[Unit]](anything))
      },
      test("be polymorphic in error type") {
        val unitIO: Task[Unit]                          = Task.unit
        val polyIO: IO[String, CompletableFuture[Unit]] = unitIO.toCompletableFuture
        assert(polyIO)(anything)
      } @@ zioTag(errors),
      test("return a `CompletableFuture` that fails if `IO` fails") {
        val ex                       = new Exception("IOs also can fail")
        val failedIO: Task[Unit]     = IO.fail[Throwable](ex)
        val failedFuture: Task[Unit] = failedIO.toCompletableFuture.flatMap(f => Task(f.get()))
        assertM(failedFuture.exit)(
          fails[Throwable](hasField("message", _.getMessage, equalTo("java.lang.Exception: IOs also can fail")))
        )
      } @@ zioTag(errors),
      test("return a `CompletableFuture` that produces the value from `IO`") {
        val someIO = Task.succeed[Int](42)
        assertM(someIO.toCompletableFuture.map(_.get()))(equalTo(42))
      }
    ) @@ zioTag(future),
    suite("`Task.toCompletableFutureE` must")(
      test("convert error of type `E` to `Throwable`") {
        val failedIO: IO[String, Unit] = IO.fail[String]("IOs also can fail")
        val failedFuture: Task[Unit] =
          failedIO.toCompletableFutureWith(new Exception(_)).flatMap(f => Task(f.get()))
        assertM(failedFuture.exit)(
          fails[Throwable](hasField("message", _.getMessage, equalTo("java.lang.Exception: IOs also can fail")))
        )
      } @@ zioTag(errors)
    ) @@ zioTag(future),
    suite("`Fiber.fromCompletionStage`")(
      test("be lazy on the `Future` parameter") {
        var evaluated                  = false
        def ftr: CompletionStage[Unit] = CompletableFuture.supplyAsync(() => evaluated = true)
        Fiber.fromCompletionStage(ftr)
        assert(evaluated)(isFalse)
      },
      test("catch exceptions thrown by lazy block") {
        val ex                              = new Exception("no future for you!")
        def noFuture: CompletionStage[Unit] = throw ex
        assertM(Fiber.fromCompletionStage(noFuture).join.exit)(fails(equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (failedFuture)") {
        val ex                             = new Exception("no value for you!")
        def noValue: CompletionStage[Unit] = CompletableFuture_.failedFuture(ex)
        assertM(Fiber.fromCompletionStage(noValue).join.exit)(fails[Throwable](equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (supplyAsync)") {
        val ex                             = new Exception("no value for you!")
        def noValue: CompletionStage[Unit] = CompletableFuture.supplyAsync(() => throw ex)
        assertM(Fiber.fromCompletionStage(noValue).join.exit)(fails[Throwable](equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that produces the value from `Future`") {
        def someValue: CompletionStage[Int] = CompletableFuture.completedFuture(42)
        assertM(Fiber.fromCompletionStage(someValue).join.exit)(succeeds(equalTo(42)))
      }
    ) @@ zioTag(future),
    suite("`Fiber.fromFutureJava` must")(
      test("be lazy on the `Future` parameter") {
        var evaluated         = false
        def ftr: Future[Unit] = CompletableFuture.supplyAsync(() => evaluated = true)
        Fiber.fromFutureJava(ftr)
        assert(evaluated)(isFalse)
      },
      test("catch exceptions thrown by lazy block") {
        val ex                     = new Exception("no future for you!")
        def noFuture: Future[Unit] = throw ex
        assertM(Fiber.fromFutureJava(noFuture).join.exit)(fails(equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (failedFuture)") {
        val ex                    = new Exception("no value for you!")
        def noValue: Future[Unit] = CompletableFuture_.failedFuture(ex)
        assertM(Fiber.fromFutureJava(noValue).join.exit)(fails[Throwable](equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that fails if `Future` fails (failedFuture)") {
        val ex                    = new Exception("no value for you!")
        def noValue: Future[Unit] = CompletableFuture.supplyAsync(() => throw ex)
        assertM(Fiber.fromFutureJava(noValue).join.exit)(fails[Throwable](equalTo(ex)))
      } @@ zioTag(errors),
      test("return an `IO` that produces the value from `Future`") {
        def someValue: Future[Int] = CompletableFuture.completedFuture(42)
        assertM(Fiber.fromFutureJava(someValue).join.exit)(succeeds(equalTo(42)))
      }
    ) @@ zioTag(future),
    suite("`Task.withCompletionHandler` must")(
      test("write and read to and from AsynchronousSocketChannel") {
        val list: List[Byte] = List(13)
        val address          = new InetSocketAddress(54321)
        val server           = AsynchronousServerSocketChannel.open().bind(address)
        val client           = AsynchronousSocketChannel.open()

        val taskServer = for {
          c <- ZIO.asyncWithCompletionHandler[AsynchronousSocketChannel](server.accept((), _))
          w <- ZIO.asyncWithCompletionHandler[Integer](c.write(ByteBuffer.wrap(list.toArray), (), _))
        } yield w

        val taskClient = for {
          _     <- ZIO.asyncWithCompletionHandler[Void](client.connect(address, (), _))
          buffer = ByteBuffer.allocate(1)
          r     <- ZIO.asyncWithCompletionHandler[Integer](client.read(buffer, (), _))
        } yield (r, buffer.array.toList)

        val task = for {
          fiberServer  <- taskServer.fork
          fiberClient  <- taskClient.fork
          resultServer <- fiberServer.join
          resultClient <- fiberClient.join
          _            <- ZIO.succeed(server.close())
        } yield (resultServer, resultClient)

        assertM(task.exit)(
          succeeds[(Integer, (Integer, List[Byte]))](equalTo((Integer.valueOf(1), (Integer.valueOf(1), list))))
        )
      }
    ) @@ zioTag(future)
  )
}
