/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
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

import zio._
import zio.test.internal.SmartAssertions

import scala.quoted._
import scala.reflect.ClassTag

object SmartAssertMacros {
  def smartAssertSingle(expr: Expr[Boolean], trace: Expr[Trace])(using Quotes): Expr[TestResult] =
    SmartAssertMacros.smartAssertSingle_impl(expr, trace)

  def smartAssert(exprs: Expr[Seq[Boolean]], trace: Expr[Trace])(using Quotes): Expr[TestResult] =
    SmartAssertMacros.smartAssert_impl(exprs, trace)


  extension (using Quotes)(typeRepr: quotes.reflect.TypeRepr) {
    def typeTree: quotes.reflect.TypeTree = {
      import quotes.reflect._
      typeRepr.widen.asType match  {
        case '[tp] => TypeTree.of[tp]
      }
    }
  }

  object MethodCall {
    def unapply(using Quotes)(tree: quotes.reflect.Term): Option[(quotes.reflect.Term, String, List[quotes.reflect.TypeRepr], Option[List[quotes.reflect.Term]])] = {
      import quotes.reflect._
      tree match {
        case Select(lhs, name) => Some((lhs, name, List.empty, None))
        case TypeApply(Select(lhs, name), tpes) => Some((lhs, name, tpes.map(_.tpe), None))
        case Apply(Select(lhs, name), args) => Some((lhs, name, List.empty, Some(args)))
        case Apply(TypeApply(Select(lhs, name), tpes), args) => Some((lhs, name, tpes.map(_.tpe), Some(args)))
        case _ => None
      }
    }
  }

  case class PositionContext(start: Int) 

  object PositionContext {
    def apply(using Quotes)(term: quotes.reflect.Term) = new PositionContext(term.pos.start)
  }

  def transformAs[Start: Type, End: Type](expr: Expr[TestLens[End]])(start: Expr[TestArrow[Any, Start]])(using PositionContext, Quotes) : Expr[TestArrow[Any, End]] = {
   val res = expr match {
      case '{ TestLensAnyOps($lhs: TestLens[a]).anything } =>
        val arrow = transformAs[Start, a](lhs.asInstanceOf[Expr[TestLens[a]]])(start)
        '{ $arrow >>> SmartAssertions.anything }

      case '{ type a; TestLensAnyOps($lhs: TestLens[`a`]).custom($customAssertion: CustomAssertion[`a`, End]) } =>
        val arrow = transformAs[Start, a](lhs.asInstanceOf[Expr[TestLens[a]]])(start)
        '{ $arrow >>> SmartAssertions.custom[a, End]($customAssertion) }

      case '{ type a >: End; TestLensAnyOps($lhs: TestLens[`a`]).subtype[End] } =>
        val arrow = transformAs[Start, a](lhs.asInstanceOf[Expr[TestLens[a]]])(start)
        Expr.summon[ClassTag[End]] match {
          case Some(tag) =>
            '{ $arrow >>> SmartAssertions.as[a, End]($tag) }
          case None =>
            throw new Error("NEED CLASS TAG")
        }

      case '{ TestLensOptionOps($lhs: TestLens[Option[End]]).some } =>
        val arrow = transformAs[Start, Option[End]](lhs.asInstanceOf[Expr[TestLens[Option[End]]]])(start)
        '{ $arrow >>> SmartAssertions.isSome }

      case '{ type e; TestLensEitherOps[`e`, End]($lhs: TestLens[Either[`e`, End]]).right }  =>
        val arrow = transformAs[Start, Either[e, End]](lhs.asInstanceOf[Expr[TestLens[Either[e, End]]]])(start)
        '{ $arrow >>> SmartAssertions.asRight }

      case '{ type e; TestLensEitherOps[End, `e`]($lhs: TestLens[Either[End, `e`]]).left }  =>
        val arrow = transformAs[Start, Either[End, e]](lhs.asInstanceOf[Expr[TestLens[Either[End, e]]]])(start)
        '{ $arrow >>> SmartAssertions.asLeft }

     case '{ TestLensCauseOps($lhs: TestLens[Cause[End]]).failure } =>
       val arrow = transformAs[Start, Cause[End]](lhs.asInstanceOf[Expr[TestLens[Cause[End]]]])(start)
       '{ $arrow >>> SmartAssertions.asCauseFailure }

     case '{ type a; TestLensCauseOps($lhs: TestLens[Cause[`a`]]).die } =>
       val arrow = transformAs[Start, Cause[a]](lhs.asInstanceOf[Expr[TestLens[Cause[a]]]])(start)
       '{ $arrow >>> SmartAssertions.asCauseDie }

     case '{ type a; TestLensCauseOps($lhs: TestLens[Cause[`a`]]).interrupted } =>
       val arrow = transformAs[Start, Cause[a]](lhs.asInstanceOf[Expr[TestLens[Cause[a]]]])(start)
       '{ $arrow >>> SmartAssertions.asCauseInterrupted }

     case '{ type a; TestLensExitOps($lhs: TestLens[Exit[End, `a`]]).failure } =>
       val arrow = transformAs[Start, Exit[End, a]](lhs.asInstanceOf[Expr[TestLens[Exit[End, a]]]])(start)
       '{ $arrow >>> SmartAssertions.asExitFailure }

     case '{ type a; TestLensExitOps($lhs: TestLens[Exit[`a`, End]]).success } =>
       val arrow = transformAs[Start, Exit[a, End]](lhs.asInstanceOf[Expr[TestLens[Exit[a, End]]]])(start)
       '{ $arrow >>> SmartAssertions.asExitSuccess }

     case '{ type e; type a; TestLensExitOps($lhs: TestLens[Exit[`e`, `a`]]).die } =>
       val arrow = transformAs[Start, Exit[e, a]](lhs.asInstanceOf[Expr[TestLens[Exit[e, a]]]])(start)
       '{ $arrow >>> SmartAssertions.asExitDie }

     case '{ type e; type a; TestLensExitOps($lhs: TestLens[Exit[`e`, `a`]]).interrupted } =>
       val arrow = transformAs[Start, Exit[e, a]](lhs.asInstanceOf[Expr[TestLens[Exit[e, a]]]])(start)
       '{ $arrow >>> SmartAssertions.asExitInterrupted }

     case other =>
       start
    }
    res.asInstanceOf[Expr[TestArrow[Any, End]]]
  }

