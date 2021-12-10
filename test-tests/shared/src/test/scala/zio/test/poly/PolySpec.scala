package zio.test.poly

import zio.test.Assertion._
import zio.test._
import zio.Random

import scala.annotation.tailrec

object PolySpec extends ZIOSpecDefault {

  sealed trait Expr[+A]

  final case class Value[+A](a: A)                          extends Expr[A]
  final case class Mapping[A, +B](expr: Expr[A], f: A => B) extends Expr[B]

  def eval[A](expr: Expr[A]): A = expr match {
    case Value(a)      => a
    case Mapping(x, f) => f(eval(x))
  }

  @tailrec
  def fuse[A](expr: Expr[A]): Expr[A] = expr match {
    case Mapping(Mapping(x, f), g) => fuse(Mapping(x, f andThen g))
    case x                         => x
  }

  def genValue(t: GenPoly): Gen[Random with Sized, Expr[t.T]] =
    t.genT.map(Value(_))

  def genMapping(t: GenPoly): Gen[Random with Sized, Expr[t.T]] =
    Gen.suspend {
      GenPoly.genPoly.flatMap { t0 =>
        genExpr(t0).flatMap { expr =>
          val genFunction: Gen[Random with Sized, t0.T => t.T] = Gen.function(t.genT)
          val genExpr1: Gen[Random with Sized, Expr[t.T]]      = genFunction.map(f => Mapping(expr, f))
          genExpr1
        }
      }
    }

  def genExpr(t: GenPoly): Gen[Random with Sized, Expr[t.T]] =
    Gen.oneOf(genMapping(t), genValue(t))

  def spec = suite("PolySpec")(
    test("map fusion") {
      check(GenPoly.genPoly.flatMap(genExpr(_)))(expr => assert(eval(fuse(expr)))(equalTo(eval(expr))))
    }
  )
}
