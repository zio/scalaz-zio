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

import scala.annotation.tailrec

/**
 * A `List` data type that tries to avoid allocating by special-casing the
 * singleton list and preventing pattern matching.
 */
private[zio] object FastList {
  private[zio] trait ListModule {
    type List[+A]

    def empty[A]: List[A]
    def cons[A](a: A, as: List[A]): List[A]
    def isEmpty[A](as: List[A]): Boolean
    def head[A](as: List[A]): A
    def tail[A](as: List[A]): List[A]
  }
  final val listModule: ListModule =
    new ListModule {
      type List[+A] = Any

      def empty[A]: List[A] = null
      def cons[A](a: A, as: List[A]): List[A] =
        as match {
          case null => a
          case as   => (a, as)
        }
      def isEmpty[A](as: List[A]): Boolean =
        as match {
          case null => true
          case _    => false
        }
      def head[A](as: List[A]): A =
        as match {
          case null   => throw new NoSuchElementException()
          case (a, _) => a.asInstanceOf[A]
          case a      => a.asInstanceOf[A]
        }
      def tail[A](as: List[A]): List[A] =
        as match {
          case (_, t) => t.asInstanceOf[List[A]]
          case null   => throw new IllegalStateException()
          case a      => null
        }
    }
  type List[+A] = listModule.List[A]
  private[zio] implicit final class ListExtensionMethods[A](val self: List[A]) extends AnyVal {
    def ::(a: A): List[A] = listModule.cons(a, self)

    def dropWhile(p: A => Boolean): List[A] =
      if (isEmpty) List.Nil
      else {
        val h = head
        val t = tail

        if (p(h)) t.dropWhile(p)
        else self
      }

    def exists(p: A => Boolean): Boolean =
      if (isEmpty) false
      else p(head) || tail.exists(p)

    def filter(p: A => Boolean): List[A] =
      if (isEmpty) List.Nil
      else {
        val h = head
        val t = tail

        if (p(h)) h :: t.filter(p)
        else t.filter(p)
      }

    @tailrec
    def foldLeft[Z](z: Z)(f: (Z, A) => Z): Z =
      if (isEmpty) z
      else {
        val z2 = f(z, head)

        tail.foldLeft(z2)(f)
      }

    def forall(p: A => Boolean): Boolean =
      if (isEmpty) true
      else p(head) && tail.forall(p)

    def head: A = listModule.head(self)

    def isEmpty: Boolean = listModule.isEmpty(self)

    def nonEmpty: Boolean = !listModule.isEmpty(self)

    def size: Int = foldLeft(0)((sum, _) => sum + 1)

    def tail: List[A] = listModule.tail(self)
  }
  private[zio] object List {
    def apply[A](as: A*): List[A] =
      as.foldRight(listModule.empty[A]) { case (a, acc) =>
        a :: acc
      }

    def empty[A]: List[A] = listModule.empty[A]

    val Nil: List[Nothing] = listModule.empty[Nothing]

    def Cons[A](head: A, tail: List[A]): List[A] = listModule.cons(head, tail)
  }
}
