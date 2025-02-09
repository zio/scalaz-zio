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

import zio.{Duration, Trace}

import java.time._
import scala.annotation.nowarn
import scala.collection.JavaConverters._

trait TimeVariants {

  /**
   * A generator of `java.time.DayOfWeek` values. Shrinks toward
   * `DayOfWeek.MONDAY`.
   */
  final def dayOfWeek(implicit trace: Trace): Gen[Any, DayOfWeek] =
    Gen.elements(
      DayOfWeek.MONDAY,
      DayOfWeek.TUESDAY,
      DayOfWeek.WEDNESDAY,
      DayOfWeek.THURSDAY,
      DayOfWeek.FRIDAY,
      DayOfWeek.SATURDAY,
      DayOfWeek.SUNDAY
    )

  /**
   * A generator of finite `zio.duration.Duration` values. Shrinks toward
   * `Duration.Zero`.
   */
  final def finiteDuration(implicit trace: Trace): Gen[Any, Duration] =
    Gen.long(0L, Long.MaxValue).map(Duration.Finite(_))

  /**
   * A generator of finite `zio.duration.Duration` values inside the specified
   * range: [min, max]. Shrinks toward min.
   */
  final def finiteDuration(min: Duration, max: Duration)(implicit trace: Trace): Gen[Any, Duration] =
    Gen.long(min.toNanos, max.toNanos).map(Duration.Finite(_))

  /**
   * A generator of `java.time.Instant` values. Shrinks toward `Instant.MIN`.
   */
  final def instant(implicit trace: Trace): Gen[Any, Instant] =
    instant(Instant.MIN, Instant.MAX)

  /**
   * A generator of `java.time.Instant` values inside the specified range: [min,
   * max]. Shrinks toward min.
   */
  final def instant(min: Instant, max: Instant)(implicit trace: Trace): Gen[Any, Instant] = {

    def genSecond(min: Instant, max: Instant): Gen[Any, Long] =
      Gen.long(min.getEpochSecond, max.getEpochSecond)

    def genNano(min: Instant, max: Instant, second: Long): Gen[Any, Long] = {
      val minNano = if (min.getEpochSecond == second) min.getNano.toLong else 0L
      val maxNano = if (max.getEpochSecond == second) max.getNano.toLong else 1000000000L
      Gen.long(minNano, maxNano)
    }

    for {
      second       <- genSecond(min, max)
      nanoFraction <- genNano(min, max, second)
    } yield Instant.ofEpochSecond(second, nanoFraction)
  }

  /**
   * A generator of `java.time.LocalDate` values. Shrinks toward
   * `LocalDate.MIN`.
   */
  final def localDate(implicit trace: Trace): Gen[Any, LocalDate] =
    localDateTime.map(_.toLocalDate)

  /**
   * A generator for `java.time.LocalDate` values inside the specified range:
   * [min, max]. Shrinks towards min.
   */
  final def localDate(min: LocalDate, max: LocalDate)(implicit trace: Trace): Gen[Any, LocalDate] =
    localDateTime(min.atStartOfDay(), max.atTime(LocalTime.MAX)).map(_.toLocalDate)

  /**
   * A generator of `java.time.LocalDateTime` values. Shrinks toward
   * `LocalDateTime.MIN`.
   */
  final def localDateTime(implicit trace: Trace): Gen[Any, LocalDateTime] =
    localDateTime(LocalDateTime.MIN, LocalDateTime.MAX)

  /**
   * A generator of `java.time.LocalDateTime` values inside the specified range:
   * [min, max]. Shrinks toward min.
   */
  final def localDateTime(min: LocalDateTime, max: LocalDateTime)(implicit
    trace: Trace
  ): Gen[Any, LocalDateTime] =
    instant(min.toInstant(utc), max.toInstant(utc)).map(LocalDateTime.ofInstant(_, utc))

  /**
   * A generator of `java.time.LocalTime` values within the specified range:
   * [min, max]. Shrinks toward `LocalTime.MIN`.
   */
  final def localTime(min: LocalTime, max: LocalTime)(implicit trace: Trace): Gen[Any, LocalTime] =
    localDateTime(min.atDate(LocalDate.MIN), max.atDate(LocalDate.MIN)).map(_.toLocalTime)

