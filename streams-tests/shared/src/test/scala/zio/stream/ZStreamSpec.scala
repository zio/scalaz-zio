package zio.stream

import zio._
import zio.stm.TQueue
import zio.concurrent.CountdownLatch
import zio.stream.ZStream.HaltStrategy
import zio.stream.ZStreamGen._
import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, flaky, nonFlaky, scala2Only, withLiveClock}
import zio.test._

import java.io.{ByteArrayInputStream, IOException}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext

object ZStreamSpec extends ZIOBaseSpec {
  import ZIOTag._

  def inParallel(action: => Unit)(implicit ec: ExecutionContext): Unit =
    ec.execute(() => action)

  def spec =
    suite("ZStreamSpec")(
      suite("Combinators")(
        suite("absolve")(
          test("happy path")(check(tinyChunkOf(Gen.int)) { xs =>
            val stream = ZStream.fromIterable(xs.map(Right(_)))
            assertZIO(stream.absolve.runCollect)(equalTo(xs))
          }),
          test("failure")(check(tinyChunkOf(Gen.int)) { xs =>
            val stream = ZStream.fromIterable(xs.map(Right(_))) ++ ZStream.succeed(Left("Ouch"))
            assertZIO(stream.absolve.runCollect.exit)(fails(equalTo("Ouch")))
          }),
          test("round-trip #1")(check(tinyChunkOf(Gen.int), Gen.string) { (xs, s) =>
            val xss    = ZStream.fromIterable(xs.map(Right(_)))
            val stream = xss ++ ZStream(Left(s)) ++ xss
            for {
              res1 <- stream.runCollect
              res2 <- stream.absolve.either.runCollect
            } yield assert(res1)(startsWith(res2))
          }),
          test("round-trip #2")(check(tinyChunkOf(Gen.int), Gen.string) { (xs, s) =>
            val xss    = ZStream.fromIterable(xs)
            val stream = xss ++ ZStream.fail(s)
            for {
              res1 <- stream.runCollect.exit
              res2 <- stream.either.absolve.runCollect.exit
            } yield assert(res1)(fails(equalTo(s))) && assert(res2)(fails(equalTo(s)))
          })
        ) @@ TestAspect.jvmOnly, // This is horrendously slow on Scala.js for some reason
        suite("aggregateAsync")(
          test("simple example") {
            ZStream(1, 1, 1, 1)
              .aggregateAsync(ZSink.foldUntil(List[Int](), 3)((acc, el: Int) => el :: acc))
              .runCollect
              .map { result =>
                assert(result.toList.flatten)(equalTo(List(1, 1, 1, 1))) &&
                assert(result.forall(_.length <= 3))(isTrue)
              }
          },
          test("error propagation 1") {
            val e = new RuntimeException("Boom")
            assertZIO(
              ZStream(1, 1, 1, 1)
                .aggregateAsync(ZSink.die(e))
                .runCollect
                .exit
            )(dies(equalTo(e)))
          },
          test("error propagation 2") {
            val e = new RuntimeException("Boom")
            assertZIO(
              ZStream(1, 1)
                .aggregateAsync(ZSink.foldLeftZIO(Nil)((_, _: Any) => ZIO.die(e)))
                .runCollect
                .exit
            )(dies(equalTo(e)))
          },
          test("interruption propagation 1") {
            for {
              latch     <- Promise.make[Nothing, Unit]
              cancelled <- Ref.make(false)
              sink = ZSink.foldZIO(List[Int]())(_ => true) { (acc, el: Int) =>
                       if (el == 1) ZIO.succeed(el :: acc)
                       else
                         (latch.succeed(()) *> ZIO.infinity)
                           .onInterrupt(cancelled.set(true))
                     }
              fiber  <- ZStream(1, 1, 2).aggregateAsync(sink).runCollect.fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- cancelled.get
            } yield assert(result)(isTrue)
          },
          test("interruption propagation 2") {
            for {
              latch     <- Promise.make[Nothing, Unit]
              cancelled <- Ref.make(false)
              sink = ZSink.fromZIO {
                       (latch.succeed(()) *> ZIO.infinity)
                         .onInterrupt(cancelled.set(true))
                     }
              fiber  <- ZStream(1, 1, 2).aggregateAsync(sink).runCollect.fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- cancelled.get
            } yield assert(result)(isTrue)
          },
          test("leftover handling") {
            val data = List(1, 2, 2, 3, 2, 3)
            assertZIO(
              ZStream(data: _*)
                .aggregateAsync(
                  ZSink.foldWeighted(List[Int]())((_, x: Int) => x.toLong, 4)((acc, el) => el :: acc)
                )
                .map(_.reverse)
                .runCollect
                .map(_.toList.flatten)
            )(equalTo(data))
          },
          test("issue 6395") {
            assertZIO(
              ZStream(1, 2, 3)
                .aggregateAsync(ZSink.collectAllN[Int](2))
                .runCollect
            )(equalTo(Chunk(Chunk(1, 2), Chunk(3))))
          } @@ exceptJS(nonFlaky),
          test("zio-kafka issue") {
            assertZIO(
              for {
                queue <- Queue.unbounded[Take[Nothing, Int]]
                fiber <- ZStream
                           .fromQueue(queue)
                           .flattenTake
                           .aggregateAsync(
                             ZSink
                               .foldLeft[Int, List[Int]](Nil) { case (l, n) => n :: l }
                           )
                           .runCollect
                           .fork

                _ <- ZIO.sleep(1.second)
                _ <- queue.offer(Take.chunk(Chunk(1, 2, 3, 4, 5)))
                _ <- ZIO.sleep(1.second)
                _ <- queue.offer(Take.chunk(Chunk(6, 7, 8, 9, 10)))
                _ <- ZIO.sleep(1.second)
                _ <- queue.offer(Take.chunk(Chunk(11, 12, 13, 14, 15)))
                _ <- queue.offer(Take.end)

                result <- fiber.join
              } yield result.filter(_.nonEmpty)
            )(equalTo(Chunk(List(5, 4, 3, 2, 1), List(10, 9, 8, 7, 6), List(15, 14, 13, 12, 11))))
          } @@ withLiveClock
        ),
        suite("transduce")(
          test("simple example") {
            assertZIO(
              ZStream('1', '2', ',', '3', '4')
                .transduce(ZSink.collectAllWhile((_: Char).isDigit) <* ZSink.collectAllWhile(!(_: Char).isDigit))
                .map(_.mkString.toInt)
                .runCollect
            )(equalTo(Chunk(12, 34)))
          },
          test("no remainder") {
            assertZIO(
              ZStream(1, 2, 3, 4)
                .transduce(ZSink.fold(100)(_ % 2 == 0)(_ + (_: Int)))
                .runCollect
            )(equalTo(Chunk(101, 105, 104)))
          },
          test("with a sink that always signals more") {
            assertZIO(
              ZStream(1, 2, 3)
                .transduce(ZSink.fold(0)(_ => true)(_ + (_: Int)))
                .runCollect
            )(equalTo(Chunk(1 + 2 + 3)))
          },
          test("propagate scope error") {
            val fail = "I'm such a failure!"
            val t    = ZSink.fail(fail)
            assertZIO(ZStream(1, 2, 3).transduce(t).runCollect.either)(isLeft(equalTo(fail)))
          }
        ),
        suite("aggregateAsyncWithinEither")(
          test("simple example") {
            assertZIO(
              ZStream(1, 1, 1, 1, 2, 2)
                .aggregateAsyncWithinEither(
                  ZSink
                    .fold((List[Int](), true))(_._2) { (acc, el: Int) =>
                      if (el == 1) (el :: acc._1, true)
                      else if (el == 2 && acc._1.isEmpty) (el :: acc._1, false)
                      else (el :: acc._1, false)
                    }
                    .map(_._1),
                  Schedule.spaced(30.minutes)
                )
                .runCollect
            )(
              equalTo(Chunk(Right(List(2, 1, 1, 1, 1)), Right(List(2))))
            )
          },
          test("fails fast") {
            for {
              queue <- Queue.unbounded[Int]
              _ <- ZStream
                     .range(1, 10)
                     .tap(i => ZIO.fail("BOOM!").when(i == 6) *> queue.offer(i))
                     .aggregateAsyncWithin(
                       ZSink.foldUntil[Int, Unit]((), 5)((_, _: Int) => ()),
                       Schedule.forever
                     )
                     .runDrain
                     .catchAll(_ => ZIO.succeed(()))
              value <- queue.takeAll
              _     <- queue.shutdown
            } yield assert(value)(equalTo(Chunk(1, 2, 3, 4, 5)))
          } @@ zioTag(errors),
          test("error propagation 1") {
            val e = new RuntimeException("Boom")
            assertZIO(
              ZStream(1, 1, 1, 1)
                .aggregateAsyncWithinEither(ZSink.die(e), Schedule.spaced(30.minutes))
                .runCollect
                .exit
            )(dies(equalTo(e)))
          } @@ zioTag(errors),
          test("error propagation 2") {
            val e = new RuntimeException("Boom")

            assertZIO(
              ZStream(1, 1)
                .aggregateAsyncWithinEither(
                  ZSink.foldZIO[Any, Nothing, Int, List[Int]](List[Int]())(_ => true)((_, _) => ZIO.die(e)),
                  Schedule.spaced(30.minutes)
                )
                .runCollect
                .exit
            )(dies(equalTo(e)))
          } @@ zioTag(errors),
          test("interruption propagation") {
            for {
              latch     <- Promise.make[Nothing, Unit]
              cancelled <- Ref.make(false)
              sink = ZSink.foldZIO(List[Int]())(_ => true) { (acc, el: Int) =>
                       if (el == 1) ZIO.succeed(el :: acc)
                       else
                         (latch.succeed(()) *> ZIO.infinity)
                           .onInterrupt(cancelled.set(true))
                     }
              fiber <- ZStream(1, 1, 2)
                         .aggregateAsyncWithinEither(sink, Schedule.spaced(30.minutes))
                         .runCollect
                         .fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- cancelled.get
            } yield assert(result)(isTrue)
          } @@ zioTag(interruption),
          test("interruption propagation") {
            for {
              latch     <- Promise.make[Nothing, Unit]
              cancelled <- Ref.make(false)
              sink = ZSink.fromZIO {
                       (latch.succeed(()) *> ZIO.infinity)
                         .onInterrupt(cancelled.set(true))
                     }
              fiber <- ZStream(1, 1, 2)
                         .aggregateAsyncWithinEither(sink, Schedule.spaced(30.minutes))
                         .runCollect
                         .fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- cancelled.get
            } yield assert(result)(isTrue)
          } @@ zioTag(interruption),
          test("child fiber handling") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(2), Chunk(3))) { c =>
              for {
                fib <- ZStream
                         .fromQueue(c.queue)
                         .map(Take(_))
                         .tap(_ => c.proceed)
                         .flattenTake
                         .aggregateAsyncWithin(ZSink.last[Int], Schedule.fixed(200.millis))
                         .interruptWhen(ZIO.never)
                         .take(2)
                         .runCollect
                         .fork
                _       <- (c.offer *> TestClock.adjust(100.millis) *> c.awaitNext).repeatN(3)
                results <- fib.join.map(_.collect { case Some(ex) => ex })
              } yield assert(results)(equalTo(Chunk(2, 3)))
            }
          } @@ zioTag(interruption) @@ TestAspect.jvmOnly,
          test("leftover handling") {
            val data = List(1, 2, 2, 3, 2, 3)
            assertZIO(
              for {
                f <- (ZStream(data: _*)
                       .aggregateAsyncWithinEither(
                         ZSink
                           .foldWeighted(List[Int]())((_, i: Int) => i.toLong, 4)((acc, el) => el :: acc)
                           .map(_.reverse),
                         Schedule.spaced(100.millis)
                       )
                       .collect { case Right(v) =>
                         v
                       }
                       .runCollect
                       .map(_.toList.flatten))
                       .fork
                _      <- TestClock.adjust(31.minutes)
                result <- f.join
              } yield result
            )(equalTo(data))
          }
        ),
        suite("acquireReleaseWith")(
          test("acquireReleaseWith")(
            for {
              done <- Ref.make(false)
              iteratorStream = ZStream
                                 .acquireReleaseWith(ZIO.succeed(0 to 2))(_ => done.set(true))
                                 .flatMap(ZStream.fromIterable(_))
              result   <- iteratorStream.runCollect
              released <- done.get
            } yield assert(result)(equalTo(Chunk(0, 1, 2))) && assert(released)(isTrue)
          ),
          test("acquireReleaseWith short circuits")(
            for {
              done <- Ref.make(false)
              iteratorStream = ZStream
                                 .acquireReleaseWith(ZIO.succeed(0 to 3))(_ => done.set(true))
                                 .flatMap(ZStream.fromIterable(_))
                                 .take(2)
              result   <- iteratorStream.runCollect
              released <- done.get
            } yield assert(result)(equalTo(Chunk(0, 1))) && assert(released)(isTrue)
          ),
          test("no acquisition when short circuiting")(
            for {
              acquired <- Ref.make(false)
              iteratorStream = (ZStream(1) ++ ZStream.acquireReleaseWith(acquired.set(true))(_ => ZIO.unit))
                                 .take(0)
              _      <- iteratorStream.runDrain
              result <- acquired.get
            } yield assert(result)(isFalse)
          ),
          test("releases when there are defects") {
            for {
              ref <- Ref.make(false)
              _ <- ZStream
                     .acquireReleaseWith(ZIO.unit)(_ => ref.set(true))
                     .flatMap(_ => ZStream.fromZIO(ZIO.dieMessage("boom")))
                     .runDrain
                     .exit
              released <- ref.get
            } yield assert(released)(isTrue)
          },
          test("flatMap associativity doesn't affect acquire release lifetime")(
            for {
              leftAssoc <- ZStream
                             .acquireReleaseWith(Ref.make(true))(_.set(false))
                             .flatMap(ZStream.succeed(_))
                             .flatMap(r => ZStream.fromZIO(r.get))
                             .runCollect
                             .map(_.head)
              rightAssoc <- ZStream
                              .acquireReleaseWith(Ref.make(true))(_.set(false))
                              .flatMap(ZStream.succeed(_).flatMap(r => ZStream.fromZIO(r.get)))
                              .runCollect
                              .map(_.head)
            } yield assert(leftAssoc -> rightAssoc)(equalTo(true -> true))
          ),
          test("propagates errors") {
            val stream = ZStream.acquireReleaseWith(ZIO.unit)(_ => ZIO.dieMessage("die"))
            assertZIO(stream.runCollect.exit)(dies(anything))
          }
        ),
        suite("branchAfter")(
          test("switches pipelines") {
            check(Gen.chunkOf(Gen.int)) { data =>
              val test =
                ZStream
                  .fromChunk(0 +: data)
                  .via {
                    ZPipeline.branchAfter(1) { values =>
                      values.toList match {
                        case 0 :: Nil => ZPipeline.identity
                        case _        => ???
                      }
                    }
                  }
                  .runCollect
              assertZIO(test.exit)(succeeds(equalTo(data)))
            }
          },
          // test("finalizes transducers") {
          //   check(Gen.chunkOf(Gen.int)) { data =>
          //     val test =
          //       Ref.make(0).flatMap { ref =>
          //         ZStream
          //           .fromChunk(data)
          //           .branchAfter(1) { values =>
          //             values.toList match {
          //               case _ =>
          //                 ZTransducer {
          //                   Managed.acquireReleaseWith(
          //                     ref
          //                       .update(_ + 1)
          //                       .as[Option[Chunk[Int]] => UIO[Chunk[Int]]] {
          //                         case None    => ZIO.succeed(Chunk.empty)
          //                         case Some(c) => ZIO.succeed(c)
          //                       }
          //                   )(_ => ref.update(_ - 1))
          //                 }
          //             }
          //           }
          //           .runDrain *> ref.get
          //       }
          //     assertZIO(test.exit)(succeeds(equalTo(0)))
          //   }
          // },
          // test("finalizes transducers - inner transducer fails") {
          //   check(Gen.chunkOf(Gen.int)) { data =>
          //     val test =
          //       Ref.make(0).flatMap { ref =>
          //         ZStream
          //           .fromChunk(data)
          //           .branchAfter(1) { values =>
          //             values.toList match {
          //               case _ =>
          //                 ZTransducer {
          //                   Managed.acquireReleaseWith(
          //                     ref
          //                       .update(_ + 1)
          //                       .as[Option[Chunk[Int]] => IO[String, Chunk[Int]]] { case _ =>
          //                         ZIO.fail("boom")
          //                       }
          //                   )(_ => ref.update(_ - 1))
          //                 }
          //             }
          //           }
          //           .runDrain
          //           .ignore *> ref.get
          //       }
          //     assertZIO(test.exit)(succeeds(equalTo(0)))
          //   }
          // },
          test("emits data if less than n are collected") {
            val gen =
              for {
                data <- Gen.chunkOf(Gen.int)
                n    <- Gen.int.filter(_ > data.length)
              } yield (data, n)

            check(gen) { case (data, n) =>
              val test =
                ZStream
                  .fromChunk(data)
                  .via(ZPipeline.branchAfter(n)(ZPipeline.prepend(_)))
                  .runCollect
              assertZIO(test.exit)(succeeds(equalTo(data)))
            }
          },
          test("applies the new flow once on remaining upstream") {
            ZStream
              .fromIterable(1 to 5)
              .rechunk(2)
              .via(ZPipeline.branchAfter(1) { chunk0 =>
                ZPipeline.prepend(chunk0)
              })
              .runCollect
              .map { res =>
                zio.test.assert(res)(equalTo(Chunk.fromIterable(1 to 5)))
              }
          }
        ),
        suite("broadcast")(
          test("Values") {
            ZIO.scoped {
              ZStream
                .range(0, 5)
                .broadcast(2, 12)
                .flatMap { streams =>
                  for {
                    out1    <- streams(0).runCollect
                    out2    <- streams(1).runCollect
                    expected = Chunk.fromIterable(Range(0, 5))
                  } yield assert(out1)(equalTo(expected)) && assert(out2)(equalTo(expected))
                }
            }
          },
          test("Errors") {
            ZIO.scoped {
              (ZStream.range(0, 1) ++ ZStream.fail("Boom")).broadcast(2, 12).flatMap { streams =>
                for {
                  out1    <- streams(0).runCollect.either
                  out2    <- streams(1).runCollect.either
                  expected = Left("Boom")
                } yield assert(out1)(equalTo(expected)) && assert(out2)(equalTo(expected))
              }
            }
          },
          test("BackPressure") {
            ZIO.scoped {
              ZStream
                .range(0, 5)
                .flatMap(ZStream.succeed(_))
                .broadcast(2, 2)
                .flatMap { streams =>
                  for {
                    ref    <- Ref.make[List[Int]](Nil)
                    latch1 <- Promise.make[Nothing, Unit]
                    fib <- streams(0)
                             .tap(i => ref.update(i :: _) *> latch1.succeed(()).when(i == 1))
                             .runDrain
                             .fork
                    _         <- latch1.await
                    snapshot1 <- ref.get
                    _         <- streams(1).runDrain
                    _         <- fib.await
                    snapshot2 <- ref.get
                  } yield assert(snapshot1)(equalTo(List(1, 0))) && assert(snapshot2)(
                    equalTo(Range(0, 5).toList.reverse)
                  )
                }
            }
          },
          test("Unsubscribe") {
            ZIO.scoped {
              ZStream.range(0, 5).broadcast(2, 2).flatMap { streams =>
                for {
                  _    <- ZIO.scoped(streams(0).toPull.ignore)
                  out2 <- streams(1).runCollect
                } yield assert(out2)(equalTo(Chunk.fromIterable(Range(0, 5))))
              }
            }
          }
        ),
        suite("buffer")(
          test("maintains elements and ordering")(check(tinyChunkOf(tinyChunkOf(Gen.int))) { chunk =>
            assertZIO(
              ZStream
                .fromChunks(chunk: _*)
                .buffer(2)
                .runCollect
            )(equalTo(chunk.flatten))
          }) @@ flaky,
          test("buffer the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO(
              (ZStream.range(0, 10) ++ ZStream.fail(e))
                .buffer(2)
                .runCollect
                .exit
            )(fails(equalTo(e)))
          },
          test("fast producer progress independently") {
            for {
              ref   <- Ref.make(List[Int]())
              latch <- Promise.make[Nothing, Unit]
              s = ZStream
                    .range(1, 5)
                    .tap(i => ref.update(i :: _) *> latch.succeed(()).when(i == 4))
                    .buffer(2)
              l1 <- s.take(2).runScoped(ZSink.collectAll)
              _  <- latch.await
              l2 <- ref.get
            } yield assert(l1.toList)(equalTo((1 to 2).toList)) && assert(l2.reverse)(equalTo((1 to 4).toList))
          }
        ),
        suite("bufferChunks")(
          test("maintains elements and ordering")(check(tinyChunkOf(tinyChunkOf(Gen.int))) { chunk =>
            assertZIO(
              ZStream
                .fromChunks(chunk: _*)
                .bufferChunks(2)
                .runCollect
            )(equalTo(chunk.flatten))
          }),
          test("bufferChunks the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO(
              (ZStream.range(0, 10) ++ ZStream.fail(e))
                .bufferChunks(2)
                .runCollect
                .exit
            )(fails(equalTo(e)))
          },
          test("fast producer progress independently") {
            for {
              ref   <- Ref.make(List[Int]())
              latch <- Promise.make[Nothing, Unit]
              s = ZStream
                    .range(1, 5)
                    .tap(i => ref.update(i :: _) *> latch.succeed(()).when(i == 4))
                    .bufferChunks(2)
              l1 <- s.take(2).runScoped(ZSink.collectAll)
              _  <- latch.await
              l2 <- ref.get
            } yield assert(l1.toList)(equalTo((1 to 2).toList)) && assert(l2.reverse)(equalTo((1 to 4).toList))
          } @@ exceptJS(nonFlaky),
          test("preservers order of elements") {
            val expected = Chunk.fromIterable(0 until 100)
            ZStream
              .fromChunk(expected)
              .buffer(16)
              .runCollect
              .map(v => assertTrue(v == expected))
          } @@ exceptJS(nonFlaky(1000))
        ),
        suite("bufferChunksDropping")(
          test("buffer the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO(
              (ZStream.range(1, 1000) ++ ZStream.fail(e) ++ ZStream.range(1001, 2000))
                .bufferChunksDropping(2)
                .runCollect
                .exit
            )(fails(equalTo(e)))
          },
          test("fast producer progress independently") {
            for {
              ref    <- Ref.make(List.empty[Int])
              latch1 <- Promise.make[Nothing, Unit]
              latch2 <- Promise.make[Nothing, Unit]
              latch3 <- Promise.make[Nothing, Unit]
              latch4 <- Promise.make[Nothing, Unit]
              s1 = ZStream(0) ++ ZStream
                     .fromZIO(latch1.await)
                     .flatMap(_ => ZStream.range(1, 17).rechunk(1).ensuring(latch2.succeed(())))
              s2 = ZStream
                     .fromZIO(latch3.await)
                     .flatMap(_ => ZStream.range(17, 25).rechunk(1).ensuring(latch4.succeed(())))
              s3 = ZStream(-1)
              s  = (s1 ++ s2 ++ s3).bufferChunksDropping(8)
              snapshots <- ZIO.scoped {
                             s.toPull.flatMap { as =>
                               for {
                                 zero      <- as
                                 _         <- latch1.succeed(())
                                 _         <- latch2.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot1 <- ref.get
                                 _         <- latch3.succeed(())
                                 _         <- latch4.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot2 <- ref.get
                               } yield (zero, snapshot1, snapshot2)
                             }
                           }
            } yield assert(snapshots._1)(equalTo(Chunk.single(0))) && assert(snapshots._2)(
              equalTo(List(8, 7, 6, 5, 4, 3, 2, 1))
            ) &&
              assert(snapshots._3)(
                equalTo(List(24, 23, 22, 21, 20, 19, 18, 17, 8, 7, 6, 5, 4, 3, 2, 1))
              )
          }
        ),
        suite("bufferChunksSliding")(
          test("buffer the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO(
              (ZStream.range(1, 1000) ++ ZStream.fail(e) ++ ZStream.range(1001, 2000))
                .bufferChunksSliding(2)
                .runCollect
                .exit
            )(fails(equalTo(e)))
          },
          test("fast producer progress independently") {
            for {
              ref    <- Ref.make(List.empty[Int])
              latch1 <- Promise.make[Nothing, Unit]
              latch2 <- Promise.make[Nothing, Unit]
              latch3 <- Promise.make[Nothing, Unit]
              latch4 <- Promise.make[Nothing, Unit]
              latch5 <- Promise.make[Nothing, Unit]
              s1 = ZStream(0) ++ ZStream
                     .fromZIO(latch1.await)
                     .flatMap(_ => ZStream.range(1, 17).rechunk(1).ensuring(latch2.succeed(())))
              s2 = ZStream
                     .fromZIO(latch3.await)
                     .flatMap(_ => ZStream.range(17, 26).rechunk(1).ensuring(latch4.succeed(())))
              s3 = ZStream
                     .fromZIO(latch5.await)
                     .flatMap(_ => ZStream(-1))
              s = (s1 ++ s2 ++ s3).bufferChunksSliding(8)
              snapshots <- ZIO.scoped {
                             s.toPull.flatMap { as =>
                               for {
                                 zero      <- as
                                 _         <- latch1.succeed(())
                                 _         <- latch2.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot1 <- ref.get
                                 _         <- latch3.succeed(())
                                 _         <- latch4.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot2 <- ref.get
                               } yield (zero, snapshot1, snapshot2)
                             }
                           }
            } yield assert(snapshots._1)(equalTo(Chunk.single(0))) && assert(snapshots._2)(
              equalTo(List(16, 15, 14, 13, 12, 11, 10, 9))
            ) &&
              assert(snapshots._3)(
                equalTo(List(25, 24, 23, 22, 21, 20, 19, 18, 16, 15, 14, 13, 12, 11, 10, 9))
              )
          }
        ),
        suite("bufferDropping")(
          test("buffer the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO(
              (ZStream.range(1, 1000) ++ ZStream.fail(e) ++ ZStream.range(1001, 2000))
                .bufferDropping(2)
                .runCollect
                .exit
            )(fails(equalTo(e)))
          },
          test("fast producer progress independently") {
            for {
              ref    <- Ref.make(List.empty[Int])
              latch1 <- Promise.make[Nothing, Unit]
              latch2 <- Promise.make[Nothing, Unit]
              latch3 <- Promise.make[Nothing, Unit]
              latch4 <- Promise.make[Nothing, Unit]
              s1 = ZStream(0) ++ ZStream
                     .fromZIO(latch1.await)
                     .flatMap(_ => ZStream.range(1, 17).ensuring(latch2.succeed(())))
              s2 = ZStream
                     .fromZIO(latch3.await)
                     .flatMap(_ => ZStream.range(17, 25).ensuring(latch4.succeed(())))
              s3 = ZStream(-1)
              s  = (s1 ++ s2 ++ s3).bufferDropping(8)
              snapshots <- ZIO.scoped {
                             s.toPull.flatMap { as =>
                               for {
                                 zero      <- as
                                 _         <- latch1.succeed(())
                                 _         <- latch2.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot1 <- ref.get
                                 _         <- latch3.succeed(())
                                 _         <- latch4.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot2 <- ref.get
                               } yield (zero, snapshot1, snapshot2)
                             }
                           }
            } yield assert(snapshots._1)(equalTo(Chunk.single(0))) && assert(snapshots._2)(
              equalTo(List(8, 7, 6, 5, 4, 3, 2, 1))
            ) &&
              assert(snapshots._3)(
                equalTo(List(24, 23, 22, 21, 20, 19, 18, 17, 8, 7, 6, 5, 4, 3, 2, 1))
              )
          }
        ),
        suite("range")(
          test("range includes min value and excludes max value") {
            assertZIO(
              (ZStream.range(1, 2)).runCollect
            )(equalTo(Chunk(1)))
          },
          test("two large ranges can be concatenated") {
            assertZIO(
              (ZStream.range(1, 1000) ++ ZStream.range(1000, 2000)).runCollect
            )(equalTo(Chunk.fromIterable(Range(1, 2000))))
          },
          test("two small ranges can be concatenated") {
            assertZIO(
              (ZStream.range(1, 10) ++ ZStream.range(10, 20)).runCollect
            )(equalTo(Chunk.fromIterable(Range(1, 20))))
          },
          test("range emits no values when start >= end") {
            assertZIO(
              (ZStream.range(1, 1) ++ ZStream.range(2, 1)).runCollect
            )(equalTo(Chunk.empty))
          },
          test("range emits values in chunks of chunkSize") {
            assertZIO(
              (ZStream
                .range(1, 10, 2))
                .mapChunks(c => Chunk[Int](c.sum))
                .runCollect
            )(equalTo(Chunk(1 + 2, 3 + 4, 5 + 6, 7 + 8, 9)))
          }
        ),
        suite("bufferSliding")(
          test("buffer the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO(
              (ZStream.range(1, 1000) ++ ZStream.fail(e) ++ ZStream.range(1001, 2000))
                .bufferSliding(2)
                .runCollect
                .exit
            )(fails(equalTo(e)))
          },
          test("fast producer progress independently") {
            for {
              ref    <- Ref.make(List.empty[Int])
              latch1 <- Promise.make[Nothing, Unit]
              latch2 <- Promise.make[Nothing, Unit]
              latch3 <- Promise.make[Nothing, Unit]
              latch4 <- Promise.make[Nothing, Unit]
              latch5 <- Promise.make[Nothing, Unit]
              s1 = ZStream(0) ++ ZStream
                     .fromZIO(latch1.await)
                     .flatMap(_ => ZStream.range(1, 17).ensuring(latch2.succeed(())))
              s2 = ZStream
                     .fromZIO(latch3.await)
                     .flatMap(_ => ZStream.range(17, 26).ensuring(latch4.succeed(())))
              s3 = ZStream
                     .fromZIO(latch5.await)
                     .flatMap(_ => ZStream(-1))
              s = (s1 ++ s2 ++ s3).bufferSliding(8)
              snapshots <- ZIO.scoped {
                             s.toPull.flatMap { as =>
                               for {
                                 zero      <- as
                                 _         <- latch1.succeed(())
                                 _         <- latch2.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot1 <- ref.get
                                 _         <- latch3.succeed(())
                                 _         <- latch4.await
                                 _         <- as.flatMap(a => ref.update(a.toList ::: _)).repeatN(7)
                                 snapshot2 <- ref.get
                               } yield (zero, snapshot1, snapshot2)
                             }
                           }
            } yield assert(snapshots._1)(equalTo(Chunk.single(0))) && assert(snapshots._2)(
              equalTo(List(16, 15, 14, 13, 12, 11, 10, 9))
            ) &&
              assert(snapshots._3)(
                equalTo(List(25, 24, 23, 22, 21, 20, 19, 18, 16, 15, 14, 13, 12, 11, 10, 9))
              )
          } @@ exceptJS(nonFlaky),
          test("propagates defects") {
            for {
              exit <- ZStream
                        .fromZIO(ZIO.dieMessage("die"))
                        .bufferSliding(1)
                        .runDrain
                        .exit
            } yield assert(exit)(dies(hasMessage(equalTo("die"))))
          }
        ),
        suite("bufferUnbounded")(
          test("buffer the Stream")(check(Gen.chunkOf(Gen.int)) { chunk =>
            assertZIO(
              ZStream
                .fromIterable(chunk)
                .bufferUnbounded
                .runCollect
            )(equalTo(chunk))
          }),
          test("buffer the Stream with Error") {
            val e = new RuntimeException("boom")
            assertZIO((ZStream.range(0, 10) ++ ZStream.fail(e)).bufferUnbounded.runCollect.exit)(
              fails(equalTo(e))
            )
          },
          test("fast producer progress independently") {
            for {
              ref   <- Ref.make(List[Int]())
              latch <- Promise.make[Nothing, Unit]
              s = ZStream
                    .range(1, 1000)
                    .tap(i => ref.update(i :: _) *> latch.succeed(()).when(i == 999))
                    .rechunk(1000)
                    .bufferUnbounded
              l1 <- s.take(2).runCollect
              _  <- latch.await
              l2 <- ref.get
            } yield assert(l1.toList)(equalTo((1 to 2).toList)) && assert(l2.reverse)(equalTo((1 until 1000).toList))
          }
        ),
        suite("catchAllCause")(
          test("recovery from errors") {
            val s1 = ZStream(1, 2) ++ ZStream.fail("Boom")
            val s2 = ZStream(3, 4)
            s1.catchAllCause(_ => s2).runCollect.map(assert(_)(equalTo(Chunk(1, 2, 3, 4))))
          },
          test("recovery from defects") {
            val s1 = ZStream(1, 2) ++ ZStream.dieMessage("Boom")
            val s2 = ZStream(3, 4)
            s1.catchAllCause(_ => s2).runCollect.map(assert(_)(equalTo(Chunk(1, 2, 3, 4))))
          },
          test("happy path") {
            val s1 = ZStream(1, 2)
            val s2 = ZStream(3, 4)
            s1.catchAllCause(_ => s2).runCollect.map(assert(_)(equalTo(Chunk(1, 2))))
          },
          test("executes finalizers") {
            for {
              fins   <- Ref.make(List[String]())
              s1      = (ZStream(1, 2) ++ ZStream.fail("Boom")).ensuring(fins.update("s1" :: _))
              s2      = (ZStream(3, 4) ++ ZStream.fail("Boom")).ensuring(fins.update("s2" :: _))
              _      <- s1.catchAllCause(_ => s2).runCollect.exit
              result <- fins.get
            } yield assert(result)(equalTo(List("s2", "s1")))
          },
          test("releases all resources by the time the failover stream has started") {
            for {
              fins <- Ref.make(Chunk[Int]())
              s = ZStream.finalizer(fins.update(1 +: _)) *>
                    ZStream.finalizer(fins.update(2 +: _)) *>
                    ZStream.finalizer(fins.update(3 +: _)) *>
                    ZStream.fail("boom")
              result <- s.drain.catchAllCause(_ => ZStream.fromZIO(fins.get)).runCollect
            } yield assert(result.flatten)(equalTo(Chunk(1, 2, 3)))
          },
          test("propagates the right Exit value to the failing stream (#3609)") {
            for {
              ref <- Ref.make[Exit[Any, Any]](Exit.unit)
              _ <- ZStream
                     .acquireReleaseExitWith(ZIO.unit)((_, exit) => ref.set(exit))
                     .flatMap(_ => ZStream.fail("boom"))
                     .either
                     .runDrain
                     .exit
              result <- ref.get
            } yield assert(result)(fails(equalTo("boom")))
          }
        ),
        suite("catchSome")(
          test("recovery from some errors") {
            val s1 = ZStream(1, 2) ++ ZStream.fail("Boom")
            val s2 = ZStream(3, 4)
            s1.catchSome { case "Boom" => s2 }.runCollect.map(assert(_)(equalTo(Chunk(1, 2, 3, 4))))
          },
          test("fails stream when partial function does not match") {
            val s1 = ZStream(1, 2) ++ ZStream.fail("Boom")
            val s2 = ZStream(3, 4)
            s1.catchSome { case "Boomer" => s2 }.runCollect.either
              .map(assert(_)(isLeft(equalTo("Boom"))))
          }
        ),
        suite("catchSomeCause")(
          test("recovery from some errors") {
            val s1 = ZStream(1, 2) ++ ZStream.failCause(Cause.fail("Boom"))
            val s2 = ZStream(3, 4)
            s1.catchSomeCause { case Cause.Fail("Boom", _) => s2 }.runCollect
              .map(assert(_)(equalTo(Chunk(1, 2, 3, 4))))
          },
          test("halts stream when partial function does not match") {
            val s1 = ZStream(1, 2) ++ ZStream.fail("Boom")
            val s2 = ZStream(3, 4)
            s1.catchSomeCause { case Cause.empty => s2 }.runCollect.either
              .map(assert(_)(isLeft(equalTo("Boom"))))
          }
        ),
        test("collect") {
          assertZIO(ZStream(Left(1), Right(2), Left(3)).collect { case Right(n) =>
            n
          }.runCollect)(equalTo(Chunk(2)))
        },
        test("changes") {
          check(pureStreamOfInts) { stream =>
            for {
              actual <- stream.changes.runCollect.map(_.toList)
              expected <- stream.runCollect.map { as =>
                            as.foldLeft[List[Int]](Nil) { (s, n) =>
                              if (s.isEmpty || s.head != n) n :: s else s
                            }.reverse
                          }
            } yield assert(actual)(equalTo(expected))
          }
        },
        test("changesWithZIO") {
          check(pureStreamOfInts) { stream =>
            for {
              actual <- stream.changesWithZIO((l, r) => ZIO.succeed(l == r)).runCollect.map(_.toList)
              expected <- stream.runCollect.map { as =>
                            as.foldLeft[List[Int]](Nil) { (s, n) =>
                              if (s.isEmpty || s.head != n) n :: s else s
                            }.reverse
                          }
            } yield assert(actual)(equalTo(expected))
          }
        },
        suite("collectZIO")(
          test("collectZIO") {
            assertZIO(
              ZStream(Left(1), Right(2), Left(3)).collectZIO { case Right(n) =>
                ZIO.succeed(n * 2)
              }.runCollect
            )(equalTo(Chunk(4)))
          },
          test("collectZIO on multiple Chunks") {
            assertZIO(
              ZStream
                .fromChunks(Chunk(Left(1), Right(2)), Chunk(Right(3), Left(4)))
                .collectZIO { case Right(n) =>
                  ZIO.succeed(n * 10)
                }
                .runCollect
            )(equalTo(Chunk(20, 30)))
          },
          test("collectZIO fails") {
            assertZIO(
              ZStream(Left(1), Right(2), Left(3)).collectZIO { case Right(_) =>
                ZIO.fail("Ouch")
              }.runDrain.either
            )(isLeft(isNonEmptyString))
          },
          test("laziness on chunks") {
            assertZIO(
              ZStream(1, 2, 3).collectZIO {
                case 3 => ZIO.fail("boom")
                case x => ZIO.succeed(x)
              }.either.runCollect
            )(equalTo(Chunk(Right(1), Right(2), Left("boom"))))
          }
        ),
        test("collectSome")(check(Gen.bounded(0, 5)(pureStreamGen(Gen.option(Gen.int), _))) { s =>
          for {
            res1 <- (s.collectSome.runCollect)
            res2 <- (s.runCollect.map(_.collect { case Some(x) => x }))
          } yield assert(res1)(equalTo(res2))
        }),
        suite("collectWhile")(
          test("collectWhile") {
            assertZIO(ZStream(Some(1), Some(2), Some(3), None, Some(4)).collectWhile { case Some(v) =>
              v
            }.runCollect)(equalTo(Chunk(1, 2, 3)))
          },
          test("collectWhile short circuits") {
            assertZIO(
              (ZStream(Option(1)) ++ ZStream.fail("Ouch")).collectWhile { case None =>
                1
              }.runDrain.either
            )(isRight(isUnit))
          }
        ),
        suite("collectWhileZIO")(
          test("collectWhileZIO") {
            assertZIO(
              ZStream(Some(1), Some(2), Some(3), None, Some(4)).collectWhileZIO { case Some(v) =>
                ZIO.succeed(v * 2)
              }.runCollect
            )(equalTo(Chunk(2, 4, 6)))
          },
          test("collectWhileZIO short circuits") {
            assertZIO(
              (ZStream(Option(1)) ++ ZStream.fail("Ouch"))
                .collectWhileZIO[Any, String, Int] { case None =>
                  ZIO.succeed(1)
                }
                .runDrain
                .either
            )(isRight(isUnit))
          },
          test("collectWhileZIO fails") {
            assertZIO(
              ZStream(Some(1), Some(2), Some(3), None, Some(4)).collectWhileZIO { case Some(_) =>
                ZIO.fail("Ouch")
              }.runDrain.either
            )(isLeft(isNonEmptyString))
          },
          test("laziness on chunks") {
            assertZIO(
              ZStream(1, 2, 3, 4).collectWhileZIO {
                case 3 => ZIO.fail("boom")
                case x => ZIO.succeed(x)
              }.either.runCollect
            )(equalTo(Chunk(Right(1), Right(2), Left("boom"))))
          }
        ),
        suite("concat")(
          test("concat")(check(streamOfInts, streamOfInts) { (s1, s2) =>
            for {
              chunkConcat  <- s1.runCollect.zipWith(s2.runCollect)(_ ++ _).exit
              streamConcat <- (s1 ++ s2).runCollect.exit
            } yield assert(streamConcat.isSuccess && chunkConcat.isSuccess)(isTrue) implies assert(
              streamConcat
            )(
              equalTo(chunkConcat)
            )
          }),
          test("finalizer order") {
            for {
              log <- Ref.make[List[String]](Nil)
              _ <- (ZStream.finalizer(log.update("Second" :: _)) ++ ZStream
                     .finalizer(log.update("First" :: _))).runDrain
              execution <- log.get
            } yield assert(execution)(equalTo(List("First", "Second")))
          }
        ),
        suite("distributedWithDynamic")(
          test("ensures no race between subscription and stream end") {
            ZIO.scoped {
              ZStream.empty.distributedWithDynamic(1, _ => ZIO.succeed(_ => true)).flatMap { add =>
                val subscribe = ZStream.unwrap(add.map { case (_, queue) =>
                  ZStream.fromQueue(queue).collectWhileSuccess
                })
                Promise.make[Nothing, Unit].flatMap { onEnd =>
                  subscribe.ensuring(onEnd.succeed(())).runDrain.fork *>
                    onEnd.await *>
                    subscribe.runDrain *>
                    ZIO.succeed(assertCompletes)
                }
              }
            }
          }
        ),
        suite("drain")(
          test("drain")(
            for {
              ref <- Ref.make(List[Int]())
              _   <- ZStream.range(0, 10).mapZIO(i => ref.update(i :: _)).drain.runDrain
              l   <- ref.get
            } yield assert(l.reverse)(equalTo(Range(0, 10).toList))
          ),
          test("isn't too eager") {
            for {
              ref    <- Ref.make[Int](0)
              res    <- (ZStream(1).tap(ref.set) ++ ZStream.fail("fail")).runDrain.either
              refRes <- ref.get
            } yield assert(refRes)(equalTo(1)) && assert(res)(isLeft(equalTo("fail")))
          }
        ),
        suite("drainFork")(
          test("runs the other stream in the background") {
            for {
              latch <- Promise.make[Nothing, Unit]
              _ <- ZStream
                     .fromZIO(latch.await)
                     .drainFork(ZStream.fromZIO(latch.succeed(())))
                     .runDrain
            } yield assertCompletes
          },
          test("interrupts the background stream when the foreground exits") {
            for {
              bgInterrupted <- Ref.make(false)
              latch         <- Promise.make[Nothing, Unit]
              _ <- (ZStream(1, 2, 3) ++ ZStream.fromZIO(latch.await).drain)
                     .drainFork(
                       ZStream.fromZIO(
                         (latch.succeed(()) *> ZIO.never).onInterrupt(bgInterrupted.set(true))
                       )
                     )
                     .runDrain
              result <- bgInterrupted.get
            } yield assert(result)(isTrue)
          } @@ zioTag(interruption),
          test("fails the foreground stream if the background fails with a typed error") {
            assertZIO(ZStream.never.drainFork(ZStream.fail("Boom")).runDrain.exit)(
              fails(equalTo("Boom"))
            )
          } @@ zioTag(errors),
          test("fails the foreground stream if the background fails with a defect") {
            val ex = new RuntimeException("Boom")
            assertZIO(ZStream.never.drainFork(ZStream.die(ex)).runDrain.exit)(dies(equalTo(ex)))
          } @@ zioTag(errors)
        ),
        suite("drop")(
          test("drop")(check(streamOfInts, Gen.int) { (s, n) =>
            for {
              dropStreamResult <- s.drop(n).runCollect.exit
              dropListResult   <- s.runCollect.map(_.drop(n)).exit
            } yield assert(dropListResult.isSuccess)(isTrue) implies assert(dropStreamResult)(
              equalTo(dropListResult)
            )
          }),
          test("doesn't swallow errors")(
            assertZIO(
              (ZStream.fail("Ouch") ++ ZStream(1))
                .drop(1)
                .runDrain
                .either
            )(isLeft(equalTo("Ouch")))
          )
        ),
        suite("dropRight")(
          test("dropRight")(check(streamOfInts, Gen.int(0, 1000)) { (s, n) =>
            for {
              dropStreamResult <- s.dropRight(n).runCollect.exit
              dropListResult   <- s.runCollect.map(_.dropRight(n)).exit
            } yield assert(dropListResult.isSuccess)(isTrue) implies assert(dropStreamResult)(
              equalTo(dropListResult)
            )
          }),
          test("doesn't swallow errors")(
            assertZIO(
              (ZStream(1) ++ ZStream.fail("Ouch"))
                .drop(1)
                .runDrain
                .either
            )(isLeft(equalTo("Ouch")))
          )
        ),
        test("dropUntil") {
          check(pureStreamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
            for {
              res1 <- s.dropUntil(p).runCollect
              res2 <- s.runCollect.map(_.dropWhile(!p(_)).drop(1))
            } yield assert(res1)(equalTo(res2))
          }
        },
        suite("dropWhile")(
          test("dropWhile")(
            check(pureStreamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
              for {
                res1 <- s.dropWhile(p).runCollect
                res2 <- s.runCollect.map(_.dropWhile(p))
              } yield assert(res1)(equalTo(res2))
            }
          ),
          test("short circuits") {
            assertZIO(
              (ZStream(1) ++ ZStream.fail("Ouch"))
                .take(1)
                .dropWhile(_ => true)
                .runDrain
                .either
            )(isRight(isUnit))
          }
        ),
        test("either") {
          val s = ZStream(1, 2, 3) ++ ZStream.fail("Boom")
          s.either.runCollect
            .map(assert(_)(equalTo(Chunk(Right(1), Right(2), Right(3), Left("Boom")))))
        },
        test("ensuring") {
          for {
            log <- Ref.make[List[String]](Nil)
            _ <- (for {
                   _ <- ZStream.acquireReleaseWith(log.update("Acquire" :: _))(_ => log.update("Release" :: _))
                   _ <- ZStream.fromZIO(log.update("Use" :: _))
                 } yield ()).ensuring(log.update("Ensuring" :: _)).runDrain
            execution <- log.get
          } yield assert(execution)(equalTo(List("Ensuring", "Release", "Use", "Acquire")))
        },
        test("environmentWith") {
          for {
            result <- ZStream.service[String].provideEnvironment(ZEnvironment("test")).runHead.some
          } yield assert(result)(equalTo("test"))
        },
        suite("environmentWithZIO")(
          test("environmentWithZIO") {
            for {
              result <- ZStream
                          .environmentWithZIO[String](environment => ZIO.succeed(environment.get))
                          .provideEnvironment(ZEnvironment("test"))
                          .runHead
                          .some
            } yield assert(result)(equalTo("test"))
          },
          test("environmentWithZIO fails") {
            for {
              result <-
                ZStream.environmentWithZIO[Int](_ => ZIO.fail("fail")).provideEnvironment(ZEnvironment(0)).runHead.exit
            } yield assert(result)(fails(equalTo("fail")))
          } @@ zioTag(errors)
        ),
        suite("environmentWithStream")(
          test("environmentWithStream") {
            for {
              result <- ZStream
                          .environmentWithStream[String](environment => ZStream.succeed(environment.get))
                          .provideEnvironment(ZEnvironment("test"))
                          .runHead
                          .some
            } yield assert(result)(equalTo("test"))
          },
          test("environmentWithStream fails") {
            for {
              result <-
                ZStream
                  .environmentWithStream[Int](_ => ZStream.fail("fail"))
                  .provideEnvironment(ZEnvironment(0))
                  .runHead
                  .exit
            } yield assert(result)(fails(equalTo("fail")))
          } @@ zioTag(errors)
        ),
        test("execute") {
          for {
            ref <- Ref.make(List[Int]())
            _   <- ZStream.execute(ref.set(List(1))).runDrain
            l   <- ref.get
          } yield assert(l)(equalTo(List(1)))
        },
        test("filter")(check(pureStreamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
          for {
            res1 <- s.filter(p).runCollect
            res2 <- s.runCollect.map(_.filter(p))
          } yield assert(res1)(equalTo(res2))
        }),
        suite("filterZIO")(
          test("filterZIO")(check(pureStreamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
            for {
              res1 <- s.filterZIO(s => ZIO.succeed(p(s))).runCollect
              res2 <- s.runCollect.map(_.filter(p))
            } yield assert(res1)(equalTo(res2))
          }),
          test("laziness on chunks") {
            assertZIO(
              ZStream(1, 2, 3).filterZIO {
                case 3 => ZIO.fail("boom")
                case _ => ZIO.succeed(true)
              }.either.runCollect
            )(equalTo(Chunk(Right(1), Right(2), Left("boom"))))
          }
        ),
        test("find")(check(pureStreamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
          for {
            res1 <- s.find(p).runHead
            res2 <- s.runCollect.map(_.find(p))
          } yield assert(res1)(equalTo(res2))
        }),
        suite("findZIO")(
          test("findZIO")(check(pureStreamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
            for {
              res1 <- s.findZIO(s => ZIO.succeed(p(s))).runHead
              res2 <- s.runCollect.map(_.find(p))
            } yield assert(res1)(equalTo(res2))
          }),
          test("throws correct error") {
            assertZIO(
              ZStream(1, 2, 3).findZIO {
                case 3 => ZIO.fail("boom")
                case _ => ZIO.succeed(false)
              }.either.runCollect
            )(equalTo(Chunk(Left("boom"))))
          }
        ),
        suite("flatMap")(
          test("deep flatMap stack safety") {
            def fib(n: Int): ZStream[Any, Nothing, Int] =
              if (n <= 1) ZStream.succeed(n)
              else
                fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => ZStream.succeed(a + b)))

            val stream   = fib(20)
            val expected = 6765

            assertZIO(stream.runCollect)(equalTo(Chunk(expected)))
          } @@ TestAspect.jvmOnly, // Too slow on Scala.js
          test("left identity")(check(Gen.int, Gen.function(pureStreamOfInts)) { (x, f) =>
            for {
              res1 <- ZStream(x).flatMap(f).runCollect
              res2 <- f(x).runCollect
            } yield assert(res1)(equalTo(res2))
          }),
          test("right identity")(
            check(pureStreamOfInts)(m =>
              for {
                res1 <- m.flatMap(i => ZStream(i)).runCollect
                res2 <- m.runCollect
              } yield assert(res1)(equalTo(res2))
            )
          ),
          test("associativity") {
            val tinyStream = Gen.int(0, 2).flatMap(pureStreamGen(Gen.int, _))
            val fnGen      = Gen.function(tinyStream)
            check(tinyStream, fnGen, fnGen) { (m, f, g) =>
              for {
                leftStream  <- m.flatMap(f).flatMap(g).runCollect
                rightStream <- m.flatMap(x => f(x).flatMap(g)).runCollect
              } yield assert(leftStream)(equalTo(rightStream))
            }
          } @@ TestAspect.jvmOnly, // Too slow on Scala.js
          test("inner finalizers") {
            for {
              effects <- Ref.make(List[Int]())
              push     = (i: Int) => effects.update(i :: _)
              latch   <- Promise.make[Nothing, Unit]
              fiber <- ZStream(
                         ZStream.acquireReleaseWith(push(1))(_ => push(1)),
                         ZStream.fromZIO(push(2)),
                         ZStream.acquireReleaseWith(push(3))(_ => push(3)) *> ZStream.fromZIO(
                           latch.succeed(()) *> ZIO.never
                         )
                       ).flatMap(identity).runDrain.fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- effects.get
            } yield assert(result)(equalTo(List(3, 3, 2, 1, 1)))
          },
          test("finalizer ordering 1") {
            for {
              effects <- Ref.make(List[String]())
              push     = (i: String) => effects.update(i :: _)
              stream = for {
                         _ <- ZStream.acquireReleaseWith(push("open1"))(_ => push("close1"))
                         _ <- ZStream
                                .fromChunks(Chunk(()), Chunk(()))
                                .tap(_ => ZIO.debug("use2") *> push("use2"))
                                .ensuring(push("close2"))
                         _ <- ZStream.acquireReleaseWith(ZIO.debug("open3") *> push("open3"))(_ =>
                                ZIO.debug("close3") *> push("close3")
                              )
                         _ <- ZStream
                                .fromChunks(Chunk(()), Chunk(()))
                                .tap(_ => push("use4"))
                                .ensuring(push("close4"))
                       } yield ()
              _      <- stream.runDrain
              result <- effects.get
            } yield assert(result.reverse)(
              equalTo(
                List(
                  "open1",
                  "use2",
                  "open3",
                  "use4",
                  "use4",
                  "close4",
                  "close3",
                  "use2",
                  "open3",
                  "use4",
                  "use4",
                  "close4",
                  "close3",
                  "close2",
                  "close1"
                )
              )
            )
          },
          test("finalizer ordering 2") {
            for {
              effects <- Ref.make(List[String]())
              push     = (i: String) => effects.update(i :: _)
              stream = for {
                         _ <- ZStream
                                .fromChunks(Chunk(1), Chunk(2))
                                .tap(n => ZIO.debug(s">>> use2 $n") *> push("use2"))
                         _ <- ZStream.acquireReleaseWith(ZIO.debug("open3") *> push("open3"))(_ =>
                                ZIO.debug("close3") *> push("close3")
                              )
                       } yield ()
              _      <- stream.runDrain
              result <- effects.get
            } yield assert(result.reverse)(
              equalTo(
                List(
                  "use2",
                  "open3",
                  "close3",
                  "use2",
                  "open3",
                  "close3"
                )
              )
            )
          },
          test("exit signal") {
            for {
              ref <- Ref.make(false)
              inner = ZStream
                        .acquireReleaseExitWith(ZIO.unit)((_, e) =>
                          e match {
                            case Exit.Failure(_) => ref.set(true)
                            case Exit.Success(_) => ZIO.unit
                          }
                        )
                        .flatMap(_ => ZStream.fail("Ouch"))
              _   <- ZStream.succeed(()).flatMap(_ => inner).runDrain.either.unit
              fin <- ref.get
            } yield assert(fin)(isTrue)
          },
          test("finalizers are registered in the proper order") {
            for {
              fins <- Ref.make(List[Int]())
              s = ZStream.finalizer(fins.update(1 :: _)) *>
                    ZStream.finalizer(fins.update(2 :: _))
              _      <- ZIO.scoped[Any](s.toPull.flatten)
              result <- fins.get
            } yield assert(result)(equalTo(List(1, 2)))
          },
          test("early release finalizer concatenation is preserved") {
            for {
              fins <- Ref.make(List[Int]())
              s = ZStream.finalizer(fins.update(1 :: _)) *>
                    ZStream.finalizer(fins.update(2 :: _))
              result <- Scope.make.flatMap { scope =>
                          scope.extend(s.toPull).flatMap { pull =>
                            pull *> scope.close(Exit.unit) *> fins.get
                          }
                        }
            } yield assert(result)(equalTo(List(1, 2)))
          }
        ),
        suite("flatMapPar")(
          test("guarantee ordering")(check(tinyListOf(Gen.int)) { (m: List[Int]) =>
            for {
              flatMap    <- ZStream.fromIterable(m).flatMap(i => ZStream(i, i)).runCollect
              flatMapPar <- ZStream.fromIterable(m).flatMapPar(1)(i => ZStream(i, i)).runCollect
            } yield assert(flatMap)(equalTo(flatMapPar))
          }),
          test("consistent with flatMap")(
            check(Gen.int(1, 10000), tinyListOf(Gen.int)) { (n, m) =>
              for {
                flatMap <- ZStream
                             .fromIterable(m)
                             .flatMap(i => ZStream(i, i))
                             .runCollect
                             .map(_.toSet)
                flatMapPar <- ZStream
                                .fromIterable(m)
                                .flatMapPar(n)(i => ZStream(i, i))
                                .runCollect
                                .map(_.toSet)
              } yield assert(n)(isGreaterThan(0)) implies assert(flatMap)(equalTo(flatMapPar))
            }
          ),
          test("short circuiting") {
            assertZIO(
              ZStream
                .mergeAll(2)(
                  ZStream.never,
                  ZStream(1)
                )
                .take(1)
                .runCollect
            )(equalTo(Chunk(1)))
          },
          test("interruption propagation") {
            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              fiber <- ZStream(())
                         .flatMapPar(1)(_ =>
                           ZStream.fromZIO(
                             (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                           )
                         )
                         .runDrain
                         .fork
              _         <- latch.await
              _         <- fiber.interrupt
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue)
          },
          test("inner errors interrupt all fibers") {
            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- ZStream(
                          ZStream.fromZIO(
                            (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                          ),
                          ZStream.fromZIO(latch.await *> ZIO.fail("Ouch"))
                        ).flatMapPar(2)(identity).runDrain.either
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(isLeft(equalTo("Ouch")))
          } @@ exceptJS(nonFlaky),
          test("outer errors interrupt all fibers") {
            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- (ZStream(()) ++ ZStream.fromZIO(latch.await *> ZIO.fail("Ouch")))
                          .flatMapPar(2) { _ =>
                            ZStream.fromZIO(
                              (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                            )
                          }
                          .runDrain
                          .either
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(isLeft(equalTo("Ouch")))
          } @@ exceptJS(nonFlaky),
          test("inner defects interrupt all fibers") {
            val ex = new RuntimeException("Ouch")

            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- ZStream(
                          ZStream.fromZIO(
                            (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                          ),
                          ZStream.fromZIO(latch.await *> ZIO.die(ex))
                        ).flatMapPar(2)(identity).runDrain.exit
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(dies(equalTo(ex)))
          },
          test("outer defects interrupt all fibers") {
            val ex = new RuntimeException()

            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- (ZStream(()) ++ ZStream.fromZIO(latch.await *> ZIO.die(ex)))
                          .flatMapPar(2) { _ =>
                            ZStream.fromZIO(
                              (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                            )
                          }
                          .runDrain
                          .exit
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(dies(equalTo(ex)))
          } @@ exceptJS(nonFlaky),
          test("finalizer ordering") {
            for {
              execution <- Ref.make[List[String]](Nil)
              inner =
                ZStream
                  .acquireReleaseWith(execution.update("InnerAcquire" :: _))(_ => execution.update("InnerRelease" :: _))
              _ <-
                ZStream
                  .acquireReleaseWith(execution.update("OuterAcquire" :: _).as(inner))(_ =>
                    execution.update("OuterRelease" :: _)
                  )
                  .flatMapPar(2)(identity)
                  .runDrain
              results <- execution.get
            } yield assert(results)(
              equalTo(List("OuterRelease", "InnerRelease", "InnerAcquire", "OuterAcquire"))
            )
          } @@ exceptJS(nonFlaky),
          test("preserves the scope") {
            for {
              ref   <- Ref.make[Chunk[String]](Chunk.empty)
              scope <- Scope.make
              _ <- scope.extend {
                     ZStream(1, 2)
                       .flatMapPar(1) { i =>
                         ZStream.fromZIO {
                           ZIO.acquireRelease {
                             ref.update(_ :+ s"acquire $i")
                           } { _ =>
                             ref.update(_ :+ s"release $i")
                           }
                         }
                       }
                       .runDrain
                   }
              before <- ref.getAndSet(Chunk.empty)
              _      <- scope.close(Exit.unit)
              after  <- ref.get
            } yield assertTrue(before == Chunk("acquire 1", "acquire 2")) &&
              assertTrue(after == Chunk("release 2", "release 1"))
          },
          test("interruption propagation") {
            val stream = ZStream(1, 2, 3, 4, 5).flatMapPar(2)(_ => ZStream.fromZIO(ZIO.interrupt))
            for {
              exit <- stream.runDrain.exit
            } yield assertTrue(exit.isInterrupted)
          }
        ),
        suite("flatMapParSwitch")(
          test("guarantee ordering no parallelism") {
            for {
              lastExecuted <- Ref.make(false)
              semaphore    <- Semaphore.make(1)
              _ <- ZStream(1, 2, 3, 4)
                     .flatMapParSwitch(1) { i =>
                       if (i > 3)
                         ZStream
                           .acquireReleaseWith(ZIO.unit)(_ => lastExecuted.set(true))
                           .flatMap(_ => ZStream.empty)
                       else ZStream.scoped(semaphore.withPermitScoped).flatMap(_ => ZStream.never)
                     }
                     .runDrain
              result <- semaphore.withPermit(lastExecuted.get)
            } yield assert(result)(isTrue)
          },
          test("guarantee ordering with parallelism") {
            for {
              lastExecuted <- Ref.make(0)
              promise      <- Promise.make[Nothing, Unit]
              semaphore    <- Semaphore.make(4)
              _ <- ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
                     .flatMapParSwitch(4) { i =>
                       if (i > 8)
                         ZStream
                           .acquireReleaseWith(ZIO.unit)(_ =>
                             lastExecuted
                               .updateAndGet(_ + 1)
                               .flatMap(n => if (n == 4) promise.succeed(()) else promise.await)
                           )
                           .flatMap(_ => ZStream.empty)
                       else ZStream.scoped(semaphore.withPermitScoped).flatMap(_ => ZStream.never)
                     }
                     .runDrain
              result <- semaphore.withPermits(4)(lastExecuted.get)
            } yield assert(result)(equalTo(4))
          },
          test("short circuiting") {
            assertZIO(
              ZStream(ZStream.never, ZStream(1))
                .flatMapParSwitch(2)(identity)
                .take(1)
                .runCollect
            )(equalTo(Chunk(1)))
          },
          test("interruption propagation") {
            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              fiber <- ZStream(())
                         .flatMapParSwitch(1)(_ =>
                           ZStream.fromZIO(
                             (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                           )
                         )
                         .runCollect
                         .fork
              _         <- latch.await
              _         <- fiber.interrupt
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue)
          } @@ flaky,
          test("inner errors interrupt all fibers") {
            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- ZStream(
                          ZStream.fromZIO(
                            (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                          ),
                          ZStream.fromZIO(latch.await *> ZIO.fail("Ouch"))
                        ).flatMapParSwitch(2)(identity).runDrain.either
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(isLeft(equalTo("Ouch")))
          } @@ exceptJS(nonFlaky),
          test("outer errors interrupt all fibers") {
            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- (ZStream(()) ++ ZStream.fromZIO(latch.await *> ZIO.fail("Ouch")))
                          .flatMapParSwitch(2) { _ =>
                            ZStream.fromZIO(
                              (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                            )
                          }
                          .runDrain
                          .either
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(isLeft(equalTo("Ouch")))
          } @@ exceptJS(nonFlaky),
          test("inner defects interrupt all fibers") {
            val ex = new RuntimeException("Ouch")

            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- ZStream(
                          ZStream.fromZIO(
                            (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                          ),
                          ZStream.fromZIO(latch.await *> ZIO.die(ex))
                        ).flatMapParSwitch(2)(identity).runDrain.exit
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(dies(equalTo(ex)))
          },
          test("outer defects interrupt all fibers") {
            val ex = new RuntimeException()

            for {
              substreamCancelled <- Ref.make[Boolean](false)
              latch              <- Promise.make[Nothing, Unit]
              result <- (ZStream(()) ++ ZStream.fromZIO(latch.await *> ZIO.die(ex)))
                          .flatMapParSwitch(2) { _ =>
                            ZStream.fromZIO(
                              (latch.succeed(()) *> ZIO.infinity).onInterrupt(substreamCancelled.set(true))
                            )
                          }
                          .runDrain
                          .exit
              cancelled <- substreamCancelled.get
            } yield assert(cancelled)(isTrue) && assert(result)(dies(equalTo(ex)))
          } @@ exceptJS(nonFlaky),
          test("finalizer ordering") {
            for {
              execution <- Ref.make(List.empty[String])
              inner = ZStream.acquireReleaseWith(execution.update("InnerAcquire" :: _))(_ =>
                        execution.update("InnerRelease" :: _)
                      )
              _ <-
                ZStream
                  .acquireReleaseWith(execution.update("OuterAcquire" :: _).as(inner))(_ =>
                    execution.update("OuterRelease" :: _)
                  )
                  .flatMapParSwitch(2)(identity)
                  .runDrain
              results <- execution.get
            } yield assert(results)(
              equalTo(List("OuterRelease", "InnerRelease", "InnerAcquire", "OuterAcquire"))
            )
          }
        ),
        test("flattenChunks") {
          check(Gen.chunkOf(Gen.chunkOf(Gen.int))) { chunks =>
            assertZIO(ZStream.fromChunks(chunks).flattenChunks.chunks.runCollect)(equalTo(chunks))
          }
        },
        suite("flattenExitOption")(
          test("happy path") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .range(0, 10)
                  .toQueue(1)
                  .flatMap(q => ZStream.fromQueue(q).map(_.exit).flattenExitOption.runCollect)
              }
                .map(_.flatMap(_.toList))
            )(equalTo(Chunk.fromIterable(Range(0, 10))))
          },
          test("errors") {
            val e = new RuntimeException("boom")
            assertZIO(
              ZIO.scoped {
                (ZStream.range(0, 10) ++ ZStream.fail(e))
                  .toQueue(1)
                  .flatMap(q => ZStream.fromQueue(q).map(_.exit).flattenExitOption.runCollect)
              }.exit
            )(fails(equalTo(e)))
          } @@ zioTag(errors)
        ),
        test("flattenIterables")(check(tinyListOf(tinyListOf(Gen.int))) { lists =>
          assertZIO(ZStream.fromIterable(lists).flattenIterables.runCollect)(equalTo(Chunk.fromIterable(lists.flatten)))
        }),
        suite("flattenTake")(
          test("happy path")(check(tinyListOf(Gen.chunkOf(Gen.int))) { chunks =>
            assertZIO(
              ZStream
                .fromChunks(chunks: _*)
                .mapChunks(chunk => Chunk.single(Take.chunk(chunk)))
                .flattenTake
                .runCollect
            )(equalTo(chunks.fold(Chunk.empty)(_ ++ _)))
          }),
          test("stop collecting on Exit.Failure") {
            assertZIO(
              ZStream(
                Take.chunk(Chunk(1, 2)),
                Take.single(3),
                Take.end
              ).flattenTake.runCollect
            )(equalTo(Chunk(1, 2, 3)))
          },
          test("work with empty chunks") {
            assertZIO(
              ZStream(Take.chunk(Chunk.empty), Take.chunk(Chunk.empty)).flattenTake.runCollect
            )(isEmpty)
          },
          test("work with empty streams") {
            assertZIO(ZStream.fromIterable[Take[Nothing, Nothing]](Nil).flattenTake.runCollect)(
              isEmpty
            )
          }
        ),
        test("runFoldWhile") {
          val stream = ZStream(1, 1, 1, 1, 1)
          for {
            sum <- stream.runFoldWhile(0)(_ < 3)(_ + _)
          } yield assertTrue(sum == 3)
        },
        suite("foreach")(
          suite("foreach")(
            test("with small data set") {
              for {
                ref <- Ref.make(0)
                _   <- ZStream(1, 1, 1, 1, 1).foreach[Any, Nothing](a => ref.update(_ + a))
                sum <- ref.get
              } yield assert(sum)(equalTo(5))
            },
            test("with bigger data set") {
              for {
                ref <- Ref.make(0L)
                _ <-
                  ZStream.fromIterable(List.fill(1000)(1L)).foreach[Any, Nothing](a => ref.update(_ + a))
                sum <- ref.get
              } yield assert(sum)(equalTo(1000L))
            }
          ),
          suite("foreachWhile")(
            test("with small data set") {
              val expected = 3
              for {
                ref <- Ref.make(0)
                _ <- ZStream(1, 1, 1, 1, 1, 1).runForeachWhile[Any, Nothing](a =>
                       ref.modify(sum =>
                         if (sum >= expected) (false, sum)
                         else (true, sum + a)
                       )
                     )
                sum <- ref.get
              } yield assert(sum)(equalTo(expected))
            },
            test("with bigger data set") {
              val expected = 500L
              for {
                ref <- Ref.make(0L)
                _ <- ZStream
                       .fromIterable[Long](List.fill(1000)(1L))
                       .runForeachWhile[Any, Nothing](a =>
                         ref.modify(sum =>
                           if (sum >= expected) (false, sum)
                           else (true, sum + a)
                         )
                       )
                sum <- ref.get
              } yield assert(sum)(equalTo(expected))
            }
          ),
          test("foreachWhile short circuits") {
            for {
              flag <- Ref.make(true)
              _ <- (ZStream(true, true, false) ++ ZStream.fromZIO(flag.set(false)).drain)
                     .runForeachWhile(ZIO.succeed(_))
              skipped <- flag.get
            } yield assert(skipped)(isTrue)
          }
        ),
        test("forever") {
          for {
            ref <- Ref.make(0)
            _ <- ZStream(1).forever.runForeachWhile[Any, Nothing](_ =>
                   ref.modify(sum => (if (sum >= 9) false else true, sum + 1))
                 )
            sum <- ref.get
          } yield assert(sum)(equalTo(10))
        },
        suite("groupBy")(
          test("values") {
            val words = List.fill(100)(0 to 100).flatten.map(_.toString())
            assertZIO(
              ZStream
                .fromIterable(words)
                .groupByKey(identity, 8192) { case (k, s) =>
                  ZStream.fromZIO(s.runCollect.map(l => k -> l.size))
                }
                .runCollect
                .map(_.toMap)
            )(equalTo((0 to 100).map((_.toString -> 100)).toMap))
          },
          test("first") {
            val words = List.fill(1000)(0 to 100).flatten.map(_.toString())
            assertZIO(
              ZStream
                .fromIterable(words)
                .groupByKey(identity, 1050)
                .first(2) { case (k, s) =>
                  ZStream.fromZIO(s.runCollect.map(l => k -> l.size))
                }
                .runCollect
                .map(_.toMap)
            )(equalTo((0 to 1).map((_.toString -> 1000)).toMap))
          },
          test("filter") {
            val words = List.fill(1000)(0 to 100).flatten
            assertZIO(
              ZStream
                .fromIterable(words)
                .groupByKey(identity, 1050)
                .filter(_ <= 5) { case (k, s) =>
                  ZStream.fromZIO(s.runCollect.map(l => k -> l.size))
                }
                .runCollect
                .map(_.toMap)
            )(equalTo((0 to 5).map((_ -> 1000)).toMap))
          },
          test("outer errors") {
            val words = List("abc", "test", "test", "foo")
            assertZIO(
              (ZStream.fromIterable(words) ++ ZStream.fail("Boom"))
                .groupByKey(identity) { case (_, s) => s.drain }
                .runCollect
                .either
            )(isLeft(equalTo("Boom")))
          }
        ) @@ TestAspect.jvmOnly,
        suite("haltWhen")(
          suite("haltWhen(Promise)")(
            test("halts after the current element") {
              for {
                interrupted <- Ref.make(false)
                latch       <- Promise.make[Nothing, Unit]
                halt        <- Promise.make[Nothing, Unit]
                _ <- ZStream
                       .fromZIO(latch.await.onInterrupt(interrupted.set(true)))
                       .haltWhen(halt)
                       .runDrain
                       .fork
                _      <- halt.succeed(())
                _      <- latch.succeed(())
                result <- interrupted.get
              } yield assert(result)(isFalse)
            },
            test("propagates errors") {
              for {
                halt <- Promise.make[String, Nothing]
                _    <- halt.fail("Fail")
                result <- ZStream(1)
                            .haltWhen(halt)
                            .runDrain
                            .either
              } yield assert(result)(isLeft(equalTo("Fail")))
            } @@ zioTag(errors)
          ),
          suite("haltWhen(IO)")(
            test("halts after the current element") {
              for {
                interrupted <- Ref.make(false)
                latch       <- Promise.make[Nothing, Unit]
                halt        <- Promise.make[Nothing, Unit]
                _ <- ZStream
                       .fromZIO(latch.await.onInterrupt(interrupted.set(true)))
                       .haltWhen(halt.await)
                       .runDrain
                       .fork
                _      <- halt.succeed(())
                _      <- latch.succeed(())
                result <- interrupted.get
              } yield assert(result)(isFalse)
            },
            test("propagates errors") {
              for {
                halt <- Promise.make[String, Nothing]
                _    <- halt.fail("Fail")
                result <- ZStream(0).forever
                            .haltWhen(halt.await)
                            .runDrain
                            .either
              } yield assert(result)(isLeft(equalTo("Fail")))
            } @@ zioTag(errors)
          )
        ),
        suite("haltAfter")(
          test("halts after given duration") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(2), Chunk(3), Chunk(4))) { c =>
              assertZIO(
                for {
                  fiber <- ZStream
                             .fromQueue(c.queue)
                             .collectWhileSuccess
                             .haltAfter(5.seconds)
                             .tap(_ => c.proceed)
                             .runCollect
                             .fork
                  _      <- c.offer *> TestClock.adjust(3.seconds) *> c.awaitNext
                  _      <- c.offer *> TestClock.adjust(3.seconds) *> c.awaitNext
                  _      <- c.offer *> TestClock.adjust(3.seconds) *> c.awaitNext
                  _      <- c.offer
                  result <- fiber.join
                } yield result
              )(equalTo(Chunk(Chunk(1), Chunk(2), Chunk(3))))
            }
          },
          test("will process first chunk") {
            for {
              queue  <- Queue.unbounded[Int]
              fiber  <- ZStream.fromQueue(queue).haltAfter(5.seconds).runCollect.fork
              _      <- TestClock.adjust(6.seconds)
              _      <- queue.offer(1)
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(1)))
          }
        ),
        suite("grouped")(
          test("sanity") {
            assertZIO(ZStream(1, 2, 3, 4, 5).grouped(2).runCollect)(equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5))))
          },
          test("group size is correct") {
            assertZIO(ZStream.range(0, 100).grouped(10).map(_.size).runCollect)(equalTo(Chunk.fill(10)(10)))
          },
          test("doesn't emit empty chunks") {
            assertZIO(ZStream.fromIterable(List.empty[Int]).grouped(5).runCollect)(equalTo(Chunk.empty))
          },
          test("is equivalent to Array#grouped") {
            check(pureStreamOfInts, Gen.int(1, 10)) { case (stream, chunkSize) =>
              for {
                result1 <- stream.grouped(chunkSize).runCollect
                partial <- stream.runCollect
                arrays   = partial.toArray.grouped(chunkSize).toArray
                result2  = Chunk.fromArray(arrays.map(Chunk.fromArray(_)))
              } yield assert(result1)(equalTo(result2))
            }
          },
          test("emits elements properly when a failure occurs") {
            for {
              ref         <- Ref.make[Chunk[Chunk[Int]]](Chunk.empty)
              streamChunks = ZStream.fromChunks(Chunk(1, 2, 3, 4), Chunk(5, 6, 7), Chunk(8))
              stream       = (streamChunks ++ ZStream.fail("Ouch")).grouped(3)
              expected     = Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8))
              either      <- stream.mapZIO(chunk => ref.update(_ :+ chunk)).runCollect.either
              result      <- ref.get
            } yield assert(either)(isLeft(equalTo("Ouch"))) && assert(result)(equalTo(expected))
          }
        ),
        suite("groupedWithin")(
          test("group based on time passed") {
            assertWithChunkCoordination(List(Chunk(1, 2), Chunk(3, 4), Chunk.single(5))) { c =>
              val stream = ZStream
                .fromQueue(c.queue)
                .collectWhileSuccess
                .flattenChunks
                .groupedWithin(10, 2.seconds)
                .tap(_ => c.proceed)

              assertZIO(for {
                f      <- stream.runCollect.fork
                _      <- c.offer *> TestClock.adjust(2.seconds) *> c.awaitNext
                _      <- c.offer *> TestClock.adjust(2.seconds) *> c.awaitNext
                _      <- c.offer
                result <- f.join
              } yield result)(equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5))))
            }
          },
          test("group based on time passed (#5013)") {
            val chunkResult = Chunk(
              Chunk(1, 2, 3),
              Chunk(4, 5, 6),
              Chunk(7, 8, 9),
              Chunk(10, 11, 12, 13, 14, 15, 16, 17, 18, 19),
              Chunk(20, 21, 22, 23, 24, 25, 26, 27, 28, 29)
            )

            assertWithChunkCoordination((1 to 29).map(Chunk.single).toList) { c =>
              for {
                latch <- ZStream.Handoff.make[Unit]
                ref   <- Ref.make(0)
                fiber <- ZStream
                           .fromQueue(c.queue)
                           .collectWhileSuccess
                           .flattenChunks
                           .tap(_ => c.proceed)
                           .groupedWithin(10, 3.seconds)
                           .tap(chunk => ref.update(_ + chunk.size) *> latch.offer(()))
                           .run(ZSink.take(5))
                           .fork
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                result0 <- latch.take *> ref.get
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                result1 <- latch.take *> ref.get
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                _       <- c.offer *> TestClock.adjust(1.second) *> c.awaitNext
                result2 <- latch.take *> ref.get
                // This part is to make sure schedule clock is being restarted
                // when the specified amount of elements has been reached
                _       <- TestClock.adjust(2.second) *> (c.offer *> c.awaitNext).repeatN(9)
                result3 <- latch.take *> ref.get
                _       <- c.offer *> c.awaitNext *> TestClock.adjust(2.second) *> (c.offer *> c.awaitNext).repeatN(8)
                result4 <- latch.take *> ref.get
                result  <- fiber.join
              } yield assert(result)(equalTo(chunkResult)) &&
                assert(result0)(equalTo(3)) &&
                assert(result1)(equalTo(6)) &&
                assert(result2)(equalTo(9)) &&
                assert(result3)(equalTo(19)) &&
                assert(result4)(equalTo(29))
            }
          },
          test("group immediately when chunk size is reached") {
            assertZIO(ZStream(1, 2, 3, 4).groupedWithin(2, 10.seconds).runCollect)(
              equalTo(Chunk(Chunk(1, 2), Chunk(3, 4)))
            )
          }
        ),
        test("interleave") {
          val s1 = ZStream(2, 3)
          val s2 = ZStream(5, 6, 7)

          assertZIO(s1.interleave(s2).runCollect)(equalTo(Chunk(2, 5, 3, 6, 7)))
        },
        test("interleaveWith") {
          def interleave(b: Chunk[Boolean], s1: => Chunk[Int], s2: => Chunk[Int]): Chunk[Int] =
            b.headOption.map { hd =>
              if (hd) s1 match {
                case h +: t =>
                  h +: interleave(b.tail, t, s2)
                case _ =>
                  if (s2.isEmpty) Chunk.empty
                  else interleave(b.tail, Chunk.empty, s2)
              }
              else
                s2 match {
                  case h +: t =>
                    h +: interleave(b.tail, s1, t)
                  case _ =>
                    if (s1.isEmpty) Chunk.empty
                    else interleave(b.tail, s1, Chunk.empty)
                }
            }.getOrElse(Chunk.empty)

          val int = Gen.int(0, 5)

          check(
            int.flatMap(pureStreamGen(Gen.boolean, _)),
            int.flatMap(pureStreamGen(Gen.int, _)),
            int.flatMap(pureStreamGen(Gen.int, _))
          ) { (b, s1, s2) =>
            for {
              interleavedStream <- s1.interleaveWith(s2)(b).runCollect
              b                 <- b.runCollect
              s1                <- s1.runCollect
              s2                <- s2.runCollect
              interleavedLists   = interleave(b, s1, s2)
            } yield assert(interleavedStream)(equalTo(interleavedLists))
          }
        },
        suite("Stream.intersperse")(
          test("intersperse several") {
            ZStream(1, 2, 3, 4)
              .map(_.toString)
              .intersperse("@")
              .runCollect
              .map(result => assert(result)(equalTo(Chunk("1", "@", "2", "@", "3", "@", "4"))))
          },
          test("intersperse several with begin and end") {
            ZStream(1, 2, 3, 4)
              .map(_.toString)
              .intersperse("[", "@", "]")
              .runCollect
              .map(result => assert(result)(equalTo(Chunk("[", "1", "@", "2", "@", "3", "@", "4", "]"))))
          },
          test("intersperse single") {
            ZStream(1)
              .map(_.toString)
              .intersperse("@")
              .runCollect
              .map(result => assert(result)(equalTo(Chunk("1"))))
          },
          test("intersperse single with begin and end") {
            ZStream(1)
              .map(_.toString)
              .intersperse("[", "@", "]")
              .runCollect
              .map(result => assert(result)(equalTo(Chunk("[", "1", "]"))))
          },
          test("mkString(Sep) equivalence") {
            check(
              Gen
                .int(0, 10)
                .flatMap(Gen.listOfN(_)(Gen.small(Gen.chunkOfN(_)(Gen.int))))
            ) { chunks =>
              val stream = ZStream.fromChunks(chunks: _*)

              for {
                interspersed <- stream.map(_.toString).intersperse("@").runCollect.map(_.mkString)
                regular      <- stream.map(_.toString).runCollect.map(_.mkString("@"))
              } yield assert(interspersed)(equalTo(regular))
            }
          },
          test("mkString(Before, Sep, After) equivalence") {
            check(
              Gen
                .int(0, 10)
                .flatMap(Gen.listOfN(_)(Gen.small(Gen.chunkOfN(_)(Gen.int))))
            ) { chunks =>
              val stream = ZStream.fromChunks(chunks: _*)

              for {
                interspersed <- stream.map(_.toString).intersperse("[", "@", "]").runCollect.map(_.mkString)
                regular      <- stream.map(_.toString).runCollect.map(_.mkString("[", "@", "]"))
              } yield assert(interspersed)(equalTo(regular))
            }
          },
          test("intersperse several from repeat effect (#3729)") {
            ZStream
              .repeatZIO(ZIO.succeed(42))
              .map(_.toString)
              .take(4)
              .intersperse("@")
              .runCollect
              .map(result => assert(result)(equalTo(Chunk("42", "@", "42", "@", "42", "@", "42"))))
          },
          test("intersperse several from repeat effect chunk single element (#3729)") {
            ZStream
              .repeatZIOChunk(ZIO.succeed(Chunk(42)))
              .map(_.toString)
              .intersperse("@")
              .take(4)
              .runCollect
              .map(result => assert(result)(equalTo(Chunk("42", "@", "42", "@"))))
          },
          suite("issue #9181")(
            test("it intersperses elements") {
              assertZIO(
                ZStream('a', 'b', 'c').via(ZPipeline.intersperse(',')).runCollect
              )(equalTo(Chunk('a', ',', 'b', ',', 'c')))
            },
            test("it intersperses elements with prefix and suffix") {
              assertZIO(
                ZStream('a', 'b', 'c').via(ZPipeline.intersperse('[', ',', ']')).runCollect
              )(equalTo(Chunk('[', 'a', ',', 'b', ',', 'c', ']')))
            }
          )
        ),
        suite("interruptWhen")(
          suite("interruptWhen(Promise)")(
            test("interrupts the current element") {
              for {
                interrupted <- Ref.make(false)
                latch       <- Promise.make[Nothing, Unit]
                halt        <- Promise.make[Nothing, Unit]
                started     <- Promise.make[Nothing, Unit]
                fiber <- ZStream
                           .fromZIO(
                             (started.succeed(()) *> latch.await).onInterrupt(interrupted.set(true))
                           )
                           .interruptWhen(halt)
                           .runDrain
                           .fork
                _      <- started.await *> halt.succeed(())
                _      <- fiber.await
                result <- interrupted.get
              } yield assert(result)(isTrue)
            },
            test("propagates errors") {
              for {
                halt <- Promise.make[String, Nothing]
                _    <- halt.fail("Fail")
                result <- ZStream.never
                            .interruptWhen(halt)
                            .runDrain
                            .either
              } yield assert(result)(isLeft(equalTo("Fail")))
            } @@ zioTag(errors) @@ exceptJS(nonFlaky)
          ) @@ zioTag(interruption),
          test("preserves scope of inner fibers") {
            for {
              promise <- Promise.make[Nothing, Unit]
              queue1  <- Queue.unbounded[Chunk[Int]]
              queue2  <- Queue.unbounded[Chunk[Int]]
              _       <- queue1.offer(Chunk(1))
              _       <- queue2.offer(Chunk(2))
              _       <- queue1.offer(Chunk(3)).fork
              _       <- queue2.offer(Chunk(4)).fork
              s1       = ZStream.fromChunkQueue(queue1)
              s2       = ZStream.fromChunkQueue(queue2)
              s3       = s1.zipLatest(s2).interruptWhen(promise.await).take(3)
              _       <- s3.runDrain
            } yield assertCompletes
          } @@ exceptJS(nonFlaky),
          suite("interruptWhen(IO)")(
            test("interrupts the current element") {
              for {
                interrupted <- Ref.make(false)
                latch       <- Promise.make[Nothing, Unit]
                halt        <- Promise.make[Nothing, Unit]
                started     <- Promise.make[Nothing, Unit]
                fiber <- ZStream
                           .fromZIO(
                             (started.succeed(()) *> latch.await).onInterrupt(interrupted.set(true))
                           )
                           .interruptWhen(halt.await)
                           .runDrain
                           .fork
                _      <- started.await *> halt.succeed(())
                _      <- fiber.await
                result <- interrupted.get
              } yield assert(result)(isTrue)
            },
            test("propagates errors") {
              for {
                halt <- Promise.make[String, Nothing]
                _    <- halt.fail("Fail")
                result <- ZStream
                            .fromZIO(ZIO.never)
                            .interruptWhen(halt.await)
                            .runDrain
                            .either
              } yield assert(result)(isLeft(equalTo("Fail")))
            } @@ zioTag(errors)
          ) @@ zioTag(interruption),
          test("preserves scope of inner fibers") {
            for {
              queue1 <- Queue.unbounded[Chunk[Int]]
              queue2 <- Queue.unbounded[Chunk[Int]]
              _      <- queue1.offer(Chunk(1))
              _      <- queue2.offer(Chunk(2))
              _      <- queue1.offer(Chunk(3)).fork
              _      <- queue2.offer(Chunk(4)).fork
              s1      = ZStream.fromChunkQueue(queue1)
              s2      = ZStream.fromChunkQueue(queue2)
              s3      = s1.zipLatest(s2).interruptWhen(ZIO.never).take(3)
              _      <- s3.runDrain
            } yield assertCompletes
          } @@ exceptJS(nonFlaky),
          test("i9091 - forked children are not interrupted early by interruptWhen") {
            for {
              requestQueue <- Queue.unbounded[String]
              counter      <- Ref.make(0)
              _ <- ZStream
                     .unwrapScoped(
                       ZStream
                         .fromQueue(requestQueue)
                         .runForeach(_ => counter.update(_ + 1))
                         .fork
                         .as(ZStream.succeed("") ++ ZStream.never)
                     )
                     .interruptWhen(ZIO.never)
                     .runDrain
                     .fork
              _ <- requestQueue.offer("some message").forever.fork
              _ <- (ZIO.yieldNow *> counter.get).repeatUntil(_ >= 10)
            } yield assertCompletes
          } @@ exceptJS(nonFlaky) @@ TestAspect.timeout(30.seconds)
        ),
        suite("interruptAfter")(
          test("interrupts after given duration") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(2), Chunk(3))) { c =>
              assertZIO(
                for {
                  fiber <- ZStream
                             .fromQueue(c.queue)
                             .collectWhileSuccess
                             .interruptAfter(5.seconds)
                             .tap(_ => c.proceed)
                             .runCollect
                             .fork
                  _      <- c.offer *> TestClock.adjust(3.seconds) *> c.awaitNext
                  _      <- c.offer *> TestClock.adjust(3.seconds) *> c.awaitNext
                  _      <- c.offer
                  result <- fiber.join
                } yield result
              )(equalTo(Chunk(Chunk(1), Chunk(2))))
            }
          },
          test("interrupts before first chunk") {
            for {
              queue  <- Queue.unbounded[Int]
              fiber  <- ZStream.fromQueue(queue).interruptAfter(5.seconds).runCollect.fork
              _      <- TestClock.adjust(6.seconds)
              _      <- queue.offer(1)
              result <- fiber.join
            } yield assert(result)(isEmpty)
          }
        ) @@ zioTag(interruption),
        suite("onExecutor")(
          test("shifts and shifts back if there is a previous locked executor") {
            val global = Executor.fromExecutionContext(ExecutionContext.global)
            for {
              default   <- ZIO.executor
              ref1      <- Ref.make[Executor](default)
              ref2      <- Ref.make[Executor](default)
              stream1    = ZStream.fromZIO(ZIO.executor.flatMap(ref1.set)).onExecutor(global)
              stream2    = ZStream.fromZIO(ZIO.executor.flatMap(ref2.set))
              _         <- (stream1 ++ stream2).runDrain.onExecutor(default)
              executor1 <- ref1.get
              executor2 <- ref2.get
            } yield assert(executor1)(equalTo(global)) &&
              assert(executor2)(equalTo(default))
          },
          test("shifts and does not shift back if there is no previous locked executor") {
            val thread = ZIO.succeed(Thread.currentThread())
            val global = Executor.fromExecutionContext(ExecutionContext.global)
            for {
              default <- thread
              during  <- Ref.make[Thread](default)
              after   <- Ref.make[Thread](default)
              stream1  = ZStream.fromZIO(thread.flatMap(during.set)).onExecutor(global)
              stream2  = ZStream.fromZIO(thread.flatMap(after.set))
              _       <- (stream1 ++ stream2).runDrain
              thread1 <- during.get
              thread2 <- after.get
            } yield assert(thread1)(equalTo(thread2))
          }
        ),
        suite("scoped")(
          test("preserves failure of effect") {
            assertZIO(
              ZStream.scoped(ZIO.fail("error")).runCollect.either
            )(isLeft(equalTo("error")))
          },
          test("preserves interruptibility of effect") {
            for {
              interruptible <- ZStream
                                 .scoped(ZIO.suspendSucceed(ZIO.checkInterruptible(ZIO.succeed(_))))
                                 .runHead
              uninterruptible <- ZStream
                                   .scoped(ZIO.uninterruptible(ZIO.checkInterruptible(ZIO.succeed(_))))
                                   .runHead
            } yield assert(interruptible)(isSome(equalTo(InterruptStatus.Interruptible))) &&
              assert(uninterruptible)(isSome(equalTo(InterruptStatus.Uninterruptible)))
          },
          test("failure of inner finalizers does not affect outer finalizers") {
            def resource(ref: Ref[Chunk[String]])(name: String): ZIO[Scope, Nothing, Unit] =
              ZIO.acquireRelease(ref.update(_ :+ s"Acquiring $name"))(_ => ref.update(_ :+ s"Releasing $name"))
            def badResource(ref: Ref[Chunk[String]])(name: String): ZIO[Scope, Nothing, Unit] =
              ZIO.acquireRelease(ref.update(_ :+ s"Acquiring $name"))(_ => ZIO.dieMessage("Die"))
            def stream(ref: Ref[Chunk[String]]): ZStream[Any, Nothing, Unit] =
              ZStream.scoped(resource(ref)("A")) *>
                ZStream.scoped(resource(ref)("B")) *>
                ZStream.scoped(badResource(ref)("C"))
            for {
              ref   <- Ref.make[Chunk[String]](Chunk.empty)
              exit  <- stream(ref).runDrain.exit
              value <- ref.get
            } yield assert(exit)(dies(hasMessage(equalTo("Die")))) &&
              assert(value)(equalTo(Chunk("Acquiring A", "Acquiring B", "Acquiring C", "Releasing B", "Releasing A")))
          },
          test("complex scope propagation (i9316)") {
            def resource(ref: Ref[List[String]])(i: Int) =
              ZIO.acquireRelease(ref.update(s"acquire $i" :: _).as(i))(_ => ref.update(s"release $i" :: _))
            for {
              ref <- Ref.make[List[String]](Nil)
              _ <- ZIO.scoped(
                     ZStream
                       .fromIterable(0 until 3)
                       .mapZIO(resource(ref))
                       .mapZIO { i =>
                         ZIO.scoped {
                           ZStream
                             .scoped(ZIO.blocking(ref.update(s"process $i" :: _)))
                             .runScoped(ZSink.collectAll)
                         }
                       }
                       .runDrain
                   )
              out <- ref.get.map(_.reverse)
            } yield assertTrue(
              out == List(
                "acquire 0",
                "process 0",
                "acquire 1",
                "process 1",
                "acquire 2",
                "process 2",
                "release 2",
                "release 1",
                "release 0"
              )
            )
          }
        ),
        test("map")(check(pureStreamOfInts, Gen.function(Gen.int)) { (s, f) =>
          for {
            res1 <- s.map(f).runCollect
            res2 <- s.runCollect.map(_.map(f))
          } yield assert(res1)(equalTo(res2))
        }),
        test("mapAccum") {
          assertZIO(ZStream(1, 1, 1).mapAccum(0)((acc, el) => (acc + el, acc + el)).runCollect)(
            equalTo(Chunk(1, 2, 3))
          )
        },
        suite("mapAccumZIO")(
          test("mapAccumZIO happy path") {
            assertZIO(
              ZStream(1, 1, 1)
                .mapAccumZIO[Any, Nothing, Int, Int](0)((acc, el) => ZIO.succeed((acc + el, acc + el)))
                .runCollect
            )(equalTo(Chunk(1, 2, 3)))
          },
          test("mapAccumZIO error") {
            ZStream(1, 1, 1)
              .mapAccumZIO(0)((_, _) => ZIO.fail("Ouch"))
              .runCollect
              .either
              .map(assert(_)(isLeft(equalTo("Ouch"))))
          } @@ zioTag(errors),
          test("laziness on chunks") {
            assertZIO(
              ZStream(1, 2, 3)
                .mapAccumZIO(()) {
                  case (_, 3) => ZIO.fail("boom")
                  case (_, x) => ZIO.succeed(((), x))
                }
                .either
                .runCollect
            )(equalTo(Chunk(Right(1), Right(2), Left("boom"))))
          }
        ),
        test("mapConcat")(check(pureStreamOfInts, Gen.function(Gen.listOf(Gen.int))) { (s, f) =>
          for {
            res1 <- s.mapConcat(f).runCollect
            res2 <- s.runCollect.map(_.flatMap(v => f(v).toSeq))
          } yield assert(res1)(equalTo(res2))
        }),
        test("mapConcatChunk")(check(pureStreamOfInts, Gen.function(Gen.chunkOf(Gen.int))) { (s, f) =>
          for {
            res1 <- s.mapConcatChunk(f).runCollect
            res2 <- s.runCollect.map(_.flatMap(v => f(v).toSeq))
          } yield assert(res1)(equalTo(res2))
        }),
        suite("mapConcatChunkZIO")(
          test("mapConcatChunkZIO happy path") {
            check(pureStreamOfInts, Gen.function(Gen.chunkOf(Gen.int))) { (s, f) =>
              for {
                res1 <- s.mapConcatChunkZIO(b => ZIO.succeed(f(b))).runCollect
                res2 <- s.runCollect.map(_.flatMap(v => f(v).toSeq))
              } yield assert(res1)(equalTo(res2))
            }
          },
          test("mapConcatChunkZIO error") {
            ZStream(1, 2, 3)
              .mapConcatChunkZIO(_ => ZIO.fail("Ouch"))
              .runCollect
              .either
              .map(assert(_)(equalTo(Left("Ouch"))))
          }
        ),
        suite("mapConcatZIO")(
          test("mapConcatZIO happy path") {
            check(pureStreamOfInts, Gen.function(Gen.listOf(Gen.int))) { (s, f) =>
              for {
                res1 <- s.mapConcatZIO(b => ZIO.succeed(f(b))).runCollect
                res2 <- s.runCollect.map(_.flatMap(v => f(v).toSeq))
              } yield assert(res1)(equalTo(res2))
            }
          },
          test("mapConcatZIO error") {
            ZStream(1, 2, 3)
              .mapConcatZIO(_ => ZIO.fail("Ouch"))
              .runCollect
              .either
              .map(assert(_)(equalTo(Left("Ouch"))))
          }
        ),
        test("mapError") {
          ZStream
            .fail("123")
            .mapError(_.toInt)
            .runCollect
            .either
            .map(assert(_)(isLeft(equalTo(123))))
        },
        test("mapErrorCause") {
          ZStream
            .failCause(Cause.fail("123"))
            .mapErrorCause(_.map(_.toInt))
            .runCollect
            .either
            .map(assert(_)(isLeft(equalTo(123))))
        },
        test("mapErrorZIO") {
          ZStream
            .fail("123")
            .mapErrorZIO(v => ZIO.succeed(v.toInt))
            .runCollect
            .either
            .map(assert(_)(isLeft(equalTo(123))))
        },
        suite("mapZIO")(
          test("ZIO#foreach equivalence") {
            check(Gen.small(Gen.listOfN(_)(Gen.byte)), Gen.function(Gen.successes(Gen.byte))) { (data, f) =>
              val s = ZStream.fromIterable(data)

              for {
                l <- s.mapZIO(f).runCollect
                r <- ZIO.foreach(data)(f)
              } yield assert(l.toList)(equalTo(r))
            }
          },
          test("laziness on chunks") {
            assertZIO(
              ZStream(1, 2, 3).mapZIO {
                case 3 => ZIO.fail("boom")
                case x => ZIO.succeed(x)
              }.either.runCollect
            )(equalTo(Chunk(Right(1), Right(2), Left("boom"))))
          }
        ),
        suite("mapZIOPar")(
          test("foreachParN equivalence") {
            checkN(10)(Gen.small(Gen.listOfN(_)(Gen.byte)), Gen.function(Gen.successes(Gen.byte))) { (data, f) =>
              val s = ZStream.fromIterable(data)

              for {
                l <- s.mapZIOPar(8)(f).runCollect
                r <- ZIO.foreachPar(data)(f).withParallelism(8)
              } yield assert(l.toList)(equalTo(r))
            }
          },
          test("order when n = 1") {
            for {
              queue  <- Queue.unbounded[Int]
              _      <- ZStream.range(0, 9).mapZIOPar(1)(queue.offer).runDrain
              result <- queue.takeAll
            } yield assert(result)(equalTo(result.sorted))
          },
          test("interruption propagation") {
            for {
              interrupted <- Ref.make(false)
              latch       <- Promise.make[Nothing, Unit]
              fib <- ZStream(())
                       .mapZIOPar(1)(_ => (latch.succeed(()) *> ZIO.infinity).onInterrupt(interrupted.set(true)))
                       .runDrain
                       .fork
              _      <- latch.await
              _      <- fib.interrupt
              result <- interrupted.get
            } yield assert(result)(isTrue)
          },
          test("guarantee ordering")(check(Gen.int(1, 4096), Gen.listOf(Gen.int)) { (n: Int, m: List[Int]) =>
            for {
              mapZIO    <- ZStream.fromIterable(m).mapZIO(ZIO.succeed(_)).runCollect
              mapZIOPar <- ZStream.fromIterable(m).mapZIOPar(n)(ZIO.succeed(_)).runCollect
            } yield assert(n)(isGreaterThan(0)) implies assert(mapZIO)(equalTo(mapZIOPar))
          }),
          test("awaits children fibers properly") {
            assertZIO(
              ZStream
                .fromIterable((0 to 100))
                .interruptWhen(ZIO.never)
                .mapZIOPar(8)(_ => ZIO.succeed(1).repeatN(2000))
                .runDrain
                .exit
                .map(_.isInterrupted)
            )(equalTo(false))
          },
          test("interrupts pending tasks when one of the tasks fails") {
            for {
              interrupted <- Ref.make(0)
              latch1      <- Promise.make[Nothing, Unit]
              latch2      <- Promise.make[Nothing, Unit]
              result <- ZStream(1, 2, 3)
                          .mapZIOPar(3) {
                            case 1 => (latch1.succeed(()) *> ZIO.never).onInterrupt(interrupted.update(_ + 1))
                            case 2 => (latch2.succeed(()) *> ZIO.never).onInterrupt(interrupted.update(_ + 1))
                            case 3 => latch1.await *> latch2.await *> ZIO.fail("Boom")
                          }
                          .runDrain
                          .exit
              count <- interrupted.get
            } yield assert(count)(equalTo(2)) && assert(result)(fails(equalTo("Boom")))
          } @@ exceptJS(nonFlaky(500)),
          test("propagates correct error with subsequent mapZIOPar call (#4514)") {
            assertZIO(
              ZStream
                .fromIterable(1 to 50)
                .mapZIOPar(20)(i => if (i < 10) ZIO.succeed(i) else ZIO.fail("Boom"))
                .mapZIOPar(20)(ZIO.succeed(_))
                .runCollect
                .either
            )(isLeft(equalTo("Boom")))
          } @@ exceptJS(nonFlaky),
          test("propagates error of original stream") {
            for {
              fiber <- (ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) ++ ZStream.fail(new Throwable("Boom")))
                         .mapZIOPar(2)(_ => ZIO.sleep(1.second))
                         .runDrain
                         .fork
              _    <- TestClock.adjust(5.seconds)
              exit <- fiber.await
            } yield assert(exit)(fails(hasMessage(equalTo("Boom"))))
          },
          test("parallelism is not exceeded") {
            val iterations  = 1000
            val parallelism = 64
            for {
              latch <- CountdownLatch.make(parallelism + 1)
              f <- ZStream
                     .range(0, iterations)
                     .mapZIOPar(parallelism, parallelism)(_ => latch.countDown *> latch.await)
                     .runDrain
                     .fork
              _     <- Live.live(latch.count.delay(100.micros)).repeatUntil(_ == 1)
              _     <- latch.countDown
              count <- latch.count
              _     <- f.join
            } yield assertTrue(count == 0)
          } @@ TestAspect.jvmOnly @@ nonFlaky,
          test("accumulates parallel errors") {
            sealed abstract class DbError extends Product with Serializable
            case object Missing           extends DbError
            case object QtyTooLarge       extends DbError

            for {
              latch  <- Promise.make[Nothing, Unit]
              start1 <- Promise.make[Nothing, Unit]
              start2 <- Promise.make[Nothing, Unit]
              f <- ZStream(1 to 2: _*)
                     .mapZIOPar(3) {
                       case 1 => start1.succeed(()) *> latch.await *> ZIO.fail(Missing)
                       case 2 => start2.succeed(()) *> latch.await *> ZIO.fail(QtyTooLarge)
                       case _ => ZIO.succeed(true)
                     }
                     .runDrain
                     .fork
              _    <- start1.await *> start2.await *> latch.succeed(())
              exit <- f.await
            } yield assert(exit)(
              failsCause(
                containsCause[DbError](Cause.fail(Missing)) &&
                  containsCause[DbError](Cause.fail(QtyTooLarge))
              )
            )
          } @@ flaky
        ),
        suite("mapZIOParUnordered")(
          test("foreachParN equivalence") {
            checkN(10)(Gen.small(Gen.listOfN(_)(Gen.byte)), Gen.function(Gen.successes(Gen.byte))) { (data, f) =>
              val s = ZStream.fromIterable(data)

              for {
                l <- s.mapZIOParUnordered(8)(f).runCollect
                r <- ZIO.foreachPar(data)(f).withParallelism(8)
              } yield assert(l.toSet)(equalTo(r.toSet))
            }
          },
          test("mapping with failure is failure") {
            val stream =
              ZStream.fromIterable(0 to 3).mapZIOParUnordered(10)(_ => ZIO.fail("fail"))
            assertZIO(stream.runDrain.exit)(fails(equalTo("fail")))
          } @@ exceptJS(nonFlaky) @@ TestAspect.diagnose(10.seconds),
          test("interruption propagation") {
            for {
              interrupted <- Ref.make(false)
              latch       <- Promise.make[Nothing, Unit]
              fib <-
                ZStream(())
                  .mapZIOParUnordered(1)(_ => (latch.succeed(()) *> ZIO.infinity).onInterrupt(interrupted.set(true)))
                  .runDrain
                  .fork
              _      <- latch.await
              _      <- fib.interrupt
              result <- interrupted.get
            } yield assert(result)(isTrue)
          },
          test("interrupts pending tasks when one of the tasks fails U") {
            for {
              interrupted <- Ref.make(0)
              latch1      <- Promise.make[Nothing, Unit]
              latch2      <- Promise.make[Nothing, Unit]
              result <- ZStream(1, 2, 3)
                          .mapZIOParUnordered(3) {
                            case 1 => (latch1.succeed(()) *> ZIO.never).onInterrupt(interrupted.update(_ + 1))
                            case 2 => (latch2.succeed(()) *> ZIO.never).onInterrupt(interrupted.update(_ + 1))
                            case 3 => latch1.await *> latch2.await *> ZIO.fail("Boom")
                          }
                          .runDrain
                          .exit
              count <- interrupted.get
            } yield assert(count)(equalTo(2)) && assert(result)(fails(equalTo("Boom")))
          } @@ exceptJS(nonFlaky(500)),
          test("awaits children fibers properly") {
            assertZIO(
              ZStream
                .fromIterable((0 to 100))
                .interruptWhen(ZIO.never)
                .mapZIOParUnordered(8)(_ => ZIO.succeed(1).repeatN(2000))
                .runDrain
                .exit
                .map(_.isInterrupted)
            )(equalTo(false))
          },
          test("propagates error of original stream") {
            for {
              fiber <- (ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) ++ ZStream.fail(new Throwable("Boom")))
                         .mapZIOParUnordered(2)(_ => ZIO.sleep(1.second))
                         .runDrain
                         .fork
              _    <- TestClock.adjust(5.seconds)
              exit <- fiber.await
            } yield assert(exit)(fails(hasMessage(equalTo("Boom"))))
          },
          test("parallelism is not exceeded") {
            val iterations = 1000
            checkAll(Gen.fromIterable(Chunk(4, 16, 32, 64))) { parallelism =>
              for {
                latch <- CountdownLatch.make(parallelism + 1)
                f <- ZStream
                       .range(0, iterations)
                       .mapZIOParUnordered(parallelism)(_ => latch.countDown *> latch.await)
                       .runDrain
                       .fork
                _     <- Live.live(latch.count.delay(100.micros)).repeatUntil(_ == 1)
                _     <- latch.countDown
                count <- latch.count
                _     <- f.join
              } yield assertTrue(count == 0)
            }
          } @@ TestAspect.jvmOnly @@ exceptJS(nonFlaky(20)),
          test("accumulates parallel errors") {
            sealed abstract class DbError extends Product with Serializable
            case object Missing           extends DbError
            case object QtyTooLarge       extends DbError

            for {
              latch  <- Promise.make[Nothing, Unit]
              start1 <- Promise.make[Nothing, Unit]
              start2 <- Promise.make[Nothing, Unit]
              f <- ZStream(1 to 2: _*)
                     .mapZIOParUnordered(3) {
                       case 1 => start1.succeed(()) *> latch.await *> ZIO.fail(Missing)
                       case 2 => start2.succeed(()) *> latch.await *> ZIO.fail(QtyTooLarge)
                       case _ => ZIO.succeed(true)
                     }
                     .runDrain
                     .fork
              _    <- start1.await *> start2.await *> latch.succeed(())
              exit <- f.await
            } yield assert(exit)(
              failsCause(
                containsCause[DbError](Cause.fail(Missing)) &&
                  containsCause[DbError](Cause.fail(QtyTooLarge))
              )
            )
          } @@ flaky,
          test("first finished first out") {
            checkN(2)(Gen.small(Gen.chunkOfN(_)(Gen.byte))) { data =>
              val s = ZStream.fromChunk(data).zipWithIndex
              val l = data.length

              for {
                f   <- s.mapZIOParUnordered(8) { case (x, i) => ZIO.succeed(x).delay((l - i).seconds) }.runCollect.fork
                _   <- ZIO.iterate(0)(_ <= l)(i => TestClock.adjust(1.second).as(i + 1))
                res <- f.join
              } yield assert(res)(equalTo(data.reverse))
            }
          }
        ),
        suite("mergeLeft/Right")(
          test("mergeLeft with HaltStrategy.Right terminates as soon as the right stream terminates") {
            for {
              queue1 <- Queue.unbounded[Int]
              queue2 <- Queue.unbounded[Int]
              stream1 = ZStream.fromQueue(queue1)
              stream2 = ZStream.fromQueue(queue2)
              fiber  <- stream1.mergeLeft(stream2, HaltStrategy.Right).runCollect.fork
              _      <- queue1.offer(1) *> TestClock.adjust(1.second)
              _      <- queue1.offer(2) *> TestClock.adjust(1.second)
              _      <- queue2.shutdown *> TestClock.adjust(1.second)
              _      <- queue1.offer(3)
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(1, 2)))
          },
          test("mergeRight with HaltStrategy.Left terminates as soon as the left stream terminates") {
            for {
              queue1 <- Queue.unbounded[Int]
              queue2 <- Queue.unbounded[Int]
              stream1 = ZStream.fromQueue(queue1)
              stream2 = ZStream.fromQueue(queue2)
              fiber  <- stream1.mergeRight(stream2, HaltStrategy.Left).runCollect.fork
              _      <- queue2.offer(1) *> TestClock.adjust(1.second)
              _      <- queue1.offer(2) *> TestClock.adjust(1.second)
              _      <- queue1.shutdown *> TestClock.adjust(1.second)
              _      <- queue2.offer(3)
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(1)))
          }
        ),
        suite("mergeHaltLeft")(
          test("terminates as soon as the first stream terminates") {
            for {
              queue1 <- Queue.unbounded[Int]
              queue2 <- Queue.unbounded[Int]
              stream1 = ZStream.fromQueue(queue1)
              stream2 = ZStream.fromQueue(queue2)
              fiber  <- stream1.mergeHaltLeft(stream2).runCollect.fork
              _      <- queue1.offer(1) *> TestClock.adjust(1.second)
              _      <- queue1.offer(2) *> TestClock.adjust(1.second)
              _      <- queue1.shutdown *> TestClock.adjust(1.second)
              _      <- queue2.offer(3)
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(1, 2)))
          },
          test("interrupts pulling on finish") {
            val s1 = ZStream(1, 2, 3)
            val s2 = ZStream.fromZIO(Clock.sleep(5.seconds).as(4))
            assertZIO(s1.mergeHaltLeft(s2).runCollect)(equalTo(Chunk(1, 2, 3)))
          }
        ),
        suite("mergeHaltRight")(
          test("terminates as soon as the second stream terminates") {
            for {
              queue1 <- Queue.unbounded[Int]
              queue2 <- Queue.unbounded[Int]
              stream1 = ZStream.fromQueue(queue1)
              stream2 = ZStream.fromQueue(queue2)
              fiber  <- stream1.mergeHaltRight(stream2).runCollect.fork
              _      <- queue2.offer(2) *> TestClock.adjust(1.second)
              _      <- queue2.offer(3) *> TestClock.adjust(1.second)
              _      <- queue2.shutdown *> TestClock.adjust(1.second)
              _      <- queue1.offer(1)
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(2, 3)))
          } @@ exceptJS
        ),
        suite("mergeHaltEither")(
          test("terminates as soon as either stream terminates") {
            for {
              queue1 <- Queue.unbounded[Int]
              queue2 <- Queue.unbounded[Int]
              stream1 = ZStream.fromQueue(queue1)
              stream2 = ZStream.fromQueue(queue2)
              fiber  <- stream1.mergeHaltEither(stream2).runCollect.fork
              _      <- queue1.shutdown
              _      <- TestClock.adjust(1.second)
              _      <- queue2.offer(1)
              result <- fiber.join
            } yield assert(result)(isEmpty)
          }
        ),
        test("mergeSorted") {
          val genSorted = for {
            chunk  <- Gen.chunkOf(Gen.int(1, 100))
            chunks <- splitChunks(Chunk(chunk.sorted))
          } yield ZStream.fromChunks(chunks: _*)
          check(genSorted, genSorted) { (left, right) =>
            for {
              actual   <- left.mergeSorted(right).runCollect
              expected <- (left ++ right).runCollect.map(_.sorted)
            } yield assertTrue(actual == expected)
          }
        },
        suite("mergeWith")(
          test("equivalence with set union")(check(streamOfInts, streamOfInts) {
            (s1: ZStream[Any, String, Int], s2: ZStream[Any, String, Int]) =>
              for {
                mergedStream <- (s1 merge s2).runCollect.map(_.toSet).exit
                mergedLists <- s1.runCollect
                                 .zipWith(s2.runCollect)((left, right) => left ++ right)
                                 .map(_.toSet)
                                 .exit
              } yield assert(!mergedStream.isSuccess && !mergedLists.isSuccess)(isTrue) || assert(
                mergedStream
              )(
                equalTo(mergedLists)
              )
          }),
          test("fail as soon as one stream fails") {
            assertZIO(ZStream(1, 2, 3).merge(ZStream.fail(())).runCollect.exit.map(_.isSuccess))(
              equalTo(false)
            )
          } @@ exceptJS(nonFlaky(20)),
          test("prioritizes failure") {
            val s1 = ZStream.never
            val s2 = ZStream.fail("Ouch")

            assertZIO(s1.mergeWith(s2)(_ => (), _ => ()).runCollect.either)(isLeft(equalTo("Ouch")))
          }
        ),
        suite("orElseIfEmpty")(
          test("produce default value if stream is empty")(
            assertZIO(ZStream().orElseIfEmpty(0).runCollect)(equalTo(Chunk(0)))
          ),
          test("consume default stream if stream is empty")(
            assertZIO(ZStream().orElseIfEmpty(ZStream.range(0, 5)).runCollect)(equalTo(Chunk(0, 1, 2, 3, 4)))
          ),
          test("ignore default value when stream is not empty")(
            assertZIO(ZStream(1).orElseIfEmpty(0).runCollect)(equalTo(Chunk(1)))
          ),
          test("should throw correct error from default stream")(
            assertZIO(ZStream().orElseIfEmpty(ZStream.fail("Ouch")).runCollect.either)(isLeft(equalTo("Ouch")))
          )
        ),
        suite("partitionEither")(
          test("allows repeated runs without hanging") {
            val stream = ZIO.scoped {
              ZStream
                .fromIterable[Int](Seq.empty)
                .partitionEither(i => ZIO.succeed(if (i % 2 == 0) Left(i) else Right(i)))
                .map { case (evens, odds) => evens.mergeEither(odds) }
                .flatMap(_.runCollect)
            }
            assertZIO(ZIO.collectAll(Range(0, 100).toList.map(_ => stream)).map(_ => 0))(equalTo(0))
          },
          test("values") {
            ZIO.scoped {
              ZStream
                .range(0, 6)
                .partitionEither { i =>
                  if (i % 2 == 0) ZIO.succeed(Left(i))
                  else ZIO.succeed(Right(i))
                }
                .flatMap { case (s1, s2) =>
                  for {
                    out1 <- s1.runCollect
                    out2 <- s2.runCollect
                  } yield assert(out1)(equalTo(Chunk(0, 2, 4))) && assert(out2)(
                    equalTo(Chunk(1, 3, 5))
                  )
                }
            }
          },
          test("errors") {
            ZIO.scoped {
              (ZStream.range(0, 1) ++ ZStream.fail("Boom")).partitionEither { i =>
                if (i % 2 == 0) ZIO.succeed(Left(i))
                else ZIO.succeed(Right(i))
              }.flatMap { case (s1, s2) =>
                for {
                  out1 <- s1.runCollect.either
                  out2 <- s2.runCollect.either
                } yield assert(out1)(isLeft(equalTo("Boom"))) && assert(out2)(
                  isLeft(equalTo("Boom"))
                )
              }
            }
          },
          test("backpressure") {
            ZIO.scoped {
              ZStream
                .range(0, 6)
                .partitionEither(
                  i =>
                    if (i % 2 == 0) ZIO.succeed(Left(i))
                    else ZIO.succeed(Right(i)),
                  1
                )
                .flatMap { case (s1, s2) =>
                  for {
                    ref    <- Ref.make[List[Int]](Nil)
                    latch1 <- Promise.make[Nothing, Unit]
                    fib <- s1
                             .tap(i => ref.update(i :: _) *> latch1.succeed(()).when(i == 2))
                             .runDrain
                             .fork
                    _         <- latch1.await
                    snapshot1 <- ref.get
                    other     <- s2.runCollect
                    _         <- fib.await
                    snapshot2 <- ref.get
                  } yield assert(snapshot1)(equalTo(List(2, 0))) && assert(snapshot2)(
                    equalTo(List(4, 2, 0))
                  ) && assert(
                    other
                  )(
                    equalTo(
                      Chunk(
                        1,
                        3,
                        5
                      )
                    )
                  )
                }
            }
          }
        ),
        suite("peel")(
          test("peel") {
            val sink: ZSink[Any, Nothing, Int, Int, Any] = ZSink.take(3)

            ZIO.scoped {
              ZStream.fromChunks(Chunk(1, 2, 3), Chunk(4, 5, 6)).peel(sink).flatMap { case (chunk, rest) =>
                rest.runCollect.map { rest =>
                  assert(chunk)(equalTo(Chunk(1, 2, 3))) &&
                  assert(rest)(equalTo(Chunk(4, 5, 6)))
                }
              }
            }
          },
          test("propagates errors") {
            val stream = ZStream.repeatZIO(ZIO.fail("fail"))
            val sink   = ZSink.fold[Byte, Chunk[Byte]](Chunk.empty)(_ => true)(_ :+ _)
            for {
              exit <- stream.peel(sink).exit
            } yield assert(exit)(fails(equalTo("fail")))
          },
          test("preserves the scope") {
            for {
              ref     <- Ref.make[Chunk[String]](Chunk.empty)
              latch   <- Promise.make[Nothing, Unit]
              resource = (i: Int) => ZIO.acquireRelease(ZIO.succeed(i))(i => ref.update(_ :+ s"closing $i"))
              fiber <- ZStream.unwrapScoped {
                         ZStream
                           .scoped(ZIO.foreach(1 to 5)(resource))
                           .flattenIterables
                           .rechunk(10)
                           .peel(ZSink.head[Int])
                           .map { case (head, tail) =>
                             (ZStream.fromIterable(head) ++ tail)
                               .mapZIO(i => latch.succeed(()) *> ref.update(_ :+ s"processing $i").as(i))
                               .schedule(Schedule.spaced(1.second))
                           }
                       }.runDrain.fork
              _     <- latch.await
              _     <- TestClock.adjust(5.seconds)
              _     <- fiber.join
              value <- ref.get
            } yield assertTrue(
              value == Chunk(
                "processing 1",
                "processing 2",
                "processing 3",
                "processing 4",
                "processing 5",
                "closing 5",
                "closing 4",
                "closing 3",
                "closing 2",
                "closing 1"
              )
            )
          },
          test("failing sink") {
            val failingSink = ZSink.fail("it's really bad man!")
            val ex = ZIO.scoped[Any] {
              ZStream
                .range(0, 40, 10)
                .peel(failingSink)
                .map(_._1)
                .exit
            }

            ex.map { ex1 =>
              assertTrue {
                ex1.isFailure &&
                ex1.causeOption
                  .flatMap(_.failureOption) ==
                  Some("it's really bad man!")
              }
            }
          },
          test("dying sink") {
            case object Death extends RuntimeException("not today!")
            val dyingSink: ZSink[Any, Nothing, Any, Nothing, Nothing] = ZSink.failCause(Cause.die(Death))
            val ex: ZIO[Any, Nothing, Exit[Nothing, Any]] = ZIO.scoped[Any] {
              ZStream
                .range(0, 40, 10)
                .peel(dyingSink)
                .map(_._1)
                .exit
            }

            ex.map { ex1 =>
              assertTrue {
                ex1.isFailure &&
                ex1.causeOption.flatMap(_.dieOption) ==
                  Some(Death)
              }
            }
          } @@ zio.test.TestAspect.timeout(5.seconds)
        ),
        test("onError") {
          for {
            flag   <- Ref.make(false)
            exit   <- ZStream.fail("Boom").onError(_ => flag.set(true)).runDrain.exit
            called <- flag.get
          } yield assert(called)(isTrue) && assert(exit)(fails(equalTo("Boom")))
        } @@ zioTag(errors),
        test("orElse") {
          val s1 = ZStream(1, 2, 3) ++ ZStream.fail("Boom")
          val s2 = ZStream(4, 5, 6)
          s1.orElse(s2).runCollect.map(assert(_)(equalTo(Chunk(1, 2, 3, 4, 5, 6))))
        },
        test("orElseEither") {
          val s1 = ZStream.succeed(1) ++ ZStream.fail("Boom")
          val s2 = ZStream.succeed(2)
          s1.orElseEither(s2).runCollect.map(assert(_)(equalTo(Chunk(Left(1), Right(2)))))
        },
        test("orElseFail") {
          val s1 = ZStream.succeed(1) ++ ZStream.fail("Boom")
          s1.orElseFail("Boomer").runCollect.either.map(assert(_)(isLeft(equalTo("Boomer"))))
        },
        test("orElseOptional") {
          val s1 = ZStream.succeed(1) ++ ZStream.fail(None)
          val s2 = ZStream.succeed(2)
          s1.orElseOptional(s2).runCollect.map(assert(_)(equalTo(Chunk(1, 2))))
        },
        test("orElseSucceed") {
          val s1 = ZStream.succeed(1) ++ ZStream.fail("Boom")
          s1.orElseSucceed(2).runCollect.map(assert(_)(equalTo(Chunk(1, 2))))
        },
        test("provide") {
          val stream = ZStream.fromZIO(ZIO.service[Int])
          val layer  = ZLayer.succeed(1)
          assertZIO(stream.provideLayer(layer).runDrain)(Assertion.anything)
        },
        suite("repeat")(
          test("repeat")(
            assertZIO(
              ZStream(1)
                .repeat(Schedule.recurs(4))
                .runCollect
            )(equalTo(Chunk(1, 1, 1, 1, 1)))
          ),
          test("short circuits")(
            for {
              ref <- Ref.make[List[Int]](Nil)
              fiber <- ZStream
                         .fromZIO(ref.update(1 :: _))
                         .repeat(Schedule.spaced(10.millis))
                         .take(2)
                         .runDrain
                         .fork
              _      <- TestClock.adjust(50.millis)
              _      <- fiber.join
              result <- ref.get
            } yield assert(result)(equalTo(List(1, 1)))
          ),
          test("does not swallow errors on a repetition") {
            Ref.make(0).flatMap { counter =>
              ZStream
                .fromZIO(
                  counter.getAndUpdate(_ + 1).flatMap {
                    case i if i <= 2 => ZIO.succeed(i)
                    case _           => ZIO.fail("Boom")
                  }
                )
                .repeat(Schedule.recurs(3))
                .runDrain
                .exit
                .map(assert(_)(fails(equalTo("Boom"))))
            }
          }
        ),
        suite("repeatEither")(
          test("emits schedule output")(
            assertZIO(
              ZStream(1L)
                .repeatEither(Schedule.recurs(4))
                .runCollect
            )(
              equalTo(
                Chunk(
                  Right(1L),
                  Right(1L),
                  Left(0L),
                  Right(1L),
                  Left(1L),
                  Right(1L),
                  Left(2L),
                  Right(1L),
                  Left(3L)
                )
              )
            )
          ),
          test("short circuits") {
            for {
              ref <- Ref.make[List[Int]](Nil)
              fiber <- ZStream
                         .fromZIO(ref.update(1 :: _))
                         .repeatEither(Schedule.spaced(10.millis))
                         .take(3) // take one schedule output
                         .runDrain
                         .fork
              _      <- TestClock.adjust(50.millis)
              _      <- fiber.join
              result <- ref.get
            } yield assert(result)(equalTo(List(1, 1)))
          }
        ),
        test("right") {
          val s1 = ZStream.succeed(Right(1)) ++ ZStream.succeed(Left(0))
          s1.right.runCollect.either.map(assert(_)(isLeft(equalTo(None))))
        },
        test("rightOrFail") {
          val s1 = ZStream.succeed(Right(1)) ++ ZStream.succeed(Left(0))
          s1.rightOrFail(-1).runCollect.either.map(assert(_)(isLeft(equalTo(-1))))
        },
        suite("runHead")(
          test("nonempty stream")(
            assertZIO(ZStream(1, 2, 3, 4).runHead)(equalTo(Some(1)))
          ),
          test("empty stream")(
            assertZIO(ZStream.empty.runHead)(equalTo(None))
          ),
          test("Pulls up to the first non-empty chunk") {
            for {
              ref <- Ref.make[List[Int]](Nil)
              head <- ZStream(
                        ZStream.fromZIO(ref.update(1 :: _)).drain,
                        ZStream.fromZIO(ref.update(2 :: _)).drain,
                        ZStream(1),
                        ZStream.fromZIO(ref.update(3 :: _))
                      ).flatten.runHead
              result <- ref.get
            } yield assert(head)(isSome(equalTo(1))) && assert(result)(equalTo(List(2, 1)))
          }
        ),
        suite("runLast")(
          test("nonempty stream")(
            assertZIO(ZStream(1, 2, 3, 4).runLast)(equalTo(Some(4)))
          ),
          test("empty stream")(
            assertZIO(ZStream.empty.runLast)(equalTo(None))
          )
        ),
        suite("runScoped")(
          test("properly closes the resources")(
            for {
              closed <- Ref.make[Boolean](false)
              res     = ZIO.acquireRelease(ZIO.succeed(1))(_ => closed.set(true))
              stream  = ZStream.scoped(res).flatMap(a => ZStream(a, a, a))
              collectAndCheck <- ZIO.scoped {
                                   stream
                                     .runScoped(ZSink.collectAll)
                                     .flatMap(r => closed.get.map((r, _)))
                                 }
              (result, state) = collectAndCheck
              finalState     <- closed.get
            } yield {
              assert(result)(equalTo(Chunk(1, 1, 1))) && assert(state)(isFalse) && assert(finalState)(isTrue)
            }
          )
        ),
        suite("scan")(
          test("scan")(check(pureStreamOfInts) { s =>
            for {
              streamResult <- s.scan(0)(_ + _).runCollect
              chunkResult  <- s.runCollect.map(_.scan(0)(_ + _))
            } yield assert(streamResult)(equalTo(chunkResult))
          })
        ),
        suite("scanReduce")(
          test("scanReduce")(check(pureStreamOfInts) { s =>
            for {
              streamResult <- s.scanReduce(_ + _).runCollect
              chunkResult  <- s.runCollect.map(_.scan(0)(_ + _).tail)
            } yield assert(streamResult)(equalTo(chunkResult))
          })
        ),
        suite("schedule")(
          test("schedule") {
            for {
              start <- Clock.currentTime(TimeUnit.MILLISECONDS)
              fiber <- ZStream
                         .range(1, 9)
                         .schedule(Schedule.fixed(100.milliseconds))
                         .mapZIO(n => Clock.currentTime(TimeUnit.MILLISECONDS).map(now => (n, now - start)))
                         .runCollect
                         .fork
              _       <- TestClock.adjust(800.millis)
              actual  <- fiber.join
              expected = Chunk((1, 100L), (2, 200L), (3, 300L), (4, 400L), (5, 500L), (6, 600L), (7, 700L), (8, 800L))
            } yield assertTrue(actual == expected)
          },
          test("scheduleWith")(
            assertZIO(
              ZStream("A", "B", "C", "A", "B", "C")
                .scheduleWith(Schedule.recurs(2) *> Schedule.fromFunction((_) => "Done"))(
                  _.toLowerCase,
                  identity
                )
                .runCollect
            )(equalTo(Chunk("a", "b", "c", "Done", "a", "b", "c", "Done")))
          ),
          test("scheduleEither")(
            assertZIO(
              ZStream("A", "B", "C")
                .scheduleEither(Schedule.recurs(2) *> Schedule.fromFunction((_) => "!"))
                .runCollect
            )(equalTo(Chunk(Right("A"), Right("B"), Right("C"), Left("!"))))
          )
        ),
        suite("repeatElements")(
          test("repeatElementsWith")(
            assertZIO(
              ZStream("A", "B", "C")
                .repeatElementsWith(Schedule.recurs(0) *> Schedule.fromFunction((_) => 123))(
                  identity,
                  _.toString
                )
                .runCollect
            )(equalTo(Chunk("A", "123", "B", "123", "C", "123")))
          ),
          test("repeatElementsEither")(
            assertZIO(
              ZStream("A", "B", "C")
                .repeatElementsEither(Schedule.recurs(0) *> Schedule.fromFunction((_) => 123))
                .runCollect
            )(equalTo(Chunk(Right("A"), Left(123), Right("B"), Left(123), Right("C"), Left(123))))
          ),
          test("repeated && assertspaced")(
            assertZIO(
              ZStream("A", "B", "C")
                .repeatElements(Schedule.once)
                .runCollect
            )(equalTo(Chunk("A", "A", "B", "B", "C", "C")))
          ),
          test("short circuits in schedule")(
            assertZIO(
              ZStream("A", "B", "C")
                .repeatElements(Schedule.once)
                .take(4)
                .runCollect
            )(equalTo(Chunk("A", "A", "B", "B")))
          ),
          test("short circuits after schedule")(
            assertZIO(
              ZStream("A", "B", "C")
                .repeatElements(Schedule.once)
                .take(3)
                .runCollect
            )(equalTo(Chunk("A", "A", "B")))
          )
        ),
        suite("rechunk")(
          test("to smaller chunks") {
            val chunks = Chunk(
              Chunk(1, 2, 3, 4, 5),
              Chunk(6, 7, 8, 9, 10)
            )

            val stream = ZStream.fromChunks(chunks: _*)
            assertZIO(stream.rechunk(2).chunks.runCollect)(
              equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6), Chunk(7, 8), Chunk(9, 10)))
            )
          },
          test("to larger chunks") {
            val chunks = Chunk(
              Chunk(1, 2),
              Chunk(3, 4),
              Chunk(5, 6),
              Chunk(7, 8),
              Chunk(9, 10)
            )

            val stream = ZStream.fromChunks(chunks: _*)
            assertZIO(stream.rechunk(5).chunks.runCollect)(
              equalTo(Chunk(Chunk(1, 2, 3, 4, 5), Chunk(6, 7, 8, 9, 10)))
            )
          },
          test("to the same size chunks") {
            val chunks = Chunk(
              Chunk(1, 2),
              Chunk(3, 4),
              Chunk(5, 6),
              Chunk(7, 8),
              Chunk(9, 10)
            )

            val stream = ZStream.fromChunks(chunks: _*)
            assertZIO(stream.rechunk(2).chunks.runCollect)(
              equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6), Chunk(7, 8), Chunk(9, 10)))
            )
          },
          test("remainder is emitted on completion") {
            val chunks = Chunk(
              Chunk(1, 2),
              Chunk(3, 4),
              Chunk(5, 6),
              Chunk(7, 8),
              Chunk(9, 10)
            )

            val stream = ZStream.fromChunks(chunks: _*)
            assertZIO(stream.rechunk(3).chunks.runCollect)(
              equalTo(Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8, 9), Chunk(10)))
            )
          },
          test("buffer is emitted on error") {
            val chunks = Chunk(
              Chunk(Right(1), Right(2)),
              Chunk(Left("Boom!"), Right(3))
            )

            val stream = ZStream.fromChunks(chunks: _*).absolve
            assertZIO(stream.rechunk(3).either.chunks.runCollect)(
              equalTo(Chunk(Chunk(Right(1), Right(2)), Chunk(Left("Boom!"))))
            )
          },
          test("to chunk size 1") {
            val chunks = Chunk(
              Chunk(1, 2, 3, 4, 5),
              Chunk(6, 7, 8, 9, 10)
            )

            val stream = ZStream.fromChunks(chunks: _*)
            assertZIO(stream.rechunk(1).chunks.runCollect)(
              equalTo(
                Chunk(
                  Chunk(1),
                  Chunk(2),
                  Chunk(3),
                  Chunk(4),
                  Chunk(5),
                  Chunk(6),
                  Chunk(7),
                  Chunk(8),
                  Chunk(9),
                  Chunk(10)
                )
              )
            )
          }
        ),
        suite("retry")(
          test("retry a failing stream") {
            assertZIO(
              for {
                ref     <- Ref.make(0)
                stream   = ZStream.fromZIO(ref.getAndUpdate(_ + 1)) ++ ZStream.fail(None)
                results <- stream.retry(Schedule.forever).take(2).runCollect
              } yield results
            )(equalTo(Chunk(0, 1)))
          },
          test("cleanup resources before restarting the stream") {
            assertZIO(
              for {
                finalized <- Ref.make(0)
                stream = ZStream.unwrapScoped(
                           ZIO
                             .addFinalizer(finalized.getAndUpdate(_ + 1))
                             .as(ZStream.fromZIO(finalized.get) ++ ZStream.fail(None))
                         )
                results <- stream.retry(Schedule.forever).take(2).runCollect
              } yield results
            )(equalTo(Chunk(0, 1)))
          },
          test("retry a failing stream according to a schedule") {
            for {
              times <- Ref.make(List.empty[java.time.Instant])
              stream =
                ZStream
                  .fromZIO(Clock.instant.flatMap(time => times.update(time +: _)))
                  .flatMap(_ => ZStream.fail(None))
              streamFib <- stream.retry(Schedule.exponential(1.second)).take(3).runDrain.fork
              _         <- TestClock.adjust(1.second)
              _         <- TestClock.adjust(2.second)
              _         <- streamFib.interrupt
              results   <- times.get.map(_.map(_.getEpochSecond.toInt))
            } yield assert(results)(equalTo(List(3, 1, 0)))
          },
          test("reset the schedule after a successful pull") {
            for {
              times <- Ref.make(List.empty[java.time.Instant])
              ref   <- Ref.make(0)
              stream =
                ZStream
                  .fromZIO(Clock.instant.flatMap(time => times.update(time +: _) *> ref.updateAndGet(_ + 1)))
                  .flatMap { attemptNr =>
                    if (attemptNr == 3 || attemptNr == 5) ZStream.succeed(attemptNr) else ZStream.fail(None)
                  }
                  .forever
              streamFib <- stream
                             .retry(Schedule.exponential(1.second))
                             .take(2)
                             .runDrain
                             .fork
              _       <- TestClock.adjust(1.second)
              _       <- TestClock.adjust(2.second)
              _       <- TestClock.adjust(1.second)
              _       <- streamFib.join
              results <- times.get.map(_.map(_.getEpochSecond.toInt))
            } yield assert(results)(equalTo(List(4, 3, 3, 1, 0)))
          }
        ),
        suite("serviceWith")(
          test("serviceWith") {
            trait A {
              def live: UIO[Int]
            }

            val ref: Chunk[Int] = Chunk(10)

            ZStream
              .serviceWithZIO[A](_.live)
              .provideLayer(ZLayer.succeed(new A {
                override def live: UIO[Int] = ZIO.succeed(10)
              }))
              .runCollect
              .map(result => assertTrue(result == ref))
          },
          test("serviceWithStream") {
            trait A {
              def live: ZStream[Any, Nothing, Int]
            }

            val numbers = 0 to 10

            ZStream
              .serviceWithStream[A](_.live)
              .provideLayer(ZLayer.succeed(new A {
                override def live: ZStream[Any, Nothing, Int] =
                  ZStream.fromIterable(numbers)
              }))
              .runCollect
              .map(result => assertTrue(result == Chunk.fromIterable(numbers)))
          }
        ),
        test("some") {
          val s1 = ZStream.succeed(Some(1)) ++ ZStream.succeed(None)
          s1.some.runCollect.either.map(assert(_)(isLeft(isNone)))
        },
        suite("someOrElse")(
          test("falls back to the default value if None") {
            val s1 = ZStream.succeed(Some(1)) ++ ZStream.succeed(None)
            s1.someOrElse(-1).runCollect.map(assert(_)(equalTo(Chunk(1, -1))))
          },
          test("works when the default is an instance of a covariant type constructor applied to Nothing") {
            val stream =
              ZStream.succeed(Some(List("s"))) ++ ZStream.succeed(Option.empty[List[String]])
            stream.someOrElse(List.empty).runCollect.map(assert(_)(equalTo(Chunk(List("s"), List.empty))))
          }
        ),
        test("someOrFail") {
          val s1 = ZStream.succeed(Some(1)) ++ ZStream.succeed(None)
          s1.someOrFail(-1).runCollect.either.map(assert(_)(isLeft(equalTo(-1))))
        },
        suite("sliding")(
          test("returns a sliding window") {
            val result  = Chunk(Chunk(1, 2), Chunk(2, 3), Chunk(3, 4), Chunk(4, 5))
            val stream0 = ZStream.fromChunks(Chunk.empty, Chunk(1), Chunk.empty, Chunk(2, 3, 4, 5))
            val stream1 = ZStream.empty ++ ZStream(1) ++ ZStream.empty ++ ZStream(2) ++ ZStream(3, 4, 5)
            val stream2 = ZStream(1) ++ ZStream.empty ++ ZStream(2) ++ ZStream.empty ++ ZStream(3, 4, 5)
            val stream3 = ZStream.fromChunk(Chunk(1)) ++ ZStream.fromChunk(Chunk(2)) ++ ZStream(3, 4, 5)

            for {
              result1 <- ZStream(1, 2, 3, 4, 5).sliding(2).runCollect
              result2 <- stream0.sliding(2).runCollect
              result3 <- stream1.sliding(2).runCollect
              result4 <- stream2.sliding(2).runCollect
              result5 <- stream3.sliding(2).runCollect
            } yield assert(result1)(equalTo(result)) &&
              assert(result2)(equalTo(result)) &&
              assert(result3)(equalTo(result)) &&
              assert(result4)(equalTo(result)) &&
              assert(result5)(equalTo(result))
          },
          test("returns all elements if chunkSize is greater than the size of the stream") {
            assertZIO(
              ZStream(1, 2, 3, 4, 5).sliding(6).runCollect
            )(equalTo(Chunk(Chunk(1, 2, 3, 4, 5))))
          },
          test("is equivalent to Array#sliding") {
            check(pureStreamOfInts, Gen.int(1, 100), Gen.int(1, 10)) { case (stream, chunkSize, stepSize) =>
              for {
                result1 <- stream.sliding(chunkSize, stepSize).runCollect
                partial <- stream.runCollect
                arrays   = partial.toArray.sliding(chunkSize, stepSize).toArray
                result2  = Chunk.fromArray(arrays.map(Chunk.fromArray(_)))
              } yield assert(result1)(equalTo(result2))
            }
          },
          test("is mostly equivalent to ZStream#grouped when stepSize and chunkSize are equal") {
            check(pureStreamOfInts, Gen.int(1, 100)) { case (stream, chunkSize) =>
              for {
                result1 <- stream.sliding(chunkSize, chunkSize).runCollect
                result2 <- stream.grouped(chunkSize).runCollect
              } yield assert(result1)(equalTo(result2))
            }
          },
          test("fails if upstream produces an error") {
            assertZIO(
              (ZStream(1, 2, 3) ++ ZStream.fail("Ouch") ++ ZStream(4, 5))
                .sliding(2)
                .runCollect
                .either
            )(isLeft(equalTo("Ouch")))
          },
          test("should return empty chunk when stream is empty") {
            assertZIO(
              ZStream().sliding(2).runCollect
            )(equalTo(Chunk()))
          },
          test("emits elements properly when a failure occurs") {
            for {
              ref         <- Ref.make[Chunk[Chunk[Int]]](Chunk.empty)
              streamChunks = ZStream.fromChunks(Chunk(1, 2, 3, 4), Chunk(5, 6, 7), Chunk(8))
              stream       = (streamChunks ++ ZStream.fail("Ouch")).sliding(3, 3)
              expected     = Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8))
              either      <- stream.mapZIO(chunk => ref.update(_ :+ chunk)).runCollect.either
              result      <- ref.get
            } yield assert(either)(isLeft(equalTo("Ouch"))) && assert(result)(equalTo(expected))
          }
        ),
        suite("split")(
          test("should split properly") {
            val expected0 = Chunk(Chunk(1, 2, 3), Chunk(5, 6, 7), Chunk(9))
            val chunks    = Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6), Chunk(7, 8, 9), Chunk(10))
            val expected1 = Chunk(Chunk(1, 2), Chunk(4, 5), Chunk(7, 8), Chunk(10))

            for {
              result0 <- ZStream.range(0, 10).split(_ % 4 == 0).runCollect
              result1 <- ZStream.fromChunks(chunks: _*).split(_ % 3 == 0).runCollect
            } yield assert(result0)(equalTo(expected0)) && assert(result1)(equalTo(expected1))
          },
          test("is equivalent to identity when predicate isn't satisfied") {
            check(pureStreamGen(Gen.int(1, 10), 10)) { stream =>
              for {
                result1 <- stream.split(_ % 11 == 0).runCollect
                partial <- stream.runCollect
                result2  = Chunk.single(partial).filter(_.nonEmpty)
              } yield assert(result1)(equalTo(result2))
            }
          },
          test("should output empty chunk when stream is empty") {
            assertZIO(ZStream[Int]().split(_ % 11 == 0).runCollect)(equalTo(Chunk()))
          }
        ),
        suite("splitOnChunk")(
          test("consecutive delimiter yields empty Chunk") {
            val input         = ZStream.apply(Chunk(1, 2), Chunk(1), Chunk(2, 1, 2, 3, 1, 2), Chunk(1, 2))
            val splitSequence = Chunk(1, 2)
            assertZIO(input.flattenChunks.splitOnChunk(splitSequence).map(_.size).runCollect)(
              equalTo(Chunk(0, 0, 0, 1, 0))
            )
          },
          test("preserves data")(check(Gen.chunkOf(Gen.byte.filter(_ != 0.toByte))) { bytes =>
            val splitSequence = Chunk[Byte](0, 1)
            val data          = bytes.flatMap(_ +: splitSequence)
            assertZIO(ZStream.fromChunks(data).splitOnChunk(splitSequence).runCollect.map(_.flatten))(
              equalTo(bytes)
            )
          }),
          test("handles leftovers") {
            val splitSequence = Chunk(0, 1)
            assertZIO(ZStream.fromChunks(Chunk(1, 0, 2, 0, 1, 2), Chunk(2)).splitOnChunk(splitSequence).runCollect)(
              equalTo(Chunk(Chunk(1, 0, 2), Chunk(2, 2)))
            )
          },
          test("works") {
            val splitSequence = Chunk(0, 1)
            assertZIO(
              ZStream(1, 2, 0, 1, 3, 4, 0, 1, 5, 6, 5, 6)
                .splitOnChunk(splitSequence)
                .runCollect
            )(equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6, 5, 6))))
          },
          test("works from Chunks") {
            val splitSequence = Chunk(0, 1)
            assertZIO(
              ZStream
                .fromChunks(Chunk(1, 2), splitSequence, Chunk(3, 4), splitSequence, Chunk(5, 6), Chunk(5, 6))
                .splitOnChunk(splitSequence)
                .runCollect
            )(equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6, 5, 6))))
          },
          test("single delimiter edgecase") {
            assertZIO(
              ZStream(0)
                .splitOnChunk(Chunk(0))
                .runCollect
            )(equalTo(Chunk(Chunk())))
          },
          test("no delimiter in data") {
            assertZIO(
              ZStream
                .fromChunks(Chunk(1, 2), Chunk(1, 2), Chunk(1, 2))
                .splitOnChunk(Chunk(1, 1))
                .runCollect
            )(equalTo(Chunk(Chunk(1, 2, 1, 2, 1, 2))))
          },
          test("delimiter on the boundary") {
            assertZIO(
              ZStream
                .fromChunks(Chunk(1, 2), Chunk(1, 2))
                .splitOnChunk(Chunk(2, 1))
                .runCollect
            )(equalTo(Chunk(Chunk(1), Chunk(2))))
          }
        ),
        suite("take")(
          test("take")(check(streamOfInts, Gen.int) { (s, n) =>
            for {
              takeStreamResult <- s.take(n.toLong).runCollect.exit
              takeListResult   <- s.runCollect.map(_.take(n)).exit
            } yield assert(takeListResult.isSuccess)(isTrue) implies assert(takeStreamResult)(
              equalTo(takeListResult)
            )
          }),
          test("take short circuits")(
            for {
              ran    <- Ref.make(false)
              stream  = (ZStream(1) ++ ZStream.fromZIO(ran.set(true)).drain).take(0)
              _      <- stream.runDrain
              result <- ran.get
            } yield assert(result)(isFalse)
          ),
          test("take(0) short circuits")(
            for {
              units <- ZStream.never.take(0).runCollect
            } yield assert(units)(equalTo(Chunk.empty))
          ),
          test("take(1) short circuits")(
            for {
              ints <- (ZStream(1) ++ ZStream.never).take(1).runCollect
            } yield assert(ints)(equalTo(Chunk(1)))
          )
        ),
        test("takeRight") {
          check(pureStreamOfInts, Gen.int(1, 4)) { (s, n) =>
            for {
              streamTake <- s.takeRight(n).runCollect
              chunkTake  <- s.runCollect.map(_.takeRight(n))
            } yield assert(streamTake)(equalTo(chunkTake))
          }
        },
        test("takeUntil") {
          check(streamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
            for {
              streamTakeUntil <- s.takeUntil(p).runCollect.exit
              chunkTakeUntil <- s.runCollect
                                  .map(as => as.takeWhile(!p(_)) ++ as.dropWhile(!p(_)).take(1))
                                  .exit
            } yield assert(chunkTakeUntil.isSuccess)(isTrue) implies assert(streamTakeUntil)(
              equalTo(chunkTakeUntil)
            )
          }
        },
        test("takeUntilZIO") {
          check(streamOfInts, Gen.function(Gen.successes(Gen.boolean))) { (s, p) =>
            for {
              streamTakeUntilZIO <- s.takeUntilZIO(p).runCollect.exit
              chunkTakeUntilZIO <- s.runCollect
                                     .flatMap(as =>
                                       as.takeWhileZIO(p(_).map(!_))
                                         .zipWith(as.dropWhileZIO(p(_).map(!_)).map(_.take(1)))(_ ++ _)
                                     )
                                     .exit
            } yield assert(chunkTakeUntilZIO.isSuccess)(isTrue) implies assert(streamTakeUntilZIO)(
              equalTo(chunkTakeUntilZIO)
            )
          }
        },
        test("takeUntilZIO - laziness on chunks") {
          assertZIO(
            ZStream(1, 2, 3).takeUntilZIO {
              case 2 => ZIO.fail("boom")
              case _ => ZIO.succeed(false)
            }.either.runCollect
          )(equalTo(Chunk(Right(1), Left("boom"))))
        },
        suite("takeWhile")(
          test("takeWhile")(check(streamOfInts, Gen.function(Gen.boolean)) { (s, p) =>
            for {
              streamTakeWhile <- s.takeWhile(p).runCollect.exit
              chunkTakeWhile  <- s.runCollect.map(_.takeWhile(p)).exit
            } yield assert(chunkTakeWhile.isSuccess)(isTrue) implies assert(streamTakeWhile)(equalTo(chunkTakeWhile))
          }),
          test("takeWhile doesn't stop when hitting an empty chunk (#4272)") {
            ZStream
              .fromChunks(Chunk(1), Chunk(2), Chunk(3))
              .mapChunks(_.flatMap {
                case 2 => Chunk()
                case x => Chunk(x)
              })
              .takeWhile(_ != 4)
              .runCollect
              .map(assert(_)(hasSameElements(List(1, 3))))
          }
        ),
        test("takeWhile short circuits")(
          assertZIO(
            (ZStream(1) ++ ZStream.fail("Ouch"))
              .takeWhile(_ => false)
              .runDrain
              .either
          )(isRight(isUnit))
        ),
        suite("takeWhileZIO")(
          test("takeWhileZIO") {
            check(streamOfInts, Gen.function(Gen.successes(Gen.boolean))) { (s, p) =>
              for {
                streamTakeWhileZIO <- s.takeWhileZIO(p).runCollect.exit
                chunkTakeWhileZIO  <- s.runCollect.flatMap(_.takeWhileZIO(p)).exit
              } yield assert(chunkTakeWhileZIO.isSuccess)(isTrue) implies assert(streamTakeWhileZIO)(
                equalTo(chunkTakeWhileZIO)
              )
            }
          },
          test("takeWhileZIO - laziness on chunks") {
            assertZIO(
              ZStream(1, 2, 3).takeWhileZIO {
                case 2 => ZIO.fail("boom")
                case _ => ZIO.succeed(true)
              }.either.runCollect
            )(equalTo(Chunk(Right(1), Left("boom"))))
          }
        ),
        suite("tap")(
          test("tap") {
            for {
              ref <- Ref.make(0)
              res <- ZStream(1, 1).tap[Any, Nothing](a => ref.update(_ + a)).runCollect
              sum <- ref.get
            } yield assert(res)(equalTo(Chunk(1, 1))) && assert(sum)(equalTo(2))
          },
          test("laziness on chunks") {
            assertZIO(ZStream(1, 2, 3).tap(x => ZIO.when(x == 3)(ZIO.fail("error"))).either.runCollect)(
              equalTo(Chunk(Right(1), Right(2), Left("error")))
            )
          }
        ),
        suite("tapBoth")(
          test("just tap values") {
            for {
              ref   <- Ref.make(0)
              values = Chunk(1, 1)
              res <- ZStream
                       .fromIterableZIO(ZIO.attempt(values))
                       .tapBoth(e => ZIO.dieMessage(s"Unexpected attempt to tap an error $e"), v => ref.update(_ + v))
                       .runCollect
              sum <- ref.get
            } yield assertTrue(res == values, sum == values.sum)
          },
          test("just tap an error") {
            for {
              ref <- Ref.make("")
              res <-
                ZStream
                  .fail("Ouch")
                  .tapBoth(err => ref.update(_ + err), v => ZIO.dieMessage(s"Unexpected attempt to tap a value $v"))
                  .runCollect
                  .either
              err <- ref.get
            } yield assertTrue(res == Left("Ouch"), err == "Ouch")
          },
          test("tap values and then error") {
            for {
              error <- Ref.make("")
              sum   <- Ref.make(0)
              values = Chunk(1, 1)
              stream = ZStream.fromChunk(values) ++ ZStream.fail("Ouch")
              res   <- stream.tapBoth(err => error.update(_ + err), v => sum.update(_ + v)).runCollect.either
              s     <- sum.get
              e     <- error.get
            } yield assertTrue(s == values.sum, res == Left("Ouch"), e == "Ouch")
          },
          test("tap chunks lazily") {
            assertZIO {
              (ZStream(1, 2, 3): ZStream[Any, String, Int])
                .tapBoth(_ => ZIO.unit, x => ZIO.when(x == 3)(ZIO.fail("error")))
                .either
                .runCollect
            }(equalTo(Chunk(Right(1), Right(2), Left("error"))))
          }
        ),
        suite("tapError")(
          test("tapError") {
            for {
              ref <- Ref.make("")
              res <- (ZStream(1, 1) ++ ZStream.fail("Ouch")).tapError(err => ref.update(_ + err)).runCollect.either
              err <- ref.get
            } yield assert(res)(isLeft(equalTo("Ouch"))) && assert(err)(equalTo("Ouch"))
          }
        ),
        suite("tapSink")(
          test("sink that is done after stream") {
            for {
              ref      <- Ref.make(0)
              sink      = ZSink.foreach[Any, Nothing, Int](n => ref.update(_ + n))
              stream    = ZStream(1, 1, 2, 3, 5, 8).tapSink(sink)
              elements <- stream.runCollect
              done     <- ref.get
            } yield assertTrue(elements == Chunk(1, 1, 2, 3, 5, 8) && done == 20)
          },
          test("sink that is done before stream") {
            for {
              ref      <- Ref.make(0)
              sink      = ZSink.take[Int](3).map(_.sum).mapZIO(n => ref.update(_ + n))
              stream    = ZStream(1, 1, 2, 3, 5, 8).rechunk(1).tapSink(sink)
              elements <- stream.runCollect
              done     <- ref.get
            } yield assertTrue(elements == Chunk(1, 1, 2, 3, 5, 8) && done == 4)
          },
          test("sink that fails before stream") {
            for {
              ref   <- Ref.make(0)
              sink   = ZSink.fail("error")
              stream = ZStream.never.tapSink(sink)
              error <- stream.runCollect.flip
            } yield assertTrue(error == "error")
          },
          test("does not read ahead") {
            for {
              ref    <- Ref.make(0)
              stream  = ZStream(1, 2, 3, 4, 5).rechunk(1).forever
              sink    = ZSink.foreach((n: Int) => ref.update(_ + n))
              _      <- stream.tapSink(sink).take(3).runDrain
              result <- ref.get
            } yield assertTrue(result == 6)
          }
        ),
        suite("throttleEnforce")(
          test("free elements") {
            assertZIO(
              ZStream(1, 2, 3, 4)
                .throttleEnforce(0, Duration.Infinity)(_ => 0)
                .runCollect
            )(equalTo(Chunk(1, 2, 3, 4)))
          },
          test("no bandwidth") {
            assertZIO(
              ZStream(1, 2, 3, 4)
                .throttleEnforce(0, Duration.Infinity)(_ => 1)
                .runCollect
            )(equalTo(Chunk.empty))
          },
          test("refill bucket tokens") {
            assertZIO(for {
              fiber <- ZStream
                         .fromSchedule(Schedule.spaced(100.milliseconds))
                         .take(10)
                         .throttleEnforce(1, 200.milliseconds)(_ => 1)
                         .runCollect
                         .fork
              _      <- TestClock.adjust(1.seconds)
              result <- fiber.join
            } yield result)(equalTo(Chunk(0L, 2L, 4L, 6L, 8L)))
          }
        ),
        suite("throttleShape")(
          test("throttleShape") {
            for {
              fiber <- Queue
                         .bounded[Int](10)
                         .flatMap { queue =>
                           ZIO.scoped {
                             ZStream
                               .fromQueue(queue)
                               .throttleShape(1, 1.second)(_.sum.toLong)
                               .toPull
                               .flatMap { pull =>
                                 for {
                                   _    <- queue.offer(1)
                                   res1 <- pull
                                   _    <- queue.offer(2)
                                   res2 <- pull
                                   _    <- Clock.sleep(4.seconds)
                                   _    <- queue.offer(3)
                                   res3 <- pull
                                 } yield assert(Chunk(res1, res2, res3))(
                                   equalTo(Chunk(Chunk(1), Chunk(2), Chunk(3)))
                                 )
                               }
                           }
                         }
                         .fork
              _    <- TestClock.adjust(8.seconds)
              test <- fiber.join
            } yield test
          },
          test("infinite bandwidth") {
            Queue.bounded[Int](10).flatMap { queue =>
              ZIO.scoped {
                ZStream.fromQueue(queue).throttleShape(1, 0.seconds)(_ => 100000L).toPull.flatMap { pull =>
                  for {
                    _       <- queue.offer(1)
                    res1    <- pull
                    _       <- queue.offer(2)
                    res2    <- pull
                    elapsed <- Clock.currentTime(TimeUnit.SECONDS)
                  } yield assert(elapsed)(equalTo(0L)) && assert(Chunk(res1, res2))(
                    equalTo(Chunk(Chunk(1), Chunk(2)))
                  )
                }
              }
            }
          },
          test("with burst") {
            for {
              fiber <- Queue
                         .bounded[Int](10)
                         .flatMap { queue =>
                           ZIO.scoped {
                             ZStream
                               .fromQueue(queue)
                               .throttleShape(1, 1.second, 2)(_.sum.toLong)
                               .toPull
                               .flatMap { pull =>
                                 for {
                                   _    <- queue.offer(1)
                                   res1 <- pull
                                   _    <- TestClock.adjust(2.seconds)
                                   _    <- queue.offer(2)
                                   res2 <- pull
                                   _    <- TestClock.adjust(4.seconds)
                                   _    <- queue.offer(3)
                                   res3 <- pull
                                 } yield assert(Chunk(res1, res2, res3))(
                                   equalTo(Chunk(Chunk(1), Chunk(2), Chunk(3)))
                                 )
                               }
                           }
                         }
                         .fork
              test <- fiber.join
            } yield test
          },
          test("free elements") {
            assertZIO(
              ZStream(1, 2, 3, 4)
                .throttleShape(1, Duration.Infinity)(_ => 0)
                .runCollect
            )(equalTo(Chunk(1, 2, 3, 4)))
          }
        ),
        suite("debounce")(
          test("should drop earlier chunks within waitTime") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(3, 4), Chunk(5), Chunk(6, 7))) { c =>
              val stream = ZStream
                .fromQueue(c.queue)
                .collectWhileSuccess
                .debounce(1.second)
                .tap(_ => c.proceed)

              assertZIO(for {
                fiber  <- stream.runCollect.fork
                _      <- c.offer.fork
                _      <- (Clock.sleep(500.millis) *> c.offer).fork
                _      <- (Clock.sleep(2.seconds) *> c.offer).fork
                _      <- (Clock.sleep(2500.millis) *> c.offer).fork
                _      <- TestClock.adjust(3500.millis)
                result <- fiber.join
              } yield result)(equalTo(Chunk(Chunk(3, 4), Chunk(6, 7))))
            }
          },
          test("should take latest chunk within waitTime") {
            assertWithChunkCoordination(List(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6))) { c =>
              val stream = ZStream
                .fromQueue(c.queue)
                .collectWhileSuccess
                .debounce(1.second)
                .tap(_ => c.proceed)

              assertZIO(for {
                fiber  <- stream.runCollect.fork
                _      <- c.offer *> c.offer *> c.offer
                _      <- TestClock.adjust(1.second)
                result <- fiber.join
              } yield result)(equalTo(Chunk(Chunk(5, 6))))
            }
          },
          test("should work properly with parallelization") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(2), Chunk(3))) { c =>
              val stream = ZStream
                .fromQueue(c.queue)
                .collectWhileSuccess
                .debounce(1.second)
                .tap(_ => c.proceed)

              assertZIO(for {
                fiber  <- stream.runCollect.fork
                _      <- ZIO.collectAllParDiscard(List(c.offer, c.offer, c.offer))
                _      <- TestClock.adjust(1.second)
                result <- fiber.join
              } yield result)(hasSize(equalTo(1)))
            }
          },
          test("should handle empty chunks properly") {
            for {
              fiber  <- ZStream(1, 2, 3).schedule(Schedule.fixed(500.millis)).debounce(1.second).runCollect.fork
              _      <- TestClock.adjust(3.seconds)
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(3)))
          },
          test("should fail immediately") {
            val stream = ZStream.fromZIO(ZIO.fail(None)).debounce(Duration.Infinity)
            assertZIO(stream.runCollect.either)(isLeft(equalTo(None)))
          },
          test("should work with empty streams") {
            val stream = ZStream.empty.debounce(5.seconds)
            assertZIO(stream.runCollect)(isEmpty)
          },
          test("should pick last element from every chunk") {
            assertZIO(for {
              fiber  <- ZStream(1, 2, 3).debounce(1.second).runCollect.fork
              _      <- TestClock.adjust(1.second)
              result <- fiber.join
            } yield result)(equalTo(Chunk(3)))
          },
          test("should interrupt fibers properly") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(2), Chunk(3))) { c =>
              for {
                fib <- ZStream
                         .fromQueue(c.queue)
                         .tap(_ => c.proceed)
                         .flatMap(ex => ZStream.fromZIOOption(ex))
                         .flattenChunks
                         .debounce(200.millis)
                         .interruptWhen(ZIO.never)
                         .take(1)
                         .runCollect
                         .fork
                _       <- (c.offer *> TestClock.adjust(100.millis) *> c.awaitNext).repeatN(3)
                _       <- TestClock.adjust(100.millis)
                results <- fib.join
              } yield assert(results)(equalTo(Chunk(3)))
            }
          },
          test("should interrupt children fiber on stream interruption") {
            for {
              ref <- Ref.make(false)
              fiber <- (ZStream.fromZIO(ZIO.unit) ++ ZStream.fromZIO(ZIO.never.onInterrupt(ref.set(true))))
                         .debounce(800.millis)
                         .runDrain
                         .fork
              _     <- TestClock.adjust(1.minute)
              _     <- fiber.interrupt
              value <- ref.get
            } yield assert(value)(equalTo(true))
          },
          test("should not interrupt children of a pending stream") {
            val stream1 = (ZStream.succeed(0) ++ ZStream.fromZIO(ZIO.sleep(200.millis)).as(1))
              .debounce(100.millis)

            val stream2 = ZStream.succeed(42)

            for {
              fiber  <- (stream1 merge stream2).runCollect.fork
              _      <- TestClock.adjust(10.millis).repeatN(30)
              result <- fiber.join
            } yield assertTrue(result == Chunk(42, 0, 1))
          }
        ) @@ TestAspect.timeout(40.seconds),
        suite("timeout")(
          test("succeed") {
            for {
              streamOut <- ZStream
                             .succeed(1)
                             .timeout(Duration.Infinity)
                             .runCollect
              logs <- ZTestLogger.logOutput
            } yield assert(streamOut)(equalTo(Chunk(1))) && assert(logs)(isEmpty)
          },
          test("should end stream") {
            assertZIO(
              ZStream
                .range(0, 5)
                .tap(_ => ZIO.sleep(Duration.Infinity))
                .timeout(Duration.Zero)
                .runCollect
            )(isEmpty)
          }
        ),
        suite("timeoutFail")(
          test("succeed") {
            for {
              streamOut <- ZStream
                             .succeed(1)
                             .timeoutFail("Boom!")(Duration.Infinity)
                             .runCollect
              logs <- ZTestLogger.logOutput
            } yield assert(streamOut)(equalTo(Chunk(1))) && assert(logs)(isEmpty)
          },
          test("timeout failure") {
            assertZIO(
              ZStream
                .range(0, 5)
                .tap(_ => ZIO.sleep(Duration.Infinity))
                .timeoutFail(false)(Duration.Zero)
                .runDrain
                .map(_ => true)
                .either
                .map(_.merge)
            )(isFalse)
          },
          test("stream failure") {
            for {
              error <- ZStream
                         .fail("OriginalError")
                         .timeoutFail("TimeoutFail")(15.minutes)
                         .runDrain
                         .flip
            } yield assertTrue(error == "OriginalError")
          }
        ),
        test("timeoutFailCause") {
          val throwable = new Exception("BOOM")
          assertZIO(
            ZStream
              .range(0, 5)
              .tap(_ => ZIO.sleep(Duration.Infinity))
              .timeoutFailCause(Cause.die(throwable))(Duration.Zero)
              .runDrain
              .sandbox
              .either
          )(equalTo(Left(Cause.Die(throwable, StackTrace.none))))
        },
        suite("timeoutTo")(
          test("succeed") {
            assertZIO(
              ZStream
                .range(0, 5)
                .timeoutTo(Duration.Infinity)(ZStream.succeed(-1))
                .runCollect
            )(equalTo(Chunk(0, 1, 2, 3, 4)))
          },
          test("should switch stream") {
            assertWithChunkCoordination(List(Chunk(1), Chunk(2), Chunk(3))) { c =>
              assertZIO(
                for {
                  fiber <- ZStream
                             .fromQueue(c.queue)
                             .collectWhileSuccess
                             .flattenChunks
                             .timeoutTo(2.seconds)(ZStream.succeed(4))
                             .tap(_ => c.proceed)
                             .runCollect
                             .fork
                  _      <- c.offer *> TestClock.adjust(1.seconds) *> c.awaitNext
                  _      <- c.offer *> TestClock.adjust(3.seconds) *> c.awaitNext
                  _      <- c.offer
                  result <- fiber.join
                } yield result
              )(equalTo(Chunk(1, 2, 4)))
            }
          },
          test("should not apply timeout after switch") {
            for {
              queue1 <- Queue.unbounded[Int]
              queue2 <- Queue.unbounded[Int]
              stream1 = ZStream.fromQueue(queue1)
              stream2 = ZStream.fromQueue(queue2)
              fiber  <- stream1.timeoutTo(2.seconds)(stream2).runCollect.fork
              _      <- queue1.offer(1) *> TestClock.adjust(1.second)
              _      <- queue1.offer(2) *> TestClock.adjust(3.second)
              _      <- queue1.offer(3)
              _      <- queue2.offer(4) *> TestClock.adjust(3.second)
              _      <- queue2.offer(5) *> queue2.shutdown
              result <- fiber.join
            } yield assert(result)(equalTo(Chunk(1, 2, 4, 5)))
          }
        ),
        suite("toInputStream")(
          test("read one-by-one") {
            check(tinyListOf(Gen.chunkOf(Gen.byte))) { chunks =>
              val content = chunks.flatMap(_.toList)
              ZIO.scoped {
                ZStream.fromChunks(chunks: _*).toInputStream.flatMap[Scope, Throwable, TestResult] { is =>
                  val combined = Iterator.continually(is.read()).takeWhile(_ != -1).map(_.toByte).toList
                  ZIO.succeed(assert(combined)(equalTo(content)))
                }
              }
            }
          },
          test("read in batches") {
            check(tinyListOf(Gen.chunkOf(Gen.byte))) { chunks =>
              val content = chunks.flatMap(_.toList)
              ZIO.scoped {
                ZStream.fromChunks(chunks: _*).toInputStream.flatMap[Scope, Throwable, TestResult] { is =>
                  val batches: List[(Array[Byte], Int)] = Iterator.continually {
                    val buf = new Array[Byte](10)
                    val res = is.read(buf, 0, 4)
                    (buf, res)
                  }.takeWhile(_._2 != -1).toList
                  val combined = batches.flatMap { case (buf, size) => buf.take(size) }
                  ZIO.succeed(assert(combined)(equalTo(content)))
                }
              }
            }
          },
          test("`available` returns the size of chunk's leftover") {
            ZIO.scoped {
              ZStream
                .fromIterable((1 to 10).map(_.toByte))
                .rechunk(3)
                .toInputStream
                .flatMap[Scope, Throwable, TestResult](is =>
                  ZIO.attempt {
                    val cold = is.available()
                    is.read()
                    val at1 = is.available()
                    is.read(new Array[Byte](2))
                    val at3 = is.available()
                    is.read()
                    val at4 = is.available()
                    List(
                      assert(cold)(equalTo(0)),
                      assert(at1)(equalTo(2)),
                      assert(at3)(equalTo(0)),
                      assert(at4)(equalTo(2))
                    ).reduce(_ && _)
                  }
                )
            }
          },
          test("Preserves errors") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fail(new Exception("boom"))
                  .toInputStream
                  .flatMap(is =>
                    ZIO.attempt {
                      is.read
                    }
                  )
              }.exit
            )(
              fails(hasMessage(equalTo("boom")))
            )
          },
          test("Be completely lazy") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fail(new Exception("boom"))
                  .toInputStream
                  .flatMap(_ => ZIO.succeed("ok"))
              }
            )(equalTo("ok"))
          },
          test("Preserves errors in the middle") {
            val bytes: Seq[Byte] = (1 to 5).map(_.toByte)
            val str: ZStream[Any, Throwable, Byte] =
              ZStream.fromIterable(bytes) ++ ZStream.fail(new Exception("boom"))
            assertZIO(
              ZIO.scoped {
                str.toInputStream
                  .flatMap(is =>
                    ZIO.attempt {
                      val buf = new Array[Byte](50)
                      is.read(buf)
                      "ok"
                    }
                  )
              }.exit
            )(fails(hasMessage(equalTo("boom"))))
          },
          test("Allows reading something even in case of error") {
            val bytes: Seq[Byte] = (1 to 5).map(_.toByte)
            val str: ZStream[Any, Throwable, Byte] =
              ZStream.fromIterable(bytes) ++ ZStream.fail(new Exception("boom"))
            assertZIO(
              ZIO.scoped {
                str.toInputStream.flatMap(is =>
                  ZIO.attempt {
                    val buf = new Array[Byte](5)
                    is.read(buf)
                    buf.toList
                  }
                )
              }
            )(equalTo(bytes))
          },
          test("Preserves errors") {
            val stream = ZStream.fail(new Throwable("fail"))
            val pipeline = ZPipeline.fromFunction[Scope, Throwable, Byte, Unit] { s =>
              ZStream.fromZIO {
                for {
                  is <- s.toInputStream.debug("toInputStream")
                  _  <- ZIO.attemptBlocking(is.read())
                } yield ()
              }
            }
            assertZIO(stream.via(pipeline).runCollect.exit)(fails(hasMessage(containsString("fail"))))
          } @@ TestAspect.jvmOnly,
          test("respects env") {
            val src: ZStream[Resource, Nothing, (Int, Resource)] = ZStream
              .range(0, 100, 10)
              .mapZIO { a =>
                ZIO.serviceWith[Resource] { resource =>
                  (a, resource)
                }
              }

            val pl0: ZPipeline[Any, Nothing, (Int, Resource), (Int, Resource, Scope)] =
              ZPipeline.fromFunction { (strm: ZStream[Any, Nothing, (Int, Resource)]) =>
                ZStream
                  .unwrapScoped[Any] {
                    ZIO.scopeWith { scope =>
                      ZIO.succeed {
                        ZStream
                          .serviceWithStream[Scope] { scope2 =>
                            strm.map { case (i, res) =>
                              (i, res, scope2)
                            }
                          }
                          .provideEnvironment(ZEnvironment(scope))
                      }
                    }
                  }
              }

            val strm: ZStream[Resource, Nothing, (Int, Resource, Scope)] = src >>> pl0
            val z00: ZIO[Any, Nothing, Chunk[(Int, Resource, Scope)]] =
              strm.runCollect.provideLayer(ZLayer.succeed(Resource(12)))
            z00.map { chunk =>
              zio.test.assertTrue {
                chunk.map(_._3).toSet.size == 1
              } &&
              zio.test.assertTrue {
                chunk.map { case (i, res, _) =>
                  (i, res)
                } ==
                  Chunk.tabulate(100)((_, Resource(12)))
              }
            }
          },
          test("respects env multiple levels") {
            val src = ZStream.range(0, 100, 10)

            val pl1: ZPipeline[Any, Nothing, Int, (Int, Resource)] =
              ZPipeline.fromFunction { (strm: ZStream[Any, Nothing, Int]) =>
                strm.mapZIO { i =>
                  ZIO.serviceWith[Resource] { r =>
                    (i, r)
                  }
                }
                  .provideEnvironment(ZEnvironment(Resource(11)))
              }

            val pl2: ZPipeline[Any, Nothing, (Int, Resource), (Int, Resource, Resource)] =
              ZPipeline.fromFunction { (strm: ZStream[Any, Nothing, (Int, Resource)]) =>
                strm.mapZIO { case (i, r0) =>
                  ZIO.serviceWith[Resource] { r1 =>
                    (i, r0, r1)
                  }
                }
                  .provideEnvironment(ZEnvironment(Resource(12)))
              }

            val strm: ZStream[Any, Nothing, (Int, Resource, Resource)] = src >>> pl1 >>> pl2
            val z: ZIO[Any, Nothing, Chunk[(Int, Resource, Resource)]] = strm.runCollect

            assertZIO(z) {
              zio.test.Assertion.equalTo(zio.Chunk.tabulate(100)((_, Resource(11), Resource(12))))
            }
          }
        ),
        test("toIterator") {
          ZIO.scoped {
            (for {
              counter <- Ref.make(0) //Increment and get the value
              effect    = counter.updateAndGet(_ + 1)
              iterator <- ZStream.repeatZIO(effect).toIterator
              n         = 2000
              out <- ZStream
                       .fromIterator(iterator.map(_.merge), maxChunkSize = 1)
                       .mapConcatZIO(element => effect.map(newElement => List(element, newElement)))
                       .take(n.toLong)
                       .runCollect
            } yield assert(out)(equalTo(Chunk.fromIterable(1 to n)))).flatMap(ZIO.succeed(_))
          }
        } @@ TestAspect.jvmOnly, // Until #3360 is solved
        suite("toQueue")(
          test("toQueue")(check(Gen.chunkOfBounded(0, 3)(Gen.int)) { (c: Chunk[Int]) =>
            val s = ZStream.fromChunk(c).flatMap(ZStream.succeed(_))
            assertZIO(
              ZIO.scoped {
                s.toQueue(1000)
                  .flatMap(queue => queue.size.repeatWhile(_ != c.size + 1) *> queue.takeAll)
              }
            )(
              equalTo(c.map(Take.single) :+ Take.end)
            )
          }),
          test("toQueueUnbounded")(check(Gen.chunkOfBounded(0, 3)(Gen.int)) { (c: Chunk[Int]) =>
            val s = ZStream.fromChunk(c).flatMap(ZStream.succeed(_))
            assertZIO(
              ZIO.scoped {
                s.toQueueUnbounded.flatMap(queue => queue.size.repeatWhile(_ != c.size + 1) *> queue.takeAll)
              }
            )(
              equalTo(c.map(Take.single) :+ Take.end)
            )
          })
        ),
        suite("toQueueOfElements")(
          test("propagates defects") {
            for {
              queue <- ZStream.dieMessage("die").toQueueOfElements(1)
              exit  <- queue.take
            } yield assert(exit)(dies(hasMessage(equalTo("die"))))
          }
        ),
        suite("toReader")(
          test("read one-by-one") {
            check(tinyListOf(Gen.chunkOf(Gen.char))) { chunks =>
              val content = chunks.flatMap(_.toList)
              ZIO.scoped {
                ZStream.fromChunks(chunks: _*).toReader.flatMap[Scope, Throwable, TestResult] { reader =>
                  val combined = Iterator.continually(reader.read()).takeWhile(_ != -1).map(_.toChar).toList
                  ZIO.succeed(assert(combined)(equalTo(content)))
                }
              }
            }
          },
          test("read in batches") {
            check(tinyListOf(Gen.chunkOf(Gen.char))) { chunks =>
              val content = chunks.flatMap(_.toList)
              ZIO.scoped {
                ZStream.fromChunks(chunks: _*).toReader.flatMap[Scope, Throwable, TestResult] { reader =>
                  val batches: List[(Array[Char], Int)] = Iterator.continually {
                    val buf = new Array[Char](10)
                    val res = reader.read(buf, 0, 4)
                    (buf, res)
                  }.takeWhile(_._2 != -1).toList
                  val combined = batches.flatMap { case (buf, size) => buf.take(size) }
                  ZIO.succeed(assert(combined)(equalTo(content)))
                }
              }
            }
          },
          test("Throws mark not supported") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fromChunk(Chunk.fromArray("Lorem ipsum".toArray))
                  .toReader
                  .flatMap(reader =>
                    ZIO.attempt {
                      reader.mark(0)
                    }
                  )
              }.exit
            )(fails(isSubtype[IOException](anything)))
          },
          test("Throws reset not supported") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fromChunk(Chunk.fromArray("Lorem ipsum".toArray))
                  .toReader
                  .flatMap(reader =>
                    ZIO.attempt {
                      reader.reset()
                    }
                  )
              }.exit
            )(fails(isSubtype[IOException](anything)))
          },
          test("Does not support mark") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fromChunk(Chunk.fromArray("Lorem ipsum".toArray))
                  .toReader
                  .flatMap(reader => ZIO.succeed(reader.markSupported()))
              }
            )(equalTo(false))
          },
          test("Ready is false") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fromChunk(Chunk.fromArray("Lorem ipsum".toArray))
                  .toReader
                  .flatMap(reader => ZIO.succeed(reader.ready()))
              }
            )(equalTo(false))
          },
          test("Preserves errors") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fail(new Exception("boom"))
                  .toReader
                  .flatMap(reader =>
                    ZIO.attempt {
                      reader.read
                    }
                  )
                  .exit
              }
            )(
              fails(hasMessage(equalTo("boom")))
            )
          },
          test("Be completely lazy") {
            assertZIO(
              ZIO.scoped {
                ZStream
                  .fail(new Exception("boom"))
                  .toReader
                  .flatMap(_ => ZIO.succeed("ok"))
              }
            )(equalTo("ok"))
          },
          test("Preserves errors in the middle") {
            val chars: Seq[Char] = (1 to 5).map(_.toChar)
            val str: ZStream[Any, Throwable, Char] =
              ZStream.fromIterable(chars) ++ ZStream.fail(new Exception("boom"))
            assertZIO(
              ZIO.scoped {
                str.toReader
                  .flatMap(reader =>
                    ZIO.attempt {
                      val buf = new Array[Char](50)
                      reader.read(buf)
                      "ok"
                    }
                  )
                  .exit
              }
            )(fails(hasMessage(equalTo("boom"))))
          },
          test("Allows reading something even in case of error") {
            val chars: Seq[Char] = (1 to 5).map(_.toChar)
            val str: ZStream[Any, Throwable, Char] =
              ZStream.fromIterable(chars) ++ ZStream.fail(new Exception("boom"))
            assertZIO(
              ZIO.scoped {
                str.toReader.flatMap(reader =>
                  ZIO.attempt {
                    val buf = new Array[Char](5)
                    reader.read(buf)
                    buf.toList
                  }
                )
              }
            )(equalTo(chars))
          }
        ),
        test("zipAllSortedByKeyWith") {
          val genSortedByKey = for {
            map    <- Gen.mapOf(Gen.int(1, 100), Gen.int(1, 100))
            chunk   = Chunk.fromIterable(map).sorted
            chunks <- splitChunks(Chunk(chunk))
          } yield chunks
          check(genSortedByKey, genSortedByKey) { (as, bs) =>
            val left   = ZStream.fromChunks(as: _*)
            val right  = ZStream.fromChunks(bs: _*)
            val actual = left.zipAllSortedByKeyWith(right)(identity, identity)(_ + _)
            val expected = Chunk.fromIterable {
              as.flatten.toMap.foldLeft(bs.flatten.toMap) { case (map, (k, v)) =>
                map.get(k).fold(map.updated(k, v))(v1 => map.updated(k, v + v1))
              }
            }.sorted
            assertZIO(actual.runCollect)(equalTo(expected))
          }
        },
        suite("zipWith")(
          test("zip doesn't pull too much when one of the streams is done") {
            val l = ZStream.fromChunks(Chunk(1, 2), Chunk(3, 4), Chunk(5)) ++ ZStream.fail(
              "Nothing to see here"
            )
            val r = ZStream.fromChunks(Chunk("a", "b"), Chunk("c"))
            assertZIO(l.zip(r).runCollect)(equalTo(Chunk((1, "a"), (2, "b"), (3, "c"))))
          },
          test("zip equivalence with Chunk#zipWith") {
            check(
              tinyListOf(Gen.chunkOf(Gen.int)),
              tinyListOf(Gen.chunkOf(Gen.int))
            ) { (l, r) =>
              val expected = Chunk.fromIterable(l).flatten.zip(Chunk.fromIterable(r).flatten)
              assertZIO(ZStream.fromChunks(l: _*).zip(ZStream.fromChunks(r: _*)).runCollect)(
                equalTo(expected)
              )
            }
          },
          test("zipWith prioritizes failure") {
            assertZIO(
              ZStream.never
                .zipWith(ZStream.fail("Ouch"))((_, _) => None)
                .runCollect
                .either
            )(isLeft(equalTo("Ouch")))
          },
          test("zipWith dies if one of the streams throws an exception") {
            assertZIO(
              ZStream(1)
                .flatMap(_ => ZStream.succeed(throw new Exception("outch")))
                .zip(ZStream(1))
                .runCollect
                .exit
            )(dies(anything))
          },
          test("zip in uninterruptible region") {
            for {
              _ <- ZStream(1).zip(ZStream(2)).runDrain.uninterruptible
            } yield assertCompletes
          }
        ),
        suite("zipAllWith")(
          test("zipAllWith") {
            check(
              // We're using ZStream.fromChunks in the test, and that discards empty
              // chunks; so we're only testing for non-empty chunks here.
              tinyListOf(Gen.chunkOf(Gen.int).filter(_.size > 0)),
              tinyListOf(Gen.chunkOf(Gen.int).filter(_.size > 0))
            ) { (l, r) =>
              val expected =
                Chunk
                  .fromIterable(l)
                  .flatten
                  .zipAllWith(Chunk.fromIterable(r).flatten)(Some(_) -> None, None -> Some(_))(
                    Some(_) -> Some(_)
                  )

              assertZIO(
                ZStream
                  .fromChunks(l: _*)
                  .map(Option(_))
                  .zipAll(ZStream.fromChunks(r: _*).map(Option(_)))(None, None)
                  .runCollect
              )(equalTo(expected))
            }
          },
          test("zipAllWith prioritizes failure") {
            assertZIO(
              ZStream.never
                .zipAll(ZStream.fail("Ouch"))(None, None)
                .runCollect
                .either
            )(isLeft(equalTo("Ouch")))
          }
        ),
        test("zipWithIndex")(check(pureStreamOfInts) { s =>
          for {
            res1 <- (s.zipWithIndex.runCollect)
            res2 <- (s.runCollect.map(_.zipWithIndex.map(t => (t._1, t._2.toLong))))
          } yield assert(res1)(equalTo(res2))
        }),
        suite("zipLatestWith")(
          test("succeed") {
            for {
              left  <- Queue.unbounded[Chunk[Int]]
              right <- Queue.unbounded[Chunk[Int]]
              out   <- Queue.bounded[Take[Nothing, (Int, Int)]](1)
              _ <-
                ZStream.fromChunkQueue(left).zipLatest(ZStream.fromChunkQueue(right)).runIntoQueue(out).fork
              _      <- left.offer(Chunk(0))
              _      <- right.offerAll(List(Chunk(0), Chunk(1)))
              chunk1 <- ZIO.replicateZIO(2)(out.take.flatMap(_.exit)).map(_.flatten)
              _      <- left.offerAll(List(Chunk(1), Chunk(2)))
              chunk2 <- ZIO.replicateZIO(2)(out.take.flatMap(_.exit)).map(_.flatten)
            } yield assert(chunk1)(equalTo(List((0, 0), (0, 1)))) && assert(chunk2)(equalTo(List((1, 1), (2, 1))))
          } @@ exceptJS(nonFlaky),
          test("handle empty pulls properly") {
            val stream0 = ZStream.fromChunks(Chunk(), Chunk(), Chunk(2))
            val stream1 = ZStream.fromChunks(Chunk(1), Chunk(1))

            assertZIO(
              for {
                promise <- Promise.make[Nothing, Int]
                latch   <- Promise.make[Nothing, Unit]
                fiber <- (stream0 ++ ZStream.fromZIO(promise.await) ++ ZStream(2))
                           .zipLatestWith(ZStream(1, 1).ensuring(latch.succeed(())) ++ stream1)((_, x) => x)
                           .take(3)
                           .runCollect
                           .fork
                _      <- latch.await
                _      <- promise.succeed(2)
                result <- fiber.join
              } yield result
            )(equalTo(Chunk(1, 1, 1)))
          } @@ exceptJS(nonFlaky),
          test("handle empty pulls properly (JVM Only)") {
            assertZIO(
              ZStream
                .unfold(0)(n => Some((if (n < 3) Chunk.empty else Chunk.single(2), n + 1)))
                .flattenChunks
                .forever
                .zipLatestWith(ZStream(1).forever)((_, x) => x)
                .take(3)
                .runCollect
            )(equalTo(Chunk(1, 1, 1)))
          },
          test("preserves partial ordering of stream elements") {
            val genSortedStream = for {
              chunk  <- Gen.chunkOf(Gen.int(1, 100)).map(_.sorted)
              chunks <- splitChunks(Chunk(chunk))
            } yield ZStream.fromChunks(chunks: _*)
            check(genSortedStream, genSortedStream) { (left, right) =>
              for {
                out <- left.zipLatestWith(right)(_ + _).runCollect
              } yield assert(out)(isSorted)
            }
          }
        ),
        suite("zipWithNext")(
          test("should zip with next element for a single chunk") {
            for {
              result <- ZStream(1, 2, 3).zipWithNext.runCollect
            } yield assert(result)(equalTo(Chunk(1 -> Some(2), 2 -> Some(3), 3 -> None)))
          },
          test("should work with multiple chunks") {
            for {
              result <- ZStream.fromChunks(Chunk(1), Chunk(2), Chunk(3)).zipWithNext.runCollect
            } yield assert(result)(equalTo(Chunk(1 -> Some(2), 2 -> Some(3), 3 -> None)))
          },
          test("should play well with empty streams") {
            assertZIO(ZStream.empty.zipWithNext.runCollect)(isEmpty)
          },
          test("should output same values as zipping with tail plus last element") {
            check(tinyListOf(Gen.chunkOf(Gen.int))) { chunks =>
              val stream = ZStream.fromChunks(chunks: _*)
              for {
                result0 <- stream.zipWithNext.runCollect
                result1 <- stream.zipAll(stream.drop(1).map(Option(_)))(0, None).runCollect
              } yield assert(result0)(equalTo(result1))
            }
          }
        ) @@ TestAspect.jvmOnly,
        suite("zipWithPrevious")(
          test("should zip with previous element for a single chunk") {
            for {
              result <- ZStream(1, 2, 3).zipWithPrevious.runCollect
            } yield assert(result)(equalTo(Chunk(None -> 1, Some(1) -> 2, Some(2) -> 3)))
          },
          test("should work with multiple chunks") {
            for {
              result <- ZStream.fromChunks(Chunk(1), Chunk(2), Chunk(3)).zipWithPrevious.runCollect
            } yield assert(result)(equalTo(Chunk(None -> 1, Some(1) -> 2, Some(2) -> 3)))
          },
          test("should play well with empty streams") {
            assertZIO(ZStream.empty.zipWithPrevious.runCollect)(isEmpty)
          },
          test("should output same values as first element plus zipping with init") {
            check(tinyListOf(Gen.chunkOf(Gen.int))) { chunks =>
              val stream = ZStream.fromChunks(chunks: _*)
              for {
                result0 <- stream.zipWithPrevious.runCollect
                result1 <- (ZStream(None) ++ stream.map(Some(_))).zip(stream).runCollect
              } yield assert(result0)(equalTo(result1))
            }
          }
        ) @@ TestAspect.jvmOnly,
        suite("zipWithPreviousAndNext")(
          test("succeed") {
            for {
              result <- ZStream(1, 2, 3).zipWithPreviousAndNext.runCollect
            } yield assert(result)(
              equalTo(Chunk((None, 1, Some(2)), (Some(1), 2, Some(3)), (Some(2), 3, None)))
            )
          },
          test("should output same values as zipping with both previous and next element") {
            check(tinyListOf(Gen.chunkOf(Gen.int))) { chunks =>
              val stream = ZStream.fromChunks(chunks: _*)
              for {
                result0 <- stream.zipWithPreviousAndNext.runCollect
                previous = ZStream(None) ++ stream.map(Some(_))
                next     = stream.drop(1).map(Some(_)) ++ ZStream(None)
                result1 <- previous
                             .zip(stream)
                             .zip(next)
                             .runCollect
              } yield assert(result0)(equalTo(result1))
            }
          }
        ) @@ TestAspect.jvmOnly,
        suite("refineToOrDie")(
          test("does not compile when refine type is not a subtype of error type") {
            val result = typeCheck {
              """
               ZIO
                 .fail(new RuntimeException("BOO!"))
                 .refineToOrDie[Error]
                 """
            }
            val expected =
              "type arguments [Error] do not conform to method refineToOrDie's type parameter bounds [E1 <: RuntimeException]"
            assertZIO(result)(isLeft(equalTo(expected)))
          } @@ scala2Only
        ),
        suite("when")(
          test("returns the stream if the condition is satisfied") {
            check(pureStreamOfInts) { stream =>
              for {
                result1  <- stream.when(true).runCollect
                result2  <- ZStream.when(true)(stream).runCollect
                expected <- stream.runCollect
              } yield assert(result1)(equalTo(expected)) && assert(result2)(equalTo(expected))
            }
          },
          test("returns an empty stream if the condition is not satisfied") {
            check(pureStreamOfInts) { stream =>
              for {
                result1 <- stream.when(false).runCollect
                result2 <- ZStream.when(false)(stream).runCollect
                expected = Chunk[Int]()
              } yield assert(result1)(equalTo(expected)) && assert(result2)(equalTo(expected))
            }
          },
          test("dies if the condition throws an exception") {
            check(pureStreamOfInts) { stream =>
              val exception     = new Exception
              def cond: Boolean = throw exception
              assertZIO(stream.when(cond).runDrain.exit)(dies(equalTo(exception)))
            }
          }
        ),
        suite("whenCase")(
          test("returns the resulting stream if the given partial function is defined for the given value") {
            check(Gen.int) { int =>
              for {
                result  <- ZStream.whenCase(Some(int)) { case Some(v) => ZStream(v) }.runCollect
                expected = Chunk(int)
              } yield assert(result)(equalTo(expected))
            }
          },
          test("returns an empty stream if the given partial function is not defined for the given value") {
            for {
              result  <- ZStream.whenCase(Option.empty[Int]) { case Some(v) => ZStream(v) }.runCollect
              expected = Chunk.empty
            } yield assert(result)(equalTo(expected))
          },
          test("dies if evaluating the given value throws an exception") {
            val exception   = new Exception
            def badInt: Int = throw exception
            assertZIO(ZStream.whenCase(badInt) { case _ => ZStream.empty }.runDrain.exit)(dies(equalTo(exception)))
          },
          test("dies if the partial function throws an exception") {
            val exception = new Exception
            assertZIO(ZStream.whenCase(Option.empty[Int]) { case _ => throw exception }.runDrain.exit)(
              dies(equalTo(exception))
            )
          }
        ),
        suite("whenCaseZIO")(
          test("returns the resulting stream if the given partial function is defined for the given effectful value") {
            check(Gen.int) { int =>
              for {
                result  <- ZStream.whenCaseZIO(ZIO.succeed(Some(int))) { case Some(v) => ZStream(v) }.runCollect
                expected = Chunk(int)
              } yield assert(result)(equalTo(expected))
            }
          },
          test("returns an empty stream if the given partial function is not defined for the given effectful value") {
            for {
              result  <- ZStream.whenCaseZIO(ZIO.succeed(Option.empty[Int])) { case Some(v) => ZStream(v) }.runCollect
              expected = Chunk.empty
            } yield assert(result)(equalTo(expected))
          },
          test("fails if the effectful value is a failure") {
            val exception                   = new Exception
            val failure: IO[Exception, Int] = ZIO.fail(exception)
            assertZIO(ZStream.whenCaseZIO(failure) { case _ => ZStream.empty }.runDrain.exit)(fails(equalTo(exception)))
          },
          test("dies if the given partial function throws an exception") {
            val exception = new Exception
            assertZIO(ZStream.whenCaseZIO(ZIO.succeed(Option.empty[Int])) { case _ => throw exception }.runDrain.exit)(
              dies(equalTo(exception))
            )
          },
          test("infers types correctly") {
            trait R
            trait R1 extends R
            trait E1
            trait E extends E1
            trait A
            trait O
            val o                                          = new O {}
            val b: ZIO[R, E, A]                            = ZIO.succeed(new A {})
            val pf: PartialFunction[A, ZStream[R1, E1, O]] = { case _ => ZStream(o) }
            val s: ZStream[R1, E1, O]                      = ZStream.whenCaseZIO(b)(pf)
            assertZIO(s.runDrain.provideEnvironment(ZEnvironment(new R1 {})))(isUnit)
          }
        ),
        suite("whenZIO")(
          test("returns the stream if the effectful condition is satisfied") {
            check(pureStreamOfInts) { stream =>
              for {
                result1  <- stream.whenZIO(ZIO.succeed(true)).runCollect
                result2  <- ZStream.whenZIO(ZIO.succeed(true))(stream).runCollect
                expected <- stream.runCollect
              } yield assert(result1)(equalTo(expected)) && assert(result2)(equalTo(expected))
            }
          },
          test("returns an empty stream if the effectful condition is not satisfied") {
            check(pureStreamOfInts) { stream =>
              for {
                result1 <- stream.whenZIO(ZIO.succeed(false)).runCollect
                result2 <- ZStream.whenZIO(ZIO.succeed(false))(stream).runCollect
                expected = Chunk[Int]()
              } yield assert(result1)(equalTo(expected)) && assert(result2)(equalTo(expected))
            }
          },
          test("fails if the effectful condition fails") {
            check(pureStreamOfInts) { stream =>
              val exception = new Exception
              assertZIO(stream.whenZIO(ZIO.fail(exception)).runDrain.exit)(fails(equalTo(exception)))
            }
          },
          test("infers types correctly") {
            trait R
            trait R1 extends R
            trait E1
            trait E extends E1
            trait O
            val o                          = new O {}
            val b: ZIO[R, E, Boolean]      = ZIO.succeed(true)
            val stream: ZStream[R1, E1, O] = ZStream(o)
            val s1: ZStream[R1, E1, O]     = ZStream.whenZIO(b)(stream)
            val s2: ZStream[R1, E1, O]     = stream.whenZIO(b)
            assertZIO((s1 ++ s2).runDrain.provideEnvironment(ZEnvironment(new R1 {})))(isUnit)
          }
        )
      ),
      suite("Constructors")(
        test("alphanumeric") {
          for {
            chars <- ZStream.alphanumeric.take(100).runCollect
          } yield assertTrue(chars.forall(_.isLetterOrDigit))
        },
        test("rechunk") {
          check(tinyChunkOf(Gen.chunkOf(Gen.int)) <*> (Gen.int(1, 100))) { case (chunk, n) =>
            val expected = Chunk.fromIterable(chunk.flatten.grouped(n).toList)
            assertZIO(
              ZStream
                .fromChunks(chunk: _*)
                .rechunk(n)
                .mapChunks(ch => Chunk(ch))
                .runCollect
            )(equalTo(expected))
          }
        },
        test("concatAll") {
          check(tinyListOf(Gen.chunkOf(Gen.int))) { chunks =>
            assertZIO(
              ZStream.concatAll(Chunk.fromIterable(chunks.map(ZStream.fromChunk(_)))).runCollect
            )(
              equalTo(Chunk.fromIterable(chunks).flatten)
            )
          }
        },
        test("environment") {
          for {
            result <-
              ZStream.environment[String].map(_.get).provideEnvironment(ZEnvironment("test")).runCollect.map(_.head)
          } yield assert(result)(equalTo("test"))
        },
        test("environmentWith") {
          for {
            result <-
              ZStream.environmentWith[String](_.get).provideEnvironment(ZEnvironment("test")).runCollect.map(_.head)
          } yield assert(result)(equalTo("test"))
        },
        suite("environmentWithZIO")(
          test("environmentWithZIO") {
            for {
              result <- ZStream
                          .environmentWithZIO[String](environment => ZIO.succeed(environment.get))
                          .provideEnvironment(ZEnvironment("test"))
                          .runCollect
                          .map(_.head)
            } yield assert(result)(equalTo("test"))
          },
          test("environmentWithZIO fails") {
            for {
              result <-
                ZStream
                  .environmentWithZIO[Int](_ => ZIO.fail("fail"))
                  .provideEnvironment(ZEnvironment(0))
                  .runCollect
                  .exit
            } yield assert(result)(fails(equalTo("fail")))
          }
        ),
        suite("environmentWithStream")(
          test("environmentWithStream") {
            for {
              result <- ZStream
                          .environmentWithStream[String](environment => ZStream.succeed(environment.get))
                          .provideEnvironment(ZEnvironment("test"))
                          .runCollect
                          .map(_.head)
            } yield assert(result)(equalTo("test"))
          },
          test("environmentWithStream fails") {
            for {
              result <- ZStream
                          .environmentWithStream[Int](_ => ZStream.fail("fail"))
                          .provideEnvironment(ZEnvironment(0))
                          .runCollect
                          .exit
            } yield assert(result)(fails(equalTo("fail")))
          }
        ),
        suite("finalizer")(
          test("happy path") {
            for {
              log <- Ref.make[List[String]](Nil)
              _ <- (for {
                     _ <- ZStream.acquireReleaseWith(log.update("Acquire" :: _))(_ => log.update("Release" :: _))
                     _ <- ZStream.finalizer(log.update("Use" :: _))
                   } yield ()).ensuring(log.update("Ensuring" :: _)).runDrain
              execution <- log.get
            } yield assert(execution)(equalTo(List("Ensuring", "Release", "Use", "Acquire")))
          },
          test("finalizer is not run if stream is not pulled") {
            for {
              ref <- Ref.make(false)
              _   <- ZIO.scoped(ZStream.finalizer(ref.set(true)).toPull)
              fin <- ref.get
            } yield assert(fin)(isFalse)
          },
          test("i9052 - ZStream.scoped runs the finalizers") {
            val n = if (TestPlatform.isJVM) 100 else 1
            ZIO
              .foreachPar((1 to n).toList) { _ =>
                val c = new AtomicInteger(0)
                val resource =
                  ZIO.acquireRelease(ZIO.succeed(c.incrementAndGet()))(_ => ZIO.succeed(c.decrementAndGet()))

                val stream = ZStream
                  .succeed(1)
                  .repeat(Schedule.spaced(25.millis))
                  .flatMap(_ => ZStream.scoped(resource))
                  .schedule(Schedule.spaced(25.millis))

                for {
                  s <- stream.runDrain.fork
                  _ <- s.interrupt.delay(50.millis)
                } yield assertTrue(c.get() == 0)
              }
              .map(_.foldLeft(assertCompletes)(_ && _))
          }
            @@ withLiveClock // Can't emulate the bug with the TestClock unfortunately
            @@ exceptJS(nonFlaky(20))
        ),
        suite("from")(
          test("Chunk") {
            trait A
            lazy val chunk: Chunk[A]                    = ???
            lazy val actual                             = ZStream.from(chunk)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("ChunkHub") {
            trait A
            lazy val chunkHub: Hub[Chunk[A]]            = ???
            lazy val actual                             = ZStream.from(chunkHub)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("ChunkQueue") {
            trait A
            lazy val chunkQueue: Queue[Chunk[A]]        = ???
            lazy val actual                             = ZStream.from(chunkQueue)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("Chunks") {
            trait A
            lazy val chunks: Iterable[Chunk[A]]         = ???
            lazy val actual                             = ZStream.from(chunks)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("Hub") {
            trait A
            lazy val hub: Hub[A]                        = ???
            lazy val actual                             = ZStream.from(hub)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("Iterable") {
            trait A
            trait Collection[Element] extends Iterable[Element]
            lazy val iterable: Collection[A]            = ???
            lazy val actual                             = ZStream.from(iterable)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("IterableZIO") {
            trait R
            trait E
            trait A
            trait Collection[Element] extends Iterable[Element]
            lazy val iterableZIO: ZIO[R, E, Collection[A]] = ???
            lazy val actual                                = ZStream.from(iterableZIO)
            lazy val expected: ZStream[R, E, A]            = actual
            lazy val _                                     = expected
            assertCompletes
          },
          test("Iterator") {
            trait A
            trait IteratorLike[Element] extends Iterator[Element]
            lazy val iterator: IteratorLike[A]            = ???
            lazy val actual                               = ZStream.from(iterator)
            lazy val expected: ZStream[Any, Throwable, A] = actual
            lazy val _                                    = expected
            assertCompletes
          },
          test("IteratorZIO") {
            trait R
            trait A
            trait IteratorLike[Element] extends Iterator[Element]
            lazy val iteratorZIO: ZIO[R, Throwable, IteratorLike[A]] = ???
            lazy val actual                                          = ZStream.from(iteratorZIO)
            lazy val expected: ZStream[R, Throwable, A]              = actual
            lazy val _                                               = expected
            assertCompletes
          },
          test("JavaIterator") {
            trait A
            trait IteratorLike[Element] extends java.util.Iterator[Element]
            lazy val javaIterator: IteratorLike[A]        = ???
            lazy val actual                               = ZStream.from(javaIterator)
            lazy val expected: ZStream[Any, Throwable, A] = actual
            lazy val _                                    = expected
            assertCompletes
          },
          test("JavaIteratorZIO") {
            trait R
            trait A
            trait IteratorLike[Element] extends java.util.Iterator[Element]
            lazy val javaIteratorZIO: ZIO[R, Throwable, IteratorLike[A]] = ???
            lazy val actual                                              = ZStream.from(javaIteratorZIO)
            lazy val expected: ZStream[R, Throwable, A]                  = actual
            lazy val _                                                   = expected
            assertCompletes
          },
          test("Queue") {
            trait A
            lazy val queue: Queue[A]                    = ???
            lazy val actual                             = ZStream.from(queue)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("Schedule") {
            trait R
            trait A
            lazy val schedule: Schedule[R, Any, A]    = ???
            lazy val actual                           = ZStream.from(schedule)
            lazy val expected: ZStream[R, Nothing, A] = actual
            lazy val _                                = expected
            assertCompletes
          },
          test("TQueue") {
            trait A
            lazy val tQueue: TQueue[A]                  = ???
            lazy val actual                             = ZStream.from(tQueue)
            lazy val expected: ZStream[Any, Nothing, A] = actual
            lazy val _                                  = expected
            assertCompletes
          },
          test("ZIO") {
            trait R
            trait E
            trait A
            lazy val zio: ZIO[R, E, A]          = ???
            lazy val actual                     = ZStream.from(zio)
            lazy val expected: ZStream[R, E, A] = actual
            lazy val _                          = expected
            assertCompletes
          },
          test("ZIOOption") {
            trait R
            trait E
            trait A
            lazy val zioOption: ZIO[R, Option[E], A] = ???
            lazy val actual                          = ZStream.from(zioOption)
            lazy val expected: ZStream[R, E, A]      = actual
            lazy val _                               = expected
            assertCompletes
          }
        ),
        test("fromChunk") {
          check(Gen.small(Gen.chunkOfN(_)(Gen.int)))(c => assertZIO(ZStream.fromChunk(c).runCollect)(equalTo(c)))
        },
        suite("fromChunks")(
          test("fromChunks") {
            check(tinyListOf(Gen.chunkOf(Gen.int))) { cs =>
              assertZIO(ZStream.fromChunks(cs: _*).runCollect)(
                equalTo(Chunk.fromIterable(cs).flatten)
              )
            }
          },
          test("discards empty chunks") {
            ZIO.scoped {
              ZStream.fromChunks(Chunk(1), Chunk.empty, Chunk(1)).toPull.flatMap { pull =>
                assertZIO(nPulls(pull, 3))(equalTo(List(Right(Chunk(1)), Right(Chunk(1)), Left(None))))
              }
            }
          }
        ),
        suite("fromZIO")(
          test("failure") {
            assertZIO(ZStream.fromZIO(ZIO.fail("error")).runCollect.either)(isLeft(equalTo("error")))
          }
        ),
        suite("fromZIOOption")(
          test("emit one element with success") {
            val fa: ZIO[Any, Option[Int], Int] = ZIO.succeed(5)
            assertZIO(ZStream.fromZIOOption(fa).runCollect)(equalTo(Chunk(5)))
          },
          test("emit one element with failure") {
            val fa: ZIO[Any, Option[Int], Int] = ZIO.fail(Some(5))
            assertZIO(ZStream.fromZIOOption(fa).runCollect.either)(isLeft(equalTo(5)))
          } @@ zioTag(errors),
          test("do not emit any element") {
            val fa: ZIO[Any, Option[Int], Int] = ZIO.fail(None)
            assertZIO(ZStream.fromZIOOption(fa).runCollect)(equalTo(Chunk()))
          }
        ),
        suite("fromInputStream")(
          test("example 1") {
            val chunkSize = ZStream.DefaultChunkSize
            val data      = Array.tabulate[Byte](chunkSize * 5 / 2)(_.toByte)
            def is        = new ByteArrayInputStream(data)
            ZStream.fromInputStream(is, chunkSize).runCollect map { bytes => assert(bytes.toArray)(equalTo(data)) }
          },
          test("example 2") {
            check(Gen.small(Gen.chunkOfN(_)(Gen.byte)), Gen.int(1, 10)) { (bytes, chunkSize) =>
              val is = new ByteArrayInputStream(bytes.toArray)
              ZStream.fromInputStream(is, chunkSize).runCollect.map(assert(_)(equalTo(bytes)))
            }
          },
          test("should handle interruption") {
            for {
              latch      <- Promise.make[Nothing, Unit]
              data       <- ZIO.succeed("Interruptible Stream!".getBytes("UTF-8"))
              inputStream = new ByteArrayInputStream(data)
              fiber <- ZStream
                         .fromInputStream(inputStream)
                         .tap(_ => latch.succeed(()) *> ZIO.never)
                         .runCollect
                         .fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- fiber.await
            } yield assert(result)(isInterrupted)
          },
          test("fromInputStreamZIO should ensure interruption") {
            for {
              latch      <- Promise.make[Nothing, Unit]
              data       <- ZIO.succeed("ZIO Interruptible Stream!".getBytes("UTF-8"))
              inputStream = new ByteArrayInputStream(data)
              fiber <- ZStream
                         .fromInputStreamZIO(ZIO.succeed(inputStream))
                         .tap(_ => latch.succeed(()) *> ZIO.never)
                         .runCollect
                         .fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- fiber.await
            } yield assert(result)(isInterrupted)
          },
          test("fromInputStreamScoped should properly interrupts and closes stream") {
            for {
              latch      <- Promise.make[Nothing, Unit]
              data       <- ZIO.succeed("Scoped Interruptible Stream!".getBytes("UTF-8"))
              inputStream = new ByteArrayInputStream(data)
              fiber <- ZStream
                         .fromInputStreamScoped(ZIO.succeed(inputStream))
                         .tap(_ => latch.succeed(()) *> ZIO.never)
                         .runCollect
                         .fork
              _      <- latch.await
              _      <- fiber.interrupt
              result <- fiber.await
            } yield assert(result)(isInterrupted)
          }
        ),
        test("fromIterable")(check(Gen.small(Gen.chunkOfN(_)(Gen.int))) { l =>
          def lazyL = l
          assertZIO(ZStream.fromIterable(lazyL).runCollect)(equalTo(l))
        }),
        test("fromIterable is safe with very large collections") {
          for {
            _ <- ZStream.fromIterable(1 to 5000000).runDrain
          } yield assertCompletes
        },
        test("fromIterableZIO")(check(Gen.small(Gen.chunkOfN(_)(Gen.int))) { l =>
          assertZIO(ZStream.fromIterableZIO(ZIO.succeed(l)).runCollect)(equalTo(l))
        }),
        test("fromIterableZIO is safe with very large collections") {
          for {
            _ <- ZStream.fromIterableZIO(ZIO.succeed(1 to 5000000)).runDrain
          } yield assertCompletes
        },
        test("fromIterator") {
          check(Gen.small(Gen.chunkOfN(_)(Gen.int)), Gen.small(Gen.const(_), 1)) { (chunk, maxChunkSize) =>
            assertZIO(ZStream.fromIterator(chunk.iterator, maxChunkSize).runCollect)(equalTo(chunk))
          }
        },
        test("fromIteratorSucceed") {
          check(Gen.small(Gen.chunkOfN(_)(Gen.int)), Gen.small(Gen.const(_), 1)) { (chunk, maxChunkSize) =>
            assertZIO(ZStream.fromIteratorSucceed(chunk.iterator, maxChunkSize).runCollect)(equalTo(chunk))
          }
        },
        test("fromBlockingIterator") {
          check(Gen.small(Gen.chunkOfN(_)(Gen.int))) { chunk =>
            assertZIO(ZStream.blocking(ZStream.fromIterator(chunk.iterator)).runCollect)(equalTo(chunk))
          }
        },
        suite("fromIteratorScoped")(
          test("is safe to pull again after success") {
            for {
              ref <- Ref.make(false)
              pulls <- ZIO.scoped {
                         ZStream
                           .fromIteratorScoped(
                             ZIO.acquireRelease(ZIO.succeed(List(1, 2).iterator))(_ => ref.set(true)),
                             maxChunkSize = 1
                           )
                           .toPull
                           .flatMap(nPulls(_, 4))
                       }
              fin <- ref.get
            } yield assert(fin)(isTrue) && assert(pulls)(
              equalTo(List(Right(Chunk(1)), Right(Chunk(2)), Left(None), Left(None)))
            )
          }
        ),
        test("fromSchedule") {
          val schedule = Schedule.exponential(1.second) <* Schedule.recurs(5)
          val stream   = ZStream.fromSchedule(schedule)
          val zio = for {
            fiber <- stream.runCollect.fork
            _     <- TestClock.adjust(62.seconds)
            value <- fiber.join
          } yield value
          val expected = Chunk(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds)
          assertZIO(zio)(equalTo(expected))
        },
        suite("fromQueue")(
          test("emits queued elements") {
            assertWithChunkCoordination(List(Chunk(1, 2))) { c =>
              assertZIO(for {
                fiber <- ZStream
                           .fromQueue(c.queue)
                           .collectWhileSuccess
                           .flattenChunks
                           .tap(_ => c.proceed)
                           .runCollect
                           .fork
                _      <- c.offer
                result <- fiber.join
              } yield result)(equalTo(Chunk(1, 2)))
            }
          },
          test("chunks up to the max chunk size") {
            assertZIO(for {
              queue <- Queue.unbounded[Int]
              _     <- queue.offerAll(List(1, 2, 3, 4, 5, 6, 7))

              result <- ZStream
                          .fromQueue(queue, maxChunkSize = 2)
                          .mapChunks(Chunk.single)
                          .take(3)
                          .runCollect
            } yield result)(forall(hasSize(isLessThanEqualTo(2))))
          }
        ),
        test("fromTQueue") {
          TQueue.bounded[Int](5).commit.flatMap { tqueue =>
            ZIO.scoped {
              ZStream.fromTQueue(tqueue).toQueueUnbounded.flatMap { queue =>
                for {
                  _      <- tqueue.offerAll(List(1, 2, 3)).commit
                  first  <- ZStream.fromQueue(queue).take(3).runCollect
                  _      <- tqueue.offerAll(List(4, 5)).commit
                  second <- ZStream.fromQueue(queue).take(2).runCollect
                } yield assert(first)(equalTo(Chunk(1, 2, 3).map(Take.single))) &&
                  assert(second)(equalTo(Chunk(4, 5).map(Take.single)))
              }
            }
          }
        },
        test("iterate")(
          assertZIO(ZStream.iterate(1)(_ + 1).take(10).runCollect)(
            equalTo(Chunk.fromIterable(1 to 10))
          )
        ),
        test("paginate") {
          val s = (0, List(1, 2, 3))

          ZStream
            .paginate(s) {
              case (x, Nil)      => x -> None
              case (x, x0 :: xs) => x -> Some(x0 -> xs)
            }
            .runCollect
            .map(assert(_)(equalTo(Chunk(0, 1, 2, 3))))
        },
        test("paginateZIO") {
          val s = (0, List(1, 2, 3))

          assertZIO(
            ZStream
              .paginateZIO(s) {
                case (x, Nil)      => ZIO.succeed(x -> None)
                case (x, x0 :: xs) => ZIO.succeed(x -> Some(x0 -> xs))
              }
              .runCollect
          )(equalTo(Chunk(0, 1, 2, 3)))
        },
        test("paginateChunk") {
          val s        = (Chunk.single(0), List(1, 2, 3, 4, 5))
          val pageSize = 2

          assertZIO(
            ZStream
              .paginateChunk(s) {
                case (x, Nil) => x -> None
                case (x, xs)  => x -> Some(Chunk.fromIterable(xs.take(pageSize)) -> xs.drop(pageSize))
              }
              .runCollect
          )(equalTo(Chunk(0, 1, 2, 3, 4, 5)))
        },
        test("paginateChunkZIO") {
          val s        = (Chunk.single(0), List(1, 2, 3, 4, 5))
          val pageSize = 2

          assertZIO(
            ZStream
              .paginateChunkZIO(s) {
                case (x, Nil) => ZIO.succeed(x -> None)
                case (x, xs)  => ZIO.succeed(x -> Some(Chunk.fromIterable(xs.take(pageSize)) -> xs.drop(pageSize)))
              }
              .runCollect
          )(equalTo(Chunk(0, 1, 2, 3, 4, 5)))
        },
        test("range") {
          assertZIO(ZStream.range(0, 10).runCollect)(equalTo(Chunk.fromIterable(Range(0, 10))))
        },
        test("repeat")(
          assertZIO(
            ZStream
              .repeat(1)
              .take(5)
              .runCollect
          )(equalTo(Chunk(1, 1, 1, 1, 1)))
        ),
        test("repeatEffect")(
          assertZIO(
            ZStream
              .repeatZIO(ZIO.succeed(1))
              .take(2)
              .runCollect
          )(equalTo(Chunk(1, 1)))
        ),
        suite("repeatEffectOption")(
          test("emit elements")(
            assertZIO(
              ZStream
                .repeatZIOOption(ZIO.succeed(1))
                .take(2)
                .runCollect
            )(equalTo(Chunk(1, 1)))
          ),
          test("emit elements until pull fails with None")(
            for {
              ref <- Ref.make(0)
              fa = for {
                     newCount <- ref.updateAndGet(_ + 1)
                     res      <- if (newCount >= 5) ZIO.fail(None) else ZIO.succeed(newCount)
                   } yield res
              res <- ZStream
                       .repeatZIOOption(fa)
                       .take(10)
                       .runCollect
            } yield assert(res)(equalTo(Chunk(1, 2, 3, 4)))
          ),
          test("stops evaluating the effect once it fails with None") {
            for {
              ref <- Ref.make(0)
              _ <- ZIO.scoped {
                     ZStream.repeatZIOOption(ref.updateAndGet(_ + 1) *> ZIO.fail(None)).toPull.flatMap { pull =>
                       pull.ignore *> pull.ignore
                     }
                   }
              result <- ref.get
            } yield assert(result)(equalTo(1))
          }
        ),
        suite("repeatZIOWithSchedule")(
          test("succeed")(
            for {
              ref <- Ref.make[List[Int]](Nil)
              fiber <- ZStream
                         .repeatZIOWithSchedule(ref.update(1 :: _), Schedule.spaced(10.millis))
                         .take(2)
                         .runDrain
                         .fork
              _      <- TestClock.adjust(50.millis)
              _      <- fiber.join
              result <- ref.get
            } yield assert(result)(equalTo(List(1, 1)))
          ),
          test("allow schedule rely on effect value")(checkN(10)(Gen.int(1, 100)) { (length: Int) =>
            for {
              ref     <- Ref.make(0)
              effect   = ref.getAndUpdate(_ + 1).filterOrFail(_ <= length + 1)(())
              schedule = Schedule.identity[Int].whileOutput(_ < length)
              result  <- ZStream.repeatZIOWithSchedule(effect, schedule).runCollect
            } yield assert(result)(equalTo(Chunk.fromIterable(0 to length)))
          }),
          test("should perform repetitions in addition to the first execution (one repetition)") {
            assertZIO(ZStream.repeatZIOWithSchedule(ZIO.succeed(1), Schedule.once).runCollect)(
              equalTo(Chunk(1, 1))
            )
          },
          test("should perform repetitions in addition to the first execution (zero repetitions)") {
            assertZIO(ZStream.repeatZIOWithSchedule(ZIO.succeed(1), Schedule.stop).runCollect)(
              equalTo(Chunk(1))
            )
          },
          test("emits before delaying according to the schedule") {
            val interval = 1.second

            for {
              collected <- Ref.make(0)
              effect     = ZIO.unit
              schedule   = Schedule.spaced(interval)
              streamFiber <- ZStream
                               .repeatZIOWithSchedule(effect, schedule)
                               .tap(_ => collected.update(_ + 1))
                               .runDrain
                               .fork
              _                      <- TestClock.adjust(0.seconds)
              nrCollectedImmediately <- collected.get
              _                      <- TestClock.adjust(1.seconds)
              nrCollectedAfterDelay  <- collected.get
              _                      <- streamFiber.interrupt

            } yield assert(nrCollectedImmediately)(equalTo(1)) && assert(nrCollectedAfterDelay)(equalTo(2))
          }
        ),
        test("unfold") {
          assertZIO(
            ZStream
              .unfold(0) { i =>
                if (i < 10) Some((i, i + 1))
                else None
              }
              .runCollect
          )(equalTo(Chunk.fromIterable(0 to 9)))
        },
        test("unfoldChunk") {
          assertZIO(
            ZStream
              .unfoldChunk(0) { i =>
                if (i < 10) Some((Chunk(i, i + 1), i + 2))
                else None
              }
              .runCollect
          )(equalTo(Chunk.fromIterable(0 to 9)))
        },
        test("unfoldChunkZIO") {
          assertZIO(
            ZStream
              .unfoldChunkZIO(0) { i =>
                if (i < 10) ZIO.succeed(Some((Chunk(i, i + 1), i + 2)))
                else ZIO.succeed(None)
              }
              .runCollect
          )(equalTo(Chunk.fromIterable(0 to 9)))
        },
        test("unfoldZIO") {
          assertZIO(
            ZStream
              .unfoldZIO(0) { i =>
                if (i < 10) ZIO.succeed(Some((i, i + 1)))
                else ZIO.succeed(None)
              }
              .runCollect
          )(equalTo(Chunk.fromIterable(0 to 9)))
        },
        test("unwrapScoped") {
          def stream(promise: Promise[Nothing, Unit]) =
            ZStream.unwrapScoped {
              ZIO.acquireRelease(Console.print("acquire outer"))(_ => Console.print("release outer").orDie) *>
                ZIO.suspendSucceed(promise.succeed(()) *> ZIO.never) *>
                ZIO.succeed(ZStream(1, 2, 3))
            }
          for {
            promise <- Promise.make[Nothing, Unit]
            fiber   <- stream(promise).runDrain.fork
            _       <- promise.await
            _       <- fiber.interrupt
            output  <- TestConsole.output
          } yield assertTrue(output == Vector("acquire outer", "release outer"))
        }
      )
    ) @@ TestAspect.timed @@ TestAspect.fibers

  trait ChunkCoordination[A] {
    def queue: Queue[Exit[Option[Nothing], Chunk[A]]]
    def offer: UIO[Unit]
    def proceed: UIO[Unit]
    def awaitNext: UIO[Unit]
  }

  def assertWithChunkCoordination[A](
    chunks: List[Chunk[A]]
  )(
    assertion: ChunkCoordination[A] => ZIO[Any, Nothing, TestResult]
  ): ZIO[Any, Nothing, TestResult] =
    for {
      q  <- Queue.unbounded[Exit[Option[Nothing], Chunk[A]]]
      ps <- Queue.unbounded[Unit]
      ref <- Ref
               .make[List[List[Exit[Option[Nothing], Chunk[A]]]]](
                 chunks.init.map { chunk =>
                   List(Exit.succeed(chunk))
                 } ++ chunks.lastOption.map(chunk => List(Exit.succeed(chunk), Exit.fail(None))).toList
               )
      chunkCoordination = new ChunkCoordination[A] {
                            val queue = q
                            val offer = ref.modify {
                              case x :: xs => (x, xs)
                              case Nil     => (Nil, Nil)
                            }.flatMap(queue.offerAll).unit
                            val proceed   = ps.offer(()).unit
                            val awaitNext = ps.take
                          }
      testResult <- assertion(chunkCoordination)
    } yield testResult

  sealed trait Animal
  case class Dog(name: String) extends Animal
  case class Cat(name: String) extends Animal

  val dog: Animal = Dog("dog1")
  val cat1: Cat   = Cat("cat1")
  val cat2: Cat   = Cat("cat2")

  case class Resource(idx: Int)
}
