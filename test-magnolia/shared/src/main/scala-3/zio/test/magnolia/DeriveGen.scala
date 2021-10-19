/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
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

import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving._
import zio.random.Random
import zio.test.{Gen, Sized}

trait DeriveGen[A] {
    def derive: Gen[Random with Sized, A]
}

object DeriveGen {
    def apply[A](using DeriveGen[A]): Gen[Random with Sized, A] = 
        summon[DeriveGen[A]].derive

    inline def instance[A](gen: => Gen[Random with Sized, A]): DeriveGen[A] =
        new DeriveGen[A] {
            val derive: Gen[Random with Sized, A] = gen
        }

    given DeriveGen[Boolean] = instance(Gen.boolean)
    given DeriveGen[Byte] = instance(Gen.anyByte)
    given DeriveGen[Char] = instance(Gen.anyChar)
    given DeriveGen[Double] = instance(Gen.anyDouble)
    given DeriveGen[Float] = instance(Gen.anyFloat)
    given DeriveGen[Int] = instance(Gen.anyInt)
    given DeriveGen[Long] = instance(Gen.anyLong)
    given DeriveGen[Short] = instance(Gen.anyShort)
    given DeriveGen[String] = instance(Gen.anyString)
    given DeriveGen[Unit] = instance(Gen.unit)
    given DeriveGen[UUID] = instance(Gen.anyUUID)
    given DeriveGen[Instant] = instance(Gen.anyInstant)
    given DeriveGen[LocalDateTime] = instance(Gen.anyLocalDateTime)
    given DeriveGen[LocalDate] = instance(Gen.anyLocalDateTime.map(_.toLocalDate()))
    given DeriveGen[BigDecimal] = instance(Gen.bigDecimal(
        BigDecimal(Double.MinValue) * BigDecimal(Double.MaxValue), 
        BigDecimal(Double.MaxValue) * BigDecimal(Double.MaxValue)
    ))

    given [A, B] (using a: DeriveGen[A], b: DeriveGen[B]): DeriveGen[Either[A, B]] =
        instance(Gen.either(a.derive, b.derive))
    given [A, B] (using b: DeriveGen[B]): DeriveGen[A => B] =
        instance(Gen.function(b.derive))
    given [A] (using a: DeriveGen[A]): DeriveGen[Iterable[A]] =
        instance(Gen.oneOf(Gen.listOf(a.derive), Gen.vectorOf(a.derive), Gen.setOf(a.derive)))
    given [A] (using a: DeriveGen[A]): DeriveGen[List[A]] =
        instance(Gen.listOf(a.derive))
    given [A, B] (using a: DeriveGen[A], b: DeriveGen[B]): DeriveGen[Map[A, B]] =
        instance(Gen.mapOf(a.derive, b.derive))
    given [A] (using a: => DeriveGen[A]): DeriveGen[Option[A]] =
        instance(Gen.option(a.derive))
    given [A] (using a: DeriveGen[A]): DeriveGen[Seq[A]] =
        instance(Gen.oneOf(Gen.listOf(a.derive), Gen.vectorOf(a.derive)))
    given [A, B] (using b: DeriveGen[B]): DeriveGen[PartialFunction[A, B]] =
        instance(Gen.partialFunction(b.derive))
    given [A] (using a: DeriveGen[A]): DeriveGen[Set[A]] =
        instance(Gen.setOf(a.derive))
    given [A] (using a: DeriveGen[A]): DeriveGen[Vector[A]] =
        instance(Gen.vectorOf(a.derive))

    given DeriveGen[EmptyTuple] = instance(Gen.const(EmptyTuple))
    given [A, T <: Tuple] (using a: DeriveGen[A], t: DeriveGen[T]): DeriveGen[A *: T] =
        instance((a.derive <&> t.derive).map(_ *: _))

    inline def gen[T](using m: Mirror.Of[T]): DeriveGen[T] =
        new DeriveGen[T] {
            def derive: Gen[Random with Sized, T] = {
                val elemInstances = summonAll[m.MirroredElemTypes]
                inline m match {
                    case s: Mirror.SumOf[T]     => genSum(s, elemInstances)
                    case p: Mirror.ProductOf[T] => genProduct(p, elemInstances)
                }        
            }
        }    

    inline given derived[T](using m: Mirror.Of[T]): DeriveGen[T] =
        gen[T]

    def genSum[T](s: Mirror.SumOf[T], instances: => List[DeriveGen[_]]): Gen[Random with Sized, T] =
        Gen.suspend(Gen.oneOf(instances.map(_.asInstanceOf[DeriveGen[T]].derive) : _*))

    def genProduct[T](p: Mirror.ProductOf[T], instances: => List[DeriveGen[_]]): Gen[Random with Sized, T] = 
        Gen.suspend(
            Gen.zipAll(instances.map(_.derive)).map(lst => Tuple.fromArray(lst.toArray)).map(p.fromProduct))

    inline def summonAll[T <: Tuple]: List[DeriveGen[_]] =
        inline erasedValue[T] match {
            case _: EmptyTuple => Nil
            case _: (t *: ts) => summonInline[DeriveGen[t]] :: summonAll[ts]
        }
}

