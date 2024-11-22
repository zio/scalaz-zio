package zio.test

import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.TestUtils._

object SpecSpec extends ZIOBaseSpec {

  val specLayer: ZLayer[Any, Nothing, Unit] =
    ZLayer.succeed(())

  val neverFinalizerLayer =
    ZLayer.scoped(ZIO.acquireRelease(ZIO.unit)(_ => ZIO.never))

  def spec: Spec[TestEnvironment, TestFailure[Nothing]] = suite("SpecSpec")(
    suite("provideLayer")(
      test("does not have early initialization issues") {
        for {
          _ <- ZIO.service[Unit]
        } yield assertCompletes
      }.provideLayer(specLayer)
    ),
    suite("provideLayerShared")(
      test("gracefully handles fiber death") {
        val spec = suite("suite")(
          test("test") {
            assert(true)(isTrue)
          }
        ).provideLayerShared(ZLayer.fromZIOEnvironment(ZIO.dieMessage("everybody dies")))
        for {
          _ <- execute(spec)
        } yield assertCompletes
      },
      test("does not acquire the environment if the suite is ignored") {
        val spec = suite("suite")(
          test("test1") {
            assertZIO(ZIO.service[Ref[Boolean]].flatMap(_.get))(isTrue)
          },
          test("test2") {
            assertZIO(ZIO.service[Ref[Boolean]].flatMap(_.get))(isTrue)
          }
        )
        for {
          ref      <- Ref.make(true)
          specLayer = ZLayer.fromZIO(ref.set(false).as(ref))
          _        <- execute(spec.provideLayerShared(specLayer) @@ ifEnvSet("foo"))
          result   <- ref.get
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
            assertZIO(ZIO.service[Int])(Assertion.equalTo(42))
          }
        ).provideLayerShared(ZLayer.succeed(43))
        for {
          summary <- execute(spec)
        } yield assertTrue(summary.success == 1) && assertTrue(summary.fail == 2)
      },
      test("inner fiberRef changes override outer ones") {
        val fiberRef = FiberRef.unsafe.make(0)(Unsafe.unsafe)
        val inner    = ZLayer.scoped(ZIO.acquireRelease(fiberRef.set(1))(_ => fiberRef.set(-1)))
        val outer    = ZLayer.scoped(ZIO.acquireRelease(fiberRef.set(2))(_ => fiberRef.set(-2)))
        val spec = suite("suite")(
          test("test") {
            for {
              value <- fiberRef.get
            } yield assertTrue(value == 1)
          }.provideLayerShared(inner).provideLayer(outer)
        )
        for {
          summary <- execute(spec)
        } yield assertTrue(summary.success == 1)
      },
      test("dependencies of shared service are scoped to lifetime of suite") {
        trait Service {
          def open: UIO[Boolean]
        }
        object Service {
          val open: ZIO[Service, Nothing, Boolean] =
            ZIO.serviceWithZIO(_.open)
        }
        val layer =
          ZLayer {
            for {
              ref <- Ref.make(true)
              _   <- ZIO.addFinalizer(ref.set(false))
            } yield new Service {
              def open: UIO[Boolean] =
                ref.get
            }
          }
        val spec = suite("suite")(
          test("test1") {
            assertZIO(Service.open)(isTrue)
          },
          test("test2") {
            assertZIO(Service.open)(isTrue)
          }
        ).provideLayerShared(layer).provideLayer(Scope.default) @@ sequential
        assertZIO(succeeded(spec))(isTrue)
      },
      test("propagates the scope to multiple tests") {
        def assertion = ZIO.scoped {
          for {
            rnd <- ZIO.random
          } yield assertTrue(rnd == Random.RandomLive)
        }
        val spec = suite("suite")(
          test("test1") {
            assertion
          },
          test("test2") {
            assertion
          }
        ).provideLayerShared(ZLayer.scoped(ZIO.withRandomScoped(Random.RandomLive)))
        for {
          summary <- execute(spec)
        } yield assertTrue(summary.success == 2)
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
                 ).provideSomeLayerShared[TestEnvironment](specLayer) @@ nondeterministic
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
        ).provideSomeLayerShared[TestEnvironment](specLayer) @@ silent
        assertZIO(succeeded(spec))(isTrue)
      },
      test("releases resources as soon as possible") {
        for {
          ref      <- Ref.make[List[String]](List.empty)
          acquire   = ref.update("Acquiring" :: _)
          release   = ref.update("Releasing" :: _)
          update    = ZIO.service[Ref[Int]].flatMap(_.updateAndGet(_ + 1))
          specLayer = ZLayer.scoped(ZIO.acquireRelease(acquire *> Ref.make(0))(_ => release))
          spec = suite("spec")(
                   suite("suite1")(
                     test("test1") {
                       assertZIO(update)(equalTo(1))
                     },
                     test("test2") {
                       assertZIO(update)(equalTo(2))
                     }
                   ).provideLayerShared(specLayer),
                   suite("suite2")(
                     test("test1") {
                       assertZIO(update)(equalTo(1))
                     },
                     test("test2") {
                       assertZIO(update)(equalTo(2))
                     }
                   ).provideLayerShared(specLayer)
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
          ).provideLayerShared(ZLayer.scoped[Any](ZIO.acquireRelease(Ref.make(0))(_.set(-1))))
        assertZIO(succeeded(spec))(isTrue)
      },
      test("dependencies of shared service are scoped to lifetime of suite") {
        trait Service {
          def open: UIO[Boolean]
        }
        object Service {
          val open: ZIO[Service, Nothing, Boolean] =
            ZIO.serviceWithZIO(_.open)
        }
        val layer =
          ZLayer {
            for {
              ref <- Ref.make(true)
              _   <- ZIO.addFinalizer(ref.set(false))
            } yield new Service {
              def open: UIO[Boolean] =
                ref.get
            }
          }
        val spec = suite("suite")(
          test("test1") {
            assertZIO(Service.open)(isTrue)
          },
          test("test2") {
            assertZIO(Service.open)(isTrue)
          }
        ).provideSomeLayerShared[Scope](layer).provideLayer(Scope.default) @@ sequential
        assertZIO(succeeded(spec))(isTrue)
      },
      test("propagates the scope to multiple tests") {
        def assertion = ZIO.scoped {
          for {
            rnd <- ZIO.random
          } yield assertTrue(rnd == Random.RandomLive)
        }
        val spec = suite("suite")(
          test("test1") {
            assertion
          },
          test("test2") {
            assertion
          }
        ).provideSomeLayerShared(ZLayer.scoped(ZIO.withRandomScoped(Random.RandomLive)))
        for {
          summary <- execute(spec)
        } yield assertTrue(summary.success == 2)
      }
    ),
    suite("iterable constructor") {
      Chunk(
        test("some test") {
          assert(true)(isTrue)
        },
        test("some other test") {
          assert(false)(isFalse)
        }
      )
    },
    suite("suite does not wait for finalizer to complete")(
      test("some test") {
        assertCompletes
      },
      test("some other test") {
        assertCompletes
      }
    ).provideLayerShared(neverFinalizerLayer) @@ shutdownTimeout(2.seconds)
  )

  private def shutdownTimeout(d: Duration): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        TestExecutor.overrideShutdownTimeout.set(Some(d)) *> test
    }
}
