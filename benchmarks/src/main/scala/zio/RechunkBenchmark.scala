package zio

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio.BenchmarkUtil.unsafeRun
import zio.stream.{ZPipeline, ZStream}

import java.util.concurrent.TimeUnit

@State(JScope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 1)
@Warmup(iterations = 15, timeUnit = TimeUnit.SECONDS, time = 1)
@Fork(value = 1)
class RechunkBenchmark {

  @Param(Array("10000"))
  var chunkCount: Int = _

  @Param(Array("10"))
  var hugeChunkCount: Int = _

  @Param(Array("10"))
  var smallChunkSize: Int = _

  @Param(Array("10000"))
  var largeChunkSize: Int = _

  @Param(Array("10000000"))
  var hugeChunkSize: Int = _

  var smallChunks: IndexedSeq[Chunk[Int]] = _
  var largeChunks: IndexedSeq[Chunk[Int]] = _
  var hugeChunks: IndexedSeq[Chunk[Int]]  = _

  @Setup
  def setup(): Unit = {
    smallChunks = (1 to chunkCount).map(i => Chunk.fromArray(Array.fill(smallChunkSize)(i)))
    largeChunks = (1 to chunkCount).map(i => Chunk.fromArray(Array.fill(largeChunkSize)(i)))
    hugeChunks = (1 to hugeChunkCount).map(i => Chunk.fromArray(Array.fill(hugeChunkSize)(i)))
  }

  @Benchmark
  def rechunkSmallToLarge: Long = {
    val result = ZStream.fromChunks(smallChunks: _*).via(ZPipeline.rechunk(largeChunkSize)).runCount
    unsafeRun(result)
  }

  @Benchmark
  def rechunkLargeToSmall: Long = {
    val result = ZStream.fromChunks(largeChunks: _*).via(ZPipeline.rechunk(smallChunkSize)).runCount
    unsafeRun(result)
  }

  @Benchmark
  def rechunkSmallToSmall: Long = {
    val result = ZStream.fromChunks(smallChunks: _*).via(ZPipeline.rechunk(smallChunkSize)).runCount
    unsafeRun(result)
  }

  @Benchmark
  def rechunkSmallTo1: Long = {
    val result = ZStream.fromChunks(smallChunks: _*).via(ZPipeline.rechunk(1)).runCount
    unsafeRun(result)
  }

  @Benchmark
  def rechunkHugeToLarge: Long = {
    val result = ZStream.fromChunks(hugeChunks: _*).via(ZPipeline.rechunk(largeChunkSize)).runCount
    unsafeRun(result)
  }

  @Benchmark
  def rechunkLargeToHuge: Long = {
    val result = ZStream.fromChunks(largeChunks: _*).via(ZPipeline.rechunk(hugeChunkSize)).runCount
    unsafeRun(result)
  }
}
