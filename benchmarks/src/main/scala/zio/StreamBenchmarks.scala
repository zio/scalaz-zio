package zio

import akka.Done
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
import scala.concurrent.{Await, ExecutionContextExecutor}

@State(JScope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 1)
@Warmup(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 1)
@Fork(value = 1)
class StreamBenchmarks {
  @Param(Array("10000"))
  var chunkCount: Int = _

  @Param(Array("5000"))
  var chunkSize: Int = _

  implicit val system: ActorSystem          = ActorSystem("benchmarks")
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  var akkaChunks: IndexedSeq[Array[Int]]   = _
  var fs2Chunks: IndexedSeq[FS2Chunk[Int]] = _
  var zioChunks: IndexedSeq[Chunk[Int]]    = _
  var zioChunkChunk: Chunk[Int]            = _

  @Setup
  def setup(): Unit = {
    akkaChunks = (1 to chunkCount).map(i => Array.fill(chunkSize)(i))
    fs2Chunks = (1 to chunkCount).map(i => FS2Chunk.array(Array.fill(chunkSize)(i)))
    zioChunks = (1 to chunkCount).map(i => Chunk.fromArray(Array.fill(chunkSize)(i)))
    zioChunkChunk = Chunk.fromArray((1 to chunkCount).toArray).flatMap(i => Chunk.fromArray(Array.fill(chunkSize)(i)))
  }

  @TearDown
  def shutdown(): Unit = {
    system.terminate()
    ()
  }

  @Benchmark
  def akkaChunkFilterMapSum: Long = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .filter(_ % 2 == 0)
      .map(_.toLong)
      .toMat(AkkaSink.fold(0L)(_ + _))(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2ChunkFilterMapSum: Long = {
    val stream = FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .filter(_ % 2 == 0)
      .map(_.toLong)
      .covary[CatsIO]
      .compile
      .fold(0L)(_ + _)
    stream.unsafeRunSync()
  }

  @Benchmark
  def zioChunkFilterMapSum: Long = {
    val stream = ZStream
      .fromChunks(zioChunks: _*)
      .filter(_ % 2 == 0)
      .map(_.toLong)

    val sink   = ZSink.foldLeftChunks(0L)((s, as: Chunk[Long]) => as.fold(s)(_ + _))
    val result = stream.run(sink)

    unsafeRun(result)
  }

  @Benchmark
  def zioChunkChunkFilterMapSum: Long =
    zioChunkChunk
      .filter(_ % 2 == 0)
      .map(_.toLong)
      .fold(0L)(_ + _)

  @Benchmark
  def fs2MapAccum: Option[(Long, Long)] =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .mapAccumulate(0L) { case (acc, i) =>
        val added = acc + i
        (added, added)
      }
      .covary[CatsIO]
      .compile
      .last
      .unsafeRunSync()

  @Benchmark
  def zioMapAccum: Option[Long] = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .mapAccum(0L) { case (acc, i) =>
        val added = acc + i
        (added, added)
      }
      .runLast

    unsafeRun(result)
  }

