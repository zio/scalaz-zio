package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.language.implicitConversions
import zio.{Chunk, ChunkBuilder, Trace, ZIO}
import zio.internal.stacktracer.SourceLocation
import zio.test.Assertion.Arguments

import scala.annotation.tailrec
import scala.util.control.NonFatal

case class TestResult(arrow: TestArrow[Any, Boolean]) { self =>

  lazy val result: TestTrace[Boolean] = TestArrow.run(arrow, Right(()))

  lazy val failures: Option[TestTrace[Boolean]] = TestTrace.prune(result, false)

  def isFailure: Boolean = failures.isDefined

  def isSuccess: Boolean = failures.isEmpty

  def &&(that: TestResult): TestResult = TestResult(arrow && that.arrow)

  def ||(that: TestResult): TestResult = TestResult(arrow || that.arrow)

  def unary_! : TestResult = TestResult(!arrow)

  def implies(that: TestResult): TestResult = !self || that

  def ==>(that: TestResult): TestResult = self.implies(that)

  def iff(that: TestResult): TestResult =
    (self ==> that) && (that ==> self)

  def <==>(that: TestResult): TestResult =
    self.iff(that)

  def ??(message: String): TestResult = self.label(message)

  def label(message: String): TestResult = TestResult(arrow.label(message))

  def setGenFailureDetails(details: GenFailureDetails): TestResult =
    TestResult(arrow.setGenFailureDetails(details))
}

object TestResult {
  def allSuccesses(assert: TestResult, asserts: TestResult*): TestResult = asserts.foldLeft(assert)(_ && _)

