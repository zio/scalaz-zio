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

package zio.stm

import zio.{Promise, Scope, UIO, Unsafe, Trace, ZIO}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import java.util.concurrent.ConcurrentLinkedQueue

final class TSemaphore private (
  val permits: TRef[Long],
  fairness: Boolean,
  private val waiters: ConcurrentLinkedQueue[Promise[Nothing, Unit]]
) extends Serializable {

  def acquire: USTM[Unit] = acquireN(1L)

  def acquireN(n: Long): USTM[Unit] = ZSTM.Effect { (journal, _, _) =>
    assertNonNegative(n)
    val current = permits.unsafeGet(journal)
    if (current >= n) {
      permits.unsafeSet(journal, current - n)
    } else {
      throw ZSTM.RetryException
    }
  }

  def release: USTM[Unit] = releaseN(1L)

  def releaseN(n: Long): USTM[Unit] = ZSTM.Effect { (journal, _, _) =>
    assertNonNegative(n)
    val current = permits.unsafeGet(journal)
    permits.unsafeSet(journal, current + n)
    if (!fairness) {
      // Unfair: Wake waiters immediately without FIFO
      var loop = true
      while (loop && permits.unsafeGet(journal) > 0 && !waiters.isEmpty) {
        val waiter = waiters.poll()
        if (waiter != null) waiter.succeedUnit.unsafe.run
        loop = permits.unsafeGet(journal) > 0
      }
    }
  }

  private def assertNonNegative(n: Long): Unit =
    require(n >= 0, s"Unexpected negative `$n` permits requested.")
}

object TSemaphore {
  def make(permits: => Long, fairness: Boolean = true)(implicit trace: Trace): USTM[TSemaphore] =
    ZSTM.succeed(unsafe.make(permits, fairness)(Unsafe.unsafe))

  object unsafe {
    def make(permits: Long, fairness: Boolean)(implicit unsafe: Unsafe): TSemaphore =
      new TSemaphore(TRef.unsafeMake(permits), fairness, new ConcurrentLinkedQueue[Promise[Nothing, Unit]]())
  }
}