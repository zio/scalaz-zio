/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio.Clock.ClockLive
import zio._
import zio.internal.FiberRuntime
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, OffsetDateTime, ZoneId}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.SortedSet
import scala.collection.{mutable => mu}

/**
 * `TestClock` makes it easy to deterministically and efficiently test effects
 * involving the passage of time.
 *
 * Instead of waiting for actual time to pass, `sleep` and methods implemented
 * in terms of it schedule effects to take place at a given clock time. Users
 * can adjust the clock time using the `adjust` and `setTime` methods, and all
 * effects scheduled to take place on or before that time will automatically be
 * run in order.
 *
 * For example, here is how we can test `ZIO#timeout` using `TestClock`:
 *
 * {{{
 *   import zio.ZIO
 *   import zio.test.TestClock
 *
 *   for {
 *     fiber  <- ZIO.sleep(5.minutes).timeout(1.minute).fork
 *     _      <- TestClock.adjust(1.minute)
 *     result <- fiber.join
 *   } yield result == None
 * }}}
 *
 * Note how we forked the fiber that `sleep` was invoked on. Calls to `sleep`
 * and methods derived from it will semantically block until the time is set to
 * on or after the time they are scheduled to run. If we didn't fork the fiber
 * on which we called sleep we would never get to set the time on the line
 * below. Thus, a useful pattern when using `TestClock` is to fork the effect
 * being tested, then adjust the clock time, and finally verify that the
 * expected effects have been performed.
 *
 * For example, here is how we can test an effect that recurs with a fixed
 * delay:
 *
 * {{{
 *   import zio.Queue
 *   import zio.test.TestClock
 *
 *   for {
 *     q <- Queue.unbounded[Unit]
 *     _ <- q.offer(()).delay(60.minutes).forever.fork
 *     a <- q.poll.map(_.isEmpty)
 *     _ <- TestClock.adjust(60.minutes)
 *     b <- q.take.as(true)
 *     c <- q.poll.map(_.isEmpty)
 *     _ <- TestClock.adjust(60.minutes)
 *     d <- q.take.as(true)
 *     e <- q.poll.map(_.isEmpty)
 *   } yield a && b && c && d && e
 * }}}
 *
 * Here we verify that no effect is performed before the recurrence period, that
 * an effect is performed after the recurrence period, and that the effect is
 * performed exactly once. The key thing to note here is that after each
 * recurrence the next recurrence is scheduled to occur at the appropriate time
 * in the future, so when we adjust the clock by 60 minutes exactly one value is
 * placed in the queue, and when we adjust the clock by another 60 minutes
 * exactly one more value is placed in the queue.
 */
trait TestClock extends Clock with Restorable {
  def adjust(duration: Duration)(implicit trace: Trace): UIO[Unit]
  def adjustWith[R, E, A](duration: Duration)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  def setTime(instant: Instant)(implicit trace: Trace): UIO[Unit]
  def setTimeZone(zone: ZoneId)(implicit trace: Trace): UIO[Unit]
  def sleeps(implicit trace: Trace): UIO[List[Instant]]
  def timeZone(implicit trace: Trace): UIO[ZoneId]
}

object TestClock extends Serializable {

  final case class Test(
    clockState: Ref.Atomic[TestClock.Data],
    live: Live,
    annotations: Annotations,
    warningState: Ref.Synchronized[TestClock.WarningData],
    suspendedWarningState: Ref.Synchronized[TestClock.SuspendedWarningData]
  ) extends TestClock
      with TestClockPlatformSpecific {

    /**
     * See https://github.com/zio/zio/issues/9385
     */
    private[this] val freezeLock = Semaphore.unsafe.make(1)(Unsafe)

    /**
     * Increments the current clock time by the specified duration. Any effects
     * that were scheduled to occur on or before the new time will be run in
     * order.
     */
    def adjust(duration: Duration)(implicit trace: Trace): UIO[Unit] =
      warningDone *> run(_.plus(duration))

    /**
     * Increments the current clock time by the specified duration. Any effects
     * that were scheduled to occur on or before the new time will be run in
     * order.
     */
    def adjustWith[R, E, A](duration: Duration)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      zio <& adjust(duration)

    /**
     * Returns the current clock time as an `OffsetDateTime`.
     */
    def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] =
      warningStart *> ZIO.succeed(unsafe.currentDateTime()(Unsafe.unsafe))

