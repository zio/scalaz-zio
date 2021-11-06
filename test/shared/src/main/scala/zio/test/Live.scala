package zio.test

import zio.{Has, IO, ZEnv, ZIO, ZLayer, ZManaged, ZTraceElement}
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * The `Live` trait provides access to the "live" environment from within the
 * test environment for effects such as printing test results to the console or
 * timing out tests where it is necessary to access the real environment.
 *
 * The easiest way to access the "live" environment is to use the `live` method
 * with an effect that would otherwise access the test environment.
 *
 * {{{
 * import zio.Clock
 * import zio.test._
 *
 * val realTime = live(Clock.nanoTime)
 * }}}
 *
 * The `withLive` method can be used to apply a transformation to an effect
 * with the live environment while ensuring that the effect itself still runs
 * with the test environment, for example to time out a test. Both of these
 * methods are re-exported in the `environment` package for easy availability.
 */
trait Live {
  def provide[E, A](zio: ZIO[ZEnv, E, A])(implicit trace: ZTraceElement): IO[E, A]
}

object Live {

  /**
   * Constructs a new `Live` service that implements the `Live` interface.
   * This typically should not be necessary as `TestEnvironment` provides
   * access to live versions of all the standard ZIO environment types but
   * could be useful if you are mixing in interfaces to create your own
   * environment type.
   */
  def default: ZLayer[ZEnv, Nothing, Has[Live]] = {
    implicit val trace = Tracer.newTrace
    ZManaged
      .access[ZEnv] { zenv =>
        new Live {
          def provide[E, A](zio: ZIO[ZEnv, E, A])(implicit trace: ZTraceElement): IO[E, A] =
            zio.provide(zenv)
        }
      }
      .toLayer
  }

  /**
   * Provides an effect with the "live" environment.
   */
  def live[E, A](zio: ZIO[ZEnv, E, A])(implicit trace: ZTraceElement): ZIO[Has[Live], E, A] =
    ZIO.accessZIO(_.get.provide(zio))

  /**
   * Provides a transformation function with access to the live environment
   * while ensuring that the effect itself is provided with the test
   * environment.
   */
  def withLive[R <: Has[Live], E, E1, A, B](
    zio: ZIO[R, E, A]
  )(f: IO[E, A] => ZIO[ZEnv, E1, B])(implicit trace: ZTraceElement): ZIO[R, E1, B] =
    ZIO.environment[R].flatMap(r => live(f(zio.provide(r))))
}
