---
id: dependency-injection-in-zio
title: "Getting Started With Dependency Injection in ZIO"
sidebar_label: "Getting Started"
---

:::caution
In this page, we will focus on essential parts of dependency injection in ZIO. So in some examples we are not going to cover all the best practices for writing ZIO services.

In real world applications, we encourage to use [service pattern](../service-pattern/service-pattern.md) to write ZIO services.
:::

## Essential Steps of Dependency Injection in ZIO

We can achieve dependency injection through these three simple steps:

1. Accessing services from the ZIO environment through the `ZIO.serviceXYZ` operations.
2. Writing application logic using services and composing them together.
3. Building the dependency graph using manual or automatic layer construction (optional).
4. Providing dependencies to the ZIO environment through the `ZIO.provideXYZ` operations.

### Step 1: Accessing Services From The ZIO Environment

To write application logic, we need to access services from the ZIO environment. We can do this by using the `ZIO.serviceXYZ` operation.

For example, assume we have the following services:

```scala mdoc:silent
import zio._

final class A {
  def foo: UIO[String] = ZIO.succeed("Hello!")
}

final class B {
  def bar: UIO[Int] = ZIO.succeed(42)
}
```

When we call `ZIO.service[A]`, we are asking the ZIO environment for the `A` service. So then we can access all the functionality of the `A` service:

```scala mdoc:compile-only
val effect: ZIO[A, Nothing, String] =
  for {
    a <- ZIO.service[A] 
    r <- a.foo
  } yield r
```

The signature of the above effect, says that in order to produce a value of type `String`, I need the `A` service from the ZIO environment.

We can also use `ZIO.serviceWith`/`ZIO.srviceWithZIO` to directly access one of the service functionalities:

```scala mdoc:silent
object A {
  def foo: ZIO[A, Nothing, String] = ZIO.serviceWithZIO[A](_.foo) 
}

object B {
  def bar: ZIO[B, Nothing, Int] = ZIO.serviceWithZIO[B](_.bar)
}
```

### Step 2: Writing Application Logic Using Services

ZIO is a composable data type on its environment type parameter. So when we have an effect that requires the `A` service, and also we have another effect that requires the `B` service; when we compose these two services together, the resulting effect requires both `A` and `B` services:

```scala mdoc:silent
// Sequential Composition Example
val myApp: ZIO[A with B, Nothing, (String, Int)] =
  for {
    a <- A.foo
    b <- B.bar
  } yield (a, b)
```

```scala mdoc:silent:nest
// Parallel Composition Example
val myApp: ZIO[A with B, Nothing, (String, Int)] = A.foo <&> B.bar
```

Now the `myApp` effect requires `A` and `B` services to fulfill its functionality. We can see that we are writing application logic, we are not concerned about how services will be created! We are focused on using services to write the application logic.

In the next step, we are going to build a dependency graph that holds two `A` and `B` services.

### Step 3: Building The Dependency Graph (Optional)

To be able to run our application, we need to build the dependency graph that it needs. This can be done using the `ZLayer` data type. It allows us to build up the whole application's dependency graph by composing layers manually or automatically.

Assume each of these services has its own layer like the below:

```scala mdoc:silent:nest
object A {
  def foo: ZIO[A, Nothing, String] = 
    ZIO.serviceWithZIO[A](_.foo) 
  
  val layer: ZLayer[Any, Nothing, A] = 
    ZLayer.succeed(new A) 
}

object B {
  def bar: ZIO[B, Nothing, Int] = 
    ZIO.serviceWithZIO[B](_.bar)
  
  val layer: ZLayer[Any, Nothing, B] = 
    ZLayer.succeed(new B)
}
```

In the previous example, the `myApp` application requires the `A` and `B` services. We can build that manually by composing two `A` and `B` layers horizontally:

```scala mdoc:silent
val appLayer: ZLayer[Any, Nothing, A with B] = 
  A.layer ++ B.layer
```

Or we can use automatic layer construction:

```scala mdoc:compile-only
val appLayer: ZLayer[Any, Nothing, A with B] =
  ZLayer.make[A with B](A.layer, B.layer) 
```

:::note
Automatic layer construction is useful when the dependency graph is large and complex. So in simple cases, it doesn't demonstrate the power of automatic layer construction.
:::

### Step 4: Providing Dependencies to the ZIO Environment

To run our application, we need to provide (inject) all dependencies to the ZIO environment. This can be done by using one of the `ZIO.provideXYZ` operations. This allows us to propagate dependencies from button to top:

Let's provide our application with the `appLayer`:

```scala mdoc:silent
val result: ZIO[Any, Nothing, (String, Int)] = myApp.provideLayer(appLayer)
```

Here the `ZLayer` data types act as a dependency/environment eliminator. By providing required dependencies to our ZIO application, `ZLayer` eliminates all dependencies from the environment of our application.

That's it! Now we can run our application:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = result
}
```

Usually, when we use automatic layer construction, we skip the second step and instead provide all dependencies directly to the `ZIO.provide` operation. It takes care of building the dependency graph and providing the dependency graph to our ZIO application:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = myApp.provide(A.layer, B.layer)
}
```

```scala mdoc:invisible:reset

```

## Dependency Injection and Service Pattern

### Dependency Injection When Writing Services

When writing services, we might want to use other services. In such cases, we would like dependent services injected into our service. This is where we need to use dependency injection in order to write services.

In ZIO, when we write services, we use class constructors to pass dependencies to the service. This is similar to the object-oriented style.

For example, assume we have written the following `A` and `B` services:


