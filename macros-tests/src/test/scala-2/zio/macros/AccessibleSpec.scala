package zio.macros

import zio._
import zio.managed._
import zio.stream._
import zio.test.Assertion._
import zio.test._

object AccessibleSpec extends ZIOSpecDefault {

  def spec = suite("AccessibleSpec")(
    suite("Accessible macro")(
      test("compiles when applied to object with empty Service") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service
            }
          """
        })(isRight(anything))
      },
      test("fails when applied to object without a Service") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module
          """
        })(isLeft(anything))
      },
      test("success when applied to trait") {
        assertZIO(typeCheck {
          """
            @accessible
            trait Module
          """
        })(isRight(anything))
      },
      test("fails when applied to class") {
        assertZIO(typeCheck {
          """
            @accessible
            class Module
          """
        })(isLeft(anything))
      },
      test("generates accessor for values") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                val foo: UIO[Unit]
              }
            }

            object Check {
              val foo: ZIO[Module.Service, Nothing, Unit] =
                Module.foo
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for functions") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                def foo(i: Int): UIO[Unit]
              }
            }

            object Check {
              def foo(i: Int): ZIO[Module.Service, Nothing, Unit] =
                Module.foo(i)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for varargs functions") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                def varargsFoo(a: Int, b: Int*): UIO[Unit]
              }
            }

            object Check {
              def varargsFoo(a: Int, b: Int*): ZIO[Module.Service, Nothing, Unit] =
                Module.varargsFoo(a, b: _*)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for members returning ZManaged") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                def umanaged(s: String): UManaged[Int]
                def urmanaged(s: String): URManaged[String, Int]
                def zmanaged(s: String): ZManaged[String, String, Int]
              }
            }

            object Check {
              def umanaged(s: String): ZManaged[Module.Service, Nothing, Int] =
                Module.umanaged(s)
              def urmanaged(s: String): ZManaged[String with Module.Service, Nothing, Int] =
                Module.urmanaged(s)
              def zmanaged(s: String): ZManaged[String with Module.Service, String, Int] =
                Module.zmanaged(s)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with default method implementations") {
        assertZIO(typeCheck {
          """
             @accessible
             object Module {
               trait Service {
                 def foo(x: Int): Task[Unit] = foo(x.toString)
                 def foo(x: String): Task[Unit]
               }
             }
          """.stripMargin
        })(isRight(anything))
      },
      test("generates accessor for service with one type param") {
        assertZIO(typeCheck {
          """
             @accessible
             object Module {
               trait Service[T] {
                 val v: Task[T]
                 def f1: UIO[Unit]
                 def f2(): UIO[Unit]
                 def f3(t: T): UIO[Unit]
                 def f4(t: T)(i: Int): UIO[Unit]
                 def f5(t: T)(implicit i: Int): UIO[Unit]
                 def f6(t: T*): UIO[Unit]
               }
             }

             object Check {
               def v[T: Tag]: ZIO[Module.Service[T], Throwable, T] =
                 Module.v[T]
               def f1[T: Tag]: ZIO[Module.Service[T], Nothing, Unit] =
                 Module.f1[T]
               def f2[T: Tag](): ZIO[Module.Service[T], Nothing, Unit] =
                 Module.f2[T]()
               def f3[T: Tag](t: T): ZIO[Module.Service[T], Nothing, Unit] =
                 Module.f3[T](t)
               def f4[T: Tag](t: T)(i: Int): ZIO[Module.Service[T], Nothing, Unit] =
                 Module.f4[T](t)(i)
               def f5[T: Tag](t: T)(implicit i: Int): ZIO[Module.Service[T], Nothing, Unit] =
                 Module.f5[T](t)
               def f6[T: Tag](t: T*): ZIO[Module.Service[T], Nothing, Unit] =
                 Module.f6[T](t: _*)
             }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with contravariant type param") {
        assertZIO(typeCheck {
          """
             @accessible
             object Module {
               trait Service[-R] {
                 val v: RIO[R, Unit]
               }
             }

             object Check {
               def v[R: Tag]: ZIO[Module.Service[R] with R, Throwable, Unit] =
                 Module.v[R]
             }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with two type params and type bounds") {
        assertZIO(typeCheck {
          """
             trait Foo
             trait Bar

             @accessible
             object Module {
               trait Service[T <: Foo, U >: Bar] {
                 val v: Task[T]
                 def f1: UIO[U]
                 def f2(): UIO[U]
                 def f3(t: T): UIO[U]
                 def f4(t: T)(u: U): UIO[U]
                 def f5(t: T)(implicit u: U): UIO[U]
                 def f6(t: T*): UIO[U]
               }
             }

             object Check {
               def v[T <: Foo: Tag, U >: Bar: Tag]: ZIO[Module.Service[T, U], Throwable, T] =
                 Module.v[T, U]
               def f1[T <: Foo: Tag, U >: Bar: Tag]: ZIO[Module.Service[T, U], Nothing, U] =
                 Module.f1[T, U]
               def f2[T <: Foo: Tag, U >: Bar: Tag](): ZIO[Module.Service[T, U], Nothing, U] =
                 Module.f2[T, U]()
               def f3[T <: Foo: Tag, U >: Bar: Tag](t: T): ZIO[Module.Service[T, U], Nothing, U] =
                 Module.f3[T, U](t)
               def f4[T <: Foo: Tag, U >: Bar: Tag](t: T)(u: U): ZIO[Module.Service[T, U], Nothing, U] =
                 Module.f4[T, U](t)(u)
               def f5[T <: Foo: Tag, U >: Bar: Tag](t: T)(implicit u: U): ZIO[Module.Service[T, U], Nothing, U] =
                 Module.f5[T, U](t)
               def f6[T <: Foo: Tag, U >: Bar: Tag](t: T*): ZIO[Module.Service[T, U], Nothing, U] =
                 Module.f6[T, U](t: _*)
             }
          """
        })(isRight(anything))
      },
      test("generates accessors for all capabilities") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                val static                                 : UIO[String]
                def zeroArgs                               : UIO[Int]
                def zeroArgsWithParens()                   : UIO[Long]
                def singleArg(arg1: Int)                   : UIO[String]
                def multiArgs(arg1: Int, arg2: Long)       : UIO[String]
                def multiParamLists(arg1: Int)(arg2: Long) : UIO[String]
                def typedVarargs[T](arg1: Int, arg2: T*)   : UIO[T]
                def command(arg1: Int)                     : UIO[Unit]
                def overloaded(arg1: Int)                  : UIO[String]
                def overloaded(arg1: Long)                 : UIO[String]

                val staticManaged                                 : UManaged[String]
                def zeroArgsManaged                               : UManaged[Int]
                def zeroArgsTypedManaged[T]                       : UManaged[T]
                def zeroArgsWithParensManaged()                   : UManaged[Long]
                def singleArgManaged(arg1: Int)                   : UManaged[String]
                def multiArgsManaged(arg1: Int, arg2: Long)       : UManaged[String]
                def multiParamListsManaged(arg1: Int)(arg2: Long) : UManaged[String]
                def typedVarargsManaged[T](arg1: Int, arg2: T*)   : UManaged[T]
                def commandManaged(arg1: Int)                     : UManaged[Unit]
                def overloadedManaged(arg1: Int)                  : UManaged[String]
                def overloadedManaged(arg1: Long)                 : UManaged[String]

                def function(arg1: Int)                    : String
                def sink(arg1: Int)                        : ZSink[Any, Nothing, Int, Int, List[Int]]
                def stream(arg1: Int)                      : ZStream[Any, Nothing, Int]
              }
            }

            object Check {
              val static                                 : ZIO[Module.Service, Nothing, String] = Module.static
              def zeroArgs                               : ZIO[Module.Service, Nothing, Int]    = Module.zeroArgs
              def zeroArgsWithParens()                   : ZIO[Module.Service, Nothing, Long]   = Module.zeroArgsWithParens()
              def singleArg(arg1: Int)                   : ZIO[Module.Service, Nothing, String] = Module.singleArg(arg1)
              def multiArgs(arg1: Int, arg2: Long)       : ZIO[Module.Service, Nothing, String] = Module.multiArgs(arg1, arg2)
              def multiParamLists(arg1: Int)(arg2: Long) : ZIO[Module.Service, Nothing, String] = Module.multiParamLists(arg1)(arg2)
              def typedVarargs[T](arg1: Int, arg2: T*)   : ZIO[Module.Service, Nothing, T]      = Module.typedVarargs[T](arg1, arg2: _*)
              def command(arg1: Int)                     : ZIO[Module.Service, Nothing, Unit]   = Module.command(arg1)
              def overloaded(arg1: Int)                  : ZIO[Module.Service, Nothing, String] = Module.overloaded(arg1)
              def overloaded(arg1: Long)                 : ZIO[Module.Service, Nothing, String] = Module.overloaded(arg1)

              val staticManaged                                 : ZManaged[Module.Service, Nothing, String] = Module.staticManaged
              def zeroArgsManaged                               : ZManaged[Module.Service, Nothing, Int]    = Module.zeroArgsManaged
              def zeroArgsTypedManaged[T]                       : ZManaged[Module.Service, Nothing, T]      = Module.zeroArgsTypedManaged[T]
              def zeroArgsWithParensManaged()                   : ZManaged[Module.Service, Nothing, Long]   = Module.zeroArgsWithParensManaged()
              def singleArgManaged(arg1: Int)                   : ZManaged[Module.Service, Nothing, String] = Module.singleArgManaged(arg1)
              def multiArgsManaged(arg1: Int, arg2: Long)       : ZManaged[Module.Service, Nothing, String] = Module.multiArgsManaged(arg1, arg2)
              def multiParamListsManaged(arg1: Int)(arg2: Long) : ZManaged[Module.Service, Nothing, String] = Module.multiParamListsManaged(arg1)(arg2)
              def typedVarargsManaged[T](arg1: Int, arg2: T*)   : ZManaged[Module.Service, Nothing, T]      = Module.typedVarargsManaged[T](arg1, arg2: _*)
              def commandManaged(arg1: Int)                     : ZManaged[Module.Service, Nothing, Unit]   = Module.commandManaged(arg1)
              def overloadedManaged(arg1: Int)                  : ZManaged[Module.Service, Nothing, String] = Module.overloadedManaged(arg1)
              def overloadedManaged(arg1: Long)                 : ZManaged[Module.Service, Nothing, String] = Module.overloadedManaged(arg1)

              def function(arg1: Int)                    : ZIO[Module.Service, Throwable, String] = Module.function(arg1)
              def sink(arg1: Int)                        : ZSink[Module.Service, Nothing, Int, Int, List[Int]] = Module.sink(arg1)
              def stream(arg1: Int)                      : ZStream[Module.Service, Nothing, Int] = Module.stream(arg1)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for throwing values") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                @throwing
                val test: Unit
              }
            }
            object Check {
              val foo: ZIO[Module.Service, Nothing, Unit] =
                Module.test
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for throwing methods") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                @throwing
                def test: Unit
              }
            }
            object Check {
              def foo: ZIO[Module.Service, Throwable, Unit] =
                Module.test
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for values") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                val test: Unit
              }
            }
            object Check {
              val foo: ZIO[Module.Service, Nothing, Unit] =
                Module.test
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for methods") {
        assertZIO(typeCheck {
          """
            @accessible
            object Module {
              trait Service {
                def test: Unit
              }
            }
            object Check {
              def foo: ZIO[Module.Service, Nothing, Unit] =
                Module.test
            }
          """
        })(isRight(anything))
      },
      test("bubbles the error to the error channel for defs") {
        @accessible
        object Module {
          trait Service {
            @throwing
            def test(): Unit = throw new Exception("ups")
          }
        }
        def layer = ZLayer.succeed(new Module.Service {})
        assertZIO(Module.test().flip.provide(layer))(hasField("message", _.getMessage, equalTo("ups")))
      },
      test("deprecated annotation pass to accessor") {
        @accessible
        object Module {
          trait Service {
            @deprecated("msg", "v")
            def test(): Unit = throw new Exception("ups")

            def dummy(): Int = 0
          }
        }

        @annotation.nowarn
        def usingTest() = Module.test()

        def layer = ZLayer.succeed(new Module.Service {})
        assertZIO(Module.dummy().provide(layer))(equalTo(0))
      },
      test("deprecated annotation doesn't throw warning") {
        @accessible
        object Module {
          trait Service {
            @deprecated("msg", "v")
            def test(): Unit = throw new Exception("ups")

            def dummy(): Int = 0
          }
        }

        def layer = ZLayer.succeed(new Module.Service {})
        assertZIO(Module.dummy().provide(layer))(equalTo(0))
      }
    )
  )
}
