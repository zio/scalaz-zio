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

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.compression.Gzipper._
import zio.{Chunk, ZIO}

import java.util.zip.{CRC32, Deflater}
import zio.Trace

private[compression] class Gzipper(
  bufferSize: Int,
  level: CompressionLevel,
  strategy: CompressionStrategy,
  flushMode: FlushMode
) {
  private val crc             = new CRC32()
  private val buffer          = new Array[Byte](bufferSize)
  private var headerSent      = false
  private var inputSize: Long = 0
  private val deflater: Deflater = {
    val deflater = new Deflater(level.jValue, true)
    deflater.setStrategy(strategy.jValue)
    deflater
  }

  def onNone(implicit trace: Trace): ZIO[Any, Nothing, Chunk[Byte]] = ZIO.succeed {
    deflater.finish()
    val restAndTrailer = Deflate.pullOutput(deflater, buffer, flushMode) ++ getTrailer
    val lastChunk      = if (headerSent) restAndTrailer else header ++ restAndTrailer
    deflater.reset()
    crc.reset()
    inputSize = 0
    headerSent = false
    lastChunk
  }

  def onChunk(chunk: Chunk[Byte])(implicit trace: Trace): ZIO[Any, Nothing, Chunk[Byte]] = ZIO.succeed {
    val input = chunk.toArray
    inputSize += input.length
    crc.update(input)
    deflater.setInput(input)
    val deflated = Deflate.pullOutput(deflater, buffer, flushMode)
    if (headerSent) deflated
    else {
      headerSent = true
      header ++ deflated
    }
  }

  def getTrailer: Chunk[Byte] = {
    def byte(v: Long, n: Int) = ((v >> n * 8) & 0xff).toByte

    val v = crc.getValue

    // ISIZE (Input SIZE) -- this contains the size of the original (uncompressed) input data modulo 2^32.
    val s = inputSize & 0xffffffffL

    Chunk(byte(v, 0), byte(v, 1), byte(v, 2), byte(v, 3), byte(s, 0), byte(s, 1), byte(s, 2), byte(s, 3))
  }

  def close(): Unit =
    deflater.finish()
}

private[stream] object Gzipper {

  // No XFL regardless maximum of the fastest compression used, no MTIME timestamp, unknown OS.
  private val header: Chunk[Byte] = Chunk(31, 139, 8, 0, 0, 0, 0, 0, 0, 255).map(_.toByte)

  def make(
    bufferSize: Int,
    level: CompressionLevel,
    strategy: CompressionStrategy,
    flushMode: FlushMode
  )(implicit trace: Trace): ZIO[Any, Nothing, Gzipper] =
    ZIO.succeed(new Gzipper(bufferSize, level, strategy, flushMode))
}
