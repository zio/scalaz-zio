/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
 * Copyright 2013-2021 Miles Sabin
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

import scala.annotation.implicitAmbiguous

/**
 * Evidence type `A` is not equal to type `B`.
 *
 * Based on https://github.com/milessabin/shapeless.
 */
sealed abstract class =!=[A, B] extends Serializable

object =!= {
  private val instance: =!=[Any, Any] = new =!=[Any, Any] {}

  def unexpected: Nothing = sys.error("Unexpected invocation")

  implicit def neq[A, B]: A =!= B = instance.asInstanceOf[=!=[A, B]]

  @implicitAmbiguous("Cannot prove that ${A} =!= ${A}")
  implicit def neqAmbig1[A]: A =!= A = unexpected
  implicit def neqAmbig2[A]: A =!= A = unexpected
}
