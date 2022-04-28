package zio.test

import scala.quoted.*

trait ZIOSpecVersionSpecific[R] { self: ZIOSpec[R] =>

  transparent inline def suiteAll(inline name: String)(inline spec: Any): Any =
     ${ ZIOSpecVersionSpecificMacros.suiteAllImpl('name, 'spec) }

}


object ZIOSpecVersionSpecificMacros {

  def suiteAllImpl(name: Expr[String], spec: Expr[Any])(using ctx: Quotes) = {
    import ctx.reflect._

    enum TestOrStatement {
      case SpecCase(term: Term)
      case StatementCase(statement: Statement) 
    }

    def collectTests(tree: Tree): List[TestOrStatement] =
      tree match {

        case Block(stats, expr) =>
          stats.flatMap(collectTests) ++ collectTests(expr)

        case vd @ ValDef(_, _, _) => 
          List(TestOrStatement.StatementCase(vd))
        
        case spec: Term if spec.tpe <:< TypeRepr.of[Spec[_,_]] =>
          List(TestOrStatement.SpecCase(spec))

        case other =>

          throw new Error("UNHANDLED: " + other)
      }

    var idx = 0

    def loop(results: List[TestOrStatement], acc: List[Statement], refs: List[Ref]): Term =
      results match {
        case TestOrStatement.StatementCase(tree) :: rest =>
          loop(rest, tree :: acc, refs)
        case TestOrStatement.SpecCase(spec) :: rest =>
          val name = "spec" + idx
          idx += 1
          val symbol = Symbol.newVal(Symbol.spliceOwner, name, spec.tpe, Flags.EmptyFlags, Symbol.noSymbol)
          val valDef = ValDef(symbol, Some(spec))
          val ref = Ref(symbol)
          loop(rest, valDef :: acc, ref :: refs)
        case Nil =>



          val mySuite =  {
              val combinedTypes = refs.map(_.tpe).reduce(OrType(_, _)).widen
              val names = 
                combinedTypes.asType match {
                  case '[specType] =>
                    Varargs(refs.map{a => 
                      a.asExprOf[specType]
                    }).asExprOf[Seq[specType]]
                }
              
              names match {
                case '{ $specNames: Seq[Spec[a, b]] } =>
                  '{ suite($name)($specNames) }
              }
          }

          Block(
            acc.reverse,
            mySuite.asTerm
          )
      }

    spec.asTerm match {
      case Inlined(a, b, expr) =>
        val results = collectTests(expr)
        val combined = Inlined(a, b, loop(results, Nil, Nil))
        // println("--")
        // println(combined.tpe.dealias.widen.simplified)
        combined.asExpr
    }

  }
}