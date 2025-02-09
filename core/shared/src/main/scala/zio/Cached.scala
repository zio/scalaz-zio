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
 * A [[Cached]] is a possibly resourceful value that is loaded into memory, and
 * which can be refreshed either manually or automatically.
 */
trait Cached[+Error, +Resource] {

  /**
   * Retrieves the current value stored in the cache.
   */
  def get(implicit trace: Trace): IO[Error, Resource]

  /**
   * Refreshes the cache. This method will not return until either the refresh
   * is successful, or the refresh operation fails.
   */
  def refresh(implicit trace: Trace): IO[Error, Unit]
}

object Cached {

  /**
   * Creates a new [[Cached]] value that is automatically refreshed according to
   * the specified policy. Note that error retrying is not performed
   * automatically, so if you want to retry on errors, you should first apply
   * retry policies to the acquisition effect before passing it to this
   * constructor.
   */
  def auto[R, Error, Resource](acquire: ZIO[R, Error, Resource], policy: Schedule[Any, Any, Any])(implicit
    trace: Trace
  ): ZIO[R with Scope, Nothing, Cached[Error, Resource]] =
    for {
      manual <- manual(acquire)
      _      <- ZIO.acquireRelease(ZIO.interruptible(manual.refresh.schedule(policy)).forkDaemon)(_.interrupt)
    } yield manual

  /**
   * Creates a new [[Cached]] value that must be manually refreshed by calling
   * the refresh method. Note that error retrying is not performed
   * automatically, so if you want to retry on errors, you should first apply
   * retry policies to the acquisition effect before passing it to this
   * constructor.
   */
  def manual[R, Error, Resource](
    acquire: ZIO[R, Error, Resource]
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Cached[Error, Resource]] =
    for {
      env <- ZIO.environment[R]
      ref <- ScopedRef.fromAcquire(acquire.exit)
    } yield Manual(ref, acquire.provideEnvironment(env))

  private final case class Manual[Error, Resource](
    ref: ScopedRef[Exit[Error, Resource]],
    acquire: ZIO[Scope, Error, Resource]
  ) extends Cached[Error, Resource] {
    def get(implicit trace: Trace): IO[Error, Resource] = ref.get.unexit

    def refresh(implicit trace: Trace): IO[Error, Unit] = ref.set[Any, Error](acquire.map(Exit.succeed(_)))
  }
}
