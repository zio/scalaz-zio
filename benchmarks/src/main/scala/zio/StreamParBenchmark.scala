package zio

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink => AkkaSink, Source => AkkaSource}
import cats.effect.unsafe.implicits.global
import cats.effect.{IO => CatsIO}
import fs2.{Chunk => FS2Chunk, Stream => FS2Stream}
import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil._
import zio.stream._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration => ScalaDuration}
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

@State(JScope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 1)
@Warmup(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 1)
@Fork(value = 1)
class StreamParBenchmark {

  @Param(Array("10000"))
  var chunkCount: Int = _

  @Param(Array("5000"))
  var chunkSize: Int = _

  @Param(Array("50"))
  var parChunkSize: Int = _

  implicit val system: ActorSystem          = ActorSystem("benchmarks")
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  var akkaChunks: IndexedSeq[Array[Int]]   = _
  var fs2Chunks: IndexedSeq[FS2Chunk[Int]] = _
  var zioChunks: IndexedSeq[Chunk[Int]]    = _

  @Setup
  def setup(): Unit = {
    akkaChunks = (1 to chunkCount).map(i => Array.fill(parChunkSize)(i))
    fs2Chunks = (1 to chunkCount).map(i => FS2Chunk.array(Array.fill(parChunkSize)(i)))
    zioChunks = (1 to chunkCount).map(i => Chunk.fromArray(Array.fill(parChunkSize)(i)))
  }

  @TearDown
  def shutdown(): Unit = {
    system.terminate()
    ()
  }

  @Benchmark
  def akkaMapPar: Long = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .mapAsync(4)(i => Future(BigDecimal.valueOf(i.toLong).pow(3)))
      .toMat(AkkaSink.fold(0L)((c, _) => c + 1L))(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2MapPar: Long =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .mapAsync[CatsIO, BigDecimal](4)(i => CatsIO(BigDecimal.valueOf(i.toLong).pow(3)))
      .covary[CatsIO]
      .compile
      .fold(0L)((c, _) => c + 1L)
      .unsafeRunSync()

  @Benchmark
  def zioMapPar: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .mapZIOPar(4)(i => ZIO.succeed(BigDecimal.valueOf(i.toLong).pow(3)))
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def akkaMapParUnordered: Long = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .mapAsyncUnordered(4)(i => Future(BigDecimal.valueOf(i.toLong).pow(3)))
      .toMat(AkkaSink.fold(0L)((c, _) => c + 1L))(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2MapParUnordered: Long =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .mapAsyncUnordered[CatsIO, BigDecimal](4)(i => CatsIO(BigDecimal.valueOf(i.toLong).pow(3)))
      .covary[CatsIO]
      .compile
      .fold(0L)((c, _) => c + 1L)
      .unsafeRunSync()

  @Benchmark
  def zioMapParUnordered: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .mapZIOParUnordered(4)(i => ZIO.succeed(BigDecimal.valueOf(i.toLong).pow(3)))
      .runCount

    unsafeRun(result)
  }

}
