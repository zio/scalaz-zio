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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.time.{Duration => JavaDuration, Instant, OffsetDateTime}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration => ScalaDuration, FiniteDuration => ScalaFiniteDuration}
import scala.language.implicitConversions

trait DurationModule {
  type Duration = JavaDuration

  implicit def durationInt(n: Int): DurationSyntax = new DurationSyntax(n.toLong)

  implicit def durationLong(n: Long): DurationSyntax = new DurationSyntax(n)

  implicit def duration2DurationOps(duration: Duration): DurationOps = new DurationOps(duration)

  implicit val durationOrdering: Ordering[Duration] = new Ordering[Duration] {
    override def compare(x: Duration, y: Duration): Int = x.compareTo(y)
  }

}

object Duration {
  val Infinity: Duration = JavaDuration.ofNanos(Long.MaxValue)
  val Zero: Duration     = JavaDuration.ZERO

  object Finite {

    def apply(nanos: Long): Duration =
      if (nanos >= 0) nanos.nanos
      else Duration.Zero

    def unapply(arg: Duration): Option[Long] = if (arg >= Duration.Infinity) None else Some(arg.toNanos)

  }

  def apply(amount: Long, unit: TimeUnit): Duration = apply(amount, toChronoUnit(unit))

  def apply(amount: Long, unit: ChronoUnit): Duration = JavaDuration.of(amount, unit)

  def fromMillis(millis: Long): Duration = JavaDuration.ofMillis(millis)

  def fromNanos(nanos: Long): Duration = nanos.nanos

  def fromInterval(start: Instant, end: Instant): Duration =
    JavaDuration.between(start, end)

  def fromInterval(start: OffsetDateTime, end: OffsetDateTime): Duration =
    JavaDuration.between(start, end)

  def fromInstant(instant: Instant): Duration =
    Duration(instant.toEpochMilli, TimeUnit.MILLISECONDS)

  def fromSeconds(seconds: Long): Duration =
    JavaDuration.ofSeconds(seconds)

  def fromScala(duration: ScalaDuration): Duration = duration match {
    case d: ScalaFiniteDuration => fromNanos(d.toNanos)
    case _                      => Infinity
  }

  def fromJava(duration: JavaDuration): Duration =
    if (duration.isNegative) Zero
    else if (duration.compareTo(Infinity) >= 0) Infinity
    else fromNanos(duration.toNanos)

  private def toChronoUnit(unit: TimeUnit): ChronoUnit = unit match {
    case TimeUnit.NANOSECONDS  => ChronoUnit.NANOS
    case TimeUnit.MICROSECONDS => ChronoUnit.MICROS
    case TimeUnit.MILLISECONDS => ChronoUnit.MILLIS
    case TimeUnit.SECONDS      => ChronoUnit.SECONDS
    case TimeUnit.MINUTES      => ChronoUnit.MINUTES
    case TimeUnit.HOURS        => ChronoUnit.HOURS
    case TimeUnit.DAYS         => ChronoUnit.DAYS
  }
}

final class DurationSyntax(val n: Long) extends AnyVal {
  private[this] def asDuration(unit: TemporalUnit): Duration =
    if (n != 0) JavaDuration.of(n, unit) else Duration.Zero

  def nanoseconds: Duration = asDuration(ChronoUnit.NANOS)
  def nanos                 = nanoseconds
  def nanosecond            = nanoseconds
  def nano                  = nanoseconds

  def microseconds: Duration = asDuration(ChronoUnit.MICROS)
  def micros                 = microseconds
  def microsecond            = microseconds
  def micro                  = microseconds

  def milliseconds: Duration = asDuration(ChronoUnit.MILLIS)
  def millis                 = milliseconds
  def millisecond            = milliseconds
  def milli                  = milliseconds

  def seconds: Duration = asDuration(ChronoUnit.SECONDS)
  def second            = seconds

  def minutes: Duration = asDuration(ChronoUnit.MINUTES)
  def minute            = minutes

  def hours: Duration = asDuration(ChronoUnit.HOURS)
  def hour            = hours

  def days: Duration = asDuration(ChronoUnit.DAYS)
  def day            = days

}

final class DurationOps(private val duration: Duration) extends AnyVal {

  def +(other: Duration): Duration = {
    val thisNanos  = if (duration.toNanos > 0) duration.toNanos else 0
    val otherNanos = if (other.toNanos > 0) other.toNanos else 0
    val sum        = thisNanos + otherNanos
    if (sum >= 0) sum.nanos else Duration.Infinity
  }

  def *(factor: Double): Duration = {
    val nanos = duration.toNanos
    if (factor <= 0 || nanos <= 0) Duration.Zero
    else if (factor <= Long.MaxValue / nanos.toDouble) (nanos * factor).round.nanoseconds
    else Duration.Infinity
  }

  def >=(other: Duration): Boolean = duration.compareTo(other) >= 0
  def <=(other: Duration): Boolean = duration.compareTo(other) <= 0
  def >(other: Duration): Boolean  = duration.compareTo(other) > 0
  def <(other: Duration): Boolean  = duration.compareTo(other) < 0
  def ==(other: Duration): Boolean = duration.compareTo(other) == 0

  def render: String = {
    val nanos = duration.toNanos
    TimeUnit.NANOSECONDS.toMillis(nanos) match {
      case 0                       => s"$nanos ns"
      case millis if millis < 1000 => s"$millis ms"
      case millis if millis < 60000 =>
        val maybeMs = Option(millis % 1000).filterNot(_ == 0)
        s"${millis / 1000} s${maybeMs.fold("")(ms => s" $ms ms")}"
      case millis if millis < 3600000 =>
        val maybeSec = Option((millis % 60000) / 1000).filterNot(_ == 0)
        s"${millis / 60000} m${maybeSec.fold("")(s => s" $s s")}"
      case millis =>
        val days    = millis / 86400000
        val hours   = (millis % 86400000) / 3600000
        val minutes = (millis % 3600000) / 60000
        val seconds = (millis % 60000) / 1000

        List(days, hours, minutes, seconds)
          .zip(List("d", "h", "m", "s"))
          .collect { case (value, unit) if value != 0 => s"$value $unit" }
          .mkString(" ")
    }
  }

  def asScala: ScalaDuration = asFiniteDuration

  def asFiniteDuration: ScalaFiniteDuration = duration match {
    case Duration.Zero => ScalaDuration.Zero
    case _             => ScalaFiniteDuration(duration.toNanos, TimeUnit.NANOSECONDS)
  }

  def asJava: JavaDuration = duration

  def max(other: Duration): Duration = if (duration > other) duration else other

  def min(other: Duration): Duration = if (duration < other) duration else other

  def compare(other: Duration): Int = duration.toNanos compare other.toNanos

  def fold[Z](infinity: => Z, finite: Duration => Z): Z = duration match {
    case Duration.Infinity => infinity
    case f: Duration       => finite(f)
  }

}
