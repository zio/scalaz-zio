/*
 * Copyright 2023-2024 John A. De Goes and the ZIO Contributors
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

package zio.macros

import zio._

/**
 * A `ServiceReloader` is a "registry" of services, allowing services to be
 * reloaded dynamically. You can create a reloadable version of your service by
 * using the `reloadable` operator on `ZLayer`. Then just call
 * `ServiceLoader.reload` to reload the service.
 */
trait ServiceReloader {

  /**
   * Registers the specified service with a layer that constructs the service.
   * Returns an implementation of the service that is backed by a `ScopedRef`
   * which will handle reloading the service. Fails with a
   * `ServiceInitializationError` exception if an error occurs while acquiring
   * the service.
   */
  def register[A: Tag: IsReloadable](serviceLayer: ZLayer[Any, Any, A]): IO[ServiceReloader.Error, A]

  /**
   * Reloads the specified service, releasing any resources associated with the
   * service and acquiring a new service. Fails with a
   * `ServiceInitializationError` exception if an error occurs reloading the
   * service or a `ServiceNotRegistered` exception if a layer constructing the
   * service was never registered.
   */
  def reload[A: Tag]: IO[ServiceReloader.Error, Unit]
}

object ServiceReloader {

  sealed trait Error                                       extends Throwable
  final case class ServiceInitializationError(error: Any)  extends Error
  final case class ServiceNotRegistered(tag: LightTypeTag) extends Error

  /**
   * The live implementation of the `ServiceReloader` service.
   */
  val live: ZLayer[Any, Nothing, ServiceReloader] =
    ZLayer.scoped {
      for {
        ref   <- Ref.Synchronized.make[Map[LightTypeTag, (ZLayer[Any, Any, Any], ScopedRef[Any])]](Map.empty)
        scope <- ZIO.scope
      } yield new ServiceReloader {
        def register[A: Tag: IsReloadable](serviceLayer: ZLayer[Any, Any, A]): IO[ServiceReloader.Error, A] =
          ref.modifyZIO { map =>
            val tag = Tag[A].tag
            map.get(tag) match {
              case Some((_, scopedRef)) =>
                scope.extend {
                  scopedRef
                    .set(serviceLayer.build.map(_.unsafe.get(tag)(Unsafe.unsafe)))
                    .foldZIO(
                      error => ZIO.fail(ServiceInitializationError(error)),
                      _ => scopedRef.get.map(a => (a.asInstanceOf[A], map))
                    )
                }
              case None =>
                scope.extend {
                  ScopedRef
                    .fromAcquire(serviceLayer.build.map(_.unsafe.get[A](tag)(Unsafe.unsafe)))
                    .foldZIO(
                      error => ZIO.fail(ServiceInitializationError(error)),
                      scopedRef =>
                        ZIO.succeed(
                          (
                            IsReloadable[A].reloadable(scopedRef),
                            map.updated(tag, (serviceLayer, scopedRef.asInstanceOf[ScopedRef[Any]]))
                          )
                        )
                    )
                }
            }
          }
        def reload[A: Tag]: IO[ServiceReloader.Error, Unit] =
          ref.modifyZIO { map =>
            val tag = Tag[A].tag
            map.get(tag) match {
              case Some((serviceLayer, scopedRef)) =>
                scope.extend {
                  scopedRef
                    .set(serviceLayer.build.map(_.unsafe.get[A](tag)(Unsafe.unsafe)))
                    .foldZIO(
                      error => ZIO.fail(ServiceInitializationError(error)),
                      _ => ZIO.succeed(((), map))
                    )
                }
              case None =>
                ZIO.fail(ServiceNotRegistered(tag))
            }
          }
      }
    }

  /**
   * Registers the specified service with a layer that constructs the service.
   * Returns an implementation of the service that is backed by a `ScopedRef`
   * which will handle reloading the service. Fails with a
   * `ServiceInitializationError` exception if an error occurs while acquiring
   * the service.
   */
  def register[A: Tag: IsReloadable](
    serviceLayer: ZLayer[Any, Any, A]
  ): ZIO[ServiceReloader, ServiceReloader.Error, A] =
    ZIO.serviceWithZIO(_.register(serviceLayer))

  /**
   * Reloads the specified service, releasing any resources associated with the
   * service and acquiring a new service. Fails with a
   * `ServiceInitializationError` exception if an error occurs reloading the
   * service or a `ServiceNotRegistered` exception if a layer constructing the
   * service was never registered.
   */
  def reload[A: Tag]: ZIO[ServiceReloader, ServiceReloader.Error, Unit] =
    ZIO.serviceWithZIO(_.reload)
}
