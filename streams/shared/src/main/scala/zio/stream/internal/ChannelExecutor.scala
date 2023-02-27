package zio.stream.internal

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.ZChannel
import zio.stream.ZChannel.{ChildExecutorDecision, UpstreamPullRequest, UpstreamPullStrategy}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.collection.mutable.Stack

private[zio] class ChannelExecutor[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone](
  initialChannel: () => ZChannel[Env, InErr, InElem, InDone, OutErr, OutElem, OutDone],
  @volatile private var providedEnv: ZEnvironment[Any],
  executeCloseLastSubstream: URIO[Env, Any] => URIO[Env, Any]
) {
  import ChannelExecutor._

  private[this] def restorePipe(exit: Exit[Any, Any], prev: ErasedExecutor[Env])(implicit
    trace: Trace
  ): ZIO[Env, Nothing, Any] = {
    val currInput = input
    input = prev

    if (currInput ne null) currInput.close(exit) else ZIO.unit
  }

  private[this] final def popAllFinalizers(
    exit: Exit[Any, Any]
  )(implicit trace: Trace): URIO[Env, Any] = {

    @tailrec
    def unwind(
      acc: List[Finalizer[Env]]
    ): List[Finalizer[Env]] =
      if (doneStack.isEmpty) {
        acc.reverse
      } else {
        doneStack.pop() match {
          case ZChannel.Fold.K(_, _)      => unwind(acc)
          case ZChannel.Fold.Finalizer(f) => unwind(f :: acc)
        }
      }

    val finalizers = unwind(List.empty)
    val effect     = if (finalizers.isEmpty) ZIO.unit else runFinalizers(finalizers, exit)
    storeInProgressFinalizer(effect)
    effect
  }

  private[this] final def popNextFinalizers(): Stack[ZChannel.Fold.Finalizer[Env, Any, Any]] = {
    val builder = Stack.newBuilder[ZChannel.Fold.Finalizer[Env, Any, Any]]

    @tailrec
    def go(): Unit =
      if (doneStack.nonEmpty) {
        doneStack.head match {
          case ZChannel.Fold.K(_, _) =>
          case finalizer @ ZChannel.Fold.Finalizer(_) =>
            builder += finalizer.asInstanceOf[ZChannel.Fold.Finalizer[Env, Any, Any]]
            doneStack.pop()
            go()
        }
      }

    go()
    builder.result()
  }

  private[this] final def storeInProgressFinalizer(finalizer: URIO[Env, Any]): Unit =
    inProgressFinalizer = finalizer

  private[this] final def clearInProgressFinalizer(): Unit =
    inProgressFinalizer = null

  def close(ex: Exit[Any, Any])(implicit trace: Trace): ZIO[Env, Nothing, Any] = {
    def ifNotNull[R](zio: URIO[R, Any]): URIO[R, Any] =
      if (zio ne null) zio else ZIO.unit

    val runInProgressFinalizers = {
      val finalizer = inProgressFinalizer

      if (finalizer ne null)
        finalizer.ensuring(ZIO.succeed(clearInProgressFinalizer()))
      else null
    }

    val closeSubexecutors =
      if (activeSubexecutor eq null) null
      else activeSubexecutor.close(ex)

    val closeSelf: URIO[Env, Any] = {
      val selfFinalizers = popAllFinalizers(ex)

      if (selfFinalizers ne null)
        selfFinalizers.ensuring(ZIO.succeed(clearInProgressFinalizer()))
      else null
    }

    if ((closeSubexecutors eq null) && (runInProgressFinalizers eq null) && (closeSelf eq null)) null
    else
      (
        ifNotNull(closeSubexecutors).exit <*>
          ifNotNull(runInProgressFinalizers).exit <*>
          ifNotNull(closeSelf).exit
      ).map { case (a, b, c) => a *> b *> c }.uninterruptible.flatMap(ZIO.done(_))
  }

  def getDone: Exit[OutErr, OutDone] = done.asInstanceOf[Exit[OutErr, OutDone]]

  def getEmit: OutElem = emitted.asInstanceOf[OutElem]

  def cancelWith(exit: Exit[OutErr, OutDone]): Unit =
    cancelled = exit

  final def run()(implicit trace: Trace): ChannelState[Env, Any] = {
    var result: ChannelState[Env, Any] = null

    while (result eq null) {
      if (cancelled ne null) {
        result = processCancellation()
      } else if (activeSubexecutor ne null) {
        result = runSubexecutor()
      } else {
        try {
          currentChannel match {
            case null =>
              result = ChannelState.Done

            case ZChannel.Bridge(bridgeInput, channel) =>
              // PipeTo(left, Bridge(queue, channel))
              // In a fiber: repeatedly run left and push its outputs to the queue
              // Add a finalizer to interrupt the fiber and close the executor
              currentChannel = channel

              if (input ne null) {
                val inputExecutor = input
                input = null

                lazy val drainer: URIO[Env, Any] =
                  bridgeInput.awaitRead *> ZIO.suspendSucceed {
                    val state = inputExecutor.run()

                    state match {
                      case ChannelState.Done =>
                        val sendInput = inputExecutor.getDone match {
                          case Exit.Failure(cause) => bridgeInput.error(cause)
                          case Exit.Success(value) => bridgeInput.done(value)
                        }

                        sendInput

                      case ChannelState.Emit =>
                        bridgeInput.emit(inputExecutor.getEmit) *> drainer

                      case ChannelState.Effect(zio) =>
                        zio.foldCauseZIO(
                          cause => bridgeInput.error(cause),
                          _ => drainer
                        )

                      case r @ ChannelState.Read(_, _, _, _) =>
                        ChannelExecutor
                          .readUpstream(
                            r.asInstanceOf[ChannelState.Read[Env, Any]],
                            () => drainer,
                            (cause: Cause[Any]) => bridgeInput.error(cause)
                          )
                    }
                  }

                result = ChannelState.Effect(
                  drainer.forkDaemon.flatMap { fiber =>
                    ZIO.succeed(addFinalizer { exit =>
                      fiber.interrupt *>
                        ZIO.suspendSucceed {
                          val effect = restorePipe(exit, inputExecutor)

                          if (effect ne null) effect
                          else ZIO.unit
                        }
                    })
                  }
                )
              }

            case ZChannel.PipeTo(left, right) =>
              val previousInput = input

              val leftExec: ErasedExecutor[Env] = new ChannelExecutor(left, providedEnv, executeCloseLastSubstream)
              leftExec.input = previousInput
              input = leftExec

              addFinalizer { exit =>
                val effect = restorePipe(exit, previousInput)

                if (effect ne null) effect
                else ZIO.unit
              }

              currentChannel = right().asInstanceOf[Channel[Env]]

            case read @ ZChannel.Read(_, _) =>
              result = ChannelState.Read(
                input,
                onEffect = identity[ZIO[Env, Nothing, Unit]],
                onEmit = { (out: Any) =>
                  try {
                    currentChannel = read.more(out)
                  } catch {
                    case e: Throwable =>
                      currentChannel = read.done.onExit(Exit.die(e))
                  }
                  null
                },
                onDone = { (exit: Exit[Any, Any]) =>
                  val onExit: Exit[Any, Any] => Channel[Env] =
                    (read.done.onExit _).asInstanceOf[Exit[Any, Any] => Channel[Env]]
                  currentChannel = onExit(exit)
                  null
                }
              )

            case ZChannel.SucceedNow(terminal) =>
              result = doneSucceed(terminal)

            case ZChannel.Fail(error) =>
              result = doneHalt(error())

            case ZChannel.Succeed(effect) =>
              result = doneSucceed(effect())

            case ZChannel.Suspend(effect) =>
              currentChannel = effect()

            case ZChannel.FromZIO(zio) =>
              val pzio =
                (if (providedEnv eq null) zio()
                 else zio().provideEnvironment(providedEnv.asInstanceOf[ZEnvironment[Env]]))
                  .asInstanceOf[ZIO[Env, OutErr, OutDone]]

              result = ChannelState.Effect(
                pzio
                  .foldCauseZIO(
                    cause =>
                      doneHalt(cause) match {
                        case ChannelState.Effect(zio) => zio
                        case _                        => ZIO.unit
                      },
                    z =>
                      doneSucceed(z) match {
                        case ChannelState.Effect(zio) => zio
                        case _                        => ZIO.unit
                      }
                  )
              )

            case ZChannel.Emit(out) =>
              emitted = out
              currentChannel = if (activeSubexecutor ne null) null else ZChannel.unit
              result = ChannelState.Emit

            case ensuring @ ZChannel.Ensuring(_, _) =>
              runEnsuring(ensuring.asInstanceOf[ZChannel.Ensuring[Env, Any, Any, Any, Any, Any, Any]])

            case ZChannel.ConcatAll(combineSubK, combineSubKAndInner, onPull, onEmit, value, k) =>
              val innerExecuteLastClose =
                (f: URIO[Env, Any]) =>
                  ZIO.succeed {
                    val prevLastClose = if (closeLastSubstream eq null) ZIO.unit else closeLastSubstream
                    closeLastSubstream = prevLastClose *> f
                  }

              val exec: ErasedExecutor[Env] = new ChannelExecutor(value, providedEnv, innerExecuteLastClose)
              exec.input = input

              activeSubexecutor = Subexecutor.PullFromUpstream(
                exec,
                k.asInstanceOf[Any => Channel[Env]],
                lastDone = null,
                activeChildExecutors = Queue.empty,
                combineSubK.asInstanceOf[(Any, Any) => Any],
                combineSubKAndInner.asInstanceOf[(Any, Any) => Any],
                onPull.asInstanceOf[UpstreamPullRequest[Any] => UpstreamPullStrategy[Any]],
                onEmit.asInstanceOf[Any => ChildExecutorDecision]
              )
              closeLastSubstream = null
              currentChannel = null

            case ZChannel.Fold(value, k) =>
              doneStack.push(k.asInstanceOf[ErasedContinuation[Env]])
              currentChannel = value

            case bracketOut @ ZChannel.BracketOut(_, _) =>
              result = runBracketOut(bracketOut.asInstanceOf[ZChannel.BracketOut[Env, Any, Any]])

            case ZChannel.Provide(env, inner) =>
              val previousEnv = providedEnv
              providedEnv = env()
              currentChannel = inner.asInstanceOf[Channel[Env]]

              addFinalizer { _ =>
                ZIO.succeed {
                  providedEnv = previousEnv
                }
              }
          }
        } catch {
          case t: Throwable => currentChannel = ZChannel.failCause(Cause.die(t))
        }
      }
    }

    result
  }

  private[this] var currentChannel: Channel[Env] = erase(initialChannel())

  private[this] var done: Exit[Any, Any] = _

  private[this] var doneStack: Stack[ErasedContinuation[Env]] = Stack.empty[ErasedContinuation[Env]]

  private[this] var emitted: Any = _

  @volatile
  private[this] var inProgressFinalizer: URIO[Env, Any] = _

  @volatile
  var input: ErasedExecutor[Env] = _

  private[this] var activeSubexecutor: Subexecutor[Env] = _

  private[this] var cancelled: Exit[OutErr, OutDone] = _

  private[this] var closeLastSubstream: URIO[Env, Any] = _

  private[this] def doneSucceed(z: Any)(implicit trace: Trace): ChannelState[Env, Any] =
    if (doneStack.isEmpty) {
      done = Exit.succeed(z)
      currentChannel = null
      ChannelState.Done
    } else {
      doneStack.head match {
        case ZChannel.Fold.K(onSuccess, _) =>
          doneStack.pop()
          currentChannel = onSuccess(z)
          null

        case ZChannel.Fold.Finalizer(_) =>
          val finalizers = popNextFinalizers()

          if (doneStack.isEmpty) {
            doneStack = finalizers.asInstanceOf[Stack[ErasedContinuation[Env]]]
            done = Exit.succeed(z)
            currentChannel = null
            ChannelState.Done
          } else {
            val finalizerEffect =
              runFinalizers(finalizers.map(_.finalizer), Exit.succeed(z))
            storeInProgressFinalizer(finalizerEffect)

            ChannelState.Effect(
              finalizerEffect
                .ensuring(
                  ZIO.succeed(clearInProgressFinalizer())
                )
                .uninterruptible *> ZIO.succeed(doneSucceed(z))
            )
          }
      }
    }

  private[this] def doneHalt(cause: Cause[Any])(implicit trace: Trace): ChannelState[Env, Any] =
    if (doneStack.isEmpty) {
      done = Exit.failCause(cause)
      currentChannel = null
      ChannelState.Done
    } else {
      doneStack.head match {
        case ZChannel.Fold.K(_, onHalt) =>
          doneStack.pop()
          currentChannel = onHalt(cause)
          null

        case ZChannel.Fold.Finalizer(_) =>
          val finalizers = popNextFinalizers()

          if (doneStack.isEmpty) {
            doneStack = finalizers.asInstanceOf[Stack[ErasedContinuation[Env]]]
            done = Exit.failCause(cause)
            currentChannel = null
            ChannelState.Done
          } else {
            val finalizerEffect = runFinalizers(finalizers.map(_.finalizer), Exit.failCause(cause))
            storeInProgressFinalizer(finalizerEffect)

            ChannelState.Effect(
              finalizerEffect
                .ensuring(ZIO.succeed(clearInProgressFinalizer()))
                .uninterruptible *> ZIO.succeed(doneHalt(cause))
            )
          }
      }
    }

  private[this] def processCancellation(): ChannelState[Env, Any] = {
    currentChannel = null
    done = cancelled
    cancelled = null
    ChannelState.Done
  }

  private def runBracketOut(
    bracketOut: ZChannel.BracketOut[Env, Any, Any]
  )(implicit trace: Trace): ChannelState.Effect[Env, Any] =
    ChannelState.Effect {
      ZIO.uninterruptible {
        provide(bracketOut.acquire()).foldCauseZIO(
          cause => ZIO.succeed { currentChannel = ZChannel.failCause(cause) },
          out =>
            ZIO.succeed {
              addFinalizer(exit => provide(bracketOut.finalizer(out, exit)))
              currentChannel = ZChannel.write(out)
            }
        )
      }
    }

  private def provide[Env, OutErr, OutDone](
    zio: ZIO[Env, OutErr, OutDone]
  )(implicit trace: Trace): ZIO[Env, OutErr, OutDone] =
    if (providedEnv eq null)
      zio
    else
      zio.provideEnvironment(providedEnv.asInstanceOf[ZEnvironment[Env]])

  private[this] def runEnsuring(ensuring: ZChannel.Ensuring[Env, Any, Any, Any, Any, Any, Any]) = {
    addFinalizer(ensuring.finalizer)
    currentChannel = ensuring.channel
  }

  private[this] def addFinalizer(f: Finalizer[Env]): Unit =
    doneStack.push(ZChannel.Fold.Finalizer(f))

  private[this] def runFinalizers(finalizers: Iterable[Finalizer[Env]], ex: Exit[Any, Any])(implicit
    trace: Trace
  ): URIO[Env, Any] =
    if (finalizers.isEmpty) null
    else
      ZIO
        .foreach(finalizers)(_.apply(ex).exit)
        .map(results => Exit.collectAll(results) getOrElse Exit.unit)
        .flatMap(ZIO.done(_))

  private[this] def runSubexecutor()(implicit trace: Trace): ChannelState[Env, Any] =
    activeSubexecutor match {
      case subexec @ Subexecutor.PullFromUpstream(_, _, _, _, _, _, _, _) =>
        pullFromUpstream(subexec.asInstanceOf[Subexecutor.PullFromUpstream[Env]])

      case subexec @ Subexecutor.DrainChildExecutors(_, _, _, _, _, _, _) =>
        drainChildExecutors(subexec.asInstanceOf[Subexecutor.DrainChildExecutors[Env]])

      case subexec @ Subexecutor.PullFromChild(childExecutor, parentSubexecutor, onEmit) =>
        pullFromChild(
          childExecutor.asInstanceOf[ErasedExecutor[Env]],
          parentSubexecutor,
          onEmit,
          subexec.asInstanceOf[Subexecutor.PullFromChild[Env]]
        )

      case Subexecutor.Emit(value, next) =>
        this.emitted = value
        this.activeSubexecutor = next
        ChannelState.Emit
    }

  private def replaceSubexecutor(nextSubExec: Subexecutor[Env]): Unit = {
    currentChannel = null
    activeSubexecutor = nextSubExec
  }

  private def finishSubexecutorWithCloseEffect(
    subexecDone: Exit[Any, Any],
    closeFns: (Exit[Any, Any]) => URIO[Env, Any]*
  )(implicit trace: Trace): ChannelState[Env, Any] = {
    addFinalizer { _ =>
      ZIO.foreachDiscard(closeFns) { closeFn =>
        ZIO.succeed(closeFn(subexecDone)).flatMap { closeEffect =>
          if (closeEffect ne null) {
            closeEffect
          } else {
            ZIO.unit
          }
        }
      }
    }

    val state = subexecDone.foldExit(doneHalt, doneSucceed)
    activeSubexecutor = null
    state
  }

  def finishWithExit(exit: Exit[Any, Any])(implicit trace: Trace): ZIO[Env, Any, Any] = {
    val state = exit.foldExit(doneHalt, doneSucceed)
    activeSubexecutor = null

    if (state eq null) ZIO.unit
    else state.effect
  }

  private final def applyUpstreamPullStrategy(
    upstreamFinished: Boolean,
    queue: Queue[Subexecutor.PullFromChild[Env]],
    strategy: UpstreamPullStrategy[Any]
  ): (Option[Any], Queue[Subexecutor.PullFromChild[Env]]) =
    strategy match {
      case UpstreamPullStrategy.PullAfterNext(emitSeparator) =>
        (emitSeparator, if (!upstreamFinished || queue.exists(_ != null)) null +: queue else queue)
      case UpstreamPullStrategy.PullAfterAllEnqueued(emitSeparator) =>
        (
          emitSeparator,
          if (!upstreamFinished || queue.exists(_ != null))
            queue.enqueue(null.asInstanceOf[Subexecutor.PullFromChild[Env]])
          else queue
        )
    }

  private final def performPullFromUpstream(
    self: Subexecutor.PullFromUpstream[Env]
  )(implicit trace: Trace): ChannelState[Env, Any] =
    ChannelState.Read(
      self.upstreamExecutor,
      onEffect = (effect: ZIO[Env, Nothing, Unit]) => {
        val closeLast =
          if (closeLastSubstream eq null) ZIO.unit else closeLastSubstream
        closeLastSubstream = null
        executeCloseLastSubstream(closeLast) *> effect
      },
      onEmit = { (emitted: Any) =>
        if (this.closeLastSubstream ne null) {
          val closeLast = this.closeLastSubstream
          closeLastSubstream = null

          executeCloseLastSubstream(closeLast).map { _ =>
            val childExecutor: ErasedExecutor[Env] =
              new ChannelExecutor(
                () => self.createChild(emitted),
                providedEnv,
                executeCloseLastSubstream
              )
            childExecutor.input = input

            val (emitSeparator, updatedChildExecutors) =
              applyUpstreamPullStrategy(
                upstreamFinished = false,
                self.activeChildExecutors,
                self.onPull(UpstreamPullRequest.Pulled(emitted))
              )
            activeSubexecutor = Subexecutor.PullFromChild[Env](
              childExecutor,
              self.copy(
                activeChildExecutors = updatedChildExecutors
              ),
              self.onEmit
            )

            emitSeparator.foreach { value =>
              activeSubexecutor = Subexecutor.Emit(value, activeSubexecutor)
            }
          }
        } else {
          val childExecutor: ErasedExecutor[Env] =
            new ChannelExecutor(
              () => self.createChild(emitted),
              providedEnv,
              executeCloseLastSubstream
            )
          childExecutor.input = input

          val (emitSeparator, updatedChildExecutors) =
            applyUpstreamPullStrategy(
              upstreamFinished = false,
              self.activeChildExecutors,
              self.onPull(UpstreamPullRequest.Pulled(emitted))
            )
          activeSubexecutor = Subexecutor.PullFromChild[Env](
            childExecutor,
            self.copy(
              activeChildExecutors = updatedChildExecutors
            ),
            self.onEmit
          )

          emitSeparator.foreach { value =>
            activeSubexecutor = Subexecutor.Emit(value, activeSubexecutor)
          }
          null
        }
      },
      onDone = { (exit: Exit[Any, Any]) =>
        if (self.activeChildExecutors.exists(_ != null)) {
          val drain = Subexecutor.DrainChildExecutors(
            self.upstreamExecutor,
            self.lastDone,
            null +: self.activeChildExecutors,
            self.upstreamExecutor.getDone,
            self.combineChildResults,
            self.combineWithChildResult,
            self.onPull
          )

          if (this.closeLastSubstream ne null) {
            val closeLast = this.closeLastSubstream
            closeLastSubstream = null
            executeCloseLastSubstream(closeLast).map { _ =>
              replaceSubexecutor(drain)
            }
          } else {
            replaceSubexecutor(drain)
            null
          }
        } else {
          val lastClose = this.closeLastSubstream
          finishSubexecutorWithCloseEffect(
            exit.mapExit(self.combineWithChildResult(self.lastDone, _)),
            _ => lastClose,
            self.upstreamExecutor.close
          ).effectOrNullIgnored // NOTE: assuming finalizers cannot fail
        }
      }
    )

  private final def pullFromUpstream(
    self: Subexecutor.PullFromUpstream[Env]
  )(implicit trace: Trace): ChannelState[Env, Any] =
    self.activeChildExecutors.dequeueOption match {
      case Some((null, rest)) =>
        performPullFromUpstream(self.copy(activeChildExecutors = rest))

      case Some((activeChild, rest)) =>
        replaceSubexecutor(
          activeChild.copy(
            parentSubexecutor = self.copy(activeChildExecutors = rest)
          )
        )
        null
      case None =>
        performPullFromUpstream(self)
    }

  private def drainChildExecutors(
    self: Subexecutor.DrainChildExecutors[Env]
  )(implicit trace: Trace): ChannelState[Env, Any] =
    self.activeChildExecutors.dequeueOption match {
      case Some((null, rest)) =>
        val (emitSeparator, remainingExecutors) =
          applyUpstreamPullStrategy(
            upstreamFinished = true,
            rest,
            self.onPull(UpstreamPullRequest.NoUpstream(rest.count(_ != null)))
          )
        replaceSubexecutor(
          self.copy(activeChildExecutors = remainingExecutors)
        )

        emitSeparator match {
          case Some(value) =>
            emitted = value
            ChannelState.Emit
          case None =>
            null
        }
      case Some((activeChild, rest)) =>
        replaceSubexecutor(
          activeChild.copy(
            parentSubexecutor = self.copy(activeChildExecutors = rest)
          )
        )
        null
      case None =>
        val lastClose = closeLastSubstream
        if (lastClose != null) {
          addFinalizer(_ => ZIO.succeed(lastClose))
        }
        finishSubexecutorWithCloseEffect(
          self.upstreamDone,
          _ => lastClose,
          self.upstreamExecutor.close
        )
    }

  private def pullFromChild(
    childExecutor: ErasedExecutor[Env],
    parentSubexecutor: Subexecutor[Env],
    onEmitted: Any => ChildExecutorDecision,
    self: Subexecutor.PullFromChild[Env]
  )(implicit trace: Trace): ChannelState[Env, Any] = {
    def handleSubexecFailure(cause: Cause[Any]): ChannelState[Env, Any] = {
      val closeEffects: Seq[Exit[Any, Any] => URIO[Env, Any]] =
        Seq(parentSubexecutor.close, childExecutor.close)

      finishSubexecutorWithCloseEffect(
        Exit.failCause(cause),
        closeEffects: _*
      )
    }

    def finishWithDoneValue(doneValue: Any): Unit =
      parentSubexecutor match {
        case Subexecutor.PullFromUpstream(
              upstreamExecutor,
              createChild,
              lastDone,
              activeChildExecutors,
              combineChildResults,
              combineWithChildResult,
              onPull,
              onEmit
            ) =>
          val modifiedParent = Subexecutor.PullFromUpstream(
            upstreamExecutor,
            createChild,
            if (lastDone != null)
              combineChildResults(lastDone, doneValue)
            else doneValue,
            activeChildExecutors,
            combineChildResults,
            combineWithChildResult,
            onPull,
            onEmit
          )
          closeLastSubstream = childExecutor.close(Exit.succeed(doneValue))
          replaceSubexecutor(modifiedParent)
        case Subexecutor.DrainChildExecutors(
              upstreamExecutor,
              lastDone,
              activeChildExecutors,
              upstreamDone,
              combineChildResults,
              combineWithChildResult,
              onPull
            ) =>
          val modifiedParent = Subexecutor.DrainChildExecutors(
            upstreamExecutor,
            if (lastDone != null)
              combineChildResults(lastDone, doneValue)
            else doneValue,
            activeChildExecutors,
            upstreamDone,
            combineChildResults,
            combineWithChildResult,
            onPull
          )
          closeLastSubstream = childExecutor.close(Exit.succeed(doneValue))
          replaceSubexecutor(modifiedParent)
        case _ => ??? // TODO better encoding to avoid this
      }

    ChannelState.Read(
      childExecutor,
      onEffect = identity[ZIO[Env, Nothing, Unit]],
      onEmit = { (emitted: Any) =>
        onEmitted(emitted) match {
          case ChildExecutorDecision.Yield =>
            val modifiedParent = parentSubexecutor.enqueuePullFromChild(self)
            replaceSubexecutor(modifiedParent)
          case ChildExecutorDecision.Close(doneValue) =>
            finishWithDoneValue(doneValue)
          case ChildExecutorDecision.Continue =>
        }

        this.activeSubexecutor = Subexecutor.Emit(emitted, this.activeSubexecutor)
        null
      },
      onDone = { (exit: Exit[Any, Any]) =>
        exit match {
          case Exit.Failure(cause) =>
            handleSubexecFailure(cause).effectOrNullIgnored // NOTE: assuming finalizers cannot fail
          case e @ Exit.Success(doneValue) =>
            finishWithDoneValue(doneValue)
            null
        }
      }
    )
  }
}

