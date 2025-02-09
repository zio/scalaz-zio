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

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

private[zio] final class NamedThreadFactory(name: String, daemon: Boolean) extends ThreadFactory {

  private val parentGroup = Thread.currentThread.getThreadGroup
  private val threadGroup = new ThreadGroup(parentGroup, name)
  private val threadCount = new AtomicInteger(1)

  override def newThread(r: Runnable): Thread = {
    val newThreadNumber = threadCount.getAndIncrement()

    val thread = new Thread(threadGroup, r)
    thread.setName(s"$name-$newThreadNumber")
    thread.setDaemon(daemon)

    thread
  }

}