  /**
   * A generator of `java.time.LocalTime` values. Shrinks toward
   * `LocalTime.MIN`.
   */
  final def localTime(implicit trace: Trace): Gen[Any, LocalTime] =
    localTime(LocalTime.MIN, LocalTime.MAX)

  /**
   * A generator of `java.time.Month` values. Shrinks toward `Month.JANUARY`.
   */
  final def month(implicit trace: Trace): Gen[Any, Month] =
    Gen.elements(
      Month.JANUARY,
      Month.FEBRUARY,
      Month.MARCH,
      Month.APRIL,
      Month.MAY,
      Month.JUNE,
      Month.JULY,
      Month.AUGUST,
      Month.SEPTEMBER,
      Month.OCTOBER,
      Month.NOVEMBER,
      Month.DECEMBER
    )

  /**
   * A generator of `java.time.MonthDay` values. Shrinks toward
   * `MonthDay.of(Month.JANUARY, 1)`.
   */
  final def monthDay(implicit trace: Trace): Gen[Any, MonthDay] =
    for {
      month <- Gen.int(1, 12).map(Month.of)
      days  <- Gen.int(1, month.maxLength())
    } yield MonthDay.of(month, days)

  /**
   * A generator of `java.time.OffsetDateTime` values. Shrinks toward
   * `OffsetDateTime.MIN`.
   */
  final def offsetDateTime(implicit trace: Trace): Gen[Any, OffsetDateTime] =
    offsetDateTime(OffsetDateTime.MIN, OffsetDateTime.MAX)

  /**
   * A generator of `java.time.OffsetDateTime` values inside the specified
   * range: [min, max]. Shrinks toward min.
   */
  final def offsetDateTime(min: OffsetDateTime, max: OffsetDateTime)(implicit
    trace: Trace
  ): Gen[Any, OffsetDateTime] = {

    def genLocalDateTime(min: OffsetDateTime, max: OffsetDateTime): Gen[Any, LocalDateTime] = {
      val minInst = min.atZoneSimilarLocal(utc).toInstant
      val maxInst = max.atZoneSimilarLocal(utc).toInstant
      instant(minInst, maxInst).map(_.atOffset(utc).toLocalDateTime)
    }

    def genOffset(min: OffsetDateTime, max: OffsetDateTime, actual: LocalDateTime): Gen[Any, ZoneOffset] = {
      val minLocalDate     = min.atZoneSimilarLocal(utc).toLocalDate
      val maxLocalDate     = max.atZoneSimilarLocal(utc).toLocalDate
      val actualLocalDate  = actual.toLocalDate
      val zoneOffsetMin    = -18 * 3600
      val zoneOffsetMax    = 18 * 3600
      val minOffsetSeconds = if (minLocalDate == actualLocalDate) min.getOffset.getTotalSeconds else zoneOffsetMin
      val maxOffsetSeconds = if (maxLocalDate == actualLocalDate) max.getOffset.getTotalSeconds else zoneOffsetMax
      if (minOffsetSeconds > maxOffsetSeconds) {
        val secondsAfterMin  = (min.toInstant.toEpochMilli - actual.toInstant(utc).toEpochMilli) / 1000
        val secondsBeforeMax = (max.toInstant.toEpochMilli - actual.toInstant(utc).toEpochMilli) / 1000
        Gen
          .int(Math.max(secondsAfterMin.toInt, zoneOffsetMin), Math.min(secondsBeforeMax.toInt, zoneOffsetMax))
          .map(ZoneOffset.ofTotalSeconds)
      } else {
        Gen.int(minOffsetSeconds, maxOffsetSeconds).map(ZoneOffset.ofTotalSeconds)
      }
    }

    for {
      localDateTime <- genLocalDateTime(min, max)
      offset        <- genOffset(min, max, localDateTime)
    } yield OffsetDateTime.of(localDateTime, offset)
  }

  /**
   * A generator of `java.time.OffsetTime` values. Shrinks torward
   * `OffsetTime.MIN`.
   */
  final def offsetTime(implicit trace: Trace): Gen[Any, OffsetTime] =
    for {
      time   <- localTime
      offset <- zoneOffset
    } yield OffsetTime.of(time, ZoneOffset.ofTotalSeconds(-offset.getTotalSeconds))

