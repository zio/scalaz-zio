/*
 * Copyright 2017-2021 John A. De Goes and the ZIO Contributors
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

import zio.internal.{FiberContext, Platform, StackBool, StackTraceBuilder}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.Future

/**
 * A `Runtime[R]` is capable of executing tasks within an environment `R`.
 */
trait Runtime[+R] {

  /**
   * The environment of the runtime.
   */
  def environment: ZEnvironment[R]

  /**
   * The configuration of the runtime, which provides the essential capabilities
   * necessary to bootstrap execution of tasks.
   */
  def runtimeConfig: RuntimeConfig

  /**
   * Constructs a new `Runtime` with the specified new environment.
   */
  def as[R1](r1: ZEnvironment[R1]): Runtime[R1] =
    map(_ => r1)

  /**
   * Constructs a new `Runtime` by mapping the environment.
   */
  def map[R1](f: ZEnvironment[R] => ZEnvironment[R1]): Runtime[R1] =
    Runtime(f(environment), runtimeConfig)

  /**
   * Constructs a new `Runtime` by mapping the platform.
   */
  @deprecated("use mapRuntimeConfig", "2.0.0")
  def mapPlatform(f: Platform => Platform): Runtime[R] =
    mapRuntimeConfig(f)

  /**
   * Constructs a new `Runtime` by mapping the runtime configuration.
   */
  def mapRuntimeConfig(f: RuntimeConfig => RuntimeConfig): Runtime[R] =
    Runtime(environment, f(runtimeConfig))

  /**
   * The platform of the runtime, which provides the essential capabilities
   * necessary to bootstrap execution of tasks.
   */
  @deprecated("use runtimeConfig", "2.0.0")
  def platform: Platform =
    runtimeConfig

  /**
   * Runs the effect "purely" through an async boundary. Useful for testing.
   */
  final def run[E, A](zio: ZIO[R, E, A])(implicit trace: ZTraceElement): IO[E, A] =
    IO.async[E, A] { callback =>
      unsafeRunAsyncWith(zio)(exit => callback(ZIO.done(exit)))
    }

  /**
   * Executes the effect synchronously, failing with [[zio.FiberFailure]] if
   * there are any errors. May fail on Scala.js if the effect cannot be entirely
   * run synchronously.
   *
   * This method is effectful and should only be done at the edges of your
   * program.
   */
  final def unsafeRun[E, A](zio: ZIO[R, E, A])(implicit trace: ZTraceElement): A =
    try unsafeRunFast(zio, 50)
    catch {
      case failure: ZIO.ZioError[_] => throw FiberFailure(failure.cause)
    }

  /**
   * Executes the effect asynchronously, discarding the result of execution.
   *
   * This method is effectful and should only be invoked at the edges of your
   * program.
   */
  final def unsafeRunAsync[E, A](zio: ZIO[R, E, A])(implicit trace: ZTraceElement): Unit =
    unsafeRunAsyncWith(zio)(_ => ())

  /**
   * Executes the effect asynchronously, eventually passing the exit value to
   * the specified callback. It returns a callback, which can be used to
   * interrupt the running execution.
   *
   * This method is effectful and should only be invoked at the edges of your
   * program.
   */
  final def unsafeRunSync[E, A](zio0: ZIO[R, E, A])(implicit trace: ZTraceElement): Exit[E, A] =
    unsafeRunSyncFast(zio0)

  private[zio] final def defaultUnsafeRunSync[E, A](zio: ZIO[R, E, A])(implicit trace: ZTraceElement): Exit[E, A] = {
    val result = internal.OneShot.make[Exit[E, A]]

    unsafeRunWith(zio)(result.set)

    result.get()
  }

  private[zio] def unsafeRunSyncFast[E, A](zio: ZIO[R, E, A])(implicit trace: ZTraceElement): Exit[E, A] =
    try {
      Exit.Success(unsafeRunFast(zio, 50))
    } catch {
      case failure: ZIO.ZioError[_] => Exit.Failure(failure.cause.asInstanceOf[Cause[E]])
    }

