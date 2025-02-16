package zio

import scala.quoted._

private[zio] trait HasNoScopeCompanionVersionSpecific {

  final transparent inline given hasNoScope[R]: HasNoScope[R] =
    ${ HasNoScopeMacro.noScope[R] }

}

private[zio] object HasNoScopeMacro {

  def noScope[R: Type](using Quotes): Expr[HasNoScope[R]] = {
    import quotes.reflect._

    def intersectionTypes(tpe: TypeRepr): List[Symbol] = tpe.dealias match {
      case AndType(left, right) => intersectionTypes(left) ++ intersectionTypes(right)
      case other                => List(other.typeSymbol)
    }

    val rTypes = intersectionTypes(TypeRepr.of[R])
    val scopeType = TypeRepr.of[zio.Scope].typeSymbol

    if (rTypes.contains(scopeType)) {
      val rName = TypeRepr.of[R].typeSymbol.name 
      report.errorAndAbort(s"The type $rName contains a zio.Scope. This is not allowed.")
    } else if (rTypes.exists(_.isTypeParam)) {
      val rName = rTypes.find(_.isTypeParam).get.name
      report.errorAndAbort(
        s"Can not proof that $rName does not contain a zio.Scope. Please add a context bound ${rName}: HasNoScope."
      )
    } else {
      '{ HasNoScope.noScope.asInstanceOf[HasNoScope[R]] }
    }
  }
}

