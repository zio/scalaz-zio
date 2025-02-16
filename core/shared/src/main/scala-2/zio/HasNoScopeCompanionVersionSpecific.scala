package zio

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

private[zio] trait HasNoScopeCompanionVersionSpecific {
  implicit def noScope[R]: HasNoScope[R] =
    macro HasNoScopeMacro.impl[R]
}

private[zio] class HasNoScopeMacro(val c: blackbox.Context) {
  import c.universe._

  /**
   * Given a type `A with B with C` You'll get back List[A,B,C]
   */
  def intersectionTypes(self: Type): List[Type] =
    self.dealias match {
      case t: RefinedType =>
        t.parents.flatMap(intersectionTypes)
      case TypeRef(_, sym, _) if sym.info.isInstanceOf[RefinedTypeApi] =>
        intersectionTypes(sym.info)
      case other =>
        List(other)
    }

  def impl[R: c.WeakTypeTag]: c.Expr[HasNoScope[R]] = {
    import c._

    val rType     = weakTypeOf[R]
    val rTypes    = intersectionTypes(rType.dealias.map(_.dealias))
    val scopeType = weakTypeOf[zio.Scope]
    if (rTypes.contains(scopeType)) {
      c.abort(
        c.enclosingPosition,
        "Routes can not have a zio.Scope dependency in their environment type. You can use Handler.scoped to scope your requests."
      )
    } else if (rType.typeSymbol.isParameter) {
      val rName = rType.dealias.typeSymbol.name
      c.abort(
        c.enclosingPosition,
        s"The type $rName contains a zio.Scope. This is not allowed."
      )
    } else if (rTypes.exists(_.typeSymbol.isParameter)) {
      val rName = rTypes.find(_.typeSymbol.isParameter).get.typeSymbol.name
      c.abort(
        c.enclosingPosition,
        s"Can not proof that $rName does not contain a zio.Scope. Please add a context bound $rName: HasNoScope."
      )
    } else {
      reify(HasNoScope.noScope.asInstanceOf[HasNoScope[R]])
    }
  }

}
