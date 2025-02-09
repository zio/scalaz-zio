/*
rule = Zio2Upgrade
 */
package fix

import zio._
import zio.blocking.effectBlockingIO
import zio.blocking._
import zio.blocking.Blocking
import zio.console._
import zio.duration.Duration
import zio.internal.Platform
import zio.stream.ZStream
import zio.test.Gen

@annotation.nowarn("msg=pure expression does nothing")
object Zio2Renames {

  val flatMap1 = ZIO(1).>>=((x: Int) => ZIO(x + 1))
  val flatMap2 = ZIO(1) >>= { x: Int => ZIO(x + 1) }
  val effect   = ZIO("cool")

  val halt     = ZIO.halt(Cause.fail("fail"))
  val haltWith = ZIO.haltWith(_ => Cause.fail("fail"))

  val toManaged_ = effect.toManaged_
  val toManaged  = effect.toManaged(_ => UIO.unit)
  val bimap      = effect.bimap(_ => UIO.unit, _ => UIO.unit)

  val printline = console.putStrLn("HEY")

  // foreachParN
  val foreachParN = ZIO.foreachParN(4)(List(1,2)) { int =>
    ZIO.succeed(int)
  }

  // foreachParN[Types]
  val foreachParNWithTypes = ZIO.foreachParN[Any, Nothing, Int, Int, List](4)(List(1,2)) { int =>
    ZIO.succeed(int)
  }

  // Generators
  Gen.anyInt
  Gen.anyString
  Gen.anyUnicodeChar
  Gen.anyASCIIChar
  Gen.anyByte
  Gen.anyChar
  Gen.anyDouble
  Gen.anyFloat
  Gen.anyHexChar
  Gen.anyLong
  Gen.anyLowerHexChar
  Gen.anyShort
  Gen.anyUpperHexChar
  Gen.anyASCIIString
  Gen.anyDayOfWeek
  Gen.anyFiniteDuration
  Gen.anyUUID
  Gen.anyLocalDate
  Gen.anyLocalTime
  Gen.anyLocalDateTime
  Gen.anyMonth
  Gen.anyMonthDay
  Gen.anyOffsetDateTime
  Gen.anyOffsetTime
  Gen.anyPeriod
  Gen.anyYear
  Gen.anyYearMonth
  Gen.anyZonedDateTime
  Gen.anyZoneOffset
  Gen.anyZoneId

  // Blocking
  effectBlockingIO(1)

  ZIO.succeed(1).on _

  Cause.fail("Die").interrupted

  Fiber.Id

  zio.duration.Duration
  
  val x: Layer[Nothing, zio.random.Random] = zio.random.Random.live

  zio.internal.Executor

  Platform
    .fromExecutor(???)
  
  zio.internal.Platform
    .fromExecutor(???)

  Chunk.succeed(1).mapM(???)
  ZStream.succeed("hi") >>= (x => ZStream.succeed(x))

  ZIO.executor.map(_.asEC)
  
  ZManaged.fromFunction( (x: Int) => x)
  ZManaged.fromFunctionM( (x: Int) => ZManaged.succeed(x))
  ZManaged.accessManaged( (x: Int) => ZManaged.succeed(x))

  ZIO.serviceWith
}
