package zio.test

import zio.internal.stacktracer.SourceLocation
import zio.{Exit, Scope, Trace, ZIO}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stm.ZSTM

trait TestConstructor[-Environment, In] {
  type Out <: Spec[Environment, Any]
  def apply(label: String)(assertion: => In)(implicit sourceLocation: SourceLocation, trace: Trace): Out
}

object TestConstructor extends TestConstructorLowPriority1 {
  type WithOut[Environment, In, Out0] = TestConstructor[Environment, In] { type Out = Out0 }

  implicit def AssertConstructor[A <: TestResult]: TestConstructor.WithOut[Any, A, Spec[Any, Nothing]] =
    TestConstructorInstances.AssertConstructor0
      .asInstanceOf[TestConstructor.WithOut[Any, A, Spec[Any, Nothing]]]
}

trait TestConstructorLowPriority1 extends TestConstructorLowPriority2 {

  implicit def AssertZIOConstructor[R, E, A <: TestResult]: TestConstructor.WithOut[R, ZIO[R, E, A], Spec[R, E]] =
    TestConstructorInstances.AssertZIOConstructor0
      .asInstanceOf[TestConstructor.WithOut[R, ZIO[R, E, A], Spec[R, E]]]
}

trait TestConstructorLowPriority2 extends TestConstructorLowPriority3 {

  implicit def AssertZSTMConstructor[R, E, A <: TestResult]: TestConstructor.WithOut[R, ZSTM[R, E, A], Spec[R, E]] =
    TestConstructorInstances.AssertZSTMConstructor0
      .asInstanceOf[TestConstructor.WithOut[R, ZSTM[R, E, A], Spec[R, E]]]
}

trait TestConstructorLowPriority3 {

  implicit def AssertEitherConstructor[E, A <: TestResult]: TestConstructor.WithOut[Any, Either[E, A], Spec[Any, E]] =
    TestConstructorInstances.AssertEitherConstructor0
      .asInstanceOf[TestConstructor.WithOut[Any, Either[E, A], Spec[Any, E]]]
}

private[this] object TestConstructorInstances {

  val AssertZIOConstructor0: TestConstructor.WithOut[Any, ZIO[Any, Any, TestResult], Spec[Any, Any]] =
    new TestConstructor[Any, ZIO[Any, Any, TestResult]] {
      type Out = Spec[Any, Any]
      def apply(
        label: String
      )(
        assertion: => ZIO[Any, Any, TestResult]
      )(implicit sourceLocation: SourceLocation, trace: Trace): Spec[Any, Any] = {
        val assertion0 = ZIO.suspendSucceed(assertion)
        Spec.labeled(
          label,
          Spec
            .test(ZTest(label, assertion0), TestAnnotationMap.empty)
            .annotate(TestAnnotation.trace, sourceLocation :: Nil)
        )
      }
    }

  val AssertZSTMConstructor0: TestConstructor.WithOut[Any, ZSTM[Any, Any, TestResult], Spec[Any, Any]] =
    new TestConstructor[Any, ZSTM[Any, Any, TestResult]] {
      type Out = Spec[Any, Any]
      def apply(label: String)(
        assertion: => ZSTM[Any, Any, TestResult]
      )(implicit sourceLocation: SourceLocation, trace: Trace): Spec[Any, Any] =
        AssertZIOConstructor0(label)(assertion.commit)
    }

  val AssertEitherConstructor0: TestConstructor.WithOut[Any, Either[Any, TestResult], Spec[Any, Any]] =
    new TestConstructor[Any, Either[Any, TestResult]] {
      type Out = Spec[Any, Any]
      def apply(label: String)(
        assertion: => Either[Any, TestResult]
      )(implicit sourceLocation: SourceLocation, trace: Trace): Spec[Any, Any] =
        AssertZIOConstructor0(label)(ZIO.fromEither(assertion))
    }

  val AssertConstructor0: TestConstructor.WithOut[Any, TestResult, Spec[Any, Any]] =
    new TestConstructor[Any, TestResult] {
      type Out = Spec[Any, Any]
      def apply(label: String)(
        assertion: => TestResult
      )(implicit sourceLocation: SourceLocation, trace: Trace): Spec[Any, Any] =
        AssertZIOConstructor0(label)(ZIO.succeed(assertion))
    }
}