  /**
   * A generator of `java.time.Period` values. Shrinks toward `Period.ZERO`.
   */
  final def period(implicit trace: Trace): Gen[Any, Period] =
    for {
      years  <- Gen.int(0, Int.MaxValue)
      months <- Gen.int(0, Int.MaxValue)
      days   <- Gen.int(0, Int.MaxValue)
    } yield Period.of(years, months, days)

  /**
   * A generator of `java.time.Year` values inside the specified range: [min,
   * max]. Shrinks toward `min`.
   */
  final def year(min: Year, max: Year)(implicit trace: Trace): Gen[Any, Year] =
    Gen.int(min.getValue, max.getValue).map(Year.of)

  /**
   * A generator of `java.time.Year` values. Shrinks toward
   * `Year.of(Year.MIN_VALUE)`.
   */
  final def year(implicit trace: Trace): Gen[Any, Year] =
    Gen.int(Year.MIN_VALUE, Year.MAX_VALUE).map(Year.of)

  /**
   * A generator of `java.time.YearMonth` values. Shrinks toward
   * `YearMonth.of(Year.MIN_VALUE, Month.JANUARY)`.
   */
  final def yearMonth(implicit trace: Trace): Gen[Any, YearMonth] =
    for {
      year  <- year
      month <- Gen.int(1, 12)
    } yield YearMonth.of(year.getValue, month)

  /**
   * A generator of `java.time.YearMonth` values within specified range: [min,
   * max]. Shrinks toward `min`.
   */
  final def yearMonth(min: YearMonth, max: YearMonth)(implicit trace: Trace): Gen[Any, YearMonth] = {
    def genMonth(min: YearMonth, max: YearMonth, year: Year) = {
      val yearValue = year.getValue
      (min.getYear, max.getYear) match {
        case (`yearValue`, `yearValue`) => Gen.int(min.getMonthValue, max.getMonthValue)
        case (_, `yearValue`)           => Gen.int(1, max.getMonthValue)
        case (`yearValue`, _)           => Gen.int(min.getMonthValue, 12)
        case (_, _)                     => Gen.int(1, 12)
      }
    }

    for {
      year  <- year(Year.from(min), Year.from(max))
      month <- genMonth(min, max, year)
    } yield YearMonth.of(year.getValue, month)
  }

  /**
   * A generator of `java.time.ZonedDateTime` values. Shrinks toward
   * `ZoneDateTime.of(LocalDateTime.MIN, zoneId)`.
   */
  final def zonedDateTime(implicit trace: Trace): Gen[Any, ZonedDateTime] =
    for {
      dateTime <- localDateTime
      zoneId   <- zoneId
    } yield ZonedDateTime.of(dateTime, zoneId)

  /**
   * A generator of `java.time.ZonedDateTime` values within specified range:
   * [min, max]. Shrinks toward `min`.
   */
  final def zonedDateTime(min: ZonedDateTime, max: ZonedDateTime)(implicit trace: Trace): Gen[Any, ZonedDateTime] =
    for {
      dateTime <- localDateTime(min.toLocalDateTime, max.toLocalDateTime)
      zoneId   <- zoneId
    } yield ZonedDateTime.of(dateTime, zoneId)

  /**
   * A generator of `java.time.ZoneId` values. Doesn't have any shrinking.
   */
  @nowarn("msg=JavaConverters")
  final def zoneId(implicit trace: Trace): Gen[Any, ZoneId] =
    Gen.elements(ZoneId.getAvailableZoneIds.asScala.map(ZoneId.of).toList: _*).noShrink

  /**
   * A generator of `java.time.ZoneOffset` values. Shrinks toward
   * `ZoneOffset.MIN`.
   */
  final def zoneOffset(implicit trace: Trace): Gen[Any, ZoneOffset] =
    Gen
      .int(ZoneOffset.MIN.getTotalSeconds, ZoneOffset.MAX.getTotalSeconds)
      .map(ZoneOffset.ofTotalSeconds)

  private val utc: ZoneOffset = ZoneOffset.UTC
}