  def allSuccesses(asserts: Iterable[TestResult])(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    allSuccesses(assertCompletes, asserts.toSeq: _*)

  def anySuccesses(assert: TestResult, asserts: TestResult*): TestResult = asserts.foldLeft(assert)(_ || _)

  def anySuccesses(asserts: Iterable[TestResult])(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    anySuccesses(!assertCompletes, asserts.toSeq: _*)

  implicit def liftTestResultToZIO[R, E](result: TestResult)(implicit trace: Trace): ZIO[R, E, TestResult] =
    if (result.isSuccess)
      ZIO.succeed(result)
    else
      ZIO.die(Exit(result))

  private[zio] final case class Exit(result: TestResult) extends Throwable

  @deprecated("Use allSuccesses", "2.0.16")
  def all(asserts: TestResult*): TestResult = asserts.reduce(_ && _)

  @deprecated("Use anySuccesses", "2.0.16")
  def any(asserts: TestResult*): TestResult = asserts.reduce(_ || _)
}

sealed trait TestArrow[-A, +B] { self =>

  def render: String = {
    val builder = ChunkBuilder.make[String]()

    @tailrec
    def loop(arrows: List[Either[String, TestArrow[_, _]]]): Unit =
      if (arrows.nonEmpty) {
        arrows.head match {
          case Right(TestArrow.And(left, right)) =>
            loop(Left("(") :: Right(left) :: Left(" && ") :: Right(right) :: Left(")") :: arrows.tail)
          case Right(TestArrow.Or(left, right)) =>
            loop(Left("(") :: Right(left) :: Left(" || ") :: Right(right) :: Left(")") :: arrows.tail)
          case Right(TestArrow.Not(arrow)) =>
            loop(Left("not") :: Left("(") :: Right(arrow) :: Left(")") :: arrows.tail)
          case Right(TestArrow.AndThen(left, right)) =>
            loop(Right(left) :: Left("(") :: Right(right) :: Left(")") :: arrows.tail)
          case Right(arrow: TestArrow.Meta[_, _]) =>
            val code = arrow.code.map { code =>
              if (arrow.codeArguments.nonEmpty) s"${code}(${arrow.codeArguments.mkString(", ")})" else code
            }
            builder += code.mkString
            loop(arrows.tail)
          case Right(arrow @ TestArrow.TestArrowF(_)) =>
            builder += arrow.toString
            loop(arrows.tail)
          case Right(arrow @ TestArrow.Suspend(_)) =>
            builder += arrow.toString
            loop(arrows.tail)
          case Left(str) =>
            builder += str
            loop(arrows.tail)
        }
      }

    loop(List(Right(self)))
    builder.result.mkString
  }

  def ??(message: String): TestArrow[A, B] = self.label(message)

  def label(message: String): TestArrow[A, B] = self.meta(customLabel = Some(message))

  def setGenFailureDetails(details: GenFailureDetails): TestArrow[A, B] =
    self.meta(genFailureDetails = Some(details))

  import TestArrow._

  def meta(
    span: Option[Span] = None,
    parentSpan: Option[Span] = None,
    code: Option[String] = None,
    location: Option[String] = None,
    completeCode: Option[String] = None,
    customLabel: Option[String] = None,
    genFailureDetails: Option[GenFailureDetails] = None
  ): TestArrow[A, B] = self match {
    case self: Meta[A, B] =>
      new Meta(
        self.arrow,
        span.orElse(self.span),
        parentSpan.orElse(self.parentSpan),
        code.orElse(self.code),
        location.orElse(self.location),
        completeCode.orElse(self.completeCode),
        customLabel.orElse(self.customLabel),
        genFailureDetails.orElse(self.genFailureDetails)
      ) {
        override def codeArguments: Chunk[Arguments] = self.codeArguments
      }
    case _ =>
      Meta(
        arrow = self,
        span = span,
        parentSpan = parentSpan,
        code = code,
        location = location,
        completeCode = completeCode,
        customLabel = customLabel,
        genFailureDetails = genFailureDetails
      )
  }

  def span(span: (Int, Int)): TestArrow[A, B] =
    meta(span = Some(Span(span._1, span._2)))

  def withCode(code: String): TestArrow[A, B] =
    meta(code = Some(code))

  def withCode(code: String, arguments: Arguments*): TestArrow[A, B] = self match {
    case self: Meta[A, B] =>
      new Meta(
        self.arrow,
        self.span,
        self.parentSpan,
        Some(code),
        self.location,
        self.completeCode,
        self.customLabel,
        self.genFailureDetails
      ) {
        override def codeArguments: Chunk[Arguments] = Chunk.fromIterable(arguments)
      }
    case _ =>
      new Meta(
        arrow = self,
        span = None,
        parentSpan = None,
        code = Some(code),
        location = None,
        completeCode = None,
        customLabel = None,
        genFailureDetails = None
      ) {
        override def codeArguments: Chunk[Arguments] = Chunk.fromIterable(arguments)
      }
  }

  def withCompleteCode(completeCode: String): TestArrow[A, B] =
    meta(completeCode = Some(completeCode))

  def withLocation(implicit sourceLocation: SourceLocation): TestArrow[A, B] =
    meta(location = Some(s"${sourceLocation.path}:${sourceLocation.line}"))

  def withParentSpan(span: (Int, Int)): TestArrow[A, B] =
    meta(parentSpan = Some(Span(span._1, span._2)))

  def >>>[C](that: TestArrow[B, C]): TestArrow[A, C] =
    AndThen[A, B, C](self, that)

  def &&[A1 <: A](that: TestArrow[A1, Boolean])(implicit ev: B <:< Boolean): TestArrow[A1, Boolean] =
    And(self.asInstanceOf[TestArrow[A1, Boolean]], that)

  def ||[A1 <: A](that: TestArrow[A1, Boolean])(implicit ev: B <:< Boolean): TestArrow[A1, Boolean] =
    Or(self.asInstanceOf[TestArrow[A1, Boolean]], that)

  def unary_![A1 <: A](implicit ev: B <:< Boolean): TestArrow[A1, Boolean] =
    Not(self.asInstanceOf[TestArrow[A1, Boolean]])
}

object TestArrow {

  def succeed[A](value: => A): TestArrow[Any, A] = TestArrowF(_ => TestTrace.succeed(value))

  def fromFunction[A, B](f: A => B): TestArrow[A, B] = make(f andThen TestTrace.succeed)

  def suspend[A, B](f: A => TestArrow[Any, B]): TestArrow[A, B] = TestArrow.Suspend(f)

  def make[A, B](f: A => TestTrace[B]): TestArrow[A, B] =
    makeEither(e => TestTrace.die(e).annotate(TestTrace.Annotation.Rethrow), f)

  def makeEither[A, B](onFail: Throwable => TestTrace[B], onSucceed: A => TestTrace[B]): TestArrow[A, B] =
    TestArrowF {
      case Left(error)  => onFail(error)
      case Right(value) => onSucceed(value)
    }

  private def attempt[A](expr: => TestTrace[A]): TestTrace[A] =
    try expr
    catch {
      case ex if NonFatal(ex) =>
        ex.setStackTrace(ex.getStackTrace.filterNot { (ste: StackTraceElement) =>
          ste.getClassName.startsWith("zio.test.TestArrow")
        })
        TestTrace.die(ex)
    }

  def run[A, B](arrow: TestArrow[A, B], in: Either[Throwable, A]): TestTrace[B] = attempt {
    arrow match {
      case TestArrowF(f) =>
        f(in)

      case AndThen(f, g) =>
        val t1 = run(f, in)
        t1.result match {
          case Result.Fail           => t1.asInstanceOf[TestTrace[B]]
          case Result.Die(err)       => t1 >>> run(g, Left(err))
          case Result.Succeed(value) => t1 >>> run(g, Right(value))
        }

      case And(lhs, rhs) =>
        run(lhs, in) && run(rhs, in)

      case Or(lhs, rhs) =>
        run(lhs, in) || run(rhs, in)

      case Not(arrow) =>
        !run(arrow, in)

      case Suspend(f) =>
        in match {
          case Left(exception) =>
            TestTrace.die(exception)
          case Right(value) =>
            run(f(value), in)
        }

      case Meta(arrow, span, parentSpan, code, location, completeCode, customLabel, genFailureDetails) =>
        run(arrow, in)
          .withSpan(span)
          .withCode(code)
          .withParentSpan(parentSpan)
          .withLocation(location)
          .withCompleteCode(completeCode)
          .withCustomLabel(customLabel)
          .withGenFailureDetails(genFailureDetails)
    }

  }

  case class Span(start: Int, end: Int) {
    def substring(str: String): String = {
      val safeStart = math.max(0, math.min(start, str.length))
      val safeEnd   = math.max(safeStart, math.min(end, str.length))
      str.substring(safeStart, safeEnd)
    }
  }

  sealed case class Meta[-A, +B](
    arrow: TestArrow[A, B],
    span: Option[Span],
    parentSpan: Option[Span],
    code: Option[String],
    location: Option[String],
    completeCode: Option[String],
    customLabel: Option[String],
    genFailureDetails: Option[GenFailureDetails]
  ) extends TestArrow[A, B] {
    def codeArguments: Chunk[Arguments] = Chunk.empty
  }
  case class TestArrowF[-A, +B](f: Either[Throwable, A] => TestTrace[B])       extends TestArrow[A, B]
  case class AndThen[A, B, C](f: TestArrow[A, B], g: TestArrow[B, C])          extends TestArrow[A, C]
  case class And[A](left: TestArrow[A, Boolean], right: TestArrow[A, Boolean]) extends TestArrow[A, Boolean]
  case class Or[A](left: TestArrow[A, Boolean], right: TestArrow[A, Boolean])  extends TestArrow[A, Boolean]
  case class Not[A](arrow: TestArrow[A, Boolean])                              extends TestArrow[A, Boolean]
  case class Suspend[A, B](f: A => TestArrow[Any, B])                          extends TestArrow[A, B]
}
