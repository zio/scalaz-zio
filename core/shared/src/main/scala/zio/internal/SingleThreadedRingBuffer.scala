/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] final class SingleThreadedRingBuffer[A](capacity: Int) {
  private[this] val array   = new Array[AnyRef](capacity)
  private[this] var size    = 0
  private[this] var current = 0

  def head: Option[A] =
    Option(array(current)).asInstanceOf[Option[A]]

  def lastOrNull: A =
    if (size == 0) null.asInstanceOf[A]
    else {
      val index = if (current == 0) array.length - 1 else current - 1

      array(index).asInstanceOf[A]
    }

  def put(value: A): Unit = {
    array(current) = value.asInstanceOf[AnyRef]
    increment()
  }

  def dropLast(): Unit =
    if (size > 0) {
      decrement()
      array(current) = null
    }

  def toChunk: Chunk[A] = {
    val begin = current - size

    val newArray = if (begin < 0) {
      array.slice(capacity + begin, capacity) ++ array.slice(0, current)
    } else {
      array.slice(begin, current)
    }

    Chunk.fromArray(newArray).asInstanceOf[Chunk[A]]
  }

  def toReversedList: List[A] = {
    val begin = current - size

    val newArray = if (begin < 0) {
      array.slice(capacity + begin, capacity) ++ array.slice(0, current)
    } else {
      array.slice(begin, current)
    }

    arrayToReversedList(newArray).asInstanceOf[List[A]]
  }

  @inline private[this] def arrayToReversedList(array: Array[AnyRef]): List[AnyRef] = {
    var i                    = 0
    var result: List[AnyRef] = Nil
    while (i < array.length) {
      result ::= array(i)
      i += 1
    }
    result
  }

  @inline private[this] def increment(): Unit = {
    if (size < capacity) {
      size = size + 1
    }
    current = (current + 1) % capacity
  }

  @inline private[this] def decrement(): Unit = {
    size = size - 1
    if (current > 0) {
      current = current - 1
    } else {
      current = capacity - 1
    }
  }
}

private[zio] object SingleThreadedRingBuffer {
  def apply[A](capacity: Int): SingleThreadedRingBuffer[A] = new SingleThreadedRingBuffer[A](capacity)
}
