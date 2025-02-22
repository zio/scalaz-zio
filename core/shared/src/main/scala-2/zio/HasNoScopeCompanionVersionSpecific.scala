package zio

import scala.language.experimental.macros
import scala.reflect.macros.blackbox

private[zio] abstract class HasNoScopeCompanionVersionSpecific {

  val instance: HasNoScope[Any]

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
      val rName = rType.dealias.typeSymbol.name
      c.abort(
        c.enclosingPosition,
        s"The type $rName contains a zio.Scope. This is not allowed."
      )
    } else if (rType.typeSymbol.isParameter) {
      val rName = rType.dealias.typeSymbol.name
      c.abort(
        c.enclosingPosition,
        s"Can not prove that $rName does not contain a zio.Scope. Please add a context bound $rName: HasNoScope."
      )
    } else if (rTypes.exists(_.typeSymbol.isParameter)) {
      val rName = rTypes.find(_.typeSymbol.isParameter).get.typeSymbol.name
      c.abort(
        c.enclosingPosition,
        s"Can not prove that $rName does not contain a zio.Scope. Please add a context bound $rName: HasNoScope."
      )
    } else {
      reify(HasNoScope.instance.asInstanceOf[HasNoScope[R]])
    }
  }

}