private[zio] object ChannelExecutor {
  type Channel[R]            = ZChannel[R, Any, Any, Any, Any, Any, Any]
  type ErasedExecutor[Env]   = ChannelExecutor[Env, Any, Any, Any, Any, Any, Any]
  type ErasedContinuation[R] = ZChannel.Fold.Continuation[R, Any, Any, Any, Any, Any, Any, Any, Any]
  type Finalizer[R]          = Exit[Any, Any] => URIO[R, Any]

  sealed trait ChannelState[-R, +E] { self =>
    def effect: ZIO[R, E, Any] =
      self match {
        case ChannelState.Effect(zio) => zio
        case _                        => ZIO.unit
      }

    def effectOrNullIgnored(implicit trace: Trace): ZIO[R, Nothing, Unit] =
      self match {
        case ChannelState.Effect(zio) => zio.ignore.unit
        case _                        => null
      }
  }

  object ChannelState {
    case object Emit                                   extends ChannelState[Any, Nothing]
    case object Done                                   extends ChannelState[Any, Nothing]
    final case class Effect[R, E](zio: ZIO[R, E, Any]) extends ChannelState[R, E]
    final case class Read[R, E](
      upstream: ErasedExecutor[R],
      onEffect: ZIO[R, Nothing, Unit] => ZIO[R, Nothing, Unit],
      onEmit: Any => ZIO[R, Nothing, Unit],
      onDone: Exit[Any, Any] => ZIO[R, Nothing, Unit]
    ) extends ChannelState[R, E]
  }

  def maybeCloseBoth[Env](l: ZIO[Env, Nothing, Any], r: ZIO[Env, Nothing, Any])(implicit
    trace: Trace
  ): URIO[Env, Exit[Nothing, Any]] =
    if ((l eq null) && (r eq null)) null
    else if ((l ne null) && (r ne null)) l.exit.zipWith(r.exit)(_ *> _)
    else if (l ne null) l.exit
    else r.exit

  sealed abstract class Subexecutor[R] {
    def close(ex: Exit[Any, Any])(implicit trace: Trace): URIO[R, Any]

    def enqueuePullFromChild(child: Subexecutor.PullFromChild[R]): Subexecutor[R]
  }
  object Subexecutor {

    /**
     * Execute upstreamExecutor and for each emitted element, spawn a child
     * channel and continue with processing it by PullFromChild
     */
    final case class PullFromUpstream[R](
      upstreamExecutor: ErasedExecutor[R],
      createChild: Any => Channel[R],
      lastDone: Any,
      activeChildExecutors: Queue[Subexecutor.PullFromChild[R]],
      combineChildResults: (Any, Any) => Any,
      combineWithChildResult: (Any, Any) => Any,
      onPull: UpstreamPullRequest[Any] => UpstreamPullStrategy[Any],
      onEmit: Any => ChildExecutorDecision
    ) extends Subexecutor[R] { self =>
      def close(ex: Exit[Any, Any])(implicit trace: Trace): URIO[R, Any] = {
        val fin1 = upstreamExecutor.close(ex)
        val fins =
          activeChildExecutors.map(child => if (child != null) child.childExecutor.close(ex) else null).enqueue(fin1)

        fins
          .foldLeft[URIO[R, Exit[Nothing, Any]]](null) { case (acc, next) =>
            if ((acc eq null) && (next eq null)) null
            else if (acc eq null) next.exit
            else if (next eq null) acc
            else acc.zipWith(next.exit)(_ *> _)
          }
          .flatMap(ZIO.done(_))
      }

      override def enqueuePullFromChild(child: Subexecutor.PullFromChild[R]): Subexecutor[R] =
        this.copy(activeChildExecutors = activeChildExecutors.enqueue(child))
    }

    /**
     * Execute the childExecutor and on each emitted value, decide what to do by
     * onEmit
     */
    final case class PullFromChild[R](
      childExecutor: ErasedExecutor[R],
      parentSubexecutor: Subexecutor[R],
      onEmit: Any => ChildExecutorDecision
    ) extends Subexecutor[R] {
      def close(ex: Exit[Any, Any])(implicit trace: Trace): URIO[R, Any] = {
        val fin1 = childExecutor.close(ex)
        val fin2 = parentSubexecutor.close(ex)

        if ((fin1 eq null) && (fin2 eq null)) null
        else if ((fin1 ne null) && (fin2 ne null)) fin1.exit.zipWith(fin2.exit)(_ *> _).flatMap(ZIO.done(_))
        else if (fin1 ne null) fin1
        else fin2
      }

      override def enqueuePullFromChild(child: Subexecutor.PullFromChild[R]): Subexecutor[R] =
        this
    }

    /**
     * Transformed from PullFromUpstream when upstream has finished but there
     * are still active child executors
     */
    final case class DrainChildExecutors[R](
      upstreamExecutor: ErasedExecutor[R],
      lastDone: Any,
      activeChildExecutors: Queue[Subexecutor.PullFromChild[R]],
      upstreamDone: Exit[Any, Any],
      combineChildResults: (Any, Any) => Any,
      combineWithChildResult: (Any, Any) => Any,
      onPull: UpstreamPullRequest[Any] => UpstreamPullStrategy[Any]
    ) extends Subexecutor[R] {
      def close(ex: Exit[Any, Any])(implicit trace: Trace): URIO[R, Exit[Any, Any]] = {
        val fin1 = upstreamExecutor.close(ex)
        val fins =
          activeChildExecutors.map(child => if (child != null) child.childExecutor.close(ex) else null).enqueue(fin1)

        fins.foldLeft[URIO[R, Exit[Any, Any]]](null) { case (acc, next) =>
          if ((acc eq null) && (next eq null)) null
          else if (acc eq null) next.exit
          else if (next eq null) acc
          else acc.zipWith(next.exit)(_ *> _)
        }
      }

      override def enqueuePullFromChild(child: Subexecutor.PullFromChild[R]): Subexecutor[R] =
        this.copy(activeChildExecutors = activeChildExecutors.enqueue(child))
    }

    final case class Emit[R](value: Any, next: Subexecutor[R]) extends Subexecutor[R] {
      def close(ex: Exit[Any, Any])(implicit trace: Trace): URIO[R, Any] =
        next.close(ex)

      override def enqueuePullFromChild(child: Subexecutor.PullFromChild[R]): Subexecutor[R] =
        this
    }
  }

  private def erase[R](channel: ZChannel[R, _, _, _, _, _, _]): Channel[R] =
    channel.asInstanceOf[Channel[R]]

  private[stream] def readUpstream[R, E, E2, A](
    r: ChannelExecutor.ChannelState.Read[R, E],
    onSuccess: () => ZIO[R, E2, A],
    onFailure: Cause[E] => ZIO[R, E2, A]
  )(implicit trace: Trace): ZIO[R, E2, A] = {
    val readStack = scala.collection.mutable.Stack
      .apply[ChannelState.Read[Any, Any]](r.asInstanceOf[ChannelState.Read[Any, Any]])

    def read(): ZIO[R, E2, A] = {
      val current = readStack.pop()
      if (current.upstream eq null) {
        ZIO.dieMessage("Unexpected end of input for channel execution")
      } else {
        current.upstream.run() match {
          case ChannelState.Emit =>
            val emitEffect = current.onEmit(current.upstream.getEmit)
            if (readStack.isEmpty) {
              if (emitEffect eq null) ZIO.suspendSucceed(onSuccess())
              else
                emitEffect.asInstanceOf[ZIO[R, Nothing, Unit]].foldCauseZIO(onFailure, _ => onSuccess())
            } else {
              if (emitEffect eq null) read()
              else (emitEffect.asInstanceOf[ZIO[R, Nothing, Unit]].foldCauseZIO(onFailure, _ => read()))
            }
          case ChannelState.Done =>
            val doneEffect = current.onDone(current.upstream.getDone)
            if (readStack.isEmpty) {
              if (doneEffect eq null) ZIO.suspendSucceed(onSuccess())
              else
                doneEffect.asInstanceOf[ZIO[R, Nothing, Unit]].foldCauseZIO(onFailure, _ => onSuccess())
            } else {
              if (doneEffect eq null) read()
              else (doneEffect.asInstanceOf[ZIO[R, Nothing, Unit]].foldCauseZIO(onFailure, _ => read()))
            }
          case ChannelState.Effect(zio) =>
            readStack.push(current)
            current
              .onEffect(zio.asInstanceOf[ZIO[Any, Nothing, Unit]])
              .catchAllCause { cause =>
                ZIO.suspendSucceed {
                  val doneEffect = current.onDone(Exit.failCause(cause))
                  if (doneEffect eq null) ZIO.unit
                  else doneEffect
                }
              }
              .foldCauseZIO(onFailure, _ => read())
          case r2 @ ChannelState.Read(upstream2, onEffect2, onEmit2, onDone2) =>
            readStack.push(current.asInstanceOf[ChannelState.Read[Any, Any]])
            readStack.push(r2.asInstanceOf[ChannelState.Read[Any, Any]])
            ZIO.succeed(()) *> read()
        }
      }
    }

    read()
  }
}

