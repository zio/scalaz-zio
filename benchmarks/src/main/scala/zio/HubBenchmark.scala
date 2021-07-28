package zio

import cats.effect.std.{Queue => CatsQueue}
import cats.effect.unsafe.implicits.global
import cats.effect.{Fiber => CFiber, IO => CIO, Resource}
import cats.syntax.all._
import fs2.concurrent.Topic
import io.github.timwspence.cats.stm._
import org.openjdk.jmh.annotations._
import zio.BenchmarkUtil._

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(value = 3)
class HubBenchmarks {

  val hubSize               = 2
  val totalSize             = 1024
  val publisherParallelism  = 1
  val subscriberParallelism = 1024

  val stm: STM[CIO] = STM.runtime[CIO].unsafeRunSync()
  import stm._

  @Benchmark
  def catsQueueBoundedBackPressure(): Int =
    catsParallel(CatsHubLike.catsQueueBounded(hubSize))

  @Benchmark
  def catsQueueBoundedParallel(): Int =
    catsParallel(CatsHubLike.catsQueueBounded(totalSize))

  @Benchmark
  def catsQueueBoundedSequential(): Int =
    catsSequential(CatsHubLike.catsQueueBounded(totalSize))

  @Benchmark
  def catsQueueUnboundedParallel(): Int =
    catsParallel(CatsHubLike.catsQueueUnbounded)

  @Benchmark
  def catsQueueUnboundedSequential(): Int =
    catsSequential(CatsHubLike.catsQueueUnbounded)

  @Benchmark
  def catsSTMQueueParallel(): Int =
    catsParallel(CatsHubLike.catsSTMQueueUnbounded)

  @Benchmark
  def catsSTMQueueSequential(): Int =
    catsSequential(CatsHubLike.catsSTMQueueUnbounded)

  @Benchmark
  def fs2TopicBackPressure(): Int =
    catsParallel(CatsHubLike.fs2TopicBounded(hubSize))

  @Benchmark
  def fs2TopicParallel(): Int =
    catsParallel(CatsHubLike.fs2TopicBounded(totalSize))

  @Benchmark
  def fs2TopicSequential(): Int =
    catsSequential(CatsHubLike.fs2TopicBounded(totalSize))

  @Benchmark
  def zioHubBoundedBackPressure(): Int =
    zioParallel(ZIOHubLike.zioHubBounded(hubSize))

  @Benchmark
  def zioHubBoundedParallel(): Int =
    zioParallel(ZIOHubLike.zioHubBounded(totalSize))

  @Benchmark
  def zioHubBoundedSequential(): Int =
    zioSequential(ZIOHubLike.zioHubBounded(totalSize))

  @Benchmark
  def zioHubUnboundedParallel(): Int =
    zioParallel(ZIOHubLike.zioHubUnbounded)

  @Benchmark
  def zioHubUnboundedSequential(): Int =
    zioSequential(ZIOHubLike.zioHubUnbounded)

  @Benchmark
  def zioQueueBoundedBackPressure(): Int =
    zioParallel(ZIOHubLike.zioQueueBounded(hubSize))

  @Benchmark
  def zioQueueBoundedParallel(): Int =
    zioParallel(ZIOHubLike.zioQueueBounded(totalSize))

  @Benchmark
  def zioQueueBoundedSequential(): Int =
    zioSequential(ZIOHubLike.zioQueueBounded(totalSize))

  @Benchmark
  def zioQueueUnboundedParallel(): Int =
    zioParallel(ZIOHubLike.zioQueueUnbounded)

  @Benchmark
  def zioQueueUnboundedSequential(): Int =
    zioSequential(ZIOHubLike.zioQueueUnbounded)

  trait ZIOHubLike[A] {
    def publish(a: A): UIO[Any]
    def subscribe: ZManaged[Any, Nothing, Int => UIO[Any]]
  }

  object ZIOHubLike {

    def zioHubBounded[A](capacity: Int): UIO[ZIOHubLike[A]] =
      ZHub.bounded[A](capacity).map { hub =>
        new ZIOHubLike[A] {
          def publish(a: A): UIO[Any] =
            hub.publish(a)
          def subscribe: ZManaged[Any, Nothing, Int => UIO[Any]] =
            hub.subscribe.map(dequeue => n => zioRepeat(n)(dequeue.take))
        }
      }

    def zioHubUnbounded[A]: UIO[ZIOHubLike[A]] =
      ZHub.unbounded[A].map { hub =>
        new ZIOHubLike[A] {
          def publish(a: A): UIO[Any] =
            hub.publish(a)
          def subscribe: ZManaged[Any, Nothing, Int => UIO[Any]] =
            hub.subscribe.map(dequeue => n => zioRepeat(n)(dequeue.take))
        }
      }

