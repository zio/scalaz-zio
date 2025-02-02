/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

package zio

import scala.annotation.experimental
import scala.quoted.*

private[zio] transparent trait IsReloadableVersionSpecific {

  /**
   * Generates a proxy instance of the specified service.
   *
   * @tparam A
   *   The type of the service.
   * @param service
   *   The [[zio.ScopedRef]] containing the service for which a proxy is to be
   *   generated.
   * @return
   *   A proxy instance of the service that forwards ZIO method calls to the
   *   underlying service and allows the service to change its behavior at
   *   runtime.
   */
  @experimental
  inline given derived[A]: IsReloadable[A] = ${ IsReloadableMacros.derive[A] }
}

private object IsReloadableMacros {

  @experimental
  def derive[A: Type](using Quotes): Expr[IsReloadable[A]] =
    '{
      new IsReloadable[A] {
        override def reloadable(scopedRef: ScopedRef[A]): A =
          ${ makeImpl('scopedRef) }
      }
    }

  @experimental
  def makeImpl[A: Type](service: Expr[ScopedRef[A]])(using Quotes): Expr[A] = {
    import quotes.reflect.*

    // TODO replace with `ValOrDefDef` https://github.com/lampepfl/dotty/pull/16974/
    def nameAndReturnType(t: Tree): Option[(String, TypeTree)] =
      t match {
        case DefDef(name, _, tpt, _) => Some((name, tpt))
        case ValDef(name, tpt, _)    => Some((name, tpt))
        case _                       => None
      }

    val tpe = TypeRepr.of[A]

    def unsupported(reason: String): Nothing =
      report.errorAndAbort(
        s"""Unable to generate a ZIO service proxy for `${tpe.typeSymbol.fullName}` due to the following reason:
           |
           |  $reason
           |
           |To generate a ZIO service proxy, please ensure the following:
           |  1. The type is either a trait or a class with an empty primary constructor.
           |  2. The type includes only ZIO methods or vals.
           |  3. The type does not have any abstract type members.
           |""".stripMargin
      )

    def defect(reason: String): Nothing =
      report.errorAndAbort(
        s"""Defect in zio.IsReloadable:
           |
           |  $reason""".stripMargin
      )

    tpe.typeSymbol.primaryConstructor.tree match {
      case d: DefDef =>
        if (d.termParamss.exists(_.params.nonEmpty)) {
          unsupported("Primary constructor with non-empty parameters detected")
        }

      case other =>
        defect(s"Unexpected primary constructor tree: $other")
    }

    tpe.typeSymbol.typeMembers.foreach { m =>
      if (m.flags.is(Flags.Deferred) && !m.flags.is(Flags.Param)) {
        unsupported(s"Abstract type member detected: ${m.tree.show}")
      }
    }

    def forwarders(cls: Symbol) =
      (tpe.typeSymbol.methodMembers.view ++ tpe.typeSymbol.fieldMembers.view).flatMap { m =>
        nameAndReturnType(m.tree).flatMap { (name, tpt) =>
          val returnsZIO = tpt.tpe <:< TypeRepr.of[ZIO[_, _, _]]

          if (returnsZIO) {
            val privateWithin = m.privateWithin.map(_.typeSymbol).getOrElse(Symbol.noSymbol)
            if (m.isDefDef)
              Some(Symbol.newMethod(cls, name, tpe.memberType(m), Flags.Override, privateWithin))
            else if (m.isValDef)
              Some(Symbol.newVal(cls, name, tpe.memberType(m), Flags.Override, privateWithin))
            else
              defect(s"Unexpected member tree: ${m.tree}")
          } else if (m.flags.is(Flags.Deferred)) {
            val memberType = if (m.isDefDef) "method" else "field"
            unsupported(
              s"non-ZIO $memberType detected: $name"
            )
          } else None
        }
      }.toList

    val parents =
      if (tpe.typeSymbol.flags.is(Flags.Trait))
        TypeTree.of[Object] :: TypeTree.of[A] :: Nil
      else
        TypeTree.of[A] :: Nil

    val cls = Symbol.newClass(
      parent = Symbol.spliceOwner,
      name = s"_ZIOProxy_${tpe.typeSymbol.name}",
      parents = parents.map(_.tpe),
      decls = forwarders,
      selfType = None
    )

    // TODO replace with `ValOrDefDef` https://github.com/lampepfl/dotty/pull/16974/
    def typeAndParams(m: Symbol): Option[(TypeTree, List[TypeDef], List[TermParamClause])] =
      m.tree match {
        case d: DefDef if !m.isClassConstructor =>
          Some((d.returnTpt, d.leadingTypeParams, d.termParamss))
        case v: ValDef =>
          Some((v.tpt, Nil, Nil))
        case _ =>
          None
      }

    val trace = '{ summon[Trace] }.asTerm
    val body = cls.declarations.flatMap { member =>
      typeAndParams(member).flatMap { (tpt, typeParams, termParamss) =>
        val body =
          service.asTerm
            .select(TypeRepr.of[ScopedRef[_]].typeSymbol.methodMember("get").head)
            .appliedTo(trace)
            .select(TypeRepr.of[ZIO[_, _, _]].typeSymbol.methodMember("flatMap").head)
            .appliedToTypes(tpt.tpe.dealias.typeArgs)
            .appliedTo(
              Lambda(
                owner = member,
                tpe = MethodType("_$1" :: Nil)(_ => tpe :: Nil, _ => tpt.tpe),
                rhsFn = {
                  case (_, _$1 :: Nil) =>
                    _$1
                      .asInstanceOf[Term]
                      .select(member)
                      .appliedToTypes(typeParams.map(_.symbol.typeRef))
                      .appliedToArgss(termParamss.map(_.params.map(p => Ident(p.symbol.termRef))))

                  case (_, ps) =>
                    defect(s"Unexpected lambda params in the proxy method body $ps")
                }
              )
            )
            .appliedTo(trace)

        if (member.isDefDef)
          Some(DefDef(member, _ => Some(body)))
        else if (member.isValDef)
          Some(ValDef(member, Some(body)))
        else
          defect(s"Unexpected member declaration: ${member.tree}")
      }
    }

    val clsDef = ClassDef(cls, parents, body = body)
    val newCls =
      Typed(
        New(TypeIdent(cls))
          .select(cls.primaryConstructor)
          .appliedToNone,
        TypeTree.of[A]
      )

    Block(List(clsDef), newCls).asExprOf[A]
  }
}