  def transform[A: Type](expr: Expr[A])(using PositionContext, Quotes) : Expr[TestArrow[Any, A]] = {
    import quotes.reflect._

    def getSpan(term: quotes.reflect.Term): Expr[(Int, Int)] = 
      Expr(term.pos.start - summon[PositionContext].start, term.pos.end - summon[PositionContext].start)

    expr match {
      case '{ type t; type v; zio.test.SmartAssertionOps[`t`](${something}: `t`).is[`v`](${Unseal(Lambda(terms, body))}) } =>
        val lhs = transform(something).asInstanceOf[Expr[TestArrow[Any, t]]]
        val res = transformAs(body.asExprOf[TestLens[v]])(lhs)
        res.asInstanceOf[Expr[TestArrow[Any, A]]]

      case Unseal(Inlined(a, b, expr)) => Inlined(a, b, transform(expr.asExprOf[A]).asTerm).asExprOf[zio.test.TestArrow[Any, A]]

      case Unseal(Apply(Select(lhs, op @ (">" | ">=" | "<" | "<=")), List(rhs))) =>
        val span = getSpan(rhs)
        lhs.tpe.widen.asType match {
          case '[l] => 
            Expr.summon[Ordering[l]] match { 
              case Some(ord) =>
                op match {
                    case ">" =>
                      '{${transform(lhs.asExprOf[l])} >>> SmartAssertions.greaterThan(${rhs.asExprOf[l]})($ord).span($span)}.asExprOf[TestArrow[Any, A]]
                    case ">=" =>
                      '{${transform(lhs.asExprOf[l])} >>> SmartAssertions.greaterThanOrEqualTo(${rhs.asExprOf[l]})($ord).span($span)}.asExprOf[TestArrow[Any, A]]
                    case "<" =>
                      '{${transform(lhs.asExprOf[l])} >>> SmartAssertions.lessThan(${rhs.asExprOf[l]})($ord).span($span)}.asExprOf[TestArrow[Any, A]]
                    case "<=" =>
                      '{${transform(lhs.asExprOf[l])} >>> SmartAssertions.lessThanOrEqualTo(${rhs.asExprOf[l]})($ord).span($span)}.asExprOf[TestArrow[Any, A]]
                }
              case _ => throw new Error("NO")
            }
        }

      case Unseal(MethodCall(lhs, "==", tpes, Some(List(rhs)))) =>
        val span = getSpan(rhs)
        lhs.tpe.widen.asType match {
          case '[l] => 
            '{${transform(lhs.asExprOf[l])} >>> SmartAssertions.equalTo(${rhs.asExprOf[l]}).span($span)}.asExprOf[TestArrow[Any, A]]
        }


      case Unseal(method @ MethodCall(lhs, name, tpeArgs, args)) =>
        def body(param: Term) =
          (tpeArgs, args) match {
            case (Nil, None) => Select.unique(param, name)
            case (tpeArgs, Some(args)) => Select.overloaded(param, name, tpeArgs, args)
            case (tpeArgs, None) => TypeApply(Select.unique(param, name), tpeArgs.map(_.typeTree))
          }

        val tpe = lhs.tpe.widen

        if(tpe.typeSymbol.isPackageDef)
          '{TestArrow.succeed($expr).span(${getSpan(method)})}
        else
          tpe.asType match {
            case '[l] =>
              val selectBody = '{
                (from: l) => ${ body('{from}.asTerm).asExprOf[A] }
              }
              val lhsExpr = transform(lhs.asExprOf[l]).asExprOf[TestArrow[Any, l]]
              val assertExpr = '{TestArrow.fromFunction[l, A](${selectBody})}
              val pos = summon[PositionContext]
              val span = Expr((lhs.pos.end - pos.start, method.pos.end - pos.start))
              '{$lhsExpr >>> $assertExpr.span($span)}
          }


      case Unseal(tree) =>
        val span = getSpan(tree)
       '{TestArrow.succeed($expr).span($span)}
    }
  }

  def smartAssertSingle_impl(using Quotes)(value: Expr[Boolean], trace: Expr[Trace]): Expr[TestResult] = {
    import quotes.reflect._
    val code = Macros.showExpr(value)

    implicit val ptx: PositionContext = 
      PositionContext(value.asTerm)

    val ast = transform(value)

    val arrow = ast.asExprOf[TestArrow[Any, Boolean]]
    '{TestResult($arrow.withCode(${Expr(code)}).withLocation($trace))}
  }

  def smartAssert_impl(using Quotes)(values: Expr[Seq[Boolean]], trace: Expr[Trace]): Expr[TestResult] = {
    import quotes.reflect._

    values match {
        case Varargs(head +: tail) =>
          tail.foldLeft(smartAssertSingle_impl(head, trace)) { (acc, expr) =>
            '{$acc && ${smartAssertSingle_impl(expr, trace)}}
        }

        case other =>
          throw new Error(s"Improper Varargs: ${other}")
    }
  }

  object Unseal {
    def unapply(using Quotes)(expr: Expr[_]): Option[quotes.reflect.Term] = {
      import quotes.reflect._
      Some(expr.asTerm)
    }
  }
}

object Macros {
  def assertZIO_impl[R: Type, E: Type, A: Type](effect: Expr[ZIO[R, E, A]])(assertion: Expr[Assertion[A]])
                                               (using Quotes): Expr[ZIO[R, E, TestResult]] = {
    import quotes.reflect._
    val code = Expr(showExpr(effect))
    val assertionCode = Expr(showExpr(assertion))
    '{_root_.zio.test.CompileVariants.assertZIOProxy($effect, $code, $assertionCode)($assertion)}
  }

  def assert_impl[A](value: Expr[A])(assertion: Expr[Assertion[A]], trace: Expr[Trace])(using Quotes, Type[A]): Expr[TestResult] = {
    import quotes.reflect._
    val code = showExpr(value)
    val assertionCode = showExpr(assertion)
    '{_root_.zio.test.CompileVariants.assertProxy($value, ${Expr(code)}, ${Expr(assertionCode)})($assertion)($trace)}
  }

  def showExpr[A](expr: Expr[A])(using Quotes): String = {
    import quotes.reflect._
    expr.asTerm.pos.sourceCode.get
  }

  def showExpression_impl[A](value: Expr[A])(using Quotes): Expr[String] = {
    import quotes.reflect._
    Expr(showExpr(value))
  }
}



