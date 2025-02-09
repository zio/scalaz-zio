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

import java.util.concurrent.{ConcurrentHashMap => JConcurrentHashMap}

private[test] final case class ConcurrentHashMap[K, V] private (private val map: JConcurrentHashMap[K, V]) {
  def foldLeft[B](z: B)(op: (B, (K, V)) => B): B = {
    var result = z
    val it     = map.entrySet.iterator
    while (it.hasNext) {
      val e = it.next()
      result = op(result, (e.getKey, e.getValue))
    }
    result
  }
  def getOrElseUpdate(key: K, op: => V): V =
    map.computeIfAbsent(key, _ => op)
}

private[test] object ConcurrentHashMap {
  def empty[K, V]: ConcurrentHashMap[K, V] =
    new ConcurrentHashMap[K, V](new JConcurrentHashMap[K, V]())
}
