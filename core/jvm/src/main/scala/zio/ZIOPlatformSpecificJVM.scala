package zio

import zio.interop.javaz

import java.nio.channels.CompletionHandler
import java.util.concurrent.{CompletableFuture, CompletionStage, Future}

private[zio] trait ZIOPlatformSpecificJVM {

  def asyncWithCompletionHandler[T](op: CompletionHandler[T, Any] => Any)(implicit trace: Trace): Task[T] =
    javaz.asyncWithCompletionHandler(op)

  def fromCompletionStage[A](cs: => CompletionStage[A])(implicit trace: Trace): Task[A] =
    javaz.fromCompletionStage(cs)

  /**
   * Alias for `formCompletionStage` for a concrete implementation of
   * CompletionStage
   */
  def fromCompletableFuture[A](cs: => CompletableFuture[A])(implicit trace: Trace): Task[A] =
    fromCompletionStage(cs)

  /**
   * WARNING: this uses the blocking Future#get, consider using
   * `fromCompletionStage`
   */
  def fromFutureJava[A](future: => Future[A])(implicit trace: Trace): Task[A] = javaz.fromFutureJava(future)

  /**
   * Lifts a value of `A`, converting it into an error as an option in the error
   * channel when its value is `null`, making it easier to interop with Java
   * code.
   */
  final def fromNullable[A](v: => A)(implicit trace: Trace): IO[Option[Nothing], A] =
    ZIO.suspendSucceed {
      val v0 = v
      if (v0 == null) Exit.failNone
      else Exit.succeed(v0)
    }

}
