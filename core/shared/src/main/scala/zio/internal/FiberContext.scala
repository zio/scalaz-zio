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

package zio.internal

import zio.Fiber.Status
import zio.ZIO.{FlatMap, TracedCont}
import zio._
import zio.internal.FiberContext.FiberRefLocals
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.{switch, tailrec}

/**
 * An implementation of Fiber that maintains context necessary for evaluation.
 */
private[zio] final class FiberContext[E, A](
  protected val fiberId: FiberId,
  var runtimeConfig: RuntimeConfig,
  startEnv: AnyRef,
  startExec: zio.Executor,
  startLocked: Boolean,
  startIStatus: InterruptStatus,
  parentTrace: Option[ZTrace],
  initialTracingStatus: Boolean,
  var fiberRefLocals: FiberRefLocals,
  openScope: ZScope.Open[Exit[E, A]]
) extends Fiber.Runtime.Internal[E, A]
    with FiberRunnable { self =>
  type Erased           = ZIO[Any, Any, Any]
  type Cont             = Any => Erased
  type ErasedTracedCont = TracedCont[Any, Any, Any, Any]

  import FiberContext._
  import FiberState._

  // Accessed from multiple threads:
  private val state = new AtomicReference[FiberState[E, A]](FiberState.initial)

  @volatile
  private[this] var asyncEpoch: Long = 0L

  private[this] val stack = Stack[ErasedTracedCont]()

  private[this] val interruptStatus = StackBool(startIStatus.toBoolean)

  private[this] var currentEnvironment       = startEnv
  private[this] var currentExecutor          = startExec
  private[this] var currentLocked            = startLocked
  private[this] var currentForkScopeOverride = Option.empty[ZScope[Exit[Any, Any]]]
  private[this] var currentTracingStatus     = initialTracingStatus

  var scopeKey: ZScope.Key = null

  @volatile var nextEffect: Erased = null

  private[this] val traceExec: Boolean =
    runtimeConfig.tracing.tracingConfig.traceExecution

  private[this] val traceStack: Boolean =
    runtimeConfig.tracing.tracingConfig.traceStack

  private[this] val execTrace =
    if (traceExec) SingleThreadedRingBuffer[ZTraceElement](runtimeConfig.tracing.tracingConfig.executionTraceLength)
    else null

  @noinline
  private[this] def inTracingRegion: Boolean =
    if (traceExec || traceStack) currentTracingStatus else false

  private[this] def captureStackTrace(): List[ZTraceElement] =
    if (traceStack) stack.toList.reverse.map(_.trace) else Nil

  private[this] def captureTrace(lastStack: String): ZTrace = {
    val exec = if (execTrace ne null) execTrace.toReversedList else Nil
    val stack = {
      val stack0 = captureStackTrace()
      if (lastStack ne null) lastStack.asInstanceOf[ZTraceElement] :: stack0 else stack0
    }
    ZTrace(fiberId, exec, stack, parentTrace)
  }

  private[this] def cutAncestryTrace(trace: ZTrace): ZTrace = {
    val maxExecLength  = runtimeConfig.tracing.tracingConfig.ancestorExecutionTraceLength
    val maxStackLength = runtimeConfig.tracing.tracingConfig.ancestorStackTraceLength
    val maxAncestors   = runtimeConfig.tracing.tracingConfig.ancestryLength - 1

    val truncatedParentTrace = ZTrace.truncatedParentTrace(trace, maxAncestors)

    ZTrace(
      executionTrace = trace.executionTrace.take(maxExecLength),
      stackTrace = captureStackTrace().take(maxStackLength),
      parentTrace = truncatedParentTrace,
      fiberId = trace.fiberId
    )
  }

  private[zio] def awaitAsync(k: Callback[E, A]): Any =
    register0(xx => k(Exit.flatten(xx))) match {
      case null =>
      case v    => k(v)
    }

  private[this] class InterruptExit(implicit val trace: ZTraceElement) extends TracedCont[Any, Any, E, Any] {
    def apply(v: Any): IO[E, Any] =
      if (isInterruptible()) {
        interruptStatus.popDrop(())

        ZIO.succeedNow(v)
      } else { // TODO: Delete this 'else' branch
        ZIO.succeed(interruptStatus.popDrop(v))
      }
  }

  private[this] class Finalizer(val finalizer: UIO[Any]) extends ErasedTracedCont {
    def apply(v: Any): Erased = {
      disableInterrupt()
      restoreInterrupt()(finalizer.trace)
      finalizer.map(_ => v)(finalizer.trace)
    }
    override val trace: ZTraceElement = finalizer.trace
  }

  private[this] def disableInterrupt(): Unit =
    interruptStatus.push(false)

  /**
   * Unwinds the stack, looking for the first error handler, and exiting
   * interruptible / uninterruptible regions.
   */
  private[this] def unwindStack(): Boolean = {
    var unwinding      = true
    var discardedFolds = false

    // Unwind the stack, looking for an error handler:
    while (unwinding && !stack.isEmpty) {
      stack.pop() match {
        case _: InterruptExit =>
          // do not remove InterruptExit from stack trace as it was not added
          interruptStatus.popDrop(())

        case finalizer: Finalizer =>
          implicit val trace: ZTraceElement = finalizer.trace
          disableInterrupt()

          stack.push(
            TracedCont(cause =>
              finalizer.finalizer.foldCauseZIO(
                finalizerCause => {
                  interruptStatus.popDrop(null)

                  addSuppressedCause(finalizerCause)

                  ZIO.failCause(cause.asInstanceOf[Cause[Any]])
                },
                _ => {
                  interruptStatus.popDrop(null)

                  ZIO.failCause(cause.asInstanceOf[Cause[Any]])
                }
              )
            )
          )

          unwinding = false

        case fold: ZIO.Fold[_, _, _, _, _] if !shouldInterrupt() =>
          // Push error handler back onto the stack and halt iteration:
          val k = fold.failure.asInstanceOf[Cont]

          stack.push(TracedCont(k)(fold.trace))

          unwinding = false

        case _: ZIO.Fold[_, _, _, _, _] =>
          discardedFolds = true

        case _ =>
      }
    }

    discardedFolds
  }

  private[this] def executor: zio.Executor =
    if (currentExecutor ne null) currentExecutor else runtimeConfig.executor

  @inline private[this] def raceWithImpl[R, EL, ER, E, A, B, C](
    race: ZIO.RaceWith[R, EL, ER, E, A, B, C]
  )(implicit trace: ZTraceElement): ZIO[R, E, C] = {
    @inline def complete[E0, E1, A, B](
      winner: Fiber[E0, A],
      loser: Fiber[E1, B],
      cont: (Exit[E0, A], Fiber[E1, B]) => ZIO[R, E, C],
      winnerExit: Exit[E0, A],
      ab: AtomicBoolean,
      cb: ZIO[R, E, C] => Any
    ): Any =
      if (ab.compareAndSet(true, false)) {
        winnerExit match {
          case exit: Exit.Success[_] =>
            cb(winner.inheritRefs.flatMap(_ => cont(exit, loser)))
          case exit: Exit.Failure[_] =>
            cb(cont(exit, loser))
        }
      }

    val raceIndicator = new AtomicBoolean(true)

    val scope = race.scope()
    val left  = fork[EL, A](race.left().asInstanceOf[IO[EL, A]], scope)
    val right = fork[ER, B](race.right().asInstanceOf[IO[ER, B]], scope)

    ZIO
      .async[R, E, C](
        { cb =>
          val leftRegister = left.register0 {
            case exit0: Exit.Success[Exit[EL, A]] =>
              complete[EL, ER, A, B](left, right, race.leftWins, exit0.value, raceIndicator, cb)
            case exit: Exit.Failure[_] => complete(left, right, race.leftWins, exit, raceIndicator, cb)
          }

          if (leftRegister ne null)
            complete(left, right, race.leftWins, leftRegister, raceIndicator, cb)
          else {
            val rightRegister = right.register0 {
              case exit0: Exit.Success[Exit[_, _]] =>
                complete(right, left, race.rightWins, exit0.value, raceIndicator, cb)
              case exit: Exit.Failure[_] => complete(right, left, race.rightWins, exit, raceIndicator, cb)
            }

            if (rightRegister ne null)
              complete(right, left, race.rightWins, rightRegister, raceIndicator, cb)
          }
        },
        left.fiberId // FIXME with composite fiber id: right.fiberId
      )
  }

  override final def run(): Unit = runUntil(executor.yieldOpCount)

  /**
   * The main evaluator loop for the fiber. For purely synchronous effects, this will run either
   * to completion, or for the specified maximum operation count. For effects with asynchronous
   * callbacks, the loop will proceed no further than the first asynchronous boundary.
   */
  override final def runUntil(maxOpCount: Int): Unit =
    try {
      // Do NOT accidentally capture `curZio` in a closure, or Scala will wrap
      // it in `ObjectRef` and performance will plummet.
      var curZio = nextEffect

      nextEffect = null

      // Put the stack reference on the stack:
      val stack = this.stack

      // Store the trace of the immediate future flatMap during evaluation
      // of a 1-hop left bind, to show a stack trace closer to the point of failure
      var fastPathFlatMapContinuationTrace: String = null

      val traceStack = self.traceStack
      val traceExec  = self.traceExec

      @noinline
      def fastPathTrace(current: FlatMap[Any, Any, Any, Any]): Unit = {
        // record the nearest continuation for a better trace in case of failure
        if (traceStack) fastPathFlatMapContinuationTrace = current.trace.toString
        if (traceExec && (execTrace.lastOrNull ne current.zio.trace)) execTrace.put(current.zio.trace)
      }

      @noinline
      def fastPathTraceCleanup(current: FlatMap[Any, Any, Any, Any]): Unit = {
        // delete continuation trace as it was "popped" after success
        fastPathFlatMapContinuationTrace = null
        // record continuation in exec as we're just "passing" it
        if (traceExec && (execTrace.lastOrNull ne current.trace)) execTrace.put(current.trace)
      }

      if (runtimeConfig.enableCurrentFiber) Fiber._currentFiber.set(this)

      while (curZio ne null) {
        try {
          var opCount: Int = 0

          while ({
            val tag = curZio.tag
            // println(curZio)

            // Check to see if the fiber should continue executing or not:
            if (!shouldInterrupt()) {
              // Fiber does not need to be interrupted, but might need to yield:
              if (opCount == maxOpCount) {
                evaluateLater(curZio)
                curZio = null
              } else {
                // Fiber is neither being interrupted nor needs to yield. Execute
                // the next instruction in the program:
                if (traceExec && currentTracingStatus && tag > ZIO.Tags.ExecutionTracingCutoff)
                  addExecutionTrace(curZio.trace)
                (tag: @switch) match {
                  case ZIO.Tags.FlatMap =>
                    val zio = curZio.asInstanceOf[ZIO.FlatMap[Any, Any, Any, Any]]

                    val nested = zio.zio
                    val k      = zio.k

                    // A mini interpreter for the left side of FlatMap that evaluates
                    // anything that is 1-hop away. This eliminates heap usage for the
                    // happy path.
                    (nested.tag: @switch) match {
                      case ZIO.Tags.SucceedNow =>
                        val io2 = nested.asInstanceOf[ZIO.SucceedNow[Any]]

                        if (traceExec && currentTracingStatus) addExecutionTrace(zio.trace)
                        curZio = k(io2.value)

                      case ZIO.Tags.Succeed =>
                        val io2 = nested.asInstanceOf[ZIO.Succeed[Any]]

                        if (currentTracingStatus) fastPathTrace(zio)
                        val value = io2.effect()
                        if (currentTracingStatus) fastPathTraceCleanup(zio)

                        curZio = k(value)

                      case ZIO.Tags.SucceedWith =>
                        val io2    = nested.asInstanceOf[ZIO.SucceedWith[Any]]
                        val effect = io2.effect

                        if (currentTracingStatus) fastPathTrace(zio)
                        val value = effect(runtimeConfig, fiberId)
                        if (currentTracingStatus) fastPathTraceCleanup(zio)

                        curZio = k(value)

                      case ZIO.Tags.Yield =>
                        if (currentTracingStatus) fastPathTrace(zio)
                        evaluateLater(k(()))
                        if (currentTracingStatus) fastPathTraceCleanup(zio)

                        curZio = null

                      case _ =>
                        // Fallback case. We couldn't evaluate the LHS so we have to
                        // use the stack:
                        curZio = nested
                        stack.push(zio)
                    }

                  case ZIO.Tags.SucceedNow =>
                    val zio = curZio.asInstanceOf[ZIO.SucceedNow[Any]]

                    val value = zio.value

                    curZio = nextInstr(value)

                  case ZIO.Tags.Succeed =>
                    val zio    = curZio.asInstanceOf[ZIO.Succeed[Any]]
                    val effect = zio.effect

                    curZio = nextInstr(effect())

                  case ZIO.Tags.SucceedWith =>
                    val zio    = curZio.asInstanceOf[ZIO.SucceedWith[Any]]
                    val effect = zio.effect

                    curZio = nextInstr(effect(runtimeConfig, fiberId))

                  case ZIO.Tags.Fail =>
                    val zio = curZio.asInstanceOf[ZIO.Fail[Any]]

                    // Put last trace into a val to avoid `ObjectRef` boxing.
                    val fastPathTrace = fastPathFlatMapContinuationTrace
                    fastPathFlatMapContinuationTrace = null

                    val tracedCause = zio.fill(() => captureTrace(fastPathTrace))

                    val discardedFolds = unwindStack()
                    val fullCause =
                      (if (discardedFolds)
                         // We threw away some error handlers while unwinding the stack because
                         // we got interrupted during this instruction. So it's not safe to return
                         // typed failures from cause0, because they might not be typed correctly.
                         // Instead, we strip the typed failures, and return the remainders and
                         // the interruption.
                         tracedCause.stripFailures
                       else
                         tracedCause) ++ clearSuppressedCause()

                    if (stack.isEmpty) {
                      // Error not caught, stack is empty:
                      setInterrupting(true)

                      curZio = done(Exit.failCause(fullCause.asInstanceOf[Cause[E]]))(curZio.trace)
                    } else {
                      setInterrupting(false)

                      // Error caught, next continuation on the stack will deal
                      // with it, so we just have to compute it here:
                      curZio = nextInstr(fullCause)
                    }

                  case ZIO.Tags.Fold =>
                    val zio = curZio.asInstanceOf[ZIO.Fold[Any, Any, Any, Any, Any]]

                    curZio = zio.value

                    stack.push(zio)

                  case ZIO.Tags.Suspend =>
                    val zio = curZio.asInstanceOf[ZIO.Suspend[Any, Any, Any]]

                    curZio = zio.make()

                  case ZIO.Tags.SuspendWith =>
                    val zio = curZio.asInstanceOf[ZIO.SuspendWith[Any, Any, Any]]

                    curZio = zio.make(runtimeConfig, fiberId)

                  case ZIO.Tags.InterruptStatus =>
                    val zio = curZio.asInstanceOf[ZIO.InterruptStatus[Any, Any, Any]]

                    val boolFlag = zio.flag().toBoolean

                    if (interruptStatus.peekOrElse(true) != boolFlag) {
                      interruptStatus.push(boolFlag)

                      restoreInterrupt()(curZio.trace)
                    }

                    curZio = zio.zio

                  case ZIO.Tags.CheckInterrupt =>
                    val zio = curZio.asInstanceOf[ZIO.CheckInterrupt[Any, Any, Any]]

                    curZio = zio.k(InterruptStatus.fromBoolean(isInterruptible()))

                  case ZIO.Tags.TracingStatus =>
                    val zio = curZio.asInstanceOf[ZIO.TracingStatus[Any, E, Any]]

                    val oldTracingStatus = currentTracingStatus
                    currentTracingStatus = zio.flag().toBoolean

                    ensure(ZIO.succeed { currentTracingStatus = oldTracingStatus }(zio.trace))

                    curZio = zio.zio

                  case ZIO.Tags.CheckTracing =>
                    val zio = curZio.asInstanceOf[ZIO.CheckTracing[Any, Any, Any]]

                    curZio = zio.k(TracingStatus.fromBoolean(inTracingRegion))

                  case ZIO.Tags.Async =>
                    val zio                           = curZio.asInstanceOf[ZIO.Async[Any, Any, Any]]
                    implicit val trace: ZTraceElement = zio.trace

                    val epoch = asyncEpoch
                    asyncEpoch = epoch + 1

                    // Enter suspended state:
                    enterAsync(epoch, zio.register, zio.blockingOn())

                    val k = zio.register

                    curZio = k(resumeAsync(epoch)) match {
                      case Left(canceler) =>
                        setAsyncCanceler(epoch, canceler)
                        if (shouldInterrupt()) {
                          if (exitAsync(epoch)) {
                            setInterrupting(true)
                            canceler *> ZIO.failCause(clearSuppressedCause())
                          } else null
                        } else null
                      case Right(zio) =>
                        if (!exitAsync(epoch)) null else zio.asInstanceOf[Erased]
                    }

                  case ZIO.Tags.Fork =>
                    val zio = curZio.asInstanceOf[ZIO.Fork[Any, Any, Any]]

                    curZio = nextInstr(fork(zio.value, zio.scope())(zio.trace))

                  case ZIO.Tags.Descriptor =>
                    val zio = curZio.asInstanceOf[ZIO.Descriptor[Any, Any, Any]]

                    val k = zio.k

                    curZio = k(getDescriptor())

                  case ZIO.Tags.Shift =>
                    val zio = curZio.asInstanceOf[ZIO.Shift]

                    val executor = zio.executor()

                    if (executor eq null) {
                      currentLocked = false
                      curZio = ZIO.unit
                    } else {
                      currentLocked = true
                      curZio = if (executor eq currentExecutor) ZIO.unit else shift(executor)(curZio.trace)
                    }

                  case ZIO.Tags.Yield =>
                    evaluateLater(ZIO.unit)

                    curZio = null

                  case ZIO.Tags.Access =>
                    val zio = curZio.asInstanceOf[ZIO.Read[Any, Any, Any]]

                    val k = zio.k

                    curZio = k(currentEnvironment)

                  case ZIO.Tags.Provide =>
                    val zio = curZio.asInstanceOf[ZIO.Provide[Any, Any, Any]]

                    val oldEnvironment = currentEnvironment

                    currentEnvironment = zio.r().asInstanceOf[AnyRef]

                    ensure(ZIO.succeed { currentEnvironment = oldEnvironment }(zio.trace))

                    curZio = zio.zio

                  case ZIO.Tags.Trace =>
                    curZio = nextInstr(captureTrace(null))

                  case ZIO.Tags.FiberRefGetAll =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefGetAll[Any, Any, Any]]

                    curZio = nextInstr(zio.make(fiberRefLocals.get))

                  case ZIO.Tags.FiberRefModify =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefModify[Any, Any]]

                    val (result, newValue) = zio.f(getFiberRefValue(zio.fiberRef))
                    setFiberRefValue(zio.fiberRef, newValue)

                    curZio = nextInstr(result)

                  case ZIO.Tags.FiberRefLocally =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefLocally[Any, Any, E, Any]]

                    val fiberRef = zio.fiberRef

                    val oldValue = getFiberRefValue(fiberRef)

                    setFiberRefValue(fiberRef, zio.localValue)

                    curZio =
                      zio.zio.ensuring(ZIO.succeed(setFiberRefValue(fiberRef, oldValue))(curZio.trace))(curZio.trace)

                  case ZIO.Tags.FiberRefDelete =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefDelete]

                    val fiberRef = zio.fiberRef

                    removeFiberRef(fiberRef)

                    curZio = nextInstr(())

                  case ZIO.Tags.RaceWith =>
                    val zio = curZio.asInstanceOf[ZIO.RaceWith[Any, Any, Any, Any, Any, Any, Any]]
                    curZio = raceWithImpl(zio)(zio.trace).asInstanceOf[Erased]

                  case ZIO.Tags.Supervise =>
                    val zio = curZio.asInstanceOf[ZIO.Supervise[Any, Any, Any]]

                    val oldSupervisor = runtimeConfig.supervisor
                    val newSupervisor = zio.supervisor() ++ oldSupervisor

                    runtimeConfig = runtimeConfig.copy(supervisor = newSupervisor)

                    ensure(ZIO.succeed { runtimeConfig = runtimeConfig.copy(supervisor = oldSupervisor) }(zio.trace))

                    curZio = zio.zio

                  case ZIO.Tags.GetForkScope =>
                    val zio = curZio.asInstanceOf[ZIO.GetForkScope[Any, Any, Any]]

                    curZio = zio.f(currentForkScopeOverride.getOrElse(scope))

                  case ZIO.Tags.OverrideForkScope =>
                    val zio = curZio.asInstanceOf[ZIO.OverrideForkScope[Any, Any, Any]]

                    val oldForkScopeOverride = currentForkScopeOverride

                    currentForkScopeOverride = zio.forkScope()

                    ensure(ZIO.succeed { currentForkScopeOverride = oldForkScopeOverride }(zio.trace))

                    curZio = zio.zio

                  case ZIO.Tags.Ensuring =>
                    val zio = curZio.asInstanceOf[ZIO.Ensuring[Any, Any, Any]]

                    ensure(zio.finalizer())

                    curZio = zio.zio

                  case ZIO.Tags.Logged =>
                    val zio = curZio.asInstanceOf[ZIO.Logged]

                    log(zio.message, zio.overrideLogLevel, zio.overrideRef1, zio.overrideValue1, zio.trace)

                    curZio = nextInstr(())

                  case ZIO.Tags.SetRuntimeConfig =>
                    val zio = curZio.asInstanceOf[ZIO.SetRuntimeConfig]

                    runtimeConfig = zio.runtimeConfig()

                    curZio = ZIO.unit
                }
              }
            } else {
              // Fiber was interrupted
              curZio = ZIO.failCause(clearSuppressedCause())(curZio.trace)

              // Prevent interruption of interruption:
              setInterrupting(true)
            }

            opCount = opCount + 1

            (curZio ne null)
          }) {}
        } catch {
          case _: InterruptedException =>
            // Reset thread interrupt status and interrupt with zero fiber id:
            Thread.interrupted()
            val trace = curZio.trace
            curZio = ZIO.interruptAs(FiberId.None)(trace)

          case ZIO.ZioError(exit) =>
            exit match {
              case Exit.Success(value) =>
                curZio = nextInstr(value)
              case Exit.Failure(cause) =>
                val trace = curZio.trace
                curZio = ZIO.failCause(cause)(trace)
            }

          // Catastrophic error handler. Any error thrown inside the interpreter is
          // either a bug in the interpreter or a bug in the user's code. Let the
          // fiber die but attempt finalization & report errors.
          case t: Throwable =>
            curZio = if (runtimeConfig.fatal(t)) {
              fatal.set(true)
              runtimeConfig.reportFatal(t)
            } else {
              setInterrupting(true)

              ZIO.die(t)(Tracer.newTrace)
            }
        }
      }
    } finally if (runtimeConfig.enableCurrentFiber) Fiber._currentFiber.remove()

  private def addExecutionTrace(trace: ZTraceElement) =
    if (execTrace.lastOrNull ne trace)
      execTrace.put(trace)

  private[this] def shift(executor: zio.Executor)(implicit trace: ZTraceElement): UIO[Unit] =
    ZIO.succeed { currentExecutor = executor } *> ZIO.yieldNow

  private[this] def getDescriptor(): Fiber.Descriptor =
    Fiber.Descriptor(
      fiberId,
      state.get.status,
      state.get.interruptors,
      InterruptStatus.fromBoolean(isInterruptible()),
      executor,
      currentLocked,
      scope
    )

  private[this] def ensure(finalizer: UIO[Any]): Unit =
    stack.push(new Finalizer(finalizer))

  private[this] def restoreInterrupt()(implicit trace: ZTraceElement): Unit =
    stack.push(new InterruptExit())

  /**
   * Forks an `IO` with the specified failure handler.
   */
  def fork[E, A](
    zio: IO[E, A],
    forkScope: Option[ZScope[Exit[Any, Any]]] = None
  )(implicit trace: ZTraceElement): FiberContext[E, A] = {
    val childFiberRefLocals: Map[FiberRef.Runtime[_], AnyRef] = fiberRefLocals.get.transform { case (fiberRef, value) =>
      fiberRef.fork(value.asInstanceOf[fiberRef.ValueType]).asInstanceOf[AnyRef]
    }

    val tracingRegion = inTracingRegion
    val ancestry =
      if ((traceExec || traceStack) && tracingRegion) Some(cutAncestryTrace(captureTrace(null)))
      else None

    val parentScope = (forkScope orElse currentForkScopeOverride).getOrElse(scope)

    val currentEnv = currentEnvironment

    val childId = Fiber.newFiberId()

    val childScope = ZScope.unsafeMake[Exit[E, A]]()

    val childContext = new FiberContext[E, A](
      childId,
      runtimeConfig,
      currentEnv,
      currentExecutor,
      currentLocked,
      InterruptStatus.fromBoolean(interruptStatus.peekOrElse(true)),
      ancestry,
      tracingRegion,
      new AtomicReference(childFiberRefLocals),
      childScope
    )

    if (runtimeConfig.supervisor ne Supervisor.none) {
      runtimeConfig.supervisor.unsafeOnStart(currentEnv, zio, Some(self), childContext)

      childContext.onDone(exit => runtimeConfig.supervisor.unsafeOnEnd(exit.flatten, childContext))
    }

    val childZio = if (parentScope ne ZScope.global) {
      // Create a weak reference to the child fiber, so that we don't prevent it
      // from being garbage collected:
      val childContextRef = Platform.newWeakReference[FiberContext[E, A]](childContext)

      // Ensure that when the fiber's parent scope ends, the child fiber is
      // interrupted, but do so using a weak finalizer, which will be removed
      // as soon as the key is garbage collected:
      val exitOrKey = parentScope.unsafeEnsure(
        exit =>
          UIO.suspendSucceed {
            val childContext = childContextRef()

            if (childContext ne null) {
              val interruptors = exit.fold(_.interruptors, _ => Set.empty)

              childContext.interruptAs(interruptors.headOption.getOrElse(fiberId))
            } else ZIO.unit
          },
        ZScope.Mode.Weak
      )

      exitOrKey.fold(
        exit => {
          // If the parent scope is closed, then the child is immediate self-interruption.
          // We try to carry along the fiber who performed the interruption (whoever interrupted us,
          // or us, if that information is not available):
          val interruptor = exit match {
            case Exit.Failure(cause) => cause.interruptors.headOption.getOrElse(fiberId)
            case Exit.Success(_)     => fiberId
          }
          ZIO.interruptAs(interruptor)
        },
        key => {
          // Add the finalizer key to the child fiber, so that if it happens to
          // be garbage collected, then its finalizer will be garbage collected
          // too:
          childContext.scopeKey = key

          // Remove the finalizer key from the parent scope when the child
          // fiber terminates:
          childContext.onDone(_ => parentScope.unsafeDeny(key))

          zio
        }
      )
    } else zio

    childContext.nextEffect = childZio
    executor.unsafeSubmitOrThrow(childContext)

    childContext
  }

  private[this] def evaluateLater(zio: Erased): Unit = {
    nextEffect = zio
    executor.unsafeSubmitOrThrow(this)
  }

  private[this] def resumeAsync(epoch: Long)(implicit trace: ZTraceElement): Erased => Unit = { zio =>
    if (exitAsync(epoch)) evaluateLater(zio)
  }

  final def interruptAs(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, A]] = kill0(fiberId)

  def await(implicit trace: ZTraceElement): UIO[Exit[E, A]] =
    ZIO.asyncInterrupt[Any, Nothing, Exit[E, A]](
      { k =>
        val cb: Callback[Nothing, Exit[E, A]] = x => k(ZIO.done(x))
        val result                            = register0(cb)

        if (result eq null) Left(ZIO.succeed(interruptObserver(cb)))
        else Right(ZIO.succeedNow(result))
      },
      fiberId
    )

  @tailrec
  private[this] def interruptObserver(k: Callback[Nothing, Exit[E, A]]): Unit = {
    val oldState = state.get
    oldState match {
      case Executing(status, observers0, interrupted, interruptors, asyncCanceler) =>
        val observers = observers0.filter(_ ne k)
        if (!state.compareAndSet(oldState, Executing(status, observers, interrupted, interruptors, asyncCanceler)))
          interruptObserver(k)
      case _ =>
    }
  }

  def getRef[A](ref: FiberRef.Runtime[A])(implicit trace: ZTraceElement): UIO[A] = UIO {
    val oldValue = fiberRefLocals.get.get(ref).asInstanceOf[Option[A]]

    oldValue.getOrElse(ref.initial)
  }

  def getFiberRefValue[A](fiberRef: FiberRef.Runtime[A]): A =
    fiberRefLocals.get.get(fiberRef).asInstanceOf[Option[A]].getOrElse(fiberRef.initial)

  @tailrec
  def setFiberRefValue[A](fiberRef: FiberRef.Runtime[A], value: A): Unit = {
    val oldState = fiberRefLocals.get

    if (!fiberRefLocals.compareAndSet(oldState, oldState.updated(fiberRef, value.asInstanceOf[AnyRef])))
      setFiberRefValue(fiberRef, value)
    else ()
  }

  @tailrec
  def removeFiberRef[A](fiberRef: FiberRef.Runtime[A]): Unit = {
    val oldState = fiberRefLocals.get

    if (!fiberRefLocals.compareAndSet(oldState, oldState - fiberRef)) removeFiberRef(fiberRef)
    else ()
  }

  def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E, A]]] = ZIO.succeed(poll0)

  def id: FiberId = fiberId

  def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] = UIO.suspendSucceed {
    val locals = fiberRefLocals.get

    if (locals.isEmpty) UIO.unit
    else
      UIO.foreachDiscard(locals) { case (fiberRef, value) =>
        val ref = fiberRef.asInstanceOf[FiberRef.Runtime[Any]]
        ref.update(old => ref.join(old, value))
      }
  }

  def scope: ZScope[Exit[E, A]] = openScope.scope

  def status(implicit trace: ZTraceElement): UIO[Fiber.Status] = UIO(state.get.status)

  def trace(implicit trace0: ZTraceElement): UIO[ZTrace] = UIO(captureTrace(null))

  @tailrec
  private[this] def enterAsync(
    epoch: Long,
    register: AnyRef,
    blockingOn: FiberId
  )(implicit trace: ZTraceElement): Unit = {
    val oldState = state.get

    oldState match {
      case Executing(status, observers, interrupt, interruptors, CancelerState.Empty) =>
        val asyncTrace = if (traceStack && inTracingRegion) Some(trace) else None

        val newStatus = Status.Suspended(status, isInterruptible() && !isInterrupting(), epoch, blockingOn, asyncTrace)

        val newState = Executing(newStatus, observers, interrupt, interruptors, CancelerState.Pending)

        if (!state.compareAndSet(oldState, newState)) enterAsync(epoch, register, blockingOn)

      case _ =>
    }
  }

  @tailrec
  private[this] def exitAsync(epoch: Long)(implicit trace: ZTraceElement): Boolean = {
    val oldState = state.get

    oldState match {
      case Executing(Status.Suspended(status, _, oldEpoch, _, _), observers, suppressed, interruptors, _)
          if epoch == oldEpoch =>
        if (!state.compareAndSet(oldState, Executing(status, observers, suppressed, interruptors, CancelerState.Empty)))
          exitAsync(epoch)
        else true

      case _ => false
    }
  }

  @inline
  private def isInterrupted(): Boolean = state.get.interruptors.nonEmpty

  @inline
  private[this] def isInterruptible(): Boolean = interruptStatus.peekOrElse(true)

  @inline
  private[this] def isInterrupting(): Boolean = state.get().isInterrupting

  @inline
  private[this] final def shouldInterrupt(): Boolean =
    isInterrupted() && isInterruptible() && !isInterrupting()

  @tailrec
  private[this] def addSuppressedCause(cause: Cause[Nothing]): Unit =
    if (!cause.isEmpty) {
      val oldState = state.get

      oldState match {
        case Executing(status, observers, suppressed, interruptors, asyncCanceler) =>
          val newState = Executing(status, observers, suppressed ++ cause, interruptors, asyncCanceler)

          if (!state.compareAndSet(oldState, newState)) addSuppressedCause(cause)

        case _ =>
      }
    }

  @tailrec
  private[this] def clearSuppressedCause(): Cause[Nothing] = {
    val oldState = state.get

    oldState match {
      case Executing(status, observers, suppressed, interruptors, asyncCanceler) =>
        val newState = Executing(status, observers, Cause.empty, interruptors, asyncCanceler)

        if (!state.compareAndSet(oldState, newState)) clearSuppressedCause()
        else suppressed

      case _ => Cause.empty
    }
  }

  @inline
  private[this] def nextInstr(value: Any): Erased =
    if (!stack.isEmpty) {
      val k = stack.pop()

      if (traceExec && currentTracingStatus)
        addExecutionTrace(k.trace.asInstanceOf[ZTraceElement])

      k(value).asInstanceOf[IO[E, Any]]
    } else done(Exit.succeed(value.asInstanceOf[A]))(Tracer.newTrace)

  @tailrec
  private[this] def setInterrupting(value: Boolean): Unit = {
    val oldState = state.get

    oldState match {
      case Executing(
            status,
            observers: List[Callback[Nothing, Exit[E, A]]],
            interrupted,
            interruptors,
            asyncCanceler
          ) => // TODO: Dotty doesn't infer this properly
        if (
          !state.compareAndSet(
            oldState,
            Executing(status.withInterrupting(value), observers, interrupted, interruptors, asyncCanceler)
          )
        )
          setInterrupting(value)

      case _ =>
    }
  }

  def name: Option[String] = fiberRefLocals.get.get(Fiber.fiberName).map(_.asInstanceOf[String])

  override def toString(): String =
    s"FiberContext($fiberId, $name)"

  @tailrec
  private[this] def done(exit: Exit[E, A])(implicit trace: ZTraceElement): IO[E, Any] = {
    val oldState = state.get

    oldState match {
      case Executing(
            _,
            observers: List[Callback[Nothing, Exit[E, A]]],
            _,
            _,
            _
          ) => // TODO: Dotty doesn't infer this properly

        if (openScope.scope.unsafeIsClosed()) {
          val interruptorsCause = oldState.interruptorsCause

          val newExit =
            if (interruptorsCause eq Cause.empty) exit
            else
              exit.mapErrorCause { cause =>
                if (cause.contains(interruptorsCause)) cause
                else cause ++ interruptorsCause
              }

          //  We are truly "done" because the scope has been closed.
          if (!state.compareAndSet(oldState, Done(newExit))) done(exit)
          else {
            reportUnhandled(newExit, trace)
            notifyObservers(newExit, observers)

            null
          }
        } else {
          // We aren't quite done yet, because we have to close the fiber's scope:
          setInterrupting(true)
          openScope.close(exit) *> ZIO.done(exit)
        }

      case Done(_) => null // Already done
    }
  }

  private[this] def reportUnhandled(v: Exit[E, A], trace: ZTraceElement): Unit = v match {
    case Exit.Failure(cause) => log(() => cause.prettyPrint, ZIO.someDebug, trace = trace)
    case _                   =>
  }

  @tailrec
  private[this] def setAsyncCanceler(epoch: Long, asyncCanceler0: ZIO[Any, Any, Any]): Unit = {
    val oldState      = state.get
    val asyncCanceler = if (asyncCanceler0 eq null) ZIO.unit else asyncCanceler0

    oldState match {
      case Executing(
            status @ Status.Suspended(_, _, oldEpoch, _, _),
            observers,
            suppressed,
            interruptors,
            CancelerState.Pending
          ) if epoch == oldEpoch =>
        val newState = Executing(status, observers, suppressed, interruptors, CancelerState.Registered(asyncCanceler))

        if (!state.compareAndSet(oldState, newState)) setAsyncCanceler(epoch, asyncCanceler)

      case Executing(_, _, _, _, CancelerState.Empty) =>

      case Executing(
            Status.Suspended(_, _, oldEpoch, _, _),
            _,
            _,
            _,
            CancelerState.Registered(_)
          ) if epoch == oldEpoch =>
        throw new Exception("inconsistent state in setAsyncCanceler")

      case _ =>
    }
  }

  private[this] def kill0(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, A]] = {
    val interruptedCause = Cause.interrupt(fiberId)

    @tailrec
    def setInterruptedLoop(): Unit = {
      val oldState = state.get

      oldState match {
        case Executing(
              Status.Suspended(oldStatus, true, _, _, _),
              observers,
              suppressed,
              interruptors,
              CancelerState.Registered(asyncCanceler)
            ) =>
          val newState =
            Executing(
              oldStatus.withInterrupting(true),
              observers,
              suppressed,
              interruptors + fiberId,
              CancelerState.Empty
            )

          if (!state.compareAndSet(oldState, newState)) setInterruptedLoop()
          else {
            val interrupt = ZIO.failCause(interruptedCause)

            val effect =
              if (asyncCanceler eq ZIO.unit) interrupt else asyncCanceler *> interrupt

            // if we are in this critical section of code then we return
            evaluateLater(effect)
          }

        case Executing(status, observers, interrupted, interruptors, asyncCanceler) =>
          val newCause = interrupted ++ interruptedCause

          if (
            !state.compareAndSet(
              oldState,
              Executing(status, observers, newCause, interruptors + fiberId, asyncCanceler)
            )
          )
            setInterruptedLoop()

        case _ =>
      }
    }

    UIO.suspendSucceed {
      setInterruptedLoop()

      await
    }
  }

  @tailrec
  private[zio] def onDone(k: Callback[Nothing, Exit[E, A]]): Unit = {
    val oldState = state.get

    oldState match {
      case Executing(status, observers0, interrupt, interruptors, asyncCanceler) =>
        val observers = k :: observers0

        if (!state.compareAndSet(oldState, Executing(status, observers, interrupt, interruptors, asyncCanceler)))
          onDone(k)

      case Done(v) => k(Exit.succeed(v)); ()
    }
  }

  @tailrec
  private def register0(k: Callback[Nothing, Exit[E, A]]): Exit[E, A] = {
    val oldState = state.get

    oldState match {
      case Executing(status, observers0, interrupt, interruptors, asyncCanceler) =>
        val observers = k :: observers0

        if (!state.compareAndSet(oldState, Executing(status, observers, interrupt, interruptors, asyncCanceler)))
          register0(k)
        else null

      case Done(v) => v
    }
  }

  private[this] def poll0: Option[Exit[E, A]] =
    state.get match {
      case Done(r) => Some(r)
      case _       => None
    }

  private[this] def notifyObservers(
    v: Exit[E, A],
    observers: List[Callback[Nothing, Exit[E, A]]]
  ): Unit =
    if (observers.nonEmpty) {
      val result = Exit.succeed(v)
      observers.foreach(k => k(result))
    }

  private[this] def log(
    message: () => String,
    overrideLogLevel: Option[LogLevel],
    overrideRef1: FiberRef.Runtime[_] = null,
    overrideValue1: AnyRef = null,
    trace: ZTraceElement
  ): Unit = {
    val logLevel = overrideLogLevel match {
      case Some(level) => level
      case _           => getFiberRefValue(FiberRef.currentLogLevel)
    }

    val spans = getFiberRefValue(FiberRef.currentLogSpan)

    val contextMap =
      if (overrideRef1 ne null) {
        val map = fiberRefLocals.get

        if (overrideValue1 eq null) map - overrideRef1
        else map.updated(overrideRef1, overrideValue1)
      } else fiberRefLocals.get

    runtimeConfig.logger(trace, fiberId, logLevel, message, contextMap, spans)

    ()
  }
}
private[zio] object FiberContext {
  sealed abstract class FiberState[+E, +A] extends Serializable with Product {
    def suppressed: Cause[Nothing]
    def status: Fiber.Status
    def isInterrupting: Boolean = status.isInterrupting
    def interruptors: Set[FiberId]
    def interruptorsCause: Cause[Nothing] =
      interruptors.foldLeft[Cause[Nothing]](Cause.empty) { case (acc, interruptor) =>
        acc ++ Cause.interrupt(interruptor)
      }
  }
  object FiberState extends Serializable {
    final case class Executing[E, A](
      status: Fiber.Status,
      observers: List[Callback[Nothing, Exit[E, A]]],
      suppressed: Cause[Nothing],
      interruptors: Set[FiberId],
      asyncCanceler: CancelerState
    ) extends FiberState[E, A]
    final case class Done[E, A](value: Exit[E, A]) extends FiberState[E, A] {
      def suppressed: Cause[Nothing] = Cause.empty
      def status: Fiber.Status       = Status.Done
      def interruptors: Set[FiberId] = Set.empty
    }

    def initial[E, A]: Executing[E, A] =
      Executing[E, A](Status.Running(false), Nil, Cause.empty, Set.empty[FiberId], CancelerState.Empty)
  }

  sealed abstract class CancelerState

  object CancelerState {
    case object Empty                                        extends CancelerState
    case object Pending                                      extends CancelerState
    case class Registered(asyncCanceler: ZIO[Any, Any, Any]) extends CancelerState
  }

  type FiberRefLocals = AtomicReference[Map[FiberRef.Runtime[_], AnyRef]]

  val fatal: AtomicBoolean =
    new AtomicBoolean(false)
}
