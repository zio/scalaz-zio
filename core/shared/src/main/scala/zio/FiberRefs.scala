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

package zio

import zio.internal.SpecializationHelpers.SpecializeInt
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.annotation.tailrec

/**
 * `FiberRefs` is a data type that represents a collection of `FiberRef` values.
 * This allows safely propagating `FiberRef` values across fiber boundaries, for
 * example between an asynchronous producer and consumer.
 */
final class FiberRefs private (
  private[zio] val fiberRefLocals: Map[FiberRef[_], FiberRefs.Value]
) { self =>
  import FiberRef.currentRuntimeFlags
  import zio.FiberRefs.{StackEntry, Value}

  /**
   * Returns a new fiber refs with the specified ref deleted from it.
   */
  def delete(fiberRef: FiberRef[_]): FiberRefs = {
    val newMap = fiberRefLocals - fiberRef
    if (newMap eq fiberRefLocals) self
    else FiberRefs(newMap)
  }

  /**
   * Returns a set of each `FiberRef` in this collection.
   */
  def fiberRefs: Set[FiberRef[_]] =
    fiberRefLocals.keySet

  /**
   * Boolean flag which indicates whether the FiberRefs map contains an entry
   * that will cause the map to be changed when [[forkAs]] is called.
   *
   * This way we can avoid calling `fiberRefLocals.transform` on every
   * invocation of [[forkAs]] when we already know that the map will not be
   * transformed
   */
  private var needsTransformWhenForked: Boolean = true

  /**
   * Forks this collection of fiber refs as the specified child fiber id. This
   * will potentially modify the value of the fiber refs, as determined by the
   * individual fiber refs that make up the collection.
   */
  def forkAs(childId: FiberId.Runtime): FiberRefs =
    if (needsTransformWhenForked) {
      val childMap = fiberRefLocals.transform { (fiberRef, entry) =>
        if (fiberRef.fork == ZIO.identityFn[Any]) {
          entry
        } else {
          import entry.{depth, stack}

          type T = fiberRef.Value with AnyRef
          val oldValue = stack.head.value.asInstanceOf[T]
          val newValue = fiberRef.patch(fiberRef.fork)(oldValue).asInstanceOf[T]
          if (oldValue eq newValue) entry
          else Value(::(StackEntry(childId, newValue, 0), stack), depth + 1)
        }
      }

      if (childMap ne fiberRefLocals) FiberRefs(childMap)
      else {
        needsTransformWhenForked = false
        self
      }
    } else self

  /**
   * Gets the value of the specified `FiberRef` in this collection of `FiberRef`
   * values if it exists or `None` otherwise.
   */
  def get[A](fiberRef: FiberRef[A]): Option[A] =
    Option(getOrNull(fiberRef))

  /**
   * Gets the value of the specified `FiberRef` in this collection of `FiberRef`
   * values if it exists or the `initial` value of the `FiberRef` otherwise.
   */
  def getOrDefault[@specialized(SpecializeInt) A](fiberRef: FiberRef[A]): A = {
    val out = fiberRefLocals.getOrElse(fiberRef, null)
    if (out eq null) fiberRef.initial
    else out.stack.head.asInstanceOf[StackEntry[A]].value // asInstanceOf needed to avoid boxing
  }

  /**
   * Gets the value of the `RuntimeFlags` in this collection of `FiberRef`
   * values if it exists or the `initial` value if it doesn't.
   *
   * This method is implemented separately of [[getOrDefault]] to avoid boxing
   * across for both Scala 2 and 3.
   *
   * '''NOTE''': This method is package-private so that it can be used in
   * `FiberRuntime`. For extracting the value of `RuntimeFlags`` anywhere else,
   * use `ZIO.withFiberRuntime`.
   */
  private[zio] def getRuntimeFlags(implicit unsafe: Unsafe): RuntimeFlags = {
    val out = fiberRefLocals.getOrElse(currentRuntimeFlags, null)
    if (out eq null) currentRuntimeFlags.initial
    else out.stack.head.asInstanceOf[StackEntry[RuntimeFlags]].value // asInstanceOf needed to avoid boxing
  }

  private[zio] def getOrNull[A](fiberRef: FiberRef[A]): A = {
    val out = fiberRefLocals.getOrElse(fiberRef, null)
    if (out eq null) null else out.stack.head.value
  }.asInstanceOf[A]

  /**
   * Joins this collection of fiber refs to the specified collection, as the
   * specified fiber id. This will perform diffing and merging to ensure
   * preservation of maximum information from both child and parent refs.
   */
  def joinAs(fiberId: FiberId.Runtime)(that: FiberRefs): FiberRefs = {
    val childFiberRefs  = that.fiberRefLocals
    var fiberRefLocals0 = self.fiberRefLocals

    val it = childFiberRefs.iterator
    while (it.hasNext) {
      val (fiberRef, value0) = it.next()
      val childStack         = value0.stack
      val childHead          = childStack.head

      if (childHead.id ne fiberId) {
        val childDepth = value0.depth
        val childValue = childHead.value
        val ref        = fiberRef.asInstanceOf[FiberRef[Any]]
        val value1     = fiberRefLocals0.getOrElse(ref, null)

        if (value1 eq null) {
          if (childValue.asInstanceOf[AnyRef] ne ref.initial.asInstanceOf[AnyRef]) {
            val newValue = Value(::(StackEntry(fiberId, ref.join(ref.initial, childValue), 0), List.empty), 1)
            fiberRefLocals0 = fiberRefLocals0.updated(ref, newValue)
          }
        } else {
          type V = ref.Value with AnyRef
          val parentStack = value1.stack
          val parentDepth = value1.depth

          val ancestor = findAncestor(ref, parentStack, parentDepth, childStack, childDepth)
          val patch    = ref.diff(ancestor, childValue)

          val oldValue = parentStack.head.value.asInstanceOf[V]
          val newValue = ref.join(oldValue, ref.patch(patch)(oldValue)).asInstanceOf[V]

          if (oldValue ne newValue) {
            val newEntry = parentStack match {
              case StackEntry(parentFiberId, _, parentVersion) :: tail =>
                if (parentFiberId eq fiberId)
                  Value(::(StackEntry(parentFiberId, newValue, parentVersion + 1), tail), parentDepth)
                else
                  Value(::(StackEntry(fiberId, newValue, 0), parentStack), parentDepth + 1)
            }
            fiberRefLocals0 = fiberRefLocals0.updated(ref, newEntry)
          }
        }
      }
    }

    if (self.fiberRefLocals eq fiberRefLocals0) self
    else FiberRefs(fiberRefLocals0)
  }

  @tailrec
  private def findAncestor(
    ref: FiberRef[?],
    parentStack: List[StackEntry[?]],
    parentDepth: Int,
    childStack: List[StackEntry[?]],
    childDepth: Int
  ): Any =
    (parentStack, childStack) match {
      case (
            StackEntry(parentFiberId, parentValue, parentVersion) :: parentAncestors,
            StackEntry(childFiberId, childValue, childVersion) :: childAncestors
          ) =>
        if (parentFiberId eq childFiberId)
          if (childVersion > parentVersion) parentValue else childValue
        else if (childDepth > parentDepth)
          findAncestor(ref, parentStack, parentDepth, childAncestors, childDepth - 1)
        else if (childDepth < parentDepth)
          findAncestor(ref, parentAncestors, parentDepth - 1, childStack, childDepth)
        else
          findAncestor(ref, parentAncestors, parentDepth - 1, childAncestors, childDepth - 1)
      case _ =>
        ref.initial
    }

  def setAll(implicit trace: Trace): UIO[Unit] =
    ZIO.foreachDiscard(fiberRefs) { fiberRef =>
      fiberRef.asInstanceOf[FiberRef[Any]].set(getOrDefault(fiberRef))
    }

  override final def toString(): String = fiberRefLocals.mkString("FiberRefLocals(", ",", ")")

  def updatedAs[@specialized(SpecializeInt) A](
    fiberId: FiberId.Runtime
  )(fiberRef: FiberRef[A], value: A): FiberRefs =
    if (fiberRef eq currentRuntimeFlags)
      updateRuntimeFlags(fiberId)(value.asInstanceOf[RuntimeFlags])
    else {
      type A0 = A with AnyRef
      updateAnyRef(fiberId)(
        fiberRef.asInstanceOf[FiberRef[A0]],
        value.asInstanceOf[A0]
      )
    }

  private def updateAnyRef[A <: AnyRef](fiberId: FiberId.Runtime)(fiberRef: FiberRef[A], value: A): FiberRefs = {
    val oldEntry = fiberRefLocals.getOrElse(fiberRef, null)
    val newEntry =
      if (oldEntry eq null)
        Value(::(StackEntry(fiberId, value, 0), List.empty), 1)
      else {
        val oldStack = oldEntry.stack.asInstanceOf[::[StackEntry[A]]]
        val oldDepth = oldEntry.depth
        if (oldStack.head.value eq value)
          oldEntry
        else if (oldStack.head.id eq fiberId) {
          Value(
            ::(StackEntry(fiberId, value, oldStack.head.version + 1), oldStack.tail),
            oldEntry.depth
          )
        } else
          Value(::(StackEntry(fiberId, value, 0), oldStack), oldDepth + 1)
      }

    if (oldEntry eq newEntry) self
    else FiberRefs(fiberRefLocals.updated(fiberRef, newEntry))
  }

  /**
   * Unfortunate code duplication to avoid boxing of `RuntimeFlags` (Int) which
   * are updated for each forked fiber.
   *
   * Specialization also won't help because:
   *   1. For objects, we want to use referential equality (`eq`) to speed up
   *      comparisons as `==` can be very expensive for large lists / Maps / etc
   *      that might be stored in the FiberRef
   *   1. It doesn't work for Scala 3
   */
  private[zio] def updateRuntimeFlags(fiberId: FiberId.Runtime)(value: RuntimeFlags): FiberRefs = {
    val oldEntry = fiberRefLocals.getOrElse(currentRuntimeFlags, null)
    val newEntry =
      if (oldEntry eq null)
        Value(::(StackEntry(fiberId, value, 0), List.empty), 1)
      else {
        val oldStack = oldEntry.stack.asInstanceOf[::[StackEntry[Int]]]
        val oldDepth = oldEntry.depth
        if (oldStack.head.value == value)
          oldEntry
        else if (oldStack.head.id eq fiberId) {
          Value(
            ::(StackEntry(fiberId, value, oldStack.head.version + 1), oldStack.tail),
            oldEntry.depth
          )
        } else
          Value(::(StackEntry(fiberId, value, 0), oldStack), oldDepth + 1)
      }

    if (oldEntry eq newEntry) self
    else FiberRefs(fiberRefLocals.updated(currentRuntimeFlags, newEntry))
  }

}

