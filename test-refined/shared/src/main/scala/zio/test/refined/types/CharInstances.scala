package zio.test.refined.types

import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.char._
import zio.test.Gen
import zio.test.magnolia.DeriveGen
import zio.Random
object char extends CharInstances

trait CharInstances {
  val lowerCaseCharGen: Gen[Random, LowerCaseChar] = Gen.alphaChar.map(v => Refined.unsafeApply(v.toLower))
  val upperCaseCharGen: Gen[Random, UpperCaseChar] = Gen.alphaChar.map(v => Refined.unsafeApply(v.toUpper))

  implicit val lowerCaseCharDeriveGen: DeriveGen[LowerCaseChar] = DeriveGen.instance(lowerCaseCharGen)
  implicit val upperCaseCharDeriveGen: DeriveGen[UpperCaseChar] = DeriveGen.instance(upperCaseCharGen)
}
