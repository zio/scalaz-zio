package zio.macros

import zio._
import zio.managed._
import zio.test.Assertion._
import zio.test._

object AccessibleMMSpec extends ZIOSpecDefault {

  def spec = suite("AccessibleMMSpec")(
    suite("AccessibleMM macro")(
      test("compiles when applied to object with empty Service") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[F[_, _]]
            }
          """
        })(isRight(anything))
      },
      test("fails when applied to object without a Service") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module
          """
        })(isLeft(anything))
      },
      test("success when applied to trait") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            trait Module[F[_, _]]
          """
        })(isRight(anything))
      },
      test("fails when applied to class") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            class Module[F[_, _]]
          """
        })(isLeft(anything))
      },
      test("fails when applied to object with Service without type param") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service
            }
          """
        })(isLeft(anything))
      },
      test("fails when applied to Service with Service without suitable type param") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[F[_]]
            }
          """
        })(isLeft(anything))
      },
      test("fails when applied to Service with Service with multiple suitable type params") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[F[_, _], G[_, _]]
            }
          """
        })(isLeft(anything))
      },
      test("fails when applied to non-ZIO type param") {
        assertZIO(typeCheck {
          """
            @accessibleMM[Either]
            object Module {
              trait Service[F[_, _]]
            }
          """
        })(isLeft(anything))
      },
      test("generates accessor for values") {
        assertZIO(typeCheck {
          """
            @accessibleMM[URIO]
            object Module {
              trait Service[F[_, _]] {
                val foo: F[Unit, Unit]
              }
            }

            object Check {
              val foo: ZIO[Module.Service[URIO] with Unit, Nothing, Unit] =
                Module.foo
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for functions") {
        assertZIO(typeCheck {
          """
            @accessibleMM[URIO]
            object Module {
              trait Service[F[_, _]] {
                def foo(i: Int): F[Unit, Unit]
              }
            }

            object Check {
              def foo(i: Int): ZIO[Module.Service[URIO] with Unit, Nothing, Unit] =
                Module.foo(i)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for varargs functions") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[F[_, _]] {
                def varargsFoo(a: Int, b: Int*): F[Unit, Unit]
              }
            }

            object Check {
              def varargsFoo(a: Int, b: Int*): ZIO[Module.Service[IO], Unit, Unit] =
                Module.varargsFoo(a, b: _*)
            }
          """
        })(isRight(anything))
      },
      test("compiles when applied to method with simple return type") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[F[_, _]] {
                def foo(a: Int): Task[Unit]
              }
            }

            object Check {
              def foo(a: Int): ZIO[Module.Service[IO], Throwable, Unit] =
                Module.foo(a)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for members returning ZManaged") {
        assertZIO(typeCheck {
          """
            @accessibleMM[RManaged]
            object Module {
              trait Service[F[_, _]] {
                def managed(s: String): F[Unit, Int]
              }
            }

            object Check {
              def managed(s: String): ZManaged[Module.Service[RManaged] with Unit, Throwable, Int] =
                Module.managed(s)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with default method implementations") {
        assertZIO(typeCheck {
          """
             @accessibleMM[IO]
             object Module {
               trait Service[F[_, _]] {
                 def foo(x: Int): F[Unit, Unit] = foo(x.toString)
                 def foo(x: String): F[Unit, Unit]
               }
             }

             object Check {
              def foo(x: Int): ZIO[Module.Service[IO], Unit, Unit] =
                Module.foo(x)
              def foo(x: String): ZIO[Module.Service[IO], Unit, Unit] =
                Module.foo(x)
            }
          """.stripMargin
        })(isRight(anything))
      },
      test("generates accessor for service with one type param other than F") {
        assertZIO(typeCheck {
          """
             @accessibleMM[IO]
             object Module {
               trait Service[F[_, _], T] {
                 val v: F[Int, T]
                 def f1: F[Int, Unit]
                 def f2(): F[Int, Unit]
                 def f3(t: T): F[Int, Unit]
                 def f4(t: T)(i: Int): F[Int, Unit]
                 def f5(t: T)(implicit i: Int): F[Int, Unit]
                 def f6(t: T*): F[Int, Unit]
               }
             }

             object Check {
               def v[T: Tag]: ZIO[Module.Service[IO, T], Int, T] =
                 Module.v[T]
               def f1[T: Tag]: ZIO[Module.Service[IO, T], Int, Unit] =
                 Module.f1[T]
               def f2[T: Tag](): ZIO[Module.Service[IO, T], Int, Unit] =
                 Module.f2[T]()
               def f3[T: Tag](t: T): ZIO[Module.Service[IO, T], Int, Unit] =
                 Module.f3[T](t)
               def f4[T: Tag](t: T)(i: Int): ZIO[Module.Service[IO, T], Int, Unit] =
                 Module.f4[T](t)(i)
               def f5[T: Tag](t: T)(implicit i: Int): ZIO[Module.Service[IO, T], Int, Unit] =
                 Module.f5[T](t)
               def f6[T: Tag](t: T*): ZIO[Module.Service[IO, T], Int, Unit] =
                 Module.f6[T](t: _*)
             }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with contravariant type param") {
        assertZIO(typeCheck {
          """
             @accessibleMM[RIO]
             object Module {
               trait Service[-A, F[_, _]] {
                 val v: F[A, Int]
               }
             }

             object Check {
               def v[A: Tag]: ZIO[Module.Service[A, RIO] with A, Throwable, Int] =
                 Module.v[A]
             }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with two type params and type bounds") {
        assertZIO(typeCheck {
          """
             trait Foo
             trait Bar

             @accessibleMM[IO]
             object Module {
               trait Service[T <: Foo, M[_, _], U >: Bar] {
                 val v: M[U, T]
                 def f1: M[T, U]
                 def f2(): M[T, U]
                 def f3(t: T): M[T, U]
                 def f4(t: T)(u: U): M[T, U]
                 def f5(t: T)(implicit u: U): M[T, U]
                 def f6(t: T*): M[T, U]
               }
             }

             object Check {
               def v[T <: Foo: Tag, U >: Bar: Tag]: ZIO[Module.Service[T, IO, U], U, T] =
                 Module.v[T, U]
               def f1[T <: Foo: Tag, U >: Bar: Tag]: ZIO[Module.Service[T, IO, U], T, U] =
                 Module.f1[T, U]
               def f2[T <: Foo: Tag, U >: Bar: Tag](): ZIO[Module.Service[T, IO, U], T, U] =
                 Module.f2[T, U]()
               def f3[T <: Foo: Tag, U >: Bar: Tag](t: T): ZIO[Module.Service[T, IO, U], T, U] =
                 Module.f3[T, U](t)
               def f4[T <: Foo: Tag, U >: Bar: Tag](t: T)(u: U): ZIO[Module.Service[T, IO, U], T, U] =
                 Module.f4[T, U](t)(u)
               def f5[T <: Foo: Tag, U >: Bar: Tag](t: T)(implicit u: U): ZIO[Module.Service[T, IO, U], T, U] =
                 Module.f5[T, U](t)
               def f6[T <: Foo: Tag, U >: Bar: Tag](t: T*): ZIO[Module.Service[T, IO, U], T, U] =
                 Module.f6[T, U](t: _*)
             }
          """
        })(isRight(anything))
      },
      test("generates accessors for ZIO capabilities") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[M[_, _]] {
                val static                                 : M[Int, String]
                def zeroArgs                               : M[Int, Int]
                def zeroArgsWithParens()                   : M[Int, Long]
                def singleArg(arg1: Int)                   : M[Int, String]
                def multiArgs(arg1: Int, arg2: Long)       : M[Int, String]
                def multiParamLists(arg1: Int)(arg2: Long) : M[Int, String]
                def typedVarargs[T](arg1: Int, arg2: T*)   : M[Int, T]
                def command(arg1: Int)                     : M[Int, Unit]
                def overloaded(arg1: Int)                  : M[Int, String]
                def overloaded(arg1: Long)                 : M[Int, String]
              }
            }

            object Check {
              val static                                 : ZIO[Module.Service[IO], Int, String] = Module.static
              def zeroArgs                               : ZIO[Module.Service[IO], Int, Int]    = Module.zeroArgs
              def zeroArgsWithParens()                   : ZIO[Module.Service[IO], Int, Long]   = Module.zeroArgsWithParens()
              def singleArg(arg1: Int)                   : ZIO[Module.Service[IO], Int, String] = Module.singleArg(arg1)
              def multiArgs(arg1: Int, arg2: Long)       : ZIO[Module.Service[IO], Int, String] = Module.multiArgs(arg1, arg2)
              def multiParamLists(arg1: Int)(arg2: Long) : ZIO[Module.Service[IO], Int, String] = Module.multiParamLists(arg1)(arg2)
              def typedVarargs[T](arg1: Int, arg2: T*)   : ZIO[Module.Service[IO], Int, T]      = Module.typedVarargs[T](arg1, arg2: _*)
              def command(arg1: Int)                     : ZIO[Module.Service[IO], Int, Unit]   = Module.command(arg1)
              def overloaded(arg1: Int)                  : ZIO[Module.Service[IO], Int, String] = Module.overloaded(arg1)
              def overloaded(arg1: Long)                 : ZIO[Module.Service[IO], Int, String] = Module.overloaded(arg1)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for ZManaged capabilities") {
        assertZIO(typeCheck {
          """
            @accessibleMM[URManaged]
            object Module {
              trait Service[M[_, _]] {
                val staticManaged                                 : M[Int, String]
                def zeroArgsManaged                               : M[Int, Int]
                def zeroArgsTypedManaged[T]                       : M[Int, T]
                def zeroArgsWithParensManaged()                   : M[Int, Long]
                def singleArgManaged(arg1: Int)                   : M[Int, String]
                def multiArgsManaged(arg1: Int, arg2: Long)       : M[Int, String]
                def multiParamListsManaged(arg1: Int)(arg2: Long) : M[Int, String]
                def typedVarargsManaged[T](arg1: Int, arg2: T*)   : M[Int, T]
                def commandManaged(arg1: Int)                     : M[Int, Unit]
                def overloadedManaged(arg1: Int)                  : M[Int, String]
                def overloadedManaged(arg1: Long)                 : M[Int, String]
              }
            }

            object Check {
              val staticManaged                                 : ZManaged[Module.Service[URManaged] with Int, Nothing, String] = Module.staticManaged
              def zeroArgsManaged                               : ZManaged[Module.Service[URManaged] with Int, Nothing, Int]    = Module.zeroArgsManaged
              def zeroArgsTypedManaged[T]                       : ZManaged[Module.Service[URManaged] with Int, Nothing, T]      = Module.zeroArgsTypedManaged[T]
              def zeroArgsWithParensManaged()                   : ZManaged[Module.Service[URManaged] with Int, Nothing, Long]   = Module.zeroArgsWithParensManaged()
              def singleArgManaged(arg1: Int)                   : ZManaged[Module.Service[URManaged] with Int, Nothing, String] = Module.singleArgManaged(arg1)
              def multiArgsManaged(arg1: Int, arg2: Long)       : ZManaged[Module.Service[URManaged] with Int, Nothing, String] = Module.multiArgsManaged(arg1, arg2)
              def multiParamListsManaged(arg1: Int)(arg2: Long) : ZManaged[Module.Service[URManaged] with Int, Nothing, String] = Module.multiParamListsManaged(arg1)(arg2)
              def typedVarargsManaged[T](arg1: Int, arg2: T*)   : ZManaged[Module.Service[URManaged] with Int, Nothing, T]      = Module.typedVarargsManaged[T](arg1, arg2: _*)
              def commandManaged(arg1: Int)                     : ZManaged[Module.Service[URManaged] with Int, Nothing, Unit]   = Module.commandManaged(arg1)
              def overloadedManaged(arg1: Int)                  : ZManaged[Module.Service[URManaged] with Int, Nothing, String] = Module.overloadedManaged(arg1)
              def overloadedManaged(arg1: Long)                 : ZManaged[Module.Service[URManaged] with Int, Nothing, String] = Module.overloadedManaged(arg1)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for method capabilities") {
        assertZIO(typeCheck {
          """
            @accessibleMM[IO]
            object Module {
              trait Service[F[_, _]] {
                def function(arg1: Int) : String
              }
            }

            object Check {
              def function(arg1: Int) : ZIO[Module.Service[IO], Throwable, String] = Module.function(arg1)
            }
          """
        })(isRight(anything))
      },
      test("preserves type constructor co- and contravariance") {
        assertZIO(typeCheck {
          """
             @accessibleMM[URIO]
             object Module {
               trait Service[F[-_, +_]] {
                 val v: F[Any, Int]
                 final val vCov: F[Int, Any] = v
               }
             }

             object Check {
               def assertServiceVariant[S[F[-_, +_]]] = ()
               assertServiceVariant[Module.Service]

               val v: ZIO[Module.Service[URIO] with Any, Nothing, Int] =
                 Module.v

               val vCov: ZIO[Module.Service[URIO] with Int, Nothing, Any] =
                 Module.vCov
             }
          """
        })(isRight(anything))
      },
      test("preserves Service covariance") {
        assertZIO(typeCheck {
          """
             @accessibleMM[URIO]
             object Module {
               trait Service[+F[_, _]] {
                 val v: F[Any, Int]
               }

               val narrow: Service[URIO] = ???
               val widen: Service[RIO] = narrow
             }

             object Check {
               def assertServiceVariant[S[+F[_, _]]] = ()
               assertServiceVariant[Module.Service]

               val v: ZIO[Module.Service[URIO], Nothing, Int] =
                 Module.v
             }
          """
        })(isRight(anything))
      },
      test("preserves Service contravariance") {
        assertZIO(typeCheck {
          """
             @accessibleMM[URIO]
             object Module {
               trait Service[-F[_, _]] {
                 val v: F[Any, Int]
               }

               val narrow: Service[RIO] = ???
               val widen: Service[URIO] = narrow
             }

             object Check {
               def assertServiceVariant[S[-F[_, _]]] = ()
               assertServiceVariant[Module.Service]

               val v: ZIO[Module.Service[URIO], Nothing, Int] =
                 Module.v
             }
          """
        })(isRight(anything))
      },
      // this test mimics the situation when covariant type appears in contravariant position
      // in reality, the code will not compile due to true variance check, but in tests `c.typecheck` doesn't check it
      test("fails when contravariant type appears in covariant position") {
        assertZIO(typeCheck {
          """
             @accessibleMM[URIO]
             object Module {
               trait Service[F[+_, +_]] {
                 val v: F[Int, Int]
               }
             }

             object Check {
               val v: ZIO[Module.Service[URIO] with AnyVal, Nothing, Int] =
                 Module.v
             }
          """
        })(isLeft(anything))
      }
    )
  )
}
