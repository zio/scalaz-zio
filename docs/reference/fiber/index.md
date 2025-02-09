---
id: index
title: "Introduction to ZIO Fibers"
---

A Fiber can be thought of as a virtual thread. A Fiber is the analog of a Java thread (`java.lang.Thread`), but it performs much better. Fibers are implemented in such a fashion that a single JVM thread will execute many fibers. We can think of fibers as unbounded JVM threads.

> **Warning** if you are not an experienced ZIO programmer:
>
> You should avoid using fibers manually. ZIO gives you many concurrent primitives like `raceWith`, `zipPar`, `foreachPar`, and so forth, which utilize fibers under the hood without any manual effort required.
>
> Fibers, just like threads, are low-level constructs. It's not generally recommended to deal with them in your code directly. It is very easy to make lots of mistakes or to introduce performance problems by manually using them.

## Why Fibers?

There are some limitations with JVM threads:

1. **Threads are scarce** — Threads on the JVM map to the operating system level threads which imposes an upper bound on the number of threads that we can have inside our application.
2. **Expensive on creation** — The creation of threads is expensive in terms of time and memory complexity.
3. **Much Overhead on Context Switching** — Switching between the execution of one thread to another thread is not cheap, it takes a lot of time.
4. **Lack of Composability** — Threads are not typed. They don't have a meaningful return type. In Java, when we create a thread, we have to provide a `run` function that returns void. So threads cannot finish with any specific value. Due to this limitation, we cannot compose threads. Also, a thread has no type parameter for error. It is expected to throw any exception of type `Throwable` to signal errors.

In the following sections, we are going to discuss the key features of fibers, and how fibers overcome the drawbacks of Java threads.

### Unbounded Size

So whereas the mapping from JVM threads to operating system threads is one-to-one, **the mapping of fibers to threads is many-to-one**. Each JVM thread will end up executing anywhere from hundreds to thousands or even tens of thousands of fibers concurrently, by hopping back and forth between them as necessary. This gives us virtual threads that have the benefits of threads, but the scalability way beyond threads. In other words, fibers offer us massive concurrent **lightweight green threading** on the JVM.

As a rough rule of thumb, we can have an application with a thousand real threads. No problem, modern servers can support applications with a thousand threads. However, we cannot have an application with a hundred thousand threads, that application will die. That just won't make any progress. The JVM nor our operating system can physically support a hundred thousand threads. However, it is no problem to have a Scala application with a hundred thousand fibers. Such an application can still perform in a very high-performance fashion, and the miracle that enables that to happen is fiber.

### Lightweight

**JVM threads are expensive to create in terms of time and memory complexity.** Also it takes a lot of time to switch between one thread of execution to another. In contrast to that, fibers are virtual, and as they use **green threading**, they are considered to be **lightweight cooperative threads**. This means that fibers always _yield_ their executions to each other without the overhead of preemptive scheduling.

### Asynchronous

Fibers are asynchronous, while threads are always synchronous. That is why fibers scale better than threads.

### Typed and Composable

**Fibers have typed error and success values**. A fiber has two type parameters, `E` and `A`:

- The `E` corresponds to the error channel. It indicates the error type with which the fiber can fail.

- The `A` corresponds to the success value of the computation. That is the type with which the fiber can succeed.

The fact that fibers are typed allows us to write more type-safe programs. Also, it increases the compositional properties of our programs because we can wait on a fiber to finish and then expect to receive a value of type `A`.

### Interrupt Safe

Threads in Java can be terminated via the stop method, but this is not a safe operation. The stop operation has been [deprecated](https://docs.oracle.com/javase/1.5.0/docs/guide/misc/threadPrimitiveDeprecation.html). So this is not a safe way to force kill a thread. Instead, we should try to request an interruption of the thread, but in this case, **the thread may not respond to our request, and it may just go forever**.

**Fiber has a safe version of this functionality that works very well**. Just like we can interrupt a thread, we can interrupt a fiber too, but interruption of fibers is much more reliable. It will always work, and **it probably works very fast**. We don't need to wait around, we can just try to interrupt them, and they will be gone very soon.

### Structured Concurrency

With fibers, we can have hundreds of thousands and even millions of fibers that are started and working together. So we can reach a very massive concurrency with fibers. Now how can we manage all these fibers? Some of them are top-level fibers and some others are forked and become children of their parents. How can we manage their scopes, how to keep track of all fibers, and prevent them to leak? What happens to the execution of a child fiber if its parent execution is interrupted? The child fibers should be scoped to their parent fibers. We need a way to manage these scopes automatically. This is where structured concurrency shines.

> _**Important**:_
>
> It's worth mentioning that in the ZIO model, all code runs on fibers. There is no such thing as code that is executed outside of fibers. When we create a main function in ZIO that returns an effect, then even if we don't explicitly fork a fiber, the effect will be executed on what is called the main fiber. It's a top-level fiber.
>
>It's just like if we have a main function in Java then that main function will execute on the main thread. There is no code in Java that does not execute on a thread. All code executes on a thread even if you didn't create a thread.

ZIO provides structured concurrency. The way ZIO's structured concurrency works is that **the child fibers are scoped to their parent fibers** which means that **when the parent effect finishes execution, then all childs' effects will be automatically interrupted**. So when we fork, and we get back a fiber, the fiber's lifetime is bound to the parent fiber that forked it. It is almost impossible to leak fibers because child fibers are guaranteed to complete before their parents.

The structured concurrency gives us a way to reason about fiber lifespans. We can statically reason about the lifetimes of children fibers just by looking at our code. We don't need to insert complicated logic to keep track of all the child fibers and manually shut them down.

#### Global Lifetime

Sometimes we want a child fiber to outlive the scope of the parent. What can we do in that case? Well, ZIO offers an operator called `forkDaemon` which forks the fiber as a daemon fiber. Daemon fibers can outlive their parents. They can live forever. They run in the background doing their work until they end with failure or success. This gives us a way to spawn background jobs that should just keep on going regardless of what happens to the parent.

#### Fine-grained Scope

If we need a very flexible fine-grained control over the lifetime of a fiber there is another operator called `forkin`. We can fork a fiber inside a specific scope, and when that scope is closed then the fiber will be terminated.

## Fiber Data Types

ZIO fiber contains a few data types that can help us to solve complex problems:

- **[Fiber](fiber.md)** — A fiber value models an `IO` value that has started running, and is the moral equivalent of a green thread.
- **[Fiber.Status](fiberstatus.md)** — `Fiber.Status` describes the current status of a Fiber.
- **[FiberId](fiberid.md)** — `FiberId` describes the unique identity of a Fiber.
