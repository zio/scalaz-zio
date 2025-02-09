/*
 * Copyright 2022-2024 John A. De Goes and the ZIO Contributors
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
package zio.internal

import scala.reflect.ClassTag

import zio.Chunk

private[zio] final class PinchableArray[A: ClassTag](hint: Int) extends Iterable[A] { self =>
  import java.lang.System

  private var array  = if (hint < 0) null else new Array[A](hint)
  private var _pinch = null.asInstanceOf[Array[A]]
  private var _size  = 0

  def +=(a: A): Unit = {
    ensureCapacity(1)

    array(_size) = a
    _size += 1
  }

  def ++=(as: Chunk[A]): Unit = {
    ensureCapacity(as.length)

    as.copyToArray(array, _size)

    _size = _size + as.length
  }

  def apply(index: Int): A = array(index) // No error checking for performance

  def ensureCapacity(elements: Int): Unit = {
    val newSize = _size + elements

    if (array eq null) {
      array = new Array[A](newSize)
    } else if (newSize > array.length) {
      val newStack = new Array[A](newSize + _size / 2)
      System.arraycopy(array, 0, newStack, 0, _size)
      array = newStack
    }
  }

  def iterator: Iterator[A] =
    new Iterator[A] {
      var index = 0

      def hasNext: Boolean = index < self._size

      def next(): A = {
        if (!hasNext) throw new NoSuchElementException("There are no elements to iterate")

        val value = self.array(index)

        index = index + 1

        value
      }
    }

  def length: Int = _size

  def pinch(): Chunk[A] = {
    val size = self._size

    if (size == 0) Chunk.empty
    else {
      ensurePinchCapacity(size)

      System.arraycopy(array, 0, _pinch, 0, size)

      _size = 0

      Chunk.fromArray(_pinch).take(size)
    }
  }

  private[zio] def asChunk(): Chunk[A] =
    if (array eq null) Chunk.empty
    else Chunk.fromArray(array).take(_size)

  private def ensurePinchCapacity(newSize: Int): Unit =
    if (_pinch eq null) {
      _pinch = new Array[A](newSize)
    } else if (newSize > _pinch.length) {
      _pinch = new Array[A](newSize)
    }

  override def toString(): String = self.mkString("PinchableArray(", ",", ")")
}
private[zio] object PinchableArray {
  def make[A: ClassTag](hint: Int): PinchableArray[A] = new PinchableArray[A](hint)
}
