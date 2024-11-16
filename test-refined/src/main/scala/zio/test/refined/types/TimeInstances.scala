package zio.test.refined.types

import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.time.{Day, Hour, Millis, Minute, Month, Second}
import zio.test.Gen
import zio.test.magnolia.DeriveGen

object time extends TimeInstances

trait TimeInstances {
  val monthGen: Gen[Any, Month]   = Gen.int(1, 12).map(Refined.unsafeApply)
  val dayGen: Gen[Any, Day]       = Gen.int(1, 31).map(Refined.unsafeApply)
  val hourGen: Gen[Any, Hour]     = Gen.int(1, 23).map(Refined.unsafeApply)
  val minuteGen: Gen[Any, Minute] = Gen.int(1, 59).map(Refined.unsafeApply)
  val secondGen: Gen[Any, Second] = Gen.int(1, 59).map(Refined.unsafeApply)
  val millsGen: Gen[Any, Millis]  = Gen.int(1, 999).map(Refined.unsafeApply)

  implicit def monthDeriveGen: DeriveGen[Month]   = DeriveGen.instance(monthGen)
  implicit def dayDeriveGen: DeriveGen[Day]       = DeriveGen.instance(dayGen)
  implicit def hourDeriveGen: DeriveGen[Hour]     = DeriveGen.instance(hourGen)
  implicit def minuteDeriveGen: DeriveGen[Minute] = DeriveGen.instance(minuteGen)
  implicit def secondDeriveGen: DeriveGen[Second] = DeriveGen.instance(secondGen)
  implicit def millsDeriveGen: DeriveGen[Millis]  = DeriveGen.instance(millsGen)
}
