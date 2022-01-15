/*
 * Copyright 2020-2022 John A. De Goes and the ZIO Contributors
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

package zio.stream.internal

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

object Utils {
  def zipChunks[A, B, C](cl: Chunk[A], cr: Chunk[B], f: (A, B) => C): (Chunk[C], Either[Chunk[A], Chunk[B]]) =
    if (cl.size > cr.size)
      (cl.take(cr.size).zipWith(cr)(f), Left(cl.drop(cr.size)))
    else
      (cl.zipWith(cr.take(cl.size))(f), Right(cr.drop(cl.size)))

  def zipLeftChunks[A, B](cl: Chunk[A], cr: Chunk[B]): (Chunk[A], Either[Chunk[A], Chunk[B]]) =
    if (cl.size > cr.size)
      (cl.take(cr.size), Left(cl.drop(cr.size)))
    else
      (cl, Right(cr.drop(cl.size)))

  def zipRightChunks[A, B](cl: Chunk[A], cr: Chunk[B]): (Chunk[B], Either[Chunk[A], Chunk[B]]) =
    if (cl.size > cr.size)
      (cr, Left(cl.drop(cr.size)))
    else
      (cr.take(cl.size), Right(cr.drop(cl.size)))
}
