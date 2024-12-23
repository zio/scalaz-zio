package zio.macros

import zio._
import zio.managed._
import zio.test.Assertion._
import zio.test._

object AccessibleMSpec extends ZIOSpecDefault {

  def spec = suite("AccessibleMSpec")(
    suite("AccessibleM macro")(
      test("compiles when applied to object with empty Service") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_]]
            }
          """
        })(isRight(anything))
      },
      test("fails when applied to object without a Service") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module
          """
        })(isLeft(anything))
      },
      test("success when applied to trait") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            trait Module[F[_]]
          """
        })(isRight(anything))
      },
      test("fails when applied to class") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            class Module[F[_]]
          """
        })(isLeft(anything))
      },
      test("fails when applied to object with Service without type param") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service
            }
          """
        })(isLeft(anything))
      },
      test("fails when applied to Service with Service without suitable type param") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_, _]]
            }
          """
        })(isLeft(anything))
      },
      test("fails when applied to Service with Service with multiple suitable type params") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_], G[_]]
            }
          """
        })(isLeft(anything))
      },
      test("fails when applied to non-ZIO type param") {
        assertZIO(typeCheck {
          """
            @accessibleM[List]
            object Module {
              trait Service[F[_]]
            }
          """
        })(isLeft(anything))
      },
      test("generates accessor for values") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_]] {
                val foo: F[Unit]
              }
            }

            object Check {
              val foo: ZIO[Module.Service[UIO], Nothing, Unit] =
                Module.foo
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for functions") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_]] {
                def foo(i: Int): F[Unit]
              }
            }

            object Check {
              def foo(i: Int): ZIO[Module.Service[UIO], Nothing, Unit] =
                Module.foo(i)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for varargs functions") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_]] {
                def varargsFoo(a: Int, b: Int*): F[Unit]
              }
            }

            object Check {
              def varargsFoo(a: Int, b: Int*): ZIO[Module.Service[UIO], Nothing, Unit] =
                Module.varargsFoo(a, b: _*)
            }
          """
        })(isRight(anything))
      },
      test("compiles when applied to method with simple return type") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_]] {
                def foo(a: Int): Task[Unit]
              }
            }

            object Check {
              def foo(a: Int): ZIO[Module.Service[UIO], Throwable, Unit] =
                Module.foo(a)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for members returning ZManaged") {
        assertZIO(typeCheck {
          """
            @accessibleM[TaskManaged]
            object Module {
              trait Service[F[_]] {
                def managed(s: String): F[Int]
              }
            }

            object Check {
              def managed(s: String): ZManaged[Module.Service[TaskManaged], Throwable, Int] =
                Module.managed(s)
            }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with default method implementations") {
        assertZIO(typeCheck {
          """
             @accessibleM[Task]
             object Module {
               trait Service[F[_]] {
                 def foo(x: Int): F[Unit] = foo(x.toString)
                 def foo(x: String): F[Unit]
               }
             }

             object Check {
              def foo(x: Int): ZIO[Module.Service[Task], Throwable, Unit] =
                Module.foo(x)
              def foo(x: String): ZIO[Module.Service[Task], Throwable, Unit] =
                Module.foo(x)
            }
          """.stripMargin
        })(isRight(anything))
      },
      test("generates accessor for service with one type param other than F") {
        assertZIO(typeCheck {
          """
             @accessibleM[Task]
             object Module {
               trait Service[F[_], T] {
                 val v: F[T]
                 def f1: F[Unit]
                 def f2(): F[Unit]
                 def f3(t: T): F[Unit]
                 def f4(t: T)(i: Int): F[Unit]
                 def f5(t: T)(implicit i: Int): F[Unit]
                 def f6(t: T*): F[Unit]
               }
             }

             object Check {
               def v[T: Tag]: ZIO[Module.Service[Task, T], Throwable, T] =
                 Module.v[T]
               def f1[T: Tag]: ZIO[Module.Service[Task, T], Throwable, Unit] =
                 Module.f1[T]
               def f2[T: Tag](): ZIO[Module.Service[Task, T], Throwable, Unit] =
                 Module.f2[T]()
               def f3[T: Tag](t: T): ZIO[Module.Service[Task, T], Throwable, Unit] =
                 Module.f3[T](t)
               def f4[T: Tag](t: T)(i: Int): ZIO[Module.Service[Task, T], Throwable, Unit] =
                 Module.f4[T](t)(i)
               def f5[T: Tag](t: T)(implicit i: Int): ZIO[Module.Service[Task, T], Throwable, Unit] =
                 Module.f5[T](t)
               def f6[T: Tag](t: T*): ZIO[Module.Service[Task, T], Throwable, Unit] =
                 Module.f6[T](t: _*)
             }
          """
        })(isRight(anything))
      },
      test("generates accessor for service with covariant type param") {
        assertZIO(typeCheck {
          """
             @accessibleM[UIO]
             object Module {
               trait Service[+A, F[_]] {
                 val v: F[A]
               }
             }

             object Check {
               def v[A: Tag]: ZIO[Module.Service[A, UIO], Nothing, A] =
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

             @accessibleM[Task]
             object Module {
               trait Service[T <: Foo, M[_], U >: Bar] {
                 val v: M[T]
                 def f1: M[U]
                 def f2(): M[U]
                 def f3(t: T): M[U]
                 def f4(t: T)(u: U): M[U]
                 def f5(t: T)(implicit u: U): M[U]
                 def f6(t: T*): M[U]
               }
             }

             object Check {
               def v[T <: Foo: Tag, U >: Bar: Tag]: ZIO[Module.Service[T, Task, U], Throwable, T] =
                 Module.v[T, U]
               def f1[T <: Foo: Tag, U >: Bar: Tag]: ZIO[Module.Service[T, Task, U], Throwable, U] =
                 Module.f1[T, U]
               def f2[T <: Foo: Tag, U >: Bar: Tag](): ZIO[Module.Service[T, Task, U], Throwable, U] =
                 Module.f2[T, U]()
               def f3[T <: Foo: Tag, U >: Bar: Tag](t: T): ZIO[Module.Service[T, Task, U], Throwable, U] =
                 Module.f3[T, U](t)
               def f4[T <: Foo: Tag, U >: Bar: Tag](t: T)(u: U): ZIO[Module.Service[T, Task, U], Throwable, U] =
                 Module.f4[T, U](t)(u)
               def f5[T <: Foo: Tag, U >: Bar: Tag](t: T)(implicit u: U): ZIO[Module.Service[T, Task, U], Throwable, U] =
                 Module.f5[T, U](t)
               def f6[T <: Foo: Tag, U >: Bar: Tag](t: T*): ZIO[Module.Service[T, Task, U], Throwable, U] =
                 Module.f6[T, U](t: _*)
             }
          """
        })(isRight(anything))
      },
      test("generates accessors for ZIO capabilities") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[M[_]] {
                val static                                 : M[String]
                def zeroArgs                               : M[Int]
                def zeroArgsWithParens()                   : M[Long]
                def singleArg(arg1: Int)                   : M[String]
                def multiArgs(arg1: Int, arg2: Long)       : M[String]
                def multiParamLists(arg1: Int)(arg2: Long) : M[String]
                def typedVarargs[T](arg1: Int, arg2: T*)   : M[T]
                def command(arg1: Int)                     : M[Unit]
                def overloaded(arg1: Int)                  : M[String]
                def overloaded(arg1: Long)                 : M[String]
              }
            }

            object Check {
              val static                                 : ZIO[Module.Service[UIO], Nothing, String] = Module.static
              def zeroArgs                               : ZIO[Module.Service[UIO], Nothing, Int]    = Module.zeroArgs
              def zeroArgsWithParens()                   : ZIO[Module.Service[UIO], Nothing, Long]   = Module.zeroArgsWithParens()
              def singleArg(arg1: Int)                   : ZIO[Module.Service[UIO], Nothing, String] = Module.singleArg(arg1)
              def multiArgs(arg1: Int, arg2: Long)       : ZIO[Module.Service[UIO], Nothing, String] = Module.multiArgs(arg1, arg2)
              def multiParamLists(arg1: Int)(arg2: Long) : ZIO[Module.Service[UIO], Nothing, String] = Module.multiParamLists(arg1)(arg2)
              def typedVarargs[T](arg1: Int, arg2: T*)   : ZIO[Module.Service[UIO], Nothing, T]      = Module.typedVarargs[T](arg1, arg2: _*)
              def command(arg1: Int)                     : ZIO[Module.Service[UIO], Nothing, Unit]   = Module.command(arg1)
              def overloaded(arg1: Int)                  : ZIO[Module.Service[UIO], Nothing, String] = Module.overloaded(arg1)
              def overloaded(arg1: Long)                 : ZIO[Module.Service[UIO], Nothing, String] = Module.overloaded(arg1)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for ZManaged capabilities") {
        assertZIO(typeCheck {
          """
            @accessibleM[UManaged]
            object Module {
              trait Service[M[_]] {
                val staticManaged                                 : M[String]
                def zeroArgsManaged                               : M[Int]
                def zeroArgsTypedManaged[T]                       : M[T]
                def zeroArgsWithParensManaged()                   : M[Long]
                def singleArgManaged(arg1: Int)                   : M[String]
                def multiArgsManaged(arg1: Int, arg2: Long)       : M[String]
                def multiParamListsManaged(arg1: Int)(arg2: Long) : M[String]
                def typedVarargsManaged[T](arg1: Int, arg2: T*)   : M[T]
                def commandManaged(arg1: Int)                     : M[Unit]
                def overloadedManaged(arg1: Int)                  : M[String]
                def overloadedManaged(arg1: Long)                 : M[String]
              }
            }

            object Check {
              val staticManaged                                 : ZManaged[Module.Service[UManaged], Nothing, String] = Module.staticManaged
              def zeroArgsManaged                               : ZManaged[Module.Service[UManaged], Nothing, Int]    = Module.zeroArgsManaged
              def zeroArgsTypedManaged[T]                       : ZManaged[Module.Service[UManaged], Nothing, T]      = Module.zeroArgsTypedManaged[T]
              def zeroArgsWithParensManaged()                   : ZManaged[Module.Service[UManaged], Nothing, Long]   = Module.zeroArgsWithParensManaged()
              def singleArgManaged(arg1: Int)                   : ZManaged[Module.Service[UManaged], Nothing, String] = Module.singleArgManaged(arg1)
              def multiArgsManaged(arg1: Int, arg2: Long)       : ZManaged[Module.Service[UManaged], Nothing, String] = Module.multiArgsManaged(arg1, arg2)
              def multiParamListsManaged(arg1: Int)(arg2: Long) : ZManaged[Module.Service[UManaged], Nothing, String] = Module.multiParamListsManaged(arg1)(arg2)
              def typedVarargsManaged[T](arg1: Int, arg2: T*)   : ZManaged[Module.Service[UManaged], Nothing, T]      = Module.typedVarargsManaged[T](arg1, arg2: _*)
              def commandManaged(arg1: Int)                     : ZManaged[Module.Service[UManaged], Nothing, Unit]   = Module.commandManaged(arg1)
              def overloadedManaged(arg1: Int)                  : ZManaged[Module.Service[UManaged], Nothing, String] = Module.overloadedManaged(arg1)
              def overloadedManaged(arg1: Long)                 : ZManaged[Module.Service[UManaged], Nothing, String] = Module.overloadedManaged(arg1)
            }
          """
        })(isRight(anything))
      },
      test("generates accessors for method capabilities") {
        assertZIO(typeCheck {
          """
            @accessibleM[UIO]
            object Module {
              trait Service[F[_]] {
                def function(arg1: Int) : String
              }
            }

            object Check {
              def function(arg1: Int) : ZIO[Module.Service[UIO], Throwable, String] = Module.function(arg1)
            }
          """
        })(isRight(anything))
      },
      test("preserves type constructor covariance") {
        assertZIO(typeCheck {
          """
             @accessibleM[UIO]
             object Module {
               trait Service[F[+_]] {
                 val v: F[Int]
                 final val vCov: F[Any] = v
               }
             }

             object Check {
               def assertServiceVariant[S[F[+_]]] = ()
               assertServiceVariant[Module.Service]

               val v: ZIO[Module.Service[UIO], Nothing, Int] =
                 Module.v

               val vCov: ZIO[Module.Service[UIO], Nothing, Any] =
                 Module.vCov
             }
          """
        })(isRight(anything))
      },
      test("preserves Service covariance") {
        assertZIO(typeCheck {
          """
             @accessibleM[UIO]
             object Module {
               trait Service[+F[_]] {
                 val v: F[Int]
               }

               val narrow: Service[UIO] = ???
               val widen: Service[Task] = narrow
             }

             object Check {
               def assertServiceVariant[S[+F[_]]] = ()
               assertServiceVariant[Module.Service]

               val v: ZIO[Module.Service[UIO], Nothing, Int] =
                 Module.v
             }
          """
        })(isRight(anything))
      },
      test("preserves Service contravariance") {
        assertZIO(typeCheck {
          """
             @accessibleM[UIO]
             object Module {
               trait Service[-F[_]] {
                 val v: F[Int]
               }

               val narrow: Service[Task] = ???
               val widen: Service[UIO] = narrow
             }

             object Check {
               def assertServiceVariant[S[-F[_]]] = ()
               assertServiceVariant[Module.Service]

               val v: ZIO[Module.Service[UIO], Nothing, Int] =
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
             @accessibleM[UIO]
             object Module {
               trait Service[F[-_]] {
                 val v: F[AnyVal]
               }
             }

             object Check {
               val v: ZIO[Module.Service[UIO], Nothing, Int] =
                 Module.v
             }
          """
        })(isLeft(anything))
      }
    )
  )
}
