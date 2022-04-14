package zio.examples.macros

import zio.macros.{accessible, throwing}
import zio.stream.{ZSink, ZStream}
import zio._

object UpdatedAccessibleMacroExample {
  trait Foo { val value: String }
  case class Bar(value: String) extends Foo
  case class Wrapped[T](value: T)

  // Trait Without Companion Object
  @accessible
  trait FooBarService {
    val foo: UIO[Unit]
    def foo2: UIO[Unit]
    def foo3(): UIO[Unit]
    def bar(n: Int): UIO[Unit]
    def baz(x: Int, y: Int): IO[String, Int]
    def poly[A](a: A): IO[Long, A]
    def poly2[A <: Foo](a: Wrapped[A]): IO[String, List[A]]
    def dependent(n: Int): ZIO[Random, Long, Int]
    val value: String
    def value2: String
    def value3(): String
    def function(n: Int): String
    def stream(n: Int): ZStream[Any, String, Int]
    def sink(n: Int): ZSink[Any, Nothing, Int, Nothing, Chunk[Int]]
    @throwing
    def withEx(): String
    @throwing
    def withEx1(p: String): String
  }

  object FooBarSanityCheck {
    val _foo: URIO[FooBarService, Unit]                                        = FooBarService.foo
    def _foo2: URIO[FooBarService, Unit]                                       = FooBarService.foo2
    def _foo3(): URIO[FooBarService, Unit]                                     = FooBarService.foo3()
    def _bar(n: Int): URIO[FooBarService, Unit]                                = FooBarService.bar(n)
    def _baz(x: Int, y: Int): ZIO[FooBarService, String, Int]                  = FooBarService.baz(x, y)
    def _poly[A](a: A): ZIO[FooBarService, Long, A]                            = FooBarService.poly(a)
    def _poly2[A <: Foo](a: Wrapped[A]): ZIO[FooBarService, String, List[A]]   = FooBarService.poly2(a)
    def _dependent(n: Int): ZIO[FooBarService with Random, Long, Int]          = FooBarService.dependent(n)
    val _value: URIO[FooBarService, String]                                    = FooBarService.value
    def _value2: URIO[FooBarService, String]                                   = FooBarService.value2
    def _value3(): URIO[FooBarService, String]                                 = FooBarService.value3()
    def _function(n: Int): URIO[FooBarService, String]                         = FooBarService.function(n)
    def _stream(n: Int): ZStream[FooBarService, String, Int]                   = FooBarService.stream(n)
    def _sink(n: Int): ZSink[FooBarService, Nothing, Int, Nothing, Chunk[Int]] = FooBarService.sink(n)
    def _withEx(): RIO[FooBarService, String]                                  = FooBarService.withEx()
    def _withEx1(p: String): RIO[FooBarService, String]                        = FooBarService.withEx1(p)
  }

  // Trait With Companion Object
  @accessible
  trait CompanionService {
    val foo: UIO[Unit]
    def bar(n: Int): UIO[Unit]
    def baz(x: Int, y: Int): IO[String, Int]
    def poly[A](a: A): IO[Long, A]
    def poly2[A <: Foo](a: Wrapped[A]): IO[String, List[A]]
    def dependent(n: Int): ZIO[Random, Long, Int]
    val value: String
    def function(n: Int): String
    def stream(n: Int): ZStream[Any, String, Int]
    def sink(n: Int): ZSink[Any, Nothing, Int, Nothing, Chunk[Int]]
  }

  object CompanionService {
    def someExistingMethod(string: String): UIO[Int] = ZIO.succeed(string.length)
  }

  object CompanionSanityCheck {
    val _foo: URIO[CompanionService, Unit]                                      = CompanionService.foo
    def _bar(n: Int): URIO[CompanionService, Unit]                              = CompanionService.bar(n)
    def _baz(x: Int, y: Int): ZIO[CompanionService, String, Int]                = CompanionService.baz(x, y)
    def _poly[A](a: A): ZIO[CompanionService, Long, A]                          = CompanionService.poly(a)
    def _poly2[A <: Foo](a: Wrapped[A]): ZIO[CompanionService, String, List[A]] = CompanionService.poly2(a)
    def _dependent(n: Int): ZIO[CompanionService with Random, Long, Int]        = CompanionService.dependent(n)
    def _value: RIO[CompanionService, String]                                   = CompanionService.value
    def _function(n: Int): RIO[CompanionService, String]                        = CompanionService.function(n)
    def _stream(n: Int): ZStream[CompanionService, String, Int]                 = CompanionService.stream(n)
    def _sink(n: Int): ZSink[CompanionService, Nothing, Int, Nothing, Chunk[Int]] =
      CompanionService.sink(n)

    def _someExistingMethod(string: String): UIO[Int] = CompanionService.someExistingMethod(string)
  }
}

@accessible
object AccessibleMacroExample {
  type AccessibleMacroExample = AccessibleMacroExample.Service

  trait Foo { val value: String }
  case class Bar(value: String) extends Foo
  case class Wrapped[T](value: T)

  trait Service {
    val foo: UIO[Unit]
    def foo2: UIO[Unit]
    def foo3(): UIO[Unit]
    def bar(n: Int): UIO[Unit]
    def baz(x: Int, y: Int): IO[String, Int]
    def poly[A](a: A): IO[Long, A]
    def poly2[A <: Foo](a: Wrapped[A]): IO[String, List[A]]
    def dependent(n: Int): ZIO[Random, Long, Int]
    val value: String
    def value2: String
    def value3(): String
    def function(n: Int): String
    def stream(n: Int): ZStream[Any, String, Int]
    def sink(n: Int): ZSink[Any, Nothing, Int, Nothing, Chunk[Int]]
    @throwing
    def withEx(): String
    @throwing
    def withEx1(p: String): String
  }

