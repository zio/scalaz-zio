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

import zio.internal.{FiberRuntime, FiberScope, IsFatal, Platform}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.Future

/**
 * A `Runtime[R]` is capable of executing tasks within an environment `R`.
 */
trait Runtime[+R] { self =>

  /**
   * The environment of the runtime.
   */
  def environment: ZEnvironment[R]

  /**
   * The [[zio.FiberRefs]] that will be used for all effects executed by this
   * runtime.
   */
  def fiberRefs: FiberRefs

  /**
   * Constructs a new `Runtime` by mapping the environment.
   */
  def mapEnvironment[R1](f: ZEnvironment[R] => ZEnvironment[R1]): Runtime[R1] =
    Runtime(f(environment), fiberRefs, runtimeFlags)

  /**
   * Runs the effect "purely" through an async boundary. Useful for testing.
   */
  final def run[E, A](zio: ZIO[R, E, A])(implicit trace: Trace): IO[E, A] =
    ZIO.fiberIdWith { fiberId =>
      ZIO.asyncInterrupt[Any, E, A] { callback =>
        val fiber = unsafe.fork(zio)(trace, Unsafe.unsafe)
        fiber.unsafe.addObserver(callback(_))(Unsafe.unsafe)
        Left(ZIO.blocking(fiber.interruptAs(fiberId)))
      }
    }

  /**
   * The [[zio.RuntimeFlags]] that will be used for all effects executed by this
   * [[zio.Runtime]].
   */
  def runtimeFlags: RuntimeFlags

  /**
   * Constructs a new `Runtime` with the specified new environment.
   */
  def withEnvironment[R1](r1: ZEnvironment[R1]): Runtime[R1] =
    mapEnvironment(_ => r1)

  trait UnsafeAPI {

    /**
     * Executes the effect asynchronously, returning a fiber whose methods can
     * await the exit value of the fiber or interrupt the fiber.
     *
     * This method is effectful and should only be used at the edges of your
     * application.
     */
    def fork[E, A](zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): Fiber.Runtime[E, A]

    /**
     * Executes the effect synchronously and returns its result as a
     * [[zio.Exit]] value. May fail on Scala.js if the effect cannot be entirely
     * run synchronously.
     *
     * This method is effectful and should only be used at the edges of your
     * application.
     */
    def run[E, A](zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): Exit[E, A]

    /**
     * Executes the effect asynchronously, returning a Future that will be
     * completed when the effect has been fully executed. The Future can be
     * canceled, which will be translated into ZIO's interruption model.
     *
     * This method is effectful and should only be used at the edges of your
     * application.
     */
    def runToFuture[E <: Throwable, A](
      zio: ZIO[R, E, A]
    )(implicit trace: Trace, unsafe: Unsafe): CancelableFuture[A]
  }

  trait UnsafeAPI3 {

    /**
     * Attempts to execute the effect synchronously and returns its result as a
     * [[zio.Exit]] value. If the effect cannot be entirely run synchronously,
     * the effect will be forked and the fiber will be returned.
     *
     * This method is effectful and should only be used at the edges of your
     * application.
     */
    def runOrFork[E, A](
      zio: ZIO[R, E, A]
    )(implicit trace: Trace, unsafe: Unsafe): Either[Fiber.Runtime[E, A], Exit[E, A]]
  }

  def unsafe: UnsafeAPI with UnsafeAPI3 =
    new UnsafeAPIV1 {}

  protected abstract class UnsafeAPIV1 extends UnsafeAPI with UnsafeAPI3 {

    private val fiberIdGen = self.fiberRefs.getOrDefault(FiberRef.currentFiberIdGenerator)

    def fork[E, A](zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): internal.FiberRuntime[E, A] = {
      val fiber = makeFiber(zio)
      fiber.startConcurrently(zio)
      fiber
    }

    def run[E, A](zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): Exit[E, A] =
      runOrFork(zio) match {
        case Left(fiber) =>
          import internal.{FiberMessage, OneShot}
          val result = OneShot.make[Exit[E, A]]
          fiber.unsafe.addObserver(result.set)
          internal.Blocking.signalBlocking()
          result.get()
        case Right(exit) => exit
      }

