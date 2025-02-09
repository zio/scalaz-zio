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

import scala.annotation.implicitNotFound

/**
 * A value of type `Eql[A, B]` provides implicit evidence that two values with
 * types `A` and `B` could potentially be equal, that is, that `A` is a subtype
 * of `B` or `B` is a subtype of `A`.
 */
@implicitNotFound(
  "This operation assumes that values of types ${A} and ${B} could " +
    "potentially be equal. However, ${A} and ${B} are unrelated types, so " +
    "they cannot be equal."
)
sealed abstract class Eql[A, B]
object Eql extends EqlLowPriority {
  implicit final def eqlSubtype1[A <: B, B]: Eql[A, B] = instance.asInstanceOf[Eql[A, B]]
}

private[test] sealed abstract class EqlLowPriority {
  protected val instance: Eql[Any, Any] = new Eql[Any, Any] {}

  implicit final def eqlSubtype2[A, B <: A]: Eql[A, B] = instance.asInstanceOf[Eql[A, B]]
}
