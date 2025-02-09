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

package zio.stream.compression

import zio.{Chunk, Scope, ZIO, Trace}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.zip.Deflater
import java.{util => ju}
import scala.annotation.tailrec

private[compression] object Deflate {

  def makeDeflater(
    bufferSize: Int = 64 * 1024,
    noWrap: Boolean = false,
    level: CompressionLevel,
    strategy: CompressionStrategy,
    flushMode: FlushMode
  )(implicit trace: Trace): ZIO[Scope, Nothing, Option[Chunk[Byte]] => ZIO[Any, Nothing, Chunk[Byte]]] =
    ZIO
      .acquireRelease(ZIO.succeed {
        val deflater = new Deflater(level.jValue, noWrap)
        deflater.setStrategy(strategy.jValue)
        (deflater, new Array[Byte](bufferSize))
      }) { case (deflater, _) =>
        ZIO.succeed(deflater.end())
      }
      .map {
        case (deflater, buffer) => {
          case Some(chunk) =>
            ZIO.succeed {
              deflater.setInput(chunk.toArray)
              Deflate.pullOutput(deflater, buffer, flushMode)
            }
          case None =>
            ZIO.succeed {
              deflater.finish()
              val out = Deflate.pullOutput(deflater, buffer, flushMode)
              deflater.reset()
              out
            }
        }
      }

  private[compression] def pullOutput(deflater: Deflater, buffer: Array[Byte], flushMode: FlushMode): Chunk[Byte] = {
    @tailrec
    def next(acc: Chunk[Byte]): Chunk[Byte] = {
      val size    = deflater.deflate(buffer, 0, buffer.length, flushMode.jValue)
      val current = Chunk.fromArray(ju.Arrays.copyOf(buffer, size))
      if (current.isEmpty) acc
      else next(acc ++ current)
    }

    next(Chunk.empty)
  }

}