    def zioQueueBounded[A](capacity: Int): UIO[ZIOHubLike[A]] =
      Ref.make(0L).zipWith(Ref.make[Map[Long, Queue[A]]](Map.empty)) { (key, ref) =>
        new ZIOHubLike[A] {
          def publish(a: A): UIO[Any] =
            ref.get.flatMap(map => ZIO.foreach(map.values)(_.offer(a)))
          def subscribe: ZManaged[Any, Nothing, Int => UIO[Any]] =
            for {
              key   <- key.getAndUpdate(_ + 1).toManaged
              queue <- Queue.bounded[A](capacity).toManaged
              _     <- ZManaged.acquireRelease(ref.update(_ + (key -> queue)))(ref.update(_ - key))
            } yield n => zioRepeat(n)(queue.take)
        }
      }

    def zioQueueUnbounded[A]: UIO[ZIOHubLike[A]] =
      Ref.make(0L).zipWith(Ref.make[Map[Long, Queue[A]]](Map.empty)) { (key, ref) =>
        new ZIOHubLike[A] {
          def publish(a: A): UIO[Any] =
            ref.get.flatMap(map => ZIO.foreach(map.values)(_.offer(a)))
          def subscribe: ZManaged[Any, Nothing, Int => UIO[Any]] =
            for {
              key   <- key.getAndUpdate(_ + 1).toManaged
              queue <- Queue.unbounded[A].toManaged
              _     <- ZManaged.acquireRelease(ref.update(_ + (key -> queue)))(ref.update(_ - key))
            } yield n => zioRepeat(n)(queue.take)
        }
      }
  }

  trait CatsHubLike[A] {
    def publish(a: A): CIO[Any]
    def subscribe: Resource[CIO, Int => CIO[Any]]
  }

  object CatsHubLike {

    def catsQueueBounded[A](capacity: Int): CIO[CatsHubLike[A]] =
      CIO.ref(0L).map2(CIO.ref[Map[Long, CatsQueue[CIO, A]]](Map.empty)) { (key, ref) =>
        new CatsHubLike[A] {
          def publish(a: A): CIO[Unit] =
            ref.get.flatMap(_.values.toList.traverse_(_.offer(a)))
          def subscribe: Resource[CIO, Int => CIO[Any]] =
            for {
              key   <- Resource.eval(key.modify(n => (n + 1, n)))
              queue <- Resource.eval(CatsQueue.bounded[CIO, A](capacity))
              _     <- Resource.make(ref.update(_ + (key -> queue)))(_ => ref.update(_ - key))
            } yield n => catsRepeat(n)(queue.take)
        }
      }

    def catsQueueUnbounded[A]: CIO[CatsHubLike[A]] =
      CIO.ref(0L).map2(CIO.ref[Map[Long, CatsQueue[CIO, A]]](Map.empty)) { (key, ref) =>
        new CatsHubLike[A] {
          def publish(a: A): CIO[Unit] =
            ref.get.flatMap(_.values.toList.traverse_(_.offer(a)))
          def subscribe: Resource[CIO, Int => CIO[Any]] =
            for {
              key   <- Resource.eval(key.modify(n => (n + 1, n)))
              queue <- Resource.eval(CatsQueue.unbounded[CIO, A])
              _     <- Resource.make(ref.update(_ + (key -> queue)))(_ => ref.update(_ - key))
            } yield n => catsRepeat(n)(queue.take)
        }
      }

    def catsSTMQueueUnbounded[A]: CIO[CatsHubLike[A]] =
      stm.commit {
        TVar.of(0L).map2(TVar.of[Map[Long, TQueue[A]]](Map.empty)) { (key, ref) =>
          new CatsHubLike[A] {
            def publish(a: A): CIO[Unit] =
              stm.commit(ref.get.flatMap(_.values.toList.traverse_(_.put(a))))
            def subscribe: Resource[CIO, Int => CIO[Any]] =
              for {
                key   <- Resource.eval(stm.commit(key.get.flatMap(n => key.modify(_ + 1).as(n))))
                queue <- Resource.eval(stm.commit(TQueue.empty[A]))
                _     <- Resource.make(stm.commit(ref.modify(_ + (key -> queue))))(_ => stm.commit(ref.modify(_ - key)))
              } yield n => catsRepeat(n)(stm.commit(queue.read))
          }
        }
      }

