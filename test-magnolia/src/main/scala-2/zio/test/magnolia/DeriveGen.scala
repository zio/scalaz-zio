/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test.magnolia

import magnolia1._
import zio.Chunk
import zio.test.Gen

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID

/**
 * A `DeriveGen[A]` can derive a generator of `A` values. Implicit instances of
 * DeriveGen are automatically provided for standard types and algebraic data
 * types made up of standard types. For example, here is how you can
 * automatically derive generators for case classes and sealed traits:
 *
 * {{{
 * final case class Point(x: Double, y: Double)
 *
 * val genPoint: Gen[Any, Point] = DeriveGen[Point]
 *
 * sealed trait Color
 * case object Red   extends Color
 * case object Green extends Color
 * case object Blue  extends Color
 *
 * val genColor: Gen[Any, Color] = DeriveGen[Color]
 * }}}
 *
 * You can derive generators that include your own custom types by providing an
 * implicit `DeriveGen` instance for your type using a generator and the
 * `instance` method.
 */
trait DeriveGen[A] {
  def derive: Gen[Any, A]
}

object DeriveGen {

  /**
   * Derives a generator of `A` values given an implicit `DeriveGen` instance.
   */
  def apply[A](implicit ev: DeriveGen[A]): Gen[Any, A] = ev.derive

  /**
   * Constructs a `DeriveGen` instance given a generator of `A` values.
   */
  def instance[A](gen: Gen[Any, A]): DeriveGen[A] =
    new DeriveGen[A] {
      val derive: Gen[Any, A] = gen
    }

  implicit val genBoolean: DeriveGen[Boolean]             = instance(Gen.boolean)
  implicit val genByte: DeriveGen[Byte]                   = instance(Gen.byte)
  implicit val genChar: DeriveGen[Char]                   = instance(Gen.char)
  implicit val genDouble: DeriveGen[Double]               = instance(Gen.double)
  implicit val genFloat: DeriveGen[Float]                 = instance(Gen.float)
  implicit val genInt: DeriveGen[Int]                     = instance(Gen.int)
  implicit val genLong: DeriveGen[Long]                   = instance(Gen.long)
  implicit val genShort: DeriveGen[Short]                 = instance(Gen.short)
  implicit val genString: DeriveGen[String]               = instance(Gen.string)
  implicit val genUnit: DeriveGen[Unit]                   = instance(Gen.unit)
  implicit val genUUID: DeriveGen[UUID]                   = instance(Gen.uuid)
  implicit val genInstant: DeriveGen[Instant]             = instance(Gen.instant)
  implicit val genLocalDateTime: DeriveGen[LocalDateTime] = instance(Gen.localDateTime)
  implicit val genLocalDate: DeriveGen[LocalDate]         = instance(Gen.localDate)
  implicit val genLocalTime: DeriveGen[LocalTime]         = instance(Gen.localTime)

  implicit val genBigDecimal: DeriveGen[BigDecimal] = instance(
    Gen.bigDecimal(
      BigDecimal(Double.MinValue) * BigDecimal(Double.MaxValue),
      BigDecimal(Double.MaxValue) * BigDecimal(Double.MaxValue)
    )
  )

  implicit val genBigInt: DeriveGen[BigInt] = instance(
    Gen.bigInt(
      BigInt(Int.MinValue) * BigInt(Int.MaxValue),
      BigInt(Int.MaxValue) * BigInt(Int.MaxValue)
    )
  )

  implicit val genBigIntegerJava: DeriveGen[java.math.BigInteger] = instance(
    Gen
      .bigIntegerJava(
        BigInt(Int.MinValue) * BigInt(Int.MaxValue),
        BigInt(Int.MaxValue) * BigInt(Int.MaxValue)
      )
  )

  implicit val genJavaBigDecimalGen: DeriveGen[java.math.BigDecimal] = instance(
    Gen
      .bigDecimalJava(
        BigDecimal(Double.MinValue) * BigDecimal(Double.MaxValue),
        BigDecimal(Double.MaxValue) * BigDecimal(Double.MaxValue)
      )
  )

  implicit def genEither[A, B](implicit ev1: DeriveGen[A], ev2: DeriveGen[B]): DeriveGen[Either[A, B]] =
    instance(Gen.either(ev1.derive, ev2.derive))

  implicit def genFunction[A, B](implicit ev: DeriveGen[B]): DeriveGen[A => B] =
    instance(Gen.function(ev.derive))

  implicit def genIterable[A](implicit ev: DeriveGen[A]): DeriveGen[Iterable[A]] =
    instance(Gen.oneOf(Gen.listOf(ev.derive), Gen.vectorOf(ev.derive), Gen.setOf(ev.derive)))

  implicit def genList[A](implicit ev: DeriveGen[A]): DeriveGen[List[A]] =
    instance(Gen.listOf(ev.derive))

