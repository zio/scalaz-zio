---
id: ref
title: "Ref"
---

`Ref[A]` models a **mutable reference** to a value of type `A` in which we can store **immutable** data. The two basic operations are `set`, which fills the `Ref` with a new value, and `get`, which retrieves its current content.

`Ref` provides us a way to functionally manage in-memory state. All operations on `Ref` are atomic and thread-safe, giving us a reliable foundation for synchronizing concurrent programs.

`Ref`:
- is purely functional and referentially transparent
- is concurrent-safe and lock-free
- updates and modifies atomically

## Concurrent Stateful Application
**`Ref` is the foundation for writing concurrent stateful applications**. Anytime we need to share information between multiple fibers, and those fibers have to update the same information, they need to communicate through something that provides the guarantee of atomicity. Because `Ref` is **concurrent-safe**, we can share the same `Ref` among many fibers. All of which can update `Ref` concurrently, removing the worry of race conditions. Even if we had ten thousand fibers all updating the same `Ref`, as long as they are using atomic update and modify functions, we will have zero race conditions.


## Operations
Though `Ref` has many operations, here we will introduce the most common and important ones.

### make
`Ref` is never empty, it always contains something. We can create a `Ref` by providing the initial value to its `make` method, a constructor of the `Ref` data type. We should pass an **immutable value** of type `A` to the constructor, and it returns an `UIO[Ref[A]]` value:

```scala mdoc:invisible
import zio._
```

```scala
def make[A](a: A): UIO[Ref[A]]
```

As we can see, the output is wrapped in`UIO`, which means creating a `Ref` is effectful. Whenever we `make`, `update`, or `modify` the `Ref`, we are performing an effectful operation.

Let's create some `Ref`s from immutable values:

```scala mdoc
val counterRef = Ref.make(0)
val stringRef = Ref.make("initial") 

sealed trait State
case object Active  extends State
case object Changed extends State
case object Closed  extends State

val stateRef = Ref.make(Active) 
```

> _**Warning**_:  
>
> A big mistake when creating a `Ref` is trying to store mutable data inside it. A`Ref` must be used with **immutable data**. Otherwise, we lose our atomic guarantees, which can lead to collisions and race conditions. 

The following snippet compiles, but it leads to race conditions due to a mutable variable being provided to `make`:

```scala mdoc:nest
// Compiles but don't work properly
val init = collection.mutable.Seq(1,3,5)
val counterRef = Ref.make(init)
```

To correct this, we should change the `init` to be immutable:

```scala mdoc:nest
val init = Seq(1,3,5)
val counterRef = Ref.make(init)
```

### get
The `get` method returns the current value of the reference. Its return type is `IO[EB, B]` in which `B` is the value type of the effect and in the failure case, `EB` is the error type of that effect.

```scala
def get: IO[EB, B]
```

As the `make` and `get` methods of `Ref` are effectful, we can chain them together with `flatMap`. In the following example, we create a `Ref` with `initial` value, and then we acquire the current state with the `get` method:

```scala mdoc:silent
Ref.make("initial")
   .flatMap(_.get)
   .flatMap(current => Console.printLine(s"current value of ref: $current"))
```

We can refactor this to use a for-comprehension rather than a series of `flatMap`s to increase readability:

```scala mdoc:silent
for {
  ref   <- Ref.make("initial")
  value <- ref.get
} yield assert(value == "initial")
```

Note that, there is no way to access the shared state outside the monadic operations.

### set
The `set` method atomically writes a new value to the `Ref`.

```scala mdoc:silent
for {
  ref   <- Ref.make("initial")
  _     <- ref.set("update")
  value <- ref.get
} yield assert(value == "update")
```

### update
With `update`, we can atomically update the state of `Ref` with a given **pure** function, that is, it needs to be deterministic and free of side effects.

```scala
def update(f: A => A): IO[E, Unit]
```

Assume we have a counter, we can increase its value with the `update` method:

```scala mdoc:silent:nest
val counterInitial = 0
for {
  counterRef <- Ref.make(counterInitial)
  _          <- counterRef.update(_ + 1)
  value <- counterRef.get
} yield assert(value == 1)
```

:::caution

`update` is not the composition of `get` and `set`. This composition is not concurrent-safe. Whenever we need to update our state, we should use the `update` operation which modifies its `Ref` atomically. 
:::

For example, the following snippet is not concurrent-safe:

```scala mdoc:compile-only
// Unsafe State Management
object UnsafeCountRequests extends ZIOAppDefault {

  def request(counter: Ref[Int]) = for {
    current <- counter.get
    _ <- counter.set(current + 1)
  } yield ()

  private val initial = 0
  private val myApp =
    for {
      ref <- Ref.make(initial)
      _ <- request(ref) zipPar request(ref)
      rn <- ref.get
      _ <- Console.printLine(s"total requests performed: $rn")
    } yield ()

  def run = myApp
}
```

The above snippet doesn't behave deterministically. This program sometimes prints `2` and sometimes prints `1`. We can fix it by using `update`:

```scala mdoc:compile-only
// Safe State Management
object CountRequests extends ZIOAppDefault {

  def request(counter: Ref[Int]): ZIO[Any, Nothing, Unit] = {
    for {
      _ <- counter.update(_ + 1)
      reqNumber <- counter.get
      _ <- Console.printLine(s"request number: $reqNumber").orDie
    } yield ()
  }

  private val initial = 0
  private val myApp =
    for {
      ref <- Ref.make(initial)
      _ <- request(ref) zipPar request(ref)
      rn <- ref.get
      _ <- Console.printLine(s"total requests performed: $rn").orDie
    } yield ()

  def run = myApp
}
```

Here is another use case of `update` to write a `repeat` combinator:

```scala mdoc:silent
def repeat[E, A](n: Int)(io: IO[E, A]): IO[E, Unit] =
  Ref.make(0).flatMap { iRef =>
    def loop: IO[E, Unit] = iRef.get.flatMap { i =>
      if (i < n)
        io *> iRef.update(_ + 1) *> loop
      else
        ZIO.unit
    }
    loop
  }
```

### modify
`modify` is a more powerful version of `update`. It atomically modifies `Ref` by the given function, and also computes a return value. The function that we pass to `modify` needs to be a pure function; it needs to be deterministic and free of side effects.

```scala
def modify[B](f: A => (B, A)): IO[E, B]
```

Remember the `CountRequest` example. What if we want to log the number of each request inside the `request` function? Let's see what happens if we write that function with the composition of `update` and `get` methods:

```scala mdoc:silent:nest
// Unsafe in Concurrent Environment
def request(counter: Ref[Int]) = {
  for {
    _  <- counter.update(_ + 1)
    rn <- counter.get
    _  <- Console.printLine(s"request number received: $rn")
  } yield ()
}
```
What happens if, between running `update` and `get`, a second `update` occurs on another fiber? This would not behave deterministically in concurrent environments. So we need a way to perform a combination of **get, set, get** atomically. This is where `modify` comes in. Here we will edit `request` to use `modify`:

```scala mdoc:silent:nest
// Safe in Concurrent Environment
def request(counter: Ref[Int]) = {
  for {
    rn <- counter.modify(c => (c + 1, c + 1))
    _  <- Console.printLine(s"request number received: $rn")
  } yield ()
}
```

## AtomicReference in Java 
For Java programmers, we can think of `Ref` as an `AtomicReference`. Java has a `java.util.concurrent.atomic` package which contains `AtomicReference`, `AtomicLong`, `AtomicBoolean` and so forth. `Ref` has roughly the same power, guarantees, and limitations as `AtomicReference`, but is higher-level and ZIO-friendly. 
 
## Ref vs. State Monad
Basically `Ref` allows us to have all the power of State Monad inside ZIO. State Monad lacks two important features that we use in real-life application development:

1. Concurrency Support
2. Error Handling

### Concurrency
State Monad is an effect system that only includes state. It allows us to do pure stateful computations. We can only get, set, and update (and related computations) state. State Monad updates its state with series of stateful computations sequentially, but **it can't be used to do async or concurrent computations**. `Ref`, in contrast, has great support for concurrent and async programming.

### Error Handling
In most real-life,stateful applications, we will involve some database IO and API calls and/or some concurrent and sync operations which can fail in different ways along the path of execution. So besides state management, we need a way to handle errors. The State Monad doesn't have the ability to model error management. We can combine State Monad and Either Monad with StateT monad transformer, but it imposes massive performance overhead. It doesn't buy us anything that we can't do with `Ref`. So it is an anti-pattern. In the ZIO model, errors are encoded in effects and `Ref` utilizes that. So, in addition to state management, we have the ability to handle errors without additional work.

## State Transformers

Those who live on the dark side of mutation sometimes have it easy; they can add state everywhere like it's Christmas. Behold:

```scala mdoc:silent
var idCounter = 0
def freshVar: String = {
  idCounter += 1
  s"var${idCounter}"
}
val v1 = freshVar
val v2 = freshVar
val v3 = freshVar
```

As functional programmers, we know better and have captured state mutation in the form of functions of type `S => (A, S)`. `Ref` provides such an encoding, with `S` being the type of the value, and `modify` embodying the state mutation function.

```scala mdoc:silent
Ref.make(0).flatMap { idCounter =>
  def freshVar: UIO[String] =
    idCounter.modify(cpt => (s"var${cpt + 1}", cpt + 1))

  for {
    v1 <- freshVar
    v2 <- freshVar
    v3 <- freshVar
  } yield ()
}
```

## Building more sophisticated concurrency primitives

`Ref` is low-level enough that it can serve as the foundation for other concurrency data types.

For example, semaphores are a classic abstract data type for controlling access to shared resources. They are defined as a triplet `S = (v, P, V)` where `v` is the number of units of the resource that are currently available, and `P` and `V` are operations that decrement and increment `v`, respectively. `P` will only complete when `v` is non-negative and must wait if it isn't.

With `Ref`, it's easy to implement such a semaphore! The only difficulty is in `P`, where we must fail and retry when either `v` is negative, or its value has changed between the moment we read it and the moment we try to update it. A naive implementation could look like:

```scala mdoc:silent
sealed trait S {
  def P: UIO[Unit]
  def V: UIO[Unit]
}

object S {
  def apply(v: Long): UIO[S] =
    Ref.make(v).map { vref =>
      new S {
        def V = vref.update(_ + 1).unit

        def P = (vref.get.flatMap { v =>
          if (v < 0)
            ZIO.fail(())
          else
            vref.modify(v0 => if (v0 == v) (true, v - 1) else (false, v)).flatMap {
              case false => ZIO.fail(())
              case true  => ZIO.unit
            }
        } <> P).unit
      }
    }
}
```

Let's rock these crocodile boots we found the other day at the market and test our semaphore at the night club, yee-haw:

```scala mdoc:silent
import zio.Console._

val party = for {
  dancefloor <- S(10)
  dancers <- ZIO.foreachPar(1 to 100) { i =>
    dancefloor.P *> Random.nextDouble.map(d => Duration.fromNanos((d * 1000000).round)).flatMap { d =>
      printLine(s"${i} checking my boots") *> ZIO.sleep(d) *> printLine(s"${i} dancing like it's 99")
    } *> dancefloor.V
  }
} yield ()
```

It goes without saying you should take a look at ZIO's own `Semaphore`, it does all this and more without wasting all those CPU cycles while waiting.
