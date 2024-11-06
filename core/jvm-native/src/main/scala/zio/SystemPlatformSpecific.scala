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

  trait SystemLivePlatformSpecific extends System {
    @transient override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def env(variable: String)(implicit unsafe: Unsafe): Option[String] =
          Option(JSystem.getenv(variable))

        override def envOrElse(variable: String, alt: => String)(implicit unsafe: Unsafe): String =
          envOrElseWith(variable, alt)(env)

        override def envOrOption(variable: String, alt: => Option[String])(implicit unsafe: Unsafe): Option[String] =
          envOrOptionWith(variable, alt)(env)

        @nowarn("msg=JavaConverters")
        override def envs()(implicit unsafe: Unsafe): Map[String, String] =
          JSystem.getenv.asScala.toMap

        override def lineSeparator()(implicit unsafe: Unsafe): String =
          JSystem.lineSeparator

        @nowarn("msg=JavaConverters")
        override def properties()(implicit unsafe: Unsafe): Map[String, String] =
          JSystem.getProperties.asScala.toMap

        override def property(prop: String)(implicit unsafe: Unsafe): Option[String] =
          Option(JSystem.getProperty(prop))

        override def propertyOrElse(prop: String, alt: => String)(implicit unsafe: Unsafe): String =
          propertyOrElseWith(prop, alt)(property)

        override def propertyOrOption(prop: String, alt: => Option[String])(implicit
          unsafe: Unsafe
        ): Option[String] =
          propertyOrOptionWith(prop, alt)(property)
      }
  }

}
