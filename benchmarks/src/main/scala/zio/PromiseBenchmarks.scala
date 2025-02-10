package zio

import cats.effect.kernel.Deferred
import cats.syntax.traverse._
import cats.instances.list._
import cats.effect.unsafe.implicits.global
import cats.effect.{IO => CIO}
import cats.syntax.foldable._
import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(value = 3)
class PromiseBenchmarks {

  val n            = 100000
  val waiters: Int = 16

  @Benchmark
  def zioPromiseAwaitDone(): Unit = {

    val io =
      Promise
        .make[Nothing, Unit]
        .flatMap { promise =>
          promise.succeed(()) *> promise.await
        }
        .repeatN(n)

    unsafeRun(io)
  }

  @Benchmark
  def catsPromiseAwaitDone(): Unit = {

    val io =
      Deferred[CIO, Unit].flatMap { promise =>
        promise.complete(()).flatMap(_ => promise.get)
      }.replicateA_(n)

    io.unsafeRunSync()
  }

  @Benchmark
  def zioPromiseMultiAwaitDone(): Unit = {
    def createWaiters(promise: Promise[Nothing, Unit]): ZIO[Any, Nothing, Seq[Fiber[Nothing, Unit]]] =
      ZIO.foreach(Range(0, waiters))(_ => promise.await.forkDaemon)

    val io = Promise
      .make[Nothing, Unit]
      .flatMap { promise =>
        for {
          fibers <- createWaiters(promise)
          _      <- promise.done(Exit.unit)
          _      <- ZIO.foreachDiscard(fibers)(_.join)
        } yield ()
      }
      .repeatN(1023)

    unsafeRun(io)
  }

  @Benchmark
  def catsPromiseMultiAwaitDone(): Unit = {
    def createWaiters(promise: Deferred[CIO, Unit]): CIO[List[cats.effect.Fiber[CIO, Throwable, Unit]]] =
      List.range(0, waiters).traverse(_ => promise.get.start)

    val io =
      Deferred[CIO, Unit].flatMap { promise =>
        for {
          fibers <- createWaiters(promise)
          _      <- promise.complete(())
          _      <- fibers.traverse_(_.join)
        } yield ()
      }.replicateA_(1023)

    io.unsafeRunSync()
  }
}
