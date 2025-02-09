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

package zio

import scala.annotation.implicitNotFound

/**
 * `IsReloadable[Service]` provides evidence that we know enough about the
 * structure of a service to create a reloadable version of it.
 *
 * The `reloadable` function creates a reloadable instance of the service that
 * forwards every ZIO method call to the underlying service, wrapped in a
 * [[zio.ScopedRef]]. The reloadable servservice allows the service to change
 * its behavior at runtime.
 *
 * @note
 *   In order to successfully generate a reloadable service, the type `Service`
 *   must meet the following requirements:
 *   - `Service` should be either a trait or a class with a primary constructor
 *     without any term parameters.
 *   - `Service` should contain only ZIO methods or vals.
 *   - `Service` should not have any abstract type members.
 *
 * @example
 *   {{{
 *   trait MyService { def foo: UIO[String] }
 *
 *   val service1: MyService = new MyService { def foo = ZIO.succeed("zio1") }
 *   val service2: MyService = new MyService { def foo = ZIO.succeed("zio2") }
 *   for {
 *     ref       <- ScopedRef.make(service1)
 *     reloadable = IsReloadable[MyService].reloadable(ref)
 *     res1      <- reloadable.foo
 *     _         <- ref.set(ZIO.succeed(service2))
 *     res2      <- reloadable.foo
 *   } yield assertTrue(res1 == "zio1" && res2 == "zio2")
 *   }}}
 */
@implicitNotFound(
  "Unable to generate a reloadable service for ${Service}." +
    "\n\nPlease ensure the following:" +
    "\n  1. The type is either a trait or a class with an empty primary constructor." +
    "\n  2. The type includes only ZIO methods or vals." +
    "\n  3. The type does not have any abstract type members."
)
trait IsReloadable[Service] {
  def reloadable(scopedRef: ScopedRef[Service]): Service
}
object IsReloadable extends IsReloadableVersionSpecific {
  def apply[Service](implicit isReloadable: IsReloadable[Service]): IsReloadable[Service] =
    isReloadable
}
