/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

package zio.test.refined

import eu.timepit.refined.api.Refined
import eu.timepit.refined.internal.WitnessAs
import eu.timepit.refined.numeric.{Greater, Less}
import zio.test.Gen
import zio.test.magnolia.DeriveGen

object numeric extends NumericInstances

trait NumericInstances {

  def intGreaterThanGen[N](implicit
    wn: WitnessAs[N, Int]
  ): Gen[Any, Refined[Int, Greater[N]]] = Gen.int(wn.snd, Int.MaxValue).map(Refined.unsafeApply)

  def longGreaterThanGen[N](implicit
    wn: WitnessAs[N, Long]
  ): Gen[Any, Refined[Long, Greater[N]]] = Gen.long(wn.snd, Long.MaxValue).map(Refined.unsafeApply)

  def shortGreaterThanGen[N](implicit
    wn: WitnessAs[N, Short]
  ): Gen[Any, Refined[Short, Greater[N]]] = Gen.short(wn.snd, Short.MaxValue).map(Refined.unsafeApply)

  def byteGreaterThanGen[N](implicit
    wn: WitnessAs[N, Byte]
  ): Gen[Any, Refined[Byte, Greater[N]]] = Gen.byte(wn.snd, Byte.MaxValue).map(Refined.unsafeApply)

  def doubleGreaterThanGen[N](implicit
    wn: WitnessAs[N, Double]
  ): Gen[Any, Refined[Double, Greater[N]]] = Gen.double(wn.snd, Double.MaxValue).map(Refined.unsafeApply)

  def intLessThanGen[N](implicit
    wn: WitnessAs[N, Int]
  ): Gen[Any, Refined[Int, Less[N]]] = Gen.int(Int.MinValue, wn.snd).map(Refined.unsafeApply)

  def longLessThanGen[N](implicit
    wn: WitnessAs[N, Long]
  ): Gen[Any, Refined[Long, Less[N]]] = Gen.long(Long.MinValue, wn.snd).map(Refined.unsafeApply)

  def shortLessThanGen[N](implicit
    wn: WitnessAs[N, Short]
  ): Gen[Any, Refined[Short, Less[N]]] = Gen.short(Short.MinValue, wn.snd).map(Refined.unsafeApply)

  def byteLessThanGen[N](implicit
    wn: WitnessAs[N, Byte]
  ): Gen[Any, Refined[Byte, Less[N]]] = Gen.byte(Byte.MinValue, wn.snd).map(Refined.unsafeApply)

  def doubleLessThanGen[N](implicit
    wn: WitnessAs[N, Double]
  ): Gen[Any, Refined[Double, Less[N]]] = Gen.double(Double.MinValue, wn.snd).map(Refined.unsafeApply)

  implicit def intGreaterThan[N](implicit
    wn: WitnessAs[N, Int]
  ): DeriveGen[Int Refined Greater[N]] = DeriveGen.instance(intGreaterThanGen)

  implicit def longGreaterThan[N](implicit
    wn: WitnessAs[N, Long]
  ): DeriveGen[Long Refined Greater[N]] =
    DeriveGen.instance(longGreaterThanGen)

  implicit def shortGreaterThan[N](implicit
    wn: WitnessAs[N, Short]
  ): DeriveGen[Short Refined Greater[N]] =
    DeriveGen.instance(shortGreaterThanGen)

  implicit def byteGreaterThan[N](implicit
    wn: WitnessAs[N, Byte]
  ): DeriveGen[Byte Refined Greater[N]] =
    DeriveGen.instance(byteGreaterThanGen)

  implicit def doubleGreaterThan[N](implicit
    wn: WitnessAs[N, Double]
  ): DeriveGen[Double Refined Greater[N]] =
    DeriveGen.instance(doubleGreaterThanGen)

  implicit def intLessThan[N](implicit
    wn: WitnessAs[N, Int]
  ): DeriveGen[Int Refined Less[N]] =
    DeriveGen.instance(intLessThanGen)

  implicit def longLessThan[N](implicit
    wn: WitnessAs[N, Long]
  ): DeriveGen[Long Refined Less[N]] =
    DeriveGen.instance(longLessThanGen)

  implicit def shortLessThan[N](implicit
    wn: WitnessAs[N, Short]
  ): DeriveGen[Short Refined Less[N]] =
    DeriveGen.instance(shortLessThanGen)

  implicit def byteLessThan[N](implicit
    wn: WitnessAs[N, Byte]
  ): DeriveGen[Byte Refined Less[N]] =
    DeriveGen.instance(byteLessThanGen)

  implicit def doubleLessThan[N](implicit
    wn: WitnessAs[N, Double]
  ): DeriveGen[Double Refined Less[N]] =
    DeriveGen.instance(doubleLessThanGen)

}
