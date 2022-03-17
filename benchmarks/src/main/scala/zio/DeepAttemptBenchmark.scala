package zio

import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._

import java.util.concurrent.TimeUnit
import scala.concurrent.Await

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class DeepAttemptBenchmark {
  case class ZIOError(message: String)

  @Param(Array("1000"))
  var depth: Int = _

  def halfway: Int = depth / 2

  @Benchmark
  def futureDeepAttempt(): BigInt = {
    import scala.concurrent.Future
    import scala.concurrent.duration.Duration.Inf

    def descend(n: Int): Future[BigInt] =
      if (n == depth) Future.failed(new Exception("Oh noes!"))
      else if (n == halfway) descend(n + 1).recover { case _ => 50 }
      else descend(n + 1).map(_ + n)

    Await.result(descend(0), Inf)
  }

  @Benchmark
  def completableFutureDeepAttempt(): BigInt = {
    import java.util.concurrent.CompletableFuture

    def descent(n: Int): CompletableFuture[BigInt] =
      if (n == depth) {
        val f = new CompletableFuture[BigInt]()
        f.completeExceptionally(new Exception("Oh noes!"))
        f
      } else if (n == halfway) {
        descent(n + 1).exceptionally(_ => 50)
      } else {
        descent(n + 1).thenApply(_ + n)
      }

    descent(0).get()
  }

  @Benchmark
  def monoDeepAttempt(): BigInt = {
    import reactor.core.publisher.Mono

    def descent(n: Int): Mono[BigInt] =
      if (n == depth)
        Mono.error(new Exception("Oh noes!"))
      else if (n == halfway)
        descent(n + 1).onErrorReturn(BigInt.apply(50))
      else
        descent(n + 1).map(_ + n)

    descent(0).block()
  }

  @Benchmark
  def rxSingleDeepAttempt(): BigInt = {
    import io.reactivex.Single

    def descent(n: Int): Single[BigInt] =
      if (n == depth)
        Single.error(new Exception("Oh noes!"))
      else if (n == halfway)
        descent(n + 1)
          .onErrorReturn(_ => 50)
      else
        descent(n + 1).map(_ + n)

    descent(0).blockingGet()
  }

  @Benchmark
  def twitterDeepAttempt(): BigInt = {
    import com.twitter.util.{Await, Future}

    def descent(n: Int): Future[BigInt] =
      if (n == depth)
        Future.exception(new Error("Oh noes!"))
      else if (n == halfway)
        descent(n + 1).handle { case _ => 50 }
      else descent(n + 1).map(_ + n)

    Await.result(descent(0))
  }

  @Benchmark
  def zioDeepAttempt(): BigInt = {
    def descend(n: Int): IO[ZIOError, BigInt] =
      if (n == depth) IO.fail(ZIOError("Oh noes!"))
      else if (n == halfway) descend(n + 1).fold[BigInt](_ => 50, identity)
      else descend(n + 1).map(_ + n)

    unsafeRun(descend(0))
  }

  @Benchmark
  def zioDeepAttemptBaseline(): BigInt = {
    def descend(n: Int): IO[Error, BigInt] =
      if (n == depth) IO.fail(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).fold[BigInt](_ => 50, identity)
      else descend(n + 1).map(_ + n)

    unsafeRun(descend(0))
  }

  @Benchmark
  def catsDeepAttempt(): BigInt = {
    import cats.effect._

    def descend(n: Int): IO[BigInt] =
      if (n == depth) IO.raiseError(new Error("Oh noes!"))
      else if (n == halfway) descend(n + 1).attempt.map(_.fold(_ => 50, a => a))
      else descend(n + 1).map(_ + n)

    descend(0).unsafeRunSync()
  }
}