  private[zio] def unsafeRunFast[E, A](zio: ZIO[R, E, A], maxStack: Int)(implicit
    trace0: ZTraceElement
  ): A = {
    import ZIO.TracedCont
    import Runtime.{Lazy, UnsafeSuccess}

    type Erased  = ZIO[Any, Any, Any]
    type ErasedK = TracedCont[Any, Any, Any, Any]

    val nullK = null.asInstanceOf[ErasedK]

    def loop(zio: ZIO[R, _, _], stack: Int, stackTraceBuilder: Lazy[StackTraceBuilder]): UnsafeSuccess =
      if (stack >= maxStack) {
        defaultUnsafeRunSync(zio) match {
          case Exit.Success(success) => success.asInstanceOf[UnsafeSuccess]
          case Exit.Failure(cause)   => throw new ZIO.ZioError(cause, zio.trace)
        }
      } else {
        var curZio         = zio.asInstanceOf[Erased]
        var x1, x2, x3, x4 = nullK
        var success        = null.asInstanceOf[UnsafeSuccess]

        while (success eq null) {
          try {
            curZio.tag match {
              case ZIO.Tags.FlatMap =>
                val zio = curZio.asInstanceOf[ZIO.FlatMap[R, E, Any, A]]

                val k = zio.asInstanceOf[ErasedK]

                if (x4 eq null) {
                  x4 = x3; x3 = x2; x2 = x1; x1 = k

                  curZio = zio.zio.asInstanceOf[Erased]
                } else {
                  // Our "register"-based stack can't handle it, try consuming more JVM stack:
                  curZio = k(loop(zio.zio, stack + 1, stackTraceBuilder))
                }

              case ZIO.Tags.SucceedNow =>
                val zio = curZio.asInstanceOf[ZIO.SucceedNow[Any]]

                if (x1 ne null) {
                  val k = x1
                  x1 = x2; x2 = x3; x3 = x4; x4 = nullK
                  curZio = k(zio.value)
                } else {
                  success = zio.value.asInstanceOf[UnsafeSuccess]
                }

              case ZIO.Tags.Fail =>
                val zio = curZio.asInstanceOf[ZIO.Fail[E]]

                throw new ZIO.ZioError(zio.cause(), zio.trace)

              case ZIO.Tags.Succeed =>
                val zio = curZio.asInstanceOf[ZIO.Succeed[Any]]

                if (x1 ne null) {
                  val k = x1
                  x1 = x2; x2 = x3; x3 = x4; x4 = nullK
                  curZio = k(zio.effect())
                } else {
                  success = zio.effect().asInstanceOf[UnsafeSuccess]
                }

              case _ =>
                val zio = curZio

                // Give up, the mini-interpreter can't handle it:
                defaultUnsafeRunSync(zio) match {
                  case Exit.Success(value) =>
                    if (x1 ne null) {
                      val k = x1
                      x1 = x2; x2 = x3; x3 = x4; x4 = nullK
                      curZio = k(value)
                    } else {
                      success = value.asInstanceOf[UnsafeSuccess]
                    }

                  case Exit.Failure(cause) => throw new ZIO.ZioError(cause, zio.trace)
                }
            }
          } catch {
            case failure: ZIO.ZioError[_] =>
              val builder = stackTraceBuilder.value

              builder += failure.trace
              if (x1 ne null) {
                builder += x1.trace
                if (x2 ne null) {
                  builder += x2.trace
                  if (x3 ne null) {
                    builder += x3.trace
                    if (x4 ne null) {
                      builder += x4.trace
                    }
                  }
                }
              }

              throw failure

            case t: Throwable =>
              val builder = stackTraceBuilder.value

              builder += zio.trace

              if (x1 ne null) {
                builder += x1.trace
                if (x2 ne null) {
                  builder += x2.trace
                  if (x3 ne null) {
                    builder += x3.trace
                    if (x4 ne null) {
                      builder += x4.trace
                    }
                  }
                }
              }

              if (!runtimeConfig.fatal(t)) throw new ZIO.ZioError(Cause.die(t), trace0)
              else runtimeConfig.reportFatal(t)
          }
        }

        success
      }

    val stackTraceBuilder = Lazy.stackTraceBuilder()

    try {
      loop(zio, 0, stackTraceBuilder).asInstanceOf[A]
    } catch {
      case failure: ZIO.ZioError[_] =>
        val cause = failure.cause.asInstanceOf[Cause[E]]

        val fiberId = cause.trace.fiberId.getOrElse(FiberId.unsafeMake())

        val trace = ZTrace(fiberId, stackTraceBuilder.value.result())

        throw new ZIO.ZioError(cause.traced(trace), trace0)
    }
  }

  /**
   * Executes the effect asynchronously, eventually passing the exit value to
   * the specified callback.
   *
   * This method is effectful and should only be invoked at the edges of your
   * program.
   */
  final def unsafeRunAsyncWith[E, A](
    zio: ZIO[R, E, A]
  )(k: Exit[E, A] => Any)(implicit trace: ZTraceElement): Unit = {
    unsafeRunAsyncCancelable(zio)(k)
    ()
  }

