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

import java.lang.{System => JSystem}
import scala.annotation.nowarn
import scala.collection.JavaConverters._

private[zio] trait SystemPlatformSpecific { self: System.type =>

  private[zio] val environmentProvider = new EnvironmentProvider {
    override def env(variable: String): Option[String] =
      Option(JSystem.getenv(variable))

    @nowarn("msg=JavaConverters")
    override def envs: Map[String, String] =
      JSystem.getenv().asScala.toMap
  }

}
