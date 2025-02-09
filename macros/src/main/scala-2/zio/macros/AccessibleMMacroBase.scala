/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

package zio.macros

import scala.annotation.nowarn
import scala.reflect.macros.whitebox

private[macros] abstract class AccessibleMMacroBase(override val c: whitebox.Context) extends AccessibleMacroBase(c) {

  import c.universe._

  protected def expectedTypeParams: Long

  protected def aliases: Seq[Tree]

  @nowarn("msg=pattern var [^\\s]+ in method unapply is never used")
  protected val tp: Tree = c.prefix.tree match {
    case q"new ${macroNm: Ident}[$typeParam]" if macroNm.name == TypeName(macroName) =>
      typeParam
    case _ =>
      abort("could not unquote annotation. Make sure you have specified the type parameter.")
  }

  protected val tpTpe: Type = {
    val res = c.typecheck(tq"$tp", c.TYPEmode).tpe
    if (types.keySet.contains(res))
      res
    else
      abort(s"unsupported type constructor $res. Supported constructors: ${types.keySet}")
  }

  private lazy val types: Map[Type, Tree] =
    aliases.map(a => c.typecheck(tq"$a", c.TYPEmode).tpe -> a).toMap

  @nowarn("msg=pattern var [^\\s]+ in method unapply is never used")
  protected def macroApply(annottees: Seq[c.Tree]): MacroApply = new MacroApply(annottees) {

    private val typeParamToInject = {
      val candidates =
        moduleInfo.serviceTypeParams.filter(tp => tp.tparams.size == expectedTypeParams)

      candidates match {
        case Seq(c)   => c
        case Seq()    => abort(s"`Service` doesn't have type param for [$tpTpe]")
        case nonEmpty => abort(s"`Service` contains several possible candidates for [$tpTpe]: $nonEmpty")
      }
    }

    protected def treeTpe(tree: Tree): Type =
      tree match {
        case tq"${typeName: Ident}[..${typeParams}]" =>
          val typeArgs     = typeParams.map(p => c.typecheck(tq"$p", c.TYPEmode, silent = true).tpe)
          val shouldInject = typeName.name == typeParamToInject.name

          val injectedTypeName =
            if (shouldInject) types(tpTpe)
            else typeName

          c.typecheck(tq"$injectedTypeName[..$typeArgs]", c.TYPEmode).tpe
        case _ => abort(s"could not unquote return type tree $tree")
      }

    override protected def typeArgsForService(serviceTypeParams: List[TypeDef]): List[TypeName] =
      serviceTypeParams.map {
        case `typeParamToInject` => TypeName(tp.toString)
        case other               => other.name
      }

    override protected def typeParamsForAccessors(serviceTypeParams: List[TypeDef]): List[TypeDef] =
      serviceTypeParams.filterNot(_ == typeParamToInject)
  }

}
