package zio.chunks

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import zio._

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
class ChunkMapBenchmarks {
  @Param(Array("1000"))
  var size: Int = _

  var intArray: Array[Int]   = _
  var intChunk: Chunk[Int]   = _
  var intVector: Vector[Int] = _
  var intList: List[Int]     = _

  var stringArray: Array[String]   = _
  var stringChunk: Chunk[String]   = _
  var stringVector: Vector[String] = _
  var stringList: List[String]     = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    intArray = (1 to size).toArray
    stringArray = intArray.map(_.toString)
    intChunk = Chunk.fromArray(intArray)
    stringChunk = intChunk.map(_.toString)
    intVector = intArray.toVector
    stringVector = intVector.map(_.toString)
    intList = intArray.toList
    stringList = intList.map(_.toString)
  }

  @Benchmark
  def mapIntArray(): Array[Int] = intArray.map(_ * 2)

  @Benchmark
  def mapStringArray(): Array[String] = stringArray.map(_ + "123")

  @Benchmark
  def mapIntChunk(): Chunk[Int] = intChunk.map(_ * 2)

  @Benchmark
  def mapStringChunk(): Chunk[String] = stringChunk.map(_ + "123")

  @Benchmark
  def mapIntVector(): Vector[Int] = intVector.map(_ * 2)

  @Benchmark
  def mapStringVector(): Vector[String] = stringVector.map(_ + "123")

  @Benchmark
  def mapIntList(): List[Int] = intList.map(_ * 2)

  @Benchmark
  def mapStringList(): List[String] = stringList.map(_ + "123")

  @Benchmark
  def mapZIO(): Unit =
    BenchmarkUtil.unsafeRun(stringChunk.mapZIODiscard(_ => ZIO.unit))

}
