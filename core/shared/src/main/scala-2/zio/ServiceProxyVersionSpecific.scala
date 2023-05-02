/*
 * Copyright 2017-2023 John A. De Goes and the ZIO Contributors
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

import zio.internal.macros.ServiceProxyMacros

trait ServiceProxyVersionSpecific {

  /**
   * Generates a proxy instance of the specified service.
   *
   * @tparam A
   *   The type of the service.
   * @param service
   *   The [[zio.ScopedRef]] containing the service for which a proxy is to be
   *   generated.
   * @return
   *   A proxy instance of the service that forwards ZIO method calls to the
   *   underlying service and allows the service to change its behavior at
   *   runtime.
   */
  def generate[A](service: ScopedRef[A]): A = macro ServiceProxyMacros.makeImpl[A]
}
