/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
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

import java.time.{Instant, OffsetDateTime}
import java.time.temporal.ChronoField._
import java.time.temporal.TemporalAdjusters
import java.time.{Instant, OffsetDateTime}
import scala.annotation.tailrec

/**
 * A `Schedule[Env, In, Out]` defines a recurring schedule, which consumes
 * values of type `In`, and which returns values of type `Out`.
 *
 * Schedules are defined as a possibly infinite set of intervals spread out over
 * time. Each interval defines a window in which recurrence is possible.
 *
 * When schedules are used to repeat or retry effects, the starting boundary of
 * each interval produced by a schedule is used as the moment when the effect
 * will be executed again.
 *
 * Schedules compose in the following primary ways:
 *
 * * Union. This performs the union of the intervals of two schedules. *
 * Intersection. This performs the intersection of the intervals of two
 * schedules. * Sequence. This concatenates the intervals of one schedule onto
 * another.
 *
 * In addition, schedule inputs and outputs can be transformed, filtered (to
 * terminate a schedule early in response to some input or output), and so
 * forth.
 *
 * A variety of other operators exist for transforming and combining schedules,
 * and the companion object for `Schedule` contains all common types of
 * schedules, both for performing retrying, as well as performing repetition.
 */
trait Schedule[-Env, -In, +Out] extends Serializable { self =>
  import Schedule.Decision._
  import Schedule._

  type State

  def initial: State

  def step(now: OffsetDateTime, in: In, state: State)(implicit
    trace: Trace
  ): ZIO[Env, Nothing, (State, Out, Decision)]

  /**
   * Returns a new schedule that performs a geometric intersection on the
   * intervals defined by both schedules.
   */
  final def &&[Env1 <: Env, In1 <: In, Out2](that: Schedule[Env1, In1, Out2])(implicit
    zippable: Zippable[Out, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In1, zippable.Out] =
    (self intersectWith that)(_ intersect _)

  /**
   * Returns a new schedule that has both the inputs and outputs of this and the
   * specified schedule.
   */
  final def ***[Env1 <: Env, In2, Out2](
    that: Schedule[Env1, In2, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, (In, In2), (Out, Out2)] =
    new Schedule[Env1, (In, In2), (Out, Out2)] {
      override type State = (self.State, that.State)
      override final val initial: State = (self.initial, that.initial)
      override final def step(now: OffsetDateTime, in: (In, In2), state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, (Out, Out2), Decision)] = {
        val (in1, in2) = in

        self.step(now, in1, state._1).zipWith(that.step(now, in2, state._2)) {
          case ((lState, out, Continue(lInterval)), (rState, out2, Continue(rInterval))) =>
            val interval = lInterval.union(rInterval)
            ((lState, rState), out -> out2, Continue(interval))
          case ((lState, out, _), (rState, out2, _)) =>
            ((lState, rState), out -> out2, Done)
        }
      }
    }

  /**
   * The same as `&&`, but ignores the left output.
   */
  final def *>[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State), Env1, In1, Out2] =
    (self && that).map(_._2)

