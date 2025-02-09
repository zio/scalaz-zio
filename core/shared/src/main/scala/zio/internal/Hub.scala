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

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A `Hub[A]` is a concurrent data structure that allows multiple publishers to
 * publish `A` values and multiple subscribers to poll `A` values with the
 * guarantee that all subscribers will receive all values published to the hub
 * while they are subscribed.
 */
private[zio] abstract class Hub[A] extends Serializable {

  /**
   * The maximum capacity of the hub.
   */
  def capacity: Int

  /**
   * Checks whether the hub is currently empty.
   */
  def isEmpty(): Boolean

  /**
   * Checks whether the hub is currently full.
   */
  def isFull(): Boolean

  /**
   * Publishes the specified value to the hub and returns whether the value was
   * successfully published to the hub.
   */
  def publish(a: A): Boolean

  /**
   * Publishes the specified values to the hub, returning the values that could
   * not be successfully published to the hub.
   */
  def publishAll[A1 <: A](as: Iterable[A1]): Chunk[A1]

  /**
   * The current number of values in the hub.
   */
  def size(): Int

  /**
   * Drops a value from the hub.
   */
  def slide(): Unit

  /**
   * Subscribes to receive values from the hub.
   */
  def subscribe(): Hub.Subscription[A]
}

private[zio] object Hub {

  /**
   * Constructs a new bounded hub with the requested capacity.
   */
  def bounded[A](requestedCapacity: Int): Hub[A] = {
    // NOTE: Do not use `assert` as the compiler removes it in releases
    require(requestedCapacity > 0)
    if (requestedCapacity == 1) new BoundedHubSingle
    else if (nextPow2(requestedCapacity) == requestedCapacity) new BoundedHubPow2(requestedCapacity)
    else new BoundedHubArb(requestedCapacity)
  }

  /**
   * Constructs a new unbounded hub.
   */
  def unbounded[A]: Hub[A] =
    new UnboundedHub

  /**
   * A `Subscription[A]` is a subscription to receive `A` values from the hub.
   * The guarantee is that a subscriber will receive all values published to hub
   * while it is subscribed.
   */
  private[zio] abstract class Subscription[A] extends Serializable {

    /**
     * Checks whether there are values available to take from the hub.
     */
    def isEmpty(): Boolean

    /**
     * Takes a value from the hub if there is one or else returns the specified
     * default value.
     */
    def poll(default: A): A

    /**
     * Takes up to the specified number of values from the hub.
     */
    def pollUpTo(n: Int): Chunk[A]

    /**
     * The current number of values available to take from the hub.
     */
    def size(): Int

    /**
     * Unsubscribes this subscriber from the hub.
     */
    def unsubscribe(): Unit
  }

  private def nextPow2(n: Int): Int = {
    val nextPow = (math.log(n.toDouble) / math.log(2.0)).ceil.toInt
    math.pow(2, nextPow.toDouble).toInt.max(2)
  }
}
