package zio

import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky, withLiveClock}
import zio.test._

object ZLayerSpec extends ZIOBaseSpec {

  import ZIOTag._

  def testSize[R](layer: Layer[Nothing, R], n: Int, label: String = ""): UIO[TestResult] =
    ZIO.scoped {
      layer.build.flatMap { env =>
        ZIO.succeed(assert(env.size)(if (label == "") equalTo(n) else equalTo(n) ?? label))
      }
    }

  val acquire1 = "Acquiring Module 1"
  val acquire2 = "Acquiring Module 2"
  val acquire3 = "Acquiring Module 3"
  val release1 = "Releasing Module 1"
  val release2 = "Releasing Module 2"
  val release3 = "Releasing Module 3"

  trait Service1

  def makeLayer1(ref: Ref[Vector[String]]): ZLayer[Any, Nothing, Service1] =
    ZLayer.scoped {
      ZIO.acquireRelease(ref.update(_ :+ acquire1).as(new Service1 {}))(_ => ref.update(_ :+ release1))
    }

  trait Service2

  def makeLayer2(ref: Ref[Vector[String]]): ZLayer[Any, Nothing, Service2] =
    ZLayer.scoped {
      ZIO.acquireRelease(ref.update(_ :+ acquire2).as(new Service2 {}))(_ => ref.update(_ :+ release2))
    }

  trait Service3

  def makeLayer3(ref: Ref[Vector[String]]): ZLayer[Any, Nothing, Service3] =
    ZLayer.scoped {
      ZIO.acquireRelease(ref.update(_ :+ acquire3).as(new Service3 {}))(_ => ref.update(_ :+ release3))
    }

  def makeRef: UIO[Ref[Vector[String]]] =
    Ref.make(Vector.empty)

