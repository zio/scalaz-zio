package zio.test

import zio.{Chunk, ZIO}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.reflect.macros.whitebox

class SmartSpecMacros(val c: whitebox.Context) {
  import c.universe._

  sealed trait TestOrStatement extends Product with Serializable

  object TestOrStatement {
    final case class Test(tree: Tree)      extends TestOrStatement
    final case class Statement(tree: Tree) extends TestOrStatement
  }

  def collectTests(tree: Tree): List[TestOrStatement] =
    tree match {
      case Block(statements, finalStatement) =>
        statements.flatMap(collectTests) ++ collectTests(finalStatement)

      case test if test.tpe <:< c.weakTypeOf[Spec[_, _]] =>
        List(TestOrStatement.Test(test))

      case test if test.tpe <:< c.weakTypeOf[ZIO[_, _, Chunk[Spec[_, _]]]] =>
        List(TestOrStatement.Test(test))

      case test if test.tpe <:< c.weakTypeOf[Chunk[Spec[_, _]]] =>
        List(TestOrStatement.Test(test))

      case other =>
        List(TestOrStatement.Statement(other))
    }

  def suiteImpl(name: c.Expr[String])(spec: c.Tree): c.Tree = {
    val result = collectTests(spec)

    @tailrec
    def loop(remaining: List[TestOrStatement], acc: List[Tree], names: List[String]): Tree =
      remaining match {
        case head :: tail =>
          head match {
            case TestOrStatement.Test(tree) =>
              val newName  = c.freshName("test")
              val newNames = newName :: names
              val newTree  = q"specBuffer += $tree"
              loop(tail, newTree :: acc, newNames)
            case TestOrStatement.Statement(tree) =>
              loop(tail, tree :: acc, names)
          }
        case Nil =>
          val allEnvs = result.collect { case TestOrStatement.Test(tree) =>
            tree.tpe.typeArgs.head
          }

          val refined = internal.refinedType(allEnvs, c.prefix.tree.symbol)

          q"""
val specBuffer = scala.collection.mutable.ListBuffer.empty[_root_.zio.test.Spec[$refined,Any]]
..${acc.reverse}
_root_.zio.test.suite($name)(
  specBuffer.toList:_*
)
"""
      }

    loop(result, Nil, Nil)

  }

}
