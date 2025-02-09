package zio

import zio.test._

object ZEnvironmentSpec extends ZIOBaseSpec {

  def spec = suite("ZEnvironmentSpec")(
    test("getting from an empty environment should succeed") {
      for {
        _ <- ZIO.succeed(ZEnvironment.empty.get)
      } yield assertCompletes
    },
    test("supertypes are removed from the cache when a newer subtype is added") {
      sealed trait Foo
      case class Foo1() extends Foo
      case class Foo2() extends Foo

      val foo: Foo   = Foo1(): Foo
      val foo1: Foo1 = Foo1()
      val foo2: Foo2 = Foo2()
      val foo3: Foo  = Foo2(): Foo

      var env: ZEnvironment[Foo & Foo1 & Foo2] = ZEnvironment.empty.asInstanceOf[ZEnvironment[Foo & Foo1 & Foo2]]
      env = env.add(foo)
      val f0 = env.get[Foo] // Should be foo

      env = env.add(foo1)
      val f1 = env.get[Foo] // Should be foo1

      env = env.add(foo2)
      val f2 = env.get[Foo]  // Should be foo2
      val f3 = env.get[Foo1] // Should be foo1
      val f4 = env.get[Foo2] // Should be foo2

      env = env.add(foo3)
      val f5 = env.get[Foo]  // Should be foo3
      val f6 = env.get[Foo1] // Should be foo1
      val f7 = env.get[Foo2] // Should still be foo2 because foo3 is a supertype of foo2

      assertTrue(
        f0 eq foo,
        f1 eq foo1,
        f2 eq foo2,
        f3 eq foo1,
        f4 eq foo2,
        f5 eq foo3,
        f6 eq foo1,
        f7 eq foo2
      )
    },
    test("pruning a union on a subtype that implements multiple services") {
      trait Foo
      trait Bar
      final class FooBar extends Foo with Bar

      val env    = ZEnvironment(new FooBar)
      val pruned = env.prune[Foo & Bar]

      assertTrue(env == pruned)
    },
    test("get[Any] on an empty ZEnvironment returns Unit") {
      val value = ZEnvironment.empty.get[Any]
      assertTrue(value.isInstanceOf[Unit])
    },
    test("equality") {
      val env1 = ZEnvironment("foo", 42)
      val env2 = ZEnvironment("foo", 42)
      assertTrue(env1 == env2)
    },
    test("equality takes into account order") {
      val env1 = ZEnvironment("foo", 42)
      val env2 = ZEnvironment(42, "foo")
      assertTrue(env1 != env2)
    },
    test("hashCode") {
      val env1 = ZEnvironment("foo", 42)
      val env2 = ZEnvironment("foo", 42)
      assertTrue(env1.hashCode == env2.hashCode)
    },
    test("hashCode takes into account order") {
      val env1 = ZEnvironment("foo", 42)
      val env2 = ZEnvironment(42, "foo")
      assertTrue(env1.hashCode != env2.hashCode)
    },
    test("diff on two equal environments should return an empty patch") {
      val env1  = ZEnvironment("foo", 42)
      val env2  = ZEnvironment("foo", 42)
      val patch = ZEnvironment.Patch.diff(env1, env2)
      assertTrue(patch.isEmpty)
    }
  )
}
