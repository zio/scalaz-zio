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

import zio.Chunk

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID
import scala.compiletime.{erasedValue, summonInline}
import scala.deriving._
import zio._
import zio.test.Gen

trait DeriveGen[A] {
  def derive: Gen[Any, A]
}

object DeriveGen {
  def apply[A](using DeriveGen[A]): Gen[Any, A] =
    summon[DeriveGen[A]].derive

  inline def instance[A](gen: => Gen[Any, A]): DeriveGen[A] =
    new DeriveGen[A] {
      val derive: Gen[Any, A] = gen
    }

  given DeriveGen[Boolean]       = instance(Gen.boolean)
  given DeriveGen[Byte]          = instance(Gen.byte)
  given DeriveGen[Char]          = instance(Gen.char)
  given DeriveGen[Double]        = instance(Gen.double)
  given DeriveGen[Float]         = instance(Gen.float)
  given DeriveGen[Int]           = instance(Gen.int)
  given DeriveGen[Long]          = instance(Gen.long)
  given DeriveGen[Short]         = instance(Gen.short)
  given DeriveGen[String]        = instance(Gen.string)
  given DeriveGen[Unit]          = instance(Gen.unit)
  given DeriveGen[UUID]          = instance(Gen.uuid)
  given DeriveGen[Instant]       = instance(Gen.instant)
  given DeriveGen[LocalDateTime] = instance(Gen.localDateTime)
  given DeriveGen[LocalDate]     = instance(Gen.localDate)
  given DeriveGen[LocalTime]     = instance(Gen.localTime)
  given DeriveGen[BigDecimal] = instance(
    Gen.bigDecimal(
      BigDecimal(Double.MinValue) * BigDecimal(Double.MaxValue),
      BigDecimal(Double.MaxValue) * BigDecimal(Double.MaxValue)
    )
  )

  given [A, B](using a: DeriveGen[A], b: DeriveGen[B]): DeriveGen[Either[A, B]] =
    instance(Gen.either(a.derive, b.derive))
  given [A, B](using b: DeriveGen[B]): DeriveGen[A => B] =
    instance(Gen.function(b.derive))
  given [A](using a: DeriveGen[A]): DeriveGen[Iterable[A]] =
    instance(Gen.oneOf(Gen.listOf(a.derive), Gen.vectorOf(a.derive), Gen.setOf(a.derive)))
  given [A](using a: DeriveGen[A]): DeriveGen[List[A]] =
    instance(Gen.listOf(a.derive))
  given [A](using a: DeriveGen[A]): DeriveGen[Chunk[A]] =
    instance(Gen.chunkOf(a.derive))
  given [A, B](using a: DeriveGen[A], b: DeriveGen[B]): DeriveGen[Map[A, B]] =
    instance(Gen.mapOf(a.derive, b.derive))
  given [A](using a: => DeriveGen[A]): DeriveGen[Option[A]] =
    instance(Gen.option(a.derive))
  given [A](using a: DeriveGen[A]): DeriveGen[Seq[A]] =
    instance(Gen.oneOf(Gen.listOf(a.derive), Gen.vectorOf(a.derive)))
  given [A, B](using b: DeriveGen[B]): DeriveGen[PartialFunction[A, B]] =
    instance(Gen.partialFunction(b.derive))
  given [A](using a: DeriveGen[A]): DeriveGen[Set[A]] =
    instance(Gen.setOf(a.derive))
  given [A](using a: DeriveGen[A]): DeriveGen[Vector[A]] =
    instance(Gen.vectorOf(a.derive))

  given DeriveGen[EmptyTuple] = instance(Gen.const(EmptyTuple))
  given [A, T <: Tuple](using a: DeriveGen[A], t: DeriveGen[T]): DeriveGen[A *: T] =
    instance((a.derive <*> t.derive).map(_ *: _))

  inline def gen[T](using m: Mirror.Of[T]): DeriveGen[T] =
    new DeriveGen[T] {
      def derive: Gen[Any, T] = {
        val elemInstances = summonAll[m.MirroredElemTypes]
        inline m match {
          case s: Mirror.SumOf[T]     => genSum(s, elemInstances)
          case p: Mirror.ProductOf[T] => genProduct(p, elemInstances)
        }
      }
    }

  inline given derived[T](using m: Mirror.Of[T]): DeriveGen[T] =
    gen[T]

  def genSum[T](s: Mirror.SumOf[T], instances: => List[DeriveGen[_]]): Gen[Any, T] =
    Gen.suspend(Gen.oneOf(instances.map(_.asInstanceOf[DeriveGen[T]].derive): _*))

  def genProduct[T](p: Mirror.ProductOf[T], instances: => List[DeriveGen[_]]): Gen[Any, T] =
    Gen.suspend(Gen.collectAll(instances.map(_.derive)).map(lst => Tuple.fromArray(lst.toArray)).map(p.fromProduct))

  inline def summonAll[T <: Tuple]: List[DeriveGen[_]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[DeriveGen[t]] :: summonAll[ts]
    }
}
