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

package zio

import zio.Clock.ClockLive
import zio.internal.stacktracer.Tracer
import zio.Scheduler
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.Schedule.Decision._

import java.lang.{System => JSystem}
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, OffsetDateTime, ZoneId}
import java.util.concurrent.TimeUnit

trait Clock extends Serializable { self =>

  def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long]

  def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long]

  def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime]

  def instant(implicit trace: Trace): UIO[java.time.Instant]

  def javaClock(implicit trace: Trace): UIO[java.time.Clock]

  def localDateTime(implicit trace: Trace): UIO[java.time.LocalDateTime]

  def nanoTime(implicit trace: Trace): UIO[Long]

  def scheduler(implicit trace: Trace): UIO[Scheduler]

  def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit]

  private[zio] trait UnsafeAPI {
    def currentTime(unit: TimeUnit)(implicit unsafe: Unsafe): Long
    def currentTime(unit: ChronoUnit)(implicit unsafe: Unsafe): Long
    def currentDateTime()(implicit unsafe: Unsafe): OffsetDateTime
    def instant()(implicit unsafe: Unsafe): Instant
    def localDateTime()(implicit unsafe: Unsafe): LocalDateTime
    def nanoTime()(implicit unsafe: Unsafe): Long
  }

  private[zio] def unsafe: UnsafeAPI = new UnsafeAPI {
    def currentTime(unit: TimeUnit)(implicit unsafe: Unsafe): Long =
      Runtime.default.unsafe.run(self.currentTime(unit)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure

    def currentTime(unit: ChronoUnit)(implicit unsafe: Unsafe): Long =
      Runtime.default.unsafe
        .run(self.currentTime(unit)(Trace.empty, DummyImplicit.dummyImplicit))(Trace.empty, unsafe)
        .getOrThrowFiberFailure

    def currentDateTime()(implicit unsafe: Unsafe): OffsetDateTime =
      Runtime.default.unsafe.run(self.currentDateTime(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure

    def instant()(implicit unsafe: Unsafe): Instant =
      Runtime.default.unsafe.run(self.instant(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure

    def localDateTime()(implicit unsafe: Unsafe): LocalDateTime =
      Runtime.default.unsafe.run(self.localDateTime(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure

    def nanoTime()(implicit unsafe: Unsafe): Long =
      Runtime.default.unsafe.run(self.nanoTime(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure
  }
}

object Clock extends ClockPlatformSpecific with Serializable {

  val tag: Tag[Clock] = Tag[Clock]

  /**
   * An implementation of the `Clock` service backed by a `java.time.Clock`.
   */
  final case class ClockJava(clock: java.time.Clock) extends Clock {
    def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] =
      ZIO.succeedUnsafe(implicit u => unsafe.currentDateTime())
    def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] =
      ZIO.succeedUnsafe(implicit u => unsafe.currentTime(unit))
    def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] =
      ZIO.succeedUnsafe(implicit u => unsafe.currentTime(unit))
    def instant(implicit trace: Trace): UIO[Instant] =
      ZIO.succeedUnsafe(implicit u => unsafe.instant())
    def javaClock(implicit trace: Trace): UIO[java.time.Clock] =
      ZIO.succeed(clock)
    def localDateTime(implicit trace: Trace): UIO[LocalDateTime] =
      ZIO.succeedUnsafe(implicit u => unsafe.localDateTime())
    def nanoTime(implicit trace: Trace): UIO[Long] =
      ZIO.succeedUnsafe(implicit u => unsafe.nanoTime())
    def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
      ZIO.asyncInterruptUnsafe { implicit u => cb =>
        val canceler = globalScheduler.schedule(() => cb(ZIO.unit), duration)
        Left(ZIO.succeed(canceler()))
      }
    def scheduler(implicit trace: Trace): UIO[Scheduler] =
      ZIO.succeed(globalScheduler)

    @transient override private[zio] val unsafe: UnsafeAPI = new UnsafeAPI {
      override def currentTime(unit: TimeUnit)(implicit unsafe: Unsafe): Long = {
        val inst = instant()
        unit match {
          case TimeUnit.NANOSECONDS =>
            inst.getEpochSecond * 1000000000 + inst.getNano
          case TimeUnit.MICROSECONDS =>
            inst.getEpochSecond * 1000000 + inst.getNano / 1000
          case _ => unit.convert(inst.toEpochMilli, TimeUnit.MILLISECONDS)
        }
      }

      override def currentTime(unit: ChronoUnit)(implicit unsafe: Unsafe): Long =
        unit.between(Instant.EPOCH, instant())

      override def currentDateTime()(implicit unsafe: Unsafe): OffsetDateTime =
        OffsetDateTime.now(clock)

      override def instant()(implicit unsafe: Unsafe): Instant =
        clock.instant()

      override def localDateTime()(implicit unsafe: Unsafe): LocalDateTime =
        LocalDateTime.now(clock)

      override def nanoTime()(implicit unsafe: Unsafe): Long =
        currentTime(TimeUnit.NANOSECONDS)
    }
  }

  object ClockLive extends Clock {
    def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] =
      ZIO.succeedUnsafe(implicit u => unsafe.currentTime(unit))

    def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] =
      ZIO.succeedUnsafe(implicit u => unsafe.currentTime(unit))

    def nanoTime(implicit trace: Trace): UIO[Long] =
      ZIO.succeedUnsafe(implicit u => unsafe.nanoTime())

    def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
      ZIO.asyncInterruptUnsafe { implicit u => cb =>
        val canceler = globalScheduler.schedule(() => cb(ZIO.unit), duration)
        Left(ZIO.succeed(canceler()))
      }

    def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] =
      ZIO.succeedUnsafe(implicit u => unsafe.currentDateTime())

    override def instant(implicit trace: Trace): UIO[Instant] =
      ZIO.succeedUnsafe(implicit u => unsafe.instant())

    override def localDateTime(implicit trace: Trace): UIO[LocalDateTime] =
      ZIO.succeedUnsafe(implicit u => unsafe.localDateTime())

    def scheduler(implicit trace: Trace): UIO[Scheduler] =
      ZIO.succeed(globalScheduler)

    def javaClock(implicit trace: Trace): UIO[java.time.Clock] = {

      final case class JavaClock(zoneId: ZoneId) extends java.time.Clock {
        def getZone(): ZoneId =
          zoneId
        def instant(): Instant =
          Instant.now
        override def withZone(zoneId: ZoneId): JavaClock =
          copy(zoneId = zoneId)
      }

      ZIO.succeed(JavaClock(ZoneId.systemDefault))
    }

    @transient override private[zio] val unsafe: UnsafeAPI = new UnsafeAPI {
      override def currentTime(unit: TimeUnit)(implicit unsafe: Unsafe): Long = {
        val inst = instant()
        // A nicer solution without loss of precision or range would be
        // unit.toChronoUnit.between(Instant.EPOCH, inst)
        // However, ChronoUnit is not available on all platforms
        unit match {
          case TimeUnit.NANOSECONDS =>
            inst.getEpochSecond() * 1000000000 + inst.getNano()
          case TimeUnit.MICROSECONDS =>
            inst.getEpochSecond() * 1000000 + inst.getNano() / 1000
          case _ => unit.convert(inst.toEpochMilli(), TimeUnit.MILLISECONDS)
        }
      }

      override def currentTime(unit: ChronoUnit)(implicit unsafe: Unsafe): Long =
        unit.between(Instant.EPOCH, instant())

      override def currentDateTime()(implicit unsafe: Unsafe): OffsetDateTime =
        OffsetDateTime.now()

      override def instant()(implicit unsafe: Unsafe): Instant =
        Instant.now()

      override def localDateTime()(implicit unsafe: Unsafe): LocalDateTime =
        LocalDateTime.now()

      override def nanoTime()(implicit unsafe: Unsafe): Long =
        JSystem.nanoTime
    }
  }

  /**
   * Returns the current time, relative to the Unix epoch.
   */
  def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] =
    ZIO.clockWith(_.currentTime(unit))

  def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] =
    ZIO.clockWith(_.currentTime(unit))

  /**
   * Get the current time, represented in the current timezone.
   */
  def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] =
    ZIO.clockWith(_.currentDateTime)

  def instant(implicit trace: Trace): UIO[java.time.Instant] =
    ZIO.clockWith(_.instant)

  /**
   * Constructs a `java.time.Clock` backed by the `Clock` service.
   */
  def javaClock(implicit trace: Trace): UIO[java.time.Clock] =
    ZIO.clockWith(_.javaClock)

  def localDateTime(implicit trace: Trace): UIO[java.time.LocalDateTime] =
    ZIO.clockWith(_.localDateTime)

  /**
   * Returns the system nano time, which is not relative to any date.
   */
  def nanoTime(implicit trace: Trace): UIO[Long] =
    ZIO.clockWith(_.nanoTime)

  /**
   * Returns the scheduler used for scheduling effects.
   */
  def scheduler(implicit trace: Trace): UIO[Scheduler] =
    ZIO.clockWith(_.scheduler)

  /**
   * Sleeps for the specified duration. This is always asynchronous.
   */
  def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
    ZIO.clockWith(_.sleep(duration))

}
