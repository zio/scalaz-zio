---
id: defining-polymorphic-services-in-zio
title: "Defining Polymorphic Services in ZIO"
sidebar_label: "Polymorphic Services"
---

As we discussed [here](../contextual/zenvironment.md), the `ZEnvironment`, which is the underlying data type used by `ZLayer`, is backed by a type-level mapping from types of services to implementations of those services. This functionality is backed by `izumi.reflect.Tag`, which captures a type as a value.

We just need to know what is the type of service when we put it in the `ZEnvironment` because `ZEnvironment` is essentially a map from _service types (interfaces)_ to _implementation of those interfaces_. To implement the map, the `ZEnvironment` needs a type tag for the new service, and also needs a way to remove the old service from the type level map. So we should have service type information at the runtime.

We can think of `Tag[A]` as like a `TypeTag[A]` or `ClassTag[A]` from the Scala standard library but available on a cross-version and cross-platform basis. Basically, it carries information about a certain type into runtime that was available at compile time. Methods that construct `ZEnvironment` values generally require a tag for the value being included in the “bundle of services”.

As a user, we should not normally interact with `Tag` except where we define polymorphic services. In general, a `Tag` should always be available whenever we have a concrete type. The only time we should have to use it is when we have a _polymorphic service_. If we are using polymorphic code, we need to provide implicit evidence that a tag exists for that type (`implicit tag: Tag[A]`) or as a context-bound for that type parameter: (`A: Tag`).

Let's try to write a polymorphic service. Assume we have the following service interface:

```scala mdoc:silent
trait KeyValueStore[K, V, E, F[_, _]] {
  def get(key: K): F[E, V]

  def set(key: K, value: V): F[E, V]

  def remove(key: K): F[E, Unit]
}
```

In the next step, we are going to write its accessors (note: [accessor methods](accessor-methods.md) are deprecated). We might end up with the following snippet code:

```scala mdoc:fail:silent
import zio._

object KeyValueStore {
  def get[K, V, E](key: K): ZIO[KeyValueStore[K, V, E, IO], E, V] =
    ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.get(key))

  def set[K, V, E](key: K, value: V): ZIO[KeyValueStore[K, V, E, IO], E, V] =
    ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.set(key, value))

  def remove[K, V, E](key: K): ZIO[KeyValueStore[K, V, E, IO], E, Unit] =
    ZIO.serviceWithZIO(_.remove(key))
}

// error: could not find implicit value for izumi.reflect.Tag[K]. Did you forget to put on a Tag, TagK or TagKK context bound on one of the parameters in K? e.g. def x[T: Tag, F[_]: TagK] = ...
// 
// 
// <trace>: 
//   deriving Tag for K, dealiased: K:
//   could not find implicit value for Tag[K]: K is a type parameter without an implicit Tag!
//     ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.get(key))
//                                                   ^
// error: could not find implicit value for izumi.reflect.Tag[K]. Did you forget to put on a Tag, TagK or TagKK context bound on one of the parameters in K? e.g. def x[T: Tag, F[_]: TagK] = ...
// 
// 
// <trace>: 
//   deriving Tag for K, dealiased: K:
//   could not find implicit value for Tag[K]: K is a type parameter without an implicit Tag!
//     ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.set(key, value))
//                                                   ^
// error: could not find implicit value for izumi.reflect.Tag[K]. Did you forget to put on a Tag, TagK or TagKK context bound on one of the parameters in K? e.g. def x[T: Tag, F[_]: TagK] = ...
// 
// 
// <trace>: 
//   deriving Tag for K, dealiased: K:
//   could not find implicit value for Tag[K]: K is a type parameter without an implicit Tag!
//     ZIO.serviceWithZIO(_.remove(key))
//                       ^
```

The compiler generates the following errors:

```
could not find implicit value for izumi.reflect.Tag[K]. Did you forget to put on a Tag, TagK or TagKK context bound on one of the parameters in K? e.g. def x[T: Tag, F[_]: TagK] = ...


<trace>: 
  deriving Tag for K, dealiased: K:
  could not find implicit value for Tag[K]: K is a type parameter without an implicit Tag!
    ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.get(key))
```

As the compiler says, we should put `Tag` as a context-bound for `K`, `V`, and `E` type parameters:

```scala mdoc:silent
import zio._

object KeyValueStore {
  def get[K: Tag, V: Tag, E: Tag](key: K): ZIO[KeyValueStore[K, V, E, IO], E, V] =
    ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.get(key))

  def set[K: Tag, V: Tag, E: Tag](key: K, value: V): ZIO[KeyValueStore[K, V, E, IO], E, V] =
    ZIO.serviceWithZIO[KeyValueStore[K, V, E, IO]](_.set(key, value))

  def remove[K: Tag, V: Tag, E: Tag](key: K): ZIO[KeyValueStore[K, V, E, IO], E, Unit] =
    ZIO.serviceWithZIO(_.remove(key))
}
```

Now, we can continue and implement the in-memory version of this key-value store:

```scala mdoc:silent
case class InmemoryKeyValueStore(map: Ref[Map[String, Int]])
  extends KeyValueStore[String, Int, String, IO] {

  override def get(key: String): IO[String, Int] =
    map.get.map(_.get(key)).someOrFail(s"$key not found")

  override def set(key: String, value: Int): IO[String, Int] =
    map.update(_.updated(key, value)).map(_ => value)

  override def remove(key: String): IO[String, Unit] =
    map.update(_.removed(key))
}

object InmemoryKeyValueStore {
  def layer: ULayer[KeyValueStore[String, Int, String, IO]] =
    ZLayer {
      Ref.make(Map[String, Int]()).map(InmemoryKeyValueStore.apply)
    }
}
```

The last step is to use the service in a ZIO application:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  val myApp: ZIO[KeyValueStore[String, Int, String, IO], String, Unit] =
    for {
      _ <- KeyValueStore.set[String, Int, String]("key1", 3).debug
      _ <- KeyValueStore.get[String, Int, String]("key1").debug
      _ <- KeyValueStore.remove[String, Int, String]("key1")
      _ <- KeyValueStore.get[String, Int, String]("key1").either.debug
    } yield ()

  def run = myApp.provide(InmemoryKeyValueStore.layer)
  
}

// Output:
// 3
// 3
// not found
```

Note that in the above example, one might want to write accessors more polymorphic. So in this case we should add `TagKK` as a context-bound of the `F` type parameter:

```scala mdoc:compile-only
object KeyValueStore {
  def get[K: Tag, V: Tag, E: Tag, F[_, _] : TagKK](key: K): ZIO[KeyValueStore[K, V, E, F], Nothing, F[E, V]] =
    ZIO.serviceWith[KeyValueStore[K, V, E, F]](_.get(key))

  def set[K: Tag, V: Tag, E: Tag, F[_, _] : TagKK](key: K, value: V): ZIO[KeyValueStore[K, V, E, F], Nothing, F[E, V]] =
    ZIO.serviceWith[KeyValueStore[K, V, E, F]](_.set(key, value))

  def remove[K: Tag, V: Tag, E: Tag, F[_, _] : TagKK](key: K): ZIO[KeyValueStore[K, V, E, F], E, Unit] =
    ZIO.serviceWith(_.remove(key))
}
```
