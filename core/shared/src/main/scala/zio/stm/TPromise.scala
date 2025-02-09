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

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.Unsafe

final class TPromise[E, A] private (val ref: TRef[Option[Either[E, A]]]) extends AnyVal {
  def await: STM[E, A] =
    ref.get.collect { case Some(e) =>
      STM.fromEither(e)
    }.flatten

  def done(v: Either[E, A]): USTM[Boolean] =
    ref.get.flatMap {
      case Some(_) => STM.succeedNow(false)
      case None    => ref.set(Some(v)) *> STM.succeedNow(true)
    }

  def fail(e: E): USTM[Boolean] =
    done(Left(e))

  def poll: USTM[Option[Either[E, A]]] =
    ref.get

  def succeed(a: A): USTM[Boolean] =
    done(Right(a))
}

object TPromise {
  def make[E, A]: USTM[TPromise[E, A]] =
    ZSTM.succeed(unsafe.make()(Unsafe.unsafe))

  object unsafe {
    def make[E, A]()(implicit unsafe: Unsafe): TPromise[E, A] =
      new TPromise(TRef.unsafeMake[Option[Either[E, A]]](None))
  }
}
