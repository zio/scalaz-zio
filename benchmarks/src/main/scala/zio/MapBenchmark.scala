package zio

import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations._
import zio.BenchmarkUtil._

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class MapBenchmark {
  @Param(Array("500"))
  var depth: Int = _

  @Benchmark
  def futureMap(): BigInt = {
    import scala.concurrent.duration.Duration.Inf
    import scala.concurrent.{Await, Future}

    @tailrec
    def sumTo(t: Future[BigInt], n: Int): Future[BigInt] =
      if (n <= 1) t
      else sumTo(t.map(_ + n), n - 1)

    Await.result(sumTo(Future(0), depth), Inf)
  }

  @Benchmark
  def completableFutureMap(): BigInt = {
    import java.util.concurrent.CompletableFuture

    @tailrec
    def sumTo(t: CompletableFuture[BigInt], n: Int): CompletableFuture[BigInt] =
      if (n <= 1) t
      else sumTo(t.thenApply(_ + n), n - 1)

    sumTo(CompletableFuture.completedFuture(0), depth)
      .get()
  }

  @Benchmark
  def monoMap(): BigInt = {
    import reactor.core.publisher.Mono

    @tailrec
    def sumTo(t: Mono[BigInt], n: Int): Mono[BigInt] =
      if (n <= 1) t
      else sumTo(t.map(_ + n), n - 1)

    sumTo(Mono.fromCallable(() => 0), depth)
      .block()
  }

  @Benchmark
  def rxSingleMap(): BigInt = {
    import io.reactivex.Single

    @tailrec
    def sumTo(t: Single[BigInt], n: Int): Single[BigInt] =
      if (n <= 1) t
      else sumTo(t.map(_ + n), n - 1)

    sumTo(Single.fromCallable(() => 0), depth)
      .blockingGet()
  }

  @Benchmark
  def twitterFutureMap(): BigInt = {
    import com.twitter.util.{Await, Future}

    @tailrec
    def sumTo(t: Future[BigInt], n: Int): Future[BigInt] =
      if (n <= 1) t
      else sumTo(t.map(_ + n), n - 1)

    Await.result(sumTo(Future.apply(0), depth))

  }

  @Benchmark
  def zioMap(): BigInt = zioMap(BenchmarkUtil)

  private[this] def zioMap(runtime: Runtime[Any]): BigInt = {
    @tailrec
    def sumTo(t: UIO[BigInt], n: Int): UIO[BigInt] =
      if (n <= 1) t
      else sumTo(t.map(_ + n), n - 1)

    runtime.unsafeRun(sumTo(IO.succeed(0), depth))
  }

  @Benchmark
  def catsMap(): BigInt = {
    import cats.effect._

    @tailrec
    def sumTo(t: IO[BigInt], n: Int): IO[BigInt] =
      if (n <= 1) t
      else sumTo(t.map(_ + n), n - 1)

    sumTo(IO(0), depth).unsafeRunSync()
  }
}
