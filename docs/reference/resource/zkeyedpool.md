---
id: zkeyedpool
title: "ZKeyedPool"
---

The `ZKeyedPool[+Err, -Key, Item]` is a pool of items of type `Item` that are associated with a key of type `Key`. An attempt to get an item from a pool may fail with an error of type `Err`.

The interface is similar to [`ZPool`](zpool.md), but it allows associating items with keys:

```scala
trait ZKeyedPool[+Err, -Key, Item] {
  def get(key: Key): ZIO[Scope, Err, Item]
  def invalidate(item: Item): UIO[Unit]
}
```

The two fundamental operators on a `ZPool` is `get` and `invalidate`:
- The `get` operator retrieves an item associated with the given key from the pool in a scoped effect.
- The `invalidate` operator invalidates the specified item. This will cause the pool to eventually reallocate the item.

There  couple of ways to create a `ZKeyedPool`:

Generally there are two ways to create a `ZKeyedPool`:
1. Fixed-size Pools
2. Dynamic-size Pools

### Fixed-size Pools

1. We can create a pool that has a fixed number of items for each key:

```scala
object ZKeyedPool {
  def make[Key, Env: EnvironmentTag, Err, Item](
       get: Key => ZIO[Env, Err, Item],
       size: => Int
    ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] = ???
}
```

For example The `ZKeyedPool.make(key => resource(key), 3)` creates a pool of resources where each key has a pool of size 3:

```scala mdoc:compile-only
import zio._

object ZKeyedPoolExample extends ZIOAppDefault {
  def resource(key: String): ZIO[Scope, Nothing, String] = ZIO.acquireRelease(
    ZIO.random
      .flatMap(_.nextUUID.map(_.toString))
      .flatMap(uuid => ZIO.debug(s"Acquiring the resource with the $key key and the $uuid id").as(uuid))
  )(uuid => ZIO.debug(s"Releasing the resource with the $key key and the $uuid id!"))

  def run =
    for {
      pool <- ZKeyedPool.make(resource, 3)
      _    <- pool.get("foo")
      item <- pool.get("bar")
      _    <- ZIO.debug(s"Item: $item")
    } yield ()
}
```

Here is an example output of the above code:

```
Acquiring the resource with the foo key and the 82ee3cab-7f4c-47f1-b3e6-0cd49035925d id!
Acquiring the resource with the foo key and the f9cd881f-fa2e-421c-a6ae-c8d16f6b4500 id!
Acquiring the resource with the foo key and the 09a8f4c9-24ee-411c-b1d0-958479266cb0 id!
Acquiring the resource with the bar key and the 4d6f9c95-8d72-4560-bc20-0965b547cfb7 id!
Acquiring the resource with the bar key and the 44bf6641-bb0f-4088-989b-95fb442d93ab id!
Acquiring the resource with the bar key and the fc2780a7-1717-4027-b201-65441168bfce id!
Item: 4d6f9c95-8d72-4560-bc20-0965b547cfb7
Releasing the resource with the bar key and the fc2780a7-1717-4027-b201-65441168bfce id!
Releasing the resource with the bar key and the 44bf6641-bb0f-4088-989b-95fb442d93ab id!
Releasing the resource with the bar key and the 4d6f9c95-8d72-4560-bc20-0965b547cfb7 id!
Releasing the resource with the foo key and the 09a8f4c9-24ee-411c-b1d0-958479266cb0 id!
Releasing the resource with the foo key and the f9cd881f-fa2e-421c-a6ae-c8d16f6b4500 id!
Releasing the resource with the foo key and the 82ee3cab-7f4c-47f1-b3e6-0cd49035925d id!
```

2. We can create a pool that has a fixed number of items but with different pool size for each key:

```scala
object ZKeyedPool {
  def make[Key, Env: EnvironmentTag, Err, Item](
    get: Key => ZIO[Env, Err, Item],
    size: Key => Int
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] = ???
}
```

In the following example, we have created a pool of resources where based on the key, the pool size for that key is different, the pool size for keys starting with "foo" is 2, and for keys starting with "bar" is 3, and for all other keys, the pool size is 1:

```scala mdoc:invisible
import zio._

def resource(key: String): ZIO[Scope, Nothing, String] = ZIO.acquireRelease(
  ZIO.random
    .flatMap(_.nextUUID.map(_.toString))
    .flatMap(uuid => ZIO.debug(s"Acquiring the resource with $key key and $uuid id").as(uuid))
)(uuid => ZIO.debug(s"Releasing the resource with $key key and $uuid id!"))

```

```scala mdoc:compile-only
for {
  pool <- ZKeyedPool.make(resource, (key: String) => key match {
    case k if k.startsWith("foo") => 2
    case k if k.startsWith("bar") => 3
    case _                        => 1
  })
  _    <- pool.get("foo1")
  item <- pool.get("bar1")
  _    <- ZIO.debug(s"Item: $item")
} yield ()
```

Here is an example output of the above code:

```
Acquiring the resource with foo1 key and 052778eb-31c2-4eac-806b-46651813b457 id
Acquiring the resource with foo1 key and bd39dbe4-8f43-4376-a209-5af8ca118af2 id
Acquiring the resource with bar1 key and ecfc80da-c8b2-4726-813c-259748a98c3e id
Acquiring the resource with bar1 key and 0ddfd051-7bf8-4596-a7b9-4011ceeb0976 id
Acquiring the resource with bar1 key and 67239ac8-5def-45ac-962f-b05fb82bf0c3 id
Item: ecfc80da-c8b2-4726-813c-259748a98c3e
Releasing the resource with bar1 key and 67239ac8-5def-45ac-962f-b05fb82bf0c3 id!
Releasing the resource with bar1 key and 0ddfd051-7bf8-4596-a7b9-4011ceeb0976 id!
Releasing the resource with bar1 key and ecfc80da-c8b2-4726-813c-259748a98c3e id!
Releasing the resource with foo1 key and bd39dbe4-8f43-4376-a209-5af8ca118af2 id!
Releasing the resource with foo1 key and 052778eb-31c2-4eac-806b-46651813b457 id!
```

### Dynamic-size Pools

1. We can create a pool with the specified minimum and maximum sized and time to live before a pool whose excess items are not being used will be shrunk down to the minimum size:

```scala
object ZKeyedPool {
  def make[Key, Env: EnvironmentTag, Err, Item](
    get: Key => ZIO[Env, Err, Item],
    range: Key => Range,
    timeToLive: Duration
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] = ???
}
```

2. Similarly, we can create a pool of resources where the minimum and maximum size of the pool is different for each key. Also, the time to live for each key can be different:

```scala
  def make[Key, Env: EnvironmentTag, Err, Item](
    get: Key => ZIO[Env, Err, Item],
    range: Key => Range,
    timeToLive: Key => Duration
  ): ZIO[Env with Scope, Nothing, ZKeyedPool[Err, Key, Item]] = ???
```
