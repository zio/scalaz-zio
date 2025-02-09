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

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A very fast, growable/shrinkable, mutable stack.
 */
private[zio] final class Stack[A <: AnyRef]() extends Iterable[A] { self =>
  private[this] var array  = new Array[AnyRef](13)
  private[this] var packed = 0

  private def getUsed: Int          = packed & 0xf
  private def setUsed(n: Int): Unit = packed = (packed & 0xfffffff0) + (n & 0xf)

  private def getNesting: Int          = (packed >>> 4)
  private def setNesting(n: Int): Unit = packed = (packed & 0xf) + (n << 4)

  override def size: Int = getUsed + (12 * getNesting)

  def iterator: Iterator[A] =
    new Iterator[A] {
      var currentArray = self.array
      var currentIndex = findNonNull(currentArray)
      var _next        = computeNext()

      private def hasMoreChunks(): Boolean = (currentArray ne null) && currentArray(0).isInstanceOf[Array[_]]

      def hasNext: Boolean = _next ne null

      def next(): A = {
        if (!hasNext) throw new NoSuchElementException("There are no elements to iterate")

        val value = _next

        var result = null.asInstanceOf[A]

        while ((result eq null) && currentIndex >= 0) {
          result = computeNext()
        }

        _next = result

        value
      }

      private def computeNext(): A =
        if (currentIndex < 0) null.asInstanceOf[A]
        else {
          if (currentIndex == 0 && hasMoreChunks()) {
            currentArray = currentArray(0).asInstanceOf[Array[AnyRef]]
            currentIndex = 12
          }

          val value = currentArray(currentIndex).asInstanceOf[A]

          currentIndex = currentIndex - 1

          value
        }

      private def findNonNull[A <: AnyRef](array: Array[A]): Int = {
        var index = -1

        var i = array.length - 1

        while (i >= 0) {
          if (array(i) ne null) {
            index = i
            i = -1 // Terminate loop
          } else {
            i = i - 1
          }
        }

        index
      }
    }

  /**
   * Determines if the stack is empty.
   */
  override def isEmpty: Boolean =
    getUsed <= 0

  /**
   * Pushes an item onto the stack.
   */
  def push(a: A): Unit =
    if (getUsed == 13) {
      array = Array(array, a, null, null, null, null, null, null, null, null, null, null, null)
      setUsed(2)
      setNesting(getNesting + 1)
    } else {
      array(getUsed) = a
      setUsed(getUsed + 1)
    }

  /**
   * Pops an item off the stack, or returns `null` if the stack is empty.
   */
  def pop(): A =
    if (getUsed <= 0) {
      null.asInstanceOf[A]
    } else {
      val idx = getUsed - 1
      var a   = array(idx)
      if (idx == 0 && getNesting > 0) {
        array = a.asInstanceOf[Array[AnyRef]]
        a = array(12)
        array(12) = null // GC
        setUsed(12)
        setNesting(getNesting - 1)
      } else {
        array(idx) = null // GC
        setUsed(idx)
      }
      a.asInstanceOf[A]
    }

  def popOrElse(that: A): A = {
    val a = pop()

    if (a eq null) that else a
  }

  /**
   * Peeks the item on the head of the stack, or returns `null` if empty.
   */
  def peek(): A =
    if (getUsed <= 0) {
      null.asInstanceOf[A]
    } else {
      val idx = getUsed - 1
      var a   = array(idx)
      if (idx == 0 && getNesting > 0) a = (a.asInstanceOf[Array[AnyRef]])(12)
      a.asInstanceOf[A]
    }

  def peekOrElse(a: A): A = if (getUsed <= 0) a else peek()
}

private[zio] object Stack {
  def apply[A <: AnyRef](): Stack[A] = new Stack[A]
  def apply[A <: AnyRef](a: A): Stack[A] = {
    val stack = new Stack[A]

    stack.push(a)

    stack
  }

  def fromIterable[A <: AnyRef](as: Iterable[A]): Stack[A] = {
    val stack = Stack[A]()

    as.toList.reverse.foreach(stack.push(_))

    stack
  }
}
