package zio

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import org.scalacheck

import zio.BenchmarkUtil.unsafeRun
import zio.test.Gen

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class GenBenchmarks {

  @Param(Array("1000"))
  var listSize: Int = _

  @Param(Array("1000"))
  var elementSize: Int = _
  @Benchmark
  def zioDouble: List[Double] =
    unsafeRun(Gen.listOfN(listSize)(Gen.uniform).sample.map(_.value).runHead.some)

  @Benchmark
  def zioIntListsOfSizeN: List[List[Int]] =
    unsafeRun(
      Gen
        .listOfN(listSize)(Gen.listOfN(elementSize)(Gen.int))
        .sample
        .map(_.value)
        .runHead
        .some
    )

  @Benchmark
  def zioStringsOfSizeN: List[String] =
    unsafeRun(
      Gen
        .listOfN(listSize)(Gen.stringN(elementSize)(Gen.char))
        .sample
        .map(_.value)
        .runHead
        .some
    )

  @Benchmark
  def scalaCheckDoubles: List[Double] =
    scalacheck.Gen.listOfN(listSize, scalacheck.Gen.choose(0.0, 1.0)).sample.get

  @Benchmark
  def scalaCheckIntListsOfSizeN: List[List[Int]] =
    scalacheck.Gen
      .listOfN(listSize, scalacheck.Gen.listOfN(elementSize, scalacheck.Gen.choose(Int.MinValue, Int.MaxValue)))
      .sample
      .get

  @Benchmark
  def scalaCheckStringsOfSizeN: List[String] =
    scalacheck.Gen
      .listOfN(listSize, scalacheck.Gen.listOfN(elementSize, scalacheck.Gen.alphaChar).map(_.mkString))
      .sample
      .get

  @Benchmark
  def hedgehogDoubles: List[Double] =
    hedgehog.Gen
      .list(hedgehog.Gen.double(hedgehog.Range.constant(0.0, 1.0)), hedgehog.Range.constant(0, listSize))
      .run(hedgehog.Size(0), hedgehog.core.Seed.fromTime())
      .value
      ._2
      .get

  @Benchmark
  def hedgehogIntListsOfSizeN: List[List[Int]] =
    hedgehog.Gen
      .int(hedgehog.Range.constant(Int.MinValue, Int.MaxValue))
      .list(hedgehog.Range.constant(0, elementSize))
      .list(hedgehog.Range.constant(0, listSize))
      .run(hedgehog.Size(0), hedgehog.core.Seed.fromTime())
      .value
      ._2
      .get

  @Benchmark
  def hedgehogStringsOfSizeN: List[String] =
    hedgehog.Gen
      .string(hedgehog.Gen.alpha, hedgehog.Range.constant(0, elementSize))
      .list(hedgehog.Range.constant(0, listSize))
      .run(hedgehog.Size(0), hedgehog.core.Seed.fromTime())
      .value
      ._2
      .get

  @Benchmark
  def nyayaDoubles: List[Double] =
    nyaya.gen.Gen.double.list.sample()

  @Benchmark
  def nyayaIntListsOfSizeN: List[List[Int]] =
    nyaya.gen.Gen.int.list(0 to listSize).list(0 to elementSize).sample()

  @Benchmark
  def nyayaStringsOfSizeN: List[String] =
    nyaya.gen.Gen.string(0 to elementSize).list(0 to listSize).sample()

}
