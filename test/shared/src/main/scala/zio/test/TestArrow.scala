package zio.test

import zio.ZIO
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}
import zio.ZTraceElement

case class Assert(arrow: TestArrow[Any, Boolean]) {
  def &&(that: Assert): Assert = Assert(arrow && that.arrow)

  def ||(that: Assert): Assert = Assert(arrow || that.arrow)

  def unary_! : Assert = Assert(!arrow)
}

object Assert {
  def all(asserts: Assert*): Assert = asserts.reduce(_ && _)

  def any(asserts: Assert*): Assert = asserts.reduce(_ || _)

  implicit def trace2TestResult(assert: Assert): TestResult = {
    val trace = TestArrow.run(assert.arrow, Right(()))
    if (trace.isSuccess) BoolAlgebra.success(AssertionResult.TraceResult(trace))
    else BoolAlgebra.failure(AssertionResult.TraceResult(trace))
  }

  implicit def traceM2TestResult[R, E](zio: ZIO[R, E, Assert])(implicit trace: ZTraceElement): ZIO[R, E, TestResult] =
    zio.map(trace2TestResult)

}

sealed trait TestArrow[-A, +B] { self =>
  import TestArrow._

  def meta(
    span: Option[Span] = None,
    parentSpan: Option[Span] = None,
    code: Option[String] = None,
    location: Option[String] = None
  ): TestArrow[A, B] = self match {
    case meta: Meta[A, B] =>
      meta.copy(
        span = meta.span.orElse(span),
        parentSpan = meta.parentSpan.orElse(parentSpan),
        code = meta.code.orElse(code),
        location = meta.location.orElse(location)
      )
    case _ =>
      Meta(assert = self, span = span, parentSpan = parentSpan, code = code, location = location)
  }

  def span(span: (Int, Int)): TestArrow[A, B] =
    meta(span = Some(Span(span._1, span._2)))

  def withCode(code: String): TestArrow[A, B] =
    meta(code = Some(code))

  def withLocation(implicit trace: ZTraceElement): TestArrow[A, B] =
    trace match {
      case ZTraceElement.SourceLocation(_, file, line, _) =>
        meta(location = Some(s"$file:$line"))
      case _ => self
    }

  def withParentSpan(span: (Int, Int)): TestArrow[A, B] =
    meta(parentSpan = Some(Span(span._1, span._2)))

  def >>>[C](that: TestArrow[B, C]): TestArrow[A, C] = AndThen[A, B, C](self, that)

  def &&(that: TestArrow[Any, Boolean])(implicit ev: Any <:< A, ev2: B <:< Boolean): TestArrow[Any, Boolean] =
    And(self.asInstanceOf[TestArrow[Any, Boolean]], that)

  def ||(that: TestArrow[Any, Boolean])(implicit ev: Any <:< A, ev2: B <:< Boolean): TestArrow[Any, Boolean] =
    Or(self.asInstanceOf[TestArrow[Any, Boolean]], that)

  def unary_!(implicit ev: Any <:< A, ev2: B <:< Boolean): TestArrow[Any, Boolean] =
    Not(self.asInstanceOf[TestArrow[Any, Boolean]])
}

object TestArrow {

  def succeed[A](value: => A): TestArrow[Any, A] = TestArrowF(_ => Trace.succeed(value))

  def fromFunction[A, B](f: A => B): TestArrow[A, B] = make(f andThen Trace.succeed)

  def suspend[A, B](f: A => TestArrow[Any, B]): TestArrow[A, B] = TestArrow.Suspend(f)

  def make[A, B](f: A => Trace[B]): TestArrow[A, B] =
    makeEither(e => Trace.die(e).annotate(Trace.Annotation.Rethrow), f)

  def makeEither[A, B](onFail: Throwable => Trace[B], onSucceed: A => Trace[B]): TestArrow[A, B] =
    TestArrowF {
      case Left(error)  => onFail(error)
      case Right(value) => onSucceed(value)
    }

  private def attempt[A](f: => Trace[A]): Trace[A] =
    Try(f) match {
      case Failure(exception) => Trace.die(exception)
      case Success(value)     => value
    }

  def run[A, B](assert: TestArrow[A, B], in: Either[Throwable, A]): Trace[B] = attempt {
    assert match {
      case TestArrowF(f) =>
        f(in)

      case AndThen(f, g) =>
        val t1 = run(f, in)
        t1.result match {
          case Result.Fail           => t1.asInstanceOf[Trace[B]]
          case Result.Die(err)       => t1 >>> run(g, Left(err))
          case Result.Succeed(value) => t1 >>> run(g, Right(value))
        }

      case And(lhs, rhs) =>
        run(lhs, in) && run(rhs, in)

      case Or(lhs, rhs) =>
        run(lhs, in) || run(rhs, in)

      case Not(assert) =>
        !run(assert, in)

      case Suspend(f) =>
        in match {
          case Left(exception) =>
            Trace.die(exception)
          case Right(value) =>
            run(f(value), in)
        }

      case Meta(assert, span, parentSpan, code, location) =>
        run(assert, in)
          .withSpan(span)
          .withCode(code)
          .withParentSpan(parentSpan)
          .withLocation(location)
    }
  }

  case class Span(start: Int, end: Int) {
    def substring(str: String): String = str.substring(start, end)

  }

  case class Meta[-A, +B](
    assert: TestArrow[A, B],
    span: Option[Span],
    parentSpan: Option[Span],
    code: Option[String],
    location: Option[String]
  ) extends TestArrow[A, B]
  case class TestArrowF[-A, +B](f: Either[Throwable, A] => Trace[B])            extends TestArrow[A, B] {}
  case class AndThen[A, B, C](f: TestArrow[A, B], g: TestArrow[B, C])           extends TestArrow[A, C]
  case class And(left: TestArrow[Any, Boolean], right: TestArrow[Any, Boolean]) extends TestArrow[Any, Boolean]
  case class Or(left: TestArrow[Any, Boolean], right: TestArrow[Any, Boolean])  extends TestArrow[Any, Boolean]
  case class Not(assert: TestArrow[Any, Boolean])                               extends TestArrow[Any, Boolean]
  case class Suspend[A, B](f: A => TestArrow[Any, B])                           extends TestArrow[A, B]
}
