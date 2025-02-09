/*
 * Copyright 2022-2024 John A. De Goes and the ZIO Contributors
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
 * A [[ScopedRef]] is a reference whose value is associated with resources,
 * which must be released properly. You can both get the current value of any
 * [[ScopedRef]], as well as set it to a new value (which may require new
 * resources). The reference itself takes care of properly releasing resources
 * for the old value whenever a new value is obtained.
 */
trait ScopedRef[A] {

  /**
   * Sets the value of this reference to the specified resourcefully-created
   * value. Any resources associated with the old value will be released.
   *
   * This method will not return until the old resources are released and either
   * the reference is successfully changed to the new value or the attempt to
   * acquire a new value fails.
   */
  def set[R, E](acquire: ZIO[R with Scope, E, A])(implicit trace: Trace): ZIO[R, E, Unit]

  /**
   * The same as [[ScopedRef#set]], but performs the set asynchronously,
   * ignoring failures.
   */
  def setAsync[R, E](acquire: ZIO[R with Scope, E, A])(implicit trace: Trace): ZIO[R, Nothing, Unit] =
    set[R, E](acquire).forkDaemon.unit

  /**
   * Retrieves the current value of the reference.
   */
  def get(implicit trace: Trace): UIO[A]
}
object ScopedRef {

  /**
   * Creates a new [[ScopedRef]] from the specified value. This method should
   * not be used for values whose creation require the acquisition of resources.
   */
  def make[A](a: => A): ZIO[Scope, Nothing, ScopedRef[A]] = fromAcquire[Any, Nothing, A](ZIO.succeed(a))

  /**
   * Creates a new [[ScopedRef]] from an effect that resourcefully produces a
   * value.
   */
  def fromAcquire[R, E, A](acquire: ZIO[R, E, A])(implicit trace: Trace): ZIO[R with Scope, E, ScopedRef[A]] =
    ZIO.uninterruptible {
      for {
        newScope <- Scope.make
        a <-
          acquire.provideSomeEnvironment[R](_.add[Scope](newScope)).onError(cause => newScope.close(Exit.fail(cause)))
        ref      <- Ref.Synchronized.make((newScope, a))
        scopedRef = Synch(ref)
        _        <- ZIO.addFinalizer(scopedRef.close)
      } yield scopedRef
    }

  private case class Synch[A](ref: Ref.Synchronized[(Scope.Closeable, A)]) extends ScopedRef[A] {
    final def close(implicit trace: Trace): UIO[Any] =
      ref.get.flatMap { case (scope, _) =>
        scope.close(Exit.unit)
      }

    final def get(implicit trace: Trace): UIO[A] = ref.get.map(_._2)

    final def set[R, E](acquire: ZIO[R with Scope, E, A])(implicit trace: Trace): ZIO[R, E, Unit] =
      ref.modifyZIO { case (oldScope, a) =>
        ZIO.uninterruptible {
          for {
            _        <- oldScope.close(Exit.unit)
            newScope <- Scope.make
            exit     <- newScope.extend[R](acquire).exit
            result <- exit match {
                        case Exit.Failure(cause) =>
                          newScope.close(Exit.unit).as(Exit.failCause(cause) -> (oldScope -> a))
                        case Exit.Success(a) => ZIO.succeed(ZIO.unit -> (newScope -> a))
                      }
          } yield result
        }
      }.flatten
  }
}
