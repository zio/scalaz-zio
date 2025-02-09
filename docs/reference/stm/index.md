---
id: index
title: "Introduction to Software Transactional Memory"
---

## Overview

ZIO supports Software Transactional Memory (STM) which is a modular composable concurrency data structure. It allows us to combine and compose a group of memory operations and perform all of them in one single atomic operation.

Software Transactional Memory is an abstraction for concurrent communications. The main benefits of STM are composability and modularity. We can write concurrent abstractions that can be composed with any other abstraction built using STM, without exposing the details of how our abstraction ensures safety. This is typically not the case with the locking mechanism.

The idea of the transactional operation is not new, they have been the fundamental of distributed systems, and those databases that guarantee us an ACID property. Software transactional memory is just all about memory operations. All operations are performed on memory. It is not related to a remote system or a database. Very similar to the database concept of ACID property, but the _durability_, is missing which doesn't make sense for in-memory operations.

In transactional memory, we get these aspects of ACID properties:

- **Atomicity** — On write operations, we want _atomic update_, which means the update operation either should run at once or not at all.
- **Consistency** — On read operations, we want a _consistent view_ of the state of the program that ensures us all references to the state, get the same value whenever they get the state.
- **Isolated** — If we have multiple updates, we need to perform these updates in isolated transactions. So each transaction doesn't affect other concurrent transactions. No matter how many fibers are running any number of transactions. None of them have to worry about what is happening in the other transactions.

The ZIO STM API is inspired by Haskell's [STM library](http://hackage.haskell.org/package/stm-2.5.0.0/docs/Control-Concurrent-STM.html) although the implementation in ZIO is completely different.

## The Problem

Let's start from a simple `inc` function, which takes a mutable reference of `Int` and increases it by `amount`:

```scala mdoc:invisible
import zio.{Ref, ZIO}
import zio.stm.{TRef, STM}
```

```scala mdoc:silent
def inc(counter: Ref[Int], amount: Int) = for {
  c <- counter.get
  _ <- counter.set(c + amount)
} yield c
```

If there is only one fiber in the world, it is not a problem. This function sounds correct. But what happens if in between reading the value of the counter and setting a new value, another fiber comes and mutates the value of the counter? Another fiber is just updating the counter just after we read the counter. So this function is subject to a race condition, we can test that with the following program:

```scala mdoc:silent
for {
  counter <- Ref.make(0)
  _ <- ZIO.collectAllPar(ZIO.replicate(10)(inc(counter, 1)))
  value <- counter.get
} yield (value)
```

The above program runs 10 concurrent fibers to increase the counter value. However, we cannot expect this program to always return 10 as a result. 

To fix this issue, we need to perform the `get` and `set` operations atomically. The `Ref` data type some other API like `update`, `updateAndGet`, and `modify` which perform the reading and writing atomically:

```scala mdoc:nest
def inc(counter: Ref[Int], amount: Int) = counter.updateAndGet(_ + amount)
```

The most important note about the `modify` operation is that it doesn't use pessimistic locking. It doesn't use any locking primitives for the critical section. It has an optimistic assumption about occurring collisions.

The `modify` function takes these three steps:

1. It assumes that other fibers don't change the shared state and don't interfere in most cases. So it read the shared state without using any locking primitives.
2. It should prepare itself for the worst-case scenarios. If another fiber is accessing the data at the same time, what would happen? Therefore, when we write a new value, it should check everything. It must ensure that it sees a consistent state of the universe, and if it does, then it can change that value.
4. If it encounters an inconsistent value, it shouldn't continue. So it aborts updating the shared state with an invalidated assumption. It should retry the `modify` operation with an updated state.

Let's see how the `modify` function of `Ref` is implemented without any locking mechanism:

```scala mdoc:invisible
import java.util.concurrent.atomic.AtomicReference
import zio.{UIO, ZIO}
```

```scala mdoc:silent:nest
  final case class Ref[A](value: AtomicReference[A]) { self =>
    def modify[B](f: A => (B, A)): UIO[B] = ZIO.succeed {
      var loop = true
      var b: B = null.asInstanceOf[B]
      while (loop) {
        val current = value.get
        val tuple   = f(current)
        b = tuple._1
        loop = !value.compareAndSet(current, tuple._2)
      }
      b
    }
 }
```

As we see, the `modify` operation is implemented in terms of the `compare-and-swap` operation which helps us to perform read and update atomically.

Let's rename the `inc` function to the `deposit` as follows to try the classic problem of transferring money from one account to another:

```scala mdoc:invisible:reset
import zio.{Ref, ZIO, IO}
import zio.stm.{TRef, STM}
```

```scala mdoc:silent
def deposit(accountBalance: Ref[Int], amount: Int) = accountBalance.update(_ + amount)
```

And the `withdraw` function:

```scala mdoc:silent:nest
def withdraw(accountBalance: Ref[Int], amount: Int) = accountBalance.update(_ - amount) 
```

