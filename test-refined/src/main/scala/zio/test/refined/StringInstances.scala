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
import eu.timepit.refined.string.{EndsWith, StartsWith, Uuid}
import shapeless.Witness
import zio.test.magnolia.DeriveGen
import zio.test.Gen

object string extends StringInstances

trait StringInstances {

  def endsWithStringGen[R, S <: String](implicit
    ws: Witness.Aux[S],
    charGen: Gen[R, Char]
  ): Gen[R, Refined[String, EndsWith[S]]] = Gen.string(charGen).map(v => Refined.unsafeApply(v + ws.value))

  def endsWithString1Gen[R, S <: String](implicit
    ws: Witness.Aux[S],
    charGen: Gen[R, Char]
  ): Gen[R, Refined[String, EndsWith[S]]] =
    Gen.string1(charGen).map(v => Refined.unsafeApply(v + ws.value)) //Only String can call `+` since scala 2.13.0.

  def endsWithStringNGen[R, S <: String, P](implicit
    ws: Witness.Aux[S],
    charGen: Gen[R, Char],
    sizeGen: Gen[R, Int Refined P]
  ): Gen[R, Refined[String, EndsWith[S]]] =
    sizeGen.flatMap(s => Gen.stringN(s.value)(charGen).map(v => Refined.unsafeApply(v + ws.value)))

  def startsWithStringGen[R, S <: String](implicit
    ws: Witness.Aux[S],
    charGen: Gen[R, Char]
  ): Gen[R, Refined[String, StartsWith[S]]] = Gen.string(charGen).map(v => Refined.unsafeApply(ws.value + v))

  def startsWithString1Gen[R, S <: String](implicit
    ws: Witness.Aux[S],
    charGen: Gen[R, Char]
  ): Gen[R, Refined[String, StartsWith[S]]] = Gen.string1(charGen).map(v => Refined.unsafeApply(ws.value + v))

  def startsWithStringNGen[R, S <: String, P](implicit
    ws: Witness.Aux[S],
    charGen: Gen[R, Char],
    sizeGen: Gen[R, Int Refined P]
  ): Gen[R, Refined[String, StartsWith[S]]] =
    sizeGen.flatMap(s => Gen.stringN(s.value)(charGen).map(v => Refined.unsafeApply(ws.value + v)))

  val uuidStringGen: Gen[Any, Refined[String, Uuid]] =
    Gen.uuid.map(value => Refined.unsafeApply(value.toString))

  implicit val uuidStringDeriveGen: DeriveGen[Refined[String, Uuid]] =
    DeriveGen.instance(uuidStringGen)

  implicit def endsWithStringDeriveGen[S <: String](implicit
    ws: Witness.Aux[S],
    deriveGenT: DeriveGen[Char]
  ): DeriveGen[Refined[String, EndsWith[S]]] =
    DeriveGen.instance(endsWithStringGen(ws, deriveGenT.derive))

  implicit def startsWithStringDeriveGen[S <: String](implicit
    ws: Witness.Aux[S],
    deriveGenT: DeriveGen[Char]
  ): DeriveGen[Refined[String, StartsWith[S]]] =
    DeriveGen.instance(startsWithStringGen(ws, deriveGenT.derive))

  implicit def endsWithString1DeriveGen[S <: String](implicit
    ws: Witness.Aux[S],
    deriveGenT: DeriveGen[Char]
  ): DeriveGen[Refined[String, EndsWith[S]]] =
    DeriveGen.instance(endsWithString1Gen(ws, deriveGenT.derive))

  implicit def startsWithString1DeriveGen[S <: String](implicit
    ws: Witness.Aux[S],
    deriveGenT: DeriveGen[Char]
  ): DeriveGen[Refined[String, StartsWith[S]]] =
    DeriveGen.instance(startsWithString1Gen(ws, deriveGenT.derive))

  implicit def endsWithStringNDeriveGen[S <: String, P](implicit
    ws: Witness.Aux[S],
    deriveGenT: DeriveGen[Char],
    deriveGenSize: DeriveGen[Int Refined P]
  ): DeriveGen[Refined[String, EndsWith[S]]] =
    DeriveGen.instance(endsWithStringNGen(ws, deriveGenT.derive, deriveGenSize.derive))

  implicit def startsWithStringNDeriveGen[S <: String, P](implicit
    ws: Witness.Aux[S],
    deriveGenT: DeriveGen[Char],
    deriveGenSize: DeriveGen[Int Refined P]
  ): DeriveGen[Refined[String, StartsWith[S]]] =
    DeriveGen.instance(startsWithStringNGen(ws, deriveGenT.derive, deriveGenSize.derive))

}
