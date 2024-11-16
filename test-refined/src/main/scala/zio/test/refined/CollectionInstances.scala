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
import eu.timepit.refined.collection.{NonEmpty, Size}
import zio.test.Gen.{listOfN, vectorOfN}
import zio.test.magnolia.DeriveGen
import zio.test.Gen
import zio.{Chunk, NonEmptyChunk}

object collection extends CollectionInstances

trait CollectionInstances {

  def nonEmptyChunkRefinedGen[R, T](implicit
    genT: Gen[R, T]
  ): Gen[R, Refined[NonEmptyChunk[T], NonEmpty]] =
    Gen.chunkOf1(genT).map(Refined.unsafeApply)

  def nonEmptyListRefinedGen[R, T](implicit
    genT: Gen[R, T]
  ): Gen[R, Refined[List[T], NonEmpty]] = Gen.listOf1(genT).map(Refined.unsafeApply)

  def nonEmptyVectorRefinedGen[R, T](implicit
    genT: Gen[R, T]
  ): Gen[R, Refined[Vector[T], NonEmpty]] = Gen.vectorOf1(genT).map(Refined.unsafeApply)

  def sizedChunkRefinedGen[R, T, P](implicit
    genT: Gen[R, T],
    sizeGen: Gen[R, Int Refined P]
  ): Gen[R, Refined[Chunk[T], Size[P]]] =
    sizeGen.flatMap { n =>
      Gen.chunkOfN(n.value)(genT)
    }.map(r => Refined.unsafeApply(r))

  def listSizeRefinedGen[R, T, P](implicit
    genT: Gen[R, T],
    sizeGen: Gen[R, Int Refined P]
  ): Gen[R, Refined[List[T], Size[P]]] =
    sizeGen.flatMap { n =>
      listOfN(n.value)(genT)
    }.map(Refined.unsafeApply)

  def vectorSizeRefinedGen[R, T, P](implicit
    genT: Gen[R, T],
    sizeGen: Gen[R, Int Refined P]
  ): Gen[R, Refined[Vector[T], Size[P]]] = sizeGen.flatMap { n =>
    vectorOfN(n.value)(genT)
  }.map(Refined.unsafeApply)

  implicit def nonEmptyChunkRefinedDeriveGen[C, T](implicit
    deriveGenT: DeriveGen[T]
  ): DeriveGen[Refined[NonEmptyChunk[T], NonEmpty]] =
    DeriveGen.instance(
      nonEmptyChunkRefinedGen(deriveGenT.derive)
    )

  implicit def nonEmptyListRefinedDeriveGen[T](implicit
    deriveGenT: DeriveGen[T]
  ): DeriveGen[Refined[List[T], NonEmpty]] = DeriveGen.instance(
    nonEmptyListRefinedGen(deriveGenT.derive)
  )

  implicit def nonEmptyVectorRefinedDeriveGen[T](implicit
    deriveGenT: DeriveGen[T]
  ): DeriveGen[Refined[Vector[T], NonEmpty]] =
    DeriveGen.instance(
      nonEmptyVectorRefinedGen(deriveGenT.derive)
    )

  implicit def sizedChunkRefinedDeriveGen[T, P](implicit
    deriveGenT: DeriveGen[T],
    deriveGenSize: DeriveGen[Int Refined P]
  ): DeriveGen[Refined[Chunk[T], Size[P]]] =
    DeriveGen.instance(
      sizedChunkRefinedGen(deriveGenT.derive, deriveGenSize.derive)
    )

  implicit def listSizeRefinedDeriveGen[T, P](implicit
    deriveGenT: DeriveGen[T],
    deriveGenSize: DeriveGen[Int Refined P]
  ): DeriveGen[Refined[List[T], Size[P]]] =
    DeriveGen.instance(
      listSizeRefinedGen(deriveGenT.derive, deriveGenSize.derive)
    )

  implicit def vectorSizeRefinedDeriveGen[T, P](implicit
    deriveGenT: DeriveGen[T],
    deriveGenSize: DeriveGen[Int Refined P]
  ): DeriveGen[Refined[Vector[T], Size[P]]] =
    DeriveGen.instance(
      vectorSizeRefinedGen(deriveGenT.derive, deriveGenSize.derive)
    )
}
