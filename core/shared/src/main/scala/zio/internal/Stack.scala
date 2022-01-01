/*
 * Copyright 2017-2022 John A. De Goes and the ZIO Contributors
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

/**
 * A very fast, growable/shrinkable, mutable stack.
 */
private[zio] final class Stack[A <: AnyRef]() {
  private[this] var array   = new Array[AnyRef](13)
  private[this] var size    = 0
  private[this] var nesting = 0

  /**
   * Determines if the stack is empty.
   */
  def isEmpty: Boolean = size == 0

  /**
   * Pushes an item onto the stack.
   */
  def push(a: A): Unit =
    if (size == 13) {
      array = Array(array, a, null, null, null, null, null, null, null, null, null, null, null)
      size = 2
      nesting += 1
    } else {
      array(size) = a
      size += 1
    }

  /**
   * Pops an item off the stack, or returns `null` if the stack is empty.
   */
  def pop(): A =
    if (size <= 0) {
      null.asInstanceOf[A]
    } else {
      val idx = size - 1
      var a   = array(idx)
      if (idx == 0 && nesting > 0) {
        array = a.asInstanceOf[Array[AnyRef]]
        a = array(12)
        array(12) = null // GC
        size = 12
        nesting -= 1
      } else {
        array(idx) = null // GC
        size = idx
      }
      a.asInstanceOf[A]
    }

  /**
   * Peeks the item on the head of the stack, or returns `null` if empty.
   */
  def peek(): A =
    if (size <= 0) {
      null.asInstanceOf[A]
    } else {
      val idx = size - 1
      var a   = array(idx)
      if (idx == 0 && nesting > 0) a = (a.asInstanceOf[Array[AnyRef]])(12)
      a.asInstanceOf[A]
    }

  def peekOrElse(a: A): A = if (size <= 0) a else peek()
}

private[zio] object Stack {
  def apply[A <: AnyRef](): Stack[A] = new Stack[A]
  def apply[A <: AnyRef](a: A): Stack[A] = {
    val stack = new Stack[A]

    stack.push(a)

    stack
  }
}