  /**
   * A symbolic alias for `andThen`.
   */
  final def ++[Env1 <: Env, In1 <: In, Out2 >: Out](
    that: Schedule[Env1, In1, Out2]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State, Boolean), Env1, In1, Out2] =
    self andThen that

  /**
   * Returns a new schedule that allows choosing between feeding inputs to this
   * schedule, or feeding inputs to the specified schedule.
   */
  final def +++[Env1 <: Env, In2, Out2](
    that: Schedule[Env1, In2, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, Either[In, In2], Either[Out, Out2]] =
    new Schedule[Env1, Either[In, In2], Either[Out, Out2]] {
      override type State = (self.State, that.State)
      override final val initial: State = (self.initial, that.initial)
      override final def step(
        now: OffsetDateTime,
        either: Either[In, In2],
        state: State
      )(implicit trace: Trace): ZIO[Env1, Nothing, (State, Either[Out, Out2], Decision)] =
        either match {
          case Left(in) =>
            self.step(now, in, state._1).map { case (lState, out, decision) =>
              ((lState, state._2), Left(out), decision)
            }
          case Right(in2) =>
            that.step(now, in2, state._2).map { case (rState, out2, decision) =>
              ((state._1, rState), Right(out2), decision)
            }
        }
    }

  /**
   * Operator alias for `andThenEither`.
   */
  final def <||>[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  ): Schedule.WithState[(self.State, that.State, Boolean), Env1, In1, Either[Out, Out2]] =
    self.andThenEither(that)

  /**
   * The same as `&&`, but ignores the right output.
   */
  final def <*[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State), Env1, In1, Out] =
    (self && that).map(_._1)

  /**
   * An operator alias for `zip`.
   */
  final def <*>[Env1 <: Env, In1 <: In, Out2](that: Schedule[Env1, In1, Out2])(implicit
    zippable: Zippable[Out, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In1, zippable.Out] =
    self zip that

  /**
   * A backwards version of `>>>`.
   */
  final def <<<[Env1 <: Env, In2](
    that: Schedule[Env1, In2, In]
  ): Schedule.WithState[(that.State, self.State), Env1, In2, Out] =
    that >>> self

  /**
   * Returns the composition of this schedule and the specified schedule, by
   * piping the output of this one into the input of the other. Effects
   * described by this schedule will always be executed before the effects
   * described by the second schedule.
   */
  final def >>>[Env1 <: Env, Out2](
    that: Schedule[Env1, Out, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In, Out2] =
    new Schedule[Env1, In, Out2] {
      override type State = (self.State, that.State)
      override final val initial: State = (self.initial, that.initial)
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out2, Decision)] =
        self.step(now, in, state._1).flatMap {
          case (lState, out, Done) =>
            that.step(now, out, state._2).map { case (rState, out2, _) =>
              ((lState, rState), out2, Done)
            }
          case (lState, out, Continue(interval)) =>
            that.step(now, out, state._2).map {
              case (rState, out2, Done) => ((lState, rState), out2, Done)
              case (rState, out2, Continue(interval2)) =>
                val combined = interval max interval2

                ((lState, rState), out2, Continue(combined))
            }
        }
    }

  /**
   * Returns a new schedule that performs a geometric union on the intervals
   * defined by both schedules.
   */
  final def ||[Env1 <: Env, In1 <: In, Out2](that: Schedule[Env1, In1, Out2])(implicit
    zippable: Zippable[Out, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In1, zippable.Out] =
    (self unionWith that)(_ union _)

  /**
   * Returns a new schedule that chooses between two schedules with a common
   * output.
   */
  final def |||[Env1 <: Env, Out1 >: Out, In2](
    that: Schedule[Env1, In2, Out1]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State), Env1, Either[In, In2], Out1] =
    (self +++ that).map(_.merge)

  /**
   * Returns a new schedule with the given delay added to every interval defined
   * by this schedule.
   */
  final def addDelay(f: Out => Duration)(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out] =
    addDelayZIO(out => ZIO.succeed(f(out)))

  /**
   * Returns a new schedule with the given effectfully computed delay added to
   * every interval defined by this schedule.
   */
  final def addDelayZIO[Env1 <: Env](f: Out => URIO[Env1, Duration])(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env1, In, Out] =
    modifyDelayZIO((out, duration) => f(out).map(duration + _))

  /**
   * The same as `andThenEither`, but merges the output.
   */
  final def andThen[Env1 <: Env, In1 <: In, Out2 >: Out](
    that: Schedule[Env1, In1, Out2]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State, Boolean), Env1, In1, Out2] =
    (self andThenEither that).map(_.merge)

  /**
   * Returns a new schedule that first executes this schedule to completion, and
   * then executes the specified schedule to completion.
   */
  final def andThenEither[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  ): Schedule.WithState[(self.State, that.State, Boolean), Env1, In1, Either[Out, Out2]] =
    new Schedule[Env1, In1, Either[Out, Out2]] {
      override type State = (self.State, that.State, Boolean)
      override final val initial: State = (self.initial, that.initial, true)
      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Either[Out, Out2], Decision)] = {
        val onLeft = state._3
        if (onLeft) self.step(now, in, state._1).flatMap {
          case (lState, out, Continue(interval)) =>
            ZIO.succeed(((lState, state._2, true), Left(out), Continue(interval)))
          case (lState, _, Done) =>
            that.step(now, in, state._2).map { case (rState, out, decision) =>
              ((lState, rState, false), Right(out), decision)
            }
        }
        else
          that.step(now, in, state._2).map { case (rState, out, decision) =>
            ((state._1, rState, false), Right(out), decision)
          }
      }
    }

  /**
   * Returns a new schedule that maps this schedule to a constant output.
   */
  final def as[Out2](out2: => Out2)(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out2] =
    self.map(_ => out2)

  /**
   * Returns a new schedule that passes each input and output of this schedule
   * to the specified function, and then determines whether or not to continue
   * based on the return value of the function.
   */
  final def check[In1 <: In](test: (In1, Out) => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env, In1, Out] =
    checkZIO((in1, out) => ZIO.succeed(test(in1, out)))

  /**
   * Returns a new schedule that passes each input and output of this schedule
   * to the specified function, and then determines whether or not to continue
   * based on the return value of the function.
   */
  final def checkZIO[Env1 <: Env, In1 <: In](
    test: (In1, Out) => URIO[Env1, Boolean]
  ): Schedule.WithState[self.State, Env1, In1, Out] =
    new Schedule[Env1, In1, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).flatMap {
          case (state, out, Done) => ZIO.succeed((state, out, Done))
          case (state, out, Continue(interval)) =>
            test(in, out).map { b =>
              if (b) (state, out, Continue(interval)) else (state, out, Done)
            }
        }
    }

  /**
   * Returns a new schedule that collects the outputs of this one into a chunk.
   */
  final def collectAll[Out1 >: Out](implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Chunk[Out1]), Env, In, Chunk[Out1]] =
    collectWhile(_ => true)

  /**
   * Returns a new schedule that collects the outputs of this one into a list as
   * long as the condition f holds.
   */
  final def collectWhile[Out1 >: Out](f: Out => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Chunk[Out1]), Env, In, Chunk[Out1]] =
    collectWhileZIO(out => ZIO.succeed(f(out)))

  /**
   * Returns a new schedule that collects the outputs of this one into a list as
   * long as the effectual condition f holds.
   */
  final def collectWhileZIO[Env1 <: Env, Out1 >: Out](f: Out => URIO[Env1, Boolean])(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Chunk[Out1]), Env1, In, Chunk[Out1]] =
    new Schedule[Env1, In, Chunk[Out1]] {
      override type State = (self.State, Chunk[Out1])
      override final val initial: State = (self.initial, Chunk.empty)
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Chunk[Out1], Decision)] = {
        val s = state._1
        val z = state._2
        self.step(now, in, s).flatMap {
          case (s, out, Done) =>
            f(out).map { b =>
              if (!b) ((s, z), z, Done)
              else {
                val z2 = z :+ out
                ((s, z2), z2, Done)
              }
            }
          case (s, out, Continue(interval)) =>
            f(out).map { b =>
              if (!b) ((s, z), z, Done)
              else {
                val z2 = z :+ out
                ((s, z2), z2, Continue(interval))
              }

            }
        }
      }
    }

  /**
   * Returns a new schedule that collects the outputs of this one into a list
   * until the condition f fails.
   */
  final def collectUntil[Out1 >: Out](f: Out => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Chunk[Out1]), Env, In, Chunk[Out1]] =
    collectUntilZIO(out => ZIO.succeed(f(out)))

  /**
   * Returns a new schedule that collects the outputs of this one into a list
   * until the effectual condition f fails.
   */
  final def collectUntilZIO[Env1 <: Env, Out1 >: Out](f: Out => URIO[Env1, Boolean])(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Chunk[Out1]), Env1, In, Chunk[Out1]] =
    collectWhileZIO(!f(_))

  /**
   * A named alias for `<<<`.
   */
  final def compose[Env1 <: Env, In2](
    that: Schedule[Env1, In2, In]
  ): Schedule.WithState[(that.State, self.State), Env1, In2, Out] =
    that >>> self

  /**
   * Returns a new schedule that deals with a narrower class of inputs than this
   * schedule.
   */
  final def contramap[Env1 <: Env, In2](f: In2 => In)(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env, In2, Out] =
    self.contramapZIO(in => ZIO.succeed(f(in)))

  /**
   * Returns a new schedule that deals with a narrower class of inputs than this
   * schedule.
   */
  final def contramapZIO[Env1 <: Env, In2](f: In2 => URIO[Env1, In]): Schedule.WithState[self.State, Env1, In2, Out] =
    new Schedule[Env1, In2, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in2: In2, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out, Decision)] =
        f(in2).flatMap(in => self.step(now, in, state))
    }

  /**
   * A schedule that recurs during the given duration
   */
  final def upTo(duration: Duration)(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Option[OffsetDateTime]), Env, In, Out] =
    self <* Schedule.upTo(duration)

  /**
   * Returns a new schedule with the specified effectfully computed delay added
   * before the start of each interval produced by this schedule.
   */
  final def delayed(f: Duration => Duration)(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out] =
    self.delayedZIO(d => ZIO.succeed(f(d)))

  /**
   * Returns a new schedule that outputs the delay between each occurence.
   */
  final def delays: Schedule.WithState[self.State, Env, In, Duration] =
    new Schedule[Env, In, Duration] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env, Nothing, (State, Duration, Decision)] =
        self.step(now, in, state).flatMap {
          case (state, _, Done) =>
            ZIO.succeed((state, Duration.Zero, Done))
          case (state, _, Continue(interval)) =>
            val delay = Duration.fromInterval(now, interval.start)
            ZIO.succeed((state, delay, Continue(interval)))
        }
    }

  /**
   * Returns a new schedule with the specified effectfully computed delay added
   * before the start of each interval produced by this schedule.
   */
  final def delayedZIO[Env1 <: Env](
    f: Duration => URIO[Env1, Duration]
  ): Schedule.WithState[self.State, Env1, In, Out] =
    modifyDelayZIO((_, delay) => f(delay))

  /**
   * Returns a new schedule that contramaps the input and maps the output.
   */
  final def dimap[In2, Out2](f: In2 => In, g: Out => Out2)(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env, In2, Out2] =
    contramap(f).map(g)

  /**
   * Returns a new schedule that contramaps the input and maps the output.
   */
  final def dimapZIO[Env1 <: Env, In2, Out2](
    f: In2 => URIO[Env1, In],
    g: Out => URIO[Env1, Out2]
  ): Schedule.WithState[self.State, Env1, In2, Out2] =
    contramapZIO(f).mapZIO(g)

  /**
   * Returns a driver that can be used to step the schedule, appropriately
   * handling sleeping.
   */
  final def driver(implicit trace: Trace): UIO[Schedule.Driver[self.State, Env, In, Out]] =
    Ref.make[(Option[Out], self.State)]((None, self.initial)).map { ref =>
      val next = (in: In) =>
        for {
          state <- ref.get.map(_._2)
          now   <- Clock.currentDateTime
          dec   <- self.step(now, in, state)
          v <- dec match {
                 case (state, out, Done) =>
                   ref.set((Some(out), state)) *> Exit.failNone.asInstanceOf[Exit[None.type, Out]]
                 case (state, out, Continue(interval)) =>
                   ref.set((Some(out), state)) *> ZIO.sleep(Duration.fromInterval(now, interval.start)) as out
               }
        } yield v

      val last = ref.get.flatMap {
        case (None, _)    => ZIO.fail(new NoSuchElementException("There is no value left"))
        case (Some(b), _) => Exit.succeed(b)
      }

      val reset = ref.set((None, self.initial))

      val state = ref.get.map(_._2)

      Schedule.Driver(next, last, reset, state)
    }

  /**
   * A named alias for `||`.
   */
  final def either[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In1, (Out, Out2)] =
    self || that

  /**
   * The same as `either` followed by `map`.
   */
  final def eitherWith[Env1 <: Env, In1 <: In, Out2, Out3](
    that: Schedule[Env1, In1, Out2]
  )(f: (Out, Out2) => Out3)(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, that.State), Env1, In1, Out3] =
    (self || that).map(f.tupled)

  /**
   * Returns a new schedule that will run the specified finalizer as soon as the
   * schedule is complete. Note that unlike `ZIO#ensuring`, this method does not
   * guarantee the finalizer will be run. The `Schedule` may not initialize or
   * the driver of the schedule may not run to completion. However, if the
   * `Schedule` ever decides not to continue, then the finalizer will be run.
   */
  final def ensuring(finalizer: UIO[Any]): Schedule.WithState[self.State, Env, In, Out] =
    new Schedule[Env, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).flatMap {
          case (state, out, Done)               => finalizer.as((state, out, Done))
          case (state, out, Continue(interval)) => ZIO.succeed((state, out, Continue(interval)))
        }
    }

  /**
   * Returns a new schedule that packs the input and output of this schedule
   * into the first element of a tuple. This allows carrying information through
   * this schedule.
   */
  final def first[X]: Schedule.WithState[(self.State, Unit), Env, (In, X), (Out, X)] =
    self *** Schedule.identity[X]

  /**
   * Returns a new schedule that folds over the outputs of this one.
   */
  final def fold[Z](z: Z)(f: (Z, Out) => Z)(implicit trace: Trace): Schedule.WithState[(self.State, Z), Env, In, Z] =
    foldZIO(z)((z, out) => ZIO.succeed(f(z, out)))

  /**
   * Returns a new schedule that effectfully folds over the outputs of this one.
   */
  final def foldZIO[Env1 <: Env, Z](
    z: Z
  )(f: (Z, Out) => URIO[Env1, Z]): Schedule.WithState[(self.State, Z), Env1, In, Z] =
    new Schedule[Env1, In, Z] {
      override type State = (self.State, Z)
      override final val initial: State = (self.initial, z)
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Z, Decision)] = {
        val s = state._1
        val z = state._2
        self.step(now, in, s).flatMap {
          case (s, _, Done)                 => ZIO.succeed(((s, z), z, Done))
          case (s, out, Continue(interval)) => f(z, out).map(z2 => ((s, z2), z, Continue(interval)))
        }
      }
    }

  /**
   * Returns a new schedule that loops this one continuously, resetting the
   * state when this schedule is done.
   */
  final def forever: Schedule.WithState[self.State, Env, In, Out] =
    new Schedule[Env, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).flatMap {
          case (_, _, Done)                     => step(now, in, initial)
          case (state, out, Continue(interval)) => ZIO.succeed((state, out, Continue(interval)))
        }
    }

  /**
   * Returns a new schedule that combines this schedule with the specified
   * schedule, continuing as long as both schedules want to continue and merging
   * the next intervals according to the specified merge function.
   */
  final def intersectWith[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  )(f: (Intervals, Intervals) => Intervals)(implicit
    zippable: Zippable[Out, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In1, zippable.Out] =
    new Schedule[Env1, In1, zippable.Out] {
      override type State = (self.State, that.State)
      override final val initial: State = (self.initial, that.initial)

      private def loop(
        in: In1,
        lState: self.State,
        out: Out,
        lInterval: Intervals,
        rState: that.State,
        out2: Out2,
        rInterval: Intervals
      )(implicit trace: Trace): ZIO[Env1, Nothing, (State, zippable.Out, Decision)] = {
        val combined = f(lInterval, rInterval)
        if (combined.nonEmpty)
          ZIO.succeed(((lState, rState), zippable.zip(out, out2), Continue(combined)))
        else if (lInterval < rInterval)
          self.step(lInterval.end, in, lState).flatMap {
            case ((lState, out, Continue(lInterval))) =>
              loop(in, lState, out, lInterval, rState, out2, rInterval)
            case ((lState, out, _)) => ZIO.succeed(((lState, rState), zippable.zip(out, out2), Done))
          }
        else
          that.step(rInterval.end, in, rState).flatMap {
            case ((rState, out2, Continue(rInterval))) =>
              loop(in, lState, out, lInterval, rState, out2, rInterval)
            case ((rState, out2, _)) => ZIO.succeed(((lState, rState), zippable.zip(out, out2), Done))
          }
      }

      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, zippable.Out, Decision)] = {
        val left  = self.step(now, in, state._1)
        val right = that.step(now, in, state._2)

        left.zipWith(right)((_, _)).flatMap {
          case ((lState, out, Continue(lInterval)), (rState, out2, Continue(rInterval))) =>
            loop(in, lState, out, lInterval, rState, out2, rInterval)
          case ((lState, out, _), (rState, out2, _)) =>
            ZIO.succeed(((lState, rState), zippable.zip(out, out2), Done))
        }
      }
    }

  /**
   * Returns a new schedule that randomly modifies the size of the intervals of
   * this schedule.
   */
  final def jittered(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out] =
    jittered(0.8, 1.2)

  /**
   * Returns a new schedule that randomly modifies the size of the intervals of
   * this schedule.
   *
   * The new interval size is between `min * old interval size` and `max * old
   * interval size`.
   *
   * [Research](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
   * shows that `jittered(0.0, 1.0)` is a suitable range for a retrying
   * schedule.
   */
  final def jittered(min: Double, max: Double)(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env, In, Out] =
    delayedZIO[Env] { duration =>
      Random.nextDouble.map { random =>
        val d        = duration.toNanos
        val jittered = d * min * (1 - random) + d * max * random

        Duration.fromNanos(jittered.toLong)
      }
    }

  /**
   * Returns a new schedule that makes this schedule available on the `Left`
   * side of an `Either` input, allowing propagating some type `X` through this
   * channel on demand.
   */
  final def left[X]: Schedule.WithState[(self.State, Unit), Env, Either[In, X], Either[Out, X]] =
    self +++ Schedule.identity[X]

  /**
   * Returns a new schedule that maps the output of this schedule through the
   * specified function.
   */
  final def map[Out2](f: Out => Out2)(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out2] =
    self.mapZIO(out => ZIO.succeed(f(out)))

  /**
   * Returns a new schedule that maps the output of this schedule through the
   * specified effectful function.
   */
  final def mapZIO[Env1 <: Env, Out2](f: Out => URIO[Env1, Out2]): Schedule.WithState[self.State, Env1, In, Out2] =
    new Schedule[Env1, In, Out2] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out2, Decision)] =
        self.step(now, in, state).flatMap { case (state, out, decision) =>
          f(out).map(out2 => (state, out2, decision))
        }
    }

  /**
   * Returns a new schedule that modifies the delay using the specified
   * function.
   */
  final def modifyDelay(f: (Out, Duration) => Duration): Schedule.WithState[self.State, Env, In, Out] =
    modifyDelayZIO((out, duration) => Exit.succeed(f(out, duration)))

  /**
   * Returns a new schedule that modifies the delay using the specified
   * effectual function.
   */
  final def modifyDelayZIO[Env1 <: Env](
    f: (Out, Duration) => URIO[Env1, Duration]
  ): Schedule.WithState[self.State, Env1, In, Out] =
    new Schedule[Env1, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).flatMap {
          case (state, out, Done) => ZIO.succeed((state, out, Done))
          case (state, out, Continue(interval)) =>
            val delay = Interval(now, interval.start).size

            f(out, delay).map { duration =>
              val oldStart = interval.start
              val newStart = now.plusNanos(duration.toNanos)
              val delta    = java.time.Duration.between(oldStart, newStart)
              val newEnd =
                if (interval.end == OffsetDateTime.MAX) OffsetDateTime.MAX
                else
                  try { interval.end.plus(delta) }
                  catch { case _: java.time.DateTimeException => OffsetDateTime.MAX }

              val newInterval = Interval(newStart, newEnd)

              (state, out, Continue(newInterval))
            }
        }
    }

  /**
   * Returns a new schedule that applies the current one but runs the specified
   * effect for every decision of this schedule. This can be used to create
   * schedules that log failures, decisions, or computed values.
   */
  final def onDecision[Env1 <: Env](
    f: (State, Out, Decision) => URIO[Env1, Any]
  ): Schedule.WithState[self.State, Env1, In, Out] =
    new Schedule[Env1, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).flatMap { case (state, out, decision) =>
          f(state, out, decision).as((state, out, decision))
        }
    }

  /**
   * Returns a new schedule that passes through the inputs of this schedule.
   */
  final def passthrough[In1 <: In](implicit trace: Trace): Schedule.WithState[self.State, Env, In1, In1] =
    new Schedule[Env, In1, In1] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env, Nothing, (State, In1, Decision)] =
        self.step(now, in, state).map { case (state, _, decision) =>
          (state, in, decision)
        }
    }

  /**
   * Returns a new schedule with its environment provided to it, so the
   * resulting schedule does not require any environment.
   */
  final def provideEnvironment(env: ZEnvironment[Env]): Schedule.WithState[self.State, Any, In, Out] =
    new Schedule[Any, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).provideEnvironment(env)
    }

  /**
   * Transforms the environment being provided to this schedule with the
   * specified function.
   */
  final def provideSomeEnvironment[Env2](
    f: ZEnvironment[Env2] => ZEnvironment[Env]
  ): Schedule.WithState[self.State, Env2, In, Out] =
    new Schedule[Env2, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env2, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).provideSomeEnvironment(f)
    }

  /**
   * Returns a new schedule that reconsiders every decision made by this
   * schedule, possibly modifying the next interval and the output type in the
   * process.
   */
  final def reconsider[Out2](
    f: (State, Out, Decision) => Either[Out2, (Out2, Interval)]
  )(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out2] =
    reconsiderZIO { case (state, out, decision) => ZIO.succeed(f(state, out, decision)) }

  /**
   * Returns a new schedule that effectfully reconsiders every decision made by
   * this schedule, possibly modifying the next interval and the output type in
   * the process.
   */
  final def reconsiderZIO[Env1 <: Env, In1 <: In, Out2](
    f: (State, Out, Decision) => URIO[Env1, Either[Out2, (Out2, Interval)]]
  ): Schedule.WithState[self.State, Env1, In1, Out2] =
    new Schedule[Env1, In1, Out2] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out2, Decision)] =
        self.step(now, in, state).flatMap {
          case (state, out, Done) =>
            f(state, out, Done).map {
              case Left(out2)       => (state, out2, Done)
              case Right((out2, _)) => (state, out2, Done)
            }
          case (state, out, Continue(interval)) =>
            f(state, out, Continue(interval)).map {
              case Left(out2)              => (state, out2, Done)
              case Right((out2, interval)) => (state, out2, Continue(interval))
            }
        }
    }

  /**
   * Returns a new schedule that outputs the number of repetitions of this one.
   */
  final def repetitions(implicit trace: Trace): Schedule.WithState[(self.State, Long), Env, In, Long] =
    fold(0L)((n: Long, _: Out) => n + 1L)

  /**
   * Return a new schedule that automatically resets the schedule to its initial
   * state after some time of inactivity defined by `duration`.
   */
  final def resetAfter(duration: Duration)(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, Option[OffsetDateTime]), Env, In, Out] =
    (self zip Schedule.elapsed).resetWhen(_._2 >= duration).map(_._1)

  /**
   * Resets the schedule when the specified predicate on the schedule output
   * evaluates to true.
   */
  final def resetWhen(f: Out => Boolean): Schedule.WithState[self.State, Env, In, Out] =
    new Schedule[Env, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).flatMap { case (state, out, decision) =>
          if (f(out)) self.step(now, in, self.initial) else ZIO.succeed((state, out, decision))
        }
    }

  /**
   * Returns a new schedule that makes this schedule available on the `Right`
   * side of an `Either` input, allowing propagating some type `X` through this
   * channel on demand.
   */
  final def right[X]: Schedule.WithState[(Unit, self.State), Env, Either[X, In], Either[X, Out]] =
    Schedule.identity[X] +++ self

  /**
   * Runs a schedule using the provided inputs, and collects all outputs.
   */
  final def run(now: OffsetDateTime, input: Iterable[In])(implicit trace: Trace): URIO[Env, Chunk[Out]] = {
    def loop(
      now: OffsetDateTime,
      xs: List[In],
      state: State,
      acc: Chunk[Out]
    ): URIO[Env, Chunk[Out]] =
      xs match {
        case Nil => ZIO.succeed(acc)
        case in :: xs =>
          self.step(now, in, state).flatMap {
            case (_, out, Done)                   => ZIO.succeed(acc :+ out)
            case (state, out, Continue(interval)) => loop(interval.start, xs, state, acc :+ out)
          }
      }

    loop(now, input.toList, self.initial, Chunk.empty)
  }

  /**
   * Returns a new schedule that packs the input and output of this schedule
   * into the second element of a tuple. This allows carrying information
   * through this schedule.
   */
  final def second[X]: Schedule.WithState[(Unit, self.State), Env, (X, In), (X, Out)] =
    Schedule.identity[X] *** self

  /**
   * Returns a new schedule that effectfully processes every input to this
   * schedule.
   */
  final def tapInput[Env1 <: Env, In1 <: In](
    f: In1 => URIO[Env1, Any]
  ): Schedule.WithState[self.State, Env1, In1, Out] =
    new Schedule[Env1, In1, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out, Decision)] =
        f(in) *> self.step(now, in, state)
    }

  /**
   * Returns a new schedule that effectfully processes every output from this
   * schedule.
   */
  final def tapOutput[Env1 <: Env](f: Out => URIO[Env1, Any]): Schedule.WithState[self.State, Env1, In, Out] =
    new Schedule[Env1, In, Out] {
      override type State = self.State
      override final val initial: State = self.initial
      override final def step(now: OffsetDateTime, in: In, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, Out, Decision)] =
        self.step(now, in, state).tap { case (_, out, _) => f(out) }
    }

  /**
   * Returns a new schedule that combines this schedule with the specified
   * schedule, continuing as long as either schedule wants to continue and
   * merging the next intervals according to the specified merge function.
   */
  final def unionWith[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  )(
    f: (Intervals, Intervals) => Intervals
  )(implicit zippable: Zippable[Out, Out2]): Schedule.WithState[(self.State, that.State), Env1, In1, zippable.Out] =
    new Schedule[Env1, In1, zippable.Out] {
      override type State = (self.State, that.State)
      override final val initial: State = (self.initial, that.initial)
      override final def step(now: OffsetDateTime, in: In1, state: State)(implicit
        trace: Trace
      ): ZIO[Env1, Nothing, (State, zippable.Out, Decision)] = {
        val left  = self.step(now, in, state._1)
        val right = that.step(now, in, state._2)

        left.zipWith(right) {
          case ((lstate, l, Done), (rstate, r, Done)) =>
            ((lstate, rstate), zippable.zip(l, r), Done)
          case ((lstate, l, Done), (rstate, r, Continue(interval))) =>
            ((lstate, rstate), zippable.zip(l, r), Continue(interval))
          case ((lstate, l, Continue(interval)), (rstate, r, Done)) =>
            ((lstate, rstate), zippable.zip(l, r), Continue(interval))
          case ((lstate, l, Continue(linterval)), (rstate, r, Continue(rinterval))) =>
            val combined = f(linterval, rinterval)
            ((lstate, rstate), zippable.zip(l, r), Continue(combined))
        }
      }
    }

  /**
   * Returns a new schedule that maps the output of this schedule to unit.
   */
  final def unit(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Unit] =
    self.as(())

  /**
   * Returns a new schedule that continues until the specified predicate on the
   * input evaluates to true.
   */
  final def untilInput[In1 <: In](f: In1 => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env, In1, Out] =
    check((in, _) => !f(in))

  /**
   * Returns a new schedule that continues until the specified effectful
   * predicate on the input evaluates to true.
   */
  final def untilInputZIO[Env1 <: Env, In1 <: In](
    f: In1 => URIO[Env1, Boolean]
  )(implicit trace: Trace): Schedule.WithState[self.State, Env1, In1, Out] =
    checkZIO((in, _) => f(in).map(b => !b))

  /**
   * Returns a new schedule that continues until the specified predicate on the
   * output evaluates to true.
   */
  final def untilOutput(f: Out => Boolean)(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out] =
    check((_, out) => !f(out))

  /**
   * Returns a new schedule that continues until the specified effectful
   * predicate on the output evaluates to true.
   */
  final def untilOutputZIO[Env1 <: Env](f: Out => URIO[Env1, Boolean])(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env1, In, Out] =
    checkZIO((_, out) => f(out).map(b => !b))

  /**
   * Returns a new schedule that continues for as long the specified predicate
   * on the input evaluates to true.
   */
  final def whileInput[In1 <: In](f: In1 => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[self.State, Env, In1, Out] =
    check((in, _) => f(in))

  /**
   * Returns a new schedule that continues for as long the specified effectful
   * predicate on the input evaluates to true.
   */
  final def whileInputZIO[Env1 <: Env, In1 <: In](
    f: In1 => URIO[Env1, Boolean]
  ): Schedule.WithState[self.State, Env1, In1, Out] =
    checkZIO((in, _) => f(in))

  /**
   * Returns a new schedule that continues for as long the specified predicate
   * on the output evaluates to true.
   */
  final def whileOutput(f: Out => Boolean)(implicit trace: Trace): Schedule.WithState[self.State, Env, In, Out] =
    check((_, out) => f(out))

  /**
   * Returns a new schedule that continues for as long the specified effectful
   * predicate on the output evaluates to true.
   */
  final def whileOutputZIO[Env1 <: Env](f: Out => URIO[Env1, Boolean]): Schedule.WithState[self.State, Env1, In, Out] =
    checkZIO((_, out) => f(out))

  /**
   * A named method for `&&`.
   */
  final def zip[Env1 <: Env, In1 <: In, Out2](that: Schedule[Env1, In1, Out2])(implicit
    zippable: Zippable[Out, Out2]
  ): Schedule.WithState[(self.State, that.State), Env1, In1, zippable.Out] =
    self && that

  /**
   * The same as `&&`, but ignores the right output.
   */
  final def zipLeft[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State), Env1, In1, Out] =
    self <* that

  /**
   * The same as `&&`, but ignores the left output.
   */
  final def zipRight[Env1 <: Env, In1 <: In, Out2](
    that: Schedule[Env1, In1, Out2]
  )(implicit trace: Trace): Schedule.WithState[(self.State, that.State), Env1, In1, Out2] =
    self *> that

  /**
   * Equivalent to `zip` followed by `map`.
   */
  final def zipWith[Env1 <: Env, In1 <: In, Out2, Out3](
    that: Schedule[Env1, In1, Out2]
  )(f: (Out, Out2) => Out3)(implicit
    trace: Trace
  ): Schedule.WithState[(self.State, that.State), Env1, In1, Out3] =
    (self zip that).map(f.tupled)
}

