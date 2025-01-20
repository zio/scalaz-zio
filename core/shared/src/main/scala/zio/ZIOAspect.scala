package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.metrics.{Metric, MetricLabel}
import scala.concurrent.ExecutionContext

trait ZIOAspect[+LowerR, -UpperR, +LowerE, -UpperE, +LowerA, -UpperA] { self =>

  def apply[R >: LowerR <: UpperR, E >: LowerE <: UpperE, A >: LowerA <: UpperA](zio: ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A]

  def >>>[
    LowerR1 >: LowerR,
    UpperR1 <: UpperR,
    LowerE1 >: LowerE,
    UpperE1 <: UpperE,
    LowerA1 >: LowerA,
    UpperA1 <: UpperA
  ](
    that: ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1]
  ): ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1] =
    self.andThen(that)

  /**
   * Returns a new aspect that represents the sequential composition of this
   * aspect with the specified one.
   */
  def @@[
    LowerR1 >: LowerR,
    UpperR1 <: UpperR,
    LowerE1 >: LowerE,
    UpperE1 <: UpperE,
    LowerA1 >: LowerA,
    UpperA1 <: UpperA
  ](
    that: ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1]
  ): ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1] =
    self >>> that

  def andThen[
    LowerR1 >: LowerR,
    UpperR1 <: UpperR,
    LowerE1 >: LowerE,
    UpperE1 <: UpperE,
    LowerA1 >: LowerA,
    UpperA1 <: UpperA
  ](
    that: ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1]
  ): ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1] =
    new ZIOAspect[LowerR1, UpperR1, LowerE1, UpperE1, LowerA1, UpperA1] {
      def apply[R >: LowerR1 <: UpperR1, E >: LowerE1 <: UpperE1, A >: LowerA1 <: UpperA1](
        zio: ZIO[R, E, A]
      )(implicit trace: Trace): ZIO[R, E, A] =
        that(self(zio))
    }

  /**
   * Returns a new aspect that flips the behavior it applies to error and
   * success channels. If the old aspect affected success values in some way,
   * then the new aspect will affect error values in the same way.
   */
  def flip: ZIOAspect[LowerR, UpperR, LowerA, UpperA, LowerE, UpperE] =
    new ZIOAspect[LowerR, UpperR, LowerA, UpperA, LowerE, UpperE] {
      def apply[R >: LowerR <: UpperR, E >: LowerA <: UpperA, A >: LowerE <: UpperE](zio: ZIO[R, E, A])(implicit
        trace: Trace
      ): ZIO[R, E, A] = self(zio.flip).flip
    }
}

object ZIOAspect {

  /**
   * An aspect that annotates each log in this effect with the specified log
   * annotation.
   */
  def annotated(key: String, value: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.logAnnotate(key, value)(zio)
    }

  /**
   * An aspect that annotates each log in this effect with the specified log
   * annotations.
   */
  def annotated(annotations: (String, String)*): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.logAnnotate(annotations.toSet)(zio)
    }

  /**
   * An aspect that prints the results of effects to the console for debugging
   * purposes.
   */
  val debug: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.debug
    }

  /**
   * An aspect that prints the results of an effect to the console for debugging
   * purposes, using a specified user-defined prefix label.
   */
  def debug(prefix: => String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.debug(prefix)
    }

  /**
   * An aspect that logs values by using [[ZIO.log]].
   */
  val logged: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.tap(value => ZIO.log(String.valueOf(value)))
    }

  /**
   * An aspect that logs values using a specified user-defined prefix label,
   * using [[ZIO.log]].
   */
  def logged(label: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      override def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.tap(value => ZIO.log(label + ": " + String.valueOf(value)))
    }

  /**
   * An aspect that logs values using a specified function that convers the
   * value into a log message. The log message is logged using [[ZIO.log]].
   */
  def loggedWith[A](f: A => String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, A] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, A] {
      override def apply[R, E, A0 <: A](zio: ZIO[R, E, A0])(implicit trace: Trace): ZIO[R, E, A0] =
        zio.tap(value => ZIO.log(f(value)))
    }

  /**
   * An aspect that nests all configuration loaded by this workflow under the
   * specified name.
   */
  def nested(name: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.configProviderWith { configProvider =>
          ZIO.withConfigProvider(configProvider.nested(name))(zio)
        }
    }

  /**
   * As aspect that runs effects on the specified `ExecutionContext`.
   */
  def onExecutionContext(ec: ExecutionContext): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.onExecutionContext(ec)
    }

  /**
   * As aspect that runs effects on the specified `Executor`.
   */
  def onExecutor(executor: Executor): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.onExecutor(executor)
    }

  /**
   * As aspect that runs effects with the specified maximum number of fibers for
   * parallel operators.
   */
  def parallel(n: Int): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.withParallelism(n)
    }

  /**
   * As aspect that runs effects with an unbounded maximum number of fibers for
   * parallel operators.
   */
  def parallelUnbounded: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.withParallelismUnbounded
    }

  /**
   * An aspect that retries effects according to the specified schedule.
   */
  def retry[R1, E1](schedule: Schedule[R1, E1, Any]): ZIOAspect[Nothing, R1, Nothing, E1, Nothing, Any] =
    new ZIOAspect[Nothing, R1, Nothing, E1, Nothing, Any] {
      def apply[R <: R1, E <: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.retry(schedule)
    }

  /**
   * An aspect that tags each metric in this effect with the specified tags.
   */
  def tagged(tags: (String, String)*): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.tagged(tags.map { case (key, value) => MetricLabel(key, value) }.toSet)(zio)
    }

  /**
   * An aspect that times out effects.
   */
  def timeoutFail[E1](e: => E1)(d: Duration): ZIOAspect[Nothing, Any, E1, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, E1, Any, Nothing, Any] {
      def apply[R, E >: E1, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.timeoutFail(e)(d)
    }

  /**
   * An aspect that returns effects unchanged.
   */
  val identity: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] = zio
    }
}
