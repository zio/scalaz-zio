package fix

import scalafix.v1._
import scala.meta._

class CurriedAssert extends SemanticRule("CurriedAssert") {

  val assert = SymbolMatcher.normalized(
    "zio.test.package.assert",
    "zio.test.package.assertM"
  )

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t @ assert(Term.Apply.After_4_6_0(name, argClause)) if argClause.values.size == 2 =>
        val (value, assertion) = (argClause.values: @unchecked) match {
          case List(value, assertion) => (value, assertion)
        }
        Patch.replaceTree(t, name.toString + "(" + value + ")(" + assertion + ")")
      case _ =>
        Patch.empty
    }.asPatch
}
