package zio

import cats._
import cats.effect.{Fiber => CFiber, IO => CIO}

import scala.concurrent.ExecutionContext

object BenchmarkUtil extends Runtime[Any] { self =>
  val environment = Runtime.default.environment

  val fiberRefs = Runtime.default.fiberRefs

  val runtimeFlags = Runtime.default.runtimeFlags

  implicit val futureExecutionContext: ExecutionContext =
    ExecutionContext.global

  def repeat[R, E, A](n: Int)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    if (n <= 1) zio
    else zio *> repeat(n - 1)(zio)

  def verify(cond: Boolean)(message: => String): IO[AssertionError, Unit] =
    ZIO.when(!cond)(ZIO.fail(new AssertionError(message))).unit

  def catsForeach[A, B](as: List[A])(f: A => CIO[B]): CIO[List[B]] =
    Traverse[List].traverse(as)(f)

  def catsForeachDiscard[A, B](as: List[A])(f: A => CIO[B]): CIO[Unit] =
    Traverse[List].traverse_(as)(f)

  def catsForkAll[A](as: Iterable[CIO[A]]): CIO[CFiber[CIO, Throwable, List[A]]] = ???

  def catsRepeat[A](n: Int)(io: CIO[A]): CIO[A] =
    if (n <= 1) io
    else io.flatMap(_ => catsRepeat(n - 1)(io))

  def unsafeRun[E, A](zio: ZIO[Any, E, A], fiberRootsEnabled: Boolean = true): A = {
    val rt = if (fiberRootsEnabled) self else NoFiberRootsRuntime
    Unsafe.unsafe(implicit unsafe => rt.unsafe.run(zio).getOrThrowFiberFailure())
  }

  private object NoFiberRootsRuntime extends Runtime[Any] {
    val environment  = Runtime.default.environment
    val fiberRefs    = Runtime.default.fiberRefs
    val runtimeFlags = RuntimeFlags(RuntimeFlag.CooperativeYielding, RuntimeFlag.Interruption)
  }
}
