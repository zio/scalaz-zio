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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.annotation.tailrec

/**
 * `FiberRefs` is a data type that represents a collection of `FiberRef` values.
 * This allows safely propagating `FiberRef` values across fiber boundaries, for
 * example between an asynchronous producer and consumer.
 */
final class FiberRefs private (
  private[zio] val fiberRefLocals: Map[FiberRef[_], ::[(FiberId.Runtime, Any)]]
) { self =>

  /**
   * Returns a new fiber refs with the specified ref deleted from it.
   */
  def delete(fiberRef: FiberRef[_]): FiberRefs =
    FiberRefs(fiberRefLocals - fiberRef)

  /**
   * Returns a set of each `FiberRef` in this collection.
   */
  def fiberRefs: Set[FiberRef[_]] =
    fiberRefLocals.keySet

  /**
   * Forks this collection of fiber refs as the specified child fiber id. This
   * will potentially modify the value of the fiber refs, as determined by the
   * individual fiber refs that make up the collection.
   */
  def forkAs(childId: FiberId.Runtime): FiberRefs = {
    val childFiberRefLocals: Map[FiberRef[_], ::[(FiberId.Runtime, Any)]] =
      fiberRefLocals.transform { case (fiberRef, stack) =>
        val oldValue = stack.head._2.asInstanceOf[fiberRef.Value]
        val newValue = fiberRef.patch(fiberRef.fork)(oldValue)
        if (oldValue == newValue) stack else ::(childId -> newValue, stack)
      }

    FiberRefs(childFiberRefLocals)
  }

  /**
   * Gets the value of the specified `FiberRef` in this collection of `FiberRef`
   * values if it exists or `None` otherwise.
   */
  def get[A](fiberRef: FiberRef[A]): Option[A] =
    fiberRefLocals.get(fiberRef).map(_.head._2.asInstanceOf[A])

  /**
   * Gets the value of the specified `FiberRef` in this collection of `FiberRef`
   * values if it exists or the `initial` value of the `FiberRef` otherwise.
   */
  def getOrDefault[A](fiberRef: FiberRef[A]): A =
    get(fiberRef).getOrElse(fiberRef.initial)

  /**
   * Joins this collection of fiber refs to the specified collection, as the
   * specified fiber id. This will perform diffing and merging to ensure
   * preservation of maximum information from both child and parent refs.
   */
  def joinAs(fiberId: FiberId.Runtime)(that: FiberRefs): FiberRefs = {
    val parentFiberRefs = self.fiberRefLocals
    val childFiberRefs  = that.fiberRefLocals

    val fiberRefLocals = childFiberRefs.foldLeft(parentFiberRefs) { case (parentFiberRefs, (fiberRef, childStack)) =>
      val ref        = fiberRef.asInstanceOf[FiberRef[Any]]
      val childValue = childStack.head._2

      if (childStack.head._1 == fiberId) {
        parentFiberRefs
      } else {

        parentFiberRefs
          .get(ref)
          .fold {
            if (childValue == ref.initial) parentFiberRefs
            else parentFiberRefs.updated(ref, ::((fiberId, ref.join(ref.initial, childValue)), Nil))
          } { parentStack =>
            def compareFiberId(left: FiberId.Runtime, right: FiberId.Runtime): Int = {
              val compare = left.startTimeMillis.compare(right.startTimeMillis)
              if (compare == 0) left.id.compare(right.id) else compare
            }

            @tailrec
            def findAncestor(
              parentStack: List[(FiberId.Runtime, Any)],
              childStack: List[(FiberId.Runtime, Any)],
              childModified: Boolean = false
            ): (Any, Boolean) =
              (parentStack, childStack) match {
                case ((parentFiberId, _) :: parentAncestors, (childFiberId, childValue) :: childAncestors) =>
                  val compare = compareFiberId(parentFiberId, childFiberId)
                  if (compare < 0) findAncestor(parentStack, childAncestors, true)
                  else if (compare > 0) findAncestor(parentAncestors, childStack, childModified)
                  else (childValue, childModified)
                case _ =>
                  (ref.initial, true)
              }

            val (ancestor, wasModified) = findAncestor(parentStack, childStack)

            if (!wasModified) parentFiberRefs
            else {
              val patch = ref.diff(ancestor, childValue)

              val oldValue = parentStack.head._2
              val newValue = ref.join(oldValue, ref.patch(patch)(oldValue))

              if (oldValue == newValue) parentFiberRefs
              else {
                val newStack = parentStack match {
                  case (parentFiberId, _) :: tail =>
                    if (parentFiberId == fiberId) ::((parentFiberId, newValue), tail)
                    else ::((fiberId, newValue), parentStack)
                }
                parentFiberRefs.updated(ref, newStack)
              }
            }
          }
      }
    }

    FiberRefs(fiberRefLocals)
  }

  def setAll(implicit trace: Trace): UIO[Unit] =
    ZIO.foreachDiscard(fiberRefs) { fiberRef =>
      fiberRef.asInstanceOf[FiberRef[Any]].set(getOrDefault(fiberRef))
    }

  override final def toString(): String = fiberRefLocals.mkString("FiberRefLocals(", ",", ")")

  def updatedAs[A](fiberId: FiberId.Runtime)(fiberRef: FiberRef[A], value: A): FiberRefs = {
    val oldStack = fiberRefLocals.get(fiberRef).getOrElse(List.empty)
    val newStack =
      if (oldStack.isEmpty) ::((fiberId, value.asInstanceOf[Any]), Nil)
      else if (oldStack.head._1 == fiberId) ::((fiberId, value.asInstanceOf[Any]), oldStack.tail)
      else ::((fiberId, value), oldStack)

    FiberRefs(fiberRefLocals.updated(fiberRef, newStack))
  }

  private[zio] def updatedAsAll(fiberId: FiberId.Runtime)(fiberRefs: Map[FiberRef[_], Any]): FiberRefs =
    FiberRefs(
      fiberRefs.foldLeft(fiberRefLocals) { case (fiberRefLocals, (fiberRef, newValue)) =>
        fiberRefLocals.get(fiberRef) match {
          case Some(stack @ ((_, oldValue) :: tail)) if oldValue != newValue =>
            fiberRefLocals.updated(fiberRef, ::((fiberId, newValue), stack))
          case None => fiberRefLocals.updated(fiberRef, ::((fiberId, newValue), Nil))
          case _    => fiberRefLocals
        }
      }
    )
}