  /**
   * Executes the effect asynchronously, eventually passing the exit value to
   * the specified callback. It returns a callback, which can be used to
   * interrupt the running execution.
   *
   * This method is effectful and should only be invoked at the edges of your
   * program.
   */
  final def unsafeRunAsyncCancelable[E, A](
    zio: ZIO[R, E, A]
  )(k: Exit[E, A] => Any)(implicit trace: ZTraceElement): FiberId => Exit[E, A] = {
    lazy val curZio = zio
    val canceler    = unsafeRunWith(curZio)(k)
    fiberId => {
      val result = internal.OneShot.make[Exit[E, A]]
      canceler(fiberId)(result.set)
      result.get()
    }
  }

  /**
   * Executes the Task/RIO effect synchronously, failing with the original
   * `Throwable` on both [[Cause.Fail]] and [[Cause.Die]]. In addition, appends
   * a new element to the `Throwable`s "caused by" chain, with this `Cause`
   * "pretty printed" (in stackless mode) as the message. May fail on Scala.js
   * if the effect cannot be entirely run synchronously.
   *
   * This method is effectful and should only be done at the edges of your
   * program.
   */
  final def unsafeRunTask[A](task: RIO[R, A])(implicit trace: ZTraceElement): A =
    unsafeRunSync(task).fold(cause => throw cause.squashTrace, identity)

  /**
   * Runs the IO, returning a Future that will be completed when the effect has
   * been executed.
   *
   * This method is effectful and should only be used at the edges of your
   * program.
   */
  final def unsafeRunToFuture[E <: Throwable, A](
    zio: ZIO[R, E, A]
  )(implicit trace: ZTraceElement): CancelableFuture[A] = {
    val p: scala.concurrent.Promise[A] = scala.concurrent.Promise[A]()

    val canceler = unsafeRunWith(zio)(_.fold(cause => p.failure(cause.squashTraceWith(identity)), p.success))

    new CancelableFuture[A](p.future) {
      def cancel(): Future[Exit[Throwable, A]] = {
        val p: scala.concurrent.Promise[Exit[Throwable, A]] = scala.concurrent.Promise[Exit[Throwable, A]]()
        canceler(FiberId.None)(p.success)
        p.future
      }
    }
  }

  /**
   * Constructs a new `Runtime` with the specified blocking executor.
   */
  def withBlockingExecutor(e: Executor): Runtime[R] = mapRuntimeConfig(_.copy(blockingExecutor = e))

  /**
   * Constructs a new `Runtime` with the specified executor.
   */
  def withExecutor(e: Executor): Runtime[R] = mapRuntimeConfig(_.copy(executor = e))

  /**
   * Constructs a new `Runtime` with the specified fatal predicate.
   */
  def withFatal(f: Throwable => Boolean): Runtime[R] = mapRuntimeConfig(_.copy(fatal = f))

  /**
   * Constructs a new `Runtime` with the fatal error reporter.
   */
  def withReportFatal(f: Throwable => Nothing): Runtime[R] = mapRuntimeConfig(_.copy(reportFatal = f))

  private final def unsafeRunWith[E, A](
    zio: ZIO[R, E, A]
  )(k: Exit[E, A] => Any)(implicit trace: ZTraceElement): FiberId => (Exit[E, A] => Any) => Unit = {
    val fiberId = FiberId.unsafeMake()

    val scope = ZScope.unsafeMake[Exit[E, A]]()

    val supervisor = runtimeConfig.supervisor

    lazy val context: FiberContext[E, A] = new FiberContext[E, A](
      fiberId,
      runtimeConfig,
      StackBool(InterruptStatus.Interruptible.toBoolean),
      new java.util.concurrent.atomic.AtomicReference(
        Map(ZFiberRef.currentEnvironment -> environment.asInstanceOf[AnyRef])
      ),
      scope
    )

    if (supervisor ne Supervisor.none) {
      supervisor.unsafeOnStart(environment, zio, None, context)

      context.unsafeOnDone(exit => supervisor.unsafeOnEnd(exit.flatten, context))
    }

    context.nextEffect = zio
    context.run()
    context.unsafeOnDone(exit => k(exit.flatten))

    fiberId =>
      k => unsafeRunAsyncWith(context.interruptAs(fiberId))((exit: Exit[Nothing, Exit[E, A]]) => k(exit.flatten))
  }
}

