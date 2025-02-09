package zio

import org.openjdk.jmh.annotations.{Scope => JScope, _}
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(1)
class ZEnvironmentBenchmark {
  import BenchmarkUtil._
  import BenchmarkedEnvironment._

  implicit val u: Unsafe = Unsafe.unsafe

  var env: ZEnvironment[Env]           = _
  var smallEnv: ZEnvironment[SmallEnv] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    env = BenchmarkedEnvironment.makeLarge()
    smallEnv = BenchmarkedEnvironment.makeSmall()
  }

  @Benchmark
  def access(bh: Blackhole) = {
    bh.consume(env.get[Foo040])
    bh.consume(env.get[Foo041])
    bh.consume(env.get[Foo042])
    bh.consume(env.get[Foo043])
    bh.consume(env.get[Foo044])
    bh.consume(env.get[Foo045])
    bh.consume(env.get[Foo046])
    bh.consume(env.get[Foo047])
    bh.consume(env.get[Foo048])
    bh.consume(env.get[Foo049])
  }

  @Benchmark
  def add() =
    env.add(new Bar000).add(new Bar001).add(new Bar002).add(new Bar003).add(new Bar004)

  @Benchmark
  def addGetOne() =
    env.add(new Bar000).get[Bar000]

  @Benchmark
  @OperationsPerInvocation(10000)
  def addGetRepeat(bh: Blackhole) = {
    var i = 0
    var e = env
    while (i < 10000) {
      e = e
        .add(new Foo040)
        .add(new Foo041)
        .add(new Foo042)
        .add(new Foo043)
        .add(new Foo044)
        .add(new Foo045)
        .add(new Foo046)
        .add(new Foo047)
        .add(new Foo048)
        .add(new Foo049)
      bh.consume(e.get[Foo040])
      i += 1
    }
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def addGetRepeatBaseline(bh: Blackhole) = {
    var i = 0
    var e = env
    while (i < 10000) {
      e = env
        .add(new Foo040)
        .add(new Foo041)
        .add(new Foo042)
        .add(new Foo043)
        .add(new Foo044)
        .add(new Foo045)
        .add(new Foo046)
        .add(new Foo047)
        .add(new Foo048)
        .add(new Foo049)
      bh.consume(e.get[Foo040])
      i += 1
    }
  }

  @Benchmark
  def addGetMulti(bh: Blackhole) = {
    val e = env.add(new Bar001)
    bh.consume(e.get[Bar001])
    bh.consume(e.get[Foo040])
    bh.consume(e.get[Foo041])
    bh.consume(e.get[Foo042])
    bh.consume(e.get[Foo043])
    bh.consume(e.get[Foo044])
    bh.consume(e.get[Foo045])
    bh.consume(e.get[Foo046])
    bh.consume(e.get[Foo047])
    bh.consume(e.get[Foo048])
    bh.consume(e.get[Foo049])
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def accessAfterScoped() = {
    val _ = env.get[Foo025]
    unsafe.run(
      ZIO
        .foreachDiscard(1 to 10000)(_ => ZIO.scoped(ZIO.environmentWith[Foo025](_.get[Foo025])))
        .provideEnvironment(env)
    )
  }

  @Benchmark
  @OperationsPerInvocation(10000)
  def accessAfterScopedUncached() =
    unsafe.run(
      ZIO
        .foreachDiscard(1 to 10000)(_ => ZIO.scoped(ZIO.environmentWith[Foo025](_.get[Foo025])))
        .provideEnvironment(env)
    )

  @Benchmark
  @OperationsPerInvocation(10000)
  def accessScope() =
    unsafe.run(
      ZIO
        .foreachDiscard(1 to 10000)(_ => ZIO.scoped(ZIO.environmentWith[Scope](_.get[Scope])))
        .provideEnvironment(env)
    )

  @Benchmark
  def union() =
    env.unionAll(smallEnv)

  @Benchmark
  def prune() =
    env.prune[Foo001 with Foo002 with Foo003]

}

object BenchmarkedEnvironment {

  final class Bar000
  final class Bar001
  final class Bar002
  final class Bar003
  final class Bar004

  final class Foo000
  final class Foo001
  final class Foo002
  final class Foo003
  final class Foo004
  final class Foo005
  final class Foo006
  final class Foo007
  final class Foo008
  final class Foo009

  final class Foo010
  final class Foo011
  final class Foo012
  final class Foo013
  final class Foo014
  final class Foo015
  final class Foo016
  final class Foo017
  final class Foo018
  final class Foo019

  final class Foo020
  final class Foo021
  final class Foo022
  final class Foo023
  final class Foo024
  final class Foo025
  final class Foo026
  final class Foo027
  final class Foo028
  final class Foo029

  final class Foo030
  final class Foo031
  final class Foo032
  final class Foo033
  final class Foo034
  final class Foo035
  final class Foo036
  final class Foo037
  final class Foo038
  final class Foo039

  final class Foo040
  final class Foo041
  final class Foo042
  final class Foo043
  final class Foo044
  final class Foo045
  final class Foo046
  final class Foo047
  final class Foo048
  final class Foo049

  def makeSmall(): ZEnvironment[SmallEnv] =
    ZEnvironment.empty
      .add(new Bar000)
      .add(new Bar001)
      .add(new Bar002)
      .add(new Bar003)
      .add(new Bar004)

  def makeLarge(): ZEnvironment[Env] =
    ZEnvironment.empty
      .add(new Foo000)
      .add(new Foo001)
      .add(new Foo002)
      .add(new Foo003)
      .add(new Foo004)
      .add(new Foo005)
      .add(new Foo006)
      .add(new Foo007)
      .add(new Foo008)
      .add(new Foo009)
      .add(new Foo010)
      .add(new Foo011)
      .add(new Foo012)
      .add(new Foo013)
      .add(new Foo014)
      .add(new Foo015)
      .add(new Foo016)
      .add(new Foo017)
      .add(new Foo018)
      .add(new Foo019)
      .add(new Foo020)
      .add(new Foo021)
      .add(new Foo022)
      .add(new Foo023)
      .add(new Foo024)
      .add(new Foo025)
      .add(new Foo026)
      .add(new Foo027)
      .add(new Foo028)
      .add(new Foo029)
      .add(new Foo030)
      .add(new Foo031)
      .add(new Foo032)
      .add(new Foo033)
      .add(new Foo034)
      .add(new Foo035)
      .add(new Foo036)
      .add(new Foo037)
      .add(new Foo038)
      .add(new Foo039)
      .add(new Foo040)
      .add(new Foo041)
      .add(new Foo042)
      .add(new Foo043)
      .add(new Foo044)
      .add(new Foo045)
      .add(new Foo046)
      .add(new Foo047)
      .add(new Foo048)
      .add(new Foo049)

  type SmallEnv = Bar000 with Bar001 with Bar002 with Bar003 with Bar004

  type Env = Foo000
    with Foo001
    with Foo002
    with Foo003
    with Foo004
    with Foo005
    with Foo006
    with Foo007
    with Foo008
    with Foo009
    with Foo010
    with Foo011
    with Foo012
    with Foo013
    with Foo014
    with Foo015
    with Foo016
    with Foo017
    with Foo018
    with Foo019
    with Foo020
    with Foo021
    with Foo022
    with Foo023
    with Foo024
    with Foo025
    with Foo026
    with Foo027
    with Foo028
    with Foo029
    with Foo030
    with Foo031
    with Foo032
    with Foo033
    with Foo034
    with Foo035
    with Foo036
    with Foo037
    with Foo038
    with Foo039
    with Foo040
    with Foo041
    with Foo042
    with Foo043
    with Foo044
    with Foo045
    with Foo046
    with Foo047
    with Foo048
    with Foo049

}

/*
[info] Benchmark                                         Mode  Cnt        Score        Error  Units
[info] ZEnvironmentBenchmark.access                     thrpt    6  9350561.124 ±  74526.515  ops/s
[info] ZEnvironmentBenchmark.accessAfterScoped          thrpt    6  1959546.633 ±  26073.619  ops/s
[info] ZEnvironmentBenchmark.accessAfterScopedUncached  thrpt    6   832135.422 ±  31198.798  ops/s
[info] ZEnvironmentBenchmark.accessScope                thrpt    6  1904231.684 ±  19471.317  ops/s
[info] ZEnvironmentBenchmark.add                        thrpt    6  1828685.550 ±  12715.251  ops/s
[info] ZEnvironmentBenchmark.addGetMulti                thrpt    6   476091.751 ±  21265.381  ops/s
[info] ZEnvironmentBenchmark.addGetOne                  thrpt    6  8632006.222 ± 333867.899  ops/s
[info] ZEnvironmentBenchmark.addGetRepeat               thrpt    6   459445.983 ±  15382.794  ops/s
[info] ZEnvironmentBenchmark.addGetRepeatBaseline       thrpt    6   560367.659 ±  18064.408  ops/s
[info] ZEnvironmentBenchmark.prune                      thrpt    6   238747.880 ±   5288.523  ops/s
[info] ZEnvironmentBenchmark.union                      thrpt    6  2658024.830 ±  53314.151  ops/s
 */
