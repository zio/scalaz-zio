package zio.test

import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.TestUtils._
import zio.test.environment._

object SpecSpec extends ZIOBaseSpec {

  val layer: ZLayer[Any, Nothing, Has[Unit]] =
    ZLayer.succeed(())

  def spec: Spec[TestEnvironment, TestFailure[Nothing], TestSuccess] = suite("SpecSpec")(
    suite("provideCustomLayer")(
      test("provides the part of the environment that is not part of the `TestEnvironment`") {
        for {
          _ <- ZIO.environment[TestEnvironment]
          _ <- ZIO.environment[Has[Unit]]
        } yield assertCompletes
      }.provideCustomLayer(layer)
    ),
    suite("provideLayer")(
      test("does not have early initialization issues") {
        for {
          _ <- ZIO.environment[Has[Unit]]
        } yield assertCompletes
      }.provideLayer(layer)
    ),
    suite("provideLayerShared")(
      test("gracefully handles fiber death") {
        val spec = suite("suite")(
          test("test") {
            assert(true)(isTrue)
          }
        ).provideLayerShared(ZLayer.fromZIOMany(ZIO.dieMessage("everybody dies")))
        for {
          _ <- execute(spec)
        } yield assertCompletes
      },
      test("does not acquire the environment if the suite is ignored") {
        val spec = suite("suite")(
          test("test1") {
            assertM(ZIO.service[Ref[Boolean]].flatMap(_.get))(isTrue)
          },
          test("test2") {
            assertM(ZIO.service[Ref[Boolean]].flatMap(_.get))(isTrue)
          }
        )
        for {
          ref    <- Ref.make(true)
          layer   = ZLayer.fromZIO(ref.set(false).as(ref))
          _      <- execute(spec.provideCustomLayerShared(layer) @@ ifEnvSet("foo"))
          result <- ref.get
        } yield assert(result)(isTrue)
      },
      test("is not interfered with by test level failures") {
        val spec = suite("suite")(
          test("test1") {
            assert(1)(Assertion.equalTo(2))
          },
          test("test2") {
            assert(1)(Assertion.equalTo(1))
          },
          test("test3") {
            assertM(ZIO.service[Int])(Assertion.equalTo(42))
          }
        ).provideLayerShared(ZLayer.succeed(43))
        for {
          executedSpec <- execute(spec)
          successes = executedSpec.fold[Int] { c =>
                        (c: @unchecked) match {
                          case ExecutedSpec.SuiteCase(_, counts) => counts.sum
                          case ExecutedSpec.TestCase(_, test, _) => if (test.isRight) 1 else 0
                        }
                      }
          failures = executedSpec.fold[Int] { c =>
                       (c: @unchecked) match {
                         case ExecutedSpec.SuiteCase(_, counts) => counts.sum
                         case ExecutedSpec.TestCase(_, test, _) => if (test.isLeft) 1 else 0
                       }
                     }
        } yield assert(successes)(equalTo(1)) && assert(failures)(equalTo(2))
      }
    ),
    suite("provideSomeLayerShared")(
      test("leaves the remainder of the environment") {
        for {
          ref <- Ref.make[Set[Int]](Set.empty)
          spec = suite("suite")(
                   test("test1") {
                     for {
                       n <- Random.nextInt
                       _ <- ref.update(_ + n)
                     } yield assertCompletes
                   },
                   test("test2") {
                     for {
                       n <- Random.nextInt
                       _ <- ref.update(_ + n)
                     } yield assertCompletes
                   },
                   test("test3") {
                     for {
                       n <- Random.nextInt
                       _ <- ref.update(_ + n)
                     } yield assertCompletes
                   }
                 ).provideSomeLayerShared[TestEnvironment](layer) @@ nondeterministic
          _      <- execute(spec)
          result <- ref.get
        } yield assert(result)(hasSize(isGreaterThan(1)))
      },
      test("does not cause the remainder to be shared") {
        val spec = suite("suite")(
          test("test1") {
            for {
              _      <- Console.printLine("Hello, World!")
              output <- TestConsole.output
            } yield assert(output)(equalTo(Vector("Hello, World!\n")))
          },
          test("test2") {
            for {
              _      <- Console.printLine("Hello, World!")
              output <- TestConsole.output
            } yield assert(output)(equalTo(Vector("Hello, World!\n")))
          }
        ).provideSomeLayerShared[TestEnvironment](layer) @@ silent
        assertM(succeeded(spec))(isTrue)
      },
      test("releases resources as soon as possible") {
        for {
          ref    <- Ref.make[List[String]](List.empty)
          acquire = ref.update("Acquiring" :: _)
          release = ref.update("Releasing" :: _)
          update  = ZIO.service[Ref[Int]].flatMap(_.updateAndGet(_ + 1))
          layer   = ZManaged.acquireReleaseWith(acquire *> Ref.make(0))(_ => release).toLayer
          spec = suite("spec")(
                   suite("suite1")(
                     test("test1") {
                       assertM(update)(equalTo(1))
                     },
                     test("test2") {
                       assertM(update)(equalTo(2))
                     }
                   ).provideCustomLayerShared(layer),
                   suite("suite2")(
                     test("test1") {
                       assertM(update)(equalTo(1))
                     },
                     test("test2") {
                       assertM(update)(equalTo(2))
                     }
                   ).provideCustomLayerShared(layer)
                 ) @@ sequential
          succeeded <- succeeded(spec)
          log       <- ref.get.map(_.reverse)
        } yield assert(succeeded)(isTrue) &&
          assert(log)(equalTo(List("Acquiring", "Releasing", "Acquiring", "Releasing")))
      },
      test("correctly handles nested suites") {
        val spec =
          suite("a")(
            suite("b")(
              suite("c")(
                suite("d") {
                  test("test") {
                    for {
                      n <- ZIO.service[Ref[Int]].flatMap(_.updateAndGet(_ + 1))
                    } yield assert(n)(equalTo(1))
                  }
                }
              )
            )
          ).provideCustomLayerShared(ZManaged.acquireReleaseWith(Ref.make(0))(_.set(-1)).toLayer)
        assertM(succeeded(spec))(isTrue)
      }
    )
  )
}