object FiberRefs {
  private[zio] case class StackEntry[@specialized(SpecializeInt) A](
    id: FiberId.Runtime,
    value: A,
    version: Int
  )
  private[zio] case class Value(stack: ::[StackEntry[?]], depth: Int)

  /**
   * The empty collection of `FiberRef` values.
   */
  val empty: FiberRefs =
    FiberRefs(Map.empty)

  private[zio] def apply(fiberRefLocals: Map[FiberRef[_], Value]): FiberRefs =
    new FiberRefs(fiberRefLocals)

  /**
   * A `Patch` captures the changes in `FiberRef` values made by a single fiber
   * as a value. This allows fibers to apply the changes made by a workflow
   * without inheriting all the `FiberRef` values of the fiber that executed the
   * workflow.
   */
  sealed trait Patch extends Function2[FiberId.Runtime, FiberRefs, FiberRefs] { self =>
    import Patch._

    /**
     * Applies the changes described by this patch to the specified collection
     * of `FiberRef` values.
     */
    def apply(fiberId: FiberId.Runtime, fiberRefs: FiberRefs): FiberRefs = {

      @tailrec
      def loop(fiberRefs: FiberRefs, patches: List[Patch]): FiberRefs =
        patches match {
          case Add(fiberRef, value) :: patches =>
            loop(fiberRefs.updatedAs(fiberId)(fiberRef, value), patches)
          case AndThen(first, second) :: patches =>
            loop(fiberRefs, first :: second :: patches)
          case Empty :: patches =>
            loop(fiberRefs, patches)
          case Remove(fiberRef) :: patches =>
            loop(fiberRefs.delete(fiberRef), patches)
          case Update(fiberRef, patch) :: patches =>
            loop(
              fiberRefs.updatedAs(fiberId)(fiberRef, fiberRef.patch(patch)(fiberRefs.getOrDefault(fiberRef))),
              patches
            )
          case Nil =>
            fiberRefs
        }

      loop(fiberRefs, List(self))
    }

