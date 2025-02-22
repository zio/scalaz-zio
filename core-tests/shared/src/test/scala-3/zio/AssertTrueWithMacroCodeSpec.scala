package zio

import zio.test.*
import zio.test.TestArrow.Meta
import zio.test.TestArrow.TestArrowF
import zio.test.TestArrow.AndThen
import zio.test.TestArrow.And
import zio.test.TestArrow.Or
import zio.test.TestArrow.Not
import zio.test.TestArrow.Suspend
import zio.test.TestArrow.Span

object AssertTrueWithMacroCodeSpec extends ZIOBaseSpec {

  def spec: Spec[TestEnvironment & Scope, Any] =
    test(
      "[Issue #8571] the span of an inline definition is correct in relation to its actual sourcecode (and not to its macro-expanded form)"
    ) {
      val arrow = assertTrue(expandMe == 3).arrow
      val spans = collectSpans(arrow)
      // 0 - 8 maps to the code string of 'expandMe', 12 - 13 maps to the code string of '3'
      val expectedSpans = Span(0, 8) :: Span(12, 13) :: Nil
      assertTrue(spans == expectedSpans)
    }

  private def collectSpans(arrow: TestArrow[Any, ?]) = {
    def recurse(accumulator: List[Span], stack: List[TestArrow[Any, ?]]): List[Span] =
      stack match {
        case Nil => accumulator
        case head :: next =>
          head match {
            case Meta(arrow, span, parentSpan, code, location, completeCode, customLabel, genFailureDetails) =>
              recurse(accumulator ::: span.toList ::: parentSpan.toList, arrow :: next)
            case TestArrowF(f)    => recurse(accumulator, next)
            case AndThen(f, g)    => recurse(accumulator, f :: g.asInstanceOf[TestArrow[Any, ?]] :: next)
            case And(left, right) => recurse(accumulator, left :: right :: next)
            case Or(left, right)  => recurse(accumulator, left :: right :: next)
            case Not(arrow)       => recurse(accumulator, arrow :: next)
            case Suspend(f)       => recurse(accumulator, f(()) :: next)
          }
      }
    recurse(Nil, arrow :: Nil)
  }

  private inline def expandMe = {
    val arbritraryDef  = "I'm an arbitrary definition!"
    val arbritraryDef2 = "I'm an arbitrary definition too!"
    1
  }
}
