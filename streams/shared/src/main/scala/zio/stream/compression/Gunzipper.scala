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

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.zip.{CRC32, Inflater}
import java.{util => ju}
import scala.annotation.tailrec

/**
 * Performs few steps of parsing header, then decompresses body and checks
 * trailer. With reasonably chosen bufferSize there shouldn't occur many
 * concatenation of arrays.
 */
private[compression] class Gunzipper private (bufferSize: Int) {

  import Gunzipper._

  private var state: State = new ParseHeaderStep(Array.emptyByteArray, new CRC32)

  def close(): Unit = state.close()

  def onChunk(c: Chunk[Byte])(implicit trace: Trace): ZIO[Any, CompressionException, Chunk[Byte]] =
    ZIO.attempt {
      val (newState, output) = state.feed(c.toArray)
      state = newState
      output
    }.refineOrDie {
      case e: ju.zip.DataFormatException => CompressionException(e)
      case e: CompressionException       => e
    }

  def onNone(implicit trace: Trace): ZIO[Any, CompressionException, Chunk[Byte]] =
    if (state.isInProgress) ZIO.fail(CompressionException("Stream closed before completion."))
    else Exit.emptyChunk

  private def nextStep(
    acc: Array[Byte],
    checkCrc16: Boolean,
    crc32: CRC32,
    parseExtra: Boolean,
    commentsToSkip: Int
  ): Gunzipper.State =
    if (parseExtra) new ParseExtraStep(acc, crc32, checkCrc16, commentsToSkip)
    else if (commentsToSkip > 0) new SkipCommentsStep(checkCrc16, crc32, commentsToSkip)
    else if (checkCrc16) new CheckCrc16Step(Array.emptyByteArray, crc32.getValue())
    else new Decompress()

  private class ParseHeaderStep(acc: Array[Byte], crc32: CRC32) extends State {

    //TODO: If whole input is shorther than fixed header, not output is produced and no error is singaled. Is it ok?
    override def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte]) = {

      val bytes = acc ++ chunkBytes
      if (bytes.length < fixedHeaderLength) (new ParseHeaderStep(bytes, crc32), Chunk.empty)
      else {
        val (header, leftover) = bytes.splitAt(fixedHeaderLength)
        crc32.update(header)
        if (u8(header(0)) != 31 || u8(header(1)) != 139) throw CompressionException("Invalid GZIP header")
        else if (header(2) != 8)
          throw CompressionException(s"Only deflate (8) compression method is supported, present: ${header(2)}")
        else {
          val flags           = header(3) & 0xff
          val checkCrc16      = (flags & 2) > 0
          val hasExtra        = (flags & 4) > 0
          val skipFileName    = (flags & 8) > 0
          val skipFileComment = (flags & 16) > 0
          val commentsToSkip  = (if (skipFileName) 1 else 0) + (if (skipFileComment) 1 else 0)
          nextStep(header, checkCrc16, crc32, hasExtra, commentsToSkip).feed(leftover)
        }
      }
    }

    override def isInProgress: Boolean = acc.nonEmpty
  }

  private class ParseExtraStep(acc: Array[Byte], crc32: CRC32, checkCrc16: Boolean, commentsToSkip: Int) extends State {

    override def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte]) = {
      val bytes = acc ++ chunkBytes
      if (bytes.length < 12) {
        (new ParseExtraStep(bytes, crc32, checkCrc16, commentsToSkip), Chunk.empty)
      } else {
        val xlenLenght            = 2
        val extraBytes: Int       = u16(bytes(fixedHeaderLength), bytes(fixedHeaderLength + 1))
        val headerWithExtraLength = fixedHeaderLength + xlenLenght + extraBytes
        if (bytes.length < headerWithExtraLength)
          (new ParseExtraStep(bytes, crc32, checkCrc16, commentsToSkip), Chunk.empty)
        else {
          val (headerWithExtra, leftover) = bytes.splitAt(headerWithExtraLength)
          crc32.update(headerWithExtra.drop(fixedHeaderLength))
          nextStep(headerWithExtra, checkCrc16, crc32, false, commentsToSkip).feed(leftover)
        }
      }
    }
  }

  private class SkipCommentsStep(checkCrc16: Boolean, crc32: CRC32, commentsToSkip: Int) extends State {
    override def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte]) = {
      val idx               = chunkBytes.indexOf(0)
      val (upTo0, leftover) = if (idx == -1) (chunkBytes, Array.emptyByteArray) else chunkBytes.splitAt(idx + 1)
      crc32.update(upTo0)
      nextStep(Array.emptyByteArray, checkCrc16, crc32, false, commentsToSkip - 1).feed(leftover)
    }
  }

  private class CheckCrc16Step(pastCrc16Bytes: Array[Byte], crcValue: Long) extends State {
    override def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte]) = {
      val (crc16Bytes, leftover) = (pastCrc16Bytes ++ chunkBytes).splitAt(2)
      //Unlikely but possible that chunk was 1 byte only, leftover is empty.
      if (crc16Bytes.length < 2) {
        (new CheckCrc16Step(crc16Bytes, crcValue), Chunk.empty)
      } else {
        val computedCrc16 = (crcValue & 0xffffL).toInt
        val expectedCrc   = u16(crc16Bytes(0), crc16Bytes(1))
        if (computedCrc16 != expectedCrc) throw CompressionException("Invalid header CRC16")
        else new Decompress().feed(leftover)
      }
    }
  }
  private class Decompress extends State {
    private val inflater            = new Inflater(true)
    private val crc32: CRC32        = new CRC32
    private val buffer: Array[Byte] = new Array[Byte](bufferSize)

    private[compression] def pullOutput(
      inflater: Inflater,
      buffer: Array[Byte]
    ): Chunk[Byte] = {
      @tailrec
      def next(prev: Chunk[Byte]): Chunk[Byte] = {
        val read     = inflater.inflate(buffer)
        val newBytes = ju.Arrays.copyOf(buffer, read)
        crc32.update(newBytes)
        val current = Chunk.fromArray(newBytes)
        val pulled  = prev ++ current
        if (read > 0 && inflater.getRemaining > 0) next(pulled) else pulled
      }
      if (inflater.needsInput()) Chunk.empty else next(Chunk.empty)
    }

    override def close(): Unit = inflater.end()

    override def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte]) = {
      inflater.setInput(chunkBytes)
      val newChunk = pullOutput(inflater, buffer)
      if (inflater.finished()) {
        val leftover = chunkBytes.takeRight(inflater.getRemaining())
        val (state, restOfChunks) =
          new CheckTrailerStep(Array.emptyByteArray, crc32.getValue(), inflater.getBytesWritten()).feed(leftover)
        (state, newChunk ++ restOfChunks)
      } else (this, newChunk)
    }
  }

  private class CheckTrailerStep(acc: Array[Byte], expectedCrc32: Long, expectedIsize: Long) extends State {

    private def readInt(a: Array[Byte]): Int = u32(a(0), a(1), a(2), a(3))

    override def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte]) = {
      val bytes = acc ++ chunkBytes
      if (bytes.length < 8)
        ((new CheckTrailerStep(bytes, expectedCrc32, expectedIsize), Chunk.empty)) // need more input
      else {
        val (trailerBytes, leftover) = bytes.splitAt(8)
        val crc32                    = readInt(trailerBytes.take(4))
        val isize                    = readInt(trailerBytes.drop(4))
        if (expectedCrc32.toInt != crc32) throw CompressionException("Invalid CRC32")
        else if (expectedIsize.toInt != isize) throw CompressionException("Invalid ISIZE")
        else new ParseHeaderStep(Array.emptyByteArray, new CRC32()).feed(leftover)
      }
    }
  }

  private def u8(b: Byte): Int = b & 0xff

  private def u16(b1: Byte, b2: Byte): Int = u8(b1) | (u8(b2) << 8)

  private def u32(b1: Byte, b2: Byte, b3: Byte, b4: Byte) = u16(b1, b2) | (u16(b3, b4) << 16)

}

private[stream] object Gunzipper {

  private val fixedHeaderLength = 10

  private sealed trait State {
    def close(): Unit = ()
    def feed(chunkBytes: Array[Byte]): (State, Chunk[Byte])
    def isInProgress: Boolean = true
  }

  def make(bufferSize: Int)(implicit trace: Trace): ZIO[Any, Nothing, Gunzipper] =
    ZIO.succeed(new Gunzipper(bufferSize))
}
