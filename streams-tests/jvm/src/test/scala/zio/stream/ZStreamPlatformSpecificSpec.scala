package zio.stream

import zio._
import zio.test.Assertion._
import zio.test.TestAspect.flaky
import zio.test._

import java.io._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import java.nio.{Buffer, ByteBuffer}
import java.util.concurrent.CountDownLatch
import scala.concurrent.ExecutionContext.global

object ZStreamPlatformSpecificSpec extends ZIOBaseSpec {
  def socketClient(port: Int): ZIO[Scope, Throwable, AsynchronousSocketChannel] =
    ZIO.acquireRelease(ZIO.attemptBlockingIO(AsynchronousSocketChannel.open()).flatMap { client =>
      ZIO
        .fromFutureJava(client.connect(new InetSocketAddress("localhost", port)))
        .map(_ => client)
    })(c => ZIO.succeed(c.close()))

  def spec = suite("ZStream JVM")(
    suite("Constructors")(
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
            global.execute(() => chunk.foreach(a => k(ZIO.succeed(Chunk.single(a)))))
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
                         global.execute { () =>
                           // 1st consumed by sink, 2-6 – in queue, 7th – back pressured
                           (1 to 7).foreach(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                           cb(refDone.set(true) *> Exit.failNone)
                         }
                         None
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
                          global.execute(() => k(Exit.failNone))
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
                         global.execute { () =>
                           // 1st consumed by sink, 2-6 – in queue, 7th – back pressured
                           (1 to 7).foreach(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                           cb(refDone.set(true) *> Exit.failNone)
                         }
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
                          global.execute(() => k(Exit.failNone))
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
                         global.execute { () =>
                           // 1st consumed by sink, 2-6 – in queue, 7th – back pressured
                           (1 to 7).foreach(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                           cb(refDone.set(true) *> Exit.failNone)
                         }
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
                         global.execute(() => offer(ZIO.succeed(Chunk.unit)))
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
                          global.execute(() => k(Exit.failNone))
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
                         global.execute { () =>
                           // 1st consumed by sink, 2-6 – in queue, 7th – back pressured
                           (1 to 7).foreach(i => cb(refCnt.set(i) *> ZIO.succeed(Chunk.single(1))))
                           cb(refDone.set(true) *> Exit.failNone)
                         }
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
      suite("fromResource")(
        test("returns the content of the resource") {
          ZStream
            .fromResource("zio/stream/bom/quickbrown-UTF-8-with-BOM.txt")
            .via(ZPipeline.utf8Decode)
            .mkString
            .map(assert(_)(startsWithString("Sent")))
        },
        test("fails with FileNotFoundException if the stream does not exist") {
          assertZIO(ZStream.fromResource("does_not_exist").runDrain.exit)(
            fails(isSubtype[FileNotFoundException](hasMessage(containsString("does_not_exist"))))
          )
        }
      )
    ),
    suite("fromOutputStreamWriter")(
      test("reads what is written") {
        check(Gen.listOf(Gen.chunkOf(Gen.byte)), Gen.int(1, 10)) { (bytess, chunkSize) =>
          val write    = (out: OutputStream) => for (bytes <- bytess) out.write(bytes.toArray)
          val expected = bytess.foldLeft[Chunk[Byte]](Chunk.empty)(_ ++ _)
          ZStream.fromOutputStreamWriter(write, chunkSize).runCollect.map(assert(_)(equalTo(expected)))
        }
      },
      test("captures errors") {
        val write = (_: OutputStream) => throw new Exception("boom")
        ZStream.fromOutputStreamWriter(write).runDrain.exit.map(assert(_)(fails(hasMessage(equalTo("boom")))))
      },
      test("is not affected by closing the output stream") {
        val data  = Array.tabulate[Byte](ZStream.DefaultChunkSize * 5 / 2)(_.toByte)
        val write = (out: OutputStream) => { out.write(data); out.close() }
        ZStream.fromOutputStreamWriter(write).runCollect.map(assert(_)(equalTo(Chunk.fromArray(data))))
      },
      test("is interruptable") {
        val latch = new CountDownLatch(1)
        val write = (out: OutputStream) => { latch.await(); out.write(42); }
        ZStream.fromOutputStreamWriter(write).runDrain.fork.flatMap(_.interrupt).map(assert(_)(isInterrupted))
      }
    ),
    suite("fromSocketServer")(
      test("read data") {
        for {
          messages <- Gen.listOf1(Gen.byte).map(_.toArray).runCollectN(200)
          readMessages <- ZStream
                            .fromSocketServer(8896)
                            .zip(ZStream.scoped(socketClient(8896)))
                            .flatMap { case (serverChannel, clientChannel) =>
                              ZStream
                                .fromIterable(messages)
                                .mapZIO(m =>
                                  ZIO
                                    .fromFutureJava(
                                      clientChannel.write(ByteBuffer.wrap(m))
                                    ) *> serverChannel.read
                                    .take(m.size.toLong)
                                    .runCollect
                                )

                            }
                            .take(messages.size.toLong)
                            .runCollect
          expectedOutput = messages.map(Chunk.fromArray)
        } yield assert(readMessages)(hasSameElementsDistinct(expectedOutput))
      },
      test("write data") {
        for {
          messages <- Gen.listOf1(Gen.byte).map(_.toArray).runCollectN(200)
          writtenMessages <- ZStream
                               .fromSocketServer(8897)
                               .zip(ZStream.scoped(socketClient(8897)))
                               .flatMap { case (serverChannel, clientChannel) =>
                                 ZStream
                                   .fromIterable(messages)
                                   .mapZIO(m =>
                                     ZStream.fromIterable(m).run(serverChannel.write) *> {
                                       val buffer = ByteBuffer.allocate(m.length)

                                       ZIO
                                         .fromFutureJava(clientChannel.read(buffer))
                                         .repeatUntil(_ < 1)
                                         .map { _ =>
                                           (buffer: Buffer).flip()
                                           Chunk.fromArray(buffer.array)
                                         }
                                     }
                                   )
                               }
                               .take(messages.size.toLong)
                               .runCollect
          expectedOutput = messages.map(Chunk.fromArray)
        } yield assert(writtenMessages)(hasSameElementsDistinct(expectedOutput))

      }
    ) @@ flaky(20) // socket connections can be flaky some times
  )
}
