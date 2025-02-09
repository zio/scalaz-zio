package zio.stream

import zio.test.Assertion.{containsCause, equalTo, failsCause, isFalse, isTrue}
import zio.test.{Gen, assert, assertZIO, check}
import zio.{Cause, Chunk, Promise, Ref, Schedule, ZIO, ZIOBaseSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import java.nio.charset.StandardCharsets
import zio.test.Assertion.containsString

object ZStreamPlatformSpecificSpec extends ZIOBaseSpec {

  private def getTempDir: String = {
    import scalajs.js.Dynamic.{global => g}
    val os = g.require("os")
    os.tmpdir().toString
  }

  def spec = suite("ZStream JS")(
    test("async")(check(Gen.chunkOf(Gen.int)) { chunk =>
      val s = ZStream.async[Any, Throwable, Int](k => chunk.foreach(a => k(ZIO.succeed(Chunk.single(a)))))

      assertZIO(s.take(chunk.size.toLong).runCollect)(equalTo(chunk))
    }),
    suite("asyncMaybe")(
      test("asyncMaybe signal end stream") {
        for {
          result <- ZStream
                      .asyncMaybe[Any, Nothing, Int] { k =>
                        k(ZIO.fail(None))
                        None
                      }
                      .runCollect
        } yield assert(result)(equalTo(Chunk.empty))
      },
      test("asyncMaybe Some")(check(Gen.chunkOf(Gen.int)) { chunk =>
        val s = ZStream.asyncMaybe[Any, Throwable, Int](_ => Some(ZStream.fromIterable(chunk)))

        assertZIO(s.runCollect.map(_.take(chunk.size)))(equalTo(chunk))
      }),
      test("asyncMaybe None")(check(Gen.chunkOf(Gen.int)) { chunk =>
        val s = ZStream.asyncMaybe[Any, Throwable, Int] { k =>
          chunk.foreach(a => k(ZIO.succeed(Chunk.single(a))))
          None
        }

        assertZIO(s.take(chunk.size.toLong).runCollect)(equalTo(chunk))
      }),
      test("asyncMaybe back pressure") {
        for {
          refCnt  <- Ref.make(0)
          refDone <- Ref.make[Boolean](false)
          stream = ZStream.asyncMaybe[Any, Throwable, Int](
                     cb => {
                       Future
                         .sequence(
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       None
                     },
                     5
                   )
          run    <- stream.run(ZSink.take(1) *> ZSink.never).fork
          _      <- refCnt.get.repeat(Schedule.recurWhile(_ != 7))
          isDone <- refDone.get
          _      <- run.interrupt
        } yield assert(isDone)(isFalse)
      }
    ),
    suite("asyncZIO")(
      test("asyncZIO")(check(Gen.chunkOf(Gen.int).filter(_.nonEmpty)) { chunk =>
        for {
          latch <- Promise.make[Nothing, Unit]
          fiber <- ZStream
                     .asyncZIO[Any, Throwable, Int] { k =>
                       global.execute(() => chunk.foreach(a => k(ZIO.succeed(Chunk.single(a)))))
                       latch.succeed(()) *>
                         ZIO.unit
                     }
                     .take(chunk.size.toLong)
                     .run(ZSink.collectAll[Int])
                     .fork
          _ <- latch.await
          s <- fiber.join
        } yield assert(s)(equalTo(chunk))
      }),
      test("asyncZIO signal end stream") {
        for {
          result <- ZStream
                      .asyncZIO[Any, Nothing, Int] { k =>
                        k(ZIO.fail(None))
                        ZIO.unit
                      }
                      .runCollect
        } yield assert(result)(equalTo(Chunk.empty))
      },
      test("asyncZIO back pressure") {
        for {
          refCnt  <- Ref.make(0)
          refDone <- Ref.make[Boolean](false)
          stream = ZStream.asyncZIO[Any, Throwable, Int](
                     cb => {
                       Future
                         .sequence(
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       ZIO.unit
                     },
                     5
                   )
          run    <- stream.run(ZSink.take(1) *> ZSink.never).fork
          _      <- refCnt.get.repeatWhile(_ != 7)
          isDone <- refDone.get
          _      <- run.interrupt
        } yield assert(isDone)(isFalse)
      }
    ),
    suite("asyncScoped")(
      test("asyncScoped")(check(Gen.chunkOf(Gen.int).filter(_.nonEmpty)) { chunk =>
        for {
          latch <- Promise.make[Nothing, Unit]
          fiber <- ZStream
                     .asyncScoped[Any, Throwable, Int] { k =>
                       global.execute(() => chunk.foreach(a => k(ZIO.succeed(Chunk.single(a)))))
                       latch.succeed(()) *>
                         ZIO.unit
                     }
                     .take(chunk.size.toLong)
                     .run(ZSink.collectAll)
                     .fork
          _ <- latch.await
          s <- fiber.join
        } yield assert(s)(equalTo(chunk))
      }),
      test("asyncScoped signal end stream") {
        for {
          result <- ZStream
                      .asyncScoped[Any, Nothing, Int] { k =>
                        k(ZIO.fail(None))
                        ZIO.unit
                      }
                      .runCollect
        } yield assert(result)(equalTo(Chunk.empty))
      },
      test("asyncScoped back pressure") {
        for {
          refCnt  <- Ref.make(0)
          refDone <- Ref.make[Boolean](false)
          stream = ZStream.asyncScoped[Any, Throwable, Int](
                     cb => {
                       Future
                         .sequence(
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       ZIO.unit
                     },
                     5
                   )
          run    <- stream.run(ZSink.take(1) *> ZSink.never).fork
          _      <- refCnt.get.repeatWhile(_ != 7)
          isDone <- refDone.get
          _      <- run.interrupt
        } yield assert(isDone)(isFalse)
      }
    ),
    suite("asyncInterrupt")(
      test("asyncInterrupt Left") {
        for {
          cancelled <- Ref.make(false)
          latch     <- Promise.make[Nothing, Unit]
          fiber <- ZStream
                     .asyncInterrupt[Any, Nothing, Unit] { offer =>
                       offer(ZIO.succeed(Chunk.unit))
                       Left(cancelled.set(true))
                     }
                     .tap(_ => latch.succeed(()))
                     .runDrain
                     .fork
          _      <- latch.await
          _      <- fiber.interrupt
          result <- cancelled.get
        } yield assert(result)(isTrue)
      },
      test("asyncInterrupt Right")(check(Gen.chunkOf(Gen.int)) { chunk =>
        val s = ZStream.asyncInterrupt[Any, Throwable, Int](_ => Right(ZStream.fromIterable(chunk)))

        assertZIO(s.take(chunk.size.toLong).runCollect)(equalTo(chunk))
      }),
      test("asyncInterrupt signal end stream ") {
        for {
          result <- ZStream
                      .asyncInterrupt[Any, Nothing, Int] { k =>
                        k(ZIO.fail(None))
                        Left(ZIO.succeed(()))
                      }
                      .runCollect
        } yield assert(result)(equalTo(Chunk.empty))
      },
      test("asyncInterrupt back pressure") {
        for {
          selfId  <- ZIO.fiberId
          refCnt  <- Ref.make(0)
          refDone <- Ref.make[Boolean](false)
          stream = ZStream.asyncInterrupt[Any, Throwable, Int](
                     cb => {
                       Future
                         .sequence(
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       Left(ZIO.unit)
                     },
                     5
                   )
          run    <- stream.run(ZSink.take(1) *> ZSink.never).fork
          _      <- refCnt.get.repeatWhile(_ != 7)
          isDone <- refDone.get
          exit   <- run.interrupt
        } yield assert(isDone)(isFalse) &&
          assert(exit.untraced)(failsCause(containsCause(Cause.interrupt(selfId))))
      }
    ),
    test("FromFile should work") {
      val content = "Hello World"
      val path    = getTempDir + "/zio-streams-test.txt"

      val s = ZStream.fromFile(path)
      for {
        _   <- ZIO.writeFile(path, content)
        res <- s.runCollect.map(chunk => new String(chunk.toArray, StandardCharsets.UTF_8))
      } yield assert(res)(equalTo(content))
    },
    test("FromFile should fail on non existing file") {
      val path = getTempDir + "/zio-streams-test-2.txt"
      val s    = ZStream.fromFile(path)
      for {

        res <- s.runCollect.flip
      } yield assert(res.getMessage())(containsString(s"Error: ENOENT: no such file or directory"))
    }
  )
}
