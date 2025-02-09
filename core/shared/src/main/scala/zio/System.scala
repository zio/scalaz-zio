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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.lang.{System => JSystem}
import scala.annotation.nowarn
import scala.collection.JavaConverters._

trait System extends Serializable { self =>
  def env(variable: => String)(implicit trace: Trace): IO[SecurityException, Option[String]]

  def envOrElse(variable: => String, alt: => String)(implicit trace: Trace): IO[SecurityException, String]

  def envOrOption(variable: => String, alt: => Option[String])(implicit
    trace: Trace
  ): IO[SecurityException, Option[String]]

  def envs(implicit trace: Trace): IO[SecurityException, Map[String, String]]

  def lineSeparator(implicit trace: Trace): UIO[String]

  def properties(implicit trace: Trace): IO[Throwable, Map[String, String]]

  def property(prop: => String)(implicit trace: Trace): IO[Throwable, Option[String]]

  def propertyOrElse(prop: => String, alt: => String)(implicit trace: Trace): IO[Throwable, String]

  def propertyOrOption(prop: => String, alt: => Option[String])(implicit
    trace: Trace
  ): IO[Throwable, Option[String]]

  trait UnsafeAPI extends Serializable {
    def env(variable: String)(implicit unsafe: Unsafe): Option[String]
    def envOrElse(variable: String, alt: => String)(implicit unsafe: Unsafe): String
    def envOrOption(variable: String, alt: => Option[String])(implicit unsafe: Unsafe): Option[String]
    def envs()(implicit unsafe: Unsafe): Map[String, String]
    def lineSeparator()(implicit unsafe: Unsafe): String
    def properties()(implicit unsafe: Unsafe): Map[String, String]
    def property(prop: String)(implicit unsafe: Unsafe): Option[String]
    def propertyOrElse(prop: String, alt: => String)(implicit unsafe: Unsafe): String
    def propertyOrOption(prop: String, alt: => Option[String])(implicit unsafe: Unsafe): Option[String]
  }

