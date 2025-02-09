package zio.test.refined.types

import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.{MaxSize, Size}
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.types.string.{FiniteString, HexString, NonEmptyFiniteString, NonEmptyString, TrimmedString}
import shapeless.Nat._1
import shapeless.Witness
import zio.test.magnolia.DeriveGen
import zio.test.Gen

object string extends StringInstance

trait StringInstance {
  class FiniteStringPartiallyApplied[N <: Int, P](min: Int) {
    def apply[R](charGen: Gen[R, Char])(implicit ws: Witness.Aux[N]): Gen[R, Refined[String, P]] =
      for {
        i   <- Gen.int(min, ws.value)
        str <- Gen.stringN(i)(charGen)
      } yield Refined.unsafeApply(str)
  }

  def finiteStringGen[N <: Int]: FiniteStringPartiallyApplied[N, MaxSize[N]] =
    new FiniteStringPartiallyApplied[N, MaxSize[N]](0)
  def nonEmptyStringGen[R](charGen: Gen[R, Char]): Gen[R, NonEmptyString] =
    Gen.string1(charGen).map(Refined.unsafeApply)
  def nonEmptyFiniteStringGen[N <: Int]: FiniteStringPartiallyApplied[N, Size[Interval.Closed[_1, N]]] =
    new FiniteStringPartiallyApplied[N, Size[Interval.Closed[_1, N]]](1)
  def trimmedStringGen[R](charGen: Gen[R, Char]): Gen[R, TrimmedString] =
    Gen.string(charGen).map(s => Refined.unsafeApply(s.trim))
  def hexStringGen: Gen[Any, HexString] =
    Gen.oneOf(Gen.string(Gen.hexCharLower), Gen.string(Gen.hexCharUpper)).map(Refined.unsafeApply)

  implicit def finiteStringDeriveGen[N <: Int](implicit
    ws: Witness.Aux[N],
    charGen: DeriveGen[Char]
  ): DeriveGen[FiniteString[N]] =
    DeriveGen.instance(finiteStringGen[N](charGen.derive))
  implicit def nonEmptyStringDeriveGen[R](implicit charGen: DeriveGen[Char]): DeriveGen[NonEmptyString] =
    DeriveGen.instance(nonEmptyStringGen(charGen.derive))
  implicit def nonEmptyFiniteStringDeriveGen[N <: Int](implicit
    ws: Witness.Aux[N],
    charGen: DeriveGen[Char]
  ): DeriveGen[NonEmptyFiniteString[N]] =
    DeriveGen.instance(nonEmptyFiniteStringGen[N](charGen.derive))
  implicit def trimmedStringDeriveGen[R](implicit charGen: DeriveGen[Char]): DeriveGen[TrimmedString] =
    DeriveGen.instance(trimmedStringGen(charGen.derive))
  implicit def hexStringDeriveGen[R]: DeriveGen[HexString] = DeriveGen.instance(hexStringGen)
}
