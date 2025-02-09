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
package zio

/**
 * Maintains a set of runtime flags. Runtime flags affect the operation of the
 * ZIO runtime system. They are exposed to application-level code because they
 * affect the behavior and performance of application code.
 *
 * For more information on individual flags, see [[zio.RuntimeFlag]].
 */
object RuntimeFlags {

  def cooperativeYielding(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.CooperativeYielding.mask)

  def currentFiber(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.CurrentFiber.mask)

  def diff(oldValue: RuntimeFlags, newValue: RuntimeFlags): RuntimeFlags.Patch =
    RuntimeFlags.Patch(oldValue ^ newValue, newValue)

  def disable(flags: RuntimeFlags)(flag: RuntimeFlag): RuntimeFlags =
    flags & flag.notMask

  def disableAll(self: RuntimeFlags)(that: RuntimeFlags): RuntimeFlags =
    self & ~that

  def eagerShiftBack(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.EagerShiftBack.mask)

  def enable(flags: RuntimeFlags)(flag: RuntimeFlag): RuntimeFlags =
    flags | flag.mask

  def enableAll(self: RuntimeFlags)(that: RuntimeFlags): RuntimeFlags =
    self | that

  def fiberRoots(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.FiberRoots.mask)

  /**
   * This method returns true only if the flag `Interruption` is ENABLED, and
   * also the flag `WindDown` is DISABLED.
   *
   * A fiber is said to be interruptible if the feature of Interruption is
   * turned on, and the fiber is not in its wind-down phase, in which it takes
   * care of cleanup activities related to fiber shutdown.
   */
  def interruptible(flags: RuntimeFlags): Boolean =
    interruption(flags) && !windDown(flags)

  def interruption(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.Interruption.mask)

  def isDisabled(flags: RuntimeFlags)(flag: RuntimeFlag): Boolean =
    !isEnabled(flags, flag.mask)

  def isEnabled(flags: RuntimeFlags)(flag: RuntimeFlag): Boolean =
    isEnabled(flags, flag.mask)

  /**
   * Optimized variant which doesn't rely on the megamorphic call to `.mask`.
   * Prefer using this method when the RuntimeFlag being tested is statically
   * known
   */
  private def isEnabled(flags: RuntimeFlags, mask: Int): Boolean =
    (flags & mask) != 0

  def opLog(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.OpLog.mask)

  def opSupervision(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.OpSupervision.mask)

  def patch(patch: RuntimeFlags.Patch)(flags: RuntimeFlags): RuntimeFlags =
    Patch.patch(patch)(flags)

  def render(flags: RuntimeFlags): String =
    toSet(flags).mkString("RuntimeFlags(", ", ", ")")

  def runtimeMetrics(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.RuntimeMetrics.mask)

  def toSet(flags: RuntimeFlags): Set[RuntimeFlag] =
    RuntimeFlag.all.filter(isEnabled(flags))

  def windDown(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.WindDown.mask)

  def workStealing(flags: RuntimeFlags): Boolean =
    isEnabled(flags, RuntimeFlag.WorkStealing.mask)

  type Patch = Long

  object Patch {

    def andThen(first: Patch, second: Patch): Patch =
      first | second

    def apply(active: Int, enabled: Int): Patch =
      (active.toLong << 0) + ((enabled & active).toLong << 32)

    def both(left: Patch, right: Patch): Patch =
      Patch(active(left) | active(right), enabled(left) & enabled(right))

    def disabledSet(patch: Patch): Set[RuntimeFlag] =
      RuntimeFlags.toSet(active(patch) & ~enabled(patch))

    def either(left: Patch, right: Patch): Patch =
      Patch(active(left) | active(right), enabled(left) | enabled(right))

    val empty: Patch = Patch(0, 0)

    def enabledSet(patch: Patch): Set[RuntimeFlag] =
      RuntimeFlags.toSet(active(patch) & enabled(patch))

    def exclude(patch: Patch)(flag: RuntimeFlag): Patch =
      exclude(patch, flag.notMask)

    /**
     * Optimized variant of [[exclude]] that doesn't rely on the megamorphic
     * call to `.notMask`
     */
    private[zio] def exclude(patch: Patch, notMask: Int): Patch =
      Patch(active(patch) & notMask, enabled(patch))

    def includes(patch: Patch)(flag: RuntimeFlag): Boolean =
      ((active(patch) & flag.mask) != 0)

    def inverse(patch: Patch): Patch =
      Patch(active(patch), ~enabled(patch))

    def isActive(patch: Patch)(flag: RuntimeFlag): Boolean =
      isActive(patch, flag.mask)

    /**
     * Optimized variant of [[isEnabled]] that doesn't rely on the megamorphic
     * call to `.mask`
     */
    private def isActive(patch: Patch, mask: Int): Boolean =
      (active(patch) & mask) != 0

    def isDisabled(patch: Patch)(flag: RuntimeFlag): Boolean =
      isDisabled(patch, flag.mask)

    /**
     * Optimized variant of [[isDisabled]] that doesn't rely on the megamorphic
     * call to `.mask`
     */
    private[zio] def isDisabled(patch: Patch, mask: Int): Boolean =
      isActive(patch, mask) && ((enabled(patch) & mask) == 0)

    def isEmpty(patch: Patch): Boolean =
      active(patch) == 0L

    def isEnabled(patch: Patch)(flag: RuntimeFlag): Boolean =
      isEnabled(patch, flag.mask)

    /**
     * Optimized variant of [[isEnabled]] that doesn't rely on the megamorphic
     * call to `.mask`
     */
    private[zio] def isEnabled(patch: Patch, mask: Int): Boolean =
      isActive(patch, mask) && ((enabled(patch) & mask) != 0)

    def patch(patch: Patch)(flags: RuntimeFlags): RuntimeFlags =
      (flags & (~active(patch) | enabled(patch))) | (active(patch) & enabled(patch))

    def render(patch: Patch): String = {
      val enabledS =
        enabledSet(patch).mkString("(", ", ", ")")

      val disabledS =
        disabledSet(patch).mkString("(", ", ", ")")

      s"RuntimeFlags.Patch(enabled = ${enabledS}, disabled = ${disabledS})"
    }

    private def active(patch: Patch): Int  = ((patch >> 0) & 0xffffffff).toInt
    private def enabled(patch: Patch): Int = ((patch >> 32) & 0xffffffff).toInt
  }

  def apply(flags: RuntimeFlag*): RuntimeFlags =
    flags.foldLeft(0)(_ | _.mask)

  /**
   * The default set of runtime flags, recommended for most applications.
   */
  val default: RuntimeFlags =
    RuntimeFlags(
      RuntimeFlag.FiberRoots,
      RuntimeFlag.Interruption,
      RuntimeFlag.CooperativeYielding
    )

  /**
   * Creates a patch that disables the specified runtime flag.
   */
  def disable(flag: RuntimeFlag): RuntimeFlags.Patch =
    RuntimeFlags.Patch(flag.mask, 0)

  /**
   * Creates a patch that enables the specified runtime flag.
   */
  def enable(flag: RuntimeFlag): RuntimeFlags.Patch =
    RuntimeFlags.Patch(flag.mask, flag.mask)

  private[zio] val disableInterruption: RuntimeFlags.Patch = disable(RuntimeFlag.Interruption)
  private[zio] val enableInterruption: RuntimeFlags.Patch  = enable(RuntimeFlag.Interruption)

  /**
   * No runtime flags.
   */
  val none: RuntimeFlags = 0
}
