package zio.autowire

import zio._
import zio.internal.macros.StringUtils.StringOps
import zio.test.Assertion.{anything, equalTo, isLeft}
import zio.test._

import scala.annotation.nowarn

object AutoWireSpec extends ZIOBaseSpec {

  def containsStringWithoutAnsi(element: String): Assertion[String] =
    Assertion.assertion("containsStringWithoutAnsi")(_.unstyled.contains(element))

  def spec =
    suite("AutoWireSpec")(
      suite("ZIO")(
        suite("`zio.provide`")(
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
              program.provide(intLayer, stringLayer, doubleLayer)

            injected.map(result => assertTrue(result == 128))
          },
          test("automatically memoizes non-val layers") {
            def sideEffectingLayer(ref: Ref[Int]): ZLayer[Any, Nothing, String] =
              ZLayer(ref.update(_ + 1).as("Howdy"))

            val layerA: URLayer[String, Int]     = ZLayer.succeed(1)
            val layerB: URLayer[String, Boolean] = ZLayer.succeed(true)

            for {
              ref <- Ref.make(0)
              _ <- (ZIO.service[Int] <*> ZIO.service[Boolean])
                     .provide(layerA, layerB, sideEffectingLayer(ref))
              result <- ref.get
            } yield assertTrue(result == 1)
          },
          test("reports duplicate layers") {
            val checked =
              typeCheck("ZIO.service[Int].provide(ZLayer.succeed(12), ZLayer.succeed(13))")
            assertZIO(checked)(
              isLeft(
                containsStringWithoutAnsi("Ambiguous layers!") &&
                  containsStringWithoutAnsi("ZLayer.succeed(12)") &&
                  containsStringWithoutAnsi("ZLayer.succeed(13)")
              )
            )
          } @@ TestAspect.exceptScala3,
          test("reports missing top-level layers") {
            val program: URIO[String with Int, String] = ZIO.succeed("test")
            val _                                      = program

            val checked = typeCheck("program.provide(ZLayer.succeed(3))")
            assertZIO(checked)(isLeft(containsStringWithoutAnsi("String")))
          } @@ TestAspect.exceptScala3,
          test("reports multiple missing top-level layers") {
            val program: URIO[String with Int, String] = ZIO.succeed("test")
            val _                                      = program

            val checked = typeCheck("program.provide()")
            assertZIO(checked)(
              isLeft(containsStringWithoutAnsi("String") && containsStringWithoutAnsi("Int"))
            )
          } @@ TestAspect.exceptScala3,
          test("reports missing transitive dependencies") {
            import TestLayer._
            val program: URIO[OldLady, Boolean] = ZIO.service[OldLady].flatMap(_.willDie)
            val _                               = program

            val checked = typeCheck("program.provide(OldLady.live)")
            assertZIO(checked)(
              isLeft(
                containsStringWithoutAnsi("zio.autowire.AutoWireSpec.TestLayer.Fly") &&
                  containsStringWithoutAnsi("Required by TestLayer.OldLady.live")
              )
            )
          } @@ TestAspect.exceptScala3,
          test("reports nested missing transitive dependencies") {
            import TestLayer._
            val program: URIO[OldLady, Boolean] = ZIO.service[OldLady].flatMap(_.willDie)
            val _                               = program

            val checked = typeCheck("program.provide(OldLady.live, Fly.live)")
            assertZIO(checked)(
              isLeft(
                containsStringWithoutAnsi("zio.autowire.AutoWireSpec.TestLayer.Spider") &&
                  containsStringWithoutAnsi("Required by TestLayer.Fly.live")
              )
            )
          } @@ TestAspect.exceptScala3,
          test("reports circular dependencies") {
            import TestLayer._
            val program: URIO[OldLady, Boolean] = ZIO.service[OldLady].flatMap(_.willDie)
            val _                               = program

            val checked = typeCheck("program.provide(OldLady.live, Fly.manEatingFly)")
            assertZIO(checked)(
              isLeft(
                containsStringWithoutAnsi("TestLayer.Fly.manEatingFly") &&
                  containsStringWithoutAnsi("OldLady.live") &&
                  containsStringWithoutAnsi(
                    "A layer simultaneously requires and is required by another"
                  )
              )
            )
          } @@ TestAspect.exceptScala3
        ),
        suite("`ZLayer.make`")(
          test("automatically constructs a layer") {
            val doubleLayer = ZLayer.succeed(100.1)
            val stringLayer: ULayer[String] =
              ZLayer.succeed("this string is 28 chars long")
            val intLayer = ZLayer {
              (ZIO.service[String] <*> ZIO.service[Double]).map { case (str, double) =>
                str.length + double.toInt
              }
            }

            val layer =
              ZLayer.make[Int](intLayer, stringLayer, doubleLayer)
            val provided = ZIO.service[Int].provideLayer(layer)
            assertZIO(provided)(equalTo(128))
          },
          test("correctly decomposes nested, aliased intersection types") {
            type StringAlias           = String
            type HasBooleanDoubleAlias = Boolean with Double
            type And2[A, B]            = A with B
            type FinalAlias            = And2[Int, StringAlias] with HasBooleanDoubleAlias
            val _ = ZIO.environment[FinalAlias]

            val checked = typeCheck("ZLayer.make[FinalAlias]()")
            assertZIO(checked)(
              isLeft(
                containsStringWithoutAnsi("Int") &&
                  containsStringWithoutAnsi("String") &&
                  containsStringWithoutAnsi("Boolean") &&
                  containsStringWithoutAnsi("Double")
              )
            )
          } @@ TestAspect.exceptScala3,
          test("works correctly with function parameters") {
            case class MyLayer()

            def createLayerByFunction(x: () => MyLayer) = ZLayer.succeed(x())

            val byName =
              ZIO
                .service[MyLayer]
                .provide(
                  createLayerByFunction { () =>
                    val byNameLayer = MyLayer()
                    byNameLayer
                  }
                )
            assertTrue(byName != null)
          },
          test("return error when passing by-name on Scala 2 (https://github.com/zio/zio/issues/7732)") {
            assertZIO(typeCheck {
              """
                case class MyLayer()

                def createLayerByName(x: => MyLayer) = ZLayer.succeed(x)

                val byName =
                  ZIO
                    .service[MyLayer]
                    .provide(
                      createLayerByName {
                        val byNameLayer = MyLayer()
                        byNameLayer
                      }
                    )
              """
            })(isLeft(anything))
          } @@ TestAspect.exceptScala3,
          test("uses service statically known to be available") {
            trait Foo
            class Bar
            class Foo1 extends Foo
            class Foo2 extends Foo

            val barLayer: ULayer[Bar] = ZLayer.make[Bar & Foo](ZLayer.succeed(new Foo2), ZLayer.succeed(new Bar))
            val fooLayer: ULayer[Foo] = ZLayer.succeed(new Foo1)

            val l1 = ZLayer.make[Foo & Bar](barLayer, fooLayer)
            val l2 = ZLayer.make[Foo & Bar](fooLayer, barLayer)

            for {
              a1 <- ZIO.service[Foo].provideLayer(l1)
              a2 <- ZIO.service[Foo].provideLayer(l2)
            } yield assertTrue(a1.isInstanceOf[Foo1], a2.isInstanceOf[Foo1])
          },
          test("ZLayer.Debug") {
            val hello = ZLayer(ZIO.serviceWith[Int](i => s"Hello $i!"))
            @nowarn("msg=.*ZLayer Wiring Graph.*")
            val tree = ZLayer.make[String](hello, ZLayer.succeed(1), ZLayer.Debug.tree)
            @nowarn("msg=.*Mermaid Live Editor Link.*")
            val mermaid = ZLayer.make[String](hello, ZLayer.succeed(2), ZLayer.Debug.mermaid)

            for {
              s1 <- ZIO.service[String].provideLayer(tree)
              s2 <- ZIO.service[String].provideLayer(mermaid)
            } yield assertTrue(s1 == "Hello 1!", s2 == "Hello 2!")
          },
          test("takes trace from the implicit scope") {
            var numTraces: Int = 0
            val layer = {
              implicit def trace: Trace = {
                numTraces += 1
                Trace.empty
              }

              ZLayer.make[String](
                ZLayer.succeed(42),
                ZLayer.fromFunction((_: Int).toString)
              )
            }

            assertZIO(layer.build.as(numTraces))(equalTo(3))
          }
        ),
        suite("`ZLayer.makeSome`")(
          test("automatically constructs a layer, leaving off some remainder") {
            val stringLayer = ZLayer.succeed("this string is 28 chars long")
            val intLayer = ZLayer {
              (ZIO.service[String] <*> ZIO.service[Double]).map { case (str, double) =>
                str.length + double.toInt
              }
            }
            val program = ZIO.service[Int]

            val layer =
              ZLayer.makeSome[Double, Int](intLayer, stringLayer)
            val provided =
              program.provideLayer(
                ZLayer.succeed(true) ++ ZLayer.succeed(100.1) >>> layer
              )
            assertZIO(provided)(equalTo(128))
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
        override def willDie: UIO[Boolean] = ZIO.succeed(false)
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
