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

import java.io.IOException

trait ZInputStream {
  def readN(n: Int)(implicit trace: Trace): IO[Option[IOException], Chunk[Byte]]
  def skip(n: Long)(implicit trace: Trace): IO[IOException, Long]
  def readAll(bufferSize: Int)(implicit trace: Trace): IO[Option[IOException], Chunk[Byte]]
}

object ZInputStream {

  def fromInputStream(is: java.io.InputStream): ZInputStream =
    new ZInputStream {

      def readN(n: Int)(implicit trace: Trace): IO[Option[IOException], Chunk[Byte]] =
        ZIO.attemptBlockingIO {
          val b: Array[Byte] = new Array[Byte](n)
          val count          = is.read(b)
          if (count == -1) Exit.failNone else ZIO.succeed(Chunk.fromArray(b).take(count))
        }.mapError { case e: IOException =>
          Some(e)
        }.flatten

      def skip(n: Long)(implicit trace: Trace): IO[IOException, Long] =
        ZIO.attemptBlockingIO(is.skip(n))

      def readAll(bufferSize: Int)(implicit trace: Trace): IO[Option[IOException], Chunk[Byte]] =
        ZIO.attemptBlockingIO {
          val buffer = new java.io.ByteArrayOutputStream();
          val idata  = new Array[Byte](bufferSize);
          var count  = is.read(idata, 0, idata.length)

          if (count == -1) Exit.failNone
          else {
            var countTotalBytes = 0
            var data            = idata
            while (count != -1) {
              countTotalBytes = countTotalBytes + count
              buffer.write(data, 0, count);
              data = new Array[Byte](bufferSize)
              count = is.read(data, 0, data.length)
            }
            ZIO.succeed(Chunk.fromArray(buffer.toByteArray()).take(countTotalBytes))
          }
        }.mapError { case e: IOException =>
          Some(e)
        }.flatten

    }
}
