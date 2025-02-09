---
id: sharing-layers-between-multiple-files
title: "Sharing Layers Between Multiple Files"
---

In the previous example, we used the `Spec#provideXYZShared` methods to share layers between multiple specs in one file. In most cases, when the number of tests and specs grows, this is not a good idea. We want a way to share layers between multiple specs in different files.

So in such situations, we can't use the previous pattern here, because specs are in entirely different files, and we don't want the boilerplate of creating a _master spec_ that references the other specs. ZIO has a solution to this problem. We can define the resource we want to share as part of the `bootstrap` layer of `ZIOApp`.

The `bootstrap` layer is responsible for creating and managing any services that our ZIO tests need. Using `boostrap` we can have one shared layer across multiple test specs.

:::note
When layers of the same type are defined at the class level within different test suites, they will be initialized separately for each test suite. This can lead to resource exhaustion especially with limited resources like database connections or connection pools. To avoid this, we need to ensure that such layers are defined centrally such as in the companion object to be shared properly across multiple test suites.
:::

Before we start, let's remember the `Counter` service in the previous [section](sharing-layers-within-the-same-file.md):

```scala mdoc:silent
import zio._

case class Counter(value: Ref[Int]) {
  def inc: UIO[Unit] = value.update(_ + 1)
  def get: UIO[Int] = value.get
}

object Counter {
  val layer =
    ZLayer.scoped(
      ZIO.acquireRelease(
        Ref.make(0).map(Counter(_)) <* ZIO.debug("Counter initialized!")
      )(c => c.get.debug("Number of tests executed"))
    )
  def inc = ZIO.service[Counter].flatMap(_.inc)
}
```

Now, let's assume we have two specs in different files, and we want to share the `Counter` service between them. First, we need to create a base class that contains the shared bootstrap layer:

```scala mdoc:silent
import zio._
import zio.test._

abstract class SharedCounterSpec extends ZIOSpec[Counter] {
  override val bootstrap: ZLayer[Any, Nothing, Counter] = Counter.layer
}
```

Now it's time to create the specs. Each spec is extending the `SharedCounterSpec` class.

Spec1.scala:

```scala mdoc:compile-only
import zio._
import zio.test._

object Spec1 extends SharedCounterSpec {
  override def spec =
    test("test1") {
      assertTrue(true)
    } @@ TestAspect.after(Counter.inc)
}
```

Spec2.scala:

```scala mdoc:compile-only
import zio._
import zio.test._

object Spec2 extends SharedCounterSpec {
  override def spec: Spec[Scope with Counter, Any] =
    test("test2") {
      assertTrue(true)
    } @@ TestAspect.after(Counter.inc)
}
```

Now, when we run all specs (`sbt testOnly org.example.*`), we will see an output like this:

```
Counter initialized!
+ test1
+ test2
Number of tests executed: 2
```

The ZIO test runner will execute all specs with the shared bootstrap layer. This means that the `Counter` service will be created and managed only once, and will be shared between all specs.

