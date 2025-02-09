package zio

import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.TestAspect.timeout
import zio.test._

import java.time.temporal.{ChronoField, ChronoUnit}
import java.time.{Instant, OffsetDateTime, ZoneId}
import java.util.concurrent.TimeUnit
import scala.concurrent.Future

object ScheduleSpec extends ZIOBaseSpec {

  import ZIOTag._

  /**
   * Retry `once` means that we try to exec `io`, get and error, try again to
   * exec `io`, and whatever the output is, we return that second result.
   *
   * The three following tests test retry when:
   *   - the first time succeeds (no retry)
   *   - the first time fails and the second succeeds (one retry, result
   *     success)
   *   - both first time and retry fail (one retry, result failure)
   */
  def spec = suite("ScheduleSpec")(
    suite("Repeat on success according to a provided strategy")(
      test("for 'recurs(a negative number)' repeats 0 additional time") {
        // A repeat with a negative number of times should not repeat the action at all
        checkRepeat(Schedule.recurs(-5), expected = 0)
      },
      test("for 'recurs(0)' does repeat 0 additional time") {
        // A repeat with 0 number of times should not repeat the action at all
        checkRepeat(Schedule.recurs(0), expected = 0)
      },
      test("for 'recurs(1)' does repeat 1 additional time") {
        checkRepeat(Schedule.recurs(1), expected = 1)
      },
      test("for 'once' does repeats 1 additional time") {
        for {
          ref <- Ref.make(0)
          _   <- ref.update(_ + 1).repeat(Schedule.once)
          res <- ref.get
        } yield assert(res)(equalTo(2))
      },
      test("for 'recurs(a positive given number)' repeats that additional number of time") {
        checkRepeat(Schedule.recurs(42), expected = 42)
      },
      test("for 'recurWhile(cond)' repeats while the cond still holds") {
        def cond: Int => Boolean = _ < 10
        checkRepeat(Schedule.recurWhile(cond), expected = 10)
      },
      test("for 'recurWhileZIO(cond)' repeats while the effectful cond still holds") {
        def cond: Int => UIO[Boolean] = x => ZIO.succeed(x > 10)
        checkRepeat(Schedule.recurWhileZIO(cond), expected = 1)
      },
      test("for 'recurWhileEquals(cond)' repeats while the cond is equal") {
        checkRepeat(Schedule.recurWhileEquals(1), expected = 2)
      },
      test("for 'recurUntil(cond)' repeats until the cond is satisfied") {
        def cond: Int => Boolean = _ < 10
        checkRepeat(Schedule.recurUntil(cond), expected = 1)
      },
      test("for 'recurUntilZIO(cond)' repeats until the effectful cond is satisfied") {
        def cond: Int => UIO[Boolean] = x => ZIO.succeed(x > 10)
        checkRepeat(Schedule.recurUntilZIO(cond), expected = 11)
      },
      test("for 'recurUntilEquals(cond)' repeats until the cond is equal") {
        checkRepeat(Schedule.recurUntilEquals(1), expected = 1)
      }
    ),
    suite("Collect all inputs into a list")(
      test("as long as the condition f holds") {
        def cond: Int => Boolean = _ < 10
        checkRepeat(Schedule.collectWhile(cond), expected = List(1, 2, 3, 4, 5, 6, 7, 8, 9))
      },
      test("as long as the effectful condition f holds") {
        def cond = (x: Int) => ZIO.succeed(x > 10)
        checkRepeat(Schedule.collectWhileZIO(cond), expected = Nil)
      },
      test("until the effectful condition f fails") {
        def cond = (i: Int) => i < 10 && i > 1
        checkRepeat(Schedule.collectUntil(cond), expected = Chunk(1))
      },
      test("until the effectful condition f fails") {
        def cond = (x: Int) => ZIO.succeed(x > 10)
        checkRepeat(Schedule.collectUntilZIO(cond), expected = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      }
    ),
    test("Repeat on failure does not actually repeat") {
      val failed = (for {
        ref <- Ref.make(0)
        _   <- alwaysFail(ref).repeat(Schedule.recurs(42))
      } yield ()).foldZIO[Any, Int, String](
        err => ZIO.succeed(err),
        _ => ZIO.succeed("it should not be a success at all")
      )
      assertZIO(failed)(equalTo("Error: 1"))
    } @@ zioTag(errors),
    test("Repeat a scheduled repeat repeats the whole number") {
      val n = 42
      for {
        ref <- Ref.make(0)
        io   = ref.update(_ + 1).repeat(Schedule.recurs(n))
        _   <- io.repeat(Schedule.recurs(1))
        res <- ref.get
      } yield assert(res)(equalTo((n + 1) * 2))
    },
    suite("Repeat an action 2 times and call `ensuring` should")(
      test("run the specified finalizer as soon as the schedule is complete") {
        for {
          p          <- Promise.make[Nothing, Unit]
          r          <- Ref.make(0)
          _          <- r.update(_ + 2).repeat(Schedule.recurs(2)).ensuring(p.succeed(()))
          v          <- r.get
          finalizerV <- p.poll
        } yield assert(v)(equalTo(6)) && assert(finalizerV.isDefined)(equalTo(true))
      }
    ),
    suite("Simulate a schedule")(
      test("without timing out") {
        val schedule  = Schedule.exponential(1.minute)
        val scheduled = Clock.currentDateTime.flatMap(schedule.run(_, List.fill(5)(())))
        val expected  = Chunk(1.minute, 2.minute, 4.minute, 8.minute, 16.minute)
        assertZIO(scheduled)(equalTo(expected))
      } @@ timeout(1.seconds),
      test("respect Schedule.recurs even if more input is provided than needed") {
        val schedule: Schedule[Any, Any, (Long, Duration)] = Schedule.recurs(2) && Schedule.exponential(1.minute)
        val scheduled                                      = Clock.currentDateTime.flatMap(schedule.run(_, 1 to 10))
        val expected                                       = Chunk((0L, 1.minute), (1L, 2.minute), (2L, 4.minute))
        assertZIO(scheduled)(equalTo(expected))
      },
      test("respect Schedule.upTo even if more input is provided than needed") {
        val schedule  = Schedule.spaced(1.second).upTo(5.seconds)
        val scheduled = Clock.currentDateTime.flatMap(schedule.run(_, 1 to 10))
        val expected  = Chunk(0L, 1L, 2L, 3L, 4L, 5L)
        assertZIO(scheduled)(equalTo(expected))
      },
      test("free from stack overflow") {
        assertZIO(ZStream.fromSchedule(Schedule.forever *> Schedule.recurs(100000)).runCount)(
          equalTo(100000L)
        )
      }
    ),
    suite("Retry on failure according to a provided strategy")(
      test("retry 0 time for `once` when first time succeeds") {
        implicit val canFail = CanFail
        for {
          ref <- Ref.make(0)
          _   <- ref.update(_ + 1).retry(Schedule.once)
          i   <- ref.get
        } yield assert(i)(equalTo(1))
      },
      test("retry 0 time for `recurs(0)`") {
        val failed = (for {
          ref <- Ref.make(0)
          i   <- alwaysFail(ref).retry(Schedule.recurs(0))
        } yield i)
          .foldZIO[Any, Int, String](
            err => ZIO.succeed(err),
            _ => ZIO.succeed("it should not be a success")
          )
        failed.map(actual => assert(actual)(equalTo("Error: 1")))
      },
      test("retry exactly one time for `once` when second time succeeds") {
        // one retry on failure
        for {
          ref <- Ref.make(0)
          _   <- failOn0(ref).retry(Schedule.once)
          r   <- ref.get
        } yield assert(r)(equalTo(2))
      },
      test("retry exactly one time for `once` even if still in error") {
        // no more than one retry on retry `once`
        val retried = (for {
          ref <- Ref.make(0)
          _   <- alwaysFail(ref).retry(Schedule.once)
        } yield ()).foldZIO[Any, Int, String](
          err => ZIO.succeed(err),
          _ => ZIO.succeed("A failure was expected")
        )
        assertZIO(retried)(equalTo("Error: 2"))
      },
      test("for a given number of times with random jitter in (0, 1)") {
        val schedule  = Schedule.spaced(500.millis).jittered(0, 1)
        val scheduled = run(schedule >>> Schedule.elapsed)(List.fill(5)(()))
        val expected  = Chunk(0.millis, 250.millis, 500.millis, 750.millis, 1000.millis)
        assertZIO(TestRandom.feedDoubles(0.5, 0.5, 0.5, 0.5, 0.5) *> scheduled)(equalTo(expected))
      },
      test("for a given number of times with random jitter in custom interval") {
        val schedule  = Schedule.spaced(500.millis).jittered(2, 4)
        val scheduled = run(schedule >>> Schedule.elapsed)((List.fill(5)(())))
        val expected  = Chunk(0, 1500, 3000, 5000, 7000).map(_.millis)
        assertZIO(TestRandom.feedDoubles(0.5, 0.5, 1, 1, 0.5) *> scheduled)(equalTo(expected))
      },
      test("fixed delay with error predicate") {
        var i = 0
        val io = ZIO.succeed(i += 1).flatMap[Any, String, Unit] { _ =>
          if (i < 5) ZIO.fail("KeepTryingError") else ZIO.fail("GiveUpError")
        }
        val strategy = Schedule.spaced(200.millis).whileInput[String](_ == "KeepTryingError")
        val expected = (800.millis, "GiveUpError", 4L)
        val result = io.retryOrElseEither(
          strategy,
          (e: String, r: Long) => Clock.nanoTime.map(nanos => (Duration.fromNanos(nanos), e, r))
        )
        assertZIO(run(result))(isLeft(equalTo(expected)))
      },
      test("fibonacci delay") {
        assertZIO(run(Schedule.fibonacci(100.millis) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 2, 4, 7).map(i => (i * 100).millis))
        )
      },
      test("linear delay") {
        assertZIO(run(Schedule.linear(100.millis) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 3, 6, 10).map(i => (i * 100).millis))
        )
      },
      test("spaced delay") {
        assertZIO(run(Schedule.spaced(100.millis) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 2, 3, 4).map(i => (i * 100).millis))
        )
      },
      test("fixed delay") {
        assertZIO(run(Schedule.fixed(100.millis) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 2, 3, 4).map(i => (i * 100).millis))
        )
      },
      test("fixed delay with zero delay") {
        assertZIO(run(Schedule.fixed(Duration.Zero) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk.fill(5)(Duration.Zero))
        )
      },
      test("fixed delay with nanosecond delay") {
        assertZIO(run(Schedule.fixed(100.nanos) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 2, 3, 4).map(i => (i * 100).nanos))
        )
      },
      test("windowed") {
        assertZIO(run(Schedule.windowed(100.millis) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 2, 3, 4).map(i => (i * 100).millis))
        )
      },
      test("modified linear delay") {
        assertZIO(
          run(Schedule.linear(100.millis).modifyDelayZIO { case (_, d) => ZIO.succeed(d * 2) } >>> Schedule.elapsed)(
            List.fill(5)(())
          )
        )(equalTo(Chunk(0, 1, 3, 6, 10).map(i => (i * 200).millis)))
      },
      test("exponential delay with default factor") {
        assertZIO(run(Schedule.exponential(100.millis) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 3, 7, 15).map(i => (i * 100).millis))
        )
      },
      test("exponential delay with other factor") {
        assertZIO(run(Schedule.exponential(100.millis, 3.0) >>> Schedule.elapsed)(List.fill(5)(())))(
          equalTo(Chunk(0, 1, 4, 13, 40).map(i => (i * 100).millis))
        )
      },
      test("fromDurations") {
        val schedule = Schedule.fromDurations(4.seconds, 7.seconds, 12.seconds, 19.seconds)
        val expected = Chunk(0.seconds, 4.seconds, 11.seconds, 23.seconds, 42.seconds)
        val actual   = run(schedule >>> Schedule.elapsed)(List.fill(5)(()))
        assertZIO(actual)(equalTo(expected))
      }
    ) @@ zioTag(errors),
    suite("Retry according to a provided strategy")(
      test("for up to 10 times") {
        var i        = 0
        val strategy = Schedule.recurs(10)
        val io       = ZIO.succeed(i += 1).flatMap(_ => if (i < 5) ZIO.fail("KeepTryingError") else ZIO.succeed(i))
        assertZIO(io.retry(strategy))(equalTo(5))
      }
    ) @@ zioTag(errors),
    suite("Return the result of the fallback after failing and no more retries left")(
      test("if fallback succeed - retryOrElse") {
        for {
          ref <- Ref.make(0)
          o   <- alwaysFail(ref).retryOrElse(Schedule.once, ioSucceed)
        } yield assert(o)(equalTo("OrElse": Any))
      },
      test("if fallback failed - retryOrElse") {
        val failed = (for {
          ref <- Ref.make(0)
          i   <- alwaysFail(ref).retryOrElse(Schedule.once, ioFail)
        } yield i)
          .foldZIO[Any, Int, String](
            err => ZIO.succeed(err),
            _ => ZIO.succeed("it should not be a success")
          )
        assertZIO(failed)(equalTo("OrElseFailed"))
      },
      test("if fallback succeed - retryOrElseEither") {
        for {
          ref     <- Ref.make(0)
          o       <- alwaysFail(ref).retryOrElseEither(Schedule.once, ioSucceed)
          expected = Left("OrElse")
        } yield assert(o)(equalTo(expected))
      },
      test("if fallback failed - retryOrElseEither") {
        val failed = (for {
          ref <- Ref.make(0)
          i   <- alwaysFail(ref).retryOrElseEither(Schedule.once, ioFail)
        } yield i)
          .foldZIO[Any, Int, String](
            err => ZIO.succeed(err),
            _ => ZIO.succeed("it should not be a success")
          )
        assertZIO(failed)(equalTo("OrElseFailed"))
      }
    ) @@ zioTag(errors),
    suite("cron-like scheduling. Repeats at point of time (minute of hour, day of week, ...)")(
      test("recur at 01 second of each minute") {
        def toOffsetDateTime[T](in: (List[(OffsetDateTime, T)], Option[T])): List[OffsetDateTime] =
          in._1.map(t => t._1)

        val originOffset = OffsetDateTime.now().withMinute(0).withSecond(0).withNano(0)

        val inTimeSecondNanosec = originOffset.withSecond(1).withNano(1)
        val inTimeSecond        = originOffset.withSecond(1)
        val beforeTime          = originOffset.withSecond(0)
        val afterTime           = originOffset.withSecond(3)

        val input = List(inTimeSecondNanosec, inTimeSecond, beforeTime, afterTime).map((_, ()))

        assertZIO(runManually(Schedule.secondOfMinute(1), input).map(toOffsetDateTime)) {
          val expected          = originOffset.withSecond(1)
          val afterTimeExpected = expected.withMinute(expected.getMinute + 1)
          equalTo(List(expected, afterTimeExpected, expected, afterTimeExpected))
        }
      },
      test("throw IllegalArgumentException on invalid `second` argument of `secondOfMinute`") {
        val input = List(OffsetDateTime.now())
        for {
          exit <- run(Schedule.secondOfMinute(60))(input).exit
        } yield assert(exit)(dies(isSubtype[IllegalArgumentException](anything)))
      },
      test("recur at 01 minute of each hour") {
        def toOffsetDateTime[T](in: (List[(OffsetDateTime, T)], Option[T])): List[OffsetDateTime] =
          in._1.map(t => t._1)

        val originOffset = OffsetDateTime.now().withHour(0).withSecond(0).withNano(0)

        val inTimeMinuteNanosec = originOffset.withMinute(1).withNano(1)
        val inTimeMinute        = originOffset.withMinute(1)
        val beforeTime          = originOffset.withMinute(0)
        val afterTime           = originOffset.withMinute(3)

        val input = List(inTimeMinuteNanosec, inTimeMinute, beforeTime, afterTime).map((_, ()))

        assertZIO(runManually(Schedule.minuteOfHour(1), input).map(toOffsetDateTime)) {
          val expected          = originOffset.withMinute(1)
          val afterTimeExpected = expected.withHour(expected.getHour + 1)
          equalTo(List(expected, afterTimeExpected, expected, afterTimeExpected))
        }
      },
      test("throw IllegalArgumentException on invalid `minute` argument of `minuteOfHour`") {
        val input = List(OffsetDateTime.now())
        for {
          exit <- run(Schedule.minuteOfHour(60))(input).exit
        } yield assert(exit)(dies(isSubtype[IllegalArgumentException](anything)))
      },
      test("recur at 01 hour of each day") {
        def toOffsetDateTime[T](in: (List[(OffsetDateTime, T)], Option[T])): List[OffsetDateTime] =
          in._1.map(t => t._1.withNano(0))

        val originOffset = OffsetDateTime
          .now()
          .truncatedTo(ChronoUnit.HOURS)

        val inTimeHourSecond = originOffset.withHour(1).withSecond(1)
        val inTimeHour       = originOffset.withHour(1)
        val beforeTime       = originOffset.withHour(0)
        val afterTime        = originOffset.withHour(3)

        val input = List(inTimeHourSecond, inTimeHour, beforeTime, afterTime).map((_, ()))

        assertZIO(runManually(Schedule.hourOfDay(1), input).map(toOffsetDateTime)) {
          val expected          = originOffset.withHour(1)
          val afterTimeExpected = expected.plusDays(1)
          equalTo(List(expected, afterTimeExpected, expected, afterTimeExpected))
        }
      },
      test("throw IllegalArgumentException on invalid `hour` argument of `hourOfDay`") {
        val input = List(OffsetDateTime.now())
        for {
          exit <- run(Schedule.hourOfDay(24))(input).exit
        } yield assert(exit)(dies(isSubtype[IllegalArgumentException](anything)))
      },
      test("recur at Tuesday of each week") {
        def toOffsetDateTime[T](in: (List[(OffsetDateTime, T)], Option[T])): List[OffsetDateTime] =
          in._1.map(t => t._1.withNano(0))

        val originOffset = OffsetDateTime
          .now()
          .truncatedTo(ChronoUnit.DAYS)

        val tuesdayHour = originOffset.`with`(ChronoField.DAY_OF_WEEK, 2).withHour(1)
        val tuesday     = originOffset.`with`(ChronoField.DAY_OF_WEEK, 2)
        val monday      = originOffset.`with`(ChronoField.DAY_OF_WEEK, 1)
        val wednesday   = originOffset.`with`(ChronoField.DAY_OF_WEEK, 3)

        val input = List(tuesdayHour, tuesday, monday, wednesday).map((_, ()))

        assertZIO(runManually(Schedule.dayOfWeek(2), input).map(toOffsetDateTime)) {
          val expectedTuesday = originOffset.`with`(ChronoField.DAY_OF_WEEK, 2)
          val nextTuesday     = expectedTuesday.plusDays(7).`with`(ChronoField.DAY_OF_WEEK, 2)
          equalTo(List(expectedTuesday, nextTuesday, expectedTuesday, nextTuesday))
        }
      },
      test("throw IllegalArgumentException on invalid `day` argument of `dayOfWeek`") {
        val input = List(OffsetDateTime.now())
        for {
          exit <- run(Schedule.dayOfWeek(8))(input).exit
        } yield assert(exit)(dies(isSubtype[IllegalArgumentException](anything)))
      },
      test("recur in 2nd day of each month") {
        def toOffsetDateTime[T](in: (List[(OffsetDateTime, T)], Option[T])): List[OffsetDateTime] =
          in._1.map(t => t._1.withNano(0))

        val originOffset = OffsetDateTime
          .now()
          .withYear(2020)
          .withMonth(1)
          .truncatedTo(ChronoUnit.DAYS)

        val inTimeDate1 = originOffset.withDayOfMonth(2).withHour(1)
        val inTimeDate2 = originOffset.withDayOfMonth(2)
        val before      = originOffset.withDayOfMonth(1)
        val after       = originOffset.withDayOfMonth(3)

        val input = List(inTimeDate1, inTimeDate2, before, after).map((_, ()))

        assertZIO(runManually(Schedule.dayOfMonth(2), input).map(toOffsetDateTime)) {
          val expectedFirstInTime  = originOffset.withDayOfMonth(2)
          val expectedSecondInTime = expectedFirstInTime.plusMonths(1)
          val expectedBefore       = originOffset.withDayOfMonth(2)
          val expectedAfter        = originOffset.withDayOfMonth(2).plusMonths(1)
          equalTo(List(expectedFirstInTime, expectedSecondInTime, expectedBefore, expectedAfter))
        }
      },
      test("recur only in months containing valid number of days") {
        def toOffsetDateTime[T](in: (List[(OffsetDateTime, T)], Option[T])): List[OffsetDateTime] =
          in._1.map(t => t._1.withNano(0))

        val originOffset = OffsetDateTime
          .now()
          .withYear(2020)
          .withMonth(1)
          .withDayOfMonth(31)
          .truncatedTo(ChronoUnit.DAYS)

        val input = List(originOffset).map((_, ()))

        assertZIO(runManually(Schedule.dayOfMonth(30), input).map(toOffsetDateTime)) {
          val expected = originOffset.withMonth(3).withDayOfMonth(30)
          equalTo(List(expected))
        }
      },
      test("throw IllegalArgumentException on invalid `day` argument of `dayOfMonth`") {
        val input = List(OffsetDateTime.now())
        for {
          exit <- run(Schedule.dayOfMonth(32))(input).exit
        } yield assert(exit)(dies(isSubtype[IllegalArgumentException](anything)))
      }
    ),
    suite("Return the result after successful retry")(
      test("retry exactly one time for `once` when second time succeeds - retryOrElse") {
        for {
          ref <- Ref.make(0)
          o   <- failOn0(ref).retryOrElse(Schedule.once, ioFail)
        } yield assert(o)(equalTo(2))
      },
      test("retry exactly one time for `once` when second time succeeds - retryOrElse0") {
        for {
          ref     <- Ref.make(0)
          o       <- failOn0(ref).retryOrElseEither(Schedule.once, ioFail)
          expected = Right(2)
        } yield assert(o)(equalTo(expected))
      }
    ) @@ zioTag(errors),
    suite("Retry a failed action 2 times and call `ensuring` should")(
      test("run the specified finalizer as soon as the schedule is complete") {
        for {
          p          <- Promise.make[Nothing, Unit]
          v          <- ZIO.fail("oh no").retry(Schedule.recurs(2)).ensuring(p.succeed(())).option
          finalizerV <- p.poll
        } yield assert(v.isEmpty)(equalTo(true)) && assert(finalizerV.isDefined)(equalTo(true))
      }
    ) @@ zioTag(errors),
    // test("`ensuring` should only call finalizer once.") {
    //   for {
    //     ref    <- Ref.make(0)
    //     sched  = Schedule.stop.ensuring(ref.update(_ + 1))
    //     s      <- sched.initial
    //     _      <- sched.update((), s).flip
    //     _      <- sched.update((), s).flip
    //     result <- ref.get.map(assert(_)(equalTo(1)))
    //   } yield result
    // },
    test("Retry type parameters should infer correctly") {
      def foo[O](v: O): ZIO[Any, Error, Either[ScheduleFailure, ScheduleSuccess[O]]] =
        ZIO
          .fromFuture(_ => Future.successful(v))
          .foldZIO(
            _ => ZIO.fail(ScheduleError("Some error")),
            ok => ZIO.succeed(Right(ScheduleSuccess(ok)))
          )
          .retry(Schedule.spaced(2.seconds) && Schedule.recurs(1))
          .catchAll(error => ZIO.succeed(Left(ScheduleFailure(error.message))))

      val expected = Right(ScheduleSuccess("Ok"))
      assertZIO(foo("Ok"))(equalTo(expected))
    },
    test("either should not wait if neither schedule wants to continue") {
      assertZIO(
        run((Schedule.stop || (Schedule.spaced(2.seconds) && Schedule.stop)) >>> Schedule.elapsed)(List.fill(5)(()))
      )(
        equalTo(Chunk(Duration.Zero))
      )
    },
    test("perform log for each recurrence of effect") {
      def schedule[A](ref: Ref[Int]) =
        Schedule
          .recurs(3)
          .onDecision { case _ => ref.update(_ + 1) }

      for {
        ref <- Ref.make(0)
        _   <- ref.getAndUpdate(_ + 1).repeat(schedule(ref))
        res <- ref.get
      } yield assert(res)(equalTo(8))
    },
    test("Reset after some inactivity") {

      def io(ref: Ref[Int], latch: Promise[Nothing, Unit]): ZIO[Any, String, Unit] =
        ref
          .updateAndGet(_ + 1)
          .flatMap(retries =>
            // the 5th retry will fail after 10 seconds to let the schedule reset
            if (retries == 5) latch.succeed(()) *> io(ref, latch).delay(10.seconds)
            // the 10th retry will succeed, which is only possible if the schedule was reset
            else if (retries == 10) ZIO.unit
            else ZIO.fail("Boom")
          )

      assertZIO {
        for {
          retriesCounter <- Ref.make(-1)
          latch          <- Promise.make[Nothing, Unit]
          fiber          <- io(retriesCounter, latch).retry(Schedule.recurs(5).resetAfter(5.seconds)).fork
          _              <- latch.await
          _              <- TestClock.adjust(10.seconds)
          _              <- fiber.join
          retries        <- retriesCounter.get
        } yield retries
      }(equalTo(10))
    },
    test("union of two schedules should continue as long as either wants to continue") {
      val schedule: Schedule[Any, Boolean, (Boolean, Long)] =
        Schedule.recurWhile[Boolean](_ == true) || Schedule.fixed(1.second)
      assertZIO(run(schedule >>> Schedule.elapsed)(List(true, false, false, false, false)))(
        equalTo(Chunk(0, 0, 1, 2, 3).map(_.seconds))
      )
    },
    test("Schedule.fixed should compute delays correctly") {
      def offsetDateTime(millis: Long) =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of("GMT"))

      val inputs            = List(offsetDateTime(0), offsetDateTime(6500)).zip(List((), ()))
      val scheduleIntervals = runManually(Schedule.fixed(5.seconds), inputs).map(_._1.map(_._1))

      assertZIO(scheduleIntervals)(equalTo(List(offsetDateTime(5000), offsetDateTime(10000))))
    },
    suite("return values")(
      suite("delays")(
        test("duration")(checkDelays(Schedule.duration(1.second))),
        test("exponential")(checkDelays(Schedule.exponential(1.second))),
        test("fibonacci")(checkDelays(Schedule.fibonacci(1.second))),
        test("fromDuration")(checkDelays(Schedule.fromDuration(1.second))),
        test("fromDurations")(checkDelays(Schedule.fromDurations(1.second, 2.seconds, 3.seconds, 4.seconds))),
        test("linear")(checkDelays(Schedule.linear(1.second)))
      ),
      suite("repetitions")(
        test("count")(checkRepetitions(Schedule.count)),
        test("dayOfMonth")(checkRepetitions(Schedule.dayOfMonth(1))),
        test("dayOfWeek")(checkRepetitions(Schedule.dayOfWeek(1))),
        test("fixed")(checkRepetitions(Schedule.fixed(1.second))),
        test("forever")(checkRepetitions(Schedule.forever)),
        test("hourOfDay")(checkRepetitions(Schedule.hourOfDay(1))),
        test("minuteOfHour")(checkRepetitions(Schedule.minuteOfHour(1))),
        test("recurs")(checkRepetitions(Schedule.forever)),
        test("secondOfMinute")(checkRepetitions(Schedule.secondOfMinute(1))),
        test("spaced")(checkRepetitions(Schedule.spaced(1.second))),
        test("windowed")(checkRepetitions(Schedule.windowed(1.second)))
      )
    ),
    test("intersection of schedules recurring in bounded intervals") {
      val schedule: Schedule[Any, Any, (Long, Long)] = Schedule.hourOfDay(4) && Schedule.minuteOfHour(20)
      for {
        now    <- ZIO.succeed(OffsetDateTime.now)
        in      = Chunk(1, 2, 3, 4, 5)
        delays <- schedule.delays.run(now, in)
        actual  = delays.scanLeft(now)((now, delay) => now.plus(delay)).tail
      } yield assert(actual.map(_.getHour))(forall(equalTo(4))) &&
        assert(actual.map(_.getMinute))(forall(equalTo(20)))
    },
    test("union composes") {
      val monday            = Schedule.dayOfWeek(1)
      val wednesday         = Schedule.dayOfWeek(3)
      val friday            = Schedule.dayOfWeek(5)
      val mondayOrWednesday = monday || wednesday
      val wednesdayOrFriday = wednesday || friday
      val alsoWednesday     = mondayOrWednesday && wednesdayOrFriday
      for {
        now      <- ZIO.succeed(OffsetDateTime.now)
        in        = Chunk(1, 2, 3, 4, 5)
        actual   <- alsoWednesday.delays.run(now, in)
        expected <- wednesday.delays.run(now, in)
      } yield assert(actual)(equalTo(expected))
    },
    test("chooses") {
      val s1: Schedule[Any, Int, (Duration, Long)]    = Schedule.exponential(1.second) && Schedule.recurs(3)
      val s2: Schedule[Any, String, (Duration, Long)] = Schedule.exponential(8.second) && Schedule.recurs(3)
      val schedule                                    = s1 +++ s2
      for {
        now    <- ZIO.succeed(OffsetDateTime.now)
        in      = Chunk(Left(1), Right("a"), Left(2), Right("b"), Left(3), Left(4), Right("c"))
        actual <- schedule.delays.run(now, in)
      } yield assert(actual)(equalTo(Chunk(1.second, 8.seconds, 2.seconds, 16.seconds, 4.seconds, 0.seconds)))
    },
    test("passthrough") {
      for {
        ref   <- Ref.make(0)
        value <- ref.getAndUpdate(_ + 1).repeat(Schedule.recurs(10).passthrough)
      } yield assertTrue(value == 10)
    },
    test("union with cron like schedules") {
      for {
        ref     <- Ref.make[Chunk[Long]](Chunk.empty)
        _       <- TestClock.adjust(5.seconds)
        schedule = Schedule.spaced(20.seconds) || Schedule.secondOfMinute(30)
        _       <- Clock.currentTime(TimeUnit.SECONDS).tap(instant => ref.update(_ :+ instant)).repeat(schedule).fork
        _       <- TestClock.adjust(2.minutes)
        seconds <- ref.get
      } yield assertTrue(seconds == Chunk(5L, 25L, 30L, 50L, 70L, 90L, 110L))
    },
    test("collectAll") {
      val schedule = Schedule.recurs(5).collectAll
      for {
        chunk <- ZIO.unit.repeat(schedule)
      } yield assertTrue(chunk == Chunk(0L, 1L, 2L, 3L, 4L, 5L))
    }
  )

  def checkDelays[Env](schedule: Schedule[Env, Any, Duration]): URIO[Env, TestResult] =
    for {
      now      <- ZIO.succeed(OffsetDateTime.now)
      in        = Chunk(1, 2, 3, 4, 5)
      actual   <- schedule.run(now, in)
      expected <- schedule.delays.run(now, in)
    } yield assert(actual)(equalTo(expected))

  def checkRepetitions[Env](schedule: Schedule[Env, Any, Long]): URIO[Env, TestResult] =
    for {
      now      <- ZIO.succeed(OffsetDateTime.now)
      in        = Chunk(1, 2, 3, 4, 5)
      actual   <- schedule.run(now, in)
      expected <- schedule.repetitions.run(now, in)
    } yield assert(actual)(equalTo(expected))

  val ioSucceed: (String, Unit) => UIO[String]      = (_: String, _: Unit) => ZIO.succeed("OrElse")
  val ioFail: (String, Unit) => IO[String, Nothing] = (_: String, _: Unit) => ZIO.fail("OrElseFailed")

  def repeat[B](schedule: Schedule[Any, Int, B]): ZIO[Any, Nothing, B] =
    for {
      ref <- Ref.make(0)
      res <- ref.updateAndGet(_ + 1).repeat(schedule)
    } yield res

  /**
   * Run a schedule using the provided input and collect all outputs
   */
  def run[R, A, B](
    schedule: Schedule[R, A, B]
  )(input: Iterable[A]): ZIO[R, Nothing, Chunk[B]] =
    run {
      schedule.driver.flatMap { driver =>
        def loop(input: List[A], acc: Chunk[B]): ZIO[R, Nothing, Chunk[B]] =
          input match {
            case h :: t =>
              driver
                .next(h)
                .foldZIO(
                  _ => driver.last.fold(_ => acc, b => acc :+ b),
                  b => loop(t, acc :+ b)
                )
            case Nil => ZIO.succeed(acc)
          }

        loop(input.toList, Chunk.empty)
      }
    }

  def run[R, E, A](effect: ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      fiber  <- effect.fork
      _      <- TestClock.setTime(Instant.MAX)
      result <- fiber.join
    } yield result

  def runManually[Env, In, Out](
    schedule: Schedule[Env, In, Out],
    inputs: List[(OffsetDateTime, In)]
  ): ZIO[Env, Nothing, (List[(OffsetDateTime, Out)], Option[Out])] = {

    def loop(
      state: schedule.State,
      inputs: List[(OffsetDateTime, In)],
      acc: List[(OffsetDateTime, Out)]
    ): ZIO[Env, Nothing, (List[(OffsetDateTime, Out)], Option[Out])] =
      inputs match {
        case Nil => ZIO.succeed(acc.reverse -> None)
        case (odt, in) :: rest =>
          schedule.step(odt, in, state) flatMap {
            case (_, out, Schedule.Decision.Done) => ZIO.succeed(acc.reverse -> Some(out))
            case (state, out, Schedule.Decision.Continue(interval)) =>
              loop(state, rest, (interval.start -> out) :: acc)
          }
      }

    loop(schedule.initial, inputs, Nil)
  }

  def checkRepeat[B](schedule: Schedule[Any, Int, B], expected: B): ZIO[Any, Nothing, TestResult] =
    assertZIO(repeat(schedule))(equalTo(expected))

  /**
   * A function that increments ref each time it is called. It always fails,
   * with the incremented value in error
   */
  def alwaysFail(ref: Ref[Int]): IO[String, Int] =
    for {
      i <- ref.updateAndGet(_ + 1)
      x <- ZIO.fail(s"Error: $i")
    } yield x

  /**
   * A function that increments ref each time it is called. It returns either a
   * failure if ref value is 0 or less before increment, and the value in other
   * cases.
   */
  def failOn0(ref: Ref[Int]): IO[String, Int] =
    for {
      i <- ref.updateAndGet(_ + 1)
      x <- if (i <= 1) ZIO.fail(s"Error: $i") else ZIO.succeed(i)
    } yield x

  def diesWith(assertion: Assertion[Throwable]): Assertion[TestFailure[Any]] =
    isCase(
      "Runtime",
      {
        case TestFailure.Runtime(c, _) => c.dieOption
        case _                         => None
      },
      assertion
    )

  case class ScheduleError(message: String) extends Exception
  case class ScheduleFailure(message: String)
  case class ScheduleSuccess[O](content: O)

  type Logging = Logging.Service

  object Logging {
    trait Service
    val live: ZLayer[Any, Nothing, Logging] = ZLayer.succeed(new Logging.Service {})
  }
}
