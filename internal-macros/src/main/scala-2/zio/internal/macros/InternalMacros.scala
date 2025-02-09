package zio.internal.macros

import scala.reflect.macros.blackbox

class InternalMacros(val c: blackbox.Context) {
  import c.universe._

  def materializeTag[A: c.WeakTypeTag]: c.Tree = {
    val tpe = c.weakTypeOf[A]
    tpe.widen.dealias match {
      case x if x.typeSymbol.isParameter =>
        try {
          val serviceTagType = c.typecheck(q"_root_.zio.Tag[$x]").tpe
          c.inferImplicitValue(serviceTagType, silent = false, withMacrosDisabled = true)
        } catch {
          case _: Throwable =>
            c.abort(c.enclosingPosition, s"Cannot find implicit for Tag[$x]")
        }
      case tpe if isIntersection(tpe) =>
        c.abort(c.enclosingPosition, s"A Tag may not contain an intersection type, yet have provided: $tpe")
      case _ =>
        q"_root_.zio.Tag[$tpe]"
    }
  }

  def flattenIntersection(tpe: Type): List[Type] = tpe match {
    case RefinedType(parents, _) => parents.flatMap(flattenIntersection)
    case t                       => List(t)
  }

  private val badTypes = Set(c.weakTypeOf[AnyRef], c.weakTypeOf[Any], c.weakTypeOf[Object])
  def isIntersection(tpe: Type): Boolean =
    tpe.widen.dealias match {
      case tpe: RefinedType if flattenIntersection(tpe).filterNot(badTypes).distinct.length > 1 => true
      case _                                                                                    => false
    }
}
