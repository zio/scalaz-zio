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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.{IterableOnce, StrictOptimizedSeqFactory, mutable}

private[zio] trait ChunkFactory extends StrictOptimizedSeqFactory[Chunk] {

  final def from[A](source: IterableOnce[A]): Chunk[A] =
    source match {
      case iterable: Iterable[A] => Chunk.fromIterable(iterable)
      case iterableOnce =>
        val chunkBuilder = ChunkBuilder.make[A]()
        iterableOnce.iterator.foreach(chunkBuilder.addOne)
        chunkBuilder.result()
    }

  final protected def fromArraySeq[A](seq: mutable.ArraySeq[A]): Chunk[A] = {
    val arr = seq.array
    Chunk.fromArray(Array.copyAs(arr, arr.length)(seq.elemTag)).asInstanceOf[Chunk[A]]
  }
}
