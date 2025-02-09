/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.lang.ref.WeakReference

/**
 * A `FiberScope` represents the scope of a fiber lifetime.
 */
private[zio] sealed trait FiberScope {
  def fiberId: FiberId

  /**
   * Adds the specified child fiber to the scope, returning `true` if the scope
   * is still open, and `false` if it has been closed already.
   */
  private[zio] def add(currentFiber: Fiber.Runtime[_, _], runtimeFlags: RuntimeFlags, child: Fiber.Runtime[_, _])(
    implicit
    trace: Trace,
    unsafe: Unsafe
  ): Unit

  private[zio] def addAll(
    currentFiber: Fiber.Runtime[_, _],
    runtimeFlags: RuntimeFlags,
    children: Iterable[Fiber.Runtime[_, _]]
  )(implicit
    trace: Trace,
    unsafe: Unsafe
  ): Unit
}

private[zio] object FiberScope {

  /**
   * The global scope. Anything forked onto the global scope is not supervised,
   * and will only terminate on its own accord (never from interruption of a
   * parent fiber, because there is no parent fiber).
   */
  object global extends FiberScope {
    def fiberId: FiberId = FiberId.None

    private[zio] def add(currentFiber: Fiber.Runtime[_, _], runtimeFlags: RuntimeFlags, child: Fiber.Runtime[_, _])(
      implicit
      trace: Trace,
      unsafe: Unsafe
    ): Unit =
      if (RuntimeFlags.fiberRoots(runtimeFlags)) {
        Fiber._roots.add(child)
      }

    private[zio] def addAll(
      currentFiber: Fiber.Runtime[_, _],
      runtimeFlags: RuntimeFlags,
      children: Iterable[Fiber.Runtime[_, _]]
    )(implicit
      trace: Trace,
      unsafe: Unsafe
    ): Unit =
      if (RuntimeFlags.fiberRoots(runtimeFlags)) {
        children.foreach {
          Fiber._roots.add(_)
        }
      }
  }

  private final class Local(val fiberId: FiberId, parentRef: WeakReference[Fiber.Runtime[_, _]]) extends FiberScope {

    private[zio] def add(currentFiber: Fiber.Runtime[_, _], runtimeFlags: RuntimeFlags, child: Fiber.Runtime[_, _])(
      implicit
      trace: Trace,
      unsafe: Unsafe
    ): Unit = {
      val parent = parentRef.get()

      if (parent ne null) {
        // Parent is not GC'd. Let's check to see if the parent is the current
        // fiber:
        if (currentFiber eq parent) {
          // The parent is the current fiber so it is safe to directly add the
          // child to the parent:
          parent.addChild(child)
        } else {
          // The parent is not the current fiber. So we need to send a message
          // to the parent so it will add the child to itself:
          parent.tellAddChild(child)
        }
      } else {
        // Parent was GC'd. We immediately interrupt the child fiber using the id
        // of the current fiber (which is adding the child to the parent):
        child.tellInterrupt(Cause.interrupt(currentFiber.id))
      }
    }

    private[zio] def addAll(
      currentFiber: Fiber.Runtime[_, _],
      runtimeFlags: RuntimeFlags,
      children: Iterable[Fiber.Runtime[_, _]]
    )(implicit
      trace: Trace,
      unsafe: Unsafe
    ): Unit = if (children.nonEmpty) {
      val parent = parentRef.get()

      if (parent ne null) {
        // Parent is not GC'd. Let's check to see if the parent is the current
        // fiber:
        if (currentFiber eq parent) {
          // The parent is the current fiber so it is safe to directly add the
          // child to the parent:
          parent.addChildren(children)
        } else {
          // The parent is not the current fiber. So we need to send a message
          // to the parent so it will add the child to itself:
          parent.tellAddChildren(children)
        }
      } else {
        // Parent was GC'd. We immediately interrupt the child fiber using the id
        // of the current fiber (which is adding the child to the parent):
        children.foreach(
          _.tellInterrupt(Cause.interrupt(currentFiber.id))
        )
      }
    }
  }

  private[zio] def make(fiber: FiberRuntime[_, _]): FiberScope =
    new Local(fiber.id, new WeakReference(fiber))
}