  def spec =
    suite("ZLayerSpec")(
      test("Size of >>> (1)") {
        val layer = ZLayer.succeed(1) >>> ZLayer.fromFunction((n: Int) => n.toString)

        testSize(layer, 1)
      },
      test("Size of >>> (2)") {
        val layer = ZLayer.succeed(1) >>>
          (ZLayer.fromFunction((n: Int) => n.toString) ++
            ZLayer.fromFunction((n: Int) => n % 2 == 0))

        testSize(layer, 2)
      },
      test("Size of Test layers") {
        for {
          r1 <- testSize(Annotations.live, 1, "Annotations.live")
          r2 <- testSize(
                  liveEnvironment >>> (Live.default ++ Annotations.live) >>> TestConsole.debug,
                  1,
                  "TestConsole.default"
                )
          r3 <- testSize(liveEnvironment >>> Live.default, 1, "Live.default")
          r4 <- testSize(liveEnvironment >>> TestRandom.deterministic, 1, "TestRandom.live")
          r5 <- testSize(Sized.live(100), 1, "Sized.live(100)")
          r6 <- testSize(TestSystem.default, 1, "TestSystem.default")
        } yield r1 && r2 && r3 && r4 && r5 && r6
      },
      test("Size of >>> (9)") {
        val layer = liveEnvironment >>>
          (Annotations.live ++ ((Live.default ++ Annotations.live) >>> TestConsole.debug) ++
            Live.default ++ TestRandom.deterministic ++ Sized.live(100)
            ++ TestSystem.default)

        testSize(layer, 6)
      },
      test("sharing with ++") {
        val expected = Vector(acquire1, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          env     = (layer1 ++ layer1).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("sharing itself with ++") {
        val m1     = new Service1 {}
        val layer1 = ZLayer.succeed(m1)
        val env    = layer1 ++ (layer1 ++ layer1)
        env.build.flatMap(m => ZIO.attempt(assert(m.get)(equalTo(m1))))
      } @@ exceptJS(nonFlaky),
      test("sharing with >>>") {
        val expected = Vector(acquire1, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          env     = (layer1 >>> layer1).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("sharing with multiple layers") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          layer3  = makeLayer3(ref)
          env     = ((layer1 >>> layer2) ++ (layer1 >>> layer3)).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual(0))(equalTo(acquire1)) &&
          assert(actual.slice(1, 3))(hasSameElements(Vector(acquire2, acquire3))) &&
          assert(actual.slice(3, 5))(hasSameElements(Vector(release2, release3))) &&
          assert(actual(5))(equalTo(release1))
      } @@ exceptJS(nonFlaky),
      test("finalizers with ++") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          env     = (layer1 ++ layer2).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual.slice(0, 2))(hasSameElements(Vector(acquire1, acquire2))) &&
          assert(actual.slice(2, 4))(hasSameElements(Vector(release1, release2)))
      } @@ exceptJS(nonFlaky),
      test("finalizers with >>>") {
        val expected = Vector(acquire1, acquire2, release2, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          env     = (layer1 >>> layer2).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("finalizers with multiple layers") {
        val expected =
          Vector(acquire1, acquire2, acquire3, release3, release2, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          layer3  = makeLayer3(ref)
          env     = (layer1 >>> layer2 >>> layer3).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("map does not interfere with sharing") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          layer3  = makeLayer3(ref)
          env     = ((layer1.map(identity) >>> layer2) ++ (layer1 >>> layer3)).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual(0))(equalTo(acquire1)) &&
          assert(actual.slice(1, 3))(hasSameElements(Vector(acquire2, acquire3))) &&
          assert(actual.slice(3, 5))(hasSameElements(Vector(release2, release3))) &&
          assert(actual(5))(equalTo(release1))
      } @@ exceptJS(nonFlaky),
      test("mapError does not interfere with sharing") {
        implicit val canFail = CanFail
        for {
          ref   <- makeRef
          layer1 = makeLayer1(ref)
          layer2 = makeLayer2(ref)
          layer3 = makeLayer3(ref)
          env =
            ((layer1.mapError(identity) >>> layer2) ++ (layer1 >>> layer3)).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual(0))(equalTo(acquire1)) &&
          assert(actual.slice(1, 3))(hasSameElements(Vector(acquire2, acquire3))) &&
          assert(actual.slice(3, 5))(hasSameElements(Vector(release2, release3))) &&
          assert(actual(5))(equalTo(release1))
      } @@ exceptJS(nonFlaky),
      test("orDie does not interfere with sharing") {
        implicit val canFail = CanFail
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          layer3  = makeLayer3(ref)
          env     = ((layer1.orDie >>> layer2) ++ (layer1 >>> layer3)).build
          _      <- ZIO.scoped(env)
          actual <- ref.get
        } yield assert(actual(0))(equalTo(acquire1)) &&
          assert(actual.slice(1, 3))(hasSameElements(Vector(acquire2, acquire3))) &&
          assert(actual.slice(3, 5))(hasSameElements(Vector(release2, release3))) &&
          assert(actual(5))(equalTo(release1))
      } @@ exceptJS(nonFlaky),
      test("interruption with ++") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          env     = (layer1 ++ layer2).build
          fiber  <- ZIO.scoped(env).fork
          _      <- fiber.interrupt
          actual <- ref.get
        } yield (assert(actual)(contains(acquire1)) ==> assert(actual)(contains(release1))) &&
          (assert(actual)(contains(acquire2)) ==> assert(actual)(contains(release2)))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      test("interruption with >>>") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          env     = (layer1 >>> layer2).build
          fiber  <- ZIO.scoped(env).fork
          _      <- fiber.interrupt
          actual <- ref.get
        } yield (assert(actual)(contains(acquire1)) ==> assert(actual)(contains(release1))) &&
          (assert(actual)(contains(acquire2)) ==> assert(actual)(contains(release2)))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      test("interruption with multiple layers") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          layer3  = makeLayer3(ref)
          env     = ((layer1 >>> layer2) ++ (layer1 >>> layer3)).build
          fiber  <- ZIO.scoped(env).fork
          _      <- fiber.interrupt
          actual <- ref.get
        } yield (assert(actual)(contains(acquire1)) ==> assert(actual)(contains(release1))) &&
          (assert(actual)(contains(acquire2)) ==> assert(actual)(contains(release2))) &&
          (assert(actual)(contains(acquire3)) ==> assert(actual)(contains(release3)))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      test("layers can be acquired in parallel") {
        for {
          promise <- Promise.make[Nothing, Unit]
          layer1   = ZLayer(ZIO.never)
          layer2 =
            ZLayer.scoped(ZIO.acquireRelease(promise.succeed(()).map(ZEnvironment(_)))(_ => ZIO.unit))
          env = (layer1 ++ layer2).build
          _  <- ZIO.scoped(env).forkDaemon
          _  <- promise.await
        } yield assertCompletes
      },
      test("map can map a layer to an unrelated type") {
        case class A(name: String, value: Int)
        case class B(name: String)
        val l1: Layer[Nothing, A]          = ZLayer.succeed(A("name", 1))
        val l2: ZLayer[String, Nothing, B] = ZLayer.fromFunction(string => B(string))
        val live: Layer[Nothing, B]        = l1.map(a => ZEnvironment(a.get[A].name)) >>> l2
        assertZIO(ZIO.service[B].provide(live))(equalTo(B("name")))
      },
      test("memoization") {
        val expected = Vector(acquire1, release1)
        for {
          ref     <- makeRef
          memoized = makeLayer1(ref).memoize
          _ <- ZIO.scoped {
                 memoized.flatMap { layer =>
                   for {
                     _ <- ZIO.environment[Service1].provideLayer(layer)
                     _ <- ZIO.environment[Service1].provideLayer(layer)
                   } yield ()
                 }
               }
          actual <- ref.get
        } yield assert(actual)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("orElse") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          env     = ((layer1 >>> ZLayer.fail("fail")) orElse layer2).build
          fiber  <- ZIO.scoped(env).fork
          _      <- fiber.interrupt
          actual <- ref.get
        } yield (assert(actual)(contains(acquire1)) ==> assert(actual)(contains(release1))) &&
          (assert(actual)(contains(acquire2)) ==> assert(actual)(contains(release2)))
      } @@ exceptJS(nonFlaky),
      test("passthrough") {
        val layer: ZLayer[Int, Nothing, String] =
          ZLayer.fromFunction((n: Int) => n.toString)
        val live: ZLayer[Any, Nothing, Int with String] =
          ZLayer.succeed(1) >>> layer.passthrough
        val zio = for {
          i <- ZIO.service[Int]
          s <- ZIO.service[String]
        } yield (i, s)
        assertZIO(zio.provide(live))(equalTo((1, "1")))
      },
      test("fresh with ++") {
        val expected = Vector(acquire1, acquire1, release1, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          env     = (layer1 ++ layer1.fresh).build
          _      <- ZIO.scoped(env)
          result <- ref.get
        } yield assert(result)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("fresh with >>>") {
        val expected = Vector(acquire1, acquire1, release1, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          env     = (layer1 >>> layer1.fresh).build
          _      <- ZIO.scoped(env)
          result <- ref.get
        } yield assert(result)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("fresh with multiple layers") {
        val expected = Vector(acquire1, acquire1, release1, release1)
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          env     = ((layer1 ++ layer1) ++ (layer1 ++ layer1).fresh).build
          _      <- ZIO.scoped(env)
          result <- ref.get
        } yield assert(result)(equalTo(expected))
      } @@ exceptJS(nonFlaky),
      test("fresh with identical fresh layers") {
        for {
          ref    <- makeRef
          layer1  = makeLayer1(ref)
          layer2  = makeLayer2(ref)
          layer3  = makeLayer3(ref)
          env     = ((layer1.fresh >>> layer2) ++ (layer1.fresh >>> layer3)).build
          _      <- ZIO.scoped(env)
          result <- ref.get
        } yield assert(result)(hasSize(equalTo(8)))
      } @@ exceptJS(nonFlaky),
      test("fresh with combination of other operators maintains freshness") {
        def makeLayer1(ref: Ref[Vector[String]]): ZLayer[Any, Nothing, Service1] = ZLayer {
          ref.update(_ :+ "1").as(new Service1 {})
        }
        def makeLayer2(ref: Ref[Vector[String]]): ZLayer[Service1, Nothing, Service2] = ZLayer {
          (ZIO.service[Service1] *> ref.update(_ :+ "2")).as(new Service2 {})
        }

        for {
          ref   <- makeRef
          layer1 = makeLayer1(ref)
          layer2 = makeLayer2(ref)
          _ <- (ZIO.service[Service1] *> ZIO.service[Service2])
                 .provide(layer1.fresh.map(identity).update(a => a), layer2)
          result <- ref.get
        } yield assert(result)(hasSameElements(Vector("1", "1", "2")))
      } @@ exceptJS(nonFlaky),
      test("preserves identity of acquired resources") {
        for {
          testRef <- Ref.make(Vector[String]())
          layer =
            ZLayer.scoped {
              for {
                ref <-
                  ZIO.acquireRelease(Ref.make[Vector[String]](Vector()))(_.get.flatMap(testRef.set))
                _ <- ZIO.unit
              } yield ref
            }
          _      <- ZIO.scoped(layer.build.flatMap(_.get.update(_ :+ "test")))
          result <- testRef.get
        } yield assert(result)(equalTo(Vector("test")))
      },
      test("retry") {
        for {
          ref    <- Ref.make(0)
          effect  = ref.update(_ + 1) *> ZIO.fail("fail")
          layer   = ZLayer.fromZIOEnvironment(effect).retry(Schedule.recurs(3))
          _      <- ZIO.scoped(layer.build).ignore
          result <- ref.get
        } yield assert(result)(equalTo(4))
      },
      test("error handling") {
        val sleep  = ZIO.sleep(100.milliseconds)
        val layer1 = ZLayer.fail("foo")
        val layer2 = ZLayer.succeed("bar")
        val layer3 = ZLayer.succeed("baz")
        val layer4 = ZLayer.scoped(ZIO.acquireRelease(sleep)(_ => sleep))
        val env    = layer1 ++ ((layer2 ++ layer3) >+> layer4)
        assertZIO(ZIO.unit.provideLayer(env).exit)(fails(equalTo("foo")))
      } @@ withLiveClock,
      test("project") {
        final case class Person(name: String, age: Int)
        val personLayer = ZLayer.succeed(Person("User", 42))
        val ageLayer    = personLayer.project(_.age)
        assertZIO(ZIO.service[Int].provide(ageLayer))(equalTo(42))
      },
      test("tap") {
        for {
          ref   <- Ref.make("foo")
          layer  = ZLayer.succeed("bar").tap(r => ref.set(r.get))
          _     <- ZIO.scoped(layer.build)
          value <- ref.get
        } yield assert(value)(equalTo("bar"))
      },
      test("provides a partial environment to an effect") {
        val needsIntAndString = ZIO.environment[Int & String]
        val providesInt       = ZLayer.succeed(10)
        val needsString       = ZIO.provideLayer(providesInt)(needsIntAndString)
        needsString
          .provide(ZLayer.succeed("hi"))
          .map { result =>
            assertTrue(
              result.get[Int] == 10,
              result.get[String] == "hi"
            )
          }
      },
      test(">>> provides a partial environment to another layer") {
        final case class FooService(ref: Ref[Int], string: String, boolean: Boolean) {
          def get: UIO[(Int, String, Boolean)] = ref.get.map(i => (i, string, boolean))
        }
        val fooBuilder =
          ZLayer {
            for {
              ref     <- ZIO.service[Ref[Int]]
              string  <- ZIO.service[String]
              boolean <- ZIO.service[Boolean]
            } yield FooService(ref, string, boolean)
          }
        val provideRefInt = ZLayer(Ref.make(10))

        val needsStringAndBoolean = provideRefInt >>> fooBuilder

        ZIO
          .serviceWithZIO[FooService](_.get)
          .provide(needsStringAndBoolean, ZLayer.succeed("hi"), ZLayer.succeed(true))
          .map { case (int, string, boolean) =>
            assertTrue(
              int == 10,
              string == "hi",
              boolean == true
            )
          }
      },
      test(">+> provides a partial environment to another layer") {
        final case class FooService(ref: Ref[Int], string: String, boolean: Boolean) {
          def get: UIO[(Int, String, Boolean)] = ref.get.map(i => (i, string, boolean))
        }
        val fooBuilder =
          ZLayer {
            for {
              ref     <- ZIO.service[Ref[Int]]
              string  <- ZIO.service[String]
              boolean <- ZIO.service[Boolean]
            } yield FooService(ref, string, boolean)
          }
        val provideRefInt = ZLayer(Ref.make(10))

        val needsStringAndBoolean = provideRefInt >+> fooBuilder

        ZIO
          .serviceWithZIO[FooService](_.get)
          .zip(ZIO.serviceWithZIO[Ref[Int]](_.get))
          .provide(needsStringAndBoolean, ZLayer.succeed("hi"), ZLayer.succeed(true))
          .map { case (int, string, boolean, int2) =>
            assertTrue(
              int == 10,
              int2 == 10,
              string == "hi",
              boolean == true
            )
          }
      },
      test("apply provides an effect with part of its required environment") {
        val needsIntAndString = ZIO.environment[Int & String]
        val providesInt       = ZLayer.succeed(10)
        val needsString       = providesInt(needsIntAndString)
        needsString
          .provideLayer(ZLayer.succeed("hi"))
          .map { result =>
            assertTrue(
              result.get[Int] == 10,
              result.get[String] == "hi"
            )
          }
      },
      test("caching values in dependencies") {
        case class Config(value: Int)
        case class A(value: Int)
        val aLayer = ZLayer.fromFunction((config: Config) => A(config.value))

        case class B(value: Int)
        val bLayer = ZLayer.fromFunction((a: A) => B(a.value))

        case class C(value: Int)
        val cLayer = ZLayer.fromFunction((a: A) => C(a.value))

        val fedB = (ZLayer.succeed(Config(1)) >>> aLayer) >>> bLayer
        val fedC = (ZLayer.succeed(Config(2)) >>> aLayer) >>> cLayer
        for {
          tuple <- ZIO.scoped((fedB ++ fedC).build.map(v => (v.get[B], v.get[C])))
          (a, b) = tuple
        } yield assert(a.value)(equalTo(b.value))
      },
      test("extend scope") {
        for {
          ref  <- Ref.make[Vector[String]](Vector.empty)
          layer = makeLayer1(ref).extendScope
          acquire <- ZIO.scoped {
                       for {
                         _       <- ZIO.unit.provideLayer(layer)
                         acquire <- ref.get
                       } yield acquire
                     }
          release <- ref.get
        } yield assertTrue(acquire == Vector("Acquiring Module 1")) &&
          assertTrue(release == Vector("Acquiring Module 1", "Releasing Module 1"))
      },
      test("succeed is lazy") {
        import java.util.concurrent.atomic.AtomicInteger
        final case class Counter(ref: AtomicInteger) {
          def getAndIncrement: UIO[Int] = ZIO.succeed(ref.getAndIncrement())
        }
        val layer = ZLayer.succeed(Counter(new AtomicInteger(0)))
        def getAndIncrement: ZIO[Counter, Nothing, Int] =
          ZIO.serviceWithZIO(_.getAndIncrement)
        for {
          x <- getAndIncrement.provide(layer)
          y <- getAndIncrement.provide(layer)
        } yield assertTrue(x == 0 && y == 0)
      },
      test("fromFunction") {
        final case class Person(name: String, age: Int)
        val layer: ZLayer[String with Int, Nothing, Person] = ZLayer.fromFunction(Person(_, _))
        for {
          person <- (ZLayer.succeed("Jane Doe") ++ ZLayer.succeed(42) >>> layer).build
        } yield assertTrue(person == ZEnvironment(Person("Jane Doe", 42)))
      },
      test("preserves failures") {
        val layer1   = liveEnvironment >>> TestEnvironment.live
        val layer2   = ZLayer.fromZIO(ZIO.fail("fail"))
        val layer3   = liveEnvironment >>> TestEnvironment.live
        val combined = layer1 ++ layer2 ++ layer3
        for {
          exit <- ZIO.scoped(combined.build).uninterruptible.exit
        } yield assert(exit)(failsCause(containsCause(Cause.fail("fail"))))
      } @@ exceptJS(nonFlaky),
      test("fiberRef changes are memoized") {
        for {
          fiberRef    <- FiberRef.make(false)
          layer1       = ZLayer.scoped(fiberRef.locallyScoped(true))
          layer2       = ZLayer.fromZIO(fiberRef.get)
          layer3       = layer1 ++ (layer1 >>> layer2)
          environment <- layer3.build
          value        = environment.get[Boolean]
        } yield assertTrue(value)
      } @@ exceptJS(nonFlaky),
      test("runtimeFlags") {
        for {
          runtimeFlags <- ZIO.runtimeFlags.provideLayer(Runtime.enableCurrentFiber ++ Runtime.enableOpLog)
        } yield assertTrue(RuntimeFlags.isEnabled(runtimeFlags)(RuntimeFlag.CurrentFiber)) &&
          assertTrue(RuntimeFlags.isEnabled(runtimeFlags)(RuntimeFlag.OpLog))
      } @@ exceptJS(nonFlaky),
      test("suspend lazily constructs a layer") {
        var n = 0
        val layer = ZLayer.suspend {
          n += 1
          ZLayer.empty
        }
        for {
          _ <- layer.build
          _ <- layer.build
        } yield assertTrue(n == 2)
      },
      test("layers acquired in parallel are released in parallel") {
        for {
          promise1 <- Promise.make[Nothing, Unit]
          promise2 <- Promise.make[Nothing, Unit]
          layer1    = ZLayer.scoped(ZIO.addFinalizer(promise1.succeed(())) *> promise2.succeed(()))
          layer2    = ZLayer.scoped(promise2.await *> ZIO.addFinalizer(promise1.await))
          layer3    = layer1 ++ layer2
          _        <- layer3.build
        } yield assertCompletes
      },
      test("runWith works") {
        trait Dependency

        object Dependency {
          val layer = ZLayer.succeed(new Dependency {})
        }

        trait TodoRepo {
          def run(ref: Ref[List[String]]) = ref.update("Running TodoRepo" :: _)
        }

        object TodoRepo {
          val layer: ZLayer[Dependency, Nothing, TodoRepo] =
            ZLayer.fromZIO {
              ZIO.service[Dependency].zipRight(ZIO.succeed(new TodoRepo {}))
            }
        }

        trait TodoConfig {
          def run(ref: Ref[List[String]]) = ref.update("Running TodoConfig" :: _)

        }

        object TodoConfig {
          val layer: ZLayer[Any, Nothing, TodoConfig] = ZLayer.succeed(new TodoConfig {})
        }

        trait EmailService {
          def run(ref: Ref[List[String]]) = ref.update("Running EmailService" :: _)

        }

        object EmailService {
          val layer: ZLayer[Any, Nothing, EmailService] = ZLayer.succeed(new EmailService {})
        }

        object TodoApp {
          def layer(ref: Ref[List[String]]): ZLayer[TodoRepo & TodoConfig & EmailService, Nothing, Unit] =
            ZLayer.fromZIO {
              for {
                _ <- ZIO.serviceWithZIO[TodoRepo](_.run(ref))
                _ <- ZIO.serviceWithZIO[TodoConfig](_.run(ref))
                _ <- ZIO.serviceWithZIO[EmailService](_.run(ref))
              } yield ()
            }
        }

        for {
          ref    <- Ref.make(List.empty[String])
          _      <- TodoApp.layer(ref).runWith(TodoRepo.layer, TodoConfig.layer, Dependency.layer, EmailService.layer)
          result <- ref.get.map(_.toSet)
        } yield assertTrue(
          result == Set("Running TodoRepo", "Running TodoConfig", "Running EmailService")
        )
      }
    )
}