  val live: ZLayer[Console, Nothing, Service] =
    ZLayer {
      ZIO
        .service[Console]
        .map(console =>
          new Service {
            val foo: UIO[Unit]                                              = UIO.unit
            def foo2: UIO[Unit]                                             = UIO.unit
            def foo3(): UIO[Unit]                                           = UIO.unit
            def bar(n: Int): UIO[Unit]                                      = console.printLine(s"bar $n").orDie
            def baz(x: Int, y: Int): IO[String, Int]                        = UIO.succeed(x + y)
            def poly[A](a: A): IO[Long, A]                                  = UIO.succeed(a)
            def poly2[A <: Foo](a: Wrapped[A]): IO[String, List[A]]         = UIO.succeed(List(a.value))
            def dependent(n: Int): ZIO[Random, Long, Int]                   = Random.nextIntBounded(n)
            val value: String                                               = "foo"
            def value2: String                                              = "foo2"
            def value3(): String                                            = "foo3"
            def function(n: Int): String                                    = s"foo $n"
            def stream(n: Int): ZStream[Any, String, Int]                   = ZStream.fromIterable(List(1, 2, 3))
            def sink(n: Int): ZSink[Any, Nothing, Int, Nothing, Chunk[Int]] = ZSink.collectAll
            def withEx(): String                                            = throw new Exception("test")
            def withEx1(p: String): String                                  = throw new Exception("test")
          }
        )
    }

  // can use accessors even in the same compilation unit
  val program: URIO[
    AccessibleMacroExample with Random,
    (Int, String, Long, List[Foo], Int, String, String, String, String, Chunk[Int], Chunk[Int], String, String)
  ] =
    for {
      _   <- AccessibleMacroExample.foo
      _   <- AccessibleMacroExample.foo2
      _   <- AccessibleMacroExample.foo3()
      _   <- AccessibleMacroExample.bar(1)
      v1  <- AccessibleMacroExample.baz(2, 3).orDieWith(_ => new Exception)
      v2  <- AccessibleMacroExample.poly("foo").orDieWith(_ => new Exception)
      v3  <- AccessibleMacroExample.poly(4L).orDieWith(_ => new Exception)
      v4  <- AccessibleMacroExample.poly2(Wrapped(Bar("bar"))).orDieWith(_ => new Exception)
      v5  <- AccessibleMacroExample.dependent(5).orDieWith(_ => new Exception)
      v6  <- AccessibleMacroExample.value
      v7  <- AccessibleMacroExample.value2
      v8  <- AccessibleMacroExample.value3()
      v9  <- AccessibleMacroExample.function(6)
      v10 <- AccessibleMacroExample.stream(7).runCollect.orDieWith(_ => new Exception)
      v11 <- ZStream(0).run(AccessibleMacroExample.sink(8))
      v12 <- AccessibleMacroExample.withEx().catchAll(_ => ZIO.succeed("test"))
      v13 <- AccessibleMacroExample.withEx1("").catchAll(_ => ZIO.succeed("test"))
    } yield (v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13)

  // sanity check
  val _foo: URIO[AccessibleMacroExample, Unit]                                      = AccessibleMacroExample.foo
  def _foo2: URIO[AccessibleMacroExample, Unit]                                     = AccessibleMacroExample.foo2
  def _foo3(): URIO[AccessibleMacroExample, Unit]                                   = AccessibleMacroExample.foo3()
  def _bar(n: Int): URIO[AccessibleMacroExample, Unit]                              = AccessibleMacroExample.bar(n)
  def _baz(x: Int, y: Int): ZIO[AccessibleMacroExample, String, Int]                = AccessibleMacroExample.baz(x, y)
  def _poly[A](a: A): ZIO[AccessibleMacroExample, Long, A]                          = AccessibleMacroExample.poly(a)
  def _poly2[A <: Foo](a: Wrapped[A]): ZIO[AccessibleMacroExample, String, List[A]] = AccessibleMacroExample.poly2(a)
  def _dependent(n: Int): ZIO[AccessibleMacroExample with Random, Long, Int]        = AccessibleMacroExample.dependent(n)
  val _value: URIO[AccessibleMacroExample, String]                                  = AccessibleMacroExample.value
  def _value2: URIO[AccessibleMacroExample, String]                                 = AccessibleMacroExample.value2
  def _value3(): URIO[AccessibleMacroExample, String]                               = AccessibleMacroExample.value3()
  def _function(n: Int): URIO[AccessibleMacroExample, String]                       = AccessibleMacroExample.function(n)
  def _stream(n: Int): ZStream[AccessibleMacroExample, String, Int]                 = AccessibleMacroExample.stream(n)
  def _sink(n: Int): ZSink[AccessibleMacroExample, Nothing, Int, Nothing, Chunk[Int]] =
    AccessibleMacroExample.sink(n)
  def _withEx(): RIO[AccessibleMacroExample, String]           = AccessibleMacroExample.withEx()
  def _withEx1(p: String): RIO[AccessibleMacroExample, String] = AccessibleMacroExample.withEx1(p)

  // macro autogenerates accessors for
  // `foo`, `foo2`, `foo3`, `bar`, `baz`, `poly`, `poly2`,
  // `value`, `value2`, `value3` and `function` below
}
