---
id: zpool
title: "ZPool"
---

A `ZPool[E, A]` is a pool of items of type `A`, each of which may be associated with the acquisition and release of resources. An attempt to get an item `A` from a pool may fail with an error of type `E`.

## Motivation

Acquiring some resources is expensive to create and time-consuming. This includes network connections (sockets, databases, remote services), threads, and so on.

There are some cases that
- We require a **fast and predictable** way of accessing resources.
- We need a solution to **scale across the number of resources**.
- On the other hand, each **resource consumption doesn't take a long time**.

If we create a new resource for every resource acquisition, consequently we will find ourselves in a constant repetition of acquisition and release of resources. This might end up with thousands of resources (e.g. connection to a database) created within a short time, which will reduce the performance of our application.

To address these issues, we can create a pool of pre-initialized resources:
- Whenever we need a new resource, we acquire that from the existing resources of the pool. So the resource acquisition will be predictable, and it will avoid the overhead of acquisition.
- When the resource is no longer needed, we release that back to the resource pool. So the released resources will be recyclable, and it will avoid the overhead of re-acquisition.

`ZPool` is an implementation of such an idea with some excellent properties that we will cover on this page.

## Introduction

`ZPool` is an asynchronous and concurrent generalized pool of reusable resources, that is used to create and manage a pool of objects.

```scala mdoc:invisible
import zio._
```

```scala mdoc:nest
trait ZPool[+Error, Item] {
  def get: ZIO[Scope, Error, Item]
  def invalidate(item: Item): UIO[Unit]
}
```

The two fundamental operators on a `ZPool` is `get` and `invalidate`:
- The `get` operator retrieves an item from the pool in a scoped effect.
- The `invalidate` operator invalidates the specified item. This will cause the pool to eventually reallocate the item.

Let's assume we have a resource that is expensive to create and release. We want to access this resource frequently, but we don't want to create a new resource every time we need it. We can use `ZPool` to create a pool of resources and acquire them when only needed.

```scala
import zio._

object PoolExample extends ZIOAppDefault {
  def resource: ZIO[Scope, Nothing, UUID] = ZIO.acquireRelease(
    ZIO.random.flatMap(_.nextUUID).flatMap(uuid => ZIO.debug(s"Acquiring the resource: $uuid!").as(uuid))
  )(uuid => ZIO.debug(s"Releasing the resource $uuid!"))

  def run =
    for {
      pool <- ZPool.make(resource, 3)
      item <- pool.get
      _ <- ZIO.debug(s"Item: $item")
    } yield ()
}
```

In this example, we created a pool of 3 resources. When we call `pool.get`, it will return one of the resources from the pool. After calling the `pool.get` three times, if we call it again, it will block until one of the resources is released back to the pool using `ZPool#invalidate`.

## Constructing ZPools

### Fixed-size Pools

The `make` constructor is a common way to create a `ZPool`:

```scala mdoc:silent
object ZPool {
  def make[E, A](get: ZIO[Scope, E, A], size: Int): ZIO[Scope, Nothing, ZPool[E, A]] = ???
}
```

```scala mdoc:reset:invisible
```

It takes a scoped resource of type `A`, and the `size` of the pool. The return type will be a scoped `ZPool`.
- A fixed pool size will be used to pre-allocate pool entries, so all the entries of the pool will be acquired eagerly. As a client of the `ZPool` it is recommended to analyze requirements to find out the best suitable size for the resource pool. If we set up a pool with too many eagerly-acquired resources, that may reduce the performance due to the resource contention.
- As the return type of the constructor is `ZIO[Scope, Nothing, ZPool[E, A]]`, it will manage automatically the life cycle of the pool. So, as a client of `ZPool`, we do not require to shutdown the pool manually.

There is another constructor called `ZPool.fromIterable` that is suitable when no cleanup or release actions are required.

### Dynamically-sized Pools

The previous constructor creates a `ZPool` with a fixed size, so all of its entries are pre-allocated. There is another constructor that creates a pool with pre-allocated minimum entries (eagerly-acquired resources), plus it can increase its entries _on-demand_ (lazily-acquired resources) until reaches the specified maximum size:

```scala
def make[E, A](get: ZIO[Scope, E, A],
        range: Range, // minimum and maximum size of the pool
        timeToLive: Duration): ZIO[Scope, Nothing, ZPool[E, A]]
```

Having a lot of resources that are over our average requirement can waste space and degrade the performance. Therefore, this variant of `ZPool` has an _eviction policy_. By taking the `timeToLive` argument, it will evict excess items that have not been acquired for more than the `timeToLive` time, until it reaches the minimum size.

Here is an example of creating pool of database connections:

```scala mdoc:invisible
import zio._

case class Connection()

val acquireDbConnection = ZIO.succeed(Connection())

def useConnection(i: Connection) = {
  val _ = i
  ZIO.unit
}
```

```scala mdoc:silent
ZIO.scoped {
  ZPool.make(acquireDbConnection, 10 to 20, 60.seconds).flatMap { pool =>
    ZIO.scoped {
      pool.get.flatMap { conn => useConnection(conn) }
    }
  }
}
```

## Operators on ZPool

### Resource Acquisition

After creating a pool, we can retrieve a resource from the pool using `ZPool#get` operation:

```scala mdoc:nest
trait ZPool[+Error, Item] {
  def get: ZIO[Scope, Error, Item]
}
```

Here is how it works behind the scenes:
- If there is any resource available in the pre-allocated entries, it will return one of those.
- If the demand exceeds the available resources in the pool, one new resource will be allocated on-demand and returned to the client.
- If the demand exceeds the maximum size of the pool, it will block while it waits for a resource to become available.
- If the acquisition of a resource fails, then the returned effect will fail for the same reason.

In case of failure, we can retry a failed acquisition. It will repeat the acquisition attempt:

```scala mdoc:invisible
val acquireDbConnection: ZIO[Any, String, Connection] = 
  ZIO.fail("Boom!")
```

```scala mdoc:silent:nest
ZPool.make(acquireDbConnection, 10).flatMap { pool =>
  pool.get.flatMap( conn => useConnection(conn)).eventually
}
```

### Resource Invalidation

When we are working with resources, especially the remote ones, they may become invalid or faulty. On the other hand, when the resource's life cycle ends, it will be automatically returned to the resource pool. So what happens if another client acquires a resource from the pool? The faulty resource can cause a problem for the next client. To prevent that, we can use the `ZPool#invalidate` to claim that the resource is invalid:

```scala mdoc:nest
trait ZPool[+Error, Item] {
  def invalidate(item: Item): UIO[Unit]
}
```

In this case, the pool will eventually reallocate the item, but this will happen lazily rather than eagerly.
