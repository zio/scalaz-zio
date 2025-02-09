package zio.interop

import zio._
import zio.test.Assertion._
import zio.test._

import scala.scalajs.js.{Promise => JSPromise}

object JSSpec extends ZIOBaseSpec {
  def spec: Spec[Any, Throwable] = suite("JSSpec")(
    suite("Task.fromPromiseJS must")(
      test("be lazy on the Promise parameter") {
        var evaluated          = false
        def p: JSPromise[Unit] = { evaluated = true; JSPromise.resolve[Unit](()) }
        assertZIO(ZIO.fromPromiseJS(p).when(false).as(evaluated))(isFalse)
      },
      test("catch exceptions thrown by lazy block") {
        val ex                              = new Exception("no promise for you!")
        lazy val noPromise: JSPromise[Unit] = throw ex
        assertZIO(ZIO.fromPromiseJS(noPromise).exit)(dies(equalTo(ex)))
      },
      test("return a Task that fails if Promise is rejected") {
        val ex                            = new Exception("no value for you!")
        lazy val noValue: JSPromise[Unit] = JSPromise.reject(ex)
        assertZIO(ZIO.fromPromiseJS(noValue).exit)(fails(equalTo(ex)))
      },
      test("return a Task that produces the value from the Promise") {
        lazy val someValue: JSPromise[Int] = JSPromise.resolve[Int](42)
        assertZIO(ZIO.fromPromiseJS(someValue))(equalTo(42))
      },
      test("handle null produced by the completed Promise") {
        lazy val someValue: JSPromise[String] = JSPromise.resolve[String](null)
        assertZIO(ZIO.fromPromiseJS(someValue).map(Option(_)))(isNone)
      }
    ),
    suite("ZIO.fromPromiseJS must")(
      test("be lazy on the Promise parameter") {
        var evaluated          = false
        def p: JSPromise[Unit] = { evaluated = true; JSPromise.resolve[Unit](()) }
        assertZIO(ZIO.fromPromiseJS(p).when(false).as(evaluated))(isFalse)
      },
      test("catch exceptions thrown by lazy block") {
        val ex                              = new Exception("no promise for you!")
        lazy val noPromise: JSPromise[Unit] = throw ex
        assertZIO(ZIO.fromPromiseJS(noPromise).exit)(dies(equalTo(ex)))
      },
      test("return a ZIO that fails if Promise is rejected") {
        val ex                            = new Exception("no value for you!")
        lazy val noValue: JSPromise[Unit] = JSPromise.reject(ex)
        assertZIO(ZIO.fromPromiseJS(noValue).exit)(fails(equalTo(ex)))
      },
      test("return a ZIO that produces the value from the Promise") {
        lazy val someValue: JSPromise[Int] = JSPromise.resolve[Int](42)
        assertZIO(ZIO.fromPromiseJS(someValue))(equalTo(42))
      },
      test("handle null produced by the completed Promise") {
        lazy val someValue: JSPromise[String] = JSPromise.resolve[String](null)
        assertZIO(ZIO.fromPromiseJS(someValue).map(Option(_)))(isNone)
      }
    ),
    suite("ZIO#toPromiseJS must")(
      test("produce a rejected Promise from a failed ZIO") {
        val ex                          = new Exception("Ouch")
        val failedZIO: Task[Unit]       = ZIO.fail(ex)
        val rejectedPromise: Task[Unit] = failedZIO.toPromiseJS.flatMap(p => ZIO.fromFuture(_ => p.toFuture))
        assertZIO(rejectedPromise.exit)(fails(equalTo(ex)))
      },
      test("produce a resolved Promise from a successful ZIO") {
        val zio                        = ZIO.succeed(42)
        val resolvedPromise: Task[Int] = zio.toPromiseJS.flatMap(p => ZIO.fromFuture(_ => p.toFuture))
        assertZIO(resolvedPromise)(equalTo(42))
      }
    ),
    suite("ZIO#readFile")(
      test("readFile") {
        def getTempDir: String = {
          import scalajs.js.Dynamic.{global => g}
          val os = g.require("os")
          os.tmpdir().toString
        }

        val content = "Hello World"
        for {
          randomFileName <- Random.nextUUID.map(_.toString)
          tempDir        <- ZIO.attempt(getTempDir)
          file            = tempDir + "/" + randomFileName + ".txt"
          _              <- ZIO.writeFile(file, content)
          fileContent    <- ZIO.readFile(file)
        } yield assertTrue(fileContent == content)
      }
    )
  )
}
