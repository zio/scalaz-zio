package zio

import zio.ZIO.Async
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io.IOException
import scala.annotation.targetName

private[zio] transparent trait ZIOCompanionVersionSpecific {

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * This method allows you to specify the fiber id that is responsible for
   * invoking callbacks provided to the `register` function. This is called the
   * "blocking fiber", because it is stopping the fiber executing the async
   * effect from making progress (although it is not "blocking" a thread).
   * Specifying this fiber id in cases where it is known will improve
   * diagnostics, but not affect the behavior of the returned effect.
   */
  def async[R, E, A](
    register: Unsafe ?=> (ZIO[R, E, A] => Unit) => Unit,
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    Async(
      trace,
      { k =>
        register(using Unsafe)(k); null.asInstanceOf[ZIO[R, E, A]]
      },
      () => blockingOn
    )

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * With this variant, you can specify either a way to cancel the asynchrounous
   * action, or you can return the result right away if no asynchronous
   * operation is required.
   *
   * This method allows you to specify the fiber id that is responsible for
   * invoking callbacks provided to the `register` function. This is called the
   * "blocking fiber", because it is stopping the fiber executing the async
   * effect from making progress (although it is not "blocking" a thread).
   * Specifying this fiber id in cases where it is known will improve
   * diagnostics, but not affect the behavior of the returned effect.
   */
  def asyncInterrupt[R, E, A](
    register: Unsafe ?=> (ZIO[R, E, A] => Unit) => Either[URIO[R, Any], ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed {
      val cancelerRef = new java.util.concurrent.atomic.AtomicReference[URIO[R, Any]](ZIO.unit)

      ZIO
        .Async[R, E, A](
          trace,
          { k =>
            val result = register(using Unsafe)(k(_))

            result match {
              case Left(canceler) => cancelerRef.set(canceler); null.asInstanceOf[ZIO[R, E, A]]
              case Right(done)    => done
            }
          },
          () => blockingOn
        )
        .onInterrupt(cancelerRef.get())
    }

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * With this variant, the registration function may return the result right
   * away, if it turns out that no asynchronous operation is required to
   * complete the operation.
   *
   * This method allows you to specify the fiber id that is responsible for
   * invoking callbacks provided to the `register` function. This is called the
   * "blocking fiber", because it is stopping the fiber executing the async
   * effect from making progress (although it is not "blocking" a thread).
   * Specifying this fiber id in cases where it is known will improve
   * diagnostics, but not affect the behavior of the returned effect.
   */
  def asyncMaybe[R, E, A](
    register: Unsafe ?=> (ZIO[R, E, A] => Unit) => Option[ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    Async(trace, k => register(using Unsafe)(k).orNull, () => blockingOn)

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, catching any exception and translated it into a failed ZIO effect.
   *
   * This method should be used whenever you want to take arbitrary code, which
   * may throw exceptions or not be type safe, and convert it into a ZIO effect,
   * which can safely execute that code whenever the effect is executed.
   *
   * {{{
   * def printLine(line: String): Task[Unit] = ZIO.attempt(println(line))
   * }}}
   */
  def attempt[A](code: Unsafe ?=> A)(implicit trace: Trace): Task[A] =
    ZIO.suspendSucceed {
      try {
        Exit.succeed(code(using Unsafe))
      } catch {
        case t: Throwable =>
          ZIO.isFatalWith { isFatal =>
            if (!isFatal(t))
              ZIO.failCause(Cause.fail(t))
            else
              throw t
          }
      }
    }

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, catching any exception and translated it into a failed ZIO effect.
   *
   * This method should be used whenever you want to take arbitrary code, which
   * may throw exceptions or not be type safe, and convert it into a ZIO effect,
   * which can safely execute that code whenever the effect is executed.
   *
   * This variant expects that the provided code will engage in blocking I/O,
   * and therefore, pro-actively executes the code on a dedicated blocking
   * thread pool, so it won't interfere with the main thread pool that ZIO uses.
   */
  def attemptBlocking[A](effect: Unsafe ?=> A)(implicit trace: Trace): Task[A] =
    ZIO.blocking(ZIO.attempt(effect))

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, catching any exception and translated it into a failed ZIO effect.
   *
   * This method should be used whenever you want to take arbitrary code, which
   * may throw exceptions or not be type safe, and convert it into a ZIO effect,
   * which can safely execute that code whenever the effect is executed.
   *
   * This variant expects that the provided code will engage in blocking I/O,
   * and therefore, pro-actively executes the code on a dedicated blocking
   * thread pool, so it won't interfere with the main thread pool that ZIO uses.
   *
   * Additionally, this variant allows you to specify an effect that will cancel
   * the blocking operation. This effect will be executed if the fiber that is
   * executing the blocking effect is interrupted for any reason.
   */
  def attemptBlockingCancelable[R, A](effect: => A)(cancel: => URIO[R, Any])(implicit trace: Trace): RIO[R, A] =
    ZIO.blocking(ZIO.attempt(effect)).fork.flatMap(_.join).onInterrupt(cancel)

  /**
   * This function is the same as `attempt`, except that it only exposes
   * `IOException`, treating any other exception as fatal.
   */
  def attemptBlockingIO[A](effect: Unsafe ?=> A)(implicit trace: Trace): IO[IOException, A] =
    attemptBlocking(effect).refineToOrDie[IOException]

  /**
   * Wraps the provided effect in a catch-try block. Useful for handling cases
   * where the user-provided effect might throw outside the ZIO effect, but we
   * don't want to incur the performance penalty from the additional flatMap in
   * `ZIO.suspend`.
   */
  inline protected def attemptOrDieZIO[R, E, A](inline effect: ZIO[R, E, A])(using Trace): ZIO[R, E, A] =
    try effect
    catch {
      case t: Throwable =>
        ZIO.isFatalWith { isFatal =>
          if (!isFatal(t)) Exit.die(t)
          else throw t
        }
    }

  /**
   * Returns an effect that, when executed, will cautiously run the provided
   * code, ignoring it success or failure.
   */
  def ignore(code: Unsafe ?=> Any)(implicit trace: Trace): UIO[Unit] =
    ZIO.suspendSucceed {
      try {
        code(using Unsafe)

        Exit.unit
      } catch {
        case t: Throwable =>
          ZIO.isFatalWith { isFatal =>
            if (!isFatal(t))
              Exit.unit
            else
              throw t
          }
      }
    }

  /**
   * Returns an effect that models success with the specified value.
   */
  inline def succeed[A](inline a: Unsafe ?=> A)(implicit inline trace: Trace): ZIO[Any, Nothing, A] =
    ZIO.Sync(trace, () => a(using Unsafe))

  /**
   * Returns a synchronous effect that does blocking and succeeds with the
   * specified value.
   */
  inline def succeedBlocking[A](inline a: Unsafe ?=> A)(implicit trace: Trace): UIO[A] =
    ZIO.blocking(ZIO.succeed(a))

  @targetName("succeed")
  @deprecated("use succeed", "2.1.7")
  private[zio] def _succeedCompat[A](a: Unsafe ?=> A)(implicit trace: Trace): ZIO[Any, Nothing, A] =
    succeed(a)

  @targetName("succeedBlocking")
  @deprecated("use succeedBlocking", "2.1.7")
  private[zio] def _succeedBlockingCompat[A](a: Unsafe ?=> A)(implicit trace: Trace): UIO[A] =
    ZIO.blocking(ZIO.succeed(a))

}
