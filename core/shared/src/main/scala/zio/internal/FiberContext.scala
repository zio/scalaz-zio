/*
 * Copyright 2017-2022 John A. De Goes and the ZIO Contributors
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
import zio.ZFiberRef.{currentEnvironment, currentExecutor, forkScopeOverride}
import zio.ZIO.{FlatMap, TracedCont}
import zio._
import zio.internal.FiberContext.FiberRefLocals
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.{Set => JavaSet}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.annotation.{switch, tailrec}
import izumi.reflect.macrortti.LightTypeTag

/**
 * An implementation of Fiber that maintains context necessary for evaluation.
 */
private[zio] final class FiberContext[E, A](
  val fiberId: FiberId.Runtime,
  var runtimeConfig: RuntimeConfig,
  val interruptStatus: StackBool,
  val fiberRefLocals: FiberRefLocals,
  val _children: JavaSet[FiberContext[_, _]]
) extends Fiber.Runtime.Internal[E, A]
    with FiberRunnable { self =>
  import FiberContext.{erase, eraseK, eraseR, Erased, ErasedCont, ErasedTracedCont}

  import FiberContext._
  import FiberState._

  if (trackMetrics) fibersStarted.unsafeIncrement()
  if (trackMetrics) fiberForkLocations.unsafeObserve(location.toString)

  // Accessed from multiple threads:
  private val state = new AtomicReference[FiberState[E, A]](FiberState.initial)

  @volatile
  private[this] var asyncEpoch: Long = 0L

  private[this] val stack = Stack[ErasedTracedCont]()

  @volatile private[zio] var nextEffect: ZIO[_, _, _] = null

  final def await(implicit trace: ZTraceElement): UIO[Exit[E, A]] =
    ZIO.asyncInterrupt[Any, Nothing, Exit[E, A]](
      { k =>
        val cb: Callback[Nothing, Exit[E, A]] = x => k(ZIO.done(x))
        val result                            = unsafeAddObserverMaybe(cb)

        if (result eq null) Left(ZIO.succeed(unsafeRemoveObserver(cb)))
        else Right(ZIO.succeedNow(result))
      },
      fiberId
    )

  final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] =
    evalOnZIO(
      UIO {
        val chunkBuilder = ChunkBuilder.make[Fiber.Runtime[_, _]](_children.size)

        val iterator = _children.iterator()

        while (iterator.hasNext()) {
          chunkBuilder += iterator.next()
        }

        chunkBuilder.result()
      },
      UIO(Chunk.empty)
    )

  final def evalOn(effect: zio.UIO[Any], orElse: UIO[Any])(implicit trace: ZTraceElement): UIO[Unit] =
    UIO.suspendSucceed {
      if (unsafeEvalOn(effect)) ZIO.unit else orElse.unit
    }

  final def getRef[A](ref: FiberRef.Runtime[A])(implicit trace: ZTraceElement): UIO[A] =
    UIO(unsafeGetRef(ref))

  final def id: FiberId.Runtime = fiberId

  final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] = UIO.suspendSucceed {
    val locals = fiberRefLocals.get

    if (locals.isEmpty) UIO.unit
    else
      UIO.foreachDiscard(locals) { case (fiberRef, value) =>
        val ref = fiberRef.asInstanceOf[FiberRef.Runtime[Any]]
        ref.update(old => ref.join(old, value))
      }
  }

  final def interruptAs(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, A]] = unsafeInterruptAs(fiberId)

  final def location: ZTraceElement = fiberId.location

  final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E, A]]] = ZIO.succeed(unsafePoll)

  override final def run(): Unit = runUntil(unsafeGetExecutor().yieldOpCount)

  /**
   * The main evaluator loop for the fiber. For purely synchronous effects, this
   * will run either to completion, or for the specified maximum operation
   * count. For effects with asynchronous callbacks, the loop will proceed no
   * further than the first asynchronous boundary.
   */
  override final def runUntil(maxOpCount: Int): Unit =
    try {
      val logRuntime = runtimeConfig.flags.isEnabled(RuntimeConfigFlag.LogRuntime)

      // Do NOT accidentally capture `curZio` in a closure, or Scala will wrap
      // it in `ObjectRef` and performance will plummet.
      var curZio = erase(nextEffect)

      nextEffect = null

      // Put the stack reference on the stack:
      val stack = this.stack

      val emptyTraceElement = ZTraceElement.empty

      // Store the trace of the immediate future flatMap during evaluation
      // of a 1-hop left bind, to show a stack trace closer to the point of failure
      var extraTrace: ZTraceElement = emptyTraceElement

      import RuntimeConfigFlag._
      val flags = runtimeConfig.flags
      val superviseOps =
        flags.isEnabled(SuperviseOperations) &&
          (runtimeConfig.supervisor ne Supervisor.none)

      if (flags.isEnabled(EnableCurrentFiber)) Fiber._currentFiber.set(this)
      if (runtimeConfig.supervisor ne Supervisor.none) runtimeConfig.supervisor.unsafeOnResume(self)

      while (curZio ne null) {
        try {
          var opCount: Int = 0

          while ({
            val tag = curZio.tag

            // Check to see if the fiber should continue executing or not:
            if (!unsafeShouldInterrupt()) {
              // Fiber does not need to be interrupted, but might need to yield:
              val message = unsafeDrainMailbox()

              if (message ne null) {
                val oldZio = curZio

                curZio = message.flatMap(_ => oldZio)(oldZio.trace)
              } else if (opCount == maxOpCount) {
                unsafeRunLater(curZio)
                curZio = null
              } else {
                if (logRuntime) {
                  val trace = curZio.trace

                  unsafeLog(ZLogger.stringTag, curZio.unsafeLog)(trace)
                }
                if (superviseOps) runtimeConfig.supervisor.unsafeOnEffect(self, curZio)

                // Fiber is neither being interrupted nor needs to yield. Execute
                // the next instruction in the program:
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

                        curZio = k(io2.value)

                      case ZIO.Tags.Succeed =>
                        val io2 = nested.asInstanceOf[ZIO.Succeed[Any]]

                        extraTrace = zio.trace
                        val value = io2.effect()
                        extraTrace = emptyTraceElement

                        curZio = k(value)

                      case ZIO.Tags.SucceedWith =>
                        val io2    = nested.asInstanceOf[ZIO.SucceedWith[Any]]
                        val effect = io2.effect

                        extraTrace = zio.trace
                        val value = effect(runtimeConfig, fiberId)
                        extraTrace = emptyTraceElement

                        curZio = k(value)

                      case ZIO.Tags.Yield =>
                        extraTrace = zio.trace
                        unsafeRunLater(k(()))
                        extraTrace = emptyTraceElement

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

                    curZio = unsafeNextEffect(value)

                  case ZIO.Tags.Succeed =>
                    val zio    = curZio.asInstanceOf[ZIO.Succeed[Any]]
                    val effect = zio.effect

                    curZio = unsafeNextEffect(effect())

                  case ZIO.Tags.SucceedWith =>
                    val zio    = curZio.asInstanceOf[ZIO.SucceedWith[Any]]
                    val effect = zio.effect

                    curZio = unsafeNextEffect(effect(runtimeConfig, fiberId))

                  case ZIO.Tags.Fail =>
                    val zio = curZio.asInstanceOf[ZIO.Fail[Any]]

                    val fastPathTrace = if (extraTrace == emptyTraceElement) Nil else extraTrace :: Nil
                    extraTrace = emptyTraceElement

                    val cause = zio.cause
                    val tracedCause =
                      if (cause.isTraced) cause
                      else cause.traced(unsafeCaptureTrace(zio.trace :: fastPathTrace))

                    val discardedFolds = unsafeUnwindStack()
                    val strippedCause =
                      if (discardedFolds)
                        // We threw away some error handlers while unwinding the stack because
                        // we got interrupted during this instruction. So it's not safe to return
                        // typed failures from cause0, because they might not be typed correctly.
                        // Instead, we strip the typed failures, and return the remainders and
                        // the interruption.
                        tracedCause.stripFailures
                      else
                        tracedCause
                    val suppressed = unsafeClearSuppressed()
                    val fullCause =
                      if (strippedCause.contains(suppressed)) strippedCause else strippedCause ++ suppressed

                    curZio = if (stack.isEmpty) {
                      // Error not caught, stack is empty:
                      unsafeSetInterrupting(true)

                      unsafeTryDone(Exit.failCause(fullCause.asInstanceOf[Cause[E]]))(zio.trace)
                    } else {
                      unsafeSetInterrupting(false)

                      // Error caught, next continuation on the stack will deal
                      // with it, so we just have to compute it here:
                      unsafeNextEffect(fullCause)
                    }

                  case ZIO.Tags.Fold =>
                    val zio = curZio.asInstanceOf[ZIO.Fold[Any, Any, Any, Any, Any]]

                    curZio = zio.zio

                    stack.push(zio)

                  case ZIO.Tags.Suspend =>
                    val zio = curZio.asInstanceOf[ZIO.Suspend[Any, Any, Any]]

                    curZio = zio.make()

                  case ZIO.Tags.SuspendWith =>
                    val zio = curZio.asInstanceOf[ZIO.SuspendWith[Any, Any, Any]]

                    curZio = zio.make(runtimeConfig, fiberId)

                  case ZIO.Tags.InterruptStatus =>
                    val zio = curZio.asInstanceOf[ZIO.InterruptStatus[Any, Any, Any]]

                    val boolFlag = zio.flag.toBoolean

                    if (interruptStatus.peekOrElse(true) != boolFlag) {
                      interruptStatus.push(boolFlag)

                      unsafeRestoreInterrupt()(zio.trace)
                    }

                    curZio = zio.zio

                  case ZIO.Tags.CheckInterrupt =>
                    val zio = curZio.asInstanceOf[ZIO.CheckInterrupt[Any, Any, Any]]

                    curZio = zio.k(InterruptStatus.fromBoolean(unsafeIsInterruptible()))

                  case ZIO.Tags.Async =>
                    val zio                           = curZio.asInstanceOf[ZIO.Async[Any, Any, Any]]
                    implicit val trace: ZTraceElement = zio.trace

                    val epoch = asyncEpoch
                    asyncEpoch = epoch + 1

                    // Enter suspended state:
                    unsafeEnterAsync(epoch, zio.register, zio.blockingOn)

                    val k = zio.register

                    curZio = k(unsafeCreateAsyncResume(epoch)) match {
                      case Left(canceler) =>
                        unsafeSetAsyncCanceler(epoch, canceler)
                        if (unsafeShouldInterrupt()) {
                          if (unsafeExitAsync(epoch)) {
                            unsafeSetInterrupting(true)
                            canceler *> ZIO.failCause(unsafeClearSuppressed())
                          } else null
                        } else null
                      case Right(zio) =>
                        if (!unsafeExitAsync(epoch)) null else zio.asInstanceOf[Erased]
                    }

                  case ZIO.Tags.Fork =>
                    val zio = curZio.asInstanceOf[ZIO.Fork[Any, Any, Any]]

                    curZio = unsafeNextEffect(unsafeFork(zio.zio, zio.scope)(zio.trace))

                  case ZIO.Tags.Descriptor =>
                    val zio = curZio.asInstanceOf[ZIO.Descriptor[Any, Any, Any]]

                    val k = zio.k

                    curZio = k(unsafeGetDescriptor(zio.trace))

                  case ZIO.Tags.Shift =>
                    val zio      = curZio.asInstanceOf[ZIO.Shift]
                    val executor = zio.executor

                    def doShift(implicit trace: ZTraceElement): UIO[Unit] =
                      ZIO.succeed(unsafeSetRef(currentExecutor, Some(executor))) *> ZIO.yieldNow

                    curZio = if (executor eq null) {
                      unsafeSetRef(currentExecutor, None)
                      ZIO.unit
                    } else {
                      unsafeGetRef(currentExecutor) match {
                        case None =>
                          doShift(zio.trace)

                        case Some(currentExecutor) =>
                          if (executor eq currentExecutor) ZIO.unit
                          else doShift(zio.trace)
                      }
                    }

                  case ZIO.Tags.Yield =>
                    unsafeRunLater(ZIO.unit)

                    curZio = null

                  case ZIO.Tags.Trace =>
                    val zio = curZio.asInstanceOf[ZIO.Trace]

                    curZio = unsafeNextEffect(unsafeCaptureTrace(zio.trace :: Nil))

                  case ZIO.Tags.FiberRefGetAll =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefGetAll[Any, Any, Any]]

                    curZio = zio.make(fiberRefLocals.get)

                  case ZIO.Tags.FiberRefModify =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefModify[Any, Any]]

                    val (result, newValue) = zio.f(unsafeGetRef(zio.fiberRef))
                    unsafeSetRef(zio.fiberRef, newValue)

                    curZio = unsafeNextEffect(result)

                  case ZIO.Tags.FiberRefLocally =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefLocally[Any, Any, E, Any]]

                    val fiberRef = zio.fiberRef

                    val oldValue = unsafeGetRef(fiberRef)

                    unsafeSetRef(fiberRef, zio.localValue)

                    curZio = zio.zio.ensuring(ZIO.succeed(unsafeSetRef(fiberRef, oldValue))(zio.trace))(zio.trace)

                  case ZIO.Tags.FiberRefDelete =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefDelete]

                    val fiberRef = zio.fiberRef

                    unsafeDeleteRef(fiberRef)

                    curZio = unsafeNextEffect(())

                  case ZIO.Tags.FiberRefWith =>
                    val zio = curZio.asInstanceOf[ZIO.FiberRefWith[Any, Any, Any, Any]]
                    curZio = zio.f(unsafeGetRef(zio.fiberRef))

                  case ZIO.Tags.RaceWith =>
                    val zio = curZio.asInstanceOf[ZIO.RaceWith[Any, Any, Any, Any, Any, Any, Any]]
                    curZio = unsafeRace(zio)(zio.trace).asInstanceOf[Erased]

                  case ZIO.Tags.Supervise =>
                    val zio = curZio.asInstanceOf[ZIO.Supervise[Any, Any, Any]]

                    val oldSupervisor = runtimeConfig.supervisor
                    val newSupervisor = zio.supervisor ++ oldSupervisor

                    runtimeConfig = runtimeConfig.copy(supervisor = newSupervisor)

                    unsafeAddFinalizer(ZIO.succeed {
                      runtimeConfig = runtimeConfig.copy(supervisor = oldSupervisor)
                    }(zio.trace))

                    curZio = zio.zio

                  case ZIO.Tags.GetForkScope =>
                    val zio = curZio.asInstanceOf[ZIO.GetForkScope[Any, Any, Any]]

                    curZio = zio.f(unsafeGetRef(forkScopeOverride).getOrElse(scope))

                  case ZIO.Tags.OverrideForkScope =>
                    val zio = curZio.asInstanceOf[ZIO.OverrideForkScope[Any, Any, Any]]

                    val oldForkScopeOverride = unsafeGetRef(forkScopeOverride)

                    unsafeSetRef(forkScopeOverride, zio.forkScope)

                    unsafeAddFinalizer(ZIO.succeed(unsafeSetRef(forkScopeOverride, oldForkScopeOverride))(zio.trace))

                    curZio = zio.zio

                  case ZIO.Tags.Ensuring =>
                    val zio = curZio.asInstanceOf[ZIO.Ensuring[Any, Any, Any]]

                    unsafeAddFinalizer(zio.finalizer)

                    curZio = zio.zio

                  case ZIO.Tags.Logged =>
                    val zio = curZio.asInstanceOf[ZIO.Logged]

                    unsafeLog(
                      zio.message,
                      zio.cause,
                      zio.overrideLogLevel,
                      zio.overrideRef1,
                      zio.overrideValue1,
                      zio.trace
                    )

                    curZio = unsafeNextEffect(())

                  case ZIO.Tags.SetRuntimeConfig =>
                    val zio = curZio.asInstanceOf[ZIO.SetRuntimeConfig]

                    runtimeConfig = zio.runtimeConfig

                    curZio = ZIO.unit
                }
              }
            } else {
              // Fiber was interrupted
              val trace = curZio.trace

              curZio = ZIO.failCause(unsafeClearSuppressed())(trace)

              // Prevent interruption of interruption:
              unsafeSetInterrupting(true)
            }

            opCount = opCount + 1

            (curZio ne null)
          }) {}
        } catch {
          case _: InterruptedException =>
            // Reset thread interrupt status:
            Thread.interrupted()

            val trace = curZio.trace

            curZio = ZIO.interruptAs(FiberId.None)(trace)

            // Prevent interruption of interruption:
            unsafeSetInterrupting(true)

          case ZIO.ZioError(exit, trace) =>
            exit match {
              case Exit.Success(value) =>
                curZio = unsafeNextEffect(value)

              case Exit.Failure(cause) =>
                val trace = curZio.trace

                curZio = ZIO.failCause(cause)(trace)
            }

          // Catastrophic error handler. Any error thrown inside the interpreter is
          // either a bug in the interpreter or a bug in the user's code. Let the
          // fiber die but attempt finalization & report errors.
          case t: Throwable =>
            curZio = if (runtimeConfig.fatal(t)) {
              catastrophicFailure.set(true)
              runtimeConfig.reportFatal(t)
            } else {
              unsafeSetInterrupting(true)

              ZIO.die(t)(Tracer.newTrace)
            }
        }
      }
    } finally {
      import RuntimeConfigFlag._

      // FIXME: Race condition on fiber resumption
      if (runtimeConfig.flags.isEnabled(EnableCurrentFiber)) Fiber._currentFiber.remove()
      if (runtimeConfig.supervisor ne Supervisor.none) runtimeConfig.supervisor.unsafeOnSuspend(self)
    }

  override def toString(): String =
    s"FiberContext($fiberId)"

  final def scope: ZScope = ZScope.unsafeMake(self)

  final def status(implicit trace: ZTraceElement): UIO[Fiber.Status] = UIO(state.get.status)

  final def trace(implicit trace0: ZTraceElement): UIO[ZTrace] = UIO(unsafeCaptureTrace(Nil))

  private[zio] def unsafeAddChild(child: FiberContext[_, _])(implicit trace: ZTraceElement): Boolean =
    unsafeEvalOn(ZIO.succeed(_children.add(child)))

  private def unsafeAddFinalizer(finalizer: UIO[Any]): Unit = stack.push(new Finalizer(finalizer))

  @tailrec
  private def unsafeAddObserverMaybe(k: Callback[Nothing, Exit[E, A]]): Exit[E, A] = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(_, observers0, _, _, _, _) =>
        val observers = k :: observers0

        if (!state.compareAndSet(oldState, executing.copy(observers = observers)))
          unsafeAddObserverMaybe(k)
        else null

      case Done(v) => v
    }
  }

  @tailrec
  private def unsafeAddSuppressed(cause: Cause[Nothing]): Unit =
    if (!cause.isEmpty) {
      val oldState = state.get

      oldState match {
        case executing @ Executing(_, _, suppressed, _, _, _) =>
          val newState = executing.copy(suppressed = suppressed ++ cause)

          if (!state.compareAndSet(oldState, newState)) unsafeAddSuppressed(cause)

        case _ =>
      }
    }

  private def unsafeCaptureTrace(prefix: List[ZTraceElement]): ZTrace = {
    val builder = StackTraceBuilder.unsafeMake()

    prefix.foreach(builder += _)
    stack.foreach(k => builder += k.trace)

    ZTrace(fiberId, builder.result())
  }

  @tailrec
  private def unsafeClearSuppressed(): Cause[Nothing] = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(_, _, suppressed, _, _, _) =>
        val newState = executing.copy(suppressed = Cause.empty)

        if (!state.compareAndSet(oldState, newState)) unsafeClearSuppressed()
        else {
          val interruptorsCause = oldState.interruptorsCause
          if (suppressed.contains(interruptorsCause)) suppressed
          else suppressed ++ interruptorsCause
        }

      case _ => oldState.interruptorsCause
    }
  }

  private def unsafeCreateAsyncResume(epoch: Long)(implicit trace: ZTraceElement): Erased => Unit = { zio =>
    if (unsafeExitAsync(epoch)) unsafeRunLater(zio)
  }

  @tailrec
  private[zio] def unsafeDeleteRef[A](fiberRef: FiberRef.Runtime[A]): Unit = {
    val oldState = fiberRefLocals.get

    if (!fiberRefLocals.compareAndSet(oldState, oldState - fiberRef)) unsafeDeleteRef(fiberRef)
    else ()
  }

  /**
   * Disables interruption for the fiber.
   */
  private def unsafeDisableInterrupting(): Unit = interruptStatus.push(false)

  @tailrec
  private def unsafeEnterAsync(
    epoch: Long,
    register: AnyRef,
    blockingOn: FiberId
  )(implicit trace: ZTraceElement): Unit = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(status, _, _, _, CancelerState.Empty, _) =>
        val asyncTrace = trace

        val newStatus =
          Status.Suspended(status, unsafeIsInterruptible() && !unsafeIsInterrupting(), epoch, blockingOn, asyncTrace)

        val newState = executing.copy(status = newStatus, asyncCanceler = CancelerState.Pending)

        if (!state.compareAndSet(oldState, newState)) unsafeEnterAsync(epoch, register, blockingOn)

      case _ =>
    }
  }

  @tailrec
  def unsafeDrainMailbox(): UIO[Any] = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(_, _, _, _, _, mailbox) =>
        val newState = executing.copy(mailbox = null.asInstanceOf[UIO[Any]])

        if (!state.compareAndSet(oldState, newState)) unsafeDrainMailbox()
        else mailbox

      case _ => null
    }
  }

  @tailrec
  def unsafeEvalOn(effect: UIO[Any])(implicit trace: ZTraceElement): Boolean = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(_, _, _, _, _, mailbox) =>
        val newMailbox = if (mailbox eq null) effect else mailbox.flatMap(_ => effect)
        val newState   = executing.copy(mailbox = newMailbox)

        if (!state.compareAndSet(oldState, newState)) unsafeEvalOn(effect)
        else true

      case Done(_) => false
    }
  }

  @tailrec
  private def unsafeExitAsync(epoch: Long)(implicit trace: ZTraceElement): Boolean = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(Status.Suspended(status, _, oldEpoch, _, _), _, _, _, _, _) if epoch == oldEpoch =>
        val newState =
          executing.copy(status = status, asyncCanceler = CancelerState.Empty)

        if (!state.compareAndSet(oldState, newState)) unsafeExitAsync(epoch)
        else true

      case _ => false
    }
  }

  /**
   * Forks an `IO` with the specified failure handler.
   */
  def unsafeFork[E, A](
    zio: IO[E, A],
    forkScope: Option[ZScope] = None
  )(implicit trace: ZTraceElement): FiberContext[E, A] = {
    val childFiberRefLocals: Map[FiberRef.Runtime[_], AnyRef] = fiberRefLocals.get.transform { case (fiberRef, value) =>
      fiberRef.fork(value.asInstanceOf[fiberRef.ValueType]).asInstanceOf[AnyRef]
    }

    val parentScope = (forkScope orElse unsafeGetRef(forkScopeOverride)).getOrElse(scope)

    val childId       = FiberId.unsafeMake(trace)
    val grandChildren = Platform.newWeakSet[FiberContext[_, _]]()

    val childContext = new FiberContext[E, A](
      childId,
      runtimeConfig,
      StackBool(interruptStatus.peekOrElse(true)),
      new AtomicReference(childFiberRefLocals),
      grandChildren
    )

    if (runtimeConfig.supervisor ne Supervisor.none) {
      runtimeConfig.supervisor.unsafeOnStart(unsafeGetRef((currentEnvironment)), zio, Some(self), childContext)

      childContext.unsafeOnDone(exit => runtimeConfig.supervisor.unsafeOnEnd(exit.flatten, childContext))
    }

    val childZio =
      if (!parentScope.unsafeAdd(runtimeConfig, childContext)) ZIO.interruptAs(parentScope.fiberId)
      else zio

    childContext.nextEffect = childZio
    if (stack.isEmpty) unsafeGetExecutor().unsafeSubmitAndYieldOrThrow(childContext)
    else unsafeGetExecutor().unsafeSubmitOrThrow(childContext)

    childContext
  }

  private def unsafeGetDescriptor(implicit trace: ZTraceElement): Fiber.Descriptor =
    Fiber.Descriptor(
      fiberId,
      state.get.status,
      state.get.interruptors,
      InterruptStatus.fromBoolean(unsafeIsInterruptible()),
      unsafeGetExecutor(),
      unsafeGetRef(currentExecutor).isDefined,
      scope
    )

  private def unsafeGetExecutor(): zio.Executor =
    unsafeGetRef(currentExecutor).getOrElse(runtimeConfig.executor)

  private[zio] final def unsafeGetRef[A](fiberRef: FiberRef.Runtime[A]): A =
    fiberRefLocals.get.get(fiberRef).asInstanceOf[Option[A]].getOrElse(fiberRef.initial)

  private def unsafeInterruptAs(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, A]] = {
    val interruptedCause = Cause.interrupt(fiberId)

    @tailrec
    def setInterruptedLoop(): Unit = {
      val oldState = state.get

      oldState match {
        case executing @ Executing(
              Status.Suspended(oldStatus, true, _, _, _),
              _,
              _,
              interruptors,
              CancelerState.Registered(asyncCanceler),
              _
            ) =>
          val newState =
            executing.copy(
              status = oldStatus.withInterrupting(true),
              interruptors = interruptors + fiberId,
              asyncCanceler = CancelerState.Empty
            )

          if (!state.compareAndSet(oldState, newState)) setInterruptedLoop()
          else {
            val interrupt = ZIO.failCause(interruptedCause)

            val effect =
              if (asyncCanceler eq ZIO.unit) interrupt else asyncCanceler *> interrupt

            // if we are in this critical section of code then we return
            unsafeRunLater(effect)
          }

        case executing @ Executing(_, _, interrupted, interruptors, _, _) =>
          val newCause = interrupted ++ interruptedCause

          if (
            !state.compareAndSet(
              oldState,
              executing.copy(suppressed = newCause, interruptors = interruptors + fiberId)
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

  @inline
  private def unsafeIsInterrupted(): Boolean = state.get.interruptors.nonEmpty

  @inline
  private def unsafeIsInterruptible(): Boolean = interruptStatus.peekOrElse(true)

  @inline
  private def unsafeIsInterrupting(): Boolean = state.get().isInterrupting

  private def unsafeLog(tag: LightTypeTag, message: () => String)(implicit trace: ZTraceElement): Unit = {
    val logLevel    = unsafeGetRef(FiberRef.currentLogLevel)
    val spans       = unsafeGetRef(FiberRef.currentLogSpan)
    val annotations = unsafeGetRef(FiberRef.currentLogAnnotations)

    runtimeConfig.logger(trace, fiberId, logLevel, message, Cause.empty, fiberRefLocals.get, spans, annotations)
  }

  private def unsafeLog(
    message: () => String,
    cause: Cause[Any],
    overrideLogLevel: Option[LogLevel],
    overrideRef1: FiberRef.Runtime[_] = null,
    overrideValue1: AnyRef = null,
    trace: ZTraceElement
  ): Unit = {
    val logLevel = overrideLogLevel match {
      case Some(level) => level
      case _           => unsafeGetRef(FiberRef.currentLogLevel)
    }

    val spans = unsafeGetRef(FiberRef.currentLogSpan)

    val annotations = unsafeGetRef(FiberRef.currentLogAnnotations)

    val contextMap =
      if (overrideRef1 ne null) {
        val map = fiberRefLocals.get

        if (overrideValue1 eq null) map - overrideRef1
        else map.updated(overrideRef1, overrideValue1)
      } else fiberRefLocals.get

    runtimeConfig.logger(trace, fiberId, logLevel, message, cause, contextMap, spans, annotations)
  }

  @inline
  private def unsafeNextEffect(previousSuccess: Any): Erased =
    if (!stack.isEmpty) {
      val k = stack.pop()

      erase(k(previousSuccess))
    } else unsafeTryDone(Exit.succeed(previousSuccess.asInstanceOf[A]))(Tracer.newTrace)

  private[this] def unsafeNotifyObservers(
    v: Exit[E, A],
    observers: List[Callback[Nothing, Exit[E, A]]]
  ): Unit =
    if (observers.nonEmpty) {
      val result = Exit.succeed(v)
      observers.foreach(k => k(result))
    }

  private[zio] def unsafeOnDone(k: Callback[Nothing, Exit[E, A]]): Unit =
    unsafeAddObserverMaybe(k) match {
      case null => ()
      case exit => k(Exit.succeed(exit)); ()
    }

  private[this] def unsafePoll: Option[Exit[E, A]] =
    state.get match {
      case Done(r) => Some(r)
      case _       => None
    }

  private def unsafeRace[R, EL, ER, E, A, B, C](
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

    val scope = race.scope
    val left  = unsafeFork[EL, A](eraseR(race.left), scope)
    val right = unsafeFork[ER, B](eraseR(race.right), scope)

    ZIO
      .async[R, E, C](
        { cb =>
          val leftRegister = left.unsafeAddObserverMaybe {
            case exit0: Exit.Success[Exit[EL, A]] =>
              complete[EL, ER, A, B](left, right, race.leftWins, exit0.value, raceIndicator, cb)
            case exit: Exit.Failure[_] => complete(left, right, race.leftWins, exit, raceIndicator, cb)
          }

          if (leftRegister ne null)
            complete(left, right, race.leftWins, leftRegister, raceIndicator, cb)
          else {
            val rightRegister = right.unsafeAddObserverMaybe {
              case exit0: Exit.Success[Exit[_, _]] =>
                complete(right, left, race.rightWins, exit0.value, raceIndicator, cb)
              case exit: Exit.Failure[_] => complete(right, left, race.rightWins, exit, raceIndicator, cb)
            }

            if (rightRegister ne null)
              complete(right, left, race.rightWins, rightRegister, raceIndicator, cb)
          }
        },
        FiberId.combineAll(Set(left.fiberId, right.fiberId))
      )
  }

  @tailrec
  private def unsafeRemoveObserver(k: Callback[Nothing, Exit[E, A]]): Unit = {
    val oldState = state.get
    oldState match {
      case executing @ Executing(_, observers0, _, _, _, _) =>
        val observers = observers0.filter(_ ne k)
        if (!state.compareAndSet(oldState, executing.copy(observers = observers)))
          unsafeRemoveObserver(k)
      case _ =>
    }
  }
  private def unsafeReportUnhandled(v: Exit[E, A], trace: ZTraceElement): Unit = v match {
    case Exit.Failure(cause) =>
      try {
        unsafeLog(() => s"Fiber ${fiberId} did not handle an error", cause, ZIO.someDebug, trace = trace)
      } catch {
        case t: Throwable =>
          if (runtimeConfig.fatal(t)) {
            runtimeConfig.reportFatal(t)
          } else {
            println("An exception was thrown by a logger:")
            t.printStackTrace
          }
      }
    case _ =>
  }

  private def unsafeRestoreInterrupt()(implicit trace: ZTraceElement): Unit =
    stack.push(new InterruptExit())

  private def unsafeRunLater(zio: Erased): Unit = {
    nextEffect = zio
    if (stack.isEmpty) unsafeGetExecutor().unsafeSubmitAndYieldOrThrow(this)
    else unsafeGetExecutor().unsafeSubmitOrThrow(this)
  }

  @tailrec
  private def unsafeSetAsyncCanceler(epoch: Long, asyncCanceler0: ZIO[Any, Any, Any]): Unit = {
    val oldState      = state.get
    val asyncCanceler = if (asyncCanceler0 eq null) ZIO.unit else asyncCanceler0

    oldState match {
      case executing @ Executing(
            status @ Status.Suspended(_, _, oldEpoch, _, _),
            _,
            _,
            _,
            CancelerState.Pending,
            _
          ) if epoch == oldEpoch =>
        val newState = executing.copy(status = status, asyncCanceler = CancelerState.Registered(asyncCanceler))

        if (!state.compareAndSet(oldState, newState)) unsafeSetAsyncCanceler(epoch, asyncCanceler)

      case Executing(_, _, _, _, CancelerState.Empty, _) =>

      case Executing(
            Status.Suspended(_, _, oldEpoch, _, _),
            _,
            _,
            _,
            CancelerState.Registered(_),
            _
          ) if epoch == oldEpoch =>
        throw new Exception("inconsistent state in unsafeSetAsyncCanceler")

      case _ =>
    }
  }

  @tailrec
  private def unsafeSetInterrupting(value: Boolean): Unit = {
    val oldState = state.get

    oldState match {
      case Executing(
            status,
            observers,
            interrupted,
            interruptors,
            asyncCanceler,
            mailbox
          ) => // TODO: Dotty doesn't infer this properly
        if (
          !state.compareAndSet(
            oldState,
            Executing(status.withInterrupting(value), observers, interrupted, interruptors, asyncCanceler, mailbox)
          )
        )
          unsafeSetInterrupting(value)

      case _ =>
    }
  }

  @tailrec
  private[zio] def unsafeSetRef[A](fiberRef: FiberRef.Runtime[A], value: A): Unit = {
    val oldState = fiberRefLocals.get

    if (!fiberRefLocals.compareAndSet(oldState, oldState.updated(fiberRef, value.asInstanceOf[AnyRef])))
      unsafeSetRef(fiberRef, value)
    else ()
  }

  @inline
  private def unsafeShouldInterrupt(): Boolean =
    unsafeIsInterrupted() && unsafeIsInterruptible() && !unsafeIsInterrupting()

  @tailrec
  private def unsafeTryDone(exit: Exit[E, A])(implicit trace: ZTraceElement): IO[E, Any] = {
    val oldState = state.get

    oldState match {
      case executing @ Executing(
            _,
            observers,
            _,
            _,
            _,
            mailbox
          ) => // TODO: Dotty doesn't infer this properly

        if (mailbox ne null) {
          // Not done because the mailbox isn't empty:
          val newState = executing.copy(mailbox = null)

          if (!state.compareAndSet(oldState, newState)) unsafeTryDone(exit)
          else {
            unsafeSetInterrupting(true)

            mailbox *> ZIO.done(exit)
          }
        } else if (_children.isEmpty()) {
          // The mailbox is empty and the _children are shut down:
          val interruptorsCause = oldState.interruptorsCause

          val newExit =
            if (interruptorsCause eq Cause.empty) exit
            else
              exit.mapErrorCause { cause =>
                if (cause.contains(interruptorsCause)) cause
                else cause ++ interruptorsCause
              }

          //  We are truly "unsafeTryDone" because the scope has been closed.
          if (!state.compareAndSet(oldState, Done(newExit))) unsafeTryDone(exit)
          else {
            unsafeReportUnhandled(newExit, trace)
            unsafeNotifyObservers(newExit, observers.asInstanceOf[List[Callback[Nothing, Exit[E, A]]]])

            val startTimeSeconds = fiberId.startTimeSeconds
            val endTimeSeconds   = java.lang.System.currentTimeMillis() / 1000

            val lifetime = endTimeSeconds - startTimeSeconds

            if (trackMetrics) fiberLifetimes.unsafeObserve(lifetime.toDouble)

            newExit match {
              case Exit.Success(_) => if (trackMetrics) fiberSuccesses.unsafeIncrement()

              case Exit.Failure(cause) =>
                if (trackMetrics) fiberFailures.unsafeIncrement()

                cause.fold[Unit](
                  fiberFailureCauses.unsafeObserve("<empty>"),
                  (failure, _) => {
                    observeFailure(failure.getClass())
                  },
                  (defect, _) => {
                    observeFailure(defect.getClass)
                  },
                  (fiberId, _) => {
                    observeFailure(classOf[InterruptedException])
                  }
                )(combineUnit, combineUnit, leftUnit)
            }

            null
          }
        } else {
          // Not done because there are _children left to close:
          import collection.JavaConverters._

          unsafeSetInterrupting(true)

          var interruptChildren: UIO[Any] = UIO.unit
          val iterator                    = _children.iterator()
          while (iterator.hasNext()) {
            val next = iterator.next()

            interruptChildren =
              if (next eq null) interruptChildren
              else interruptChildren *> next.interruptAs(fiberId)
          }
          _children.clear()

          interruptChildren *> ZIO.done(exit)
        }

      case Done(_) => null // Already unsafeTryDone
    }
  }

  /**
   * Unwinds the stack, leaving the first error handler on the top of the stack
   * (assuming one is found), and returning whether or not some folds had to be
   * discarded (indicating a change in the error type).
   */
  private def unsafeUnwindStack(): Boolean = {
    var unwinding      = true
    var discardedFolds = false

    // Unwind the stack, looking for an error handler:
    while (unwinding && !stack.isEmpty) {
      stack.pop() match {
        case _: InterruptExit =>
          interruptStatus.popDrop(())

        case finalizer: Finalizer =>
          implicit val trace: ZTraceElement = finalizer.trace

          // We found a finalizer, we have to immediately disable interruption
          // so the runloop will continue and not abort due to interruption:
          unsafeDisableInterrupting()

          stack.push(
            TracedCont(cause =>
              finalizer.finalizer.foldCauseZIO(
                finalizerCause => {
                  interruptStatus.popDrop(null)

                  unsafeAddSuppressed(finalizerCause)

                  ZIO.failCause(coerceCause(cause))
                },
                _ => {
                  interruptStatus.popDrop(null)

                  ZIO.failCause(coerceCause(cause))
                }
              )
            )
          )

          unwinding = false

        case fold: ZIO.Fold[_, _, _, _, _] if !unsafeShouldInterrupt() =>
          // Push error handler back onto the stack and halt iteration:
          val k = eraseK(fold.failure)

          stack.push(TracedCont(k)(fold.trace))

          unwinding = false

        case _: ZIO.Fold[_, _, _, _, _] =>
          discardedFolds = true

        case _ =>
      }
    }

    discardedFolds
  }

  @inline
  private def trackMetrics: Boolean =
    runtimeConfig.flags.isEnabled(RuntimeConfigFlag.TrackRuntimeMetrics)

  @inline
  private def observeFailure(clzz: Class[_]): Unit =
    if (trackMetrics) fiberFailureCauses.unsafeObserve(clzz.getName)

  private[this] class Finalizer(val finalizer: UIO[Any]) extends ErasedTracedCont {
    def apply(v: Any): Erased = {
      unsafeDisableInterrupting()
      unsafeRestoreInterrupt()(finalizer.trace)
      finalizer.map(_ => v)(finalizer.trace)
    }
    override val trace: ZTraceElement = finalizer.trace
  }

  private[this] class InterruptExit(implicit val trace: ZTraceElement) extends TracedCont[Any, Any, E, Any] {
    def apply(v: Any): IO[E, Any] =
      if (unsafeIsInterruptible()) {
        interruptStatus.popDrop(())

        ZIO.succeedNow(v)
      } else { // TODO: Delete this 'else' branch
        ZIO.succeed(interruptStatus.popDrop(v))
      }
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
      asyncCanceler: CancelerState,
      mailbox: UIO[Any]
    ) extends FiberState[E, A]
    final case class Done[E, A](value: Exit[E, A]) extends FiberState[E, A] {
      def suppressed: Cause[Nothing] = Cause.empty
      def status: Fiber.Status       = Status.Done
      def interruptors: Set[FiberId] = Set.empty
    }

    def initial[E, A]: Executing[E, A] =
      Executing[E, A](
        Status.Running(false),
        Nil,
        Cause.empty,
        Set.empty[FiberId],
        CancelerState.Empty,
        null.asInstanceOf[UIO[Any]]
      )
  }

  sealed abstract class CancelerState

  object CancelerState {
    case object Empty                                              extends CancelerState
    case object Pending                                            extends CancelerState
    final case class Registered(asyncCanceler: ZIO[Any, Any, Any]) extends CancelerState
  }

  type FiberRefLocals = AtomicReference[Map[FiberRef.Runtime[_], AnyRef]]

  val catastrophicFailure: AtomicBoolean =
    new AtomicBoolean(false)

  import zio.ZIOMetric

  lazy val fiberFailureCauses = ZIOMetric.occurrences("zio_fiber_failure_causes", "class").setCount
  lazy val fiberForkLocations = ZIOMetric.occurrences("zio_fiber_fork", "location").setCount

  lazy val fibersStarted  = ZIOMetric.count("zio_fiber_started").counter
  lazy val fiberSuccesses = ZIOMetric.count("zio_fiber_successes").counter
  lazy val fiberFailures  = ZIOMetric.count("zio_fiber_failures").counter
  lazy val fiberLifetimes = ZIOMetric.observeHistogram("zio_fiber_lifetimes", fiberLifetimeBoundaries).histogram

  lazy val fiberLifetimeBoundaries = ZIOMetric.Histogram.Boundaries.exponential(1.0, 2.0, 100)

  val combineUnit = (a: Unit, b: Unit) => ()
  val leftUnit    = (a: Unit, b: Any) => a

  type Erased           = ZIO[Any, Any, Any]
  type ErasedCont       = Any => Erased
  type ErasedTracedCont = TracedCont[Any, Any, Any, Any]

  final def eraseR[R, E, A](zio: ZIO[R, E, A]): ZIO[Any, E, A]   = zio.asInstanceOf[ZIO[Any, E, A]]
  final def erase(zio: ZIO[_, _, _]): Erased                     = zio.asInstanceOf[Erased]
  final def eraseK[R, E, A, B](f: A => ZIO[R, E, B]): ErasedCont = f.asInstanceOf[ErasedCont]
  final def coerceCause[E](cause: Any): Cause[E]                 = cause.asInstanceOf[Cause[E]]
}
