package zio.stream.experimental

import zio._
import zio.test.Assertion._
import zio.test._
import zio.test.environment.{TestClock, TestConsole, TestRandom, TestSystem}

object ZChannelSpec extends ZIOBaseSpec {
  import ZIOTag._

  def spec: Spec[Has[Random] with Has[TestClock] with Has[TestConsole] with Has[TestRandom] with Has[
    TestSystem
  ] with Has[Annotations], TestFailure[Any], TestSuccess] = suite("ZChannelSpec")(
    suite("interpreter")(
      test("ZChannel.succeed") {
        for {
          tuple     <- ZChannel.succeed(1).runCollect
          (chunk, z) = tuple
        } yield assert(chunk)(equalTo(Chunk.empty)) && assert(z)(equalTo(1))
      },
      test("ZChannel.fail") {
        for {
          exit <- ZChannel.fail("Uh oh!").runCollect.exit
        } yield assert(exit)(fails(equalTo("Uh oh!")))
      },
      test("ZChannel.map") {
        for {
          tuple     <- ZChannel.succeed(1).map(_ + 1).runCollect
          (chunk, z) = tuple
        } yield assert(chunk)(equalTo(Chunk.empty)) && assert(z)(equalTo(2))
      },
      suite("ZChannel#flatMap")(
        test("simple") {
          val conduit = for {
            x <- ZChannel.succeed(1)
            y <- ZChannel.succeed(x * 2)
            z <- ZChannel.succeed(x + y)
          } yield x + y + z
          for {
            tuple     <- conduit.runCollect
            (chunk, z) = tuple
          } yield assert(chunk)(equalTo(Chunk.empty)) && assert(z)(equalTo(6))
        },
        test("flatMap structure confusion") {
          assertM(
            (ZChannel
              .write(Chunk(1, 2))
              .concatMap(chunk => ZChannel.writeAll(chunk: _*))
              *> ZChannel.fail("hello")).runDrain.exit
          )(fails(equalTo("hello")))
        }
      ),
      suite("ZChannel#catchAll") {
        test("catchAll structure confusion") {
          assertM(
            ZChannel
              .write(8)
              .catchAll { _ =>
                ZChannel.write(0).concatMap(_ => ZChannel.fail("err0"))
              }
              .concatMap { _ =>
                ZChannel.fail("err1")
              }
              .runCollect
              .exit
          )(fails(equalTo("err1")))
        }
      },
      suite("ZChannel#ensuring")(
        test("prompt closure between continuations") {
          Ref.make(Chunk[String]()).flatMap { events =>
            (ZChannel
              .fromZIO(events.update(_ :+ "Acquire1"))
              .ensuring(events.update(_ :+ "Release11"))
              .ensuring(events.update(_ :+ "Release12")) *>
              ZChannel.fromZIO(events.update(_ :+ "Acquire2")).ensuring(events.update(_ :+ "Release2"))).runDrain *>
              events.get.map(assert(_)(equalTo(Chunk("Acquire1", "Release11", "Release12", "Acquire2", "Release2"))))
          }
        },
        test("last finalizers are deferred to the ZManaged") {
          Ref.make(Chunk[String]()).flatMap { events =>
            def event(label: String) = events.update(_ :+ label)
            val channel =
              (ZChannel.fromZIO(event("Acquire1")).ensuring(event("Release11")).ensuring(event("Release12")) *>
                ZChannel.fromZIO(event("Acquire2")).ensuring(event("Release2"))).ensuring(event("ReleaseOuter"))

            channel.toPull.use { pull =>
              pull.exit *> events.get
            }.flatMap { eventsInZManaged =>
              events.get.map { eventsAfterZManaged =>
                assert(eventsInZManaged)(equalTo(Chunk("Acquire1", "Release11", "Release12", "Acquire2"))) &&
                assert(eventsAfterZManaged)(
                  equalTo(Chunk("Acquire1", "Release11", "Release12", "Acquire2", "Release2", "ReleaseOuter"))
                )
              }
            }
          }
        },
        test("mixture of concatMap and ensuring") {
          Ref.make(Chunk[String]()).flatMap { events =>
            case class First(i: Int)
            case class Second(i: First)

            val conduit = ZChannel
              .writeAll(1, 2, 3)
              .ensuring(events.update(_ :+ "Inner"))
              .concatMap(i => ZChannel.write(First(i)).ensuring(events.update(_ :+ "First write")))
              .ensuring(events.update(_ :+ "First concatMap"))
              .concatMap(j => ZChannel.write(Second(j)).ensuring(events.update(_ :+ "Second write")))
              .ensuring(events.update(_ :+ "Second concatMap"))

            conduit.runCollect.zip(events.get).map { case (elements, _, events) =>
              assert(events)(
                equalTo(
                  Chunk(
                    "Second write",
                    "First write",
                    "Second write",
                    "First write",
                    "Second write",
                    "First write",
                    "Inner",
                    "First concatMap",
                    "Second concatMap"
                  )
                )
              ) &&
                assert(elements)(
                  equalTo(
                    Chunk(
                      Second(First(1)),
                      Second(First(2)),
                      Second(First(3))
                    )
                  )
                )
            }

          }
        }
      ),
      suite("ZChannel#mapOut")(
        test("simple") {
          for {
            tuple     <- ZChannel.writeAll(1, 2, 3).mapOut(_ + 1).runCollect
            (chunk, z) = tuple
          } yield assert(chunk)(equalTo(Chunk(2, 3, 4))) && assert(z)(isUnit)
        },
        test("mixed with flatMap") {
          ZChannel
            .write(1)
            .mapOut(_.toString)
            .flatMap(_ => ZChannel.write("x"))
            .runCollect
            .map(_._1)
            .map { result =>
              assert(result)(equalTo(Chunk("1", "x")))
            }
        }
      ),
      suite("ZChannel.concatMap")(
        test("plain") {
          ZChannel.writeAll(1, 2, 3).concatMap(i => ZChannel.writeAll(i, i)).runCollect.map { case (chunk, _) =>
            assert(chunk)(equalTo(Chunk(1, 1, 2, 2, 3, 3)))
          }
        },
        test("complex") {
          case class First[A](a: A)
          case class Second[A](a: A)
          val conduit = ZChannel
            .writeAll(1, 2)
            .concatMap(i => ZChannel.writeAll(i, i))
            .mapOut(First(_))
            .concatMap(i => ZChannel.writeAll(i, i))
            .mapOut(Second(_))

          val expected =
            Chunk(
              Second(First(1)),
              Second(First(1)),
              Second(First(1)),
              Second(First(1)),
              Second(First(2)),
              Second(First(2)),
              Second(First(2)),
              Second(First(2))
            )

          conduit.runCollect.map { case (chunk, _) =>
            assert(chunk)(equalTo(expected))
          }
        },
        test("read from inner conduit") {
          val source = ZChannel.writeAll(1, 2, 3, 4)
          val reader = ZChannel.read[Int].flatMap(ZChannel.write(_))
          val readers =
            ZChannel.writeAll((), ()).concatMap(_ => reader *> reader)

          (source >>> readers).runCollect.map { case (chunk, _) =>
            assert(chunk)(equalTo(Chunk(1, 2, 3, 4)))
          }
        },
        test("downstream failure") {
          for {
            exit <- ZChannel
                      .write(0)
                      .concatMap(_ => ZChannel.fail("error"))
                      .runCollect
                      .exit
          } yield assert(exit)(fails(equalTo("error")))
        },
        test("upstream acquireReleaseOut + downstream failure") {
          assertM(Ref.make(Chunk[String]()).flatMap { events =>
            ZChannel
              .acquireReleaseOutWith(events.update(_ :+ "Acquired"))(_ => events.update(_ :+ "Released"))
              .concatMap(_ => ZChannel.fail("error"))
              .runDrain
              .exit <*> events.get
          })(equalTo((Exit.fail("error"), Chunk("Acquired", "Released"))))
        },
        test("multiple concatMaps with failure in first") {
          for {
            exit <- ZChannel
                      .write(())
                      .concatMap(_ => ZChannel.write(ZChannel.fail("error")))
                      .concatMap(e => e)
                      .runCollect
                      .exit
          } yield assert(exit)(fails(equalTo("error")))
        },
        test("concatMap with failure then flatMap") {
          for {
            exit <- ZChannel
                      .write(())
                      .concatMap(_ => ZChannel.fail("error"))
                      .flatMap(_ => ZChannel.write(()))
                      .runCollect
                      .exit
          } yield assert(exit)(fails(equalTo("error")))
        },
        test("multiple concatMaps with failure in first and catchAll in second") {
          for {
            exit <- ZChannel
                      .write(())
                      .concatMap(_ => ZChannel.write(ZChannel.fail("error")))
                      .concatMap(e => e.catchAllCause(_ => ZChannel.fail("error2")))
                      .runCollect
                      .exit
          } yield assert(exit)(fails(equalTo("error2")))
        },
        test("done value combination") {
          assertM(
            ZChannel
              .writeAll(1, 2, 3)
              .as(List("Outer-0"))
              .concatMapWith(i => ZChannel.write(i).as(List(s"Inner-$i")))(_ ++ _, (_, _))
              .runCollect
          )(equalTo((Chunk(1, 2, 3), (List("Inner-1", "Inner-2", "Inner-3"), List("Outer-0")))))
        }
      ),
      suite("ZChannel#managedOut")(
        test("failure") {
          for {
            exit <- ZChannel.managedOut(ZManaged.fail("error")).runCollect.exit
          } yield assert(exit)(fails(equalTo("error")))
        }
      ),
      suite("ZChannel#mergeWith")(
        test("simple merge") {
          val conduit = ZChannel
            .writeAll(1, 2, 3)
            .mergeWith(ZChannel.writeAll(4, 5, 6))(
              ex => ZChannel.MergeDecision.awaitConst(ZIO.done(ex)),
              ex => ZChannel.MergeDecision.awaitConst(ZIO.done(ex))
            )

          conduit.runCollect.map { case (chunk, _) =>
            assert(chunk.toSet)(equalTo(Set(1, 2, 3, 4, 5, 6)))
          }
        },
        test("merge with different types") {
          val left  = ZChannel.write(1) *> ZChannel.fromZIO(Task("Whatever").refineToOrDie[RuntimeException])
          val right = ZChannel.write(2) *> ZChannel.fromZIO(Task(true).refineToOrDie[IllegalStateException])

          val merged = left.mergeWith(right)(
            ex => ZChannel.MergeDecision.await(ex2 => ZIO.done(ex <*> ex2)),
            ex2 => ZChannel.MergeDecision.await(ex => ZIO.done(ex <*> ex2))
          )

          merged.runCollect.map { case (chunk, result) =>
            assert(chunk.toSet)(equalTo(Set(1, 2))) &&
              assert(result)(equalTo(("Whatever", true)))
          }
        },
        test("handles polymorphic failures") {
          val left  = ZChannel.write(1) *> ZChannel.fail("Boom").as(true)
          val right = ZChannel.write(2) *> ZChannel.fail(true).as(true)

          val merged = left.mergeWith(right)(
            ex => ZChannel.MergeDecision.await(ex2 => ZIO.done(ex).flip.zip(ZIO.done(ex2).flip).flip),
            ex2 => ZChannel.MergeDecision.await(ex => ZIO.done(ex).flip.zip(ZIO.done(ex2).flip).flip)
          )

          merged.runDrain.exit.map(ex => assert(ex)(fails(equalTo(("Boom", true)))))
        },
        test("interrupts losing side") {
          Promise.make[Nothing, Unit].flatMap { latch =>
            Ref.make(false).flatMap { interrupted =>
              val left = ZChannel.write(1) *>
                ZChannel.fromZIO((latch.succeed(()) *> ZIO.never).onInterrupt(interrupted.set(true)))
              val right = ZChannel.write(2) *> ZChannel.fromZIO(latch.await)

              val merged = left.mergeWith(right)(
                ex => ZChannel.MergeDecision.done(ZIO.done(ex)),
                _ => ZChannel.MergeDecision.done(interrupted.get.map(assert(_)(isTrue)))
              )

              merged.runDrain
            }
          }
        }
      ),
      suite("ZChannel#interruptWhen")(
        suite("interruptWhen(Promise)")(
          test("interrupts the current element") {
            for {
              interrupted <- Ref.make(false)
              latch       <- Promise.make[Nothing, Unit]
              halt        <- Promise.make[Nothing, Unit]
              started     <- Promise.make[Nothing, Unit]
              fiber <- ZChannel
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
              result <- (ZChannel.write(1) *> ZChannel.fromZIO(ZIO.never))
                          .interruptWhen(halt.await)
                          .runDrain
                          .either
            } yield assert(result)(isLeft(equalTo("Fail")))
          } @@ zioTag(errors)
        ) @@ zioTag(interruption),
        suite("interruptWhen(IO)")(
          test("interrupts the current element") {
            for {
              interrupted <- Ref.make(false)
              latch       <- Promise.make[Nothing, Unit]
              halt        <- Promise.make[Nothing, Unit]
              started     <- Promise.make[Nothing, Unit]
              fiber <- ZChannel
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
              result <- ZChannel
                          .fromZIO(ZIO.never)
                          .interruptWhen(halt.await)
                          .runDrain
                          .either
            } yield assert(result)(isLeft(equalTo("Fail")))
          } @@ zioTag(errors)
        ) @@ zioTag(interruption)
      ),
      suite("reads")(
        test("simple reads") {
          case class Whatever(i: Int)

          val left = ZChannel.writeAll(1, 2, 3)
          val right = ZChannel
            .read[Int]
            .catchAll(_ => ZChannel.end(4))
            .flatMap(i => ZChannel.write(Whatever(i)))

          val conduit = left >>> (right *> right *> right *> right)

          conduit.runCollect.map { case (outputs, _) =>
            assert(outputs)(equalTo(Chunk(1, 2, 3, 4).map(Whatever(_))))
          }
        },
        test("pipeline") {
          lazy val identity: ZChannel[Any, Any, Int, Any, Nothing, Int, Unit] =
            ZChannel.readWith(
              (i: Int) => ZChannel.write(i) *> identity,
              (_: Any) => ZChannel.end(()),
              (_: Any) => ZChannel.end(())
            )

          lazy val doubler: ZChannel[Any, Any, Int, Any, Nothing, Int, Unit] =
            ZChannel.readWith(
              (i: Int) => ZChannel.writeAll(i, i) *> doubler,
              (_: Any) => ZChannel.end(()),
              (_: Any) => ZChannel.end(())
            )

          val effect = ZChannel.fromZIO(Ref.make[List[Int]](Nil)).flatMap { ref =>
            lazy val inner: ZChannel[Any, Any, Int, Any, Nothing, Int, Unit] =
              ZChannel.readWith(
                (i: Int) => ZChannel.fromZIO(ref.update(i :: _)) *> ZChannel.write(i) *> inner,
                (_: Any) => ZChannel.end(()),
                (_: Any) => ZChannel.end(())
              )

            inner *> ZChannel.fromZIO(ref.get)
          }

          val conduit = ZChannel.writeAll(1, 2) >>>
            mapper(i => i) >>>
            mapper((i: Int) => List(i, i)).concatMap(is => ZChannel.writeAll(is: _*)).as(()) >>>
            effect

          conduit.runCollect.map { case (outputs, result) =>
            assert(outputs)(equalTo(Chunk(1, 1, 2, 2))) &&
              assert(result)(equalTo(List(2, 2, 1, 1)))
          }
        },
        test("another pipeline") {
          Ref.make(Chunk[Int]()).flatMap { sums =>
            val intProducer: ZChannel[Any, Any, Any, Any, Nothing, Int, Unit] = ZChannel.writeAll(1, 2, 3, 4, 5)

            def readNInts(n: Int): ZChannel[Any, Any, Int, Any, Nothing, Int, String] =
              if (n > 0)
                ZChannel.readWith(
                  (i: Int) => ZChannel.write(i) *> readNInts(n - 1),
                  (_: Any) => ZChannel.end("EOF"),
                  (_: Any) => ZChannel.end("EOF")
                )
              else ZChannel.end("end")

            def sum(label: String, acc: Int): ZChannel[Any, Any, Int, Any, Any, Nothing, Unit] =
              ZChannel.readWith(
                (i: Int) => sum(label, acc + i),
                (_: Any) => ZChannel.fromZIO(sums.update(_ :+ acc)),
                (_: Any) => ZChannel.fromZIO(sums.update(_ :+ acc))
              )

            val channel =
              intProducer >>> ((readNInts(2) >>> sum("left", 0)) *> (readNInts(2) >>> sum("right", 0)))

            channel.run *>
              assertM(sums.get)(equalTo(Chunk(3, 7)))
          }
        },
        test("resources") {
          Ref.make(Chunk[String]()).flatMap { events =>
            val left = ZChannel
              .acquireReleaseOutWith(events.update(_ :+ "Acquire outer"))(_ => events.update(_ :+ "Release outer"))
              .concatMap { _ =>
                ZChannel
                  .writeAll(1, 2, 3)
                  .concatMap { i =>
                    ZChannel.acquireReleaseOutWith(events.update(_ :+ s"Acquire $i").as(i))(_ =>
                      events.update(_ :+ s"Release $i")
                    )
                  }
              }

            val read =
              ZChannel.read[Int].mapZIO { i =>
                events.update(_ :+ s"Read $i").unit
              }

            val right = (read *> read).catchAll(_ => ZChannel.end(()))

            (left >>> right).runDrain *> events.get.map { events =>
              assert(events)(
                hasSameElements(
                  Chunk(
                    "Acquire outer",
                    "Acquire 1",
                    "Read 1",
                    "Release 1",
                    "Acquire 2",
                    "Read 2",
                    "Release 2",
                    "Release outer"
                  )
                )
              )
            }
          }
        },
        suite("concurrent reads")(
          test("simple concurrent reads") {
            val capacity = 128

            ZIO.collectAll(List.fill(capacity)(Random.nextInt)).flatMap { data =>
              Ref.make(data).zip(Ref.make(List[Int]())).flatMap { case (source, dest) =>
                val twoWriters = refWriter(dest).mergeWith(refWriter(dest))(
                  _ => ZChannel.MergeDecision.awaitConst(ZIO.unit),
                  _ => ZChannel.MergeDecision.awaitConst(ZIO.unit)
                )

                (refReader(source) >>> twoWriters).mapZIO(_ => dest.get).run.map { result =>
                  val missing = data.toSet -- result.toSet
                  val surplus = result.toSet -- data.toSet

                  assert(missing)(isEmpty ?? "No missing elements") &&
                  assert(surplus)(isEmpty ?? "No surplus elements")
                }

              }
            }
          } @@ TestAspect.nonFlaky(50),
          test("nested concurrent reads") {
            val capacity      = 128
            val f: Int => Int = _ + 1

            ZIO.collectAll(List.fill(capacity)(Random.nextInt)).flatMap { data =>
              Ref.make(data).zip(Ref.make(List[Int]())).flatMap { case (source, dest) =>
                val twoWriters = (mapper(f) >>> refWriter(dest)).mergeWith((mapper(f) >>> refWriter(dest)))(
                  _ => ZChannel.MergeDecision.awaitConst(ZIO.unit),
                  _ => ZChannel.MergeDecision.awaitConst(ZIO.unit)
                )

                (refReader(source) >>> twoWriters).mapZIO(_ => dest.get.map(_.toSet)).run.map { result =>
                  val expected = data.map(f).toSet
                  val missing  = expected -- result
                  val surplus  = result -- expected

                  assert(missing)(isEmpty ?? "No missing elements") &&
                  assert(surplus)(isEmpty ?? "No surplus elements")
                }
              }
            }
          } @@ TestAspect.nonFlaky(50)
        ),
        suite("ZChannel#mapError") {
          test("mapError structure confusion") {
            assertM(
              ZChannel
                .fail("err")
                .mapError(_ => 1)
                .runCollect
                .exit
            )(fails(equalTo(1)))
          }
        }
      ),
      suite("provide")(
        test("simple provide") {
          assertM(
            ZChannel
              .fromZIO(ZIO.environment[Int])
              .provide(100)
              .run
          )(equalTo(100))
        },
        test("provide <*> provide") {
          assertM(
            (ZChannel.fromZIO(ZIO.environment[Int]).provide(100) <*>
              ZChannel.fromZIO(ZIO.environment[Int]).provide(200)).run
          )(equalTo((100, 200)))
        },
        test("concatMap(provide).provide") {
          assertM(
            (ZChannel
              .fromZIO(ZIO.environment[Int])
              .emitCollect
              .mapOut(_._2)
              .concatMap(n =>
                ZChannel
                  .fromZIO(ZIO.environment[Int].map(m => (n, m)))
                  .provide(200)
                  .flatMap(ZChannel.write)
              )
              .provide(100))
              .runCollect
          )(equalTo((Chunk((100, 200)), ())))
        },
        test("provide is modular") {
          assertM(
            (for {
              v1 <- ZChannel.fromZIO(ZIO.environment[Int])
              v2 <- ZChannel.fromZIO(ZIO.environment[Int]).provide(2)
              v3 <- ZChannel.fromZIO(ZIO.environment[Int])
            } yield (v1, v2, v3)).runDrain.provide(4)
          )(equalTo((4, 2, 4)))
        }
      )
    )
  )

  def refReader[T](ref: Ref[List[T]]): ZChannel[Any, Any, Any, Any, Nothing, T, Unit] =
    ZChannel
      .fromZIO(ref.modify {
        case head :: tail => (Some(head), tail)
        case Nil          => (None, Nil)
      })
      .flatMap {
        case Some(i) => ZChannel.write(i) *> refReader(ref)
        case None    => ZChannel.end(())
      }

  def refWriter[T](ref: Ref[List[T]]): ZChannel[Any, Any, T, Any, Nothing, Nothing, Unit] =
    ZChannel.readWith(
      (in: T) => ZChannel.fromZIO(ref.update(in :: _).unit) *> refWriter(ref),
      (_: Any) => ZChannel.end(()),
      (_: Any) => ZChannel.end(())
    )

  def mapper[T, U](f: T => U): ZChannel[Any, Any, T, Any, Nothing, U, Unit] =
    ZChannel.readWith(
      (in: T) => ZChannel.write(f(in)) *> mapper(f),
      (_: Any) => ZChannel.end(()),
      (_: Any) => ZChannel.end(())
    )
}