    def fs2TopicBounded[A](capacity: Int): CIO[CatsHubLike[A]] =
      Topic[CIO, A].map { topic =>
        new CatsHubLike[A] {
          def publish(a: A): CIO[Unit] =
            topic.publish1(a).void
          def subscribe: Resource[CIO, Int => CIO[Unit]] =
            topic.subscribeAwait(capacity).map(subscription => n => subscription.take(n.toLong).compile.drain)
        }
      }
  }

  def catsParallel(makeHub: CIO[CatsHubLike[Int]]): Int = {

    val io = for {
      ref      <- CIO.ref(subscriberParallelism)
      deferred <- CIO.deferred[Unit]
      hub      <- makeHub
      subscribers <- catsForkAll(List.fill(subscriberParallelism)(hub.subscribe.use { take =>
                       ref.modify { n =>
                         if (n == 1) (n - 1, deferred.complete(()))
                         else (n - 1, CIO.unit)
                       }.flatten *>
                         take(totalSize)
                     }))
      _ <- deferred.get
      _ <- catsForkAll(List.fill(publisherParallelism)(catsRepeat(totalSize / publisherParallelism)(hub.publish(0))))
      _ <- catsJoinAll(subscribers)
    } yield 0

    io.unsafeRunSync()
  }

  def catsSequential(makeHub: CIO[CatsHubLike[Int]]): Int = {
    import cats.effect._

    val io = for {
      ref       <- IO.ref(subscriberParallelism)
      deferred1 <- Deferred[IO, Unit]
      deferred2 <- Deferred[IO, Unit]
      hub       <- makeHub
      subscribers <- catsForkAll(List.fill(subscriberParallelism)(hub.subscribe.use { take =>
                       ref.modify { n =>
                         if (n == 1) (n - 1, deferred1.complete(()))
                         else (n - 1, IO.unit)
                       }.flatten *>
                         deferred2.get *>
                         take(totalSize)
                     }))
      _ <- deferred1.get
      _ <- catsRepeat(totalSize)(hub.publish(0))
      _ <- deferred2.complete(())
      _ <- catsJoinAll(subscribers)
    } yield 0

    io.unsafeRunSync()
  }

  def zioParallel(makeHub: UIO[ZIOHubLike[Int]]): Int = {

    val io = for {
      ref     <- Ref.make(subscriberParallelism)
      promise <- Promise.make[Nothing, Unit]
      hub     <- makeHub
      subscribers <- zioForkAll(List.fill(subscriberParallelism)(hub.subscribe.use { take =>
                       promise.succeed(()).whenZIO(ref.updateAndGet(_ - 1).map(_ == 0)) *>
                         take(totalSize)
                     }))
      _ <- promise.await
      _ <- zioForkAll(List.fill(publisherParallelism)(zioRepeat(totalSize / publisherParallelism)(hub.publish(0))))
      _ <- zioJoinAll(subscribers)
    } yield 0

    unsafeRun(io)
  }

  def zioSequential(makeHub: UIO[ZIOHubLike[Int]]): Int = {

    val io = for {
      ref      <- Ref.make(subscriberParallelism)
      promise1 <- Promise.make[Nothing, Unit]
      promise2 <- Promise.make[Nothing, Unit]
      hub      <- makeHub
      subscribers <- zioForkAll(List.fill(subscriberParallelism)(hub.subscribe.use { take =>
                       promise1.succeed(()).whenZIO(ref.updateAndGet(_ - 1).map(_ == 0)) *>
                         promise2.await *>
                         take(totalSize)
                     }))
      _ <- promise1.await
      _ <- zioRepeat(totalSize)(hub.publish(0))
      _ <- promise2.succeed(())
      _ <- zioJoinAll(subscribers)
    } yield 0

    unsafeRun(io)
  }

  def zioForkAll[R, E, A](as: List[ZIO[R, E, A]]): ZIO[R, Nothing, List[Fiber[E, A]]] =
    ZIO.foreach(as)(_.fork)

  def zioJoinAll[E, A](as: List[Fiber[E, A]]): ZIO[Any, E, List[A]] =
    ZIO.foreach(as)(_.join)

  def zioRepeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    if (n <= 1) zio
    else zio *> zioRepeat(n - 1)(zio)

  def catsForkAll[A](as: List[CIO[A]]): CIO[List[CFiber[CIO, Throwable, A]]] =
    as.traverse(_.start)

  def catsJoinAll[A](as: List[CFiber[CIO, Throwable, A]]): CIO[List[A]] =
    as.traverse(_.joinWithNever)

  def catsRepeat[A](n: Int)(io: CIO[A]): CIO[A] =
    if (n <= 1) io
    else io.flatMap(_ => catsRepeat(n - 1)(io))
}
