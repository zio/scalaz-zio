package zio.stream

import zio.test.Assertion.{containsCause, equalTo, failsCause, isFalse, isTrue}
import zio.test.{Gen, assert, assertM, check}
import zio.{Cause, Chunk, IO, Promise, Ref, Schedule, Task, UIO, ZIO, ZIOBaseSpec}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ZStreamPlatformSpecificSpec extends ZIOBaseSpec {
  def spec = suite("ZStream JS")(
    test("async")(check(Gen.chunkOf(Gen.int)) { chunk =>
      val s = ZStream.async[Any, Throwable, Int](k => chunk.foreach(a => k(Task.succeed(Chunk.single(a)))))

      assertM(s.take(chunk.size.toLong).runCollect)(equalTo(chunk))
    }),
    suite("asyncMaybe")(
      test("asyncMaybe signal end stream") {
        for {
          result <- ZStream
                      .asyncMaybe[Any, Nothing, Int] { k =>
                        k(IO.fail(None))
                        None
                      }
                      .runCollect
        } yield assert(result)(equalTo(Chunk.empty))
      },
      test("asyncMaybe Some")(check(Gen.chunkOf(Gen.int)) { chunk =>
        val s = ZStream.asyncMaybe[Any, Throwable, Int](_ => Some(ZStream.fromIterable(chunk)))

        assertM(s.runCollect.map(_.take(chunk.size)))(equalTo(chunk))
      }),
      test("asyncMaybe None")(check(Gen.chunkOf(Gen.int)) { chunk =>
        val s = ZStream.asyncMaybe[Any, Throwable, Int] { k =>
          chunk.foreach(a => k(Task.succeed(Chunk.single(a))))
          None
        }

        assertM(s.take(chunk.size.toLong).runCollect)(equalTo(chunk))
      }),
      test("asyncMaybe back pressure") {
        for {
          refCnt  <- Ref.make(0)
          refDone <- Ref.make[Boolean](false)
          stream = ZStream.asyncMaybe[Any, Throwable, Int](
                     cb => {
                       Future
                         .sequence(
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeedNow(Chunk.single(1))))
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
                       global.execute(() => chunk.foreach(a => k(Task.succeed(Chunk.single(a)))))
                       latch.succeed(()) *>
                         Task.unit
                     }
                     .take(chunk.size.toLong)
                     .run(ZSink.collectAll[Throwable, Int])
                     .fork
          _ <- latch.await
          s <- fiber.join
        } yield assert(s)(equalTo(chunk))
      }),
      test("asyncZIO signal end stream") {
        for {
          result <- ZStream
                      .asyncZIO[Any, Nothing, Int] { k =>
                        k(IO.fail(None))
                        UIO.unit
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
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeedNow(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       UIO.unit
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
    suite("asyncManaged")(
      test("asyncManaged")(check(Gen.chunkOf(Gen.int).filter(_.nonEmpty)) { chunk =>
        for {
          latch <- Promise.make[Nothing, Unit]
          fiber <- ZStream
                     .asyncManaged[Any, Throwable, Int] { k =>
                       global.execute(() => chunk.foreach(a => k(Task.succeed(Chunk.single(a)))))
                       latch.succeed(()).toManaged *>
                         Task.unit.toManaged
                     }
                     .take(chunk.size.toLong)
                     .run(ZSink.collectAll)
                     .fork
          _ <- latch.await
          s <- fiber.join
        } yield assert(s)(equalTo(chunk))
      }),
      test("asyncManaged signal end stream") {
        for {
          result <- ZStream
                      .asyncManaged[Any, Nothing, Int] { k =>
                        k(IO.fail(None))
                        UIO.unit.toManaged
                      }
                      .runCollect
        } yield assert(result)(equalTo(Chunk.empty))
      },
      test("asyncManaged back pressure") {
        for {
          refCnt  <- Ref.make(0)
          refDone <- Ref.make[Boolean](false)
          stream = ZStream.asyncManaged[Any, Throwable, Int](
                     cb => {
                       Future
                         .sequence(
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeedNow(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       UIO.unit.toManaged
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
                       offer(ZIO.succeedNow(Chunk.unit))
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

        assertM(s.take(chunk.size.toLong).runCollect)(equalTo(chunk))
      }),
      test("asyncInterrupt signal end stream ") {
        for {
          result <- ZStream
                      .asyncInterrupt[Any, Nothing, Int] { k =>
                        k(IO.fail(None))
                        Left(UIO.succeedNow(()))
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
                           (1 to 7).map(i => cb(refCnt.set(i) *> ZIO.succeedNow(Chunk.single(1))))
                         )
                         .flatMap(_ => cb(refDone.set(true) *> ZIO.fail(None)))
                       Left(UIO.unit)
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
    )
  )
}
