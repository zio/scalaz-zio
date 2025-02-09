package zio.internal.macros

import zio._
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

    // Scala 3 reflection API does not expose `asSeenFrom` yet ,so that
    // we need to find the actual types for type parameters by ourselves.
    //
    // For now, it only works for classes with simply parameterized
    // consructors like `class Service[A, B](a: A, b: B)`.
    //
    // Refer to: https://github.com/lampepfl/dotty/discussions/14957
    val paramTypes = locally {
      val builder = List.newBuilder[TypeRepr]

      def unroll(meth: TypeRepr): List[TypeRepr] =
        meth match {
          case MethodType(_, pTypes, res) =>
            builder ++= pTypes
            unroll(res)
          case _ =>
            builder.result()
        }

      val ctorType =
        tpe.memberType(constructor).appliedTo(tpe.typeArgs)

      unroll(ctorType)
    }

    val trace = '{ Trace.empty }.asTerm

    val (fromServices, fromDefaults) =
      params.zip(paramTypes).partitionMap { case (pTerm, pType) =>
        pType.asType match {
          case '[r] =>
            Expr.summon[ZLayer.Derive.Default.WithContext[_, _, r]] match {
              case Some(d) => Right((pTerm.name, pType, d))
              case None    => Left((pTerm.name, pType))
            }
        }
      }

    val hookRETypes =
      tpe.baseType(TypeRepr.of[ZLayer.Derive.Scoped].typeSymbol) match {
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

    val serviceTypes = fromServices.scanRight(rInit) { case ((_, pType), r0) =>
      AndType(pType, r0)
    }

    def runNoDefault(remains: List[((String, TypeRepr), TypeRepr)], args: Map[String, Term]): Term =
      remains match {
        case Nil =>
          val newInstance =
            New(TypeTree.of[A])
              .select(constructor)
              .appliedToTypes(tpe.typeArgs)
              .appliedToArgss(paramss.map(_.params.map(p => args(p.name))))
              .asExprOf[A]

          hookRETypes match {
            case None =>
              '{ Exit.succeed[A](${ newInstance }) }.asTerm

            case Some((rType, eType)) =>
              (rType.asType, eType.asType) match {
                case ('[r], '[e]) =>
                  '{
                    val instance = ${ newInstance }
                    instance
                      .asInstanceOf[ZLayer.Derive.Scoped[r, e]]
                      .scoped(using Trace.empty)
                      .as(instance)(using Trace.empty)
                  }.asTerm
              }
          }

        case ((pName, pType), rType) :: ps =>
          (rType.asType, eInit.asType, pType.asType) match {
            case ('[r], '[e], '[a]) =>
              val tag        = summonTag[a]
              val nextEffect = '{ ZIO.service[a](using $tag, Trace.empty) }

              val lambda = Lambda(
                Symbol.spliceOwner,
                MethodType(List(pName))(
                  _ => pType :: Nil,
                  _ => TypeRepr.of[ZIO[r with Scope, e, A]]
                ),
                {
                  case (meth, (arg1: Term) :: Nil) =>
                    runNoDefault(ps, args.updated(pName, Ident(arg1.symbol.termRef)))
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
      fromDefaults.scanRight((serviceTypes.head, eInit)) { case ((pName, _, d), (r0, e0)) =>
        val dType = d.asTerm.tpe.dealias
        val r     = dType.select(dType.typeSymbol.typeMember("R")).dealias
        val e     = dType.select(dType.typeSymbol.typeMember("E")).dealias

        given Printer[TypeRepr] = Printer.TypeReprAnsiCode
        if (r.typeSymbol.isTypeDef || e.typeSymbol.isTypeDef) {
          report.errorAndAbort(
            s"""|Failed to derive a ZLayer for `${tpeSymbol.fullName}`.
                |
                |The type information `R`, `E` in `ZLayer.Derive.Default[A]` for the parameter
                |`${pName}` is missing. The resolved default instance is:
                |
                |  ${d.show}: ${d.asTerm.tpe.widen.show} 
                |
                |A frequent reason for this issue is using an incomplete type annotation like 
                |`given ZLayer.Derive.Default[A] = ???`. This can lead to the loss of specific
                |type details.
                |
                |To resolve, replace it with `ZLayer.Derive.Default.WithContext[R, E, A]`. If
                |you're using an IDE, remove the type annotations and add the inferred type
                |annotation using the IDE's assistant feature.
                |""".stripMargin,
            d.asTerm.pos
          )
        }

        (AndType(r0, r), OrType(e0, e))
      }

    def runDefault(
      remaining: List[((String, TypeRepr, Expr[ZLayer.Derive.Default[_]]), (TypeRepr, TypeRepr))],
      args: Map[String, Term]
    ): Term =
      remaining match {
        case Nil =>
          (serviceTypes.head.asType, eInit.asType) match {
            case ('[r], '[e]) =>
              val make =
                runNoDefault(fromServices.zip(serviceTypes), args)
                  .asExprOf[ZIO[r with Scope, e, A]]

              val tag = summonTag[A]
              '{ ZLayer.scoped($make)(using $tag, summon[Trace]) }.asTerm
          }

        case ((pName, pType, d), (rType, eType)) :: ps =>
          (rType.asType, eType.asType, pType.asType) match {
            case ('[r], '[e], '[a]) =>
              val nextEffect = '{ $d.layer }

              val lambda = Lambda(
                Symbol.spliceOwner,
                MethodType(List(pName))(
                  _ => TypeRepr.of[ZEnvironment[a]] :: Nil,
                  _ => TypeRepr.of[ZLayer[r, e, A]]
                ),
                {
                  case (meth, (arg1: Term) :: Nil) =>
                    runDefault(
                      ps,
                      args.updated(
                        pName,
                        Select
                          .unique(Ident(arg1.symbol.termRef), "get")
                          .appliedToType(pType)
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
        runDefault(fromDefaults.zip(reTypes), Map.empty)
          .asExprOf[ZLayer[r, e, A]]
    }
  }

  private def summonTag[A: Type](using Quotes): Expr[Tag[A]] = {
    import quotes.reflect._
    Expr.summon[Tag[A]].getOrElse(report.errorAndAbort(s"Cannot find Tag[${TypeRepr.of[A].show}] in implicit scope"))
  }
}