    /**
     * Combines this patch and the specified patch to create a new patch that
     * describes applying the changes from this patch and the specified patch
     * sequentially.
     */
    def combine(that: Patch): Patch =
      AndThen(self, that)
  }

  object Patch {

    /**
     * The empty patch that describes no changes to `FiberRef` values.
     */
    val empty: Patch =
      Patch.Empty

    /**
     * Constructs a patch that describes the changes between the specified
     * collections of `FiberRef`
     */
    def diff(oldValue: FiberRefs, newValue: FiberRefs): Patch = {
      val (removed, patch) = newValue.fiberRefLocals.foldLeft[(FiberRefs, Patch)](oldValue -> empty) {
        case ((fiberRefs, patch), (fiberRef, Value(StackEntry(_, newValue, _) :: _, _))) =>
          type V = fiberRef.Value
          fiberRefs.getOrNull(fiberRef) match {
            case null =>
              fiberRefs -> patch.combine(Add(fiberRef.asInstanceOf[FiberRef[V]], newValue.asInstanceOf[V]))
            case oldValue =>
              val patch0 =
                if (oldValue == newValue) patch
                else {
                  patch.combine(
                    Update(
                      fiberRef.asInstanceOf[FiberRef.WithPatch[V, fiberRef.Patch]],
                      fiberRef.diff(oldValue.asInstanceOf[V], newValue.asInstanceOf[V])
                    )
                  )
                }
              fiberRefs.delete(fiberRef) -> patch0
          }
      }
      removed.fiberRefLocals.foldLeft(patch) { case (patch, (fiberRef, _)) => patch.combine(Remove(fiberRef)) }
    }

    private final case class Add[Value0](fiberRef: FiberRef[Value0], value: Value0) extends Patch
    private final case class AndThen(first: Patch, second: Patch)                   extends Patch
    private case object Empty                                                       extends Patch
    private final case class Remove[Value0](fiberRef: FiberRef[Value0])             extends Patch
    private final case class Update[Value0, Patch0](fiberRef: FiberRef.WithPatch[Value0, Patch0], patch: Patch0)
        extends Patch
  }
}
