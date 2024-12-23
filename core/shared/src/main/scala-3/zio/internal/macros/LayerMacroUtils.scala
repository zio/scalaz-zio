package zio.internal.macros

import zio._
import scala.quoted._
import scala.compiletime._
import zio.internal.ansi.AnsiStringOps
import zio.internal.macros.StringUtils.StringOps
import zio.internal.stacktracer.Tracer

private[zio] object LayerMacroUtils {
  type LayerExpr[E] = Expr[ZLayer[_, E, _]]

  def composeLayer[R1, E, O1, O2](
    lhs: ZLayer[R1, E, O1],
    rhs: ZLayer[O1, E, O2]
  )(using Trace): ZLayer[R1, E, O2] =
    lhs >>> rhs

  def constructLayer[R0: Type, R: Type, E: Type](using Quotes)(
    layers: Seq[LayerExpr[E]],
    provideMethod: ProvideMethod
  ): Expr[ZLayer[R0, E, R]] = {
    import quotes.reflect._

    def renderExpr[A](expr: Expr[A]): String =
      scala.util.Try(expr.asTerm.pos.sourceCode).toOption.flatten.getOrElse(expr.show)

    def getNode(layer: LayerExpr[E]): Node[TypeRepr, LayerExpr[E]] = layer match {
      case '{ $layer: ZLayer[in, e, out] } =>
        val inputs  = getRequirements[in]
        val outputs = getRequirements[out]
        Node(inputs, outputs, layer)
    }

    def getRequirements[T: Type]: List[TypeRepr] = {
      def loop(tpe: TypeRepr): List[TypeRepr] =
        tpe.dealias.simplified match {
          case AndType(lhs, rhs)                          => loop(lhs) ++ loop(rhs)
          case AppliedType(_, TypeBounds(_, _) :: _)      => Nil
          case other if other =:= TypeRepr.of[Any]        => Nil
          case other if other.dealias.simplified != other => loop(other)
          case other                                      => List(other.dealias)
        }

      loop(TypeRepr.of[T])
    }

    val layerToDebug: PartialFunction[LayerExpr[E], ZLayer.Debug] = {
      case '{ ZLayer.Debug.tree }    => ZLayer.Debug.Tree
      case '{ ZLayer.Debug.mermaid } => ZLayer.Debug.Mermaid
    }

    '{
      val trace = summonInline[Trace]

      ${
        def typeToNode(tpe: TypeRepr): Node[TypeRepr, LayerExpr[E]] =
          Node(Nil, List(tpe), tpe.asType match { case '[t] => '{ ZLayer.environment[t](trace) } })

        def composeH(lhs: LayerExpr[E], rhs: LayerExpr[E]): LayerExpr[E] =
          lhs match {
            case '{ $lhs: ZLayer[i, E, o] } =>
              rhs match {
                case '{ $rhs: ZLayer[i2, E, o2] } =>
                  rhs.asTerm match {
                    case _: Ident => '{ $lhs.++($rhs)(summonInline) }
                    case _        => '{ $lhs +!+ $rhs }
                  }
              }
          }

        def composeV(lhs: LayerExpr[E], rhs: LayerExpr[E]): LayerExpr[E] =
          lhs match {
            case '{ $lhs: ZLayer[i, E, o] } =>
              rhs match {
                case '{ $rhs: ZLayer[`o`, E, o2] } =>
                  '{ composeLayer($lhs, $rhs)(using trace) }
              }
          }

        def buildFinalTree(tree: LayerTree[LayerExpr[E]]): LayerExpr[E] = {
          val layerExprs = tree.toList
          ValDef
            .let(Symbol.spliceOwner, layerExprs.map(_.asTerm)) { idents =>
              val exprMap = layerExprs.zip(idents.map(_.asExprOf[ZLayer[_, E, _]])).toMap
              tree.fold('{ ZLayer.unit }, exprMap, composeH, composeV).asTerm
            }
            .asExprOf[ZLayer[_, E, _]]
        }

        val builder = LayerBuilder[TypeRepr, LayerExpr[E]](
          target0 = getRequirements[R],
          remainder = getRequirements[R0],
          providedLayers0 = layers.toList,
          layerToDebug = layerToDebug,
          typeEquals = _ <:< _,
          sideEffectType = TypeRepr.of[Unit],
          anyType = TypeRepr.of[Any],
          foldTree = buildFinalTree,
          method = provideMethod,
          exprToNode = getNode,
          typeToNode = typeToNode,
          showExpr = renderExpr,
          showType = _.show,
          reportWarn = report.warning,
          reportError = report.errorAndAbort
        )

        builder.build.asTerm.asExprOf[ZLayer[R0, E, R]]
      }
    }
  }
}
