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

import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global

private[zio] trait SystemPlatformSpecific { self: System.type =>

  private[zio] val environmentProvider = new EnvironmentProvider {
    private val envMap: mutable.Map[String, String] = {
      if (js.typeOf(global.process) != "undefined" && js.typeOf(global.process.env) != "undefined") {
        global.process.env.asInstanceOf[js.Dictionary[String]]
      } else {
        mutable.Map.empty
      }
    }

    override def env(variable: String): Option[String] =
      envMap.get(variable)

    override def envs: Map[String, String] =
      envMap.toMap
  }

}
