package zio.stream

import zio._
import zio.stream.SinkUtils._
import zio.test.Assertion._
import zio.test.TestAspect.jvmOnly
import zio.test._

object ZSinkSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = {
    suite("ZSinkSpec")(
      suite("Constructors")(
        suite("drain")(
          test("fails if upstream fails") {
            ZStream(1)
              .mapZIO(_ => ZIO.fail("boom!"))
              .run(ZSink.drain)
              .exit
              .map(assert(_)(fails(equalTo("boom!"))))
          }
        ),
        suite("collectAllN")(
          test("respects the given limit") {
            ZStream
              .fromChunk(Chunk(1, 2, 3, 4))
              .transduce(ZSink.collectAllN[Int](3))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Chunk(1, 2, 3), Chunk(4)))))
          },
          test("produces empty trailing chunks") {
            ZStream
              .fromChunk(Chunk(1, 2, 3, 4))
              .transduce(ZSink.collectAllN[Int](4))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Chunk(1, 2, 3, 4), Chunk()))))
          },
          test("handles empty input") {
            ZStream
              .fromChunk(Chunk.empty: Chunk[Int])
              .transduce(ZSink.collectAllN[Int](3))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Chunk()))))
          }
        ),
        test("collectAllToSet")(
          assertZIO(
            ZStream(1, 2, 3, 3, 4)
              .run(ZSink.collectAllToSet[Int])
          )(equalTo(Set(1, 2, 3, 4)))
        ),
        suite("collectAllToSetN")(
          test("respect the given limit") {
            ZStream
              .fromChunks(Chunk(1, 2, 1), Chunk(2, 3, 3, 4))
              .transduce(ZSink.collectAllToSetN[Int](3))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Set(1, 2, 3), Set(4)))))
          },
          test("handles empty input") {
            ZStream
              .fromChunk(Chunk.empty: Chunk[Int])
              .transduce(ZSink.collectAllToSetN[Int](3))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Set.empty[Int]))))
          }
        ),
        test("collectAllToMap")(
          assertZIO(
            ZStream
              .range(0, 10)
              .run(ZSink.collectAllToMap((_: Int) % 3)(_ + _))
          )(equalTo(Map[Int, Int](0 -> 18, 1 -> 12, 2 -> 15)))
        ),
        suite("collectAllToMapN")(
          test("respects the given limit") {
            ZStream
              .fromChunk(Chunk(1, 1, 2, 2, 3, 2, 4, 5))
              .transduce(ZSink.collectAllToMapN(2)((_: Int) % 3)(_ + _))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Map(1 -> 2, 2 -> 4), Map(0 -> 3, 2 -> 2), Map(1 -> 4, 2 -> 5)))))
          },
          test("collects as long as map size doesn't exceed the limit") {
            ZStream
              .fromChunks(Chunk(0, 1, 2), Chunk(3, 4, 5), Chunk(6, 7, 8, 9))
              .transduce(ZSink.collectAllToMapN(3)((_: Int) % 3)(_ + _))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Map(0 -> 18, 1 -> 12, 2 -> 15)))))
          },
          test("handles empty input") {
            ZStream
              .fromChunk(Chunk.empty: Chunk[Int])
              .transduce(ZSink.collectAllToMapN(2)((_: Int) % 3)(_ + _))
              .runCollect
              .map(assert(_)(equalTo(Chunk(Map.empty[Int, Int]))))
          }
        ),
        suite("collectAllToMapValue")(
          test("creates map") {
            case class X(a: Int, b: String)
            assertZIO(
              ZStream
                .fromChunk(Chunk(X(1, "One"), X(2, "Two"), X(3, "Three")))
                .run(ZSink.collectAllToMapValue[X, Int, String](_.a)(_.b)((_, r) => r))
            )(equalTo(Map[Int, String](1 -> "One", 2 -> "Two", 3 -> "Three")))
          },
          test("combiens value") {
            case class X(a: Int, b: String)
            assertZIO(
              ZStream
                .fromChunk(Chunk(X(1, "One"), X(2, "Two"), X(3, "Three"), X(3, "Three2"), X(1, "One2")))
                .run(ZSink.collectAllToMapValue[X, Int, String](_.a)(_.b)(_ + _))
            )(equalTo(Map[Int, String](1 -> "OneOne2", 2 -> "Two", 3 -> "ThreeThree2")))
          }
        ),
        suite("collectAllToMapValueN")(
          test("respects the given limit") {
            case class X(a: Int, b: String)
            assertZIO(
              ZStream
                .fromChunk(Chunk(X(1, "One"), X(2, "Two"), X(3, "Three")))
                .run(ZSink.collectAllToMapValueN[Nothing, X, Int, String](2)(_.a)(_.b)((_, r) => r))
            )(equalTo(Map[Int, String](1 -> "One", 2 -> "Two")))
          },
          test("collects as long as map size doesn't exceed the limit") {
            case class X(a: Int, b: String)
            ZStream
              .fromChunks(
                Chunk(0, 1, 2).map(X(_, "a")),
                Chunk(3, 4, 5).map(X(_, "b")),
                Chunk(6, 7, 8, 9).map(X(_, "c"))
              )
              .run(ZSink.collectAllToMapValueN[Nothing, X, Int, String](3)(_.a % 3)(_.b)(_ + _))
              .map(assert(_)(equalTo(Map(0 -> "abcc", 1 -> "abc", 2 -> "abc"))))
          },
          test("handles empty input") {
            case class X(a: Int, b: String)
            ZStream
              .fromChunk(Chunk.empty: Chunk[X])
              .run(ZSink.collectAllToMapValueN[Nothing, X, Int, String](2)(_.a)(_.b)((_, r) => r))
              .map(assert(_)(equalTo(Map.empty[Int, String])))
          }
        ),
        test("dropUntil")(
          assertZIO(
            ZStream(1, 2, 3, 4, 5, 1, 2, 3, 4, 5)
              .pipeThrough(ZSink.dropUntil[Int](_ >= 3))
              .runCollect
          )(equalTo(Chunk(4, 5, 1, 2, 3, 4, 5)))
        ),
        suite("dropUntilZIO")(
          test("happy path")(
            assertZIO(
              ZStream(1, 2, 3, 4, 5, 1, 2, 3, 4, 5)
                .pipeThrough(ZSink.dropUntilZIO(x => ZIO.succeed(x >= 3)))
                .runCollect
            )(equalTo(Chunk(4, 5, 1, 2, 3, 4, 5)))
          ),
          test("error")(
            assertZIO {
              (ZStream(1, 2, 3) ++ ZStream.fail("Aie") ++ ZStream(5, 1, 2, 3, 4, 5))
                .pipeThrough(ZSink.dropUntilZIO[Any, String, Int](x => ZIO.succeed(x >= 2)))
                .either
                .runCollect
            }(equalTo(Chunk(Right(3), Left("Aie"))))
          )
        ),
        test("dropWhile")(
          assertZIO(
            ZStream(1, 2, 3, 4, 5, 1, 2, 3, 4, 5)
              .pipeThrough(ZSink.dropWhile[Int](_ < 3))
              .runCollect
          )(equalTo(Chunk(3, 4, 5, 1, 2, 3, 4, 5)))
        ),
        suite("dropWhileZIO")(
          test("happy path")(
            assertZIO(
              ZStream(1, 2, 3, 4, 5, 1, 2, 3, 4, 5)
                .dropWhileZIO(x => ZIO.succeed(x < 3))
                .runCollect
            )(equalTo(Chunk(3, 4, 5, 1, 2, 3, 4, 5)))
          ),
          test("error")(
            assertZIO {
              (ZStream(1, 2, 3) ++ ZStream.fail("Aie") ++ ZStream(5, 1, 2, 3, 4, 5))
                .pipeThrough(ZSink.dropWhileZIO[Any, String, Int](x => ZIO.succeed(x < 3)))
                .either
                .runCollect
            }(equalTo(Chunk(Right(3), Left("Aie"))))
          )
        ),
        suite("ensuring") {
          test("happy path") {
            for {
              ref    <- Ref.make(false)
              _      <- ZStream(1, 2, 3, 4, 5).run(ZSink.drain.ensuring(ref.set(true)))
              result <- ref.get
            } yield assertTrue(result)
          } +
            test("error") {
              for {
                ref    <- Ref.make(false)
                _      <- ZStream.fail("boom!").run(ZSink.drain.ensuring(ref.set(true))).ignore
                result <- ref.get
              } yield assertTrue(result)
            }
        },
        suite("environmentWithSink")(
          test("environmentWithSink") {
            assertZIO(
              ZStream("ignore this")
                .run(
                  ZSink
                    .environmentWithSink[String](environment => ZSink.succeed(environment.get))
                    .provideEnvironment(ZEnvironment("use this"))
                )
            )(equalTo("use this"))
          }
        ),
        suite("collectAllWhileWith")(
          test("example 1") {
            ZIO
              .foreach(List(1, 3, 20)) { chunkSize =>
                assertZIO(
                  ZStream
                    .fromChunk(Chunk.fromIterable(1 to 10))
                    .rechunk(chunkSize)
                    .run(ZSink.sum[Int].collectAllWhileWith(-1)((s: Int) => s == s)(_ + _))
                )(equalTo(54))
              }
              .map(_.reduce(_ && _))
          },
          test("example 2") {
            val sink = ZSink
              .head[Int]
              .collectAllWhileWith[List[Int]](Nil)((a: Option[Int]) => a.fold(true)(_ < 5))(
                (a: List[Int], b: Option[Int]) => a ++ b.toList
              )
            val stream = ZStream.fromChunk(Chunk.fromIterable(1 to 100))
            assertZIO((stream ++ stream).rechunk(3).run(sink))(equalTo(List(1, 2, 3, 4)))
          }
        ),
        test("head")(
          check(Gen.listOf(Gen.small(Gen.chunkOfN(_)(Gen.int)))) { chunks =>
            val headOpt = ZStream.fromChunks(chunks: _*).run(ZSink.head[Int])
            assertZIO(headOpt)(equalTo(chunks.flatMap(_.toSeq).headOption))
          }
        ),
        test("last")(
          check(Gen.listOf(Gen.small(Gen.chunkOfN(_)(Gen.int)))) { chunks =>
            val lastOpt = ZStream.fromChunks(chunks: _*).run(ZSink.last)
            assertZIO(lastOpt)(equalTo(chunks.flatMap(_.toSeq).lastOption))
          }
        ),
        suite("unwrapScoped")(
          test("happy path") {
            for {
              closed <- Ref.make[Boolean](false)
              res     = ZIO.acquireRelease(ZIO.succeed(100))(_ => closed.set(true))
              sink =
                ZSink.unwrapScoped(res.map(m => ZSink.count.mapZIO(cnt => closed.get.map(cl => (cnt + m, cl)))))
              resAndState <- ZStream(1, 2, 3).run(sink)
              finalState  <- closed.get
            } yield {
              assert(resAndState._1)(equalTo(103L)) && assert(resAndState._2)(isFalse) && assert(finalState)(isTrue)
            }
          },
          test("sad path") {
            for {
              closed     <- Ref.make[Boolean](false)
              res         = ZIO.acquireRelease(ZIO.succeed(100))(_ => closed.set(true))
              sink        = ZSink.unwrapScoped(res.map(_ => ZSink.succeed("ok")))
              r          <- ZStream.fail("fail").run(sink)
              finalState <- closed.get
            } yield assert(r)(equalTo("ok")) && assert(finalState)(isTrue)
          }
        )
      ),
      test("map")(
        assertZIO(ZStream.range(1, 10).run(ZSink.succeed(1).map(_.toString)))(
          equalTo("1")
        )
      ),
      suite("mapZIO")(
        test("happy path")(
          assertZIO(ZStream.range(1, 10).run(ZSink.succeed(1).mapZIO(e => ZIO.succeed(e + 1))))(
            equalTo(2)
          )
        ),
        test("failure")(
          assertZIO(ZStream.range(1, 10).run(ZSink.succeed(1).mapZIO(_ => ZIO.fail("fail"))).flip)(
            equalTo("fail")
          )
        )
      ),
      test("filterInput")(
        assertZIO(ZStream.range(1, 10).run(ZSink.collectAll.filterInput(_ % 2 == 0)))(
          equalTo(Chunk(2, 4, 6, 8))
        )
      ),
      suite("filterInputZIO")(
        test("happy path")(
          assertZIO(ZStream.range(1, 10).run(ZSink.collectAll.filterInputZIO(i => ZIO.succeed(i % 2 == 0))))(
            equalTo(Chunk(2, 4, 6, 8))
          )
        ),
        test("failure")(
          assertZIO(ZStream.range(1, 10).run(ZSink.collectAll.filterInputZIO(_ => ZIO.fail("fail"))).flip)(
            equalTo("fail")
          )
        )
      ),
      test("mapError")(
        assertZIO(ZStream.range(1, 10).run(ZSink.fail("fail").mapError(s => s + "!")).either)(
          equalTo(Left("fail!"))
        )
      ),
      test("mapErrorZIO")(
        assertZIO(ZStream.range(1, 10).run(ZSink.fail("fail").mapErrorZIO(s => ZIO.succeed(s + "!"))).either)(
          equalTo(Left("fail!"))
        )
      ),
      test("as")(
        assertZIO(ZStream.range(1, 10).run(ZSink.succeed(1).as("as")))(
          equalTo("as")
        )
      ),
      suite("contramap")(
        test("happy path") {
          val parser = ZSink.collectAll[Int].contramap[String](_.toInt)
          assertZIO(ZStream("1", "2", "3").run(parser))(equalTo(Chunk(1, 2, 3)))
        },
        test("error") {
          val parser = ZSink.fail("Ouch").contramap[String](_.toInt)
          assertZIO(ZStream("1", "2", "3").run(parser).either)(isLeft(equalTo("Ouch")))
        } @@ zioTag(errors)
      ),
      suite("contramapChunks")(
        test("happy path") {
          val parser = ZSink.collectAll[Int].contramapChunks[String](_.map(_.toInt))
          assertZIO(ZStream("1", "2", "3").run(parser))(equalTo(Chunk(1, 2, 3)))
        },
        test("error") {
          val parser = ZSink.fail("Ouch").contramapChunks[String](_.map(_.toInt))
          assertZIO(ZStream("1", "2", "3").run(parser).either)(isLeft(equalTo("Ouch")))
        } @@ zioTag(errors)
      ),
      suite("contramapZIO")(
        test("happy path") {
          val parser = ZSink.collectAll[Int].contramapZIO[Any, Throwable, String](a => ZIO.attempt(a.toInt))
          assertZIO(ZStream("1", "2", "3").run(parser))(equalTo(Chunk(1, 2, 3)))
        },
        test("error") {
          val parser = ZSink.fail("Ouch").contramapZIO[Any, String, String](a => ZIO.succeed(a.toInt))
          assertZIO(ZStream("1", "2", "3").run(parser).either)(isLeft(equalTo("Ouch")))
        } @@ zioTag(errors),
        test("error in transformation") {
          val parser = ZSink.collectAll[Int].contramapZIO[Any, Throwable, String](a => ZIO.attempt(a.toInt))
          assertZIO(ZStream("1", "a").run(parser).either)(isLeft(hasMessage(equalTo("For input string: \"a\""))))
        } @@ zioTag(errors)
      ),
      suite("contramapChunksZIO")(
        test("happy path") {
          val parser =
            ZSink
              .collectAll[Int]
              .contramapChunksZIO[Any, Throwable, String](_.mapZIO(a => ZIO.attempt(a.toInt)))
          assertZIO(ZStream("1", "2", "3").run(parser))(equalTo(Chunk(1, 2, 3)))
        },
        test("error") {
          val parser =
            ZSink.fail("Ouch").contramapChunksZIO[Any, String, String](_.mapZIO(a => ZIO.succeed(a.toInt)))
          assertZIO(ZStream("1", "2", "3").run(parser).either)(isLeft(equalTo("Ouch")))
        } @@ zioTag(errors),
        test("error in transformation") {
          val parser =
            ZSink
              .collectAll[Int]
              .contramapChunksZIO[Any, Throwable, String](_.mapZIO(a => ZIO.attempt(a.toInt)))
          assertZIO(ZStream("1", "a").run(parser).either)(isLeft(hasMessage(equalTo("For input string: \"a\""))))
        } @@ zioTag(errors)
      ),
      test("collectAllUntil") {
        val sink   = ZSink.collectAllUntil[Int](_ > 4)
        val input  = List(Chunk(3, 4, 5, 6, 7, 2), Chunk.empty, Chunk(3, 4, 5, 6, 5, 4, 3, 2), Chunk.empty)
        val result = ZStream.fromChunks(input: _*).transduce(sink).runCollect
        assertZIO(result)(
          equalTo(Chunk(Chunk(3, 4, 5), Chunk(6), Chunk(7), Chunk(2, 3, 4, 5), Chunk(6), Chunk(5), Chunk(4, 3, 2)))
        )
      },
      test("collectAllUntilZIO") {
        val sink   = ZSink.collectAllUntilZIO[Any, Nothing, Int]((i: Int) => ZIO.succeed(i > 4))
        val input  = List(Chunk(3, 4, 5, 6, 7, 2), Chunk.empty, Chunk(3, 4, 5, 6, 5, 4, 3, 2), Chunk.empty)
        val result = ZStream.fromChunks(input: _*).transduce(sink).runCollect
        assertZIO(result)(
          equalTo(Chunk(Chunk(3, 4, 5), Chunk(6), Chunk(7), Chunk(2, 3, 4, 5), Chunk(6), Chunk(5), Chunk(4, 3, 2)))
        )
      },
      test("collectAllWhile") {
        val sink   = ZSink.collectAllWhile[Int](_ < 5) <* ZSink.collectAllWhile[Int](_ >= 5)
        val input  = List(Chunk(3, 4, 5, 6, 7, 2), Chunk.empty, Chunk(3, 4, 5, 6, 5, 4, 3, 2), Chunk.empty)
        val result = ZStream.fromChunks(input: _*).transduce(sink).runCollect
        assertZIO(result)(
          equalTo(Chunk(Chunk(3, 4), Chunk(2, 3, 4), Chunk(4, 3, 2)))
        )
      },
      test("collectAllWhileZIO") {
        val sink = ZSink.collectAllWhileZIO[Any, Nothing, Int]((i: Int) => ZIO.succeed(i < 5)) <* ZSink
          .collectAllWhileZIO[Any, Nothing, Int]((i: Int) => ZIO.succeed(i >= 5))
        val input  = List(Chunk(3, 4, 5, 6, 7, 2), Chunk.empty, Chunk(3, 4, 5, 6, 5, 4, 3, 2), Chunk.empty)
        val result = ZStream.fromChunks(input: _*).transduce(sink).runCollect
        assertZIO(result)(
          equalTo(Chunk(Chunk(3, 4), Chunk(2, 3, 4), Chunk(4, 3, 2)))
        )
      },
      test("foldLeft equivalence with Chunk#foldLeft")(
        check(
          Gen.small(ZStreamGen.pureStreamGen(Gen.int, _)),
          Gen.function2(Gen.string),
          Gen.string
        ) { (s, f, z) =>
          for {
            xs <- s.run(ZSink.foldLeft(z)(f))
            ys <- s.runCollect.map(_.foldLeft(z)(f))
          } yield assert(xs)(equalTo(ys))
        }
      ),
      suite("foldZIO")(
        test("empty")(
          assertZIO(
            ZStream.empty
              .transduce(
                ZSink.foldZIO(0)(_ => true)((x, y: Int) => ZIO.succeed(x + y))
              )
              .runCollect
          )(equalTo(Chunk(0)))
        ),
        test("short circuits") {
          val empty: ZStream[Any, Nothing, Int]     = ZStream.empty
          val single: ZStream[Any, Nothing, Int]    = ZStream.succeed(1)
          val double: ZStream[Any, Nothing, Int]    = ZStream(1, 2)
          val failed: ZStream[Any, String, Nothing] = ZStream.fail("Ouch")

          def run[E](stream: ZStream[Any, E, Int]) =
            (for {
              effects <- Ref.make[List[Int]](Nil)
              exit <- stream
                        .transduce(ZSink.foldZIO(0)(_ => true) { (_, a: Int) =>
                          effects.update(a :: _) *> ZIO.succeed(30)
                        })
                        .runCollect
              result <- effects.get
            } yield exit -> result).exit

          (assertZIO(run(empty))(succeeds(equalTo((Chunk(0), Nil)))) <*>
            assertZIO(run(single))(succeeds(equalTo((Chunk(30), List(1))))) <*>
            assertZIO(run(double))(succeeds(equalTo((Chunk(30), List(2, 1))))) <*>
            assertZIO(run(failed))(fails(equalTo("Ouch")))).map { case (r1, r2, r3, r4) =>
            r1 && r2 && r3 && r4
          }
        },
        test("equivalence with List#foldLeft") {
          val ioGen = ZStreamGen.successes(Gen.string)
          check(Gen.small(ZStreamGen.pureStreamGen(Gen.int, _)), Gen.function2(ioGen), ioGen) { (s, f, z) =>
            for {
              sinkResult <- z.flatMap(z => s.run(ZSink.foldLeftZIO(z)(f)))
              foldResult <- s.runFold(List[Int]())((acc, el) => el :: acc)
                              .map(_.reverse)
                              .flatMap(_.foldLeft(z)((acc, el) => acc.flatMap(f(_, el))))
                              .exit
            } yield assert(foldResult.isSuccess)(isTrue) implies assert(foldResult)(succeeds(equalTo(sinkResult)))
          }
        }
      ),
      suite("fold")(
        test("empty")(
          assertZIO(
            ZStream.empty
              .transduce(ZSink.fold[Int, Int](0)(_ => true)(_ + _))
              .runCollect
          )(equalTo(Chunk(0)))
        ),
        test("short circuits") {
          val empty: ZStream[Any, Nothing, Int]     = ZStream.empty
          val single: ZStream[Any, Nothing, Int]    = ZStream.succeed(1)
          val double: ZStream[Any, Nothing, Int]    = ZStream(1, 2)
          val failed: ZStream[Any, String, Nothing] = ZStream.fail("Ouch")

          def run[E](stream: ZStream[Any, E, Int]) =
            (for {
              effects <- Ref.make[List[Int]](Nil)
              exit <- stream
                        .transduce(ZSink.foldZIO(0)(_ => true) { (_, a: Int) =>
                          effects.update(a :: _) *> ZIO.succeed(30)
                        })
                        .runCollect
              result <- effects.get
            } yield (exit, result)).exit

          (assertZIO(run(empty))(succeeds(equalTo((Chunk(0), Nil)))) <*>
            assertZIO(run(single))(succeeds(equalTo((Chunk(30), List(1))))) <*>
            assertZIO(run(double))(succeeds(equalTo((Chunk(30), List(2, 1))))) <*>
            assertZIO(run(failed))(fails(equalTo("Ouch")))).map { case (r1, r2, r3, r4) =>
            r1 && r2 && r3 && r4
          }
        },
        test("termination in the middle")(
          assertZIO(ZStream.range(1, 10).run(ZSink.fold[Int, Int](0)(_ <= 5)((a, b) => a + b)))(equalTo(6))
        ),
        test("immediate termination")(
          assertZIO(ZStream.range(1, 10).run(ZSink.fold[Int, Int](0)(_ <= -1)((a, b) => a + b)))(equalTo(0))
        ),
        test("termination in the middle")(
          assertZIO(ZStream.range(1, 10).run(ZSink.fold[Int, Int](0)(_ <= 500)((a, b) => a + b)))(equalTo(45))
        )
      ),
      test("foldUntil")(
        assertZIO(
          ZStream[Long](1, 1, 1, 1, 1, 1)
            .transduce(ZSink.foldUntil(0L, 3)(_ + (_: Long)))
            .runCollect
        )(equalTo(Chunk(3L, 3L, 0L)))
      ),
      test("foldUntilZIO")(
        assertZIO(
          ZStream[Long](1, 1, 1, 1, 1, 1)
            .transduce(ZSink.foldUntilZIO(0L, 3)((s, a: Long) => ZIO.succeed(s + a)))
            .runCollect
        )(equalTo(Chunk(3L, 3L, 0L)))
      ),
      test("foldWeighted")(
        assertZIO(
          ZStream[Long](1, 5, 2, 3)
            .transduce(
              ZSink.foldWeighted(List[Long]())((_, x: Long) => x * 2, 12)((acc, el) => el :: acc).map(_.reverse)
            )
            .runCollect
        )(equalTo(Chunk(List(1L, 5L), List(2L, 3L))))
      ),
      suite("foldWeightedDecompose")(
        test("simple example")(
          assertZIO(
            ZStream(1, 5, 1)
              .transduce(
                ZSink
                  .foldWeightedDecompose(List[Int]())(
                    (_, i: Int) => i.toLong,
                    4,
                    (i: Int) =>
                      if (i > 1) Chunk(i - 1, 1)
                      else Chunk(i)
                  )((acc, el) => el :: acc)
                  .map(_.reverse)
              )
              .runCollect
          )(equalTo(Chunk(List(1, 3), List(1, 1, 1))))
        ),
        test("empty stream")(
          assertZIO(
            ZStream.empty
              .transduce(
                ZSink.foldWeightedDecompose(0)((_, x: Int) => x.toLong, 1000, Chunk.single(_: Int))(_ + _)
              )
              .runCollect
          )(equalTo(Chunk(0)))
        )
      ),
      test("foldWeightedZIO")(
        assertZIO(
          ZStream[Long](1, 5, 2, 3)
            .transduce(
              ZSink
                .foldWeightedZIO(List.empty[Long])((_, a: Long) => ZIO.succeed(a * 2), 12)((acc, el) =>
                  ZIO.succeed(el :: acc)
                )
                .map(_.reverse)
            )
            .runCollect
        )(equalTo(Chunk(List(1L, 5L), List(2L, 3L))))
      ),
      suite("foldWeightedDecomposeZIO")(
        test("simple example")(
          assertZIO(
            ZStream(1, 5, 1)
              .transduce(
                ZSink
                  .foldWeightedDecomposeZIO(List.empty[Int])(
                    (_, i: Int) => ZIO.succeed(i.toLong),
                    4,
                    (i: Int) =>
                      ZIO.succeed(
                        if (i > 1) Chunk(i - 1, 1)
                        else Chunk(i)
                      )
                  )((acc, el) => ZIO.succeed(el :: acc))
                  .map(_.reverse)
              )
              .runCollect
          )(equalTo(Chunk(List(1, 3), List(1, 1, 1))))
        ),
        test("empty stream")(
          assertZIO(
            ZStream.empty
              .transduce(
                ZSink.foldWeightedDecomposeZIO[Any, Nothing, Int, Int](0)(
                  (_, x) => ZIO.succeed(x.toLong),
                  1000,
                  x => ZIO.succeed(Chunk.single(x))
                )((x, y) => ZIO.succeed(x + y))
              )
              .runCollect
          )(equalTo(Chunk(0)))
        )
      ),
      suite("fail")(
        test("handles leftovers") {
          val s =
            ZSink
              .fail("boom")
              .foldSink(err => ZSink.collectAll[Int].map(c => (c, err)), _ => sys.error("impossible"))
          assertZIO(ZStream(1, 2, 3).run(s))(equalTo((Chunk(1, 2, 3), "boom")))
        },
        test("foldSink over a dying sink") {
          case object Death extends RuntimeException("not today!")
          val dyingSink: ZSink[Any, Nothing, Any, Nothing, Nothing] = ZSink.failCause(Cause.die(Death))
          val foldedSink: ZSink[Any, String, Any, Nothing, String] = dyingSink
            .foldSink(
              _ => ZSink.fail("it's a fail"),
              _ => ZSink.succeed("it's a success")
            )

          val ex = ZStream
            .range(0, 40, 10)
            .run(foldedSink)
            .exit

          ex.map { ex1 =>
            assertTrue {
              ex1.isFailure &&
              ex1.causeOption.flatMap(_.dieOption) ==
                Some(Death)
            }
          }

        }
      ),
      suite("refine")(
        test("refineOrDie") {
          val exception = new Exception
          val refinedTo = "refined"
          val s =
            ZSink
              .fail(exception)
              .refineOrDie { case _: Exception =>
                refinedTo
              }
          assertZIO(ZStream(1, 2, 3).run(s).exit)(fails(equalTo(refinedTo)))
        },
        test("refineOrDieWith - refines") {
          object SinkFailure extends Throwable()
          object RefinedTo   extends Throwable()
          val s =
            ZSink
              .fail(SinkFailure)
              .refineOrDieWith { case _: SinkFailure.type => RefinedTo }(_ => new Throwable())
          assertZIO(ZStream(1, 2, 3).run(s).exit)(fails(equalTo(RefinedTo)))
        },
        test("refineOrDieWith - dies") {
          object DieWith extends RuntimeException()
          val s =
            ZSink
              .fail(new Throwable("Sink failed"))
              .refineOrDieWith { case _: RuntimeException => new RuntimeException("Refined") }(_ => DieWith)
          assertZIO(ZStream(1, 2, 3).run(s).exit)(dies(equalTo(DieWith)))
        }
      ),
      // suite("foreach")(
      //   test("preserves leftovers in case of failure") {
      //     for {
      //       acc <- Ref.make[Int](0)
      //       s    = ZSink.foreach[Any, String, Int]((i: Int) => if (i == 4) ZIO.fail("boom") else acc.update(_ + i))
      //       sink = s.foldSink(_ => ZSink.collectAll[String, Int], _ => sys.error("impossible"))
      //       leftover <- ZStream.fromChunks(Chunk(1, 2), Chunk(3, 4, 5)).run(sink)
      //       sum      <- acc.get
      //     } yield {
      //       assert(sum)(equalTo(6)) && assert(leftover)(equalTo(Chunk(5)))
      //     }
      //   }
      // ),
      suite("foreachWhile")(
        test("handles leftovers") {
          val leftover = ZStream
            .fromIterable(1 to 5)
            .run(ZSink.foreachWhile((n: Int) => ZIO.succeed(n <= 3)).collectLeftover)
            .map(_._2)
          assertZIO(leftover)(equalTo(Chunk(4, 5)))
        }
      ),
      suite("fromZIO")(
        test("result is ok") {
          val s = ZSink.fromZIO(ZIO.succeed("ok"))
          assertZIO(ZStream(1, 2, 3).run(s))(
            equalTo("ok")
          )
        }
      ),
      suite("fromQueue")(
        test("should enqueue all elements") {

          for {
            queue          <- Queue.unbounded[Int]
            _              <- ZStream(1, 2, 3).run(ZSink.fromQueue(queue))
            enqueuedValues <- queue.takeAll
          } yield {
            assert(enqueuedValues)(hasSameElementsDistinct(List(1, 2, 3)))
          }

        }
      ),
      suite("fromQueueWithShutdown")(
        test("should enqueue all elements and shutsdown queue") {

          def createQueueSpy[A](q: Queue[A]) = new Queue[A] {

            @volatile
            private var isShutDown = false

            override def awaitShutdown(implicit trace: Trace): UIO[Unit] = q.awaitShutdown

            override def capacity: Int = q.capacity

            override def isShutdown(implicit trace: Trace): UIO[Boolean] = ZIO.succeed(this.isShutDown)

            override def offer(a: A)(implicit trace: Trace): ZIO[Any, Nothing, Boolean] = q.offer(a)

            override def offerAll[A1 <: A](as: Iterable[A1])(implicit trace: Trace): ZIO[Any, Nothing, Chunk[A1]] =
              q.offerAll(as)

            override def shutdown(implicit trace: Trace): UIO[Unit] = ZIO.succeed(this.isShutDown = true)

            override def size(implicit trace: Trace): UIO[Int] = q.size

            override def take(implicit trace: Trace): ZIO[Any, Nothing, A] = q.take

            override def takeAll(implicit trace: Trace): ZIO[Any, Nothing, Chunk[A]] = q.takeAll

            override def takeUpTo(max: Int)(implicit trace: Trace): ZIO[Any, Nothing, Chunk[A]] =
              q.takeUpTo(max)

          }

          for {
            queue          <- Queue.unbounded[Int].map(createQueueSpy)
            _              <- ZStream(1, 2, 3).run(ZSink.fromQueueWithShutdown(queue))
            enqueuedValues <- queue.takeAll
            isShutdown     <- queue.isShutdown
          } yield {
            assert(enqueuedValues)(hasSameElementsDistinct(List(1, 2, 3))) && assert(isShutdown)(isTrue)
          }

        }
      ),
      suite("fromHub")(
        test("should publish all elements") {

          for {
            promise1 <- Promise.make[Nothing, Unit]
            promise2 <- Promise.make[Nothing, Unit]
            hub      <- Hub.unbounded[Int]
            f <- ZIO.scoped {
                   hub.subscribe.flatMap(s => promise1.succeed(()) *> promise2.await *> s.takeAll)
                 }.fork
            _              <- promise1.await
            _              <- ZStream(1, 2, 3).run(ZSink.fromHub(hub))
            _              <- promise2.succeed(())
            publisedValues <- f.join
          } yield {
            assert(publisedValues)(hasSameElementsDistinct(List(1, 2, 3)))
          }

        }
      ),
      suite("fromHubWithShutdown")(
        test("should shutdown hub") {
          for {
            hub        <- Hub.unbounded[Int]
            _          <- ZStream(1, 2, 3).run(ZSink.fromHubWithShutdown(hub))
            isShutdown <- hub.isShutdown
          } yield {
            assert(isShutdown)(isTrue)
          }

        }
      ),
      suite("succeed")(
        test("result is ok") {
          assertZIO(ZStream(1, 2, 3).run(ZSink.succeed("ok")))(
            equalTo("ok")
          )
        }
      ),
      suite("Combinators")(
        test("raceBoth") {
          check(Gen.listOf(Gen.int(0, 10)), Gen.boolean, Gen.boolean) { (ints, success1, success2) =>
            val stream = ints ++ (if (success1) List(20) else Nil) ++ (if (success2) List(40) else Nil)
            sinkRaceLaw(
              ZStream.fromIterableZIO(Random.shuffle(stream)),
              findSink(20),
              findSink(40)
            )
          }
        },
        suite("zipWithPar")(
          test("coherence") {
            check(Gen.listOf(Gen.int(0, 10)), Gen.boolean, Gen.boolean) { (ints, success1, success2) =>
              val stream = ints ++ (if (success1) List(20) else Nil) ++ (if (success2) List(40) else Nil)

              zipParLaw(
                ZStream.fromIterableZIO(Random.shuffle(stream)),
                findSink(20),
                findSink(40)
              )
            }
          },
          suite("zipRight (*>)")(
            test("happy path") {
              assertZIO(ZStream(1, 2, 3).run(ZSink.head.zipParRight(ZSink.succeed("Hello"))))(
                equalTo(("Hello"))
              )
            }
          ),
          suite("zipWith")(test("happy path") {
            assertZIO(ZStream(1, 2, 3).run(ZSink.head.zipParLeft(ZSink.succeed("Hello"))))(
              equalTo(Some(1))
            )
          }),
          test("early termination") {
            val stream = ZStream.range(0, 1000, 1)
            val sink   = ZSink.drain <&> ZSink.succeed(20)
            for {
              _ <- stream.run(sink)
            } yield assertCompletes
          }
        ),
        suite("splitWhere")(
          test("should split a stream on predicate and run each part into the sink") {
            val in = ZStream(1, 2, 3, 4, 5, 6, 7, 8)
            for {
              res <- in.via(ZPipeline.fromSink(ZSink.collectAll[Int].splitWhere[Int](_ % 2 == 0))).runCollect
            } yield {
              assertTrue(res == Chunk(Chunk(1), Chunk(2, 3), Chunk(4, 5), Chunk(6, 7), Chunk(8)))
            }
          },
          test("should split a stream on predicate and run each part into the sink, in several chunks") {
            val in = ZStream.fromChunks(Chunk(1, 2, 3, 4), Chunk(5, 6, 7, 8))
            for {
              res <- in.via(ZPipeline.fromSink(ZSink.collectAll[Int].splitWhere[Int](_ % 2 == 0))).runCollect
            } yield {
              assertTrue(res == Chunk(Chunk(1), Chunk(2, 3), Chunk(4, 5), Chunk(6, 7), Chunk(8)))
            }
          },
          test("not yield an empty sink if split on the first element") {
            val in = ZStream(1, 2, 3, 4, 5, 6, 7, 8)
            for {
              res <- in.via(ZPipeline.fromSink(ZSink.collectAll[Int].splitWhere[Int](_ % 2 != 0))).runCollect
            } yield {
              assertTrue(res == Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5, 6), Chunk(7, 8)))
            }
          }
        ),
        test("findZIO with head sink") {
          val sink: ZSink[Any, Nothing, Int, Int, Option[Option[Int]]] =
            ZSink.head[Int].findZIO(h => ZIO.succeed(h.fold(false)(_ >= 10)))
          val assertions = ZIO.foreach(Chunk(1, 3, 7, 20)) { n =>
            assertZIO(ZStream.fromIterable(1 to 100).rechunk(n).run(sink))(equalTo(Some(Some(10))))
          }
          assertions.map(tst => tst.reduce(_ && _))
        },
        test("findZIO take sink across multiple chunks") {
          val sink: ZSink[Any, Nothing, Int, Int, Option[Chunk[Int]]] =
            ZSink.take[Int](4).findZIO(s => ZIO.succeed(s.sum > 10))

          assertZIO(ZStream.fromIterable(1 to 8).rechunk(2).run(sink))(equalTo(Some(Chunk(5, 6, 7, 8))))
        },
        test("findZIO empty stream terminates with none") {
          assertZIO(
            ZStream.fromIterable(List.empty[Int]).run(ZSink.sum[Int].findZIO(s => ZIO.succeed(s > 0)))
          )(equalTo(None))
        },
        test("findZIO unsatisfied condition terminates with none") {
          assertZIO(
            ZStream
              .fromIterable(List(1, 2))
              .run(ZSink.head[Int].findZIO(h => ZIO.succeed(h.fold(false)(_ >= 3))))
          )(equalTo(None))
        },
        suite("flatMap")(
          test("non-empty input") {
            assertZIO(
              ZStream(1, 2, 3)
                .run(ZSink.head[Int].flatMap((x: Option[Int]) => ZSink.succeed(x)))
            )(equalTo(Some(1)))
          },
          test("empty input") {
            assertZIO(ZStream.empty.run(ZSink.head[Int].flatMap(h => ZSink.succeed(h))))(
              equalTo(None)
            )
          },
          test("with leftovers") {
            val headAndCount =
              ZSink.head[Int].flatMap((h: Option[Int]) => ZSink.count.map(cnt => (h, cnt)))
            check(Gen.listOf(Gen.small(Gen.chunkOfN(_)(Gen.int)))) { chunks =>
              ZStream.fromChunks(chunks: _*).run(headAndCount).map {
                case (head, count) => {
                  assert(head)(equalTo(chunks.flatten.headOption)) &&
                  assert(count + head.toList.size)(equalTo(chunks.map(_.size.toLong).sum))
                }
              }
            }
          },
          test("leftovers are kept in order") {
            Ref.make(Chunk[Chunk[Int]]()).flatMap { readData =>
              def takeN(n: Int) =
                ZSink.take[Int](n).mapZIO(c => readData.update(_ :+ c))

              def taker(data: Chunk[Chunk[Int]], n: Int): (Chunk[Int], Chunk[Chunk[Int]], Boolean) = {
                import scala.collection.mutable
                val buffer   = mutable.Buffer(data: _*)
                val builder  = mutable.Buffer[Int]()
                var wasSplit = false

                while (builder.size < n && buffer.nonEmpty) {
                  val popped = buffer.remove(0)

                  if ((builder.size + popped.size) <= n) builder ++= popped
                  else {
                    val splitIndex  = n - builder.size
                    val (take, ret) = popped.splitAt(splitIndex)
                    builder ++= take
                    buffer.prepend(ret)

                    if (splitIndex > 0)
                      wasSplit = true
                  }
                }

                (Chunk.fromIterable(builder), Chunk.fromIterable(buffer), wasSplit)
              }

              val gen =
                for {
                  sequenceSize <- Gen.int(1, 50)
                  takers       <- Gen.int(1, 5)
                  takeSizes    <- Gen.listOfN(takers)(Gen.int(1, sequenceSize))
                  inputs       <- Gen.chunkOfN(sequenceSize)(ZStreamGen.tinyChunkOf(Gen.int))
                  (expectedTakes, leftoverInputs, wasSplit) = takeSizes.foldLeft((Chunk[Chunk[Int]](), inputs, false)) {
                                                                case ((takenChunks, leftover, _), takeSize) =>
                                                                  val (taken, rest, wasSplit) =
                                                                    taker(leftover, takeSize)
                                                                  (takenChunks :+ taken, rest, wasSplit)
                                                              }
                  expectedLeftovers = if (wasSplit) leftoverInputs.head
                                      else Chunk()
                } yield (inputs, takeSizes, expectedTakes, expectedLeftovers)

              check(gen) { case (inputs, takeSizes, expectedTakes, expectedLeftovers) =>
                val takingSinks = takeSizes.map(takeN(_)).reduce(_ *> _).channel.collectElements
                val channel     = ZChannel.writeAll(inputs: _*) >>> takingSinks

                (channel.run <*> readData.getAndSet(Chunk())).map { case (leftovers, _, takenChunks) =>
                  assert(leftovers.flatten)(equalTo(expectedLeftovers)) &&
                    assert(takenChunks)(equalTo(expectedTakes))
                }
              }
            }
          } @@ jvmOnly
        ),
        suite("repeat")(
          test("runs until the source is exhausted") {
            assertZIO(
              ZStream
                .fromChunks(Chunk(1, 2), Chunk(3, 4, 5), Chunk(), Chunk(6, 7), Chunk(8, 9))
                .run(ZSink.take[Int](3).collectAll)
            )(
              equalTo(Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8, 9), Chunk.empty))
            )
          },
          test("combinators") {
            assertZIO(
              ZStream
                .fromChunks(Chunk(1, 2), Chunk(3, 4, 5), Chunk.empty, Chunk(6, 7), Chunk(8, 9))
                .run(ZSink.sum[Int].collectAll.map(_.sum))
            )(
              equalTo(45)
            )
          },
          test("handles errors") {
            assertZIO(
              (ZStream
                .fromChunks(Chunk(1, 2)))
                .run(ZSink.fail(()).collectAll)
                .either
            )(
              isLeft
            )
          }
        ),
        suite("take")(
          test("take")(
            check(Gen.chunkOf(Gen.small(Gen.chunkOfN(_)(Gen.int))), Gen.int) { (chunks, n) =>
              ZIO.scoped {
                ZStream
                  .fromChunks(chunks: _*)
                  .peel(ZSink.take[Int](n))
                  .flatMap { case (chunk, stream) =>
                    stream.runCollect.map { leftover =>
                      assert(chunk)(equalTo(chunks.flatten.take(n))) &&
                      assert(leftover)(equalTo(chunks.flatten.drop(n)))
                    }
                  }
              }
            }
          )
        ),
        test("timed") {
          for {
            f <- ZStream.fromIterable(1 to 10).mapZIO(i => Clock.sleep(10.millis).as(i)).run(ZSink.timed).fork
            _ <- TestClock.adjust(100.millis)
            r <- f.join
          } yield assert(r)(isGreaterThanEqualTo(100.millis))
        },
        suite("provide")(
          suite("provideEnvironment")(
            test("simple") {
              assertZIO(
                ZStream.unit.run(
                  ZSink.service[Int].provideEnvironment(ZEnvironment(100))
                )
              )(equalTo(100))
            },
            test("provide <*> provide") {
              assertZIO(
                ZStream.unit.run(ZSink.service[Int].provideEnvironment(ZEnvironment(100))) <*>
                  ZStream.unit.run(ZSink.service[Int].provideEnvironment(ZEnvironment(200)))
              )(equalTo((100, 200)))
            },
            test("provide is modular") {
              assertZIO(
                ZStream.unit
                  .run(for {
                    v1 <- ZSink.service[Int]
                    v2 <- ZSink.service[Int].provideEnvironment(ZEnvironment(2))
                    v3 <- ZSink.service[Int]
                  } yield (v1, v2, v3))
                  .provideEnvironment(ZEnvironment(4))
              )(equalTo((4, 2, 4)))
            }
          ),
          suite("provideSomeEnvironment")(
            test("simple") {
              assertZIO(
                ZStream.unit.run(
                  ZSink.service[Int].provideSomeEnvironment[Any](_ => ZEnvironment(100))
                )
              )(equalTo(100))
            },
            test("provideSome <*> provideSome") {
              assertZIO(
                ZStream.unit.run(ZSink.service[Int].provideSomeEnvironment[Any](_ => ZEnvironment(100))) <*>
                  ZStream.unit.run(ZSink.service[Int].provideSomeEnvironment[Any](_ => ZEnvironment(200)))
              )(equalTo((100, 200)))
            },
            test("provideSome is modular") {
              assertZIO(
                ZStream.unit
                  .run(for {
                    v1 <- ZSink.service[Int]
                    v2 <- ZSink.service[Int].provideSomeEnvironment[Any](_ => ZEnvironment(2))
                    v3 <- ZSink.service[Int]
                  } yield (v1, v2, v3))
                  .provideSomeEnvironment[Any](_ => ZEnvironment(4))
              )(equalTo((4, 2, 4)))
            }
          ),
          suite("provideLayer")(
            test("simple") {
              assertZIO(
                ZStream.unit.run(ZSink.service[Int].provideLayer(ZLayer.succeed(1)))
              )(equalTo(1))
            },
            test("provideLayer <*> provideLayer") {
              assertZIO(
                ZStream.unit.run(ZSink.service[Int].provideLayer(ZLayer.succeed(100))) <*>
                  ZStream.unit.run(ZSink.service[Int].provideLayer(ZLayer.succeed(200)))
              )(equalTo((100, 200)))
            },
            test("provideLayer is modular") {
              assertZIO(
                ZStream.unit
                  .run(for {
                    v1 <- ZSink.service[Int]
                    v2 <- ZSink.service[Int].provideLayer(ZLayer.succeed(2))
                    v3 <- ZSink.service[Int]
                  } yield (v1, v2, v3))
                  .provideLayer(ZLayer.succeed(4))
              )(equalTo((4, 2, 4)))
            }
          ),
          suite("provideSomeLayer")(
            test("simple") {
              assertZIO(
                ZStream.unit.run(
                  ZSink.service[Int].provideSomeLayer[Any](ZLayer.succeed(100))
                )
              )(equalTo(100))
            },
            test("provideSome <*> provideSome") {
              assertZIO(
                ZStream.unit.run(ZSink.service[Int].provideSomeLayer[Any](ZLayer.succeed(100))) <*>
                  ZStream.unit.run(ZSink.service[Int].provideSomeLayer[Any](ZLayer.succeed(200)))
              )(equalTo((100, 200)))
            },
            test("provideSome is modular") {
              assertZIO(
                ZStream.unit
                  .run(for {
                    v1 <- ZSink.service[Int]
                    v2 <- ZSink.service[Int].provideSomeLayer[Any](ZLayer.succeed(2))
                    v3 <- ZSink.service[Int]
                  } yield (v1, v2, v3))
                  .provideSomeLayer[Any](ZLayer.succeed(4))
              )(equalTo((4, 2, 4)))
            }
          )
        )
      ),
      test("error propagation") {
        case object ErrorStream
        case object ErrorMapped
        case object ErrorSink

        for {
          exit <- ZStream
                    .fail(ErrorStream)
                    .mapError(_ => ErrorMapped)
                    .run(
                      ZSink.drain
                        .contramapZIO((in: Any) => ZIO.attempt(in))
                        .mapError(_ => ErrorSink)
                    )
                    .exit
        } yield assert(exit)(fails(equalTo(ErrorMapped)))
      },
      test("exists") {
        check(Gen.chunkOf(Gen.int), Gen.function(Gen.boolean)) { (chunk, f) =>
          val stream = ZStream.fromChunk(chunk)
          assertZIO(stream.run(ZSink.exists(f)))(equalTo(chunk.exists(f)))
        }
      },
      test("forall") {
        check(Gen.chunkOf(Gen.int), Gen.function(Gen.boolean)) { (chunk, f) =>
          val stream = ZStream.fromChunk(chunk)
          assertZIO(stream.run(ZSink.forall(f)))(equalTo(chunk.forall(f)))
        }
      }
    )
  }
}
