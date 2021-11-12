/*
 * Copyright 2017-2021 John A. De Goes and the ZIO Contributors
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

import scala.annotation.tailrec

/**
 * The identity of a Fiber, described by the time it began life, and a
 * monotonically increasing sequence number generated from an atomic counter.
 */
sealed trait FiberId extends Serializable { self =>
  import FiberId._

  def combine(that: FiberId): FiberId =
    (self, that) match {
      case (None, that)                                 => that
      case (that, None)                                 => that
      case (Composite(self), Composite(that))           => Composite(self | that)
      case (Composite(self), that @ Runtime(_, _))      => Composite(self + that)
      case (self @ Runtime(_, _), Composite(that))      => Composite(that + self)
      case (self @ Runtime(_, _), that @ Runtime(_, _)) => Composite(Set(self, that))
    }

  def ids: Set[Int] =
    self match {
      case None                => Set.empty
      case Runtime(id, _)      => Set(id)
      case Composite(fiberIds) => fiberIds.map(_.id)
    }
}

object FiberId {

  def apply(id: Int, startTimeSeconds: Int): FiberId =
    Runtime(id, startTimeSeconds)

  def combineAll(fiberIds: Set[FiberId]): FiberId =
    fiberIds.foldLeft[FiberId](FiberId.None)(_ combine _)

  case object None                                           extends FiberId
  final case class Runtime(id: Int, startTimeSeconds: Int)   extends FiberId
  final case class Composite(fiberIds: Set[FiberId.Runtime]) extends FiberId
}