/**
 * Consumer-side view of [[SingleProducerAsyncInput]] for variance purposes.
 */
private[zio] trait AsyncInputConsumer[+Err, +Elem, +Done] {
  def takeWith[A](
    onError: Cause[Err] => A,
    onElement: Elem => A,
    onDone: Done => A
  )(implicit trace: Trace): UIO[A]
}

/**
 * Producer-side view of [[SingleProducerAsyncInput]] for variance purposes.
 */
private[zio] trait AsyncInputProducer[-Err, -Elem, -Done] {
  def emit(el: Elem)(implicit trace: Trace): UIO[Any]
  def done(a: Done)(implicit trace: Trace): UIO[Any]
  def error(cause: Cause[Err])(implicit trace: Trace): UIO[Any]
  def awaitRead(implicit trace: Trace): UIO[Any]
}

/**
 * An MVar-like abstraction for sending data to channels asynchronously.
 * Designed for one producer and multiple consumers.
 *
 * Features the following semantics:
 *   - Buffer of size 1
 *   - When emitting, the producer waits for a consumer to pick up the value to
 *     prevent "reading ahead" too much.
 *   - Once an emitted element is read by a consumer, it is cleared from the
 *     buffer, so that at most one consumer sees every emitted element.
 *   - When sending a done or error signal, the producer does not wait for a
 *     consumer to pick up the signal. The signal stays in the buffer after
 *     being read by a consumer, so it can be propagated to multiple consumers.
 *   - Trying to publish another emit/error/done after an error/done have
 *     already been published results in an interruption.
 */