    def runOrFork[E, A](
      zio: ZIO[R, E, A]
    )(implicit trace: Trace, unsafe: Unsafe): Either[internal.FiberRuntime[E, A], Exit[E, A]] = {
      import internal.FiberRuntime

      val fiberId   = fiberIdGen.make(trace)
      val fiberRefs = self.fiberRefs.updatedAs(fiberId)(FiberRef.currentEnvironment, environment)
      val fiber     = FiberRuntime[E, A](fiberId, fiberRefs.forkAs(fiberId), runtimeFlags)

      val supervisor = fiber.getSupervisor()

      if (supervisor ne Supervisor.none) {
        supervisor.onStart(environment, zio, None, fiber)

        fiber.addObserver(exit => supervisor.onEnd(exit, fiber))
      }

      val exit = fiber.start[R](zio)

      if (exit ne null) Right(exit)
      else {
        FiberScope.global.add(null, runtimeFlags, fiber)
        Left(fiber)
      }
    }

    def runToFuture[E <: Throwable, A](
      zio: ZIO[R, E, A]
    )(implicit trace: Trace, unsafe: Unsafe): CancelableFuture[A] = {
      val p: scala.concurrent.Promise[A] = scala.concurrent.Promise[A]()

      val fiber = makeFiber(zio)

      fiber.addObserver(_.foldExit(cause => p.failure(cause.squashTraceWith(identity)), p.success))

      fiber.startConcurrently(zio)

      new CancelableFuture[A](p.future) {
        def cancel(): Future[Exit[Throwable, A]] = {
          val p: scala.concurrent.Promise[Exit[Throwable, A]] = scala.concurrent.Promise[Exit[Throwable, A]]()
          val cancelFiber                                     = makeFiber(fiber.interruptAs(FiberId.None))
          cancelFiber.addObserver(_.foldExit(cause => p.failure(cause.squashTraceWith(identity)), p.success))
          cancelFiber.start(fiber.interruptAs(FiberId.None))
          p.future
        }
      }
    }

    private def makeFiber[E, A](
      zio: ZIO[R, E, A]
    )(implicit trace: Trace, unsafe: Unsafe): internal.FiberRuntime[E, A] = {
      val fiberId   = fiberIdGen.make(trace)
      val fiberRefs = self.fiberRefs.updatedAs(fiberId)(FiberRef.currentEnvironment, environment)
      val fiber     = FiberRuntime[E, A](fiberId, fiberRefs.forkAs(fiberId), runtimeFlags)

      FiberScope.global.add(null, runtimeFlags, fiber)

      val supervisor = fiber.getSupervisor()

      if (supervisor ne Supervisor.none) {
        supervisor.onStart(environment, zio, None, fiber)

        fiber.addObserver(exit => supervisor.onEnd(exit, fiber))
      }

      fiber
    }
  }
}

object Runtime extends RuntimePlatformSpecific {

  def addFatal(fatal: Class[_ <: Throwable])(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.currentFatal.locallyScopedWith(_ | IsFatal(fatal)))