It seems pretty good, but we also need to check that there is sufficient balance in the account to withdraw. So let's add an invariant to check that:

```scala mdoc:silent:nest
def withdraw(accountBalance: Ref[Int], amount: Int) = for {
  balance <- accountBalance.get
  _ <- if (balance < amount) ZIO.fail("Insufficient funds in you account") else
    accountBalance.update(_ - amount)
} yield ()
```

What if in between checking and updating the balance, another fiber comes and withdraws all money in the account? This solution has a bug. It has the potential to reach a negative balance. 

Suppose we finally reached a solution to do withdraw atomically, the problem remains. We need a way to compose `withdraw` with `deposit` atomically to create a `transfer function:

```scala mdoc:silent
def transfer(from: Ref[Int], to: Ref[Int], amount: Int) = for {
  _ <- withdraw(from, amount)
  _ <- deposit(to, amount)
} yield ()
```

In the above example, even if we assume that the `withdraw` and `deposit` are atomic, we can't compose these two transactions. They produce bugs in a concurrent environment. This code doesn't guarantee us that both `withdraw` and `deposit` are performed in one single atomic operation. Other fibers which are executing this `transfer` method can override the shared state and introduce a race condition.

We need a solution to **atomically compose transactions**. This is where software transactional memory comes into play.

## Composable Concurrency

Software transactional memory provides us a way to compose multiple transactions and perform them in one single transaction.

Let's continue our last effort to convert our `withdraw` method to be one atomic operation. To solve the problem using STM, we replace `Ref` with `TRef`. `TRef` stands for _Transactional Reference_; it is a mutable reference contained in the `STM` world. `STM` is a monadic data structure that represents an effect that can be performed transactionally:

```scala mdoc:silent:nest
def withdraw(accountBalance: TRef[Int], amount: Int): STM[String, Unit] =
  for {
    balance <- accountBalance.get
    _ <- if (balance < amount)
      STM.fail("Insufficient funds in you account")
    else
      accountBalance.update(_ - amount)
  } yield ()
```

Although the `deposit` operation is atomic, to be able to compose with `withdraw` we need to refactor it to take `TRef` and return `STM`:

```scala mdoc:silent:nest
def deposit(accountBalance: TRef[Int], amount: Int): STM[Nothing, Unit] =
  accountBalance.update(_ + amount)
```

In the `STM` world we can compose all operations and at the end of the world, we perform all of them in one single operation atomically. To be able to compose `withdraw` with `deposit` we need to stay in the `STM` world. Therefore, we didn't perform `STM.atomically` or `STM#commit` methods on each of them.

Now we can define the `transfer` method by composing these two functions in the `STM` world and converting them into the `IO` atomically:

```scala mdoc:silent
def transfer(from: TRef[Int], to: TRef[Int], amount: Int): IO[String, Unit] =
  STM.atomically {
    for {
      _ <- withdraw(from, amount)
      _ <- deposit(to, amount)
    } yield ()
  }
```

Assume we are in the middle of transferring money from one account to the other. If we withdraw the first account but haven't deposited the second account, that kind of intermediate state is not visible to any external fibers. The transaction is completely successful if there are no conflicting changes. If there are any conflicts or conflicting changes then the whole transaction, the entire STM will be retried.

## How Does it Work?

The `STM` uses the same idea as the `Ref#modify` function but with a composability feature. The main goal of `STM` is to provide a mechanism to compose multiple transactions and perform them in one single atomic operation.

The mechanism behind the compositional part is obvious. The `STM` has its own world. It has lots of useful combinators like `flatMap` and `orElse` to compose multiple `STM`s and create more elegant ones. After we perform a transaction with `STM#commit` or `STM.atomically` the runtime system does the following steps. These steps are not exactly accurate, but they draw an outline of what happens during the transaction:

1. **Starting a Transaction** — When we start a transaction, the runtime system creates a virtual space to keep track of the transaction logs which is build up by recording the reads and tentative writes that the transaction will perform during the transaction steps.
2. **Virtual Execution** — The runtime starts speculating the execution of transactions on every read and write operation. It has two internal logs;  the read and the write log. On the read log, it saves the version of all variables it reads during the intermediate steps, and on the write log, it saves the intermediate result of the transaction. It doesn't change the shared state on the main memory. Anything that is inside an atomic block is not executed immediately, it's executed in the virtual world, just by putting stuff in the internal log, not in the main memory. In this particular model, we guarantee that all computations are isolated from one another.
3. **Commit Phase (Real Execution)** — When it comes to the end of the transaction the runtime system should check everything it has read. It should make sure that it sees a consistent state of the universe and if it has, then it atomically commits. As the STM is optimistic, it assumes that in the middle of a transaction, the chance of interfering with the shared state by other fibers is very rare. But it must ready itself for the worst cases. It should validate its assumption in the final stage. It checks whether the transactional variables involved were modified by any other threads or not. If its assumption got invalidated in the meanwhile of the transaction, it should abandon the transaction and retry it again. It jumps to the start of the transaction with the original and default values and tries again until it succeeds; This is necessary to resolve conflicts. Otherwise, if there is no conflict, it commits the final value atomically to the memory and succeeds. From the point of view of other fibers, all values in memory exchange in one blink of an eye. It's all atomic.

