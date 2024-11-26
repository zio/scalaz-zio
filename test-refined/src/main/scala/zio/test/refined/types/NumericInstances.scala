package zio.test.refined.types

import eu.timepit.refined.api.Refined
import eu.timepit.refined.types.numeric._
import zio.test.Gen
import zio.test.magnolia.DeriveGen

object numeric extends NumericInstances

trait NumericInstances {
  val posByteGen: Gen[Any, PosByte]           = Gen.byte(1, Byte.MaxValue).map(Refined.unsafeApply)
  val nonNegByteGen: Gen[Any, NonNegByte]     = Gen.byte(0, Byte.MaxValue).map(Refined.unsafeApply)
  val negByteGen: Gen[Any, NegByte]           = Gen.byte(Byte.MinValue, -1).map(Refined.unsafeApply)
  val nonPosByteGen: Gen[Any, NonPosByte]     = Gen.byte(Byte.MinValue, 0).map(Refined.unsafeApply)
  val posShortGen: Gen[Any, PosShort]         = Gen.short(1, Short.MaxValue).map(Refined.unsafeApply)
  val nonNegShortGen: Gen[Any, NonNegShort]   = Gen.short(0, Short.MaxValue).map(Refined.unsafeApply)
  val negShortGen: Gen[Any, NegShort]         = Gen.short(Short.MinValue, -1).map(Refined.unsafeApply)
  val nonPosShortGen: Gen[Any, NonPosShort]   = Gen.short(Short.MinValue, 0).map(Refined.unsafeApply)
  val posIntGen: Gen[Any, PosInt]             = Gen.int(1, Int.MaxValue).map(Refined.unsafeApply)
  val nonNegIntGen: Gen[Any, NonNegInt]       = Gen.int(0, Int.MaxValue).map(Refined.unsafeApply)
  val negIntGen: Gen[Any, NegInt]             = Gen.int(Int.MinValue, -1).map(Refined.unsafeApply)
  val nonPosIntGen: Gen[Any, NonPosInt]       = Gen.int(Int.MinValue, 0).map(Refined.unsafeApply)
  val posLongGen: Gen[Any, PosLong]           = Gen.long(1, Long.MaxValue).map(Refined.unsafeApply)
  val nonNegLongGen: Gen[Any, NonNegLong]     = Gen.long(0, Long.MaxValue).map(Refined.unsafeApply)
  val negLongGen: Gen[Any, NegLong]           = Gen.long(Long.MinValue, -1).map(Refined.unsafeApply)
  val nonPosLongGen: Gen[Any, NonPosLong]     = Gen.long(Long.MinValue, 0).map(Refined.unsafeApply)
  val posBigIntGen: Gen[Any, PosBigInt]       = Gen.bigInt(1, Long.MaxValue).map(Refined.unsafeApply)
  val nonNegBigIntGen: Gen[Any, NonNegBigInt] = Gen.bigInt(0, Long.MaxValue).map(Refined.unsafeApply)
  val negBigIntGen: Gen[Any, NegBigInt]       = Gen.bigInt(Long.MinValue, -1).map(Refined.unsafeApply)
  val nonPosBigIntGen: Gen[Any, NonPosBigInt] = Gen.bigInt(Long.MinValue, 0).map(Refined.unsafeApply)
  val posFloatGen: Gen[Any, PosFloat] = Gen
    .double(1, Float.MaxValue.toDouble)
    .map(v => Refined.unsafeApply(v.toFloat))
  val nonNegFloatGen: Gen[Any, NonNegFloat] = Gen
    .double(0, Float.MaxValue.toDouble)
    .map(v => Refined.unsafeApply(v.toFloat))
  val negFloatGen: Gen[Any, NegFloat] = Gen
    .double(Float.MinValue.toDouble, -1)
    .map(v => Refined.unsafeApply(v.toFloat))
  val nonPosFloatGen: Gen[Any, NonPosFloat] = Gen
    .double(Float.MinValue.toDouble, 0)
    .map(v => Refined.unsafeApply(v.toFloat))
  val posDoubleGen: Gen[Any, PosDouble]         = Gen.double(1, Double.MaxValue).map(Refined.unsafeApply)
  val nonNegDoubleGen: Gen[Any, NonNegDouble]   = Gen.double(0, Double.MaxValue).map(Refined.unsafeApply)
  val negDoubleGen: Gen[Any, NegDouble]         = Gen.double(Double.MinValue, -1).map(Refined.unsafeApply)
  val nonPosDoubleGen: Gen[Any, NonPosDouble]   = Gen.double(Double.MinValue, 0).map(Refined.unsafeApply)
  val posBigDecimalGen: Gen[Any, PosBigDecimal] = Gen.bigDecimal(1, Double.MaxValue).map(Refined.unsafeApply)
  val nonNegBigDecimalGen: Gen[Any, NonNegBigDecimal] =
    Gen.bigDecimal(0, Double.MaxValue).map(Refined.unsafeApply)
  val negBigDecimalGen: Gen[Any, NegBigDecimal] = Gen.bigDecimal(Double.MinValue, -1).map(Refined.unsafeApply)
  val nonPosBigDecimalGen: Gen[Any, NonPosBigDecimal] =
    Gen.bigDecimal(Double.MinValue, 0).map(Refined.unsafeApply)
  val nonNanFloatGen: Gen[Any, NonNaNFloat]   = Gen.float.map(Refined.unsafeApply)
  val nonNanDoubleGen: Gen[Any, NonNaNDouble] = Gen.double.map(Refined.unsafeApply)

