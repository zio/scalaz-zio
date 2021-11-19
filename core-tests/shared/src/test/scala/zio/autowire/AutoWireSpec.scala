package zio.autowire

import zio._
import zio.internal.macros.StringUtils.StringOps
import zio.test.Assertion.{equalTo, isLeft}
import zio.test.AssertionM.Render.param
import zio.test._

object AutoWireSpec extends ZIOBaseSpec {

  def containsStringWithoutAnsi(element: String): Assertion[String] =
    Assertion.assertion("containsStringWithoutAnsi")(param(element))(_.removingAnsiCodes.contains(element))

  def spec =
    suite("AutoWireSpec")(
      suite("ZIO")(
        suite("`zio.inject`")(
          test("automatically constructs a layer from its dependencies") {
            val doubleLayer: ULayer[Double] = ZLayer.succeed(100.1)
            val stringLayer                 = ZLayer.succeed("this string is 28 chars long")
            val intLayer =
              ZLayer {
                for {
                  str    <- ZIO.service[String]
                  double <- ZIO.service[Double]
                } yield str.length + double.toInt
              }

            val program: URIO[Int, Int] = ZIO.service[Int]
            val injected: ZIO[Any, Nothing, Int] =
              program.inject(intLayer, stringLayer, doubleLayer)

            injected.map(result => assertTrue(result == 128))
          },
          test("automatically memoizes non-val layers") {
            def sideEffectingLayer(ref: Ref[Int]): ZLayer[Any, Nothing, String] =
              ref.update(_ + 1).as("Howdy").toLayer

            val layerA: URLayer[String, Int]     = ZLayer.succeed(1)
            val layerB: URLayer[String, Boolean] = ZLayer.succeed(true)

            for {
              ref <- Ref.make(0)
              _ <- (ZIO.service[Int] <*> ZIO.service[Boolean])
                     .inject(layerA, layerB, sideEffectingLayer(ref))
              result <- ref.get
            } yield assertTrue(result == 1)
          },
          test("reports duplicate layers") {
            val checked =
              typeCheck("ZIO.service[Int].inject(ZLayer.succeed(12), ZLayer.succeed(13))")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("Int is provided by multiple layers") &&
                  containsStringWithoutAnsi("ZLayer.succeed(12)") &&
                  containsStringWithoutAnsi("ZLayer.succeed(13)")
              )
            )
          } @@ TestAspect.exceptDotty,
          test("reports unused, extra layers") {
            val someLayer: URLayer[Double, String] = ZLayer.succeed("hello")
            val doubleLayer: ULayer[Double]        = ZLayer.succeed(1.0)
            val _                                  = (someLayer, doubleLayer)

            val checked =
              typeCheck(
                "ZIO.service[Int].inject(ZLayer.succeed(12), doubleLayer, someLayer)"
              )
            assertM(checked)(isLeft(containsStringWithoutAnsi("unused")))
          } @@ TestAspect.exceptDotty,
          test("reports missing top-level layers") {
            val program: URIO[String with Int, String] = UIO("test")
            val _                                      = program

            val checked = typeCheck("program.inject(ZLayer.succeed(3))")
            assertM(checked)(isLeft(containsStringWithoutAnsi("missing String")))
          } @@ TestAspect.exceptDotty,
          test("reports multiple missing top-level layers") {
            val program: URIO[String with Int, String] = UIO("test")
            val _                                      = program

            val checked = typeCheck("program.inject()")
            assertM(checked)(
              isLeft(containsStringWithoutAnsi("missing String") && containsStringWithoutAnsi("missing Int"))
            )
          } @@ TestAspect.exceptDotty,
          test("reports missing transitive dependencies") {
            import TestLayer._
            val program: URIO[OldLady, Boolean] = ZIO.service[OldLady].flatMap(_.willDie)
            val _                               = program

            val checked = typeCheck("program.inject(OldLady.live)")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("missing zio.autowire.AutoWireSpec.TestLayer.Fly") &&
                  containsStringWithoutAnsi("for TestLayer.OldLady.live")
              )
            )
          } @@ TestAspect.exceptDotty,
          test("reports nested missing transitive dependencies") {
            import TestLayer._
            val program: URIO[OldLady, Boolean] = ZIO.service[OldLady].flatMap(_.willDie)
            val _                               = program

            val checked = typeCheck("program.inject(OldLady.live, Fly.live)")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("missing zio.autowire.AutoWireSpec.TestLayer.Spider") &&
                  containsStringWithoutAnsi("for TestLayer.Fly.live")
              )
            )
          } @@ TestAspect.exceptDotty,
          test("reports circular dependencies") {
            import TestLayer._
            val program: URIO[OldLady, Boolean] = ZIO.service[OldLady].flatMap(_.willDie)
            val _                               = program

            val checked = typeCheck("program.inject(OldLady.live, Fly.manEatingFly)")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("TestLayer.Fly.manEatingFly") &&
                  containsStringWithoutAnsi(
                    "both requires and is transitively required by TestLayer.OldLady.live"
                  )
              )
            )
          } @@ TestAspect.exceptDotty
        ),
        suite("injectCustom")(
          test("automatically constructs a layer, leaving off ZEnv") {
            val stringLayer = Console.readLine.orDie.toLayer
            val program     = ZIO.service[String].zipWith(Random.nextInt)((str, int) => s"$str $int")
            val provided = TestConsole.feedLines("Your Lucky Number is:") *>
              program.injectCustom(stringLayer)

            assertM(provided)(equalTo("Your Lucky Number is: -1295463240"))
          }
        ),
        suite("injectSome")(
          test("automatically constructs a layer, leaving off some environment") {
            val stringLayer = Console.readLine.orDie.toLayer
            val program     = ZIO.service[String].zipWith(Random.nextInt)((str, int) => s"$str $int")
            val provided = TestConsole.feedLines("Your Lucky Number is:") *>
              program.injectSome[Random with Console](stringLayer)

            assertM(provided)(equalTo("Your Lucky Number is: -1295463240"))
          }
        ),
        suite("`ZLayer.wire`")(
          test("automatically constructs a layer") {
            val doubleLayer = ZLayer.succeed(100.1)
            val stringLayer: ULayer[String] =
              ZLayer.succeed("this string is 28 chars long")
            val intLayer = (ZIO.service[String] <*> ZIO.service[Double]).map { case (str, double) =>
              str.length + double.toInt
            }.toLayer

            val layer =
              ZLayer.wire[Int](intLayer, stringLayer, doubleLayer)
            val provided = ZIO.service[Int].provide(layer)
            assertM(provided)(equalTo(128))
          },
          test("correctly decomposes nested, aliased intersection types") {
            type StringAlias           = String
            type HasBooleanDoubleAlias = Boolean with Double
            type And2[A, B]            = A with B
            type FinalAlias            = And2[Int, StringAlias] with HasBooleanDoubleAlias
            val _ = ZIO.environment[FinalAlias]

            val checked = typeCheck("ZLayer.wire[FinalAlias]()")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("missing Int") &&
                  containsStringWithoutAnsi("missing String") &&
                  containsStringWithoutAnsi("missing Boolean") &&
                  containsStringWithoutAnsi("missing Double")
              )
            )
          } @@ TestAspect.exceptDotty
        ),
        suite("`ZLayer.wireSome`")(
          test("automatically constructs a layer, leaving off some remainder") {
            val stringLayer = ZLayer.succeed("this string is 28 chars long")
            val intLayer = (ZIO.service[String] <*> ZIO.service[Double]).map { case (str, double) =>
              str.length + double.toInt
            }.toLayer
            val program = ZIO.service[Int]

            val layer =
              ZLayer.wireSome[Double with Boolean, Int](intLayer, stringLayer)
            val provided =
              program.provide(
                ZLayer.succeed(true) ++ ZLayer.succeed(100.1) >>> layer
              )
            assertM(provided)(equalTo(128))
          }
        )
      ),
      suite("ZManaged")(
        suite("`zmanaged.inject`")(
          test("automatically constructs a layer") {
            val doubleLayer = ZLayer.succeed(100.1)
            val stringLayer = ZLayer.succeed("this string is 28 chars long")
            val intLayer =
              ZLayer {
                for {
                  str    <- ZManaged.service[String]
                  double <- ZManaged.service[Double]
                } yield str.length + double.toInt
              }

            val program  = ZManaged.service[Int]
            val provided = program.inject(intLayer, stringLayer, doubleLayer)
            assertM(provided.useNow)(equalTo(128))
          },
          test("automatically memoizes non-val layers") {
            def sideEffectingLayer(ref: Ref[Int]): ZLayer[Any, Nothing, String] =
              ref.update(_ + 1).as("Howdy").toLayer

            val layerA: URLayer[String, Int]     = ZLayer.succeed(1)
            val layerB: URLayer[String, Boolean] = ZLayer.succeed(true)

            (for {
              ref <- Ref.make(0).toManaged
              _ <- (ZManaged.service[Int] <*> ZManaged.service[Boolean])
                     .inject(layerA, layerB, sideEffectingLayer(ref))
              result <- ref.get.toManaged
            } yield assert(result)(equalTo(1))).useNow
          },
          test("reports missing top-level layers") {
            val program: ZManaged[String with Int, Nothing, String] = ZManaged.succeed("test")
            val _                                                   = program

            val checked = typeCheck("program.inject(ZLayer.succeed(3))")
            assertM(checked)(isLeft(containsStringWithoutAnsi("missing String")))
          } @@ TestAspect.exceptDotty,
          test("reports multiple missing top-level layers") {
            val program: ZManaged[String with Int, Nothing, String] = ZManaged.succeed("test")
            val _                                                   = program

            val checked = typeCheck("program.inject()")
            assertM(checked)(
              isLeft(containsStringWithoutAnsi("missing String") && containsStringWithoutAnsi("missing Int"))
            )
          } @@ TestAspect.exceptDotty,
          test("reports missing transitive dependencies") {
            import TestLayer._
            val program: URManaged[OldLady, Boolean] = ZManaged.service[OldLady].flatMap(_.willDie.toManaged)
            val _                                    = program

            val checked = typeCheck("program.inject(OldLady.live)")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("missing zio.autowire.AutoWireSpec.TestLayer.Fly") &&
                  containsStringWithoutAnsi("for TestLayer.OldLady.live")
              )
            )
          } @@ TestAspect.exceptDotty,
          test("reports nested missing transitive dependencies") {
            import TestLayer._
            val program: URManaged[OldLady, Boolean] = ZManaged.service[OldLady].flatMap(_.willDie.toManaged)
            val _                                    = program

            val checked = typeCheck("program.inject(OldLady.live, Fly.live)")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("missing zio.autowire.AutoWireSpec.TestLayer.Spider") &&
                  containsStringWithoutAnsi("for TestLayer.Fly.live")
              )
            )
          } @@ TestAspect.exceptDotty,
          test("reports circular dependencies") {
            import TestLayer._
            val program: URManaged[OldLady, Boolean] = ZManaged.service[OldLady].flatMap(_.willDie.toManaged)
            val _                                    = program

            val checked = typeCheck("program.inject(OldLady.live, Fly.manEatingFly)")
            assertM(checked)(
              isLeft(
                containsStringWithoutAnsi("TestLayer.Fly.manEatingFly") &&
                  containsStringWithoutAnsi(
                    "both requires and is transitively required by TestLayer.OldLady.live"
                  )
              )
            )
          } @@ TestAspect.exceptDotty
        ),
        suite("injectCustom")(
          test("automatically constructs a layer, leaving off ZEnv") {
            val stringLayer = Console.readLine.orDie.toLayer
            val program     = ZManaged.service[String].zipWith(Random.nextInt.toManaged)((str, int) => s"$str $int")
            val provided = TestConsole.feedLines("Your Lucky Number is:").toManaged *>
              program.injectCustom(stringLayer)

            assertM(provided.useNow)(equalTo("Your Lucky Number is: -1295463240"))
          }
        ),
        suite("injectSome")(
          test("automatically constructs a layer, leaving off some environment") {
            val stringLayer = Console.readLine.orDie.toLayer
            val program     = ZManaged.service[String].zipWith(Random.nextInt.toManaged)((str, int) => s"$str $int")
            val provided = TestConsole.feedLines("Your Lucky Number is:").toManaged *>
              program.injectSome[Random with Console](stringLayer)

            assertM(provided.useNow)(equalTo("Your Lucky Number is: -1295463240"))
          }
        )
      )
    )

  object TestLayer {
    trait OldLady {
      def willDie: UIO[Boolean]
    }

    object OldLady {
      def live: URLayer[Fly, OldLady] = ZLayer.succeed(new OldLady {
        override def willDie: UIO[Boolean] = UIO(false)
      })
    }

    trait Fly {}
    object Fly {
      def live: URLayer[Spider, Fly]          = ZLayer.succeed(new Fly {})
      def manEatingFly: URLayer[OldLady, Fly] = ZLayer.succeed(new Fly {})
    }

    trait Spider {}
    object Spider {
      def live: ULayer[Spider] = ZLayer.succeed(new Spider {})
    }
  }
}
