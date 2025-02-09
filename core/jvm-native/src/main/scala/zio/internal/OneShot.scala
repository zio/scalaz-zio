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

package zio.internal

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.locks.{Condition, ReentrantLock}

/**
 * A variable that can be set a single time. The synchronous, effectful
 * equivalent of `Promise`.
 */
private[zio] final class OneShot[A] private () extends ReentrantLock(false) {
  @volatile private var value                 = null.asInstanceOf[A with AnyRef]
  private final val isSetCondition: Condition = this.newCondition()

  /**
   * Sets the variable to the value. The behavior of this function is undefined
   * if the variable has already been set.
   */
  def set(v: A): Unit = {
    if (v == null) throw new Error("Defect: OneShot variable cannot be set to null value")

    this.lock()

    try {
      if (value ne null) throw new Error("Defect: OneShot variable being set twice")

      value = v.asInstanceOf[A with AnyRef]

      this.isSetCondition.signalAll()
    } finally {
      this.unlock()
    }
  }

  /**
   * Determines if the variable has been set.
   */
  def isSet: Boolean = value ne null

  /**
   * Retrieves the value of the variable, blocking if necessary.
   *
   * @param timeout
   *   The maximum amount of time the thread will be blocked, in milliseconds.
   * @throws Error
   *   if the timeout is reached without the value being set.
   */
  def get(timeout: Long): A =
    if (value ne null) value
    else {
      this.lock()

      try {
        if (value eq null) this.isSetCondition.await(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
      } finally {
        this.unlock()
      }

      if (value eq null) throw new Error("Timed out waiting for variable to be set")

      value
    }

  /**
   * Retrieves the value of the variable, blocking if necessary.
   *
   * This will block until the value is set or the thread is interrupted.
   */
  def get(): A =
    if (value ne null) value
    else {
      this.lock()

      try {
        while (value eq null) this.isSetCondition.await()
      } finally {
        this.unlock()
      }

      value
    }

}

private[zio] object OneShot {

  private final val nanosPerMilli = 1000000L

  /**
   * Makes a new (unset) variable.
   */
  def make[A]: OneShot[A] = new OneShot()
}