  def addLogAnnotation(annotation: LogAnnotation)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.currentLogAnnotations.locallyScopedWith(_ + (annotation.key -> annotation.value)))

  def addLogger(logger: ZLogger[String, Any])(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(ZIO.withLoggerScoped(logger))

  def addSupervisor(supervisor: Supervisor[Any])(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.currentSupervisor.locallyScopedWith(_ ++ supervisor))

  /**
   * Builds a new runtime given an environment `R` and a [[zio.FiberRefs]].
   */
  def apply[R](
    r: ZEnvironment[R],
    fiberRefs0: FiberRefs,
    runtimeFlags0: RuntimeFlags
  ): Runtime[R] =
    new Runtime[R] {
      val environment           = r
      override val fiberRefs    = fiberRefs0
      override val runtimeFlags = runtimeFlags0
    }

  /**
   * The default [[Runtime]] for most ZIO applications. This runtime is
   * configured with the the default runtime configuration, which is optimized
   * for typical ZIO applications.
   */
  val default: Runtime[Any] =
    Runtime(ZEnvironment.empty, FiberRefs.empty, RuntimeFlags.default)

  def disableFlags(flags: RuntimeFlag*)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped {
      ZIO.foreachDiscard(flags)(f => ZIO.withRuntimeFlagsScoped(RuntimeFlags.disable(f)))
    }

  def enableCooperativeYielding(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.CooperativeYielding)

  def enableCurrentFiber(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.CurrentFiber)

  def enableFlags(flags: RuntimeFlag*)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped {
      ZIO.foreachDiscard(flags)(f => ZIO.withRuntimeFlagsScoped(RuntimeFlags.enable(f)))
    }

  def enableFiberRoots(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.FiberRoots)

  def enableOpLog(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.OpLog)

  def enableOpSupervision(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.OpSupervision)

  def enableRuntimeMetrics(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.RuntimeMetrics)

  def enableWorkStealing(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    enableFlags(RuntimeFlag.WorkStealing)

  val removeDefaultLoggers: ZLayer[Any, Nothing, Unit] = {
    implicit val trace = Trace.empty
    ZLayer.scoped(FiberRef.currentLoggers.locallyScopedWith(_ -- Runtime.defaultLoggers))
  }

  def setBlockingExecutor(executor: Executor)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.currentBlockingExecutor.locallyScoped(executor))

  def setConfigProvider(configProvider: ConfigProvider)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(ZIO.withConfigProviderScoped(configProvider))

  def setExecutor(executor: Executor)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.overrideExecutor.locallyScoped(Some(executor)))

  def setUnhandledErrorLogLevel(logLevel: LogLevel)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.unhandledErrorLogLevel.locallyScoped(Some(logLevel)))

  def setReportFatal(reportFatal: Throwable => Nothing)(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(FiberRef.currentReportFatal.locallyScoped(reportFatal))

  object unsafe {

    /**
     * Unsafely creates a `Runtime` from a `ZLayer` whose resources will be
     * allocated immediately, and not released until the `Runtime` is shut down
     * or the end of the application.
     *
     * This method is useful for small applications and integrating ZIO with
     * legacy code, but other applications should investigate using
     * [[ZIO.provide]] directly in their application entry points.
     */
    def fromLayer[R](layer: Layer[Any, R])(implicit trace: Trace, unsafe: Unsafe): Runtime.Scoped[R] = {
      val (runtime, shutdown) = default.unsafe.run {
        Scope.make.flatMap { scope =>
          scope.extend(layer.toRuntime).flatMap { acquire =>
            val finalizer = () =>
              default.unsafe.run {
                scope.close(Exit.unit).uninterruptible.unit
              }.getOrThrowFiberFailure()

            ZIO.succeed(Platform.addShutdownHook(finalizer)).as((acquire, finalizer))
          }
        }
      }.getOrThrowFiberFailure()

      Runtime.Scoped(runtime.environment, runtime.fiberRefs, runtime.runtimeFlags, () => shutdown())
    }
  }

  class Proxy[+R](underlying: Runtime[R]) extends Runtime[R] {
    def environment = underlying.environment
    def fiberRefs   = underlying.fiberRefs

    def runtimeFlags = underlying.runtimeFlags
  }

  /**
   * A runtime that can be shutdown to release resources allocated to it.
   */
  final case class Scoped[+R](
    environment: ZEnvironment[R],
    fiberRefs: FiberRefs,
    runtimeFlags: RuntimeFlags,
    shutdown0: () => Unit
  ) extends Runtime[R] { self =>
    override final def mapEnvironment[R1](f: ZEnvironment[R] => ZEnvironment[R1]): Runtime.Scoped[R1] =
      Scoped(f(environment), fiberRefs, runtimeFlags, shutdown0)

    trait UnsafeAPI2 {

      /**
       * Shuts down this runtime and releases resources allocated to it. Once
       * this runtime has been shut down the behavior of methods on it is
       * undefined and it should be discarded.
       */
      def shutdown()(implicit unsafe: Unsafe): Unit
    }

    override def unsafe: UnsafeAPI with UnsafeAPI2 with UnsafeAPI3 =
      new UnsafeAPIV2 {}

    protected abstract class UnsafeAPIV2 extends UnsafeAPIV1 with UnsafeAPI2 {
      def shutdown()(implicit unsafe: Unsafe) = shutdown0()
    }

  }
}
