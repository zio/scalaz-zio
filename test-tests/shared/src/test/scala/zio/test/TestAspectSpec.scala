package zio.test

import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.TestUtils._

import scala.reflect.ClassTag

object TestAspectSpec extends ZIOBaseSpec {

  def spec = suite("TestAspectSpec")(
    test("around evaluates tests inside context of Scope") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 assertZIO(ref.get)(equalTo(1))
               } @@ around(ref.set(1), ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isTrue) &&
        assert(after)(equalTo(-1))
      }
    },
    test("aroundAll evaluates all tests between before and after effects") {
      for {
        ref <- Ref.make(0)
        spec = suite("suite1")(
                 suite("suite2")(
                   test("test1") {
                     assertZIO(ref.get)(equalTo(1))
                   },
                   test("test2") {
                     assertZIO(ref.get)(equalTo(1))
                   },
                   test("test3") {
                     assertZIO(ref.get)(equalTo(1))
                   }
                 ) @@ aroundAll(ref.update(_ + 1), ref.update(_ - 1)),
                 test("test4") {
                   assertZIO(ref.get)(equalTo(1))
                 } @@ aroundAll(ref.update(_ + 1), ref.update(_ - 1))
               )
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isTrue) &&
        assert(after)(equalTo(0))
      }
    },
    test("after evaluates in case if test IO fails") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 ZIO.fail("error")
               } @@ after(ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isFalse) &&
        assert(after)(equalTo(-1))
      }
    },
    test("after evaluates in case if test IO dies") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 ZIO.dieMessage("death")
               } @@ after(ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isFalse) &&
        assert(after)(equalTo(-1))
      }
    },
    test("afterFailure evaluates in case if test IO fails") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 ZIO.fail("error")
               } @@ afterFailure(ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isFalse) &&
        assert(after)(equalTo(-1))
      }
    },
    test("afterFailure does not evaluate in case if test IO succeeds") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 assertZIO(ref.get)(equalTo(0))
               } @@ afterFailure(ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isTrue) &&
        assert(after)(equalTo(0))
      }
    },
    test("afterSuccess evaluates in case if test IO succeeds") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 assertZIO(ref.get)(equalTo(0))
               } @@ afterSuccess(ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isTrue) &&
        assert(after)(equalTo(-1))
      }
    },
    test("afterSuccess does not evaluate in case if test IO fails") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 ZIO.fail("error")
               } @@ afterSuccess(ref.set(-1))
        result <- succeeded(spec)
        after  <- ref.get
      } yield {
        assert(result)(isFalse) &&
        assert(after)(equalTo(0))
      }
    },
    test("exceptJS runs tests on all platforms except ScalaJS") {
      assert(TestPlatform.isJS)(isFalse)
    } @@ exceptJS,
    test("exceptJVM runs tests on all platforms except the JVM") {
      assert(TestPlatform.isJVM)(isFalse)
    } @@ exceptJVM,
    test("exceptNative runs tests on all platforms except ScalaNative") {
      assert(TestPlatform.isNative)(isFalse)
    } @@ exceptNative,
    test("exceptScala2 runs tests on all versions except Scala 2") {
      assert(TestVersion.isScala2)(isFalse)
    } @@ exceptScala2,
    test("exceptScala3 runs tests on all versions except Scala3") {
      assert(TestVersion.isScala3)(isFalse)
    } @@ exceptScala3,
    test("failure makes a test pass if the result was a failure") {
      assert(throw new java.lang.Exception("boom"))(isFalse)
    } @@ failing,
    test("failure makes a test pass if it died with a specified failure") {
      throw new NullPointerException()
      assertCompletes
    } @@ failing(diesWithSubtypeOf[NullPointerException]),
    test("failure does not make a test pass if it failed with an unexpected exception") {
      assert(throw new NullPointerException())(isFalse)
    } @@ failing(diesWithSubtypeOf[IllegalArgumentException])
      @@ failing,
    test("failure does not make a test pass if the specified failure does not match") {
      assert(throw new RuntimeException())(isFalse)
    } @@ failing(diesWith(_.getMessage == "boom"))
      @@ failing,
    test("failure makes tests pass on any assertion failure") {
      assert(true)(equalTo(false))
    } @@ failing,
    test("failure makes tests pass on an expected assertion failure") {
      assert(true)(equalTo(false))
    } @@ failing[TestFailure[Any]] {
      case TestFailure.Assertion(_, _) => true
      case TestFailure.Runtime(_, _)   => false
    },
    test("flaky retries a test that fails") {
      for {
        ref <- Ref.make(0)
        spec = test("flaky test") {
                 assertZIO(ref.updateAndGet(_ + 1))(equalTo(100))
               } @@ flaky
        result <- succeeded(spec)
        n      <- ref.get
      } yield assert(result)(isTrue) && assert(n)(equalTo(100))
    },
    test("flaky retries a test that dies") {
      for {
        ref <- Ref.make(0)
        spec = test("flaky test that dies") {
                 assertZIO(ref.updateAndGet(_ + 1).filterOrDieMessage(_ >= 100)("die"))(equalTo(100))
               } @@ flaky
        result <- succeeded(spec)
        n      <- ref.get
      } yield assert(result)(isTrue) && assert(n)(equalTo(100))
    },
    test("flaky retries a test with a limit") {
      assert(true)(isFalse)
    } @@ flaky @@ failing,
    test("ifEnvOption runs a test if environment variable satisfies assertion") {
      assert(true)(isTrue)
    } @@ ifEnvOption("QWERTY")(_ => true) @@ success @@ jvmOnly,
    test("ifEnvOption ignores a test if environment variable does not satisfy assertion") {
      assert(true)(isFalse)
    } @@ ifEnvOption("QWERTY")(_ => false) @@ jvmOnly,
    test("ifEnv runs a test if environment variable satisfies assertion") {
      assert(true)(isTrue)
    } @@ ifEnv("PATH")(_.contains("bin")) @@ success @@ jvmOnly,
    test("ifEnv ignores a test if environment variable does not satisfy assertion") {
      assert(true)(isFalse)
    } @@ ifEnv("PATH")(_ => false) @@ jvmOnly,
    test("ifEnv ignores a test if environment variable does not exist") {
      assert(true)(isFalse)
    } @@ ifEnv("QWERTY")(_ => true) @@ jvmOnly,
    test("ifEnvSet runs a test if environment variable is set") {
      assert(true)(isTrue)
    } @@ ifEnvSet("PATH") @@ success @@ jvmOnly,
    test("ifEnvSet ignores a test if environment variable is not set") {
      assert(true)(isFalse)
    } @@ ifEnvSet("QWERTY") @@ jvmOnly,
    test("ifEnvNotSet runs a test if environment variable is not set") {
      assert(true)(isTrue)
    } @@ ifEnvNotSet("QWERTY") @@ success @@ jvmOnly,
    test("ifEnvNotSet ignores a test if environment variable is set") {
      assert(true)(isFalse)
    } @@ ifEnvNotSet("PATH") @@ jvmOnly,
    test("ifPropOption runs a test if property satisfies assertion") {
      assert(true)(isTrue)
    } @@ ifPropOption("qwerty")(_ => true) @@ success @@ jvmOnly,
    test("ifPropOption ignores a test if property does not satisfy assertion") {
      assert(true)(isFalse)
    } @@ ifPropOption("qwerty")(_ => false) @@ jvmOnly,
    test("ifProp runs a test if property satisfies assertion") {
      assert(true)(isTrue)
    } @@ ifProp("java.vm.name")(_.contains("VM")) @@ success @@ jvmOnly,
    test("ifProp ignores a test if property does not satisfy assertion") {
      assert(true)(isFalse)
    } @@ ifProp("java.vm.name")(_ => false) @@ jvmOnly,
    test("ifProp ignores a test if property does not exist") {
      assert(true)(isFalse)
    } @@ ifProp("qwerty")(_ => true) @@ jvmOnly,
    test("ifPropSet runs a test if property is set") {
      assert(true)(isTrue)
    } @@ ifPropSet("java.vm.name") @@ success @@ jvmOnly,
    test("ifPropSet ignores a test if property is not set") {
      assert(true)(isFalse)
    } @@ ifPropSet("qwerty") @@ jvmOnly,
    test("ifPropNotSet runs a test if property is not set") {
      assert(true)(isTrue)
    } @@ ifPropNotSet("qwerty") @@ success @@ jvmOnly,
    test("ifPropNotSet ignores a test if property is set") {
      assert(true)(isFalse)
    } @@ ifPropNotSet("java.vm.name") @@ jvmOnly,
    test("js applies test aspect only on ScalaJS") {
      for {
        ref    <- Ref.make(false)
        spec    = test("test")(assert(true)(isTrue)) @@ js(after(ref.set(true)))
        _      <- execute(spec)
        result <- ref.get
      } yield if (TestPlatform.isJS) assert(result)(isTrue) else assert(result)(isFalse)
    },
    test("jsOnly runs tests only on ScalaJS") {
      val spec   = test("Javascript-only")(assert(TestPlatform.isJS)(isTrue)) @@ jsOnly
      val result = if (TestPlatform.isJS) succeeded(spec) else isIgnored(spec)
      assertZIO(result)(isTrue)
    },
    test("jvm applies test aspect only on jvm") {
      for {
        ref    <- Ref.make(false)
        spec    = test("test")(assert(true)(isTrue)) @@ jvm(after(ref.set(true)))
        _      <- execute(spec)
        result <- ref.get
      } yield assert(if (TestPlatform.isJVM) result else !result)(isTrue)
    },
    test("jvmOnly runs tests only on the JVM") {
      val spec   = test("JVM-only")(assert(TestPlatform.isJVM)(isTrue)) @@ jvmOnly
      val result = if (TestPlatform.isJVM) succeeded(spec) else isIgnored(spec)
      assertZIO(result)(isTrue)
    },
    test("native applies test aspect only on ScalaNative") {
      for {
        ref    <- Ref.make(false)
        spec    = test("test")(assert(true)(isTrue)) @@ native(after(ref.set(true)))
        _      <- execute(spec)
        result <- ref.get
      } yield if (TestPlatform.isNative) assert(result)(isTrue) else assert(result)(isFalse)
    },
    test("nativeOnly runs tests only on ScalaNative") {
      val spec   = test("Native-only")(assert(TestPlatform.isNative)(isTrue)) @@ nativeOnly
      val result = if (TestPlatform.isNative) succeeded(spec) else isIgnored(spec)
      assertZIO(result)(isTrue)
    },
    suite("nonTermination")(
      test("makes a test pass if it does not terminate within the specified time") {
        assertZIO(ZIO.never)(anything)
      } @@ nonTermination(10.milliseconds),
      test("makes a test fail if it succeeds within the specified time") {
        assertZIO(ZIO.unit)(anything)
      } @@ nonTermination(1.minute) @@ failing,
      test("makes a test fail if it fails within the specified time") {
        assertZIO(ZIO.fail("fail"))(anything)
      } @@ nonTermination(1.minute) @@ failing
    ),
    test("repeats sets the number of times to repeat a test to the specified value") {
      for {
        ref   <- Ref.make(0)
        spec   = test("test")(assertZIO(ref.update(_ + 1))(anything)) @@ nonFlaky @@ repeats(42)
        _     <- execute(spec)
        value <- ref.get
      } yield assert(value)(equalTo(43))
    },
    test("retries sets the number of times to repeat a test to the specified value") {
      for {
        ref   <- Ref.make(0)
        spec   = test("test")(assertZIO(ref.update(_ + 1))(nothing)) @@ flaky @@ retries(42)
        _     <- execute(spec)
        value <- ref.get
      } yield assert(value)(equalTo(43))
    },
    test("samples sets the number of sufficient samples to the specified value") {
      for {
        ref   <- Ref.make(0)
        _     <- check(Gen.int.noShrink)(_ => assertZIO(ref.update(_ + 1))(anything))
        value <- ref.get
      } yield assert(value)(equalTo(42))
    } @@ samples(42),
    test("scala2 applies test aspect only on Scala 2") {
      for {
        ref    <- Ref.make(false)
        spec    = test("test")(assert(true)(isTrue)) @@ scala2(after(ref.set(true)))
        _      <- execute(spec)
        result <- ref.get
      } yield if (TestVersion.isScala2) assert(result)(isTrue) else assert(result)(isFalse)
    },
    test("scala3 applies test aspect only on Scala 3") {
      for {
        ref    <- Ref.make(false)
        spec    = test("test")(assert(true)(isTrue)) @@ scala3(after(ref.set(true)))
        _      <- execute(spec)
        result <- ref.get
      } yield if (TestVersion.isScala3) assert(result)(isTrue) else assert(result)(isFalse)
    },
    test("scala2Only runs tests only on Scala 2") {
      val spec   = test("Scala2-only")(assert(TestVersion.isScala2)(isTrue)) @@ scala2Only
      val result = if (TestVersion.isScala2) succeeded(spec) else isIgnored(spec)
      assertZIO(result)(isTrue)
    },
    test("scala3Only runs tests only on Scala 3") {
      val spec   = test("Scala3-only")(assert(TestVersion.isScala3)(isTrue)) @@ scala3Only
      val result = if (TestVersion.isScala3) succeeded(spec) else isIgnored(spec)
      assertZIO(result)(isTrue)
    },
    test("setSeed sets the random seed to the specified value before each test") {
      assertZIO(TestRandom.getSeed)(equalTo(seed & ((1L << 48) - 1)))
    } @@ setSeed(seed),
    test("shrinks sets the maximum number of shrinkings to the specified value") {
      for {
        ref   <- Ref.make(0)
        _     <- check(Gen.int)(_ => assertZIO(ref.update(_ + 1))(nothing))
        value <- ref.get
      } yield assert(value)(equalTo(1))
    } @@ shrinks(0),
    test("shrinks preserves the original failure") {
      check(Gen.int) { n =>
        assert(n)(equalTo(n + 1))
      }
    } @@ shrinks(0) @@ failing,
    test("size sets the size to the specified value") {
      assertZIO(Sized.size)(equalTo(42))
    } @@ size(42),
    test("timeout makes tests fail after given duration") {
      assertZIO(ZIO.never *> ZIO.unit)(equalTo(()))
    } @@ timeout(1.nanos)
      @@ failing(diesWithSubtypeOf[TestTimeoutException]),
    test("verify verifies the specified post-condition after each test is run") {
      for {
        ref <- Ref.make(false)
        spec = suite("verify")(
                 test("first test")(ZIO.succeed(assertCompletes)),
                 test("second test")(ref.set(true).as(assertCompletes))
               ) @@ sequential @@ verify(assertZIO(ref.get)(isTrue))
        result <- succeeded(spec)
      } yield assert(result)(isFalse)
    },
    test("withLiveEnvironment runs tests with the live environment") {
      for {
        _ <- ZIO.sleep(1.nanosecond)
      } yield assertCompletes
    } @@ withLiveEnvironment,
    test("withConfigProvider runs tests with the specified config provider") {
      for {
        value <- ZIO.config(Config.string("key"))
      } yield assertTrue(value == "value")
    } @@ withConfigProvider(ConfigProvider.fromMap(Map("key" -> "value"))),
    suite("checks")(
      test("runs aspect for every check sample") {
        check(Gen.int)(_ => assertCompletesZIO) *>
          checksCounter.get.map(assert(_)(equalTo((3, 3))))
      } @@ samples(3) @@ checks(checksCounterAspect),
      test("can interact with services in the environment") {
        check(Gen.int) { n =>
          for {
            service <- ZIO.service[CounterService]
            _       <- service.increment(n)
            m       <- service.get
          } yield assert(m)(equalTo(n))
        }
      }
        .@@(checksZIO(resetCounterServiceAspect))
        .provide(CounterService.live),
      test("can combine multiple aspects") {
        check(Gen.int)(_ => assertCompletesZIO) *>
          checksCounter.get.map(assert(_)(equalTo((6, 6))))
      } @@ samples(3) @@ checks(checksCounterAspect) @@ checks(checksCounterAspect)
    ) @@ sequential @@ after(resetChecksCounter)
  )

  def diesWithSubtypeOf[E](implicit ct: ClassTag[E]): TestFailure[E] => Boolean =
    diesWith(ct.unapply(_).isDefined)

  def diesWith(assertion: Throwable => Boolean): TestFailure[Any] => Boolean = {
    case TestFailure.Assertion(_, _) => false
    case TestFailure.Runtime(cause, _) =>
      cause.dieOption match {
        case Some(t) => assertion(t)
        case None    => false
      }
  }

  val interruptionTimeoutFailure: TestTimeoutException =
    TestTimeoutException(
      "Timeout of 10 ms exceeded. Couldn't interrupt test within 1 ns, possible resource leak!"
    )

  val seed = -1157790455010312737L

  val checksCounter = Unsafe.unsafe(implicit u => FiberRef.unsafe.make((0, 0)))

  val resetChecksCounter = checksCounter.set((0, 0))

  val checksCounterAspect = new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ZIO
        .acquireReleaseWith(
          checksCounter.update { case (before, after) => (before + 1, after) }
        )(_ => checksCounter.update { case (before, after) => (before, after + 1) })(_ => zio)
  }

  trait CounterService {
    def get: UIO[Int]
    def increment(amount: Int): UIO[Unit]
    def reset: UIO[Unit]
  }

  object CounterService {
    val live: ULayer[CounterService] = ZLayer {
      for {
        ref <- Ref.make[Int](0)
      } yield new CounterService {
        def get: UIO[Int]                     = ref.get
        def increment(amount: Int): UIO[Unit] = ref.update(_ + amount)
        def reset: UIO[Unit]                  = ref.set(0)
      }
    }
  }

  val resetCounterServiceAspect = ZIO.serviceWith[CounterService] { cs =>
    new TestAspect.CheckAspect {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace) =
        zio <* cs.reset
    }
  }
}
