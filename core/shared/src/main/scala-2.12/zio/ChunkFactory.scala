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

import scala.collection.generic.IndexedSeqFactory
import scala.collection.mutable

private[zio] trait ChunkFactory extends IndexedSeqFactory[Chunk] {

  final protected def fromArraySeq[A](seq: mutable.ArraySeq[A]): Chunk[A] = {
    val builder = ChunkBuilder.make[A]()
    builder.sizeHint(seq)
    builder ++= seq
    builder.result()
  }

}
