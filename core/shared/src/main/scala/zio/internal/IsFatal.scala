/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

sealed trait IsFatal extends (Throwable => Boolean) { self =>
  import IsFatal._

  def apply(t: Throwable): Boolean =
    if (t.isInstanceOf[StackOverflowError]) true
    else
      self match {
        case Both(left, right) => left(t) || right(t)
        case Empty             => false
        case Single(tag)       => tag.isAssignableFrom(t.getClass)
      }

  def |(that: IsFatal): IsFatal =
    (self, that) match {
      case (self, Empty) => self
      case (Empty, that) => that
      case (self, that)  => Both(self, that)
    }
}

object IsFatal {

  def apply(tag: Class[_ <: Throwable]): IsFatal =
    Single(tag)

  val empty: IsFatal =
    Empty

  private final case class Single(tag: Class[_ <: Throwable])  extends IsFatal
  private case object Empty                                    extends IsFatal
  private final case class Both(left: IsFatal, right: IsFatal) extends IsFatal

  sealed trait Patch { self =>

    def apply(isFatal: IsFatal): IsFatal = {

      def loop(isFatal: IsFatal, patches: List[Patch]): IsFatal =
        patches match {
          case Patch.Add(added) :: patches             => loop(isFatal | added, patches)
          case Patch.AndThen(first, second) :: patches => loop(isFatal, first :: second :: patches)
          case Patch.Empty :: patches                  => loop(isFatal, patches)
          case Patch.Remove(removed) :: patches        => loop(remove(isFatal, removed), patches)
          case Nil                                     => isFatal
        }

      loop(isFatal, List(self))
    }

    def combine(that: Patch): Patch =
      Patch.AndThen(self, that)
  }

  object Patch {

    def diff(oldValue: IsFatal, newValue: IsFatal): Patch =
      if (oldValue == newValue) Empty
      else {
        val oldIsFatal = toSet(oldValue)
        val newIsFatal = toSet(newValue)
        val added = newIsFatal
          .diff(oldIsFatal)
          .foldLeft(empty)((patch, isFatal) => patch.combine(Add(isFatal)))
        val removed = oldIsFatal
          .diff(newIsFatal)
          .foldLeft(empty)((patch, isFatal) => patch.combine(Remove(isFatal)))
        added.combine(removed)
      }

    val empty: Patch =
      Empty

    private final case class Add(isFatal: IsFatal)                extends Patch
    private final case class AndThen(first: Patch, second: Patch) extends Patch
    private case object Empty                                     extends Patch
    private final case class Remove(isFatal: IsFatal)             extends Patch
  }

  private def remove(self: IsFatal, that: IsFatal): IsFatal =
    if (self == that) IsFatal.empty
    else
      self match {
        case Both(left, right) => remove(left, that) | remove(right, that)
        case supervisor        => supervisor
      }

  private[zio] def toSet(isFatal: IsFatal): Set[IsFatal] =
    if (isFatal == IsFatal.empty) Set.empty
    else
      isFatal match {
        case Both(left, right) => toSet(left) ++ toSet(right)
        case supervisor        => Set(supervisor)
      }
}
