package zio.internal.macros

import zio._
import zio.ZLayer.{Default, LifecycleHooks}
import scala.quoted._
import zio.internal.macros.StringUtils.StringOps
import zio.internal.ansi.AnsiStringOps

object ZLayerDerivationMacros {

  transparent inline def deriveLayer[A]: ZLayer[Nothing, Any, A] = ${ deriveLayerImpl[A] }

  def deriveLayerImpl[A: Type](using Quotes) = {
    import quotes.reflect._

    val tpe       = TypeRepr.of[A]
    val tpeSymbol = tpe.typeSymbol

    if (
      tpe.classSymbol.isEmpty ||
      tpeSymbol.flags.is(Flags.Abstract) ||
      tpeSymbol.flags.is(Flags.Trait)
    ) {
      report.errorAndAbort(s"Failed to derive a ZLayer: type `${tpeSymbol.fullName}` is not a concrete class.")
    }

    if (tpeSymbol.flags.is(Flags.JavaDefined)) {
      report.errorAndAbort(s"Failed to derive a ZLayer: Java type `${tpeSymbol.fullName}` is not supported.")
    }

    val constructor = tpeSymbol.primaryConstructor

    val paramss = constructor.tree.asInstanceOf[DefDef].termParamss
    val params  = paramss.flatMap(_.params.asInstanceOf[List[ValDef]])

    val trace = '{ summon[Trace] }.asTerm

    val (fromServices, fromDefaults) = params.partitionMap { p =>
      p.tpt.tpe.asType match {
        case '[r] =>
          Expr.summon[Default.Resolved[_, _, r]] match {
            case Some(d) => Right((p, d))
            case None    => Left(p)
          }
      }
    }

    val hookRETypes =
      tpe.baseType(TypeRepr.of[LifecycleHooks].typeSymbol) match {
        case sym if sym.typeSymbol.isNoSymbol => None
        case sym =>
          sym.typeArgs match {
            case r :: e :: Nil => Some((r, e))
            case other =>
              report.errorAndAbort(
                s"Failed to derive a ZLayer for `${tpeSymbol.fullName}`: Defect in `ZLayer.derive` found."
              )
          }
      }

    val (rInit, eInit) = hookRETypes.getOrElse((TypeRepr.of[Any], TypeRepr.of[Nothing]))

    val serviceTypes = fromServices.scanRight(rInit)((p, r0) => AndType(p.tpt.tpe, r0))

    def runNoDefault(remains: List[(ValDef, TypeRepr)], args: Map[String, Term]): Term =
      remains match {
        case Nil =>
          val newInstance =
            New(TypeTree.of[A])
              .select(constructor)
              .appliedToArgss(paramss.map(_.params.map(p => args(p.name))))
              .asExprOf[A]

          hookRETypes match {
            case None =>
              '{ ZIO.succeed[A](${ newInstance }) }.asTerm

            case Some((rType, eType)) =>
              (rType.asType, eType.asType) match {
                case ('[r], '[e]) =>
                  '{
                    ZIO
                      .suspendSucceed[r, e, A] {
                        val instance = ${ newInstance }
                        instance.asInstanceOf[LifecycleHooks[r, e]].initialize.as(instance)
                      }
                      .withFinalizer(_.asInstanceOf[LifecycleHooks[r, e]].cleanup)
                  }.asTerm
              }
          }

        case (param, rType) :: ps =>
          val paramType = param.tpt.tpe.dealias
          (rType.asType, eInit.asType, paramType.asType) match {
            case ('[r], '[e], '[p]) =>
              val nextEffect = '{ ZIO.service[p] }

              val lambda = Lambda(
                Symbol.spliceOwner,
                MethodType(List(param.name))(
                  _ => param.tpt.tpe :: Nil,
                  _ => TypeRepr.of[ZIO[r with Scope, e, A]]
                ),
                {
                  case (meth, (arg1: Term) :: Nil) =>
                    runNoDefault(ps, args.updated(param.name, Ident(arg1.symbol.termRef)))
                      .changeOwner(meth)

                  case _ =>
                    report.errorAndAbort(
                      s"Failed to derive a ZLayer for `${tpeSymbol.fullName}`: Defect in `ZLayer.derive` found."
                    )
                }
              )

              Select
                .unique(nextEffect.asTerm, "flatMap")
                .appliedToTypes(AndType(rType, TypeRepr.of[Scope]) :: eInit :: tpe :: Nil)
                .appliedTo(lambda)
                .appliedTo(trace)
          }

      }

    val reTypes =
      fromDefaults.scanRight((serviceTypes.head, eInit)) { case ((p, d), (r0, e0)) =>
        val dType = d.asTerm.tpe.dealias
        val r     = dType.select(dType.typeSymbol.typeMember("R")).dealias
        val e     = dType.select(dType.typeSymbol.typeMember("E")).dealias

        given Printer[TypeRepr] = Printer.TypeReprAnsiCode
        if (r.typeSymbol.isTypeDef || e.typeSymbol.isTypeDef) {
          report.errorAndAbort(
            s"""|Failed to derive a ZLayer for `${tpeSymbol.fullName}`.
                |
                |The type information `R`, `E` in `ZLayer.Default[A]` for the parameter 
                |`${p.name}` is missing. The resolved default instance is:
                |
                |  ${d.show}: ${d.asTerm.tpe.widen.show} 
                |
                |A frequent reason for this issue is using an explicit type annotation like 
                |`given ZLayer.Default[A] = ???`.  This can lead to the loss of specific type
                |details.
                |
                |To resolve, replace it with `ZLayer.Default.Resolved[R, E, A]`. If you're using
                |an IDE, remove the type annotations and add the inferred type annotation using
                |the IDE's assistant feature.
                |""".stripMargin,
            d.asTerm.pos
          )
        }

        (AndType(r0, r), OrType(e0, e))
      }

    def runDefault(remaining: List[((ValDef, Expr[Default[_]]), (TypeRepr, TypeRepr))], args: Map[String, Term]): Term =
      remaining match {
        case Nil =>
          (serviceTypes.head.asType, eInit.asType) match {
            case ('[r], '[e]) =>
              val make = runNoDefault(fromServices.zip(serviceTypes), args).asExprOf[ZIO[r with Scope, e, A]]
              '{ ZLayer.scoped($make) }.asTerm
          }

        case ((param, d), (rType, eType)) :: ps =>
          val paramType = param.tpt.tpe
          (rType.asType, eType.asType, paramType.asType) match {
            case ('[r], '[e], '[a]) =>
              val nextEffect = '{ $d.layer }

              val lambda = Lambda(
                Symbol.spliceOwner,
                MethodType(List(param.name))(
                  _ => TypeRepr.of[ZEnvironment[a]] :: Nil,
                  _ => TypeRepr.of[ZLayer[r, e, A]]
                ),
                {
                  case (meth, (arg1: Term) :: Nil) =>
                    runDefault(
                      ps,
                      args.updated(
                        param.name,
                        Select
                          .unique(Ident(arg1.symbol.termRef), "get")
                          .appliedToType(paramType)
                      )
                    ).changeOwner(meth)

                  case _ =>
                    report.errorAndAbort(
                      s"Failed to derive a ZLayer for `${tpeSymbol.fullName}`: Defect in `ZLayer.derive` found."
                    )
                }
              )

              Select
                .unique(nextEffect.asTerm, "flatMap")
                .appliedToTypes(rType :: eType :: tpe :: Nil)
                .appliedTo(lambda)
                .appliedTo(trace)
          }
      }

    val (rType, eType) = reTypes.head

    (rType.simplified.asType, eType.simplified.asType) match {
      case ('[r], '[e]) =>
        runDefault(fromDefaults.zip(reTypes), Map.empty).asExprOf[ZLayer[r, e, A]]
    }
  }
}