  def unsafe: UnsafeAPI =
    new UnsafeAPI {
      def env(variable: String)(implicit unsafe: Unsafe): Option[String] =
        Runtime.default.unsafe.run(self.env(variable)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def envOrElse(variable: String, alt: => String)(implicit unsafe: Unsafe): String =
        Runtime.default.unsafe
          .run(self.envOrElse(variable, alt)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def envOrOption(variable: String, alt: => Option[String])(implicit
        unsafe: Unsafe
      ): Option[String] =
        Runtime.default.unsafe
          .run(self.envOrOption(variable, alt)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def envs()(implicit unsafe: Unsafe): Map[String, String] =
        Runtime.default.unsafe.run(self.envs(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def lineSeparator()(implicit unsafe: Unsafe): String =
        Runtime.default.unsafe.run(self.lineSeparator(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def properties()(implicit unsafe: Unsafe): Map[String, String] =
        Runtime.default.unsafe.run(self.properties(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def property(prop: String)(implicit unsafe: Unsafe): Option[String] =
        Runtime.default.unsafe.run(self.property(prop)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def propertyOrElse(prop: String, alt: => String)(implicit unsafe: Unsafe): String =
        Runtime.default.unsafe
          .run(self.propertyOrElse(prop, alt)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def propertyOrOption(prop: String, alt: => Option[String])(implicit
        unsafe: Unsafe
      ): Option[String] =
        Runtime.default.unsafe
          .run(self.propertyOrOption(prop, alt)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()
    }
}

object System extends SystemPlatformSpecific {

  val tag: Tag[System] = Tag[System]

  object SystemLive extends System {
    def env(variable: => String)(implicit trace: Trace): IO[SecurityException, Option[String]] =
      ZIO.attempt(unsafe.env(variable)(Unsafe.unsafe)).refineToOrDie[SecurityException]

    def envOrElse(variable: => String, alt: => String)(implicit trace: Trace): IO[SecurityException, String] =
      ZIO.attempt(unsafe.envOrElse(variable, alt)(Unsafe.unsafe)).refineToOrDie[SecurityException]

    def envOrOption(variable: => String, alt: => Option[String])(implicit
      trace: Trace
    ): IO[SecurityException, Option[String]] =
      ZIO.attempt(unsafe.envOrOption(variable, alt)(Unsafe.unsafe)).refineToOrDie[SecurityException]

    def envs(implicit trace: Trace): IO[SecurityException, Map[String, String]] =
      ZIO.attempt(unsafe.envs()(Unsafe.unsafe)).refineToOrDie[SecurityException]

    def lineSeparator(implicit trace: Trace): UIO[String] =
      ZIO.succeed(unsafe.lineSeparator()(Unsafe.unsafe))

    def properties(implicit trace: Trace): IO[Throwable, Map[String, String]] =
      ZIO.attempt(unsafe.properties()(Unsafe.unsafe))

    def property(prop: => String)(implicit trace: Trace): IO[Throwable, Option[String]] =
      ZIO.attempt(unsafe.property(prop)(Unsafe.unsafe))

    def propertyOrElse(prop: => String, alt: => String)(implicit trace: Trace): IO[Throwable, String] =
      ZIO.attempt(unsafe.propertyOrElse(prop, alt)(Unsafe.unsafe))

    def propertyOrOption(prop: => String, alt: => Option[String])(implicit
      trace: Trace
    ): IO[Throwable, Option[String]] =
      ZIO.attempt(unsafe.propertyOrOption(prop, alt)(Unsafe.unsafe))

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def env(variable: String)(implicit unsafe: Unsafe): Option[String] =
          environmentProvider.env(variable)

        override def envOrElse(variable: String, alt: => String)(implicit unsafe: Unsafe): String =
          envOrElseWith(variable, alt)(env)

        override def envOrOption(variable: String, alt: => Option[String])(implicit unsafe: Unsafe): Option[String] =
          envOrOptionWith(variable, alt)(env)

        override def envs()(implicit unsafe: Unsafe): Map[String, String] =
          environmentProvider.envs

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

  private[zio] trait EnvironmentProvider {
    def env(variable: String): Option[String]
    def envs: Map[String, String]
  }

  private[zio] def envOrElseWith(variable: String, alt: => String)(env: String => Option[String]): String =
    env(variable).getOrElse(alt)

  private[zio] def envOrOptionWith(variable: String, alt: => Option[String])(
    env: String => Option[String]
  ): Option[String] =
    env(variable).orElse(alt)

  private[zio] def propertyOrElseWith(prop: String, alt: => String)(property: String => Option[String]): String =
    property(prop).getOrElse(alt)

  private[zio] def propertyOrOptionWith(prop: String, alt: => Option[String])(
    property: String => Option[String]
  ): Option[String] =
    property(prop).orElse(alt)

  /**
   * Retrieves the value of an environment variable.
   */
  def env(variable: => String)(implicit trace: Trace): IO[SecurityException, Option[String]] =
    ZIO.systemWith(_.env(variable))

  /**
   * Retrieves the value of an environment variable or else returns the
   * specified fallback value.
   */
  def envOrElse(variable: => String, alt: => String)(implicit
    trace: Trace
  ): IO[SecurityException, String] =
    ZIO.systemWith(_.envOrElse(variable, alt))

  /**
   * Retrieves the value of an environment variable or else returns the
   * specified optional fallback value.
   */
  def envOrOption(variable: => String, alt: => Option[String])(implicit
    trace: Trace
  ): IO[SecurityException, Option[String]] =
    ZIO.systemWith(_.envOrOption(variable, alt))

  /**
   * Retrieves the values of all environment variables.
   */
  def envs(implicit trace: Trace): IO[SecurityException, Map[String, String]] =
    ZIO.systemWith(_.envs)

  /**
   * Retrieves the values of all system properties.
   */
  def properties(implicit trace: Trace): Task[Map[String, String]] =
    ZIO.systemWith(_.properties)

  /**
   * Retrieves the value of a system property.
   */
  def property(prop: => String)(implicit trace: Trace): Task[Option[String]] =
    ZIO.systemWith(_.property(prop))

  /**
   * Retrieves the value of a system property or else return the specified
   * fallback value.
   */
  def propertyOrElse(prop: => String, alt: => String)(implicit trace: Trace): Task[String] =
    ZIO.systemWith(_.propertyOrElse(prop, alt))

  /**
   * Retrieves the value of a system property or else return the specified
   * optional fallback value.
   */
  def propertyOrOption(prop: => String, alt: => Option[String])(implicit
    trace: Trace
  ): Task[Option[String]] =
    ZIO.systemWith(_.propertyOrOption(prop, alt))

  /**
   * Retrieves the value of the system-specific line separator.
   */
  def lineSeparator(implicit trace: Trace): UIO[String] =
    ZIO.systemWith(_.lineSeparator)

  private val osName =
    Option(scala.util.Try(java.lang.System.getProperty("os.name")).getOrElse("")).map(_.toLowerCase()).getOrElse("")

  lazy val os: OS =
    if (osName.contains("win")) OS.Windows
    else if (osName.contains("mac")) OS.Mac
    else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) OS.Unix
    else if (osName.contains("sunos")) OS.Solaris
    else OS.Unknown

  sealed trait OS { self =>
    def isWindows: Boolean = self == OS.Windows
    def isMac: Boolean     = self == OS.Mac
    def isUnix: Boolean    = self == OS.Unix
    def isSolaris: Boolean = self == OS.Solaris
    def isUnknown: Boolean = self == OS.Unknown
  }
  object OS {
    case object Windows extends OS
    case object Mac     extends OS
    case object Unix    extends OS
    case object Solaris extends OS
    case object Unknown extends OS
  }
}