private[zio] class SingleProducerAsyncInput[Err, Elem, Done](
  ref: Ref[SingleProducerAsyncInput.State[Err, Elem, Done]]
) extends AsyncInputConsumer[Err, Elem, Done]
    with AsyncInputProducer[Err, Elem, Done] {
  import SingleProducerAsyncInput.State

  def emit(el: Elem)(implicit trace: Trace): UIO[Any] =
    Promise.make[Nothing, Unit].flatMap { p =>
      ref.modify {
        case s @ State.Emit(notifyConsumers) =>
          val (notifyConsumer, notifyConsumers) = s.notifyConsumers.dequeue
          (
            notifyConsumer.succeed(Right(el)),
            if (notifyConsumers.isEmpty) State.Empty(p)
            else State.Emit(notifyConsumers)
          )
        case s @ State.Error(_)              => (ZIO.interrupt, s)
        case s @ State.Done(_)               => (ZIO.interrupt, s)
        case s @ State.Empty(notifyProducer) => (notifyProducer.await, s)
      }.flatten
    }

  def done(a: Done)(implicit trace: Trace): UIO[Any] =
    ref.modify {
      case State.Emit(notifyConsumers)     => (ZIO.foreachDiscard(notifyConsumers)(_.succeed(Left(a))), State.Done(a))
      case s @ State.Error(_)              => (ZIO.interrupt, s)
      case s @ State.Done(_)               => (ZIO.interrupt, s)
      case s @ State.Empty(notifyProducer) => (notifyProducer.await, s)
    }.flatten

  def error(cause: Cause[Err])(implicit trace: Trace): UIO[Any] =
    ref.modify {
      case State.Emit(notifyConsumers)     => (ZIO.foreachDiscard(notifyConsumers)(_.failCause(cause)), State.Error(cause))
      case s @ State.Error(_)              => (ZIO.interrupt, s)
      case s @ State.Done(_)               => (ZIO.interrupt, s)
      case s @ State.Empty(notifyProducer) => (notifyProducer.await, s)
    }.flatten

  def takeWith[A](
    onError: Cause[Err] => A,
    onElement: Elem => A,
    onDone: Done => A
  )(implicit trace: Trace): UIO[A] =
    Promise.make[Err, Either[Done, Elem]].flatMap { p =>
      ref.modify {
        case State.Emit(notifyConsumers) =>
          (p.await.foldCause(onError, _.fold(onDone, onElement)), State.Emit(notifyConsumers.enqueue(p)))
        case s @ State.Error(a) => (ZIO.succeed(onError(a)), s)
        case s @ State.Done(a)  => (ZIO.succeed(onDone(a)), s)
        case s @ State.Empty(notifyProducer) =>
          (notifyProducer.succeed(()) *> p.await.foldCause(onError, _.fold(onDone, onElement)), State.Emit(Queue(p)))
      }.flatten
    }

  def take[A](implicit trace: Trace): UIO[Exit[Either[Err, Done], Elem]] =
    takeWith(c => Exit.failCause(c.map(Left(_))), Exit.succeed(_), d => Exit.fail(Right(d)))

  def close(implicit trace: Trace): UIO[Any] =
    ZIO.fiberId.flatMap(id => error(Cause.interrupt(id)))

  def awaitRead(implicit trace: Trace): UIO[Any] =
    ref.modify {
      case s @ State.Empty(notifyProducer) => (notifyProducer.await, s)
      case s                               => (ZIO.unit, s)
    }.flatten
}

private[zio] object SingleProducerAsyncInput {
  def make[Err, Elem, Done](implicit trace: Trace): UIO[SingleProducerAsyncInput[Err, Elem, Done]] =
    Promise
      .make[Nothing, Unit]
      .flatMap(p => Ref.make[State[Err, Elem, Done]](State.Empty(p)))
      .map(new SingleProducerAsyncInput(_))

  sealed trait State[Err, Elem, Done]
  object State {
    case class Empty[Err, Elem, Done](notifyProducer: Promise[Nothing, Unit]) extends State[Err, Elem, Done]
    case class Emit[Err, Elem, Done](notifyConsumers: Queue[Promise[Err, Either[Done, Elem]]])
        extends State[Err, Elem, Done]
    case class Error[Err, Elem, Done](cause: Cause[Err]) extends State[Err, Elem, Done]
    case class Done[Err, Elem, Done](done: Done)         extends State[Err, Elem, Done]
  }
}
