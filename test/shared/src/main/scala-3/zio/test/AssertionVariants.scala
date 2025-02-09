/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.{ErrorMessage => M}
import zio.test.Assertion.Arguments.valueArgument

trait AssertionVariants {
  private def diffProduct[T](
    obj1: T,
    obj2: T,
    paramNames: List[String] = Nil,
    rootClassName: Option[String] = None
  ): String = {
    val currClassName = rootClassName.getOrElse(obj1.getClass.getSimpleName)

    (obj1, obj2) match {
      case (seq1: Iterable[Any], seq2: Iterable[Any]) => {
        val maxSize    = math.max(seq1.size, seq2.size)
        val paddedSeq1 = seq1.toVector.padTo(maxSize, null)
        val paddedSeq2 = seq2.toVector.padTo(maxSize, null)

        paddedSeq1
          .zip(paddedSeq2)
          .zipWithIndex
          .flatMap { case ((subObj1, subObj2), index) =>
            val newParamName = s"[$index]"
            if (subObj1 != subObj2 && !subObj1.isInstanceOf[Product]) {
              val paramName = s"${paramNames.reverse.mkString("")}[$index]"
              Some(s"$currClassName$paramName : expected '$subObj2' got '$subObj1'\n")
            } else {
              diffProduct(subObj1, subObj2, newParamName :: paramNames, Some(currClassName))
            }
          }
          .mkString
      }
      case (obj1: Product, obj2: Product) if obj1.productArity == obj2.productArity =>
        obj1.productIterator
          .zip(obj2.productIterator)
          .zip(obj1.productElementNames)
          .flatMap { case ((subObj1, subObj2), paramName) =>
            val newParamName = if (paramName.nonEmpty) s".$paramName" else ""
            if (subObj1 != subObj2 && !subObj1.isInstanceOf[Product])
              s"$currClassName${paramNames.reverse.mkString("")}$newParamName : expected '$subObj2' got '$subObj1'\n"
            else
              diffProduct(subObj1, subObj2, newParamName :: paramNames, Some(currClassName))
          }
          .mkString
      case _ => ""
    }
  }

  /**
   * Makes a new assertion that requires a value equal the specified value.
   */
  final def equalTo[A](expected: A): Assertion[A] =
    Assertion[A](
      TestArrow
        .make[A, Boolean] { actual =>
          val result = (actual, expected) match {
            case (left: Array[_], right: Array[_])         => left.sameElements[Any](right)
            case (left: CharSequence, right: CharSequence) => left.toString == right.toString
            case (left, right)                             => left == right
          }
          TestTrace.boolean(result) {
            if (expected.isInstanceOf[Product]) {
              M.text(diffProduct(actual, expected))
            } else {
              M.pretty(actual) + M.equals + M.pretty(expected)
            }
          }
        }
        .withCode("equalTo", valueArgument(expected))
    )
}