  @Benchmark
  def akkaSliding: Long = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .sliding(100, 1)
      .toMat(AkkaSink.fold(0L)((c, _) => c + 1L))(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2Sliding: Long =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .sliding(100, 1)
      .covary[CatsIO]
      .compile
      .fold(0L)((c, _) => c + 1L)
      .unsafeRunSync()

  @Benchmark
  def zioSliding: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .sliding(100, 1)
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def akkaTakeWhile: Option[Int] = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .takeWhile(i => (i < (chunkCount * chunkSize) / 2))
      .toMat(AkkaSink.lastOption)(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2TakeWhile: Option[Int] =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .takeWhile(i => (i < (chunkCount * chunkSize) / 2))
      .covary[CatsIO]
      .compile
      .last
      .unsafeRunSync()

  @Benchmark
  def zioTakeWhile: Option[Int] = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .takeWhile(i => (i < (chunkCount * chunkSize) / 2))
      .runLast

    unsafeRun(result)
  }

  @Benchmark
  def akkaGroupWithin: Long = {
    val program = AkkaSource
      .fromIterator(() => akkaChunks.iterator.flatten)
      .groupedWithin(100, ScalaDuration(1, TimeUnit.SECONDS))
      .toMat(AkkaSink.fold(0L)((c, _) => c + 1L))(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2GroupWithin: Long =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .groupWithin[CatsIO](100, ScalaDuration(1, TimeUnit.SECONDS))
      .covary[CatsIO]
      .compile
      .fold(0L)((c, _) => c + 1L)
      .unsafeRunSync()

  @Benchmark
  def zioGroupWithin: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .groupedWithin(100, Duration(1, TimeUnit.SECONDS))
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def fs2GroupAdjacentBy: Long =
    FS2Stream(fs2Chunks: _*)
      .flatMap(FS2Stream.chunk(_))
      .groupAdjacentBy(_ % 2)
      .covary[CatsIO]
      .compile
      .fold(0L)((c, _) => c + 1L)
      .unsafeRunSync()

  @Benchmark
  def zioGroupAdjacentBy: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .groupAdjacentBy(_ % 2)
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioGroupByKey: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .groupByKey(_ % 2) { case (k, s) =>
        ZStream.fromZIO(s.runCollect.map(vs => k -> vs))
      }
      .runCount

    unsafeRun(result)
  }

  val chunkToConst: Chunk[Int] => Int                                                        = _ => 1
  val strmChunkToStrmConsts: ZStream[Any, Nothing, Chunk[Int]] => ZStream[Any, Nothing, Int] = _.map(chunkToConst)

  @Benchmark
  def zioChunkToConstDirect: Long = {
    val result =
      strmChunkToStrmConsts(
        ZStream
          .fromChunks(zioChunks: _*)
          .chunks
      ).runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioChunkToConstBaseline: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .chunks
      .via(ZPipeline.map(chunkToConst))
      .runCount

    unsafeRun(result)
  }

  @Benchmark
  def zioChunkToConstFromFunction: Long = {
    val result = ZStream
      .fromChunks(zioChunks: _*)
      .chunks
      .via(ZPipeline.fromFunction(strmChunkToStrmConsts))
      .runCount

    unsafeRun(result)
  }

}

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class CSVStreamBenchmarks {
  @Param(Array("5000"))
  var chunkSize: Int = _

  @Param(Array("100"))
  var rows: Int = _

  @Param(Array("100"))
  var cols: Int = _

  var genCsvChunks: Array[Array[Char]] = _

  implicit val system: ActorSystem          = ActorSystem("benchmarks")
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  @Setup
  def setup(): Unit =
    genCsvChunks = CSV.genChoppedCsv(rows, cols, chunkSize)

  @TearDown
  def shutdown(): Unit = {
    system.terminate()
    ()
  }

  @Benchmark
  def akkaCsvTokenize(): Done = {
    val chunks = genCsvChunks

    val program = AkkaSource
      .fromIterator(() => chunks.iterator.flatten)
      .scan((Vector.empty[Char], Vector.empty[CSV.Token])) { case ((acc, _), char) =>
        if (char == CSV.ColumnSep) {
          Vector.empty[Char] ->
            ((if (acc.nonEmpty)
                Vector(CSV.Column(acc.mkString))
              else Vector.empty[CSV.Token]) ++
              Vector(CSV.NewCol))
        } else if (char == CSV.RowSep) {
          Vector.empty[Char] ->
            ((if (acc.nonEmpty)
                Vector(CSV.Column(acc.mkString))
              else Vector.empty[CSV.Token]) ++
              Vector(CSV.NewCol))
        } else (acc :+ char) -> Vector.empty[CSV.Token]
      }
      .mapConcat(t => t._2)
      .toMat(AkkaSink.ignore)(Keep.right)

    Await.result(program.run(), ScalaDuration.Inf)
  }

  @Benchmark
  def fs2CsvTokenize(): Unit = {
    import cats.effect.unsafe.implicits.global

    val chunks = genCsvChunks.map(FS2Chunk.array(_))
    val stream = FS2Stream(chunks.toIndexedSeq: _*)
      .flatMap(FS2Stream.chunk)
      .mapAccumulate(Vector.empty[Char]) { case (acc, char) =>
        if (char == CSV.ColumnSep) {
          Vector.empty[Char] ->
            ((if (acc.nonEmpty)
                Vector(CSV.Column(acc.mkString))
              else Vector.empty[CSV.Token]) ++
              Vector(CSV.NewCol))
        } else if (char == CSV.RowSep) {
          Vector.empty[Char] ->
            ((if (acc.nonEmpty)
                Vector(CSV.Column(acc.mkString))
              else Vector.empty[CSV.Token]) ++
              Vector(CSV.NewCol))
        } else (acc :+ char) -> Vector.empty[CSV.Token]
      }
      .flatMap(t => FS2Stream(t._2))
      .covary[CatsIO]
      .compile
      .drain

    stream.unsafeRunSync()
  }

  @Benchmark
  def zioCsvTokenize(): Unit = {
    val chunks = genCsvChunks.map(Chunk.fromArray)
    val stream = ZStream
      .fromChunks(chunks.toIndexedSeq: _*)
      .mapAccum[Vector[Char], Chunk[CSV.Token]](Vector.empty[Char]) { case (acc, char) =>
        if (char == CSV.ColumnSep) {
          Vector.empty[Char] ->
            ((if (acc.nonEmpty)
                Chunk(CSV.Column(acc.mkString))
              else Chunk.empty) ++
              Chunk(CSV.NewCol))
        } else if (char == CSV.RowSep) {
          Vector.empty[Char] ->
            ((if (acc.nonEmpty)
                Chunk(CSV.Column(acc.mkString))
              else Chunk.empty) ++
              Chunk(CSV.NewCol))
        } else (acc :+ char) -> Chunk.empty
      }
      .mapConcatChunk(identity)

    unsafeRun(stream.run(ZSink.drain))
  }
}

object CSV {
  sealed trait Token
  case object NewCol               extends Token
  case object NewRow               extends Token
  case class Column(value: String) extends Token

  val ColumnSep = ','
  val RowSep    = '\n'

  def alphanumeric(random: scala.util.Random, min: Int, max: Int): String = {
    val n = scala.util.Random.nextInt(max - min + 1) + min

    random.alphanumeric.take(n).mkString
  }

  def genCsv(cols: Int, rows: Int, min: Int = 5, max: Int = 100): String = {
    val random: scala.util.Random = new scala.util.Random(rows * cols * (min + 1) * max)

    val builder = new scala.collection.mutable.StringBuilder()

    for {
      _   <- (0 to rows).toList
      col <- (0 to cols).toList
    } {
      if (col > 0) builder
      builder ++= ColumnSep.toString

      builder ++= alphanumeric(random, min, max)

      if (col == cols - 1)
        builder ++= RowSep.toString
    }

    builder.toString
  }

  def chop(n: Int, s: String): Array[Array[Char]] =
    s.grouped(n).toArray.map(_.toArray)

  def genChoppedCsv(rows: Int, cols: Int, chunkSize: Int): Array[Array[Char]] =
    chop(chunkSize, genCsv(rows, cols))
}
