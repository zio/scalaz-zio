package zio

import akka.NotUsed
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

  @Benchmark
  def zioFlatMapPar: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .flatMapPar(4)(i => ZStream(i, i + 1))
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioFlatMapParChunks: Long = {
    val result = ZStream
      .fromIterable(zioChunks)
      .flatMapPar(4) { c =>
        val cc = c.flatMap(i => Chunk(i, i + 1))
        ZStream.fromChunk(cc)
      }
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioFlatMapParChunksFair: Long = {
    val result = ZStream
      .fromIterable(zioChunks)
      .flatMapPar(4) { c =>
        ZStream
          .fromChunk(c)
          .flatMap(i => ZStream(i, i + 1))
      }
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def akkaFlatMapPar: Long = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .flatMapMerge(
        4,
        i => {
          val ints: scala.collection.immutable.Iterable[Int] = Vector(i, i + 1)
          val akkaSrc: AkkaSource[Int, NotUsed]              = AkkaSource(ints)
          akkaSrc
        }
      )
      .toMat(AkkaSink.fold(0L)((c, ignored) => c + 1L))(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2FlatMapPar: Long =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .map { i =>
        FS2Stream(i, i + 1)
      }
      .covary[CatsIO]
      .parJoin(4)
      .compile
      .fold(0L)((c, _) => c + 1L)
      .unsafeRunSync()

  @Benchmark
  def akkaMerge: Long = {
    val src = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
    val program = src
      .merge(src)
      .toMat(AkkaSink.fold(0L)((c, ignored) => c + 1L))(Keep.right)
    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def akkaMergeChunks: Long = {
    val src = AkkaSource
      .fromIterator(() => akkaChunks.iterator)
    val program = src
      .merge(src)
      .toMat(AkkaSink.fold(0L)((c, arr) => c + arr.length))(Keep.right)
    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def zioMerge: Long = {
    val strm = ZStream
      .fromIterable(zioChunks)

    val result = strm
      .merge(strm)
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioMerge2: Long = {
    val strm = ZStream
      .fromIterable(zioChunks)

    val result = ZStream(strm, strm)
      .flatMapPar(2)(identity)
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioMergeWithIdentity: Long = {
    val strm = ZStream
      .fromIterable(zioChunks)

    val result = strm
      .mergeWith(strm)(
        identity,
        identity
      )
      .runCount

    unsafeRun(result)
  }

  /*@Benchmark
  def fs2Merge: Long = {
    val strm = FS2Stream(fs2Chunks: _*)
    strm
      .merge(strm)
      .fold(0L)((c, _) => c + 1L)
      .covary[CatsIO]
      .unsafeRunSync()
  }*/
}
