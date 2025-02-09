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
import eu.timepit.refined.generic.Equal
import shapeless.Witness
import zio.test.magnolia.DeriveGen
import zio.test.Gen

object generic extends GenericInstances

trait GenericInstances {

  def equalArbitraryGen[T, U <: T](implicit
    wu: Witness.Aux[U]
  ): Gen[Any, Refined[T, Equal[U]]] =
    Gen.const(wu.value).map(Refined.unsafeApply)

  implicit def equalArbitrary[T, U <: T](implicit
    wu: Witness.Aux[U]
  ): DeriveGen[Refined[T, Equal[U]]] =
    DeriveGen.instance(equalArbitraryGen(wu))
}