object Runtime {
  private[zio] type UnsafeSuccess <: AnyRef
  private[zio] class Lazy[A](thunk: () => A) {
    lazy val value = thunk()
  }
  private[zio] object Lazy {
    def apply[A](a: => A): Lazy[A] = new Lazy(() => a)

    def stackTraceBuilder[A](): Lazy[StackTraceBuilder] =
      new Lazy(() => StackTraceBuilder.unsafeMake())
  }

  class Proxy[+R](underlying: Runtime[R]) extends Runtime[R] {
    def runtimeConfig = underlying.runtimeConfig
    def environment   = underlying.environment
  }

  /**
   * A runtime that can be shutdown to release resources allocated to it.
   */
  abstract class Managed[+R] extends Runtime[R] {

    /**
     * Shuts down this runtime and releases resources allocated to it. Once this
     * runtime has been shut down the behavior of methods on it is undefined and
     * it should be discarded.
     */
    def shutdown(): Unit

    override final def as[R1](r1: ZEnvironment[R1]): Runtime.Managed[R1] =
      map(_ => r1)

    override final def map[R1](f: ZEnvironment[R] => ZEnvironment[R1]): Runtime.Managed[R1] =
      Managed(f(environment), runtimeConfig, () => shutdown())

    override final def mapRuntimeConfig(f: RuntimeConfig => RuntimeConfig): Runtime.Managed[R] =
      Managed(environment, f(runtimeConfig), () => shutdown())

    override final def withExecutor(e: Executor): Runtime.Managed[R] =
      mapRuntimeConfig(_.copy(executor = e))

    override final def withFatal(f: Throwable => Boolean): Runtime.Managed[R] =
      mapRuntimeConfig(_.copy(fatal = f))

    override final def withReportFatal(f: Throwable => Nothing): Runtime.Managed[R] =
      mapRuntimeConfig(_.copy(reportFatal = f))
  }

  object Managed {

    /**
     * Builds a new managed runtime given an environment `R`, a
     * [[zio.RuntimeConfig]], and a shut down action.
     */
    def apply[R](r: ZEnvironment[R], runtimeConfig0: RuntimeConfig, shutdown0: () => Unit): Runtime.Managed[R] =
      new Runtime.Managed[R] {
        val environment   = r
        val runtimeConfig = runtimeConfig0
        def shutdown()    = shutdown0()
      }
  }

  /**
   * Builds a new runtime given an environment `R` and a [[zio.RuntimeConfig]].
   */
  def apply[R](r: ZEnvironment[R], runtimeConfig0: RuntimeConfig): Runtime[R] = new Runtime[R] {
    val environment   = r
    val runtimeConfig = runtimeConfig0
  }

  /**
   * The default [[Runtime]] for most ZIO applications. This runtime is
   * configured with the default environment, containing standard services, as
   * well as the default runtime configuration, which is optimized for typical
   * ZIO applications.
   */
  lazy val default: Runtime[ZEnv] = Runtime(ZEnvironment.default, RuntimeConfig.default)

  /**
   * The global [[Runtime]], which piggybacks atop the global execution context
   * available to Scala applications. Use of this runtime is not generally
   * recommended, unless the intention is to avoid creating any thread pools or
   * other resources.
   */
  lazy val global: Runtime[ZEnv] = Runtime(ZEnvironment.default, RuntimeConfig.global)

  /**
   * Unsafely creates a `Runtime` from a `ZLayer` whose resources will be
   * allocated immediately, and not released until the `Runtime` is shut down or
   * the end of the application.
   *
   * This method is useful for small applications and integrating ZIO with
   * legacy code, but other applications should investigate using
   * [[ZIO.provide]] directly in their application entry points.
   */
  def unsafeFromLayer[R](
    layer: Layer[Any, R],
    runtimeConfig: RuntimeConfig = RuntimeConfig.default
  )(implicit trace: ZTraceElement): Runtime.Managed[R] = {
    val runtime = Runtime(ZEnvironment.empty, runtimeConfig)
    val (environment, shutdown) = runtime.unsafeRun {
      ZManaged.ReleaseMap.make.flatMap { releaseMap =>
        ZManaged.currentReleaseMap.locally(releaseMap)(layer.build.zio).flatMap { case (_, acquire) =>
          val finalizer = () =>
            runtime.unsafeRun {
              releaseMap.releaseAll(Exit.unit, ExecutionStrategy.Sequential).uninterruptible.unit
            }

          UIO.succeed(Platform.addShutdownHook(finalizer)).as((acquire, finalizer))
        }
      }
    }

    Runtime.Managed(environment, runtimeConfig, shutdown)
  }
}