  implicit def genChunk[A](implicit ev: DeriveGen[A]): DeriveGen[Chunk[A]] =
    instance(Gen.chunkOf(ev.derive))

  implicit def genMap[A, B](implicit ev1: DeriveGen[A], ev2: DeriveGen[B]): DeriveGen[Map[A, B]] =
    instance(Gen.mapOf(ev1.derive, ev2.derive))

  implicit def genOption[A](implicit ev: DeriveGen[A]): DeriveGen[Option[A]] =
    instance(Gen.option(ev.derive))

  implicit def genSeq[A](implicit ev: DeriveGen[A]): DeriveGen[Seq[A]] =
    instance(Gen.oneOf(Gen.listOf(ev.derive), Gen.vectorOf(ev.derive)))

  implicit def genPartialFunction[A, B](implicit ev: DeriveGen[B]): DeriveGen[PartialFunction[A, B]] =
    instance(Gen.partialFunction(ev.derive))

  implicit def genSet[A](implicit ev: DeriveGen[A]): DeriveGen[Set[A]] =
    instance(Gen.setOf(ev.derive))

  implicit def genTuple2[A, B](implicit ev1: DeriveGen[A], ev2: DeriveGen[B]): DeriveGen[(A, B)] =
    instance(ev1.derive <*> ev2.derive)

  implicit def genTuple3[A, B, C](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C]
  ): DeriveGen[(A, B, C)] =
    instance(ev1.derive <*> ev2.derive <*> ev3.derive)

  implicit def genTuple4[A, B, C, D](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D]
  ): DeriveGen[(A, B, C, D)] =
    instance(ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive)

  implicit def genTuple5[A, B, C, D, F](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D],
    ev5: DeriveGen[F]
  ): DeriveGen[(A, B, C, D, F)] =
    instance(ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive <*> ev5.derive)

  implicit def genTuple6[A, B, C, D, F, G](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D],
    ev5: DeriveGen[F],
    ev6: DeriveGen[G]
  ): DeriveGen[(A, B, C, D, F, G)] =
    instance(ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive <*> ev5.derive <*> ev6.derive)

  implicit def genTuple7[A, B, C, D, F, G, H](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D],
    ev5: DeriveGen[F],
    ev6: DeriveGen[G],
    ev7: DeriveGen[H]
  ): DeriveGen[(A, B, C, D, F, G, H)] =
    instance(ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive <*> ev5.derive <*> ev6.derive <*> ev7.derive)

  implicit def genTuple8[A, B, C, D, F, G, H, I](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D],
    ev5: DeriveGen[F],
    ev6: DeriveGen[G],
    ev7: DeriveGen[H],
    ev8: DeriveGen[I]
  ): DeriveGen[(A, B, C, D, F, G, H, I)] =
    instance(
      ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive <*> ev5.derive <*> ev6.derive <*> ev7.derive <*> ev8.derive
    )

  implicit def genTuple9[A, B, C, D, F, G, H, I, J](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D],
    ev5: DeriveGen[F],
    ev6: DeriveGen[G],
    ev7: DeriveGen[H],
    ev8: DeriveGen[I],
    ev9: DeriveGen[J]
  ): DeriveGen[(A, B, C, D, F, G, H, I, J)] =
    instance(
      ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive <*> ev5.derive <*> ev6.derive <*> ev7.derive <*> ev8.derive <*> ev9.derive
    )

  implicit def genTuple10[A, B, C, D, F, G, H, I, J, K](implicit
    ev1: DeriveGen[A],
    ev2: DeriveGen[B],
    ev3: DeriveGen[C],
    ev4: DeriveGen[D],
    ev5: DeriveGen[F],
    ev6: DeriveGen[G],
    ev7: DeriveGen[H],
    ev8: DeriveGen[I],
    ev9: DeriveGen[J],
    ev10: DeriveGen[K]
  ): DeriveGen[(A, B, C, D, F, G, H, I, J, K)] =
    instance(
      ev1.derive <*> ev2.derive <*> ev3.derive <*> ev4.derive <*> ev5.derive <*> ev6.derive <*> ev7.derive <*> ev8.derive <*> ev9.derive <*> ev10.derive
    )

  implicit def genVector[A](implicit ev: DeriveGen[A]): DeriveGen[Vector[A]] =
    instance(Gen.vectorOf(ev.derive))

  type Typeclass[T] = DeriveGen[T]

  def join[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
    instance(Gen.suspend(Gen.collectAll(caseClass.parameters.map(_.typeclass.derive)).map(caseClass.rawConstruct)))

  def split[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
    instance(Gen.suspend(Gen.oneOf(sealedTrait.subtypes.map(_.typeclass.derive): _*)))

  implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]
}