    /**
     * Returns the current clock time in the specified time unit.
     */
    def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] =
      warningStart *> ZIO.succeed(unsafe.currentTime(unit)(Unsafe.unsafe))

    def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] =
      warningStart *> ZIO.succeed(unsafe.currentTime(unit)(Unsafe.unsafe))

    /**
     * Returns the current clock time in nanoseconds.
     */
    def nanoTime(implicit trace: Trace): UIO[Long] =
      warningStart *> ZIO.succeed(unsafe.nanoTime()(Unsafe.unsafe))

    /**
     * Returns the current clock time as an `Instant`.
     */
    def instant(implicit trace: Trace): UIO[Instant] =
      warningStart *> ZIO.succeed(unsafe.instant()(Unsafe.unsafe))

    /**
     * Constructs a `java.time.Clock` backed by the `Clock` service.
     */
    def javaClock(implicit trace: Trace): UIO[java.time.Clock] = {

      final case class JavaClock(clockState: Ref.Atomic[TestClock.Data], zoneId: ZoneId) extends java.time.Clock {
        def getZone(): ZoneId =
          zoneId
        def instant(): Instant =
          clockState.unsafe.get(Unsafe.unsafe).instant
        override def withZone(zoneId: ZoneId): JavaClock =
          copy(zoneId = zoneId)
      }

      clockState.get.map(data => JavaClock(clockState, data.timeZone))
    }

    /**
     * Returns the current clock time as a `LocalDateTime`.
     */
    def localDateTime(implicit trace: Trace): UIO[LocalDateTime] =
      warningStart *> ZIO.succeed(unsafe.localDateTime()(Unsafe.unsafe))

    /**
     * Saves the `TestClock`'s current state in an effect which, when run, will
     * restore the `TestClock` state to the saved state
     */
    def save(implicit trace: Trace): UIO[UIO[Unit]] =
      for {
        clockData <- clockState.get
      } yield clockState.set(clockData)

    /**
     * Sets the current clock time to the specified `Instant`. Any effects that
     * were scheduled to occur on or before the new time will be run in order.
     */
    def setTime(instant: Instant)(implicit trace: Trace): UIO[Unit] =
      warningDone *> run(_ => instant)

    /**
     * Sets the time zone to the specified time zone. The clock time in terms of
     * nanoseconds since the epoch will not be adjusted and no scheduled effects
     * will be run as a result of this method.
     */
    def setTimeZone(zone: ZoneId)(implicit trace: Trace): UIO[Unit] =
      clockState.update(_.copy(timeZone = zone))

    /**
     * Semantically blocks the current fiber until the clock time is equal to or
     * greater than the specified duration. Once the clock time is adjusted to
     * on or after the duration, the fiber will automatically be resumed.
     */
    def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
      ZIO.suspendSucceedUnsafe { implicit unsafe =>
        val duration0 = duration
        if (duration0 <= Duration.Zero) Exit.unit
        else if (duration0 == Duration.Infinity) ZIO.never
        else {
          clockState.unsafe.modify { data =>
            val end = data.instant.plus(duration0)
            if (end.isAfter(data.instant)) {
              val promise = Promise.unsafe.make[Nothing, Unit](FiberId.None)
              (warningStart *> promise.await, data.copy(sleeps = (end, promise) :: data.sleeps))
            } else
              (Exit.unit, data)
          }
        }
      }

    /**
     * Returns a list of the times at which all queued effects are scheduled to
     * resume.
     */
    def sleeps(implicit trace: Trace): UIO[List[Instant]] =
      clockState.get.map(_.sleeps.map(_._1))

    /**
     * Returns the time zone.
     */
    def timeZone(implicit trace: Trace): UIO[ZoneId] =
      clockState.get.map(_.timeZone)

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def currentTime(unit: TimeUnit)(implicit unsafe: Unsafe): Long =
          unit.convert(clockState.unsafe.get.instant.toEpochMilli, TimeUnit.MILLISECONDS)

        override def currentTime(unit: ChronoUnit)(implicit unsafe: Unsafe): Long =
          unit.between(Instant.EPOCH, clockState.unsafe.get.instant)

        override def currentDateTime()(implicit unsafe: Unsafe): OffsetDateTime = {
          val data = clockState.unsafe.get
          OffsetDateTime.ofInstant(data.instant, data.timeZone)
        }

        override def instant()(implicit unsafe: Unsafe): Instant =
          clockState.unsafe.get.instant

        override def localDateTime()(implicit unsafe: Unsafe): LocalDateTime = {
          val data = clockState.unsafe.get
          LocalDateTime.ofInstant(data.instant, data.timeZone)
        }

        override def nanoTime()(implicit unsafe: Unsafe): Long =
          currentTime(ChronoUnit.NANOS)
      }

    /**
     * Cancels the warning message that is displayed if a test is advancing the
     * `TestClock` but a fiber is not suspending.
     */
    private[TestClock] def suspendedWarningDone(implicit trace: Trace): UIO[Unit] =
      suspendedWarningState.updateSomeZIO { case SuspendedWarningData.Pending(cancel) =>
        ZIO.succeed {
          cancel()
          SuspendedWarningData.start
        }
      }

    /**
     * Cancels the warning message that is displayed if a test is using time but
     * is not advancing the `TestClock`.
     */
    private[TestClock] def warningDone(implicit trace: Trace): UIO[Unit] =
      warningState.updateSomeZIO {
        case WarningData.Pending(cancelWarning) =>
          ZIO.succeed {
            cancelWarning()
            WarningData.done
          }
        case WarningData.Start => Exit.succeed(WarningData.done)
      }

    /**
     * Polls until all descendants of this fiber are done or suspended.
     */
    private def awaitSuspended(implicit trace: Trace): UIO[Unit] =
      ZIO.suspendSucceed {
        val ref = new AtomicBoolean(false)

        def allSuspendedUnchanged(
          l: collection.Map[FiberId, Fiber.Status],
          r: collection.Map[FiberId, Fiber.Status]
        ): Boolean = {
          val it = r.iterator
          while (it.hasNext) {
            val rkv = it.next()
            val lv  = l.getOrElse(rkv._1, null) // If missing, it means it transitioned to Done so we can ignore it
            if (lv != rkv._2) return false
          }
          true
        }

        // Sleep and yield a few times to give suspended fibers a chance to resume
        def f(d: Duration) = ClockLive.sleep(d) *> freeze

        f(5.millis).zipWith(f(5.millis))(allSuspendedUnchanged)
          .flatMap {
            if (_) ZIO.succeed(ref.get)
            else if (ref.compareAndSet(false, true)) suspendedWarningStart *> Exit.failUnit
            else Exit.failUnit
          }
          .eventually
          .flatMap(ZIO.whenDiscard(_)(suspendedWarningDone))
      }

    /**
     * Captures a "snapshot" of the identifier and status of all fibers in this
     * test other than the current fiber. Fails with the `Unit` value if any of
     * these fibers are not done or suspended. Note that because we cannot
     * synchronize on the status of multiple fibers at the same time this
     * snapshot may not be fully consistent.
     */
    private val freeze: IO[Unit, collection.Map[FiberId, Fiber.Status]] = {
      implicit val trace: Trace = Trace.empty
      freezeLock.withPermit {
        ZIO.fiberIdWith { fiberId =>
          annotations.get(TestAnnotation.fibers).flatMap {
            case _: Left[?, ?] => Exit.succeed(Map.empty)
            case Right(refs) =>
              val map  = mu.HashMap.empty[FiberId, Fiber.Status]
              val it   = refs.iterator.flatMap(_.get())
              var fail = false
              while (it.hasNext && !fail) {
                val f = it.next().asInstanceOf[FiberRuntime[Any, Any]]
                if (f.id ne fiberId) f.getStatus() match {
                  case s: Fiber.Status.Suspended => map.update(f.id, s)
                  case Fiber.Status.Done         => ()
                  case _                         => fail = true
                }
              }
              if (fail) Exit.failUnit else Exit.succeed(map)
          }
        }
      }
    }

    /**
     * Returns a set of all fibers in this test.
     */
    def supervisedFibers(implicit trace: Trace): UIO[SortedSet[Fiber.Runtime[Any, Any]]] =
      ZIO.fiberIdWith { fiberId =>
        annotations.get(TestAnnotation.fibers).map {
          case Left(_) => SortedSet.empty
          case Right(refs) =>
            val builder = SortedSet.newBuilder[Fiber.Runtime[Any, Any]]
            refs.foreach(_.get().foreach(f => if (f.id != fiberId) builder += f))
            builder.result()
        }
      }

    /**
     * Runs all effects scheduled to occur on or before the specified instant,
     * which may depend on the current time, in order.
     */
    private def run(f: Instant => Instant)(implicit trace: Trace): UIO[Unit] =
      awaitSuspended *>
        clockState.modify { data =>
          val end = f(data.instant)
          data.sleeps.sortBy(_._1) match {
            case (instant, promise) :: sleeps if !end.isBefore(instant) =>
              (Some((end, promise)), Data(instant, sleeps, data.timeZone))
            case _ => (None, Data(end, data.sleeps, data.timeZone))
          }
        }.flatMap {
          case None => Exit.unit
          case Some((end, promise)) =>
            promise.unsafe.done(Exit.unit)(Unsafe)
            ZIO.yieldNow *> run(_ => end)
        }

    /**
     * Forks a fiber that will display a warning message if a test is advancing
     * the `TestClock` but a fiber is not suspending.
     */
    private def suspendedWarningStart(implicit trace: Trace): UIO[Unit] =
      suspendedWarningState.updateSomeZIO { case SuspendedWarningData.Start =>
        ZIO.succeedUnsafe { implicit unsafe =>
          val f = ZIO
            .logWarning(suspendedWarning)
            .zipRight(suspendedWarningState.set(SuspendedWarningData.done))
          val cancel = Clock.globalScheduler.schedule(() => Runtime.default.unsafe.run(f), 5.seconds)
          SuspendedWarningData.pending(cancel)
        }
      }

    /**
     * Forks a fiber that will display a warning message if a test is using time
     * but is not advancing the `TestClock`.
     */
    private def warningStart(implicit trace: Trace): UIO[Unit] =
      warningState.updateSomeZIO { case WarningData.Start =>
        ZIO.succeedUnsafe { implicit unsafe =>
          val f      = ZIO.logWarning(warning)
          val cancel = Clock.globalScheduler.schedule(() => Runtime.default.unsafe.run(f), 5.seconds)
          WarningData.pending(cancel)
        }
      }

  }

  /**
   * Constructs a new `Test` object that implements the `TestClock` interface.
   * This can be useful for mixing in with implementations of other interfaces.
   */
  def live(
    data: Data
  )(implicit
    trace: Trace
  ): ZLayer[Annotations with Live, Nothing, TestClock] =
    ZLayer.scoped {
      ZIO.environmentWithZIO { env =>
        val live                  = env.get[Live]
        val annotations           = env.get[Annotations]
        val scope                 = env.getScope
        val warningState          = Ref.Synchronized.unsafe.make(WarningData.start)(Unsafe)
        val suspendedWarningState = Ref.Synchronized.unsafe.make(SuspendedWarningData.start)(Unsafe)
        val clockState            = Ref.unsafe.make(data)(Unsafe)
        val test                  = Test(clockState, live, annotations, warningState, suspendedWarningState)
        ZIO.withClockScoped(test) *> scope.addFinalizer(test.warningDone *> test.suspendedWarningDone).as(test)
      }
    }

  val any: ZLayer[TestClock, Nothing, TestClock] =
    ZLayer.environment[TestClock](Tracer.newTrace)

  val default: ZLayer[Live with Annotations, Nothing, TestClock] =
    live(Data(Instant.EPOCH, Nil, ZoneId.of("UTC")))(Tracer.newTrace)

  /**
   * Accesses a `TestClock` instance in the environment and increments the time
   * by the specified duration, running any actions scheduled for on or before
   * the new time in order.
   */
  def adjust(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
    testClockWith(_.adjust(duration))

  def adjustWith[R, E, A](duration: => Duration)(zio: ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    testClockWith(_.adjustWith(duration)(zio))

  /**
   * Accesses a `TestClock` instance in the environment and saves the clock
   * state in an effect which, when run, will restore the `TestClock` to the
   * saved state.
   */
  def save(implicit trace: Trace): UIO[UIO[Unit]] =
    testClockWith(_.save)

  /**
   * Accesses a `TestClock` instance in the environment and sets the clock time
   * to the specified `Instant`, running any actions scheduled for on or before
   * the new time in order.
   */
  def setTime(instant: => Instant)(implicit trace: Trace): UIO[Unit] =
    testClockWith(_.setTime(instant))

  /**
   * Accesses a `TestClock` instance in the environment, setting the time zone
   * to the specified time zone. The clock time in terms of nanoseconds since
   * the epoch will not be altered and no scheduled actions will be run as a
   * result of this effect.
   */
  def setTimeZone(zone: => ZoneId)(implicit trace: Trace): UIO[Unit] =
    testClockWith(_.setTimeZone(zone))

  /**
   * Accesses a `TestClock` instance in the environment and returns a list of
   * times that effects are scheduled to run.
   */
  def sleeps(implicit trace: Trace): UIO[List[Instant]] =
    testClockWith(_.sleeps)

  /**
   * Accesses a `TestClock` instance in the environment and returns the current
   * time zone.
   */
  def timeZone(implicit trace: Trace): UIO[ZoneId] =
    testClockWith(_.timeZone)

  /**
   * `Data` represents the state of the `TestClock`, including the clock time
   * and time zone.
   */
  final case class Data(
    instant: Instant,
    sleeps: List[(Instant, Promise[Nothing, Unit])],
    timeZone: ZoneId
  )

  /**
   * `Sleep` represents the state of a scheduled effect, including the time the
   * effect is scheduled to run, a promise that can be completed to resume
   * execution of the effect, and the fiber executing the effect.
   */
  final case class Sleep(duration: Duration, promise: Promise[Nothing, Unit], fiberId: FiberId)

  /**
   * `WarningData` describes the state of the warning message that is displayed
   * if a test is using time by is not advancing the `TestClock`. The possible
   * states are `Start` if a test has not used time, `Pending` if a test has
   * used time but has not adjusted the `TestClock`, and `Done` if a test has
   * adjusted the `TestClock` or the warning message has already been displayed.
   */
  private[TestClock] sealed abstract class WarningData

  private object WarningData {

    case object Start                                      extends WarningData
    final case class Pending(cancelWarning: () => Boolean) extends WarningData
    case object Done                                       extends WarningData

    /**
     * State indicating that a test has not used time.
     */
    val start: WarningData = Start

    /**
     * State indicating that a test has used time but has not adjusted the
     * `TestClock` with a reference to the fiber that will display the warning
     * message.
     */
    def pending(cancel: () => Boolean): WarningData = Pending(cancel)

    /**
     * State indicating that a test has used time or the warning message has
     * already been displayed.
     */
    val done: WarningData = Done
  }

  private[TestClock] sealed abstract class SuspendedWarningData

  private object SuspendedWarningData {

    case object Start                                      extends SuspendedWarningData
    final case class Pending(cancelWarning: () => Boolean) extends SuspendedWarningData
    case object Done                                       extends SuspendedWarningData

    /**
     * State indicating that a test has not adjusted the clock.
     */
    val start: SuspendedWarningData = Start

    /**
     * State indicating that a test has adjusted the clock but a fiber is still
     * running with a reference to the fiber that will display the warning
     * message.
     */
    def pending(cancelToken: () => Boolean): SuspendedWarningData = Pending(cancelToken)

    /**
     * State indicating that the warning message has already been displayed.
     */
    val done: SuspendedWarningData = Done
  }

  /**
   * The warning message that will be displayed if a test is using time but is
   * not advancing the `TestClock`.
   */
  private val warning =
    "Warning: A test is using time, but is not advancing the test clock, " +
      "which may result in the test hanging. Use TestClock.adjust to " +
      "manually advance the time."

  /**
   * The warning message that will be displayed if a test is advancing the clock
   * but a fiber is still running.
   */
  private val suspendedWarning =
    "Warning: A test is advancing the test clock, but a fiber is not " +
      "suspending, which may result in the test hanging. Use " +
      "TestAspect.diagnose to identity the fiber that is not suspending."
}
