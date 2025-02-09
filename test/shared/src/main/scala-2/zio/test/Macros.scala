/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

import zio.UIO
import zio.internal.macros.CleanCodePrinter

import scala.reflect.macros.{TypecheckException, blackbox}

private[test] object Macros {

  def typeCheck_impl(c: blackbox.Context)(code: c.Expr[String]): c.Expr[UIO[Either[String, Unit]]] = {
    import c.universe._
    try {
      c.typecheck(c.parse(c.eval(c.Expr[String](c.untypecheck(code.tree)))))
      c.Expr(q"zio.ZIO.succeed(scala.util.Right(()))")
    } catch {
      case e: TypecheckException => c.Expr(q"zio.ZIO.succeed(scala.util.Left(${e.getMessage}))")
      case t: Throwable          => c.Expr(q"""zio.ZIO.die(new RuntimeException("Compilation failed: " + ${t.getMessage}))""")
    }
  }

  private[test] val fieldInAnonymousClassPrefix = "$anon.this."

  def assertZIO_impl(c: blackbox.Context)(effect: c.Tree)(assertion: c.Tree): c.Tree = {
    import c.universe._
    q"_root_.zio.test.CompileVariants.assertZIOProxy($effect)($assertion)"
  }

  def assert_impl(c: blackbox.Context)(expr: c.Tree)(assertion: c.Tree): c.Tree = {
    import c.universe._
    val code = CleanCodePrinter.show(c)(expr)
    q"_root_.zio.test.CompileVariants.assertProxy($expr, $code)($assertion)"
  }

  def new_assert_impl(c: blackbox.Context)(expr: c.Tree)(assertion: c.Tree): c.Tree = {
    import c.universe._

    // Pilfered (with immense gratitude & minor modifications)
    // from https://github.com/com-lihaoyi/sourcecode
    def text[T: c.WeakTypeTag](tree: c.Tree): (Int, Int, String) = {
      val fileContent = new String(tree.pos.source.content)
      var start = tree.collect { case treeVal =>
        treeVal.pos match {
          case NoPosition => Int.MaxValue
          case p          => p.start
        }
      }.min
      val initialStart = start

      // Moves to the true beginning of the expression, in the case where the
      // internal expression is wrapped in parens.
      while ((start - 2) >= 0 && fileContent(start - 2) == '(') {
        start -= 1
      }

      val g      = c.asInstanceOf[reflect.macros.runtime.Context].global
      val parser = g.newUnitParser(fileContent.drop(start))
      parser.expr()
      val end = parser.in.lastOffset
      (initialStart - start, start, fileContent.slice(start, start + end))
    }

    val codeString      = text(expr)._3
    val assertionString = text(assertion)._3
    q"_root_.zio.test.CompileVariants.newAssertProxy($expr, $codeString, $assertionString)($assertion)"
  }

  def showExpression_impl(c: blackbox.Context)(expr: c.Tree): c.Tree = {
    import c.universe._
    q"${CleanCodePrinter.show(c)(expr)}"
  }
}