object FiberRefs {

  /**
   * The empty collection of `FiberRef` values.
   */
  val empty: FiberRefs =
    FiberRefs(Map.empty)

  private[zio] def apply(fiberRefLocals: Map[FiberRef[_], ::[(FiberId.Runtime, Any)]]): FiberRefs =
    new FiberRefs(fiberRefLocals)

  /**
   * A `Patch` captures the changes in `FiberRef` values made by a single fiber
   * as a value. This allows fibers to apply the changes made by a workflow
   * without inheriting all the `FiberRef` values of the fiber that executed the
   * workflow.
   */
  sealed abstract case class Patch private (private val patches: Map[FiberRef[_], Any])
      extends Function2[FiberId.Runtime, FiberRefs, FiberRefs] {

    /**
     * Applies the changes described by this patch to the specified collection
     * of `FiberRef` values.
     */
    def apply(fiberId: FiberId.Runtime, fiberRefs: FiberRefs): FiberRefs =
      patches.foldLeft(fiberRefs) { case (fiberRefs, (fiberRef, patch)) =>
        val value = fiberRefs.getOrDefault(fiberRef)
        fiberRefs.updatedAs(fiberId)(
          fiberRef.asInstanceOf[FiberRef[fiberRef.Value]],
          fiberRef.patch(patch.asInstanceOf[fiberRef.Patch])(value.asInstanceOf[fiberRef.Value])
        )
      }
  }

  object Patch {

    /**
     * Constructs a patch from a collection of patches to `FiberRef` values.
     */
    private[zio] def apply(patches: Map[FiberRef[_], Any]): Patch =
      new Patch(patches) {}
  }
}