Everything done within a transaction to other transactions looks like it happens at once or not at all. So no matter how many pieces of memory it touches during the transaction. From the other transaction perspective, all of these changes happen at once.


## STM Data Types

Like the `ZIO` data type, the `ZSTM` has some type aliases as follows:

```scala
type RSTM[-R, +A]  = ZSTM[R, Throwable, A]
type URSTM[-R, +A] = ZSTM[R, Nothing, A]
type STM[+E, +A]   = ZSTM[Any, E, A]
type USTM[+A]      = ZSTM[Any, Nothing, A]
type TaskSTM[+A]   = ZSTM[Any, Throwable, A]
```

There are a variety of transactional data structures that can take part in an STM transaction:

- **[TArray](tarray.md)** - A `TArray[A]` is an array of mutable references that can participate in transactions.
- **[TRandom](trandom.md)** — `TRandom` is a random service that provides utilities to generate random numbers, which can participate in STM transactions.
- **[TSet](tset.md)** - A `TSet` is a mutable set that can participate in transactions.
- **[TMap](tmap.md)** - A `TMap[A]` is a mutable map that can participate in transactions.
- **[TRef](tref.md)** - A `TRef` is a mutable reference to an immutable value that can participate in transactions.
- **[TPriorityQueue](tpriorityqueue.md)** - A `TPriorityQueue[A]` is a mutable priority queue that can participate in transactions.
- **[TPromise](tpromise.md)** - A `TPromise` is a mutable reference that can be set exactly once and can participate in transactions.
- **[TQueue](tqueue.md)** - A `TQueue` is a mutable queue that can participate in transactions.
- **[TReentrantLock](treentrantlock.md)** - A `TReentrantLock` is a reentrant read / write lock that can be composed.
- **[TSemaphore](tsemaphore.md)** - A `TSemaphore` is a semaphore that can participate in transactions.
- **[THub](thub.md)** - A `THub` is a hub that can participate in STM transactions.

Since STM places a great emphasis on compositionality, we can build upon these data structures and define our very own concurrent data structures. For example, we can build a transactional priority queue using `TRef`, `TMap` and `TQueue`.

## Advantage of Using STM

1. **Composable Transaction** — Combining atomic operations using locking-oriented programming is almost impossible. ZIO provides the `STM` data type, which has lots of combinators to compose transactions.
2. **Declarative** — ZIO STM is completely declarative. It doesn't require us to think about low-level primitives. It doesn't force us to think about the ordering of locks. Reasoning concurrent programs in a declarative fashion is very simple. We can just focus on the logic of our program and run it in a concurrent environment deterministically. The user code is much simpler of course because it doesn't have to deal with the concurrency at all. 
3. **Optimistic Concurrency** — In most cases, we are allowed to be optimistic unless there is tremendous contention. So if we haven't tremendous contention it really pays to be optimistic. It allows a higher volume of concurrent transactions.
4. **Lock-Free** — All operations are non-blocking using lock-free algorithms.
5. **Fine-Grained Locking**— Coarse-grained locking is very simple to implement, but it has a negative impact on performance, while fine-grained locking significantly has better performance, but it is very cumbersome, sophisticated, and error-prone even for experienced programmers. We would like to have the ease of use of coarse-grain locking, but at the same time, we would like to have the efficiency of fine-grain locking. ZIO provides several data types which are a very coarse way of using concurrency, but they are implemented as if every single word were lockable. So the granularity of concurrency is fine-grained. It increases the performance and concurrency. For example, if we have two fibers accessing the same `TArray`, one of them reads and writes on the first index of our array, and another one reads and writes to the second index of that array, they will not conflict. It is just like as if we were locking the indices, not the whole array. 

## Implication of Using STM

1. **Running I/O Inside STM**— There is a strict boundary between the `STM` world and the `ZIO` world. This boundary propagates even deeper because we are not allowed to execute arbitrary effects in the `STM` universe. Performing side effects and I/O operations inside a transaction is problematic. In the `STM` the only effect that exists is the `STM` itself. We cannot print something or launch a missile inside a transaction as it will nondeterministically get printed on every reties that transaction does that.
2. **Large Allocations** — We should be very careful in choosing the best data structure for using STM operations. For example, if we use a single data structure with `TRef` and that data structure occupies a big chunk of memory. Every time we are updating this data structure during the transaction, the runtime system needs a fresh copy of this chunk of memory.
3. **Running Expensive Operations**— The beautiful feature of the `retry` combinator is when we decide to retry the transaction, the `retry` avoids the busy loop. It waits until any of the underlying transactional variables have changed. However, we should be careful about running expensive operations multiple times.