```scala mdoc:silent
import zio._

final class A {
  def foo: ZIO[Any, Nothing, String] = ZIO.succeed("Hello!")
}

object A {
  def foo: ZIO[A, Nothing, String] = ZIO.serviceWithZIO[A](_.foo)

  val layer: ZLayer[Any, Nothing, A] = ZLayer.succeed(new A)
}

final class B {
  def bar: ZIO[Any, Nothing, Int] = ZIO.succeed(42)
}

object B {
  def bar: ZIO[B, Nothing, Int] = ZIO.serviceWithZIO[B](_.bar)

  val layer: ZLayer[Any, Nothing, B] = ZLayer.succeed(new B)
}
```

In order to write a service that depends on `A` and `B` services, we use the class constructor to pass dependencies to our service:

```scala mdoc:silent
final case class C(a: A, b: B) {
  def baz: ZIO[Any, Nothing, Unit] =
    for {
      _ <- a.foo
      _ <- b.bar
    } yield ()
}
```

To write a layer for our `C` service, we can use `ZIO.service` to access dependent services effectfully and pass them to the service's constructor:

```scala mdoc:silent
object C {
  def baz: ZIO[C, Nothing, Unit] = ZIO.serviceWithZIO[C](_.baz)

  val layer: ZLayer[A with B, Nothing, C] =
    ZLayer {
      for {
        a <- ZIO.service[A]
        b <- ZIO.service[B]
      } yield C(a, b)
    }
}
```

Now, assume we have the following application logic:

```scala mdoc:silent
val myApp: ZIO[A with B with C, Nothing, Unit] =
  for {
    _ <- A.foo
    _ <- C.baz
  } yield ()
```

In order to run the application, we should provide the `A`, `B` and `C` services:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = myApp.provide(A.layer, B.layer, C.layer)
}
```

```scala mdoc:invisible:reset

```

### Dependency Injection When Writing Services Using Interfaces

Although dependency injection is not about coding to the interface, it is a good pattern to have testable and configurable programs. 

When we code to the interface, our application logic will not dependent on any concrete implementation. So we can replace implementations, without changing the application. This is what [Service Pattern](../service-pattern/service-pattern.md) encourages us when writing services.

Let's try an example. Assume we want to implement service `C` which is implemented in terms of `A` and `B` services. We want to keep our code modular and testable.

The first step is to define interfaces for each service. This gives us the contract for how our services work together and lets us figure out our architecture and divide and conquer:

```scala mdoc:silent
import zio._

trait A {
  def foo: ZIO[Any, Nothing, Int]
}

trait B {
  def bar: ZIO[Any, Nothing, String]
}

trait C {
  def baz: ZIO[Any, Nothing, Unit]
}
```

The next step is to create implementations of our services taking their dependencies as constructor parameters. It's just constructor-based dependency injection:

```scala mdoc:silent
final case class ALive() extends A {
  def foo = ZIO.succeed(42)
}

final case class BLive() extends B {
  def bar: ZIO[Any, Nothing, String] = ZIO.succeed("Hello!")
}

final case class CLive(a: A, b: B) extends C {
  def baz: ZIO[Any, Nothing, Unit] =
    for {
      _ <- a.foo
      _ <- b.bar
    } yield ()
}
```

Now, we need to create layers for each of our implementations. This lets ZIO automatically wire them together. It also lets us take care of any setup or tear down. We use `ZIO.service` to grab things from the environment:

```scala modc:silent
import zio._

object ALive {
  val layer: ZLayer[Any, Nothing, ALive] = ZLayer.succeed(ALive())
}

object BLive {
  val layer: ZLayer[Any, Nothing, BLive] = ZLayer.succeed(BLive())
}

object CLive {
  val layer: ZLayer[B with A, Nothing, CLive] =
    ZLayer {
      for {
        a <- ZIO.service[A]
        b <- ZIO.service[B]
      } yield CLive(a, b)
    }
}
```

Finally, it is time to write our application logic in terms of our services.  We use `ZIO.service` once more in our main application to actually access the service that contains our main application logic and call it:

```scala mdoc:silent
import zio._

val myApp: ZIO[A with C, Nothing, Unit] =
  for {
    a <- ZIO.service[A]
    _ <- a.foo
    c <- ZIO.service[C]
    _ <- c.baz
  } yield ()
```

Now, in order to run our application, we wire all of our services together with `ZIO#provide` and inject them to our application:

```scala
import zio._

object MainApp extends ZIOAppDefault {
  def run = myApp.provide(
    ALive.layer,
    BLive.layer,
    CLive.layer
  )
}
```

For any purpose, if we decided to use another implementation for the `A` service, we can replace it easily without changing our application logic:

```scala
import zio._

final case class ACustom() extends A {
  def foo = ZIO.succeed(84)
}

object ACustom {
  val layer: ZLayer[Any, Nothing, A] = ZLayer.succeed(ACustom())
}

object MainApp extends ZIOAppDefault {
  def run = myApp.provide(
    ACustom.layer,
    BLive.layer,
    CLive.layer
  )
}
```

## Conclusion

Following is a summary of some essential points when using dependency injection in ZIO:

1. For each service, we should write a layer that contains the recipe for creating the service.
2. We use class constructors to pass dependencies to our services. So inside the service, it is not idiomatic to use `ZIO.service` to access dependent services.
3. When writing a layer for a service that is dependent on other services, we use `ZIO.service` to access required services from the environment and then pass them to the service's constructor.
4. We use layers to compose and wire them together to create the dependency graph.
