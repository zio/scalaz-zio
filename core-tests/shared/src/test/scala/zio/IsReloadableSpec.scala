/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio

import zio.test._

@scala.annotation.experimental
object IsReloadableSpec extends ZIOSpecDefault {

  val spec = suite("IsReloadable")(
    suite("creates a reloadable service")(
      test("switches underlying service in runtime") {
        trait Foo { def bar: UIO[String] }

        val service1: Foo = new Foo { def bar = ZIO.succeed("zio1") }
        val service2: Foo = new Foo { def bar = ZIO.succeed("zio2") }
        for {
          ref       <- ScopedRef.make(service1)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res1      <- reloadable.bar
          _         <- ref.set(ZIO.succeed(service2))
          res2      <- reloadable.bar
        } yield assertTrue(res1 == "zio1" && res2 == "zio2")
      },
      test("the type out of the current lexical scope") {
        object foo {
          trait Foo { def bar: UIO[String] }
        }
        val service: foo.Foo = new foo.Foo { def bar = ZIO.succeed("zio") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[foo.Foo].reloadable(ref)
          res1      <- reloadable.bar
        } yield assertTrue(res1 == "zio")
      },
      test("multiple methods") {
        trait Foo {
          def bar: UIO[String]
          def baz: UIO[Int]
        }
        val service: Foo = new Foo {
          override def bar: UIO[String] = ZIO.succeed("zio")
          override def baz: UIO[Int]    = ZIO.succeed(1)
        }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res1      <- reloadable.bar
          res2      <- reloadable.baz
        } yield assertTrue(res1 == "zio" && res2 == 1)
      },
      test("trait with type parameter") {
        trait Foo[A] { def bar: UIO[A] }
        val service: Foo[String] = new Foo[String] { def bar = ZIO.succeed("baz") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo[String]].reloadable(ref)
          res       <- reloadable.bar
        } yield assertTrue(res == "baz")
      },
      test("trait with higher kinded type parameter") {
        trait Foo[F[_]] { def bar: UIO[F[String]] }
        val service: Foo[List] = new Foo[List] { def bar = ZIO.succeed("baz" :: Nil) }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo[List]].reloadable(ref)
          res       <- reloadable.bar
        } yield assertTrue(res == "baz" :: Nil)
      },
      test("abstract class") {
        abstract class Foo { def bar: UIO[String] }
        val service: Foo = new Foo { def bar = ZIO.succeed("zio") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar
        } yield assertTrue(res == "zio")
      },
      test("class") {
        class Foo { def bar: UIO[String] = ZIO.succeed("zio1") }
        val service: Foo = new Foo {
          override def bar = ZIO.succeed("zio2")
        }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar
        } yield assertTrue(res == "zio2")
      }
    ),
    suite("forwards")(
      test("methods with params") {
        trait Foo { def bar(a: String): UIO[Int] }
        val service: Foo = new Foo { def bar(a: String) = ZIO.succeed(a.length) }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar("zio")
        } yield assertTrue(res == 3)
      },
      test("generic methods") {
        trait Foo { def bar[A](a: A): UIO[A] }
        val service: Foo = new Foo { def bar[A](a: A) = ZIO.succeed(a) }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar[String]("zio")
        } yield assertTrue(res == "zio")
      },
      test("curried methods") {
        trait Foo { def bar(a: Int)(b: String): UIO[String] }
        val service: Foo = new Foo { def bar(a: Int)(b: String) = ZIO.succeed(b * a) }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar(3)("zio")
        } yield assertTrue(res == "zioziozio")
      },
      test("implicit clauses") {
        trait Foo { def bar(a: Int)(implicit b: String): UIO[String] }
        val service: Foo       = new Foo { def bar(a: Int)(implicit b: String) = ZIO.succeed(b * a) }
        implicit val b: String = "zio"
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar(3)
        } yield assertTrue(res == "zioziozio")
      },
      test("inherited abstract methods") {
        trait Foo0 { def bar(a: Int): UIO[String] }
        trait Foo extends Foo0

        val service: Foo = new Foo { def bar(a: Int) = ZIO.succeed("zio" * a) }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar(3)
        } yield assertTrue(res == "zioziozio")
      },
      test("overridden methods with default implementation") {
        trait Foo { def bar: UIO[String] = ZIO.succeed("zio1") }
        val service: Foo = new Foo { override def bar = ZIO.succeed("zio2") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar
        } yield assertTrue(res == "zio2")
      },
      test("package private methods") {
        trait Foo { private[zio] def bar: UIO[String] }
        val service: Foo = new Foo { private[zio] def bar: UIO[String] = ZIO.succeed("zio") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res       <- reloadable.bar
        } yield assertTrue(res == "zio")
      },
      test("ZIO vals") {
        trait Foo { val bar: UIO[String] }
        val service1: Foo = new Foo { val bar: UIO[String] = ZIO.succeed("zio1") }
        val service2: Foo = new Foo { val bar: UIO[String] = ZIO.succeed("zio2") }
        for {
          ref       <- ScopedRef.make(service1)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res1      <- reloadable.bar
          _         <- ref.set(ZIO.succeed(service2))
          res2      <- reloadable.bar
        } yield assertTrue(res1 == "zio1" && res2 == "zio2")
      },
      test("ZIO vals with default implementation") {
        trait Foo { val bar: UIO[String] = ZIO.succeed("zio") }
        val service: Foo = new Foo { override val bar: UIO[String] = ZIO.succeed("zio1") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
          res1      <- reloadable.bar
        } yield assertTrue(res1 == "zio1")
      },
      test("keeps non-ZIO default implementations") {
        trait Foo {
          def bar: UIO[String]
          def qux: String = "quux"
        }
        val service: Foo = new Foo { def bar = ZIO.succeed("baz") }
        for {
          ref       <- ScopedRef.make(service)
          reloadable = IsReloadable[Foo].reloadable(ref)
        } yield assertTrue(reloadable.qux == "quux")
      }
    ),
    suite("fails to compile")(
      test("non-ZIO abstract method") {
        for {
          res <- typeCheck(
                   """
        trait Foo { def qux: String }
        val service: Foo = new Foo { def qux = "quux" }
        for {
          ref <- ScopedRef.make(service)
        } yield IsReloadable[Foo].reloadable(ref)
        """
                 )
        } yield
          if (TestVersion.isScala2)
            assertTrue(res.swap.exists(_.contains("non-ZIO")))
          else
            assertTrue(res.isLeft)
      },
      test("classes/traits with non-empty primary constructor") {
        for {
          res <- typeCheck(
                   """
          class Foo(s: String) { def bar: UIO[String] = ZIO.succeed(s) }
          val service: Foo = new Foo("zio")
          for {
            ref       <- ScopedRef.make(service)
            reloadable = IsReloadable[Foo].reloadable(ref)
            res       <- reloadable.bar
          } yield res
          """
                 )
        } yield
          if (TestVersion.isScala2)
            assertTrue(res.swap.exists(_.contains("non-empty parameters")))
          else
            assertTrue(res.isLeft)
      },
      test("abstract type members") {
        for {
          res <- typeCheck(
                   """
            trait Foo {
              type T
              def bar: UIO[T]
            }

            val service: Foo = new Foo {
              type T = String
              def bar: UIO[String] = ZIO.succeed("zio")
            }
            for {
              ref       <- ScopedRef.make(service)
              reloadable = IsReloadable[Foo].reloadable(ref)
              res       <- reloadable.bar
            } yield assertTrue(res == "zio")
            """
                 )
        } yield
          if (TestVersion.isScala2)
            assertTrue(res.swap.exists(_.contains("Abstract")))
          else
            assertTrue(res.isLeft)
      }
    )
  )
}