object Schedule {

  /**
   * A schedule that recurs anywhere, collecting all inputs into a list.
   */
  def collectAll[A](implicit trace: Trace): Schedule.WithState[(Unit, Chunk[A]), Any, A, Chunk[A]] =
    identity[A].collectAll

  /**
   * A schedule that recurs as long as the condition f holds, collecting all
   * inputs into a list.
   */
  def collectWhile[A](f: A => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[(Unit, Chunk[A]), Any, A, Chunk[A]] =
    identity[A].collectWhile(f)

  /**
   * A schedule that recurs as long as the effectful condition holds, collecting
   * all inputs into a list.
   */
  def collectWhileZIO[Env, A](f: A => URIO[Env, Boolean])(implicit
    trace: Trace
  ): Schedule.WithState[(Unit, Chunk[A]), Env, A, Chunk[A]] =
    identity[A].collectWhileZIO(f)

  /**
   * A schedule that recurs until the condition f fails, collecting all inputs
   * into a list.
   */
  def collectUntil[A](f: A => Boolean)(implicit
    trace: Trace
  ): Schedule.WithState[(Unit, Chunk[A]), Any, A, Chunk[A]] =
    identity[A].collectUntil(f)

  /**
   * A schedule that recurs until the effectful condition f fails, collecting
   * all inputs into a list.
   */
  def collectUntilZIO[Env, A](f: A => URIO[Env, Boolean])(implicit
    trace: Trace
  ): Schedule.WithState[(Unit, Chunk[A]), Env, A, Chunk[A]] =
    identity[A].collectUntilZIO(f)

  /**
   * Takes a schedule that produces a delay, and returns a new schedule that
   * uses this delay to further delay intervals in the resulting schedule.
   */
  def delayed[Env, In](schedule: Schedule[Env, In, Duration])(implicit
    trace: Trace
  ): Schedule.WithState[schedule.State, Env, In, Duration] =
    schedule.addDelay(x => x)

  /**
   * A schedule that recurs for as long as the predicate evaluates to true.
   */
  def recurWhile[A](f: A => Boolean)(implicit trace: Trace): Schedule.WithState[Unit, Any, A, A] =
    identity[A].whileInput(f)

  /**
   * A schedule that recurs for as long as the effectful predicate evaluates to
   * true.
   */
  def recurWhileZIO[Env, A](f: A => URIO[Env, Boolean]): Schedule.WithState[Unit, Env, A, A] =
    identity[A].whileInputZIO(f)

  /**
   * A schedule that recurs for as long as the predicate is equal.
   */
  def recurWhileEquals[A](a: => A)(implicit trace: Trace): Schedule.WithState[Unit, Any, A, A] =
    identity[A].whileInput(_ == a)

  /**
   * A schedule that recurs for until the predicate evaluates to true.
   */
  def recurUntil[A](f: A => Boolean)(implicit trace: Trace): Schedule.WithState[Unit, Any, A, A] =
    identity[A].untilInput(f)

  /**
   * A schedule that recurs for until the predicate evaluates to true.
   */
  def recurUntilZIO[Env, A](f: A => URIO[Env, Boolean])(implicit
    trace: Trace
  ): Schedule.WithState[Unit, Env, A, A] =
    identity[A].untilInputZIO(f)

  /**
   * A schedule that recurs for until the predicate is equal.
   */
  def recurUntilEquals[A](a: => A)(implicit trace: Trace): Schedule.WithState[Unit, Any, A, A] =
    identity[A].untilInput(_ == a)

  /**
   * A schedule that recurs for until the input value becomes applicable to
   * partial function and then map that value with given function.
   */
  def recurUntil[A, B](pf: PartialFunction[A, B])(implicit
    trace: Trace
  ): Schedule.WithState[Unit, Any, A, Option[B]] =
    identity[A].map(pf.lift(_)).untilOutput(_.isDefined)

  /**
   * A schedule that can recur one time, the specified amount of time into the
   * future.
   */
  def duration(duration: Duration): Schedule.WithState[Boolean, Any, Any, Duration] =
    new Schedule[Any, Any, Duration] {
      override type State = Boolean
      override final val initial: State = true
      def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Duration, Decision)] =
        ZIO.succeed {
          if (state) {
            val interval = Interval.after(now.plusNanos(duration.toNanos))
            (false, duration, Decision.Continue(interval))
          } else {
            (false, Duration.Zero, Decision.Done)
          }
        }
    }

  /**
   * A schedule that occurs everywhere, which returns the total elapsed duration
   * since the first step.
   */
  val elapsed: Schedule.WithState[Option[OffsetDateTime], Any, Any, Duration] =
    new Schedule[Any, Any, Duration] {
      override type State = Option[OffsetDateTime]
      override final val initial: State = None
      def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Duration, Decision)] =
        ZIO.succeed {
          state match {
            case None => (Some(now), Duration.Zero, Decision.Continue(Interval(now, OffsetDateTime.MAX)))
            case Some(start) =>
              val duration = Duration.fromInterval(start, now)

              (Some(start), duration, Decision.Continue(Interval(now, OffsetDateTime.MAX)))
          }
        }
    }

  /**
   * A schedule that always recurs, but will wait a certain amount between
   * repetitions, given by `base * factor.pow(n)`, where `n` is the number of
   * repetitions so far. Returns the current duration between recurrences.
   */
  def exponential(base: Duration, factor: Double = 2.0)(implicit
    trace: Trace
  ): Schedule.WithState[Long, Any, Any, Duration] =
    delayed[Any, Any](forever.map(i => base * math.pow(factor, i.doubleValue)))

  /**
   * A schedule that always recurs, increasing delays by summing the preceding
   * two delays (similar to the fibonacci sequence). Returns the current
   * duration between recurrences.
   */
  def fibonacci(
    one: Duration
  )(implicit trace: Trace): Schedule.WithState[(Duration, Duration), Any, Any, Duration] =
    delayed[Any, Any] {
      unfold[(Duration, Duration)]((one, one)) { case (a1, a2) =>
        (a2, a1 + a2)
      }.map(_._1)
    }

  // format: off
  /**
   * A schedule that recurs on a fixed interval. Returns the number of
   * repetitions of the schedule so far.
   *
   * If the action run between updates takes longer than the interval, then the
   * action will be run immediately, but re-runs will not "pile up".
   *
   * <pre>
   * |-----interval-----|-----interval-----|-----interval-----|
   * |---------action--------||action|-----|action|-----------|
   * </pre>
   */
  def fixed(interval: Duration): Schedule.WithState[(Option[(Long, Long)], Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      import java.time.Duration
      override type State = (Option[(Long, Long)], Long)
      override final val initial: State        = (None, 0L)
      private val intervalNanos = interval.toNanos()
      def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        ZIO.succeed(state match {
          case (Some((startNanos, lastRun)), n) =>
            val nowNanos     = Duration.between(Instant.EPOCH, now).toNanos()
            val runningBehind = nowNanos > (lastRun + intervalNanos)
            val boundary =
              if (interval.isZero) interval
              else Duration.ofNanos(intervalNanos - ((nowNanos - startNanos) % intervalNanos))
            val sleepTime = if (boundary.isZero) interval else boundary
            val nextRun   = if (runningBehind) now else now.plus(sleepTime)

            (
              (Some((startNanos, Duration.between(Instant.EPOCH, nextRun.toInstant).toNanos())), n + 1L),
              n,
              Decision.Continue(Interval.after(nextRun))
            )
          case (None, n) =>
            val nowNanos = Duration.between(Instant.EPOCH, now.toInstant).toNanos
            val nextRun   = now.plus(interval)

            (
              (Some((nowNanos, Duration.between(Instant.EPOCH, nextRun.toInstant).toNanos())), n + 1L),
              n,
              Decision.Continue(Interval.after(nextRun))
            )
        })
    }

  /**
   * A schedule that recurs during the given duration
   */
  def upTo(duration: Duration)(implicit
    trace: Trace
  ): Schedule.WithState[Option[OffsetDateTime], Any, Any, Duration] =
    elapsed.whileOutput(_ < duration)

  /**
   * A schedule that always recurs, producing a count of repeats: 0, 1, 2.
   */
  val forever: Schedule.WithState[Long, Any, Any, Long] =
    unfold(0L)(_ + 1L)

  /**
   * A schedule that recurs once with the specified delay.
   */
  def fromDuration(duration: Duration): Schedule.WithState[Boolean, Any, Any, Duration] =
    Schedule.duration(duration)

  /**
   * A schedule that recurs once for each of the specified durations, delaying
   * each time for the length of the specified duration. Returns the length of
   * the current duration between recurrences.
   */
  def fromDurations(
    duration: Duration,
    durations: Duration*
  ): Schedule.WithState[(::[Duration], Boolean), Any, Any, Duration] =
    new Schedule[Any, Any, Duration] {
      override type State = (::[Duration], Boolean)
      override final val initial: State = (::(duration, durations.toList), true)
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Duration, Decision)] = {
        val durations = state._1
        val continue  = state._2
        ZIO.succeed(if (continue) {
          val interval = Interval.after(now.plusNanos(durations.head.toNanos))
          durations match {
            case x :: y :: z => ((::(y, z), true), x, Decision.Continue(interval))
            case x :: y      => ((::(x, y), false), x, Decision.Continue(interval))
          }
        } else ((durations, false), Duration.Zero, Decision.Done))
      }
    }

  /**
   * A schedule that always recurs, mapping input values through the specified
   * function.
   */
  def fromFunction[A, B](f: A => B)(implicit trace: Trace): Schedule.WithState[Unit, Any, A, B] =
    identity[A].map(f)

  /**
   * A schedule that always recurs, which counts the number of recurrences.
   */
  val count: Schedule.WithState[Long, Any, Any, Long] =
    unfold(0L)(_ + 1L)

  /**
   * A schedule that always recurs, which returns inputs as outputs.
   */
  def identity[A]: Schedule.WithState[Unit, Any, A, A] =
    new Schedule[Any, A, A] {
      override type State = Unit
      override final val initial: State = ()

      override final def step(now: OffsetDateTime, in: A, state: State)(implicit
                                                                        trace: Trace
      ): ZIO[Any, Nothing, (State, A, Decision)] =
        ZIO.succeed((state, in, Decision.Continue(Interval.after(now))))
    }

  /**
   * A schedule that always recurs, but will repeat on a linear time interval,
   * given by `base * n` where `n` is the number of repetitions so far. Returns
   * the current duration between recurrences.
   */
  def linear(base: Duration)(implicit trace: Trace): Schedule.WithState[Long, Any, Any, Duration] =
    delayed[Any, Any](forever.map(i => base * (i + 1).doubleValue()))

  /**
   * A schedule that recurs one time.
   */
  def once(implicit trace: Trace): Schedule.WithState[Long, Any, Any, Unit] =
    recurs(1).unit

  /**
   * A schedule spanning all time, which can be stepped only the specified
   * number of times before it terminates.
   */
  def recurs(n: Long)(implicit trace: Trace): Schedule.WithState[Long, Any, Any, Long] =
    forever.whileOutput(_ < n)

  /**
   * A schedule spanning all time, which can be stepped only the specified
   * number of times before it terminates.
   */
  def recurs(n: Int)(implicit trace: Trace): Schedule.WithState[Long, Any, Any, Long] =
    recurs(n.toLong)

  /**
   * Returns a schedule that recurs continuously, each repetition spaced the
   * specified duration from the last run.
   */
  def spaced(duration: Duration)(implicit trace: Trace): Schedule.WithState[Long, Any, Any, Long] =
    forever.addDelay(_ => duration)

  /**
   * A schedule that does not recur, it just stops.
   */
  def stop(implicit trace: Trace): Schedule.WithState[Long, Any, Any, Unit] =
    recurs(0).unit

  /**
   * Returns a schedule that repeats one time, producing the specified constant
   * value.
   */
  def succeed[A](a: => A)(implicit trace: Trace): Schedule.WithState[Long, Any, Any, A] =
    forever.as(a)

  /**
   * Unfolds a schedule that repeats one time from the specified state and
   * iterator.
   */
  def unfold[A](a: => A)(f: A => A): Schedule.WithState[A, Any, Any, A] =
    new Schedule[Any, Any, A] {
      override type State = A
      override final lazy val initial: State = a
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, A, Decision)] =
        ZIO.succeed {
          (f(state), state, Decision.Continue(Interval(now, OffsetDateTime.MAX)))
        }
    }

  //format: off
  /**
   * A schedule that divides the timeline to `interval`-long windows, and sleeps
   * until the nearest window boundary every time it recurs.
   *
   * For example, `windowed(10.seconds)` would produce a schedule as follows:
   * <pre>
   *      10s        10s        10s       10s
   * |----------|----------|----------|----------|
   * |action------|sleep---|act|-sleep|action----|
   * </pre>
   */
  def windowed(interval: Duration): Schedule.WithState[(Option[Long], Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      override type State = (Option[Long], Long)
      override final val initial: State = (None, 0L)
      private val nanos  = interval.toNanos
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        ZIO.succeed(state match {
          case (Some(startNanos), n) =>
            (
              (Some(startNanos), n + 1),
              n,
              Decision.Continue(
                Interval.after(
                  now.plus(
                    nanos - (Duration.fromInterval(Instant.EPOCH, now.toInstant).toNanos - startNanos) % nanos,
                    java.time.temporal.ChronoUnit.NANOS
                  )
                )
              )
            )
          case (None, n) =>
            (
              (Some(Duration.fromInterval(Instant.EPOCH, now.toInstant).toNanos), n + 1),
              n,
              Decision.Continue(Interval.after(now.plus(nanos, java.time.temporal.ChronoUnit.NANOS)))
            )
        })
    }

  /**
   * Cron-like schedule that recurs every specified `second` of each minute. It
   * triggers at zero nanosecond of the second. Producing a count of repeats: 0,
   * 1, 2.
   *
   * NOTE: `second` parameter is validated lazily. Must be in range 0...59.
   */
  def secondOfMinute(second0: Int)(implicit trace: Trace): Schedule.WithState[(OffsetDateTime, Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      override type State = (OffsetDateTime, Long)
      override final val initial: State = (OffsetDateTime.MIN, 0L)
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        if (second0 < 0 || 59 < second0) {
          ZIO.die(
            new IllegalArgumentException(s"Invalid argument in `secondOfMinute($second0)`. Must be in range 0...59")
          )
        } else {
          val (end0, n) = state
          val initial  = n == 0
          val second00 = nextSecond(now, second0, initial)
          val start    = beginningOfSecond(second00)
          val end      = endOfSecond(second00)
          val interval = Interval(start, end)
          ZIO.succeed(((end, n + 1), n, Decision.Continue(interval)))
        }
    }

  /**
   * Cron-like schedule that recurs every specified `minute` of each hour. It
   * triggers at zero second of the minute. Producing a count of repeats: 0, 1,
   * 2.
   *
   * NOTE: `minute` parameter is validated lazily. Must be in range 0...59.
   */
  def minuteOfHour(minute: Int)(implicit trace: Trace): Schedule.WithState[(OffsetDateTime, Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      override type State = (OffsetDateTime, Long)
      override final val initial: State = (OffsetDateTime.MIN, 0L)
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        if (minute < 0 || 59 < minute) {
          ZIO.die(new IllegalArgumentException(s"Invalid argument in `minuteOfHour($minute)`. Must be in range 0...59"))
        } else {
          val (end0, n) = state
          val initial  = n == 0
          val minute0  = nextMinute(now, minute, initial)
          val start    = beginningOfMinute(minute0)
          val end      = endOfMinute(minute0)
          val interval = Interval(start, end)
          ZIO.succeed(((end, n + 1), n, Decision.Continue(interval)))
        }
    }

  /**
   * Cron-like schedule that recurs every specified `hour` of each day. It
   * triggers at zero minute of the hour. Producing a count of repeats: 0, 1, 2.
   *
   * NOTE: `hour` parameter is validated lazily. Must be in range 0...23.
   */
  def hourOfDay(hour: Int)(implicit trace: Trace): Schedule.WithState[(OffsetDateTime, Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      override type State = (OffsetDateTime, Long)
      override final val initial: State = (OffsetDateTime.MIN, 0L)
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        if (hour < 0 || 23 < hour) {
          ZIO.die(new IllegalArgumentException(s"Invalid argument in `hourOfDay($hour)`. Must be in range 0...23"))
        } else {
          val (end0, n) = state
          val initial  = n == 0
          val hour0    = nextHour(now, hour, initial)
          val start    = beginningOfHour(hour0)
          val end      = endOfHour(hour0)
          val interval = Interval(start, end)
          ZIO.succeed(((end, n + 1), n, Decision.Continue(interval)))
        }
    }

  /**
   * Cron-like schedule that recurs every specified `day` of each week. It
   * triggers at zero hour of the week. Producing a count of repeats: 0, 1, 2.
   *
   * NOTE: `day` parameter is validated lazily. Must be in range 1 (Monday)...7
   * (Sunday).
   */
  def dayOfWeek(day: Int)(implicit trace: Trace): Schedule.WithState[(OffsetDateTime, Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      override type State = (OffsetDateTime, Long)
      override final val initial: State = (OffsetDateTime.MIN, 0L)
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        if (day < 1 || 7 < day) {
          ZIO.die(
            new IllegalArgumentException(
              s"Invalid argument in `dayOfWeek($day)`. Must be in range 1 (Monday)...7 (Sunday)"
            )
          )
        } else {
          val (end0, n) = state
          val initial  = n == 0
          val day0     = nextDay(now, day, initial)
          val start    = beginningOfDay(day0)
          val end      = endOfDay(day0)
          val interval = Interval(start, end)
          ZIO.succeed(((end, n + 1), n, Decision.Continue(interval)))
        }
    }

  /**
   * Cron-like schedule that recurs every specified `day` of month. Won't recur
   * on months containing less days than specified in `day` param.
   *
   * It triggers at zero hour of the day. Producing a count of repeats: 0, 1, 2.
   *
   * NOTE: `day` parameter is validated lazily. Must be in range 1...31.
   */
  def dayOfMonth(day: Int)(implicit trace: Trace): Schedule.WithState[(OffsetDateTime, Long), Any, Any, Long] =
    new Schedule[Any, Any, Long] {
      override type State = (OffsetDateTime, Long)
      override final val initial: State = (OffsetDateTime.MIN, 0L)
      override final def step(now: OffsetDateTime, in: Any, state: State)(implicit
        trace: Trace
      ): ZIO[Any, Nothing, (State, Long, Decision)] =
        if (day < 1 || 31 < day) {
          ZIO.die(new IllegalArgumentException(s"Invalid argument in `dayOfMonth($day)`. Must be in range 1...31"))
        } else {
          val (end0, n) = state
          val initial  = n == 0
          val day0     = nextDayOfMonth(now, day, initial)
          val start    = beginningOfDay(day0)
          val end      = endOfDay(day0)
          val interval = Interval(start, end)
          ZIO.succeed(((end, n + 1), n, Decision.Continue(interval)))
        }
    }

  /**
   * An `Interval` represents an interval of time. Intervals can encompass all time, or no time
   * at all.
   */
  sealed abstract class Interval private (val start: OffsetDateTime, val end: OffsetDateTime) { self =>

    final def <(that: Interval): Boolean = (self min that) == self

    final def isEmpty: Boolean =
      start.compareTo(end) >= 0

    final def intersect(that: Interval): Interval = {
      val start = Interval.max(self.start, that.start)
      val end   = Interval.min(self.end, that.end)

      Interval(start, end)
    }

    final def max(that: Interval): Interval = {
      val m = self min that

      if (m == self) that else self
    }

    final def min(that: Interval): Interval =
      if (self.end.compareTo(that.start) <= 0) self
      else if (that.end.compareTo(self.start) <= 0) that
      else if (self.start.compareTo(that.start) < 0) self
      else if (that.start.compareTo(self.start) < 0) that
      else if (self.end.compareTo(that.end) <= 0) self
      else that

    final def nonEmpty: Boolean =
      !isEmpty

    final def size: Duration = Duration.fromNanos(java.time.Duration.between(start, end).toNanos)
  }

  object Interval extends Function2[OffsetDateTime, OffsetDateTime, Interval] {

    /**
     * Constructs a new interval from the two specified endpoints. If the start endpoint greater
     * than the end endpoint, then a zero size interval will be returned.
     */
    def apply(start: OffsetDateTime, end: OffsetDateTime): Interval =
      if (start.isBefore(end) || start == end) new Interval(start, end) {}
      else empty

    def after(start: OffsetDateTime): Interval = Interval(start, OffsetDateTime.MAX)

    def before(end: OffsetDateTime): Interval = Interval(OffsetDateTime.MIN, end)

    /**
     * An interval of zero-width.
     */
    val empty: Interval = Interval(OffsetDateTime.MIN, OffsetDateTime.MIN)

    private def min(l: OffsetDateTime, r: OffsetDateTime): OffsetDateTime = if (l.compareTo(r) <= 0) l else r
    private def max(l: OffsetDateTime, r: OffsetDateTime): OffsetDateTime = if (l.compareTo(r) >= 0) l else r
  }

  /**
   * Intervals represents a set of intervals.
   */
  sealed abstract case class Intervals private (intervals: List[Interval]) { self =>

    /**
     * A symbolic alias for `intersect`.
     */
    def &&(that: Intervals): Intervals =
      self.intersect(that)

    /**
     * A symbolic alias for `union`.
     */
    def ||(that: Intervals): Intervals =
      self.union(that)

    /**
      * The union of this set of intervals and the specified set of intervals
      */
    def union(that: Intervals): Intervals = {

      @tailrec
      def loop(left: List[Interval], right: List[Interval], interval: Interval, acc: List[Interval]): Intervals =
        (left, right) match {
          case (Nil, Nil) =>
            Intervals((interval :: acc).reverse)
          case (Nil, right :: rights)   =>
            if (interval.end.isBefore(right.start)) loop(Nil, rights, right, interval :: acc)
            else loop(Nil, rights, Interval(interval.start, right.end), acc)
          case (left :: lefts, Nil)   =>
            if (interval.end.isBefore(left.start)) loop(lefts, Nil, left, interval :: acc)
            else loop(lefts, Nil, Interval(interval.start, left.end), acc)
          case (left :: lefts, right :: rights) if left.start.isBefore(right.start) =>
            if (interval.end.isBefore(left.start)) loop(lefts, right :: rights, left, interval :: acc)
            else loop(lefts, right :: rights, Interval(interval.start, left.end), acc)
          case (left :: lefts, right :: rights) =>
            if (interval.end.isBefore(right.start)) loop(left :: lefts, rights, right, interval :: acc)
            else loop(left :: lefts, rights, Interval(interval.start, right.end), acc)
        }

      (self.intervals, that.intervals) match {
        case (left, Nil) =>
          Intervals(left)
        case (Nil, right) =>
          Intervals(right)
        case (left :: lefts, right :: rights) if left.start.isBefore(right.start) =>
          loop(lefts, right :: rights, left, List.empty)
        case (left :: lefts, right :: rights) =>
          loop(left :: lefts, rights, right, List.empty)
      }
    }

    /**
      * The intersection of this set of intervals and the specified set of
      * intervals.
      */
    def intersect(that: Intervals): Intervals = {

      @tailrec
      def loop(left: List[Interval], right: List[Interval], acc: List[Interval]): Intervals =
        (left, right) match {
          case (Nil, _) =>
            Intervals(acc.reverse)
          case (_, Nil)   =>
            Intervals(acc.reverse)
          case (left :: lefts, right :: rights) =>
            val interval = left.intersect(right)
            val intervals = if (interval.isEmpty) acc else interval :: acc
            if (left < right) loop(lefts, right :: rights, intervals)
            else loop(left :: lefts, rights, intervals)
        }

      loop(self.intervals, that.intervals, List.empty)
    }

    /**
      * The start of the earliest interval in this set.
      */
    def start: OffsetDateTime =
      intervals.headOption.getOrElse(Interval.empty).start

    /**
      * The end of the latest interval in this set.
      */
    def end: OffsetDateTime =
      intervals.headOption.getOrElse(Interval.empty).end

    /**
      * Whether the start of this set of intervals is before the start of the
      * specified set of intervals
      */
    def <(that: Intervals): Boolean =
      self.start.isBefore(that.start)

    /**
      * Whether this set of intervals is empty.
      */
    def nonEmpty: Boolean =
      intervals.nonEmpty

    /**
     * The set of intervals that starts last.
     */
    def max(that: Intervals): Intervals =
      if (self < that) that else self
  }

  object Intervals {

    /**
     * Constructs a set of intervals from the specified intervals.
     */
    def apply(intervals: Interval*): Intervals =
      intervals.foldLeft(Intervals.empty) { (intervals, interval) =>
        intervals.union(Intervals(List(interval)))
      }

    /**
      * The empty set of intervals.
      */
    val empty: Intervals =
      Intervals(List.empty)

    private def apply(intervals: List[Interval]): Intervals =
      new Intervals(intervals) {}
  }


  def minOffsetDateTime(l: OffsetDateTime, r: OffsetDateTime): OffsetDateTime =
    if (l.compareTo(r) <= 0) l else r

  def maxOffsetDateTime(l: OffsetDateTime, r: OffsetDateTime): OffsetDateTime =
    if (l.compareTo(r) >= 0) l else r

  type WithState[State0, -Env, -In0, +Out0] = Schedule[Env, In0, Out0] { type State = State0 }

  final case class Driver[+State, -Env, -In, +Out](
    next: In => ZIO[Env, None.type, Out],
    last: IO[NoSuchElementException, Out],
    reset: UIO[Unit],
    state: UIO[State]
  )

  sealed trait Decision

  object Decision {

    final case class Continue(interval: Intervals) extends Decision
    object Continue {
      def apply(interval: Interval): Decision =
        Continue(Intervals(interval))
    }

    case object Done                              extends Decision
  }

  private def nextDay(now: OffsetDateTime, day: Int, initial: Boolean): OffsetDateTime = {
    val temporalAdjuster = if (initial) TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(day)) else TemporalAdjusters.next(java.time.DayOfWeek.of(day))
    now.`with`(temporalAdjuster)
  }

  private def nextDayOfMonth(now: OffsetDateTime, day: Int, initial: Boolean): OffsetDateTime =
    if (now.getDayOfMonth == day && initial) now
    else if (now.getDayOfMonth < day) now.`with`(DAY_OF_MONTH, day.toLong)
    else findNextMonth(now, day, 1)

  private def findNextMonth(now: OffsetDateTime, day: Int, months: Int): OffsetDateTime =
    if (now.`with`(DAY_OF_MONTH, day.toLong).plusMonths(months.toLong).getDayOfMonth == day)
      now.`with`(DAY_OF_MONTH, day.toLong).plusMonths(months.toLong)
    else findNextMonth(now, day, months + 1)

  private def nextHour(now: OffsetDateTime, hour: Int, initial: Boolean): OffsetDateTime =
    if (now.getHour == hour && initial) now
    else if (now.getHour < hour) now.`with`(HOUR_OF_DAY, hour.toLong)
    else now.`with`(HOUR_OF_DAY, hour.toLong).plusDays(1)

  private def nextMinute(now: OffsetDateTime, minute: Int, initial: Boolean): OffsetDateTime =
    if (now.getMinute == minute && initial) now
    else if (now.getMinute < minute) now.`with`(MINUTE_OF_HOUR, minute.toLong)
    else now.`with`(MINUTE_OF_HOUR, minute.toLong).plusHours(1L)

  private def nextSecond(now: OffsetDateTime, second: Int, initial: Boolean): OffsetDateTime =
    if (now.getSecond == second && initial) now
    else if (now.getSecond < second) now.`with`(SECOND_OF_MINUTE, second.toLong)
    else now.`with`(SECOND_OF_MINUTE, second.toLong).plusMinutes(1L)

  private def beginningOfDay(now: OffsetDateTime): OffsetDateTime =
    OffsetDateTime.of(now.getYear, now.getMonth.getValue, now.getDayOfMonth, 0, 0, 0, 0, now.getOffset)

  private def endOfDay(now: OffsetDateTime): OffsetDateTime =
    beginningOfDay(now).plusDays(1L)

  private def beginningOfHour(now: OffsetDateTime): OffsetDateTime =
    OffsetDateTime.of(now.getYear, now.getMonth.getValue, now.getDayOfMonth, now.getHour, 0, 0, 0, now.getOffset)

  private def endOfHour(now: OffsetDateTime): OffsetDateTime =
    beginningOfHour(now).plusHours(1L)

  private def beginningOfMinute(now: OffsetDateTime): OffsetDateTime =
    OffsetDateTime.of(
      now.getYear,
      now.getMonth.getValue,
      now.getDayOfMonth,
      now.getHour,
      now.getMinute,
      0,
      0,
      now.getOffset
    )

  private def endOfMinute(now: OffsetDateTime): OffsetDateTime =
    beginningOfMinute(now).plusMinutes(1L)

  private def beginningOfSecond(now: OffsetDateTime): OffsetDateTime =
    OffsetDateTime.of(
      now.getYear,
      now.getMonth.getValue,
      now.getDayOfMonth,
      now.getHour,
      now.getMinute,
      now.getSecond,
      0,
      now.getOffset
    )

  private def endOfSecond(now: OffsetDateTime): OffsetDateTime =
    beginningOfSecond(now).plusSeconds(1L)
}