  implicit val posByteDeriveGen: DeriveGen[PosByte]                   = DeriveGen.instance(posByteGen)
  implicit val nonNegByteDeriveGen: DeriveGen[NonNegByte]             = DeriveGen.instance(nonNegByteGen)
  implicit val negByteDeriveGen: DeriveGen[NegByte]                   = DeriveGen.instance(negByteGen)
  implicit val nonPosByteDeriveGen: DeriveGen[NonPosByte]             = DeriveGen.instance(nonPosByteGen)
  implicit val posShortDeriveGen: DeriveGen[PosShort]                 = DeriveGen.instance(posShortGen)
  implicit val nonNegShortDeriveGen: DeriveGen[NonNegShort]           = DeriveGen.instance(nonNegShortGen)
  implicit val negShortDeriveGen: DeriveGen[NegShort]                 = DeriveGen.instance(negShortGen)
  implicit val nonPosShortDeriveGen: DeriveGen[NonPosShort]           = DeriveGen.instance(nonPosShortGen)
  implicit val posIntDeriveGen: DeriveGen[PosInt]                     = DeriveGen.instance(posIntGen)
  implicit val nonNegIntDeriveGen: DeriveGen[NonNegInt]               = DeriveGen.instance(nonNegIntGen)
  implicit val negIntDeriveGen: DeriveGen[NegInt]                     = DeriveGen.instance(negIntGen)
  implicit val nonPosIntDeriveGen: DeriveGen[NonPosInt]               = DeriveGen.instance(nonPosIntGen)
  implicit val posLongDeriveGen: DeriveGen[PosLong]                   = DeriveGen.instance(posLongGen)
  implicit val nonNegLongDeriveGen: DeriveGen[NonNegLong]             = DeriveGen.instance(nonNegLongGen)
  implicit val negLongDeriveGen: DeriveGen[NegLong]                   = DeriveGen.instance(negLongGen)
  implicit val nonPosLongDeriveGen: DeriveGen[NonPosLong]             = DeriveGen.instance(nonPosLongGen)
  implicit val posBigIntDeriveGen: DeriveGen[PosBigInt]               = DeriveGen.instance(posBigIntGen)
  implicit val nonNegBigIntDeriveGen: DeriveGen[NonNegBigInt]         = DeriveGen.instance(nonNegBigIntGen)
  implicit val negBigIntDeriveGen: DeriveGen[NegBigInt]               = DeriveGen.instance(negBigIntGen)
  implicit val nonPosBigIntDeriveGen: DeriveGen[NonPosBigInt]         = DeriveGen.instance(nonPosBigIntGen)
  implicit val posFloatDeriveGen: DeriveGen[PosFloat]                 = DeriveGen.instance(posFloatGen)
  implicit val nonNegFloatDeriveGen: DeriveGen[NonNegFloat]           = DeriveGen.instance(nonNegFloatGen)
  implicit val negFloatDeriveGen: DeriveGen[NegFloat]                 = DeriveGen.instance(negFloatGen)
  implicit val nonPosFloatDeriveGen: DeriveGen[NonPosFloat]           = DeriveGen.instance(nonPosFloatGen)
  implicit val posDoubleDeriveGen: DeriveGen[PosDouble]               = DeriveGen.instance(posDoubleGen)
  implicit val nonNegDoubleDeriveGen: DeriveGen[NonNegDouble]         = DeriveGen.instance(nonNegDoubleGen)
  implicit val negDoubleDeriveGen: DeriveGen[NegDouble]               = DeriveGen.instance(negDoubleGen)
  implicit val nonPosDoubleDeriveGen: DeriveGen[NonPosDouble]         = DeriveGen.instance(nonPosDoubleGen)
  implicit val posBigDecimalDeriveGen: DeriveGen[PosBigDecimal]       = DeriveGen.instance(posBigDecimalGen)
  implicit val nonNegBigDecimalDeriveGen: DeriveGen[NonNegBigDecimal] = DeriveGen.instance(nonNegBigDecimalGen)
  implicit val negBigDecimalDeriveGen: DeriveGen[NegBigDecimal]       = DeriveGen.instance(negBigDecimalGen)
  implicit val nonPosBigDecimalDeriveGen: DeriveGen[NonPosBigDecimal] = DeriveGen.instance(nonPosBigDecimalGen)
  implicit val nonNanFloatDeriveGen: DeriveGen[NonNaNFloat]           = DeriveGen.instance(nonNanFloatGen)
  implicit val nonNanDoubleDeriveGen: DeriveGen[NonNaNDouble]         = DeriveGen.instance(nonNanDoubleGen)
}
